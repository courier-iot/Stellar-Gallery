@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.courieriot.stellargallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudUpload
import com.courieriot.stellargallery.sorter.AlbumStatus
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.courieriot.stellargallery.Memory
import com.courieriot.stellargallery.ui.theme.Magenta
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
    SORTER("Sorter", Icons.Outlined.AutoAwesome),
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
    val hideVault by vm.hideVaultEntry.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    val showMemories by vm.showMemories.collectAsStateWithLifecycle()
    val sorterOn by vm.sorterEnabled.collectAsStateWithLifecycle()
    val sorterState by vm.sorterState.collectAsStateWithLifecycle()
    val smartAlbums = remember(sorterState, sorterOn) {
        if (!sorterOn) emptyList()
        else sorterState.albums.filter { it.album.status == AlbumStatus.APPROVED }
    }
    val mediaById = remember(media) { media.associateBy { it.id } }
    LaunchedEffect(sorterOn) { if (!sorterOn && tab == HomeTab.SORTER) tab = HomeTab.PHOTOS }
    var confirmEmpty by remember { mutableStateOf(false) }

    val list = when (tab) {
        HomeTab.PHOTOS -> media
        HomeTab.FAVORITES -> favorites
        HomeTab.BIN -> trash
        HomeTab.ALBUMS, HomeTab.SORTER -> emptyList()
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
                var menu by remember { mutableStateOf(false) }
                TopAppBar(
                    title = {
                        // Long-press the title to open the vault when its icon is hidden.
                        Box(
                            Modifier.combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                                onLongClick = { nav.navigate("vault") },
                            ),
                        ) { BrandTitle() }
                    },
                    actions = {
                        if (tab == HomeTab.BIN && trash.isNotEmpty()) {
                            IconButton(onClick = { confirmEmpty = true }) { Icon(Icons.Outlined.DeleteForever, "Empty bin") }
                        }
                        IconButton(onClick = { nav.navigate("search") }) { Icon(Icons.Outlined.Search, "Search") }
                        if (!hideVault) {
                            IconButton(onClick = { nav.navigate("vault") }) { Icon(Icons.Outlined.Lock, "Vault") }
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                MenuRow(Icons.Outlined.Wifi, "Wi-Fi share") { menu = false; vm.sendQueue = emptyList(); nav.navigate("share") }
                                MenuRow(Icons.Outlined.Slideshow, "Slideshow") { menu = false; nav.navigate("slideshow/all/0") }
                                MenuRow(Icons.Outlined.Map, "Photo map") { menu = false; nav.navigate("map") }
                                MenuRow(Icons.Outlined.FileCopy, "Find duplicates") { menu = false; nav.navigate("duplicates") }
                                MenuRow(Icons.Outlined.PieChart, "Storage") { menu = false; nav.navigate("storage") }
                                MenuRow(Icons.Outlined.CloudUpload, "Backup") { menu = false; nav.navigate("backup") }
                                MenuRow(Icons.Outlined.Settings, "Settings") { menu = false; nav.navigate("settings") }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Panel.copy(alpha = 0.92f)) {
                HomeTab.entries.filter { it != HomeTab.SORTER || sorterOn }.forEach { t ->
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
                tab == HomeTab.ALBUMS -> AlbumsGrid(
                    albums = albums,
                    smart = smartAlbums,
                    mediaById = mediaById,
                    onOpen = { nav.navigate("album/${it.bucketId}") },
                    onOpenSmart = { nav.navigate("smart/$it") },
                )
                tab == HomeTab.SORTER -> SorterTab(vm, nav)
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
                        onSelectionChange = vm::setSelection,
                        onColumnsChange = vm::setGridColumns,
                        header = if (tab == HomeTab.PHOTOS && showMemories && memories.isNotEmpty()) {
                            {
                                MemoriesRow(memories) { m ->
                                    val src = vm.setCustomList("memories", m.items.map { it.id })
                                    nav.navigate("viewer/$src/0")
                                }
                            }
                        } else null,
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
                    actions = {
                        if (items.any { !it.isVideo }) {
                            IconButton(onClick = { nav.navigate("slideshow/alb_$bucketId/0") }) {
                                Icon(Icons.Outlined.Slideshow, "Slideshow")
                            }
                        }
                        IconButton(onClick = { vm.sendQueue = items; nav.navigate("share") }) {
                            Icon(Icons.Outlined.Wifi, "Send album over Wi-Fi")
                        }
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
                onOpen = { idx -> nav.navigate("viewer/alb_$bucketId/$idx") },
                onToggle = vm::toggleSelection,
                onSelectionChange = vm::setSelection,
                onColumnsChange = vm::setGridColumns,
            )
        }
    }
}

@Composable
fun MenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

@Composable
private fun MemoriesRow(memories: List<Memory>, onOpen: (Memory) -> Unit) {
    Column(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
        Text(
            "On this day",
            style = MaterialTheme.typography.titleMedium,
            color = Neon,
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 6.dp),
        ) {
            items(memories, key = { it.yearsAgo }) { m ->
                Box(
                    Modifier
                        .size(width = 150.dp, height = 190.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, Brush.linearGradient(listOf(Neon, Magenta)), RoundedCornerShape(16.dp))
                        .clickable { onOpen(m) },
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(m.items.first().uri).size(480).build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().background(Panel),
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))),
                    )
                    Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                        Text(
                            if (m.yearsAgo == 1) "1 year ago" else "${m.yearsAgo} years ago",
                            color = Color.White, style = MaterialTheme.typography.titleSmall,
                        )
                        Text("${m.items.size} items", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
