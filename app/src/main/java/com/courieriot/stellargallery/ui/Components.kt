@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.courieriot.stellargallery.ui

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.courieriot.stellargallery.MainViewModel
import com.courieriot.stellargallery.data.Album
import com.courieriot.stellargallery.data.MediaItem
import com.courieriot.stellargallery.ui.theme.Magenta
import com.courieriot.stellargallery.ui.theme.Neon
import com.courieriot.stellargallery.ui.theme.Panel
import com.courieriot.stellargallery.ui.theme.PanelHigh
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---------------- Branding ----------------

@Composable
fun BrandTitle(text: String = "STELLAR GALLERY") {
    Text(
        text,
        style = TextStyle(
            brush = Brush.linearGradient(listOf(Neon, Magenta)),
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            letterSpacing = 3.sp,
        ),
    )
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------- Grid ----------------

private sealed interface GridEntry { val key: Any }
private data class HeaderEntry(val label: String) : GridEntry { override val key: Any get() = "h_$label" }
private data class CellEntry(val item: MediaItem, val index: Int) : GridEntry { override val key: Any get() = item.id }

private val dayFmt = DateTimeFormatter.ofPattern("EEE, MMM d")
private val dayYearFmt = DateTimeFormatter.ofPattern("MMM d, yyyy")
private val fullFmt = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a")

fun dayLabel(millis: Long): String {
    val zone = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when {
        d == today -> "Today"
        d == today.minusDays(1) -> "Yesterday"
        d.year == today.year -> d.format(dayFmt)
        else -> d.format(dayYearFmt)
    }
}

fun fullDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(fullFmt)

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

private fun buildEntries(items: List<MediaItem>, group: Boolean): List<GridEntry> {
    if (!group) return items.mapIndexed { i, m -> CellEntry(m, i) }
    val out = ArrayList<GridEntry>(items.size + 32)
    var last: String? = null
    items.forEachIndexed { i, m ->
        val label = dayLabel(m.dateTaken)
        if (label != last) { out += HeaderEntry(label); last = label }
        out += CellEntry(m, i)
    }
    return out
}

@Composable
fun MediaGrid(
    items: List<MediaItem>,
    columns: Int,
    groupByDate: Boolean,
    selection: Set<Long>,
    emptyText: String,
    onOpen: (Int) -> Unit,
    onToggle: (Long) -> Unit,
    showExpiry: Boolean = false,
) {
    if (items.isEmpty()) { EmptyState(emptyText); return }
    val entries = remember(items, groupByDate) { buildEntries(items, groupByDate) }
    val selecting = selection.isNotEmpty()
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(start = 3.dp, end = 3.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(
            items = entries,
            key = { it.key },
            span = { e -> if (e is HeaderEntry) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
        ) { e ->
            when (e) {
                is HeaderEntry -> Text(
                    e.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 6.dp),
                )
                is CellEntry -> MediaThumb(
                    item = e.item,
                    selected = e.item.id in selection,
                    selectionMode = selecting,
                    showExpiry = showExpiry,
                    onClick = { if (selecting) onToggle(e.item.id) else onOpen(e.index) },
                    onLongClick = { onToggle(e.item.id) },
                )
            }
        }
    }
}

@Composable
fun MediaThumb(
    item: MediaItem,
    selected: Boolean,
    selectionMode: Boolean,
    showExpiry: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val inset by animateDpAsState(if (selected) 8.dp else 0.dp, label = "inset")
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(item.uri).size(360).build(),
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .padding(inset)
                .clip(shape)
                .background(Panel)
                .then(if (selected) Modifier.border(2.dp, Neon, shape) else Modifier),
        )
        if (item.isVideo) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(inset + 4.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(12.dp))
                Text(formatDuration(item.duration), color = Color.White, fontSize = 10.sp)
            }
        }
        if (item.isFavorite && !selectionMode) {
            Icon(
                Icons.Filled.Favorite, null, tint = Magenta,
                modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).size(14.dp),
            )
        }
        if (showExpiry && item.dateExpires > 0) {
            val days = ((item.dateExpires * 1000 - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)
            Text(
                "${days}d",
                color = Color.White, fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(inset + 4.dp)
                    .background(Magenta.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp),
            )
        }
        if (selectionMode) {
            Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) Neon else Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(22.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape),
            )
        }
    }
}

@Composable
fun AlbumsGrid(albums: List<Album>, onOpen: (Album) -> Unit) {
    if (albums.isEmpty()) { EmptyState("No albums yet."); return }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(albums, key = { it.bucketId }) { a ->
            Column(Modifier.clickable { onOpen(a) }) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(a.cover.uri).size(500).build(),
                    contentDescription = a.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Panel)
                        .border(1.dp, Brush.linearGradient(listOf(Neon.copy(alpha = .6f), Magenta.copy(alpha = .6f))), RoundedCornerShape(14.dp)),
                )
                Spacer(Modifier.height(6.dp))
                Text(a.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text("${a.count}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------- Selection ----------------

@Composable
fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    TopAppBar(
        title = { Text("$count selected") },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Clear selection") } },
        actions = {
            IconButton(onClick = onSelectAll) { Icon(Icons.Filled.SelectAll, "Select all") }
            actions()
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = PanelHigh),
    )
}

private enum class PickMode { COPY, MOVE }

@Composable
fun RowScope.MediaSelectionActions(vm: MainViewModel, nav: NavController, items: List<MediaItem>) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<PickMode?>(null) }
    val allFav = items.isNotEmpty() && items.all { it.isFavorite }

    IconButton(onClick = { shareItems(context, items) }) { Icon(Icons.Outlined.Share, "Share") }
    IconButton(onClick = { vm.setFavorite(items, !allFav) }) {
        Icon(if (allFav) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, "Favorite", tint = if (allFav) Magenta else Color.White)
    }
    IconButton(onClick = { vm.trash(items) }) { Icon(Icons.Outlined.Delete, "Move to bin") }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Copy to album") },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                onClick = { menu = false; picker = PickMode.COPY },
            )
            DropdownMenuItem(
                text = { Text("Move to album") },
                leadingIcon = { Icon(Icons.Outlined.Folder, null) },
                onClick = { menu = false; picker = PickMode.MOVE },
            )
            DropdownMenuItem(
                text = { Text("Hide in vault") },
                leadingIcon = { Icon(Icons.Outlined.VisibilityOff, null) },
                onClick = { menu = false; hideOrSetup(vm, nav, items) },
            )
            DropdownMenuItem(
                text = { Text("Send over Wi-Fi") },
                leadingIcon = { Icon(Icons.Outlined.Wifi, null) },
                onClick = { menu = false; vm.sendQueue = items; nav.navigate("share") },
            )
        }
    }
    picker?.let { mode ->
        val albums by vm.albums.collectAsStateWithLifecycle()
        AlbumPickerDialog(
            albums = albums,
            title = if (mode == PickMode.COPY) "Copy to…" else "Move to…",
            onPick = { path ->
                if (mode == PickMode.COPY) vm.copyTo(items, path) else vm.moveTo(items, path)
                picker = null
            },
            onDismiss = { picker = null },
        )
    }
}

@Composable
fun RowScope.BinSelectionActions(vm: MainViewModel, items: List<MediaItem>) {
    IconButton(onClick = { vm.restore(items) }) { Icon(Icons.Outlined.Restore, "Restore") }
    IconButton(onClick = { vm.deleteForever(items) }) { Icon(Icons.Outlined.DeleteForever, "Delete forever") }
}

fun hideOrSetup(vm: MainViewModel, nav: NavController, items: List<MediaItem>) {
    if (!vm.vaultHasPin()) {
        vm.toast("Set up a vault PIN first, then hide your photos")
        nav.navigate("vault")
    } else {
        vm.hideInVault(items)
    }
}

@Composable
fun AlbumPickerDialog(albums: List<Album>, title: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var newName by remember { mutableStateOf("") }
    val targets = remember(albums) {
        albums.filter { it.relativePath.startsWith("DCIM/") || it.relativePath.startsWith("Pictures/") }
            .distinctBy { it.relativePath }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.take(60) },
                    label = { Text("New album name") },
                    singleLine = true,
                    trailingIcon = {
                        val clean = newName.replace(Regex("[/\\\\:*?\"<>|]"), "").trim()
                        if (clean.isNotEmpty()) {
                            IconButton(onClick = { onPick("Pictures/$clean/") }) { Icon(Icons.Filled.Check, "Create") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(targets, key = { it.relativePath }) { a ->
                        ListItem(
                            headlineContent = { Text(a.name) },
                            supportingContent = { Text(a.relativePath, style = MaterialTheme.typography.labelSmall) },
                            leadingContent = {
                                AsyncImage(
                                    model = a.cover.uri, contentDescription = null, contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)),
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { onPick(a.relativePath) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------- Utilities ----------------

fun shareItems(context: Context, items: List<MediaItem>) {
    if (items.isEmpty()) return
    val uris = ArrayList<Uri>(items.map { it.uri })
    val type = when {
        items.none { it.isVideo } -> "image/*"
        items.all { it.isVideo } -> "video/*"
        else -> "*/*"
    }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    intent.type = type
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newUri(context.contentResolver, "media", uris[0]).apply {
        uris.drop(1).forEach { addItem(ClipData.Item(it)) }
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share")) }
}

fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun ViewerAction(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Icon(icon, label, tint = tint)
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
    }
}
