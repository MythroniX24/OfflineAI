package com.om.offlineai.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.om.offlineai.ui.screens.*

sealed class Screen(val route: String) {
    object ModelSetup    : Screen("model_setup")
    object ChatList      : Screen("chat_list")
    object Chat          : Screen("chat/{convId}") {
        fun route(id: Long) = "chat/$id"
    }
    object Memory        : Screen("memory")
    object Knowledge     : Screen("knowledge")
    object Settings      : Screen("settings")
    object ModelInfo     : Screen("model_info")
}

@Composable
fun AppNavGraph(
    navController: NavHostController,
    startDestination: String
) {
    NavHost(navController = navController, startDestination = startDestination) {

        composable(Screen.ModelSetup.route) {
            ModelSetupScreen(
                onModelLoaded = {
                    navController.navigate(Screen.ChatList.route) {
                        popUpTo(Screen.ModelSetup.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.ChatList.route) {
            ConversationListScreen(
                onOpenChat    = { id -> navController.navigate(Screen.Chat.route(id)) },
                onNewChat     = { id -> navController.navigate(Screen.Chat.route(id)) },
                onOpenMemory  = { navController.navigate(Screen.Memory.route) },
                onOpenKnowledge = { navController.navigate(Screen.Knowledge.route) },
                onOpenSettings  = { navController.navigate(Screen.Settings.route) }
            )
        }

        composable(
            route = Screen.Chat.route,
            arguments = listOf(navArgument("convId") { type = NavType.LongType })
        ) { back ->
            val convId = back.arguments?.getLong("convId") ?: return@composable
            ChatScreen(
                convId   = convId,
                onBack   = { navController.popBackStack() },
                onMemory = { navController.navigate(Screen.Memory.route) }
            )
        }

        composable(Screen.Memory.route) {
            MemoryScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.Knowledge.route) {
            KnowledgeScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onBack      = { navController.popBackStack() },
                onModelInfo = { navController.navigate(Screen.ModelInfo.route) }
            )
        }

        composable(Screen.ModelInfo.route) {
            ModelInfoScreen(onBack = { navController.popBackStack() })
        }
    }
}
