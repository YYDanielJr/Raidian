package yy.daniel.raidian.monitor

import java.util.concurrent.TimeUnit

/** Both app fallback and runner commands have the same timeout and bounded output. */
object BoundedCommand {
    data class Result(val output: String, val exitCode: Int)
    fun run(command: String, timeoutMs: Long = 4000): Result {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread {
            try {
                process.inputStream.reader().use { input ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        synchronized(output) {
                            val keep = n.coerceAtMost((1_048_576 - output.length).coerceAtLeast(0))
                            output.append(buffer, 0, keep)
                        }
                    }
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        return try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                Result("", -1) // 不把超时的半截输出当作成功快照。
            } else {
                reader.join(250)
                Result(synchronized(output) { output.toString().trim() }, process.exitValue())
            }
        } finally {
            process.destroy()
            try { process.inputStream.close() } catch (_: Exception) { }
        }
    }
}
