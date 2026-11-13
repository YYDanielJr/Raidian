package yy.daniel.raidian.runtime

import android.os.Process
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import yy.daniel.raidian.RaidianApp
import yy.daniel.raidian.battery.BatteryCurveService
import yy.daniel.raidian.monitor.MonitorService
import yy.daniel.raidian.privilege.PrivilegeManager

/**
 * 统一前后台策略。
 *
 * 规则：
 *  - 前台（任意 Activity 处于 STARTED）：允许 UI 采样；也不启动任何常驻服务。
 *  - 后台且有「会话记录 / 悬浮窗 / 续航曲线记录」之一：保留对应前台服务。
 *  - 后台且三者都没有：停掉所有服务、循环和权限探测，并在 [IDLE_EXIT_DELAY_MS]
 *    后结束进程。这样系统既不会因为前台服务把我们当成“正在工作”，也不会在后台
 *    留下空的采样协程。再次打开应用会重新冷启动。
 *
 * 为什么用 [ProcessLifecycleOwner]：它把多 Activity 合并成一个进程级前后台状态，
 * 并有约 700ms 的切换防抖，不会因为 Activity 重建/转场误判。
 */
class BackgroundPolicy(private val app: RaidianApp) {

    companion object {
        private const val TAG = "RaidianBg"

        /**
         * 空闲退出延迟。留一段时间覆盖“返回桌面后马上又点回来”和 Activity 转场；
         * 期间不做任何采集，只有一次 delay，开销可以忽略。
         */
        private const val IDLE_EXIT_DELAY_MS = 30_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var foreground = false
    private var idleExitJob: Job? = null

    /** 在 Application.onCreate 末尾调用一次 */
    fun install() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                Log.i(TAG, "process foreground")
                foreground = true
                reevaluate()
            }

            override fun onStop(owner: LifecycleOwner) {
                Log.i(TAG, "process background")
                foreground = false
                reevaluate()
            }
        })
    }

    /** 会话/悬浮窗/曲线记录状态变化时调用（立即重新评估） */
    fun onWorkChanged() {
        reevaluate()
    }

    /** 当前是否真的还有需要后台运行的工作 */
    fun hasBackgroundWork(): Boolean =
        app.recorder.isRunning() || app.overlayController.isShown() || app.batteryCurves.isEnabled()

    private fun reevaluate() {
        // 前台本身就是“活跃”：用户在界面上，实时页需要采样，不能退出。
        if (foreground) {
            cancelIdleExit()
            app.privileges.setProbeMode(PrivilegeManager.ProbeMode.NORMAL)
            if (app.batteryCurves.isEnabled()) app.batteryCurves.ensureLoop()
            if (app.recorder.isRunning() || app.overlayController.isShown()) {
                MonitorService.start(app)
            }
            return
        }

        val recording = app.recorder.isRunning()
        val overlay = app.overlayController.isShown()
        val curves = app.batteryCurves.isEnabled()

        if (recording || overlay || curves) {
            cancelIdleExit()
            // 后台任务只需要偶发探测采集服务是否还在；曲线记录只用本地电池接口，
            // 不需要连接探测（真正读 batterystats 时再按需重连）。
            app.privileges.setProbeMode(
                if (recording || overlay) PrivilegeManager.ProbeMode.LOW
                else PrivilegeManager.ProbeMode.OFF
            )
            if (curves) app.batteryCurves.ensureLoop()
            if (recording || overlay) MonitorService.start(app)
            return
        }

        enterIdle()
    }

    private fun enterIdle() {
        Log.i(TAG, "idle: no recording/overlay/curves; stop services")
        // 先停所有可停的东西，再留一次延迟退出；即使进程没被杀，也不会再采样。
        app.privileges.setProbeMode(PrivilegeManager.ProbeMode.OFF)
        app.batteryCurves.suspendForIdle()
        MonitorService.stop(app)
        BatteryCurveService.stop(app)

        if (idleExitJob?.isActive == true) return
        idleExitJob = scope.launch {
            delay(IDLE_EXIT_DELAY_MS)
            // 延迟期间用户可能已经回到前台，或重新开始了记录/浮窗。
            if (foreground || hasBackgroundWork()) return@launch
            app.privileges.setProbeMode(PrivilegeManager.ProbeMode.OFF)
            app.batteryCurves.suspendForIdle()
            MonitorService.stop(app)
            BatteryCurveService.stop(app)
            // 不保留任何常驻组件后主动结束进程。系统本来也会在内存压力下回收，
            // 这里只是确保“后台没有任何事可做”时不会长期占着进程。
            Log.i(TAG, "idle timeout: exiting process")
            Process.killProcess(Process.myPid())
        }
    }

    private fun cancelIdleExit() {
        idleExitJob?.cancel()
        idleExitJob = null
    }
}
