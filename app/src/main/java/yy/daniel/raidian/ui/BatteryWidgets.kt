package yy.daniel.raidian.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 续航页 / 充电页共用的小块组件(本次新增的两个曲线页专用,不改动既有页面)。
 */

/** 两列数值网格(数值块复用实时页的 [DataCell],观感一致) */
@Composable
fun StatGrid(items: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        items.chunked(2).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                row.forEach { (label, value) ->
                    DataCell(label = label, value = value, modifier = Modifier.weight(1f))
                }
                // 奇数项补空位,保持左对齐两列
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 一列"标签 : 值"信息行(卡片内,复用详情页的 [InfoRow] 排版) */
@Composable
fun InfoBlock(items: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        items.forEach { (label, value) -> InfoRow(label, value) }
    }
}

/** 卡片内的灰色说明/空态文字 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
