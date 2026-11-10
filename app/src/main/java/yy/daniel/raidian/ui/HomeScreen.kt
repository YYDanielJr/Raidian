package yy.daniel.raidian.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.NavigationRail
import top.yukonga.miuix.kmp.basic.NavigationRailItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.rememberNavigationRailState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.theme.MiuixTheme
import yy.daniel.raidian.R

/** 手机主页滚动内容末尾为悬浮栏预留的可滚动安全空间，不参与悬浮栏自身布局。 */
internal val HomeFloatingNavContentPadding = 96.dp

/**
 * 主页容器:底部导航 + 四个并列的功能页。
 *
 * 「续航曲线」「充电曲线」是本次新增的两个功能页,与既有的「实时监控」「记录」并列,
 * 因此这里新建了一个底部导航栏把它们串起来(而不是像以前那样把入口塞进顶栏)。
 *
 * 导航栏样式:手机用真正覆盖在页面上的悬浮胶囊栏,内容会从它下方滚过;
 * 平板用左侧 `NavigationRail`(大屏惯例,与设置页的导航形态一致)。
 * 手机页使用 [HorizontalPager],底栏点击与左右滑动共享同一份分页状态与切换动画。
 *
 * 顶栏入口保留:「设置」仍在实时页顶栏右上角(见 MainScreen)。
 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier, vm: MainViewModel = viewModel()) {
    val tablet = isTablet()
    val backdrop = rememberUiBackdrop(MiuixTheme.colorScheme.surface)
    val blurSupported = isMiuixBlurSupported()

    // 底部导航项:实时 / 续航 / 充电 / 记录
    // 续航、充电图标用自绘矢量(icon 库里没有电池类图标,见 res/drawable/ic_battery_*.xml)
    val items = listOf(
        NavEntry("实时", MiuixIcons.Home),
        NavEntry("续航", ImageVector.vectorResource(R.drawable.ic_battery_endurance)),
        NavEntry("充电", ImageVector.vectorResource(R.drawable.ic_battery_charge)),
        NavEntry("记录", MiuixIcons.ListView),
    )
    val pagerState = rememberPagerState(pageCount = { items.size })
    val scope = rememberCoroutineScope()

    fun animateToPage(index: Int) {
        if (index == pagerState.currentPage && pagerState.currentPageOffsetFraction == 0f) return
        scope.launch {
            pagerState.animateScrollToPage(
                page = index,
                animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
            )
        }
    }

    // 与 HyperOS 多分页应用一致:不在首页时按返回先回到第一个分页。
    BackHandler(enabled = pagerState.currentPage != 0) { animateToPage(0) }

    /** 各页自带 Scaffold 与顶栏;外层不能再套 Scaffold,否则系统栏 inset 会被计算两次。 */
    @Composable
    fun renderPage(index: Int) {
        when (index) {
            0 -> MainScreen(
                modifier = Modifier.fillMaxSize(),
                vm = vm,
                visible = pagerState.currentPage == index,
            )
            1 -> EnduranceScreen(modifier = Modifier.fillMaxSize(), vm = vm)
            2 -> ChargingScreen(modifier = Modifier.fillMaxSize(), vm = vm)
            else -> RecordsScreen(
                modifier = Modifier.fillMaxSize(),
                vm = vm,
                showBack = false,
            )
        }
    }

    // 根 Scaffold 只提供 MiuixPopupHost:记录页等子 Scaffold 的 OverlayDialog 会注册到
    // 最外层宿主,从而始终盖住悬浮导航栏。系统栏 inset 必须显式为 0,否则子页会再次
    // 处理一遍状态栏,重现顶部双倍留白。
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        if (tablet) {
            // ---- 平板:左侧常驻导航栏(悬浮底栏在大屏上离手指太远,不符合平板习惯)----
            Row(modifier = Modifier.fillMaxSize()) {
                val expand = windowWidthDp() >= 1000
                val railState = rememberNavigationRailState(initialValue = if (expand)
                    top.yukonga.miuix.kmp.basic.NavigationRailValue.Expanded else top.yukonga.miuix.kmp.basic.NavigationRailValue.Collapsed)
                LaunchedEffect(expand) { if (expand) railState.expand() else railState.collapse() }
                NavigationRail(state = railState, showDivider = false,
                    expandContentDescription = "展开导航", collapseContentDescription = "收起导航") {
                    items.forEachIndexed { i, entry ->
                        NavigationRailItem(
                            selected = pagerState.currentPage == i,
                            onClick = { animateToPage(i) },
                            icon = entry.icon,
                            label = entry.label,
                        )
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    key = { it },
                    verticalAlignment = Alignment.Top,
                ) { page ->
                    renderPage(page)
                }
            }
        } else {
            // ---- 手机:页面与底栏放在同一个 Box 中,底栏是覆盖层而不是布局占位。----
            // 旧实现把它放进 Scaffold.bottomBar,导致两类问题:
            // 1. Scaffold 给正文预留整条底栏高度,视觉上不再是悬浮;
            // 2. 外层 Scaffold 又给正文加了一次顶部系统栏 inset,与页面自身顶栏叠加,
            //    形成明显偏大的“额头”。同层覆盖可同时消除这两个问题。
            Box(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (blurSupported) Modifier.layerBackdrop(backdrop) else Modifier),
                    key = { it },
                    verticalAlignment = Alignment.Top,
                ) { page ->
                    renderPage(page)
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    FloatingNavBar(
                        items = items,
                        selectedIndex = pagerState.currentPage,
                        onSelect = ::animateToPage,
                        backdrop = backdrop,
                    )
                }
            }
        }
    }
}
