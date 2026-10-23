package yy.daniel.raidian.battery

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * 系统电池统计(`dumpsys batterystats`)解析。
 *
 * 这是"续航曲线"最核心的数据源,原因:
 *  - `Time on battery:` / `Screen on:` 就是**系统口径**的"上次充满电之后的总续航时长 /
 *    亮屏时长"(batterystats 的当前统计在**充满电/拔电**时自动重置,正好等于用户要的
 *    "从上次充到 100% 之后"),不需要我们自己推算;
 *  - `Estimated power use (mAh)` 段给出**每个 UID 的估算耗电 mAh**(系统按功耗模型算的),
 *    这是每应用耗电量与每应用功耗曲线的来源。
 *
 * 关于参考实现:references/Cumulus-APP-main **没有**用这个数据源(它的续航/亮屏是用
 * 2 秒采样点事后推算、且完全没有每 UID mAh,见 utils/BatteryStatsRecordAnalysis.kt:
 * 105-133、289-307),所以这一层是按 AOSP `BatteryStatsService.dump` 的**实机输出**
 * 写的,小米 13 Ultra(Android 16)与小米 17 Pro Max(Android 17)输出格式一致,已在实机核对:
 *
 * ```
 *   Estimated battery capacity: 5000 mAh
 *   Time on battery: 1d 3h 12m 17s 13ms (99.6%) realtime, 42m 38s 504ms (2.6%) uptime
 *   Time on battery screen off: 1d 3h 11m 44s 645ms (100.0%) realtime, ...
 *   Screen on: 32s 368ms (0.0%) 2x, Interactive: 18s 744ms (0.0%)
 *     Capacity: 5000, Computed drain: 0.975, actual drain: 0.975
 *   UID u0a292: 9.85 fg: 0.0156 (2s 23ms) bg: 9.81 (532ms) cached: ...
 *   UID 1000: 70.6 bg: 70.6
 * ```
 *
 * 全量 `dumpsys batterystats` 有 1.6 MB(实测小米 13 Ultra),每 5 分钟拉一次不可接受,
 * 因此命令里先用设备上的 grep 只留需要的几行(实测 0.66s / 几十行)。
 */
object BatteryStatsParser {

    private const val TAG = "BatteryStats"

    /**
     * 取数命令:只保留 4 类行 —— 顶部容量/时长行、Screen on 行、Estimated power 段的
     * `Capacity:` 行、逐 UID 行。`2>/dev/null` 掩盖个别 ROM 的权限提示。
     */
    const val CMD: String =
        "dumpsys batterystats 2>/dev/null | grep -E " +
            "'^  (Time on battery|Screen on:|Estimated battery capacity:|UID )|^    Capacity:'"

    /** 一个 UID 的估算耗电 */
    data class UidPower(
        /** 十进制 uid(u0a292 → 10292) */
        val uid: Int,
        /** 原始 token(u0a292 / 1000),查不到包名时用于展示 */
        val token: String,
        /** 本统计周期(≈上次充满)内的估算耗电 mAh */
        val mah: Double,
    )

    /** 一次系统电池统计快照 */
    data class SystemBatteryStats(
        /** 读取时刻(epoch ms) */
        val at: Long,
        /** 上次充满(统计重置)以来的 on-battery 时长 ms */
        val timeOnBatteryMs: Long,
        /** 其中灭屏时长 ms */
        val screenOffMs: Long,
        /** 其中 doze 时长 ms */
        val dozeMs: Long,
        /** 亮屏时长 ms(= on-battery − 灭屏,系统另给的 Screen on 行优先) */
        val screenOnMs: Long,
        /** 系统识别到的电池容量 mAh */
        val capacityMah: Int,
        /** 本周期估算总耗电 mAh */
        val computedDrainMah: Double,
        /** 逐 UID 估算耗电(已按 mAh 降序) */
        val uids: List<UidPower>,
    ) {
        /** 全部 UID 之和 mAh(用于算占比;系统另有 system/其它开销,故通常小于 computedDrain) */
        val uidTotalMah: Double get() = uids.sumOf { it.mah }
    }

    /** 时长 token:13ms / 17s / 12m / 3h / 1d */
    private val DUR_TOKEN = Regex("^(\\d+)(ms|d|h|m|s)$")

    /** UID 行:`UID u0a292: 9.85 ...` / `UID 1000: 70.6 ...` */
    private val UID_LINE = Regex("^UID\\s+(\\S+?):\\s*([\\d.]+)")

    /** 应用 uid token:`u<user>a<app>` / `u<user>i<isolated>` / `u<user>s<shared>` */
    private val UID_APP = Regex("^u(\\d+)([ais])(\\d+)$")

    /** 解析 "1d 3h 12m 17s 13ms" 这类时长;括号后的百分比/uptime 部分一律不算 */
    fun parseDurationMs(text: String): Long {
        val head = text.substringBefore('(').trim()
        if (head.isEmpty()) return -1L
        var ms = 0L
        var hit = false
        for (tok in head.split(Regex("\\s+"))) {
            val m = DUR_TOKEN.matchEntire(tok) ?: continue
            val v = m.groupValues[1].toLongOrNull() ?: continue
            hit = true
            ms += when (m.groupValues[2]) {
                "d" -> v * 86_400_000L
                "h" -> v * 3_600_000L
                "m" -> v * 60_000L
                "s" -> v * 1_000L
                else -> v
            }
        }
        return if (hit) ms else -1L
    }

    /**
     * uid token → 十进制 uid。
     * AOSP 规则:uid = userId × 100000 + (应用 10000 + appId | 隔离 99000 + id | 共享 1000 + id);
     * 纯数字(1000 / 1002 / 0)直接取值。
     */
    fun uidOf(token: String): Int {
        val m = UID_APP.matchEntire(token)
        if (m != null) {
            val user = m.groupValues[1].toIntOrNull() ?: return -1
            val id = m.groupValues[3].toIntOrNull() ?: return -1
            val base = when (m.groupValues[2]) {
                "a" -> 10_000 + id
                "i" -> 99_000 + id
                else -> 1_000 + id
            }
            return user * 100_000 + base
        }
        return token.toIntOrNull() ?: -1
    }

    /** 解析 [CMD] 的输出;完全没有可用行时返回 null(调用方保留上一次结果) */
    fun parse(out: String, at: Long): SystemBatteryStats? {
        var timeOnBattery = -1L
        var screenOff = -1L
        var doze = -1L
        var screenOn = -1L
        var capacity = -1
        var drain = -1.0
        val uids = ArrayList<UidPower>()
        for (raw in out.lines()) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            when {
                t.startsWith("Time on battery screen off:") ->
                    screenOff = parseDurationMs(t.substringAfter(':'))
                t.startsWith("Time on battery screen doze:") ->
                    doze = parseDurationMs(t.substringAfter(':'))
                t.startsWith("Time on battery:") ->
                    timeOnBattery = parseDurationMs(t.substringAfter(':'))
                t.startsWith("Screen on:") ->
                    screenOn = parseDurationMs(t.substringAfter(':'))
                t.startsWith("Estimated battery capacity:") ->
                    capacity = t.substringAfter(':').trim().substringBefore(' ')
                        .toIntOrNull() ?: -1
                t.startsWith("Capacity:") ->
                    drain = Regex("Computed drain:\\s*([\\d.]+)").find(t)
                        ?.groupValues?.get(1)?.toDoubleOrNull() ?: -1.0
                t.startsWith("UID ") -> {
                    val m = UID_LINE.find(t) ?: continue
                    val uid = uidOf(m.groupValues[1])
                    val mah = m.groupValues[2].toDoubleOrNull() ?: continue
                    if (uid >= 0 && mah > 0) uids.add(UidPower(uid, m.groupValues[1], mah))
                }
            }
        }
        if (timeOnBattery < 0 && screenOn < 0 && uids.isEmpty()) return null
        // 亮屏时长:优先用系统 Screen on 行;取不到时用 on-battery − 灭屏兜底
        val screenOnFinal = when {
            screenOn >= 0 -> screenOn
            timeOnBattery >= 0 && screenOff >= 0 -> (timeOnBattery - screenOff).coerceAtLeast(0)
            else -> -1L
        }
        Log.i(TAG, "system stats: onBattery=${timeOnBattery}ms screenOn=${screenOnFinal}ms " +
            "cap=$capacity drain=$drain uids=${uids.size}")
        return SystemBatteryStats(
            at = at,
            timeOnBatteryMs = timeOnBattery,
            screenOffMs = screenOff,
            dozeMs = doze,
            screenOnMs = screenOnFinal,
            capacityMah = capacity,
            computedDrainMah = drain,
            uids = uids.sortedByDescending { it.mah },
        )
    }
}

/**
 * uid → 应用信息(包名 / 应用名)解析,带内存缓存。
 *
 * 系统统计里只有 uid,展示需要包名与应用名(每应用耗电列表、图标)。
 * `getPackagesForUid` 属于公开 API,配合 manifest 已声明的 QUERY_ALL_PACKAGES 可列全。
 */
class UidInfoResolver(private val context: Context) {

    /** 一个 uid 对应的展示信息 */
    data class Info(val uid: Int, val pkg: String, val label: String, val isSystem: Boolean)

    private val cache = HashMap<Int, Info>()

    /** 常见系统 uid 的名称(无包名可查时用;AOSP 固定分配) */
    private val systemNames: Map<Int, String> = mapOf(
        0 to "内核(root)",
        1000 to "Android 系统",
        1001 to "电话 / 无线电",
        1002 to "蓝牙",
        1010 to "WiFi",
        1013 to "媒体服务",
        1016 to "网络管理",
        1019 to "NFC",
        1021 to "GPS",
        1027 to "媒体扫描",
        1041 to "音频服务",
        1068 to "媒体解码",
        1069 to "DRM",
        1073 to "输入法服务",
        2000 to "ADB / Shell",
    )

    /** 解析(带缓存)。取不到包名时返回系统进程名或 "UID <n>" */
    @Synchronized
    fun resolve(uid: Int): Info {
        cache[uid]?.let { return it }
        val pm = context.packageManager
        var pkg = ""
        var label = ""
        try {
            val names = pm.getPackagesForUid(uid)
            if (!names.isNullOrEmpty()) {
                pkg = names[0]
                label = try {
                    pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
                } catch (e: Exception) {
                    pkg
                }
            }
        } catch (e: Exception) {
            // 忽略:走下面的兜底名称
        }
        val isSystem = pkg.isEmpty()
        val finalLabel = when {
            label.isNotBlank() -> label
            systemNames.containsKey(uid) -> systemNames.getValue(uid)
            uid in 10_000..19_999 -> "系统进程 ${uid - 10_000}"
            else -> "UID $uid"
        }
        val info = Info(uid, pkg, finalLabel, isSystem)
        cache[uid] = info
        return info
    }

    /** 包名 → 应用名(每应用功耗详情页用;非包名(系统 uid)原样返回) */
    @Synchronized
    fun labelOfPkg(pkg: String): String {
        if (pkg.isBlank()) return ""
        return try {
            val pm: PackageManager = context.packageManager
            pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
        } catch (e: Exception) {
            pkg
        }
    }
}
