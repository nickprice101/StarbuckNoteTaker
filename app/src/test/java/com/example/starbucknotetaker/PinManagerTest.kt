package com.example.starbucknotetaker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PinManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pin_prefs_secure", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences("pin_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        File(context.filesDir, "notes.enc").delete()
        val attachmentsDir = File(context.filesDir, "attachments")
        if (attachmentsDir.exists()) {
            attachmentsDir.deleteRecursively()
        }
    }

    @After
    fun tearDown() {
        File(context.filesDir, "notes.enc").delete()
        val attachmentsDir = File(context.filesDir, "attachments")
        if (attachmentsDir.exists()) {
            attachmentsDir.deleteRecursively()
        }
    }

    @Test
    fun setPinStoresHashedValue() {
        val manager = PinManager(context)

        manager.setPin("1234")

        assertTrue(manager.checkPin("1234"))
        assertFalse(manager.checkPin("0000"))
        assertEquals(4, manager.getPinLength())
        val stored = manager.getStoredPin()
        assertNotNull(stored)
        assertNotEquals("1234", stored)
    }

    @Test
    fun updatePinRehashesValue() {
        val manager = PinManager(context)
        manager.setPin("1234")
        val previous = manager.getStoredPin()

        val updated = manager.updatePin("1234", "5678")

        assertTrue(updated)
        assertTrue(manager.checkPin("5678"))
        assertFalse(manager.checkPin("1234"))
        assertEquals(4, manager.getPinLength())
        val stored = manager.getStoredPin()
        assertNotNull(stored)
        assertNotEquals("5678", stored)
        assertNotEquals(previous, stored)
    }

    @Test
    fun migratesLegacyPinAndReencryptsData() {
        val legacyPin = "2468"
        val legacyPrefs = context.getSharedPreferences("pin_prefs", Context.MODE_PRIVATE)
        legacyPrefs.edit().putString("pin", legacyPin).commit()

        val attachmentStore = AttachmentStore(context)
        val noteStore = EncryptedNoteStore(context, attachmentStore)
        val attachmentData = "hello".toByteArray()
        val attachmentId = attachmentStore.saveAttachment(legacyPin, attachmentData, "legacyAttachment")
        val note = Note(
            id = 1L,
            title = "Legacy",
            content = "Content",
            images = listOf(NoteImage(attachmentId = attachmentId))
        )
        noteStore.saveNotes(listOf(note), legacyPin)

        val manager = PinManager(context)

        assertTrue(manager.checkPin(legacyPin))
        val stored = manager.getStoredPin()
        assertNotNull(stored)
        assertNotEquals(legacyPin, stored)

        val reloaded = noteStore.loadNotes(stored!!)
        assertEquals(1, reloaded.size)
        val reloadedAttachment = attachmentStore.openAttachment(stored, attachmentId)
        assertNotNull(reloadedAttachment)
        assertArrayEquals(attachmentData, reloadedAttachment)
    }
}
