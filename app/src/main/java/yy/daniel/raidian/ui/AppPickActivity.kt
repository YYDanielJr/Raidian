package yy.daniel.raidian.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme
import yy.daniel.raidian.RaidianApp
import yy.daniel.raidian.monitor.SessionRecorder

/**
 * 指定应用记录的选择页:列出可启动应用供挑选为"记录目标"。
 *
 * 列表第一行是「跟随当前前台(不指定)」—— 选择后清除门控,记录器回到
 * 原来的"谁在前台就记谁"。其余为设备上所有带启动入口的应用(名称/包名
 * 可过滤),点某行即设为记录目标并返回主页。
 *
 * 选择结果写入 SessionRecorder 的 watchTarget(SharedPreferences 持久化),
 * 主页「会话记录」卡实时反映。之后开始记录时,仅当目标应用在前台才采样。
 */
class AppPickActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Miuix SearchBar 内部调用 NavigationBackHandler,需要
            // LocalNavigationEventDispatcherOwner(与 RecordsActivity #13 同款,
            // 见 AGENTS.md);普通 ComponentActivity 无 navigation3 宿主,
            // 必须在根组合显式提供,否则进入页面即崩溃。
            val owner = rememberNavigationEventDispatcherOwner(enabled = true, parent = null)
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                MiuixTheme {
                    AppPickScreen(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** 设备上一个可启动应用(名称 + 包名 + 图标) */
private data class AppEntry(val label: String, val pkg: String, val icon: android.graphics.drawable.Drawable?)

@Composable
private fun AppPickScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val raidian = context.applicationContext as RaidianApp
    val watch by raidian.recorder.watchTarget.collectAsState()

    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) } // null = 加载中

    // 异步枚举可启动应用(后台线程,避免卡 UI)
    LaunchedEffect(Unit) {
        apps = withContext(kotlinx.coroutines.Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(intent, 0)
            resolved
                .map { it.activityInfo }
                .filter { it.packageName != context.packageName }
                .distinctBy { it.packageName }
                .mapNotNull { ai ->
                    val label = try {
                        ai.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }
                    } catch (e: Exception) { null }
                    if (label != null) {
                        val icon = try { ai.loadIcon(pm) } catch (e: Exception) { null }
                        AppEntry(label, ai.packageName, icon)
                    } else null
                }
                .sortedBy { it.label.lowercase() }
        }
    }

    fun pick(entry: AppEntry?) {
        raidian.recorder.setWatchTarget(entry?.let { SessionRecorder.WatchTarget(it.pkg, it.label) })
        (context.findActivity())?.finish()
    }

    val q = query.trim().lowercase()
    val filtered = apps?.filter {
        q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q)
    } ?: emptyList()

    // 搜索框常开:作为顶栏下的过滤条(不展开为整页)
    val expanded = false

    SubPageScaffold(
        title = "选择记录目标",
        onBack = { (context.findActivity())?.finish() },
        content = { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    // 说明
                    Text(
                        "开始记录后，仅当所选应用在前台时才会采样；切走自动暂停，回来自动续录。",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Spacer(Modifier.height(4.dp))

                    // 搜索过滤条
                    SearchBar(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        inputField = {
                            InputField(
                                query = query,
                                onQueryChange = { query = it },
                                onSearch = { },
                                expanded = expanded,
                                onExpandedChange = { },
                                label = "搜索应用",
                            )
                        },
                        expanded = expanded,
                        onExpandedChange = { },
                    ) { }
                    Spacer(Modifier.height(4.dp))

                    // 顶部"跟随前台"选项
                    SectionHeader("通用")
                    HyperListGroup(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        HyperListRow(onClick = { pick(null) }) {
                            ListRowContent(
                                title = "跟随当前前台应用",
                                summary = if (watch == null) "当前选中" else "不指定，谁在前台记谁",
                                trailing = {
                                    if (watch == null) {
                                        Icon(MiuixIcons.Basic.Check, contentDescription = null,
                                            tint = MiuixTheme.colorScheme.primary)
                                    }
                                },
                            )
                        }
                    }

                    // 应用列表
                    SectionHeader(if (q.isEmpty()) "全部应用" else "搜索结果")
                    if (apps == null) {
                        HyperCard {
                            Text("正在加载应用列表…", fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    } else if (filtered.isEmpty()) {
                        HyperCard {
                            Text("没有匹配的应用", fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    } else {
                        HyperListGroup(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            filtered.forEach { app ->
                                val isCurrent = watch?.pkg == app.pkg
                                HyperListRow(onClick = { pick(app) }) {
                                    ListRowContent(
                                        title = app.label,
                                        summary = app.pkg,
                                        leading = { AppIcon(app.icon) },
                                        trailing = {
                                            if (isCurrent) {
                                                Icon(MiuixIcons.Basic.Check, contentDescription = null,
                                                    tint = MiuixTheme.colorScheme.primary)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        },
    )
}

/** 从任意 Context 向上找宿主 Activity(非 Activity 上下文返回 null) */
private tailrec fun android.content.Context.findActivity(): android.app.Activity? =
    when (this) {
        is android.app.Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }
