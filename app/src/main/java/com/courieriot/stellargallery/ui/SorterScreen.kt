package com.courieriot.stellargallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.courieriot.stellargallery.MainViewModel
import com.courieriot.stellargallery.sorter.AlbumStatus
import com.courieriot.stellargallery.sorter.SearchModelState
import com.courieriot.stellargallery.sorter.SmartAlbumView
import com.courieriot.stellargallery.ui.theme.Magenta
import com.courieriot.stellargallery.ui.theme.Neon
import com.courieriot.stellargallery.ui.theme.Panel

/** The Sorter tab: suggestions to approve, your smart albums, and what the sorter is doing. */
@Composable
fun SorterTab(vm: MainViewModel, nav: NavController) {
    val state by vm.sorterState.collectAsStateWithLifecycle()
    val media by vm.media.collectAsStateWithLifecycle()
    val byId = remember(media) { media.associateBy { it.id } }
    var renaming by remember { mutableStateOf<SmartAlbumView?>(null) }
    var creating by remember { mutableStateOf(false) }
    var showDismissed by remember { mutableStateOf(false) }

    val suggested = state.albums.filter { it.album.status == AlbumStatus.SUGGESTED && it.ids.size >= 3 }.sortedByDescending { it.ids.size }
    val waiting = state.albums.count { it.album.status == AlbumStatus.SUGGESTED && it.ids.size < 3 }
    val approved = state.albums.filter { it.album.status == AlbumStatus.APPROVED }
    val dismissed = state.albums.filter { it.album.status == AlbumStatus.DISMISSED }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(16.dp).fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AutoAwesome, null, tint = Neon)
                        Spacer(Modifier.width(8.dp))
                        Text("Photo sorter", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(6.dp))
                    when {
                        state.modelError != null -> Text(state.modelError ?: "", color = Magenta)
                        state.scanning -> {
                            Text("Looking at your photos… ${state.done} of ${state.total}")
                            LinearProgressIndicator(
                                progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                            Text(
                                "Only new photos get scanned after this. Everything stays on your phone.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        else -> Text(
                            "Sorted ${state.sortedCount} of ${state.photoCount} photos and videos.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.search != SearchModelState.Ready) {
                        Text(
                            "Tip: turn on Search & sort in Settings to search by what's in a photo and describe your own albums.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (!state.scanning && state.modelError == null && state.photoCount > 0) {
                        Text(
                            "${state.photoCount - state.sortedCount} left unsorted on purpose — it only files photos it's confident about.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { nav.navigate("trainer") }, enabled = state.unsureCount > 0 && !state.scanning) {
                            Text(if (state.unsureCount > 0) "Train it (${minOf(20, state.unsureCount)} quick questions)" else "Train it")
                        }
                        OutlinedButton(onClick = { creating = true }) {
                            Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("New")
                        }
                    }
                }
            }
        }

        if (suggested.isNotEmpty()) {
            item { SectionTitle("Suggested albums") }
            items(suggested, key = { "sg_" + it.album.id }) { sv ->
                SuggestionCard(
                    view = sv,
                    coverUris = sv.ids.asSequence().mapNotNull { byId[it]?.uri }.take(10).toList(),
                    onOpen = { nav.navigate("smart/${sv.album.id}") },
                    onApprove = { vm.sorterApprove(sv.album.id) },
                    onRename = { renaming = sv },
                    onDismiss = { vm.sorterDismiss(sv.album.id) },
                )
            }
        }
        if (waiting > 0) {
            item {
                Text(
                    "$waiting more categories are waiting for photos.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (approved.isNotEmpty()) {
            item { SectionTitle("In your Albums tab") }
            items(approved, key = { "ap_" + it.album.id }) { sv ->
                ListItem(
                    headlineContent = { Text(sv.album.name) },
                    supportingContent = { Text("${sv.ids.size} items") },
                    leadingContent = {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current).data(sv.ids.firstNotNullOfOrNull { byId[it]?.uri }).size(150).build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Panel),
                        )
                    },
                    trailingContent = {
                        TextButton(onClick = { nav.navigate("match/${sv.album.id}") }) { Text("Match") }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { nav.navigate("smart/${sv.album.id}") },
                )
            }
        }

        item {
            Text(
                "Teach it: open a smart album, select photos, and tap “Not this album” or “Move to…”. " +
                    "From any album you can also select photos → ⋮ → Add to smart album. It learns from every change.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        if (dismissed.isNotEmpty()) {
            item {
                TextButton(onClick = { showDismissed = !showDismissed }) {
                    Text(if (showDismissed) "Hide dismissed (${dismissed.size})" else "Show dismissed (${dismissed.size})")
                }
            }
            if (showDismissed) {
                items(dismissed, key = { "ds_" + it.album.id }) { sv ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(sv.album.name, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.sorterRestore(sv.album.id) }) { Text("Restore") }
                    }
                }
            }
        }
    }

    renaming?.let { sv ->
        RenameDialog(sv.album.name, onDismiss = { renaming = null }) { vm.sorterRename(sv.album.id, it); renaming = null }
    }
    if (creating) NewSmartAlbumDialog(vm, onDismiss = { creating = false }, onMatch = { id -> nav.navigate("match/$id") })
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = Neon, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun SuggestionCard(
    view: SmartAlbumView,
    coverUris: List<android.net.Uri>,
    onOpen: () -> Unit,
    onApprove: () -> Unit,
    onRename: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(view.album.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${view.ids.size} items", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Preview")
            }
            Spacer(Modifier.height(8.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) {
                items(coverUris) { u ->
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(u).size(200).build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black)
                            .clickable(onClick = onOpen),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onApprove, modifier = Modifier.padding(start = 6.dp)) { Text("Approve") }
                TextButton(onClick = onRename) { Text("Rename") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Dismiss", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename album") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, singleLine = true) },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = { onSave(name) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun NewSmartAlbumDialog(vm: MainViewModel, onDismiss: () -> Unit, onMatch: ((String) -> Unit)? = null) {
    val state by vm.sorterState.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    val canDescribe = state.search == SearchModelState.Ready
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New smart album") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(40) }, label = { Text("Name") }, singleLine = true)
                if (canDescribe) {
                    OutlinedTextField(
                        value = prompt, onValueChange = { prompt = it.take(80) },
                        label = { Text("What goes in it? (optional)") },
                        placeholder = { Text("e.g. orange cat, Magic cards, receipts") },
                        singleLine = true,
                    )
                } else {
                    Text(
                        "Next you'll pick a few example photos, and the sorter will find more like them for you to confirm.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (onMatch != null) {
                Button(enabled = name.isNotBlank(), onClick = {
                    vm.sorterCreateThen(name, if (canDescribe) prompt else null) { id -> onMatch(id) }
                    onDismiss()
                }) { Text("Create & match") }
            } else {
                Button(enabled = name.isNotBlank(), onClick = {
                    vm.sorterCreate(name, if (canDescribe) prompt else null)
                    onDismiss()
                }) { Text("Create") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Picks a smart album (approved or suggested). Can also create a new one. */
@Composable
fun SmartAlbumPicker(
    vm: MainViewModel,
    title: String,
    excludeId: String?,
    onDismiss: () -> Unit,
    onNew: ((String) -> Unit)? = null,
    onPick: (String) -> Unit,
) {
    val state by vm.sorterState.collectAsStateWithLifecycle()
    val choices = state.albums
        .filter { it.album.status != AlbumStatus.DISMISSED && it.album.id != excludeId }
        .sortedWith(compareBy<SmartAlbumView>({ if (it.album.status == AlbumStatus.APPROVED) 0 else 1 }, { it.album.name.lowercase() }))
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (onNew != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newName, onValueChange = { newName = it.take(40) },
                            label = { Text("New smart album") }, singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(enabled = newName.isNotBlank(), onClick = { onNew(newName) }) { Text("Create") }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                if (choices.isEmpty()) {
                    Text("No smart albums yet.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        items(choices, key = { it.album.id }) { sv ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onPick(sv.album.id) }
                                    .padding(vertical = 10.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (sv.album.status == AlbumStatus.APPROVED) "✦" else "·",
                                    color = Neon,
                                    modifier = Modifier.width(18.dp),
                                )
                                Text(sv.album.name, modifier = Modifier.weight(1f))
                                Text("${sv.ids.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun SmartBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .border(1.dp, Neon, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) { Text("✦ smart", color = Neon, style = MaterialTheme.typography.labelSmall) }
}
