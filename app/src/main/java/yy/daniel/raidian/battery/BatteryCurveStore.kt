package yy.daniel.raidian.battery

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 续航 / 充电曲线的数据模型与落盘存储。
 *
 * 目录布局(与应用内既有会话记录同风格,均在 app 私有 files 下,无需任何存储权限):
 *
 * ```
 * files/raidian/endurance/<yyyyMMdd_HHmmss>/     # 一个「放电周期」= 从某次充满 100% 到下次充满
 *   meta.json     周期元数据(起止时间、起止电量、容量、样本数)
 *   drain.csv     放电曲线:电量 / 电流 / 电压 / 功耗 / 温度 / 屏态 + 系统口径时长与耗电
 *   apps.csv      每应用耗电快照(长表:ts,uid,pkg,label,mah),用于「每应用功耗曲线」
 *
 * files/raidian/charging/<yyyyMMdd_HHmmss>/      # 一次「充电会话」= 插电开始到拔电/充满
 *   meta.json     会话元数据(时长、起止电量、峰值/均值功率、充入能量)
 *   charge.csv    充电曲线:电量 / 电流 / 电压 / 功耗 / 温度 + 充放电状态
 * ```
 *
 * 为什么用 CSV 而不是 SQLite(对比 references/Cumulus-APP-main 的 BatteryDataSQLHelper):
 *  - 曲线是**顺序追加**的时序数据,不留历史版本、不做条件查询,CSV 更简单也能直接 pull 出来看;
 *  - Cumulus 用 `timestamp INTEGER PRIMARY KEY` + `CONFLICT_REPLACE`,同秒两条会互相覆盖
 *    (见 BatteryDataSQLHelper.kt:35-44、80-85),这里用毫秒时间戳 + 追加,天然无覆盖;
 *  - 每行 flush/append 即落盘,进程被杀只丢最后一行(与 SessionRecorder 的取舍一致)。
 */
class BatteryCurveStore(private val context: Context) {

    companion object {
        private const val TAG = "BatteryCurveStore"
        private const val DRAIN_HEADER =
            "ts,level,current_ma,voltage_mv,power_mw,battery_temp_c,charge_state,plugged," +
                "screen_on,time_on_battery_ms,screen_on_ms,computed_drain_mah,uid_total_mah"
        private const val APPS_HEADER = "ts,uid,pkg,label,mah"
        private const val CHARGE_HEADER =
            "ts,elapsed_ms,level,current_ma,voltage_mv,power_mw,battery_temp_c,status,plugged"
    }

    // ---------------- 模型 ----------------

    /** 放电曲线上的一个点(一分钟一点) */
    data class DrainPoint(
        val ts: Long,
        val level: Int,
        val currentMa: Int,
        val voltageMv: Int,
        val powerMw: Long,
        val tempC: Float,
        val chargeState: Int,
        val plugged: Int,
        val screenOn: Boolean,
        /** 系统口径:上次充满以来的 on-battery 时长 ms(-1 无数据) */
        val timeOnBatteryMs: Long,
        /** 系统口径:亮屏时长 ms(-1 无数据) */
        val screenOnMs: Long,
        /** 系统口径:本周期估算总耗电 mAh(-1 无数据) */
        val computedDrainMah: Double,
        /** 系统口径:逐 UID 耗电之和 mAh(-1 无数据) */
        val uidTotalMah: Double,
    )

    /** 某时刻某 uid 的累计耗电 mAh(长表一行) */
    data class AppPoint(
        val ts: Long,
        val uid: Int,
        val pkg: String,
        val label: String,
        val mah: Double,
    )

    /** 放电周期元数据 */
    data class CycleMeta(
        val startTs: Long,
        val endTs: Long = 0L,
        /** 周期起点电量(正常为 100;首次安装未经历充满时是当时的电量) */
        val startLevel: Int = -1,
        val endLevel: Int = -1,
        /** 系统识别容量 mAh */
        val capacityMah: Int = -1,
        val sampleCount: Int = 0,
        val appSampleCount: Int = 0,
        /** 是否由"充到 100%"触发的新周期(否则是首次安装时的兜底周期) */
        val fromFullCharge: Boolean = true,
    )

    /** 充电曲线上的一个点(30 秒一点) */
    data class ChargePoint(
        val ts: Long,
        val elapsedMs: Long,
        val level: Int,
        val currentMa: Int,
        val voltageMv: Int,
        val powerMw: Long,
        val tempC: Float,
        val status: Int,
        val plugged: Int,
    )

    /** 充电会话元数据 */
    data class ChargeMeta(
        val startTs: Long,
        val endTs: Long = 0L,
        val durationMs: Long = 0L,
        val startLevel: Int = -1,
        val endLevel: Int = -1,
        val sampleCount: Int = 0,
        val peakPowerMw: Long = -1L,
        /** 按时长加权的平均功率 mW */
        val avgPowerMw: Double = -1.0,
        /** 充入能量 mWh(功率梯形积分) */
        val energyMwh: Double = -1.0,
        val maxTempC: Float = -1f,
        val capacityMah: Int = -1,
        /** 结束时是否已充满 */
        val reachedFull: Boolean = false,
    ) {
        /** 充入电量百分比 */
        val gainedPercent: Int
            get() = if (startLevel >= 0 && endLevel >= 0) (endLevel - startLevel).coerceAtLeast(0) else -1

        /** 推算充入容量 mAh(需要系统容量) */
        val chargedMah: Int
            get() = if (capacityMah > 0 && gainedPercent > 0) capacityMah * gainedPercent / 100 else -1
    }

    // ---------------- 目录 ----------------

    private fun rootEndurance(): File = File(context.filesDir, "raidian/endurance")
    private fun rootCharging(): File = File(context.filesDir, "raidian/charging")

    private fun newDirName(ts: Long): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(ts))

    /** 建一个新周期目录并写好表头 */
    fun createCycle(startTs: Long): File {
        val dir = File(rootEndurance(), newDirName(startTs))
        dir.mkdirs()
        writeIfAbsent(File(dir, "drain.csv"), DRAIN_HEADER)
        writeIfAbsent(File(dir, "apps.csv"), APPS_HEADER)
        return dir
    }

    /** 建一个新充电会话目录并写好表头 */
    fun createCharge(startTs: Long): File {
        val dir = File(rootCharging(), newDirName(startTs))
        dir.mkdirs()
        writeIfAbsent(File(dir, "charge.csv"), CHARGE_HEADER)
        return dir
    }

    private fun writeIfAbsent(f: File, header: String) {
        try {
            if (!f.exists()) f.writeText(header + "\n", Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "writeIfAbsent failed: ${f.name}", e)
        }
    }

    /** 列出全部放电周期(新 → 旧) */
    fun listCycles(): List<File> = listDirs(rootEndurance())

    /** 列出全部充电会话(新 → 旧) */
    fun listCharges(): List<File> = listDirs(rootCharging())

    private fun listDirs(root: File): List<File> =
        (root.listFiles { f -> f.isDirectory } ?: emptyArray())
            .sortedByDescending { it.name }

    /** 递归删除若干目录,返回成功删除的个数 */
    fun deleteDirs(dirs: List<File>): Int {
        var n = 0
        dirs.forEach { d ->
            try {
                if (d.deleteRecursively()) n++
            } catch (e: Exception) {
                Log.w(TAG, "delete failed: ${d.name}", e)
            }
        }
        return n
    }

    /** 清理超过 [days] 天的周期/会话(默认 30 天;0 或负数表示不清理) */
    fun prune(days: Int): Int {
        if (days <= 0) return 0
        val cut = System.currentTimeMillis() - days * 86_400_000L
        val stale = (listCycles() + listCharges()).filter { dirTs(it) in 1 until cut }
        return deleteDirs(stale)
    }

    /** 从目录名 yyyyMMdd_HHmmss 反解时间戳;解析失败返回 -1 */
    fun dirTs(dir: File): Long = try {
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(dir.name)?.time ?: -1L
    } catch (e: Exception) {
        -1L
    }

    // ---------------- 写入 ----------------

    /** 追加一个放电点(单行 append,进程被杀最多丢最后一行) */
    fun appendDrain(dir: File, p: DrainPoint): Boolean = appendLine(
        File(dir, "drain.csv"),
        listOf(
            "${p.ts}", "${p.level}", "${p.currentMa}", "${p.voltageMv}", "${p.powerMw}",
            fmt1(p.tempC), "${p.chargeState}", "${p.plugged}",
            if (p.screenOn) "1" else "0",
            "${p.timeOnBatteryMs}", "${p.screenOnMs}", fmt2(p.computedDrainMah), fmt2(p.uidTotalMah),
        ).joinToString(",")
    )

    /** 追加一组每应用耗电快照 */
    fun appendApps(dir: File, points: List<AppPoint>): Boolean {
        if (points.isEmpty()) return false
        val sb = StringBuilder()
        points.forEach { a ->
            sb.append(a.ts).append(',').append(a.uid).append(',')
                .append(csvEsc(a.pkg)).append(',').append(csvEsc(a.label)).append(',')
                .append(fmt2(a.mah)).append('\n')
        }
        return appendRaw(File(dir, "apps.csv"), sb.toString())
    }

    /** 追加一个充电点 */
    fun appendCharge(dir: File, p: ChargePoint): Boolean = appendLine(
        File(dir, "charge.csv"),
        listOf(
            "${p.ts}", "${p.elapsedMs}", "${p.level}", "${p.currentMa}", "${p.voltageMv}",
            "${p.powerMw}", fmt1(p.tempC), "${p.status}", "${p.plugged}",
        ).joinToString(",")
    )

    private fun appendLine(f: File, line: String): Boolean = appendRaw(f, line + "\n")

    private fun appendRaw(f: File, text: String): Boolean = try {
        synchronized(lock) { f.appendText(text, Charsets.UTF_8) }
        true
    } catch (e: Exception) {
        Log.w(TAG, "append failed: ${f.name}", e)
        false
    }

    private val lock = Any()

    // ---------------- 读取 ----------------

    /** 读取放电曲线(drain.csv);文件不存在/损坏时返回空表 */
    fun readDrain(dir: File): List<DrainPoint> {
        val lines = readLines(File(dir, "drain.csv"))
        val out = ArrayList<DrainPoint>(lines.size)
        lines.forEach { r ->
            if (r.size < 13) return@forEach
            out.add(
                DrainPoint(
                    ts = r[0].toLongOrNull() ?: return@forEach,
                    level = r[1].toIntOrNull() ?: -1,
                    currentMa = r[2].toIntOrNull() ?: -1,
                    voltageMv = r[3].toIntOrNull() ?: -1,
                    powerMw = r[4].toLongOrNull() ?: -1L,
                    tempC = r[5].toFloatOrNull() ?: -1f,
                    chargeState = r[6].toIntOrNull() ?: -1,
                    plugged = r[7].toIntOrNull() ?: 0,
                    screenOn = r[8] == "1",
                    timeOnBatteryMs = r[9].toLongOrNull() ?: -1L,
                    screenOnMs = r[10].toLongOrNull() ?: -1L,
                    computedDrainMah = r[11].toDoubleOrNull() ?: -1.0,
                    uidTotalMah = r[12].toDoubleOrNull() ?: -1.0,
                )
            )
        }
        return out
    }

    /** 读取每应用耗电快照(apps.csv,长表) */
    fun readApps(dir: File): List<AppPoint> {
        val lines = readLines(File(dir, "apps.csv"))
        val out = ArrayList<AppPoint>(lines.size)
        lines.forEach { r ->
            if (r.size < 5) return@forEach
            out.add(
                AppPoint(
                    ts = r[0].toLongOrNull() ?: return@forEach,
                    uid = r[1].toIntOrNull() ?: return@forEach,
                    pkg = r[2],
                    label = r[3],
                    mah = r[4].toDoubleOrNull() ?: return@forEach,
                )
            )
        }
        return out
    }

    /** 读取充电曲线 */
    fun readCharge(dir: File): List<ChargePoint> {
        val lines = readLines(File(dir, "charge.csv"))
        val out = ArrayList<ChargePoint>(lines.size)
        lines.forEach { r ->
            if (r.size < 9) return@forEach
            out.add(
                ChargePoint(
                    ts = r[0].toLongOrNull() ?: return@forEach,
                    elapsedMs = r[1].toLongOrNull() ?: 0L,
                    level = r[2].toIntOrNull() ?: -1,
                    currentMa = r[3].toIntOrNull() ?: -1,
                    voltageMv = r[4].toIntOrNull() ?: -1,
                    powerMw = r[5].toLongOrNull() ?: -1L,
                    tempC = r[6].toFloatOrNull() ?: -1f,
                    status = r[7].toIntOrNull() ?: -1,
                    plugged = r[8].toIntOrNull() ?: 0,
                )
            )
        }
        return out
    }

    /** 读 CSV:去掉表头,做最小 CSV 解析(支持引号包裹的字段) */
    private fun readLines(f: File): List<List<String>> {
        if (!f.exists()) return emptyList()
        return try {
            f.readLines(Charsets.UTF_8)
                .drop(1)
                .filter { it.isNotBlank() }
                .map { splitCsv(it) }
        } catch (e: Exception) {
            Log.w(TAG, "read failed: ${f.name}", e)
            emptyList()
        }
    }

    private fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>(8)
        val sb = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuote && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++
                }
                c == '"' -> inQuote = !inQuote
                c == ',' && !inQuote -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    // ---------------- meta.json(手写序列化,沿用 SessionMeta 的做法) ----------------

    fun writeCycleMeta(dir: File, meta: CycleMeta) = writeText(
        File(dir, "meta.json"),
        "{" +
            "\"startTs\":${meta.startTs},\"endTs\":${meta.endTs}," +
            "\"startLevel\":${meta.startLevel},\"endLevel\":${meta.endLevel}," +
            "\"capacityMah\":${meta.capacityMah},\"sampleCount\":${meta.sampleCount}," +
            "\"appSampleCount\":${meta.appSampleCount}," +
            "\"fromFullCharge\":${meta.fromFullCharge}}"
    )

    fun writeChargeMeta(dir: File, meta: ChargeMeta) = writeText(
        File(dir, "meta.json"),
        "{" +
            "\"startTs\":${meta.startTs},\"endTs\":${meta.endTs}," +
            "\"durationMs\":${meta.durationMs}," +
            "\"startLevel\":${meta.startLevel},\"endLevel\":${meta.endLevel}," +
            "\"sampleCount\":${meta.sampleCount},\"peakPowerMw\":${meta.peakPowerMw}," +
            "\"avgPowerMw\":${meta.avgPowerMw},\"energyMwh\":${meta.energyMwh}," +
            "\"maxTempC\":${meta.maxTempC},\"capacityMah\":${meta.capacityMah}," +
            "\"reachedFull\":${meta.reachedFull}}"
    )

    private fun writeText(f: File, text: String) {
        try {
            f.writeText(text, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "write meta failed: ${f.name}", e)
        }
    }

    /** 读周期元数据;文件缺失/损坏返回 null */
    fun readCycleMeta(dir: File): CycleMeta? {
        val m = readJson(File(dir, "meta.json")) ?: return null
        val start = m["startTs"]?.toLongOrNull() ?: return null
        return CycleMeta(
            startTs = start,
            endTs = m["endTs"]?.toLongOrNull() ?: 0L,
            startLevel = m["startLevel"]?.toIntOrNull() ?: -1,
            endLevel = m["endLevel"]?.toIntOrNull() ?: -1,
            capacityMah = m["capacityMah"]?.toIntOrNull() ?: -1,
            sampleCount = m["sampleCount"]?.toIntOrNull() ?: 0,
            appSampleCount = m["appSampleCount"]?.toIntOrNull() ?: 0,
            fromFullCharge = m["fromFullCharge"]?.toBooleanStrictOrNull() ?: true,
        )
    }

    /** 读充电会话元数据 */
    fun readChargeMeta(dir: File): ChargeMeta? {
        val m = readJson(File(dir, "meta.json")) ?: return null
        val start = m["startTs"]?.toLongOrNull() ?: return null
        return ChargeMeta(
            startTs = start,
            endTs = m["endTs"]?.toLongOrNull() ?: 0L,
            durationMs = m["durationMs"]?.toLongOrNull() ?: 0L,
            startLevel = m["startLevel"]?.toIntOrNull() ?: -1,
            endLevel = m["endLevel"]?.toIntOrNull() ?: -1,
            sampleCount = m["sampleCount"]?.toIntOrNull() ?: 0,
            peakPowerMw = m["peakPowerMw"]?.toLongOrNull() ?: -1L,
            avgPowerMw = m["avgPowerMw"]?.toDoubleOrNull() ?: -1.0,
            energyMwh = m["energyMwh"]?.toDoubleOrNull() ?: -1.0,
            maxTempC = m["maxTempC"]?.toFloatOrNull() ?: -1f,
            capacityMah = m["capacityMah"]?.toIntOrNull() ?: -1,
            reachedFull = m["reachedFull"]?.toBooleanStrictOrNull() ?: false,
        )
    }

    /** 极简 JSON 对象解析(只支持本文件写出的"扁平 字符串/数字/布尔"结构) */
    private fun readJson(f: File): Map<String, String>? {
        if (!f.exists()) return null
        return try {
            val text = f.readText(Charsets.UTF_8)
            val out = HashMap<String, String>()
            Regex("\"(\\w+)\"\\s*:\\s*(\"[^\"]*\"|[^,}]+)").findAll(text).forEach { m ->
                val v = m.groupValues[2].trim().removeSurrounding("\"")
                out[m.groupValues[1]] = v
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "read json failed: ${f.name}", e)
            null
        }
    }

    // ---------------- 格式化辅助 ----------------

    private fun fmt1(v: Float): String = if (v < 0) "-1" else String.format(Locale.US, "%.1f", v)

    private fun fmt2(v: Double): String = if (v < 0) "-1" else String.format(Locale.US, "%.3f", v)

    private fun csvEsc(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""
}
