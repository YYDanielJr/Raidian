package yy.daniel.raidian.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import yy.daniel.raidian.monitor.SessionMeta
import java.io.File

/**
 * 记录页 Activity(历史记录数据):会话历史列表。
 *
 * 从主页顶栏"记录"按钮进入。列表行用 HyperOS 风格分组列表
 * ([HyperListGroup]/[HyperListRow]/[ListRowContent]):行内容贴容器、
 * 无上下内边距、无分割线、按压反馈随行实际区域(容器圆角裁切首/末行)。
 *
 * 交互:
 *  - 点击某条会话 → 新 Activity([SessionDetailActivity])打开详情;
 *  - 长按某条会话 → 进入多选管理:可勾选多条,顶栏右侧"全选/取消全选"
 *    (SelectAll 图标),底部中央红色删除按钮,点击弹出 Miuix OverlayDialog
 *    删除确认,一次删除多条。
 */
class RecordsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Miuix 的 OverlayDialog(经 DialogContentLayout)内部无条件调用
            // NavigationBackHandler,它需要组合树里存在 LocalNavigationEventDispatcherOwner,
            // 否则抛 IllegalStateException 崩溃(见 AGENTS.md #13)。普通 ComponentActivity
            // 没有 navigation3 宿主,必须在根组合显式提供:
            //  - rememberNavigationEventDispatcherOwner(enabled, parent = null):创建根级
            //    dispatcher。注意 parent 必须显式传 null —— 省略会去读当前 Local 值(无则
            //    抛同样的错)。
            //  - CompositionLocalProvider 把它注入子树,让对话框内的 NavigationBackHandler
            //    能注册 handler 而不崩溃。
            val owner = rememberNavigationEventDispatcherOwner(enabled = true, parent = null)
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                MiuixTheme {
                    RecordsScreen(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
fun RecordsScreen(modifier: Modifier = Modifier, vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val sessions by vm.sessions.collectAsState()

    // 多选管理状态:null = 未进入多选;Set = 已选中的会话目录绝对路径
    var selected by remember { mutableStateOf<Set<String>?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val selecting = selected != null
    val allKeys = sessions.map { it.dir.absolutePath }

    // 进页面:刷新会话列表
    LaunchedEffect(Unit) { vm.refreshSessions() }

    // 多选时系统返回键 = 退出多选(不删除)
    BackHandler(enabled = selecting) {
        selected = null
    }
    // 删除确认对话框显示时,系统返回键 = 关闭对话框(注意:本宿主无 navigation3
    // 把系统返回事件桥接进上方的 NavigationEventDispatcher,对话框内建的返回处理
    // 收不到事件,需在此显式拦截;该 BackHandler 在后组合、优先于上面的多选退出)
    BackHandler(enabled = showDeleteDialog) {
        showDeleteDialog = false
    }

    fun toggleSelect(path: String) {
        val cur = selected ?: return
        selected = if (path in cur) cur - path else cur + path
    }

    Box(modifier = modifier.fillMaxSize()) {
        SubPageScaffold(
            // 进入选择状态不改标题:标题始终是"历史记录数据"
            title = "历史记录数据",
            subtitle = if (selecting) "已选 ${selected?.size ?: 0} 项" else "",
            onBack = {
                if (selecting) selected = null
                else (context.findActivity())?.finish()
            },
            actions = {
                if (selecting) {
                    // 全选 / 取消全选:SelectAll 图标按钮,已全选 = 蓝色,否则黑色
                    val allSelected = allKeys.isNotEmpty() && selected?.containsAll(allKeys) == true
                    IconButton(
                        onClick = {
                            selected = if (allSelected) emptySet() else allKeys.toSet()
                        },
                    ) {
                        Icon(
                            imageVector = MiuixIcons.SelectAll,
                            contentDescription = if (allSelected) "取消全选" else "全选",
                            tint = if (allSelected) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
            content = { padding ->
                Box(modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)) {
                    SessionListPage(
                        vm = vm,
                        selecting = selecting,
                        selectedPaths = selected ?: emptySet(),
                        onOpen = { dir -> context.startActivity(SessionDetailActivity.intent(context, dir)) },
                        onLongPress = { path ->
                            // 长按:进入多选并把当前行选中
                            if (selected == null) selected = setOf(path)
                            else selected = (selected ?: emptySet()) + path
                        },
                        onToggle = { path -> toggleSelect(path) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 多选态:底部中央红色删除按钮
                    if (selecting) {
                        DeleteFab(
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 28.dp),
                        )
                    }
                }
                // 删除确认对话框:必须放在 content 槽内 —— Scaffold 在这里提供
                // LocalDialogStates 且其自带 MiuixPopupHost 渲染这些状态;放外面
                // 会落在 CompositionLocal 作用域之外,dialog 不会显示。
                if (showDeleteDialog) {
                    val count = selected?.size ?: 0
                    OverlayDialog(
                        show = showDeleteDialog,
                        title = "删除记录",
                        summary = "确定删除选中的 $count 条会话记录吗？删除后不可恢复。",
                        onDismissRequest = { showDeleteDialog = false },
                    ) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            TextButton(
                                text = "取消",
                                textStyle = TextStyle(fontWeight = FontWeight.Medium, fontSize = 17.sp),
                                onClick = { showDeleteDialog = false },
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            // 删除:蓝色实心按钮(主题 primary 底 + 白字),HyperOS 一般按钮样式
                            Button(
                                onClick = {
                                    val dirs = sessions
                                        .filter { selected?.contains(it.dir.absolutePath) == true }
                                        .map { it.dir }
                                    vm.deleteSessions(dirs)
                                    showDeleteDialog = false
                                    selected = null
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColorsPrimary(),
                            ) {
                                Text("删除", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            },
        )
    }
}

/** 底部中央红色圆形删除按钮(浮空,含删除图标) */
@Composable
private fun DeleteFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = Color(0xFFE53935),
    ) {
        Icon(
            imageVector = MiuixIcons.Delete,
            contentDescription = "删除选中记录",
            tint = Color.White,
        )
    }
}

/** 从任意 Context 向上找宿主 Activity(非 Activity 上下文返回 null) */
private tailrec fun android.content.Context.findActivity(): android.app.Activity? =
    when (this) {
        is android.app.Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }

// ==================== 记录列表页 ====================

/**
 * 记录列表页:正在记录提示 + 历史会话列表。
 * [selecting] 为 true 时进入多选:行首勾选框替换行尾箭头,点击行切换勾选;
 * 否则行尾保留 ">" 箭头,点击进详情、长按进入多选。
 */
@Composable
private fun SessionListPage(
    vm: MainViewModel,
    selecting: Boolean,
    selectedPaths: Set<String>,
    onOpen: (File) -> Unit,
    onLongPress: (String) -> Unit,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val running by vm.recorder.running.collectAsState()
    val sessions by vm.sessions.collectAsState()

    // 记录一结束就刷新列表(能看到刚结束的会话)
    LaunchedEffect(running) {
        if (!running) vm.refreshSessions()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        if (running) {
            SectionHeader("正在记录")
            HyperCard {
                Text(
                    "记录中，可回到主页实时页停止。结束后本列表自动刷新。",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        SectionHeader(if (selecting) "选择要删除的记录" else "历史会话")
        if (sessions.isEmpty()) {
            HyperCard {
                Text(
                    text = "暂无记录。在主页实时页点「开始记录」后，数据会写入本机文件。",
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        } else {
            // 单个 HyperOS 分组列表:行紧贴容器、无分割线、统一高度
            HyperListGroup(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                sessions.forEach { item ->
                    val dirPath = item.dir.absolutePath
                    val checked = dirPath in selectedPaths
                    if (selecting) {
                        // 多选态:行尾显示勾选框(点击行 = 切换勾选)
                        HyperListRow(onClick = { onToggle(dirPath) }) {
                            ListRowContent(
                                title = item.meta?.targetLabel?.takeIf { it.isNotBlank() }
                                    ?: item.meta?.targetPkg?.takeIf { it.isNotBlank() }
                                    ?: item.dir.name,
                                summary = sessionSummary(item.dir, item.meta),
                                trailing = {
                                    Checkbox(
                                        state = if (checked) ToggleableState.On else ToggleableState.Off,
                                        onClick = { onToggle(dirPath) },
                                    )
                                },
                            )
                        }
                    } else {
                        // 普通态:行尾保留 ">" 箭头,点击进详情、长按进入多选
                        HyperListRow(
                            onClick = { onOpen(item.dir) },
                            onLongClick = { onLongPress(dirPath) },
                        ) {
                            ListRowContent(
                                title = item.meta?.targetLabel?.takeIf { it.isNotBlank() }
                                    ?: item.meta?.targetPkg?.takeIf { it.isNotBlank() }
                                    ?: item.dir.name,
                                summary = sessionSummary(item.dir, item.meta),
                            )
                        }
                    }
                }
            }
        }
        // 底部留白给悬浮删除按钮让路
        Spacer(Modifier.height(96.dp))
    }
}

/** 会话摘要副文本:开始时间 · 时长 · 帧率统计 · 均功耗(无则省略对应段) */
private fun sessionSummary(dir: File, meta: SessionMeta?): String {
    val parts = ArrayList<String>()
    val time = meta?.startTime ?: 0L
    parts += if (time > 0)
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(time))
    else dir.name
    val dur = meta?.durationMs ?: 0L
    parts += if (dur > 0) {
        val s = dur / 1000
        String.format("%d分%02d秒", s / 60, s % 60)
    } else "--"
    val fps = meta?.fpsStats
    parts += if (fps != null && fps.sampleCount > 0)
        String.format("平均%.1f · 1%%Low %.1f", fps.avgFps, fps.low1Fps)
    else "无帧率数据"
    val power = meta?.avgPowerMw
    if (power != null && power > 0) {
        parts += if (power > 1000) String.format("均功耗 %.2fW", power / 1000)
        else String.format("均功耗 %.0fmW", power)
    }
    return parts.joinToString(" · ")
}
