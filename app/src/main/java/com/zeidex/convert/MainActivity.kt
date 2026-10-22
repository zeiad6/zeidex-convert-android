package com.zeidex.convert

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DonutLarge
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zeidex.convert.ui.screens.HomeScreen
import com.zeidex.convert.ui.screens.SettingsScreen
import com.zeidex.convert.ui.screens.ToolsScreen
import com.zeidex.convert.ui.screens.WheelScreen
import com.zeidex.convert.ui.theme.ZeidexOrange
import com.zeidex.convert.ui.theme.ZeidexTheme
import com.zeidex.convert.ui.viewmodel.MainViewModel

sealed class Screen(val route: String, val titleRes: Int, val icon: ImageVector) {
    data object Home : Screen("home", R.string.nav_home, Icons.Default.SwapHoriz)
    data object Wheel : Screen("wheel", R.string.nav_wheel, Icons.Default.DonutLarge)
    data object Tools : Screen("tools", R.string.nav_tools, Icons.Default.Build)
    data object Settings : Screen("settings", R.string.nav_settings, Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        viewModel.handleIncomingIntent(intent)

        setContent {
            ZeidexTheme {
                MainAppScaffold(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.handleIncomingIntent(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScaffold(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val screens = listOf(
        Screen.Home,
        Screen.Wheel,
        Screen.Tools,
        Screen.Settings
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        fontWeight = FontWeight.ExtraBold,
                        color = ZeidexOrange
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                screens.forEach { screen ->
                    val isSelected = currentRoute == screen.route
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(imageVector = screen.icon, contentDescription = stringResource(screen.titleRes))
                        },
                        label = {
                            Text(
                                text = stringResource(screen.titleRes),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = ZeidexOrange,
                            selectedTextColor = ZeidexOrange,
                            indicatorColor = ZeidexOrange.copy(alpha = 0.15f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(
                route = Screen.Home.route,
                enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(250)) },
                exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(250)) }
            ) {
                HomeScreen(viewModel = viewModel)
            }
            composable(
                route = Screen.Wheel.route,
                enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(250)) },
                exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(250)) }
            ) {
                WheelScreen(viewModel = viewModel)
            }
            composable(
                route = Screen.Tools.route,
                enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(250)) },
                exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(250)) }
            ) {
                ToolsScreen(viewModel = viewModel)
            }
            composable(
                route = Screen.Settings.route,
                enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(250)) },
                exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(250)) }
            ) {
                SettingsScreen()
            }
        }
    }
}
