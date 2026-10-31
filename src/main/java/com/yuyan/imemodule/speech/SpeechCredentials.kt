package com.yuyan.imemodule.speech

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 凭据仅在运行时写入禁止备份的私有目录，密文密钥由 Android Keystore 持有。 */
class SpeechCredentials(context: Context) {
    private val directory = File(context.noBackupFilesDir, "speech_credentials")

    private fun file(providerId: String): AtomicFile {
        require(providerId.matches(Regex("[a-zA-Z0-9_-]+"))) { "Invalid speech provider ID" }
        return AtomicFile(File(directory, "$providerId.bin"))
    }

    @Synchronized
    fun read(providerId: String): String? {
        val source = file(providerId)
        if (!source.baseFile.exists()) return null
        return runCatching {
            val bytes = source.readFully()
            require(bytes.size > 12) { "Invalid credential data" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(providerId.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    fun write(providerId: String, apiKey: String) {
        val target = file(providerId)
        if (apiKey.isBlank()) {
            target.delete()
            return
        }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create credential directory" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(providerId.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.iv + cipher.doFinal(apiKey.trim().toByteArray(Charsets.UTF_8))
        val stream = target.startWrite()
        try {
            stream.write(encrypted)
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    companion object { private const val ALIAS = "yuyan_speech_credentials_v1" }
}
