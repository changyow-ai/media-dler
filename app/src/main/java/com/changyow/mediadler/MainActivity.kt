package com.changyow.mediadler

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.changyow.mediadler.transcribe.TranscriptionService
import kotlinx.coroutines.launch
import com.changyow.mediadler.ui.home.HomeScreen
import com.changyow.mediadler.ui.settings.SettingsScreen
import com.changyow.mediadler.ui.theme.MediaDlerTheme
import com.changyow.mediadler.ui.transcribe.TranscriptHistoryScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val manager = appContainer.transcriptionManager
        // Persisted jobs load off the main thread; wait for that before deciding what to resume/open,
        // otherwise we'd read an empty queue on a cold launch and skip both actions.
        lifecycleScope.launch {
            manager.awaitHydrated()
            // Resume a job interrupted by a previous process death.
            if (manager.hasPending()) TranscriptionService.start(this@MainActivity)
            // A finished-but-unseen transcript jumps straight to its result.
            manager.firstUnseenCompleted()?.let { TranscribeActivity.start(this@MainActivity, it.id) }
        }

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
