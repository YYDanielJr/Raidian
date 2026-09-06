package yy.daniel.raidian.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * HyperOS 风格分组列表的共享排版与行组件(Phase 3 UI 规范)。
 *
 * 视觉约束(与 HyperOS 系统设置页一致):
 *  - 列表行内容紧贴外部圆角容器:分组容器本身没有任何内部 padding,行与行之间
 *    无空隙、无分割线;
 *  - 单行(仅主文本)最小行高 56dp,带副标题的行自动抬到 68dp(更高一点,
 *    双行不显得挤),内容在行内垂直居中,同一列表内行高一致;
 *  - 点按反馈按行实际区域绘制:容器 `clip(圆角)` 会把首/末行的按压矩形裁出
 *    上圆/下圆,中间行是直角矩形 —— 无需逐行计算圆角;
 *  - 按压反馈渐入渐出:按下用快弹簧(渐入),松开用慢一点的回弹(渐出),
 *    与 Miuix 官方 indication 的动画节奏一致。
 *
 * 组件分两层:
 *  - [HyperListGroup]:圆角容器,clip 内部内容(行背景与按压反馈都被裁圆角);
 *  - [HyperListRow]:一行(整行可点),画行内按压反馈矩形;
 *  - [ListRowContent]:标准"标题+副标题+尾部"行排版,主文本 17sp Medium、
 *    副文本 14sp Normal(见 [ListType])。
 */

/** 分组容器默认圆角(与 HyperOS 卡片一致) */
private val GroupCornerRadius = 16.dp

/** 行内容横向留白(HyperOS 设置项 16dp,行文字相对容器左缘的缩进) */
val RowHorizontalPadding = 16.dp

/** 单行(仅主文本)最小行高(HyperOS 设置项行高) */
val SingleLineRowMinHeight = 56.dp

/** 双行(主文本 + 副文本)行高:比单行更高,双行内容不挤 */
val TwoLineRowMinHeight = 68.dp

/** 按压遮罩目标透明度(与 Miuix 官方 indication 的按压增量一致) */
private const val PressOverlayAlpha = 0.10f

/** 按压渐入动画(Miuix 官方:快弹簧,临界阻尼) */
private val PressEnterSpring = folmeSpring<Float>(damping = 1.0f, response = 0.2f)

/** 松开渐出动画(Miuix 官方:略带回弹,响应更慢,渐出更柔和) */
private val PressExitSpring = folmeSpring<Float>(damping = 0.95f, response = 0.35f)

/**
 * 列表排版 style(Compose 层):
 *  - 主文本:17sp、FontWeight.Medium
 *  - 副文本:14sp、FontWeight.Normal
 */
object ListType {
    /** 列表行主文本 */
    val Main: TextStyle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium)
    /** 列表行副文本 */
    val Secondary: TextStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal)
}

/**
 * 列表行主文本(17sp / Medium)。列表内所有一级标题统一走这里。
 */
@Composable
fun ListMainText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.onBackground,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = ListType.Main,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 列表行副文本(14sp / Normal)。列表内所有二级说明/摘要统一走这里。
 */
@Composable
fun ListSecondaryText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    maxLines: Int = 2,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = ListType.Secondary,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 圆角分组容器(HyperOS 卡片式分组):容器背景 + 把行内容与按压反馈裁成圆角。
 *
 * 内部无 padding:行由 [HyperListRow] 顺序排入,内容贴容器边、行间无缝。
 *
 * @param modifier 外部 modifier(页面横向边距等)
 * @param cornerRadius 容器圆角,默认 16dp
 * @param color 容器背景色;null 时用主题 surfaceContainer(Miuix Card 同款)
 */
@Composable
fun HyperListGroup(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = GroupCornerRadius,
    color: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val containerColor = color ?: MiuixTheme.colorScheme.surfaceContainer
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .background(containerColor),
        content = content,
    )
}

/**
 * 列表行(可点击):一行就是一个完整的点击区域,按压时在**行自身矩形**上
 * 叠半透明遮罩,按下渐入、松开渐出(弹簧动画,节奏与 Miuix 官方一致)。
 * 行贴容器排列,容器圆角负责把首/末行遮罩裁成对应圆角。
 *
 * @param onClick 点击回调;传 null 时整行不可点(纯展示行)
 * @param onLongClick 长按回调(可选);提供后点击与长按独立
 * @param modifier 外部 modifier
 * @param minHeight 统一最小行高(默认 [SingleLineRowMinHeight]);
 *   带副标题的行应让 [ListRowContent] 自行抬到 [TwoLineRowMinHeight]
 * @param content 行内容(RowScope)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HyperListRow(
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    minHeight: Dp = SingleLineRowMinHeight,
    content: @Composable RowScope.() -> Unit,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongClick by rememberUpdatedState(onLongClick)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    // 按压反馈的透明度:按下/松开各自用弹簧渐入渐出(非闪入闪出)
    val pressAlpha = remember { Animatable(0f) }
    LaunchedEffect(isPressed) {
        pressAlpha.animateTo(
            targetValue = if (isPressed) 1f else 0f,
            animationSpec = if (isPressed) PressEnterSpring else PressExitSpring,
        )
    }
    val feedbackColor = MiuixTheme.colorScheme.onSurface

    val clickableModifier = if (currentOnClick != null) {
        if (currentOnLongClick != null) {
            Modifier.combinedClickable(
                interactionSource = interactionSource,
                role = Role.Button,
                indication = null,
                onClick = { currentOnClick?.invoke() },
                onLongClick = { currentOnLongClick?.invoke() },
            )
        } else {
            Modifier.clickable(
                interactionSource = interactionSource,
                role = Role.Button,
                indication = null,
                onClick = { currentOnClick?.invoke() },
            )
        }
    } else {
        Modifier
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .drawBehind {
                if (pressAlpha.value > 0.001f) {
                    drawRect(
                        color = feedbackColor.copy(alpha = PressOverlayAlpha * pressAlpha.value),
                    )
                }
            }
            .then(clickableModifier),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * 标准列表行内容排版:标题(17sp/Medium)+ 可选副标题(14sp/Normal)+ 尾部。
 * 使用方把它放进 [HyperListRow] 的内容槽。
 *
 * 内容相对行左缘缩进 16dp(文字不与容器边缘相切,与 HyperOS 设置项一致),
 * 行背景与按压反馈仍由 [HyperListRow] 覆盖到容器边缘,视觉上内容贴边。
 *
 * 高度:有副标题时内容列抬到 [TwoLineRowMinHeight],让双行行比单行更高一点、
 * 文字不挤;单行则保持 [SingleLineRowMinHeight]。文本在列内垂直居中。
 *
 * @param title 主文本(标题)
 * @param summary 副文本(说明/摘要);null/空串时不显示
 * @param leading 行首内容(如应用图标);默认无。非空时显示在标题左侧
 * @param trailing 行尾内容,默认右箭头;传 {} 可去掉
 */
@Composable
fun RowScope.ListRowContent(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = { ListChevron() },
) {
    if (leading != null) {
        // 行首(leading)单独放:自带左缘留白(24dp 视觉起点,图标宽 32 近似),
        // 与 HyperOS 设置页"图标 + 两行文字"排法一致
        Box(
            modifier = Modifier.padding(start = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            leading()
        }
        Spacer(Modifier.width(14.dp))
    }
    val hasSummary = summary != null && summary.isNotEmpty()
    Column(
        modifier = modifier
            .weight(1f)
            .padding(start = RowHorizontalPadding)
            .then(if (hasSummary) Modifier.heightIn(min = TwoLineRowMinHeight) else Modifier),
        verticalArrangement = Arrangement.Center,
    ) {
        ListMainText(title)
        if (hasSummary) {
            Spacer(Modifier.height(1.dp))
            ListSecondaryText(summary, maxLines = 1)
        }
    }
    Spacer(Modifier.width(8.dp))
    // trailing 单独放进内层 Row:自带右缘留白,同时保留 RowScope(receiver 由该 Row 提供)
    Row(
        modifier = Modifier.padding(end = RowHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        content = trailing,
    )
}

/** 行尾右箭头(HyperOS 列表标配) */
@Composable
private fun RowScope.ListChevron() {
    Icon(
        imageVector = MiuixIcons.ChevronForward,
        contentDescription = null,
        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
    )
}
