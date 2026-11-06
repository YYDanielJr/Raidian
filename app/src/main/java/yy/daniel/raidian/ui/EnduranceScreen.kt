package yy.daniel.raidian.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 续航曲线页。
 *
 * 展示三块内容:
 *  1. **本次续航**(系统口径):总续航时长、亮屏时长 —— 来自 `dumpsys batterystats` 的
 *     `Time on battery` / `Screen on`,它随"充满电/拔电"自动重置,因此就是
 *     "上一次充电到 100% 之后"的累计值(详见 battery/BatteryStatsParser);
 *     另有本机自采样的平均功耗、预估可用时长与本周期耗电。
 *  2. **曲线**:本周期电量曲线 + 功耗曲线(每 60s 一点,由后台记录器持续写入)。
 *  3. **每应用耗电**:系统按 UID 估算的 mAh 排行,点进去看该应用在本周期的功耗曲线;
 *     再下面是历史周期列表与记录开关。
 *
 * 与既有「实时监控」页并列,由底部悬浮导航栏切换(见 HomeScreen)。
 */
@Composable
fun EnduranceScreen(modifier: Modifier = Modifier, vm: MainViewModel = viewModel()) {
    val live by vm.enduranceLive.collectAsState()
    val cycles by vm.cycles.collectAsState()
    val enabled by vm.curveEnabled.collectAsState()
    val readable by vm.systemReadable.collectAsState()
    val context = LocalContext.current

    // 进页面:刷新历史周期 + 立即拉一次系统统计(不等 5 分钟节拍)
    LaunchedEffect(Unit) {
        vm.refreshCycles()
        vm.refreshSystemStats()
    }

    SubPageScaffold(
        title = "续航曲线",
        subtitle = when {
            !enabled -> "后台记录已关闭"
            live.charging -> "充电中（续航数据以系统统计为准）"
            else -> "后台记录中"
        },
        onBack = null,
        content = { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding),
            ) {
                // ---------------- 本次续航 ----------------
                SectionHeader("本次续航")
                HyperCard {
                    StatGrid(
                        listOf(
                            "总续航时长" to fmtDuration(live.timeOnBatteryMs),
                            "亮屏时长" to fmtDuration(live.screenOnMs),
                            "当前电量" to if (live.level >= 0) "${live.level}%" else "--",
                            "平均功耗" to fmtPower(live.avgPowerMw),
                            "本周期耗电" to fmtMah(live.computedDrainMah),
                            "预估可用" to fmtDuration(live.estRemainMs),
                            "已记录放电" to fmtDuration(live.localDischargeMs),
                            "当前状态" to live.chargeStateText,
                        )
                    )
                    Spacer(Modifier.height(4.dp))
                    InfoBlock(
                        listOf(
                            "周期起点" to (
                                if (live.cycleStartTs > 0)
                                    "${fmtDateTime(live.cycleStartTs)}（${live.startLevel}%）"
                                else "--"
                                ),
                            "曲线点数" to "${live.sampleCount}",
                            "电池容量" to if (live.capacityMah > 0) "${live.capacityMah} mAh" else "--",
                            "系统统计" to when {
                                !readable -> "不可读（需 ADB 或 root 授权）"
                                live.sysStatsTs > 0 -> "已更新 ${fmtClock(live.sysStatsTs)}"
                                else -> "等待首次读取"
                            },
                        )
                    )
                }

                if (!readable) {
                    HyperCard {
                        EmptyHint(
                            "总续航时长 / 亮屏时长 / 每应用耗电来自系统 `dumpsys batterystats`，" +
                                "需要 ADB 或 root 授权才能读取。未授权时仍会记录本机电量与功耗曲线。"
                        )
                    }
                }

                // ---------------- 曲线 ----------------
                val pts = live.curve
                val levelSeries = remember(pts) {
                    pts.map { if (it.level in 0..100) it.level.toFloat() else null }
                }
                val powerSeries = remember(pts) {
                    pts.map { if (it.powerMw > 0) it.powerMw.toFloat() else null }
                }
                val xLabels = remember(pts, live.cycleStartTs) {
                    relativeAxisLabels(pts.map { it.ts }, live.cycleStartTs)
                }

                SectionHeader("本周期曲线")
                HyperCard {
                    if (pts.size < 2) {
                        EmptyHint("曲线数据不足：后台记录开启后每 60 秒记一个点，稍后回来查看。")
                    } else {
                        DetailChart(
                            title = "电量（%）",
                            values = levelSeries,
                            minY = 0f,
                            maxY = 100f,
                            color = ChartColors.BatteryLevel,
                            xLabels = xLabels,
                            elapsedMs = pts.map { it.ts - live.cycleStartTs },
                        )
                        Spacer(Modifier.height(12.dp))
                        DetailChart(
                            title = "功耗（mW）",
                            values = powerSeries,
                            minY = 0f,
                            maxY = null,
                            color = ChartColors.Power,
                            xLabels = xLabels,
                            elapsedMs = pts.map { it.ts - live.cycleStartTs },
                        )
                    }
                }

                // ---------------- 每应用耗电 ----------------
                SectionHeader("应用耗电（上次充满至今）")
                val apps = live.apps
                if (apps.isEmpty()) {
                    HyperCard {
                        EmptyHint(
                            if (readable) "暂无逐应用统计（读取系统统计后出现）"
                            else "需要 ADB 或 root 授权后才能读取系统逐应用耗电统计"
                        )
                    }
                } else {
                    HyperListGroup(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        apps.take(20).forEach { app ->
                            val icon = remember(app.pkg) {
                                if (app.pkg.isNotBlank()) appIconOf(context, app.pkg) else null
                            }
                            HyperListRow(onClick = {
                                // 逐应用功耗曲线:把周期目录与 uid 传给详情页
                                if (live.cycleDir.isNotBlank()) {
                                    context.startActivity(
                                        Intent(context, AppPowerDetailActivity::class.java)
                                            .putExtra(AppPowerDetailActivity.EXTRA_DIR, live.cycleDir)
                                            .putExtra(AppPowerDetailActivity.EXTRA_UID, app.uid)
                                    )
                                }
                            }) {
                                ListRowContent(
                                    title = app.label,
                                    summary = buildString {
                                        append(fmtMah(app.mah))
                                        if (app.avgPowerMw >= 0) append(" · 平均 ${fmtPower(app.avgPowerMw)}")
                                        if (app.percent >= 0) append(" · ${fmtPercent(app.percent)}")
                                    },
                                    leading = { AppIcon(icon) },
                                )
                            }
                        }
                    }
                    Text(
                        if (apps.size > 20) "仅显示耗电最高的 20 项（共 ${apps.size} 项）" else "共 ${apps.size} 项",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                // ---------------- 历史周期 ----------------
                SectionHeader("历史周期")
                if (cycles.isEmpty()) {
                    HyperCard { EmptyHint("暂无历史周期：完成一次「充满 → 放电」后自动归档。") }
                } else {
                    HyperListGroup(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        cycles.forEach { item ->
                            val meta = item.cycle
                            HyperListRow(onClick = {
                                context.startActivity(
                                    Intent(context, BatteryCycleDetailActivity::class.java)
                                        .putExtra(BatteryCycleDetailActivity.EXTRA_DIR, item.dir.absolutePath)
                                )
                            }) {
                                ListRowContent(
                                    title = fmtDateTime(meta?.startTs ?: 0L) +
                                        if (item.active) " · 进行中" else "",
                                    summary = buildString {
                                        append("续航 ${fmtDuration(item.timeOnBatteryMs)}")
                                        append(" · 亮屏 ${fmtDuration(item.screenOnMs)}")
                                        val sl = meta?.startLevel ?: -1
                                        val el = meta?.endLevel ?: -1
                                        if (sl >= 0 && el >= 0) append(" · ${sl}%→${el}%")
                                    },
                                )
                            }
                        }
                    }
                }

                // ---------------- 记录设置 ----------------
                SectionHeader("记录设置")
                HyperListGroup(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    HyperListRow(onClick = { vm.setCurveEnabled(!enabled) }) {
                        ListRowContent(
                            title = "后台记录续航曲线",
                            summary = if (enabled) "每 30 秒采样、每 5 分钟读一次系统统计"
                            else "已关闭：不再记录新的曲线数据",
                            trailing = { Switch(checked = enabled, onCheckedChange = null) },
                        )
                    }
                    HyperListRow(onClick = { vm.refreshSystemStats() }) {
                        ListRowContent(
                            title = "立即刷新系统统计",
                            summary = "重新读取 dumpsys batterystats（续航/亮屏/每应用耗电）",
                            trailing = {},
                        )
                    }
                }
                // 到达列表末尾时可把最后一项完整滚到悬浮导航栏上方。
                Spacer(
                    Modifier.height(
                        if (isTablet()) 24.dp else HomeFloatingNavContentPadding
                    )
                )
            }
        },
    )
}

/**
 * 历史周期详情(独立 Activity):周期元数据 + 电量/功耗曲线 + 该周期的每应用耗电排行。
 * 曲线与排行都从周期目录里现读(CSV),因此历史周期也能看到完整曲线。
 */
@Composable
fun CycleDetailContent(
    meta: yy.daniel.raidian.battery.BatteryCurveStore.CycleMeta?,
    drain: List<yy.daniel.raidian.battery.BatteryCurveStore.DrainPoint>,
    apps: List<Pair<Int, Triple<String, String, Double>>>,
    dirPath: String,
    contentPadding: PaddingValues = PaddingValues(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val last = drain.lastOrNull()
    val powerSeries = remember(drain) {
        drain.map { if (it.powerMw > 0) it.powerMw.toFloat() else null }
    }
    val levelSeries = remember(drain) {
        drain.map { if (it.level in 0..100) it.level.toFloat() else null }
    }
    val xLabels = remember(drain, meta) {
        relativeAxisLabels(drain.map { it.ts }, meta?.startTs ?: (drain.firstOrNull()?.ts ?: 0L))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        SectionHeader("周期概况")
        HyperCard {
            StatGrid(
                listOf(
                    "续航时长" to fmtDuration(last?.timeOnBatteryMs ?: -1L),
                    "亮屏时长" to fmtDuration(last?.screenOnMs ?: -1L),
                    "周期耗电" to fmtMah(last?.computedDrainMah ?: -1.0),
                    "曲线点数" to "${drain.size}",
                )
            )
            InfoBlock(
                listOf(
                    "开始" to fmtDateTimeSec(meta?.startTs ?: 0L),
                    "结束" to if ((meta?.endTs ?: 0L) > 0) fmtDateTimeSec(meta!!.endTs) else "进行中",
                    "电量变化" to "${meta?.startLevel ?: -1}% → ${meta?.endLevel ?: -1}%",
                    "电池容量" to if ((meta?.capacityMah ?: -1) > 0) "${meta!!.capacityMah} mAh" else "--",
                    "起点来源" to if (meta?.fromFullCharge == true) "充满 100% 自动开新周期" else "首次安装兜底周期",
                )
            )
        }

        SectionHeader("曲线")
        HyperCard {
            if (drain.size < 2) {
                EmptyHint("该周期曲线数据不足。")
            } else {
                DetailChart("电量（%）", levelSeries, 0f, 100f, ChartColors.BatteryLevel, xLabels,
                    elapsedMs = drain.map { it.ts - (meta?.startTs ?: drain.first().ts) })
                Spacer(Modifier.height(12.dp))
                DetailChart("功耗（mW）", powerSeries, 0f, null, ChartColors.Power, xLabels,
                    elapsedMs = drain.map { it.ts - (meta?.startTs ?: drain.first().ts) })
            }
        }

        SectionHeader("应用耗电")
        if (apps.isEmpty()) {
            HyperCard { EmptyHint("该周期没有逐应用统计（当时可能未授权 ADB 或 root）。") }
        } else {
            HyperListGroup(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                apps.take(30).forEach { (uid, info) ->
                    val (label, pkg, mah) = info
                    val icon = remember(pkg) { if (pkg.isNotBlank()) appIconOf(context, pkg) else null }
                    HyperListRow(onClick = {
                        context.startActivity(
                            Intent(context, AppPowerDetailActivity::class.java)
                                .putExtra(AppPowerDetailActivity.EXTRA_DIR, dirPath)
                                .putExtra(AppPowerDetailActivity.EXTRA_UID, uid)
                        )
                    }) {
                        ListRowContent(
                            title = label,
                            summary = fmtMah(mah),
                            leading = { AppIcon(icon) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 详情页里"最近一次系统统计是否可用"的小提示行(充电页/续航页共用) */
@Composable
fun StatusLine(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(0.dp))
        Text(text, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}
