package yy.daniel.raidian.monitor

import kotlinx.serialization.Serializable

@Serializable
data class ThermalReading(val name: String, val celsius: Float, val kind: String, val source: String)

/** Pure parsers: Scene sysfs conventions and FrameX ThermalServiceParser / CpuStatParser. */
object MetricsParser {
    fun cpuIds(text: String): List<Int> = text.trim().split(Regex("[,\\s]+"))
        .flatMap { token ->
            val range = token.split('-')
            val start = range.firstOrNull()?.toIntOrNull()
            val end = range.getOrNull(1)?.toIntOrNull() ?: start
            if (start != null && end != null && start in 0..4095 && end in start..4095) (start..end).toList() else emptyList()
        }.distinct().sorted()

    fun blocks(text: String): Map<String, String> {
        val result = linkedMapOf<String, StringBuilder>()
        var key: String? = null
        text.lineSequence().forEach { line ->
            if (line.startsWith("@@")) { key = line.drop(2); result[key!!] = StringBuilder() }
            else key?.let { result[it]?.append(line)?.append('\n') }
        }
        return result.mapValues { it.value.toString().trim() }
    }

    fun khzToMhz(raw: String): Int = raw.trim().toLongOrNull()?.takeIf { it in 1..20_000_000 }
        ?.div(1000)?.toInt() ?: -1
    fun hzToMhz(raw: String): Int = raw.trim().toLongOrNull()?.takeIf { it in 1_000_000..20_000_000_000 }
        ?.div(1_000_000)?.toInt() ?: -1
    fun currentMa(rawUa: Int): Int = if (rawUa == Int.MIN_VALUE) -1 else (kotlin.math.abs(rawUa.toLong()) / 1000).toInt()
    fun powerMw(rawUa: Int, voltageMv: Int): Long =
        if (rawUa == Int.MIN_VALUE || voltageMv <= 0) -1 else kotlin.math.abs(rawUa.toLong()) * voltageMv / 1_000_000

    data class CpuTimes(val total: Long, val idle: Long)
    fun cpuTimes(text: String): Map<String, CpuTimes> = text.lineSequence().mapNotNull { line ->
        val p = line.trim().split(Regex("\\s+"))
        if (p.size < 5 || !p[0].matches(Regex("cpu\\d*"))) return@mapNotNull null
        val fields = p.drop(1).take(8).map { it.toLongOrNull() ?: return@mapNotNull null }
        if (fields.any { it < 0 }) return@mapNotNull null
        p[0] to CpuTimes(fields.sum(), fields[3] + (fields.getOrNull(4) ?: 0))
    }.toMap()
    fun cpuLoad(before: CpuTimes?, after: CpuTimes?): Float {
        if (before == null || after == null) return -1f
        val total = after.total - before.total
        val idle = after.idle - before.idle
        return if (total <= 0 || idle !in 0..total) -1f else ((total - idle).toDouble() / total * 100).toFloat()
    }

    fun gpuLoad(busyText: String, percentText: String): Float {
        val p = busyText.trim().split(Regex("\\s+"))
        val busy = p.getOrNull(0)?.toLongOrNull()
        val total = p.getOrNull(1)?.toLongOrNull()
        if (busy != null && total != null && total >= 0 && busy in 0..total) {
            return if (total == 0L) 0f else (busy.toDouble() / total * 100).toFloat()
        }
        return percentText.trim().removeSuffix("%").toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..100f } ?: -1f
    }

    private fun kind(name: String, type: Int? = null): String {
        when (type) { 0 -> return "cpu"; 1 -> return "gpu"; 2 -> return "battery"; 3 -> return "skin"; 9 -> return "npu" }
        val n = name.lowercase()
        return when {
            n.contains("gpu") -> "gpu"
            n.contains("npu") || n.startsWith("nsp") -> "npu"
            n.startsWith("cpu") || n.contains("cluster") -> "cpu"
            n.contains("batt") -> "battery"
            n.contains("skin") || n.contains("shell") || n.contains("quiet") -> "skin"
            n.contains("soc") || n.startsWith("apc") || n.startsWith("aoss") || n.startsWith("tsens") ||
                n.startsWith("qmx") || n.startsWith("ddr") || n.startsWith("mdmss") || n.startsWith("media") -> "soc"
            else -> "other"
        }
    }
    private fun valid(name: String, c: Float) = !name.contains("trip", true) && c.isFinite() && c in 0f..150f

    fun thermalSysfs(text: String): List<ThermalReading> = text.lines().mapNotNull { line ->
        val name = line.substringBeforeLast(' ', "")
        val value = line.substringAfterLast(' ').trim().toFloatOrNull()?.div(1000) ?: return@mapNotNull null
        if (name.isBlank() || !valid(name, value)) null else ThermalReading(name, value, kind(name), "sysfs")
    }

    fun thermalHal(dump: String): List<ThermalReading> {
        // Cached temperatures 是上次阈值事件值，可比当前温度高几十度；不能混入实时数据。
        if (dump.contains("HAL Ready: false", true)) return emptyList()
        if (!dump.contains("Current temperatures from HAL:")) return emptyList()
        val section = dump.substringAfter("Current temperatures from HAL:")
            .substringBefore("Current cooling devices").substringBefore("Temperature static thresholds")
        return Regex("Temperature\\{([^}]*)\\}").findAll(section).mapNotNull { match ->
            val fields = match.groupValues[1].split(',').associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
            val name = fields["mName"] ?: return@mapNotNull null
            val c = fields["mValue"]?.toFloatOrNull() ?: return@mapNotNull null // HAL 契约为摄氏度。
            if (!valid(name, c)) null else ThermalReading(name, c, kind(name, fields["mType"]?.toIntOrNull()), "thermal_hal")
        }.distinctBy { it.name }.toList()
    }

    fun coreTemperatures(sensors: List<ThermalReading>, count: Int, xring: Boolean): List<Float> = List(count) { core ->
        // O1 HAL 的 cpu-3/5/6/8/9 有明确核号；ccw、top、crg 是共享温区，绝不复制给其它核。
        if (xring) sensors.firstOrNull { it.name == "cpu-$core" }?.celsius ?: -1f else -1f
    }
}
