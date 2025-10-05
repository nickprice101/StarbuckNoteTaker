package com.example.starbucknotetaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPreviewFetcherTest {

    @Test
    fun extractUrls_marksTerminalUrlComplete_whenLooksComplete() {
        val detections = extractUrls("https://example.com")

        assertEquals(1, detections.size)
        assertTrue(detections[0].isComplete)
    }

    @Test
    fun extractUrls_doesNotPrematurelyCompletePartialUrl() {
        val detections = extractUrls("https://example")

        assertEquals(1, detections.size)
        assertFalse(detections[0].isComplete)
    }

    @Test
    fun extractUrls_marksLocalhostWithPortComplete() {
        val detections = extractUrls("Visit http://localhost:3000")

        assertEquals(1, detections.size)
        assertTrue(detections[0].isComplete)
    }
}
