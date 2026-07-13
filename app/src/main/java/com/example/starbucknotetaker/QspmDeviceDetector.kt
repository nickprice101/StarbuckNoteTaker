package com.example.starbucknotetaker

import android.os.Build

/**
 * Detects devices that need the Qualcomm QSPM binder warmup path.
 */
internal object QspmDeviceDetector {

    fun shouldWarmUpCurrentDevice(): Boolean =
        shouldWarmUp(
            hardware = Build.HARDWARE,
            board = Build.BOARD,
            manufacturer = Build.MANUFACTURER,
            socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MANUFACTURER
            } else {
                null
            },
            socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL
            } else {
                null
            },
        )

    fun shouldWarmUp(
        hardware: String?,
        board: String?,
        manufacturer: String?,
        socManufacturer: String?,
        socModel: String?,
    ): Boolean =
        listOf(hardware, board, manufacturer, socManufacturer, socModel)
            .filterNotNull()
            .any { value ->
                value.contains("qcom", ignoreCase = true) ||
                    value.contains("qualcomm", ignoreCase = true) ||
                    value.contains("snapdragon", ignoreCase = true)
            }
}
