package com.example.starbucknotetaker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

class BiometricUnlockActivity : AppCompatActivity() {
    private val noteViewModel: NoteViewModel by viewModels()
    
    companion object {
        const val EXTRA_NOTE_ID = "extra_note_id"
        const val EXTRA_NOTE_TITLE = "extra_note_title"
        
        fun createIntent(context: Context, noteId: Long, noteTitle: String): Intent {
            return Intent(context, BiometricUnlockActivity::class.java).apply {
                putExtra(EXTRA_NOTE_ID, noteId)
                putExtra(EXTRA_NOTE_TITLE, noteTitle)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
        val noteTitle = intent.getStringExtra(EXTRA_NOTE_TITLE) ?: "Note"
        
        if (noteId == -1L) {
            Log.e(BIOMETRIC_LOG_TAG, "BiometricUnlockActivity: Invalid note ID")
            finish()
            return
        }
        
        Log.d(BIOMETRIC_LOG_TAG, "BiometricUnlockActivity: Starting biometric authentication for noteId=$noteId")
        startBiometricAuthentication(noteId, noteTitle)
    }
    
    private fun startBiometricAuthentication(noteId: Long, noteTitle: String) {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                Log.d(BIOMETRIC_LOG_TAG, "BiometricUnlockActivity: Authentication SUCCESS for noteId=$noteId")
                
                // Mark note as temporarily unlocked
                noteViewModel.markNoteTemporarilyUnlocked(noteId)
                
                // Navigate directly to note detail using Intent
                val detailIntent = Intent(this@BiometricUnlockActivity, MainActivity::class.java).apply {
                    putExtra("navigate_to_note", noteId)
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                }
                
                Log.d(BIOMETRIC_LOG_TAG, "BiometricUnlockActivity: Starting MainActivity with noteId=$noteId")
                startActivity(detailIntent)
                finish()
            }
            
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Log.d(BIOMETRIC_LOG_TAG, "BiometricUnlockActivity: Authentication ERROR code=$errorCode message=\"$errString\"")
                
                when (errorCode) {
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_USER_CANCELED -> {
                        // User chose to use PIN instead or canceled
                        val mainIntent = Intent(this@BiometricUnlockActivity, MainActivity::class.java).apply {
                            putExtra("show_pin_for_note", noteId)
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(mainIntent)
                        finish()
                    }
                    else -> {
                        Toast.makeText(this@BiometricUnlockActivity, errString, Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
            
            override fun onAuthenticationFailed() {
                Log.d(BIOMETRIC_LOG_TAG, "BiometricUnlockActivity: Authentication FAILED")
                // Don't finish - let user try again
            }
        })
        
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock note")
            .setSubtitle("Authenticate to open \"$noteTitle\"")
            .setNegativeButtonText("Use PIN")
            .build()
            
        biometricPrompt.authenticate(promptInfo)
    }
}
