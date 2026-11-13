package yy.daniel.raidian.monitor

import java.io.File

/**
 * 在 runner 内直接读文件，避免每核心 fork cat。
 * 拓扑来源对照 Scene CpuFrequencyUtils.related_cpus、FrameX CpuMonitor 的 policy 探测。
 * CPUFreq 为 kHz；devfreq/Adreno gpuclk 为 Hz，不能根据数值大小猜单位。
 * 无 canRead/exists 预检：shell 域下预检与实际读取权限可能不同。
 */
class HardwareSnapshotReader {
    private fun read(path: String): String = try { File(path).readText().trim() } catch (_: Exception) { "" }

    // 106/38 个温区逐个读文件在 2Hz 采样下没有意义：温度是慢变量。
    // 运行在 runner 进程里也复用这一份缓存，减少每秒上百次 sysfs open。
    @Volatile private var thermalCache: String? = null
    @Volatile private var thermalCacheAt = 0L

    fun readSnapshot(): String = buildString {
        fun block(key: String, value: String) { append("@@").append(key).append('\n').append(value).append('\n') }
        val present = read("/sys/devices/system/cpu/present")
        val ids = MetricsParser.cpuIds(present).ifEmpty {
            File("/sys/devices/system/cpu").listFiles()?.mapNotNull {
                Regex("cpu(\\d+)").matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull()
            }.orEmpty().sorted()
        }
        block("PRESENT", ids.joinToString(" "))
        block("ONLINE", read("/sys/devices/system/cpu/online"))
        for (id in ids) {
            val root = "/sys/devices/system/cpu/cpu$id"
            block("CORE$id", listOf("scaling_cur_freq", "cpuinfo_max_freq", "scaling_max_freq", "related_cpus")
                .joinToString("|") { read("$root/cpufreq/$it") } + "|" + read("$root/cpu_capacity"))
        }
        block("STAT", read("/proc/stat").lineSequence().takeWhile { it.startsWith("cpu") }.joinToString("\n"))
        block("MEM", read("/proc/meminfo"))
        block("NET", read("/proc/net/dev"))
        // Scene GpuUtils 的 Adreno / Mali devfreq 路径；每次只读一次 reset-on-read gpubusy。
        block("GPU_HZ", sequenceOf("/sys/class/devfreq/gpufreq/cur_freq", "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
            "/sys/class/kgsl/kgsl-3d0/gpuclk", "/sys/kernel/gpu/gpu_clock")
            .map(::read).firstOrNull { it.toLongOrNull()?.let { v -> v > 0 } == true }.orEmpty())
        block("GPU_BUSY", read("/sys/class/kgsl/kgsl-3d0/gpubusy"))
        block("GPU_PERCENT", sequenceOf("/sys/class/devfreq/gpufreq/mali_ondemand/utilisation",
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load")
            .map(::read).firstOrNull { it.isNotEmpty() }.orEmpty())
        block("THERMAL", readThermals())
    }

    fun readThermals(): String {
        val now = System.nanoTime() / 1_000_000L
        thermalCache?.takeIf { now - thermalCacheAt < 2_000L }?.let { return it }
        val value = buildString {
            val zones = File("/sys/class/thermal").listFiles()?.filter { it.name.matches(Regex("thermal_zone\\d+")) }.orEmpty()
            for (zone in zones) {
                val name = read("${zone.path}/type")
                val temp = read("${zone.path}/temp")
                if (name.isNotBlank() && temp.isNotBlank()) append(name).append(' ').append(temp).append('\n')
            }
        }
        thermalCache = value
        thermalCacheAt = now
        return value
    }
}
