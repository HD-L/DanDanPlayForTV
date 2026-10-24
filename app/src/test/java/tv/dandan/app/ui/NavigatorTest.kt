package tv.dandan.app.ui

import org.junit.Assert.*
import org.junit.Test
import tv.dandan.core.MediaSource
import tv.dandan.core.SourceKind

class NavigatorTest {
    private val source = MediaSource("s1", "NAS", SourceKind.SMB, "smb://nas/share")

    @Test fun pushAndPopKeepDirectoryStackInsideTab() {
        val navigator = Navigator(1)
        navigator.push(Route.Directory(source))
        navigator.push(Route.Directory(source, "smb://nas/share/anime", "anime"))
        assertTrue(navigator.canPop)
        assertTrue(navigator.pop())
        assertEquals("directory:s1:smb://nas/share", navigator.current.key)
        assertTrue(navigator.pop())
        assertFalse(navigator.pop())
        assertEquals(Route.Library, navigator.current)
    }

    @Test fun switchingTabDropsTheOldStackAndRecordsDirection() {
        val navigator = Navigator(0)
        navigator.push(Route.VideoUrl)
        navigator.switchTab(3)
        assertFalse(navigator.canPop)
        assertEquals(Route.Settings, navigator.current)
        assertEquals(1, navigator.direction)
        navigator.switchTab(0)
        assertEquals(-1, navigator.direction)
        assertEquals(Route.Home, navigator.current)
    }

    @Test fun popToRootReturnsToTabRoot() {
        val navigator = Navigator(2)
        navigator.push(Route.AnimeSeasons)
        navigator.push(Route.Credentials)
        navigator.popToRoot()
        assertEquals(Route.Anime, navigator.current)
        assertFalse(navigator.canPop)
    }

    @Test fun routeKeysDistinguishBindingMode() {
        assertNotEquals(Route.AnimeSearch().key, Route.AnimeSearch(tv.dandan.core.MediaEntry("m1", "video", "u")).key)
        assertEquals(Route.Preference(PreferenceSection.DANMAKU).key, Route.Preference(PreferenceSection.DANMAKU).key)
    }
}
