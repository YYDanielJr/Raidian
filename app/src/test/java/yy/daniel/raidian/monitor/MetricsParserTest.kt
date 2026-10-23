package yy.daniel.raidian.monitor

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class MetricsParserTest {
    @Test fun discoversTenCoresAndSparseOnlineSets() {
        assertEquals((0..9).toList(), MetricsParser.cpuIds("0-9"))
        assertEquals(listOf(0, 1, 4, 8, 9), MetricsParser.cpuIds("0-1,4 8-9"))
        assertTrue(MetricsParser.cpuIds("permission denied").isEmpty())
    }

    @Test fun halUsesCurrentSectionAndPreservesAllSensors() {
        val result = MetricsParser.thermalHal("""
            Cached temperatures:
            Temperature{mValue=95.0, mType=0, mName=cpu-8, mStatus=0}
            HAL Ready: true
            Current temperatures from HAL:
            Temperature{mValue=32.0, mType=0, mName=cpu-8, mStatus=0}
            Temperature{mValue=31.0, mType=1, mName=gpu-ss-0, mStatus=0}
            Temperature{mValue=33.0, mType=9, mName=npu-crg, mStatus=0}
            Temperature{mValue=30.0, mType=3, mName=quiet_therm, mStatus=0}
            Current cooling devices from HAL:
        """.trimIndent())
        assertEquals(4, result.size)
        assertEquals(32f, result[0].celsius)
        assertEquals(listOf("cpu", "gpu", "npu", "skin"), result.map { it.kind })
    }

    @Test fun unavailableHalDoesNotPresentCachedValuesAsCurrent() {
        assertTrue(MetricsParser.thermalHal("Cached temperatures:\nTemperature{mValue=95, mType=0, mName=cpu-8}").isEmpty())
        assertTrue(MetricsParser.thermalHal("HAL Ready: false\nCurrent temperatures from HAL:\nTemperature{mValue=40, mType=0, mName=cpu-8}").isEmpty())
    }

    @Test fun missingCoreSensorsRemainUnavailable() {
        val sensors = listOf(ThermalReading("cpu-8", 40f, "cpu", "thermal_hal"), ThermalReading("cpu-top", 42f, "cpu", "thermal_hal"))
        val values = MetricsParser.coreTemperatures(sensors, 10, true)
        assertEquals(10, values.size)
        assertEquals(40f, values[8])
        assertEquals(-1f, values[9])
        assertTrue(MetricsParser.coreTemperatures(sensors, 10, false).all { it == -1f })
    }

    @Test fun sysfsMillidegreesExcludeThresholdsAndInvalidSensors() {
        val sensors = MetricsParser.thermalSysfs("cpuss-0 43000\ngpuss-0 41000\ncpu-hw-trip-0 95000\ncpu-1 -273000\ncpu-2 NaN\nquiet_therm 31000")
        assertEquals(listOf("cpu", "gpu", "skin"), sensors.map { it.kind })
        assertEquals(43f, sensors.first().celsius)
    }

    @Test fun frequencyUnitsAreSourceSpecific() {
        assertEquals(3398, MetricsParser.khzToMhz("3398400"))
        assertEquals(900, MetricsParser.hzToMhz("900000000"))
        assertEquals(-1, MetricsParser.hzToMhz("Permission denied"))
    }

    @Test fun smallCurrentsAndUnsupportedSentinelsNeverBecomeFalsePower() {
        assertEquals(0, MetricsParser.currentMa(900))
        assertEquals(3L, MetricsParser.powerMw(900, 4000))
        assertEquals(4000L, MetricsParser.powerMw(-1_000_000, 4000))
        assertEquals(-1L, MetricsParser.powerMw(Int.MIN_VALUE, 4000))
        assertEquals(0L, MetricsParser.powerMw(0, 4000))
    }

    @Test fun cpuDeltasDoNotDoubleCountGuestOrAcceptReset() {
        val before = MetricsParser.cpuTimes("cpu 100 0 0 100 0 0 0 0 50 50\ncpu9 20 0 0 80 0 0 0 0")
        val after = MetricsParser.cpuTimes("cpu 130 0 0 170 0 0 0 0 70 70\ncpu9 60 0 0 140 0 0 0 0")
        assertEquals(30f, MetricsParser.cpuLoad(before["cpu"], after["cpu"]))
        assertEquals(40f, MetricsParser.cpuLoad(before["cpu9"], after["cpu9"]))
        assertEquals(-1f, MetricsParser.cpuLoad(after["cpu"], before["cpu"]))
        assertEquals(-1f, MetricsParser.cpuLoad(null, after["cpu"]))
    }

    @Test fun gpuHandlesWhitespaceIdleAndDeniedIndependently() {
        assertEquals(50f, MetricsParser.gpuLoad(" 652988 1305976", ""))
        assertEquals(0f, MetricsParser.gpuLoad("0 0", ""))
        assertEquals(-1f, MetricsParser.gpuLoad("10 0", ""))
        assertEquals(-1f, MetricsParser.gpuLoad("Permission denied", ""))
        assertEquals(57f, MetricsParser.gpuLoad("", "57%"))
    }

    @Test fun tenCoreCsvKeepsCpuEightNineAndUsesInvariantDecimal() {
        val old = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val s = Sample(0, cpuLoad = 12.5f, perCoreFreqMhz = List(10) { 1000 + it })
            val header = s.toCsvHeader().split(',')
            val row = s.toCsvRow().split(',')
            assertEquals(header.size, row.size)
            assertEquals("1009", row[header.indexOf("cpu9_freq_mhz")])
            assertEquals("12.5", row[header.indexOf("cpu_load_pct")])
            assertTrue(header.contains("cpu9_temp_c"))
            assertFalse(header.contains("cpu10_freq_mhz"))
        } finally { Locale.setDefault(old) }
    }

    @Test fun schemaWidthStaysStableWhenAReadFails() {
        val expected = Sample(0, perCoreFreqMhz = List(10) { 1000 }).toCsvHeader(10).split(',').size
        assertEquals(expected, Sample(500).toCsvRow(10).split(',').size)
    }
}
