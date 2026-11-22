package com.courieriot.stellargallery.ui

import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.courieriot.stellargallery.MainViewModel
import com.courieriot.stellargallery.ui.theme.Neon
import kotlinx.coroutines.delay

@Composable
fun SlideshowScreen(vm: MainViewModel, nav: NavController, source: String, startIndex: Int) {
    val context = LocalContext.current
    val view = LocalView.current
    val flow = remember(source) { vm.listFor(source) }
    val initial = remember(source) { vm.snapshotFor(source) }
    val all by flow.collectAsStateWithLifecycle(initialValue = initial)
    val photos = remember(all) { all.filter { !it.isVideo } }
    val startId = remember(source) { initial.getOrNull(startIndex)?.id }
    var index by rememberSaveable {
        mutableIntStateOf(initial.filter { !it.isVideo }.indexOfFirst { it.id == startId }.coerceAtLeast(0))
    }
    var playing by remember { mutableStateOf(true) }
    var chrome by remember { mutableStateOf(false) }
    val secs by vm.slideshowSeconds.collectAsStateWithLifecycle()

    // Fullscreen + keep the screen awake.
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    if (photos.isEmpty()) {
        LaunchedEffect(Unit) { vm.toast("No photos to show"); nav.popBackStack() }
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val safeIndex = index.coerceIn(0, photos.lastIndex)

    LaunchedEffect(safeIndex, playing, secs, photos.size) {
        // Preload the next photo so the fade is smooth.
        photos.getOrNull((safeIndex + 1) % photos.size)?.let {
            context.imageLoader.enqueue(ImageRequest.Builder(context).data(it.uri).size(2048).build())
        }
        if (playing && photos.size > 1) {
            delay(secs * 1000L)
            index = (safeIndex + 1) % photos.size
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { chrome = !chrome },
    ) {
        Crossfade(targetState = photos[safeIndex], animationSpec = tween(900), label = "slide") { item ->
            val zoom = remember(item.id) { Animatable(1f) }
            LaunchedEffect(item.id) {
                zoom.animateTo(1.08f, tween((secs * 1000) + 900, easing = LinearEasing))
            }
            AsyncImage(
                model = ImageRequest.Builder(context).data(item.uri).size(2048).build(),
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value },
            )
        }

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
                Text("${safeIndex + 1} / ${photos.size}", color = Color.White)
            }
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                    .navigationBarsPadding()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { index = (safeIndex - 1 + photos.size) % photos.size }) {
                        Icon(Icons.Filled.SkipPrevious, "Previous", tint = Color.White)
                    }
                    IconButton(onClick = { playing = !playing }) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (playing) "Pause" else "Play",
                            tint = Neon, modifier = Modifier.size(40.dp),
                        )
                    }
                    IconButton(onClick = { index = (safeIndex + 1) % photos.size }) {
                        Icon(Icons.Filled.SkipNext, "Next", tint = Color.White)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 5, 8, 12).forEach { s ->
                        FilterChip(
                            selected = secs == s,
                            onClick = { vm.setSlideshowSeconds(s) },
                            label = { Text("${s}s") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Neon.copy(alpha = 0.2f),
                                selectedLabelColor = Neon,
                                labelColor = Color.White,
                            ),
                        )
                    }
                }
            }
        }
    }
}
