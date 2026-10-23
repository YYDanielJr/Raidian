package yy.daniel.raidian.monitor

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * 一次完整采样的数据快照 —— 贯穿全链路(采集 → 悬浮窗 → 实时页 → CSV 落盘)的
 * 唯一数据载体。
 *
 * 单位约定(非常重要,改动采集前先读这里,并对照 references/scene-alternatives 里
 * vtools 的 ElectricityUnit 等实现):
 *  - 频率:MHz
 *  - 温度:摄氏度(上游源可能是 0.1°C 或 m°C,统一在采集处换算成 °C 再入库)
 *  - 电流:mA(上游 BatteryManager 返回 µA,换算成 mA)
 *  - 电压:mV
 *  - 功率:mW(= mA × mV / 1000)
 *  - 网络字节:累计值,单位 B(不是增量)
 *
 * 约定:凡是"当前无有效值"的数值字段一律用 -1 表示,字符串字段用空串表示。
 * CSV 表头与数据行保持一致；消费方按列名定位，以兼容历史会话及不同核心数量。
 */
@Serializable
data class Sample(
    /** 距会话开始的时间,毫秒(会话由 SessionRecorder.start 建立起点) */
    val elapsedMs: Long,
    /** 帧率,每秒帧数;无有效值时 -1(桌面等无渲染层场景会长期是 -1) */
    val fps: Float = -1f,
    /** 单帧耗时,毫秒(= 1000/fps);无有效值时 -1 */
    val frameTimeMs: Float = -1f,

    /** 最低频率簇 MHz；完整拓扑保存在 cpuPolicyIds。 */
    val cpuL: Int = -1,
    /** 中核簇频率 MHz */
    val cpuM: Int = -1,
    /** 大核簇频率 MHz */
    val cpuB: Int = -1,
    /** 整机 CPU 占用率 0-100(由 /proc/stat 前后两次 jiffies 差值算出) */
    val cpuLoad: Float = -1f,

    /**
     * 每个逻辑核的当前频率(MHz,下标 = 逻辑核号)。
     * 按设备 present 拓扑动态分配，读不到或离线的核为 -1。
     * 阶段四后新增:用于详情页"每核频率曲线"(见 Sample.toCsvHeader)。
     */
    val perCoreFreqMhz: List<Int> = emptyList(),

    /** GPU 频率 MHz;小米 13 Ultra 上 kgsl/gpu sysfs 对 shell 身份被 SELinux 拒,恒为 -1 */
    val gpuFreq: Int = -1,
    /** GPU 占用率 0-100(高通 kgsl gpubusy 的 busy/total 比值;shell 身份可读) */
    val gpuLoad: Float = -1f,

    /** 电池温度，摄氏度（ACTION_BATTERY_CHANGED，0.1°C 已 /10）。 */
    val batteryTemp: Float = -1f,
    /** 各 thermal zone 中 cpu* 类的最高温度,摄氏度 */
    val maxCpuTemp: Float = -1f,
    /** 主板/外壳等最高温度,摄氏度 */
    val maxOtherTemp: Float = -1f,
    /** 电池温度之外最热点(通常 SoC,来自 soc* 类 zone),摄氏度 */
    val maxSocTemp: Float = -1f,

    /** 电流幅值 mA（BatteryManager.CURRENT_NOW 的绝对值，µA 已 /1000）。 */
    val currentMa: Int = -1,
    /** 电压 mV（ACTION_BATTERY_CHANGED）。 */
    val voltageMv: Int = -1,
    /** 电池端净功率幅值 mW；充电时并非整机功耗。 */
    val powerMw: Long = -1,
    /** 电池百分比 0-100 */
    val batteryLevel: Int = -1,
    /** 充电状态:0 未充 1 交流 2 usb 3 无线 */
    val chargeState: Int = -1,

    /** 已用内存 MB(/proc/meminfo Total − Available,kB 已 /1024) */
    val usedMemMb: Long = -1,
    /** 总内存 MB */
    val totalMemMb: Long = -1,

    /** 前台应用包名 */
    val frontPkg: String = "",
    /** 前台应用名(取不到时退化为包名) */
    val frontApp: String = "",

    /** 网络:WiFi 收/发字节;移动收/发字节(累计,单位 B,来自 /proc/net/dev) */
    val wifiRxBytes: Long = -1,
    val wifiTxBytes: Long = -1,
    val mobileRxBytes: Long = -1,
    val mobileTxBytes: Long = -1,
    val perCoreLoad: List<Float> = emptyList(),
    /** 1 在线，0 离线，-1 未知。 */
    val perCoreOnline: List<Int> = emptyList(),
    val perCoreTemp: List<Float> = emptyList(),
    val perCoreMaxMhz: List<Int> = emptyList(),
    val perCoreLimitMhz: List<Int> = emptyList(),
    /** 每核所属 policy 的最低核号。保留 O1 的全部四簇。 */
    val cpuPolicyIds: List<Int> = emptyList(),
    val thermalReadings: List<ThermalReading> = emptyList(),
    val gpuTemp: Float = -1f,
    val npuTemp: Float = -1f,
    val skinTemp: Float = -1f,
    val thermalStatus: Int = -1,
    val availableMemMb: Long = -1,
    val swapUsedMb: Long = -1,
    val chargeCounterMah: Int = -1,
    val sampleDurationMs: Long = -1,
    val privileged: Boolean = false,
) {
    /**
     * 生成 CSV 表头。
     * 注意:字段顺序必须与 [toCsvRow] 完全一致,并与消费方
     * (SessionStore.readSamples → MainViewModel 的列号下标)保持同步。
     *
     * 每核频率列按会话的真实核数展开；
     * 消费方按表头前缀 "cpuN_freq_mhz" 定位,而不是硬编码列号,以兼容旧会话。
     */
    fun toCsvHeader(coreCount: Int = perCoreFreqMhz.size): String = buildList {
        addAll(listOf("elapsed_ms", "fps", "frame_time_ms",
            "cpu_l_mhz", "cpu_m_mhz", "cpu_b_mhz", "cpu_load_pct"))
        for (i in 0 until coreCount) add("cpu${i}_freq_mhz")
        addAll(listOf("gpu_mhz", "gpu_load_pct",
            "battery_temp_c", "max_cpu_temp_c", "max_soc_temp_c", "max_other_temp_c",
            "current_ma", "voltage_mv", "power_mw", "battery_level_pct", "charge_state",
            "used_mem_mb", "total_mem_mb",
            "front_pkg", "front_app",
            "wifi_rx_b", "wifi_tx_b", "mobile_rx_b", "mobile_tx_b"))
        for (i in 0 until coreCount) addAll(listOf("cpu${i}_load_pct", "cpu${i}_online", "cpu${i}_temp_c",
            "cpu${i}_max_mhz", "cpu${i}_limit_mhz", "cpu${i}_policy"))
        addAll(listOf("gpu_temp_c", "npu_temp_c", "skin_temp_c", "thermal_status", "available_mem_mb",
            "swap_used_mb", "charge_counter_mah", "sample_duration_ms", "privileged", "thermal_json"))
    }.joinToString(",")

    /** 生成一行 CSV 数据;字符串字段(包名/应用名)做引号转义以兼容逗号 */
    fun toCsvRow(coreCount: Int = perCoreFreqMhz.size): String = buildList {
        addAll(listOf("$elapsedMs", fmt(fps), fmt(frameTimeMs),
            "$cpuL", "$cpuM", "$cpuB", fmt(cpuLoad)))
        for (i in 0 until coreCount) add("${perCoreFreqMhz.getOrNull(i) ?: -1}")
        addAll(listOf("$gpuFreq", fmt(gpuLoad),
            fmt(batteryTemp), fmt(maxCpuTemp), fmt(maxSocTemp), fmt(maxOtherTemp),
            "$currentMa", "$voltageMv", "$powerMw", "$batteryLevel", "$chargeState",
            "$usedMemMb", "$totalMemMb",
            csvEsc(frontPkg), csvEsc(frontApp),
            "$wifiRxBytes", "$wifiTxBytes", "$mobileRxBytes", "$mobileTxBytes"))
        for (i in 0 until coreCount) addAll(listOf(fmt(perCoreLoad.getOrNull(i) ?: -1f),
            "${perCoreOnline.getOrNull(i) ?: -1}", fmt(perCoreTemp.getOrNull(i) ?: -1f),
            "${perCoreMaxMhz.getOrNull(i) ?: -1}", "${perCoreLimitMhz.getOrNull(i) ?: -1}",
            "${cpuPolicyIds.getOrNull(i) ?: -1}"))
        addAll(listOf(fmt(gpuTemp), fmt(npuTemp), fmt(skinTemp), "$thermalStatus", "$availableMemMb",
            "$swapUsedMb", "$chargeCounterMah", "$sampleDurationMs", if (privileged) "1" else "0",
            csvEsc(Json.encodeToString(thermalReadings))))
    }.joinToString(",")


    /** 浮点格式化为 1 位小数;负值(无效)统一写成 -1 */
    private fun fmt(v: Float): String = if (!v.isFinite() || v < 0) "-1" else String.format(Locale.US, "%.1f", v)

    /** CSV 字符串字段转义:包一层引号,内部引号翻倍(标准 CSV 规则) */
    private fun csvEsc(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""
}
