package com.changyow.mediadler

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.changyow.mediadler.transcribe.TranscriptionService
import com.changyow.mediadler.ui.home.HomeScreen
import com.changyow.mediadler.ui.settings.SettingsScreen
import com.changyow.mediadler.ui.theme.MediaDlerTheme
import com.changyow.mediadler.ui.transcribe.TranscriptHistoryScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val manager = appContainer.transcriptionManager
        // Resume a job interrupted by a previous process death.
        if (manager.hasPending()) TranscriptionService.start(this)
        // A finished-but-unseen transcript jumps straight to its result.
        manager.firstUnseenCompleted()?.let { TranscribeActivity.start(this, it.id) }

        setContent {
            MediaDlerTheme { AppNavHost() }
        }
    }
}

@Composable
private fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpenSettings = { navController.navigate("settings") },
                onOpenHistory = { navController.navigate("transcripts") },
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("transcripts") {
            TranscriptHistoryScreen(onBack = { navController.popBackStack() })
        }
    }
}
