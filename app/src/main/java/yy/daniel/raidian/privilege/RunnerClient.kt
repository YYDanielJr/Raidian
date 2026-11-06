package yy.daniel.raidian.privilege

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import yy.daniel.raidian.monitor.BoundedCommand
import java.io.DataInputStream
import java.io.DataOutputStream

class RunnerClient(private val root: Boolean, private val token: String, private val appUid: Int = Process.myUid()) {
    fun call(operation: Int, command: String = ""): BoundedCommand.Result = LocalSocket().use { socket ->
        socket.connect(LocalSocketAddress(RunnerProtocol.socketName(appUid, root)))
        // LocalSocket creates its native fd lazily in connect(); setOption before that throws.
        socket.soTimeout = 6500
        check(socket.peerCredentials.uid == if (root) 0 else 2000) { "采集进程身份不匹配" }
        val output = DataOutputStream(socket.outputStream)
        output.writeInt(RunnerProtocol.VERSION)
        output.write(token.toByteArray(Charsets.US_ASCII))
        output.writeInt(operation)
        if (operation == RunnerProtocol.EXEC) RunnerProtocol.write(output, command)
        output.flush()
        val input = DataInputStream(socket.inputStream)
        val exitCode = input.readInt()
        BoundedCommand.Result(RunnerProtocol.read(input), exitCode)
    }
}
