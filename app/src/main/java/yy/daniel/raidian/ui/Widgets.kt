package yy.daniel.raidian.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import yy.daniel.raidian.monitor.Sample

/**
 * 数值展示类小组件(不是 HyperOS 列表行,是卡片内的数据块)。
 *
 * 数据网格 [SampleGrid] / 带图的 [ChartRow] 用于实时页;
 * [DetailChart] 用于会话详情页的曲线;
 * [InfoRow] 是"标签:值"型信息行,放在详情信息卡里。
 */

/** 实时数据九宫格:按可用宽度自适应列数(3~6 列),负值/无效显示 "--" */
@Composable
fun SampleGrid(s: Sample) {
    fun fmt(v: Float, unit: String): String =
        if (v < 0) "--" else String.format("%.0f%s", v, unit)

    // 9 个单元:标签 → 值
    val cells: List<Pair<String, String>> = listOf(
        "FPS" to (if (s.fps >= 0f) String.format("%.1f", s.fps) else "--"),
        "CPU占用" to fmt(s.cpuLoad, "%"),
        "GPU占用" to fmt(s.gpuLoad, "%"),
        "CPU频率" to (if (s.cpuB > 0) "${s.cpuB}MHz" else "--"),
        "GPU频率" to (if (s.gpuFreq > 0) "${s.gpuFreq}MHz" else "--"),
        "功耗" to (if (s.powerMw > 0) {
            if (s.powerMw > 1000) String.format("%.1fW", s.powerMw / 1000f) else "${s.powerMw}mW"
        } else "--"),
        "电池温度" to (if (s.batteryTemp > 0) String.format("%.1f°C", s.batteryTemp) else "--"),
        "核心温度" to (if (s.maxSocTemp > 0) String.format("%.1f°C", s.maxSocTemp) else "--"),
        "内存" to (if (s.usedMemMb > 0) "${s.usedMemMb}MB" else "--"),
    )

    // 自适应列数:可用宽度越大列数越多(平板/并排时信息密度高)。
    // BoxWithConstraints 只用于读取可用宽度(maxWidth);内容必须用 Column
    // 垂直排列 —— Box 的 content 是 BoxScope,直接放多个 Row 会相互重叠!
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
    ) {
        val maxW = maxWidth
        val columns = when {
            maxW < 240.dp -> 2
            maxW < 420.dp -> 3
            maxW < 560.dp -> 4
            else -> 6
        }
        // 每行 columns 个,不足一行也整行铺开(weight 均分)
        val rows = cells.chunked(columns)
        Column(modifier = Modifier.fillMaxWidth()) {
            rows.forEachIndexed { ri, rowCells ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    rowCells.forEach { (label, value) ->
                        DataCell(label, value, Modifier.weight(1f))
                    }
                }
                if (ri < rows.size - 1) Spacer(Modifier.height(10.dp))
            }
        }
    }
}

/** 网格里的一个数据单元(标签 + 大号数值) */
@Composable
fun DataCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = 2.dp)) {
        Text(label, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 一行 = 左标签列(名称 + 当前值) + 右折线图(带 y 轴刻度)。
 * 数值单位处理:mW/W 类 >1000 自动转 W 显示。
 */
@Composable
fun ChartRow(
    label: String,
    unit: String,
    values: List<Float?>,
    current: Float,
    minY: Float?,
    maxY: Float?,
    color: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.width(76.dp)) {
            Text(label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(
                text = if (current >= 0)
                    if (unit == "W" || unit == "mW")
                        if (current > 1000) String.format("%.2fW", current / 1000) else String.format("%.0fmW", current)
                    else if (unit == "FPS") String.format("%.1f", current)
                    else String.format("%.1f%s", current, unit)
                else "--",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = color,
            )
        }
        Spacer(Modifier.width(8.dp))
        LineChart(
            values = values,
            minY = minY,
            maxY = maxY,
            color = color,
            showAxis = true,
            modifier = Modifier.weight(1f).height(60.dp),
        )
    }
}

/** 多线指标行(实时页用):左标签列(名称+多条当前值)+ 右多线图 + 下方小图例 */
@Composable
fun MultiSeriesRow(
    label: String,
    unit: String,
    series: List<List<Float?>>,
    curValues: List<Float>,
    colors: List<Color>,
    seriesLabels: List<String>,
    minY: Float? = null,
    maxY: Float? = null,
    xLabels: List<String>? = null,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.width(88.dp)) {
                Text(label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                // 当前值:每簇一行 "簇名 值"
                seriesLabels.forEachIndexed { i, s ->
                    val v = curValues.getOrNull(i) ?: -1f
                    Text(
                        text = if (v >= 0) String.format("%s %.0f%s", s, v, unit) else "$s --",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        color = colors.getOrNull(i) ?: Color.Gray,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            val lines: List<Pair<String, List<Float?>>> = series.mapIndexed { i, v ->
                Pair(seriesLabels.getOrNull(i) ?: "s$i", v)
            }
            MultiLineChart(
                series = lines,
                colors = colors,
                minY = minY,
                maxY = maxY,
                showAxis = true,
                xLabels = xLabels,
                modifier = Modifier.weight(1f).height(76.dp),
            )
        }
    }
}

/** 无数据占位行(如 GPU 频率无 root 读不到时) */
@Composable
fun NoDataRow(label: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            "$label 暂无数据",
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 详情页里的一张图:小标题 + 带坐标轴的 LineChart(可选 x 时间标签) */
@Composable
fun DetailChart(
    title: String,
    values: List<Float?>,
    minY: Float?,
    maxY: Float?,
    color: Color,
    xLabels: List<String>? = null,
) {
    Column {
        Text(title, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(4.dp))
        LineChart(
            values = values,
            minY = minY,
            maxY = maxY,
            color = color,
            showAxis = true,
            xLabels = xLabels,
            modifier = Modifier.fillMaxWidth().height(140.dp),
        )
    }
}

/** 详情页多序列图:每逻辑核一条频率曲线(同簇同色系,标题注明核号) */
@Composable
fun DetailMultiChart(
    title: String,
    perCore: List<List<Float>>,
    xLabels: List<String>? = null,
) {
    Column {
        Text(title, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(4.dp))
        val series = perCore.mapIndexed { i, values -> "cpu$i" to sampleSeries(values) }
        val palette = ChartColors.corePalette(perCore.size)
        MultiLineChart(
            series = series,
            colors = palette,
            minY = 0f,
            showAxis = true,
            xLabels = xLabels,
            modifier = Modifier.fillMaxWidth().height(180.dp),
        )
        Spacer(Modifier.height(4.dp))
        // 图例:核号 + 所属簇颜色
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            perCore.forEachIndexed { i, _ ->
                Text("cpu$i", fontSize = 11.sp, color = palette.getOrNull(i) ?: Color.Gray)
            }
        }
    }
}

/** "标签:值"信息行(详情页卡片内) */
@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            label,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(96.dp),
        )
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}
