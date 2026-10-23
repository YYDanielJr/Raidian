package yy.daniel.raidian.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 平板 / 大屏响应式判定与布局常量。
 *
 * 断点沿用 Android 官方约定:
 *  - [TabletBreakpointDp] = 600dp:sw600dp 及以上视为"平板/大屏"(一加平板 Pro
 *    竖屏约 800dp、横屏约 1600dp 均命中)。手机(小米 13 Ultra 约 440dp)不命中。
 *
 * 使用:UI 里 `val tablet = isTablet()` 后在 窄屏(单列)/ 宽屏(多栏)之间切换。
 */
private const val TabletBreakpointDp = 600

/** 平板/大屏判断(以窗口最短边 sw 为准,旋转时自动跟随) */
@Composable
fun isTablet(): Boolean {
    val cfg = LocalConfiguration.current
    val sw = cfg.screenWidthDp.coerceAtMost(cfg.screenHeightDp)
    return remember(sw) { sw >= TabletBreakpointDp }
}

/** 当前可用宽度(dp,随旋转变化;remember 以便在宽度变化时触发重组) */
@Composable
fun windowWidthDp(): Int {
    val cfg = LocalConfiguration.current
    return remember(cfg.screenWidthDp, cfg.screenHeightDp) { cfg.screenWidthDp }
}

/** 平板详情/设置等子页的最大内容宽度(避免横屏整条拉太宽,居中限宽) */
val TabletContentMaxWidthDp = 720

/** 与平板系统设置一致使用紧凑标题，手机保留可折叠大标题。 */
@Composable
fun AdaptiveTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.surface,
    largeTitle: String = title,
    titleColor: Color = MiuixTheme.colorScheme.onSurface,
    largeTitleColor: Color = titleColor,
    subtitle: String = "",
    subtitleColor: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    titlePadding: Dp = 24.dp,
    navigationIconPadding: Dp = 12.dp,
    scrollBehavior: ScrollBehavior? = null,
) {
    if (isTablet()) {
        SmallTopAppBar(title = title, modifier = modifier, color = color, titleColor = titleColor,
            subtitle = subtitle, subtitleColor = subtitleColor, navigationIcon = navigationIcon,
            actions = actions, titlePadding = titlePadding, navigationIconPadding = navigationIconPadding,
            scrollBehavior = scrollBehavior)
    } else {
        TopAppBar(title = title, modifier = modifier, color = color, titleColor = titleColor,
            largeTitle = largeTitle, largeTitleColor = largeTitleColor, subtitle = subtitle,
            subtitleColor = subtitleColor, navigationIcon = navigationIcon, actions = actions,
            titlePadding = titlePadding, navigationIconPadding = navigationIconPadding, scrollBehavior = scrollBehavior)
    }
}
