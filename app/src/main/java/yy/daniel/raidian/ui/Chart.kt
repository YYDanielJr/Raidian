package yy.daniel.raidian.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 轻量折线图(纯 Compose Canvas 手绘,无第三方图表库)。
 *
 * 行为约定:
 *  - 入参 [values] 为 List<Float?>:null 视为"断点",断点两侧不连线(数据缺口处留白);
 *  - 纵轴范围:传入 [minY]/[maxY] 则固定;两者都 null 时按数据自适应,并上下留 8% 边;
 *  - -1 这类"无有效值"哨兵不在这里处理 —— 调用方应先用 [sampleSeries] 转成 null。
 *  - 坐标轴:[showAxis]=true 时左侧画 y 刻度值与 x 轴刻度([xLabels] 提供底标文字);
 *    绘图区自动为坐标轴留白(左 ~44dp、下 ~20dp)。
 *
 * 用途:实时页近 2 分钟曲线 + 会话详情页各指标曲线(见 ChartRow / DetailChart)。
 */
@Composable
fun LineChart(
    values: List<Float?>,
    modifier: Modifier = Modifier,
    color: Color = ChartColors.Power,
    minY: Float? = null,
    maxY: Float? = null,
    gridColor: Color = ChartAxisDefaults.Grid,
    lineWidth: Dp = 1.5.dp,
    fillAlpha: Float = 0f,
    showAxis: Boolean = true,
    xLabels: List<String>? = null,
) {
    val axisColor = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurfaceVariantSummary
    Canvas(modifier = modifier) {
        if (size.width <= 0f || size.height <= 0f || values.isEmpty()) return@Canvas

        // 坐标轴留白
        val ax = axisOffsets()
        val plotW = size.width - ax.left - ax.right
        val plotH = size.height - ax.top - ax.bottom
        if (plotW <= 0f || plotH <= 0f) return@Canvas

        // 只取有效点(index, value)
        val pts = values.mapIndexedNotNull { i, v -> if (v != null) i to v else null }
        if (pts.isEmpty()) {
            drawAxes(ax, plotW, plotH, 0f, 1f, xLabels, axisColor)
            return@Canvas
        }

        // 纵轴范围
        val lo = minY ?: pts.minOf { it.second }
        val hi = maxY ?: pts.maxOf { it.second }
        val span = (hi - lo).takeIf { it > 0f } ?: 1f
        val pad = if (minY == null && maxY == null) span * 0.08f else 0f
        val lo2 = (lo - pad).coerceAtMost(lo)
        val hi2 = (hi + pad).coerceAtLeast(hi)
        val span2 = (hi2 - lo2).coerceAtLeast(1f)

        fun xOf(i: Int): Float = ax.left + plotW * i / (values.size - 1).coerceAtLeast(1)
        fun yOf(v: Float): Float = ax.top + plotH - (v - lo2) / span2 * plotH

        drawAxes(ax, plotW, plotH, lo2, lo2 + span2, xLabels, axisColor)

        // 连线(断点断开)
        val linePath = Path()
        var first = true
        var i = 0
        while (i < pts.size) {
            val (idx, v) = pts[i]
            if (first) { linePath.moveTo(xOf(idx), yOf(v)); first = false }
            else linePath.lineTo(xOf(idx), yOf(v))
            val next = pts.getOrNull(i + 1)
            if (next != null && next.first - idx > 1) first = true
            i++
        }
        if (fillAlpha > 0f) {
            val fillPath = Path()
            if (pts.size >= 1) {
                fillPath.moveTo(xOf(pts.first().first), ax.top + plotH)
                pts.forEach { (idx, v) -> fillPath.lineTo(xOf(idx), yOf(v)) }
                fillPath.lineTo(xOf(pts.last().first), ax.top + plotH)
                fillPath.close()
            }
            drawPath(fillPath, color = color.copy(alpha = fillAlpha))
        }
        drawPath(linePath, color = color, style = Stroke(width = lineWidth.toPx(), cap = StrokeCap.Round))
    }
}

/**
 * 多序列折线图:同一坐标系叠画多条线(如每个 CPU 核心的频率曲线)。
 * 各序列独立断点处理;坐标轴/颜色语义同 [LineChart]。
 */
@Composable
fun MultiLineChart(
    series: List<Pair<String, List<Float?>>>,
    modifier: Modifier = Modifier,
    colors: List<Color> = emptyList(),
    minY: Float? = null,
    maxY: Float? = null,
    gridColor: Color = ChartAxisDefaults.Grid,
    lineWidth: Dp = 1.2.dp,
    showAxis: Boolean = true,
    xLabels: List<String>? = null,
) {
    val axisColor = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurfaceVariantSummary
    Canvas(modifier = modifier) {
        if (size.width <= 0f || size.height <= 0f || series.isEmpty()) return@Canvas

        val ax = axisOffsets()
        val plotW = size.width - ax.left - ax.right
        val plotH = size.height - ax.top - ax.bottom
        if (plotW <= 0f || plotH <= 0f) return@Canvas

        // 每序列有效点 + 全量并集纵轴
        val allPts = ArrayList<Float>()
        val each = series.map { (_, values) ->
            val pts = values.mapIndexedNotNull { i, v -> if (v != null) i to v else null }
            pts.forEach { allPts.add(it.second) }
            pts
        }
        val maxLen = series.maxOf { it.second.size }.coerceAtLeast(1)
        if (allPts.isEmpty()) {
            drawAxes(ax, plotW, plotH, 0f, 1f, xLabels, axisColor)
            return@Canvas
        }
        val lo = minY ?: allPts.min()
        val hi = maxY ?: allPts.max()
        val span = (hi - lo).takeIf { it > 0f } ?: 1f
        val pad = if (minY == null && maxY == null) span * 0.08f else 0f
        val lo2 = lo - pad
        val hi2 = hi + pad
        val span2 = (hi2 - lo2).coerceAtLeast(1f)

        fun xOf(i: Int): Float = ax.left + plotW * i / (maxLen - 1).coerceAtLeast(1)
        fun yOf(v: Float): Float = ax.top + plotH - (v - lo2) / span2 * plotH

        drawAxes(ax, plotW, plotH, lo2, lo2 + span2, xLabels, axisColor)

        // 逐序列连线
        series.forEachIndexed { si, _ ->
            val pts = each[si]
            if (pts.isEmpty()) return@forEachIndexed
            val color = colors.getOrNull(si) ?: ChartColors.Power
            val linePath = Path()
            var first = true
            var i = 0
            while (i < pts.size) {
                val (idx, v) = pts[i]
                if (first) { linePath.moveTo(xOf(idx), yOf(v)); first = false }
                else linePath.lineTo(xOf(idx), yOf(v))
                val next = pts.getOrNull(i + 1)
                if (next != null && next.first - idx > 1) first = true
                i++
            }
            drawPath(linePath, color = color, style = Stroke(width = lineWidth.toPx(), cap = StrokeCap.Round))
        }
    }
}

// ==================== 坐标轴绘制 ====================

/** 轴占位(px):左 y 轴文字宽、无上/右、下 x 轴标签高 */
private data class AxisInset(val left: Float, val top: Float, val right: Float, val bottom: Float)

private fun DrawScope.axisOffsets(): AxisInset = AxisInset(
    left = ChartAxisDefaults.YAxisWidthDp.dp.toPx(),
    top = 0f,
    right = 4.dp.toPx(),
    bottom = ChartAxisDefaults.XAxisHeightDp.dp.toPx(),
)

/**
 * 画网格(横线)+ y 轴刻度文字 + 轴框 + x 轴刻度/标签。
 * 刻度数固定 4 档(0..4)。
 */
private fun DrawScope.drawAxes(
    ax: AxisInset,
    plotW: Float,
    plotH: Float,
    lo: Float,
    hi: Float,
    xLabels: List<String>?,
    labelColor: Color,
) {
    val left = ax.left
    val top = ax.top
    val bottom = top + plotH
    val right = left + plotW
    val span = (hi - lo).takeIf { it > 0f } ?: 1f

    val lineColor = labelColor.copy(alpha = 0.35f)
    val gridColor = labelColor.copy(alpha = 0.15f)
    val textSizePx = 10.dp.toPx()

    // 构造文字画笔(每次 draw 调用创建可接受:图表数量有限)
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelColor.toArgb()
        textSize = textSizePx
    }

    val nTicks = 4
    // 网格 + y 刻度文字
    for (t in 0..nTicks) {
        val v = lo + span * t / nTicks
        val y = bottom - plotH * t / nTicks
        drawLine(
            color = gridColor,
            start = Offset(left, y),
            end = Offset(right, y),
            strokeWidth = 1f,
        )
        val text = axisNum(v)
        val tw = textPaint.measureText(text)
        drawContext.canvas.nativeCanvas.drawText(text, left - tw - 5.dp.toPx(), y + textSizePx * 0.35f, textPaint)
        drawLine(color = lineColor, start = Offset(left - 4.dp.toPx(), y), end = Offset(left, y), strokeWidth = 1f)
    }
    // 轴框
    drawLine(color = lineColor, start = Offset(left, top), end = Offset(left, bottom), strokeWidth = 1f)
    drawLine(color = lineColor, start = Offset(left, bottom), end = Offset(right, bottom), strokeWidth = 1f)

    // x 轴刻度 + 标签
    for (t in 0..nTicks) {
        val x = left + plotW * t / nTicks
        drawLine(color = lineColor, start = Offset(x, bottom), end = Offset(x, bottom + 4.dp.toPx()), strokeWidth = 1f)
    }
    if (!xLabels.isNullOrEmpty()) {
        val n = xLabels.size
        for (t in 0..nTicks) {
            val x = left + plotW * t / nTicks
            val idx = (n - 1) * t / nTicks
            val lbl = xLabels.getOrNull(idx) ?: ""
            val tw = textPaint.measureText(lbl)
            val lx = (x - tw / 2).coerceIn(left, (right - tw).coerceAtLeast(left))
            drawContext.canvas.nativeCanvas.drawText(
                lbl, lx, bottom + 5.dp.toPx() + textSizePx, textPaint
            )
        }
    }
}

/** 把可空 Float 列表变成折线图可用值(-1 视为空/断点,null 原样透传) */
fun sampleSeries(values: List<Float>): List<Float?> =
    values.map { if (!it.isFinite() || it < 0f) null else it }
