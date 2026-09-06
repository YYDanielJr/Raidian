package yy.daniel.raidian.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import yy.daniel.raidian.RaidianApp
import yy.daniel.raidian.monitor.Sample

/**
 * 悬浮窗控制器(Application 级,随进程常驻 —— 架构关键,勿移到 Activity/ViewModel)。
 *
 * 职责:
 *  - 持有 [OverlayWindow] 单例与 [OverlayPrefs] 配置
 *  - 悬浮窗显示时,在本控制器自己的协程作用域里启动全局轮询采集
 *    (每 500ms 采样 → 刷新悬浮窗 → 写入 lastSample/history 供主界面实时页)
 *  - 主界面 Activity 销毁(用户切去玩游戏)后,悬浮窗仍持续更新 —— 因为轮询
 *    挂在 Application 级作用域上,与界面生命周期无关(实机验证)
 *
 * 数据流约定:
 *  - 悬浮窗显示:OverlayController 内部轮询(pollJob),主界面 UI 轮询自动停;
 *  - 悬浮窗未显示:主界面用 publishSample() 自轮询并发布,避免双采样抢 binder。
 */
class OverlayController(private val app: RaidianApp) {

    /** 本控制器自己的协程作用域(独立于 Activity) */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 悬浮窗是否显示中 */
    private val _shown = MutableStateFlow(false)
    val shown: StateFlow<Boolean> = _shown.asStateFlow()

    @Volatile private var overlay: OverlayWindow? = null
    @Volatile private var pollJob: Job? = null

    /** 悬浮窗配置(主界面配置页与窗口共用) */
    val prefs: OverlayPrefs = OverlayPrefs(app)

    /** 显示悬浮窗并启动数据轮询;无悬浮窗权限则引导去系统设置授权 */
    fun show() {
        if (_shown.value) return
        if (!Settings.canDrawOverlays(app)) {
            openSettings(app)
            return
        }
        val ov = overlay ?: OverlayWindow(app, prefs).also { overlay = it }
        ov.enabledItems = prefs.enabledItems()
        ov.show()
        _shown.value = true
        startPolling()
    }

    /** 隐藏悬浮窗并停止轮询 */
    fun hide() {
        overlay?.hide()
        _shown.value = false
        pollJob?.cancel()
        pollJob = null
    }

    /** 显示/隐藏切换(主界面按钮用) */
    fun toggle() {
        if (_shown.value) hide() else show()
    }

    fun isShown(): Boolean = _shown.value

    /** 更新悬浮窗显示项(持久化并即时生效) */
    fun setEnabledItems(items: Set<String>) {
        prefs.setEnabledItems(items)
        overlay?.enabledItems = items
    }

    /** 设置触摸穿透(持久化并即时生效) */
    fun setTouchThrough(through: Boolean) {
        prefs.setTouchThrough(through)
        overlay?.setTouchThrough(through)
    }

    /** 设置字号 sp(持久化并即时生效) */
    fun setTextSize(sp: Int) {
        prefs.setTextSizeSp(sp)
        overlay?.setTextSize(sp)
    }

    /** 供外部(主界面)跳转悬浮窗权限设置页;个别机型无包名入口时兜底通用页 */
    fun openSettings(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${app.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            val fallback = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(fallback)
        }
    }

    /** 悬浮窗轮询循环:500ms 一次采样 → 刷新悬浮窗 + 发布到历史缓冲 */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                try {
                    val s = app.sampler.sample(System.currentTimeMillis())
                    overlay?.update(s)
                    pushSample(s)
                } catch (e: Exception) {
                    // 单次失败忽略,下轮重试
                }
                delay(500)
            }
        }
    }

    /** 最新一次采样(主界面实时页与悬浮窗同源读取这里) */
    private val _lastSample = MutableStateFlow(Sample(elapsedMs = 0))
    val lastSample: StateFlow<Sample> = _lastSample.asStateFlow()

    /** 实时历史缓冲(最近 240 点 ≈ 2 分钟),实时页画曲线用 */
    private val _history = MutableStateFlow<List<Sample>>(emptyList())
    val history: StateFlow<List<Sample>> = _history.asStateFlow()

    /** 把一次采样写进 lastSample 与 history(环形截断到 HISTORY_MAX) */
    private fun pushSample(s: Sample) {
        _lastSample.value = s
        val cur = _history.value
        val next = if (cur.size >= HISTORY_MAX) cur.drop(1) + s else cur + s
        _history.value = next
    }

    /**
     * 向最新值/历史缓冲发布一次采样。
     * 用途:主界面在悬浮窗**未显示**时自轮询调它(见 MainViewModel.startUiPolling);
     * 悬浮窗显示时由 [startPolling] 内部调用,本方法直接跳过,避免双采样。
     */
    fun publishSample(s: Sample) {
        if (_shown.value) return  // 悬浮窗轮询已在跑,避免双采样
        overlay?.update(s)
        pushSample(s)
    }

    companion object {
        /** 历史缓冲点数:240 点 × 500ms ≈ 2 分钟 */
        private const val HISTORY_MAX = 240
    }
}
