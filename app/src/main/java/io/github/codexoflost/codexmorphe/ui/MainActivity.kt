package io.github.codexoflost.codexmorphe.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import io.github.codexoflost.codexmorphe.model.PatchEntry
import io.github.codexoflost.codexmorphe.preferences.PatchPreferenceManager
import io.github.codexoflost.codexmorphe.ui.dashboard.AppDashboardScreen
import io.github.codexoflost.codexmorphe.ui.patches.PatchSettingsScreen
import io.github.codexoflost.codexmorphe.ui.theme.CodexMorpheTheme

sealed class Screen {
    data object Dashboard : Screen()
    data class PatchSettings(
        val packageName: String,
        val appName: String,
        val authorName: String,
        val patches: List<PatchEntry>
    ) : Screen()
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefManager = PatchPreferenceManager(this)

        setContent {
            CodexMorpheTheme {
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Dashboard) }

                when (val screen = currentScreen) {
                    is Screen.Dashboard -> {
                        AppDashboardScreen(
                            onNavigateToPatches = { pkg, name, author, patches ->
                                currentScreen = Screen.PatchSettings(pkg, name, author, patches)
                            }
                        )
                    }
                    is Screen.PatchSettings -> {
                        PatchSettingsScreen(
                            appName = screen.appName,
                            packageName = screen.packageName,
                            authorName = screen.authorName,
                            patches = screen.patches,
                            prefManager = prefManager,
                            onNavigateBack = {
                                currentScreen = Screen.Dashboard
                            }
                        )
                    }
                }
            }
        }
    }
}
