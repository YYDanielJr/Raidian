package yy.daniel.raidian

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import yy.daniel.raidian.monitor.DeviceProfile
import yy.daniel.raidian.monitor.Sample
import java.io.File

/** Opt-in device smoke test. Run with the authorized O1 tablet and installed Endfield. */
@RunWith(AndroidJUnit4::class)
class XringSamplingTest {
    @Test fun parsesThermalDumpOnAndroidRegexEngine() {
        val dump = "Current temperatures from HAL:\nTemperature{mValue=42.5, mType=0, mName=cpu-9, mStatus=0}\nCurrent cooling devices from HAL:"
        assertEquals(42.5f, yy.daniel.raidian.monitor.MetricsParser.thermalHal(dump).single().celsius)
    }
    @Test fun recordsTenCoresAndLiveHalWhileGameIsForeground() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        File(context.filesDir, "xring-test-progress.txt").writeText("")
        fun progress(step: String) { File(context.filesDir, "xring-test-progress.txt").appendText("$step\n") }
        progress("start")
        val app = context.applicationContext as RaidianApp
        assumeTrue(DeviceProfile.detect().isXring)
        app.shizuku.refresh()
        assertTrue("请先在 Shizuku 授权 Raidian", app.shizuku.available())
        val game = "com.hypergryph.endfield"
        val launcher = context.packageManager.getLaunchIntentForPackage(game)
        assumeTrue(launcher != null)
        fun launch(component: String) {
            instrumentation.uiAutomation.executeShellCommand("am start -W -n $component").use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
            }
        }
        launch("yy.daniel.raidian/.MainActivity")
        delay(2000)
        progress("main started")
        instrumentation.runOnMainSync { app.recorder.start(game, "明日方舟：终末地", game) }
        progress("recording started")
        launch(launcher!!.component!!.flattenToString())
        progress("game started")
        val samples = mutableListOf<Sample>()
        try {
            repeat(60) { index ->
                if (index == 20) {
                    launch("yy.daniel.raidian/.MainActivity")
                    delay(2500)
                    assertTrue("切出游戏未暂停", app.recorder.paused.value)
                    val csv = File(app.recorder.currentSessionDir, "samples.csv")
                    val pausedSize = csv.length()
                    delay(1500)
                    assertEquals("暂停后仍写入游戏记录", pausedSize, csv.length())
                    instrumentation.runOnMainSync { app.overlayController.show() }
                    launch(launcher.component!!.flattenToString())
                    progress("pause/resume and overlay enabled")
                }
                delay(1000)
                samples += app.sampler.sample(SystemClock.elapsedRealtime())
                progress("sample ${samples.size}: ${samples.last().frontPkg} fps=${samples.last().fps}")
            }
        } finally {
            instrumentation.runOnMainSync { app.recorder.stop(); app.overlayController.hide() }
            File(context.filesDir, "xring-validation.json").writeText(Json.encodeToString(samples))
        }
        val gameSamples = samples.filter { it.frontPkg == game }
        assertTrue("未识别到游戏前台", gameSamples.size >= 10)
        assertTrue("遗漏 CPU 8/9", gameSamples.all { it.perCoreFreqMhz.size == 10 })
        assertTrue("未读取到大核频率", gameSamples.any { it.perCoreFreqMhz[9] > 0 })
        assertTrue("温度无效", gameSamples.any { it.maxCpuTemp in 10f..120f && it.gpuTemp in 10f..120f })
        assertTrue("游戏帧率无有效值", gameSamples.any { it.fps in 1f..144f })
        assertTrue("CPU 占用无效", gameSamples.any { it.perCoreLoad.size == 10 && it.perCoreLoad.all { load -> load in 0f..100f } })
        val dir = app.recorder.currentSessionDir!!
        val (header, rows) = app.sessionStore.readSamplesWithHeader(dir)
        assertTrue(header.contains("cpu9_freq_mhz"))
        assertTrue(rows.size >= 20)
        assertTrue(rows.all { it.size == header.size })
        val temps = header.indexOf("thermal_json")
        assertTrue(rows.any { Json.decodeFromString<List<yy.daniel.raidian.monitor.ThermalReading>>(it[temps]).size >= 20 })
    }
}
