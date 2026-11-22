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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
import androidx.compose.material.icons.outlined.AutoAwesome
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
import coil.request.videoFrameMillis
import com.courieriot.stellargallery.MainViewModel
import com.courieriot.stellargallery.data.Album
import com.courieriot.stellargallery.data.MediaItem
import com.courieriot.stellargallery.sorter.SmartAlbumView
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
fun EmptyState(text: String, modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------- Images ----------------

/** Image request that grabs a frame ~1.5s into videos (many videos start on a black frame). */
fun mediaRequest(context: Context, item: MediaItem, size: Int): ImageRequest =
    ImageRequest.Builder(context).data(item.uri).size(size).apply {
        if (item.isVideo) videoFrameMillis(if (item.duration in 1L..2999L) item.duration / 2 else 1500L)
    }.build()

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
    onSelectionChange: (Set<Long>) -> Unit,
    onColumnsChange: ((Int) -> Unit)? = null,
    showExpiry: Boolean = false,
    header: (@Composable () -> Unit)? = null,
) {
    if (items.isEmpty() && header == null) { EmptyState(emptyText); return }
    val entries = remember(items, groupByDate) { buildEntries(items, groupByDate) }
    val ids = remember(items) { items.map { it.id } }
    val indexOf = remember(items) { HashMap<Long, Int>(items.size * 2).apply { items.forEachIndexed { i, m -> put(m.id, i) } } }
    val state = rememberLazyGridState()
    val selecting = selection.isNotEmpty()

    val autoScroll = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(autoScroll.floatValue) {
        val speed = autoScroll.floatValue
        if (speed != 0f) while (isActive) { state.scrollBy(speed); delay(16) }
    }
    val curSelection by rememberUpdatedState(selection)
    val curSelChange by rememberUpdatedState(onSelectionChange)
    val curColumns by rememberUpdatedState(columns)
    val curColChange by rememberUpdatedState(onColumnsChange)
    val curIds by rememberUpdatedState(ids)
    val curIndex by rememberUpdatedState(indexOf)
    val edgePx = with(LocalDensity.current) { 64.dp.toPx() }

    LazyVerticalGrid(
        state = state,
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(start = 3.dp, end = 3.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .fillMaxSize()
            // Pinch with two fingers to change how many photos fit per row.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var acc = 1f
                    do {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val cb = curColChange
                        if (cb != null && ev.changes.count { it.pressed } >= 2) {
                            acc *= ev.calculateZoom()
                            if (acc > 1.3f) { cb(curColumns - 1); acc = 1f }
                            else if (acc < 0.77f) { cb(curColumns + 1); acc = 1f }
                            ev.changes.forEach { it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            }
            // Long-press then drag to select a range.
            .pointerInput(Unit) {
                var anchor = -1
                var base: Set<Long> = emptySet()
                fun idAt(p: Offset): Long? = state.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                    p.x >= info.offset.x && p.x <= info.offset.x + info.size.width &&
                        p.y >= info.offset.y && p.y <= info.offset.y + info.size.height
                }?.key as? Long
                detectDragGesturesAfterLongPress(
                    onDragStart = { p ->
                        val id = idAt(p)
                        if (id != null) {
                            anchor = curIndex[id] ?: -1
                            base = curSelection
                            curSelChange(base + id)
                        }
                    },
                    onDragEnd = { anchor = -1; autoScroll.floatValue = 0f },
                    onDragCancel = { anchor = -1; autoScroll.floatValue = 0f },
                    onDrag = { change, _ ->
                        if (anchor >= 0) {
                            change.consume()
                            val y = change.position.y
                            autoScroll.floatValue = when {
                                y > size.height - edgePx -> 14f
                                y < edgePx -> -14f
                                else -> 0f
                            }
                            val cur = idAt(change.position)?.let { curIndex[it] }
                            if (cur != null) {
                                val lo = minOf(anchor, cur)
                                val hi = maxOf(anchor, cur)
                                curSelChange(base + curIds.subList(lo, hi + 1))
                            }
                        }
                    },
                )
            },
    ) {
        if (header != null) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
        }
        if (items.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(emptyText, Modifier.height(260.dp))
            }
        }
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
) {
    val context = LocalContext.current
    val inset by animateDpAsState(if (selected) 8.dp else 0.dp, label = "inset")
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = mediaRequest(context, item, 360),
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
fun AlbumsGrid(
    albums: List<Album>,
    smart: List<SmartAlbumView> = emptyList(),
    mediaById: Map<Long, MediaItem> = emptyMap(),
    onOpen: (Album) -> Unit,
    onOpenSmart: (String) -> Unit = {},
) {
    if (albums.isEmpty() && smart.isEmpty()) { EmptyState("No albums yet."); return }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (smart.isNotEmpty()) {
            item(key = "smart_h", span = { GridItemSpan(maxLineSpan) }) {
                Text("✦ Smart albums", style = MaterialTheme.typography.titleSmall, color = Neon)
            }
            items(smart, key = { "s_" + it.album.id }) { sv ->
                val cover = sv.ids.firstNotNullOfOrNull { mediaById[it] }
                AlbumCard(
                    name = sv.album.name,
                    count = sv.ids.size,
                    coverUri = cover?.uri,
                    smart = true,
                    onClick = { onOpenSmart(sv.album.id) },
                )
            }
            item(key = "folders_h", span = { GridItemSpan(maxLineSpan) }) {
                Text("Folders", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(albums, key = { it.bucketId }) { a ->
            AlbumCard(name = a.name, count = a.count, coverUri = a.cover.uri, smart = false, onClick = { onOpen(a) })
        }
    }
}

@Composable
private fun AlbumCard(name: String, count: Int, coverUri: Uri?, smart: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(coverUri).size(500).build(),
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Panel)
                    .border(
                        if (smart) 2.dp else 1.dp,
                        Brush.linearGradient(listOf(Neon.copy(alpha = if (smart) 1f else .6f), Magenta.copy(alpha = if (smart) 1f else .6f))),
                        RoundedCornerShape(14.dp),
                    ),
            )
            if (smart) {
                Text(
                    "✦",
                    color = Neon,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurface)
        Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    var smartPicker by remember { mutableStateOf(false) }
    val sorterOn by vm.sorterEnabled.collectAsStateWithLifecycle()
    val allFav = items.isNotEmpty() && items.all { it.isFavorite }
    if (smartPicker) {
        SmartAlbumPicker(
            vm, title = "Add to smart album", excludeId = null,
            onDismiss = { smartPicker = false },
            onNew = { name -> vm.sorterCreate(name, null, items); smartPicker = false },
        ) { targetId ->
            vm.sorterAdd(targetId, items)
            smartPicker = false
        }
    }

    IconButton(onClick = { vm.shareMedia(context, items) }) { Icon(Icons.Outlined.Share, "Share") }
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
            if (sorterOn) {
                DropdownMenuItem(
                    text = { Text("Add to smart album") },
                    leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null) },
                    onClick = { menu = false; smartPicker = true },
                )
            }
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
