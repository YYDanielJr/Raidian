package yy.daniel.raidian.privilege

import android.content.Context
import android.os.Process
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

    init { scope.launch { while (isActive) { if (!state.value.busy) refresh(); delay(3000) } } }

    suspend fun select(mode: AccessMode) = actions.withLock {
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
            if (mode == AccessMode.DEFAULT || !connected) return@withLock null
            try { client().call(operation, command) }
            catch (e: Exception) {
                mutable.value = state.value.copy(connected = false, uid = null, message = "连接已中断，暂用默认权限")
                null
            }
        }
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
