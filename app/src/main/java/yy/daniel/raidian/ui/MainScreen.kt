package yy.daniel.raidian.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.AppRecording
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 主页(实时页):只放"实时"页的内容 —— 实时数据网格、实时曲线,
 * 以及记录会话 / 悬浮窗快捷开关 / Shizuku 状态。
 *
 * 「记录」「设置」两个入口在顶栏右侧,点击分别打开
 * [RecordsActivity](会话记录)与 [SettingsActivity](实时曲线/悬浮窗设置)。
 */
@Composable
fun MainScreen(modifier: Modifier = Modifier, vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            vm.setUiVisible(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
        }
        lifecycle.addObserver(observer)
        vm.setUiVisible(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); vm.setUiVisible(false) }
    }
    /** 悬浮窗权限是否已授予(决定实时页按钮可用性与提示) */
    val canOverlay by rememberOverlayPermission()

    // 进页面:刷新 Shizuku 状态、启动 UI 级采样轮询(会话列表由记录页自行加载)
    LaunchedEffect(Unit) {
        vm.refreshPrivileges()
        vm.startUiPolling()
    }

    val scrollBehavior = MiuixScrollBehavior(
        state = rememberTopAppBarState(),
        snapAnimationSpec = spring(stiffness = 2500f),
        canScroll = { true }
    )

    val backdrop = rememberUiBackdrop()
    val blurSupported = isMiuixBlurSupported()

    val tablet = isTablet()

    Scaffold(
        modifier = modifier,
        topBar = {
            AdaptiveTopAppBar(
                title = "实时监控",
                modifier = Modifier.miuixTopBarBlur(backdrop),
                color = if (blurSupported) Color.Transparent else MiuixTheme.colorScheme.surface,
                largeTitle = "Raidian 性能监控器",
                // subtitle = "Raidian · 性能监控",
                titleColor = MiuixTheme.colorScheme.onSurface,
                largeTitleColor = MiuixTheme.colorScheme.onSurface,
                subtitleColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                actions = {
                    // 设置入口(记录/续航/充电已移到 HomeScreen 的底部导航,这里只留设置)
                    TopBarEntry(
                        icon = { Icon(MiuixIcons.Settings, contentDescription = "设置", tint = MiuixTheme.colorScheme.onSurface) },
                        label = "设置",
                        onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) },
                    )
                },
                titlePadding = 24.dp,
                navigationIconPadding = 12.dp,
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        // 滚动内容在本 Box 内上滑时,把滚动派发给顶栏实现大标题回缩
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (blurSupported) Modifier.layerBackdrop(backdrop) else Modifier)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {
            // 导航(底部悬浮栏 / 平板侧栏)由 HomeScreen 负责,这里只画实时内容
            LivePage(
                vm = vm,
                canOverlay = canOverlay,
                contentPadding = padding,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 顶栏右侧"图标+文字"入口按钮(HyperOS 顶栏次级动作样式) */
@Composable
private fun androidx.compose.foundation.layout.RowScope.TopBarEntry(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon()
//            Spacer(Modifier.width(3.dp))
//            Text(
//                label,
//                fontSize = 15.sp,
//                color = MiuixTheme.colorScheme.onSurface,
//            )
        }
    }
}

// ==================== 实时页 ====================

/**
 * 实时页:实时数据卡片 + 实时曲线 + 会话记录控制 + 悬浮窗开关 + Shizuku 状态。
 */
@Composable
private fun LivePage(
    vm: MainViewModel,
    canOverlay: Boolean,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val last by vm.lastSample.collectAsState()
    val running by vm.recorder.running.collectAsState()
    val paused by vm.recorder.paused.collectAsState()
    val fpsStats by vm.recorder.fpsStats.collectAsState()
    val history by vm.history.collectAsState()
    val overlayShown by vm.overlayShown.collectAsState()
    val watchTarget by vm.watchTarget.collectAsState()
    val context = LocalContext.current
    val tablet = isTablet()
    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { vm.startRecording() }

    // 宽屏整页内容限宽居中(平板横屏避免内容拉满整条,维持舒适阅读宽度)
    val contentMaxWidth = if (tablet) TabletContentMaxWidthDp.dp else Dp.Infinity

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(modifier = Modifier.widthIn(max = contentMaxWidth).fillMaxWidth()) {
        // 无悬浮窗权限时:顶部提示去授权
        if (!canOverlay) {
            SectionHeader("悬浮窗权限")
            TipCard(
                text = "需要悬浮窗权限才能显示监控浮窗",
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }
            )
        }

        SectionHeader("设备")
        HyperCard { DeviceEnvCard() }
        SectionHeader("实时数据")
        HyperCard { SampleGrid(last) }

        // 实时曲线:近 2 分钟历史,显示项与颜色可在悬浮窗设置页配置(见 LiveChartPrefs)
        SectionHeader("实时曲线")
        androidx.compose.runtime.CompositionLocalProvider(LocalChartContext provides ChartContext(
            elapsedMs = history.map { it.elapsedMs - (history.firstOrNull()?.elapsedMs ?: 0L) },
            breakBefore = history.mapIndexed { i, point -> i > 0 && point.elapsedMs - history[i - 1].elapsedMs > 1500 },
            source = "实时监控 · 打开时快照",
            corePolicies = last.cpuPolicyIds,
            coreMaxMhz = last.perCoreMaxMhz,
            perCoreFreq = last.perCoreFreqMhz.indices.map { core ->
                sampleSeries(history.map { it.perCoreFreqMhz.getOrNull(core)?.toFloat() ?: -1f })
            },
        )) {
        HyperCard {
            // x 轴时间标签(相对首点,mm:ss;点数太少返回 null 只画刻度)
            val t0 = history.firstOrNull()?.elapsedMs
            val timeX = if (history.size >= 4 && t0 != null) {
                val ticks = 4
                (0..ticks).map { t ->
                    val idx = (history.size - 1) * t / ticks
                    val e = history.getOrNull(idx)?.elapsedMs?.minus(t0) ?: 0L
                    val s = e / 1000
                    String.format("%d:%02d", s / 60, s % 60)
                }
            } else null

            // 单值序列:key → (值列表, 当前值, 单位)
            val single = mapOf(
                "fps" to Triple(sampleSeries(history.map { it.fps }), last.fps, "FPS"),
                "cpu_load" to Triple(sampleSeries(history.map { it.cpuLoad }), last.cpuLoad, "%"),
                "gpu_load" to Triple(sampleSeries(history.map { it.gpuLoad }), last.gpuLoad, "%"),
                "gpu_freq" to Triple(sampleSeries(history.map { it.gpuFreq.toFloat() }), last.gpuFreq.toFloat(), "MHz"),
                "power" to Triple(sampleSeries(history.map { it.powerMw.toFloat() }), last.powerMw.toFloat(), "mW"),
                "battery_temp" to Triple(sampleSeries(history.map { it.batteryTemp }), last.batteryTemp, "°C"),
                "soc_temp" to Triple(sampleSeries(history.map { it.maxSocTemp }), last.maxSocTemp, "°C"),
                "memory" to Triple(sampleSeries(history.map { it.usedMemMb.toFloat() }), last.usedMemMb.toFloat(), "MB"),
                "gpu_temp" to Triple(sampleSeries(history.map { it.gpuTemp }), last.gpuTemp, "℃"),
                "npu_temp" to Triple(sampleSeries(history.map { it.npuTemp }), last.npuTemp, "℃"),
                "skin_temp" to Triple(sampleSeries(history.map { it.skinTemp }), last.skinTemp, "℃"),
            )
            // 多线序列(CPU 频率三簇 L/M/B)
            val xring = yy.daniel.raidian.monitor.DeviceProfile.detect().isXring
            val policyCores = listOf(0, 2, 4, 8)
            val multiCpuFreq = if (xring) policyCores.map { core -> sampleSeries(history.map { it.perCoreFreqMhz.getOrNull(core)?.toFloat() ?: -1f }) } else listOf(
                sampleSeries(history.map { it.cpuL.toFloat() }),
                sampleSeries(history.map { it.cpuM.toFloat() }),
                sampleSeries(history.map { it.cpuB.toFloat() }),
            )
            val cpuFreqCur = if (xring) policyCores.map { last.perCoreFreqMhz.getOrNull(it)?.toFloat() ?: -1f }
                else listOf(last.cpuL.toFloat(), last.cpuM.toFloat(), last.cpuB.toFloat())

            // 依次画启用项:手机单列;平板单值项两两并排(多线项整宽)
            val enabledItems = LiveChartPrefs.ALL.filter { LiveChartPrefs.isOn(context, it.key) }
            if (tablet) {
                // 多线项(CPU 频率)整宽放最前
                enabledItems.filter { it.key == "cpu_freq" }.forEach { item ->
                    LiveMultiChart(
                        item = item, multiCpuFreq = multiCpuFreq, cpuFreqCur = cpuFreqCur,
                        timeX = timeX, context = context,
                    )
                }
                // 单值项:两两一组并排
                val singles = enabledItems.filter { it.key != "cpu_freq" }
                singles.chunked(2).forEach { pair ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        pair.forEach { item ->
                            androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                                LiveSingleChart(item = item, single = single, timeX = timeX, context = context)
                            }
                        }
                        // 补空位保持对齐
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            } else {
                enabledItems.forEach { item ->
                    if (item.key == "cpu_freq") {
                        LiveMultiChart(item, multiCpuFreq, cpuFreqCur, timeX, context)
                    } else {
                        LiveSingleChart(item, single, timeX, context)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }

        }

        // 会话记录:记录目标(列表行)+ 控制(状态/统计/开始停止)
        SectionHeader("会话记录")

        // 记录目标:与 AppPickActivity 内部一致的列表项(图标 + 应用名 + 包名)。
        // 未指定时整行用次要色(灰),不用黑色主文字。
        val target = watchTarget
        val targetIcon = remember(target?.pkg) {
            target?.pkg?.let { pkg -> appIconOf(context, pkg) }
        }
        HyperListGroup(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            HyperListRow(onClick = { context.startActivity(Intent(context, AppPickActivity::class.java)) }) {
                if (target != null) {
                    ListRowContent(
                        title = target.label,
                        summary = target.pkg,
                        leading = { AppIcon(targetIcon) },
                        trailing = {
                            Text(
                                "更改",
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        },
                    )
                } else {
                    ListRowContent(
                        title = "跟随当前前台应用",
                        summary = "未指定 · 谁在前台记谁",
                        titleColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        HyperCard {
            Text(
                text = when {
                    running && paused -> "正在记录中 · 目标不在前台，已暂停"
                    running -> "正在记录中"
                    else -> "未在记录"
                },
                style = MiuixTheme.textStyles.body1,
            )
            if (running) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "平均帧 %.1f  ｜  1%% Low %.1f  ｜  0.1%% Low %.1f".format(
                        fpsStats.avgFps, fpsStats.low1Fps, fpsStats.low01Fps
                    ),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (target != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "仅当「${target.label}」在前台时记录，切走自动暂停、回来自动续录。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "清除指定 →",
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .clickable { vm.setWatchTarget(null) }
                        .padding(vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        if (running) vm.stopRecording()
                        else if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        else vm.startRecording()
                    },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (running) "停止记录" else "开始记录", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        BackgroundSettingsRow()
        HardwareDetails(last)

        // 悬浮窗开关 + 权限通道:平板两卡并排
        if (tablet) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    SectionHeader("悬浮窗")
                    HyperCard {
                        Text(
                            text = if (overlayShown) "悬浮窗已显示" else "悬浮窗未显示",
                            style = MiuixTheme.textStyles.body1
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { vm.toggleOverlay() },
                            colors = if (overlayShown) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColorsPrimary(),
                            modifier = Modifier.fillMaxWidth(),
                            enabled = canOverlay,
                        ) {
                            Text(if (overlayShown) "隐藏悬浮窗" else "显示悬浮窗", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    SectionHeader("权限通道")
                    HyperCard {
                        AccessStatus(vm)
                    }
                }
            }
        } else {
            SectionHeader("悬浮窗")
            HyperCard {
                Text(
                    text = if (overlayShown) "悬浮窗已显示" else "悬浮窗未显示",
                    style = MiuixTheme.textStyles.body1
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { vm.toggleOverlay() },
                    colors = if (overlayShown) ButtonDefaults.buttonColors() else ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canOverlay,
                ) {
                    Text(if (overlayShown) "隐藏悬浮窗" else "显示悬浮窗", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
            SectionHeader("权限通道")
            HyperCard {
                AccessStatus(vm)
            }
        }
        // 手机主页的悬浮导航覆盖在内容上：只在滚动末尾补安全空间，平时内容仍从栏下经过。
        Spacer(Modifier.height(if (tablet) 20.dp else HomeFloatingNavContentPadding))
        } // 内层限宽 Column 结束
    } // 外层滚动 Column 结束
}

/** 设备环境卡内容:识别出的 ROM 家族 / 型号 / 采集适配方式(见 DeviceProfile) */
@Composable
private fun DeviceEnvCard() {
    val profile = remember { yy.daniel.raidian.monitor.DeviceProfile.detect() }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        InfoRow("系统", profile.rom.label)
        InfoRow("机型", profile.model.ifBlank { profile.brand })
        InfoRow("处理器", if (profile.isXring) "玄戒 O1" else profile.socModel.ifBlank { profile.socManufacturer })
        InfoRow(
            "版本", profile.romVersion.ifBlank {
                "Android ${android.os.Build.VERSION.RELEASE}"
            }
        )
    }
}

/**
 * 实时曲线 · 单值指标行(帧率/占用/功耗/温度/内存等)。
 * 内部按是否有数据决定画 [ChartRow] 还是 [NoDataRow]。
 */
@Composable
private fun LiveSingleChart(
    item: LiveChartPrefs.ChartItem,
    single: Map<String, Triple<List<Float?>, Float, String>>,
    timeX: List<String>?,
    context: android.content.Context,
) {
    val entry = single[item.key]
    if (entry == null) {
        NoDataRow(item.label)
        return
    }
    val (values, current, unit) = entry
    if (!values.any { it != null }) {
        NoDataRow(item.label)
        return
    }
    val color = LiveChartPrefs.color(context, item.key)
    val (minY, maxY) = when (item.key) {
        "fps" -> 0f to maxOf(120f, values.filterNotNull().maxOrNull() ?: 0f)
        "gpu_load" -> 0f to 100f
        "cpu_load" -> 0f to 100f
        else -> null to null
    }
    ChartRow(item.label, unit, values, current, minY, maxY, color)
}

/**
 * 实时曲线 · 多线指标(CPU 频率,按实际簇分档:小/中/大;2 簇设备无中核时只画两条)。
 */
@Composable
private fun LiveMultiChart(
    item: LiveChartPrefs.ChartItem,
    multiCpuFreq: List<List<Float?>>,
    cpuFreqCur: List<Float>,
    timeX: List<String>?,
    context: android.content.Context,
) {
    // 过滤掉全空序列(如 2 簇设备的"中核"),标签/颜色/当前值同步裁剪
    val fullLabels = item.series.map { it.label }
    val activeIdx = multiCpuFreq.indices.filter { i ->
        multiCpuFreq[i].any { it != null }
    }
    val colors = item.series.indices.map { si -> LiveChartPrefs.seriesColor(context, item.key, si) }
    if (activeIdx.isNotEmpty()) {
        val series = activeIdx.map { multiCpuFreq[it] }
        val cur = activeIdx.map { cpuFreqCur[it] }
        val labels = activeIdx.map { fullLabels[it] }
        val actColors = activeIdx.map { colors[it] }
        MultiSeriesRow(
            label = item.label,
            unit = "MHz",
            series = series,
            curValues = cur,
            colors = actColors,
            minY = 0f,
            maxY = null,
            seriesLabels = labels,
            xLabels = timeX,
        )
        Spacer(Modifier.height(8.dp))
    } else {
        NoDataRow(item.label)
    }
}

/** 提示型卡片(整行可点,右侧"去授权 >"),用于悬浮窗权限引导 */
@Composable
private fun TipCard(text: String, onClick: () -> Unit) {
    top.yukonga.miuix.kmp.basic.Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        insideMargin = androidx.compose.foundation.layout.PaddingValues(16.dp),
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.14f)
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, fontSize = 14.sp, modifier = Modifier.weight(1f), color = MiuixTheme.colorScheme.onSurface)
            Text("去授权 ＞", fontSize = 14.sp, color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun AccessStatus(vm: MainViewModel) {
    PrivilegeStatusCard(vm.privileges)
}
