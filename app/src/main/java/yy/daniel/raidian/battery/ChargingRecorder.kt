package yy.daniel.raidian.battery

import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 充电曲线记录器。
 *
 * 需求(用户原话):「充电曲线记录需要记录充电电量曲线,可以的话再额外记录充电功耗曲线」。
 *
 * 数据源:[BatteryStateReader] 的 30 秒一次快照(电量 / 电流 / 电压 / 功耗 / 温度 / 充放电状态),
 * 会话边界**实时判定**(插上充电器即开始、拔下或充满即结束),而不是像参考实现 Cumulus 那样
 * 事后再从 2 秒采样序列里回溯切分 —— 那种做法会把涓流段丢掉、也无法记录"当前正在充电"的实况
 * (见 references/Cumulus-APP-main .../utils/BatteryStatsRecordAnalysis.kt:70-103 的
 * `getLastChargingRecords`,它要求充入 ≥10% 才认一段会话)。
 *
 * 一句话:插着电就每 30 秒记一点,点里既有电量(充电电量曲线)也有功率(充电功耗曲线)。
 */
class ChargingRecorder(private val store: BatteryCurveStore) {

    companion object {
        private const val TAG = "ChargingRecorder"
        /** 单点间隔(ms):30 秒一点,一小时充电约 120 点 */
        private const val DEFAULT_INTERVAL_MS = 30_000L
        /** 梯形积分最多跨越多久(超过则视为采样断档,不计能量) */
        private const val MAX_INTEGRATE_GAP_MS = 120_000L
        /** 内存里保留的会话点数上限(30s 一点 → 8 小时) */
        private const val MAX_POINTS = 960
        /** 短于该时长的会话不落盘(插一下就拔的抖动) */
        private const val MIN_SESSION_MS = 60_000L
    }

    /** 界面观察的实况 */
    data class Live(
        val recording: Boolean = false,
        val charging: Boolean = false,
        val pluggedIn: Boolean = false,
        val chargeStateText: String = "--",
        val level: Int = -1,
        val powerMw: Long = -1L,
        val currentMa: Int = -1,
        val voltageMv: Int = -1,
        val tempC: Float = -1f,
        /** 本次(或最近一次)会话开始时刻 */
        val sessionStartTs: Long = 0L,
        val sessionDir: String = "",
        val startLevel: Int = -1,
        /** 当前会话已持续 ms(结束时为总时长) */
        val durationMs: Long = 0L,
        /** 当前/最近会话的曲线点 */
        val points: List<BatteryCurveStore.ChargePoint> = emptyList(),
        val peakPowerMw: Long = -1L,
        /** 按时长加权的平均功率 mW */
        val avgPowerMw: Double = -1.0,
        /** 充入能量 mWh */
        val energyMwh: Double = -1.0,
        /** 按当前速率预估还需多久充满 ms(-1 无法估算) */
        val estFullRemainMs: Long = -1L,
    )

    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()

    @Volatile private var sessionDir: File? = null
    @Volatile private var sessionStartTs = 0L
    @Volatile private var sessionStartLevel = -1
    @Volatile private var lastWriteTs = 0L
    @Volatile private var lastPoint: BatteryCurveStore.ChargePoint? = null
    @Volatile private var lastState: BatteryStateReader.State? = null

    private var peakPower = -1L
    private var energySum = 0.0        // mWh
    private var powerTimeSum = 0.0     // mW·h
    private var timeSum = 0.0          // h
    private var maxTemp = -1f
    private val points = ArrayList<BatteryCurveStore.ChargePoint>(128)

    private var capacityMah = -1

    /** 系统容量(用于推算充入 mAh),由控制器在读到系统统计后注入 */
    fun setCapacity(mah: Int) {
        if (mah > 0) capacityMah = mah
    }

    // ------------------------------------------------------------------

    /** 进程重启后:若磁盘上最后一次充电会话还没结束(endTs=0),继续用它 */
    fun resumeIfUnfinished() {
        if (sessionDir != null) return
        val latest = store.listCharges().firstOrNull() ?: return
        val meta = store.readChargeMeta(latest) ?: return
        if (meta.endTs != 0L) return
        sessionDir = latest
        sessionStartTs = meta.startTs
        sessionStartLevel = meta.startLevel
        points.clear()
        val loaded = store.readCharge(latest)
        points.addAll(if (loaded.size > MAX_POINTS) loaded.takeLast(MAX_POINTS) else loaded)
        lastPoint = points.lastOrNull()
        loaded.forEach { p ->
            if (p.powerMw > 0) energySum += p.powerMw * (30_000.0 / 3_600_000.0)
        }
        lastWriteTs = points.lastOrNull()?.ts ?: 0L
        Log.i(TAG, "resume charging session ${latest.name} points=${points.size}")
    }

    private fun startSession(st: BatteryStateReader.State) {
        val dir = store.createCharge(st.ts)
        sessionDir = dir
        sessionStartTs = st.ts
        sessionStartLevel = st.level
        lastWriteTs = 0L
        lastPoint = null
        peakPower = -1L
        energySum = 0.0; powerTimeSum = 0.0; timeSum = 0.0
        maxTemp = -1f
        points.clear()
        Log.i(TAG, "charging session start ${dir.name} level=${st.level}")
    }

    /**
     * 每 30 秒由控制器调用一次。
     * @param writeIntervalMs 落盘间隔(默认 30s)
     */
    fun onSample(st: BatteryStateReader.State, writeIntervalMs: Long = DEFAULT_INTERVAL_MS) {
        lastState = st

        if (sessionDir == null) {
            // 开始条件:系统状态机报告"正在充电"(仅插着但未充不算,避免充满后台涓流造出一堆空会话)
            if (st.charging) {
                startSession(st)
            } else {
                publish()
                return
            }
        }

        // ---- 累计(与上一点做梯形积分,断档过大不计) ----
        val lp = lastPoint
        if (lp != null && st.powerMw > 0) {
            val dt = st.ts - lp.ts
            if (dt in 1..MAX_INTEGRATE_GAP_MS) {
                val dtH = dt / 3_600_000.0
                val avg = (lp.powerMw + st.powerMw) / 2.0
                energySum += avg * dtH
                powerTimeSum += avg * dtH
                timeSum += dtH
            }
        }
        if (st.powerMw > peakPower) peakPower = st.powerMw
        if (st.tempC > maxTemp) maxTemp = st.tempC

        // ---- 落点 ----
        if (lastWriteTs == 0L || st.ts - lastWriteTs >= writeIntervalMs) {
            lastWriteTs = st.ts
            val dir = sessionDir
            val p = BatteryCurveStore.ChargePoint(
                ts = st.ts,
                elapsedMs = st.ts - sessionStartTs,
                level = st.level,
                currentMa = st.currentMa,
                voltageMv = st.voltageMv,
                powerMw = st.powerMw,
                tempC = st.tempC,
                status = st.status,
                plugged = st.plugged,
            )
            if (dir != null && store.appendCharge(dir, p)) {
                points.add(p)
                if (points.size > MAX_POINTS) points.removeAt(0)
            }
            lastPoint = p
        }

        // ---- 结束条件:拔电 / 充满 ----
        val unplugged = !st.pluggedIn
        val full = st.full || st.level >= 100 && !st.charging
        if (unplugged || full) {
            finishSession(st, full)
            // 结束时若已拔电,不再新建会话;若仅"充满但还插着",保持空闲等下一次 CHARGING
        }

        publish()
    }

    private fun finishSession(st: BatteryStateReader.State, reachedFull: Boolean) {
        val dir = sessionDir ?: return
        val duration = st.ts - sessionStartTs
        val meta = BatteryCurveStore.ChargeMeta(
            startTs = sessionStartTs,
            endTs = st.ts,
            durationMs = duration,
            startLevel = sessionStartLevel,
            endLevel = st.level,
            sampleCount = points.size,
            peakPowerMw = peakPower,
            avgPowerMw = if (timeSum > 0) powerTimeSum / timeSum else -1.0,
            energyMwh = if (energySum > 0) energySum else -1.0,
            maxTempC = maxTemp,
            capacityMah = capacityMah,
            reachedFull = reachedFull,
        )
        if (duration < MIN_SESSION_MS && points.size < 2) {
            // 抖动会话:删掉,不污染历史
            store.deleteDirs(listOf(dir))
            Log.i(TAG, "drop too-short charging session ${dir.name} (${duration}ms)")
        } else {
            store.writeChargeMeta(dir, meta)
            Log.i(TAG, "charging session end ${dir.name} dur=${duration}ms " +
                "level=${sessionStartLevel}->${st.level} peak=${peakPower}mW avg=${meta.avgPowerMw}mW")
        }
        // 保留最后一组点给界面展示,但标记为不在记录中
        sessionDir = null
    }

    /** 关闭记录(拔电/停用):若正在记录则封存 */
    fun close() {
        val dir = sessionDir ?: return
        val st = lastState
        if (st != null) {
            finishSession(st, st.full)
        } else {
            store.deleteDirs(listOf(dir))
            sessionDir = null
        }
    }

    private fun publish() {
        val st = lastState
        val lvl = st?.level ?: -1
        // 预估剩余:按本会话已有速率外推(至少充进 1% 才估)
        var remain = -1L
        if (sessionDir != null && lvl in 1..99 && sessionStartLevel >= 0) {
            val gained = lvl - sessionStartLevel
            val elapsed = (st?.ts ?: 0L) - sessionStartTs
            if (gained >= 1 && elapsed > 0) {
                remain = elapsed / gained * (100 - lvl)
            }
        }
        _live.value = Live(
            recording = sessionDir != null,
            charging = st?.charging ?: false,
            pluggedIn = st?.pluggedIn ?: false,
            chargeStateText = st?.chargeStateText ?: "--",
            level = lvl,
            powerMw = st?.powerMw ?: -1L,
            currentMa = st?.currentMa ?: -1,
            voltageMv = st?.voltageMv ?: -1,
            tempC = st?.tempC ?: -1f,
            sessionStartTs = sessionStartTs,
            sessionDir = sessionDir?.absolutePath ?: "",
            startLevel = sessionStartLevel,
            durationMs = if (sessionStartTs > 0 && st != null) st.ts - sessionStartTs else 0L,
            points = ArrayList(points),
            peakPowerMw = peakPower,
            avgPowerMw = if (timeSum > 0) powerTimeSum / timeSum else -1.0,
            energyMwh = if (energySum > 0) energySum else -1.0,
            estFullRemainMs = remain,
        )
    }

    /** 当前是否正在记录一次充电会话 */
    fun isRecording(): Boolean = sessionDir != null

    /** 上次充满/结束后的充电状态文案里用到的插头类型 */
    fun pluggedText(): String = when (lastState?.plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "交流"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线"
        else -> "未插电"
    }
}
