package yy.daniel.raidian.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

class ChartDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val snapshot = intent.getStringExtra("chart_snapshot").orEmpty()
        setContent {
            val owner = rememberNavigationEventDispatcherOwner(enabled = true, parent = null)
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                MiuixTheme {
                    var loading by remember { mutableStateOf(true) }
                    var document by remember { mutableStateOf<ChartDocument?>(null) }
                    LaunchedEffect(snapshot) {
                        document = withContext(Dispatchers.IO) { ChartSnapshots.read(this@ChartDetailActivity, snapshot) }
                        loading = false
                    }
                    ChartDetailScreen(document, loading, onBack = { finish() })
                }
            }
        }
    }
}

@Composable
private fun ChartDetailScreen(document: ChartDocument?, loading: Boolean, onBack: () -> Unit) {
    SubPageScaffold(title = document?.title ?: "曲线详情", onBack = onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 900.dp).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (document == null) {
                    item { HyperCard { Text(if (loading) "正在加载曲线" else "曲线快照已失效，请返回重新打开") } }
                } else {
                    val ordered = if (document.series.any { it.coreId != null }) orderedCoreSeries(document.series) else document.series
                    item {
                        SectionHeader("曲线信息")
                        HyperCard {
                            if (document.source.isNotBlank()) InfoRow("数据来源", document.source)
                            InfoRow("时间范围", if (validChartTimeline(document.elapsedMs, document.pointCount))
                                "${chartTime(document.elapsedMs.first(), true)} ～ ${chartTime(document.elapsedMs.last(), true)}"
                                else "按采样序号显示")
                            Spacer(Modifier.height(12.dp))
                            Text("轻触曲线选择采样点，横向拖动查看相邻点。", fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            if (document.series.any { it.coreId != null }) {
                                Spacer(Modifier.height(8.dp))
                                Text("按频率簇的硬件最高频率从高到低排列。", fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                    }
                    items(ordered, key = { it.coreId?.let { id -> "cpu$id" } ?: it.name }) { series ->
                        val title = if (series.coreId != null) "CPU ${series.coreId} · ${coreClusterLabel(series, document.series)}" else series.name
                        SectionHeader(title)
                        HyperCard { InteractiveSeries(document, series) }
                    }
                }
            }
        }
    }
}

@Composable
private fun InteractiveSeries(document: ChartDocument, series: ChartSeriesData) {
    var selected by rememberSaveable(series.name) { mutableStateOf<Int?>(null) }
    val stats = remember(series) { chartStats(series.values) }
    val times = remember(document, series) {
        document.elapsedMs.takeIf { validChartTimeline(it, series.values.size) }.orEmpty()
    }
    val labels = remember(times, series.values.size) {
        chartTimeLabels(times) ?: (0..4).map { (1 + ((series.values.size - 1).coerceAtLeast(0) * it / 4)).toString() }
    }
    fun value(v: Number?): String = v?.let { String.format("%.2f %s", it.toDouble(), document.unit).trim() } ?: "--"
    LineChart(
        values = series.values, color = Color(series.colorArgb),
        minY = document.minY, maxY = document.maxY,
        xLabels = labels, elapsedMs = times, breakBefore = document.breakBefore,
        selectedIndex = selected, onSelect = { selected = it },
        modifier = Modifier.fillMaxWidth().height(240.dp),
    )
    Spacer(Modifier.height(16.dp))
    val point = selected
    if (point != null && point in series.values.indices) {
        val x = times.getOrNull(point)?.let { "时间 ${chartTime(it, true)}" } ?: "样本 ${point + 1}"
        Text("$x · ${value(series.values[point])}", fontSize = 17.sp, fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.primary)
        if (series.values[point] == null) Text("该采样点无有效读数", fontSize = 14.sp)
    } else {
        Text("尚未选择采样点", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    Spacer(Modifier.height(16.dp))
    InfoRow("采样均值", value(stats.average))
    InfoRow("最低值", value(stats.min))
    InfoRow("最高值", value(stats.max))
    InfoRow("有效／缺失", "${stats.count}／${stats.missing}")
    if (series.maxMhz > 0) InfoRow("硬件最高频率", "${series.maxMhz} MHz")
}
