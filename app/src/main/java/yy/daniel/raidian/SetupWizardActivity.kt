package yy.daniel.raidian

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import yy.daniel.raidian.overlay.OverlayWindow
import yy.daniel.raidian.ui.HyperCard
import yy.daniel.raidian.ui.HyperListGroup
import yy.daniel.raidian.ui.HyperListRow
import yy.daniel.raidian.ui.ListMainText
import yy.daniel.raidian.ui.ListRowContent
import yy.daniel.raidian.ui.ListSecondaryText
import yy.daniel.raidian.ui.LiveChartPrefs
import yy.daniel.raidian.ui.SectionHeader

/**
 * 首次设置向导(仿 HyperOS 首次开机设置)。
 *
 * 应用首次启动时由 MainActivity 路由到这里;也可以在主页「设置」里随时重新
 * 打开(二次进入,重新授权 / 调整默认偏好)。
 *
 * 向导共 6 步,按权限 → 偏好的顺序:
 *  0 欢迎         应用说明与将要完成的设置项
 *  1 Shizuku 授权 核心:未运行 → 引导打开 Shizuku;已运行未授权 → 请求授权
 *  2 悬浮窗权限   未授予 → 去系统设置授权
 *  3 悬浮窗设置   悬浮窗显示项 + 触摸穿透
 *  4 实时曲线     主页实时曲线显示哪些指标
 *  5 完成         状态汇总 + 开始使用
 *
 * 本页不依赖 MainViewModel(它是 Activity 级),直接读 Application 级后端
 * (RaidianApp 的 shizuku / overlayController),与主页同源。
 */
class SetupWizardActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Miuix 部分组件(如对话框)内部需要 LocalNavigationEventDispatcherOwner,
            // 普通 ComponentActivity 无 navigation3 宿主,根组合显式提供(见 AGENTS.md #13)
            val owner = rememberNavigationEventDispatcherOwner(enabled = true, parent = null)
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                MiuixTheme {
                    SetupWizardScreen(
                        firstRun = intent?.getBooleanExtra(EXTRA_FIRST_RUN, false) ?: false,
                    )
                }
            }
        }
    }

    companion object {
        /** 是否由 MainActivity 首次启动路由进入（决定返回键的"跳过"语义） */
        const val EXTRA_FIRST_RUN = "first_run"
    }
}

/** 向导总页数 */
private const val PAGE_COUNT = 6

@Composable
private fun SetupWizardScreen(firstRun: Boolean) {
    val context = LocalContext.current
    val app = RaidianApp.get(context)
    val setupPrefs = remember { SetupPrefs(context) }

    // ---- 当前步骤(0..PAGE_COUNT-1)----
    var page by remember { mutableIntStateOf(0) }

    // ---- 实时状态(定时刷新:Shizuku 授权 / 悬浮窗权限都是去外部系统操作后返回)----
    var shizukuOk by remember { mutableStateOf(app.shizuku.available()) }
    var shizukuAlive by remember { mutableStateOf(app.shizuku.isBinderAlive()) }
    val overlayGranted by yy.daniel.raidian.ui.rememberOverlayPermission()
    val shizukuInstalled = remember { isShizukuInstalled(context) }

    LaunchedEffect(Unit) {
        while (true) {
            app.shizuku.refresh()
            shizukuOk = app.shizuku.available()
            shizukuAlive = app.shizuku.isBinderAlive()
            delay(800)
        }
    }

    // ---- 悬浮窗偏好(向导内调整即写配置,与设置页同源)----
    var enabledItems by remember { mutableStateOf(app.overlayController.prefs.enabledItems()) }
    var touchThrough by remember { mutableStateOf(app.overlayController.prefs.touchThrough()) }
    fun updateEnabled(newSet: Set<String>) {
        enabledItems = newSet
        app.overlayController.setEnabledItems(newSet)
    }
    fun updateTouch(v: Boolean) {
        touchThrough = v
        app.overlayController.setTouchThrough(v)
    }

    /** 进入主页:置"已完成"，关闭向导（首次启动时落到下方主页，二次进入时回到设置页） */
    fun enterApp() {
        setupPrefs.completed = true
        (context as? android.app.Activity)?.finish()
    }

    // 返回键:步骤内先回上一步；第 0 步时，首次启动视为"稍后设置"直接跳过
    // (记已完成避免下次冷启动再次弹出),二次进入则直接返回
    BackHandler {
        if (page > 0) {
            page--
        } else {
            if (firstRun) setupPrefs.completed = true
            (context as? android.app.Activity)?.finish()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 顶部:返回 + 步骤指示点(HyperOS 设置页圆点样式)
        Box(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .height(56.dp).padding(16.dp),
        ) {
            if (page > 0) {
                IconButton(
                    onClick = { page-- },
                    modifier = Modifier.align(Alignment.CenterStart),
                ) {
                    Icon(
                        imageVector = MiuixIcons.Back,
                        contentDescription = "上一步",
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
            StepDots(
                current = page,
                total = PAGE_COUNT,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // 页面内容(带轻微淡入淡出切换)
        Box(modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().weight(1f)) {
            Crossfade(targetState = page, animationSpec = tween(180)) { p ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    when (p) {
                        0 -> WelcomePage()
                        1 -> ShizukuPage(
                            installed = shizukuInstalled,
                            alive = shizukuAlive,
                            ok = shizukuOk,
                            onOpenManager = { openShizukuManager(context) },
                            onRequest = { app.shizuku.requestPermission() },
                        )
                        2 -> OverlayPermissionPage(
                            granted = overlayGranted,
                            onGrant = { openOverlayPermission(context) },
                        )
                        3 -> OverlayPrefsPage(
                            enabledItems = enabledItems,
                            onToggleItem = { key, on ->
                                updateEnabled(if (on) enabledItems + key else enabledItems - key)
                            },
                            touchThrough = touchThrough,
                            onToggleTouch = { updateTouch(!touchThrough) },
                        )
                        4 -> ChartPrefsPage()
                        5 -> FinishPage(
                            shizukuOk = shizukuOk,
                            overlayGranted = overlayGranted,
                            itemCount = enabledItems.count { it in OverlayWindow.ALL_ITEMS },
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        // 底部主按钮
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
                .padding(bottom = 12.dp, top = 4.dp),
        ) {
            Button(
                onClick = { if (page < PAGE_COUNT - 1) page++ else enterApp() },
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = if (page < PAGE_COUNT - 1) "下一步" else "开始使用",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** 顶部步骤指示点:当前步为胶囊长条,其余为圆点(HyperOS 设置页风格) */
@Composable
private fun StepDots(current: Int, total: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { i ->
            val active = i == current
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .width(if (active) 18.dp else 6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (active) MiuixTheme.colorScheme.primary
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.35f)
                    ),
            )
        }
    }
}

/** 页面顶部大标题 + 说明 */
@Composable
private fun PageHeading(title: String, description: String) {
    Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp)) {
        Text(
            text = title,
            fontSize = 30.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = description,
            fontSize = 15.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 单行状态:左侧图标(已完成打勾 / 未完成灰点)+ 右侧文本 */
@Composable
private fun StatusRow(text: String, ok: Boolean, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(
                    if (ok) MiuixTheme.colorScheme.primary.copy(alpha = 0.16f)
                    else Color.Transparent
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (ok) {
                Icon(
                    imageVector = MiuixIcons.Basic.Check,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            fontSize = 15.sp,
            color = if (ok) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurface,
        )
    }
}

/** 步骤说明小卡片(标题 + 副标题,无行尾箭头) */
@Composable
private fun InfoRow(title: String, summary: String) {
    HyperListRow {
        ListRowContent(
            title = title,
            summary = summary,
            trailing = {},
        )
    }
}

// ==================== 步骤 0:欢迎 ====================

@Composable
private fun WelcomePage() {
    PageHeading(
        title = "欢迎使用 Raidian",
        description = "Raidian 在游戏等应用上层显示实时帧率、占用、温度与功耗，也可以把一段运行过程记录下来生成曲线报告。" +
            "核心数据需要 Shizuku 提权读取，请按顺序完成以下几步授权与偏好设置，大约需要一分钟。",
    )
    SectionHeader("本向导将完成")
    HyperListGroup(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        InfoRow("Shizuku 授权", "提权通道，用于读取帧率、功耗、温度等系统数据")
        InfoRow("悬浮窗权限", "把监控浮窗叠加到游戏等应用上层")
        InfoRow("显示偏好", "选择悬浮窗显示项与主页实时曲线指标")
    }
    Text(
        text = "以后随时可以在主页「设置」里重新打开本向导，补授权或调整偏好。",
        fontSize = 13.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

// ==================== 步骤 1:Shizuku 授权 ====================

@Composable
private fun ShizukuPage(
    installed: Boolean,
    alive: Boolean,
    ok: Boolean,
    onOpenManager: () -> Unit,
    onRequest: () -> Unit,
) {
    PageHeading(
        title = "授权 Shizuku",
        description = "Shizuku 提供 adb 级提权通道，Raidian 通过它读取帧率、温度与功耗等数据。" +
            "若设备上还没有 Shizuku，请先安装并启动服务，再回到本页完成授权。",
    )
    SectionHeader("当前状态")
    HyperCard {
        when {
            ok -> StatusRow("已授权，可以读取系统数据", ok = true)
            alive -> StatusRow("Shizuku 已运行，尚未向 Raidian 授权", ok = false)
            installed -> StatusRow("Shizuku 未运行", ok = false)
            else -> StatusRow("未检测到 Shizuku 应用", ok = false)
        }
        when {
            ok -> {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "授权后本应用会自动连接提权通道，无需其它操作。",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            alive -> {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onRequest,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("请求授权", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
            else -> {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onOpenManager,
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = if (installed) "打开 Shizuku" else "去安装 Shizuku",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (!installed) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "未检测到 Shizuku 应用。请从应用商店或 GitHub 发布页安装 Shizuku，安装后回到本页继续。",
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

// ==================== 步骤 2:悬浮窗权限 ====================

@Composable
private fun OverlayPermissionPage(
    granted: Boolean,
    onGrant: () -> Unit,
) {
    PageHeading(
        title = "悬浮窗权限",
        description = "悬浮窗让监控数据常驻显示在游戏等应用的上层。点击下方按钮前往系统设置，允许 Raidian 显示在其他应用上层后返回本页。",
    )
    SectionHeader("当前状态")
    HyperCard {
        if (granted) {
            StatusRow("已授权，可以显示悬浮窗", ok = true)
            Spacer(Modifier.height(10.dp))
            Text(
                text = "回到主页即可用「显示悬浮窗」按钮试一下效果。",
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        } else {
            StatusRow("尚未授予悬浮窗权限", ok = false)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("去系统设置授权", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ==================== 步骤 3:悬浮窗设置 ====================

/** 悬浮窗显示项列表(键与 OverlayWindow.ALL_ITEMS 对应,顺序与设置页一致) */
private val OverlayPrefRows = listOf(
    "帧率" to OverlayWindow.ITEM_FPS,
    "CPU 频率 / 占用" to OverlayWindow.ITEM_CPU,
    "GPU 占用" to OverlayWindow.ITEM_GPU,
    "电池 / 核心温度" to OverlayWindow.ITEM_TEMP,
    "功耗 / 电量" to OverlayWindow.ITEM_POWER,
    "内存占用" to OverlayWindow.ITEM_MEM,
    "前台应用" to OverlayWindow.ITEM_FRONT,
    "网络（暂无数据）" to OverlayWindow.ITEM_NET,
)

@Composable
private fun OverlayPrefsPage(
    enabledItems: Set<String>,
    onToggleItem: (String, Boolean) -> Unit,
    touchThrough: Boolean,
    onToggleTouch: () -> Unit,
) {
    PageHeading(
        title = "悬浮窗显示内容",
        description = "选择悬浮窗上常驻显示哪些数据，后续可在主页「设置 · 悬浮窗设置」中随时增删。",
    )
    SectionHeader("显示项")
    HyperListGroup(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        OverlayPrefRows.forEach { (label, key) ->
            val on = key in enabledItems
            HyperListRow(onClick = { onToggleItem(key, !on) }) {
                ListRowContent(
                    title = label,
                    trailing = {
                        Switch(checked = on, onCheckedChange = null)
                    },
                )
            }
        }
    }
    SectionHeader("交互")
    HyperListGroup(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        HyperListRow(onClick = onToggleTouch) {
            ListRowContent(
                title = "触摸穿透",
                summary = if (touchThrough) "已开启:悬浮窗不拦截触摸，适合游戏中"
                else "已关闭:悬浮窗可点按拖动",
                trailing = {
                    Switch(checked = touchThrough, onCheckedChange = null)
                },
            )
        }
    }
}

// ==================== 步骤 4:实时曲线 ====================

@Composable
private fun ChartPrefsPage() {
    val context = LocalContext.current
    PageHeading(
        title = "主页实时曲线",
        description = "主页「实时曲线」按这里的勾选显示。默认已开启帧率、占用与功耗，其它指标按需打开；配色可在「设置 · 实时曲线设置」中调整。",
    )
    HyperListGroup(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        LiveChartPrefs.ALL.forEach { item ->
            // 本地状态驱动 UI;切换时同时写配置(与主页实时曲线读取同源)
            var on by remember(item.key) { mutableStateOf(LiveChartPrefs.isOn(context, item.key)) }
            HyperListRow(onClick = {
                on = !on
                LiveChartPrefs.setOn(context, item.key, on)
            }) {
                ListRowContent(
                    title = item.label,
                    summary = if (item.multiLine) item.series.joinToString(" ") { it.label } else null,
                    trailing = {
                        Switch(checked = on, onCheckedChange = null)
                    },
                )
            }
        }
    }
}

// ==================== 步骤 5:完成 ====================

@Composable
private fun FinishPage(
    shizukuOk: Boolean,
    overlayGranted: Boolean,
    itemCount: Int,
) {
    PageHeading(
        title = "完成设置",
        description = "Raidian 已准备好。下面的状态如有未完成项，可以随时在主页顶栏「设置」中重新打开本向导补授权。",
    )
    HyperCard {
        ListMainText("状态汇总")
        Spacer(Modifier.height(12.dp))
        StatusRow(
            text = if (shizukuOk) "Shizuku:已授权" else "Shizuku:未授权（无法读取系统数据）",
            ok = shizukuOk,
        )
        Spacer(Modifier.height(10.dp))
        StatusRow(
            text = if (overlayGranted) "悬浮窗:已授权" else "悬浮窗:未授权（悬浮窗无法显示）",
            ok = overlayGranted,
        )
        Spacer(Modifier.height(10.dp))
        ListSecondaryText("悬浮窗显示项:已启用 $itemCount 项")
    }
    if (!shizukuOk) {
        Text(
            text = "提示:记录会话、读取帧率与温度等功能依赖 Shizuku，建议先授权再开始使用。",
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
    }
}

// ==================== 外部跳转辅助 ====================

/** 是否已安装 Shizuku(v13 后管理器包名 moe.shizuku.manager;旧版另有 privileged.api) */
private fun isShizukuInstalled(context: Context): Boolean {
    val pm = context.packageManager
    return pm.getLaunchIntentForPackage("moe.shizuku.privileged.api") != null ||
        pm.getLaunchIntentForPackage("moe.shizuku.manager") != null
}

/** 打开 Shizuku 管理器(找不到则不做任何事) */
private fun openShizukuManager(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        ?: context.packageManager.getLaunchIntentForPackage("moe.shizuku.manager")
    if (intent != null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

/** 跳转悬浮窗授权设置页;个别机型无包名入口时兜底到通用悬浮窗管理页 */
private fun openOverlayPermission(context: Context) {
    try {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e2: Exception) {
        }
    }
}
