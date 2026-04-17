package com.om.offlineai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import com.om.offlineai.engine.ModelState
import com.om.offlineai.ui.navigation.AppNavGraph
import com.om.offlineai.ui.navigation.Screen
import com.om.offlineai.ui.theme.OfflineAITheme
import com.om.offlineai.viewmodel.ModelViewModel
import com.om.offlineai.viewmodel.SettingsViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Show splash screen while model state is loading
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val settingsVm: SettingsViewModel = hiltViewModel()
            val modelVm: ModelViewModel = hiltViewModel()
            val settings by settingsVm.settings.collectAsState()
            val modelState by modelVm.uiState.collectAsState()

            // Keep splash visible until we know if a model is loaded
            var splashDone by remember { mutableStateOf(false) }
            splashScreen.setKeepOnScreenCondition { !splashDone }
            LaunchedEffect(modelState) { splashDone = true }

            OfflineAITheme(darkTheme = settings.darkMode) {
                val navController = rememberNavController()
                val startDestination = when (modelState.state) {
                    is ModelState.Loaded -> Screen.ChatList.route
                    else                 -> Screen.ModelSetup.route
                }
                AppNavGraph(navController = navController, startDestination = startDestination)
            }
        }
    }
}
