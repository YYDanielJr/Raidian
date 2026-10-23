package yy.daniel.raidian.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * 主页"实时曲线"的显示配置(哪些指标显示 + 各曲线颜色),以及九宫格指标映射。
 *
 * 持久化在独立 SharedPreferences(live_chart_prefs)。每一项对应九宫格/实时页的一个
 * 指标;每项 1~N 条曲线系列(如 CPU 频率 = 小/中/大三簇三条线),每系列独立配色。
 *
 * 默认只开少量常用项(fps/cpu_load/gpu_load/power),其余默认关 —— 用户可自由开启。
 */
object LiveChartPrefs {

    /** 一个曲线系列(线):显示名 + 默认 Material 色(0xAARRGGBB) */
    data class Series(val label: String, val defaultArgb: Int)

    /** 一条可配置曲线指标 */
    data class ChartItem(
        val key: String,
        val label: String,
        val defaultOn: Boolean,
        val series: List<Series>,
    ) {
        /** 该指标是否多线(系列 > 1) */
        val multiLine: Boolean get() = series.size > 1
    }

    /** 全部可配置指标(顺序 = 实时页绘制顺序,与 SampleGrid 九宫格对应) */
    val ALL = listOf(
        ChartItem("fps", "帧率", true, listOf(Series("帧率", 0xFF43A047.toInt()))),
        ChartItem("cpu_load", "CPU 占用", true, listOf(Series("CPU占用", 0xFF00897B.toInt()))),
        ChartItem("gpu_load", "GPU 占用", true, listOf(Series("GPU占用", 0xFF673AB7.toInt()))),
        ChartItem(
            "cpu_freq", "CPU 频率", false,
            if (yy.daniel.raidian.monitor.DeviceProfile.detect().isXring) listOf(
                Series("CPU 0–1", 0xFF66BB6A.toInt()), Series("CPU 2–3", 0xFF26A69A.toInt()),
                Series("CPU 4–7", 0xFF1E88E5.toInt()), Series("CPU 8–9", 0xFFFFB300.toInt()),
            ) else listOf(
                Series("小核", 0xFF66BB6A.toInt()),
                Series("中核", 0xFF1E88E5.toInt()),
                Series("大核", 0xFFFFB300.toInt()),
            ),
        ),
        ChartItem("gpu_freq", "GPU 频率", false, listOf(Series("GPU频率", 0xFFF4511E.toInt()))),
        ChartItem("power", "电池功率", true, listOf(Series("电池功率", 0xFF1E88E5.toInt()))),
        ChartItem("battery_temp", "电池温度", false, listOf(Series("电池温度", 0xFFFB8C00.toInt()))),
        ChartItem("soc_temp", "核心温度", false, listOf(Series("核心温度", 0xFFAB47BC.toInt()))),
        ChartItem("memory", "内存", false, listOf(Series("内存", 0xFF607D8B.toInt()))),
        ChartItem("gpu_temp", "GPU 温度", false, listOf(Series("GPU 温度", 0xFF3949AB.toInt()))),
        ChartItem("npu_temp", "NPU 温度", false, listOf(Series("NPU 温度", 0xFF00897B.toInt()))),
        ChartItem("skin_temp", "机身温度", false, listOf(Series("机身温度", 0xFFFB8C00.toInt()))),
    )

    /** 用户可选的 Material 色板(色块网格用) */
    val MATERIAL_COLORS = listOf(
        0xFFF44336, 0xFFE91E63, 0xFF9C27B0, 0xFF673AB7,
        0xFF3F51B5, 0xFF1E88E5, 0xFF039BE5, 0xFF00ACC1,
        0xFF00897B, 0xFF43A047, 0xFF7CB342, 0xFFFDD835,
        0xFFFFB300, 0xFFFB8C00, 0xFFF4511E, 0xFF6D4C41,
        0xFF546E7A, 0xFF37474F,
    ).map { Color(it) }

    private fun sp(context: Context) =
        context.getSharedPreferences("live_chart_prefs", Context.MODE_PRIVATE)

    /** 某指标是否显示;无记录用默认 */
    fun isOn(context: Context, key: String): Boolean =
        sp(context).getBoolean("on_$key", ALL.firstOrNull { it.key == key }?.defaultOn ?: true)

    fun setOn(context: Context, key: String, on: Boolean) {
        sp(context).edit().putBoolean("on_$key", on).apply()
    }

    /** 某指标第 [si] 条系列颜色(无记录用默认 Material 色) */
    fun seriesColor(context: Context, key: String, si: Int): Color {
        val item = ALL.firstOrNull { it.key == key } ?: return Color(0xFF1E88E5)
        val def = item.series.getOrNull(si)?.defaultArgb ?: 0xFF1E88E5.toInt()
        val stored = sp(context).getInt("color_${key}_$si", Int.MIN_VALUE)
        return Color(if (stored == Int.MIN_VALUE) def else stored)
    }

    fun setSeriesColor(context: Context, key: String, si: Int, color: Color) {
        sp(context).edit().putInt("color_${key}_$si", color.toArgb()).apply()
    }

    /** 单线指标的简写:取第 0 系列颜色 */
    fun color(context: Context, key: String): Color = seriesColor(context, key, 0)

    fun setColor(context: Context, key: String, color: Color) = setSeriesColor(context, key, 0, color)
}
