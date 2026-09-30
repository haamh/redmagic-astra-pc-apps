package com.stream4k60.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.rememberNavController
import com.stream4k60.app.ui.main.MainStudioScreen
import com.stream4k60.app.ui.profiles.ProfileManagerScreen
import com.stream4k60.app.ui.settings.SettingsScreen

@Composable
fun AppNavHost(){
    val nav=rememberNavController()
    NavHost(nav, startDestination=NavRoutes.MainStudio.route){
        composable(NavRoutes.MainStudio.route){MainStudioScreen({category->nav.navigate("settings_category/${category}")},{nav.navigate(NavRoutes.ProfileManager.route)})}
        composable(NavRoutes.Settings.route){SettingsScreen(onNavigateBack={nav.popBackStack()})}
        composable(NavRoutes.SettingsCategory.route, arguments=listOf(navArgument("category"){type=NavType.StringType})){entry->
            SettingsScreen(onNavigateBack={nav.popBackStack()},initialCategory=entry.arguments?.getString("category") ?: "General")
        }
        composable(NavRoutes.ProfileManager.route){ProfileManagerScreen(onClose={nav.popBackStack()})}
        composable(NavRoutes.SettingsGeneral.route){SettingsScreen(onNavigateBack={nav.popBackStack()},initialCategory="General")}
        composable(NavRoutes.SettingsStream.route){SettingsScreen(onNavigateBack={nav.popBackStack()},initialCategory="Stream")}
        composable(NavRoutes.SettingsOutput.route){SettingsScreen(onNavigateBack={nav.popBackStack()},initialCategory="Output")}
        composable(NavRoutes.SettingsAudio.route){SettingsScreen(onNavigateBack={nav.popBackStack()},initialCategory="Audio")}
        composable(NavRoutes.SettingsVideo.route){SettingsScreen(onNavigateBack={nav.popBackStack()},initialCategory="Video")}
        composable(NavRoutes.SettingsHotkeys.route){SettingsScreen(onNavigateBack={nav.popBackStack()},initialCategory="Hotkeys")}
        composable(NavRoutes.SettingsAdvanced.route){SettingsScreen(onNavigateBack={nav.popBackStack()},initialCategory="Advanced")}
        composable(NavRoutes.SettingsAccessibility.route){SettingsScreen(onNavigateBack={nav.popBackStack()},initialCategory="Accessibility")}
    }
}
