package com.example.starbucknotetaker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class MainActivity : ComponentActivity() {
    private val noteViewModel: NoteViewModel by viewModels()
    private var encoder: Interpreter? = null
    private var decoder: Interpreter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            val (encFile, decFile) = ModelFetcher.ensureModels(this@MainActivity)
            encoder = Interpreter(mapFile(encFile))
            decoder = Interpreter(mapFile(decFile))
        }

        val pinManager = PinManager(applicationContext)
        setContent {
            StarbuckNoteTakerTheme {
                val navController = rememberNavController()
                AppContent(navController, noteViewModel, pinManager)
            }
        }
    }

    private fun mapFile(file: File): MappedByteBuffer {
        val raf = java.io.RandomAccessFile(file, "r")
        return raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()).also { raf.close() }
    }

    override fun onDestroy() {
        super.onDestroy()
        encoder?.close()
        decoder?.close()
    }
}

@Composable
fun AppContent(navController: NavHostController, noteViewModel: NoteViewModel, pinManager: PinManager) {
    val start = if (pinManager.isPinSet()) "pin_enter" else "pin_setup"
    val context = LocalContext.current
    var requireAuth by remember { mutableStateOf(false) }
    var pinCheckEnabled by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && pinCheckEnabled) {
                requireAuth = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(requireAuth) {
        if (requireAuth && navController.currentDestination?.route !in listOf("pin_enter", "pin_setup")) {
            navController.navigate("pin_enter") {
                popUpTo("pin_enter") { inclusive = true }
            }
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
                navController.navigate("list") {
                    popUpTo("pin_enter") { inclusive = true }
                }
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
                onSave = { title, content, images ->
                    noteViewModel.addNote(title, content, images)
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
                    onSave = { title, content, images ->
                        noteViewModel.updateNote(index, title, content, images)
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
