package tv.dandan.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import tv.dandan.app.ui.theme.TvColors
import tv.dandan.app.ui.theme.TvDimens

private fun focusBorder(color: Color, shape: RoundedCornerShape) = Border(BorderStroke(3.dp, color), inset = 0.dp, shape = shape)

/** 占位封面的底色：按标题取一组低饱和色，海报墙上相邻的占位不会一模一样。 */
private val placeholderTones = listOf(Color(0xFF2B3A55), Color(0xFF3D2C4E), Color(0xFF24463F), Color(0xFF4A3B2A), Color(0xFF27424F), Color(0xFF46303A))

/** 封面图；无图时用标题首字生成占位，不留空白。[fullTitle] 为真时占位同时写出完整标题（片库中未识别的作品）。 */
@Composable
fun Poster(imageUrl: String, title: String, modifier: Modifier = Modifier, fullTitle: Boolean = false) {
    Box(modifier.background(TvColors.surfaceBright)) {
        if (imageUrl.isNotBlank()) {
            AsyncImage(model = imageUrl, contentDescription = title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (fullTitle) {
            val tone = placeholderTones[Math.floorMod(title.hashCode(), placeholderTones.size)]
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(tone, TvColors.surfaceVariant)))) {
                Text(title.take(1).ifBlank { "弹" }, style = MaterialTheme.typography.displayMedium, color = TvColors.onSurface.copy(alpha = .22f), modifier = Modifier.align(Alignment.TopStart).padding(12.dp, 6.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = TvColors.onSurface, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp, 16.dp))
            }
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(TvColors.surfaceBright, TvColors.surfaceVariant))), contentAlignment = Alignment.Center) {
                Text(title.take(1).ifBlank { "弹" }, style = MaterialTheme.typography.displayMedium, color = TvColors.muted)
            }
        }
    }
}

/** 竖版海报卡：2:3 封面，焦点放大并显示边框，下方标题最多两行。 */
@Composable
fun PosterCard(
    title: String,
    imageUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    badge: String = "",
    progress: Float = -1f,
    onFocus: () -> Unit = {},
    fullTitlePlaceholder: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.medium as RoundedCornerShape
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val titleColor by animateColorAsState(if (focused) TvColors.onSurface else TvColors.muted, tween(160), label = "title")
    LaunchedEffect(focused) { if (focused) onFocus() }
    Column(modifier.width(TvDimens.posterWidth)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            interactionSource = interaction,
            shape = CardDefaults.shape(shape),
            scale = CardDefaults.scale(focusedScale = 1.08f),
            border = CardDefaults.border(focusedBorder = focusBorder(TvColors.accent, shape)),
            glow = CardDefaults.glow(focusedGlow = Glow(TvColors.accent.copy(alpha = .45f), 18.dp)),
            modifier = Modifier.size(TvDimens.posterWidth, TvDimens.posterHeight).semantics { contentDescription = title },
        ) {
            Box(Modifier.fillMaxSize()) {
                Poster(imageUrl, title, Modifier.fillMaxSize(), fullTitle = fullTitlePlaceholder)
                if (badge.isNotBlank()) Badge(badge, Modifier.align(Alignment.TopEnd).padding(8.dp))
                if (progress >= 0f) ProgressStripe(progress, Modifier.align(Alignment.BottomCenter))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TvColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

/** 横版卡：用于继续观看与视频文件，带进度条与说明。 */
@Composable
fun WideCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    imageUrl: String = "",
    subtitle: String = "",
    progress: Float = -1f,
    icon: ImageVector? = null,
    onFocus: () -> Unit = {},
) {
    val shape = MaterialTheme.shapes.medium as RoundedCornerShape
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus() }
    Card(
        onClick = onClick,
        interactionSource = interaction,
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1.06f),
        border = CardDefaults.border(focusedBorder = focusBorder(TvColors.accent, shape)),
        glow = CardDefaults.glow(focusedGlow = Glow(TvColors.accent.copy(alpha = .4f), 16.dp)),
        modifier = modifier.size(TvDimens.wideCardWidth, TvDimens.wideCardHeight),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (imageUrl.isNotBlank()) Poster(imageUrl, title, Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(TvColors.surfaceBright, TvColors.surfaceVariant))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, .45f to Color.Transparent, 1f to TvColors.scrim)))
            if (icon != null) Icon(icon, contentDescription = null, tint = TvColors.onSurface.copy(alpha = if (focused) 1f else .7f), modifier = Modifier.align(Alignment.Center).size(44.dp))
            Column(Modifier.align(Alignment.BottomStart).padding(14.dp, 10.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = TvColors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TvColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (progress >= 0f) ProgressStripe(progress, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** 紧凑入口卡：图标在左、标题与一行说明在右；用于海报墙上方的来源与工具。 */
@Composable
fun CompactTile(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    icon: ImageVector? = null,
    accent: Color = TvColors.accent,
    onFocus: () -> Unit = {},
) {
    val shape = MaterialTheme.shapes.medium as RoundedCornerShape
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus() }
    Card(
        onClick = onClick,
        interactionSource = interaction,
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(containerColor = TvColors.surfaceVariant.copy(alpha = .85f), focusedContainerColor = TvColors.surfaceBright),
        scale = CardDefaults.scale(focusedScale = 1.05f),
        border = CardDefaults.border(focusedBorder = focusBorder(accent, shape)),
        glow = CardDefaults.glow(focusedGlow = Glow(accent.copy(alpha = .3f), 12.dp)),
        modifier = modifier.size(212.dp, 76.dp),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(26.dp)); Spacer(Modifier.width(12.dp)) }
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, color = TvColors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TvColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 入口卡：图标 + 标题 + 说明，用于媒体源、快捷操作与设置分组。 */
@Composable
fun TileCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    icon: ImageVector? = null,
    accent: Color = TvColors.accent,
    onFocus: () -> Unit = {},
) {
    val shape = MaterialTheme.shapes.medium as RoundedCornerShape
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    LaunchedEffect(focused) { if (focused) onFocus() }
    Card(
        onClick = onClick,
        interactionSource = interaction,
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(containerColor = TvColors.surfaceVariant, focusedContainerColor = TvColors.surfaceBright),
        scale = CardDefaults.scale(focusedScale = 1.05f),
        border = CardDefaults.border(focusedBorder = focusBorder(accent, shape)),
        glow = CardDefaults.glow(focusedGlow = Glow(accent.copy(alpha = .35f), 14.dp)),
        modifier = modifier.size(TvDimens.tileWidth, TvDimens.tileHeight),
    ) {
        Column(Modifier.fillMaxSize().padding(18.dp, 16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            if (icon != null) Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(30.dp)) else Spacer(Modifier.height(30.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, color = TvColors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TvColors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun Badge(text: String, modifier: Modifier = Modifier, color: Color = TvColors.amber) {
    Surface(modifier, shape = RoundedCornerShape(6.dp), colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = color.copy(alpha = .92f))) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Color(0xFF1B1300), modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
fun ProgressStripe(progress: Float, modifier: Modifier = Modifier) {
    val fraction by animateFloatAsState(progress.coerceIn(0f, 1f), tween(300), label = "progress")
    Box(modifier.fillMaxWidth().height(5.dp).background(Color.Black.copy(alpha = .55f))) {
        Box(Modifier.fillMaxWidth(fraction).height(5.dp).background(TvColors.amber))
    }
}

/** 一行标题 + 横向卡片列表的通用骨架。 */
@Composable
fun RowHeader(title: String, modifier: Modifier = Modifier, trailing: String = "") {
    Row(modifier.fillMaxWidth().padding(end = TvDimens.screenPaddingEnd), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = TvColors.onSurface)
        Spacer(Modifier.weight(1f))
        if (trailing.isNotBlank()) Text(trailing, style = MaterialTheme.typography.labelMedium, color = TvColors.muted)
    }
}
