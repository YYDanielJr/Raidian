package yy.daniel.raidian.shizuku

import android.content.Context
import yy.daniel.raidian.shizuku.CommandResult
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shizuku UserService:运行在以 Shizuku 授予的身份(shell/adb)启动的独立进程
 * (`yy.daniel.raidian:runner`)里,负责执行需要提权的 shell 命令 / 直读系统文件。
 *
 * 工作方式(Shizuku v13 UserService 机制):
 *  1. 应用侧 ShizukuShell 用 UserServiceArgs 调 bindUserService;
 *  2. Shizuku fork 一个 app_process 进程加载本 APK,并以
 *     (Context) 或 () 构造器实例化本类(两种构造器都提供,框架任选其一);
 *  3. 框架把 [ICommandRunner.Stub] 的 Binder 回传给发起方,后续 AIDL 调用直达本类。
 *
 * 因此本类所有代码都运行在**提权身份**下,能读 thermal/cpu 等 shell 可读 sysfs。
 *
 * 实机踩坑记录(必须遵守,否则复现 9s 拖垮采样 / 空结果):
 *  - 不要用 File.canRead() 预检:app_process(shell 身份)下 canRead() 因 SELinux
 *    域判定可能恒为 false,导致遍历空手而归 → 见 readThermalZones 的实现;
 *  - 单条命令带超时(CMD_TIMEOUT_MS),防止个别慢命令阻塞 runner 的 binder 线程。
 */
class CommandRunnerService private constructor(
    private val context: Context?,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) : ICommandRunner.Stub() {
    private val hardware = yy.daniel.raidian.monitor.HardwareSnapshotReader()
    override fun readHardwareSnapshot(): String = hardware.readSnapshot()

    /** Shizuku UserService 需要 () 构造器 */
    constructor() : this(null, Unit)
    /** 有 Context 时框架会用这个构造器(可访问应用资源/文件) */
    constructor(context: Context) : this(context, Unit)

    companion object {
        /** 单条命令超时:防个别慢命令阻塞 runner 单线程 */
        private const val CMD_TIMEOUT_MS = 4000L
    }

    /**
     * 执行命令并等待完成,带超时。
     * 输出用 daemon 读线程异步收(避免大输出占满管道导致子进程写阻塞);
     * 超时则杀进程、立即返回已读到的输出,不留僵尸。
     */
    private fun runWithTimeout(command: String): String {
        return yy.daniel.raidian.monitor.BoundedCommand.run(command, CMD_TIMEOUT_MS).output
    }

    /** AIDL:执行命令返回输出文本(带超时;超时/异常返回 ERROR 前缀或已读输出) */
    override fun executeCommand(command: String): String {
        val t0 = System.currentTimeMillis()
        val r = try {
            runWithTimeout(command)
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
        val ms = System.currentTimeMillis() - t0
        if (ms > 200) {
            // 慢命令记日志,便于发现拖垮采样的命令
            android.util.Log.w("RunnerCmd", "cmd ${ms}ms: ${command.take(80)}")
        }
        return r
    }

    /** AIDL:执行命令,仅返回退出码(丢弃输出;带超时,超时返回 -1) */
    override fun executeCommandWithExitCode(command: String): Int =
        try { yy.daniel.raidian.monitor.BoundedCommand.run(command).exitCode } catch (_: Exception) { -1 }

    override fun executeCommandWithResult(command: String): CommandResult {
        val result = try { yy.daniel.raidian.monitor.BoundedCommand.run(command) }
            catch (e: Exception) { yy.daniel.raidian.monitor.BoundedCommand.Result(e.message.orEmpty(), -1) }
        return CommandResult().apply { output = result.output; exitCode = result.exitCode }
    }

    /** AIDL:结束本 UserService 进程(Shizuku 会在需要时重新拉起) */
    override fun destroy() {
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /**
     * AIDL:读取全部 thermal zone,返回 "type temp" 行(单位 m°C),每 zone 一行。
     *
     * 实现要点(实机验证,阶段二性能修复):
     *  - 直接 JVM File 遍历 /sys/class/thermal/thermal_zone*,不开 shell 子进程
     *    (毫秒级;对比 shell 逐 zone cat,106 个 zone 实测 ~9s);
     *  - **不要**用 File.canRead() 预检 —— app_process(shell 身份)下可能因
     *    SELinux 域判定恒 false → 直接 try readText,单 zone 失败仅跳过;
     *  - 单 zone 读失败不影响其它 zone(各自 try 包裹)。
     */
    override fun readThermalZones(): String {
        val t0 = System.currentTimeMillis()
        val r = try {
            val sb = StringBuilder()
            val dir = java.io.File("/sys/class/thermal")
            val zones = dir.listFiles { f -> f.isDirectory && f.name.startsWith("thermal_zone") }
                ?: return ""
            for (z in zones) {
                val typeF = java.io.File(z, "type")
                val tempF = java.io.File(z, "temp")
                try {
                    if (typeF.exists() && tempF.exists()) {
                        val type = typeF.readText().trim()
                        val temp = tempF.readText().trim()
                        if (type.isNotEmpty() && temp.isNotEmpty()) {
                            sb.append(type).append(' ').append(temp).append('\n')
                        }
                    }
                } catch (e: Exception) {
                    // 单个 zone 读失败跳过
                }
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
        val ms = System.currentTimeMillis() - t0
        if (ms > 200) android.util.Log.w("RunnerCmd", "thermalZones ${ms}ms")
        return r
    }
}
