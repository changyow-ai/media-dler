package com.changyow.mediadler

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Subject
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
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
    val entry by navController.currentBackStackEntryAsState()
    val destinations = listOf(
        Triple("home", "下載", Icons.Filled.Download),
        Triple("transcripts", "轉錄記錄", Icons.AutoMirrored.Filled.Subject),
        Triple("settings", "設定", Icons.Filled.Settings),
    )
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            NavigationBar {
                destinations.forEach { (route, label, icon) ->
                    NavigationBarItem(
                        selected = entry?.destination?.route == route,
                        onClick = {
                            navController.navigate(route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(icon, contentDescription = null) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable("home") { HomeScreen() }
            composable("settings") { SettingsScreen() }
            composable("transcripts") { TranscriptHistoryScreen() }
        }
    }
}
