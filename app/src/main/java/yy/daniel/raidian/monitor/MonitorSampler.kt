package yy.daniel.raidian.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 同一采样器供记录、界面和浮窗使用。锁保护差分计数器；短时请求共享同一个快照，
 * 避免重复读取会清零的 Adreno gpubusy，以及并发破坏 CPU 差分。
 * 数据源对照 Scene/FrameX；O1 的 thermal sysfs 被拒时取当前 Thermal HAL。
 */
class MonitorSampler(
    private val context: Context,
    private val exec: CommandExecutor,
    private val appState: AppStateReader,
) {
    private companion object {
        /**
         * 慢字段（前台 / FPS / 温度 / 电池）的刷新间隔。
         * 这些字段包含 shell/dumpsys，放在主采样路径上会把 500ms 节奏拖到 1s 以上；
         * 现在由后台协程刷新，主采样只读缓存，因此主路径只做一次硬件快照。
         */
        const val SLOW_INTERVAL_MS = 1_000L
        /** 灭屏后没有画面变化，FPS/前台/温度都可以更慢；这是长时间记录最省电的区间。 */
        const val SLOW_INTERVAL_SCREEN_OFF_MS = 3_000L
        /** 温度：O1 只能读 Thermal HAL，温度变化慢，2s 一次不会影响判读 */
        const val THERMAL_TTL_MS = 2_000L
        /** 温度读取失败/无权限后的重试间隔，避免每轮都起一个 dumpsys 进程 */
        const val THERMAL_RETRY_MS = 30_000L
    }

    private val mutex = Mutex()
    private var previousCpu = emptyMap<String, MetricsParser.CpuTimes>()
    private var cached: Sample? = null
    private var sampledAt = 0L
    private var lastIdentity = ""
    private var knownCoreCount = 0

    // 慢字段缓存：由 slowScope 后台刷新，主采样路径只读，绝不在这里等 shell。
    @Volatile private var frontCache: Pair<String, String>? = null
    @Volatile private var fpsCache = AppStateReader.FpsState.NONE
    @Volatile private var fpsCachePkg = ""
    @Volatile private var thermalHalCache: List<ThermalReading>? = null
    @Volatile private var thermalHalCacheAt = 0L
    @Volatile private var thermalHalRetryAt = 0L
    @Volatile private var batteryCache: Battery? = null

    private val slowScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slowRefreshRunning = AtomicBoolean(false)
    @Volatile private var nextSlowAt = 0L

    /** 上次采样里 sysfs 温区是否为空，后台刷新据此决定要不要再读 Thermal HAL。 */
    @Volatile private var needThermalHal = true

    suspend fun sample(elapsedMs: Long, maxCacheAgeMs: Long = 400L): Sample = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = SystemClock.elapsedRealtime()
            val identity = exec.identity
            val privileged = exec.privileged
            cached?.takeIf { now - sampledAt < maxCacheAgeMs && identity == lastIdentity }?.let {
                return@withLock it.copy(elapsedMs = elapsedMs)
            }
            if (identity != lastIdentity) previousCpu = emptyMap()
            lastIdentity = identity
            val start = SystemClock.elapsedRealtime()
            val blocks = MetricsParser.blocks(safe("") { exec.readHardwareSnapshot() })
            val ids = MetricsParser.cpuIds(blocks["PRESENT"].orEmpty())
            knownCoreCount = maxOf(knownCoreCount, (ids.maxOrNull() ?: -1) + 1)
            val n = knownCoreCount
            val onlineText = blocks["ONLINE"].orEmpty()
            val onlineIds = MetricsParser.cpuIds(onlineText)
            val online = List(n) { if (onlineText.isBlank()) -1 else if (it in onlineIds) 1 else 0 }
            val cores = List(n) { blocks["CORE$it"].orEmpty().split('|') }
            fun freq(field: Int) = List(n) { MetricsParser.khzToMhz(cores[it].getOrElse(field) { "" }) }
            val frequencies = freq(0).mapIndexed { i, v -> if (online[i] == 0) -1 else v }
            val maxFreq = freq(1)
            val limits = freq(2)
            val policies = List(n) { MetricsParser.cpuIds(cores[it].getOrElse(3) { "" }).minOrNull() ?: -1 }
            val groups = policies.filter { it >= 0 }.distinct().sortedBy { policy ->
                policies.indices.filter { policies[it] == policy }.maxOfOrNull { maxFreq[it] } ?: -1
            }
            fun groupFreq(group: Int) = frequencies.indices.filter { policies[it] == group }.map { frequencies[it] }.maxOrNull() ?: -1
            // 老 CSV/浮窗继续提供 L/M/B；完整 policy 和每核数据另存，四簇不丢失。
            val little = groups.firstOrNull()?.let(::groupFreq) ?: -1
            val big = groups.takeIf { it.size > 1 }?.last()?.let(::groupFreq) ?: -1
            val middle = groups.drop(1).dropLast(1).maxOfOrNull(::groupFreq) ?: -1
            val cpu = MetricsParser.cpuTimes(blocks["STAT"].orEmpty())
            val cpuLoad = MetricsParser.cpuLoad(previousCpu["cpu"], cpu["cpu"])
            val coreLoad = List(n) { if (online[it] == 0) -1f else MetricsParser.cpuLoad(previousCpu["cpu$it"], cpu["cpu$it"]) }
            previousCpu = cpu

            val mem = blocks["MEM"].orEmpty().lines().mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                parts.getOrNull(1)?.toLongOrNull()?.let { parts[0].removeSuffix(":") to it / 1024 }
            }.toMap()
            val total = mem["MemTotal"] ?: -1
            val available = mem["MemAvailable"] ?: -1
            val net = readNetwork(blocks["NET"].orEmpty())
            val profile = DeviceProfile.detect()
            var thermals = MetricsParser.thermalSysfs(blocks["THERMAL"].orEmpty())
            needThermalHal = profile.isXring || profile.isColorOs || thermals.isEmpty()
            if (needThermalHal) thermalHalCache?.let { thermals = it }
            fun maxTemp(vararg kind: String) = thermals.filter { it.kind in kind }.maxOfOrNull { it.celsius } ?: -1f
            val thermalStatus = try {
                context.getSystemService(PowerManager::class.java).currentThermalStatus
            } catch (_: Exception) { -1 }

            // 慢字段由后台协程刷新；主路径只读上一份缓存，主采样耗时稳定在几十毫秒。
            maybeRefreshSlow(now)
            val front: Pair<String, String> = frontCache ?: ("" to "")
            val fps = if (front.first.isNotEmpty() && front.first == fpsCachePkg) fpsCache
                else AppStateReader.FpsState.NONE
            val batt = batteryCache ?: Battery(-1f, Int.MIN_VALUE, -1, -1, -1, -1)
            Sample(
                elapsedMs = elapsedMs, fps = fps.fps, frameTimeMs = fps.frameTimeMs,
                cpuL = little, cpuM = middle, cpuB = big, cpuLoad = cpuLoad,
                perCoreFreqMhz = frequencies, perCoreLoad = coreLoad, perCoreOnline = online,
                perCoreTemp = MetricsParser.coreTemperatures(thermals, n, profile.isXring),
                perCoreMaxMhz = maxFreq, perCoreLimitMhz = limits, cpuPolicyIds = policies,
                gpuFreq = MetricsParser.hzToMhz(blocks["GPU_HZ"].orEmpty()),
                gpuLoad = MetricsParser.gpuLoad(blocks["GPU_BUSY"].orEmpty(), blocks["GPU_PERCENT"].orEmpty()),
                thermalReadings = thermals, maxCpuTemp = maxTemp("cpu"), maxSocTemp = maxTemp("soc", "gpu", "npu"),
                maxOtherTemp = maxTemp("skin", "other"), gpuTemp = maxTemp("gpu"), npuTemp = maxTemp("npu"),
                skinTemp = maxTemp("skin"), thermalStatus = thermalStatus,
                batteryTemp = batt.temp, currentMa = MetricsParser.currentMa(batt.rawUa), voltageMv = batt.voltage,
                powerMw = MetricsParser.powerMw(batt.rawUa, batt.voltage), batteryLevel = batt.level,
                chargeState = batt.plugged, chargeCounterMah = batt.counter,
                totalMemMb = total, availableMemMb = available,
                usedMemMb = if (total >= 0 && available in 0..total) total - available else -1,
                swapUsedMb = if (mem.containsKey("SwapTotal") && mem.containsKey("SwapFree"))
                    (mem.getValue("SwapTotal") - mem.getValue("SwapFree")).coerceAtLeast(0) else -1,
                frontPkg = front.first, frontApp = front.second,
                wifiRxBytes = net[0], wifiTxBytes = net[1], mobileRxBytes = net[2], mobileTxBytes = net[3],
                sampleDurationMs = SystemClock.elapsedRealtime() - start, privileged = privileged && exec.privileged,
            ).also {
                cached = it
                sampledAt = SystemClock.elapsedRealtime()
            }
        }
    }

    /**
     * 后台刷新慢字段：前台应用、FPS、电池、Thermal HAL。
     *
     * 这些读取都可能走采集服务/ dumpsys，单次 100~500ms；如果放在 500ms 的采样主路径里，
     * CSV 间隔会被拖到 1s 以上，而且前台/FPS/HAL 会在同一拍集中执行，造成明显卡顿。
     * 现在主路径只做硬件快照，慢字段在后台串行刷新；同一时刻最多一个刷新协程。
     */
    private fun maybeRefreshSlow(now: Long) {
        if (now < nextSlowAt) return
        if (!slowRefreshRunning.compareAndSet(false, true)) return
        val interactive = try {
            context.getSystemService(PowerManager::class.java).isInteractive
        } catch (_: Exception) {
            true
        }
        nextSlowAt = now + if (interactive) SLOW_INTERVAL_MS else SLOW_INTERVAL_SCREEN_OFF_MS
        slowScope.launch {
            try {
                val newFront = safe("" to "") { appState.readFrontApp() }
                frontCache = newFront
                if (newFront.first != fpsCachePkg) {
                    // 切应用后旧应用的 FPS 不再有意义，先清空，等本轮读到新值。
                    fpsCache = AppStateReader.FpsState.NONE
                    fpsCachePkg = newFront.first
                }
                val newFps = if (newFront.first.isEmpty()) AppStateReader.FpsState.NONE
                    else safe(AppStateReader.FpsState.NONE) { appState.readFpsFor(newFront.first) }
                fpsCache = newFps
                fpsCachePkg = newFront.first

                batteryCache = battery()

                if (needThermalHal) {
                    val nowT = SystemClock.elapsedRealtime()
                    if (nowT - thermalHalCacheAt >= THERMAL_TTL_MS && nowT >= thermalHalRetryAt) {
                        // 只取 HAL 当前温度段。完整 dumpsys 还带冷却设备/阈值（约 14KB），
                        // 而采样只需要 2.5KB 的 Temperature 行；少传少解析。
                        val dump = safe("") {
                            exec.execute(
                                "dumpsys -t 1 thermalservice 2>/dev/null | " +
                                    "sed -n -e '/HAL Ready:/p' " +
                                    "-e '/Current temperatures from HAL:/,/Current cooling devices/p'"
                            )
                        }
                        val hal = MetricsParser.thermalHal(dump)
                        if (hal.isNotEmpty()) {
                            thermalHalCache = hal
                            thermalHalCacheAt = nowT
                            thermalHalRetryAt = 0L
                        } else {
                            thermalHalRetryAt = nowT + THERMAL_RETRY_MS
                        }
                    }
                }
            } finally {
                slowRefreshRunning.set(false)
            }
        }
    }

    private suspend fun <T> safe(fallback: T, block: suspend () -> T): T = try { block() }
        catch (e: CancellationException) { throw e } catch (_: Exception) { fallback }

    private data class Battery(val temp: Float, val rawUa: Int, val voltage: Int, val level: Int, val plugged: Int, val counter: Int)
    private fun battery(): Battery {
        // Scene/Cumulus 使用的标准 Android API；CURRENT_NOW 始终 µA，不按数值大小猜测。
        val intent = try { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (_: Exception) { null }
        val bm = context.getSystemService(BatteryManager::class.java)
        fun property(id: Int) = try { bm.getIntProperty(id) } catch (_: Exception) { Int.MIN_VALUE }
        val rawTemp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val rawLevel = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val counter = property(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        return Battery(
            if (rawTemp == Int.MIN_VALUE) -1f else rawTemp / 10f,
            property(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1,
            if (rawLevel >= 0 && scale > 0) rawLevel * 100 / scale else -1,
            if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) 3 else plugged,
            if (counter >= 0) counter / 1000 else -1,
        )
    }

    private fun readNetwork(text: String): List<Long> {
        if (!text.contains("Inter-")) {
            val mobileRx = TrafficStats.getMobileRxBytes()
            val mobileTx = TrafficStats.getMobileTxBytes()
            // 没有接口明细时，不把总流量伪装成 Wi-Fi 流量。
            return listOf(-1, -1, mobileRx, mobileTx)
        }
        val totals = LongArray(4)
        text.lines().filter { it.contains(':') }.forEach { line ->
            val name = line.substringBefore(':').trim()
            val fields = line.substringAfter(':').trim().split(Regex("\\s+"))
            val offset = when { name.startsWith("wlan") || name.startsWith("wifi") -> 0
                name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("wwan") -> 2
                else -> return@forEach }
            val rx = fields.getOrNull(0)?.toLongOrNull() ?: return@forEach
            val tx = fields.getOrNull(8)?.toLongOrNull() ?: return@forEach
            totals[offset] += rx; totals[offset + 1] += tx
        }
        return totals.toList()
    }
}
