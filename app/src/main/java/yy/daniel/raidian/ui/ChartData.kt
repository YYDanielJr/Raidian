package yy.daniel.raidian.ui

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Serializable
data class ChartSeriesData(
    val name: String,
    val values: List<Float?>,
    val colorArgb: Int,
    val coreId: Int? = null,
    val policyId: Int = -1,
    val maxMhz: Int = -1,
)

@Serializable
data class ChartDocument(
    val title: String,
    val unit: String,
    val series: List<ChartSeriesData>,
    val elapsedMs: List<Long> = emptyList(),
    val breakBefore: List<Boolean> = emptyList(),
    val minY: Float? = null,
    val maxY: Float? = null,
    val source: String = "",
) {
    val pointCount: Int get() = series.maxOfOrNull { it.values.size } ?: 0
}

data class ChartStats(val count: Int, val missing: Int, val min: Float?, val max: Float?, val average: Double?)

fun chartStats(values: List<Float?>): ChartStats {
    var count = 0
    var sum = 0.0
    var min = Float.POSITIVE_INFINITY
    var max = Float.NEGATIVE_INFINITY
    for (value in values) if (value != null && value.isFinite()) {
        count++
        sum += value
        min = minOf(min, value)
        max = maxOf(max, value)
    }
    return ChartStats(count, values.size - count, min.takeIf { count > 0 }, max.takeIf { count > 0 },
        if (count > 0) sum / count else null)
}

fun validChartTimeline(times: List<Long>, count: Int): Boolean =
    count > 0 && times.size == count && (1 until times.size).all { times[it] >= times[it - 1] }

fun chartFraction(index: Int, count: Int, times: List<Long>): Float =
    if (times.size == count && times.size > 1 && times.last() > times.first())
        ((times[index] - times.first()).toDouble() / (times.last() - times.first())).toFloat()
    else index.toFloat() / (count - 1).coerceAtLeast(1)

/** 根据实际时间而非等间隔数组下标选择最近采样点。 */
fun nearestChartIndex(fraction: Float, count: Int, times: List<Long>): Int? {
    if (count <= 0) return null
    val f = fraction.coerceIn(0f, 1f)
    if (times.size != count || times.last() <= times.first()) return (f * (count - 1)).roundToInt()
    val target = times.first() + ((times.last() - times.first()) * f.toDouble()).roundToLong()
    val hit = times.binarySearch(target)
    if (hit >= 0) return hit
    val right = (-hit - 1).coerceAtMost(count - 1)
    val left = (right - 1).coerceAtLeast(0)
    return if (abs(times[left] - target) <= abs(times[right] - target)) left else right
}

fun chartTime(ms: Long, precise: Boolean = false): String {
    val t = ms.coerceAtLeast(0)
    val seconds = t / 1000
    val base = if (seconds >= 3600) String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    return if (precise) base + String.format(Locale.US, ".%03d", t % 1000) else base
}

fun chartTimeLabels(times: List<Long>): List<String>? = times.takeIf { it.isNotEmpty() }?.let {
    (0..4).map { tick -> chartTime(it.first() + (it.last() - it.first()) * tick / 4) }
}

/** 仅按记录的 policy 和硬件最高频率排序，不把当前设备拓扑套到旧记录上。 */
fun orderedCoreSeries(series: List<ChartSeriesData>): List<ChartSeriesData> {
    val maxima = series.filter { it.policyId >= 0 }.groupBy { it.policyId }
        .mapValues { (_, cores) -> cores.maxOf { it.maxMhz } }
    return series.sortedWith(compareByDescending<ChartSeriesData> { maxima[it.policyId] ?: it.maxMhz }
        .thenBy { it.policyId }.thenBy { it.coreId ?: Int.MAX_VALUE })
}

fun coreClusterLabel(core: ChartSeriesData, series: List<ChartSeriesData>): String {
    if (core.policyId < 0 || core.maxMhz <= 0) return "簇信息未记录"
    val policies = orderedCoreSeries(series).filter { it.policyId >= 0 && it.maxMhz > 0 }.map { it.policyId }.distinct()
    val labels = when (policies.size) {
        1 -> listOf("核心簇")
        2 -> listOf("大核簇", "小核簇")
        3 -> listOf("超大核簇", "大核簇", "小核簇")
        4 -> listOf("超大核簇", "大核簇", "中核簇", "小核簇")
        else -> policies.indices.map { "频率簇 ${it + 1}" }
    }
    return labels.getOrNull(policies.indexOf(core.policyId)) ?: "簇信息未记录"
}
