package tv.dandan.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import tv.dandan.app.data.SourceRules
import tv.dandan.core.MediaSource
import tv.dandan.core.SourceKind

class VideoInputContractTest {
    private fun validate(location: String) = SourceRules.validate(MediaSource("direct-video", "网络视频", SourceKind.HTTP, location))

    @Test fun directVideoUsesTheSharedSourceContract() {
        val location = "https://media.example/video.m3u8?session=allowed"
        assertEquals(location, validate(location).location)
    }

    @Test fun directVideoRejectsCredentialsInvalidHostLengthAndFragments() {
        listOf(
            "https://name:secret@media.example/video.mp4",
            "https://invalid_host/video.mp4",
            "https:///video.mp4",
            "https://media.example/video.mp4#secret",
            "https://media.example/" + "x".repeat(8200),
        ).forEach { location -> assertThrows(IllegalArgumentException::class.java) { validate(location) } }
    }

    @Test fun manualVideoAddressDoesNotAcceptOtherProtocols() {
        listOf("content://provider/video", "file:///video.mp4", "ftp://media.example/video.mp4", "smb://media.example/video.mp4", "javascript:secret").forEach { location ->
            assertThrows(IllegalArgumentException::class.java) { validate(location) }
        }
    }
}
