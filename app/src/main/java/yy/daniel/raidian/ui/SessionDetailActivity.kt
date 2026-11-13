package yy.daniel.raidian.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.fillMaxWidth
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
    val loading by vm.sessionLoading.collectAsState()
    val exporting by vm.exporting.collectAsState()
    val exportMessage by vm.exportMessage.collectAsState()
    val htmlLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri != null && dirPath != null) vm.exportSession(File(dirPath), uri, true)
    }
    val csvLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null && dirPath != null) vm.exportSession(File(dirPath), uri, false)
    }

    // 进入页面:加载该会话详情(目录无效时显示"无法读取")
    LaunchedEffect(dirPath) {
        val dir = dirPath?.takeIf { it.isNotBlank() }?.let { File(it) }
        vm.openSession(dir)
    }

    SubPageScaffold(
        title = "会话详情",
        onBack = { (context.findActivity())?.finish() },
        content = { padding ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                SessionDetailContent(
                    detail = detail,
                    contentPadding = padding,
                    loading = loading,
                    exportContent = {
                        HyperCard {
                            Text("导出报告", fontSize = 17.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("HTML 包含统计和曲线，可离线查看。CSV 保留全部原始采样列，可用 Excel 等表格软件打开。", fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Spacer(Modifier.height(16.dp))
                            val name = "Raidian_${dirPath?.let { File(it).name }.orEmpty()}"
                            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(),
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                                top.yukonga.miuix.kmp.basic.Button(onClick = { htmlLauncher.launch("$name.html") }, enabled = !exporting,
                                    colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColorsPrimary(), modifier = Modifier.weight(1f),
                                    insideMargin = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) {
                                    Text("导出 HTML", maxLines = 1)
                                }
                                top.yukonga.miuix.kmp.basic.Button(onClick = { csvLauncher.launch("$name.csv") }, enabled = !exporting,
                                    colors = top.yukonga.miuix.kmp.basic.ButtonDefaults.buttonColorsPrimary(), modifier = Modifier.weight(1f),
                                    insideMargin = PaddingValues(horizontal = 8.dp, vertical = 12.dp)) {
                                    Text("导出 CSV", maxLines = 1)
                                }
                            }
                            if (exporting || exportMessage != null) {
                                Spacer(Modifier.height(12.dp))
                                Text(if (exporting) "正在导出，请稍候" else exportMessage.orEmpty(), fontSize = 14.sp)
                            }
                        }
                    },
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                )
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


/** Each chart is its own lazy item; parsing and preparing all series happens off the UI thread. */
@Composable
fun SessionDetailContent(
    detail: MainViewModel.SessionDetail?,
    contentPadding: PaddingValues = PaddingValues(),
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    exportContent: @Composable () -> Unit = {},
) {
    val charts by androidx.compose.runtime.produceState<List<ChartDocument>>(emptyList(), detail) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            detail?.let(::sessionCharts).orEmpty()
        }
    }
    androidx.compose.foundation.lazy.LazyColumn(modifier = modifier.padding(contentPadding),
        contentPadding = PaddingValues(bottom = 20.dp)) {
        if (detail == null) {
            item { Text(if (loading) "正在加载会话" else "无法读取该会话", fontSize = 14.sp,
                modifier = Modifier.padding(16.dp)) }
        } else {
            item(key = "summary") {
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
                if (meta.intervalMs > 0) InfoRow("采样间隔", "${meta.intervalMs} 毫秒")
                if (meta.recordedSamples >= 0) InfoRow("记录点数", meta.recordedSamples.toString())
                meta.fpsStats.takeIf { it.sampleCount > 0 }?.let { fps ->
                    InfoRow("帧率统计", String.format(
                        "平均 %.1f · 1%%Low %.1f · 0.1%%Low %.1f （采样 %d）",
                        fps.avgFps, fps.low1Fps, fps.low01Fps, fps.sampleCount
                    ))
                    Text("Low 值为采样窗口的低帧均值估算。", fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                if (meta.avgPowerMw > 0) {
                    InfoRow("平均电池功率", if (meta.avgPowerMw > 1000)
                        String.format("%.2f W", meta.avgPowerMw / 1000) else String.format("%.0f mW", meta.avgPowerMw))
                }
                if (meta.avgBatteryTemp > 0) InfoRow("平均电池温度", String.format("%.1f °C", meta.avgBatteryTemp))
                if (meta.avgCpuTemp > 0) InfoRow("平均核心温度", String.format("%.1f °C", meta.avgCpuTemp))
                // 指定应用门控的记录:标注只录了目标应用在前台的时段
                if (meta.watchPkg.isNotEmpty()) {
                    InfoRow("记录范围", String.format("仅 %s 在前台时", meta.targetLabel.ifBlank { meta.watchPkg }))
                }
            }


            }
            item(key = "export") { exportContent() }
            item(key = "heading") {
                SectionHeader("曲线")
                Text("点击曲线查看原始采样点。概览按显示宽度保留峰谷，原始数据不变。", fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(horizontal = 16.dp))
            }
            items(charts.size, key = { charts[it].title }) { index ->
                val document = charts[index]
                HyperCard {
                    Text(document.title, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    ChartPreview(document) {
                        if (document.series.size == 1) {
                            LineChart(document.series.first().values, color = Color(document.series.first().colorArgb),
                                minY = document.minY, maxY = document.maxY, elapsedMs = document.elapsedMs,
                                breakBefore = document.breakBefore, xLabels = chartTimeLabels(document.elapsedMs),
                                modifier = Modifier.fillMaxWidth().height(160.dp))
                        } else {
                            MultiLineChart(document.series.map { it.name to it.values },
                                colors = document.series.map { Color(it.colorArgb) }, minY = document.minY, maxY = document.maxY,
                                elapsedMs = document.elapsedMs, breakBefore = document.breakBefore,
                                xLabels = chartTimeLabels(document.elapsedMs), modifier = Modifier.fillMaxWidth().height(180.dp))
                        }
                    }
                    if (document.series.size > 1) Text(document.series.joinToString("　") { it.name }, fontSize = 12.sp)
                }
            }
        }
    }
}

/** Shared full-resolution chart documents for the report screen and offline HTML export. */
fun sessionCharts(detail: MainViewModel.SessionDetail): List<ChartDocument> = buildList {
    val source = "会话记录 · ${detail.meta.targetLabel.ifBlank { detail.meta.targetPkg }}"
    fun multi(title: String, cores: List<List<Float>>) {
        if (cores.none { values -> values.any { it.isFinite() && it >= 0 } }) return
        val palette = ChartColors.corePalette(cores.size)
        add(ChartDocument(title, chartUnit(title), cores.mapIndexed { core, values ->
            ChartSeriesData("CPU $core", sampleSeries(values), palette[core].toArgb(), core,
                detail.corePolicies.getOrNull(core) ?: -1, detail.coreMaxMhz.getOrNull(core) ?: -1)
        }, detail.elapsed, detail.breakBefore, minY = 0f, source = source))
    }
    fun single(title: String, values: List<Float>, color: Color, max: Float? = null) {
        if (values.none { it.isFinite() && it >= 0 }) return
        add(ChartDocument(title, chartUnit(title), listOf(ChartSeriesData(title, sampleSeries(values), color.toArgb())),
            detail.elapsed, detail.breakBefore, minY = 0f, maxY = max, source = source))
    }
    multi("CPU 核心频率（MHz）", detail.perCoreFreq)
    multi("CPU 每核占用（%）", detail.perCoreLoad)
    multi("CPU 独立温度传感器（℃）", detail.perCoreTemp)
    single("CPU 占用（%）", detail.cpuLoad, ChartColors.CpuTemp, 100f)
    single("GPU 占用（%）", detail.gpuLoad, ChartColors.Gpu, 100f)
    single("帧率（FPS）", detail.fps, ChartColors.Fps, maxOf(120f, detail.fps.maxOrNull() ?: 0f))
    single("电池功率（mW）", detail.power, ChartColors.Power)
    single("电池温度（℃）", detail.batteryTemp, ChartColors.BatteryTemp)
    single("核心温度（℃）", detail.cpuTemp, ChartColors.CpuTemp)
    val labels = mapOf("gpu_temp_c" to "GPU 温度（℃）", "npu_temp_c" to "NPU 温度（℃）",
        "skin_temp_c" to "机身温度（℃）", "thermal_status" to "系统热状态（0～6）",
        "available_mem_mb" to "可用内存（MB）", "swap_used_mb" to "交换区已用（MB）")
    detail.extraSeries.forEach { (key, values) ->
        single(labels[key] ?: key, values, if (key.contains("temp")) ChartColors.GpuTemp else ChartColors.Memory)
    }
}
