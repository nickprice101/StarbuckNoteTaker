package com.example.starbucknotetaker

import android.content.Context
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.util.concurrent.atomic.AtomicBoolean

class NativeLibraryLoaderTest {
    private val context: Context = mock()

    @After
    fun tearDown() {
        NativeLibraryLoader.setLoadLibraryOverrideForTesting(null)
        resetPenguinFlag()
    }

    @Test
    fun ensurePenguinReturnsTrueWhenLibraryLoads() {
        NativeLibraryLoader.setLoadLibraryOverrideForTesting { }
        val loaded = NativeLibraryLoader.ensurePenguin(context)
        assertTrue(loaded)
        assertTrue(isPenguinLoaded())
    }

    private fun resetPenguinFlag() {
        val field = NativeLibraryLoader::class.java.getDeclaredField("penguinLoaded")
        field.isAccessible = true
        val flag = field.get(NativeLibraryLoader) as AtomicBoolean
        flag.set(false)
    }

    private fun isPenguinLoaded(): Boolean {
        val field = NativeLibraryLoader::class.java.getDeclaredField("penguinLoaded")
        field.isAccessible = true
        val flag = field.get(NativeLibraryLoader) as AtomicBoolean
        return flag.get()
    }
}
