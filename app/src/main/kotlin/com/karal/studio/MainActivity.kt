package com.karal.studio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.karal.studio.data.repository.SettingsKeys
import com.karal.studio.ui.AboutScreen
import com.karal.studio.ui.AiBuilderScreen
import com.karal.studio.ui.BackupsScreen
import com.karal.studio.ui.BotRuntimeScreen
import com.karal.studio.ui.ConsoleScreen
import com.karal.studio.ui.DiagnosticsScreen
import com.karal.studio.ui.HomeScreen
import com.karal.studio.ui.HostingScreen
import com.karal.studio.ui.KaralViewModel
import com.karal.studio.ui.NewCommandScreen
import com.karal.studio.ui.NewProjectScreen
import com.karal.studio.ui.ProjectDetailScreen
import com.karal.studio.ui.ProjectsScreen
import com.karal.studio.ui.ScriptEditorScreen
import com.karal.studio.ui.SettingsScreen
import com.karal.studio.ui.StudioScreen
import com.karal.studio.ui.TemplatesScreen
import com.karal.studio.ui.VisualEditorScreen
import com.karal.studio.ui.theme.KaralTheme
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: KaralViewModel = viewModel()
            val nav = rememberNavController()
            var localeTag by rememberSaveable { mutableStateOf("hi") }
            var theme by remember { mutableStateOf(SettingsKeys.THEME_SYSTEM) }
            LaunchedEffect(vm) {
                val rows = vm.settings.first()
                theme = rows.firstOrNull { it.key == SettingsKeys.THEME }?.value ?: SettingsKeys.THEME_SYSTEM
                localeTag = rows.firstOrNull { it.key == SettingsKeys.LANGUAGE }?.value ?: SettingsKeys.LANGUAGE_HI
            }
            val dark = theme == SettingsKeys.THEME_DARK ||
                (theme == SettingsKeys.THEME_SYSTEM && androidx.compose.foundation.isSystemInDarkTheme())

            LaunchedEffect(localeTag) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(localeTag))
            }

            KaralTheme(darkTheme = dark) {
                val entry by nav.currentBackStackEntryAsState()
                val route = entry?.destination?.route.orEmpty()
                val showShell = route in bottomRoutes
                Scaffold(
                    bottomBar = {
                        if (showShell) {
                            NavigationBar {
                                destinations.forEach { (destinationRoute, labelRes, icon) ->
                                    val selected = entry?.destination?.hierarchy?.any { it.route == destinationRoute } == true
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = {
                                            nav.navigate(destinationRoute) {
                                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = { Icon(icon, contentDescription = null) },
                                        label = { Text(stringResource(labelRes)) }
                                    )
                                }
                            }
                        }
                    },
                    floatingActionButton = {
                        if (showShell) CreateFab(nav)
                    }
                ) { padding ->
                    NavHost(nav, startDestination = "home", Modifier.padding(padding)) {
                        composable("home") { HomeScreen(vm, nav) }
                        composable("projects") { ProjectsScreen(vm, nav) }
                        composable("studio") { StudioScreen(vm, nav) }
                        composable("hosting") { HostingScreen(vm, nav) }
                        composable("settings") { SettingsScreen(vm, nav, theme, localeTag) { localeTag = it } }
                        composable("project/new") { NewProjectScreen(vm, nav) }
                        composable("project/{projectId}") { back ->
                            ProjectDetailScreen(vm, nav, back.arguments?.getString("projectId").orEmpty())
                        }
                        composable("command/new?projectId={projectId}") { back ->
                            NewCommandScreen(vm, nav, back.arguments?.getString("projectId").orEmpty())
                        }
                        composable("command/{commandId}/visual") { back ->
                            VisualEditorScreen(vm, nav, back.arguments?.getString("commandId").orEmpty())
                        }
                        composable("command/{commandId}/script") { back ->
                            ScriptEditorScreen(vm, nav, back.arguments?.getString("commandId").orEmpty())
                        }
                        composable("command/{commandId}/ai") { back ->
                            AiBuilderScreen(vm, nav, back.arguments?.getString("commandId").orEmpty())
                        }
                        composable("templates?projectId={projectId}") { back ->
                            TemplatesScreen(vm, nav, back.arguments?.getString("projectId").orEmpty())
                        }
                        composable("console?botId={botId}") { back ->
                            ConsoleScreen(vm, nav, back.arguments?.getString("botId").orEmpty())
                        }
                        composable("bot/{botId}") { back ->
                            BotRuntimeScreen(vm, nav, back.arguments?.getString("botId").orEmpty())
                        }
                        composable("backups") { BackupsScreen(vm, nav) }
                        composable("about") { AboutScreen(nav) }
                        composable("diagnostics") { DiagnosticsScreen(vm, nav) }
                    }
                }
            }
        }
    }

    private companion object {
        val bottomRoutes = setOf("home", "projects", "studio", "hosting", "settings")
        val destinations = listOf(
            Triple("home", R.string.nav_home, Icons.Filled.Home),
            Triple("projects", R.string.nav_projects, Icons.Filled.Folder),
            Triple("studio", R.string.nav_studio, Icons.Filled.Code),
            Triple("hosting", R.string.nav_hosting, Icons.Filled.CloudUpload),
            Triple("settings", R.string.nav_settings, Icons.Filled.Settings)
        )
    }
}

@Composable
private fun CreateFab(nav: androidx.navigation.NavController) {
    var expanded by remember { mutableStateOf(false) }
    ExtendedFloatingActionButton(
        onClick = { expanded = true },
        icon = { Icon(Icons.Filled.Add, null) },
        text = { Text(stringResource(R.string.create)) }
    )
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.new_project)) },
            onClick = { expanded = false; nav.navigate("project/new") }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.new_command)) },
            onClick = { expanded = false; nav.navigate("command/new?projectId=") }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.templates)) },
            onClick = { expanded = false; nav.navigate("templates?projectId=") }
        )
    }
}
