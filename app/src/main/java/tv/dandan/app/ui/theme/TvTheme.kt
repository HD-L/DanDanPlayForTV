package tv.dandan.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Shapes
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

/**
 * 弹弹 TV 的视觉基调：深色客厅背景、青色强调、琥珀色进度。
 * 所有页面只通过 [MaterialTheme] 取色，不在页面里硬编码颜色。
 */
object TvColors {
    val background = Color(0xFF0B1118)
    val surface = Color(0xFF141C27)
    val surfaceVariant = Color(0xFF1C2736)
    val surfaceBright = Color(0xFF263447)
    val onSurface = Color(0xFFEDF2F9)
    val muted = Color(0xFF9DAEC4)
    val accent = Color(0xFF62DCD1)
    val onAccent = Color(0xFF062321)
    val amber = Color(0xFFF4B860)
    val danger = Color(0xFFFF6B6B)
    val scrim = Color(0xCC070B11)
}

private val colorScheme = darkColorScheme(
    primary = TvColors.accent,
    onPrimary = TvColors.onAccent,
    primaryContainer = TvColors.accent,
    onPrimaryContainer = TvColors.onAccent,
    secondary = TvColors.amber,
    onSecondary = Color(0xFF2A1B00),
    background = TvColors.background,
    onBackground = TvColors.onSurface,
    surface = TvColors.surface,
    onSurface = TvColors.onSurface,
    surfaceVariant = TvColors.surfaceVariant,
    onSurfaceVariant = TvColors.muted,
    inverseSurface = TvColors.onSurface,
    inverseOnSurface = TvColors.background,
    error = TvColors.danger,
    onError = Color.White,
    border = TvColors.accent,
    borderVariant = Color(0xFF34465C),
    scrim = TvColors.scrim,
)

/** 十英尺 UI 字号：正文不低于 18sp，标题可读距离 3 米。 */
private val typography = Typography(
    displayLarge = TextStyle(fontSize = 44.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold),
    displayMedium = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold),
    displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
    headlineLarge = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 19.sp, lineHeight = 27.sp),
    bodyMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 页面边距与卡片尺寸的统一来源。 */
object TvDimens {
    val screenPaddingStart = 48.dp
    val screenPaddingEnd = 48.dp
    val screenPaddingTop = 28.dp
    val rowSpacing = 28.dp
    val cardSpacing = 16.dp
    val posterWidth = 156.dp
    val posterHeight = 224.dp
    val wideCardWidth = 300.dp
    val wideCardHeight = 168.dp
    val tileWidth = 232.dp
    val tileHeight = 128.dp
    val railCollapsed = 72.dp
    val railExpanded = 232.dp
}

@Composable
fun DanDanTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorScheme, typography = typography, shapes = shapes, content = content)
}
