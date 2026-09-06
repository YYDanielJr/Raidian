package yy.daniel.raidian.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
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
    /** 悬浮窗权限是否已授予(决定实时页按钮可用性与提示) */
    val canOverlay = Settings.canDrawOverlays(context)

    // 进页面:刷新 Shizuku 状态、启动 UI 级采样轮询(会话列表由记录页自行加载)
    LaunchedEffect(Unit) {
        vm.refreshShizuku()
        vm.startUiPolling()
    }

    val scrollBehavior = MiuixScrollBehavior(
        state = rememberTopAppBarState(),
        snapAnimationSpec = spring(stiffness = 2500f),
        canScroll = { true }
    )

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = "实时监控",
                largeTitle = "Raidian 性能监控器",
                // subtitle = "Raidian · 性能监控",
                titleColor = MiuixTheme.colorScheme.onSurface,
                largeTitleColor = MiuixTheme.colorScheme.onSurface,
                subtitleColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                actions = {
                    TopBarEntry(
                        icon = { Icon(MiuixIcons.ListView, contentDescription = null, tint = MiuixTheme.colorScheme.onSurface) },
                        label = "记录",
                        onClick = { context.startActivity(Intent(context, RecordsActivity::class.java)) },
                    )
                    TopBarEntry(
                        icon = { Icon(MiuixIcons.Settings, contentDescription = null, tint = MiuixTheme.colorScheme.onSurface) },
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
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {
            LivePage(
                vm = vm,
                canOverlay = canOverlay,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
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

        // 设备环境:识别出的 ROM 家族 / 型号 / 采集适配方式(见 DeviceProfile)
        val profile = remember { yy.daniel.raidian.monitor.DeviceProfile.detect() }
        SectionHeader("设备环境")
        HyperCard {
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                InfoRow("系统", profile.rom.label)
                InfoRow("机型", profile.model.ifBlank { profile.brand })
                InfoRow("簇探测", profile.clusterProbe.label)
                InfoRow(
                    "版本", profile.romVersion.ifBlank {
                        "Android ${android.os.Build.VERSION.RELEASE}"
                    }
                )
            }
        }

        // 实时数据卡片(九宫格数值)
        SectionHeader("实时数据")
        HyperCard {
            SampleGrid(last)
        }

        // 实时曲线:近 2 分钟历史,显示项与颜色可在悬浮窗设置页配置(见 LiveChartPrefs)
        SectionHeader("实时曲线")
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
            )
            // 多线序列(CPU 频率三簇 L/M/B)
            val multiCpuFreq = listOf(
                sampleSeries(history.map { it.cpuL.toFloat() }),
                sampleSeries(history.map { it.cpuM.toFloat() }),
                sampleSeries(history.map { it.cpuB.toFloat() }),
            )
            val cpuFreqCur = listOf(last.cpuL.toFloat(), last.cpuM.toFloat(), last.cpuB.toFloat())

            // 依次画启用项
            LiveChartPrefs.ALL.forEach { item ->
                if (!LiveChartPrefs.isOn(context, item.key)) return@forEach
                if (item.key == "cpu_freq") {
                    // 三簇三线(颜色分别为 L/M/B 的配置)
                    val colors = (0 until 3).map { si -> LiveChartPrefs.seriesColor(context, item.key, si) }
                    val hasAny = multiCpuFreq.any { line -> line.any { it != null } }
                    if (hasAny) {
                        MultiSeriesRow(
                            label = item.label,
                            unit = "MHz",
                            series = multiCpuFreq,
                            curValues = cpuFreqCur,
                            colors = colors,
                            minY = 0f,
                            maxY = null,
                            seriesLabels = listOf("小核", "中核", "大核"),
                            xLabels = timeX,
                        )
                    } else {
                        NoDataRow(item.label)
                    }
                } else {
                    val (values, current, unit) = single[item.key] ?: return@forEach
                    val hasData = values.any { it != null }
                    if (!hasData) {
                        NoDataRow(item.label)
                        Spacer(Modifier.height(8.dp))
                        return@forEach
                    }
                    val color = LiveChartPrefs.color(context, item.key)
                    val (minY, maxY) = when (item.key) {
                        "fps" -> 0f to 120f
                        "gpu_load" -> 0f to 100f
                        "cpu_load" -> 0f to 100f
                        else -> null to null
                    }
                    ChartRow(item.label, unit, values, current, minY, maxY, color)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        // 会话控制:开始/停止记录 + 记录目标 + 当前帧统计
        SectionHeader("会话记录")
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
            Spacer(Modifier.height(12.dp))
            // 记录目标(指定应用):显示当前选择,点击进入应用选择页
            val targetText = watchTarget?.label ?: "跟随当前前台应用"
            Text(
                text = "记录目标：$targetText",
                fontSize = 15.sp,
                color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .clickable { context.startActivity(Intent(context, AppPickActivity::class.java)) }
                    .padding(vertical = 10.dp, horizontal = 4.dp),
            )
            if (watchTarget != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "仅当「$targetText」在前台时记录，切走自动暂停、回来自动续录。",
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
                    onClick = { if (running) vm.stopRecording() else vm.startRecording() },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (running) "停止记录" else "开始记录", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // 悬浮窗开关
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

        // Shizuku 授权状态 + 请求授权按钮
        SectionHeader("权限通道")
        HyperCard {
            ShizukuStatus(vm)
        }
        Spacer(Modifier.height(20.dp))
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

/**
 * Shizuku 状态卡:未运行 → 引导打开 Shizuku 管理器;运行未授权 → 请求授权按钮。
 */
@Composable
private fun ShizukuStatus(vm: MainViewModel) {
    val available = vm.shizuku.available()
    val context = LocalContext.current
    Column {
        Text(
            text = when {
                !vm.shizuku.isBinderAlive() -> "Shizuku 未运行"
                !available -> "Shizuku 已运行，未授权"
                else -> "Shizuku 已授权"
            },
            style = MiuixTheme.textStyles.body1,
            color = if (available) MiuixTheme.colorScheme.primary
            else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )

        if (!available) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    if (vm.shizuku.isBinderAlive()) {
                        vm.shizuku.requestPermission() // 弹 Shizuku 授权对话框
                    } else {
                        openShizukuManager(context) // 引导去装/开 Shizuku
                    }
                }, colors = ButtonDefaults.buttonColorsPrimary(), modifier = Modifier.weight(1f)) {
                    Text(if (vm.shizuku.isBinderAlive()) "请求授权" else "打开 Shizuku", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** 用系统包管理器找 Shizuku 应用并打开(找不到则无操作) */
private fun openShizukuManager(context: android.content.Context) {
    val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        ?: context.packageManager.getLaunchIntentForPackage("moe.shizuku.manager")
    if (intent != null) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
