package yy.daniel.raidian.overlay

import android.content.Context

/**
 * 悬浮窗配置的持久化(SharedPreferences,文件名 raidian_overlay)。
 *
 * 保存内容:
 *  - 启用的显示项(Set<String>,键见 [OverlayWindow.ALL_ITEMS])
 *  - 悬浮窗位置(x/y 虚拟屏像素)
 *  - 触摸穿透开关
 *  - 字号档位(8~18sp)
 *
 * 读写方:主界面设置页(悬浮窗设置分页)与 OverlayController 写;
 * OverlayWindow 创建/更新时读。所有改动即时生效(OverlayController 转发到窗口)。
 */
class OverlayPrefs(context: Context) {

    private val sp = context.getSharedPreferences("raidian_overlay", Context.MODE_PRIVATE)

    /** 默认显示项(全部启用,不含网络 —— 网络暂未采集,无数据) */
    private val defaultItems = listOf(
        OverlayWindow.ITEM_FPS, OverlayWindow.ITEM_CPU, OverlayWindow.ITEM_GPU,
        OverlayWindow.ITEM_TEMP, OverlayWindow.ITEM_POWER,
    )

    /** 已启用的显示项集合;首次/无记录时返回默认集;过滤非法键防脏数据 */
    fun enabledItems(): Set<String> {
        val stored = sp.getStringSet("enabled_items", null) ?: return defaultItems.toSet()
        // 只保留合法项,避免脏数据
        return stored.filter { it in OverlayWindow.ALL_ITEMS }.toSet()
    }

    fun setEnabledItems(items: Set<String>) {
        sp.edit().putStringSet("enabled_items", items).apply()
    }

    /** 悬浮窗位置(虚拟屏像素;未记忆过则 null,创建时用默认位) */
    fun position(): Pair<Int, Int>? {
        val x = sp.getInt("pos_x", Int.MIN_VALUE)
        val y = sp.getInt("pos_y", Int.MIN_VALUE)
        return if (x == Int.MIN_VALUE || y == Int.MIN_VALUE) null else x to y
    }

    fun setPosition(x: Int, y: Int) {
        sp.edit().putInt("pos_x", x).putInt("pos_y", y).apply()
    }

    /** 触摸穿透:true = 悬浮窗不拦截触摸(游戏中用) */
    fun touchThrough(): Boolean = sp.getBoolean("touch_through", false)

    fun setTouchThrough(v: Boolean) {
        sp.edit().putBoolean("touch_through", v).apply()
    }

    /** 字号档位(11 基准,可增减,见 OverlayWindow 的 8~18sp 范围) */
    fun textSizeSp(): Int = sp.getInt("text_size_sp", 11)

    fun setTextSizeSp(v: Int) {
        // 夹在 8~18sp 之间,避免字号越界
        sp.edit().putInt("text_size_sp", v.coerceIn(8, 18)).apply()
    }
}
