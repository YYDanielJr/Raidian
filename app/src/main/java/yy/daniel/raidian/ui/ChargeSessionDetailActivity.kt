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
import java.io.File

/**
 * 历史「充电会话」详情页(独立 Activity)。
 *
 * 展示一次充电的完整曲线(电量 % + 充电功率 W)、会话统计,并与**上一次充电**做对比
 * (用时与平均功率的变化),便于观察充电速度是否退化。
 */
class ChargeSessionDetailActivity : ComponentActivity() {

    companion object {
        /** 充电会话目录绝对路径 */
        const val EXTRA_DIR = "dir"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dir = intent.getStringExtra(EXTRA_DIR).orEmpty()
        setContent {
            MiuixTheme {
                ChargeSessionDetailScreen(dirPath = dir, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** 详情页一次性读出的数据 */
private data class ChargeLoaded(
    val meta: BatteryCurveStore.ChargeMeta?,
    val points: List<BatteryCurveStore.ChargePoint>,
    val prev: BatteryCurveStore.ChargeMeta?,
)

@Composable
private fun ChargeSessionDetailScreen(dirPath: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var meta by remember { mutableStateOf<BatteryCurveStore.ChargeMeta?>(null) }
    var points by remember { mutableStateOf<List<BatteryCurveStore.ChargePoint>>(emptyList()) }
    var prev by remember { mutableStateOf<BatteryCurveStore.ChargeMeta?>(null) }

    LaunchedEffect(dirPath) {
        if (dirPath.isBlank()) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            val store = RaidianApp.get(context).batteryCurves.store
            val dir = File(dirPath)
            // 上一次充电:历史列表按目录名(时间)降序,取当前目录的下一条
            val dirs = store.listCharges()
            val idx = dirs.indexOfFirst { it.absolutePath == dirPath }
            val prevDir = if (idx >= 0) dirs.getOrNull(idx + 1) else null
            ChargeLoaded(
                meta = store.readChargeMeta(dir),
                points = store.readCharge(dir),
                prev = prevDir?.let { store.readChargeMeta(it) },
            )
        }
        meta = loaded.meta
        points = loaded.points
        prev = loaded.prev
    }

    Box(modifier = modifier.fillMaxSize()) {
        SubPageScaffold(
            title = "充电详情",
            subtitle = fmtDateTime(meta?.startTs ?: 0L),
            onBack = { (context as? android.app.Activity)?.finish() },
            content = { padding ->
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(padding),
                    ) {
                        SectionHeader("本次充电")
                        HyperCard {
                            StatGrid(
                                listOf(
                                    "充入电量" to "${meta?.startLevel ?: -1}% → ${meta?.endLevel ?: -1}%",
                                    "用时" to fmtDuration(meta?.durationMs ?: -1L),
                                    "平均功率" to fmtPower(meta?.avgPowerMw ?: -1.0),
                                    "峰值功率" to fmtPower(meta?.peakPowerMw ?: -1L),
                                    "充入能量" to fmtEnergy(meta?.energyMwh ?: -1.0),
                                    "最高温度" to if ((meta?.maxTempC ?: -1f) > 0)
                                        String.format("%.1f°C", meta!!.maxTempC) else "--",
                                )
                            )
                            InfoBlock(
                                listOf(
                                    "开始" to fmtDateTimeSec(meta?.startTs ?: 0L),
                                    "结束" to if ((meta?.endTs ?: 0L) > 0) fmtDateTimeSec(meta!!.endTs) else "进行中",
                                    "样本数" to "${meta?.sampleCount ?: 0}",
                                    "充入容量" to if ((meta?.chargedMah ?: -1) > 0) "${meta!!.chargedMah} mAh" else "--",
                                    "是否充满" to if (meta?.reachedFull == true) "是" else "否（拔电结束）",
                                )
                            )
                        }

                        // 用局部 val 承接(委托属性 prev 无法智能转换)
                        val p = prev
                        if (p != null) {
                            SectionHeader("与上次充电对比")
                            HyperCard {
                                val dDur = (meta?.durationMs ?: 0L) - p.durationMs
                                val dAvg = (meta?.avgPowerMw ?: -1.0) - p.avgPowerMw
                                InfoBlock(
                                    listOf(
                                        "上次" to (
                                            "${fmtDateTime(p.startTs)} · " +
                                                "${p.startLevel}%→${p.endLevel}% · " +
                                                fmtDuration(p.durationMs)
                                            ),
                                        "用时变化" to if (dDur == 0L) "--" else
                                            (if (dDur > 0) "+" else "-") + fmtDuration(kotlin.math.abs(dDur)),
                                        "平均功率变化" to if (dAvg.isNaN()) "--" else
                                            String.format("%+.0f mW", dAvg),
                                    )
                                )
                            }
                        }

                        SectionHeader("曲线")
                        HyperCard {
                            if (points.size < 2) {
                                EmptyHint("该充电会话曲线数据不足。")
                            } else {
                                val levelSeries = remember(points) {
                                    points.map { if (it.level in 0..100) it.level.toFloat() else null }
                                }
                                val powerSeries = remember(points) {
                                    points.map { if (it.powerMw > 0) it.powerMw / 1000f else null }
                                }
                                val xLabels = remember(points, meta) {
                                    relativeAxisLabels(
                                        points.map { it.ts },
                                        meta?.startTs ?: (points.firstOrNull()?.ts ?: 0L)
                                    )
                                }
                                DetailChart(
                                    title = "电量（%）",
                                    values = levelSeries,
                                    minY = 0f,
                                    maxY = 100f,
                                    color = ChartColors.BatteryLevel,
                                    xLabels = xLabels,
                                    elapsedMs = points.map { it.ts - (meta?.startTs ?: points.first().ts) },
                                )
                                Spacer(Modifier.height(12.dp))
                                DetailChart(
                                    title = "充电功率（W）",
                                    values = powerSeries,
                                    minY = 0f,
                                    maxY = null,
                                    color = ChartColors.Charge,
                                    xLabels = xLabels,
                                    elapsedMs = points.map { it.ts - (meta?.startTs ?: points.first().ts) },
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
