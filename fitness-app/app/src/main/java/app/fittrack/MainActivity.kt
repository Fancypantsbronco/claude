package app.fittrack

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.fittrack.notify.Notifications
import app.fittrack.ui.BodyScreen
import app.fittrack.ui.ExerciseEditScreen
import app.fittrack.ui.ExerciseHistoryScreen
import app.fittrack.ui.ExtrasScreen
import app.fittrack.ui.FitTheme
import app.fittrack.ui.GymScreen
import app.fittrack.ui.HomeScreen
import app.fittrack.ui.LiveRunScreen
import app.fittrack.ui.RunDetailScreen
import app.fittrack.ui.RunListScreen
import app.fittrack.ui.SettingsScreen
import app.fittrack.ui.WorkoutScreen

class MainActivity : ComponentActivity() {

    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingRoute.value = intent?.getStringExtra(EXTRA_ROUTE)
        setContent {
            FitTheme {
                AppRoot(pendingRoute.value) { pendingRoute.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute.value = it }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Start", Icons.Filled.Home),
    Tab("gym", "Gym", Icons.Filled.FitnessCenter),
    Tab("run", "Laufen", Icons.Filled.DirectionsRun),
    Tab("body", "Gewicht", Icons.Filled.MonitorWeight),
    Tab("extras", "Bonus", Icons.Filled.EmojiEvents),
)

/** Wechselt zu einem Tab, ohne den Verlauf aufzublähen. */
fun NavHostController.goTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun AppRoot(pendingRoute: String?, onRouteHandled: () -> Unit) {
    val nav = rememberNavController()
    val ctx = LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !Notifications.canPost(ctx)) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(pendingRoute) {
        if (pendingRoute != null) {
            val base = pendingRoute.substringBefore('?')
            if (tabs.any { it.route == base } && pendingRoute == base) nav.goTab(base) else nav.navigate(pendingRoute)
            onRouteHandled()
        }
    }
    val entry by nav.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route?.substringBefore('?')
    val showBar = tabs.any { it.route == currentRoute }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBar) NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = currentRoute == t.route,
                        onClick = { nav.goTab(t.route) },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") { HomeScreen(nav) }
            composable("gym") { GymScreen(nav) }
            composable("run") { RunListScreen(nav) }
            composable(
                "body?add={add}",
                arguments = listOf(navArgument("add") { type = NavType.BoolType; defaultValue = false }),
            ) { BodyScreen(nav, openAdd = it.arguments?.getBoolean("add") == true) }
            composable("extras") { ExtrasScreen(nav) }
            composable("workout/{id}") { WorkoutScreen(nav, it.arguments?.getString("id").orEmpty()) }
            composable("exercise/{id}") { ExerciseEditScreen(nav, it.arguments?.getString("id").orEmpty()) }
            composable("history/{id}") { ExerciseHistoryScreen(nav, it.arguments?.getString("id").orEmpty()) }
            composable("liverun") { LiveRunScreen(nav) }
            composable("rundetail/{id}") { RunDetailScreen(nav, it.arguments?.getString("id").orEmpty()) }
            composable("settings") { SettingsScreen(nav) }
        }
    }
}
