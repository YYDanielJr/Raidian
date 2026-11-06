package yy.daniel.raidian

import org.junit.Assert.*
import org.junit.Test
import yy.daniel.raidian.privilege.RunnerProtocol
import java.io.*

class RunnerProtocolTest {
    @Test fun largeUnicodePayloadRoundTripsWithoutWriteUtfLimit() {
        val text = "温度与功耗\n".repeat(30_000)
        val bytes = ByteArrayOutputStream()
        RunnerProtocol.write(DataOutputStream(bytes), text)
        assertEquals(text, RunnerProtocol.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))))
    }
    @Test fun rejectsNegativeAndOversizeFramesBeforeAllocation() {
        for (size in listOf(-1, RunnerProtocol.MAX_BYTES + 1, Int.MAX_VALUE)) {
            val bytes = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(size) }.toByteArray()
            assertThrows(IllegalArgumentException::class.java) {
                RunnerProtocol.read(DataInputStream(ByteArrayInputStream(bytes)))
            }
        }
    }
    @Test fun rejectsTruncatedFrame() {
        val bytes = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(20); writeByte(0) } }.toByteArray()
        assertThrows(EOFException::class.java) { RunnerProtocol.read(DataInputStream(ByteArrayInputStream(bytes))) }
    }
    @Test fun separatesAndroidUsersAndPrivilegeIdentities() {
        assertNotEquals(RunnerProtocol.socketName(10001, false), RunnerProtocol.socketName(110001, false))
        assertNotEquals(RunnerProtocol.socketName(10001, false), RunnerProtocol.socketName(10001, true))
    }
    @Test fun quotesShellMetacharacters() {
        assertEquals("'a'\"'\"'b;\$(id)'", RunnerProtocol.quote("a'b;\$(id)"))
    }
}
