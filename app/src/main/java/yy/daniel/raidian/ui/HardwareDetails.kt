package yy.daniel.raidian.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import yy.daniel.raidian.monitor.DeviceProfile
import yy.daniel.raidian.monitor.Sample

private fun temperature(v: Float) = if (v >= 0) String.format("%.1f ℃", v) else "未提供"
private fun frequency(v: Int) = if (v > 0) "$v MHz" else "未提供"

@Composable
fun BackgroundSettingsRow() {
    val context = LocalContext.current
    HyperListGroup(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        HyperListRow(onClick = {
            context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:${context.packageName}")))
        }) {
            ListRowContent(title = "后台运行设置", summary = "持续记录时，请将省电策略设为无限制，并允许通知")
        }
    }
}

/** 首页保留实时概览，完整信息由独立 Activity 展示。 */
@Composable
fun HardwareDetails(sample: Sample) {
    val context = LocalContext.current
    SectionHeader("CPU 核心")
    HyperCard {
        CpuCoreOverview(sample)
        DetailEntryButton("查看 CPU 详情") {
            context.startActivity(Intent(context, CpuDetailsActivity::class.java))
        }
    }
    SectionHeader("温度与运行状态")
    HyperCard {
        RuntimeOverview(sample)
        RuntimeNotes(sample)
        DetailEntryButton("查看运行状态详情") {
            context.startActivity(Intent(context, RuntimeStatusActivity::class.java))
        }
    }
}

@Composable
private fun DetailEntryButton(text: String, onClick: () -> Unit) {
    Spacer(Modifier.height(20.dp))
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColorsPrimary(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CpuCoreOverview(sample: Sample) {
    val count = sample.perCoreFreqMhz.size
    if (count == 0) {
        HardwareNote("等待核心数据")
        return
    }
    val groups = sample.cpuPolicyIds.filter { it >= 0 }.distinct()
    Text("$count 核 · ${groups.size} 个频率簇", fontSize = 17.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(16.dp))
    CoreRow(listOf("核心", "频率", "占用", "温度"), heading = true)
    sample.perCoreFreqMhz.forEachIndexed { core, freq ->
        val load = sample.perCoreLoad.getOrNull(core) ?: -1f
        val online = sample.perCoreOnline.getOrNull(core)
        CoreRow(listOf(
            "CPU $core",
            if (online == 0) "离线" else if (freq > 0) "$freq MHz" else "--",
            if (online == 0) "--" else if (load >= 0) String.format("%.0f%%", load) else "--",
            temperature(sample.perCoreTemp.getOrNull(core) ?: -1f),
        ))
    }
    Spacer(Modifier.height(16.dp))
    HardwareNote("仅显示系统提供的独立核心温度，共享温区可在运行状态详情中查看。")
}

@Composable
fun CpuDetailsContent(sample: Sample) {
    SectionHeader("核心实时状态")
    HyperCard { CpuCoreOverview(sample) }
    SectionHeader("频率限制")
    val groups = sample.cpuPolicyIds.filter { it >= 0 }.distinct()
    if (groups.isEmpty()) {
        HyperCard { HardwareNote("等待频率簇数据") }
    } else {
        groups.forEach { policy ->
            val cores = sample.cpuPolicyIds.indices.filter { sample.cpuPolicyIds[it] == policy }
            val max = cores.maxOfOrNull { sample.perCoreMaxMhz.getOrNull(it) ?: -1 } ?: -1
            val limit = cores.maxOfOrNull { sample.perCoreLimitMhz.getOrNull(it) ?: -1 } ?: -1
            HyperCard {
                Text("CPU ${cores.joinToString("、")}", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(12.dp))
                HardwareInfoRow("当前允许上限", frequency(limit))
                HardwareInfoRow("硬件最高频率", frequency(max))
            }
        }
    }
}

@Composable
private fun RuntimeOverview(sample: Sample) {
    HardwareInfoRow("CPU 最高", temperature(sample.maxCpuTemp))
    HardwareInfoRow("GPU 温度", temperature(sample.gpuTemp))
    HardwareInfoRow("NPU 温度", temperature(sample.npuTemp))
    HardwareInfoRow("机身温度", temperature(sample.skinTemp))
    val thermal = listOf("正常", "轻度", "中度", "重度", "严重", "紧急", "关机保护")
    HardwareInfoRow("热状态", thermal.getOrNull(sample.thermalStatus) ?: "未提供")
    HardwareInfoRow("可用内存", if (sample.availableMemMb >= 0) "${sample.availableMemMb} MB" else "--")
    HardwareInfoRow("交换区已用", if (sample.swapUsedMb >= 0) "${sample.swapUsedMb} MB" else "--")
    HardwareInfoRow("采集状态", if (sample.privileged) "ADB 或 root · ${sample.sampleDurationMs} ms" else "基础采集，等待 ADB 或 root 授权")
}

@Composable
private fun RuntimeNotes(sample: Sample) {
    if (sample.chargeState > 0) {
        Spacer(Modifier.height(12.dp))
        HardwareNote("充电时显示电池端净功率，不能据此计算整机功耗。")
    }
    if (DeviceProfile.detect().isXring && (sample.gpuFreq < 0 || sample.gpuLoad < 0)) {
        Spacer(Modifier.height(12.dp))
        HardwareNote("当前系统未开放 GPU 频率或占用接口，未读取到的项目显示为 --。")
    }
}

@Composable
fun RuntimeStatusContent(sample: Sample) {
    SectionHeader("温度与运行状态")
    HyperCard {
        RuntimeOverview(sample)
        RuntimeNotes(sample)
    }
    SectionHeader("温度传感器 · ${sample.thermalReadings.size} 个")
    if (sample.thermalReadings.isEmpty()) {
        HyperCard { HardwareNote("暂无可用的温度传感器数据") }
    } else {
        sample.thermalReadings.groupBy { it.kind }.toSortedMap().forEach { (kind, sensors) ->
            HyperCard {
                val title = when (kind) {
                    "cpu" -> "CPU 温区"
                    "gpu" -> "GPU 温区"
                    "npu" -> "NPU 温区"
                    "battery" -> "电池温区"
                    "skin" -> "机身温区"
                    "soc" -> "SoC 温区"
                    else -> "其他温区"
                }
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(12.dp))
                sensors.sortedBy { it.name }.forEach { sensor ->
                    HardwareInfoRow(sensor.name, temperature(sensor.celsius))
                }
            }
        }
    }
}

@Composable
private fun HardwareNote(text: String) {
    Text(text, fontSize = 14.sp, lineHeight = 22.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun HardwareInfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp, lineHeight = 22.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, modifier = Modifier.weight(1f), fontSize = 16.sp, lineHeight = 24.sp,
            fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun CoreRow(values: List<String>, heading: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        values.forEachIndexed { index, value ->
            Text(value, modifier = Modifier.weight(if (index == 1) 1.35f else 1f),
                fontSize = if (heading) 12.sp else 14.sp,
                color = if (heading) MiuixTheme.colorScheme.onSurfaceVariantSummary else MiuixTheme.colorScheme.onSurface)
        }
    }
}
