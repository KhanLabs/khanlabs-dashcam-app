package dev.khanlabs.dashcam.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector

enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    Dashboard("dashboard", "Dashboard", Icons.Filled.SpaceDashboard),
    Videos("videos", "Videos", Icons.Filled.VideoLibrary),
    Map("map", "Map", Icons.Filled.Map),
    Settings("settings", "Settings", Icons.Filled.Settings)
}
