package yy.daniel.raidian.monitor

import android.content.Context
import android.net.TrafficStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 采样器:每次调用产出 1 个 [Sample],是后台记录与悬浮窗共用的数据源。
 *
 * 数据来源分类:
 *  - sysfs(需提权,经 [CommandExecutor] / Shizuku runner 进程):CPU 频率、温度、GPU
 *  - /proc:CPU 负载(/proc/stat)、内存(/proc/meminfo)、网络(/proc/net/dev)
 *  - shell 命令:dumpsys battery(温度/电量/电压/充电状态,shell 身份可读)
 *  - Android API:BatteryManager(电流)、ActivityManager(前台)、TrafficStats(兜底)
 *
 * 容错原则:所有路径都 try 包裹,任何单项失败只把对应字段置 -1/空,
 * 绝不让一次采样整体失败 —— 采样循环(SessionRecorder/OverlayController)依赖这一点。
 *
 * 性能原则(阶段二实测):每 tick 尽量只做 4 次跨进程往返 ——
 *  1 条合并 shell(CPU 频率+负载+内存+网络)+ 1 条 GPU 探测 shell +
 *  1 次 thermal 直接 File 遍历 + 1 次 dumpsys battery;前台识别优先用
 *  RunningAppProcessInfo(不额外 shell);FPS 只在有渲染层时走 shell。
 */
class MonitorSampler(
    private val context: Context,
    private val exec: CommandExecutor,
    private val appState: AppStateReader,
) {
    // CPU 簇档映射缓存:每个逻辑核 -> 档位 0/1/2(0=小 1=中 2=大,见 buildClusterMap)
    private var clusterMap: List<Int>? = null

    // 上一个采样点的 CPU jiffies(总 / 空闲)与时间戳,用于算两次之间的占用率
    private var lastCpuTotal = 0L
    private var lastCpuIdle = 0L
    private var lastCpuTimeMs = 0L

    /**
     * 执行一次完整采样。
     * @param elapsedMs 距会话开始的毫秒数(仅透传写入 Sample,不含计时逻辑)
     * @return 采样快照;极端情况下整次失败也返回一个"空" Sample 而非抛异常
     */
    suspend fun sample(elapsedMs: Long): Sample {
        return withContext(Dispatchers.IO) {
            try {
                // 各读取内部已自行合并/降级,见各 readXxx 注释
                val merged = readMerged()
                val (gpuFreq, gpuLoad) = readGpu()
                val batt = readBattery()
                val temps = readTemps()
                val front = appState.readFrontApp()
                val fpsState = appState.readFpsFor(front.first)

                Sample(
                    elapsedMs = elapsedMs,
                    fps = fpsState.fps,
                    frameTimeMs = if (fpsState.fps > 0) 1000f / fpsState.fps else -1f,
                    cpuL = merged.cpuL, cpuM = merged.cpuM, cpuB = merged.cpuB,
                    cpuLoad = merged.cpuLoad,
                    perCoreFreqMhz = merged.perCoreFreq,
                    gpuFreq = gpuFreq, gpuLoad = gpuLoad,
                    batteryTemp = batt.temp,
                    maxCpuTemp = temps.maxCpu,
                    maxSocTemp = temps.maxSoc,
                    maxOtherTemp = temps.maxOther,
                    currentMa = batt.currentMa, voltageMv = batt.voltageMv,
                    powerMw = batt.powerMw,
                    batteryLevel = batt.level, chargeState = batt.charge,
                    usedMemMb = merged.memUsed, totalMemMb = merged.memTotal,
                    frontPkg = front.first, frontApp = front.second,
                    wifiRxBytes = merged.wifiRx, wifiTxBytes = merged.wifiTx,
                    mobileRxBytes = merged.mobileRx, mobileTxBytes = merged.mobileTx,
                )
            } catch (e: Exception) {
                // 兜底:绝不抛给上层,返回全空采样
                Sample(elapsedMs = elapsedMs)
            }
        }
    }

    /**
     * 合并读取:CPU 频率(每簇取代表核)+ CPU 负载 + 内存 + 网络,拼成**一条** shell
     * 命令执行,减少进程创建/跨进程 binder 次数(阶段二性能修复核心)。
     *
     * 输出带 @@ 标记便于分段解析:
     *   @@C<簇号>  → 随后一行是该簇代表核的 scaling_cur_freq(Hz)
     *   @@LOAD    → /proc/stat 首行 cpu 汇总行
     *   @@MEM     → /proc/meminfo 前 3 行
     *   @@NET     → /proc/net/dev 全文
     */
    private suspend fun readMerged(): MergedRead {
        val cm = clusterMap ?: buildClusterMap().also { clusterMap = it }
        val nCores = cm.size
        // 每档位代表核(该档第一个逻辑核),用于 L/M/B 三档显示。
        // 档位 0/1/2 = 小/中/大(见 buildClusterMap):簇数 <3 的设备(如 2 簇)
        // 后置档位无代表核,对应 freqs 保持 -1,UI 显示 "--"。簇数 >3 的未知
        // 设备沿用旧语义只显示前三档。
        val clusterReps = cm.distinct().sorted().map { cl -> cl to cm.indexOfFirst { it == cl } }
        val sb = StringBuilder()
        clusterReps.forEach { (cl, core) ->
            sb.append("echo \"@@C$cl\"; cat /sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq 2>/dev/null; echo; ")
        }
        // 每个逻辑核独立频率(详情页每核曲线):@@P<core> 标记逐核 cat
        for (core in 0 until nCores) {
            sb.append("echo \"@@P$core\"; cat /sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq 2>/dev/null; echo; ")
        }
        sb.append("echo \"@@LOAD\"; cat /proc/stat 2>/dev/null | head -1; ")
        sb.append("echo \"@@MEM\"; head -3 /proc/meminfo 2>/dev/null; ")
        sb.append("echo \"@@NET\"; cat /proc/net/dev 2>/dev/null; echo")
        val out = exec.execute(sb.toString())

        // 逐行解析上面的标记块(按 4 簇预留,实际簇数少则后置位保持 -1)
        val freqs = IntArray(4) { -1 }
        val perCore = IntArray(nCores) { -1 }
        var total = 0L; var idle = 0L
        var memTotal = -1L; var memAvail = -1L
        var wifiRx = 0L; var wifiTx = 0L; var mobileRx = 0L; var mobileTx = 0L
        var curKey = ""
        out.lines().forEach { line ->
            when {
                line.startsWith("@@C") || line.startsWith("@@P") ||
                    line.startsWith("@@LOAD") || line.startsWith("@@MEM") ||
                    line.startsWith("@@NET") -> curKey = line.removePrefix("@@")
                // /proc/stat: cpu user nice system idle iowait irq softirq steal ...
                curKey == "LOAD" && line.startsWith("cpu ") -> {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size > 4) {
                        total = parts.drop(1).take(8).sumOf { it.toLongOrNull() ?: 0L }
                        idle = (parts.getOrNull(4)?.toLongOrNull() ?: 0L) +
                            (parts.getOrNull(5)?.toLongOrNull() ?: 0L) // idle + iowait
                    }
                }
                // /proc/meminfo: kB 单位
                curKey == "MEM" && line.startsWith("MemTotal:") ->
                    memTotal = line.split(Regex("\\s+"))[1].toLongOrNull() ?: -1L
                curKey == "MEM" && line.startsWith("MemAvailable:") ->
                    memAvail = line.split(Regex("\\s+"))[1].toLongOrNull() ?: -1L
                // /proc/net/dev: 每行 "接口: rx字节 ... tx字节 ..."(索引 0=rx, 8=tx)
                curKey == "NET" && line.contains(":") -> {
                    val name = line.substringBefore(':').trim()
                    if (name.isNotEmpty()) {
                        val rest = line.substringAfter(':').trim().split(Regex("\\s+"))
                        if (rest.size >= 9) {
                            val rx = rest[0].toLongOrNull() ?: 0L
                            val tx = rest[8].toLongOrNull() ?: 0L
                            when {
                                name.startsWith("wlan") || name.startsWith("wifi") -> { wifiRx += rx; wifiTx += tx }
                                name.startsWith("rmnet") || name.startsWith("ccmni") ||
                                    name.startsWith("radio") || name.startsWith("wwan") -> { mobileRx += rx; mobileTx += tx }
                            }
                        }
                    }
                }
                // @@C<n> 后的频率行(簇代表核):Hz → MHz(/1000)
                curKey.startsWith("C") && curKey.length == 2 -> {
                    val cl = curKey[1].digitToIntOrNull()
                    val v = line.trim().toLongOrNull()
                    if (cl != null && v != null && cl in 0..3) freqs[cl] = (v / 1000).toInt()
                }
                // @@P<core> 后的频率行(单个逻辑核):Hz → MHz(/1000)
                curKey.startsWith("P") && curKey.length > 1 -> {
                    val core = curKey.substring(1).toIntOrNull()
                    val v = line.trim().toLongOrNull()
                    if (core != null && v != null && core in 0 until nCores) perCore[core] = (v / 1000).toInt()
                }
            }
        }
        // CPU 占用率:用两次采样间 (总 − 空闲) 增量 / 总增量
        var load = -1f
        val now = System.currentTimeMillis()
        if (lastCpuTotal > 0) {
            val dt = now - lastCpuTimeMs
            val dTotal = total - lastCpuTotal
            val dIdle = idle - lastCpuIdle
            if (dt > 0 && dTotal > 0) {
                load = ((1f - dIdle.toFloat() / dTotal) * 100).coerceIn(0f, 100f)
            }
        }
        lastCpuTotal = total; lastCpuIdle = idle; lastCpuTimeMs = now
        // 内存 kB → MB
        if (memTotal > 0) memTotal /= 1024
        if (memAvail > 0) memAvail /= 1024
        val used = if (memTotal > 0 && memAvail >= 0) memTotal - memAvail else -1L
        // 每核频率按核号升序排(逻辑核 0..nCores-1)
        val perCoreList = perCore.toList()
        val base = MergedRead(freqs[0], freqs[1], freqs[2], load, used, memTotal,
            wifiRx, wifiTx, mobileRx, mobileTx, perCoreList)
        if (wifiRx == 0L && wifiTx == 0L && mobileRx == 0L && mobileTx == 0L) {
            // 没有任何网络接口被识别(少见):用系统 TrafficStats 的总字节兜底(仅 RX)
            val tRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0)
            return base.copy(wifiRx = tRx)
        }
        return base
    }

    /** 单次合并读取的中间结果(内部使用) */
    private data class MergedRead(
        val cpuL: Int, val cpuM: Int, val cpuB: Int, val cpuLoad: Float,
        val memUsed: Long, val memTotal: Long,
        val wifiRx: Long, val wifiTx: Long, val mobileRx: Long, val mobileTx: Long,
        /** 每逻辑核频率 MHz,下标 = 逻辑核号;读不到为 -1 */
        val perCoreFreq: List<Int> = emptyList(),
    )

    // CPU 读取已并入 readMerged()(CPU 频率/负载/内存/网络一次 shell 完成)

    /**
     * 构建"逻辑核 → 簇档位"映射,返回列表长度 = 实际核数,下标即逻辑核号,
     * 值为档位 0(小)/1(中)/2(大)。
     *
     * 按 [DeviceProfile.clusterProbe] 分派(厂商/SoC 差异的核心落点):
     *  - [DeviceProfile.ClusterProbe.CAPACITY_8GEN3]:骁龙 8 Gen 3(ColorOS 一加平板 Pro
     *    实测)是 **4 簇 + 中核双 bin**,频率表每簇各不相同(cpu2-4 与 cpu5-6 两张中核表),
     *    按"频率表相同"聚类会得到 4 簇、把真正的大核(cpu7)挤出前 3 → L/M/B 错位。
     *    改按 `/sys/devices/system/cpu/cpuN/cpu_capacity` 归簇(同簇同容量),再按容量
     *    升序把 4 簇折成 3 档:最低=小、最高=大、中间全部=中(多簇取其中一簇,不影响
     *    档位语义——逐核 perCore 曲线仍按核号独立记录)。
     *  - 其它(HyperOS 小米 13 Ultra 等):按"共享同一组 scaling_available_frequencies"
     *    聚类,天然 3 簇且顺序即小中大(旧行为,已实机验证)。
     *
     * 修正(继承旧代码):不用 Runtime.availableProcessors()(可能因离线核偏小,实机返回 6
     * 但实际有 cpu0..cpu7),而是遍历 /sys/devices/system/cpu/cpu* 目录探测最大核号。
     */
    private suspend fun buildClusterMap(): List<Int> {
        val n = probeCoreCount()
        return when (DeviceProfile.detect().clusterProbe) {
            DeviceProfile.ClusterProbe.CAPACITY_8GEN3 -> buildByCapacity(n)
            else -> buildByFreqTable(n)
        }
    }

    /** 骁龙 8 Gen 3 / 4 簇中核双 bin:按 cpu_capacity 归簇折 3 档 */
    private suspend fun buildByCapacity(n: Int): List<Int> {
        // 1) 逐核读 capacity;读不到(个别核离线/路径拒)的核先按 0 处理,末尾回退
        val caps = IntArray(n) { -1 }
        for (core in 0 until n) {
            val line = exec.readFile("/sys/devices/system/cpu/cpu$core/cpu_capacity")
            caps[core] = line.trim().toIntOrNull() ?: -1
        }
        // 2) 有效容量按升序取"簇代表值"(去重),得到实际簇数 k
        val distinct = caps.filter { it > 0 }.distinct().sorted()
        if (distinct.isEmpty()) {
            // capacity 全读不到(极端):退频率表聚类
            return buildByFreqTable(n)
        }
        // 3) 折成 3 档:下标 0 = 最小容量(小核),最后 = 最大容量(大核),
        //    中间若干簇(8 Gen 3 有 2 个中核 bin)全部折成档位 1(中)
        val minCap = distinct.first()
        val maxCap = distinct.last()
        return List(n) { core ->
            when (caps[core]) {
                minCap -> 0          // 小核
                maxCap -> 2          // 大核
                else -> if (caps[core] > 0) 1 else 0 // 中核;读不到的核并入小核(占位)
            }
        }
    }

    /** 小米等传统 3 簇(每簇一张频率表):按频率表相同聚类,簇序天然小中大 */
    private suspend fun buildByFreqTable(n: Int): List<Int> {
        val map = MutableList(n) { 0 }
        val seen = mutableListOf<List<Long>>()
        for (core in 0 until n) {
            // 同一簇的核共享同一组可用频率,据此聚类
            val freqPath = "/sys/devices/system/cpu/cpu$core/cpufreq/scaling_available_frequencies"
            val line = exec.readFile(freqPath)
            val freqs = line.split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
            val idx = seen.indexOfFirst { it == freqs }
            if (idx >= 0) map[core] = idx else { seen.add(freqs); map[core] = seen.size - 1 }
        }
        // 簇号按出现顺序是 0..k-1;调用方按 freqs[0]/[1]/[2] 取前三个簇做 L/M/B。
        // 真实拓扑(小米 13 Ultra):cpu0-2 小、cpu3-6 中、cpu7 大 → 3 簇,映射恰好
        // 是 0(小)1(中)2(大)。若设备簇数更多,后面簇只进 perCore 不进 L/M/B 显示。
        return map
    }

    /** 探测逻辑核总数:列 /sys/devices/system/cpu/cpu[0-9]* 目录取最大核号+1 */
    private suspend fun probeCoreCount(): Int {
        val out = exec.execute("ls -d /sys/devices/system/cpu/cpu[0-9]* 2>/dev/null | sed 's/.*cpu//'")
        val maxCore = out.lines().mapNotNull { it.trim().toIntOrNull() }.maxOrNull() ?: 1
        return (maxCore + 1).coerceIn(1, Sample.MAX_CORES)
    }

    // ---- GPU ----
    /** GPU 一次读取结果 */
    private data class GpuRead(val freq: Int, val load: Float)

    /**
     * 读 GPU 频率/占用。
     *
     * 实机约束(小米 13 Ultra / Adreno 740,Android 16,shell 身份实测):
     *  - 频率节点全被 SELinux 拒:/sys/kernel/gpu 下的 gpu_clock、
     *    /sys/class/kgsl/kgsl-3d0 下的 gpuclk 与 devfreq 下的 cur_freq 一律
     *    Permission denied,无 root 读不到 → gpuFreq 保持 -1。
     *  - 占用:高通 kgsl 的 /sys/class/kgsl/kgsl-3d0/gpubusy **shell 可读**,
     *    输出 `busy total` 两个累计值,每次读取后计数器即复位;
     *    占用率 = busy/total × 100(实测游戏运行 ~15%,待机 ~0-2%,符合物理)。
     *    该节点每读一次就清零,是"当前窗口"计数,无需跨采样差分 —— 采样本身
     *    就在消耗 GPU 时间,比值自洽。
     *
     * @return (频率MHz, 占用率%);读不到的字段 -1
     */
    private suspend fun readGpu(): Pair<Int, Float> {
        // 一次 shell 读多个已知候选节点(cat 失败自动空)
        val probe = "freq=\$(cat /sys/kernel/gpu/gpu_clock 2>/dev/null | head -1); " +
            "kgslclk=\$(cat /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null | head -1); " +
            "dfclk=\$(cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq 2>/dev/null | head -1); " +
            "busy=\$(cat /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null | head -1); " +
            "echo \"F=\$freq K=\$kgslclk D=\$dfclk B=\$busy\""
        val out = exec.execute(probe)
        var freq = -1
        var load = -1f

        // 频率:任一源读到即用;单位统一换算到 MHz
        //  - /sys/kernel/gpu/gpu_clock:Hz(如 6xxx万级)
        //  - kgsl gpuclk:kHz(如 30xxxxx)
        //  - devfreq cur_freq:Hz
        val fRaw = Regex("F=([0-9]+)").find(out)?.groupValues?.get(1)?.toLongOrNull()
        val kRaw = Regex("K=([0-9]+)").find(out)?.groupValues?.get(1)?.toLongOrNull()
        val dRaw = Regex("D=([0-9]+)").find(out)?.groupValues?.get(1)?.toLongOrNull()
        val fVal = listOfNotNull(fRaw, kRaw, dRaw).firstOrNull { it != null && it > 0 }
        if (fVal != null) {
            freq = when {
                fVal >= 1_000_000 -> (fVal / 1000).toInt()      // Hz → MHz
                fVal >= 10_000 -> (fVal / 1000).toInt()          // kHz → MHz(kgsl)
                else -> fVal.toInt()                              // 已是 MHz(少见)
            }
        }

        // 占用:gpubusy "busy total"(每读复位,取比值)。注意 cat 输出常带前导空格,
        // 实际形如 "B= 652988 1022170",因此 B= 后可跟空白再取两个数。
        // 空闲时输出 "0 0"(busy=0,total=0)= GPU 完全没压力,应显示 0% 而非 --
        val m = Regex("B=\\s*(\\d+)\\s+(\\d+)").find(out)
        if (m != null) {
            val busy = m.groupValues[1].toLongOrNull() ?: 0L
            val total = m.groupValues[2].toLongOrNull() ?: 0L
            load = if (total > 0) {
                (busy.toDouble() / total * 100).toFloat().coerceIn(0f, 100f)
            } else {
                // total == 0:窗口内 GPU 无任何忙碌周期(空闲),视为 0%
                0f
            }
        }
        return freq to load
    }

    // ---- 电池 ----
    /** 电池一次读取结果 */
    private data class BatteryRead(
        val temp: Float, val currentMa: Int, val voltageMv: Int,
        val level: Int, val charge: Int, val powerMw: Long,
    )

    /**
     * 读电池温度/电量/电压/充电状态/电流。
     * 实机约束(务必记住):
     *  - 电池 sysfs current_now 对 shell 身份被拒,不能 cat;
     *  - 改走 BatteryManager.BATTERY_PROPERTY_CURRENT_NOW(应用可读,µA);
     *  - 温度/电量/电压/充电状态用 `dumpsys battery`(shell 可读,Scene 同款,稳定)。
     */
    private suspend fun readBattery(): BatteryRead {
        // 温度/电量/状态:优先 dumpsys battery(shell 可读,Scene 同款,稳定)
        var temp = -1f; var cur = -1; var volt = -1; var level = -1; var charge = 0
        try {
            val dump = exec.execute("dumpsys battery 2>/dev/null")
            var seenLevel = false; var seenTemp = false; var seenVolt = false
            dump.lines().forEach { line ->
                val t = line.trim()
                val idx = t.indexOf(':')
                if (idx > 0 && idx < t.length - 1) {
                    val key = t.substring(0, idx).trim()
                    val value = t.substring(idx + 1).trim()
                    when {
                        key == "level" && !seenLevel -> { level = value.toIntOrNull() ?: -1; seenLevel = true }
                        key == "temperature" && !seenTemp -> {
                            temp = (value.toFloatOrNull() ?: -1f) / 10f; seenTemp = true // dumpsys 温度是 0.1°C
                        }
                        key == "voltage" && !seenVolt -> { volt = value.toIntOrNull() ?: -1; seenVolt = true }
                        key == "AC powered" && value == "true" -> charge = 1
                        key == "USB powered" && value == "true" -> charge = 2
                        key == "Wireless powered" && value == "true" -> charge = 3
                    }
                }
            }
        } catch (e: Exception) { }
        // 电流:BatteryManager 属性(Scene 用同 API)
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            cur = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        } catch (e: Exception) { }
        // 电流单位换算:小米返回 µA;Scene 用 /1000 规则;放电为负时取反(统一放电为正)
        // (曾出现把 µA 当 mA 导致 4000W 功耗的换算 bug,改这里必测!)
        var currentMa = -1
        if (cur != Int.MIN_VALUE) {
            val sign = if (cur < 0) -1 else 1
            val absVal = kotlin.math.abs(cur)
            currentMa = when {
                absVal > 1_000_000 -> absVal / 1000  // nA -> mA
                absVal > 100_000 -> absVal / 1000     // µA -> mA
                absVal > 1_000 -> absVal / 1000       // µA(小值) -> mA
                else -> absVal                        // 已是 mA
            } * sign
        }
        val absCur = kotlin.math.abs(currentMa)
        // 功耗 mW = mA * mV / 1000
        var power = -1L
        if (absCur > 0 && volt > 0) power = absCur.toLong() * volt / 1000
        return BatteryRead(temp, absCur, volt, level, charge, power)
    }

    // ---- 温度 ----
    /** 热区一次读取结果(maxCpu=CPU类最高 / maxSoc=芯片非CPU区最高 / maxOther=外壳及其它) */
    private data class TempRead(val maxCpu: Float, val maxSoc: Float, val maxOther: Float)

    /**
     * 读温度。按 [DeviceProfile] 分派:
     *  - **ColorOS/OPPO 系优先 `dumpsys thermalservice`**:实测(一加平板 Pro, ColorOS
     *    16)输出 AOSP 标准 `Temperature{mValue=.., mType=.., mName=..}` 块,已带类型码
     *    (0=CPU 1=GPU 2=BATTERY 3=SKIN 9=NPU 8=socd)与 ℃ 单位,跨厂商无需逐 zone 名
     *    映射,还能拿到 GPU/NPU 温度。参考 FrameX ThermalServiceParser 的解析。
     *    失败(权限/命令超时/无数据)时退回 sysfs 遍历。
     *  - **HyperOS/其它走 sysfs 兜底**(CommandExecutor.readThermalZones,提权进程内
     *    JVM File 遍历,毫秒级),并扩展分类:ColorOS 的 zone 名(cpu-X-Y-Z/cpuss 归
     *    cpu;nsph/ mdmss/socd/ddr/video/gpuss 前缀归 soc;shell/ skin/batt 归 other)。
     *
     * 分类语义(UI 悬浮窗/CSV 消费):
     *  - maxCpuTemp  = CPU 类最高温
     *  - maxSocTemp  = "芯温":芯片上非 CPU 区最高(GPU/NPU/socd 等)
     *  - maxOtherTemp= 外壳/外设/未分类最高
     */
    private suspend fun readTemps(): TempRead {
        val profile = DeviceProfile.detect()
        // ColorOS 优先 thermalservice(标准化 HAL 温度,实测含每核 CPU/GPU/NPU/skin)
        if (profile.isColorOs) {
            val hal = readThermalHal()
            if (hal != null) return hal
            // HAL 不可用 → 落 sysfs(下方统一走,但 ColorOS zone 名不同,走扩展分类)
        }
        return readTempsSysfs()
    }

    /** 解析 `dumpsys thermalservice` 输出(参考 FrameX ThermalServiceParser) */
    private suspend fun readThermalHal(): TempRead? {
        val dump = try { exec.execute("dumpsys thermalservice 2>/dev/null") } catch (e: Exception) { return null }
        if (dump.isBlank() || !dump.contains("Temperature{")) return null
        // 只取 "Cached temperatures:" 之后的 Temperature{...} 块(避免 HAL 关闭时的空壳)
        val body = if (dump.contains("Cached temperatures:"))
            dump.substringAfter("Cached temperatures:") else dump

        var maxCpu = -1f; var maxSoc = -1f; var maxOther = -1f
        val block = Regex("""Temperature\{([^}]*)\}""")
        for (m in block.findAll(body)) {
            val s = m.groupValues[1]
            val vRaw = Regex("""mValue\s*=\s*(-?[0-9.]+)""").find(s)?.groupValues?.get(1)?.toFloatOrNull() ?: continue
            val type = Regex("""mType\s*=\s*(\d+)""").find(s)?.groupValues?.get(1)?.toIntOrNull()
            val name = Regex("""mName\s*=\s*([^,\s}]+)""").find(s)?.groupValues?.get(1).orEmpty().uppercase()
            // mValue 是 ℃(实测 CPU0=79.0);个别 OEM 可能给 m°C/0.1°C → 用 FrameX 式启发归一
            val c = if (kotlin.math.abs(vRaw) > 200f) vRaw / 1000f else vRaw
            if (c !in 0f..150f) continue // 过滤未接入的非法值
            val kind = when {
                // AOSP 类型码优先(0=CPU 1=GPU 2=BATTERY 3=SKIN 8=socd 9=NPU)
                type == 0 || (type == null && (name.contains("CPU") || name.contains("CLUSTER") || name.contains("CPUSS"))) -> "cpu"
                type == 1 || name.contains("GPU") -> "soc"        // GPU 归"芯片非CPU"
                type == 9 || name.contains("NPU") || name.contains("NSP") -> "soc"
                type == 8 || name.contains("SOCD") || name.contains("IBAT") || name.contains("VBAT") -> "soc"
                type == 3 || name.contains("SKIN") || name.contains("SHELL") || name.contains("QUIET") ||
                    name.contains("BACK") || name.contains("FRAME") -> "other"
                type == 2 || name.contains("BATTERY") || name.contains("BATT") -> "other" // 电池温度另取,dumpsys 更准
                type == null -> null
                // 其余类型码(4 camera/5 flashlight/6/7 ...)归 other,保持有值
                else -> "other"
            } ?: continue
            when (kind) {
                "cpu" -> if (c > maxCpu) maxCpu = c
                "soc" -> if (c > maxSoc) maxSoc = c
                "other" -> if (c > maxOther) maxOther = c
            }
        }
        return if (maxCpu > 0 || maxSoc > 0 || maxOther > 0) TempRead(maxCpu, maxSoc, maxOther) else null
    }

    /** sysfs thermal zone 遍历(提权进程内 File 遍历,见 CommandExecutor.readThermalZones) */
    private suspend fun readTempsSysfs(): TempRead {
        val out = exec.readThermalZones()
        var maxCpu = -1f; var maxSoc = -1f; var maxOther = -1f
        val colorOs = DeviceProfile.detect().isColorOs
        out.lines().forEach { line ->
            if (line.isBlank()) return@forEach
            val sp = line.indexOf(' ')
            if (sp <= 0) return@forEach
            val type = line.substring(0, sp)
            val v = line.substring(sp + 1).trim().toFloatOrNull() ?: return@forEach
            if (v <= -1000f) return@forEach // 未接入传感器的 zone,值为极大负值
            val c = v / 1000f // m°C → °C
            val t = type.lowercase()
            // zone 类型名归类。
            // HyperOS(小米,保持原已验证语义):cpu/cpuss 前缀 → cpu;soc/apc/aoss/tsens → soc;
            //   其余一律丢弃(maxOther 恒 -1,与阶段一~八实机一致)。
            // ColorOS(zone 命名不同):cpu-X-Y-Z/cpuss → cpu;gpuss(GPU)/nsph(NPU)/
            //   mdmss(基带)/socd/ddr/video → soc;shell/skin/batt 等外壳/外设 → other。
            val kind = if (colorOs) {
                when {
                    t.startsWith("cpu") || t.startsWith("cpuss") -> "cpu"
                    t.startsWith("gpuss") || t.startsWith("aoss") || t.startsWith("nsph") ||
                        t.startsWith("mdmss") || t.startsWith("socd") || t.startsWith("ddr") ||
                        t.startsWith("video") || t.contains("soc") || t.contains("apc") ||
                        t.contains("tsens") -> "soc"
                    t.startsWith("shell") || t.startsWith("skin") || t.startsWith("batt") ||
                        t.startsWith("xo") || t.startsWith("usb") || t.startsWith("pa") ||
                        t.startsWith("wlan") || t.startsWith("camera") || t.startsWith("rear") ||
                        t.startsWith("cam") || t.startsWith("vbat") || t.startsWith("ibat") ||
                        t.startsWith("pm8") || t.contains("therm") || t.contains("charger") ||
                        t.contains("svoc") -> "other"
                    else -> null
                }
            } else {
                when {
                    t.startsWith("cpu") || t.startsWith("cpuss") -> "cpu"
                    t.contains("soc") || t.contains("apc") ||
                        t.startsWith("aoss") || t.contains("tsens") -> "soc"
                    else -> null
                }
            } ?: return@forEach
            when (kind) {
                "cpu" -> if (c > maxCpu) maxCpu = c
                "soc" -> if (c > maxSoc) maxSoc = c
                "other" -> if (c > maxOther) maxOther = c
            }
        }
        return TempRead(maxCpu, maxSoc, maxOther)
    }
}
