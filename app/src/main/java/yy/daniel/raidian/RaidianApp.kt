package yy.daniel.raidian

import android.app.Application
import android.content.Context
import yy.daniel.raidian.monitor.AppStateReader
import yy.daniel.raidian.monitor.CommandExecutor
import yy.daniel.raidian.monitor.MonitorSampler
import yy.daniel.raidian.monitor.SessionRecorder
import yy.daniel.raidian.monitor.SessionStore
import yy.daniel.raidian.privilege.PrivilegeManager
import yy.daniel.raidian.monitor.SurfaceFlingerFpsReader
import yy.daniel.raidian.overlay.OverlayController
import yy.daniel.raidian.runtime.BackgroundPolicy

/** Application 级依赖装配；权限通道、采样、记录与浮窗共享同一实例。 */
class RaidianApp : Application() {

    lateinit var privileges: PrivilegeManager
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

    /**
     * 续航 / 充电曲线记录控制器(Application 级常驻):
     * 用户开启后台记录时每 30s 记电池状态、每 15min 读系统 batterystats；
     * 默认关闭，不创建前台服务。
     */
    lateinit var batteryCurves: yy.daniel.raidian.battery.BatteryCurveController
        private set

    /** 统一前后台策略：无任务时停服务、停探测并在延迟后退出进程 */
    lateinit var backgroundPolicy: BackgroundPolicy
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 0) 设备/系统画像识别(采样层按此分派探测策略),先记录便于排查
        val profile = yy.daniel.raidian.monitor.DeviceProfile.detect()
        android.util.Log.i("RaidianEnv", "device profile: $profile")
        privileges = PrivilegeManager(this)
        executor = CommandExecutor().apply { privileges = this@RaidianApp.privileges }
        // 3) 前台应用识别(FPS 读取器随后装配)
        appState = AppStateReader(this, executor)
        // 4) 采样器
        sampler = MonitorSampler(this, executor, appState)
        // 5) 装配 FPS 读取器(经采集服务 读 SurfaceFlinger)
        appState.fpsReader = SurfaceFlingerFpsReader(executor, appState)
        // 6) 会话记录器 + 读取端
        recorder = SessionRecorder(this, sampler, appState)
        sessionStore = SessionStore(this)
        // 7) 悬浮窗控制器(Application 级,常驻)
        overlayController = OverlayController(this)
        // 8) 续航 / 充电曲线记录(默认关闭；开启时才启动循环与前台服务)
        batteryCurves = yy.daniel.raidian.battery.BatteryCurveController(this, executor)
        // 9) 统一前后台策略(必须在各组件就绪之后、曲线 start 之前创建)
        backgroundPolicy = BackgroundPolicy(this)
        batteryCurves.start()
        // 观察进程级前后台；Application 启动时可能没有 Activity（例如服务重启），
        // 首次评估会在 30s 后结束这种“无任务”进程。
        backgroundPolicy.install()
        backgroundPolicy.onWorkChanged()
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
