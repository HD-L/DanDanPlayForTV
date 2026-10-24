package tv.dandan.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal interface StoreStorage {
    fun read(key: String): String?
    /** 必须一次性写入；返回失败时不得发布任何字段的新值。 */
    fun write(changes: Map<String, String?>): Boolean
}
internal interface StoredSecrets {
    fun encrypt(value: String): String
    fun decrypt(value: String): String
}
internal object RepositoryTransactions {
    private val lock = Any()
    private var epoch = 0L
    fun <T> run(block: () -> T): T = synchronized(lock, block)
    fun historyEpoch(): Long = run { epoch }
    fun invalidateHistory() { run { epoch++ } }
}
/** 同目录临时文件经完整校验后原子移动，一份文件承载所有事务字段。 */
internal class AndroidStoreStorage(context: Context) : StoreStorage {
    private class State(val file: AtomicFile, var values: Map<String, String>)
    private val state: State
    init {
        val application = context.applicationContext
        val path = File(application.filesDir, "tv_data_v2.json")
        state = RepositoryTransactions.run {
            states.getOrPut(path.absolutePath) {
                val file = AtomicFile(path)
                val values = if (path.exists() || File(path.absolutePath + ".bak").exists()) {
                    val json = JSONObject(file.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
                    json.keys().asSequence().associateWith { json.getString(it) }
                } else application.getSharedPreferences("tv_data_v1", Context.MODE_PRIVATE).all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
                State(file, values)
            }
        }
    }
    override fun read(key: String): String? = RepositoryTransactions.run { state.values[key] }
    override fun write(changes: Map<String, String?>): Boolean = RepositoryTransactions.run {
        val next = state.values.toMutableMap()
        changes.forEach { (key, value) -> if (value == null) next.remove(key) else next[key] = value }
        val bytes = JSONObject(next).toString().toByteArray(Charsets.UTF_8)
        try {
            AtomicDiskFile.replace(state.file.baseFile, bytes)
            state.values = next
            true
        } catch (_: Exception) { false }
    }

    companion object { private val states = ConcurrentHashMap<String, State>() }
}
internal class KeystoreStoredSecrets : StoredSecrets {
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("dandan_tv_secrets_v1", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("dandan_tv_secrets_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun encrypt(value: String): String = RepositoryTransactions.run { SecretCodec.encrypt(value, key) }
    override fun decrypt(value: String): String = RepositoryTransactions.run { SecretCodec.decrypt(value, key) }
}
