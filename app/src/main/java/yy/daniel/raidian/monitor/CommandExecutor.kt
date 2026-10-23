package yy.daniel.raidian.monitor

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 命令执行通道。所有需要提权的 shell 读取统一走这里。
 *
 * 优先级:
 *  1. Shizuku(若已授予权限)—— 具备 shell 身份,能读 thermal、cpu 等 sysfs。
 *  2. 应用自身 sh —— 只能读应用可读的数据,用于无 Shizuku 时的降级。
 *
 * 设计取舍:后台记录的核心是持续采集(500ms/点),Shell 每 tick 起进程开销不小,
 * 因此 [execute] 尽量合并多条读取为一条 shell 命令串;单条慢命令(如逐 zone cat
 * thermal)还会拖垮整个采样节奏,所以 [readThermalZones] 的降级分支套了 timeout 限时。
 */
class CommandExecutor {
    private val localHardware = HardwareSnapshotReader()
    suspend fun readHardwareSnapshot(): String = withContext(Dispatchers.IO) {
        shizuku?.readHardwareSnapshot() ?: localHardware.readSnapshot()
    }
    companion object {
        private const val TAG = "CmdExec"
    }

    /** 命令通道实现,由 Shizuku 接入层注入(RaidianApp.onCreate 装配) */
    @Volatile
    var shizuku: ShizukuShell? = null

    /** 最近一次错误信息(用于界面显示降级原因 / 排查) */
    @Volatile
    var lastError: String = ""

    /**
     * 执行一条 shell 命令,返回 stdout 合并 stderr 的文本。
     * 优先 Shizuku,失败时回退应用内 Runtime.exec。
     */
    suspend fun execute(cmd: String): String {
        val s = shizuku
        if (s != null && s.available()) {
            val r = s.exec(cmd)
            if (r != null) return r
        }
        // 降级:尝试应用内直接读(应用对 thermal_zone 大多可读)
        return withContext(Dispatchers.IO) {
            try {
                val result = BoundedCommand.run(cmd)
                if (result.exitCode == -1) lastError = "命令超时"
                result.output
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                lastError = e.message ?: "exec failed"
                ""
            }
        }
    }

    /** 一次读单个 sysfs 文件,不存在或读失败返回空串 */
    suspend fun readFile(path: String): String = execute("cat $path 2>/dev/null").trim()

    /**
     * 读取全部热区;返回 "type temp" 行或空。
     * 优先走 Shizuku runner 进程内直接 JVM File 遍历(毫秒级,见
     * CommandRunnerService.readThermalZones);仅当无 Shizuku 时才降级 shell 遍历。
     */
    suspend fun readThermalZones(): String {
        val s = shizuku
        if (s != null && s.available()) {
            val r = s.readThermalZones()
            if (r != null && r.isNotBlank()) return r
        }
        // 降级:shell 遍历(慢且仅无 Shizuku 时兜底,加 timeout 防拖垮轮询)
        // 注意不能逐 zone fork cat(106 zone 实测 ~9s);此处带 timeout 限 2s
        val inner = "cd /sys/class/thermal 2>/dev/null && for z in thermal_zone*; do " +
            "t=\$(cat \$z/type 2>/dev/null); v=\$(cat \$z/temp 2>/dev/null); " +
            "[ -n \"\$t\" ] && [ -n \"\$v\" ] && echo \"\$t \$v\"; done"
        return execute("timeout 2 sh -c '$inner' 2>/dev/null")
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
