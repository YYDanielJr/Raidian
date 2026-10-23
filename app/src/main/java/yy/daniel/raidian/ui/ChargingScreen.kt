package yy.daniel.raidian.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 充电曲线页。
 *
 * 展示:
 *  1. **当前/最近一次充电**:状态、电量、充电功率、电流、电压、温度,以及本次会话的
 *     起止电量、已用时、预计充满、峰值/平均功率、充入能量;
 *  2. **充电曲线**:电量曲线(%) + 充电功率曲线(W),每 30 秒一点,后台自动记录;
 *  3. **历史充电**:每次插电到拔电/充满算一次会话,点进去看曲线与对比。
 *
 * 记录逻辑见 battery/ChargingRecorder(插上即开始、拔下或充满即封存)。
 */
@Composable
fun ChargingScreen(modifier: Modifier = Modifier, vm: MainViewModel = viewModel()) {
    val live by vm.chargingLive.collectAsState()
    val sessions by vm.chargeSessions.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.refreshChargeSessions() }

    SubPageScaffold(
        title = "充电曲线",
        subtitle = when {
            live.recording -> "正在记录本次充电"
            live.pluggedIn -> "已插电"
            else -> "未在充电"
        },
        onBack = null,
        content = { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding),
            ) {
                // ---------------- 当前充电 ----------------
                SectionHeader(if (live.recording) "本次充电" else "最近一次充电")
                HyperCard {
                    StatGrid(
                        listOf(
                            "状态" to live.chargeStateText,
                            "电量" to if (live.level >= 0) "${live.level}%" else "--",
                            "充电功率" to fmtPower(live.powerMw),
                            "电流" to if (live.currentMa != -1) "${kotlin.math.abs(live.currentMa)} mA" else "--",
                            "电压" to if (live.voltageMv > 0) "${live.voltageMv} mV" else "--",
                            "温度" to if (live.tempC > 0) String.format("%.1f°C", live.tempC) else "--",
                        )
                    )
                    InfoBlock(
                        listOf(
                            "开始时间" to fmtDateTimeSec(live.sessionStartTs),
                            "已用时长" to fmtDuration(live.durationMs),
                            "预计充满" to when {
                                live.recording && live.estFullRemainMs > 0 -> "约 ${fmtDuration(live.estFullRemainMs)}"
                                live.recording -> "估算中"
                                else -> "--"
                            },
                            "电量区间" to if (live.startLevel >= 0) "${live.startLevel}% → ${live.level}%" else "--",
                            "峰值功率" to fmtPower(live.peakPowerMw),
                            "平均功率" to fmtPower(live.avgPowerMw),
                            "充入能量" to fmtEnergy(live.energyMwh),
                        )
                    )
                }

                // ---------------- 充电曲线 ----------------
                val pts = live.points
                val levelSeries = remember(pts) {
                    pts.map { if (it.level in 0..100) it.level.toFloat() else null }
                }
                // 充电功率:后端记的是 mW,曲线按 W 展示(与 Cumulus 的充电曲线口径一致)
                val powerSeries = remember(pts) {
                    pts.map { if (it.powerMw > 0) it.powerMw / 1000f else null }
                }
                val xLabels = remember(pts, live.sessionStartTs) {
                    relativeAxisLabels(pts.map { it.ts }, live.sessionStartTs)
                }

                SectionHeader("充电曲线")
                HyperCard {
                    if (pts.size < 2) {
                        EmptyHint(
                            if (live.recording) "正在记录，插上充电器后每 30 秒记一个点，稍后回来查看。"
                            else "还没有充电记录：插上充电器会自动开始记录。"
                        )
                    } else {
                        DetailChart(
                            title = "电量（%）",
                            values = levelSeries,
                            minY = 0f,
                            maxY = 100f,
                            color = ChartColors.BatteryLevel,
                            xLabels = xLabels,
                            elapsedMs = pts.map { it.ts - live.sessionStartTs },
                        )
                        Spacer(Modifier.height(12.dp))
                        DetailChart(
                            title = "充电功率（W）",
                            values = powerSeries,
                            minY = 0f,
                            maxY = null,
                            color = ChartColors.Charge,
                            xLabels = xLabels,
                            elapsedMs = pts.map { it.ts - live.sessionStartTs },
                        )
                    }
                }

                // ---------------- 历史充电 ----------------
                SectionHeader("历史充电")
                if (sessions.isEmpty()) {
                    HyperCard { EmptyHint("暂无历史充电记录。") }
                } else {
                    HyperListGroup(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        sessions.forEach { item ->
                            val meta = item.charge
                            HyperListRow(onClick = {
                                context.startActivity(
                                    Intent(context, ChargeSessionDetailActivity::class.java)
                                        .putExtra(
                                            ChargeSessionDetailActivity.EXTRA_DIR,
                                            item.dir.absolutePath
                                        )
                                )
                            }) {
                                ListRowContent(
                                    title = fmtDateTime(meta?.startTs ?: 0L) +
                                        if (item.active) " · 进行中" else "",
                                    summary = buildString {
                                        append("${meta?.startLevel ?: -1}% → ${meta?.endLevel ?: -1}%")
                                        append(" · ${fmtDuration(meta?.durationMs ?: 0L)}")
                                        val avg = meta?.avgPowerMw ?: -1.0
                                        if (avg >= 0) append(" · 平均 ${fmtPower(avg)}")
                                        if (meta?.reachedFull == true) append(" · 已充满")
                                    },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "记录方式：插上充电器即开始、拔下或充满即结束，每 30 秒记一点（电量 + 功率 + 温度）。",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
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
