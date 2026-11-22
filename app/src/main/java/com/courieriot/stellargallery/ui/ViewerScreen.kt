@file:OptIn(ExperimentalFoundationApi::class)

package com.courieriot.stellargallery.ui

import android.net.Uri
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.courieriot.stellargallery.MainViewModel
import com.courieriot.stellargallery.data.MediaItem
import com.courieriot.stellargallery.ui.theme.Magenta

@Composable
fun ViewerScreen(vm: MainViewModel, nav: NavController, source: String, startIndex: Int) {
    val context = LocalContext.current
    val flow = remember(source) { vm.listFor(source) }
    val initial = remember(source) { vm.snapshotFor(source) }
    val list by flow.collectAsStateWithLifecycle(initialValue = initial)

    if (list.isEmpty()) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }

    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, list.lastIndex)) { list.size }
    val current = list[pager.currentPage.coerceIn(0, list.lastIndex)]
    var chrome by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }
    val isTrash = source == "trash"

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            key = { list.getOrNull(it)?.id ?: it },
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { p ->
            val item = list.getOrNull(p) ?: return@HorizontalPager
            if (item.isVideo) {
                VideoPage(
                    uri = item.uri,
                    placeholder = item.uri,
                    active = p == pager.currentPage,
                    onChromeChange = { chrome = it },
                    controlsBottomPadding = 76.dp,
                )
            } else {
                ZoomableImage(model = item.uri, onTap = { chrome = !chrome })
            }
        }

        AnimatedVisibility(
            visible = chrome, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        current.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    )
                    Text(fullDate(current.dateTaken), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { showInfo = true }) { Icon(Icons.Outlined.Info, "Details", tint = Color.White) }
            }
        }

        AnimatedVisibility(
            visible = chrome, enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                    .navigationBarsPadding()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                if (isTrash) {
                    ViewerAction(Icons.Outlined.Restore, "Restore") { vm.restore(listOf(current)) }
                    ViewerAction(Icons.Outlined.DeleteForever, "Delete") { vm.deleteForever(listOf(current)) }
                } else {
                    ViewerAction(Icons.Outlined.Share, "Share") { shareItems(context, listOf(current)) }
                    ViewerAction(
                        if (current.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        "Favorite",
                        tint = if (current.isFavorite) Magenta else Color.White,
                    ) { vm.setFavorite(listOf(current), !current.isFavorite) }
                    if (!current.isVideo) {
                        ViewerAction(Icons.Outlined.Edit, "Edit") { vm.editTarget = current; nav.navigate("editor") }
                    }
                    ViewerAction(Icons.Outlined.VisibilityOff, "Hide") { hideOrSetup(vm, nav, listOf(current)) }
                    ViewerAction(Icons.Outlined.Delete, "Delete") { vm.trash(listOf(current)) }
                }
            }
        }
    }

    if (showInfo) InfoDialog(current) { showInfo = false }
}

@Composable
private fun InfoDialog(item: MediaItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Details") },
        text = {
            Column {
                InfoRow("Name", item.name)
                InfoRow("Date", fullDate(item.dateTaken))
                InfoRow("Size", formatSize(context, item.size))
                if (item.width > 0 && item.height > 0) InfoRow("Resolution", "${item.width} × ${item.height}")
                if (item.isVideo) InfoRow("Length", formatDuration(item.duration))
                InfoRow("Type", item.mimeType)
                InfoRow("Folder", item.relativePath.ifBlank { item.bucketName })
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Pinch / double-tap zoom image. Single-finger swipes pass through to the pager when not zoomed. */
@Composable
fun ZoomableImage(model: Any?, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val tap by rememberUpdatedState(onTap)

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = boxSize.width * (s - 1f) / 2f
        val maxY = boxSize.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { tap() },
                    onDoubleTap = { p ->
                        if (scale > 1f) {
                            scale = 1f; offset = Offset.Zero
                        } else {
                            val center = Offset(boxSize.width / 2f, boxSize.height / 2f)
                            scale = 2.5f
                            offset = clamp((center - p) * 1.5f, 2.5f)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers > 1 || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newScale = (scale * zoom).coerceIn(1f, 6f)
                            offset = if (newScale <= 1f) Offset.Zero else clamp(offset + pan, newScale)
                            scale = newScale
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(model).size(2560).build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                },
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPage(
    uri: Uri,
    placeholder: Any?,
    active: Boolean,
    onChromeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    controlsBottomPadding: Dp = 0.dp,
) {
    if (!active) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            AsyncImage(
                model = placeholder, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Icon(Icons.Filled.PlayCircle, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(72.dp))
        }
        return
    }
    val context = LocalContext.current
    val onChrome by rememberUpdatedState(onChromeChange)
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player.pause() }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                setControllerVisibilityListener(
                    PlayerView.ControllerVisibilityListener { v -> onChrome(v == View.VISIBLE) },
                )
            }
        },
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = controlsBottomPadding)
            .navigationBarsPadding(),
    )
}
