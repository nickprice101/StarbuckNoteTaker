package com.example.starbucknotetaker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StarbuckNoteTakerAppTest {

    @Test
    fun qspmWarmUp_skipsGenericX86Emulator() {
        assertFalse(
            QspmDeviceDetector.shouldWarmUp(
                hardware = "ranchu",
                board = "emu64xa",
                manufacturer = "Google",
                socManufacturer = null,
                socModel = "x86_64",
            )
        )
    }

    @Test
    fun qspmWarmUp_runsOnQualcommDevices() {
        assertTrue(
            QspmDeviceDetector.shouldWarmUp(
                hardware = "qcom",
                board = "kalama",
                manufacturer = "Qualcomm",
                socManufacturer = "Qualcomm",
                socModel = "Snapdragon",
            )
        )
    }
}
