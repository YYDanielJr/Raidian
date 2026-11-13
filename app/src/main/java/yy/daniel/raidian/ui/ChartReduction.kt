package yy.daniel.raidian.ui

/** Render-only reduction. Indices always refer to the original, full-resolution series. */
data class ChartVertex(val index: Int, val value: Float, val move: Boolean)

/**
 * Keep first/min/max/last in each horizontal pixel bucket, in time order.
 * Flush at missing values and explicit pauses so reduction never bridges a gap.
 * Complexity O(samples), output O(width + discontinuities); called only when the
 * data or viewport changes, never during scrolling or selection redraws.
 */
fun reduceChart(
    values: List<Float?>,
    times: List<Long> = emptyList(),
    breakBefore: List<Boolean> = emptyList(),
    pixels: Int,
    count: Int = values.size,
): List<ChartVertex> {
    val result = ArrayList<ChartVertex>()
    val width = pixels.coerceAtLeast(1)
    var bucket = -1
    var first = -1
    var last = -1
    var low = -1
    var high = -1
    var move = true
    fun flush() {
        if (first < 0) return
        val indices = intArrayOf(first, low, high, last).apply { sort() }
        var previous = -1
        for (index in indices) {
            if (index != previous) {
                result.add(ChartVertex(index, values[index]!!, move))
                move = false
                previous = index
            }
        }
        first = -1
    }
    for (i in values.indices) {
        val value = values[i]
        if (value == null || !value.isFinite()) {
            flush()
            move = true
            continue
        }
        if (breakBefore.getOrNull(i) == true) {
            flush()
            move = true
        }
        val nextBucket = (chartFraction(i, count, times) * width).toInt().coerceIn(0, width - 1)
        if (first >= 0 && nextBucket != bucket) flush()
        if (first < 0) {
            first = i
            low = i
            high = i
            bucket = nextBucket
        }
        if (value < values[low]!!) low = i
        if (value > values[high]!!) high = i
        last = i
    }
    flush()
    return result
}
