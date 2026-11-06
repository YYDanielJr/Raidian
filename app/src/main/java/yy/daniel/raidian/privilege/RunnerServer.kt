package yy.daniel.raidian.privilege

import android.net.LocalServerSocket
import android.os.Process
import yy.daniel.raidian.monitor.BoundedCommand
import yy.daniel.raidian.monitor.HardwareSnapshotReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * app_process entry point, started by ADB or su. No ActivityManager hidden APIs or
 * Shizuku dependency. Linux SO_PEERCRED authenticates both ends of the local socket.
 * The APK stays in PackageManager's read-only install directory (Android 14 DCL).
 */
object RunnerServer {
    @JvmStatic fun main(args: Array<String>) {
        val uid = Process.myUid()
        check(uid == 0 || uid == 2000) { "ADB 或 root 身份才能启动采集服务" }
        require(args.size == 2)
        val appUid = args[0].toInt()
        val token = args[1]
        require(token.matches(Regex("[0-9a-f]{64}")))
        require(appUid >= 10000)
        val apk = System.getenv("CLASSPATH") ?: error("缺少 APK 路径")
        val revision = File(apk).lastModified()
        val server = LocalServerSocket(RunnerProtocol.socketName(appUid, uid == 0))
        val hardware = HardwareSnapshotReader()
        // Short one-request connections: reconnect after app death; no lingering app references.
        while (true) {
            server.accept().use { socket ->
                try {
                    if (socket.peerCredentials.uid != appUid) return@use
                    socket.soTimeout = 6000
                    val input = DataInputStream(socket.inputStream)
                    val output = DataOutputStream(socket.outputStream)
                    check(input.readInt() == RunnerProtocol.VERSION)
                    val suppliedToken = ByteArray(64).also(input::readFully)
                    check(java.security.MessageDigest.isEqual(token.toByteArray(), suppliedToken))
                    val op = input.readInt()
                    var stop = false
                    val result = when (op) {
                        RunnerProtocol.HELLO -> BoundedCommand.Result("$uid|$revision", 0)
                        RunnerProtocol.EXEC -> BoundedCommand.run(RunnerProtocol.read(input))
                        RunnerProtocol.HARDWARE -> BoundedCommand.Result(hardware.readSnapshot(), 0)
                        RunnerProtocol.THERMAL -> BoundedCommand.Result(hardware.readThermals(), 0)
                        RunnerProtocol.STOP -> { stop = true; BoundedCommand.Result("", 0) }
                        else -> BoundedCommand.Result("未知操作", -2)
                    }
                    output.writeInt(result.exitCode)
                    RunnerProtocol.write(output, result.output)
                    output.flush()
                    if (stop) { server.close(); return }
                } catch (e: Exception) {
                    android.util.Log.w("RaidianRunner", "请求未完成：${e.javaClass.simpleName}")
                    // A malformed or disconnected client must not terminate the privileged server.
                }
            }
        }
    }
}
