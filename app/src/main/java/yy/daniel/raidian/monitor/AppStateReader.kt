package yy.daniel.raidian.monitor

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 前台应用识别与 FPS 状态读取。
 *
 * 前台应用识别优先级:
 *  1. ActivityManager.RunningAppProcessInfo(快、无 shell,过滤自身进程)
 *  2. dumpsys activity(经采集服务/CommandExecutor,更准确,能看到顶层 Activity 归属)
 *
 * 用途:会话记录的开始按钮(SessionRecorder.start 的目标应用)、
 * 以及决定 FPS 应该去读哪个应用的 SurfaceFlinger 渲染层。
 */
class AppStateReader(
    private val context: Context,
    private val exec: CommandExecutor? = null,
) {
    private val pm = context.packageManager
    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    /** 帧率读取器,由采集循环注入(SurfaceFlinger/Choreographer/模拟) */
    @Volatile
    var fpsReader: FpsReader? = null

    /** 帧率快照(帧率 + 单帧耗时);无有效值用 [NONE](-1/-1) */
    data class FpsState(val fps: Float, val frameTimeMs: Float) {
        companion object { val NONE = FpsState(-1f, -1f) }
    }

    /**
     * 读取前台应用,返回 (包名, 应用名)。
     * 自身的悬浮窗/记录界面会让自身常驻前台,所以两步都要过滤掉自身。
     */
    suspend fun readFrontApp(): Pair<String, String> {
        return withContext(Dispatchers.IO) {
            try {
                val exe = exec
                if (exe?.privileged == true) {
                    // 平板多窗口优先跟随真正获得焦点的窗口；前台进程并不等于前台应用。
                    val window = exe.execute("dumpsys -t 1 window 2>/dev/null | grep 'mCurrentFocus='")
                    val pkg = Regex("([A-Za-z][\\w.]*)/").find(window)?.groupValues?.get(1)
                    if (pkg != null) return@withContext pkg to labelOf(pkg)
                    val activity = exe.execute("dumpsys -t 1 activity activities 2>/dev/null | grep 'topResumedActivity' | head -1")
                    val fallback = Regex("([A-Za-z][\\w.]*)/").find(activity)?.groupValues?.get(1)
                    if (fallback != null) return@withContext fallback to labelOf(fallback)
                    return@withContext "" to ""
                }
                // 未授权时只能知道自身是否可见，不能把其它进程猜成目标应用。
                if (am.runningAppProcesses.orEmpty().any {
                    it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                        it.pkgList?.contains(context.packageName) == true
                }) context.packageName to labelOf(context.packageName) else "" to ""

            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                "" to ""
            }
        }
    }

    /** 包名 → 应用显示名;查不到时退化为包名 */
    private fun labelOf(pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    /** 读取当前 FPS(fpsReader 内部自行判断前台与渲染层) */
    suspend fun readFps(): FpsState {
        val r = fpsReader ?: return FpsState.NONE
        return try {
            val fps = r.readFps()
            // fps==0:层在但无新帧(静止),是有效读数,保留 0;仅 <0 视为读不到
            if (fps >= 0) FpsState(fps, if (fps > 0) 1000f / fps else -1f) else FpsState.NONE
        } catch (e: Exception) {
            FpsState.NONE
        }
    }

    /**
     * 用已知前台包名读取 FPS(避免 FPS 读取器内部重复查前台 —— 采样主路径用这个)。
     * 仍可能由读取器内部决定是否需要 shell;前台未知时传空串走默认分支。
     */
    suspend fun readFpsFor(pkg: String): FpsState {
        val r = fpsReader as? SurfaceFlingerFpsReader ?: return readFps()
        return try {
            val fps = r.readFpsForPkg(pkg)
            if (fps >= 0) FpsState(fps, if (fps > 0) 1000f / fps else -1f) else FpsState.NONE
        } catch (e: Exception) {
            FpsState.NONE
        }
    }
}

/** 帧率读取接口:不同实现(现为 SurfaceFlinger)返回当前帧率,无效返回 -1 */
interface FpsReader {
    /** 返回当前每秒帧数,无效返回 -1 */
    fun readFps(): Float
}
