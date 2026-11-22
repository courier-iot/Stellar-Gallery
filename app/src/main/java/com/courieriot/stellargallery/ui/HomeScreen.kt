@file:OptIn(ExperimentalMaterial3Api::class)

package com.courieriot.stellargallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.courieriot.stellargallery.MainViewModel
import com.courieriot.stellargallery.data.SortOrder
import com.courieriot.stellargallery.ui.theme.Neon
import com.courieriot.stellargallery.ui.theme.Panel

enum class HomeTab(val label: String, val icon: ImageVector) {
    PHOTOS("Photos", Icons.Outlined.PhotoLibrary),
    ALBUMS("Albums", Icons.Outlined.Collections),
    FAVORITES("Favorites", Icons.Outlined.FavoriteBorder),
    BIN("Bin", Icons.Outlined.Delete),
}

@Composable
fun HomeScreen(vm: MainViewModel, nav: NavController) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.PHOTOS) }
    val media by vm.media.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val trash by vm.trash.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val columns by vm.gridColumns.collectAsStateWithLifecycle()
    val group by vm.groupByDate.collectAsStateWithLifecycle()
    val sort by vm.sortOrder.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    var confirmEmpty by remember { mutableStateOf(false) }

    val list = when (tab) {
        HomeTab.PHOTOS -> media
        HomeTab.FAVORITES -> favorites
        HomeTab.BIN -> trash
        HomeTab.ALBUMS -> emptyList()
    }
    val source = when (tab) {
        HomeTab.FAVORITES -> "fav"
        HomeTab.BIN -> "trash"
        else -> "all"
    }
    val selected = remember(list, selection) { list.filter { it.id in selection } }

    BackHandler(enabled = selection.isNotEmpty()) { vm.clearSelection() }
    LaunchedEffect(tab) { vm.clearSelection() }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            if (selection.isNotEmpty()) {
                SelectionTopBar(
                    count = selected.size,
                    onClose = vm::clearSelection,
                    onSelectAll = { vm.selectAll(list) },
                ) {
                    if (tab == HomeTab.BIN) BinSelectionActions(vm, selected)
                    else MediaSelectionActions(vm, nav, selected)
                }
            } else {
                TopAppBar(
                    title = { BrandTitle() },
                    actions = {
                        if (tab == HomeTab.BIN && trash.isNotEmpty()) {
                            IconButton(onClick = { confirmEmpty = true }) { Icon(Icons.Outlined.DeleteForever, "Empty bin") }
                        }
                        IconButton(onClick = { vm.sendQueue = emptyList(); nav.navigate("share") }) {
                            Icon(Icons.Outlined.Wifi, "Wi-Fi share")
                        }
                        IconButton(onClick = { nav.navigate("vault") }) { Icon(Icons.Outlined.Lock, "Vault") }
                        IconButton(onClick = { nav.navigate("settings") }) { Icon(Icons.Outlined.Settings, "Settings") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Panel.copy(alpha = 0.92f)) {
                HomeTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Neon,
                            selectedTextColor = Neon,
                            indicatorColor = Neon.copy(alpha = 0.15f),
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                loading && media.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                tab == HomeTab.ALBUMS -> AlbumsGrid(albums) { nav.navigate("album/${it.bucketId}") }
                else -> Column(Modifier.fillMaxSize()) {
                    if (tab == HomeTab.BIN && trash.isNotEmpty()) {
                        Text(
                            "Items in the bin are deleted for good after 30 days.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    MediaGrid(
                        items = list,
                        columns = columns,
                        groupByDate = group && tab != HomeTab.BIN &&
                            (sort == SortOrder.NEWEST || sort == SortOrder.OLDEST),
                        selection = selection,
                        showExpiry = tab == HomeTab.BIN,
                        emptyText = when (tab) {
                            HomeTab.FAVORITES -> "No favorites yet.\nTap the heart on any photo."
                            HomeTab.BIN -> "The bin is empty."
                            else -> "No photos or videos found."
                        },
                        onOpen = { idx -> nav.navigate("viewer/$source/$idx") },
                        onToggle = vm::toggleSelection,
                    )
                }
            }
        }
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty the bin?") },
            text = { Text("${trash.size} items will be deleted permanently.") },
            confirmButton = {
                Button(onClick = { confirmEmpty = false; vm.deleteForever(trash) }) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun AlbumScreen(vm: MainViewModel, nav: NavController, bucketId: Long) {
    val media by vm.media.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val columns by vm.gridColumns.collectAsStateWithLifecycle()
    val group by vm.groupByDate.collectAsStateWithLifecycle()
    val sort by vm.sortOrder.collectAsStateWithLifecycle()
    val items = remember(media, bucketId) { media.filter { it.bucketId == bucketId } }
    val selected = remember(items, selection) { items.filter { it.id in selection } }
    val name = items.firstOrNull()?.bucketName ?: "Album"

    BackHandler(enabled = selection.isNotEmpty()) { vm.clearSelection() }
    LaunchedEffect(Unit) { vm.clearSelection() }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            if (selection.isNotEmpty()) {
                SelectionTopBar(selected.size, vm::clearSelection, { vm.selectAll(items) }) {
                    MediaSelectionActions(vm, nav, selected)
                }
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(name, maxLines = 1)
                            Text(
                                "${items.size} items",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            MediaGrid(
                items = items,
                columns = columns,
                groupByDate = group && (sort == SortOrder.NEWEST || sort == SortOrder.OLDEST),
                selection = selection,
                emptyText = "This album is empty.",
                onOpen = { idx -> nav.navigate("viewer/a$bucketId/$idx") },
                onToggle = vm::toggleSelection,
            )
        }
    }
}
