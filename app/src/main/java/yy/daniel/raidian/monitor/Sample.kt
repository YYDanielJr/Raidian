package yy.daniel.raidian.monitor

import kotlinx.serialization.Serializable

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
 * CSV 序列化(表头/行)与 SessionStore 的 CSV 解析、MainViewModel 按列号读取、
 * 悬浮窗 OverlayWindow 的格式化,全都依赖本类的字段顺序 —— 增删字段时务必同步。
 */
@Serializable
data class Sample(
    /** 距会话开始的时间,毫秒(会话由 SessionRecorder.start 建立起点) */
    val elapsedMs: Long,
    /** 帧率,每秒帧数;无有效值时 -1(桌面等无渲染层场景会长期是 -1) */
    val fps: Float = -1f,
    /** 单帧耗时,毫秒(= 1000/fps);无有效值时 -1 */
    val frameTimeMs: Float = -1f,

    /** 小核簇频率 MHz(见 MonitorSampler.buildClusterMap 的簇划分) */
    val cpuL: Int = -1,
    /** 中核簇频率 MHz */
    val cpuM: Int = -1,
    /** 大核簇频率 MHz */
    val cpuB: Int = -1,
    /** 整机 CPU 占用率 0-100(由 /proc/stat 前后两次 jiffies 差值算出) */
    val cpuLoad: Float = -1f,

    /**
     * 每个逻辑核的当前频率(MHz,下标 = 逻辑核号)。
     * 长度固定为 [MAX_CORES](8),读不到的核为 -1。
     * 阶段四后新增:用于详情页"每核频率曲线"(见 Sample.toCsvHeader)。
     */
    val perCoreFreqMhz: List<Int> = emptyList(),

    /** GPU 频率 MHz;小米 13 Ultra 上 kgsl/gpu sysfs 对 shell 身份被 SELinux 拒,恒为 -1 */
    val gpuFreq: Int = -1,
    /** GPU 占用率 0-100(高通 kgsl gpubusy 的 busy/total 比值;shell 身份可读) */
    val gpuLoad: Float = -1f,

    /** 电池温度,摄氏度(来自 dumpsys battery,上游 0.1°C 已 /10) */
    val batteryTemp: Float = -1f,
    /** 各 thermal zone 中 cpu* 类的最高温度,摄氏度 */
    val maxCpuTemp: Float = -1f,
    /** 主板/外壳等最高温度,摄氏度 */
    val maxOtherTemp: Float = -1f,
    /** 电池温度之外最热点(通常 SoC,来自 soc* 类 zone),摄氏度 */
    val maxSocTemp: Float = -1f,

    /** 电流 mA(放电为正;来自 BatteryManager.CURRENT_NOW,µA 已 /1000) */
    val currentMa: Int = -1,
    /** 电压 mV(来自 dumpsys battery) */
    val voltageMv: Int = -1,
    /** 功率 mW(current × voltage / 1000) */
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
) {
    /**
     * 生成 CSV 表头。
     * 注意:字段顺序必须与 [toCsvRow] 完全一致,并与消费方
     * (SessionStore.readSamples → MainViewModel 的列号下标)保持同步。
     *
     * 每核频率列按 [MAX_CORES] 展开为 cpu0_freq_mhz .. cpu7_freq_mhz;
     * 消费方按表头前缀 "cpuN_freq_mhz" 定位,而不是硬编码列号,以兼容旧会话。
     */
    fun toCsvHeader(): String = buildList {
        addAll(listOf("elapsed_ms", "fps", "frame_time_ms",
            "cpu_l_mhz", "cpu_m_mhz", "cpu_b_mhz", "cpu_load_pct"))
        for (i in 0 until MAX_CORES) add("cpu${i}_freq_mhz")
        addAll(listOf("gpu_mhz", "gpu_load_pct",
            "battery_temp_c", "max_cpu_temp_c", "max_soc_temp_c", "max_other_temp_c",
            "current_ma", "voltage_mv", "power_mw", "battery_level_pct", "charge_state",
            "used_mem_mb", "total_mem_mb",
            "front_pkg", "front_app",
            "wifi_rx_b", "wifi_tx_b", "mobile_rx_b", "mobile_tx_b"))
    }.joinToString(",")

    /** 生成一行 CSV 数据;字符串字段(包名/应用名)做引号转义以兼容逗号 */
    fun toCsvRow(): String = buildList {
        addAll(listOf("$elapsedMs", fmt(fps), fmt(frameTimeMs),
            "$cpuL", "$cpuM", "$cpuB", fmt(cpuLoad)))
        for (i in 0 until MAX_CORES) add("${perCoreFreqMhz.getOrNull(i) ?: -1}")
        addAll(listOf("$gpuFreq", fmt(gpuLoad),
            fmt(batteryTemp), fmt(maxCpuTemp), fmt(maxSocTemp), fmt(maxOtherTemp),
            "$currentMa", "$voltageMv", "$powerMw", "$batteryLevel", "$chargeState",
            "$usedMemMb", "$totalMemMb",
            csvEsc(frontPkg), csvEsc(frontApp),
            "$wifiRxBytes", "$wifiTxBytes", "$mobileRxBytes", "$mobileTxBytes"))
    }.joinToString(",")

    companion object {
        /** 支持记录的最大逻辑核数:超出部分频率不记录(小米 13 Ultra 为 8 核) */
        const val MAX_CORES = 8
    }

    /** 浮点格式化为 1 位小数;负值(无效)统一写成 -1 */
    private fun fmt(v: Float): String = if (v < 0) "-1" else String.format("%.1f", v)

    /** CSV 字符串字段转义:包一层引号,内部引号翻倍(标准 CSV 规则) */
    private fun csvEsc(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""
}
