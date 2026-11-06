package yy.daniel.raidian.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import yy.daniel.raidian.RaidianApp
import yy.daniel.raidian.privilege.AccessMode
import yy.daniel.raidian.privilege.PrivilegeManager

class PrivilegeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val owner = rememberNavigationEventDispatcherOwner(enabled = true, parent = null)
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                MiuixTheme { PrivilegeScreen() }
            }
        }
    }
}

@Composable
fun PrivilegeSettingsRow() {
    val context = LocalContext.current
    HyperListGroup(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        HyperListRow(onClick = { context.startActivity(Intent(context, PrivilegeActivity::class.java)) }) {
            ListRowContent(title = "采集权限", summary = "默认权限、无线 ADB、电脑 ADB、root")
        }
    }
}

@Composable
fun PrivilegeStatusCard(manager: PrivilegeManager) {
    val state by manager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Text("${state.mode.label} · ${if (state.connected) "已连接，UID ${state.uid}" else "基础采集"}")
    Spacer(Modifier.height(8.dp))
    Text(state.message, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    Spacer(Modifier.height(12.dp))
    Button(onClick = { context.startActivity(Intent(context, PrivilegeActivity::class.java)) },
        modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColorsPrimary()) { Text("管理采集权限") }
}

@Composable
private fun PrivilegeScreen() {
    val context = LocalContext.current
    val manager = RaidianApp.get(context).privileges
    val state by manager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pairingPort by rememberSaveable { mutableStateOf("") }
    // Never persist the short-lived pairing secret, including saved-instance state.
    var pairingCode by remember { mutableStateOf("") }
    var connectPort by rememberSaveable { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    var networkMessage by remember { mutableStateOf("") }
    val networkPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { networkMessage = ""; scope.launch { manager.startWireless(null) } }
        else networkMessage = "未允许自动发现。可手动填写本机连接端口后重试。"
    }
    SubPageScaffold(title = "采集权限", onBack = { (context as? Activity)?.finish() }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                SectionHeader("权限方式")
                HyperListGroup(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    AccessMode.entries.forEach { mode ->
                        HyperListRow(onClick = { if (!state.busy) scope.launch { manager.select(mode) } }) {
                            ListRowContent(title = mode.label, summary = when (mode) {
                                AccessMode.DEFAULT -> "应用自身权限，无需授权"
                                AccessMode.WIRELESS_ADB -> "通过系统无线调试配对，无需电脑"
                                AccessMode.COMPUTER_ADB -> "在电脑执行启动命令，支持 USB 或无线连接"
                                AccessMode.ROOT -> "通过设备的 root 管理器授权"
                            }, trailing = { if (state.mode == mode) Text("已选", color = MiuixTheme.colorScheme.primary) })
                        }
                    }
                }
                HyperCard {
                    Text(if (state.connected) "服务已连接 · UID ${state.uid}" else "当前使用默认权限采集")
                    Spacer(Modifier.height(8.dp))
                    Text(if (state.busy) "正在处理，请稍候…" else state.message)
                    if (state.mode != AccessMode.DEFAULT) {
                        Spacer(Modifier.height(12.dp))
                        Button(enabled = !state.busy, onClick = { scope.launch { manager.refresh() } },
                            modifier = Modifier.fillMaxWidth()) { Text("刷新连接") }
                        Button(enabled = !state.busy, onClick = { scope.launch { manager.stop() } },
                            modifier = Modifier.fillMaxWidth()) { Text("停止所选服务") }
                    }
                }
                when (state.mode) {
                    AccessMode.DEFAULT -> HyperCard {
                        Text("可记录电池温度、电量、电流及系统允许读取的数据。跨应用帧率和部分硬件指标可能不可用，缺失数据显示为「—」。")
                    }
                    AccessMode.ROOT -> HyperCard {
                        Text("设备需已具备 root。请在 root 管理器中允许 Raidian；部分管理器会在点击启动后询问。启动失败不会自动改用 ADB。")
                        Spacer(Modifier.height(12.dp))
                        Button(enabled = !state.busy, onClick = { scope.launch { manager.startRoot() } },
                            colors = ButtonDefaults.buttonColorsPrimary(), modifier = Modifier.fillMaxWidth()) { Text("请求 root 并启动") }
                    }
                    AccessMode.COMPUTER_ADB -> HyperCard {
                        Text("打开 USB 调试并允许电脑连接，或在电脑完成无线 ADB 配对。确认 adb devices 显示设备后执行下方命令。多台设备时，在 adb 后加上 -s 设备序列号。")
                        Spacer(Modifier.height(12.dp))
                        SelectionContainer { Text(manager.computerCommand()) }
                        Button(onClick = {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                ClipData.newPlainText("Raidian ADB", manager.computerCommand()))
                            copied = true
                        }, modifier = Modifier.fillMaxWidth()) { Text(if (copied) "已复制命令" else "复制启动命令") }
                        Text("命令包含本次安装的连接凭据，请勿分享。适用于 PowerShell、命令提示符、macOS 和 Linux 终端。应用更新后请停止旧服务，再复制新命令。")
                    }
                    AccessMode.WIRELESS_ADB -> HyperCard {
                        Text("在开发者选项中开启「无线调试」。建议分屏打开本页与系统设置，再选择「使用配对码配对设备」。配对端口与连接端口不同，请勿混用。")
                        Button(onClick = {
                            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                        }, modifier = Modifier.fillMaxWidth()) { Text("打开开发者选项") }
                        PortField(pairingPort, { pairingPort = it }, "配对窗口中的端口")
                        PortField(pairingCode, { pairingCode = it.take(6) }, "六位配对码")
                        Button(enabled = !state.busy && pairingPort.toIntOrNull() in 1..65535 && pairingCode.length == 6,
                            onClick = { val code = pairingCode; pairingCode = ""; scope.launch { manager.pair(pairingPort.toInt(), code) } },
                            modifier = Modifier.fillMaxWidth()) { Text("配对") }
                        Spacer(Modifier.height(12.dp))
                        PortField(connectPort, { connectPort = it }, "无线调试主页的连接端口（留空自动发现）")
                        Button(enabled = !state.busy && (connectPort.isBlank() || connectPort.toIntOrNull() in 1..65535),
                            onClick = {
                                if (connectPort.isBlank() && Build.VERSION.SDK_INT >= 37 &&
                                    context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
                                    networkPermission.launch("android.permission.ACCESS_LOCAL_NETWORK")
                                } else scope.launch { manager.startWireless(connectPort.toIntOrNull()) }
                            },
                            colors = ButtonDefaults.buttonColorsPrimary(), modifier = Modifier.fillMaxWidth()) { Text("连接并启动") }
                        Text("仅连接本机。配对通常只需一次；无线调试关闭或网络变化后，连接端口可能改变。")
                        if (networkMessage.isNotEmpty()) Text(networkMessage)
                    }
                }
                HyperCard {
                    Text("ADB 与 root 启动的是 Raidian 内置采集服务，无需 Shizuku。启动成功后可断开电脑或关闭无线调试。设备重启后需要重新启动服务；应用更新后也需重启服务。系统或厂商仍可能限制部分指标。")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun PortField(value: String, onChange: (String) -> Unit, label: String) {
    TextField(value = value, onValueChange = { onChange(it.filter { c -> c in '0'..'9' }.take(6)) },
        label = label, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
}
