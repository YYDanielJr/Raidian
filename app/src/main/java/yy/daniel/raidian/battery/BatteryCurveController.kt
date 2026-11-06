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

    /** 是否开启后台记录(默认开:不开关就永远没有曲线数据) */
    fun enabled(): Boolean = sp.getBoolean("enabled", true)

    fun setEnabled(v: Boolean) = sp.edit().putBoolean("enabled", v).apply()

    /** 历史保留天数(0 = 不自动清理) */
    fun keepDays(): Int = sp.getInt("keep_days", 30)

    fun setKeepDays(d: Int) = sp.edit().putInt("keep_days", d.coerceIn(0, 365)).apply()

    companion object {
        /** 主循环节拍:30s(充电曲线的分辨率;电池状态读取本身零 shell 开销) */
        const val TICK_MS = 30_000L
        /** 放电曲线落点间隔:60s */
        const val DRAIN_INTERVAL_MS = 60_000L
        /** 系统 batterystats 读取间隔:5min(一次 shell,实测 ~0.7s) */
        const val SYSTEM_INTERVAL_MS = 300_000L
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
 *  - 每 5min:`dumpsys batterystats` → 系统口径续航/亮屏时长 + 逐 UID 耗电
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

    /** Application.onCreate 调用:按配置启动循环 */
    fun start() {
        _enabled.value = prefs.enabled()
        charging.resumeIfUnfinished()
        if (_enabled.value) {
            ensureLoop()
            startServiceSafe()
        }
    }

    /** 界面开关:开/关后台记录(立即生效并持久化) */
    fun setEnabled(v: Boolean) {
        prefs.setEnabled(v)
        _enabled.value = v
        if (v) {
            ensureLoop()
            startServiceSafe()
            refreshNow()
        } else {
            endurance.close()
            charging.close()
            BatteryCurveService.stop(app)
        }
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

    private fun ensureLoop() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    private suspend fun loop() {
        var lastSysAt = 0L
        var lastPruneAt = System.currentTimeMillis()
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
                if (lastSysAt == 0L || st.ts - lastSysAt >= BatteryCurvePrefs.SYSTEM_INTERVAL_MS) {
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

    /** 启动前台服务保活(Android 12+ 后台启动前台服务会抛异常,这里必须吞掉) */
    private fun startServiceSafe() {
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
