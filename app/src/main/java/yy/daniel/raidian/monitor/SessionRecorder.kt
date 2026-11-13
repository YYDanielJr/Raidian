package yy.daniel.raidian.monitor

import android.content.Context
import android.util.Log
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话记录器:驱动 [MonitorSampler] 周期性采样,把每次采样写进 CSV,
 * 并在线维护平均帧、1% Low、0.1% Low 与功耗/温度均值统计。
 *
 * 每个监控会话独占一个目录:
 *   files/raidian/sessions/<yyyyMMdd_HHmmss>/
 *     session.json  元数据(手写 JSON,含均值统计,stop 时更新)
 *     samples.csv   原始采样(时间、帧率、各频率、温度、功耗、内存、网络)
 *
 * 生命周期:start() 建目录开文件并启动协程循环;stop() 停止循环、写回统计、关文件。
 * 由 RaidianApp 持有单例(RaidianApp.recorder),主界面/悬浮窗共用一个实例;
 * 进程被杀时循环随之结束,因此"长时间后台记录"若需保活应挂到 MonitorService 前台服务。
 */
class SessionRecorder(
    private val context: Context,
    private val sampler: MonitorSampler,
    private val appState: AppStateReader,
) {
    companion object {
        private const val TAG = "SessionRecorder"
        /** 默认采样间隔:500ms,即约 2 点/秒 */
        private const val DEFAULT_INTERVAL_MS = 500L
        /** 温区明细 JSON 写入间隔；曲线只用标量温度，明细主要用于诊断。 */
        private const val THERMAL_JSON_INTERVAL_MS = 30_000L
    }

    // ---- 对外状态(界面可观察) ----

    /** 会话是否进行中 */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 实时帧列表:只收集 fps>0 的采样点,用于低帧统计 */
    private val recentFps = ArrayList<Float>(1024)
    /** 当前统计(平均帧/1%Low/0.1%Low/样本数),随采样实时更新 */
    private val _fpsStats = MutableStateFlow(FpsStats())
    val fpsStats: StateFlow<FpsStats> = _fpsStats.asStateFlow()

    // ---- 内部状态 ----

    /** 采样周期 ms(可调;写进 session.json 以便日后回放校准时间轴) */
    @Volatile var intervalMs: Long = DEFAULT_INTERVAL_MS
        private set

    // ---- 指定应用记录(门控目标,跨 Activity 共享 + 持久化) ----

    /** 记录目标(包名 + 名);null = 跟随当前前台(不门控) */
    data class WatchTarget(val pkg: String, val label: String)

    private val _watchTarget = MutableStateFlow<WatchTarget?>(
        context.getSharedPreferences("record_target", Context.MODE_PRIVATE)
            .getString("pkg", "")?.takeIf { it.isNotEmpty() }?.let { pkg ->
                WatchTarget(pkg, context.getSharedPreferences("record_target", Context.MODE_PRIVATE)
                    .getString("label", "") ?: pkg)
            }
    )
    val watchTarget: StateFlow<WatchTarget?> = _watchTarget.asStateFlow()

    /** 设置/清除记录目标(持久化到 SharedPreferences) */
    fun setWatchTarget(t: WatchTarget?) {
        _watchTarget.value = t
        val sp = context.getSharedPreferences("record_target", Context.MODE_PRIVATE)
        sp.edit().apply {
            if (t == null) { remove("pkg"); remove("label") }
            else { putString("pkg", t.pkg); putString("label", t.label) }
            apply()
        }
    }

    /** 是否正因目标不在前台而暂停(界面可观察) */
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    /** 本次会话生效的门控包名(取开始瞬间的 watchTarget) */
    @Volatile private var gatePkg: String? = null

    @Volatile private var sessionDir: File? = null
    @Volatile private var sessionMeta: SessionMeta? = null
    @Volatile private var csvWriter: java.io.BufferedWriter? = null
    private var csvCoreCount: Int? = null
    @Volatile private var job: kotlinx.coroutines.Job? = null
    @Volatile private var stopFlag = false
    private var generation = 0L
    private var recordedSamples = 0

    /** 温区 JSON 的缓存：明细变化慢，没必要每 500ms 重新序列化 38 个对象。 */
    private var lastThermalJson = "[]"
    private var lastThermalJsonAt = 0L

    /** 帧统计（排序/百分位）刷新间隔；每 500ms 对不断增长的列表排序太浪费。 */
    private var lastStatsAt = 0L

    /** 采集协程作用域:独立于任何 Activity/ViewModel,Dispatchers.Default 跑 IO 混合任务 */
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.Default
    )

    /** 会话开始时间(epoch ms),用于算 elapsed */
    @Volatile private var startElapsed = 0L

    /** 上次"有效"采样时刻(用于暂停/恢复时的节奏对齐,防止恢复瞬间密集补采) */
    @Volatile private var lastSampleAt = 0L

    // ---- 均值累计(多协程写,统一用 statLock 保护) ----
    private val statLock = Any()
    private var powerSum = 0.0
    private var powerCount = 0L
    private var battTempSum = 0.0
    private var battTempCount = 0L
    private var cpuTempSum = 0.0
    private var cpuTempCount = 0L

    /** 当前会话目录(空会话时为 null);记录页/详情页也会用到 */
    val currentSessionDir: File? get() = sessionDir

    fun isRunning(): Boolean = _running.value

    /**
     * 开始一个会话。
     * @param targetPkg 前台/目标应用包名(写入元数据 targetPkg)
     * @param targetLabel 应用名
     * @param watchPkg 门控目标包名:非空时仅当该应用在前台才记录(否则暂停);
     *                 null/空 = 不门控(跟随前台,原行为)
     */
    @Synchronized
    fun start(targetPkg: String, targetLabel: String, watchPkg: String? = null) {
        if (_running.value) return
        intervalMs = SamplingPrefs.read(context)
        stopFlag = false
        val token = ++generation
        recordedSamples = 0
        lastThermalJson = "[]"
        lastThermalJsonAt = 0L
        lastStatsAt = 0L
        gatePkg = watchPkg?.takeIf { it.isNotBlank() } ?: _watchTarget.value?.pkg
        _paused.value = false
        recentFps.clear()
        _fpsStats.value = FpsStats()
        synchronized(statLock) {
            powerSum = 0.0; powerCount = 0L
            battTempSum = 0.0; battTempCount = 0L
            cpuTempSum = 0.0; cpuTempCount = 0L
        }
        val time = System.currentTimeMillis()
        startElapsed = SystemClock.elapsedRealtime()
        lastSampleAt = startElapsed
        // 毫秒时间戳避免快速停止再开始时覆盖上一份记录。
        val dirName = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(time))
        val base = File(context.filesDir, "raidian/sessions")
        val dir = File(base, dirName)
        dir.mkdirs()
        val meta = SessionMeta(
            startTime = time,
            targetPkg = targetPkg,
            targetLabel = targetLabel,
            intervalMs = intervalMs,
            watchPkg = gatePkg ?: "",
        )
        sessionMeta = meta
        sessionDir = dir
        try {
            // 先写表头再写数据
            val f = File(dir, "samples.csv")
            val w = f.bufferedWriter(Charsets.UTF_8)
            csvCoreCount = null // 首次快照后按真实拓扑写表头，不能预设八核。
            csvWriter = w
        } catch (e: Exception) {
            Log.e(TAG, "start: cannot open csv", e)
            return
        }
        writeMeta(meta)
        _running.value = true
        MonitorService.start(context)
        notifyBackgroundPolicy()
        job = scope.launch { loop(token) }
    }

    /** 停止会话:停循环、关文件、把统计均值写回 session.json */
    @Synchronized
    fun stop() {
        if (!_running.value) return
        stopFlag = true
        generation++
        job?.cancel()
        job = null
        try { csvWriter?.close() } catch (e: Exception) { }
        csvWriter = null
        // 更新元数据:写入结束时间/时长/帧统计/均值
        val meta = sessionMeta
        val dir = sessionDir
        if (meta != null && dir != null) {
            val stats = computeStats(recentFps)
            val end = System.currentTimeMillis()
            var avgPower = -1.0; var avgBatt = -1.0; var avgCpu = -1.0
            synchronized(statLock) {
                if (powerCount > 0) avgPower = powerSum / powerCount
                if (battTempCount > 0) avgBatt = battTempSum / battTempCount
                if (cpuTempCount > 0) avgCpu = cpuTempSum / cpuTempCount
            }
            writeMeta(meta.copy(
                endTime = end,
                durationMs = SystemClock.elapsedRealtime() - startElapsed,
                recordedSamples = recordedSamples,
                fpsStats = stats,
                avgPowerMw = avgPower,
                avgBatteryTemp = avgBatt,
                avgCpuTemp = avgCpu,
            ))
        }
        _running.value = false
        _paused.value = false
        MonitorService.stopIfIdle(context)
        notifyBackgroundPolicy()
    }

    /** 记录开始/结束会改变前后台策略；组件可能早于策略初始化，失败不影响记录。 */
    private fun notifyBackgroundPolicy() {
        try {
            yy.daniel.raidian.RaidianApp.get(context).backgroundPolicy.onWorkChanged()
        } catch (_: Exception) {
        }
    }

    /**
     * 采样主循环:按 intervalMs 节奏采样 → 统计 → 写 CSV。
     *
     * 旧实现每 50ms 醒一次检查时间，记录期间每秒 20 次无意义唤醒；现在按“距下次采样”
     * 直接 delay，通常每 500ms 醒一次。门控目标检测有 shell 开销，单独降到 2s 一次；
     * 暂停时同样只按 2s 节拍醒来检查前台，不做任何采样或写盘。
     */
    private suspend fun loop(token: Long) {
        val gateCheckEvery = intervalMs.coerceAtLeast(2_000L)
        var lastGateCheck = 0L
        while (!stopFlag) {
            currentCoroutineContext().ensureActive()
            val wp = gatePkg
            val now = SystemClock.elapsedRealtime()

            // ---- 门控检测:指定了目标应用时,前台不在目标 → 暂停 ----
            if (wp != null && (lastGateCheck == 0L || now - lastGateCheck >= gateCheckEvery)) {
                lastGateCheck = now
                val (front, _) = try {
                    appState.readFrontApp()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "" to ""
                }
                if (front != wp) {
                    // 目标不在前台:暂停采样(不写 CSV),等下一个门控节拍再查
                    _paused.value = true
                    lastSampleAt = 0L // 复位节奏:恢复后立即采一点
                    kotlinx.coroutines.delay(gateCheckEvery)
                    continue
                } else if (_paused.value) {
                    _paused.value = false
                }
            }

            // 节奏:距上次有效采样 ≥ intervalMs 才采;暂停复位过节奏则立即采
            val sinceLast = now - lastSampleAt
            if (lastSampleAt == 0L || sinceLast >= intervalMs) {
                lastSampleAt = now
                val elapsed = now - startElapsed
                try {
                    val s = sampler.sample(elapsed, maxCacheAgeMs = minOf(400L, intervalMs / 2))
                    currentCoroutineContext().ensureActive()
                    synchronized(this) {
                        if (generation != token || !_running.value || (wp != null && s.frontPkg != wp)) return@synchronized
                        // FPS 进统计(仅有效帧)
                        if (s.fps > 0) {
                            synchronized(recentFps) {
                                recentFps.add(s.fps)
                                if (recentFps.size > 60 * 60 * 2) recentFps.removeAt(0)
                            }
                        }
                        synchronized(statLock) {
                            if (s.powerMw >= 0) { powerSum += s.powerMw; powerCount++ }
                            if (s.batteryTemp > 0) { battTempSum += s.batteryTemp; battTempCount++ }
                            if (s.maxCpuTemp > 0) { cpuTempSum += s.maxCpuTemp; cpuTempCount++ }
                        }
                        appendCsv(s)
                        // 1% Low 需要把不断增长的帧率列表排序；每 5s 更新一次足够，
                        // 不必在每个 500ms 采样点都做一次全量排序。
                        val statsNow = SystemClock.elapsedRealtime()
                        if (lastStatsAt == 0L || statsNow - lastStatsAt >= 5_000L) {
                            lastStatsAt = statsNow
                            updateStats()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 单次采样失败不中断记录
                    Log.w(TAG, "sample failed", e)
                }
            }

            // 按下一个采样点/门控检查点计算等待时间，不再固定 50ms 轮询。
            val after = SystemClock.elapsedRealtime()
            val sampleWait = if (lastSampleAt == 0L) 0L else
                (lastSampleAt + intervalMs - after).coerceAtLeast(0L)
            val gateWait = if (wp != null && lastGateCheck != 0L)
                (lastGateCheck + gateCheckEvery - after).coerceAtLeast(0L) else Long.MAX_VALUE
            val wait = minOf(sampleWait, gateWait).coerceAtLeast(50L)
            kotlinx.coroutines.delay(wait)
        }
    }

    /** 追加一行 CSV;synchronized(w) + 每行 flush,崩溃/杀进程也不丢已采数据 */
    private fun appendCsv(s: Sample) {
        try {
            val w = csvWriter ?: return
            synchronized(w) {
                if (csvCoreCount == null) {
                    csvCoreCount = s.perCoreFreqMhz.size
                    w.write(s.toCsvHeader(csvCoreCount!!))
                    w.newLine()
                }
                // 温区明细 30s 刷新一次；其余行复用上一份 JSON，避免每行序列化 38 个对象。
                val now = SystemClock.elapsedRealtime()
                if (s.thermalReadings.isNotEmpty() &&
                    (lastThermalJson == "[]" || now - lastThermalJsonAt >= THERMAL_JSON_INTERVAL_MS)) {
                    lastThermalJson = Json.encodeToString(s.thermalReadings)
                    lastThermalJsonAt = now
                }
                val thermalJson = if (s.thermalReadings.isEmpty()) "[]" else lastThermalJson
                w.write(s.toCsvRow(csvCoreCount!!, thermalJson))
                w.newLine()
                w.flush() // 保证崩溃时数据不丢
                recordedSamples++
            }
        } catch (e: Exception) {
            Log.w(TAG, "csv write failed", e)
        }
    }

    /**
     * 用当前 recentFps 更新 fpsStats(实时页显示)。
     * 1% Low 的业界定义是"帧时间排序后最低 1% 的平均",这里简化为
     * 帧率升序后取最低 1% / 0.1% 求平均(见 percentilMean)。
     */
    private fun updateStats() {
        val snapshot = synchronized(recentFps) { recentFps.toList() }
        if (snapshot.isEmpty()) return
        val avg = snapshot.average().toFloat()
        val sorted = snapshot.sorted()
        val n = sorted.size
        val low1 = percentileMean(sorted, 0.01)
        val low01 = percentileMean(sorted, 0.001)
        _fpsStats.value = FpsStats(
            avgFps = avg,
            low1Fps = low1,
            low01Fps = low01,
            sampleCount = n,
        )
    }

    /**
     * 取排序(升序)序列最低 p 比例的均值。
     * 帧率数值低 = 卡顿,因此"最低 1% 帧率"近似反映最卡的那 1% 采样。
     */
    private fun percentileMean(sorted: List<Float>, p: Double): Float {
        if (sorted.isEmpty()) return -1f
        val n = sorted.size
        val cut = (n * p).toInt().coerceAtLeast(1)
        return sorted.take(cut).average().toFloat()
    }

    /** stop 时最终统计(与 updateStats 逻辑一致,输入 recentFps 全量) */
    private fun computeStats(fpsList: List<Float>): FpsStats {
        val snap = synchronized(fpsList) { fpsList.toList() }
        if (snap.isEmpty()) return FpsStats()
        return FpsStats(
            avgFps = snap.average().toFloat(),
            low1Fps = percentileMean(snap.sorted(), 0.01),
            low01Fps = percentileMean(snap.sorted(), 0.001),
            sampleCount = snap.size,
        )
    }

    /** 把元数据写成 session.json(手写 JSON,见 SessionMeta.toJson) */
    private fun writeMeta(meta: SessionMeta) {
        try {
            val dir = sessionDir ?: return
            val f = File(dir, "session.json")
            f.writeText(meta.toJson(), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "writeMeta failed", e)
        }
    }
}

/** 会话帧率统计(平均/1%Low/0.1%Low);-1 或 0 表示无有效数据 */
data class FpsStats(
    val avgFps: Float = -1f,
    val low1Fps: Float = -1f,
    val low01Fps: Float = -1f,
    val sampleCount: Int = 0,
)

/** 会话元数据:一次会话的静态信息 + stop 时写入的统计结果 */
data class SessionMeta(
    /** 开始时间(epoch ms) */
    val startTime: Long,
    /** 结束时间(epoch ms);stop 时写入,记录中为 0 */
    val endTime: Long = 0,
    /** 时长 ms;stop 时写入 */
    val durationMs: Long = 0,
    /** 目标(前台)应用包名 */
    val targetPkg: String = "",
    /** 目标(前台)应用名 */
    val targetLabel: String = "",
    /**
     * 门控目标应用包名:仅当该应用在前台时才记录(否则暂停)。
     * 空/缺省 = 不门控(跟随前台,原行为)。
     */
    val watchPkg: String = "",
    /** 采样间隔 ms */
    val intervalMs: Long = 0,
    /** 帧率统计 */
    val fpsStats: FpsStats = FpsStats(),
    /** 平均功耗 mW(会话内 powerMw>0 的采样均值) */
    val avgPowerMw: Double = -1.0,
    /** 平均电池温度 °C */
    val avgBatteryTemp: Double = -1.0,
    /** 平均核心温度 °C */
    val avgCpuTemp: Double = -1.0,
    /** 实际落盘的 CSV 行数，独立于有效 FPS 样本数。旧会话缺失时为 -1。 */
    val recordedSamples: Int = -1,
) {
    /** 序列化为 JSON 字符串(手写拼接,字段少、结构固定,避免引入 JSON 库) */
    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"startTime\":$startTime,")
        sb.append("\"endTime\":$endTime,")
        sb.append("\"durationMs\":$durationMs,")
        sb.append("\"targetPkg\":\"").append(escape(targetPkg)).append("\",")
        sb.append("\"targetLabel\":\"").append(escape(targetLabel)).append("\",")
        sb.append("\"watchPkg\":\"").append(escape(watchPkg)).append("\",")
        sb.append("\"intervalMs\":$intervalMs,")
        sb.append("\"avgFps\":").append(fpsStats.avgFps).append(",")
        sb.append("\"low1Fps\":").append(fpsStats.low1Fps).append(",")
        sb.append("\"low01Fps\":").append(fpsStats.low01Fps).append(",")
        sb.append("\"sampleCount\":").append(fpsStats.sampleCount).append(",")
        sb.append("\"recordedSamples\":").append(recordedSamples).append(",")
        sb.append("\"avgPowerMw\":").append(avgPowerMw).append(",")
        sb.append("\"avgBatteryTemp\":").append(avgBatteryTemp).append(",")
        sb.append("\"avgCpuTemp\":").append(avgCpuTemp)
        sb.append("}")
        return sb.toString()
    }

    /** JSON 字符串值转义(反斜杠/引号/换行) */
    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
