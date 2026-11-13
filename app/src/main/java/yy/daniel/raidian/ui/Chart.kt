package yy.daniel.raidian.ui

import android.graphics.Paint
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.input.pointer.pointerInput
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

/** Full data remains available to selection and statistics; only the drawing is reduced. */
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
    elapsedMs: List<Long> = emptyList(),
    breakBefore: List<Boolean> = emptyList(),
    selectedIndex: Int? = null,
    onSelect: ((Int) -> Unit)? = null,
) {
    val times = remember(elapsedMs, values.size) { elapsedMs.takeIf { validChartTimeline(it, values.size) }.orEmpty() }
    val select by rememberUpdatedState(onSelect)
    val selection = rememberUpdatedState(selectedIndex)
    val touch = if (onSelect == null) Modifier else Modifier
        .pointerInput(times, values.size, showAxis) {
            detectTapGestures { point ->
                val left = if (showAxis) ChartAxisDefaults.YAxisWidthDp.dp.toPx() else 0f
                val width = (size.width - left - 4.dp.toPx()).coerceAtLeast(1f)
                nearestChartIndex((point.x - left) / width, values.size, times)?.let { select?.invoke(it) }
            }
        }
        .pointerInput(times, values.size, showAxis) {
            fun pick(x: Float) {
                val left = if (showAxis) ChartAxisDefaults.YAxisWidthDp.dp.toPx() else 0f
                val width = (size.width - left - 4.dp.toPx()).coerceAtLeast(1f)
                nearestChartIndex((x - left) / width, values.size, times)?.let { select?.invoke(it) }
            }
            detectHorizontalDragGestures(onDragStart = { pick(it.x) }) { change, _ ->
                change.consume()
                pick(change.position.x)
            }
        }
    val axisColor = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurfaceVariantSummary
    // Keep this modifier stable when the selection changes; the marker is read in onDrawBehind.
    val drawing = remember(values, times, breakBefore, color, minY, maxY, lineWidth, fillAlpha, showAxis, xLabels, axisColor) {
        Modifier.drawWithCache {
            val ax = if (showAxis) axisOffsets() else AxisInset(0f, 6.dp.toPx(), 4.dp.toPx(), 0f)
            val w = (size.width - ax.left - ax.right).coerceAtLeast(0f)
            val h = (size.height - ax.top - ax.bottom).coerceAtLeast(0f)
            val vertices = reduceChart(values, times, breakBefore, w.toInt())
            val range = chartRange(listOf(vertices), minY, maxY)
            fun x(i: Int) = ax.left + w * chartFraction(i, values.size, times)
            fun y(v: Float) = ax.top + h - (v - range.first) / range.second * h
            val paths = chartPaths(vertices, ::x, ::y, ax.top + h, fillAlpha > 0f)
            val stroke = Stroke(width = lineWidth.toPx(), cap = StrokeCap.Round)
            onDrawBehind {
                if (showAxis) drawAxes(ax, w, h, range.first, range.first + range.second, xLabels, axisColor)
                if (fillAlpha > 0f) drawPath(paths.fill, color.copy(alpha = fillAlpha))
                drawPath(paths.line, color, style = stroke)
                paths.dots.forEach { drawCircle(color, 2.dp.toPx(), it) }
                selection.value?.takeIf { it in values.indices }?.let { index ->
                    val px = x(index)
                    drawLine(axisColor.copy(alpha = 0.6f), Offset(px, ax.top), Offset(px, ax.top + h), 1.dp.toPx())
                    values[index]?.takeIf { it.isFinite() }?.let { drawCircle(color, 5.dp.toPx(), Offset(px, y(it))) }
                }
            }
        }
    }
    Box(modifier.then(touch).then(drawing))
}

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
    elapsedMs: List<Long> = emptyList(),
    breakBefore: List<Boolean> = emptyList(),
) {
    val count = series.maxOfOrNull { it.second.size } ?: 0
    val times = remember(elapsedMs, count) { elapsedMs.takeIf { validChartTimeline(it, count) }.orEmpty() }
    val axisColor = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurfaceVariantSummary
    val drawing = remember(series, times, breakBefore, colors, minY, maxY, lineWidth, showAxis, xLabels, axisColor) {
        Modifier.drawWithCache {
            val ax = if (showAxis) axisOffsets() else AxisInset(0f, 6.dp.toPx(), 4.dp.toPx(), 0f)
            val w = (size.width - ax.left - ax.right).coerceAtLeast(0f)
            val h = (size.height - ax.top - ax.bottom).coerceAtLeast(0f)
            val reduced = series.map { reduceChart(it.second, times, breakBefore, w.toInt(), count) }
            val range = chartRange(reduced, minY, maxY)
            fun x(i: Int) = ax.left + w * chartFraction(i, count, times)
            fun y(v: Float) = ax.top + h - (v - range.first) / range.second * h
            val paths = reduced.map { chartPaths(it, ::x, ::y, ax.top + h, false) }
            val stroke = Stroke(width = lineWidth.toPx(), cap = StrokeCap.Round)
            onDrawBehind {
                if (showAxis) drawAxes(ax, w, h, range.first, range.first + range.second, xLabels, axisColor)
                paths.forEachIndexed { i, path ->
                    val color = colors.getOrNull(i) ?: ChartColors.Power
                    drawPath(path.line, color, style = stroke)
                    path.dots.forEach { drawCircle(color, 2.dp.toPx(), it) }
                }
            }
        }
    }
    Box(modifier.then(drawing))
}

private fun chartRange(series: List<List<ChartVertex>>, minY: Float?, maxY: Float?): Pair<Float, Float> {
    var low = Float.POSITIVE_INFINITY
    var high = Float.NEGATIVE_INFINITY
    for (points in series) for (point in points) {
        low = minOf(low, point.value)
        high = maxOf(high, point.value)
    }
    val lo = minY ?: low.takeIf { it.isFinite() } ?: 0f
    val hi = maxY ?: high.takeIf { it.isFinite() } ?: 1f
    val pad = if (minY == null && maxY == null) (hi - lo).coerceAtLeast(1f) * 0.08f else 0f
    return (lo - pad) to (hi - lo + 2 * pad).coerceAtLeast(1f)
}

private data class ChartPaths(val line: Path, val fill: Path, val dots: List<Offset>)

private fun chartPaths(points: List<ChartVertex>, x: (Int) -> Float, y: (Float) -> Float, bottom: Float, filled: Boolean): ChartPaths {
    val line = Path()
    val fill = Path()
    val dots = ArrayList<Offset>()
    points.forEachIndexed { i, point ->
        val px = x(point.index)
        val py = y(point.value)
        val end = i == points.lastIndex || points[i + 1].move
        if (point.move) {
            line.moveTo(px, py)
            if (filled) fill.moveTo(px, bottom)
            if (end) dots.add(Offset(px, py))
        } else line.lineTo(px, py)
        if (filled) {
            fill.lineTo(px, py)
            if (end) { fill.lineTo(px, bottom); fill.close() }
        }
    }
    return ChartPaths(line, fill, dots)
}

// ==================== 坐标轴绘制 ====================

/** 轴占位(px):左 y 轴文字宽、无上/右、下 x 轴标签高 */
private data class AxisInset(val left: Float, val top: Float, val right: Float, val bottom: Float)

private fun androidx.compose.ui.unit.Density.axisOffsets(): AxisInset = AxisInset(
    left = ChartAxisDefaults.YAxisWidthDp.dp.toPx(),
    top = 6.dp.toPx(),
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
