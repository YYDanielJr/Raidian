package yy.daniel.raidian.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yy.daniel.raidian.RaidianApp
import yy.daniel.raidian.monitor.Sample
import yy.daniel.raidian.monitor.SessionMeta
import yy.daniel.raidian.monitor.SessionRecorder
import yy.daniel.raidian.monitor.SessionStore
import yy.daniel.raidian.overlay.OverlayController
import java.io.File

/**
 * 主界面状态与逻辑(AndroidViewModel,随 Activity 存在)。
 *
 * 后端对象全部从 Application([RaidianApp])取,本类只做转发/编排:
 *  - 会话列表/详情的异步加载
 *  - UI 级采样轮询(仅悬浮窗未显示时自轮询,保证实时页有数据)
 *
 * 悬浮窗与它的轮询由 Application 级 [OverlayController] 持有,不随 Activity 销毁
 * (这是"切后台悬浮窗仍更新"的关键,勿把轮询搬到本类长期持有)。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val raidian = app as RaidianApp

    val privileges = raidian.privileges
    val recorder = raidian.recorder
    val overlayController: OverlayController = raidian.overlayController

    /** 最新采样(悬浮窗/主界面同源,都是 OverlayController.lastSample) */
    val lastSample: StateFlow<Sample> = overlayController.lastSample

    /** 悬浮窗是否显示中 */
    val overlayShown: StateFlow<Boolean> = overlayController.shown

    /** 悬浮窗配置(读写 SharedPreferences,见 OverlayPrefs) */
    val overlayPrefs = overlayController.prefs

    /** 采样历史(实时曲线数据源) */
    val history: StateFlow<List<Sample>> = overlayController.history

    // ==================== 续航 / 充电曲线 ====================

    /** 曲线记录控制器(Application 级常驻,每 30s 采样、每 5min 读系统 batterystats) */
    val batteryCurves = raidian.batteryCurves

    /** 续航实况(系统口径续航/亮屏时长 + 逐应用耗电 + 本周期曲线) */
    val enduranceLive: StateFlow<yy.daniel.raidian.battery.EnduranceRecorder.Live> =
        batteryCurves.endurance.live

    /** 充电实况(当前/最近一次充电会话的曲线与统计) */
    val chargingLive: StateFlow<yy.daniel.raidian.battery.ChargingRecorder.Live> =
        batteryCurves.charging.live

    /** 后台记录开关 */
    val curveEnabled: StateFlow<Boolean> = batteryCurves.enabled

    /** 系统统计是否可读(false = 未连接采集服务,续航时长/每应用耗电拿不到,只剩本地曲线) */
    val systemReadable: StateFlow<Boolean> = batteryCurves.systemReadable

    /** 曲线历史一行(放电周期或充电会话;两个 meta 只会有一个非空) */
    data class CurveItem(
        val dir: File,
        val cycle: yy.daniel.raidian.battery.BatteryCurveStore.CycleMeta? = null,
        val charge: yy.daniel.raidian.battery.BatteryCurveStore.ChargeMeta? = null,
        /** 周期最后一次采样里的系统口径续航时长 ms(历史列表摘要用;-1 无) */
        val timeOnBatteryMs: Long = -1L,
        /** 周期最后一次采样里的系统口径亮屏时长 ms */
        val screenOnMs: Long = -1L,
    ) {
        val active: Boolean
            get() = cycle?.let { it.endTs == 0L } ?: (charge?.endTs == 0L)
    }

    private val _cycles = MutableStateFlow<List<CurveItem>>(emptyList())

    /** 放电周期历史(新 → 旧;第一条可能是"进行中"的当前周期) */
    val cycles: StateFlow<List<CurveItem>> = _cycles.asStateFlow()

    private val _chargeSessions = MutableStateFlow<List<CurveItem>>(emptyList())

    /** 充电会话历史(新 → 旧) */
    val chargeSessions: StateFlow<List<CurveItem>> = _chargeSessions.asStateFlow()

    /** 刷新放电周期列表(顺带读每周期最后一个采样点,供列表摘要显示续航/亮屏时长) */
    fun refreshCycles() {
        viewModelScope.launch {
            val store = batteryCurves.store
            _cycles.value = withContext(Dispatchers.IO) {
                store.listCycles().map { dir ->
                    val last = store.readDrain(dir).lastOrNull()
                    CurveItem(
                        dir = dir,
                        cycle = store.readCycleMeta(dir),
                        timeOnBatteryMs = last?.timeOnBatteryMs ?: -1L,
                        screenOnMs = last?.screenOnMs ?: -1L,
                    )
                }
            }
        }
    }

    /** 刷新充电会话列表 */
    fun refreshChargeSessions() {
        viewModelScope.launch {
            val store = batteryCurves.store
            _chargeSessions.value = withContext(Dispatchers.IO) {
                store.listCharges().map { CurveItem(it, null, store.readChargeMeta(it)) }
            }
        }
    }

    /** 开关后台记录(写配置并立即生效) */
    fun setCurveEnabled(v: Boolean) = batteryCurves.setEnabled(v)

    /** 立即读一次系统 batterystats(进入曲线页 / 手动刷新) */
    fun refreshSystemStats() = batteryCurves.refreshNow()

    /** 删除若干周期/会话目录 */
    fun deleteCurves(dirs: List<File>, charging: Boolean) {
        if (dirs.isEmpty()) return
        viewModelScope.launch {
            val store = batteryCurves.store
            withContext(Dispatchers.IO) { store.deleteDirs(dirs) }
            if (charging) refreshChargeSessions() else refreshCycles()
        }
    }

    /** 保留天数(0 = 不清理) */
    fun keepDays(): Int = batteryCurves.prefs.keepDays()

    fun setKeepDays(d: Int) = batteryCurves.prefs.setKeepDays(d)

    /** 会话列表的一行:目录 + 解析出的元数据(解析失败可为 null) */
    data class SessionItem(val dir: File, val meta: SessionMeta?)

    private val _sessions = MutableStateFlow<List<SessionItem>>(emptyList())
    val sessions: StateFlow<List<SessionItem>> = _sessions.asStateFlow()

    private val _sessionDetail = MutableStateFlow<SessionDetail?>(null)
    val sessionDetail: StateFlow<SessionDetail?> = _sessionDetail.asStateFlow()

    /** 详情页的数据:元数据 + 从 CSV 抽出的各曲线序列 */
    data class SessionDetail(
        val meta: SessionMeta,
        /** elapsed_ms(相对起点)列表,曲线 x 轴备用 */
        val elapsed: List<Long>,
        val fps: List<Float>,
        val batteryTemp: List<Float>,
        val cpuTemp: List<Float>,
        val power: List<Float>,
        val cpuLoad: List<Float>,
        /** GPU 占用 %(阶段四:kgsl gpubusy;无则空) */
        val gpuLoad: List<Float>,
        /** 每逻辑核频率 MHz:下标 = 核号,元素为该核各采样点序列 */
        val perCoreFreq: List<List<Float>>,
        val perCoreLoad: List<List<Float>> = emptyList(),
        val perCoreTemp: List<List<Float>> = emptyList(),
        val extraSeries: Map<String, List<Float>> = emptyMap(),
        val corePolicies: List<Int> = emptyList(),
        val coreMaxMhz: List<Int> = emptyList(),
        val breakBefore: List<Boolean> = emptyList(),
    )

    /** 手动刷新 ADB 或 root 授权状态(进入页面 / 授权回调后调用) */
    fun refreshPrivileges() { viewModelScope.launch { privileges.refresh() } }

    // ---- 指定应用记录(门控) ----

    /** 当前"记录目标"选择(null = 跟随前台),状态实际存在 SessionRecorder(跨 Activity) */
    val watchTarget: StateFlow<SessionRecorder.WatchTarget?> = recorder.watchTarget

    /** 设置/清除记录目标(持久化在 SessionRecorder 内) */
    fun setWatchTarget(t: SessionRecorder.WatchTarget?) = recorder.setWatchTarget(t)

    /** 开始记录会话:指定了目标应用则以其为门控;否则取当前前台作为目标 */
    fun startRecording() {
        val appState = raidian.appState
        val watch = recorder.watchTarget.value
        viewModelScope.launch {
            val front = appState.readFrontApp()
            if (watch != null) {
                raidian.recorder.start(watch.pkg, watch.label, watch.pkg)
            } else {
                raidian.recorder.start(front.first, front.second, null)
            }
        }
    }

    fun stopRecording() = raidian.recorder.stop()

    fun toggleOverlay() = overlayController.toggle()

    /** 刷新会话历史列表(后台 IO 读目录 + 解析 session.json) */
    fun refreshSessions() {
        viewModelScope.launch {
            val store: SessionStore = raidian.sessionStore
            val items = withContext(Dispatchers.IO) {
                store.listDirs().map { dir ->
                    SessionItem(dir, store.readMeta(dir))
                }
            }
            _sessions.value = items
        }
    }

    /** 删除一组会话目录(后台 IO),完成后刷新列表 */
    fun deleteSessions(dirs: List<File>) {
        if (dirs.isEmpty()) return
        viewModelScope.launch {
            val store: SessionStore = raidian.sessionStore
            withContext(Dispatchers.IO) {
                store.deleteDirs(dirs)
            }
            refreshSessions()
        }
    }

    /**
     * 打开某会话详情(后台 IO 读 session.json + samples.csv 并抽取曲线序列)。
     * @param dir 目标会话目录;传 null 表示关闭详情页。
     */
    fun openSession(dir: File?) {
        viewModelScope.launch {
            if (dir == null) {
                _sessionDetail.value = null
                return@launch
            }
            val store: SessionStore = raidian.sessionStore
            val detail = withContext(Dispatchers.IO) {
                val meta = store.readMeta(dir)
                if (meta == null) null
                else {
                    val (header, rows) = store.readSamplesWithHeader(dir)
                    if (rows.isEmpty()) {
                        // 只有元数据(空记录,如 FPS 无数据时也应可看)
                        SessionDetail(
                            meta = meta,
                            elapsed = emptyList(), fps = emptyList(),
                            batteryTemp = emptyList(), cpuTemp = emptyList(),
                            power = emptyList(), cpuLoad = emptyList(),
                            gpuLoad = emptyList(), perCoreFreq = emptyList(),
                        )
                    } else {
                        // 按表头列名定位(兼容新旧表头;找不到的列整列为 -1/空)
                        fun idx(name: String): Int = header.indexOf(name)
                        val iElapsed = idx("elapsed_ms"); val iFps = idx("fps")
                        val iLoad = idx("cpu_load_pct")
                        val iGpuLoad = idx("gpu_load_pct")
                        val iBatt = idx("battery_temp_c"); val iCpuT = idx("max_cpu_temp_c")
                        val iPower = idx("power_mw")
                        val coreCount = header.mapNotNull { Regex("cpu(\\d+)_freq_mhz").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
                            .maxOrNull()?.plus(1) ?: 0
                        val coreIdx = (0 until coreCount)
                            .map { it to idx("cpu${it}_freq_mhz") }
                        // 断点:elapsed 跳变超过 2×采样间隔视为暂停段(曲线留白)
                        val gapNs = (meta.intervalMs.takeIf { it > 0 } ?: 500L) * 2

                        val elapsed = ArrayList<Long>(rows.size)
                        val fps = ArrayList<Float>(rows.size)
                        val batt = ArrayList<Float>(rows.size)
                        val cpuT = ArrayList<Float>(rows.size)
                        val power = ArrayList<Float>(rows.size)
                        val load = ArrayList<Float>(rows.size)
                        val gpuLoad = ArrayList<Float>(rows.size)
                        val perCore = coreIdx.map { ArrayList<Float>(rows.size) }
                        for (r in rows) {
                            val e = r.getOrNull(iElapsed)?.toLongOrNull() ?: 0L
                            // 保留每条真实读数，暂停段通过独立 breakBefore 标志断线
                            fun add(list: MutableList<Float>, v: Float) { list.add(v) }
                            elapsed.add(e)
                            add(fps, r.getOrNull(iFps)?.toFloatOrNull() ?: -1f)
                            add(batt, r.getOrNull(iBatt)?.toFloatOrNull() ?: -1f)
                            add(cpuT, r.getOrNull(iCpuT)?.toFloatOrNull() ?: -1f)
                            add(power, r.getOrNull(iPower)?.toFloatOrNull() ?: -1f)
                            add(load, r.getOrNull(iLoad)?.toFloatOrNull() ?: -1f)
                            add(gpuLoad, r.getOrNull(iGpuLoad)?.toFloatOrNull() ?: -1f)
                            coreIdx.forEachIndexed { ci, (_, col) ->
                                add(perCore[ci], r.getOrNull(col)?.toFloatOrNull() ?: -1f)
                            }
                        }
                        fun series(name: String): List<Float> {
                            val col = idx(name)
                            return rows.map { row ->
                                row.getOrNull(col)?.toFloatOrNull()?.takeIf { it.isFinite() } ?: -1f
                            }
                        }
                        fun firstInt(name: String): Int = rows.asSequence().mapNotNull { it.getOrNull(idx(name))?.toIntOrNull() }
                            .firstOrNull { it >= 0 } ?: -1
                        SessionDetail(meta, elapsed, fps, batt, cpuT, power, load, gpuLoad, perCore,
                            corePolicies = (0 until coreCount).map { firstInt("cpu${it}_policy") },
                            coreMaxMhz = (0 until coreCount).map { firstInt("cpu${it}_max_mhz") },
                            breakBefore = elapsed.mapIndexed { i, t -> i > 0 && t - elapsed[i - 1] > gapNs },
                            perCoreLoad = (0 until coreCount).map { series("cpu${it}_load_pct") },
                            perCoreTemp = (0 until coreCount).map { series("cpu${it}_temp_c") },
                            extraSeries = listOf("gpu_temp_c", "npu_temp_c", "skin_temp_c", "thermal_status", "available_mem_mb", "swap_used_mb")
                                .associateWith(::series))
                    }
                }
            }
            _sessionDetail.value = detail
        }
    }

    // ---- 界面级实时采样 ----

    /** UI 轮询是否在跑(Activity 存活期间 true,onCleared 置 false) */
    private var polling = false
    @Volatile private var uiVisible = false
    fun setUiVisible(visible: Boolean) { uiVisible = visible }

    /**
     * 主界面可见期间持续采样并发布到 [OverlayController],保证实时页数据
     * 不依赖悬浮窗是否显示。Activity 销毁时由 onCleared 停止。
     *
     * 悬浮窗显示时跳过本轮:此时 OverlayController 自身在轮询并推送同源数据,
     * 避免两个循环并发打满 采集服务通道（请求串行,并发会互相排队)。
     */
    fun startUiPolling() {
        if (polling) return
        polling = true
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (polling) {
                if (uiVisible && !overlayController.shown.value) {
                    try {
                        val s = raidian.sampler.sample(System.currentTimeMillis())
                        overlayController.publishSample(s)
                    } catch (e: Exception) {
                        // 单次失败忽略
                    }
                }
                kotlinx.coroutines.delay(500)
            }
        }
    }

    /** Activity 销毁:停 UI 轮询(悬浮窗轮询在 OverlayController,不受影响) */
    override fun onCleared() {
        polling = false
        super.onCleared()
    }
}
