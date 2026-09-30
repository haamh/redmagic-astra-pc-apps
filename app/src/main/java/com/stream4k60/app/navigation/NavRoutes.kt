package com.stream4k60.app.navigation

sealed class NavRoutes(val route: String) {
    object MainStudio : NavRoutes("main_studio")
    object Settings : NavRoutes("settings")
    object SettingsCategory : NavRoutes("settings_category/{category}")
    object SettingsGeneral : NavRoutes("settings_general")
    object SettingsStream : NavRoutes("settings_stream")
    object SettingsOutput : NavRoutes("settings_output")
    object SettingsAudio : NavRoutes("settings_audio")
    object SettingsVideo : NavRoutes("settings_video")
    object SettingsHotkeys : NavRoutes("settings_hotkeys")
    object SettingsAdvanced : NavRoutes("settings_advanced")
    object SettingsAccessibility : NavRoutes("settings_accessibility")
    object SceneEditor : NavRoutes("scene_editor")
    object SourceEditor : NavRoutes("source_editor")
    object FilterEditor : NavRoutes("filter_editor")
    object ProfileManager : NavRoutes("profile_manager")
    object SceneCollections : NavRoutes("scene_collections")
    object StudioMode : NavRoutes("studio_mode")
    object Stats : NavRoutes("stats")
}
