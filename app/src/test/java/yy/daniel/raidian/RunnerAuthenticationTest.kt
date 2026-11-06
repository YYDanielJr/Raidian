package yy.daniel.raidian

import org.junit.Assert.*
import org.junit.Test
import yy.daniel.raidian.privilege.RunnerAuthentication
import yy.daniel.raidian.privilege.RunnerProtocol
import java.io.*
import java.net.*
import java.util.concurrent.*

class RunnerAuthenticationTest {
    private val token = "12".repeat(32)
    private fun handshake(clientToken: String = token, clientUid: Int = 10279): Pair<Boolean, Boolean> {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val server = executor.submit<Boolean> {
                    listener.accept().use {
                        it.soTimeout = 2000
                        runCatching {
                            RunnerAuthentication.server(DataInputStream(it.getInputStream()), DataOutputStream(it.getOutputStream()), token, 10279)
                        }.isSuccess
                    }
                }
                val client = Socket("127.0.0.1", listener.localPort).use {
                    it.soTimeout = 2000
                    runCatching {
                        RunnerAuthentication.client(DataInputStream(it.getInputStream()), DataOutputStream(it.getOutputStream()), clientToken, clientUid)
                    }.isSuccess
                }
                return client to server.get(3, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun bothSidesAuthenticate() { assertEquals(true to true, handshake()) }
    @Test fun incorrectSecretRejectedByBothSides() { assertEquals(false to false, handshake("34".repeat(32))) }
    @Test fun differentAndroidUserRejected() { assertEquals(false to false, handshake(clientUid = 110279)) }
    @Test fun replayedClientProofCannotAuthenticateFreshChallenge() {
        val input = ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                writeInt(RunnerProtocol.VERSION)
                write(ByteArray(32))
                write(ByteArray(32)) // A guessed or previous response, not this challenge's HMAC.
            }
        }.toByteArray()
        val responses = (0..1).map {
            val output = ByteArrayOutputStream()
            assertThrows(IllegalStateException::class.java) {
                RunnerAuthentication.server(DataInputStream(ByteArrayInputStream(input)), DataOutputStream(output), token, 10279)
            }
            output.toByteArray()
        }
        assertFalse(responses[0].contentEquals(responses[1]))
        assertFalse(String(responses[0], Charsets.ISO_8859_1).contains(token))
    }
    @Test fun portCandidatesAreBoundedAndDistinct() {
        for (uid in listOf(10000, 10279, 110279, Int.MAX_VALUE)) {
            val ports = RunnerProtocol.ports(uid)
            assertEquals(8, ports.toSet().size)
            assertTrue(ports.all { it in 32000..59999 })
        }
    }
    @Test fun messagesRejectTamperingReflectionAndOtherSessions() {
        val session = RunnerAuthentication.Session(ByteArray(32) { 7 })
        val payload = "read-only test".toByteArray()
        val encoded = ByteArrayOutputStream().also {
            session.write(DataOutputStream(it), "request", payload)
        }.toByteArray()
        assertArrayEquals(payload, session.read(DataInputStream(encoded.inputStream()), "request"))
        for ((reader, direction, data) in listOf(
            Triple(session, "response", encoded),
            Triple(RunnerAuthentication.Session(ByteArray(32) { 8 }), "request", encoded),
            Triple(session, "request", encoded.clone().apply { this[4] = (this[4].toInt() xor 1).toByte() })
        )) {
            assertThrows(IllegalStateException::class.java) { reader.read(DataInputStream(data.inputStream()), direction) }
        }
    }
}
