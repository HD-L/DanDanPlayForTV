package tv.dandan.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import tv.dandan.app.ui.theme.TvColors

/** 设置与操作列表的基础行：焦点时整行变亮并出现边框，不做位移。 */
@Composable
fun ActionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    icon: ImageVector? = null,
    value: String = "",
    chevron: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.small as RoundedCornerShape
    val tint = if (danger) TvColors.danger else TvColors.onSurface
    ListItem(
        selected = false,
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = ListItemDefaults.shape(shape),
        colors = ListItemDefaults.colors(
            containerColor = TvColors.surfaceVariant.copy(alpha = .55f),
            contentColor = tint,
            focusedContainerColor = TvColors.surfaceBright,
            focusedContentColor = if (danger) TvColors.danger else TvColors.onSurface,
            disabledContentColor = TvColors.muted,
        ),
        scale = ListItemDefaults.scale(focusedScale = 1.0f),
        border = ListItemDefaults.border(focusedBorder = Border(BorderStroke(2.dp, if (danger) TvColors.danger else TvColors.accent), shape = shape)),
        leadingContent = icon?.let { { Icon(it, contentDescription = null, tint = if (danger) TvColors.danger else TvColors.accent, modifier = Modifier.size(26.dp)) } },
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (subtitle.isBlank()) null else { { Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TvColors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (value.isNotBlank()) Text(value, style = MaterialTheme.typography.labelLarge, color = TvColors.accent, maxLines = 1)
                trailing?.invoke()
                if (chevron) { Spacer(Modifier.width(6.dp)); Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = TvColors.muted) }
            }
        },
    )
}

/** 开关行：确定键切换。 */
@Composable
fun ToggleRow(title: String, checked: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String = "", icon: ImageVector? = null) {
    ActionRow(title, onClick = { onToggle(!checked) }, modifier = modifier, subtitle = subtitle, icon = icon, trailing = {
        Switch(checked = checked, onCheckedChange = null)
    })
}

/** 选项行：确定键循环切换，右侧显示当前值。 */
@Composable
fun <T> ChoiceRow(title: String, value: T, options: List<T>, onChange: (T) -> Unit, modifier: Modifier = Modifier, subtitle: String = "", format: (T) -> String = { it.toString() }, icon: ImageVector? = null) {
    ActionRow(title, onClick = { onChange(options[(options.indexOf(value).coerceAtLeast(-1) + 1) % options.size]) }, modifier = modifier, subtitle = subtitle, icon = icon, value = format(value), trailing = {
        Spacer(Modifier.width(8.dp)); Icon(Icons.Default.KeyboardArrowRight, contentDescription = "切换", tint = TvColors.muted)
    })
}

/** 列表分组标题。 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = TvColors.accent, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
    }
}

/** 键值信息行，不可聚焦。 */
@Composable
fun InfoLine(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = TvColors.onSurface) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TvColors.muted, modifier = Modifier.width(150.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

/** 胶囊标签：标签、筛选项等可点选的小元素；选中时填充强调色。 */
@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
fun TvChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false) {
    androidx.tv.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        colors = androidx.tv.material3.FilterChipDefaults.colors(
            containerColor = TvColors.surfaceVariant, contentColor = TvColors.onSurface,
            focusedContainerColor = TvColors.accent, focusedContentColor = TvColors.onAccent,
            selectedContainerColor = TvColors.accent.copy(alpha = .3f), selectedContentColor = TvColors.onSurface,
            focusedSelectedContainerColor = TvColors.accent, focusedSelectedContentColor = TvColors.onAccent,
        ),
        scale = androidx.tv.material3.FilterChipDefaults.scale(focusedScale = 1.06f),
    ) { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
}
