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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.flow.collectLatest

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
    var showBiometricOptIn by remember { mutableStateOf(false) }
    var pendingBiometricOptIn by remember { mutableStateOf(false) }
    val biometricStatusOverride = BiometricPromptTestHooks.overrideCanAuthenticate
    if (biometricStatusOverride != null) {
        Log.d(BIOMETRIC_LOG_TAG, "Using biometricStatus override value=${'$'}biometricStatusOverride")
    }
    val biometricStatus = biometricStatusOverride
        ?: biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        )
    val canUseBiometric = biometricsEnabled && biometricStatus == BiometricManager.BIOMETRIC_SUCCESS
    val startDestination = if (isPinSet) "list" else "pin_setup"
    var biometricPromptTrigger by remember { mutableLongStateOf(0L) }

    val openNoteAfterUnlock: (Long) -> Unit = { noteId ->
        Log.d(BIOMETRIC_LOG_TAG, "openNoteAfterUnlock start noteId=${'$'}noteId")
        val note = noteViewModel.getNoteById(noteId)
        if (note != null) {
            navController.navigate("detail/$noteId") {
                launchSingleTop = true
            }
            Log.d(
                BIOMETRIC_LOG_TAG,
                "openNoteAfterUnlock navigated noteId=${'$'}noteId currentRoute=${'$'}{navController.currentBackStackEntry?.destination?.route} previousRoute=${'$'}{navController.previousBackStackEntry?.destination?.route} currentDestination=${'$'}{navController.currentDestination?.route}"
            )
        } else {
            Log.d(BIOMETRIC_LOG_TAG, "openNoteAfterUnlock missing noteId=${'$'}noteId")
            Toast.makeText(context, "Note is no longer available", Toast.LENGTH_SHORT).show()
        }
    }

    val launchedBiometricRequest = remember { mutableStateOf<BiometricUnlockRequest?>(null) }
    val launchedBiometricRequestState = rememberUpdatedState(launchedBiometricRequest.value)

    val biometricAuthenticationCallback = remember(noteViewModel) {
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val currentRequest = noteViewModel.currentBiometricUnlockRequest()
                val capturedRequest = launchedBiometricRequestState.value
                Log.d(
                    BIOMETRIC_LOG_TAG,
                    "onAuthenticationSucceeded callback currentRequest=${'$'}{currentRequest?.noteId} capturedRequest=${'$'}{capturedRequest?.noteId}"
                )

                val request = currentRequest ?: capturedRequest
                if (request == null) {
                    launchedBiometricRequest.value = null
                    Log.e(BIOMETRIC_LOG_TAG, "onAuthenticationSucceeded abort missing request")
                    return
                }

                if (currentRequest == null) {
                    Log.w(
                        BIOMETRIC_LOG_TAG,
                        "onAuthenticationSucceeded recovered_captured_request noteId=${'$'}{request.noteId}"
                    )
                }

                noteViewModel.markNoteTemporarilyUnlocked(request.noteId)
                noteViewModel.clearBiometricUnlockRequest()
                noteViewModel.clearPendingOpenNoteId()
                noteViewModel.clearPendingUnlockNavigationNoteId()
                noteViewModel.setPendingUnlockNavigationNoteId(request.noteId)
                val pendingAfterSet = noteViewModel.pendingUnlockNavigationNoteId.value
                if (pendingAfterSet != request.noteId) {
                    Log.w(
                        BIOMETRIC_LOG_TAG,
                        "onAuthenticationSucceeded pending_after_set_mismatch pending=${'$'}pendingAfterSet expected=${'$'}{request.noteId}"
                    )
                } else {
                    Log.d(
                        BIOMETRIC_LOG_TAG,
                        "onAuthenticationSucceeded pending_after_set=${'$'}pendingAfterSet expected=${'$'}{request.noteId}"
                    )
                }
                launchedBiometricRequest.value = null
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                val request = noteViewModel.currentBiometricUnlockRequest()
                Log.d(
                    BIOMETRIC_LOG_TAG,
                    "onAuthenticationError code=${'$'}errorCode message=\"${'$'}errString\" requestNoteId=${'$'}{request?.noteId}"
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

    val biometricPrompt = remember(activity, executor, biometricAuthenticationCallback) {
        BiometricPrompt(activity, executor, biometricAuthenticationCallback)
    }

    val biometricOptInPrompt = remember(activity, executor) {
        BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                pinManager.setBiometricEnabled(true)
                biometricsEnabled = true
                pendingBiometricOptIn = false
                Toast.makeText(context, "Biometric unlock enabled", Toast.LENGTH_SHORT).show()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                pendingBiometricOptIn = false
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                ) {
                    Toast.makeText(context, errString, Toast.LENGTH_SHORT).show()
                }
            }
        })
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

    LaunchedEffect(noteViewModel) {
        noteViewModel.biometricUnlockEvents.collectLatest {
            biometricPromptTrigger++
        }
    }

    // Keyed on the current request token and dedicated trigger to avoid stale captures
    LaunchedEffect(biometricPromptTrigger, biometricUnlockRequest?.token) {
        if (biometricPromptTrigger == 0L) return@LaunchedEffect
        val request = biometricUnlockRequest ?: return@LaunchedEffect
        Log.d(
            BIOMETRIC_LOG_TAG,
            "Launching biometric prompt noteId=${'$'}{request.noteId} token=${'$'}{request.token} trigger=${'$'}biometricPromptTrigger}"
        )
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
                "Launching biometric prompt intercepted noteId=${'$'}{request.noteId} token=${'$'}{request.token}"
            )
        } else {
            biometricPrompt.authenticate(promptInfo)
        }

    }

    LaunchedEffect(noteViewModel) {
        noteViewModel.pendingUnlockNavigationNoteId.collectLatest { noteId ->
            if (noteId != null) {
                val currentActivity = currentActivityState.value
                Log.d(
                    BIOMETRIC_LOG_TAG,
                    "navigatePendingUnlock requested noteId=${'$'}noteId lifecycle=${'$'}{currentActivity.lifecycle.currentState}"
                )
                navigatePendingUnlock(
                    currentActivity.lifecycle,
                    noteViewModel,
                    noteId,
                    openNoteAfterUnlock
                )
            }
        }
    }

    LaunchedEffect(pendingBiometricOptIn) {
        if (pendingBiometricOptIn) {
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Enable biometric unlock")
                .setSubtitle("Confirm your biometrics to enable unlocking notes.")
                .setNegativeButtonText("Cancel")
                .build()
            try {
                biometricOptInPrompt.authenticate(promptInfo)
            } finally {
                pendingBiometricOptIn = false
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
                navController.navigate("detail/$noteId") {
                    launchSingleTop = true
                }
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
                    Log.d(
                        BIOMETRIC_LOG_TAG,
                        "onOpenNote tap noteId=${'$'}{note.id} locked=${'$'}{note.isLocked} tempUnlocked=${'$'}{noteViewModel.isNoteTemporarilyUnlocked(note.id)} canUseBiometric=${'$'}canUseBiometric"
                    )
                    if (note.isLocked && !noteViewModel.isNoteTemporarilyUnlocked(note.id)) {
                        if (canUseBiometric) {
                            noteViewModel.clearPendingOpenNoteId()
                            noteViewModel.requestBiometricUnlock(note.id, note.title)
                        } else {
                            noteViewModel.setPendingOpenNoteId(note.id)
                        }
                    } else {
                        navController.navigate("detail/${note.id}")
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
            if (noteId != null && note != null) {
                DisposableEffect(noteId) {
                    onDispose { noteViewModel.relockNote(noteId) }
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
                pendingBiometricOptIn = false
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
                        pendingBiometricOptIn = true
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
                    pendingBiometricOptIn = false
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
        if (note != null) {
            PinPromptDialog(
                title = "Unlock note",
                message = "Enter your PIN to open \"${note.title}\".",
                pinManager = pinManager,
                showBiometricOption = canUseBiometric,
                onBiometricRequested = {
                    noteViewModel.clearPendingOpenNoteId()
                    if (canUseBiometric) {
                        Log.d(BIOMETRIC_LOG_TAG, "PinPromptDialog biometric requested noteId=${'$'}noteId")
                        noteViewModel.requestBiometricUnlock(noteId, note.title)
                    }
                },
                onDismiss = { noteViewModel.clearPendingOpenNoteId() },
                onPinConfirmed = {
                    noteViewModel.markNoteTemporarilyUnlocked(noteId)
                    noteViewModel.clearPendingOpenNoteId()
                    Log.d(BIOMETRIC_LOG_TAG, "PinPromptDialog pin confirmed noteId=${'$'}noteId")
                    noteViewModel.clearPendingUnlockNavigationNoteId()
                    noteViewModel.setPendingUnlockNavigationNoteId(noteId)
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
