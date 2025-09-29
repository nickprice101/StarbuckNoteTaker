package com.example.starbucknotetaker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

class BiometricUnlockActivity : AppCompatActivity() {
    
    companion object {
        const val EXTRA_NOTE_ID = "extra_note_id"
        const val EXTRA_NOTE_TITLE = "extra_note_title"
        
        fun createIntent(context: Context, noteId: Long, noteTitle: String): Intent {
            return Intent(context, BiometricUnlockActivity::class.java).apply {
                putExtra(EXTRA_NOTE_ID, noteId)
                putExtra(EXTRA_NOTE_TITLE, noteTitle)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d(BIOMETRIC_LOG_TAG, "BiometricUnlockActivity: onCreate called")
        
        val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
        val noteTitle = intent.getStringExtra(EXTRA_NOTE_TITLE) ?: "Note"
        
        if (noteId == -1L) {
            Log.e(BIOMETRIC_LOG_TAG, "BiometricUnlockActivity: Invalid note ID")
            finish()
            return
        }
        
        Log.d(BIOMETRIC_LOG_TAG, "BiometricUnlockActivity: Starting biometric authentication for noteId=$noteId")
        
        // Start biometric authentication immediately
        startBiometricAuthentication(noteId, noteTitle)
    }
    
    private fun startBiometricAuthentication(noteId: Long, noteTitle: String) {
        try {
            val executor = ContextCompat.getMainExecutor(this)
            val biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    Log.d(BIOMETRIC_LOG_TAG, "BiometricUnlockActivity: Authentication SUCCESS for noteId=$noteId")
                    
                    // CRITICAL FIX: Don't use ViewModel here - pass the unlock result back to MainActivity
                    // The MainActivity will handle marking the note as unlocked in its own ViewModel instance
                    
                    val resultIntent = Intent().apply {
                        putExtra("biometric_unlock_success", true)
                        putExtra("unlocked_note_id", noteId)
                    }
                    setResult(RESULT_OK, resultIntent)
                    
                    Log.d(BIOMETRIC_LOG_TAG, "BiometricUnlockActivity: Finishing with success result")
                    finish()
                }
                
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Log.d(BIOMETRIC_LOG_TAG, "BiometricUnlockActivity: Authentication ERROR code=$errorCode message=\"$errString\"")
                    
                    when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_USER_CANCELED -> {
                            // User chose to use PIN instead
                            val resultIntent = Intent().apply {
                                putExtra("biometric_unlock_success", false)
                                putExtra("use_pin_instead", true)
                                putExtra("note_id_for_pin", noteId)
                            }
                            setResult(RESULT_OK, resultIntent)
                        }
                        else -> {
                            // Other errors
                            Toast.makeText(this@BiometricUnlockActivity, errString, Toast.LENGTH_LONG).show()
                            setResult(RESULT_CANCELED)
                        }
                    }
                    finish()
                }
                
                override fun onAuthenticationFailed() {
                    Log.d(BIOMETRIC_LOG_TAG, "BiometricUnlockActivity: Authentication FAILED - letting user retry")
                    // Don't finish - let user try again
                }
            })
            
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock note")
                .setSubtitle("Authenticate to open \"$noteTitle\"")
                .setNegativeButtonText("Use PIN")
                .build()
                
            biometricPrompt.authenticate(promptInfo)
            
        } catch (e: Exception) {
            Log.e(BIOMETRIC_LOG_TAG, "BiometricUnlockActivity: Error starting biometric prompt", e)
            Toast.makeText(this, "Biometric authentication failed to start", Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
        }
    }
}
