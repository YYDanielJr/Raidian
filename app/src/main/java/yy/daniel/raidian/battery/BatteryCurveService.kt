package yy.daniel.raidian.battery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import yy.daniel.raidian.MainActivity
import yy.daniel.raidian.RaidianApp

/**
 * 续航 / 充电曲线记录的前台服务。
 *
 * 为什么需要它:一轮放电可能持续十几小时,期间用户会切后台、灭屏,普通后台进程随时可能被
 * HyperOS 回收 —— 进程一死,记录循环就停,曲线出现空洞。这里用一条 IMPORTANCE_LOW 的
 * 常驻通知把进程钉住(用户可在通知里一键停止记录)。
 *
 * 记录逻辑本身仍在 Application 级 [BatteryCurveController] 里(与悬浮窗同一套架构),
 * 本服务只负责"保活 + 通知",不做任何采集 —— 这样 Activity/服务来回销毁都不会中断记录。
 */
class BatteryCurveService : Service() {

    companion object {
        private const val TAG = "BatteryCurveSvc"
        private const val CHANNEL_ID = "raidian_battery_curve"
        private const val NOTIFICATION_ID = 2

        /** 启动(幂等;Android 8+ 必须走 startForegroundService) */
        fun start(context: Context) {
            val i = Intent(context, BatteryCurveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        /** 停止(用户关闭记录开关时调用) */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, BatteryCurveService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "stop failed", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 被系统回收后重启:确保记录循环还在跑
        try {
            RaidianApp.instance.batteryCurves.start()
        } catch (e: Exception) {
            Log.w(TAG, "restart controller failed", e)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // 服务被停(用户关开关 / 系统回收)时把内存里的曲线写盘,不做别的清理
        try {
            RaidianApp.instance.batteryCurves.endurance.flush()
        } catch (e: Exception) {
            Log.w(TAG, "flush on destroy failed", e)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID, "续航与充电记录", NotificationManager.IMPORTANCE_LOW
            )
            ch.description = "持续记录续航曲线与充电曲线"
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        b.setContentTitle("Raidian 正在记录续航")
            .setContentText("持续记录放电曲线与充电曲线，点击打开")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .setContentIntent(pi)
        return b.build()
    }
}
