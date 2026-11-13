package yy.daniel.raidian.monitor

import org.junit.Assert.*
import org.junit.Test
import yy.daniel.raidian.ui.ChartDocument
import yy.daniel.raidian.ui.ChartSeriesData
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringWriter

class SessionExporterTest {
    @Test fun csvRetainsAllColumnsRowsAndQuotesWithUtf8Bom() {
        val file = File.createTempFile("raidian-export", ".csv")
        try {
            val csv = "elapsed_ms,app,thermal\n500,\"终末地\",\"{\"\"temp\"\":42}\"\n1000,\"a,b\",-1\n"
            file.writeText(csv)
            val output = ByteArrayOutputStream()
            SessionExporter.csv(file, output)
            assertEquals("\uFEFF$csv", output.toString("UTF-8"))
        } finally { file.delete() }
    }

    @Test fun htmlIsOfflineEscapedAndUsesFullDataForStatistics() {
        val values = MutableList<Float?>(14_400) { 60f }
        values[1000] = 0f
        values[1001] = null
        val chart = ChartDocument("帧率<script>", "FPS", listOf(ChartSeriesData("A&B", values, 0xff008800.toInt())),
            values.indices.map { it * 500L })
        val writer = StringWriter()
        SessionExporter.html(SessionMeta(0, targetLabel = "<img src=x onerror=alert(1)>", intervalMs = 500,
            recordedSamples = 14400, durationMs = 7200000), listOf(chart), writer)
        val html = writer.toString()
        assertTrue(html.startsWith("<!doctype html>"))
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"))
        assertTrue(html.contains("A&amp;B"))
        assertTrue(html.contains("14399／1"))
        assertTrue(html.contains("2:00:00"))
        assertFalse(html.contains("<script"))
        assertFalse(html.contains("https://"))
        assertTrue(html.length < 200_000)
    }

    @Test fun emptyLegacySessionCanBeExported() {
        val writer = StringWriter()
        SessionExporter.html(SessionMeta(0), emptyList(), writer)
        assertTrue(writer.toString().contains("本会话暂无有效曲线数据"))
        assertTrue(writer.toString().contains("未记录"))
    }
}
