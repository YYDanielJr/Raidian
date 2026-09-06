package yy.daniel.raidian

import android.content.Context

/**
 * 设置向导(首次开机向导)的完成状态持久化。
 *
 * MainActivity 启动时读取 [completed]:首次安装未完成 → 重定向到
 * [SetupWizardActivity];完成 → 直接进主页。设置页可随时再次打开向导
 * (重新授权/调整偏好),再次打开不会清掉"已完成"标记,中途退出也保留,
 * 只有正常走完并点「开始使用」才置回 true(实际上再次进入时本来也是 true,
 * 该标记仅用于首次启动判定)。
 */
class SetupPrefs(context: Context) {
    private val sp = context.getSharedPreferences("raidian_setup", Context.MODE_PRIVATE)

    var completed: Boolean
        get() = sp.getBoolean("completed", false)
        set(v) {
            sp.edit().putBoolean("completed", v).apply()
        }
}
