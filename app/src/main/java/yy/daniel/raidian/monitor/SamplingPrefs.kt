package yy.daniel.raidian.monitor

import android.content.Context

object SamplingPrefs {
    const val DEFAULT_MS = 500L
    const val MIN_MS = 250L
    const val MAX_MS = 10_000L
    val presets = listOf(250L, 500L, 1_000L, 2_000L, 5_000L)

    fun read(context: Context): Long = context.getSharedPreferences("sampling", Context.MODE_PRIVATE)
        .getLong("interval_ms", DEFAULT_MS).coerceIn(MIN_MS, MAX_MS)

    fun save(context: Context, interval: Long) {
        require(interval in MIN_MS..MAX_MS)
        context.getSharedPreferences("sampling", Context.MODE_PRIVATE).edit()
            .putLong("interval_ms", interval).apply()
    }
}
