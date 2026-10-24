package tv.dandan.app.ui

import org.junit.Assert.*
import org.junit.Test
import tv.dandan.app.data.SourceRules
import tv.dandan.app.remote.PhoneFormSession
import tv.dandan.core.MediaSource
import tv.dandan.core.SourceKind

class SourceFormRulesTest {
    @Test fun allFourNetworkTypesValidateThroughTheSamePhoneFormAndSaveRules() {
        val fixtures = listOf(SourceKind.HTTP to "https://nas.example/video.mp4", SourceKind.WEBDAV to "https://nas.example/dav/",
            SourceKind.SMB to "smb://nas.example/share/", SourceKind.FTP to "ftp://nas.example/video/")
        fixtures.forEach { (kind, location) ->
            val session = PhoneFormSession(SourceFormRules.form())
            val values = session.submit(session.id, mapOf("kind" to kind.name, "name" to "家庭媒体", "location" to location,
                "username" to "viewer", "password" to "stored-secret"), validator = { SourceFormRules.validate(it) })
            val saved = SourceFormRules.candidate(values, newId = "saved-source")
            assertEquals(kind, saved.kind)
            assertEquals(location, saved.location)
            assertEquals("saved-source", saved.id)
            SourceRules.validate(saved)
            assertFalse(session.isOpen())
        }
    }

    @Test fun localFormNeedsANameButPhoneCannotGrantDirectoryPermission() {
        val session = PhoneFormSession(SourceFormRules.form())
        val values = session.submit(session.id, mapOf("kind" to "DOCUMENT_TREE", "name" to "本地视频", "location" to "content://untrusted/tree/fake",
            "username" to "hidden-user", "password" to "hidden-secret"), validator = { SourceFormRules.validate(it) })
        val pending = SourceFormRules.candidate(values)
        assertEquals(SourceKind.DOCUMENT_TREE, pending.kind)
        assertEquals("", pending.location)
        assertEquals("", pending.username)
        assertEquals("", pending.password)
        assertThrows(IllegalArgumentException::class.java) { SourceRules.validate(pending) }
        assertThrows(IllegalArgumentException::class.java) { SourceFormRules.withDirectory(pending, "file:///storage/video") }
        assertEquals("content://provider/tree/root", SourceFormRules.withDirectory(pending, "content://provider/tree/root").location)
    }

    @Test fun changingToHttpClearsConditionalNetworkCredentialsAndPreservesSourceId() {
        val original = MediaSource("old-source", "NAS", SourceKind.WEBDAV, "https://nas.example/dav/", "viewer", "old-secret")
        val session = PhoneFormSession(SourceFormRules.form(original))
        val values = session.submit(session.id, mapOf("kind" to "HTTP", "name" to "在线视频", "location" to "https://nas.example/video.mkv"),
            validator = { SourceFormRules.validate(it, original) })
        val edited = SourceFormRules.candidate(values, original)
        assertEquals(SourceKind.HTTP, edited.kind)
        assertEquals("old-source", edited.id)
        assertEquals("", edited.username)
        assertEquals("", edited.password)
    }

    @Test fun localEditKeepsExistingSystemDirectoryButNetworkToLocalNeedsSelection() {
        val values = mapOf("kind" to "DOCUMENT_TREE", "name" to "本地视频", "location" to "content://untrusted/tree/phone")
        val local = MediaSource("local", "电影", SourceKind.DOCUMENT_TREE, "content://provider/tree/granted")
        val network = MediaSource("network", "网络", SourceKind.FTP, "ftp://nas.example/video/")
        assertEquals("content://provider/tree/granted", SourceFormRules.candidate(values, local).location)
        assertEquals("", SourceFormRules.candidate(values, network).location)
    }

    @Test fun invalidNetworkAddressStaysInTheSameFormUntilCorrected() {
        val session = PhoneFormSession(SourceFormRules.form())
        val wrong = mapOf("kind" to "WEBDAV", "name" to "NAS", "location" to "https://user:secret@nas.example/dav/")
        assertThrows(IllegalArgumentException::class.java) { session.submit(session.id, wrong, validator = { SourceFormRules.validate(it) }) }
        assertTrue(session.isOpen())
        val valid = session.submit(session.id, wrong + ("location" to "https://nas.example/dav/"), validator = { SourceFormRules.validate(it) })
        assertEquals("https://nas.example/dav/", SourceFormRules.candidate(valid).location)
    }

    @Test fun unknownTypeOrMissingNameCannotConsumeThePhoneForm() {
        val session = PhoneFormSession(SourceFormRules.form())
        assertThrows(IllegalArgumentException::class.java) { session.submit(session.id, mapOf("kind" to "NFS", "name" to "NAS"), validator = { SourceFormRules.validate(it) }) }
        assertTrue(session.isOpen())
        assertNotNull(SourceFormRules.validate(mapOf("kind" to "NFS", "name" to "NAS")))
        assertNotNull(SourceFormRules.validate(mapOf("kind" to "DOCUMENT_TREE", "name" to "")))
        assertNotNull(SourceFormRules.validate(mapOf("kind" to "DOCUMENT_TREE", "name" to "名".repeat(201))))
    }

    @Test fun hiddenLocationAndAuthFieldsAreOptionalForLocalSubmission() {
        val session = PhoneFormSession(SourceFormRules.form())
        val submitted = session.submit(session.id, mapOf("kind" to "DOCUMENT_TREE", "name" to "影片"), validator = { SourceFormRules.validate(it) })
        assertEquals("", submitted["location"])
        assertEquals("", submitted["username"])
        assertEquals("", submitted["password"])
    }
}
