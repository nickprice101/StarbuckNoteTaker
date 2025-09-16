package com.example.starbucknotetaker

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log

/**
 * Minimal stub implementation of the QSPM AIDL service. Some devices look up
 * this service by name; providing a no-op implementation prevents binder lookup
 * failures while keeping the functionality optional for the app.
 */
class QspmService : Service() {
    override fun onBind(intent: Intent?): IBinder {
        Log.d("QspmService", "Stub QSPM service bound")
        return Binder()
    }
}

