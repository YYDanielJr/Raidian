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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    PermissionTitle("${state.mode.label} · ${if (state.connected) "已连接" else "基础采集"}")
    Spacer(Modifier.height(8.dp))
    PermissionNote(state.message)
    Spacer(Modifier.height(20.dp))
    PermissionButton("管理采集权限", onClick = {
        context.startActivity(Intent(context, PrivilegeActivity::class.java))
    })
}

@Composable
private fun PrivilegeScreen() {
    val context = LocalContext.current
    val manager = RaidianApp.get(context).privileges
    val state by manager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var pairingPort by rememberSaveable { mutableStateOf("") }
    // Never persist the short-lived pairing secret, including saved-instance state.
    var pairingCode by remember { mutableStateOf("") }
    var connectPort by rememberSaveable { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    var commandExpanded by remember { mutableStateOf(false) }
    var networkMessage by remember { mutableStateOf("") }
    val networkPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            networkMessage = ""
            scope.launch { manager.startWireless(null) }
        } else networkMessage = "未允许自动发现。可手动填写本机连接端口后重试。"
    }
    SubPageScaffold(title = "采集权限", onBack = { (context as? Activity)?.finish() }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                SectionHeader("权限方式")
                HyperListGroup(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    AccessMode.entries.forEach { mode ->
                        HyperListRow(
                            onClick = if (state.busy) null else ({
                                focus.clearFocus()
                                commandExpanded = false
                                copied = false
                                networkMessage = ""
                                scope.launch { manager.select(mode) }
                            }),
                            modifier = Modifier.semantics { selected = state.mode == mode },
                        ) {
                            ListRowContent(
                                title = mode.label,
                                summary = when (mode) {
                                    AccessMode.DEFAULT -> "使用应用自身权限，无需授权"
                                    AccessMode.WIRELESS_ADB -> "通过本机无线调试启动，无需电脑"
                                    AccessMode.COMPUTER_ADB -> "通过电脑启动，支持 USB 或无线连接"
                                    AccessMode.ROOT -> "通过设备的 root 管理器授权"
                                },
                                trailing = {
                                    RadioButton(selected = state.mode == mode, onClick = null, enabled = !state.busy)
                                },
                            )
                        }
                    }
                }

                SectionHeader("连接状态")
                HyperCard {
                    Text(
                        text = when {
                            state.busy -> "正在处理，请稍候…"
                            state.mode == AccessMode.DEFAULT -> "默认权限采集"
                            state.connected -> "采集服务已连接"
                            else -> "采集服务未连接"
                        },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (state.connected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    PermissionNote(state.message)
                    if (state.mode != AccessMode.DEFAULT) {
                        Spacer(Modifier.height(8.dp))
                        PermissionNote(
                            if (state.connected) "当前通过${state.mode.label}采集。"
                            else "暂用默认权限，可用数据以系统开放范围为准。",
                        )
                        Spacer(Modifier.height(20.dp))
                        ConnectionActions(
                            enabled = !state.busy,
                            onRefresh = { scope.launch { manager.refresh() } },
                            onStop = { scope.launch { manager.stop() } },
                        )
                    }
                }

                when (state.mode) {
                    AccessMode.DEFAULT -> {
                        SectionHeader("可用信息")
                        PermissionCard(
                            title = "基础数据直接查看",
                            description = "可记录电池温度、电量、电流及系统允许读取的其他数据。跨应用帧率和部分硬件指标需要 ADB 或 root 权限。",
                        ) {
                            Spacer(Modifier.height(12.dp))
                            PermissionNote("无法读取的指标显示为「—」，不代表数值为零。")
                        }
                    }
                    AccessMode.ROOT -> {
                        SectionHeader("root 授权")
                        PermissionCard(
                            title = "允许 Raidian 使用 root",
                            description = "设备需已具备 root。请在 root 管理器中允许 Raidian，部分管理器会在启动时询问。",
                        ) {
                            Spacer(Modifier.height(20.dp))
                            PermissionButton("请求 root 并启动", enabled = !state.busy) {
                                scope.launch { manager.startRoot() }
                            }
                        }
                        PermissionFootnote("若未出现授权提示，请打开 root 管理器手动允许 Raidian，再返回重试。")
                    }
                    AccessMode.COMPUTER_ADB -> {
                        SectionHeader("电脑启动")
                        PermissionCard(
                            title = "在电脑执行启动命令",
                            description = "开启 USB 调试并允许电脑连接，或在电脑完成无线 ADB 配对。确认 adb devices 中设备已连接，再复制命令到电脑终端执行。",
                        ) {
                            Spacer(Modifier.height(20.dp))
                            PermissionButton(if (copied) "已复制命令" else "复制启动命令") {
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                    ClipData.newPlainText("Raidian ADB", manager.computerCommand()),
                                )
                                copied = true
                            }
                            Spacer(Modifier.height(12.dp))
                            TextButton(
                                text = if (commandExpanded) "收起启动命令" else "查看启动命令",
                                onClick = { commandExpanded = !commandExpanded },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            )
                            if (commandExpanded) {
                                Spacer(Modifier.height(16.dp))
                                SelectionContainer {
                                    Text(
                                        text = manager.computerCommand(),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        lineHeight = 19.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.fillMaxWidth()
                                            .background(MiuixTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                                            .padding(16.dp),
                                    )
                                }
                            }
                        }
                        PermissionFootnote("命令包含本次安装的连接凭据，请勿分享。支持 PowerShell、命令提示符、macOS 和 Linux 终端。")
                        PermissionFootnote("连接多台设备时，在 adb 后添加 -s 设备序列号。应用更新后，请停止旧服务，再复制并执行新命令。")
                    }
                    AccessMode.WIRELESS_ADB -> {
                        SectionHeader("无线启动")
                        PermissionCard(
                            title = "1．开启无线调试",
                            description = "进入开发者选项，开启「无线调试」。用分屏或小窗让 Raidian 与系统设置同时可见。",
                        ) {
                            Spacer(Modifier.height(20.dp))
                            PermissionButton("打开开发者选项") {
                                runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                            }
                        }
                        PermissionFootnote("系统设置不支持分屏时，可将 Raidian 放入小窗。")
                        PermissionCard(
                            title = "2．配对设备",
                            description = "在系统无线调试中选择「使用配对码配对设备」，填写窗口中的端口和六位配对码。",
                        ) {
                            Spacer(Modifier.height(16.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                PortField(pairingPort, { pairingPort = it }, "配对端口", enabled = !state.busy)
                                PortField(pairingCode, { pairingCode = it }, "六位配对码", enabled = !state.busy, maxLength = 6)
                            }
                            Spacer(Modifier.height(20.dp))
                            PermissionButton(
                                "配对",
                                enabled = !state.busy && pairingPort.toIntOrNull() in 1..65535 && pairingCode.length == 6,
                            ) {
                                focus.clearFocus()
                                val code = pairingCode
                                pairingCode = ""
                                scope.launch { manager.pair(pairingPort.toInt(), code) }
                            }
                        }
                        PermissionFootnote("配对期间请保留系统配对窗口，关闭后端口可能失效。已配对过的设备可直接连接。")
                        PermissionCard(
                            title = "3．启动采集服务",
                            description = "填写无线调试主页显示的连接端口，留空则自动发现。连接端口与配对端口不同。",
                        ) {
                            Spacer(Modifier.height(16.dp))
                            PortField(connectPort, { connectPort = it }, "连接端口（可留空）", enabled = !state.busy)
                            Spacer(Modifier.height(20.dp))
                            PermissionButton(
                                "连接并启动",
                                enabled = !state.busy && (connectPort.isBlank() || connectPort.toIntOrNull() in 1..65535),
                            ) {
                                focus.clearFocus()
                                networkMessage = ""
                                if (connectPort.isBlank() && Build.VERSION.SDK_INT >= 37 &&
                                    context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
                                    networkPermission.launch("android.permission.ACCESS_LOCAL_NETWORK")
                                } else scope.launch { manager.startWireless(connectPort.toIntOrNull()) }
                            }
                            if (networkMessage.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                PermissionNote(networkMessage)
                            }
                        }
                        PermissionFootnote("仅连接本机。无线调试开关或网络变化后，连接端口可能改变。")
                    }
                }
                SectionHeader("使用说明")
                PermissionFootnote("ADB 与 root 模式使用 Raidian 内置采集服务，无需安装 Shizuku。启动成功后可断开电脑或关闭无线调试。")
                PermissionFootnote("设备重启或应用更新后，需要重新启动采集服务。系统或厂商仍可能限制部分指标。")
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Follow the home cards: 17 sp heading, 14/22 sp notes, 20 dp before actions. */
@Composable
private fun PermissionCard(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    HyperCard {
        PermissionTitle(title)
        Spacer(Modifier.height(8.dp))
        PermissionNote(description)
        content()
    }
}

@Composable
private fun PermissionTitle(text: String) {
    Text(text, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
}

@Composable
private fun PermissionNote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontSize = 14.sp, lineHeight = 22.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun PermissionFootnote(text: String) {
    PermissionNote(text, Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 6.dp))
}

@Composable
private fun PermissionButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = true,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = enabled,
        colors = if (primary) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ConnectionActions(enabled: Boolean, onRefresh: () -> Unit, onStop: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Fit two full labels on tablets; keep comfortable touch targets on phones / large text.
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        if (maxWidth >= (400 * fontScale).dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PermissionButton("刷新连接", Modifier.weight(1f), enabled, onClick = onRefresh)
                PermissionButton("停止所选服务", Modifier.weight(1f), enabled, primary = false, onClick = onStop)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PermissionButton("刷新连接", enabled = enabled, onClick = onRefresh)
                PermissionButton("停止所选服务", enabled = enabled, primary = false, onClick = onStop)
            }
        }
    }
}

@Composable
private fun PortField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean, maxLength: Int = 5) {
    val focus = LocalFocusManager.current
    TextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c in '0'..'9' }.take(maxLength)) },
        label = label, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth(),
    )
}
