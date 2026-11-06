package yy.daniel.raidian

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import yy.daniel.raidian.privilege.*
import yy.daniel.raidian.monitor.MetricsParser

/** Tests real app_process services. Run on a disposable emulator with shell ADB (not adb root). */
class PrivilegeIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as RaidianApp
    private fun shell(command: String): String {
        // UiAutomation uses Runtime.exec(String), which does NOT parse shell quotes.
        val pipes = instrumentation.uiAutomation.executeShellCommandRw("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { it.write((command + "\nexit\n").toByteArray()) }
        return ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader().use { it.readText() }
    }

    @Test fun defaultAdbDisconnectAndReconnect() = runBlocking {
        val manager = app.privileges
        manager.select(AccessMode.DEFAULT)
        assertFalse(manager.connected)
        assertTrue(app.executor.execute("id -u").trim().toInt() >= 10000)
        val basic = app.sampler.sample(0)
        assertFalse(basic.privileged)
        assertTrue(basic.batteryLevel in 0..100)
        manager.select(AccessMode.COMPUTER_ADB)
        manager.stop()
        shell(manager.startCommand())
        delay(1000)
        RunnerClient(false, RunnerIdentity.token(app)).call(RunnerProtocol.HELLO)
        repeat(30) { if (!manager.connected) { delay(200); manager.refresh() } }
        assertTrue(manager.state.value.toString(), manager.connected)
        assertEquals("2000", app.executor.execute("id -u").trim())
        assertThrows(Exception::class.java) { RunnerClient(false, "0".repeat(64)).call(RunnerProtocol.EXEC, "id") }
        assertEquals("2000", app.executor.execute("id -u").trim())
        assertTrue(MetricsParser.blocks(app.executor.readHardwareSnapshot()).containsKey("PRESENT"))
        assertTrue(app.executor.execute("dumpsys -t 1 battery").contains("level:"))
        val start = SystemClock.elapsedRealtime()
        assertEquals("", app.executor.execute("sleep 10; echo should_not_appear"))
        assertTrue("command must be bounded", SystemClock.elapsedRealtime() - start < 7000)
        assertEquals("alive", app.executor.execute("echo alive"))

        manager.select(AccessMode.DEFAULT)
        assertTrue(app.executor.execute("id -u").trim().toInt() >= 10000)
        assertFalse(app.sampler.sample(1000).privileged)
        manager.select(AccessMode.COMPUTER_ADB)
        assertTrue("service survives mode changes", manager.connected)
        assertEquals("2000", app.executor.execute("id -u").trim())
        manager.stop()
        assertFalse(manager.connected)
        assertTrue(app.executor.execute("id -u").trim().toInt() >= 10000)
        shell(manager.startCommand())
        repeat(30) { if (!manager.connected) { delay(200); manager.refresh() } }
        assertTrue("service reconnects after restart", manager.connected)
        manager.stop()
        manager.select(AccessMode.DEFAULT)
    }

    @Test fun rootStartupAndSampling() = runBlocking {
        // Opt in explicitly: this test may display the device root authorization dialog.
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("root") == "true")
        val manager = app.privileges
        manager.select(AccessMode.ROOT)
        manager.startRoot()
        assertTrue(manager.state.value.toString(), manager.connected)
        assertEquals("0", app.executor.execute("id -u").trim())
        assertTrue(app.sampler.sample(0).privileged)
        assertTrue(app.executor.execute("dumpsys -t 1 battery").contains("level:"))
        manager.stop()
        manager.select(AccessMode.DEFAULT)
    }

    @Test fun wirelessPairAndStart() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(arguments.containsKey("connectPort") || arguments.containsKey("pairPort"))
        val manager = app.privileges
        manager.select(AccessMode.WIRELESS_ADB)
        if (arguments.containsKey("pairPort")) {
            manager.pair(arguments.getString("pairPort")!!.toInt(), arguments.getString("pairCode")!!)
            assertTrue(manager.state.value.toString(), manager.state.value.message.startsWith("配对成功"))
        }
        manager.startWireless(arguments.getString("connectPort")?.toInt()?.takeIf { it > 0 })
        assertTrue(manager.state.value.toString(), manager.connected)
        assertEquals("2000", app.executor.execute("id -u").trim())
        assertTrue(app.sampler.sample(0).privileged)
        manager.stop()
        // New WirelessAdb instance loads persisted identity; no second pairing.
        manager.startWireless(arguments.getString("connectPort")?.toInt()?.takeIf { it > 0 })
        assertTrue(manager.state.value.toString(), manager.connected)
        manager.stop()
        manager.select(AccessMode.DEFAULT)
    }

    @Test fun optimizedReleaseRunnerEntryPoint() = runBlocking {
        val apk = InstrumentationRegistry.getArguments().getString("releaseApk")
        org.junit.Assume.assumeTrue(apk != null)
        val manager = app.privileges
        manager.select(AccessMode.ROOT)
        manager.stop()
        val command = manager.startCommand().replace(app.applicationInfo.sourceDir, apk!!)
        val result = yy.daniel.raidian.monitor.BoundedCommand.run(command, 30_000, listOf("su", "-c"))
        assertEquals(result.output, 0, result.exitCode)
        val client = RunnerClient(true, RunnerIdentity.token(app))
        try {
            delay(2000)
            assertEquals("0", client.call(RunnerProtocol.EXEC, "id -u").output.trim())
            assertTrue(client.call(RunnerProtocol.HARDWARE).output.contains("@@PRESENT"))
        } finally {
            client.call(RunnerProtocol.STOP)
            manager.select(AccessMode.DEFAULT)
        }
    }
}
