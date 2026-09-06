package yy.daniel.raidian.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import yy.daniel.raidian.RaidianApp

/**
 * 前台监控服务。承载:
 *  - 会话记录 [SessionRecorder](后台记录的主载体)
 *  - 悬浮窗(由 Application 级 [OverlayController] 管理,本服务可作为常驻载体
 *    保证应用被切后台后进程不被回收,后台记录/悬浮窗持续运行)
 *
 * 作为 specialUse 前台服务(见 AndroidManifest 的 foregroundServiceType),
 * 提供常驻通知,用户可从通知/系统界面手动停止。
 *
 * 注意(阶段二现状):当前主界面与 OverlayController 用 Application 级常驻
 * (进程存活即工作),本服务尚未被实际 start;若要让"后台记录"在进程被杀后仍
 * 可靠,需接入本服务 + START_STICKY(见 AGENTS.md 待办 #10)。
 */
class MonitorService : Service() {
    companion object {
        private const val TAG = "MonitorService"
        private const val CHANNEL_ID = "raidian_monitor"
        private const val NOTIFICATION_ID = 1

        /** 启动前台服务(O 及以上必须走 startForegroundService) */
        fun start(context: Context) {
            val i = Intent(context, MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        /** 停止前台服务 */
        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }

    /** 非绑定服务,不提供 binder */
    override fun onBind(intent: Intent?): IBinder? = null

    /** 创建通知渠道并把自己提升为前台(必须在 startForegroundService 后尽快调用) */
    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 由宿主(RaidianApp/主界面)持有全局 recorder 单例并 start/stop
        return START_STICKY
    }

    /** 服务被系统/用户停止时,若正在记录则停止记录,避免留下"半截"会话 */
    override fun onDestroy() {
        super.onDestroy()
        try {
            val app = RaidianApp.instance
            if (app.recorder.isRunning()) app.recorder.stop()
        } catch (e: Exception) {
            Log.w(TAG, "onDestroy cleanup failed", e)
        }
    }

    /** 创建低优先级通知渠道(IMPORTANCE_LOW:不响铃不打扰) */
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID, "性能监控", NotificationManager.IMPORTANCE_LOW
            )
            ch.description = "后台性能记录与悬浮窗"
            nm.createNotificationChannel(ch)
        }
    }

    /** 构造常驻通知:标题 + 点击打开主界面 */
    private fun buildNotification(): Notification {
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        b.setContentTitle("Raidian 正在监控")
            .setContentText("点击打开主界面")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(
                // 点击通知 → 打开主界面
                android.app.PendingIntent.getActivity(
                    this, 0,
                    Intent(this, yy.daniel.raidian.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
        return b.build()
    }
}
