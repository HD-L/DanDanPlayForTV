package tv.dandan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.dandan.app.ui.components.*
import tv.dandan.app.ui.screens.Hero
import tv.dandan.app.ui.screens.HeroBackdrop
import tv.dandan.app.ui.theme.DanDanTvTheme
import tv.dandan.app.ui.theme.TvColors
import tv.dandan.app.ui.theme.TvDimens
import tv.dandan.core.AnimeSummary

/** 以 1080p 电视（xhdpi，960×540dp）渲染关键界面，输出到 build/screenshots 供人工核对。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val weekly = (1..8).map { AnimeSummary("$it", listOf("葬送的芙莉莲", "孤独摇滚！", "咒术回战 第二季", "间谍过家家", "我推的孩子", "天国大魔境", "无职转生 II", "跃动青春")[it - 1], rating = listOf("9.1", "8.7", "8.9", "8.2", "8.8", "8.0", "8.5", "8.3")[it - 1], airDay = it % 7) }

    @Test fun homePosterWall() {
        compose.setContent {
            DanDanTvTheme {
                Box(Modifier.fillMaxSize().background(TvColors.background)) {
                    Row(Modifier.fillMaxSize()) {
                        NavRail(selected = 0, onSelect = {})
                        Box(Modifier.fillMaxSize()) {
                            HeroBackdrop(Hero("葬送的芙莉莲", "魔王被打倒后，精灵魔法使芙莉莲踏上了解人类之心的旅途。", meta = "今天更新 · 周五 · 评分 9.1"))
                            LazyColumn(contentPadding = PaddingValues(start = TvDimens.screenPaddingStart, top = 230.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(TvDimens.rowSpacing), modifier = Modifier.fillMaxSize()) {
                                item {
                                    ContentRow("继续观看", listOf("[Nekomoe kissaten] Frieren - 12 [1080p].mkv", "间谍过家家 S2 第 03 集")) { _, title, modifier ->
                                        WideCard(title, onClick = {}, modifier = modifier, subtitle = "12:40 / 24:10", progress = .52f, icon = Icons.Default.PlayArrow)
                                    }
                                }
                                item {
                                    ContentRow("今天更新 · 周五", weekly, trailing = "8 部") { _, anime, modifier -> PosterCard(anime.title, anime.imageUrl, {}, modifier, badge = anime.rating) }
                                }
                                item {
                                    ContentRow("快捷入口", listOf("浏览媒体库", "打开视频链接")) { _, title, modifier -> TileCard(title, {}, modifier, "本地 · 网络 · 收藏 · 索引", Icons.Default.List) }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("葬送的芙莉莲").requestFocus()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/home.png")
    }

    @Test fun settingsRows() {
        compose.setContent {
            DanDanTvTheme {
                Row(Modifier.fillMaxSize().background(TvColors.background)) {
                    NavRail(selected = 3, onSelect = {})
                    ScreenScaffold("弹幕设置", subtitle = "确定切换选项；更改自动保存。") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ToggleRow("显示弹幕", true, {})
                            ToggleRow("滚动弹幕", true, {})
                            ToggleRow("顶部弹幕", false, {})
                            ChoiceRow("弹幕语言", "简体", listOf("原始", "简体", "繁体"), {})
                            SectionTitle("样式")
                            ChoiceRow("字号", 24f, listOf(18f, 24f), {}, format = { "${it.toInt()} sp" })
                            ChoiceRow("透明度", .85f, listOf(.85f), {}, format = { "85%" })
                            ActionRow("编辑关键字与正则屏蔽", onClick = {}, chevron = true, icon = Icons.Default.Add)
                            ActionRow("清除本机凭证", onClick = {}, danger = true)
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("弹幕语言").requestFocus()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/settings.png")
    }

    @Test fun statusPanes() {
        compose.setContent {
            DanDanTvTheme {
                Row(Modifier.fillMaxSize().background(TvColors.background)) {
                    Box(Modifier.weight(1f).fillMaxSize()) { ErrorPane("服务认证失败，请检查开发者凭证、设备时间和应用权限", onRetry = {}, onBack = {}) }
                    Box(Modifier.weight(1f).fillMaxSize().padding(24.dp)) { EmptyPane("还没有收藏。在目录或索引中长按视频，选择“收藏此视频”。", actionLabel = "浏览媒体库") {} }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/screenshots/status.png")
    }
}
