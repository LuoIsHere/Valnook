package dev.valnook.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import androidx.room.withTransaction
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.database.WalletPrivateIdentity
import dev.valnook.domain.repository.WalletPrivateContent
import dev.valnook.domain.repository.WalletPrivateRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.*
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SECURITY: No network/export capability. Only linkage UUIDs enter Room; all values stay in this vault.
 * 安全边界：卡号、有效期、CVV 和配色只能进入此加密存储，禁止写入普通数据库、日志或导出模型。
 * `persistent=false` is exclusively for isolated tests/disabled demo sessions; never use it as a crypto fallback.
 * persistent=false 仅用于隔离测试及不提供卡背功能的演示会话；真实模式始终加密，失败必须报错。
 */
class LocalWalletPrivateRepository(
    context: Context,
    private val db: ValnookDatabase,
    persistent: Boolean = true,
    directoryName: String = "wallet-private-v1"
) : WalletPrivateRepository {
    private val mutex = Mutex()
    private val records: PrivateRecords = if (persistent) EncryptedPrivateRecords(
        File(context.noBackupFilesDir, directoryName), "${context.packageName}.$directoryName") else MemoryPrivateRecords()

    override suspend fun read(cardId: Long): WalletPrivateContent = withContext(Dispatchers.IO) {
        mutex.withLock { db.withTransaction {
            checkReadable(cardId)
            val token = db.walletPrivateIdentity().token(cardId) ?: return@withTransaction WalletPrivateContent()
            decode(requireNotNull(records.read(token)) { "Private card storage unavailable" })
        } }
    }

    override suspend fun save(cardId: Long, content: WalletPrivateContent): WalletPrivateContent {
        content.validated()
        return withContext(Dispatchers.IO + NonCancellable) { mutex.withLock { db.withTransaction {
            checkReadable(cardId)
            val existing = db.walletPrivateIdentity().token(cardId)
            val previous = existing?.let { decode(requireNotNull(records.read(it))) } ?: WalletPrivateContent()
            check(content.revision == previous.revision) { "Private card changed; reopen editor" }
            val token = existing ?: UUID.randomUUID().toString()
            val next = content.copy(revision = previous.revision + 1)
            if (existing == null) db.walletPrivateIdentity().insert(WalletPrivateIdentity(cardId, token))
            records.write(token, encode(next))
            next
        } } }
    }

    override suspend fun warningDismissed(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { records.read("preferences")?.contentEquals(byteArrayOf(1)) == true }
    }
    override suspend fun dismissWarning() = withContext(Dispatchers.IO) {
        mutex.withLock { records.write("preferences", byteArrayOf(1)) }
    }

    /** FK deletion/recovery commits revoke access first; orphan cleanup is safe to repeat after a crash. */
    suspend fun cleanup(clearPreferences: Boolean = false) = withContext(Dispatchers.IO) {
        mutex.withLock { db.withTransaction {
            val live = db.walletPrivateIdentity().tokens().toSet()
            records.names().filter { it !in live && (it != "preferences" || clearPreferences) }.forEach(records::delete)
            if(clearPreferences && live.isEmpty())records.destroyKey()
        } }
    }

    private suspend fun checkReadable(cardId: Long) {
        check(!db.audit().maintenanceState().maintenance_in_progress) { "Maintenance in progress" }
        checkNotNull(db.wallet().card(cardId)) { "Card no longer exists" }
    }

    private fun encode(value: WalletPrivateContent): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { out ->
            out.writeInt(2)
            out.writeUTF(value.number); out.writeUTF(value.expiry); out.writeUTF(value.cvv1); out.writeUTF(value.cvv2)
            out.writeLong(value.backColor); out.writeLong(value.edgeColor); out.writeLong(value.revision)
            out.writeBoolean(value.showNumberSpacing)
        }
        buffer.toByteArray()
    }
    private fun decode(bytes: ByteArray): WalletPrivateContent = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.readInt()
            check(version in 1..2)
            // Device-only format upgrade: keep v1 readable without exposing or truncating old values.
            // 仅升级本机密文格式；旧版默认显示间隔，旧 CVV 保留，新保存严格校验，不进入任何导出。
            WalletPrivateContent(input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(),
                input.readLong(), input.readLong(), input.readLong(),
                if(version >= 2) input.readBoolean() else true)
                .validated(legacyCvv = version == 1).also { check(input.available() == 0) }
        }
    } finally { bytes.fill(0) }
}

private interface PrivateRecords {
    fun read(name: String): ByteArray?
    fun write(name: String, bytes: ByteArray)
    fun names(): Set<String>
    fun delete(name: String)
    fun destroyKey()
}

private class MemoryPrivateRecords : PrivateRecords {
    private val values = mutableMapOf<String, ByteArray>()
    override fun read(name: String) = values[name]?.copyOf()
    override fun write(name: String, bytes: ByteArray) { values.put(name, bytes.copyOf())?.fill(0); bytes.fill(0) }
    override fun names() = values.keys.toSet()
    override fun delete(name: String) { values.remove(name)?.fill(0) }
    override fun destroyKey() = Unit
}

private class EncryptedPrivateRecords(private val directory: File, private val alias: String) : PrivateRecords {
    // SECURITY: Keep the key in Android Keystore, ciphertext in noBackupFilesDir, and fresh GCM IVs per write.
    // 安全边界：密钥不得导出，文件不得搬到共享/备份目录，nonce 不得复用。不得增加明文兼容或失败降级。
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Local encryption key unavailable" }
        check(directory.listFiles().orEmpty().none { it.name.contains(".bin") }) { "Local encryption key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    private fun file(name: String): AtomicFile {
        require(name == "preferences" || runCatching { UUID.fromString(name).toString() == name }.getOrDefault(false))
        return AtomicFile(File(directory, "$name.bin"))
    }
    override fun read(name: String): ByteArray? {
        val file = file(name)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().use { source ->
            val packed = source.readNBytes(8193)
            check(packed.size in 29..8192)
            check(packed[0] == 1.toByte())
            // Authentication failure must propagate; never display unauthenticated or substituted plaintext.
            // 验证失败必须中止读取；禁止返回未认证数据、旧缓存或明文“兼容”结果。
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, packed.copyOfRange(1, 13)))
                updateAAD(name.toByteArray(Charsets.UTF_8))
            }.doFinal(packed, 13, packed.size - 13)
        }
    }
    override fun write(name: String, bytes: ByteArray) {
        try {
            check(directory.isDirectory || directory.mkdirs())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key(true)); updateAAD(name.toByteArray(Charsets.UTF_8))
            }
            val encrypted = cipher.doFinal(bytes)
            val file = file(name)
            val stream = file.startWrite()
            try {
                stream.write(1); stream.write(cipher.iv); stream.write(encrypted); file.finishWrite(stream)
            } catch (error: Exception) { file.failWrite(stream); throw error }
        } finally { bytes.fill(0) }
    }
    override fun names(): Set<String> = directory.listFiles().orEmpty().mapNotNull { file ->
        file.name.removeSuffix(".bak").removeSuffix(".new").takeIf { it.endsWith(".bin") }?.removeSuffix(".bin")
    }.toSet()
    override fun delete(name: String) { file(name).delete() }
    override fun destroyKey() { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
}
