package com.example.starbucknotetaker

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.AlertDialog
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.starbucknotetaker.ui.AddNoteScreen
import com.example.starbucknotetaker.ui.EditNoteScreen
import com.example.starbucknotetaker.ui.NoteDetailScreen
import com.example.starbucknotetaker.ui.NoteEntryMode
import com.example.starbucknotetaker.ui.NoteListScreen
import com.example.starbucknotetaker.ui.PinEnterScreen
import com.example.starbucknotetaker.ui.PinSetupScreen
import com.example.starbucknotetaker.ui.SettingsScreen
import com.example.starbucknotetaker.ui.StarbuckNoteTakerTheme
import com.example.starbucknotetaker.BiometricOptInReplayGuard.ClearAction
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay

class MainActivity : AppCompatActivity() {
    private val noteViewModel: NoteViewModel by viewModels()

    @VisibleForTesting
    internal fun getNoteViewModelForTest(): NoteViewModel = noteViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)

        val pinManager = PinManager(applicationContext)
        setContent {
            StarbuckNoteTakerTheme {
                val navController = rememberNavController()
                AppContent(navController, noteViewModel, pinManager)
            }
        }

        handleReminderIntent(intent)
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleReminderIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleReminderIntent(intent: Intent?) {
        val noteId = intent?.getLongExtra(EXTRA_NOTE_ID, -1L)?.takeIf { it > 0 } ?: return
        noteViewModel.handleReminderNavigation(noteId)
        intent.removeExtra(EXTRA_NOTE_ID)
    }

    private fun handleShareIntent(intent: Intent?) {
        val sourceIntent = intent ?: return
        val pending = when (sourceIntent.action) {
            Intent.ACTION_SEND -> parseShareSendIntent(sourceIntent)
            Intent.ACTION_SEND_MULTIPLE -> parseShareSendMultipleIntent(sourceIntent)
            else -> null
        } ?: return

        noteViewModel.setPendingShare(pending)
        persistSharedUris(pending)

        sourceIntent.action = null
        sourceIntent.type = null
        sourceIntent.removeExtra(Intent.EXTRA_TEXT)
        sourceIntent.removeExtra(Intent.EXTRA_SUBJECT)
        sourceIntent.removeExtra(Intent.EXTRA_TITLE)
        sourceIntent.removeExtra(Intent.EXTRA_STREAM)
        sourceIntent.clipData = null
    }

    private fun parseShareSendIntent(intent: Intent): PendingShare? {
        val title = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            ?: intent.getStringExtra(Intent.EXTRA_TITLE)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val stream = intent.getParcelableExtraUri(Intent.EXTRA_STREAM)
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

        val images = mutableListOf<Uri>()
        val files = mutableListOf<Uri>()
        stream?.let { uri ->
            if (isImageType(resolveStreamType(intent, uri))) {
                images += uri
            } else {
                files += uri
            }
        }

        if (title.isNullOrBlank() && text.isNullOrBlank() && images.isEmpty() && files.isEmpty()) {
            return null
        }

        return PendingShare(
            title = title,
            text = text,
            images = images,
            files = files,
        )
    }

    private fun parseShareSendMultipleIntent(intent: Intent): PendingShare? {
        val title = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            ?: intent.getStringExtra(Intent.EXTRA_TITLE)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val uris = intent.getParcelableArrayListExtraUris(Intent.EXTRA_STREAM)
            ?: extractClipDataUris(intent.clipData)

        if (title.isNullOrBlank() && text.isNullOrBlank() && (uris == null || uris.isEmpty())) {
            return null
        }

        val images = mutableListOf<Uri>()
        val files = mutableListOf<Uri>()
        uris?.forEach { uri ->
            if (isImageType(resolveStreamType(intent, uri))) {
                images += uri
            } else {
                files += uri
            }
        }

        return PendingShare(
            title = title,
            text = text,
            images = images,
            files = files,
        )
    }

    private fun resolveStreamType(intent: Intent, uri: Uri): String? {
        val explicitType = intent.type?.takeUnless { it == "*/*" }
        if (!explicitType.isNullOrBlank()) {
            return explicitType
        }
        return try {
            contentResolver.getType(uri) ?: explicitType
        } catch (_: SecurityException) {
            explicitType
        }
    }

    private fun isImageType(type: String?): Boolean {
        return type?.startsWith("image/") == true
    }

    private fun persistSharedUris(share: PendingShare) {
        val uris = share.images + share.files
        uris.forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
        }
    }

    private fun extractClipDataUris(clipData: android.content.ClipData?): List<Uri>? {
        if (clipData == null || clipData.itemCount == 0) return null
        val uris = mutableListOf<Uri>()
        for (index in 0 until clipData.itemCount) {
            val uri = clipData.getItemAt(index)?.uri
            if (uri != null) {
                uris += uri
            }
        }
        return uris
    }

    private fun Intent.getParcelableExtraUri(name: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name)
        }
    }

    private fun Intent.getParcelableArrayListExtraUris(name: String): List<Uri>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(name, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableArrayListExtra(name)
        }
    }

    companion object {
        const val ACTION_VIEW_NOTE_FROM_REMINDER = "com.example.starbucknotetaker.action.VIEW_NOTE"
        const val EXTRA_NOTE_ID = "extra_note_id"
    }
}

@Composable
fun AppContent(navController: NavHostController, noteViewModel: NoteViewModel, pinManager: PinManager) {
    val context = LocalContext.current
    val activity = remember(context) { context as AppCompatActivity }
    val executor = remember(activity) { ContextCompat.getMainExecutor(activity) }
    val biometricManager = remember(activity) { BiometricManager.from(activity) }
    val summarizerState by noteViewModel.summarizerState.collectAsState()
    val pendingShare by noteViewModel.pendingShare.collectAsState()
    val pendingOpenNoteId by noteViewModel.pendingOpenNoteId.collectAsState()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentActivityState = rememberUpdatedState(activity)
    val isPinSet = pinManager.isPinSet()
    var hasLoadedInitialPin by remember { mutableStateOf(false) }
    var pendingUnlockNoteId by remember { mutableStateOf<Long?>(null) }
    var biometricsEnabled by remember { mutableStateOf(pinManager.isBiometricEnabled()) }
    val biometricUnlockRequest by noteViewModel.biometricUnlockRequest.collectAsState()
    val biometricUnlockRequestState = rememberUpdatedState(biometricUnlockRequest)
    val biometricOptInReplayGuard = remember(noteViewModel) {
        BiometricOptInReplayGuard(
            logger = { message -> Log.d(BIOMETRIC_LOG_TAG, message) },
            notifyBiometricLog = { message -> BiometricPromptTestHooks.notifyBiometricLog(message) },
            currentBiometricUnlockRequest = { noteViewModel.currentBiometricUnlockRequest() },
            currentActiveRequestToken = { biometricUnlockRequestState.value?.token },
        )
    }
    var showBiometricOptIn by remember { mutableStateOf(false) }
    val pendingBiometricOptIn by biometricOptInReplayGuard.pendingOptIn
    val biometricPromptTrigger by biometricOptInReplayGuard.promptTrigger
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // DEBUG: Navigation state tracking
    var navigationAttempts by remember { mutableStateOf(0) }
    var lastNavigationAttempt by remember { mutableStateOf("") }
    var lastSuccessfulNavigation by remember { mutableStateOf("") }
    
    // DEBUG: Biometric unlock state tracking  
    var biometricUnlockAttempts by remember { mutableStateOf(0) }
    var lastBiometricSuccess by remember { mutableStateOf("") }

    // ---[ Ironclad biometric flow distinction helpers ]---
    fun isBiometricOptInFlowActive(): Boolean = pendingBiometricOptIn
    fun isBiometricUnlockFlowActive(): Boolean = biometricUnlockRequest != null && !pendingBiometricOptIn

    fun clearPendingBiometricOptIn(reason: String): BiometricOptInReplayGuard.ClearResult {
        val result = biometricOptInReplayGuard.clearPendingOptIn(reason)
        val logMessage =
            "clearPendingBiometricOptIn result reason=${reason} action=${result.action} " +
                "pendingOptIn=${result.pendingOptIn} promptTrigger=${result.promptTrigger} " +
                "matchesCurrentFlow=${result.matchesCurrentFlow} hasActiveFlow=${result.hasActiveFlow}"
        Log.d(BIOMETRIC_LOG_TAG, logMessage)
        BiometricPromptTestHooks.notifyBiometricLog(logMessage)
        return result
    }

    fun confirmPendingBiometricOptIn(reason: String) {
        biometricOptInReplayGuard.confirmPendingOptIn(reason)
    }

    val biometricStatusOverride = BiometricPromptTestHooks.overrideCanAuthenticate
    if (biometricStatusOverride != null) {
        Log.d(BIOMETRIC_LOG_TAG, "Using biometricStatus override value=${biometricStatusOverride}")
    }
    val biometricStatus = biometricStatusOverride
        ?: biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        )
    val canUseBiometric = biometricsEnabled && biometricStatus == BiometricManager.BIOMETRIC_SUCCESS
    val startDestination = if (isPinSet) "list" else "pin_setup"

    val launchedBiometricRequest = remember { mutableStateOf<BiometricUnlockRequest?>(null) }
    val launchedBiometricRequestState = rememberUpdatedState(launchedBiometricRequest.value)

    // DEBUG: Navigation monitoring LaunchedEffect
    LaunchedEffect(navBackStackEntry?.destination?.route) {
        val currentRoute = navBackStackEntry?.destination?.route
        val logMessage = "DEBUG_NAV: Route changed to '$currentRoute' at ${System.currentTimeMillis()}"
        Log.d(BIOMETRIC_LOG_TAG, logMessage)
        BiometricPromptTestHooks.notifyBiometricLog(logMessage)
        
        if (currentRoute?.startsWith("detail/") == true) {
            lastSuccessfulNavigation = "SUCCESS: Navigated to $currentRoute at ${System.currentTimeMillis()}"
            Log.d(BIOMETRIC_LOG_TAG, "DEBUG_NAV: $lastSuccessfulNavigation")
        }
    }

    // DEBUG: Comprehensive navigation attempt function
    fun attemptNavigation(noteId: Long, source: String): Boolean {
        navigationAttempts++
        lastNavigationAttempt = "ATTEMPT #$navigationAttempts from $source to detail/$noteId at ${System.currentTimeMillis()}"
        
        Log.d(BIOMETRIC_LOG_TAG, "DEBUG_NAV: $lastNavigationAttempt")
        Log.d(BIOMETRIC_LOG_TAG, "DEBUG_NAV: Current route before navigation: ${navController.currentBackStackEntry?.destination?.route}")
        Log.d(BIOMETRIC_LOG_TAG, "DEBUG_NAV: NavController state - canGoBack: ${navController.previousBackStackEntry != null}")
        Log.d(BIOMETRIC_LOG_TAG, "DEBUG_NAV: Activity lifecycle state: ${lifecycleOwner.lifecycle.currentState}")
        
        return try {
            val note = noteViewModel.getNoteById(noteId)
            if (note == null) {
                Log.e(BIOMETRIC_LOG_TAG, "DEBUG_NAV: Note not found for noteId=$noteId")
                Toast.makeText(context, "Note is no longer available", Toast.LENGTH_SHORT).show()
                return false
            }
            
            Log.d(BIOMETRIC_LOG_TAG, "DEBUG_NAV: About to call navController.navigate for noteId=$noteId")
            navController.navigate("detail/$noteId") {
                launchSingleTop = true
            }
            
            Log.d(BIOMETRIC_LOG_TAG, "DEBUG_NAV: Navigation call completed for noteId=$noteId")
            Log.d(BIOMETRIC_LOG_TAG, "DEBUG_NAV: Current route after navigation: ${navController.currentBackStackEntry?.destination?.route}")
            
            true
        } catch (e: Exception) {
            Log.e(BIOMETRIC_LOG_TAG, "DEBUG_NAV: Navigation failed for noteId=$noteId from $source", e)
            Toast.makeText(context, "Navigation failed: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    // ---[ ENHANCED biometric unlock callback with comprehensive debugging ]---
    val biometricAuthenticationCallback = remember(noteViewModel, navController) {
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                biometricUnlockAttempts++
                lastBiometricSuccess = "SUCCESS #$biometricUnlockAttempts at ${System.currentTimeMillis()}"
                
                Log.d(BIOMETRIC_LOG_TAG, "DEBUG_BIO: onAuthenticationSucceeded callback start - $lastBiometricSuccess")
                Log.d(BIOMETRIC_LOG_TAG, "DEBUG_BIO: Current app state - lifecycle: ${lifecycleOwner.lifecycle.currentState}")
                Log.d(BIOMETRIC_LOG_TAG, "DEBUG_BIO: Current navigation route: ${navController.currentBackStackEntry?.destination?.route}")
                Log.d(BIOMETRIC_LOG_TAG, "DEBUG_BIO: Activity state: ${activity.lifecycle.currentState}")
                
                // Only handle unlock flow
                if (isBiometricUnlockFlowActive()) {
                    val currentRequest = noteViewModel.currentBiometricUnlockRequest()
                    val capturedRequest = launchedBiometricRequestState.value
                    
                    Log.d(BIOMETRIC_LOG_TAG, "DEBUG_BIO: Unlock flow active - currentRequest=${currentRequest?.noteId} capturedRequest=${capturedRequest?.noteId}")

                    val request = currentRequest ?: capturedRequest
                    if (request == null) {
                        launchedBiometricRequest.value = null
                        Log.e(BIOMETRIC_LOG_TAG, "DEBUG_BIO: ABORT - missing request")
                        return
                    }
                    if (currentRequest == null) {
                        Log.w(BIOMETRIC_LOG_TAG, "DEBUG_BIO: Using captured request for noteId=${request.noteId}")
                    }

                    Log.d(BIOMETRIC_LOG_TAG, "DEBUG_BIO: Starting unlock sequence for noteId=${request.noteId}")
                    
                    // Mark note as unlocked
                    Log.d(BIOMETRIC_LOG_TAG, "DEBUG_BIO: Marking note ${request.noteId} as temporarily unlocked")
                    noteViewModel.markNoteTemporarilyUnlocked(request.noteId)
                    
                    Log.d(BIOMETRIC_LOG_TAG, "DEBUG_BIO: Clearing biometric unlock request")
                    noteViewModel.clearBiometricUnlockRequest()
                    
                    Log.d(BIOMETRIC_LOG_TAG, "DEBUG_BIO: Clearing pending open note ID")
                    noteViewModel.clearPendingOpenNoteId()
                    
                    // Wait a moment for state to settle, then attempt navigation
                    Log.d(BIOMETRIC_LOG_TAG, "DEBUG_BIO: About to attempt navigation for noteId=${request.noteId}")
                    
                    val navigationSuccess = attemptNavigation(request.noteId, "biometric_success")
                    
                    if (!navigationSuccess) {
                        Log.e(BIOMETRIC_LOG_TAG, "DEBUG_BIO: Navigation failed, setting pendingOpenNoteId as fallback")
                        noteViewModel.setPendingOpenNoteId(request.noteId)
                    }
                    
                    launchedBiometricRequest.value = null
                    Log.d(BIOMETRIC_LOG_TAG, "DEBUG_BIO: Biometric unlock sequence completed for noteId=${request.noteId}")
                    
                } else {
                    Log.w(BIOMETRIC_LOG_TAG, "DEBUG_BIO: Biometric unlock flow not active - ignoring success")
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                val request = noteViewModel.currentBiometricUnlockRequest()
                Log.d(
                    BIOMETRIC_LOG_TAG,
                    "DEBUG_BIO: onAuthenticationError code=$errorCode message=\"$errString\" requestNoteId=${request?.noteId}"
                )

                val userExit = when (errorCode) {
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_USER_CANCELED -> true
                    else -> false
                }

                val hardFailure = when (errorCode) {
                    BiometricPrompt.ERROR_HW_NOT_PRESENT,
                    BiometricPrompt.ERROR_NO_BIOMETRICS,
                    BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
                    BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED,
                    BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> true
                    else -> false
                }

                if (userExit || hardFailure) {
                    if (hardFailure) {
                        Toast.makeText(context, errString, Toast.LENGTH_LONG).show()
                    }
                    noteViewModel.clearBiometricUnlockRequest()
                    if (request != null) {
                        Log.d(BIOMETRIC_LOG_TAG, "DEBUG_BIO: Setting pendingOpenNoteId due to error")
                        noteViewModel.setPendingOpenNoteId(request.noteId)
                    }
                    return
                }

                val transientError = when (errorCode) {
                    BiometricPrompt.ERROR_CANCELED,
                    BiometricPrompt.ERROR_TIMEOUT,
                    BiometricPrompt.ERROR_LOCKOUT,
                    BiometricPrompt.ERROR_HW_UNAVAILABLE,
                    BiometricPrompt.ERROR_UNABLE_TO_PROCESS,
                    BiometricPrompt.ERROR_NO_SPACE,
                    BiometricPrompt.ERROR_VENDOR -> true
                    else -> false
                }

                if (!transientError || errorCode == BiometricPrompt.ERROR_LOCKOUT) {
                    Toast.makeText(context, errString, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ---[ Ironclad biometric opt-in callback ]---
    val biometricOptInAuthenticationCallback = remember(noteViewModel) {
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                // Only handle opt-in flow
                if (isBiometricOptInFlowActive()) {
                    val reason = "opt_in_authenticated"
                    Log.d(
                        BIOMETRIC_LOG_TAG,
                        "biometricOptInPrompt onAuthenticationSucceeded reason=$reason pendingOptIn=$pendingBiometricOptIn"
                    )
                    pinManager.setBiometricEnabled(true)
                    biometricsEnabled = true
                    clearPendingBiometricOptIn(reason)
                    Toast.makeText(context, "Biometric unlock enabled", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val reason = "opt_in_error_$errorCode"
                Log.d(
                    BIOMETRIC_LOG_TAG,
                    "biometricOptInPrompt onAuthenticationError reason=$reason pendingOptIn=$pendingBiometricOptIn"
                )
                clearPendingBiometricOptIn(reason)
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                ) {
                    Toast.makeText(context, errString, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val biometricPrompt = remember(activity, executor, biometricAuthenticationCallback) {
        BiometricPrompt(activity, executor, biometricAuthenticationCallback)
    }
    val biometricOptInPrompt = remember(activity, executor, biometricOptInAuthenticationCallback) {
        BiometricPrompt(activity, executor, biometricOptInAuthenticationCallback)
    }

    LaunchedEffect(isPinSet) {
        if (!isPinSet) {
            hasLoadedInitialPin = false
            biometricsEnabled = false
        } else if (!hasLoadedInitialPin) {
            val storedPin = pinManager.getStoredPin()
            if (storedPin != null) {
                noteViewModel.loadNotes(context, storedPin)
                hasLoadedInitialPin = true
                biometricsEnabled = pinManager.isBiometricEnabled()
            }
        }
    }

    LaunchedEffect(noteViewModel, biometricOptInReplayGuard) {
        noteViewModel.biometricUnlockEvents.collectLatest {
            biometricOptInReplayGuard.onBiometricUnlockEvent()
        }
    }

    // DEBUG: Periodic state logging
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000) // Log state every 5 seconds
            Log.d(BIOMETRIC_LOG_TAG, "DEBUG_STATE: Navigation attempts: $navigationAttempts, Biometric unlocks: $biometricUnlockAttempts")
            Log.d(BIOMETRIC_LOG_TAG, "DEBUG_STATE: Current route: ${navController.currentBackStackEntry?.destination?.route}")
            Log.d(BIOMETRIC_LOG_TAG, "DEBUG_STATE: Lifecycle: ${lifecycleOwner.lifecycle.currentState}")
            Log.d(BIOMETRIC_LOG_TAG, "DEBUG_STATE: PendingOpenNoteId: ${pendingOpenNoteId}")
            Log.d(BIOMETRIC_LOG_TAG, "DEBUG_STATE: BiometricUnlockRequest: ${biometricUnlockRequest?.noteId}")
            Log.d(BIOMETRIC_LOG_TAG, "DEBUG_STATE: Last navigation attempt: $lastNavigationAttempt")
            Log.d(BIOMETRIC_LOG_TAG, "DEBUG_STATE: Last successful navigation: $lastSuccessfulNavigation")
            Log.d(BIOMETRIC_LOG_TAG, "DEBUG_STATE: Last biometric success: $lastBiometricSuccess")
        }
    }

    // ---[ Only launch biometric unlock prompt if NOT in opt-in flow ]---
    LaunchedEffect(biometricPromptTrigger, biometricUnlockRequest?.token, pendingBiometricOptIn) {
        if (biometricPromptTrigger == 0L) return@LaunchedEffect
        val request = biometricUnlockRequest ?: return@LaunchedEffect
        if (isBiometricOptInFlowActive()) {
            val logMessage =
                "biometric unlock request suppressed noteId=${request.noteId} token=${request.token} trigger=$biometricPromptTrigger pendingOptIn=true"
            Log.w(
                BIOMETRIC_LOG_TAG,
                logMessage
            )
            BiometricPromptTestHooks.notifyBiometricLog(logMessage)
            return@LaunchedEffect
        }
        val logMessage =
            "DEBUG_BIO: Launching biometric prompt noteId=${request.noteId} token=${request.token} trigger=$biometricPromptTrigger pendingOptIn=$pendingBiometricOptIn"
        Log.d(
            BIOMETRIC_LOG_TAG,
            logMessage
        )
        BiometricPromptTestHooks.notifyBiometricLog(logMessage)
        launchedBiometricRequest.value = request
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock note")
            .setSubtitle("Authenticate to open \"${request.title}\".")
            .setNegativeButtonText("Use PIN")
            .build()
        val intercepted = BiometricPromptTestHooks.interceptAuthenticate?.let { handler ->
            runCatching { handler(promptInfo, biometricAuthenticationCallback) }
                .onFailure { throwable ->
                    Log.e(BIOMETRIC_LOG_TAG, "biometricPrompt intercept failed", throwable)
                }
                .getOrDefault(false)
        } ?: false
        if (intercepted) {
            Log.d(
                BIOMETRIC_LOG_TAG,
                "DEBUG_BIO: Biometric prompt intercepted noteId=${request.noteId} token=${request.token}"
            )
        } else {
            Log.d(BIOMETRIC_LOG_TAG, "DEBUG_BIO: Calling biometricPrompt.authenticate()")
            biometricPrompt.authenticate(promptInfo)
        }
    }

    // ---[ Only launch opt-in prompt if opt-in flow is active ]---
    LaunchedEffect(pendingBiometricOptIn) {
        if (isBiometricOptInFlowActive()) {
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Enable biometric unlock")
                .setSubtitle("Confirm your biometrics to enable unlocking notes.")
                .setNegativeButtonText("Cancel")
                .build()
            Log.d(
                BIOMETRIC_LOG_TAG,
                "Launching biometric opt-in prompt guard pendingOptIn=$pendingBiometricOptIn"
            )
            val intercepted = BiometricPromptTestHooks.interceptAuthenticate?.let { handler ->
                runCatching { handler(promptInfo, biometricOptInAuthenticationCallback) }
                    .onFailure { throwable ->
                        Log.e(BIOMETRIC_LOG_TAG, "biometricOptInPrompt intercept failed", throwable)
                    }
                    .getOrDefault(false)
            } ?: false
            if (intercepted) {
                Log.d(
                    BIOMETRIC_LOG_TAG,
                    "Launching biometric opt-in prompt intercepted pendingOptIn=$pendingBiometricOptIn"
                )
            } else {
                biometricOptInPrompt.authenticate(promptInfo)
            }
        }
    }

    LaunchedEffect(noteViewModel) {
        noteViewModel.reminderNavigation.collectLatest { noteId ->
            val note = noteViewModel.getNoteById(noteId)
            if (note == null) {
                Toast.makeText(context, "Note is no longer available", Toast.LENGTH_SHORT).show()
            } else if (note.isLocked && !noteViewModel.isNoteTemporarilyUnlocked(note.id)) {
                noteViewModel.setPendingOpenNoteId(note.id)
            } else {
                Log.d(BIOMETRIC_LOG_TAG, "DEBUG_NAV: Reminder navigation attempt for noteId=$noteId")
                attemptNavigation(noteId, "reminder")
            }
        }
    }

    LaunchedEffect(pendingShare, navBackStackEntry?.destination?.route) {
        if (pendingShare == null) return@LaunchedEffect
        val currentRoute = navBackStackEntry?.destination?.route
        if (currentRoute != "pin_enter" && currentRoute != "pin_setup") {
            Log.d(BIOMETRIC_LOG_TAG, "DEBUG_NAV: Share navigation to add screen")
            navController.navigate("add") {
                launchSingleTop = true
            }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable("pin_setup") {
            PinSetupScreen(pinManager = pinManager) { pin ->
                noteViewModel.loadNotes(context, pin)
                hasLoadedInitialPin = true
                biometricsEnabled = pinManager.isBiometricEnabled()
                showBiometricOptIn =
                    biometricStatus == BiometricManager.BIOMETRIC_SUCCESS && !biometricsEnabled
                navController.navigate("list") {
                    popUpTo("pin_setup") { inclusive = true }
                }
            }
        }
        composable("pin_enter") {
            PinEnterScreen(pinManager = pinManager) { pin ->
                noteViewModel.loadNotes(context, pin)
                hasLoadedInitialPin = true
                navController.navigate("list") {
                    popUpTo("pin_enter") { inclusive = true }
                }
            }
        }
        composable("list") {
            NoteListScreen(
                notes = noteViewModel.notes,
                onAddNote = { navController.navigate("add") },
                onAddEvent = { navController.navigate("add_event") },
                onOpenNote = { note ->
                    Log.d(
                        BIOMETRIC_LOG_TAG,
                        "DEBUG_NAV: onOpenNote tap noteId=${note.id} locked=${note.isLocked} tempUnlocked=${noteViewModel.isNoteTemporarilyUnlocked(note.id)} canUseBiometric=$canUseBiometric"
                    )
                    if (note.isLocked && !noteViewModel.isNoteTemporarilyUnlocked(note.id)) {
                        if (canUseBiometric) {
                            Log.d(BIOMETRIC_LOG_TAG, "DEBUG_NAV: Requesting biometric unlock for noteId=${note.id}")
                            noteViewModel.clearPendingOpenNoteId()
                            val clearResult = clearPendingBiometricOptIn("note_list_unlock_request")
                            val replayRequired = clearResult.action == ClearAction.FORCE_CLEAR_MISSING_ACTIVE_TOKEN ||
                                clearResult.action == ClearAction.FORCE_CLEAR_TOKEN_MISMATCH
                            if (replayRequired) {
                                launchedBiometricRequest.value = null
                                noteViewModel.clearBiometricUnlockRequest()
                                val retryLog =
                                    "reposting biometric unlock after force clear reason=note_list_unlock_request " +
                                        "action=${clearResult.action} noteId=${note.id}"
                                Log.d(BIOMETRIC_LOG_TAG, retryLog)
                                BiometricPromptTestHooks.notifyBiometricLog(retryLog)
                            }
                            noteViewModel.requestBiometricUnlock(note.id, note.title)
                        } else {
                            Log.d(BIOMETRIC_LOG_TAG, "DEBUG_NAV: Setting pendingOpenNoteId for PIN unlock, noteId=${note.id}")
                            noteViewModel.setPendingOpenNoteId(note.id)
                        }
                    } else {
                        Log.d(BIOMETRIC_LOG_TAG, "DEBUG_NAV: Direct navigation to unlocked note, noteId=${note.id}")
                        attemptNavigation(note.id, "note_list_direct")
                    }
                },
                onDeleteNote = { noteId -> noteViewModel.deleteNote(noteId) },
                onSettings = { navController.navigate("settings") },
                summarizerState = summarizerState
            )
        }
        composable("add") {
            AddNoteScreen(
                onSave = { title, content, images, files, linkPreviews, event ->
                    noteViewModel.addNote(title, content, images, files, linkPreviews, event)
                    noteViewModel.clearPendingShare()
                    navController.popBackStack()
                },
                onBack = {
                    noteViewModel.clearPendingShare()
                    navController.popBackStack()
                },
                onDisablePinCheck = {},
                onEnablePinCheck = {},
                summarizerState = summarizerState,
                entryMode = NoteEntryMode.Note,
                prefill = pendingShare,
            )
        }
        composable("add_event") {
            AddNoteScreen(
                onSave = { title, content, images, files, linkPreviews, event ->
                    if (event != null) {
                        noteViewModel.addNote(title, content, images, files, linkPreviews, event)
                    }
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
                onDisablePinCheck = {},
                onEnablePinCheck = {},
                summarizerState = summarizerState,
                entryMode = NoteEntryMode.Event,
            )
        }
        composable("detail/{noteId}") { backStackEntry ->
            val noteId = backStackEntry.arguments?.getString("noteId")?.toLongOrNull()
            val note = noteId?.let { noteViewModel.getNoteById(it) }
            Log.d(BIOMETRIC_LOG_TAG, "DEBUG_NAV: detail composable rendered for noteId=$noteId, note exists=${note != null}")
            if (noteId != null && note != null) {
                DisposableEffect(noteId) {
                    Log.d(BIOMETRIC_LOG_TAG, "DEBUG_NAV: NoteDetailScreen started for noteId=$noteId")
                    onDispose { 
                        Log.d(BIOMETRIC_LOG_TAG, "DEBUG_NAV: NoteDetailScreen disposed for noteId=$noteId")
                        noteViewModel.relockNote(noteId) 
                    }
                }
                NoteDetailScreen(
                    note = note,
                    onBack = { navController.popBackStack() },
                    onEdit = { navController.navigate("edit/$noteId") },
                    onLockRequest = {
                        noteViewModel.setNoteLock(note.id, true)
                        noteViewModel.markNoteTemporarilyUnlocked(note.id)
                    },
                    onUnlockRequest = { pendingUnlockNoteId = note.id },
                    openAttachment = { id -> noteViewModel.openAttachment(id) }
                )
            } else {
                Log.d(BIOMETRIC_LOG_TAG, "DEBUG_NAV: detail composable - note not found, going back")
                navController.popBackStack()
            }
        }
        composable("edit/{noteId}") { backStackEntry ->
            val noteId = backStackEntry.arguments?.getString("noteId")?.toLongOrNull()
            val note = noteId?.let { noteViewModel.getNoteById(it) }
            if (noteId != null && note != null) {
                EditNoteScreen(
                    note = note,
                    onSave = { title, content, images, files, linkPreviews, event ->
                        noteViewModel.updateNote(noteId, title, content, images, files, linkPreviews, event)
                        navController.popBackStack()
                    },
                    onCancel = { navController.popBackStack() },
                    onDisablePinCheck = {},
                    onEnablePinCheck = {},
                    summarizerState = summarizerState,
                    openAttachment = { id -> noteViewModel.openAttachment(id) }
                )
            } else {
                navController.popBackStack()
            }
        }
        composable("settings") {
            SettingsScreen(
                pinManager = pinManager,
                biometricEnabled = biometricsEnabled,
                onBiometricChanged = { enabled -> biometricsEnabled = enabled },
                onBack = { navController.popBackStack() },
                onImport = { uri, pin, overwrite -> noteViewModel.importNotes(context, uri, pin, overwrite) },
                onExport = { uri -> noteViewModel.exportNotes(context, uri) },
                onDisablePinCheck = {},
                onEnablePinCheck = {},
                onPinChanged = { newPin -> noteViewModel.updateStoredPin(newPin) }
            )
        }
    }

    if (showBiometricOptIn) {
        AlertDialog(
            onDismissRequest = {
                showBiometricOptIn = false
                clearPendingBiometricOptIn("opt_in_dialog_dismiss_request")
                pinManager.setBiometricEnabled(false)
                biometricsEnabled = false
            },
            title = { Text("Enable biometric unlock?") },
            text = {
                Text("Would you like to use your fingerprint or face to unlock your notes? You can change this later in Settings.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showBiometricOptIn = false
                    if (biometricStatus == BiometricManager.BIOMETRIC_SUCCESS) {
                        confirmPendingBiometricOptIn("opt_in_dialog_confirm")
                    } else {
                        Toast.makeText(
                            context,
                            "Biometric unlock isn't available right now.",
                            Toast.LENGTH_SHORT
                        ).show()
                        pinManager.setBiometricEnabled(false)
                        biometricsEnabled = false
                    }
                }) {
                    Text("Enable")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBiometricOptIn = false
                    clearPendingBiometricOptIn("opt_in_dialog_dismiss_button")
                    pinManager.setBiometricEnabled(false)
                    biometricsEnabled = false
                }) {
                    Text("Not now")
                }
            }
        )
    }

    pendingOpenNoteId?.let { noteId ->
        val note = noteViewModel.getNoteById(noteId)
        Log.d(BIOMETRIC_LOG_TAG, "DEBUG_NAV: PinPromptDialog showing for noteId=$noteId")
        if (note != null) {
            PinPromptDialog(
                title = "Unlock note",
                message = "Enter your PIN to open \"${note.title}\".",
                pinManager = pinManager,
                showBiometricOption = canUseBiometric,
                onBiometricRequested = {
                    Log.d(BIOMETRIC_LOG_TAG, "DEBUG_NAV: Biometric requested from PIN dialog for noteId=$noteId")
                    noteViewModel.clearPendingOpenNoteId()
                    if (canUseBiometric) {
                        val clearResult = clearPendingBiometricOptIn("pin_prompt_biometric_request")
                        val replayRequired = clearResult.action == ClearAction.FORCE_CLEAR_MISSING_ACTIVE_TOKEN ||
                            clearResult.action == ClearAction.FORCE_CLEAR_TOKEN_MISMATCH
                        if (replayRequired) {
                            launchedBiometricRequest.value = null
                            noteViewModel.clearBiometricUnlockRequest()
                            val retryLog =
                                "reposting biometric unlock after force clear reason=pin_prompt_biometric_request " +
                                    "action=${clearResult.action} noteId=${noteId}"
                            Log.d(BIOMETRIC_LOG_TAG, retryLog)
                            BiometricPromptTestHooks.notifyBiometricLog(retryLog)
                        }
                        noteViewModel.requestBiometricUnlock(noteId, note.title)
                    }
                },
                onDismiss = { 
                    Log.d(BIOMETRIC_LOG_TAG, "DEBUG_NAV: PIN dialog dismissed for noteId=$noteId")
                    noteViewModel.clearPendingOpenNoteId() 
                },
                onPinConfirmed = {
                    Log.d(BIOMETRIC_LOG_TAG, "DEBUG_NAV: PIN confirmed for noteId=$noteId")
                    noteViewModel.markNoteTemporarilyUnlocked(noteId)
                    noteViewModel.clearPendingOpenNoteId()
                    // Direct navigation for PIN unlock
                    attemptNavigation(noteId, "pin_confirmed")
                }
            )
        } else {
            Log.d(BIOMETRIC_LOG_TAG, "DEBUG_NAV: Note not found for pendingOpenNoteId=$noteId, clearing")
            noteViewModel.clearPendingOpenNoteId()
        }
    }

    pendingUnlockNoteId?.let { noteId ->
        val note = noteViewModel.getNoteById(noteId)
        if (note != null) {
            AlertDialog(
                onDismissRequest = { pendingUnlockNoteId = null },
                title = { Text("Remove PIN protection") },
                text = { Text("Remove the PIN lock from \"${note.title}\"?") },
                confirmButton = {
                    TextButton(onClick = {
                        noteViewModel.setNoteLock(noteId, false)
                        pendingUnlockNoteId = null
                    }) {
                        Text("Remove")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingUnlockNoteId = null }) {
                        Text("Cancel")
                    }
                }
            )
        } else {
            pendingUnlockNoteId = null
        }
    }
}

@Composable
private fun PinPromptDialog(
    title: String,
    message: String,
    pinManager: PinManager,
    showBiometricOption: Boolean,
    onBiometricRequested: () -> Unit,
    onDismiss: () -> Unit,
    onPinConfirmed: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { input ->
                        if (input.length <= 6 && input.all { it.isDigit() }) {
                            pin = input
                            error = false
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                if (error) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Incorrect PIN", color = Color.Red)
                }
                if (showBiometricOption) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onBiometricRequested) {
                        Text("Use fingerprint/face")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (pinManager.checkPin(pin)) {
                        onPinConfirmed()
                    } else {
                        error = true
                    }
                },
                enabled = pin.length >= 4
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
