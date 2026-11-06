package yy.daniel.raidian.monitor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import yy.daniel.raidian.privilege.PrivilegeManager
import yy.daniel.raidian.privilege.RunnerProtocol

/** All collectors use the selected identity. Unavailable transport falls back to app permissions. */
class CommandExecutor {
    private val localHardware = HardwareSnapshotReader()
    var privileges: PrivilegeManager? = null
    val privileged get() = privileges?.connected == true
    val identity get() = privileges?.state?.value?.let { "${it.mode}:${it.uid}:${it.connected}" }.orEmpty()
    @Volatile var lastError = ""

    suspend fun readHardwareSnapshot(): String = withContext(Dispatchers.IO) {
        privileges?.request(RunnerProtocol.HARDWARE)?.output ?: localHardware.readSnapshot()
    }
    suspend fun execute(cmd: String): String = withContext(Dispatchers.IO) {
        try {
            val result = privileges?.request(RunnerProtocol.EXEC, cmd) ?: BoundedCommand.run(cmd)
            lastError = if (result.exitCode == -1) "命令超时" else ""
            result.output
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { lastError = e.message.orEmpty(); "" }
    }
    suspend fun readFile(path: String) = execute("cat ${RunnerProtocol.quote(path)} 2>/dev/null").trim()
    suspend fun readThermalZones(): String = withContext(Dispatchers.IO) {
        privileges?.request(RunnerProtocol.THERMAL)?.output ?: localHardware.readThermals()
    }

    /**
     * 批量读多个文件,减少进程启动(一次 shell 带 @@@key 标记分段),
     * 返回 key → 文本的映射。需要读多个独立 sysfs 路径时用它合并。
     */
    suspend fun readFiles(paths: Map<String, String>): Map<String, String> {
        val sb = StringBuilder()
        val order = paths.keys.toList()
        order.forEachIndexed { i, k ->
            sb.append("echo \"@@@$k\"; cat ${paths[k]} 2>/dev/null; echo;")
            if (i > 0) sb.append(" ")
        }
        val out = execute(sb.toString())
        val map = mutableMapOf<String, String>()
        var cur: String? = null
        out.lines().forEach { line ->
            val m = MARKER.find(line)
            if (m != null) {
                cur = m.groupValues[1]
            } else if (cur != null) {
                map[cur!!] = (map[cur] ?: "") + line + "\n"
            }
        }
        return map.mapValues { it.value.trim() }
    }

    /** readFiles 的输出分段标记正则:^@@@key */
    private val MARKER = Regex("^@@@(.*)$")
}
