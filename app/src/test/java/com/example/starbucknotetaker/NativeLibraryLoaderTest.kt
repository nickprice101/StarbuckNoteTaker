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
        resetTokenizerFlag()
    }

    @Test
    fun ensurePenguinReturnsTrueWhenLibraryLoads() {
        resetTokenizerFlag()
        NativeLibraryLoader.setLoadLibraryOverrideForTesting { }
        val loaded = NativeLibraryLoader.ensurePenguin(context)

        assertTrue(loaded)
        assertTrue(isTokenizerLoaded())
    }

    private fun resetTokenizerFlag() {
        tokenizerFlag().set(false)
    }

    private fun isTokenizerLoaded(): Boolean = tokenizerFlag().get()

    private fun tokenizerFlag(): AtomicBoolean {
        val field = NativeLibraryLoader::class.java.getDeclaredField("tokenizerLoaded")
        field.isAccessible = true
        return field.get(NativeLibraryLoader) as AtomicBoolean
    }
}