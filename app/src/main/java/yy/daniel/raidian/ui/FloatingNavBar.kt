package yy.daniel.raidian.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.basic.FloatingToolbarDefaults
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar as MiuixFloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * HyperOS 悬浮导航栏。
 *
 * 直接复用 Miuix 0.9.4 Demo 的 [MiuixFloatingNavigationBar] 与
 * [FloatingNavigationBarItem],不额外绘制文字或选中底块。组件本身提供：
 * 28dp 图标、选中深色/未选中 40% 透明度、squircle 胶囊、HyperOS 阴影以及
 * 手势导航区安全距离；这与小米相册等第一方应用的纯图标悬浮栏一致。
 *
 * 作为页面根 Box 的最后一层覆盖使用；不要放进 Scaffold.bottomBar，否则正文会为它让位。
 * [NavEntry.label] 只作为无障碍描述，不在界面中显示。
 */
@Composable
fun FloatingNavBar(
    items: List<NavEntry>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
) {
    val blurSupported = isMiuixBlurSupported()
    val cornerRadius = FloatingToolbarDefaults.CornerRadius
    MiuixFloatingNavigationBar(
        modifier = modifier.miuixNavigationBlur(
            backdrop = backdrop,
            shape = RoundedCornerShape(cornerRadius),
        ),
        color = if (blurSupported) Color.Transparent else MiuixTheme.colorScheme.surfaceContainer,
        cornerRadius = cornerRadius,
    ) {
        items.forEachIndexed { index, entry ->
            FloatingNavigationBarItem(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                icon = entry.icon,
                label = entry.label,
            )
        }
    }
}

/** 导航项(标签 + 图标);HomeScreen 的底部悬浮栏与平板侧栏共用 */
data class NavEntry(val label: String, val icon: ImageVector)
