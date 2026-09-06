package yy.daniel.raidian.monitor

/**
 * 跨应用 FPS 读取:经命令通道(shell/Shizuku 身份)读 SurfaceFlinger。
 *
 * 算法(FPS-Meter-Android / mkm 同思路,详见 references/scene-alternatives):
 *  1. 用 `dumpsys SurfaceFlinger --list` 找前台应用对应的可渲染 layer;
 *  2. 对候选 layer 逐个执行 `dumpsys SurfaceFlinger --latency <layer>` 试出有
 *     真实帧数据的那个(游戏常是 SurfaceView(BLAST),需完整层名含 hash 前缀);
 *  3. 帧时间戳取每行第 2 列(actual present 时间,帧真正上屏时刻)—— 这是
 *     mkm / Scene 一系实现信任的列;第 1 列 desired、第 3 列 ready 不可靠;
 *  4. 缓冲区内 (帧数-1)/时间跨度 → FPS。
 *
 * 实机踩坑(小米 13 Ultra,Android 16,游戏 com.hypergryph.endfield 实测):
 *  - `--latency` 必须传"hash 前缀 + 完整层名"形态,如
 *    `b85e336 SurfaceView[com.hypergryph.endfield/com.u8.sdk.U8UnityContext](BLAST)#19010`;
 *    若传 `--list` 的整行 / "Output Layer 0xb4...(...)" 前缀形态,SF 只回
 *    1 行 refresh period(8333333),没有帧数据 → 误判"无渲染层"(本项目原 bug)。
 *  - 层名里的 `#19010` 序号会随 Activity/Surface 重建变化,所以层失效后要重新
 *    解析;每个采样周期解析会拖慢节奏,失败后按 [RETRY_DELAY_MS] 退避。
 *
 * 权限:实测 `dumpsys SurfaceFlinger` 在 shell 身份可读,只要 Shizuku 授权即可,
 * 无需 root(对比:`service call SurfaceFlinger 1013` 被拒)。
 */
class SurfaceFlingerFpsReader(
    private val exec: CommandExecutor,
    private val appState: AppStateReader,
) : FpsReader {

    companion object {
        /** 解析/测量失败后的重试间隔:期间内直接返回 -1,不再跑慢命令 */
        private const val RETRY_DELAY_MS = 3000L
        /** 至少多少条真实帧数据行才算 --latency 有内容(防 SoloX#303 式退化输出) */
        private const val MIN_REAL_ROWS = 3
        /** 帧时间戳若晚于当前时间这么久,认为应用已停止渲染 */
        private const val STALE_FRAME_NS = 1_500_000_000L // 1.5s
        /** FPS 物理合理区间过滤 */
        private const val MAX_FPS = 240f
    }

    /** 当前前台包名对应的候选层名列表(已过滤非渲染层并按优先级排序) */
    @Volatile private var targetLayers: List<String> = emptyList()
    /** 最近一次解析层时对应的前台包名 */
    @Volatile private var lastPkg = ""
    /** 被 SELinux 权限拒绝后置 true,此后一直返回 -1,避免反复打日志 */
    @Volatile private var disabled = false
    /** 下次允许重新解析层的时间戳(退避用) */
    @Volatile private var nextResolveAtMs = 0L

    /** 通用 FpsReader 接口实现:内部自行查询前台应用 */
    override fun readFps(): Float {
        if (disabled) return -1f
        return try {
            val (pkg, _) = kotlinx.coroutines.runBlocking { appState.readFrontApp() }
            readFpsForPkg(pkg)
        } catch (e: Exception) {
            if (isPermissionDenied(e)) disabled = true
            -1f
        }
    }

    /**
     * 已知前台包名时直接读(省一次前台查询,采样主路径走这个);pkg 为空则不做。
     *
     * 返回语义(与 -1/0 的区分,2026-09-06 实机校准):
     *  - **>0** 有效帧率
     *  - **0**  该前台应用确实有渲染层、但当前没有新帧(画面静止 / 层在但不提交帧 /
     *           UI 缓冲全 0)。悬浮窗/实时页应显示 0,而非 "--" —— 这是真实"没在出帧"。
     *  - **-1** 读不到:没有解析到任何渲染层(前台是纯无渲染场景)或权限被拒。
     */
    fun readFpsForPkg(pkg: String): Float {
        if (disabled) return -1f
        return try {
            if (pkg.isNotEmpty() && pkg != lastPkg) {
                // 前台应用变化:清缓存,立即重新解析
                lastPkg = pkg
                targetLayers = emptyList()
                nextResolveAtMs = 0L
            }
            if (pkg.isEmpty()) return -1f

            // 1) 无候选 → 解析(受退避控制)
            if (targetLayers.isEmpty()) {
                val now = System.currentTimeMillis()
                if (now < nextResolveAtMs) return -1f
                targetLayers = resolveLayers(pkg)
                if (targetLayers.isEmpty()) {
                    nextResolveAtMs = now + RETRY_DELAY_MS
                    return -1f
                }
            }

            // 2) 对候选逐个试 latency:
            //    - 返回 >0 → 有效帧率,直接用
            //    - 返回 0  → 该层有效但无新帧(静止),记录之
            //    - 返回 null → 该层无效(名字对不上/退化输出),试下一个
            var sawActiveNoFrame = false
            for (layer in targetLayers) {
                val fps = measureByLatency(layer)
                if (fps != null) {
                    if (fps > 0f) return fps
                    // fps == 0:层在,但此刻没出帧
                    sawActiveNoFrame = true
                }
            }
            if (sawActiveNoFrame) return 0f
            // 全部候选都"层无效":可能层已重建(序号变了),清掉重解析一次(受退避保护)
            targetLayers = emptyList()
            nextResolveAtMs = System.currentTimeMillis() + RETRY_DELAY_MS
            -1f
        } catch (e: Exception) {
            if (isPermissionDenied(e)) disabled = true
            -1f
        }
    }

    /** 权限拒绝类异常识别(SELinux denied / Permission denied),命中则禁用本读取器 */
    private fun isPermissionDenied(e: Exception): Boolean =
        e.message?.contains("Permission", ignoreCase = true) == true ||
            e.message?.contains("denied", ignoreCase = true) == true

    /**
     * 解析前台应用的可渲染 layer 候选名(按优先级排序)。
     *
     * 2026-09-06 实机修正(关键):必须优先用全量 dumpsys 里的 **"Output Layer"**
     * 行 —— 那才是 SurfaceFlinger 当前真正合成上屏的**活跃层**。`--list` 会列出
     * 同包的所有层(含已不活跃的旧层),旧层 `--latency` 缓冲恒为全 0,若先试到
     * 它会把静止/活跃误判成 0 并卡死(实测 launcher 有 #74 旧层全 0 与 #21751
     * 活跃层有 125 个真实时间戳)。因此 Output Layer 层必须排最前。
     *
     * 候选排序(rankCandidates 再细化):Output Layer 活跃层 > SurfaceView+BLAST > 普通层。
     */
    private fun resolveLayers(pkg: String): List<String> {
        if (pkg.isEmpty()) return emptyList()
        val ordered = mutableListOf<String>()

        // 方法1(主):全量 dumpsys 的 "Output Layer <name>" —— 当前活跃合成层
        val out1 = run("dumpsys SurfaceFlinger 2>/dev/null | grep -E 'Output Layer.*${Regex.escape(pkg)}'")
        for (rawLine in out1.lines()) {
            val name = cleanLayerName(rawLine) ?: continue
            if (isRenderableLayer(name, pkg)) ordered.add(name)
        }

        // 方法2(补):--list 里所有含包名层(Output Layer 之外的候选也收,去重)
        val listOut = run("dumpsys SurfaceFlinger --list 2>/dev/null")
        for (rawLine in listOut.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || !line.contains(pkg)) continue
            val name = cleanLayerName(line) ?: continue
            if (isRenderableLayer(name, pkg) && name !in ordered) ordered.add(name)
        }
        return rankCandidates(ordered)
    }

    /**
     * 从 dumpsys 行里抽出可传给 `--latency` 的层名。
     * 兼容形态:
     *  - `RequestedLayerState{<name> parentId=...}` → <name>
     *  - `Output Layer 0xb4...(b85e336 SurfaceView[...](BLAST)#19010)` → 括号内名字
     *  - 裸名行 → 原样
     * 关键:必须保留 hash 前缀(`b85e336 `),`--latency` 只认这种全名;
     * 不能把 "RequestedLayerState{" 前缀 / "0xb4...(" 地址前缀 / " parentId=..." 带进去。
     */
    private fun cleanLayerName(line: String): String? {
        var s = line.trim()
        if (s.isEmpty()) return null

        // Output Layer 形态:取括号内(若有)
        if (s.startsWith("Output Layer")) {
            val open = s.indexOf('(')
            val close = s.lastIndexOf(')')
            if (open >= 0 && close > open) s = s.substring(open + 1, close).trim()
            else {
                // 无括号:去掉 "Output Layer" 前缀
                s = s.removePrefix("Output Layer").trim()
            }
        }
        // RequestedLayerState{...} 形态:取花括号内、截掉父级/层级字段
        if (s.startsWith("RequestedLayerState{")) {
            s = s.removePrefix("RequestedLayerState{")
            s = s.substringBefore(" parentId=")
                .substringBefore(" relativeParentId=")
                .substringBefore(" z=")
                .removeSuffix("}")
                .trim()
        }
        if (s.isEmpty()) return null
        // 仍带着 {..} 包裹(老格式裸名含 Token 括号):退化取首 token 后原样
        return s
    }

    /** 过滤非渲染层(ActivityRecord/Bounds/Background/Dim/InputSink 等) */
    private fun isRenderableLayer(name: String, pkg: String): Boolean {
        if (name.isEmpty() || !name.contains(pkg)) return false
        if (name.contains("ActivityRecord") ||
            name.contains("ActivityRecordInputSink") ||
            name.contains("Bounds for") ||
            name.contains("Background for") ||
            name.contains("Dim layer") ||
            name.contains("SnapshotStartingWindow") ||
            name.contains("Transition") ||
            name.contains("leash") ||
            name.contains("Task=") ||
            name.startsWith("Task=")
        ) return false
        return true
    }

    /** 候选排序:SurfaceView+BLAST 优先,其次 SurfaceView,再次普通层;同权保留多个 */
    private fun rankCandidates(cands: List<String>): List<String> {
        if (cands.size <= 1) return cands
        val blast = cands.filter { it.contains("SurfaceView", true) && it.contains("BLAST", true) }
        val sv = cands.filter { it.contains("SurfaceView", true) && !it.contains("BLAST", true) }
        val other = cands.filter { !it.contains("SurfaceView", true) }
        val ordered = (blast + sv + other).distinct()
        // 至多留 4 个,避免逐候选 dumpsys 太多次
        return ordered.take(4)
    }

    /**
     * 用 `--latency <layer>` 的帧时间戳算 FPS。
     * 输出:首行为 refresh period(ns),后续每行 "desired actual ready ...",
     * 取 actual present(第 2 列,mkm/Scene 系信任列);列 2 无效时退用列 1。
     *
     * 返回语义:
     *  - Float > 0:有效帧率
     *  - 0f:该层有效(名字匹配、有帧记录行),但缓冲无任何真实时间戳
     *    (全 0 / 只有陈旧帧)—— 层活着但当前没在出帧,应视为 0 FPS
     *  - null:该层无效 —— SF 只回 1 行 refresh(层名不匹配)或退化输出,
     *    由调用方换候选/重解析
     */
    private fun measureByLatency(layer: String): Float? {
        val out = run("dumpsys SurfaceFlinger --latency '$layer' 2>/dev/null")
        val timestamps = mutableListOf<Long>()
        // 帧记录行数:首行 refresh 之外的行(含全 0 行),用于判断层是否有效
        var recordRows = 0
        out.lines().forEachIndexed { i, line ->
            if (i == 0) return@forEachIndexed // refresh period 头
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2) {
                recordRows++
                val actual = parts[1].toLongOrNull()
                val desired = parts[0].toLongOrNull()
                val ts = when {
                    actual != null && actual > 0 && actual != Long.MAX_VALUE -> actual
                    desired != null && desired > 0 && desired != Long.MAX_VALUE -> desired
                    else -> null
                }
                if (ts != null) timestamps.add(ts)
            }
        }
        // 层名不匹配 / Android 15/16 退化输出:SF 只回 1 行 refresh,无任何记录行
        if (recordRows == 0) return null

        // 层有效(有帧记录行)但没有任何真实时间戳(全 0)= 静止、没在出帧 → 0 FPS
        if (timestamps.size < MIN_REAL_ROWS) return 0f

        timestamps.sort()
        val maxTs = timestamps.last()
        val minTs = timestamps.first()
        if (maxTs <= minTs) return 0f

        // 新鲜度:最新帧若远早于当前(monotonic ns),说明该层最近没渲染 → 0 FPS
        val nowNs = System.nanoTime()
        if (nowNs - maxTs > STALE_FRAME_NS) return 0f

        // 窗口内平均帧间隔(ns/帧)
        val avgIntervalNs = (maxTs - minTs).toDouble() / (timestamps.size - 1)
        val fps = if (avgIntervalNs > 0) (1e9 / avgIntervalNs).toFloat() else -1f
        return if (fps in 1f..MAX_FPS) fps else null
    }

    /** 经命令通道执行一条 shell;任何失败返回空串(上层按"读不到"处理) */
    private fun run(cmd: String): String {
        return try {
            kotlinx.coroutines.runBlocking { exec.execute(cmd) }
        } catch (e: Exception) {
            ""
        }
    }
}
