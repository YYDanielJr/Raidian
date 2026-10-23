package yy.daniel.raidian.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager

/**
 * 电池瞬时状态读取器(续航曲线 / 充电曲线的公共数据源)。
 *
 * 为什么不用 shell:这两条曲线要**长期后台**采样(充电中 30s 一点、放电 60s 一点),
 * 而 `dumpsys battery` 每次都是一次 binder + fork,长期跑开销不划算。这里改用
 * 应用进程内可读的两条零成本通道(对照 references/Cumulus-APP-main 的
 * BackgroundService.kt:114-128 与 objects/BatteryStatsProvider.kt:99-118,两者同样只用
 * ACTION_BATTERY_CHANGED + BatteryManager 属性):
 *
 *  - `ACTION_BATTERY_CHANGED` 粘性广播(registerReceiver(null, filter) 直接取返回值):
 *    电量 / 充电状态 / 插头类型 / 电压(mV) / 温度(0.1°C);
 *  - `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW`:电流,上游单位 µA(小米实测),
 *    换算规则与本项目 MonitorSampler.readBattery 完全一致(见那里的单位换算教训:
 *    曾把 µA 当 mA 算出 4000W 功耗)。
 *
 * 单位约定:电压 mV、电流 mA(带符号,**负 = 正在充电**,这是 Android 惯例)、
 * 功率 mW(= |mA| × mV / 1000)、温度 °C。
 */
class BatteryStateReader(private val context: Context) {

    /**
     * 一次电池状态快照。
     *
     * @param ts 读取时刻(epoch ms)
     * @param level 电量 0-100;读不到 -1
     * @param status [BatteryManager.BATTERY_STATUS_*](2 充电 / 3 放电 / 4 未充电 / 5 已充满)
     * @param plugged [BatteryManager.BATTERY_PLUGGED_*] 位掩码(0 未插 / 1 AC / 2 USB / 4 无线)
     * @param currentMa 电流 mA;负 = 充入电池,正 = 由电池放出;读不到 -1
     * @param powerMw 功率 mW(|电流| × 电压 / 1000);读不到 -1
     * @param chargeCounterMah 库仑计读数 mAh(CHARGE_COUNTER,µAh ÷ 1000);读不到 -1
     * @param screenOn 屏幕是否点亮(PowerManager.isInteractive)
     */
    data class State(
        val ts: Long,
        val level: Int,
        val status: Int,
        val plugged: Int,
        val voltageMv: Int,
        val tempC: Float,
        val currentMa: Int,
        val powerMw: Long,
        val chargeCounterMah: Int,
        val screenOn: Boolean,
    ) {
        /** 是否插着充电器(AC / USB / 无线任一) */
        val pluggedIn: Boolean get() = plugged != 0

        /** 是否正在充电(系统状态机口径,不含"已充满") */
        val charging: Boolean get() = status == BatteryManager.BATTERY_STATUS_CHARGING

        /** 是否已充满 */
        val full: Boolean get() = status == BatteryManager.BATTERY_STATUS_FULL

        /** 是否处于放电(未充电)—— 续航曲线只在这种区间累计 */
        val discharging: Boolean
            get() = status == BatteryManager.BATTERY_STATUS_DISCHARGING ||
                status == BatteryManager.BATTERY_STATUS_NOT_CHARGING

        /** 对外展示用的充电状态文本 */
        val chargeStateText: String
            get() = when {
                full -> "已充满"
                charging -> when (plugged) {
                    BatteryManager.BATTERY_PLUGGED_AC -> "交流充电"
                    BatteryManager.BATTERY_PLUGGED_USB -> "USB 充电"
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线充电"
                    else -> "充电中"
                }
                pluggedIn -> "已插电(未充)"
                else -> "放电中"
            }
    }

    /** 读取一次快照。任何一步失败都退化为 -1/默认值,不抛异常(长期后台循环不能被单次失败打断) */
    fun read(now: Long = System.currentTimeMillis()): State {
        var level = -1
        var status = BatteryManager.BATTERY_STATUS_UNKNOWN
        var plugged = 0
        var voltageMv = -1
        var tempC = -1f
        try {
            val i: Intent? = context.registerReceiver(
                null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            if (i != null) {
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                val raw = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                if (raw >= 0) level = raw * 100 / scale
                status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                val v = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                // 部分 OEM 上报"伏"而非"毫伏"(Cumulus 同款容错,BackgroundService.kt:116)
                voltageMv = if (v in 1..999) v * 1000 else v
                val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                if (t != Int.MIN_VALUE) tempC = t / 10f  // 上游 0.1°C
            }
        } catch (e: Exception) {
            // 粘性广播取不到(极少见):保持 -1,由下一轮重试
        }

        var currentMa = -1
        var currentValid = false
        var counterMah = -1
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val raw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (raw != Int.MIN_VALUE) {
                currentValid = true
                // µA → mA(小米实测上游为 µA);阈值判定与本项目 MonitorSampler 一致
                val abs = kotlin.math.abs(raw.toLong())
                val ma = abs / 1000
                currentMa = (if (raw < 0) -ma else ma).toInt()
            }
            val cc = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            if (cc != Int.MIN_VALUE) counterMah = cc / 1000  // µAh → mAh
        } catch (e: Exception) {
            // 属性不支持:保持 -1
        }

        val absMa = kotlin.math.abs(currentMa)
        val powerMw = if (currentValid && voltageMv > 0) absMa.toLong() * voltageMv / 1000 else -1L

        var screenOn = false
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            screenOn = pm.isInteractive
        } catch (e: Exception) {
            // 读不到就按灭屏处理(只在无 Shizuku 时影响本地兜底估算)
        }

        return State(
            ts = now, level = level, status = status, plugged = plugged,
            voltageMv = voltageMv, tempC = tempC, currentMa = currentMa,
            powerMw = powerMw, chargeCounterMah = counterMah, screenOn = screenOn,
        )
    }
}
