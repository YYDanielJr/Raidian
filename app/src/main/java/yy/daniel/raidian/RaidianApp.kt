package yy.daniel.raidian

import android.app.Application
import android.content.Context
import yy.daniel.raidian.monitor.AppStateReader
import yy.daniel.raidian.monitor.CommandExecutor
import yy.daniel.raidian.monitor.MonitorSampler
import yy.daniel.raidian.monitor.SessionRecorder
import yy.daniel.raidian.monitor.SessionStore
import yy.daniel.raidian.monitor.ShizukuShell
import yy.daniel.raidian.monitor.SurfaceFlingerFpsReader
import yy.daniel.raidian.overlay.OverlayController

/**
 * 全局依赖容器(简化版 DI,无注入框架,手写装配)。
 *
 * 持有跨组件共享的后端对象,统一在 [onCreate] 装配一次:
 *  - shizuku:Shizuku 提权通道
 *  - executor:命令执行器(优先 Shizuku,降级本地 sh)
 *  - sampler:采样器(CPU/GPU/温度/功耗/内存/网络/前台/FPS)
 *  - recorder:会话记录器(CSV + 统计)
 *  - sessionStore:会话读取端(历史列表/详情)
 *  - appState:前台应用识别 + FPS 状态
 *  - overlayController:悬浮窗控制器(Application 级常驻轮询,不随 Activity 销毁)
 *
 * 装配顺序有依赖:executor 需要 shizuku → sampler 需要 executor+appState →
 * appState 需要 fpsReader(经 Shizuku 读 SurfaceFlinger)→ recorder 需要 sampler。
 */
class RaidianApp : Application() {

    lateinit var shizuku: ShizukuShell
        private set
    lateinit var executor: CommandExecutor
        private set
    lateinit var sampler: MonitorSampler
        private set
    lateinit var recorder: SessionRecorder
        private set
    lateinit var sessionStore: SessionStore
        private set
    lateinit var appState: AppStateReader
        private set
    lateinit var overlayController: OverlayController
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 0) 设备/系统画像识别(采样层按此分派探测策略),先记录便于排查
        val profile = yy.daniel.raidian.monitor.DeviceProfile.detect()
        android.util.Log.i("RaidianEnv", "device profile: $profile")
        // 1) Shizuku 通道(注册监听并刷新授权状态)
        shizuku = ShizukuShell(this)
        shizuku.init()
        // 2) 命令执行器(注入 Shizuku 通道)
        executor = CommandExecutor().apply {
            shizuku = this@RaidianApp.shizuku
        }
        // 3) 前台应用识别(FPS 读取器随后装配)
        appState = AppStateReader(this, executor)
        // 4) 采样器
        sampler = MonitorSampler(this, executor, appState)
        // 5) 装配 FPS 读取器(经 Shizuku 读 SurfaceFlinger)
        appState.fpsReader = SurfaceFlingerFpsReader(executor, appState)
        // 6) 会话记录器 + 读取端
        recorder = SessionRecorder(this, sampler, appState)
        sessionStore = SessionStore(this)
        // 7) 悬浮窗控制器(Application 级,常驻)
        overlayController = OverlayController(this)
    }

    companion object {
        /** 全局单例(供 Service 等无 Context 场景取后端对象) */
        lateinit var instance: RaidianApp
            private set

        /** 从任意 Context 取得应用实例 */
        fun get(context: Context): RaidianApp =
            context.applicationContext as RaidianApp
    }
}
