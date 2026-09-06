package yy.daniel.raidian.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 会话详情 Activity:展示单条历史会话的统计信息与曲线。
 *
 * 从记录页(历史记录数据)点击某条会话时,以新 Activity 打开,触发系统
 * Activity 转场动画。会话目录经 Intent extra([EXTRA_DIR])传入,进入后
 * 由 [MainViewModel.openSession] 异步加载 session.json + samples.csv。
 */
class SessionDetailActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dir = intent.getStringExtra(EXTRA_DIR)
        setContent {
            MiuixTheme {
                SessionDetailScreen(
                    dirPath = dir,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    companion object {
        /** Intent extra:会话目录绝对路径 */
        const val EXTRA_DIR = "session_dir"

        /** 从会话目录构造打开详情的 Intent */
        fun intent(context: android.content.Context, dir: File): android.content.Intent =
            android.content.Intent(context, SessionDetailActivity::class.java)
                .putExtra(EXTRA_DIR, dir.absolutePath)
    }
}

@Composable
private fun SessionDetailScreen(
    dirPath: String?,
    modifier: Modifier = Modifier,
    vm: MainViewModel = viewModel(),
) {
    val context = LocalContext.current
    val detail by vm.sessionDetail.collectAsState()

    // 进入页面:加载该会话详情(目录无效时显示"无法读取")
    LaunchedEffect(dirPath) {
        val dir = dirPath?.takeIf { it.isNotBlank() }?.let { File(it) }
        vm.openSession(dir)
    }

    SubPageScaffold(
        title = "会话详情",
        onBack = { (context.findActivity())?.finish() },
        content = { padding ->
            SessionDetailPage(
                detail = detail,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
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

// ==================== 会话详情页 ====================

/**
 * 会话详情内容:元数据信息卡(时间/时长/帧率统计/均值)+ 四条曲线
 * (帧率/功耗/电池温度/核心温度)。数据来自 vm.sessionDetail(openSession 加载)。
 */
@Composable
private fun SessionDetailPage(
    detail: MainViewModel.SessionDetail?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        if (detail == null) {
            Text(
                "无法读取该会话",
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        } else {
            val meta = detail.meta
            val target = meta.targetLabel.takeIf { it.isNotBlank() } ?: meta.targetPkg
            val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(meta.startTime))
            val dur = meta.durationMs / 1000
            val durStr = if (dur > 0) String.format("%d分%02d秒", dur / 60, dur % 60) else "--"

            // 会话信息卡
            SectionHeader(if (target.isBlank()) "会话" else target)
            HyperCard {
                InfoRow("时间", time)
                InfoRow("时长", durStr)
                meta.fpsStats.takeIf { it.sampleCount > 0 }?.let { fps ->
                    InfoRow("帧率统计", String.format(
                        "平均 %.1f · 1%%Low %.1f · 0.1%%Low %.1f （采样 %d）",
                        fps.avgFps, fps.low1Fps, fps.low01Fps, fps.sampleCount
                    ))
                }
                if (meta.avgPowerMw > 0) {
                    InfoRow("平均功耗", if (meta.avgPowerMw > 1000)
                        String.format("%.2f W", meta.avgPowerMw / 1000) else String.format("%.0f mW", meta.avgPowerMw))
                }
                if (meta.avgBatteryTemp > 0) InfoRow("平均电池温度", String.format("%.1f °C", meta.avgBatteryTemp))
                if (meta.avgCpuTemp > 0) InfoRow("平均核心温度", String.format("%.1f °C", meta.avgCpuTemp))
                // 指定应用门控的记录:标注只录了目标应用在前台的时段
                if (meta.watchPkg.isNotEmpty()) {
                    InfoRow("记录范围", String.format("仅 %s 在前台时", meta.targetLabel.ifBlank { meta.watchPkg }))
                }
            }

            // 曲线区(各曲线独立判断;无有效点的不画)
            SectionHeader("曲线")
            HyperCard {
                // x 轴时间标签(整条曲线共用 detail.elapsed)
                val xl = timeAxisLabels(detail.elapsed)
                // 每核频率曲线(多序列叠加;有任一核数据才显示)
                if (detail.perCoreFreq.isNotEmpty() && detail.perCoreFreq.any { it.any { v -> v >= 0f } }) {
                    DetailMultiChart("CPU 核心频率（MHz）", detail.perCoreFreq, xl)
                    Spacer(Modifier.height(10.dp))
                }
                // GPU 占用曲线
                if (detail.gpuLoad.any { it >= 0f }) {
                    DetailChart("GPU 占用（%）", sampleSeries(detail.gpuLoad), 0f, 100f, ChartColors.Gpu, xl)
                    Spacer(Modifier.height(10.dp))
                }
                var drewAny = detail.perCoreFreq.isNotEmpty() || detail.gpuLoad.any { it >= 0f }
                if (detail.fps.isNotEmpty() && detail.fps.any { it >= 0f }) {
                    DetailChart("帧率（FPS）", sampleSeries(detail.fps), 0f, 120f, ChartColors.Fps, xl)
                    Spacer(Modifier.height(10.dp)); drewAny = true
                }
                if (detail.power.any { it > 0f }) {
                    DetailChart("功耗（mW）", sampleSeries(detail.power), 0f, null, ChartColors.Power, xl)
                    Spacer(Modifier.height(10.dp)); drewAny = true
                }
                if (detail.batteryTemp.any { it >= 0f }) {
                    DetailChart("电池温度（°C）", sampleSeries(detail.batteryTemp), 0f, null, ChartColors.BatteryTemp, xl)
                    Spacer(Modifier.height(10.dp)); drewAny = true
                }
                if (detail.cpuTemp.any { it >= 0f }) {
                    DetailChart("核心温度（°C）", sampleSeries(detail.cpuTemp), 0f, null, ChartColors.CpuTemp, xl)
                    Spacer(Modifier.height(10.dp)); drewAny = true
                }
                if (!drewAny) {
                    Text("本会话暂无有效曲线数据", fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
