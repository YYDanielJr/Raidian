package yy.daniel.raidian.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** HyperOS 品牌蓝(Raidian 主色调,亮/暗通用) */
private val HyperosBlue = Color(0xFF3482FF)

/**
 * 全文字体排版:在 Miuix 默认字号基础上微调,接近系统设置页的观感。
 * 17sp 主文字、16sp 正文、小号 13/11sp 等,均沿用默认 TextStyles 各档位名。
 */
private val HyperosTextStyles: TextStyles
    get() = defaultTextStyles(
        main = TextStyle(fontSize = 17.sp),
        paragraph = TextStyle(fontSize = 17.sp, lineHeight = 1.2f.em),
        body1 = TextStyle(fontSize = 16.sp),
        body2 = TextStyle(fontSize = 14.sp),
        button = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
        footnote1 = TextStyle(fontSize = 13.sp),
        footnote2 = TextStyle(fontSize = 11.sp),
        headline1 = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
        headline2 = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
        subtitle = TextStyle(fontSize = 14.sp),
        title1 = TextStyle(fontSize = 32.sp),
        title2 = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Medium),
        title3 = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
        title4 = TextStyle(fontSize = 18.sp),
    )

/**
 * Raidian 主题:底层使用 Miuix(HyperOS 风格组件库)。
 * 跟随系统深浅色,固定使用小米 HyperOS 品牌蓝(亮暗各配一套 primary),
 * 不启用动态取色(Material You 取色与 HyperOS 品牌观感冲突,故关闭)。
 */
@Composable
fun RaidianTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val light = lightColorScheme(
        primary = HyperosBlue,
        onPrimary = Color.White,
    )
    val dark = darkColorScheme(
        primary = Color(0xFF3482FF),
        onPrimary = Color.White,
    )
    // Miuix 的 ThemeController:按亮/暗切换配色 + 深浅模式
    val controller = ThemeController(
        colorSchemeMode = if (darkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light,
        lightColors = light,
        darkColors = dark,
        isDark = darkTheme,
    )
    MiuixTheme(
        controller = controller,
        textStyles = HyperosTextStyles,
    ) {
        content()
    }
}
