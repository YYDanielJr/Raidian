package yy.daniel.raidian.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.AppRecording
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import yy.daniel.raidian.overlay.OverlayWindow

/**
 * 通用设置页 Activity。
 *
 * 从主页顶栏"设置"按钮进入。底部 NavigationBar 两个分页:
 *  - 「实时曲线设置」:主页实时曲线各指标(九宫格全部指标)的显示开关 + 配色;
 *  - 「悬浮窗设置」:悬浮窗开关、悬浮窗行显示项、触摸穿透、字号等全部悬浮窗相关设置。
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MiuixTheme {
                SettingsScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
fun SettingsScreen(modifier: Modifier = Modifier, vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val prefs = vm.overlayPrefs
    val overlayShown by vm.overlayShown.collectAsState()
    val canOverlay = Settings.canDrawOverlays(context)

    // 页面内本地状态(初始取配置值,改动同步写配置)
    var enabled by remember { mutableStateOf(prefs.enabledItems()) }
    var touchThrough by remember { mutableStateOf(prefs.touchThrough()) }
    var textSize by remember { mutableIntStateOf(prefs.textSizeSp()) }
    // 底部导航当前页:0 实时曲线设置 / 1 悬浮窗设置
    var tab by remember { mutableIntStateOf(0) }

    fun updateEnabled(newSet: Set<String>) {
        enabled = newSet
        vm.overlayController.setEnabledItems(newSet)
    }
    fun updateTouch(v: Boolean) {
        touchThrough = v
        vm.overlayController.setTouchThrough(v)
    }
    fun updateSize(v: Int) {
        textSize = v.coerceIn(8, 18)
        vm.overlayController.setTextSize(textSize)
    }

    fun toggleOverlayOrGuide() {
        if (canOverlay) {
            vm.overlayController.toggle()
        } else {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")
                )
            )
        }
    }

    val tabTitle = when (tab) {
        1 -> "悬浮窗设置"
        else -> "实时曲线设置"
    }

    SubPageScaffold(
        title = "设置",
        subtitle = tabTitle,
        onBack = { (context as? android.app.Activity)?.finish() },
        content = { padding ->
            val tablet = isTablet()
            if (tablet) {
                // ---- 平板/大屏:左侧 NavigationRail + 右侧内容(信息密度高、符合大屏导航习惯)----
                Row(modifier = Modifier.fillMaxSize().padding(padding)) {
                    // 左侧边导航:展开态显示图标 + 文字(平板信息密度高,常显标签)
                    val expand = windowWidthDp() >= 1000
                    val railState = rememberNavigationRailState(initialValue = if (expand)
                        top.yukonga.miuix.kmp.basic.NavigationRailValue.Expanded else top.yukonga.miuix.kmp.basic.NavigationRailValue.Collapsed)
                    androidx.compose.runtime.LaunchedEffect(expand) { if (expand) railState.expand() else railState.collapse() }
                    NavigationRail(state = railState, defaultWindowInsetsPadding = false, showDivider = false,
                        expandContentDescription = "展开导航", collapseContentDescription = "收起导航") {
                        NavigationRailItem(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            icon = MiuixIcons.AppRecording,
                            label = "实时曲线设置",
                        )
                        NavigationRailItem(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            icon = MiuixIcons.Settings,
                            label = "悬浮窗设置",
                        )
                    }
                    // 右侧内容:独立滚动
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(bottom = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = TabletContentMaxWidthDp.dp)
                                .fillMaxWidth()
                        ) {
                            SettingsBody(
                                tab = tab,
                                canOverlay = canOverlay,
                                overlayShown = overlayShown,
                                enabled = enabled,
                                touchThrough = touchThrough,
                                textSize = textSize,
                                onUpdateEnabled = ::updateEnabled,
                                onTouch = ::updateTouch,
                                onSize = ::updateSize,
                                onWizard = {
                                    context.startActivity(
                                        Intent(context, yy.daniel.raidian.SetupWizardActivity::class.java)
                                            .putExtra(yy.daniel.raidian.SetupWizardActivity.EXTRA_FIRST_RUN, false)
                                    )
                                },
                                onToggleOverlay = { toggleOverlayOrGuide() },
                            )
                        }
                    }
                }
            } else {
                // ---- 手机:内容 + 底部导航栏 ----
                val navigationBackdrop = rememberUiBackdrop()
                val blurSupported = isMiuixBlurSupported()
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (blurSupported) Modifier.layerBackdrop(navigationBackdrop)
                                else Modifier
                            )
                            .verticalScroll(rememberScrollState())
                            .padding(padding)
                            .padding(bottom = 80.dp) // 给底部导航栏让位
                    ) {
                        SettingsBody(
                            tab = tab,
                            canOverlay = canOverlay,
                            overlayShown = overlayShown,
                            enabled = enabled,
                            touchThrough = touchThrough,
                            textSize = textSize,
                            onUpdateEnabled = ::updateEnabled,
                            onTouch = ::updateTouch,
                            onSize = ::updateSize,
                            onWizard = {
                                context.startActivity(
                                    Intent(context, yy.daniel.raidian.SetupWizardActivity::class.java)
                                        .putExtra(yy.daniel.raidian.SetupWizardActivity.EXTRA_FIRST_RUN, false)
                                )
                            },
                            onToggleOverlay = { toggleOverlayOrGuide() },
                        )
                    }
                    // 底部导航栏(普通整条贴底)
                    NavigationBar(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .height(70.dp)
                            .miuixNavigationBlur(
                                backdrop = navigationBackdrop,
                                shape = RectangleShape,
                                tintAlpha = 0.72f,
                            ),
                        color = if (blurSupported) Color.Transparent else MiuixTheme.colorScheme.surface,
                        showDivider = !blurSupported,
                    ) {
                        NavigationBarItem(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            icon = MiuixIcons.AppRecording,
                            label = "实时曲线设置",
                        )
                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            icon = MiuixIcons.Settings,
                            label = "悬浮窗设置",
                        )
                    }
                }
            }
        },
    )
}

/**
 * 设置页内容主体(设置向导入口 + 当前分页内容),手机/平板共用。
 * 滚动容器由调用方提供。
 */
@Composable
private fun SettingsBody(
    tab: Int,
    canOverlay: Boolean,
    overlayShown: Boolean,
    enabled: Set<String>,
    touchThrough: Boolean,
    textSize: Int,
    onUpdateEnabled: (Set<String>) -> Unit,
    onTouch: (Boolean) -> Unit,
    onSize: (Int) -> Unit,
    onWizard: () -> Unit,
    onToggleOverlay: () -> Unit,
) {
    Column {
        // 重新运行设置向导入口:授权状态变化 / 换机 / 想重调偏好时可再次
        // 打开首次设置向导(向导可补 Shizuku 与悬浮窗授权,与本次设置同源)
        HyperListGroup(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            HyperListRow(onClick = onWizard) {
                ListRowContent(
                    title = "设置向导",
                    summary = "重新运行首次设置：补授权、重选悬浮窗与实时曲线偏好",
                )
            }
        }

        BackgroundSettingsRow()
        when (tab) {
            // ---------- Tab 0:实时曲线设置 ----------
            0 -> {
                HyperListGroup(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    LiveChartPrefs.ALL.forEach { item ->
                        chartItemRow(item)
                    }
                }
                Text(
                    "主页按以上设置绘制曲线。部分设备未开放 GPU 频率或占用接口，未读取到的项目显示占位。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Spacer(Modifier.height(6.dp))
            }

            // ---------- Tab 1:悬浮窗设置(开关 + 显示项 + 外观) ----------
            else -> {
                // 悬浮窗开关(整行可点)
                SectionHeader("悬浮窗")
                HyperListGroup(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    HyperListRow(onClick = onToggleOverlay) {
                        ListRowContent(
                            title = if (canOverlay) "悬浮窗" else "悬浮窗（未授权）",
                            summary = when {
                                !canOverlay -> "未授予悬浮窗权限，点击前往系统设置"
                                overlayShown -> "已显示，点击隐藏"
                                else -> "未显示，点击显示"
                            },
                            trailing = {
                                if (canOverlay) {
                                    Switch(checked = overlayShown, onCheckedChange = null)
                                }
                            },
                        )
                    }
                }

                // 悬浮窗行显示项(键与 OverlayWindow.ALL_ITEMS 对应)
                SectionHeader("显示项")
                HyperListGroup(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    itemSwitch("帧率", OverlayWindow.ITEM_FPS, enabled, onUpdateEnabled)
                    itemSwitch("CPU 频率 / 占用", OverlayWindow.ITEM_CPU, enabled, onUpdateEnabled)
                    itemSwitch("GPU 占用", OverlayWindow.ITEM_GPU, enabled, onUpdateEnabled)
                    itemSwitch("电池 / 核心温度", OverlayWindow.ITEM_TEMP, enabled, onUpdateEnabled)
                    itemSwitch("功耗 / 电量", OverlayWindow.ITEM_POWER, enabled, onUpdateEnabled)
                    itemSwitch("内存占用", OverlayWindow.ITEM_MEM, enabled, onUpdateEnabled)
                    itemSwitch("前台应用", OverlayWindow.ITEM_FRONT, enabled, onUpdateEnabled)
                    itemSwitch("网络（暂无数据）", OverlayWindow.ITEM_NET, enabled, onUpdateEnabled)
                }

                // 触摸穿透
                SectionHeader("交互")
                HyperListGroup(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    HyperListRow(onClick = { onTouch(!touchThrough) }) {
                        ListRowContent(
                            title = "触摸穿透",
                            summary = if (touchThrough) "已开启：悬浮窗不拦截触摸（适合游戏中）"
                            else "已关闭：悬浮窗可点按拖动，单击切换穿透",
                            trailing = {
                                Switch(checked = touchThrough, onCheckedChange = null)
                            },
                        )
                    }
                }

                // 字号(8~18sp,步进 1)
                SectionHeader("字号")
                HyperListGroup(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    HyperListRow {
                        ListRowContent(
                            title = "悬浮窗字号",
                            summary = "当前 ${textSize}sp（范围 8~18）",
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    SmallStepButton("－", { onSize(textSize - 1) })
                                    Spacer(Modifier.width(6.dp))
                                    SmallStepButton("＋", { onSize(textSize + 1) })
                                }
                            },
                        )
                    }
                }

                Text(
                    "GPU 数据取决于设备开放的接口。空闲为 0%，没有读取权限时显示 --。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/** 显示项开关行:整行可点切换,尾部 Switch 展示当前状态 */
@Composable
private fun itemSwitch(
    title: String,
    key: String,
    enabled: Set<String>,
    onChange: (Set<String>) -> Unit,
) {
    val on = key in enabled
    HyperListRow(onClick = {
        onChange(if (on) enabled - key else enabled + key)
    }) {
        ListRowContent(
            title = title,
            trailing = {
                Switch(checked = on, onCheckedChange = null)
            },
        )
    }
}

/** 字号加减用的小按钮 */
@Composable
private fun SmallStepButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.width(52.dp).height(36.dp),
        insideMargin = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * 实时曲线配置行:曲线名 + 颜色圆点(取第 1 系列色)+ 显示开关。
 * 点颜色圆点展开色板:单线指标直接选色;多线指标(如 CPU 频率三簇)逐系列一行选色。
 * 整行点击切换显示/隐藏。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun chartItemRow(
    item: LiveChartPrefs.ChartItem,
) {
    val context = LocalContext.current
    var on by androidx.compose.runtime.remember(item.key) {
        androidx.compose.runtime.mutableStateOf(LiveChartPrefs.isOn(context, item.key))
    }
    var colors by androidx.compose.runtime.remember(item.key) {
        androidx.compose.runtime.mutableStateOf(
            item.series.indices.map { LiveChartPrefs.seriesColor(context, item.key, it) }
        )
    }
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    Column {
        // 主行:点整行切换开关
        HyperListRow(onClick = {
            on = !on
            LiveChartPrefs.setOn(context, item.key, on)
        }) {
            ListRowContent(
                title = item.label + if (item.multiLine) "（多线）" else "",
                summary = if (item.multiLine) item.series.joinToString(" ") { it.label } else null,
                trailing = {
                    // 颜色圆点:点击展开选色(不触发整行开关)
                    Box(
                        modifier = Modifier
                            .padding(end = 10.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(colors.firstOrNull() ?: Color.Gray)
                            .clickable { expanded = !expanded },
                    )
                    Switch(checked = on, onCheckedChange = null)
                },
            )
        }
        // 展开:每系列一行 "系列名 + 当前色点 + 色板"
        if (expanded) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 10.dp)) {
                item.series.forEachIndexed { si, s ->
                    if (item.multiLine) {
                        Text(
                            s.label,
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                        )
                    } else {
                        Spacer(Modifier.height(6.dp))
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LiveChartPrefs.MATERIAL_COLORS.forEach { c ->
                            val isCur = colors.getOrNull(si) == c
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .then(
                                        if (isCur) Modifier.border(
                                            2.dp, MiuixTheme.colorScheme.primary, CircleShape
                                        ) else Modifier
                                    )
                                    .clickable {
                                        colors = colors.toMutableList().also { it[si] = c }
                                        LiveChartPrefs.setSeriesColor(context, item.key, si, c)
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}
