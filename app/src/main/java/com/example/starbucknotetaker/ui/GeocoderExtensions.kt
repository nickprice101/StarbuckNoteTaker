package com.example.starbucknotetaker.ui

import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal suspend fun Geocoder.getFromLocationNameCompat(
    locationName: String,
    maxResults: Int
): List<Address> {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                getFromLocationName(
                    locationName,
                    maxResults,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (continuation.isActive) {
                                continuation.resume(addresses)
                            }
                        }

                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) {
                                continuation.resume(emptyList())
                            }
                        }
                    }
                )
                continuation.invokeOnCancellation { }
            }
        } else {
            @Suppress("DEPRECATION")
            getFromLocationName(locationName, maxResults) ?: emptyList()
        }
    } catch (throwable: Exception) {
        emptyList()
    }
}
