package com.example.starbucknotetaker

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PinManager internal constructor(
    private val appContext: Context,
    private val prefs: SharedPreferences,
    private val legacyPrefs: SharedPreferences,
    private val attachmentStoreFactory: (Context) -> PinAttachmentStore,
    private val noteStoreFactory: (Context, PinAttachmentStore) -> PinNoteStore,
) {

    constructor(context: Context) : this(
        context.applicationContext,
        createEncryptedPreferences(context.applicationContext),
        context.applicationContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE),
        { AttachmentStore(it) },
        { ctx, attachments ->
            val concrete = attachments as? AttachmentStore ?: AttachmentStore(ctx)
            EncryptedNoteStore(ctx, concrete)
        }
    )

    init {
        migrateLegacyPinIfNeeded()
    }

    fun isPinSet(): Boolean = prefs.contains(KEY_PIN_HASH) || legacyPrefs.contains(KEY_PIN_LEGACY)

    fun setPin(pin: String) {
        val salt = generateSalt()
        val hash = hashPin(pin, salt)
        prefs.edit()
            .putString(KEY_PIN_HASH, hash)
            .putString(KEY_PIN_SALT, encode(salt))
            .putInt(KEY_PIN_LENGTH, pin.length)
            .remove(KEY_PIN_LEGACY)
            .apply()
        legacyPrefs.edit().remove(KEY_PIN_LEGACY).apply()
    }

    fun getStoredPin(): String? = prefs.getString(KEY_PIN_HASH, null)

    fun checkPin(pin: String): Boolean {
        val salt = prefs.getString(KEY_PIN_SALT, null) ?: return false
        val stored = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val computed = hashPin(pin, decode(salt) ?: return false)
        return constantTimeEquals(stored, computed)
    }

    fun getPinLength(): Int = prefs.getInt(KEY_PIN_LENGTH, 0)

    fun updatePin(oldPin: String, newPin: String): Boolean {
        if (!checkPin(oldPin)) {
            return false
        }
        setPin(newPin)
        return true
    }

    fun isBiometricEnabled(): Boolean = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }

    fun clearPin() {
        prefs.edit()
            .remove(KEY_PIN_HASH)
            .remove(KEY_PIN_SALT)
            .remove(KEY_PIN_LENGTH)
            .remove(KEY_BIOMETRIC_ENABLED)
            .remove(KEY_PIN_LEGACY)
            .apply()
        legacyPrefs.edit().remove(KEY_PIN_LEGACY).apply()
    }

    private fun migrateLegacyPinIfNeeded() {
        if (prefs.contains(KEY_PIN_HASH)) {
            return
        }
        val legacyPin = legacyPrefs.getString(KEY_PIN_LEGACY, null) ?: return
        val salt = generateSalt()
        val hash = hashPin(legacyPin, salt)
        reencryptStoredData(legacyPin, hash)
        prefs.edit()
            .putString(KEY_PIN_HASH, hash)
            .putString(KEY_PIN_SALT, encode(salt))
            .putInt(KEY_PIN_LENGTH, legacyPin.length)
            .remove(KEY_PIN_LEGACY)
            .apply()
        legacyPrefs.edit().remove(KEY_PIN_LEGACY).apply()
    }

    private fun reencryptStoredData(oldPin: String, newPin: String) {
        runCatching {
            val attachmentStore = attachmentStoreFactory(appContext)
            val noteStore = noteStoreFactory(appContext, attachmentStore)
            val notes = noteStore.loadNotes(oldPin)
            val attachmentIds = notes.flatMap { note ->
                note.images.mapNotNull { it.attachmentId } +
                    note.files.mapNotNull { it.attachmentId }
            }.toSet()
            attachmentIds.forEach { id ->
                runCatching { attachmentStore.reencryptAttachment(oldPin, newPin, id) }
            }
            noteStore.saveNotes(notes, newPin)
        }
    }

    private fun hashPin(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, HASH_ITERATIONS, HASH_KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        return encode(hash)
    }

    private fun generateSalt(): ByteArray {
        val bytes = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    private fun encode(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(value) }.getOrNull()

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val aBytes = a.toByteArray()
        val bBytes = b.toByteArray()
        if (aBytes.size != bBytes.size) return false
        var result = 0
        for (i in aBytes.indices) {
            result = result or (aBytes[i].toInt() xor bBytes[i].toInt())
        }
        return result == 0
    }

    companion object {
        private const val PREFS_NAME = "pin_prefs_secure"
        private const val LEGACY_PREFS_NAME = "pin_prefs"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PIN_SALT = "pin_salt"
        private const val KEY_PIN_LENGTH = "pin_length"
        private const val KEY_PIN_LEGACY = "pin"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val HASH_ITERATIONS = 10000
        private const val HASH_KEY_LENGTH = 256
        private const val SALT_LENGTH = 16

        private fun createEncryptedPreferences(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }
}
