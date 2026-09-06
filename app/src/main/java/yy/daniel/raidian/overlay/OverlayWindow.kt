package yy.daniel.raidian.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import yy.daniel.raidian.monitor.Sample
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 小型悬浮窗(前台悬浮层,显示在任意应用之上)。
 *
 * 显示主要参数:帧率、CPU/GPU 频率、温度、功耗、内存、前台应用;
 * 可拖动(拖动结束记忆位置)、单机可切换触摸穿透;
 * 显示项与字号由配置控制(见 [OverlayPrefs],配置页修改即时生效)。
 *
 * 实现说明:
 *  - WindowManager + 自绘 TextView 行(悬浮窗只能用 View 体系,不宜引入 Compose 依赖,
 *    保证低开销、低版本兼容);
 *  - 所有 View 操作必须切主线程(mainHandler.post):悬浮窗更新来自采集协程
 *    (Dispatchers.Default/IO),直接操作 View 会抛 CalledFromWrongThreadException
 *    (阶段二"黑块"bug 根因,已修);
 *  - 类型:O 及以上 TYPE_APPLICATION_OVERLAY(需要 SYSTEM_ALERT_WINDOW 权限)。
 */
class OverlayWindow(
    private val context: Context,
    private val prefs: OverlayPrefs,
) {

    companion object {
        // 显示项键(与配置存储 / 配置页 UI 对应)
        const val ITEM_FPS = "fps"
        const val ITEM_CPU = "cpu"
        const val ITEM_GPU = "gpu"
        const val ITEM_TEMP = "temp"
        const val ITEM_POWER = "power"
        const val ITEM_MEM = "mem"
        const val ITEM_NET = "net"
        const val ITEM_FRONT = "front"
        /** 全部合法显示项;配置持久化/过滤以此为白名单 */
        val ALL_ITEMS = listOf(ITEM_FPS, ITEM_CPU, ITEM_GPU, ITEM_TEMP, ITEM_POWER, ITEM_MEM, ITEM_NET, ITEM_FRONT)
    }

    private val shown = AtomicBoolean(false)
    private var wm: WindowManager? = null
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    /** 主线程 Handler(View 操作必须切主线程) */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // 各显示行(key → TextView),show 时全部建好,按 enabledItems 显隐
    private val lineViews = mutableMapOf<String, TextView>()

    /** 启用的显示项(默认取配置;变更时热更新显隐) */
    var enabledItems: Set<String> = prefs.enabledItems()
        set(v) {
            field = v
            updateVisibility()
        }

    /** 触摸是否可交互(true = 可点按拖动;false = FLAG_NOT_TOUCHABLE 穿透) */
    var touchable = !prefs.touchThrough()
        private set

    fun isShowing() = shown.get()

    /** 显示悬浮窗(异步切主线程创建窗口) */
    fun show() {
        if (shown.getAndSet(true)) return
        mainHandler.post { showOnMain() }
    }

    /** 主线程:创建窗口布局与参数并 addView */
    private fun showOnMain() {
        if (!shown.get()) return
        wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // 圆角深色卡片背景
        val ll = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xC9141618.toInt())
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0x26FFFFFF)
            }
            elevation = dp(6).toFloat()
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        // 预先建好所有行(只按需显示,避免反复建 View)
        ALL_ITEMS.forEach { key ->
            val tv = TextView(context).apply {
                textSize = this@OverlayWindow.prefs.textSizeSp().toFloat()
                setTextColor(0xFFE0E0E0.toInt())
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                includeFontPadding = false
            }
            ll.addView(tv)
            lineViews[key] = tv
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // 悬浮窗类型:O+ 必须 TYPE_APPLICATION_OVERLAY,旧系统用 SYSTEM_ALERT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val pos = prefs.position()
            if (pos != null) {
                x = pos.first; y = pos.second // 记忆位置
            } else {
                x = dp(8); y = dp(200) // 默认位置
            }
            // 记忆的触摸穿透状态在创建时一并应用
            if (prefs.touchThrough()) {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
        }
        root = ll
        params = lp
        wm?.addView(ll, lp)
        setupDrag(ll, lp)
        updateVisibility()
    }

    /** 隐藏悬浮窗(切主线程移除窗口) */
    fun hide() {
        if (!shown.getAndSet(false)) return
        mainHandler.post {
            try {
                wm?.removeView(root)
            } catch (e: Exception) { }
            root = null; wm = null; params = null
        }
    }

    /** 用最新采样刷新文本(可在任意线程调用,内部自动切主线程) */
    fun update(sample: Sample) {
        if (Thread.currentThread() === mainHandler.looper.thread) {
            updateOnMain(sample)
        } else {
            mainHandler.post { updateOnMain(sample) }
        }
    }

    /** 主线程:按显示项拼行文本(数值 -1 → 该行显示 --) */
    private fun updateOnMain(sample: Sample) {
        val root = root ?: return
        val items = enabledItems
        /** 只更新启用的行;禁用行保留原文本(反正不可见) */
        fun line(key: String, text: String) {
            val tv = lineViews[key] ?: return
            if (key in items) tv.text = text
        }
        // FPS:0 = 层在但画面静止(无新帧),显示 0;仅 <0(读不到)显示 --
        if (sample.fps >= 0f) line(ITEM_FPS, String.format("FPS %5.1f  %5.1fms", sample.fps, sample.frameTimeMs))
        else line(ITEM_FPS, "FPS  --")

        if (sample.cpuL > 0 || sample.cpuM > 0 || sample.cpuB > 0) {
            line(ITEM_CPU, String.format("CPU %4d/%4d/%4dMHz %3.0f%%",
                sample.cpuL, sample.cpuM, sample.cpuB, sample.cpuLoad))
        } else line(ITEM_CPU, "CPU  --")

        // GPU 频率(无 root 读不到,显示 --)与占用(kgsl gpubusy 可读)
        if (sample.gpuLoad >= 0f) {
            val g = if (sample.gpuFreq > 0) String.format("%dMHz ", sample.gpuFreq) else ""
            line(ITEM_GPU, String.format("GPU %s%3.0f%%", g, sample.gpuLoad))
        } else if (sample.gpuFreq > 0) line(ITEM_GPU, String.format("GPU %5dMHz", sample.gpuFreq))
        else line(ITEM_GPU, "GPU  --")

        // 电池温度 + 核心(SoC)温度
        val t = sample.batteryTemp
        val soc = sample.maxSocTemp
        if (t > 0 || soc > 0) {
            val tb = if (t > 0) String.format("%.1f", t) else "--"
            val sb = if (soc > 0) String.format("%.1f", soc) else "--"
            line(ITEM_TEMP, "电温 ${tb}C 芯温 ${sb}C")
        } else line(ITEM_TEMP, "温度 --")

        // 功耗(>1000mW 显示为 W)+ 电量
        val p = sample.powerMw
        val level = sample.batteryLevel
        if (p > 0) {
            val pw = if (p > 1000) String.format("%.2fW", p / 1000f) else "${p}mW"
            line(ITEM_POWER, if (level >= 0) "功耗 $pw  $level%" else "功耗 $pw")
        } else if (level >= 0) line(ITEM_POWER, "电量 $level%")
        else line(ITEM_POWER, "功耗 --")

        if (sample.usedMemMb > 0) {
            line(ITEM_MEM, String.format("内存 %d/%dMB", sample.usedMemMb, sample.totalMemMb))
        } else line(ITEM_MEM, "内存 --")

        // 网络行:当前未采集具体数值,固定占位
        line(ITEM_NET, "--")

        val front = sample.frontApp
        if (front.isNotEmpty()) line(ITEM_FRONT, front)
        else line(ITEM_FRONT, "")
    }

    /** 按 enabledItems 更新各行的可见性 */
    private fun updateVisibility() {
        lineViews.forEach { (k, tv) ->
            tv.visibility = if (k in enabledItems) View.VISIBLE else View.GONE
        }
    }

    /**
     * 拖动逻辑:
     *  - 拖动(位移 > 4dp)结束 → 记忆位置(setPosition);
     *  - 单击(短按无位移)→ 切换触摸穿透(游戏中临时穿透很方便)。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag(view: View, lp: WindowManager.LayoutParams) {
        val wm = wm ?: return
        var lastX = 0; var lastY = 0
        var downTime = 0L
        var moved = false
        view.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = ev.rawX.toInt(); lastY = ev.rawY.toInt()
                    downTime = System.currentTimeMillis(); moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX.toInt() - lastX
                    val dy = ev.rawY.toInt() - lastY
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(4)) moved = true
                    lp.x += dx; lp.y += dy
                    lastX = ev.rawX.toInt(); lastY = ev.rawY.toInt()
                    wm.updateViewLayout(v, lp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        // 拖动结束:记忆位置
                        try { prefs.setPosition(lp.x, lp.y) } catch (e: Exception) { }
                    } else if (System.currentTimeMillis() - downTime < 250) {
                        // 单击:切换触摸穿透
                        toggleTouchable()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** 单击悬浮窗 → 切换触摸穿透(同时写配置,下次显示沿用) */
    private fun toggleTouchable() {
        touchable = !touchable
        prefs.setTouchThrough(!touchable)
        applyTouchable()
    }

    /** 把当前 touchable 状态应用到窗口 LayoutParams(增删 FLAG_NOT_TOUCHABLE) */
    private fun applyTouchable() {
        val lp = params ?: return
        if (touchable) {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try { wm?.updateViewLayout(root, lp) } catch (e: Exception) { }
    }

    /** 由控制器(配置页开关)调用:设置触摸穿透,与悬浮窗上点按状态保持同步 */
    fun setTouchThrough(through: Boolean) {
        touchable = !through
        mainHandler.post { applyTouchable() }
    }

    /** 更新字号(配置页改字号时热更新所有行) */
    fun setTextSize(sp: Int) {
        mainHandler.post {
            lineViews.values.forEach { it.textSize = sp.toFloat() }
        }
    }

    /** dp → px(按当前屏幕密度) */
    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
