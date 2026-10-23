package yy.daniel.raidian.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

data class ChartContext(
    val elapsedMs: List<Long> = emptyList(),
    val breakBefore: List<Boolean> = emptyList(),
    val source: String = "",
    val corePolicies: List<Int> = emptyList(),
    val coreMaxMhz: List<Int> = emptyList(),
    val perCoreFreq: List<List<Float?>> = emptyList(),
)

val LocalChartContext = staticCompositionLocalOf { ChartContext() }

/** 快照通过应用私有文件传递，避免长会话超过 Binder 大小限制，重建 Activity 时可重读。 */
object ChartSnapshots {
    private val json = Json { ignoreUnknownKeys = true }
    fun save(context: Context, document: ChartDocument): String {
        val dir = File(context.cacheDir, "chart-details").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        dir.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
        val name = "${UUID.randomUUID()}.json"
        File(dir, name).writeText(json.encodeToString(document))
        return name
    }

    fun read(context: Context, name: String): ChartDocument? = runCatching {
        require(name.matches(Regex("[a-f0-9-]{36}\\.json")))
        val file = File(File(context.cacheDir, "chart-details"), name)
        require(file.length() in 1..64_000_000)
        json.decodeFromString<ChartDocument>(file.readText())
    }.getOrNull()
}

@Composable
fun ChartPreview(document: ChartDocument, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    Box(Modifier.clickable(enabled = !opening, role = Role.Button, onClickLabel = "查看${document.title}详情") {
        opening = true
        scope.launch {
            try {
                val name = withContext(Dispatchers.IO) { ChartSnapshots.save(context, document) }
                context.startActivity(Intent(context, ChartDetailActivity::class.java).putExtra("chart_snapshot", name))
            } catch (_: java.io.IOException) {
                Toast.makeText(context, "曲线详情暂时无法打开，请检查可用存储空间", Toast.LENGTH_SHORT).show()
            } finally {
                opening = false
            }
        }
    }) { content() }
}

fun chartUnit(title: String): String = title.substringAfter('（', "").substringBefore('）').substringBefore('，')
