package yy.daniel.raidian.monitor

import yy.daniel.raidian.ui.ChartDocument
import yy.daniel.raidian.ui.chartFraction
import yy.daniel.raidian.ui.chartStats
import yy.daniel.raidian.ui.chartTime
import yy.daniel.raidian.ui.reduceChart
import java.io.File
import java.io.OutputStream
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Standalone, offline report. No remote assets, scripts, or external services. */
object SessionExporter {
    fun csv(source: File, output: OutputStream) {
        // UTF-8 BOM allows Excel to recognize Chinese labels without an import wizard.
        output.write(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()))
        source.inputStream().use { it.copyTo(output) }
    }

    fun html(meta: SessionMeta, charts: List<ChartDocument>, writer: Writer) {
        fun write(s: String) = writer.write(s)
        fun row(label: String, value: String) = write("<dt>${escape(label)}</dt><dd>${escape(value)}</dd>")
        write("""<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Raidian 记录报告</title><style>
            body{font:16px/1.6 system-ui,sans-serif;background:#f5f6f8;color:#202124;margin:0 auto;padding:24px;max-width:1100px}
            section{background:white;border-radius:16px;padding:24px;margin:20px 0;break-inside:avoid}h1,h2{line-height:1.3}h2{font-size:20px}
            dl{display:grid;grid-template-columns:150px 1fr;gap:8px}dd{margin:0;overflow-wrap:anywhere}dt,.note{color:#606570}
            svg{width:100%;height:auto}table{width:100%;border-collapse:collapse;font-size:14px}td,th{text-align:left;padding:8px;border-bottom:1px solid #ddd}
            .table{overflow:auto}.legend{display:flex;flex-wrap:wrap;gap:12px;font-size:14px}@media(max-width:600px){body{padding:12px}section{padding:14px}dl{grid-template-columns:100px 1fr}}
            </style><h1>Raidian 记录报告</h1><section><h2>${escape(meta.targetLabel.ifBlank { meta.targetPkg }.ifBlank { "会话记录" })}</h2><dl>""")
        row("应用包名", meta.targetPkg)
        row("开始时间", SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(meta.startTime)))
        row("时长", chartTime(meta.durationMs))
        row("采样间隔", if (meta.intervalMs > 0) "${meta.intervalMs} 毫秒" else "未记录")
        row("记录点数", if (meta.recordedSamples >= 0) meta.recordedSamples.toString() else charts.maxOfOrNull { it.pointCount }?.toString() ?: "0")
        row("记录范围", if (meta.watchPkg.isBlank()) "跟随前台应用" else "仅 ${meta.watchPkg} 在前台时")
        row("平均帧率", number(meta.fpsStats.avgFps.toDouble()))
        row("1% Low／0.1% Low", "${number(meta.fpsStats.low1Fps.toDouble())}／${number(meta.fpsStats.low01Fps.toDouble())}")
        row("平均电池功率", number(meta.avgPowerMw) + " mW")
        row("平均电池温度", number(meta.avgBatteryTemp) + " ℃")
        row("平均核心温度", number(meta.avgCpuTemp) + " ℃")
        write("</dl><p class=note>Low 值为采样窗口的低帧均值估算。曲线按真实时间绘制，保留峰谷和数据缺口；统计使用全部有效采样点。完整逐点数据请另存 CSV 表格。连接电源时，电池功率为电池端净功率。</p></section>")
        if (charts.isEmpty()) write("<section>本会话暂无有效曲线数据。</section>")
        for (chart in charts) {
            write("<section><h2>${escape(chart.title)}</h2>")
            val statistics = chart.series.map { chartStats(it.values) }
            val lo = chart.minY ?: statistics.mapNotNull { it.min }.minOrNull() ?: 0f
            val hi = chart.maxY ?: statistics.mapNotNull { it.max }.maxOrNull() ?: 1f
            val span = (hi - lo).coerceAtLeast(1f)
            write("<svg viewBox=\"0 0 1100 320\" role=\"img\" aria-label=\"${escape(chart.title)}\"><rect x=\"70\" y=\"20\" width=\"1000\" height=\"250\" fill=\"none\" stroke=\"#ccc\"/>")
            for (tick in 0..4) {
                val y = 270 - tick * 250 / 4
                val x = 70 + tick * 250
                write("<path d=\"M70 $y H1070\" stroke=\"#e5e7eb\"/><text x=\"62\" y=\"${y + 4}\" text-anchor=\"end\" font-size=\"12\">${number((lo + span * tick / 4).toDouble())}</text>")
                val label = if (chart.elapsedMs.isNotEmpty()) chartTime(chart.elapsedMs.first() + (chart.elapsedMs.last() - chart.elapsedMs.first()) * tick / 4)
                    else (chart.pointCount * tick / 4).toString()
                write("<text x=\"$x\" y=\"300\" text-anchor=\"${if (tick == 0) "start" else if (tick == 4) "end" else "middle"}\" font-size=\"12\">$label</text>")
            }
            for (series in chart.series) {
                val points = reduceChart(series.values, chart.elapsedMs, chart.breakBefore, 1000, chart.pointCount)
                val color = String.format(Locale.US, "#%06x", series.colorArgb and 0xffffff)
                write("<path fill=\"none\" stroke=\"$color\" stroke-width=\"1.4\" d=\"")
                for (point in points) {
                    val x = 70 + 1000 * chartFraction(point.index, chart.pointCount, chart.elapsedMs)
                    val y = 270 - (point.value - lo) / span * 250
                    write("${if (point.move) "M" else "L"}${coordinate(x)},${coordinate(y)} ")
                }
                write("\"/>")
                points.forEachIndexed { i, p ->
                    if (p.move && (i == points.lastIndex || points[i + 1].move)) {
                        write("<circle cx=\"${coordinate(70 + 1000 * chartFraction(p.index, chart.pointCount, chart.elapsedMs))}\" cy=\"${coordinate(270 - (p.value - lo) / span * 250)}\" r=\"2\" fill=\"$color\"/>")
                    }
                }
            }
            write("</svg><div class=legend>")
            chart.series.forEach { write("<span style=\"color:${String.format(Locale.US, "#%06x", it.colorArgb and 0xffffff)}\">${escape(it.name)}</span>") }
            write("</div><div class=table><table><thead><tr><th>指标</th><th>均值</th><th>最低</th><th>最高</th><th>有效／缺失</th></tr></thead><tbody>")
            chart.series.forEachIndexed { index, series ->
                val stats = statistics[index]
                write("<tr><td>${escape(series.name)}</td><td>${number(stats.average)}</td><td>${number(stats.min?.toDouble())}</td><td>${number(stats.max?.toDouble())}</td><td>${stats.count}／${stats.missing}</td></tr>")
            }
            write("</tbody></table></div></section>")
        }
        write("<footer class=note>由 Raidian 导出。HTML 可离线打开；缺失值不作为零参与统计。</footer></html>")
        writer.flush()
    }

    internal fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    private fun number(value: Double?): String = if (value == null || !value.isFinite() || value < 0) "—" else String.format(Locale.US, "%.2f", value)
    private fun coordinate(value: Float) = String.format(Locale.US, "%.2f", value)
}
