package com.aniki.anikiai.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.aniki.anikiai.data.remote.AnikiApi
import com.aniki.anikiai.data.repository.ItemRepository
import com.aniki.anikiai.ui.detail.ItemDetailScreen
import com.aniki.anikiai.ui.feed.FeedScreen
import com.aniki.anikiai.ui.library.LibraryScreen
import com.aniki.anikiai.ui.note.NewNoteScreen
import com.aniki.anikiai.ui.settings.SettingsScreen

private object AnikiDestinations {
    const val FEED = "feed"
    const val LIBRARY = "library"
    const val NEW_NOTE = "new_note"
    const val SETTINGS = "settings"
    const val ITEM_DETAIL = "item_detail"
    const val ITEM_DETAIL_ARG = "itemId"
    const val ITEM_DETAIL_ROUTE = "$ITEM_DETAIL/{$ITEM_DETAIL_ARG}"

    fun itemDetailRoute(itemId: String) = "$ITEM_DETAIL/$itemId"
}

@Composable
fun AnikiNavHost(
    repository: ItemRepository,
    api: AnikiApi,
    isSignedIn: Boolean,
    userEmail: String?,
    onSignOut: () -> Unit,
    navController: NavHostController = rememberNavController()
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val onFeed = currentRoute == AnikiDestinations.FEED
    val showBottomBar = onFeed || currentRoute == AnikiDestinations.LIBRARY

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            if (showBottomBar) {
                AnikiBottomBar(
                    currentRoute = currentRoute,
                    translucent = onFeed,
                    onSelectFeed = { navController.switchTab(AnikiDestinations.FEED) },
                    onSelectLibrary = { navController.switchTab(AnikiDestinations.LIBRARY) },
                    onCapture = { navController.navigate(AnikiDestinations.NEW_NOTE) }
                )
            }
        }
    ) { innerPadding ->
        NavHost(navController = navController, startDestination = AnikiDestinations.FEED) {
            composable(AnikiDestinations.FEED) {
                FeedScreen(
                    repository = repository,
                    onOpenDetail = { itemId -> navController.navigate(AnikiDestinations.itemDetailRoute(itemId)) },
                    contentPadding = innerPadding
                )
            }
            composable(AnikiDestinations.LIBRARY) {
                LibraryScreen(
                    repository = repository,
                    onOpenItem = { itemId -> navController.navigate(AnikiDestinations.itemDetailRoute(itemId)) },
                    onOpenSettings = { navController.navigate(AnikiDestinations.SETTINGS) },
                    contentPadding = innerPadding
                )
            }
            composable(AnikiDestinations.SETTINGS) {
                SettingsScreen(
                    api = api,
                    isSignedIn = isSignedIn,
                    userEmail = userEmail,
                    onBack = { navController.popBackStack() },
                    onSignOut = onSignOut,
                    onAccountDeleted = onSignOut
                )
            }
            composable(AnikiDestinations.NEW_NOTE) {
                NewNoteScreen(
                    repository = repository,
                    onDone = { navController.popBackStack() }
                )
            }
            composable(
                route = AnikiDestinations.ITEM_DETAIL_ROUTE,
                arguments = listOf(navArgument(AnikiDestinations.ITEM_DETAIL_ARG) { type = NavType.StringType })
            ) { entry ->
                val itemId = entry.arguments?.getString(AnikiDestinations.ITEM_DETAIL_ARG).orEmpty()
                ItemDetailScreen(
                    itemId = itemId,
                    repository = repository,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

/** Peer-tab switch: single instance per tab, saving/restoring each tab's own state. */
private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun AnikiBottomBar(
    currentRoute: String?,
    translucent: Boolean,
    onSelectFeed: () -> Unit,
    onSelectLibrary: () -> Unit,
    onCapture: () -> Unit
) {
    // Translucent/floating over the immersive Feed; solid/elevated on the Library. (Auto-hide is
    // P1 and skipped — a persistent translucent bar is the accepted fallback.)
    val container = if (translucent) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.80f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    Surface(color = container, tonalElevation = if (translucent) 0.dp else 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            BarItem(
                icon = Icons.Filled.Home,
                label = "Feed",
                selected = currentRoute == AnikiDestinations.FEED,
                onClick = onSelectFeed
            )
            FloatingActionButton(
                onClick = onCapture,
                modifier = Modifier.size(52.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Capture")
            }
            BarItem(
                icon = Icons.AutoMirrored.Filled.List,
                label = "Library",
                selected = currentRoute == AnikiDestinations.LIBRARY,
                onClick = onSelectLibrary
            )
        }
    }
}

@Composable
private fun BarItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = tint)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = tint, textAlign = TextAlign.Center)
    }
}
