package yy.daniel.raidian.privilege

import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Mutual nonce authentication for loopback TCP. The installation secret never crosses the socket. */
object RunnerAuthentication {
    private fun nonce() = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private fun bytes(input: DataInputStream) = ByteArray(32).also(input::readFully)
    private fun proof(token: String, role: String, appUid: Int, client: ByteArray, server: ByteArray): ByteArray {
        require(token.matches(Regex("[0-9a-f]{64}")))
        val key = token.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            update("Raidian/${RunnerProtocol.VERSION}/$appUid/2000/$role".toByteArray(Charsets.US_ASCII))
            update(client)
            doFinal(server)
        }
    }
    class Session internal constructor(private val key: ByteArray) {
        private fun tag(direction: String, payload: ByteArray) = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            update(direction.toByteArray(Charsets.US_ASCII))
            doFinal(payload)
        }
        fun write(output: DataOutputStream, direction: String, payload: ByteArray) {
            require(payload.size <= RunnerProtocol.MAX_BYTES + 16)
            output.writeInt(payload.size)
            output.write(payload)
            output.write(tag(direction, payload))
            output.flush()
        }
        fun read(input: DataInputStream, direction: String): ByteArray {
            val size = input.readInt()
            require(size in 0..RunnerProtocol.MAX_BYTES + 16)
            val payload = ByteArray(size).also(input::readFully)
            check(MessageDigest.isEqual(bytes(input), tag(direction, payload))) { "采集消息认证失败" }
            return payload
        }
    }
    fun client(input: DataInputStream, output: DataOutputStream, token: String, appUid: Int): Session {
        val client = nonce()
        output.writeInt(RunnerProtocol.VERSION)
        output.write(client)
        output.flush()
        val server = bytes(input)
        check(MessageDigest.isEqual(bytes(input), proof(token, "server", appUid, client, server))) { "采集服务认证失败" }
        output.write(proof(token, "client", appUid, client, server))
        output.flush()
        return Session(proof(token, "session", appUid, client, server))
    }
    fun server(input: DataInputStream, output: DataOutputStream, token: String, appUid: Int): Session {
        check(input.readInt() == RunnerProtocol.VERSION)
        val client = bytes(input)
        val server = nonce()
        output.write(server)
        output.write(proof(token, "server", appUid, client, server))
        output.flush()
        check(MessageDigest.isEqual(bytes(input), proof(token, "client", appUid, client, server))) { "客户端认证失败" }
        return Session(proof(token, "session", appUid, client, server))
    }
}
