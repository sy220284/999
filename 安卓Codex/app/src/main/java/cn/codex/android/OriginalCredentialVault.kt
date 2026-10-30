package cn.codex.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 与原版 keyring 的 service/account 一一对应。落盘仅含 IV 和认证密文。 */
class OriginalCredentialVault(context: Context) {
    companion object { private val keyLock = Any() }
    private val directory = File(context.noBackupFilesDir, "原版凭证").apply { mkdirs() }
    private fun key(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("codex-original-vault-v1", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("codex-original-vault-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun identity(service: String, account: String): ByteArray {
        require(service.isNotEmpty() && account.isNotEmpty() && '\u0000' !in service && '\u0000' !in account)
        return (service + '\u0000' + account).toByteArray(Charsets.UTF_8)
    }
    private fun file(aad: ByteArray) = AtomicFile(File(directory, MessageDigest.getInstance("SHA-256").digest(aad).joinToString("") { "%02x".format(it) }))
    @Synchronized fun load(service: String, account: String): String? {
        val aad = identity(service, account); val target = file(aad)
        // AtomicFile.openRead 会恢复 .bak，不能只检查主文件是否存在。
        val bytes = try { target.openRead().use { require(it.channel.size() <= 1_048_700) { "凭证存储过大" }; it.readNBytes(1_048_701) } } catch (failure: java.io.FileNotFoundException) {
            if (target.baseFile.exists() || File(target.baseFile.path + ".bak").exists()) throw failure
            return null
        }
        require(bytes.size in 29..1_048_700 && bytes[0].toInt() == 1) { "凭证存储损坏" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13))); updateAAD(aad) }
        return cipher.doFinal(bytes.copyOfRange(13, bytes.size)).toString(Charsets.UTF_8)
    }
    @Synchronized fun save(service: String, account: String, value: String) {
        val aad = identity(service, account); val plain = value.toByteArray(Charsets.UTF_8)
        require(plain.size <= 1_048_576) { "凭证内容超过上限" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(aad) }
        val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
        check(cipher.iv.size == 12)
        val target = file(aad); val stream = target.startWrite()
        try { stream.write(byteArrayOf(1)); stream.write(cipher.iv); stream.write(encrypted); target.finishWrite(stream) }
        catch (failure: Exception) { target.failWrite(stream); throw failure }
    }
    @Synchronized fun delete(service: String, account: String): Boolean {
        val target = file(identity(service, account))
        val exists = try { target.openRead().use { }; true } catch (_: java.io.FileNotFoundException) { false }
        target.delete()
        check(!target.baseFile.exists() && !File(target.baseFile.path + ".bak").exists()) { "凭证删除失败" }
        return exists
    }
}
