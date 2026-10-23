package yy.daniel.raidian.ui

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay

/** 系统授权页／首次引导结束后，恢复的页面必须重新读取权限并触发重组。 */
@Composable
fun rememberOverlayPermission(): State<Boolean> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val granted = remember(context) { mutableStateOf(Settings.canDrawOverlays(context)) }
    LaunchedEffect(context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            granted.value = Settings.canDrawOverlays(context)
            // 部分 ROM 的 AppOps 更新略晚于 Activity 恢复。
            delay(300)
            granted.value = Settings.canDrawOverlays(context)
        }
    }
    return granted
}
