package yy.daniel.raidian.monitor

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

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
    @Volatile private var job: kotlinx.coroutines.Job? = null
    @Volatile private var stopFlag = false

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
    fun start(targetPkg: String, targetLabel: String, watchPkg: String? = null) {
        if (_running.value) return
        stopFlag = false
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
        startElapsed = time
        lastSampleAt = time
        // 目录名带秒级时间戳,天然保证唯一且可按名排序(新→旧)
        val dirName = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(time))
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
            w.write(Sample(elapsedMs = 0).toCsvHeader())
            w.newLine()
            csvWriter = w
        } catch (e: Exception) {
            Log.e(TAG, "start: cannot open csv", e)
            return
        }
        writeMeta(meta)
        _running.value = true
        job = scope.launch { loop() }
    }

    /** 停止会话:停循环、关文件、把统计均值写回 session.json */
    fun stop() {
        if (!_running.value) return
        stopFlag = true
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
                durationMs = end - startElapsed,
                fpsStats = stats,
                avgPowerMw = avgPower,
                avgBatteryTemp = avgBatt,
                avgCpuTemp = avgCpu,
            ))
        }
        _running.value = false
    }

    /** 采样主循环:按 intervalMs 节奏采样 → 统计 → 写 CSV;每 50ms 醒一次检查节奏 */
    private suspend fun loop() {
        // 门控时每次采样前后台检测的间隔(前台识别有 shell 开销,不用 50ms 查)
        val gateCheckEvery = intervalMs.coerceAtLeast(200L)
        var lastGateCheck = 0L
        while (!stopFlag) {
            val now = System.currentTimeMillis()

            // ---- 门控检测:指定了目标应用时,前台不在目标 → 暂停 ----
            val wp = gatePkg
            if (wp != null) {
                if (now - lastGateCheck >= gateCheckEvery) {
                    lastGateCheck = now
                    val (front, _) = try {
                        appState.readFrontApp()
                    } catch (e: Exception) {
                        "" to ""
                    }
                    val inTarget = front == wp
                    if (!inTarget) {
                        // 目标不在前台:暂停采样(不写 CSV),等下一轮再查
                        _paused.value = true
                        lastSampleAt = 0L // 复位节奏:恢复后立即采一点
                        kotlinx.coroutines.delay(gateCheckEvery)
                        continue
                    } else if (_paused.value) {
                        _paused.value = false
                    }
                }
            }

            // 节奏:距上次有效采样 ≥ intervalMs 才采;暂停复位过节奏则立即采
            val sinceLast = now - lastSampleAt
            if (lastSampleAt == 0L || sinceLast >= intervalMs) {
                lastSampleAt = now
                val elapsed = now - startElapsed
                try {
                    val s = sampler.sample(elapsed)
                    // FPS 进统计(仅有效帧)
                    if (s.fps > 0) {
                        synchronized(recentFps) {
                            recentFps.add(s.fps)
                            if (recentFps.size > 60 * 60 * 2) recentFps.removeAt(0) // 上限:2 小时(500ms×14400 点)
                        }
                    }
                    synchronized(statLock) {
                        if (s.powerMw > 0) { powerSum += s.powerMw; powerCount++ }
                        if (s.batteryTemp > 0) { battTempSum += s.batteryTemp; battTempCount++ }
                        if (s.maxCpuTemp > 0) { cpuTempSum += s.maxCpuTemp; cpuTempCount++ }
                    }
                    appendCsv(s)
                    updateStats()
                } catch (e: Exception) {
                    // 单次采样失败不中断记录
                    Log.w(TAG, "sample failed", e)
                }
            }
            kotlinx.coroutines.delay(50)
        }
    }

    /** 追加一行 CSV;synchronized(w) + 每行 flush,崩溃/杀进程也不丢已采数据 */
    private fun appendCsv(s: Sample) {
        try {
            val w = csvWriter ?: return
            synchronized(w) {
                w.write(s.toCsvRow())
                w.newLine()
                w.flush() // 保证崩溃时数据不丢
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
        sb.append("\"avgPowerMw\":").append(avgPowerMw).append(",")
        sb.append("\"avgBatteryTemp\":").append(avgBatteryTemp).append(",")
        sb.append("\"avgCpuTemp\":").append(avgCpuTemp)
        sb.append("}")
        return sb.toString()
    }

    /** JSON 字符串值转义(反斜杠/引号/换行) */
    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
