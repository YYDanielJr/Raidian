package yy.daniel.raidian.privilege

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import yy.daniel.raidian.monitor.BoundedCommand
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.io.ByteArrayOutputStream

class RunnerClient(private val root: Boolean, private val token: String, private val appUid: Int = Process.myUid()) {
    fun call(operation: Int, command: String = ""): BoundedCommand.Result =
        if (root) callRoot(operation, command) else callAdb(operation, command)

    private fun callRoot(operation: Int, command: String): BoundedCommand.Result = LocalSocket().use { socket ->
        socket.connect(LocalSocketAddress(RunnerProtocol.socketName(appUid, root)))
        // LocalSocket creates its native fd lazily in connect(); setOption before that throws.
        socket.soTimeout = 6500
        check(socket.peerCredentials.uid == if (root) 0 else 2000) { "采集进程身份不匹配" }
        val output = DataOutputStream(socket.outputStream)
        output.writeInt(RunnerProtocol.ROOT_VERSION)
        output.write(token.toByteArray(Charsets.US_ASCII))
        exchange(DataInputStream(socket.inputStream), output, operation, command)
    }

    private fun callAdb(operation: Int, command: String): BoundedCommand.Result {
        var failure: Exception? = null
        for (port in RunnerProtocol.ports(appUid)) {
            val socket = Socket()
            val session: RunnerAuthentication.Session
            try {
                socket.connect(InetSocketAddress("127.0.0.1", port), 400)
                socket.soTimeout = 1000
                socket.tcpNoDelay = true
                session = RunnerAuthentication.client(DataInputStream(socket.getInputStream()),
                    DataOutputStream(socket.getOutputStream()), token, appUid)
            } catch (e: Exception) {
                socket.close()
                failure = e
                continue
            }
            // Once authenticated, never retry an operation: it may already have executed.
            return socket.use {
                it.soTimeout = 6500
                val request = ByteArrayOutputStream().also { buffer ->
                    DataOutputStream(buffer).apply {
                        writeInt(operation)
                        if (operation == RunnerProtocol.EXEC) RunnerProtocol.write(this, command)
                    }
                }.toByteArray()
                session.write(DataOutputStream(it.getOutputStream()), "request", request)
                val reply = DataInputStream(session.read(DataInputStream(it.getInputStream()), "response").inputStream())
                val exitCode = reply.readInt()
                BoundedCommand.Result(RunnerProtocol.read(reply), exitCode)
            }
        }
        throw failure ?: java.io.IOException("采集服务未启动")
    }

    private fun exchange(input: DataInputStream, output: DataOutputStream, operation: Int, command: String): BoundedCommand.Result {
        output.writeInt(operation)
        if (operation == RunnerProtocol.EXEC) RunnerProtocol.write(output, command)
        output.flush()
        val exitCode = input.readInt()
        return BoundedCommand.Result(RunnerProtocol.read(input), exitCode)
    }
}
