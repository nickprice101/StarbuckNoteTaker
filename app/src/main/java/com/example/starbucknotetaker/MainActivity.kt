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
import androidx.lifecycle.LifecycleEventObserver
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

// CRITICAL FIX: Define the missing constant
private const val BIOMETRIC_LOG_TAG = "BiometricNavigation"

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
    val isPinSet = pinManager.isPinSet()
    var hasLoadedInitialPin by remember { mutableStateOf(false) }
    var pendingUnlockNoteId by remember { mutableStateOf<Long?>(null) }
    var biometricsEnabled by remember { mutableStateOf(pinManager.isBiometricEnabled()) }
    val biometricUnlockRequest by noteViewModel.biometricUnlockRequest.collectAsState()
    val biometricUnlockRequestState = rememberUpdatedState(biometricUnlockRequest)
    val biometricOptInReplayGuard = remember(noteViewModel) {
        BiometricOptInReplayGuard(
            logger = { message -> Log.d(BIOMETRIC_LOG_TAG, message) },
            notifyBiometricLog = { message -> 
                try {
                    BiometricPromptTestHooks.notifyBiometricLog(message)
                } catch (e: Exception) {
                    Log.d(BIOMETRIC_LOG_TAG, "Test hook not available: $message")
                }
            },
            currentBiometricUnlockRequest = { noteViewModel.currentBiometricUnlockRequest() },
            currentActiveRequestToken = { biometricUnlockRequestState.value?.token },
        )
    }
    var showBiometricOptIn by remember { mutableStateOf(false) }
    val pendingBiometricOptIn by biometricOptInReplayGuard.pendingOptIn
    val pendingBiometricOptInState = rememberUpdatedState(pendingBiometricOptIn)
    val biometricPromptTrigger by biometricOptInReplayGuard.promptTrigger
    val lifecycleOwner = LocalLifecycleOwner.current

    // CRITICAL FIX: Simple navigation state management
    var navigationInProgress by remember { mutableStateOf(false) }

    // ---[ Ironclad biometric flow distinction helpers ]---
    fun isBiometricOptInFlowActive(): Boolean = pendingBiometricOptInState.value

    fun clearPendingBiometricOptIn(reason: String): BiometricOptInReplayGuard.ClearResult {
        val result = biometricOptInReplayGuard.clearPendingOptIn(reason)
        Log.d(BIOMETRIC_LOG_TAG, "clearPendingBiometricOptIn: $reason -> ${result.action}")
        return result
    }

    fun confirmPendingBiometricOptIn(reason: String) {
        biometricOptInReplayGuard.confirmPendingOptIn(reason)
    }

    val biometricStatusOverride = try {
        BiometricPromptTestHooks.overrideCanAuthenticate
    } catch (e: Exception) {
        null
    }
    
    val biometricStatus = biometricStatusOverride
        ?: biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        )
    val canUseBiometric = biometricsEnabled && biometricStatus == BiometricManager.BIOMETRIC_SUCCESS
    val startDestination = if (isPinSet) "list" else "pin_setup"

    val launchedBiometricRequest = remember { mutableStateOf<BiometricUnlockRequest?>(null) }

    // CRITICAL FIX: Simplified, reliable navigation function
    fun navigateToNoteImmediately(noteId: Long, source: String) {
        Log.d(BIOMETRIC_LOG_TAG, "NAVIGATE: Attempting navigation from $source to noteId=$noteId")
        
        if (navigationInProgress) {
            Log.w(BIOMETRIC_LOG_TAG, "NAVIGATE: Navigation already in progress, skipping")
            return
        }
        
        navigationInProgress = true
        
        try {
            val note = noteViewModel.getNoteById(noteId)
            if (note == null) {
                Log.e(BIOMETRIC_LOG_TAG, "NAVIGATE: Note not found for noteId=$noteId")
                Toast.makeText(context, "Note not found", Toast.LENGTH_SHORT).show()
                navigationInProgress = false
                return
            }
            
            Log.d(BIOMETRIC_LOG_TAG, "NAVIGATE: Calling navController.navigate(\"detail/$noteId\")")
            navController.navigate("detail/$noteId") {
                launchSingleTop = true
            }
            
            Log.d(BIOMETRIC_LOG_TAG, "NAVIGATE: Navigation call completed successfully")
            
        } catch (e: Exception) {
            Log.e(BIOMETRIC_LOG_TAG, "NAVIGATE: Navigation failed", e)
            Toast.makeText(context, "Navigation error: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            navigationInProgress = false
        }
    }

    // CRITICAL FIX: Monitor navigation completion
    LaunchedEffect(navBackStackEntry?.destination?.route) {
        val currentRoute = navBackStackEntry?.destination?.route
        Log.d(BIOMETRIC_LOG_TAG, "NAVIGATE: Route changed to '$currentRoute'")
        
        if (currentRoute?.startsWith("detail/") == true) {
            navigationInProgress = false
            Log.d(BIOMETRIC_LOG_TAG, "NAVIGATE: Successfully reached detail screen")
        }
    }

    // ---[ CRITICAL FIX: Bulletproof biometric unlock callback ]---
    val biometricAuthenticationCallback = remember(noteViewModel, navController) {
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                Log.d(BIOMETRIC_LOG_TAG, "BIOMETRIC: Authentication succeeded")
                
                val pendingOptIn = pendingBiometricOptInState.value
                val activeRequest = biometricUnlockRequestState.value

                if (!pendingOptIn && activeRequest != null) {
                    val request = noteViewModel.currentBiometricUnlockRequest()
                        ?: launchedBiometricRequest.value

                    if (request != null) {
                        Log.d(BIOMETRIC_LOG_TAG, "BIOMETRIC: Processing unlock for noteId=${request.noteId}")
                        
                        // Mark note as unlocked
                        noteViewModel.markNoteTemporarilyUnlocked(request.noteId)
                        
                        // Clear all pending states
                        noteViewModel.clearBiometricUnlockRequest()
                        noteViewModel.clearPendingOpenNoteId()
                        launchedBiometricRequest.value = null
                        
                        // CRITICAL: Navigate immediately on the main thread
                        navigateToNoteImmediately(request.noteId, "biometric_success")
                        
                        Log.d(BIOMETRIC_LOG_TAG, "BIOMETRIC: Unlock process completed for noteId=${request.noteId}")
                        
                        try {
                            BiometricPromptTestHooks.notifyBiometricLog("Biometric unlock success for noteId=${request.noteId}")
                        } catch (e: Exception) {
                            // Test hooks not available
                        }
                    } else {
                        Log.w(BIOMETRIC_LOG_TAG, "BIOMETRIC: Success but no request found")
                    }
                } else {
                    Log.d(BIOMETRIC_LOG_TAG, "BIOMETRIC: Success ignored (opt-in flow or no request)")
                }
            }

            override fun onAuthenticationFailed() {
                Log.d(BIOMETRIC_LOG_TAG, "BIOMETRIC: Authentication failed")
                try {
                    BiometricPromptTestHooks.notifyBiometricLog("Biometric unlock attempt failed")
                } catch (e: Exception) {
                    // Test hooks not available
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Log.d(BIOMETRIC_LOG_TAG, "BIOMETRIC: Error code=$errorCode message=\"$errString\"")
                
                val request = noteViewModel.currentBiometricUnlockRequest() ?: launchedBiometricRequest.value

                val userCanceled = when (errorCode) {
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_USER_CANCELED -> true
                    else -> false
                }

                if (userCanceled && request != null) {
                    Log.d(BIOMETRIC_LOG_TAG, "BIOMETRIC: User canceled, showing PIN for noteId=${request.noteId}")
                    noteViewModel.clearBiometricUnlockRequest()
                    noteViewModel.setPendingOpenNoteId(request.noteId)
                } else {
                    val hardFailure = when (errorCode) {
                        BiometricPrompt.ERROR_HW_NOT_PRESENT,
                        BiometricPrompt.ERROR_NO_BIOMETRICS,
                        BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
                        BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED,
                        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL -> true
                        else -> false
                    }

                    if (hardFailure) {
                        Toast.makeText(context, errString, Toast.LENGTH_LONG).show()
                        noteViewModel.clearBiometricUnlockRequest()
                        if (request != null) {
                            noteViewModel.setPendingOpenNoteId(request.noteId)
                        }
                    }
                }

                launchedBiometricRequest.value = null
                try {
                    BiometricPromptTestHooks.notifyBiometricLog("Biometric unlock error code=$errorCode")
                } catch (e: Exception) {
                    // Test hooks not available
                }
            }
        }
    }

    // Biometric opt-in callback
    val biometricOptInAuthenticationCallback = remember(noteViewModel) {
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (isBiometricOptInFlowActive()) {
                    Log.d(BIOMETRIC_LOG_TAG, "Biometric opt-in succeeded")
                    pinManager.setBiometricEnabled(true)
                    biometricsEnabled = true
                    clearPendingBiometricOptIn("opt_in_authenticated")
                    Toast.makeText(context, "Biometric unlock enabled", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Log.d(BIOMETRIC_LOG_TAG, "Biometric opt-in error code=$errorCode")
                clearPendingBiometricOptIn("opt_in_error_$errorCode")
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

    // Launch biometric prompt
    LaunchedEffect(biometricPromptTrigger, biometricUnlockRequest?.token, pendingBiometricOptIn) {
        if (biometricPromptTrigger == 0L) return@LaunchedEffect
        val request = biometricUnlockRequest ?: return@LaunchedEffect
        if (isBiometricOptInFlowActive()) {
            Log.w(BIOMETRIC_LOG_TAG, "Biometric unlock suppressed due to opt-in flow")
            return@LaunchedEffect
        }
        
        Log.d(BIOMETRIC_LOG_TAG, "Launching biometric prompt for noteId=${request.noteId}")
        launchedBiometricRequest.value = request
        
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock note")
            .setSubtitle("Authenticate to open \"${request.title}\".")
            .setNegativeButtonText("Use PIN")
            .build()
            
        val intercepted = try {
            BiometricPromptTestHooks.interceptAuthenticate?.let { handler ->
                runCatching { handler(promptInfo, biometricAuthenticationCallback) }
                    .onFailure { throwable ->
                        Log.e(BIOMETRIC_LOG_TAG, "Biometric intercept failed", throwable)
                    }
                    .getOrDefault(false)
            } ?: false
        } catch (e: Exception) {
            false
        }
        
        if (!intercepted) {
            Log.d(BIOMETRIC_LOG_TAG, "Executing biometricPrompt.authenticate()")
            biometricPrompt.authenticate(promptInfo)
        }
    }

    // Launch opt-in prompt
    LaunchedEffect(pendingBiometricOptIn) {
        if (isBiometricOptInFlowActive()) {
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Enable biometric unlock")
                .setSubtitle("Confirm your biometrics to enable unlocking notes.")
                .setNegativeButtonText("Cancel")
                .build()
            
            val intercepted = try {
                BiometricPromptTestHooks.interceptAuthenticate?.let { handler ->
                    runCatching { handler(promptInfo, biometricOptInAuthenticationCallback) }
                        .onFailure { throwable ->
                            Log.e(BIOMETRIC_LOG_TAG, "Biometric opt-in intercept failed", throwable)
                        }
                        .getOrDefault(false)
                } ?: false
            } catch (e: Exception) {
                false
            }
            
            if (!intercepted) {
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
                navigateToNoteImmediately(noteId, "reminder")
            }
        }
    }

    LaunchedEffect(pendingShare, navBackStackEntry?.destination?.route) {
        if (pendingShare == null) return@LaunchedEffect
        val currentRoute = navBackStackEntry?.destination?.route
        if (currentRoute != "pin_enter" && currentRoute != "pin_setup") {
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
                    Log.d(BIOMETRIC_LOG_TAG, "Note tap - noteId=${note.id} locked=${note.isLocked}")
                    
                    if (note.isLocked && !noteViewModel.isNoteTemporarilyUnlocked(note.id)) {
                        if (canUseBiometric) {
                            Log.d(BIOMETRIC_LOG_TAG, "Starting biometric unlock for noteId=${note.id}")
                            noteViewModel.clearPendingOpenNoteId()
                            val clearResult = clearPendingBiometricOptIn("note_list_unlock_request")
                            val replayRequired = clearResult.action == ClearAction.FORCE_CLEAR_MISSING_ACTIVE_TOKEN ||
                                clearResult.action == ClearAction.FORCE_CLEAR_TOKEN_MISMATCH
                            if (replayRequired) {
                                launchedBiometricRequest.value = null
                                noteViewModel.clearBiometricUnlockRequest()
                            }
                            noteViewModel.requestBiometricUnlock(note.id, note.title)
                        } else {
                            Log.d(BIOMETRIC_LOG_TAG, "Starting PIN unlock for noteId=${note.id}")
                            noteViewModel.setPendingOpenNoteId(note.id)
                        }
                    } else {
                        Log.d(BIOMETRIC_LOG_TAG, "Direct navigation to unlocked note")
                        navigateToNoteImmediately(note.id, "direct_access")
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
            Log.d(BIOMETRIC_LOG_TAG, "Detail screen loaded for noteId=$noteId")
            
            if (noteId != null && note != null) {
                DisposableEffect(noteId) {
                    Log.d(BIOMETRIC_LOG_TAG, "NoteDetailScreen started for noteId=$noteId")
                    onDispose { 
                        Log.d(BIOMETRIC_LOG_TAG, "NoteDetailScreen disposed for noteId=$noteId")
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
                Log.w(BIOMETRIC_LOG_TAG, "Note not found, navigating back")
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
        Log.d(BIOMETRIC_LOG_TAG, "PIN dialog for noteId=$noteId")
        if (note != null) {
            PinPromptDialog(
                title = "Unlock note",
                message = "Enter your PIN to open \"${note.title}\".",
                pinManager = pinManager,
                showBiometricOption = canUseBiometric,
                onBiometricRequested = {
                    Log.d(BIOMETRIC_LOG_TAG, "Biometric requested from PIN dialog")
                    noteViewModel.clearPendingOpenNoteId()
                    if (canUseBiometric) {
                        val clearResult = clearPendingBiometricOptIn("pin_prompt_biometric_request")
                        val replayRequired = clearResult.action == ClearAction.FORCE_CLEAR_MISSING_ACTIVE_TOKEN ||
                            clearResult.action == ClearAction.FORCE_CLEAR_TOKEN_MISMATCH
                        if (replayRequired) {
                            launchedBiometricRequest.value = null
                            noteViewModel.clearBiometricUnlockRequest()
                        }
                        noteViewModel.requestBiometricUnlock(noteId, note.title)
                    }
                },
                onDismiss = { 
                    Log.d(BIOMETRIC_LOG_TAG, "PIN dialog dismissed")
                    noteViewModel.clearPendingOpenNoteId() 
                },
                onPinConfirmed = {
                    Log.d(BIOMETRIC_LOG_TAG, "PIN confirmed for noteId=$noteId")
                    noteViewModel.markNoteTemporarilyUnlocked(noteId)
                    noteViewModel.clearPendingOpenNoteId()
                    // Navigate immediately after PIN success
                    navigateToNoteImmediately(noteId, "pin_success")
                }
            )
        } else {
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
