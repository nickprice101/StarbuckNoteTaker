package com.example.starbucknotetaker

import android.content.Context
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class AttachmentStore(private val context: Context) {
    private val directory: File = File(context.filesDir, "attachments")
    private val secureRandom = SecureRandom()

    init {
        ensureDirectory()
    }

    fun saveAttachment(pin: String, bytes: ByteArray, id: String? = null): String {
        ensureDirectory()
        val attachmentId = id ?: UUID.randomUUID().toString()
        val salt = ByteArray(16).also(secureRandom::nextBytes)
        val iv = ByteArray(12).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = deriveKey(pin, salt)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val cipherText = cipher.doFinal(bytes)
        val output = ByteArray(16 + 12 + cipherText.size)
        System.arraycopy(salt, 0, output, 0, 16)
        System.arraycopy(iv, 0, output, 16, 12)
        System.arraycopy(cipherText, 0, output, 28, cipherText.size)
        attachmentFile(attachmentId).writeBytes(output)
        return attachmentId
    }

    fun openAttachment(pin: String, id: String): ByteArray? {
        val file = attachmentFile(id)
        if (!file.exists()) {
            return null
        }
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.size < 28) {
            return null
        }
        val salt = bytes.copyOfRange(0, 16)
        val iv = bytes.copyOfRange(16, 28)
        val cipherText = bytes.copyOfRange(28, bytes.size)
        val cipher = runCatching { Cipher.getInstance("AES/GCM/NoPadding") }.getOrNull()
            ?: return null
        val key = deriveKey(pin, salt)
        return runCatching {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.doFinal(cipherText)
        }.getOrNull()
    }

    fun deleteAttachment(id: String) {
        runCatching { attachmentFile(id).takeIf(File::exists)?.delete() }
    }

    fun reencryptAttachment(oldPin: String, newPin: String, id: String): Boolean {
        val plain = openAttachment(oldPin, id) ?: return false
        saveAttachment(newPin, plain, id)
        return true
    }

    private fun attachmentFile(id: String): File {
        return File(directory, "$id.bin")
    }

    private fun ensureDirectory() {
        if (!directory.exists()) {
            directory.mkdirs()
        }
    }

    private fun deriveKey(pin: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 10000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }
}
