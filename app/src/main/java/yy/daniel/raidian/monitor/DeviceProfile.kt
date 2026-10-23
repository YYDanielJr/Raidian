package yy.daniel.raidian.monitor

import android.os.Build

/**
 * 设备/系统画像:识别当前 ROM 家族与设备特征,供采集层按环境分发探测策略。
 *
 * 背景(Raidian 后端采集是针对小米 13 Ultra / HyperOS 逐节点实测校准的):
 *  - HyperOS / MIUI:thermal zone 用 cpu/ cpuss 前缀表示 CPU、soc/apc/aoss/tsens 归 SoC;
 *    CPU 簇为"每簇一张频率表"(骁龙 8 Gen 2 的 1+4+3)。
 *  - ColorOS / OPPO 系(OPPO/OnePlus/realme):thermal zone 命名不同(cpu-X-Y-Z 细粒度、
 *    nsph/ mdmss/socd 等),且骁龙 8 Gen 3 平板 CPU 是 4 簇、中核拆两个 bin,
 *    频率表聚类会把"大核"挤出前 3 簇(L/M/B 错位)—— 必须按 cpu_capacity 归簇。
 *
 * 本类只做**静态识别**,不读 sysfs(那是采样层按 [clusterProbe] 决定的职责)。
 * 判定优先级:厂商专属版本属性 > 品牌/制造商 > Build 版本号启发。
 */
class DeviceProfile private constructor(
    /** ROM 家族:HYPEROS(小米) / COLOROS(OPPO/OnePlus/realme) / AOSP(原生及未知) */
    val rom: RomFamily,
    /** 制造商(Build.MANUFACTURER,小写) */
    val manufacturer: String,
    /** 品牌(Build.BRAND,小写) */
    val brand: String,
    /** 型号(Build.MODEL) */
    val model: String,
    /** 厂商 ROM 版本属性原文(如 V16.0.0 / V816.0.0.0),未知为空 */
    val romVersion: String,
    /** CPU 簇探测方式:由 [rom] 与 SoC 代际共同决定,采样层据此聚类 */
    val clusterProbe: ClusterProbe,
    val socModel: String = safe(Build.SOC_MODEL),
    val socManufacturer: String = safe(Build.SOC_MANUFACTURER),
) {
    val isXring: Boolean get() = socManufacturer.contains("xring", true) ||
        (socModel.equals("O1", true) && isHyperOs)
    /** 是否 ColorOS/OPPO 系(含 OnePlus/realme) */
    val isColorOs: Boolean get() = rom == RomFamily.COLOROS
    /** 是否 HyperOS/MIUI 系 */
    val isHyperOs: Boolean get() = rom == RomFamily.HYPEROS

    enum class RomFamily(val label: String) {
        HYPEROS("HyperOS / MIUI"),
        COLOROS("ColorOS（OPPO 系）"),
        AOSP("Android 原生 / 未知"),
    }

    enum class ClusterProbe(val label: String) {
        /** 高通传统 3 簇(小米 13 Ultra 等):频率表聚类即可,每簇一张表 */
        FREQ_TABLE("频率表聚类（按频率分档）"),
        /** 骁龙 8 Gen 3 等 4 簇 / 中核双 bin:必须按 cpu_capacity 归并后取 L/M/B */
        CAPACITY_8GEN3("cpu_capacity 聚类（4 簇双 bin）"),
        /** 未知:频率表聚类,但不强依赖前 3 簇顺序(旧行为) */
        UNKNOWN("频率表聚类（未知环境）"),
    }

    override fun toString(): String =
        "DeviceProfile(rom=$rom, mfr=$manufacturer, brand=$brand, model=$model, romVersion=$romVersion, cluster=$clusterProbe)"

    companion object {
        // ---- ROM 专属版本属性(经 SystemProperties,只读) ----
        private val MIUI_PROPS = listOf("ro.miui.ui.version.name", "ro.miui.ui.version.code", "ro.build.version.miui")
        private val OPLUS_PROPS = listOf("ro.build.version.oplusrom", "ro.build.version.oplusromname", "ro.oplus.version")

        /** 缓存:同一进程只探测一次(属性与 sysfs 拓扑都不变) */
        @Volatile private var cached: DeviceProfile? = null

        /**
         * 探测当前设备画像(进程内只跑一次,之后复用缓存)。
         * 全程 try 包裹,任何一步失败都降级到 AOSP/UNKNOWN,绝不让识别本身崩溃。
         */
        fun detect(): DeviceProfile {
            cached?.let { return it }
            val p = try {
                detectImpl()
            } catch (e: Exception) {
                // 识别失败:按 AOSP + 频率表聚类兜底,不抛
                DeviceProfile(RomFamily.AOSP, safe(Build.MANUFACTURER), safe(Build.BRAND),
                    safe(Build.MODEL), "", ClusterProbe.UNKNOWN)
            }
            cached = p
            return p
        }

        private fun detectImpl(): DeviceProfile {
            val mfr = safe(Build.MANUFACTURER).lowercase()
            val brand = safe(Build.BRAND).lowercase()
            val model = safe(Build.MODEL)

            // 1) 厂商专属版本属性:最可靠(OPPO 系即使品牌写 OnePlus 也有 oplusrom)
            val oplus = OPLUS_PROPS.mapNotNull { sysProp(it) }.firstOrNull { it.isNotBlank() }
            val miui = MIUI_PROPS.mapNotNull { sysProp(it) }.firstOrNull { it.isNotBlank() }

            val rom = when {
                oplus != null -> RomFamily.COLOROS
                miui != null -> RomFamily.HYPEROS
                mfr.contains("xiaomi") || brand.contains("xiaomi") ||
                    mfr.contains("redmi") || brand.contains("redmi") -> RomFamily.HYPEROS
                mfr.contains("oppo") || brand.contains("oppo") ||
                    mfr.contains("oneplus") || brand.contains("oneplus") ||
                    mfr.contains("realme") || brand.contains("realme") ||
                    brand.contains("oplus") -> RomFamily.COLOROS
                else -> RomFamily.AOSP
            }

            // 2) CPU 簇探测方式:ColorOS 8 Gen 3(及更细拓扑)按 capacity;
            //    骁龙 8 Gen 3 = SM8650 = "sun"(内核代号) / 平台名 "pineapple"
            val socName = sysProp("ro.soc.model")?.lowercase().orEmpty() +
                sysProp("ro.board.platform")?.lowercase().orEmpty() +
                sysProp("ro.hardware")?.lowercase().orEmpty()
            val clusterProbe = when {
                // OPPO 平板(一加平板 Pro SM8650,实测 4 簇中核双 bin)
                rom == RomFamily.COLOROS && (socName.contains("8650") || socName.contains("pineapple")) ->
                    ClusterProbe.CAPACITY_8GEN3
                // 兜底:未知环境仍用频率表(旧行为,小米 13 Ultra 正确)
                else -> if (rom == RomFamily.AOSP) ClusterProbe.UNKNOWN else ClusterProbe.FREQ_TABLE
            }
            return DeviceProfile(rom, mfr, brand, model, oplus ?: miui.orEmpty(), clusterProbe)
        }

        /** 安全读系统属性;失败返回 null(非关键路径不抛) */
        private fun sysProp(name: String): String? = try {
            val clz = Class.forName("android.os.SystemProperties")
            val get = clz.getMethod("get", String::class.java)
            get.invoke(null, name) as? String
        } catch (e: Exception) {
            null
        }

        private fun safe(v: String?): String = v?.trim().orEmpty()
    }
}
