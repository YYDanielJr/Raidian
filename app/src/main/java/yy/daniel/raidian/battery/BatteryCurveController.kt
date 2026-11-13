package yy.daniel.raidian.battery

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import yy.daniel.raidian.RaidianApp
import yy.daniel.raidian.monitor.CommandExecutor

/** 续航/充电曲线记录配置(SharedPreferences) */
class BatteryCurvePrefs(context: Context) {

    private val sp = context.getSharedPreferences("battery_curve", Context.MODE_PRIVATE)

    init {
        migrateDefaultOff()
    }

    /**
     * 0.5.3 之前后台记录默认开启，且用户没有手动关过时不会写入这个键。
     * 结果是「装好后什么都没点，后台就有一个前台服务常驻采样」，与“后台无任务不工作”
     * 的目标冲突。升级时把这份**隐式默认**改为关闭；用户明确按过开关的偏好原样保留。
     */
    private fun migrateDefaultOff() {
        if (sp.getBoolean(KEY_POLICY_MIGRATED, false)) return
        val explicit = sp.contains(KEY_ENABLED)
        val current = sp.getBoolean(KEY_ENABLED, true)
        val next = if (explicit) current else false
        sp.edit()
            .putBoolean(KEY_ENABLED, next)
            .putBoolean(KEY_POLICY_MIGRATED, true)
            .apply()
    }

    /** 是否开启后台记录。默认关闭；只有用户明确开启才常驻后台。 */
    fun enabled(): Boolean = sp.getBoolean(KEY_ENABLED, false)

    fun setEnabled(v: Boolean) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    /** 历史保留天数(0 = 不自动清理) */
    fun keepDays(): Int = sp.getInt("keep_days", 30)

    fun setKeepDays(d: Int) = sp.edit().putInt("keep_days", d.coerceIn(0, 365)).apply()

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_POLICY_MIGRATED = "background_policy_migrated"

        /** 主循环节拍:30s(充电曲线的分辨率;电池状态读取本身零 shell 开销) */
        const val TICK_MS = 30_000L
        /** 放电曲线落点间隔:60s */
        const val DRAIN_INTERVAL_MS = 60_000L
        /** 系统 batterystats 读取间隔:15min(一次 shell + 解析，温度/时长变化慢，不必 5min 拉一次) */
        const val SYSTEM_INTERVAL_MS = 900_000L
        /** 充电期间系统统计不需要逐应用功耗曲线，1h 读一次即可 */
        const val SYSTEM_INTERVAL_CHARGING_MS = 3_600_000L
        /** 历史清理间隔:6h */
        const val PRUNE_INTERVAL_MS = 6 * 3_600_000L
    }
}

/**
 * 续航 / 充电曲线的 Application 级常驻控制器。
 *
 * 架构与 [yy.daniel.raidian.overlay.OverlayController] 一致:循环挂在 Application 级
 * 协程作用域上,与任何 Activity 生命周期无关 —— 切后台/灭屏后仍在记录,这正是
 * "记录一整轮放电"所必需的。进程被系统回收后由前台服务([BatteryCurveService])拉起。
 *
 * 一个循环驱动两个记录器(避免各自去抢 采集服务通道):
 *  - 每 30s:读电池状态 → [ChargingRecorder] + [EnduranceRecorder]
 *  - 放电每 15min、充电每 1h:`dumpsys batterystats` → 系统口径续航/亮屏时长 + 逐 UID 耗电
 *
 * 默认**不**启动：用户明确打开「后台记录续航曲线」后才会创建前台服务。后台策略
 * （[yy.daniel.raidian.runtime.BackgroundPolicy]）在无记录/浮窗/曲线任务时会把整个
 * 进程停掉，因此“什么都没开”不会留下常驻采样。
 */
class BatteryCurveController(
    private val app: RaidianApp,
    private val executor: CommandExecutor,
) {
    companion object {
        private const val TAG = "BatteryCurve"
    }

    val prefs = BatteryCurvePrefs(app)
    val store = BatteryCurveStore(app)
    val endurance = EnduranceRecorder(app, store, UidInfoResolver(app))
    val charging = ChargingRecorder(store)

    private val reader = BatteryStateReader(app)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _enabled = MutableStateFlow(prefs.enabled())
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** 最近一次系统统计(为 null = 还没读到,通常是 未连接采集服务) */
    private val _systemStats = MutableStateFlow<BatteryStatsParser.SystemBatteryStats?>(null)
    val systemStats: StateFlow<BatteryStatsParser.SystemBatteryStats?> = _systemStats.asStateFlow()

    /** 系统统计是否可读(读成功过即 true;用于界面提示"授权 Shizuku 才能看到每应用耗电") */
    private val _systemReadable = MutableStateFlow(false)
    val systemReadable: StateFlow<Boolean> = _systemReadable.asStateFlow()

    /** 最近一次记录循环的心跳时刻(界面判断是否在后台跑) */
    private val _lastTickTs = MutableStateFlow(0L)
    val lastTickTs: StateFlow<Long> = _lastTickTs.asStateFlow()

    /** Application.onCreate 调用:按配置启动循环（默认关闭，不创建任何后台服务） */
    fun start() {
        _enabled.value = prefs.enabled()
        if (!_enabled.value) {
            BatteryCurveService.stop(app)
            return
        }
        charging.resumeIfUnfinished()
        ensureLoop()
        startServiceSafe()
    }

    /** 当前是否开启了后台曲线记录（服务与空闲策略的唯一判据） */
    fun isEnabled(): Boolean = _enabled.value && prefs.enabled()

    /** 界面开关:开/关后台记录(立即生效并持久化) */
    fun setEnabled(v: Boolean) {
        prefs.setEnabled(v)
        _enabled.value = v
        if (v) {
            charging.resumeIfUnfinished()
            ensureLoop()
            startServiceSafe()
            refreshNow()
        } else {
            job?.cancel()
            job = null
            endurance.close()
            charging.close()
            BatteryCurveService.stop(app)
        }
        // 让统一后台策略重新评估：关闭后若无其它任务，应停止服务并安排退出。
        runCatching { app.backgroundPolicy.onWorkChanged() }
    }

    /** 立即读一次系统统计(进入续航页 / 手动刷新时用,不用等 5 分钟节拍) */
    fun refreshNow() {
        scope.launch {
            try {
                readSystemStats()
            } catch (e: Exception) {
                Log.w(TAG, "refreshNow failed", e)
            }
        }
    }

    /**
     * 只确保进程内循环在跑，不启动服务。
     * 供 [BatteryCurveService.onStartCommand] 使用，避免服务自我启动的死循环。
     */
    fun ensureLoop() {
        if (!isEnabled()) return
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    /**
     * 后台策略判定“无任务”时调用：停掉循环并关闭记录器。
     * 只有已关闭后台记录时才会真正执行（开启状态本身就是一个后台任务）。
     */
    fun suspendForIdle() {
        if (isEnabled()) return
        job?.cancel()
        job = null
        runCatching {
            endurance.close()
            charging.close()
        }
    }

    private suspend fun loop() {
        var lastSysAt = 0L
        var lastPruneAt = System.currentTimeMillis()
        var wasCharging = false
        while (scope.isActive) {
            if (!prefs.enabled()) {
                delay(10_000)
                continue
            }
            try {
                val st = reader.read()
                _lastTickTs.value = st.ts
                charging.onSample(st)
                endurance.onSample(st, BatteryCurvePrefs.DRAIN_INTERVAL_MS)
                // 系统统计只用于续航时长/每应用耗电，变化慢；充电时每应用曲线不写，
                // 没必要每 5 分钟做一次 dumpsys + 解析。放电 15min、充电 1h 各一次，
                // 并在刚拔电时补一次，保证本周期最终数据不丢。
                val chargingChanged = st.charging != wasCharging
                wasCharging = st.charging
                val sysInterval = if (st.charging) BatteryCurvePrefs.SYSTEM_INTERVAL_CHARGING_MS
                    else BatteryCurvePrefs.SYSTEM_INTERVAL_MS
                val sysDue = lastSysAt == 0L || st.ts - lastSysAt >= sysInterval ||
                    (chargingChanged && !st.charging)
                if (sysDue) {
                    lastSysAt = st.ts
                    readSystemStats()
                }
                if (st.ts - lastPruneAt >= BatteryCurvePrefs.PRUNE_INTERVAL_MS) {
                    lastPruneAt = st.ts
                    val n = store.prune(prefs.keepDays())
                    if (n > 0) Log.i(TAG, "pruned $n old curve dirs")
                }
            } catch (e: Exception) {
                Log.w(TAG, "tick failed", e)
            }
            delay(BatteryCurvePrefs.TICK_MS)
        }
    }

    /** 读一次 `dumpsys batterystats` 并分发(需要 ADB 或 root;无授权时系统统计缺失,本地曲线仍继续) */
    private suspend fun readSystemStats() {
        // 曲线记录通常运行在后台探测 OFF 的状态；这是低频路径，允许在这里按需重连。
        executor.privileges?.ensureConnected()
        val out = executor.execute(BatteryStatsParser.CMD)
        if (out.isBlank()) {
            _systemReadable.value = false
            return
        }
        val sys = BatteryStatsParser.parse(out, System.currentTimeMillis())
        if (sys == null) {
            _systemReadable.value = false
            return
        }
        _systemReadable.value = true
        _systemStats.value = sys
        charging.setCapacity(sys.capacityMah)
        endurance.onSystemStats(sys)
    }

    /** 启动前台服务保活(Android 12+ 后台启动前台服务会抛异常；只在用户开启记录时调用) */
    private fun startServiceSafe() {
        if (!isEnabled()) return
        try {
            BatteryCurveService.start(app)
        } catch (e: Exception) {
            Log.w(TAG, "start foreground service failed: ${e.message}")
        }
    }

    /** 进程退出前的收尾(写盘) */
    fun shutdown() {
        job?.cancel()
        job = null
        try {
            endurance.close()
            charging.close()
        } catch (e: Exception) {
            Log.w(TAG, "shutdown flush failed", e)
        }
    }
}
