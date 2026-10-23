package yy.daniel.raidian.monitor

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    private val mutex = Mutex()
    private var previousCpu = emptyMap<String, MetricsParser.CpuTimes>()
    private var cached: Sample? = null
    private var sampledAt = 0L
    private var lastPrivilege = false
    private var knownCoreCount = 0

    suspend fun sample(elapsedMs: Long): Sample = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = SystemClock.elapsedRealtime()
            val privileged = exec.shizuku?.available() == true
            cached?.takeIf { now - sampledAt < 400 && privileged == lastPrivilege }?.let {
                return@withLock it.copy(elapsedMs = elapsedMs)
            }
            if (privileged != lastPrivilege) previousCpu = emptyMap()
            lastPrivilege = privileged
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
            if (profile.isXring || profile.isColorOs || thermals.isEmpty()) {
                val dump = safe("") { exec.execute("dumpsys -t 1 thermalservice 2>/dev/null") }
                val hal = MetricsParser.thermalHal(dump)
                if (hal.isNotEmpty()) thermals = hal
            }
            fun maxTemp(vararg kind: String) = thermals.filter { it.kind in kind }.maxOfOrNull { it.celsius } ?: -1f
            val thermalStatus = try {
                context.getSystemService(PowerManager::class.java).currentThermalStatus
            } catch (_: Exception) { -1 }
            val batt = battery()
            val front = safe("" to "") { appState.readFrontApp() }
            val fps = safe(AppStateReader.FpsState.NONE) { appState.readFpsFor(front.first) }
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
                sampleDurationMs = SystemClock.elapsedRealtime() - start, privileged = privileged,
            ).also { cached = it; sampledAt = SystemClock.elapsedRealtime() }
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
