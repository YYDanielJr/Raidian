package yy.daniel.raidian.monitor

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话目录的读取端(历史列表 + 详情页数据加载),与 [SessionRecorder] 的写端配对。
 *
 * 目录结构(由 SessionRecorder 建立):
 *   files/raidian/sessions/<yyyyMMdd_HHmmss>/
 *     session.json   元数据(手写 JSON,含统计,见 [SessionMeta])
 *     samples.csv    原始采样(首行表头)
 *
 * 读取实现全部是"轻量手写"解析(JSON/CSV),刻意不引入第三方库;
 * 因此这里解析出的字段必须与 SessionRecorder/SessionMeta 写出的格式严格一致。
 */
class SessionStore(private val context: Context) {

    /** 会话根目录(filesDir 下,应用私有,免存储权限) */
    private val sessionsDir: File
        get() = File(context.filesDir, "raidian/sessions")

    /** 全部会话目录,新 → 旧(目录名是 yyyyMMdd_HHmmss,字典序即时间序) */
    fun listDirs(): List<File> =
        (sessionsDir.listFiles { f -> f.isDirectory } ?: emptyArray())
            .sortedByDescending { it.name }

    /** 解析会话摘要(读 session.json;解析失败/文件缺失返回 null) */
    fun readMeta(dir: File): SessionMeta? {
        val f = File(dir, "session.json")
        if (!f.exists()) return null
        return try {
            parseMeta(f.readText())
        } catch (e: Exception) {
            null
        }
    }

    /** 读取 CSV 的全部数据行(去掉表头),供曲线与详情;无则空列表 */
    fun readSamples(dir: File): List<List<String>> {
        val f = File(dir, "samples.csv")
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().drop(1).mapNotNull { line ->
                if (line.isBlank()) null else splitCsv(line)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 读取 CSV 表头 + 数据行(供按列名定位曲线,兼容新旧表头)。
     * @return Pair(表头字段名列表, 数据行);文件缺失/空返回 (emptyList, emptyList)
     */
    fun readSamplesWithHeader(dir: File): Pair<List<String>, List<List<String>>> {
        val f = File(dir, "samples.csv")
        if (!f.exists()) return emptyList<String>() to emptyList()
        return try {
            val lines = f.readLines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return emptyList<String>() to emptyList()
            val header = splitCsv(lines.first())
            val rows = lines.drop(1).map { splitCsv(it) }
            header to rows
        } catch (e: Exception) {
            emptyList<String>() to emptyList()
        }
    }

    /**
     * 从目录名/元数据得到列表展示时间文本。
     * 优先用元数据里的 startTime;旧数据没有元数据时回退解析目录名。
     */
    fun dirLabel(dir: File, meta: SessionMeta?): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val ts = meta?.startTime ?: run {
            // 目录名 yyyyMMdd_HHmmss
            try {
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(dir.name)?.time ?: 0L
            } catch (e: Exception) { 0L }
        }
        return if (ts > 0) fmt.format(Date(ts)) else dir.name
    }

    /**
     * 删除一组会话目录(整个目录树递归删除)。
     * 返回成功删除的目录数;不存在的目录视为已删除(计入成功)。
     */
    fun deleteDirs(dirs: List<File>): Int {
        var ok = 0
        for (dir in dirs) {
            try {
                if (!dir.exists()) { ok++; continue }
                dir.deleteRecursively()
                if (!dir.exists()) ok++
            } catch (e: Exception) {
                // 单个失败不中断,继续删其余
            }
        }
        return ok
    }

    // ---- 手写轻量 JSON 解析(session.json 是我们自己写的,结构已知且固定) ----

    /** 把 session.json 文本解析成 [SessionMeta];字段缺失时回退默认值 */
    private fun parseMeta(text: String): SessionMeta {
        val map = parseJsonObject(text)
        val fps = FpsStats(
            avgFps = (map["avgFps"] as? Double)?.toFloat() ?: -1f,
            low1Fps = (map["low1Fps"] as? Double)?.toFloat() ?: -1f,
            low01Fps = (map["low01Fps"] as? Double)?.toFloat() ?: -1f,
            sampleCount = (map["sampleCount"] as? Double)?.toInt() ?: 0,
        )
        return SessionMeta(
            startTime = (map["startTime"] as? Double)?.toLong() ?: 0L,
            endTime = (map["endTime"] as? Double)?.toLong() ?: 0L,
            durationMs = (map["durationMs"] as? Double)?.toLong() ?: 0L,
            targetPkg = map["targetPkg"] as? String ?: "",
            targetLabel = map["targetLabel"] as? String ?: "",
            watchPkg = map["watchPkg"] as? String ?: "",
            intervalMs = (map["intervalMs"] as? Double)?.toLong() ?: 0L,
            fpsStats = fps,
            recordedSamples = (map["recordedSamples"] as? Double)?.toInt() ?: -1,
            avgPowerMw = map["avgPowerMw"] as? Double ?: -1.0,
            avgBatteryTemp = map["avgBatteryTemp"] as? Double ?: -1.0,
            avgCpuTemp = map["avgCpuTemp"] as? Double ?: -1.0,
        )
    }

    /**
     * 极简顶层 JSON 对象解析器:值只支持 Double / String / Boolean(→1.0/0.0)。
     * 足够本场景(session.json 无嵌套对象、无数组)。
     */
    private fun parseJsonObject(text: String): Map<String, Any> {
        val result = LinkedHashMap<String, Any>()
        var i = 0
        val n = text.length
        /** 跳过空白 */
        fun skipWs() { while (i < n && text[i].isWhitespace()) i++ }
        /** 从当前 '"' 读一个字符串(处理 \\ \" \n \t \r 转义) */
        fun readString(): String {
            // text[i] == '"'
            i++
            val sb = StringBuilder()
            while (i < n) {
                val c = text[i]
                when {
                    c == '\\' && i + 1 < n -> {
                        val e = text[i + 1]
                        when (e) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            else -> sb.append(e)
                        }
                        i += 2
                    }
                    c == '"' -> { i++; return sb.toString() }
                    else -> { sb.append(c); i++ }
                }
            }
            return sb.toString()
        }
        /** 读一个值:字符串/数字/布尔/null;遇到 '{' 仅跳过(本元数据无嵌套对象) */
        fun readValue(): Any? {
            skipWs()
            if (i >= n) return null
            val c = text[i]
            return when {
                c == '{' -> {
                    // 仅处理对象值内的原始 token(本元数据无嵌套对象)
                    // 简化:直接返回占位
                    i++
                    null
                }
                c == '"' -> readString()
                c == '-' || c in '0'..'9' -> {
                    val start = i
                    while (i < n && (text[i].isDigit() || text[i] in "-.eE")) i++
                    text.substring(start, i).toDoubleOrNull() ?: 0.0
                }
                text.startsWith("true", i) -> { i += 4; 1.0 }
                text.startsWith("false", i) -> { i += 5; 0.0 }
                text.startsWith("null", i) -> { i += 4; null }
                else -> { i++; null }
            }
        }
        // 首字符应为 '{',随后循环解析 key:value 对
        skipWs()
        if (i < n && text[i] == '{') {
            i++
            while (i < n) {
                skipWs()
                if (i >= n) break
                if (text[i] == '}') { i++; break }
                if (text[i] != '"') { i++; continue }
                val key = readString()
                skipWs()
                if (i < n && text[i] == ':') i++
                val v = readValue()
                if (key.isNotEmpty()) result[key] = v ?: ""
                skipWs()
                if (i < n && text[i] == ',') i++
            }
        }
        return result
    }

    // ---- CSV 行解析(带引号字段,与 Sample.toCsvRow 的 csvEsc 对应) ----

    /** 把一行 CSV 拆成字段列表;支持引号包裹与 "" 转义(SessionStore/Sample 写入格式) */
    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var i = 0
        var inQuote = false
        val n = line.length
        while (i < n) {
            val c = line[i]
            when {
                inQuote -> when {
                    c == '"' && i + 1 < n && line[i + 1] == '"' -> { sb.append('"'); i += 2 }
                    c == '"' -> { inQuote = false; i++ }
                    else -> { sb.append(c); i++ }
                }
                c == '"' -> { inQuote = true; i++ }
                c == ',' -> { out.add(sb.toString()); sb.setLength(0); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        out.add(sb.toString())
        return out
    }
}
