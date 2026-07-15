package com.aniki.anikiai.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.aniki.anikiai.ui.theme.Ink
import com.aniki.anikiai.ui.theme.InkLine
import com.aniki.anikiai.ui.theme.Muted
import com.aniki.anikiai.ui.theme.OnDarkMuted
import com.aniki.anikiai.ui.theme.Paper
import com.aniki.anikiai.ui.theme.PaperLine
import com.aniki.anikiai.ui.theme.Seal
import com.aniki.anikiai.ui.theme.SealDark
import com.aniki.anikiai.ui.theme.weightedShadow

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

/**
 * Lives outside FeedScreen's own AnikiTheme(darkGround=true) wrapper — the Scaffold's bottomBar
 * slot is a sibling, not a child, of the tab content, so it can't just read ambient
 * MaterialTheme.colorScheme to match whichever tab is showing. It styles itself directly from the
 * token palette instead: translucent ink floating over the immersive Feed, solid parchment
 * (elevated) on the Library. (Auto-hide is P1 and skipped — a persistent bar is the fallback.)
 */
@Composable
private fun AnikiBottomBar(
    currentRoute: String?,
    translucent: Boolean,
    onSelectFeed: () -> Unit,
    onSelectLibrary: () -> Unit,
    onCapture: () -> Unit
) {
    val container = if (translucent) Ink.copy(alpha = 0.55f) else Paper
    val topLine = if (translucent) PaperLine else InkLine
    val iconTint = if (translucent) Color(0xFFD6D9E4) else Muted
    val selectedTint = if (translucent) SealDark else Seal

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(container)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(topLine))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BarItem(
                    icon = Icons.Filled.Home,
                    label = "Feed",
                    selected = currentRoute == AnikiDestinations.FEED,
                    tint = iconTint,
                    selectedTint = selectedTint,
                    onClick = onSelectFeed
                )
            }
            CaptureButton(onClick = onCapture)
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                BarItem(
                    icon = Icons.AutoMirrored.Filled.List,
                    label = "Library",
                    selected = currentRoute == AnikiDestinations.LIBRARY,
                    tint = iconTint,
                    selectedTint = selectedTint,
                    onClick = onSelectLibrary
                )
            }
        }
    }
}

/** The oxblood weighted capture button — the one large seal-colored fill in the whole app. */
@Composable
private fun CaptureButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .weightedShadow(androidx.compose.foundation.shape.RoundedCornerShape(16.dp), ambient = 14.dp, contact = 4.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(Seal)
            .selectable(selected = false, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.Add, contentDescription = "Capture", tint = Paper)
    }
}

@Composable
private fun BarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    tint: Color,
    selectedTint: Color,
    onClick: () -> Unit
) {
    val color = if (selected) selectedTint else tint
    Column(
        modifier = Modifier
            .selectable(selected = selected, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = label, tint = color)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = color, textAlign = TextAlign.Center)
    }
}
