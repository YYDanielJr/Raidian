package yy.daniel.raidian

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import yy.daniel.raidian.privilege.AccessMode
import yy.daniel.raidian.ui.PrivilegeActivity
import java.io.File

class PrivilegeUiTest {
    @get:Rule val compose = createAndroidComposeRule<PrivilegeActivity>()

    @Test fun modesRootButtonAndRecording() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("root") == "true")
        val app = compose.activity.application as RaidianApp
        runBlocking { app.privileges.select(AccessMode.DEFAULT) }
        compose.onNodeWithText("电脑 ADB").performScrollTo().performClick()
        compose.onNodeWithText("复制启动命令").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("无线 ADB").performScrollTo().performClick()
        compose.onNodeWithText("连接并启动").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("root 权限").performScrollTo().performClick()
        compose.onNodeWithText("请求 root 并启动").performScrollTo().performClick()
        compose.waitUntil(15_000) { app.privileges.connected }
        assertEquals(0, app.privileges.state.value.uid)
        compose.runOnIdle { app.recorder.start("", "权限通道回归", null) }
        val dir = app.recorder.currentSessionDir!!
        try {
            compose.waitUntil(15_000) {
                File(dir, "samples.csv").takeIf { it.exists() }?.readLines()?.size?.let { it >= 12 } == true
            }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(app.filesDir, "privilege-ui.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        } finally {
            compose.runOnIdle { app.recorder.stop() }
            runBlocking { app.privileges.stop(); app.privileges.select(AccessMode.DEFAULT) }
        }
        assertTrue(File(dir, "session.json").readText().contains("recordedSamples"))
    }
}
