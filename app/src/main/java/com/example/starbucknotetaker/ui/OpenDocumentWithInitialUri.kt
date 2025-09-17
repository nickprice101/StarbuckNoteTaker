package com.example.starbucknotetaker.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts

class OpenDocumentWithInitialUri(private val initialUri: Uri?) : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val intent = super.createIntent(context, input)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            initialUri?.let { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }
        return intent
    }
}
