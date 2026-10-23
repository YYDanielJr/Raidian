package yy.daniel.raidian.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
import yy.daniel.raidian.RaidianApp
import yy.daniel.raidian.battery.BatteryCurveStore
import yy.daniel.raidian.battery.UidInfoResolver
import java.io.File

/**
 * 历史「放电周期」详情页(独立 Activity)。
 *
 * 从续航页的历史周期列表进入,展示该周期的元数据、电量/功耗曲线与逐应用耗电排行。
 * 数据全部从周期目录里的 CSV 现读,不依赖内存状态,因此历史周期也能完整回看。
 */
class BatteryCycleDetailActivity : ComponentActivity() {

    companion object {
        /** 周期目录绝对路径 */
        const val EXTRA_DIR = "dir"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dir = intent.getStringExtra(EXTRA_DIR).orEmpty()
        setContent {
            MiuixTheme {
                CycleDetailScreen(dirPath = dir, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun CycleDetailScreen(dirPath: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var meta by remember { mutableStateOf<BatteryCurveStore.CycleMeta?>(null) }
    var drain by remember { mutableStateOf<List<BatteryCurveStore.DrainPoint>>(emptyList()) }
    // (uid, (应用名, 包名, 累计 mAh))
    var apps by remember { mutableStateOf<List<Pair<Int, Triple<String, String, Double>>>>(emptyList()) }

    LaunchedEffect(dirPath) {
        if (dirPath.isBlank()) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            val store = RaidianApp.get(context).batteryCurves.store
            val resolver = UidInfoResolver(context)
            val dir = File(dirPath)
            // 逐应用:apps.csv 是长表,取每个 uid 的最后一条(即本周期末尾的累计值)
            val lastPerUid = store.readApps(dir).groupBy { it.uid }
                .mapValues { (_, v) -> v.maxByOrNull { it.ts }!! }
            val appList = lastPerUid.values
                .filter { it.mah > 0 }
                .sortedByDescending { it.mah }
                .map { a ->
                    val label = if (a.label.isNotBlank()) a.label else resolver.resolve(a.uid).label
                    a.uid to Triple(label, a.pkg, a.mah)
                }
            Triple(store.readCycleMeta(dir), store.readDrain(dir), appList)
        }
        meta = loaded.first
        drain = loaded.second
        apps = loaded.third
    }

    Box(modifier = modifier.fillMaxSize()) {
        SubPageScaffold(
            title = "周期详情",
            subtitle = fmtDateTime(meta?.startTs ?: 0L),
            onBack = { (context as? android.app.Activity)?.finish() },
            content = { padding ->
                Box(modifier = Modifier.fillMaxSize()) {
                    CycleDetailContent(
                        meta = meta,
                        drain = drain,
                        apps = apps,
                        dirPath = dirPath,
                        contentPadding = padding,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            },
        )
    }
}
