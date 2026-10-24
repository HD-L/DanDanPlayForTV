package tv.dandan.app.data

import org.junit.Assert.*
import org.junit.Test
import javax.crypto.KeyGenerator

class SecretCipherTest {
    @Test fun encryptedSecretsUseFreshIvAndRejectTampering() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val first = SecretCodec.encrypt("密码 secret", key)
        val second = SecretCodec.encrypt("密码 secret", key)
        assertNotEquals(first, second)
        assertFalse(first.contains("secret"))
        assertEquals("密码 secret", SecretCodec.decrypt(first, key))
        val parts = first.split(":").toMutableList()
        val bytes = java.util.Base64.getDecoder().decode(parts[2])
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        parts[2] = java.util.Base64.getEncoder().encodeToString(bytes)
        assertThrows(Exception::class.java) { SecretCodec.decrypt(parts.joinToString(":"), key) }
    }
}
