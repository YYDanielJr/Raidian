package yy.daniel.raidian.monitor

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import rikka.shizuku.Shizuku
import yy.daniel.raidian.shizuku.CommandRunnerService
import yy.daniel.raidian.shizuku.ICommandRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shizuku 通道封装(应用侧)。
 *
 * 以 Shizuku UserService 方式绑定一个独立进程 [CommandRunnerService]
 * (fork 出的 `:runner` 进程),该进程以被授予的 shell/adb 身份运行,
 * 可读 thermal/cpu 等 shell 可读的 sysfs、执行提权命令。
 *
 * 关键事实(实机验证,详见 AGENTS.md):
 *  - Shizuku v13 的 Shizuku.newProcess 是 private,不可用;必须用 UserService 模式
 *  - 需要 daemon(true)+tag+processNameSuffix,Shizuku 才 fork `:runner` 进程
 *  - 权限授权走 Shizuku 系统对话框,授权结果由 OnRequestPermissionResultListener 回调
 *
 * 线程:exec/readThermalZones 是 suspend,内部 withContext(IO) 走 binder,可被
 * 采集循环安全调用。
 */
class ShizukuShell(private val context: Context) {
    companion object {
        private const val TAG = "ShizukuShell"
        /** 请求授权用的 requestCode(唯一标识,与回调对账) */
        const val REQUEST_CODE = 20240
        /** 绑定 UserService 的超时:超时认为绑定失败并重试 */
        private const val BIND_TIMEOUT_MS = 5000L
        /** 首次绑定前的延迟:等 Shizuku 服务就绪,避免启动即绑定失败 */
        private const val INITIAL_BIND_DELAY_MS = 1000L
        /** UserService 的 tag,用于标识/复用(配合 :runner 进程) */
        private const val USER_SERVICE_TAG = "raidian-runner"
    }

    /** 轻量"权限已授予"状态(替代 StateFlow,避免依赖泄漏;见类尾部) */
    private val granted = MutableStateFlowLike(false)

    /** 已连接的 UserService binder 代理 */
    @Volatile private var runner: ICommandRunner? = null
    /** 是否正在发起绑定(防并发重复绑定) */
    @Volatile private var isConnecting = false
    /** 当前 ServiceConnection(用于断开时解绑同一连接) */
    private var connection: ServiceConnection? = null
    /** 进行中绑定操作的挂起结果(awaitRunner 等待它完成) */
    private var pendingConnection: CompletableDeferred<ICommandRunner?>? = null
    /** 首次允许绑定的最早时刻(见 INITIAL_BIND_DELAY_MS) */
    private var firstBindNotBeforeMs = 0L

    // ---- Shizuku 全局事件监听 ----

    /** Shizuku binder 就绪(Shizuku 服务启动/重启):刷新状态并可能重连 */
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refresh()
    }
    /** Shizuku binder 死亡(Shizuku 服务被杀):清状态、断开连接 */
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        granted.value = false
        disconnect()
    }
    /** 用户对授权对话框的回应:与 REQUEST_CODE 对账后更新授权状态 */
    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code == REQUEST_CODE) {
            granted.value = result == PackageManager.PERMISSION_GRANTED
        }
    }

    /** 权限是否已授予(界面轮询/状态) */
    var permissionGranted: Boolean
        get() = granted.value
        set(v) { granted.value = v }

    init {
        refresh()
    }

    /** 注册 Shizuku 监听(Application.onCreate 调用一次);粘性回调会立即补发当前状态 */
    fun init() {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            firstBindNotBeforeMs = SystemClock.elapsedRealtime() + INITIAL_BIND_DELAY_MS
            refresh()
        } catch (e: Exception) {
            Log.w(TAG, "init failed", e)
        }
    }

    /** 注销 Shizuku 监听并断开连接(应用退出时调用) */
    fun destroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (e: Exception) { }
        disconnect()
    }

    /**
     * 刷新授权状态:binder 存活 + 版本 ≥11 + checkSelfPermission 已授予 三者都满足
     * 才算可用。任何一步异常都按"未授权"处理,避免误用。
     */
    fun refresh() {
        try {
            val binderAlive = Shizuku.pingBinder()
            val version = try { Shizuku.getVersion() } catch (e: Exception) { -1 }
            val hasPerm = !Shizuku.isPreV11() && version >= 11 &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            granted.value = binderAlive && hasPerm
            Log.i(TAG, "refresh: binderAlive=$binderAlive ver=$version hasPerm=$hasPerm granted=${granted.value}")
            if (!binderAlive) runner = null
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed: ${e.message}", e)
            granted.value = false
            runner = null
        }
    }

    /** Shizuku 服务 binder 是否存活 */
    fun isBinderAlive(): Boolean = try {
        val alive = Shizuku.pingBinder()
        if (!alive) Log.w(TAG, "pingBinder false")
        alive
    } catch (e: Exception) {
        Log.w(TAG, "pingBinder error: ${e.message}")
        false
    }

    /** 快捷判断:已授权且 binder 存活,可以执行提权操作 */
    fun available(): Boolean = granted.value && isBinderAlive()

    /** 请求权限(主线程调用):弹出 Shizuku 系统授权对话框 */
    fun requestPermission() {
        try {
            refresh()
            if (available()) return
            if (!Shizuku.isPreV11() && Shizuku.getVersion() >= 11 &&
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
            ) {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "requestPermission failed", e)
        }
    }

    /** 供 Activity 在 onRequestPermissionsResult / 回调里刷新授权状态 */
    fun onPermissionResult(granted: Boolean) {
        this.granted.value = granted
    }

    /**
     * 绑定 UserService(连接独立提权进程),最多重试 3 次。
     * @return 绑定成功返回 runner 代理;超时/失败返回 null
     */
    private suspend fun awaitRunner(): ICommandRunner? {
        runner?.let { return it }
        // 首次绑定前等一小段,给 Shizuku 服务留出就绪时间
        val delay = firstBindNotBeforeMs - SystemClock.elapsedRealtime()
        if (delay > 0) {
            kotlinx.coroutines.delay(delay)
        }
        if (!available()) return null
        var attempts = 0
        while (attempts < 3) {
            attempts++
            connect()
            val deferred = pendingConnection ?: return runner
            val r = withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }
            if (r != null) return r
            disconnect()
            if (attempts < 3) kotlinx.coroutines.delay(300)
        }
        return null
    }

    /** 发起一次 UserService 绑定;结果经 onServiceConnected/Disconnected 回调写回 */
    private fun connect() {
        if (runner != null || isConnecting) return
        if (!isBinderAlive()) { granted.value = false; return }
        isConnecting = true
        val deferred = CompletableDeferred<ICommandRunner?>()
        pendingConnection = deferred
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (connection !== this) return // 已被新连接替换,忽略旧回调
                runner = ICommandRunner.Stub.asInterface(service)
                isConnecting = false
                pendingConnection?.complete(runner)
                pendingConnection = null
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (connection !== this) return
                runner = null
                isConnecting = false
                pendingConnection?.complete(null)
                pendingConnection = null
            }
        }
        connection = conn
        try {
            // UserService 参数:daemon 让 runner 进程常驻,processNameSuffix 决定 fork 出的进程名
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, CommandRunnerService::class.java.name)
            )
                .daemon(true)
                .tag(USER_SERVICE_TAG)
                .version(2)
                .processNameSuffix("runner")
            Shizuku.bindUserService(args, conn)
        } catch (e: Exception) {
            Log.w(TAG, "bindUserService failed", e)
            isConnecting = false
            pendingConnection?.complete(null)
            pendingConnection = null
        }
    }

    /** 解绑并清理连接状态 */
    private fun disconnect() {
        val conn = connection ?: run {
            runner = null
            isConnecting = false
            pendingConnection?.complete(null)
            pendingConnection = null
            return
        }
        try {
            // 用与绑定时相同的 UserServiceArgs 解绑(Shizuku API 要求一致)
            val args = Shizuku.UserServiceArgs(
                ComponentName(context.packageName, CommandRunnerService::class.java.name)
            )
                .daemon(true)
                .tag(USER_SERVICE_TAG)
                .version(2)
                .processNameSuffix("runner")
            Shizuku.unbindUserService(args, conn, false)
        } catch (e: Exception) {
            Log.w(TAG, "unbind failed", e)
        }
        connection = null
        runner = null
        isConnecting = false
        pendingConnection?.complete(null)
        pendingConnection = null
    }

    /**
     * 在提权进程里执行一条 shell 命令,返回输出。
     * 无权限 / 绑定失败 / 执行异常都返回 null(上层走降级)。
     */
    suspend fun exec(cmd: String): String? {
        if (!available()) return null
        val r = awaitRunner() ?: return null
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                r.executeCommand(cmd)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            runner = null
            Log.w(TAG, "exec failed", e)
            null
        }
    }

    /** 读取热区(在提权进程内直接 File 遍历,快且无 shell 开销);失败返回 null */
    suspend fun readThermalZones(): String? {
        if (!available()) return null
        val r = awaitRunner() ?: return null
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                r.readThermalZones()
            }
        } catch (e: Exception) {
            Log.w(TAG, "readThermalZones failed", e)
            null
        }
    }

    suspend fun readHardwareSnapshot(): String? {
        if (!available()) return null
        val r = awaitRunner() ?: return null
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { r.readHardwareSnapshot() }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            runner = null
            Log.w(TAG, "hardware snapshot failed", e)
            null
        }
    }

    /**
     * 轻量可变布尔状态 + 观察者(避免为一个小布尔状态引入 StateFlow 依赖):
     * 每次 set 会通知所有观察者。仅用于 granted 授权标志。
     */
    private class MutableStateFlowLike(initial: Boolean) {
        var value: Boolean = initial
            set(v) {
                field = v
                listeners.forEach { it(v) }
            }
        private val listeners = mutableListOf<(Boolean) -> Unit>()
        fun observe(l: (Boolean) -> Unit) { listeners.add(l) }
    }
}
