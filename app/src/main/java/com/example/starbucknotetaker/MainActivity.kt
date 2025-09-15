package com.example.starbucknotetaker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.starbucknotetaker.ui.AddNoteScreen
import com.example.starbucknotetaker.ui.NoteDetailScreen
import com.example.starbucknotetaker.ui.NoteListScreen
import com.example.starbucknotetaker.ui.PinEnterScreen
import com.example.starbucknotetaker.ui.PinSetupScreen
import com.example.starbucknotetaker.ui.EditNoteScreen
import com.example.starbucknotetaker.ui.StarbuckNoteTakerTheme

class MainActivity : ComponentActivity() {
    private val noteViewModel: NoteViewModel by viewModels()

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
    }
}

@Composable
fun AppContent(navController: NavHostController, noteViewModel: NoteViewModel, pinManager: PinManager) {
    val start = if (pinManager.isPinSet()) "pin_enter" else "pin_setup"
    val context = LocalContext.current
    var requireAuth by remember { mutableStateOf(false) }
    var pinCheckEnabled by remember { mutableStateOf(true) }
    var lastRoute by rememberSaveable { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && pinCheckEnabled) {
                requireAuth = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(requireAuth) {
        if (requireAuth && navController.currentDestination?.route !in listOf("pin_enter", "pin_setup")) {
            val entry = navController.currentBackStackEntry
            lastRoute = entry?.destination?.route?.let { route ->
                val args = entry.arguments
                if (args == null) {
                    route
                } else {
                    args.keySet().fold(route) { acc, key ->
                        acc.replace("{$key}", args.get(key).toString())
                    }
                }
            }
            navController.navigate("pin_enter")
        }
    }

    NavHost(navController = navController, startDestination = start) {
        composable("pin_setup") {
            PinSetupScreen(pinManager = pinManager) { pin ->
                requireAuth = false
                noteViewModel.loadNotes(context, pin)
                navController.navigate("list") {
                    popUpTo("pin_setup") { inclusive = true }
                }
            }
        }
        composable("pin_enter") {
            PinEnterScreen(pinManager = pinManager) { pin ->
                requireAuth = false
                noteViewModel.loadNotes(context, pin)
                val destination = lastRoute
                if (navController.previousBackStackEntry != null) {
                    navController.popBackStack()
                } else if (destination != null) {
                    navController.navigate(destination) {
                        popUpTo("pin_enter") { inclusive = true }
                        launchSingleTop = true
                    }
                } else {
                    navController.navigate("list") {
                        popUpTo("pin_enter") { inclusive = true }
                    }
                }
                lastRoute = null
            }
        }
        composable("list") {
            NoteListScreen(
                notes = noteViewModel.notes,
                onAddNote = { navController.navigate("add") },
                onOpenNote = { index -> navController.navigate("detail/$index") },
                onDeleteNote = { index -> noteViewModel.deleteNote(index) }
            )
        }
        composable("add") {
            AddNoteScreen(
                onSave = { title, content, images, files ->
                    noteViewModel.addNote(title, content, images, files)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
                onDisablePinCheck = { pinCheckEnabled = false },
                onEnablePinCheck = { pinCheckEnabled = true }
            )
        }
        composable("detail/{index}") { backStackEntry ->
            val index = backStackEntry.arguments?.getString("index")?.toIntOrNull() ?: 0
            val note = noteViewModel.notes.getOrNull(index)
            if (note != null) {
                NoteDetailScreen(
                    note = note,
                    onBack = { navController.popBackStack() },
                    onEdit = { navController.navigate("edit/$index") }
                )
            }
        }
        composable("edit/{index}") { backStackEntry ->
            val index = backStackEntry.arguments?.getString("index")?.toIntOrNull() ?: 0
            val note = noteViewModel.notes.getOrNull(index)
            if (note != null) {
                EditNoteScreen(
                    note = note,
                    onSave = { title, content, images, files ->
                        noteViewModel.updateNote(index, title, content, images, files)
                        navController.popBackStack()
                    },
                    onCancel = { navController.popBackStack() },
                    onDisablePinCheck = { pinCheckEnabled = false },
                    onEnablePinCheck = { pinCheckEnabled = true }
                )
            }
        }
    }
}
