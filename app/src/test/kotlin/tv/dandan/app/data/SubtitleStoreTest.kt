package tv.dandan.app.data

import org.junit.Assert.*
import org.junit.Test

class SubtitleStoreTest {
    @Test fun serviceSecretsAreEncryptedAndSettingsSurviveAnotherInstance() {
        val values = mutableMapOf<String, String>()
        val storage = object : StoreStorage {
            override fun read(key: String) = values[key]
            override fun write(changes: Map<String, String?>): Boolean { changes.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }; return true }
        }
        val secrets = object : StoredSecrets {
            override fun encrypt(value: String) = java.util.Base64.getEncoder().encodeToString(value.reversed().toByteArray())
            override fun decrypt(value: String) = String(java.util.Base64.getDecoder().decode(value)).reversed()
        }
        val prefs = SubtitlePreferences(SubtitleAccount("test-api-key", "test-user", "test-password"), false, "en")
        SubtitleStore(storage, secrets).save(prefs)
        assertEquals(prefs, SubtitleStore(storage, secrets).read())
        assertFalse(values.values.joinToString().contains("test-api-key")); assertFalse(values.values.joinToString().contains("test-password"))
    }
}
