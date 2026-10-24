package tv.dandan.app.data

import javax.crypto.SecretKey
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import java.util.Base64

internal object SecretCodec {
    fun encrypt(text: String, key: SecretKey): String {
        if (text.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return "1:${Base64.getEncoder().encodeToString(cipher.iv)}:${Base64.getEncoder().encodeToString(cipher.doFinal(text.toByteArray(Charsets.UTF_8)))}"
    }
    fun decrypt(text: String, key: SecretKey): String {
        if (text.isEmpty()) return ""
        val parts = text.split(':')
        require(parts.size == 3 && parts[0] == "1") { "凭证存储格式不支持" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.getDecoder().decode(parts[1])))
        return cipher.doFinal(Base64.getDecoder().decode(parts[2])).toString(Charsets.UTF_8)
    }
}
