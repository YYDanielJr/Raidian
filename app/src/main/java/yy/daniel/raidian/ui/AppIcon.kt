package yy.daniel.raidian.ui

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用图标(共享组件):Drawable → Bitmap → ImageBitmap 画成 [size] 圆角小方块。
 * 用于 AppPickActivity 应用列表行与主页"记录目标"行的 leading。
 * Bitmap 由 remember(drawable) 缓存,避免每帧重建。
 */
@Composable
fun AppIcon(drawable: Drawable?, size: Dp = 36.dp) {
    val density = LocalDensity.current
    val px = with(density) { size.toPx() }.toInt()
    val bmp = remember(drawable) {
        drawable?.let {
            try {
                val b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(b)
                it.setBounds(0, 0, px, px)
                it.draw(c)
                b
            } catch (e: Exception) { null }
        }
    }
    val img = bmp?.asImageBitmap()
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = null,
                modifier = Modifier.size(size),
            )
        }
    }
}

/**
 * 按包名取应用图标(在 IO 线程调用或 remember 缓存)。
 * 包不存在(应用已卸载)时返回 null,上层显示兜底方块。
 */
fun appIconOf(context: Context, pkg: String): Drawable? = try {
    context.packageManager.getApplicationIcon(pkg)
} catch (e: Exception) {
    null
}

/** 按包名取应用显示名;取不到返回包名本身 */
fun appLabelOf(context: Context, pkg: String): String = try {
    context.packageManager.getApplicationInfo(pkg, 0)
        .let { context.packageManager.getApplicationLabel(it)?.toString() }
        ?.takeIf { it.isNotBlank() } ?: pkg
} catch (e: Exception) {
    pkg
}
