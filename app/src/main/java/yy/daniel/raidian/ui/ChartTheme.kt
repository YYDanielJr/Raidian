package yy.daniel.raidian.ui

import androidx.compose.ui.graphics.Color

/**
 * 统计图的 Material 色板与格式化工具。
 *
 * 曲线颜色统一取自 Material Design 3 调色板(带 * 的为 M3 核心色,明暗按
 * 浅色界面选取 500/600/700 区间保证在白底可读):
 *  - 帧率/绿系:M3 Green(生命/正常)
 *  - 功耗/蓝系:M3 Blue(电量/数据)
 *  - 电池温度/橙红系:M3 Orange -> Red(温度偏高警示)
 *  - 核心温度/紫系:M3 Purple
 *  - GPU/深紫系:M3 DeepPurple(区别于 CPU 温度紫)
 *  - 网络/青系:M3 Teal
 *
 * 每核频率多线图在同色系内取明暗梯度,保持"同簇同色系"的同时满足 Material
 * 观感(M3 的 Blue/Green/Orange 各档位)。
 */
object ChartColors {
    // ---- 单指标主色(Material3 500/600 档) ----
    /** 帧率 FPS:绿(M3 Green 600 #43A047 系) */
    val Fps = Color(0xFF43A047)
    /** 功耗:蓝(M3 Blue 600 #1E88E5 系) */
    val Power = Color(0xFF1E88E5)
    /** 电池温度:橙(M3 Orange 500 #FB8C00 系) */
    val BatteryTemp = Color(0xFFFB8C00)
    /** 核心温度(SoC):紫(M3 Purple 500 #AB47BC 系) */
    val CpuTemp = Color(0xFFAB47BC)
    /** GPU 占用:深紫(M3 DeepPurple 500 #673AB7 系) */
    val Gpu = Color(0xFF673AB7)
    /** GPU 温度(将来用):靛(M3 Indigo) */
    val GpuTemp = Color(0xFF3949AB)
    /** 网络:青(M3 Teal 600 #00897B 系) */
    val Network = Color(0xFF00897B)
    /** 内存:蓝灰(M3 BlueGrey 500 #607D8B 系) */
    val Memory = Color(0xFF607D8B)
    /** 电量曲线(续航页):深绿(M3 Green 800),与帧率绿区分 */
    val BatteryLevel = Color(0xFF2E7D32)
    /** 充电功率/充电曲线:琥珀(M3 Amber 800) */
    val Charge = Color(0xFFF9A825)
    /** 单应用耗电(应用功耗详情):青绿(M3 Teal 600) */
    val AppPower = Color(0xFF00897B)

    // ---- 每核频率:簇内同色系明暗梯度(M3 200/300/400/500/600/700/800 档) ----
    // 小核(0-2)绿系
    val CoreSmall = listOf(
        Color(0xFF66BB6A), Color(0xFF4CAF50), Color(0xFF43A047),
    )
    // 中核(3-6)蓝系(至多 4 个)
    val CoreMid = listOf(
        Color(0xFF64B5F6), Color(0xFF42A5F5), Color(0xFF1E88E5), Color(0xFF1976D2),
    )
    // 大核(7)橙系
    val CoreBig = listOf(
        Color(0xFFFFB74D), Color(0xFFFFA726), Color(0xFFFB8C00), Color(0xFFF57C00),
    )

    /**
     * 生成 8 核(或任意核数)调色板:按已知拓扑 3 簇给小/中/大核分配色系;
     * 若核数超过 3+4+1 余量则回退到 M3 彩虹渐次(每核不同色)。
     */
    fun corePalette(coreCount: Int): List<Color> {
        if (coreCount <= 0) return emptyList()
        // 玄戒 O1 的四簇：0–1、2–3、4–7、8–9。
        if (coreCount == 10) return CoreSmall.take(2) +
            listOf(Color(0xFF26A69A), Color(0xFF00897B)) + CoreMid + CoreBig.take(2)
        val fallback = listOf(
            Color(0xFF66BB6A), Color(0xFF4CAF50), Color(0xFF42A5F5), Color(0xFF1E88E5),
            Color(0xFFFBC02D), Color(0xFFFB8C00), Color(0xFFF4511E), Color(0xFF8E24AA),
        )
        if (coreCount > CoreSmall.size + CoreMid.size + CoreBig.size) {
            return List(coreCount) { fallback[it % fallback.size] }
        }
        val out = ArrayList<Color>(coreCount)
        // 小核簇:第 0 核开始,小核数 = 按频率聚类结果;这里按常见 1+4+3/3+4+1 分布简化:
        // 从低到高:前 25% 小核、中间中核、最后大核 —— 但本项目 8 核固定 3 小 4 中 1 大
        // (cpu0-2 小 / cpu3-6 中 / cpu7 大,实机拓扑),直接按 3/4/1 拼
        out.addAll(CoreSmall.take(coreCount.coerceAtMost(3)))
        var left = coreCount - out.size
        if (left > 0) {
            out.addAll(CoreMid.take(left.coerceAtMost(4)))
            left = coreCount - out.size
        }
        if (left > 0) out.addAll(CoreBig.take(left))
        return out
    }
}

/**
 * 坐标轴/网格用色(浅色界面):极淡灰网格 + 中灰刻度文字。
 */
object ChartAxisDefaults {
    val Grid = Color(0x14000000)
    val AxisLine = Color(0x26000000)
    val Label = Color(0x99000000) // 60% 黑,弱于正文
    val TickLen = 3f
    /** y 轴刻度区宽度(dp) */
    const val YAxisWidthDp = 40f
    /** x 轴刻度区高度(dp) */
    const val XAxisHeightDp = 18f
}

/** 刻度取整辅助:把 [v] 格式化为适合坐标轴的短数字(整数/一位小数) */
fun axisNum(v: Float): String =
    if (kotlin.math.abs(v - v.toInt()) < 0.05f || kotlin.math.abs(v) >= 100f)
        v.toInt().toString()
    else String.format("%.1f", v)

/**
 * 从采样 elapsed 序列(ms)生成 x 轴时间标签(5 档:首/1/4/2/3/4/尾,mm:ss)。
 * 供会话详情页各曲线复用同一时间轴。数据为空返回 null(不画底标文字)。
 */
fun timeAxisLabels(elapsedMs: List<Long>): List<String>? {
    if (elapsedMs.isEmpty()) return null
    val n = elapsedMs.size
    val ticks = 4
    val out = ArrayList<String>(ticks + 1)
    for (t in 0..ticks) {
        val idx = (n - 1) * t / ticks
        val e = elapsedMs.getOrNull(idx) ?: 0L
        val sec = e / 1000
        out.add(String.format("%d:%02d", sec / 60, sec % 60))
    }
    return out
}
