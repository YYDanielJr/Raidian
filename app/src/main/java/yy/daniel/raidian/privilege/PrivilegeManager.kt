package yy.daniel.raidian.privilege

import android.content.Context
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import yy.daniel.raidian.monitor.BoundedCommand
import java.io.File

enum class AccessMode(val label: String) {
    DEFAULT("默认权限"), WIRELESS_ADB("无线 ADB"), COMPUTER_ADB("电脑 ADB"), ROOT("root 权限")
}
data class AccessState(val mode: AccessMode, val connected: Boolean = false, val uid: Int? = null,
    val message: String = "使用应用自身权限", val busy: Boolean = false)

/** User-selected transport; never silently requests root or falls back to another privileged identity. */
class PrivilegeManager(private val context: Context) {
    /**
     * 采集服务连接探测节拍。
     *  - NORMAL：界面在前台，3s 一次，授权/连接状态能及时反映到 UI；
     *  - LOW：后台还有记录/浮窗任务，15s 一次，仅用于发现采集服务是否断开；
     *  - OFF：后台空闲或只有本地电池记录，完全不探测（真正需要时按需重连）。
     */
    enum class ProbeMode { OFF, LOW, NORMAL }

    private val prefs = context.getSharedPreferences("privilege", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val actions = Mutex()
    private val initialMode = runCatching { AccessMode.valueOf(prefs.getString("mode", "DEFAULT")!!) }.getOrDefault(AccessMode.DEFAULT)
    private val mutable = MutableStateFlow(AccessState(initialMode))
    val state = mutable.asStateFlow()
    val mode get() = state.value.mode
    val connected get() = state.value.connected
    private val token = RunnerIdentity.token(context)
    private fun client() = RunnerClient(mode == AccessMode.ROOT, token)
    private val revision get() = File(context.applicationInfo.sourceDir).lastModified()
    private var lastFailure = ""

    private var probeJob: Job? = null
    @Volatile private var probeMode = ProbeMode.OFF
    /** 断线时按需重连的最小间隔，避免每个采样点都去撞一遍连接。 */
    @Volatile private var nextReconnectAt = 0L

    init { setProbeMode(ProbeMode.OFF) }

    /**
     * 设置连接探测节拍。OFF 会取消探测协程；重新打开时若之前是连接状态，
     * 保留 [state]，下一次真正请求仍可直接使用。
     */
    fun setProbeMode(mode: ProbeMode) {
        if (probeMode == mode && probeJob?.isActive == true) return
        probeMode = mode
        probeJob?.cancel()
        probeJob = null
        if (mode == ProbeMode.OFF) return
        val interval = if (mode == ProbeMode.NORMAL) 3_000L else 15_000L
        probeJob = scope.launch {
            while (isActive) {
                if (!state.value.busy) refresh()
                delay(interval)
            }
        }
    }

    suspend fun select(mode: AccessMode) = actions.withLock {
        nextReconnectAt = 0L
        lock.withLock {
            prefs.edit().putString("mode", mode.name).apply()
            mutable.value = AccessState(mode, message = if (mode == AccessMode.DEFAULT) "使用应用自身权限" else "等待启动采集服务")
        }
        refresh()
    }

    suspend fun refresh() = withContext(Dispatchers.IO) { lock.withLock { probe() } }

    private fun probe() {
        if (mode == AccessMode.DEFAULT) return
        try {
            val reply = client().call(RunnerProtocol.HELLO)
            val fields = reply.output.split('|')
            check(reply.exitCode == 0 && fields.size == 2 && fields[1].toLong() == revision) {
                "应用已更新，请停止旧服务后重新启动"
            }
            nextReconnectAt = 0L
            mutable.value = state.value.copy(connected = true, uid = fields[0].toInt(), message = "采集服务已连接")
        } catch (e: Exception) {
            if (lastFailure != e.toString()) {
                lastFailure = e.toString()
                android.util.Log.w("RaidianAccess", "采集服务连接失败：$e")
            }
            mutable.value = state.value.copy(connected = false, uid = null,
                message = if (e is IllegalStateException) e.message.orEmpty()
                    else if (!state.value.connected && state.value.message != "使用应用自身权限") state.value.message
                    else "采集服务未连接，请启动或重新连接")
        }
    }

    suspend fun request(operation: Int, command: String = ""): BoundedCommand.Result? = withContext(Dispatchers.IO) {
        lock.withLock {
            // 采样路径绝不能在这里同步重连：runner 未启动时一次 connect 扫描可能阻塞数秒，
            // 会把 500ms 采样拖垮。后台探测（NORMAL/LOW）负责恢复连接；采集请求在断线时
            // 直接返回 null，让上层退回应用自身权限。
            if (mode == AccessMode.DEFAULT || !connected) return@withLock null
            try {
                client().call(operation, command).also { nextReconnectAt = 0L }
            } catch (e: Exception) {
                nextReconnectAt = SystemClock.elapsedRealtime() + 15_000L
                mutable.value = state.value.copy(connected = false, uid = null, message = "连接已中断，暂用默认权限")
                null
            }
        }
    }

    /**
     * 供低频、非采样路径（如续航曲线每 15min 的 batterystats）按需重连。
     * 采样路径不要调用；它可能阻塞数秒。
     */
    suspend fun ensureConnected(minIntervalMs: Long = 15_000L): Boolean {
        if (mode == AccessMode.DEFAULT) return false
        if (connected) return true
        val now = SystemClock.elapsedRealtime()
        if (now < nextReconnectAt) return false
        nextReconnectAt = now + minIntervalMs
        refresh()
        return connected
    }

    /** Same command for su and ADB; installed APK is readable to shell, no exported command receiver. */
    fun startCommand(): String {
        val apk = RunnerProtocol.quote(context.applicationInfo.sourceDir)
        val nice = if (mode == AccessMode.ROOT) "raidian_root" else "raidian_adb"
        return "CLASSPATH=$apk nohup setsid app_process /system/bin --nice-name=$nice yy.daniel.raidian.privilege.RunnerServer ${Process.myUid()} $token </dev/null >/dev/null 2>&1 &"
    }
    fun computerCommand() = "adb shell \"${startCommand()}\""

    private suspend fun stopCurrent() { lock.withLock { runCatching { client().call(RunnerProtocol.STOP) } }; delay(250) }
    suspend fun stop() = action {
        check(mode != AccessMode.DEFAULT)
        stopCurrent()
        refresh()
        if (!connected) mutable.value = state.value.copy(message = "采集服务已停止，暂用默认权限")
    }

    private suspend fun action(block: suspend () -> Unit) = actions.withLock {
        withContext(Dispatchers.IO) {
            mutable.value = state.value.copy(busy = true)
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = state.value.copy(message = e.message?.take(240) ?: "操作失败") }
            finally { mutable.value = state.value.copy(busy = false) }
        }
    }

    suspend fun startRoot() = action {
        check(mode == AccessMode.ROOT)
        stopCurrent()
        val result = BoundedCommand.run(startCommand(), timeoutMs = 30_000, shell = listOf("su", "-c"))
        check(result.exitCode == 0) { "root 启动失败，请检查 root 管理器授权：${result.output.take(120)}" }
        awaitRunner()
    }

    suspend fun pair(port: Int, code: String) = action {
        require(port in 1..65535 && code.matches(Regex("[0-9]{6}"))) { "请输入有效配对端口和六位配对码" }
        WirelessAdb(context).use { adb ->
            check(adb.pair("127.0.0.1", port, code)) { "配对失败，请重新打开系统配对窗口" }
        }
        mutable.value = state.value.copy(message = "配对成功，请填写连接端口或自动发现后启动")
    }

    suspend fun startWireless(port: Int?) = action {
        check(mode == AccessMode.WIRELESS_ADB)
        require(port == null || port in 1..65535) { "连接端口必须在 1—65535 之间" }
        stopCurrent()
        WirelessAdb(context).use { adb ->
            val ok = if (port == null) adb.connectTls(context, 10_000) else adb.connect("127.0.0.1", port)
            check(ok) { "ADB 连接失败，请检查连接端口与无线调试开关" }
            adb.startRunner(startCommand())
            awaitRunner()
        }
    }

    private suspend fun awaitRunner() {
        repeat(20) { refresh(); if (connected) return; delay(250) }
        error("未收到采集服务响应，请检查系统限制或重启后重试")
    }
}
