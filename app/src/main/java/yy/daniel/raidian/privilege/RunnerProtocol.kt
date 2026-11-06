package yy.daniel.raidian.privilege

import java.io.DataInputStream
import java.io.DataOutputStream

/** Length-prefixed UTF-8, including dumpsys output larger than writeUTF's 64 KiB limit. */
object RunnerProtocol {
    const val VERSION = 3
    const val ROOT_VERSION = 2 // Keep the already validated root Unix-socket protocol unchanged.
    const val MAX_BYTES = 2 * 1024 * 1024
    const val HELLO = 0
    const val EXEC = 1
    const val HARDWARE = 2
    const val THERMAL = 3
    const val STOP = 4
    fun socketName(uid: Int, root: Boolean) = "yy.daniel.raidian.runner.$uid.${if (root) "root" else "adb"}"
    // A small bounded set permits unrelated local listeners without binding a wildcard address.
    fun ports(uid: Int) = (0 until 8).map { 32000 + ((uid.toLong() * 31 + it * 7919) % 28000).toInt() }
    fun write(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "响应过大" }
        out.writeInt(bytes.size)
        out.write(bytes)
    }
    fun read(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 0..MAX_BYTES) { "无效的数据长度" }
        return ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)
    }
    fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
}
