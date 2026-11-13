package yy.daniel.raidian.ui

import yy.daniel.raidian.monitor.SessionStore
import java.io.File

/** Retain only typed chart columns; raw rows and thermal JSON are released after each row. */
fun loadSessionDetail(store: SessionStore, dir: File): MainViewModel.SessionDetail? {
    val meta = store.readMeta(dir) ?: return null
    val elapsed = ArrayList<Long>()
    val extras = listOf("gpu_temp_c", "npu_temp_c", "skin_temp_c", "thermal_status", "available_mem_mb", "swap_used_mb")
    val columns = linkedMapOf<String, Int>()
    val values = linkedMapOf<String, ArrayList<Float>>()
    var elapsedColumn = -1
    var coreCount = 0
    var policies = IntArray(0)
    var maxima = IntArray(0)
    var policyColumns = emptyList<Int>()
    var maxColumns = emptyList<Int>()
    store.forEachSample(dir, onHeader = { header ->
        elapsedColumn = header.indexOf("elapsed_ms")
        coreCount = header.mapNotNull { Regex("cpu(\\d+)_freq_mhz").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull()?.plus(1) ?: 0
        val names = listOf("fps", "battery_temp_c", "max_cpu_temp_c", "power_mw", "cpu_load_pct", "gpu_load_pct") + extras +
            (0 until coreCount).flatMap { listOf("cpu${it}_freq_mhz", "cpu${it}_load_pct", "cpu${it}_temp_c") }
        names.forEach { columns[it] = header.indexOf(it); values[it] = ArrayList() }
        policies = IntArray(coreCount) { -1 }
        maxima = IntArray(coreCount) { -1 }
        policyColumns = (0 until coreCount).map { header.indexOf("cpu${it}_policy") }
        maxColumns = (0 until coreCount).map { header.indexOf("cpu${it}_max_mhz") }
    }, onRow = { row ->
        elapsed.add(row.getOrNull(elapsedColumn)?.toLongOrNull() ?: 0L)
        for ((name, index) in columns) values.getValue(name).add(row.getOrNull(index)?.toFloatOrNull()?.takeIf { it.isFinite() } ?: -1f)
        for (i in 0 until coreCount) {
            if (policies[i] < 0) policies[i] = row.getOrNull(policyColumns[i])?.toIntOrNull() ?: -1
            if (maxima[i] < 0) maxima[i] = row.getOrNull(maxColumns[i])?.toIntOrNull() ?: -1
        }
    })
    fun series(name: String): List<Float> = values[name].orEmpty()
    val gap = (meta.intervalMs.takeIf { it > 0 } ?: 500L) * 2
    return MainViewModel.SessionDetail(meta, elapsed, series("fps"), series("battery_temp_c"), series("max_cpu_temp_c"),
        series("power_mw"), series("cpu_load_pct"), series("gpu_load_pct"),
        (0 until coreCount).map { series("cpu${it}_freq_mhz") },
        perCoreLoad = (0 until coreCount).map { series("cpu${it}_load_pct") },
        perCoreTemp = (0 until coreCount).map { series("cpu${it}_temp_c") },
        extraSeries = extras.associateWith(::series), corePolicies = policies.toList(), coreMaxMhz = maxima.toList(),
        breakBefore = elapsed.mapIndexed { i, t -> i > 0 && t - elapsed[i - 1] > gap })
}
