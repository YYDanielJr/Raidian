package yy.daniel.raidian

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import top.yukonga.miuix.kmp.theme.MiuixTheme
import yy.daniel.raidian.ui.MainScreen
import yy.daniel.raidian.ui.theme.RaidianTheme

/**
 * 主界面入口 Activity。
 *
 * 只做两件事:边缘到边(沉浸) + 用 MiuixTheme 包住 MainScreen。
 * 全部业务状态在 [MainViewModel],悬浮窗/采样等常驻工作在 Application 级
 * ([RaidianApp.overlayController]),Activity 销毁不影响它们。
 *
 * 首次启动(尚未完成设置向导)时,先进入 [SetupWizardActivity] 完成
 * 授权与偏好设置,向导结束后回到主页。
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
            MiuixTheme {
                MainScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** Android Studio 预览用(不经过 RaidianTheme,便于快速看布局) */
@Preview(showBackground = true)
@Composable
fun RaidianPreview() {
    MiuixTheme {
        MainScreen()
    }
}
