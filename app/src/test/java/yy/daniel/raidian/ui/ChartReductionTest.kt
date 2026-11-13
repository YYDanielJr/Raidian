package yy.daniel.raidian.ui

import org.junit.Assert.*
import org.junit.Test

class ChartReductionTest {
    @Test fun millionPointsStayBoundedAndKeepSpikes() {
        val values = MutableList<Float?>(1_000_000) { 50f }
        values[321_123] = 1000f
        values[321_124] = 0f
        val points = reduceChart(values, pixels = 720)
        assertTrue(points.size <= 720 * 4)
        assertTrue(points.any { it.index == 321_123 && it.value == 1000f })
        assertTrue(points.any { it.index == 321_124 && it.value == 0f })
        assertEquals(0, points.first().index)
        assertEquals(values.lastIndex, points.last().index)
        assertTrue(points.zipWithNext().all { (a, b) -> a.index < b.index })
    }

    @Test fun nullsNonFiniteValuesAndPausesNeverBecomeConnectingLines() {
        val values = listOf(10f, 30f, null, 40f, Float.NaN, 20f, 60f, 70f)
        val breaks = listOf(false, false, false, false, false, false, true, false)
        val points = reduceChart(values, breakBefore = breaks, pixels = 1)
        assertEquals(listOf(0, 3, 5, 6), points.filter { it.move }.map { it.index })
        assertFalse(points.any { it.index == 2 || it.index == 4 })
        assertTrue(points.any { it.index == 6 && it.value == 60f })
    }

    @Test fun bucketsUseTimeAndPreserveTheirLocalExtrema() {
        val times = listOf(0L, 1L, 2L, 3L, 4L, 5L, 10000L)
        val values = listOf(3f, 8f, 4f, 2f, 6f, 5f, 7f)
        val points = reduceChart(values, times, pixels = 2)
        assertEquals(listOf(0, 1, 3, 5, 6), points.map { it.index })
        // Selection still consults all original samples, including a point omitted by rendering.
        assertEquals(4, nearestChartIndex(0.0004f, values.size, times))
    }

    @Test fun emptyAndIsolatedReadingsWorkAtZeroWidth() {
        assertTrue(reduceChart(emptyList(), pixels = 0).isEmpty())
        assertTrue(reduceChart(listOf(null, Float.POSITIVE_INFINITY), pixels = 1).isEmpty())
        assertEquals(listOf(ChartVertex(0, 0f, true)), reduceChart(listOf(0f), pixels = 0))
    }
}
