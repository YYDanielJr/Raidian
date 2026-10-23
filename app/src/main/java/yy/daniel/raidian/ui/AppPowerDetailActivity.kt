package yy.daniel.raidian.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
import yy.daniel.raidian.RaidianApp
import yy.daniel.raidian.battery.BatteryCurveStore
import yy.daniel.raidian.battery.UidInfoResolver
import java.io.File

/**
 * 单应用耗电详情页(独立 Activity):展示某个应用在指定放电周期内的
 * 累计耗电、占比、平均功耗,以及**该应用的功耗曲线**。
 *
 * 功耗曲线的来源:后端每 5 分钟把系统逐 UID 累计 mAh 快照写进 apps.csv;
 * 相邻两个快照求差(ΔmAh ÷ Δt × 平均电压)即得该应用在这 5 分钟窗口内的平均功耗 mW
 * (见 EnduranceRecorder.appPowerSeries)。这样即使系统只给累计值,也能还原出曲线。
 */
class AppPowerDetailActivity : ComponentActivity() {

    companion object {
        /** 周期目录绝对路径 */
        const val EXTRA_DIR = "dir"
        /** 目标应用 uid */
        const val EXTRA_UID = "uid"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dir = intent.getStringExtra(EXTRA_DIR).orEmpty()
        val uid = intent.getIntExtra(EXTRA_UID, -1)
        setContent {
            MiuixTheme {
                AppPowerDetailScreen(dirPath = dir, uid = uid, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun AppPowerDetailScreen(dirPath: String, uid: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var points by remember { mutableStateOf<List<Pair<Long, Float>>>(emptyList()) }
    var totalMah by remember { mutableStateOf(-1.0) }
    var label by remember { mutableStateOf("") }
    var pkg by remember { mutableStateOf("") }
    var cycleStart by remember { mutableStateOf(0L) }
    var sampleCount by remember { mutableStateOf(0) }

    LaunchedEffect(dirPath, uid) {
        if (dirPath.isBlank() || uid < 0) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            val app = RaidianApp.get(context)
            val store = app.batteryCurves.store
            val dir = File(dirPath)
            val resolver = UidInfoResolver(context)
            val all = store.readApps(dir).filter { it.uid == uid }.sortedBy { it.ts }
            val last = all.lastOrNull()
            val info = resolver.resolve(uid)
            Loaded(
                points = app.batteryCurves.endurance.appPowerSeries(dir, uid),
                totalMah = last?.mah ?: -1.0,
                label = last?.label?.takeIf { it.isNotBlank() } ?: info.label,
                pkg = last?.pkg ?: info.pkg,
                cycleStart = store.readCycleMeta(dir)?.startTs ?: (all.firstOrNull()?.ts ?: 0L),
                sampleCount = all.size,
            )
        }
        points = loaded.points
        totalMah = loaded.totalMah
        label = loaded.label
        pkg = loaded.pkg
        cycleStart = loaded.cycleStart
        sampleCount = loaded.sampleCount
    }

    Box(modifier = modifier.fillMaxSize()) {
        SubPageScaffold(
            title = label.ifBlank { "应用耗电详情" },
            subtitle = pkg,
            onBack = { (context as? android.app.Activity)?.finish() },
            content = { padding ->
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(padding),
                    ) {
                        SectionHeader("本周期耗电")
                        HyperCard {
                            StatGrid(
                                listOf(
                                    "累计耗电" to fmtMah(totalMah),
                                    "功耗样本" to "$sampleCount 个（5 分钟一个）",
                                )
                            )
                            InfoBlock(
                                listOf(
                                    "包名" to pkg.ifBlank { "--" },
                                    "UID" to "$uid",
                                    "周期起点" to fmtDateTime(cycleStart),
                                )
                            )
                        }

                        SectionHeader("功耗曲线")
                        HyperCard {
                            if (points.size < 2) {
                                EmptyHint(
                                    "暂无功耗曲线：需要至少两个系统统计快照（每 5 分钟一个），" +
                                        "且该应用在本周期有耗电记录。"
                                )
                            } else {
                                val values = remember(points) { points.map { it.second } }
                                val xLabels = remember(points, cycleStart) {
                                    relativeAxisLabels(points.map { it.first }, cycleStart)
                                }
                                DetailChart(
                                    title = "功耗（mW，5 分钟窗口均值）",
                                    values = values,
                                    minY = 0f,
                                    maxY = null,
                                    color = ChartColors.AppPower,
                                    xLabels = xLabels,
                                    elapsedMs = points.map { it.first - cycleStart },
                                )
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            },
        )
    }
}

/** 详情页一次性读出的数据(避免多个 var 分步赋值时闪烁) */
private data class Loaded(
    val points: List<Pair<Long, Float>>,
    val totalMah: Double,
    val label: String,
    val pkg: String,
    val cycleStart: Long,
    val sampleCount: Int,
)
