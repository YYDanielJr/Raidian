package yy.daniel.raidian.ui

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ChartDataTest {
    @Test fun selectionUsesRealTimeIncludingLongPause() {
        val times = listOf(0L, 500L, 1000L, 11000L)
        assertEquals(2, nearestChartIndex(0.5f, 4, times))
        assertEquals(3, nearestChartIndex(1f, 4, times))
        assertEquals(0, nearestChartIndex(-1f, 4, times))
        assertEquals(1f / 11, chartFraction(2, 4, times), 0.00001f)
    }

    @Test fun largeTimestampsDoNotLoseMillisecondResolution() {
        val start = 1_700_000_000_000L
        val times = listOf(start, start + 500, start + 1500)
        assertEquals(1, nearestChartIndex(1f / 3, 3, times))
        assertEquals(1f / 3, chartFraction(1, 3, times), 0.00001f)
    }

    @Test fun emptySingleAndDuplicateTimesAreSafe() {
        assertNull(nearestChartIndex(0.5f, 0, emptyList()))
        assertEquals(0, nearestChartIndex(0.9f, 1, listOf(100L)))
        assertEquals(1, nearestChartIndex(1f, 2, listOf(100L, 100L)))
        assertFalse(validChartTimeline(listOf(100L, 0L), 2))
        assertFalse(validChartTimeline(listOf(100L), 2))
    }

    @Test fun statisticsKeepZeroAndExcludeMissingAndNonFiniteValues() {
        val stats = chartStats(listOf(0f, 20f, null, Float.NaN, Float.POSITIVE_INFINITY))
        assertEquals(2, stats.count)
        assertEquals(3, stats.missing)
        assertEquals(10.0, stats.average!!, 0.001)
        assertEquals(0f, stats.min!!, 0f)
        assertNull(chartStats(listOf(null)).average)
    }

    @Test fun xringTenCoresSortByPolicyCapacityDescending() {
        val policies = listOf(0, 0, 2, 2, 4, 4, 4, 4, 8, 8)
        val maxima = mapOf(0 to 1795, 2 to 1891, 4 to 3043, 8 to 3398)
        val cores = policies.mapIndexed { core, policy ->
            ChartSeriesData("CPU $core", listOf(800f), 0, core, policy, maxima.getValue(policy))
        }
        assertEquals(listOf(8, 9, 4, 5, 6, 7, 2, 3, 0, 1), orderedCoreSeries(cores).map { it.coreId })
        assertEquals("超大核簇", coreClusterLabel(cores[8], cores))
        assertEquals("中核簇", coreClusterLabel(cores[2], cores))
        val dual = cores.filter { it.policyId == 0 || it.policyId == 8 }
        assertEquals("大核簇", coreClusterLabel(cores[8], dual))
        assertEquals("小核簇", coreClusterLabel(cores[0], dual))
    }

    @Test fun legacyRecordsDoNotBorrowCurrentDeviceTopology() {
        val core = ChartSeriesData("CPU 7", listOf(2000f), 0, 7)
        assertEquals("簇信息未记录", coreClusterLabel(core, listOf(core)))
    }

    @Test fun snapshotRoundTripPreservesExactCoordinatesAndGapBoundaryValue() {
        val doc = ChartDocument("CPU 频率", "MHz", listOf(ChartSeriesData("CPU 9", listOf(1000f, null, 3000f), 0, 9, 8, 3398)),
            listOf(0, 500, 10000), listOf(false, false, true))
        assertEquals(doc, Json.decodeFromString<ChartDocument>(Json.encodeToString(doc)))
        assertEquals("1:01:01.005", chartTime(3_661_005, true))
        assertEquals(listOf("0:00", "0:02", "0:05", "0:07", "0:10"), chartTimeLabels(doc.elapsedMs))
    }
}
