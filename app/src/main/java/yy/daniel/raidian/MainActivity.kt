package yy.daniel.raidian

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import top.yukonga.miuix.kmp.theme.MiuixTheme
import yy.daniel.raidian.ui.HomeScreen

/**
 * 主界面入口 Activity。
 *
 * 承载 [HomeScreen]:底部导航(手机为悬浮胶囊栏,平板为左侧栏)在四个并列功能页之间切换 ——
 * 「实时监控」「续航曲线」「充电曲线」「记录」。全部业务状态在 [MainViewModel],
 * 悬浮窗/采样/曲线记录等常驻工作在 Application 级([RaidianApp.overlayController]、
 * [RaidianApp.batteryCurves]),Activity 销毁不影响它们。
 *
 * 首次启动(尚未完成设置向导)时,先进入 [SetupWizardActivity] 完成授权与偏好设置。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 首次启动:路由到设置向导(完成标记见 SetupPrefs)。二次进入(设置页
        // 打开向导)由向导自身 finish 回到来源页,不影响这里的判断。
        if (SetupPrefs(this).completed.not() && savedInstanceState == null) {
            startActivity(
                Intent(this, SetupWizardActivity::class.java)
                    .putExtra(SetupWizardActivity.EXTRA_FIRST_RUN, true)
            )
        }
        enableEdgeToEdge()
        setContent {
            // Miuix 的 OverlayDialog(记录页的多选删除确认)内部无条件调用
            // NavigationBackHandler,需要组合树里存在 NavigationEventDispatcherOwner;
            // 独立 Activity 里没有 navigation3 提供它,必须在此显式注入(踩坑记录见 AGENTS #13)。
            // parent = null 必须显式传:组合根没有可继承的父 dispatcher。
            val owner = rememberNavigationEventDispatcherOwner(enabled = true, parent = null)
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                MiuixTheme {
                    HomeScreen(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** Android Studio 预览用(不经过 RaidianTheme,便于快速看布局) */
@Preview(showBackground = true)
@Composable
fun RaidianPreview() {
    MiuixTheme {
        HomeScreen()
    }
}
