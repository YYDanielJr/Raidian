package yy.daniel.raidian.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 续航 / 充电曲线的展示格式化辅助(时长、功率、电量、时间)。
 *
 * 口径约定(与后端一致,见 battery 包):
 *  - 时长:`1天3小时` / `3小时12分` / `42分` / `38秒`;负数(无数据)显示 `--`
 *  - 功率:mW 为后端单位,展示时 ≥1000mW 转 W(与 Widgets.ChartRow 的规则一致)
 *  - 能量:mWh → Wh
 */

/** 粗粒度时长:天/小时/分/秒,最多两级 */
fun fmtDuration(ms: Long): String {
    if (ms < 0) return "--"
    val s = ms / 1000
    val d = s / 86400
    val h = (s % 86400) / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return when {
        d > 0 -> if (h > 0) "${d}天${h}小时" else "${d}天"
        h > 0 -> if (m > 0) "${h}小时${m}分" else "${h}小时"
        m > 0 -> "${m}分"
        else -> "${sec}秒"
    }
}

/** 精确到秒的时长(详情页用):`1天 03:12:17` */
fun fmtDurationFull(ms: Long): String {
    if (ms < 0) return "--"
    val s = ms / 1000
    val d = s / 86400
    val h = (s % 86400) / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    val hms = String.format(Locale.US, "%02d:%02d:%02d", h, m, sec)
    return if (d > 0) "${d}天 $hms" else hms
}

/** 功率(mW → 展示串) */
fun fmtPower(mw: Long): String = when {
    mw < 0 -> "--"
    mw >= 1000 -> String.format(Locale.US, "%.2f W", mw / 1000.0)
    else -> "$mw mW"
}

/** 功率(浮点 mW) */
fun fmtPower(mw: Double): String = if (mw < 0) "--" else fmtPower(mw.toLong())

/** 能量 mWh → Wh */
fun fmtEnergy(mwh: Double): String =
    if (mwh < 0) "--" else String.format(Locale.US, "%.2f Wh", mwh / 1000.0)

/** 电量 mAh */
fun fmtMah(mah: Double): String =
    if (mah < 0) "--" else String.format(Locale.US, "%.1f mAh", mah)

/** 百分比(可带符号) */
fun fmtPercent(v: Double, digits: Int = 1): String =
    if (v < 0) "--" else String.format(Locale.US, "%.${digits}f%%", v)

/** `09-06 22:55` */
fun fmtDateTime(ts: Long): String =
    if (ts <= 0) "--" else SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ts))

/** `22:55` */
fun fmtClock(ts: Long): String =
    if (ts <= 0) "--" else SimpleDateFormat("HH:mm", Locale.US).format(Date(ts))

/** `09-06 22:55:31`(详情页) */
fun fmtDateTimeSec(ts: Long): String =
    if (ts <= 0) "--" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(ts))

/** 相对周期起点的短标签(曲线 x 轴用):`3h` / `1d6h` */
fun fmtElapsedShort(ms: Long): String {
    if (ms < 0) return "--"
    val s = ms / 1000
    val d = s / 86400
    val h = (s % 86400) / 3600
    val m = (s % 3600) / 60
    return when {
        d > 0 -> if (h > 0) "${d}d${h}h" else "${d}d"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

/**
 * 曲线 x 轴标签:按时间戳列表生成 5 档"相对起点"标签。
 * 放电周期可能跨天,所以用 [fmtElapsedShort] 而不是 mm:ss(见 ChartTheme.timeAxisLabels 的差异)。
 */
fun relativeAxisLabels(tsList: List<Long>, startTs: Long): List<String>? {
    if (tsList.size < 2 || startTs <= 0) return null
    val ticks = 4
    return (0..ticks).map { t ->
        val idx = (tsList.size - 1) * t / ticks
        fmtElapsedShort((tsList.getOrNull(idx) ?: startTs) - startTs)
    }
}
