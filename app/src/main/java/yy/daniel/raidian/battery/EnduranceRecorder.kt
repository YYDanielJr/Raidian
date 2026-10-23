package yy.daniel.raidian.battery

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale

/**
 * 续航曲线记录器。
 *
 * 需求(用户原话):「记录系统从上一次充电到 100% 之后的总续航时长和亮屏时长,
 * 如果可以的话需要记录该段时间内每个应用的耗电量和平均功耗,如果可以的话还需要
 * 记录每个应用的功耗曲线」。
 *
 * 因此本记录器有两条腿:
 *
 *  1. **系统口径(权威)**:`dumpsys batterystats` 的 `Time on battery` / `Screen on` /
 *     `Estimated power use (mAh)` 逐 UID 段。系统在"充满电/拔电"时重置这份统计,
 *     所以它天然就是"上次充到 100% 之后"的累计值 —— 总续航时长、亮屏时长、每应用
 *     耗电量都直接取自这里,不做二次推算(参考实现 Cumulus 是自采样推算,精度更低,
 *     详见 BatteryStatsParser 的类注释)。
 *
 *  2. **本地采样(曲线)**:每 30s 读一次电池状态、每 60s 落一个曲线点,给出
 *     电量/功耗随时间的变化曲线;每 5 分钟把逐 UID 累计 mAh 快照写入 apps.csv,
 *     相邻快照求差即得"每个应用的功耗曲线"(见 AppPowerSeries)。
 *
 * 周期边界:检测到「充到 100%」后,下一次电量跌破 100% 时开启新周期(旧周期封存)。
 * 首次安装(从未见过 100%)时也会先建一个兜底周期,保证立刻有数据可看。
 */
class EnduranceRecorder(
    private val context: Context,
    private val store: BatteryCurveStore,
    private val resolver: UidInfoResolver,
) {
    companion object {
        private const val TAG = "EnduranceRecorder"
        /** 电量达到该值且插着电,视为"已充满" → 下一次掉电即开新周期 */
        private const val FULL_LEVEL = 100
        /** 每个应用耗电快照最多写多少条(按 mAh 降序,避免 apps.csv 爆炸) */
        private const val APP_SNAPSHOT_MAX = 40
        /** 快照里忽略小于该值的条目(噪声) */
        private const val APP_MAH_MIN = 0.05
        /** 内存里保留的曲线点数上限(60s 一点 → 3 天) */
        private const val CURVE_MAX = 4320
        /** 低于该样本数的周期视为噪声,滚动时不封存 */
        private const val MIN_CYCLE_SAMPLES = 3
    }

    /** 每应用耗电一行(界面直接用) */
    data class AppPower(
        val uid: Int,
        val pkg: String,
        val label: String,
        /** 本周期累计耗电 mAh */
        val mah: Double,
        /** 占全部已统计 UID 耗电的比例 0-100 */
        val percent: Double,
        /** 平均功耗 mW(= mAh × 平均电压 / 本周期小时数) */
        val avgPowerMw: Double,
    )

    /** 界面观察的实况数据 */
    data class Live(
        val recording: Boolean = false,
        /** 本周期开始时刻(= 检测到充满的时刻) */
        val cycleStartTs: Long = 0L,
        val cycleDir: String = "",
        /** 周期起点电量 */
        val startLevel: Int = -1,
        val level: Int = -1,
        val screenOn: Boolean = false,
        val charging: Boolean = false,
        val pluggedIn: Boolean = false,
        val chargeStateText: String = "--",
        val powerMw: Long = -1L,
        val currentMa: Int = -1,
        val voltageMv: Int = -1,
        val tempC: Float = -1f,
        /** 系统口径:上次充满以来 on-battery 时长 ms */
        val timeOnBatteryMs: Long = -1L,
        /** 系统口径:亮屏时长 ms */
        val screenOnMs: Long = -1L,
        /** 系统口径:本周期估算总耗电 mAh */
        val computedDrainMah: Double = -1.0,
        /** 系统识别容量 mAh */
        val capacityMah: Int = -1,
        /** 本地平均功耗 mW(本周期放电样本均值) */
        val avgPowerMw: Double = -1.0,
        /** 本地累计放电时长 ms */
        val localDischargeMs: Long = 0L,
        /** 预估当前电量还能用多久 ms(按系统口径线性外推;-1 无法估算) */
        val estRemainMs: Long = -1L,
        /** 本周期本地曲线点数 */
        val sampleCount: Int = 0,
        /** 逐应用耗电(系统口径,降序) */
        val apps: List<AppPower> = emptyList(),
        /** 本周期曲线(内存缓存) */
        val curve: List<BatteryCurveStore.DrainPoint> = emptyList(),
        /** 系统统计最后更新时刻 */
        val sysStatsTs: Long = 0L,
    )

    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()

    // ---- 当前周期状态(单协程内更新,volatile 供读取) ----
    @Volatile private var cycleDir: File? = null
    @Volatile private var cycleMeta: BatteryCurveStore.CycleMeta? = null

    /** 最近一次"已充满"的时刻(>0 表示等待掉电以开启新周期) */
    @Volatile private var fullSeenTs: Long = 0L

    /** 最近一次系统性统计 */
    @Volatile private var lastSys: BatteryStatsParser.SystemBatteryStats? = null

    /** 最近一次电池快照 */
    @Volatile private var lastState: BatteryStateReader.State? = null

    /** 上次落放电点 / 上次统计口累计用 */
    private var lastDrainTs = 0L
    private var lastSampleTs = 0L
    private var lastSysWriteTs = 0L

    /** 本周期累计(本地) */
    private var dischargeMs = 0L
    private var powerSum = 0.0
    private var powerCount = 0L
    private var voltageSum = 0.0
    private var voltageCount = 0L

    /** 曲线内存缓存(新→旧按时间升序) */
    private val curve = ArrayList<BatteryCurveStore.DrainPoint>(512)

    /** 逐应用耗电(最新系统快照解析结果) */
    @Volatile private var appList: List<AppPower> = emptyList()

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 开机/启用后调用:恢复未封存的（最新）周期,没有就新建一个兜底周期 */
    fun resumeOrCreate(now: Long, level: Int) {
        if (cycleDir != null) return
        val latest = store.listCycles().firstOrNull()
        val meta = latest?.let { store.readCycleMeta(it) }
        // 未结束(endTs == 0)的周期继续用;已结束的说明上个周期正常封存,新建
        if (latest != null && meta != null && meta.endTs == 0L) {
            attach(latest, meta)
            Log.i(TAG, "resume cycle ${latest.name} samples=${meta.sampleCount}")
        } else {
            startCycle(now, level, fromFullCharge = false)
        }
    }

    private fun attach(dir: File, meta: BatteryCurveStore.CycleMeta) {
        cycleDir = dir
        cycleMeta = meta
        curve.clear()
        val loaded = store.readDrain(dir)
        curve.addAll(if (loaded.size > CURVE_MAX) loaded.takeLast(CURVE_MAX) else loaded)
        dischargeMs = 0L
        powerSum = 0.0; powerCount = 0L
        voltageSum = 0.0; voltageCount = 0L
        loaded.forEach { p ->
            // 恢复累计:只统计非充电点(充电点的电流方向与放电相反)
            if (p.chargeState == android.os.BatteryManager.BATTERY_STATUS_DISCHARGING) {
                if (p.powerMw > 0) { powerSum += p.powerMw; powerCount++ }
                if (p.voltageMv > 0) { voltageSum += p.voltageMv; voltageCount++ }
            }
        }
        // 本地放电时长:近似用采样点数 × 间隔,精确值以系统口径为准
        dischargeMs = 0L
        publish()
    }

    /** 新建一个周期目录 */
    private fun startCycle(startTs: Long, startLevel: Int, fromFullCharge: Boolean) {
        val dir = store.createCycle(startTs)
        val meta = BatteryCurveStore.CycleMeta(
            startTs = startTs,
            startLevel = startLevel,
            fromFullCharge = fromFullCharge,
        )
        store.writeCycleMeta(dir, meta)
        cycleDir = dir
        cycleMeta = meta
        curve.clear()
        dischargeMs = 0L
        powerSum = 0.0; powerCount = 0L
        voltageSum = 0.0; voltageCount = 0L
        lastDrainTs = 0L
        lastSampleTs = 0L
        appList = emptyList()
        Log.i(TAG, "start cycle ${dir.name} fromFullCharge=$fromFullCharge startLevel=$startLevel")
        publish()
    }

    /** 封存当前周期(写回统计后置空) */
    private fun sealCycle(endTs: Long, endLevel: Int) {
        val dir = cycleDir ?: return
        val meta = cycleMeta ?: return
        if (meta.sampleCount >= MIN_CYCLE_SAMPLES) {
            store.writeCycleMeta(
                dir, meta.copy(endTs = endTs, endLevel = endLevel, sampleCount = curve.size)
            )
            Log.i(TAG, "seal cycle ${dir.name} samples=${curve.size} endLevel=$endLevel")
        } else {
            // 数据太少的周期(刚建就充满)直接删掉,避免历史列表里全是空记录
            store.deleteDirs(listOf(dir))
        }
        cycleDir = null
        cycleMeta = null
    }

    // ------------------------------------------------------------------
    // 每 30s 电池快照
    // ------------------------------------------------------------------

    fun onSample(st: BatteryStateReader.State, drainIntervalMs: Long) {
        val prev = lastState
        lastState = st
        if (cycleDir == null) resumeOrCreate(st.ts, st.level)

        // ---- 周期边界:充满 → 掉电即开新周期 ----
        if (st.level >= FULL_LEVEL && st.pluggedIn) {
            if (fullSeenTs == 0L) fullSeenTs = st.ts
        } else if (fullSeenTs > 0L && st.level in 1 until FULL_LEVEL && !st.charging) {
            // 已经掉电:旧周期封存在充满那一刻,新周期从那一刻开始
            val fullTs = fullSeenTs
            fullSeenTs = 0L
            sealCycle(fullTs, FULL_LEVEL)
            startCycle(fullTs, FULL_LEVEL, fromFullCharge = true)
        }

        // ---- 本地累计 ----
        if (prev != null && st.discharging && prev.discharging) {
            val dt = st.ts - prev.ts
            if (dt in 1..(drainIntervalMs * 4)) dischargeMs += dt
        }
        if (st.discharging) {
            if (st.powerMw > 0) { powerSum += st.powerMw; powerCount++ }
            if (st.voltageMv > 0) { voltageSum += st.voltageMv; voltageCount++ }
        }

        // ---- 落一个曲线点(默认 60s 一点;充电时也记,便于在曲线上看出充电段) ----
        if (lastDrainTs == 0L || st.ts - lastDrainTs >= drainIntervalMs) {
            lastDrainTs = st.ts
            writePoint(st)
        }
        publish()
    }

    private fun writePoint(st: BatteryStateReader.State) {
        val dir = cycleDir ?: return
        val sys = lastSys
        val p = BatteryCurveStore.DrainPoint(
            ts = st.ts,
            level = st.level,
            currentMa = st.currentMa,
            voltageMv = st.voltageMv,
            powerMw = st.powerMw,
            tempC = st.tempC,
            chargeState = st.status,
            plugged = st.plugged,
            screenOn = st.screenOn,
            timeOnBatteryMs = sys?.timeOnBatteryMs ?: -1L,
            screenOnMs = sys?.screenOnMs ?: -1L,
            computedDrainMah = sys?.computedDrainMah ?: -1.0,
            uidTotalMah = sys?.uidTotalMah ?: -1.0,
        )
        if (store.appendDrain(dir, p)) {
            curve.add(p)
            if (curve.size > CURVE_MAX) curve.removeAt(0)
        }
    }

    // ------------------------------------------------------------------
    // 每 5min 系统统计(续航时长 / 亮屏时长 / 每应用耗电)
    // ------------------------------------------------------------------

    fun onSystemStats(sys: BatteryStatsParser.SystemBatteryStats) {
        lastSys = sys
        // 容量:系统给出的是设计容量,仅用于 mAh ↔ % 换算
        val dir = cycleDir
        val meta = cycleMeta
        if (dir != null && meta != null && meta.capacityMah != sys.capacityMah) {
            cycleMeta = meta.copy(capacityMah = sys.capacityMah)
            store.writeCycleMeta(dir, cycleMeta!!)
        }

        // ---- 逐 UID → 展示信息 + 平均功耗 ----
        val total = sys.uidTotalMah
        val hours = (sys.timeOnBatteryMs.takeIf { it > 0 } ?: 1L) / 3_600_000.0
        val vAvg = (voltageSum / voltageCount).takeIf { voltageCount > 0 } ?: 3800.0
        appList = sys.uids.map { u ->
            val info = resolver.resolve(u.uid)
            val avgMw = if (hours > 0) u.mah * vAvg / 1000.0 / hours else -1.0
            AppPower(
                uid = u.uid,
                pkg = info.pkg,
                label = info.label,
                mah = u.mah,
                percent = if (total > 0) u.mah * 100.0 / total else -1.0,
                avgPowerMw = avgMw,
            )
        }

        // ---- 快照写 apps.csv(供每应用功耗曲线);只取够大的、限 40 条 ----
        if (dir != null && lastState?.charging != true) {
            val rows = appList.filter { it.mah >= APP_MAH_MIN }
                .take(APP_SNAPSHOT_MAX)
                .map { BatteryCurveStore.AppPoint(sys.at, it.uid, it.pkg, it.label, it.mah) }
            if (rows.isNotEmpty()) {
                store.appendApps(dir, rows)
                lastSysWriteTs = sys.at
                cycleMeta = cycleMeta?.let { it.copy(appSampleCount = it.appSampleCount + 1) }
            }
        }
        publish()
    }

    /** 对外暴露最近一次系统统计(充电页/续航页判断是否已授权 Shizuku) */
    fun systemStats(): BatteryStatsParser.SystemBatteryStats? = lastSys

    // ------------------------------------------------------------------
    // 对外读取
    // ------------------------------------------------------------------

    /** 本周期逐应用耗电(系统口径) */
    fun apps(): List<AppPower> = appList

    /** 某个应用在本周期的功耗曲线(mW / 时间):由 apps.csv 相邻快照求差得到 */
    fun appPowerSeries(dir: File, uid: Int): List<Pair<Long, Float>> {
        val pts = store.readApps(dir).filter { it.uid == uid }.sortedBy { it.ts }
        if (pts.size < 2) return emptyList()
        val vAvg = 3800f
        val out = ArrayList<Pair<Long, Float>>(pts.size - 1)
        for (i in 1 until pts.size) {
            val dtH = (pts[i].ts - pts[i - 1].ts) / 3_600_000.0
            if (dtH <= 0) continue
            val dmAh = (pts[i].mah - pts[i - 1].mah).coerceAtLeast(0.0)
            val mw = dmAh * vAvg / 1000.0 / dtH
            out.add(pts[i].ts to mw.toFloat())
        }
        return out
    }

    private fun publish() {
        val st = lastState
        val meta = cycleMeta
        val sys = lastSys
        val level = st?.level ?: -1
        val startLevel = meta?.startLevel ?: -1
        // 预估剩余:按系统口径"每 1% 能用多久"线性外推
        var remain = -1L
        if (sys != null && sys.timeOnBatteryMs > 0 && level in 1..99 && startLevel > level) {
            val used = startLevel - level
            remain = sys.timeOnBatteryMs / used * level
        }
        _live.value = Live(
            recording = cycleDir != null,
            cycleStartTs = meta?.startTs ?: 0L,
            cycleDir = cycleDir?.absolutePath ?: "",
            startLevel = startLevel,
            level = level,
            screenOn = st?.screenOn ?: false,
            charging = st?.charging ?: false,
            pluggedIn = st?.pluggedIn ?: false,
            chargeStateText = st?.chargeStateText ?: "--",
            powerMw = st?.powerMw ?: -1L,
            currentMa = st?.currentMa ?: -1,
            voltageMv = st?.voltageMv ?: -1,
            tempC = st?.tempC ?: -1f,
            timeOnBatteryMs = sys?.timeOnBatteryMs ?: -1L,
            screenOnMs = sys?.screenOnMs ?: -1L,
            computedDrainMah = sys?.computedDrainMah ?: -1.0,
            capacityMah = sys?.capacityMah ?: (meta?.capacityMah ?: -1),
            avgPowerMw = if (powerCount > 0) powerSum / powerCount else -1.0,
            localDischargeMs = dischargeMs,
            estRemainMs = remain,
            sampleCount = curve.size,
            apps = appList,
            curve = ArrayList(curve),
            sysStatsTs = sys?.at ?: 0L,
        )
    }

    /** 立即把内存里的周期状态写盘(退出/关闭记录时调用) */
    fun flush() {
        val dir = cycleDir ?: return
        val meta = cycleMeta ?: return
        store.writeCycleMeta(dir, meta.copy(sampleCount = curve.size, endLevel = lastState?.level ?: -1))
    }

    /** 关闭记录:写盘并置为未记录(不封存周期,下次启用继续) */
    fun close() {
        flush()
    }
}
