package yy.daniel.raidian.privilege

import android.net.LocalServerSocket
import android.os.Process
import yy.daniel.raidian.monitor.BoundedCommand
import yy.daniel.raidian.monitor.HardwareSnapshotReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.InetSocketAddress

/**
 * app_process entry point, started by ADB or su. No ActivityManager hidden APIs or
 * Shizuku dependency. Root uses SO_PEERCRED; shell uses mutually authenticated loopback
 * TCP because production SELinux policies can prohibit app-to-shell Unix sockets.
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
        val hardware = HardwareSnapshotReader()
        if (uid == 2000) {
            serveAdb(appUid, token, revision, hardware)
            return
        }
        val server = LocalServerSocket(RunnerProtocol.socketName(appUid, true))
        // Short one-request connections: reconnect after app death; no lingering app references.
        while (true) {
            server.accept().use { socket ->
                try {
                    if (socket.peerCredentials.uid != appUid) return@use
                    socket.soTimeout = 6000
                    val input = DataInputStream(socket.inputStream)
                    val output = DataOutputStream(socket.outputStream)
                    check(input.readInt() == RunnerProtocol.ROOT_VERSION)
                    val suppliedToken = ByteArray(64).also(input::readFully)
                    check(java.security.MessageDigest.isEqual(token.toByteArray(), suppliedToken))
                    if (respond(input, output, uid, revision, hardware)) { server.close(); return }
                } catch (e: Exception) {
                    android.util.Log.w("RaidianRunner", "请求未完成：${e.javaClass.simpleName}")
                    // A malformed or disconnected client must not terminate the privileged server.
                }
            }
        }
    }

    private fun serveAdb(appUid: Int, token: String, revision: Long, hardware: HardwareSnapshotReader) {
        // Re-running a computer command must not create an additional privileged process.
        if (runCatching { RunnerClient(false, token, appUid).call(RunnerProtocol.HELLO).exitCode == 0 }.getOrDefault(false)) return
        val server = RunnerProtocol.ports(appUid).firstNotNullOfOrNull { port ->
            val candidate = ServerSocket()
            try {
                candidate.reuseAddress = true
                candidate.bind(InetSocketAddress("127.0.0.1", port), 8)
                candidate
            } catch (_: java.io.IOException) { candidate.close(); null }
        } ?: error("本机采集端口被占用")
        server.use {
            while (true) {
                server.accept().use { socket ->
                    try {
                        socket.soTimeout = 1000
                        socket.tcpNoDelay = true
                        val input = DataInputStream(socket.getInputStream())
                        val output = DataOutputStream(socket.getOutputStream())
                        val session = RunnerAuthentication.server(input, output, token, appUid)
                        socket.soTimeout = 6000
                        // Authenticate the whole command before executing it. A local port squatter
                        // must not relay a valid handshake and then substitute its own command.
                        val request = DataInputStream(session.read(input, "request").inputStream())
                        val response = ByteArrayOutputStream()
                        val stop = respond(request, DataOutputStream(response), 2000, revision, hardware)
                        session.write(output, "response", response.toByteArray())
                        if (stop) return
                    } catch (_: Exception) {
                        // Unauthenticated or malformed clients receive no command execution.
                    }
                }
            }
        }
    }

    private fun respond(input: DataInputStream, output: DataOutputStream, uid: Int, revision: Long,
                        hardware: HardwareSnapshotReader): Boolean {
        val op = input.readInt()
        val result = when (op) {
            RunnerProtocol.HELLO -> BoundedCommand.Result("$uid|$revision", 0)
            RunnerProtocol.EXEC -> BoundedCommand.run(RunnerProtocol.read(input))
            RunnerProtocol.HARDWARE -> BoundedCommand.Result(hardware.readSnapshot(), 0)
            RunnerProtocol.THERMAL -> BoundedCommand.Result(hardware.readThermals(), 0)
            RunnerProtocol.STOP -> BoundedCommand.Result("", 0)
            else -> BoundedCommand.Result("未知操作", -2)
        }
        output.writeInt(result.exitCode)
        RunnerProtocol.write(output, result.output)
        output.flush()
        return op == RunnerProtocol.STOP
    }
}
