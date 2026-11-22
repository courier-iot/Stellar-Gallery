@file:OptIn(ExperimentalMaterial3Api::class)

package com.courieriot.stellargallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.courieriot.stellargallery.MainViewModel
import com.courieriot.stellargallery.sorter.AlbumStatus
import com.courieriot.stellargallery.ui.theme.Panel

@Composable
fun SmartAlbumScreen(vm: MainViewModel, nav: NavController, albumId: String) {
    val state by vm.sorterState.collectAsStateWithLifecycle()
    val media by vm.media.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val columns by vm.gridColumns.collectAsStateWithLifecycle()
    val view = state.albums.firstOrNull { it.album.id == albumId }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }

    if (view == null) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    val items = remember(media, view.ids) { val set = view.ids.toHashSet(); media.filter { it.id in set } }
    val selected = remember(items, selection) { items.filter { it.id in selection } }
    val suggested = view.album.status == AlbumStatus.SUGGESTED

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
                            Text(view.album.name, maxLines = 1)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SmartBadge()
                                Text(
                                    "  ${items.size} items",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    actions = {
                        if (items.any { !it.isVideo }) {
                            IconButton(onClick = { nav.navigate("slideshow/smart_$albumId/0") }) {
                                Icon(Icons.Outlined.Slideshow, "Slideshow")
                            }
                        }
                        IconButton(onClick = { renaming = true }) { Icon(Icons.Outlined.Edit, "Rename") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (suggested) {
                                    MenuRow(Icons.Outlined.Check, "Approve (add to Albums tab)") { menu = false; vm.sorterApprove(albumId) }
                                } else {
                                    MenuRow(Icons.Outlined.Undo, "Remove from Albums tab") { menu = false; vm.sorterUnapprove(albumId) }
                                }
                                MenuRow(Icons.Outlined.Delete, if (view.album.key != null) "Dismiss album" else "Delete album") {
                                    menu = false; vm.sorterDelete(albumId); nav.popBackStack()
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            }
        },
        bottomBar = {
            if (selection.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .navigationBarsPadding(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ViewerAction(Icons.Outlined.RemoveCircleOutline, "Not this album") { vm.sorterRemove(albumId, selected) }
                    ViewerAction(Icons.Outlined.SwapHoriz, "Move to…") { moving = true }
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (suggested && selection.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Suggested by the sorter. Look good?",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(onClick = { vm.sorterApprove(albumId) }) { Text("Approve") }
                    }
                }
            }
            Box(Modifier.fillMaxSize()) {
                MediaGrid(
                    items = items,
                    columns = columns,
                    groupByDate = false,
                    selection = selection,
                    emptyText = "Nothing here yet. Add photos from any album: select → ⋮ → Add to smart album.",
                    onOpen = { idx -> nav.navigate("viewer/smart_$albumId/$idx") },
                    onToggle = vm::toggleSelection,
                    onSelectionChange = vm::setSelection,
                    onColumnsChange = vm::setGridColumns,
                )
            }
        }
    }

    if (renaming) {
        RenameDialog(view.album.name, onDismiss = { renaming = false }) { vm.sorterRename(albumId, it); renaming = false }
    }
    if (moving) {
        SmartAlbumPicker(
            vm, title = "Move to…", excludeId = albumId,
            onDismiss = { moving = false },
            onNew = { name -> vm.sorterCreate(name, null, selected); vm.sorterRemove(albumId, selected); moving = false },
        ) { target ->
            vm.sorterMove(albumId, target, selected)
            moving = false
        }
    }
}
