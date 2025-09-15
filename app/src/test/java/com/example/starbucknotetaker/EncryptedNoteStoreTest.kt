package com.example.starbucknotetaker

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class EncryptedNoteStoreTest {
    private val context: Context = mock()
    private val filesDir: File = Files.createTempDirectory("encstore").toFile()

    init {
        whenever(context.filesDir).thenReturn(filesDir)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun saveAndLoadRoundTrip() {
        val notes = listOf(Note(title = "t", content = "c"))
        val store = EncryptedNoteStore(context)
        store.saveNotes(notes, "1234")
        val loaded = store.loadNotes("1234")
        assertEquals(notes, loaded)
    }

    @Test
    fun loadLegacyFormat() {
        val notes = listOf(Note(title = "t", content = "c"))
        val bytes = legacyBytes(notes, "1234")
        val store = EncryptedNoteStore(context)
        val loaded = store.loadNotesFromBytes(bytes, "1234")
        assertEquals(notes, loaded)
    }

    private fun legacyBytes(notes: List<Note>, pin: String): ByteArray {
        val note = notes[0]
        val json = """
            [{"id":${note.id},"title":"${note.title}","content":"${note.content}","date":${note.date},"images":[],"files":[],"summary":"${note.summary}"}]
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val spec = PBEKeySpec(pin.toCharArray(), salt, 10000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        val key = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val cipherText = cipher.doFinal(json)
        return ByteArray(16 + 12 + cipherText.size).apply {
            System.arraycopy(salt, 0, this, 0, 16)
            System.arraycopy(iv, 0, this, 16, 12)
            System.arraycopy(cipherText, 0, this, 28, cipherText.size)
        }
    }
}

