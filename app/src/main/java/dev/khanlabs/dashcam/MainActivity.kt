package dev.khanlabs.dashcam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import dev.khanlabs.dashcam.ui.dashboard.DashboardScreen
import dev.khanlabs.dashcam.ui.devicestats.DeviceStatsScreen
import dev.khanlabs.dashcam.ui.gallery.GalleryScreen
import dev.khanlabs.dashcam.ui.liveview.LiveViewScreen
import dev.khanlabs.dashcam.ui.lte.LteScreen
import dev.khanlabs.dashcam.ui.lte.LteSettingsScreen
import dev.khanlabs.dashcam.ui.map.MapScreen
import dev.khanlabs.dashcam.ui.nav.Destination
import dev.khanlabs.dashcam.ui.player.PlayerScreen
import dev.khanlabs.dashcam.ui.settings.SettingsScreen
import dev.khanlabs.dashcam.ui.theme.KhanLabsDashcamTheme
import dev.khanlabs.dashcam.ui.volume.VolumeScreen

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KhanLabsDashcamTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    KhanLabsApp()
                }
            }
        }
    }
}

private const val PLAYER_ROUTE = "player/{epochSeconds}/{cameraIndex}"
private const val LTE_ROUTE = "lte"
private const val LTE_SETTINGS_ROUTE = "lte/settings"
private const val DEVICE_STATS_ROUTE = "device-stats"
private const val LIVE_VIEW_ROUTE = "live-view"
private const val VOLUME_ROUTE = "volume"

@Composable
private fun KhanLabsApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute != PLAYER_ROUTE && currentRoute != LTE_ROUTE &&
        currentRoute != LTE_SETTINGS_ROUTE && currentRoute != DEVICE_STATS_ROUTE &&
        currentRoute != LIVE_VIEW_ROUTE && currentRoute != VOLUME_ROUTE

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    Destination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Dashboard.route,
            modifier = Modifier.padding(if (showBottomBar) innerPadding else PaddingValues())
        ) {
            composable(Destination.Dashboard.route) {
                DashboardScreen(
                    onOpenLte = { navController.navigate(LTE_ROUTE) },
                    onOpenDeviceStats = { navController.navigate(DEVICE_STATS_ROUTE) },
                    onOpenLiveView = { navController.navigate(LIVE_VIEW_ROUTE) },
                    onOpenVolume = { navController.navigate(VOLUME_ROUTE) }
                )
            }
            composable(Destination.Videos.route) {
                GalleryScreen(onPlayClip = { clip ->
                    navController.navigate("player/${clip.epochSeconds}/${clip.cameraIndex.name}")
                })
            }
            composable(Destination.Map.route) { MapScreen() }
            composable(Destination.Settings.route) { SettingsScreen() }
            composable(LTE_ROUTE) {
                LteScreen(
                    onBack = { navController.popBackStack() },
                    onOpenSettings = { navController.navigate(LTE_SETTINGS_ROUTE) }
                )
            }
            composable(LTE_SETTINGS_ROUTE) {
                LteSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(DEVICE_STATS_ROUTE) {
                DeviceStatsScreen(onBack = { navController.popBackStack() })
            }
            composable(LIVE_VIEW_ROUTE) {
                LiveViewScreen(onBack = { navController.popBackStack() })
            }
            composable(VOLUME_ROUTE) {
                VolumeScreen(onBack = { navController.popBackStack() })
            }
            composable(
                route = PLAYER_ROUTE,
                arguments = listOf(
                    navArgument("epochSeconds") { type = NavType.LongType },
                    navArgument("cameraIndex") { type = NavType.StringType }
                )
            ) {
                PlayerScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
