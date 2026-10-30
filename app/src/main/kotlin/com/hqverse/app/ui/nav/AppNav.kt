package com.hqverse.app.ui.nav

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.hqverse.app.AppContainer
import com.hqverse.app.containerFactory
import com.hqverse.app.ui.*
import com.hqverse.app.ui.screens.*
import com.hqverse.app.ui.theme.LocalReduceMotion
import com.hqverse.core.ReadStatus

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val iconOff: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("home", "Início", Icons.Filled.Home, Icons.Outlined.Home),
    Tab("library", "Biblioteca", Icons.Filled.CollectionsBookmark, Icons.Outlined.CollectionsBookmark),
    Tab("collections", "Coleções", Icons.Filled.Folder, Icons.Outlined.Folder),
    Tab("stats", "Estatísticas", Icons.Filled.BarChart, Icons.Outlined.BarChart),
    Tab("settings", "Ajustes", Icons.Filled.Settings, Icons.Outlined.Settings),
)

@Composable
fun AppNav(container: AppContainer) {
    val context = LocalContext.current
    val nav = rememberNavController()
    val reduce = LocalReduceMotion.current
    val libVm: LibraryViewModel = viewModel(factory = containerFactory { LibraryViewModel(it) })
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) libVm.onFolderPicked(context, uri) }
    LaunchedEffect(Unit) { libVm.autoScanIfEnabled(context) }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBar = route in tabs.map { it.route }

    fun open(id: Long) = nav.navigate("detail/$id")
    val dur = if (reduce) 0 else 240

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            AnimatedVisibility(showBar, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    tabs.forEach { t ->
                        val sel = route == t.route
                        NavigationBarItem(
                            selected = sel, onClick = { nav.navigate(t.route) { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true } },
                            icon = { Icon(if (sel) t.icon else t.iconOff, null) }, label = { Text(t.label, maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(
            nav, startDestination = "home", modifier = Modifier.padding(if (showBar) pad else PaddingValues(0.dp)),
            enterTransition = { fadeIn(tween(dur)) + slideInVertically(tween(dur)) { if (reduce) 0 else it / 14 } },
            exitTransition = { fadeOut(tween(dur)) }, popEnterTransition = { fadeIn(tween(dur)) }, popExitTransition = { fadeOut(tween(dur)) + slideOutVertically(tween(dur)) { if (reduce) 0 else it / 14 } },
        ) {
            composable("home") { HomeScreen(libVm, onPickFolder = { picker.launch(null) }, onOpen = ::open, onRead = { nav.navigate("reader/$it") }, onSeeLibrary = { nav.navigate("library") }, onCollections = { nav.navigate("collections") }) }
            composable("library") { LibraryScreen(libVm, onPickFolder = { picker.launch(null) }, onOpen = ::open, onFolders = { nav.navigate("folders") }) }
            composable("collections") { CollectionsScreen(viewModel(factory = containerFactory { CollectionsViewModel(it) }), onOpen = { nav.navigate("collection/$it") }) }
            composable("stats") { StatsScreen(viewModel(factory = containerFactory { StatsViewModel(it) }), onOpen = ::open) }
            composable("settings") { SettingsScreen(viewModel(factory = containerFactory { SettingsViewModel(it) }), onFolders = { nav.navigate("folders") }) }
            composable("folders") { FoldersScreen(libVm, onPickFolder = { picker.launch(null) }, onBack = { nav.popBackStack() }) }
            composable("detail/{id}", listOf(navArgument("id") { type = NavType.LongType })) { e ->
                val id = e.arguments!!.getLong("id")
                DetailScreen(
                    viewModel(key = "detail$id", factory = containerFactory { DetailViewModel(it, id) }),
                    onBack = { nav.popBackStack() }, onRead = { page -> nav.navigate(if (page != null) "reader/$id?page=$page" else "reader/$id") },
                    onOpenCollection = { nav.navigate("collection/$it") },
                )
            }
            composable("collection/{id}", listOf(navArgument("id") { type = NavType.LongType })) { e ->
                val id = e.arguments!!.getLong("id")
                CollectionDetailScreen(viewModel(key = "col$id", factory = containerFactory { CollectionDetailViewModel(it, id) }), onBack = { nav.popBackStack() }, onOpen = ::open)
            }
            composable("reader/{id}?page={page}", listOf(navArgument("id") { type = NavType.LongType }, navArgument("page") { type = NavType.IntType; defaultValue = -1 })) { e ->
                val id = e.arguments!!.getLong("id"); val page = e.arguments!!.getInt("page")
                ReaderScreen(viewModel(key = "reader$id-$page", factory = containerFactory { ReaderViewModel(it, id, page) }), onBack = { nav.popBackStack() })
            }
        }
    }
}
