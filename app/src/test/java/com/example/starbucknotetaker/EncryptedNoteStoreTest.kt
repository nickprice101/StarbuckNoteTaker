package com.example.starbucknotetaker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EncryptedNoteStoreTest {
    private lateinit var context: Context
    private lateinit var notesFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notesFile = File(context.filesDir, "notes.enc")
        notesFile.delete()
    }

    @After
    fun tearDown() {
        notesFile.delete()
        File(context.filesDir, "attachments").deleteRecursively()
    }

    @Test
    fun saveAndLoadRoundTrip() {
        val notes = listOf(
            Note(title = "t", content = "c"),
            Note(
                title = "Checklist",
                content = listOf(ChecklistItem(text = "Task", isChecked = true)).asChecklistContent(),
                checklistItems = listOf(ChecklistItem(text = "Task", isChecked = true))
            )
        )
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
