package yy.daniel.raidian.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Miuix 模糊表面的公共参数。
 *
 * miuix-blur 的 RuntimeShader 路径要求 Android 13（API 33）以上。项目当前已将 minSdk
 * 对齐到 33；这里仍保留能力检查，方便预览环境以及未来调整最低版本时自动回退。
 */
internal fun isMiuixBlurSupported(): Boolean = isRuntimeShaderSupported()

/**
 * 捕获栏后方的页面内容。显式先画不透明底色，避免透明区域在模糊采样时产生色块。
 */
@Composable
internal fun rememberUiBackdrop(
    backgroundColor: Color = MiuixTheme.colorScheme.surface,
): LayerBackdrop = rememberLayerBackdrop {
    drawRect(backgroundColor)
    drawContent()
}

/** 展开/折叠标题栏使用的矩形磨砂层。 */
@Composable
internal fun Modifier.miuixTopBarBlur(backdrop: LayerBackdrop): Modifier {
    if (!isMiuixBlurSupported()) return this
    val tint = MiuixTheme.colorScheme.surface.copy(alpha = 0.70f)
    val colors = BlurDefaults.blurColors(
        blendColors = listOf(BlendColorEntry(tint, BlurBlendMode.SrcOver)),
        contrast = 1.03f,
        saturation = 1.10f,
    )
    return textureBlur(
        backdrop = backdrop,
        shape = RectangleShape,
        blurRadius = 32f,
        colors = colors,
    )
}

/**
 * 底部导航使用的磨砂层。浮动导航传入胶囊形状，贴底导航传入矩形即可。
 */
@Composable
internal fun Modifier.miuixNavigationBlur(
    backdrop: LayerBackdrop,
    shape: Shape,
    tintAlpha: Float = 0.58f,
): Modifier {
    if (!isMiuixBlurSupported()) return this
    val tint = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = tintAlpha)
    val colors = BlurDefaults.blurColors(
        blendColors = listOf(BlendColorEntry(tint, BlurBlendMode.SrcOver)),
        contrast = 1.04f,
        saturation = 1.14f,
    )
    return textureBlur(
        backdrop = backdrop,
        shape = shape,
        blurRadius = 36f,
        colors = colors,
    )
}
