package com.example.starbucknotetaker

import java.util.ArrayDeque
import java.util.Deque

/**
 * Coordinates temporary suppression of transient UI notifications (such as Toasts)
 * while sensitive interactions are in progress.
 */
object NotificationInterruptionManager {
    private val pendingCallbacks: Deque<() -> Unit> = ArrayDeque()
    private var blockCount: Int = 0

    @Synchronized
    fun blockNotifications() {
        blockCount += 1
    }

    @Synchronized
    fun releaseNotifications(): List<() -> Unit> {
        if (blockCount > 0) {
            blockCount -= 1
        }
        if (blockCount == 0 && pendingCallbacks.isNotEmpty()) {
            val callbacks = mutableListOf<() -> Unit>()
            while (pendingCallbacks.isNotEmpty()) {
                callbacks.add(pendingCallbacks.removeFirst())
            }
            return callbacks
        }
        return emptyList()
    }

    fun runOrQueue(callback: () -> Unit) {
        val shouldQueue = synchronized(this) {
            if (blockCount > 0) {
                pendingCallbacks.addLast(callback)
                true
            } else {
                false
            }
        }
        if (!shouldQueue) {
            callback()
        }
    }
}
