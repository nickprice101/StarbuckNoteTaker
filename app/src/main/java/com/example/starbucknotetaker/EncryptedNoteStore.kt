package com.example.starbucknotetaker

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class EncryptedNoteStore(private val context: Context) {
    private val file = File(context.filesDir, "notes.enc")

    fun loadNotes(pin: String): List<Note> {
        if (!file.exists()) return emptyList()
        val bytes = file.readBytes()
        if (bytes.size < 28) return emptyList()
        val salt = bytes.copyOfRange(0, 16)
        val iv = bytes.copyOfRange(16, 28)
        val cipherText = bytes.copyOfRange(28, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = deriveKey(pin, salt)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        val json = String(cipher.doFinal(cipherText), Charsets.UTF_8)
        val arr = JSONArray(json)
        val notes = mutableListOf<Note>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val imagesJson = obj.optJSONArray("images") ?: JSONArray()
            val images = mutableListOf<Uri>()
            for (j in 0 until imagesJson.length()) {
                images.add(Uri.parse(imagesJson.getString(j)))
            }
            notes.add(
                Note(
                    id = obj.getLong("id"),
                    title = obj.getString("title"),
                    content = obj.getString("content"),
                    date = obj.getLong("date"),
                    images = images
                )
            )
        }
        return notes
    }

    fun saveNotes(notes: List<Note>, pin: String) {
        val arr = JSONArray()
        notes.forEach { note ->
            val obj = JSONObject()
            obj.put("id", note.id)
            obj.put("title", note.title)
            obj.put("content", note.content)
            obj.put("date", note.date)
            val imagesArray = JSONArray()
            note.images.forEach { imagesArray.put(it.toString()) }
            obj.put("images", imagesArray)
            arr.put(obj)
        }
        val json = arr.toString().toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val key = deriveKey(pin, salt)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val cipherText = cipher.doFinal(json)
        val output = ByteArray(16 + 12 + cipherText.size)
        System.arraycopy(salt, 0, output, 0, 16)
        System.arraycopy(iv, 0, output, 16, 12)
        System.arraycopy(cipherText, 0, output, 28, cipherText.size)
        file.writeBytes(output)
    }

    private fun deriveKey(pin: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 10000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val bytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }
}

