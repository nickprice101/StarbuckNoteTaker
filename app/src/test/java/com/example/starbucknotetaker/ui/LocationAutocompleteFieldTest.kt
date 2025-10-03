package com.example.starbucknotetaker.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LocationAutocompleteFieldTest {

    @Test
    fun `venue-aware fallback preserves user venue name`() {
        val fallback = EventLocationDisplay(
            name = "Lijnbaansgracht 234A",
            address = "1017 PH Amsterdam",
        )

        val display = createVenueAwareFallbackDisplay("Melkweg", fallback)

        assertNotNull("Expected venue-aware display", display)
        assertEquals("Melkweg", display!!.name)
        assertEquals(
            "Melkweg, Lijnbaansgracht 234A, 1017 PH Amsterdam",
            display.toQueryString(),
        )
    }
}
