@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.xyoye.dandanplay.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage

private val CardShape = RoundedCornerShape(12.dp)

/**
 * 全 TV 界面共用的海报卡片：圆角 + 聚焦放大 + 白色描边强高亮，可选副标题/角标。
 * 保证首页海报墙、番剧浏览、详情页剧集货架风格统一。
 */
@Composable
fun TvPosterCard(
    imageUrl: Any?,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badge: String? = null,
    progress: Float? = null,
    width: Dp = 150.dp,
    posterHeight: Dp = 210.dp,
    onLongClick: (() -> Unit)? = null
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.width(width),
        shape = ClickableSurfaceDefaults.shape(CardShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF26262B),
            contentColor = Color(0xFFEDEDED),
            focusedContainerColor = Color(0xFF34343A),
            focusedContentColor = Color(0xFFFFFFFF)
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = CardShape)
        )
    ) {
        Column {
            Box {
                AsyncPoster(imageUrl, title, width, posterHeight)
                badge?.takeIf { it.isNotBlank() }?.let {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xCC3F8CFF))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(text = it, color = Color.White, fontSize = 10.sp)
                    }
                }
                progress?.takeIf { it > 0f }?.let { p ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color(0x88000000))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(p.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(Color(0xFF3F8CFF))
                        )
                    }
                }
            }
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = Color(0xFF9A9A9A),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun AsyncPoster(imageUrl: Any?, title: String, width: Dp, height: Dp) {
    if (imageUrl == null || (imageUrl is String && imageUrl.isBlank())) {
        // 无封面时给一个深色占位 + 首字，避免大图标 / 空白
        Box(
            modifier = Modifier.width(width).height(height).background(Color(0xFF3A3A42)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = title.take(2), color = Color(0xFF8A8A8A), fontSize = 22.sp)
        }
    } else {
        AsyncImage(
            model = imageUrl,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(width).height(height)
        )
    }
}

/**
 * 通用入口卡片（设置/功能入口用），聚焦放大 + 白色描边高亮。
 */
@Composable
fun TvEntryCard(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(88.dp),
        shape = ClickableSurfaceDefaults.shape(CardShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF26262B),
            contentColor = Color(0xFFEDEDED),
            focusedContainerColor = Color(0xFFEDEDED),
            focusedContentColor = Color(0xFF161619)
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color(0xFF3F8CFF)), shape = CardShape)
        )
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(text = label, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * 通用顶部选项卡行（参考 B 站 TV 顶部 tag：选中白字 + 蓝色下划线，可遥控聚焦）。
 * 历史记录「本地/云端」、每周番剧「每周番剧/我的追番」等共用此样式。
 */
@Composable
fun TvTabRow(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, label ->
            TvTabItem(label = label, selected = index == selectedIndex, onClick = { onSelect(index) })
        }
    }
}

@Composable
private fun TvTabItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0x00000000),
            contentColor = if (selected) Color.White else Color(0xFF9A9A9A),
            focusedContainerColor = Color(0x26FFFFFF),
            focusedContentColor = Color.White
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Text(
                text = label,
                fontSize = 20.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
            Box(
                modifier = Modifier
                    .padding(top = 5.dp)
                    .height(3.dp)
                    .width(26.dp)
                    .background(if (selected) Color(0xFF3F8CFF) else Color(0x00000000))
            )
        }
    }
}

/**
 * 深色风格搜索输入框（遥控器聚焦时可输入），用于番剧搜索。
 */
@Composable
fun TvSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "输入番剧名…"
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(10.dp)) {
        Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            if (value.isEmpty()) {
                Text(text = placeholder, color = Color(0xFF8A8A8A))
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
