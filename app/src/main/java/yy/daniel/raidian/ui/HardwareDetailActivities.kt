package yy.daniel.raidian.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import top.yukonga.miuix.kmp.theme.MiuixTheme
import yy.daniel.raidian.monitor.Sample

class CpuDetailsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHardwareContent("CPU 详情") { CpuDetailsContent(it) }
    }
}

class RuntimeStatusActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHardwareContent("运行状态详情") { RuntimeStatusContent(it) }
    }
}

private fun ComponentActivity.setHardwareContent(title: String, content: @Composable (Sample) -> Unit) {
    enableEdgeToEdge()
    setContent {
        val owner = rememberNavigationEventDispatcherOwner(enabled = true, parent = null)
        CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
            MiuixTheme {
                HardwareDetailScreen(title = title, onBack = { finish() }, content = content)
            }
        }
    }
}

/** 详情页使用共享采样源；仅页面可见时轮询，悬浮窗运行时复用它的更新。 */
@Composable
private fun HardwareDetailScreen(
    title: String,
    onBack: () -> Unit,
    content: @Composable (Sample) -> Unit,
    vm: MainViewModel = viewModel(),
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, vm) {
        val observer = LifecycleEventObserver { _, _ ->
            vm.setUiVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        }
        lifecycle.addObserver(observer)
        vm.setUiVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            lifecycle.removeObserver(observer)
            vm.setUiVisible(false)
        }
    }
    LaunchedEffect(vm) {
        vm.refreshPrivileges()
        vm.startUiPolling()
    }
    val sample by vm.lastSample.collectAsStateWithLifecycle()
    SubPageScaffold(title = title, onBack = onBack) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                content(sample)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
