package yy.daniel.raidian.ui

import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 各页面共享的骨架与排版小块。
 *
 * 子页(记录/悬浮窗/会话详情)统一用 [SubPageScaffold]:顶栏 + 返回键 + 内容区;
 * 分组卡片之间的小节标题统一用 [SectionHeader]。
 *
 * 顶栏标题默认可折叠:内容上滑时大标题回缩为小标题,回滚到顶部再展开
 * (Miuix TopAppBar scrollBehavior;调用方只需让内容可滚动,本组件负责
 * 把滚动连接接到内容外层)。
 */

/** 分组小节标题(卡片外灰字,HyperOS 设置页一致) */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    SmallTitle(
        text = text,
        modifier = modifier,
        textColor = MiuixTheme.colorScheme.onBackgroundVariant,
        insideMargin = PaddingValues(start = 28.dp, top = 14.dp, end = 28.dp, bottom = 4.dp),
    )
}

/** 白色圆角卡片,左右 12dp 页面边距、内部 16dp 留白(内容由调用方传入) */
@Composable
fun HyperCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        insideMargin = PaddingValues(16.dp),
        content = content,
    )
}

/**
 * 子页面通用骨架:顶栏(标题 + 返回箭头 + 可选右侧动作)+ 内容槽。
 *
 * 顶栏是可折叠的:内容(滚动容器)上滑时大标题回缩,下滑回顶时展开。
 * 内容槽收到 Scaffold 已扣除顶栏/系统栏的 [PaddingValues],由调用方自行铺
 * Column/scroll(滚动容器放在本组件包好的嵌套滚动区域内即可生效)。
 *
 * @param title 顶栏标题
 * @param subtitle 顶栏副标题(可选)
 * @param onBack 返回回调;传 null 时不显示返回键
 * @param actions 顶栏右侧动作(可选)
 * @param content 内容槽,收到 Scaffold 已扣除顶栏/系统栏的 [PaddingValues],
 *                由调用方自行铺 Column/scroll
 */
@Composable
fun SubPageScaffold(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior(
        state = rememberTopAppBarState(),
        snapAnimationSpec = spring(stiffness = 2500f),
        canScroll = { true },
    )
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = title,
                largeTitle = title,
                subtitle = subtitle,
                titleColor = MiuixTheme.colorScheme.onSurface,
                largeTitleColor = MiuixTheme.colorScheme.onSurface,
                subtitleColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = "返回",
                                tint = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }
                },
                actions = actions,
                titlePadding = 24.dp,
                navigationIconPadding = 12.dp,
                scrollBehavior = scrollBehavior,
            )
        }
    ) { padding ->
        // 滚动容器在本 Box 内时,滚动手势经嵌套滚动转发给顶栏折叠
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {
            content(padding)
        }
    }
}
