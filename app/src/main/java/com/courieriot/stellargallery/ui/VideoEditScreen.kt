@file:OptIn(ExperimentalMaterial3Api::class)

package com.courieriot.stellargallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.courieriot.stellargallery.MainViewModel
import com.courieriot.stellargallery.ui.theme.Neon
import com.courieriot.stellargallery.ui.theme.Panel
import kotlinx.coroutines.delay
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoEditScreen(vm: MainViewModel, nav: NavController) {
    val target = vm.videoEditTarget
    if (target == null) {
        LaunchedEffect(Unit) { nav.popBackStack() }
        return
    }
    val context = LocalContext.current
    val player = remember(target.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(target.uri))
            prepare()
        }
    }
    var duration by remember { mutableLongStateOf(target.duration.coerceAtLeast(0)) }
    var range by remember { mutableStateOf(0f..(duration.toFloat().coerceAtLeast(1f))) }
    var position by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var transformer by remember { mutableStateOf<Transformer?>(null) }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && player.duration > 0 && duration != player.duration) {
                    val firstTime = duration <= 0 || range.endInclusive >= duration.toFloat() - 1f
                    duration = player.duration
                    if (firstTime) range = 0f..duration.toFloat()
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        player.addListener(l)
        onDispose {
            player.removeListener(l)
            player.release()
            transformer?.cancel()
        }
    }

    // Track position and loop inside the selected range.
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            if (player.isPlaying && position >= range.endInclusive.toLong()) {
                player.pause()
                player.seekTo(range.start.toLong())
            }
            delay(100)
        }
    }

    LaunchedEffect(exporting) {
        val holder = ProgressHolder()
        while (exporting) {
            val t = transformer
            if (t != null && t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress = holder.progress / 100f
            delay(250)
        }
    }

    fun startTrim() {
        if (exporting) return
        player.pause()
        val startMs = range.start.toLong()
        val endMs = range.endInclusive.toLong()
        if (endMs - startMs < 500) { vm.toast("Pick at least half a second"); return }
        val out = File(context.cacheDir, "trim_${System.currentTimeMillis()}.mp4")
        val clipped = androidx.media3.common.MediaItem.Builder()
            .setUri(target.uri)
            .setClippingConfiguration(
                androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build(),
            )
            .build()
        val t = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    exporting = false
                    val base = target.name.substringBeforeLast('.')
                    vm.importTrimmedVideo(out, "${base}_trim_${System.currentTimeMillis() / 1000}.mp4")
                    nav.popBackStack()
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    exporting = false
                    out.delete()
                    vm.toast("Trim failed: ${exportException.errorCodeName}")
                }
            })
            .build()
        transformer = t
        progress = 0f
        exporting = true
        runCatching { t.start(clipped, out.absolutePath) }.onFailure {
            exporting = false
            vm.toast("Trim failed: ${it.message}")
        }
    }

    Scaffold(
        containerColor = Color(0xFF020309),
        topBar = {
            TopAppBar(
                title = { Text("Trim & frames") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.Close, "Close") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Panel)
                    .navigationBarsPadding()
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        if (player.isPlaying) player.pause()
                        else {
                            if (player.currentPosition !in range.start.toLong()..range.endInclusive.toLong()) {
                                player.seekTo(range.start.toLong())
                            }
                            player.play()
                        }
                    }) {
                        Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play", tint = Neon)
                    }
                    Text(
                        "${formatDuration(position)} / ${formatDuration(duration)}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Text("Position", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = position.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                    onValueChange = { player.seekTo(it.toLong()); position = it.toLong() },
                    valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                )
                Text(
                    "Keep ${formatDuration(range.start.toLong())} – ${formatDuration(range.endInclusive.toLong())}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RangeSlider(
                    value = range,
                    onValueChange = { r ->
                        val moved = if (r.start != range.start) r.start else r.endInclusive
                        range = r
                        player.seekTo(moved.toLong())
                    },
                    valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                )
                if (exporting) {
                    Text("Saving trimmed copy… ${(progress * 100).toInt()}%")
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                } else {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { vm.saveVideoFrame(target, player.currentPosition) }) {
                            Icon(Icons.Outlined.PhotoCamera, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Save frame")
                        }
                        Button(onClick = { startTrim() }) { Text("Save trimmed copy") }
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = false } },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
