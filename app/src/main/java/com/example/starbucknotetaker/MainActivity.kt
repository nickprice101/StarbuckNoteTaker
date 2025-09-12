package com.example.starbucknotetaker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.starbucknotetaker.ui.AddNoteScreen
import com.example.starbucknotetaker.ui.NoteDetailScreen
import com.example.starbucknotetaker.ui.NoteListScreen
import com.example.starbucknotetaker.ui.PinEnterScreen
import com.example.starbucknotetaker.ui.PinSetupScreen

class MainActivity : ComponentActivity() {
    private val noteViewModel: NoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pinManager = PinManager(applicationContext)
        setContent {
            val navController = rememberNavController()
            AppContent(navController, noteViewModel, pinManager)
        }
    }
}

@Composable
fun AppContent(navController: NavHostController, noteViewModel: NoteViewModel, pinManager: PinManager) {
    val start = if (pinManager.isPinSet()) "pin_enter" else "pin_setup"
    NavHost(navController = navController, startDestination = start) {
        composable("pin_setup") {
            PinSetupScreen(pinManager = pinManager) {
                navController.navigate("list") {
                    popUpTo("pin_setup") { inclusive = true }
                }
            }
        }
        composable("pin_enter") {
            PinEnterScreen(pinManager = pinManager) {
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
            AddNoteScreen(onSave = { title, content, images ->
                noteViewModel.addNote(title, content, images)
                navController.popBackStack()
            }, onBack = { navController.popBackStack() })
        }
        composable("detail/{index}") { backStackEntry ->
            val index = backStackEntry.arguments?.getString("index")?.toIntOrNull() ?: 0
            val note = noteViewModel.notes.getOrNull(index)
            if (note != null) {
                NoteDetailScreen(note = note, onBack = { navController.popBackStack() })
            }
        }
    }
}
