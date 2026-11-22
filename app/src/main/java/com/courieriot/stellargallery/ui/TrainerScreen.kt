@file:OptIn(ExperimentalMaterial3Api::class)

package com.courieriot.stellargallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.courieriot.stellargallery.MainViewModel
import com.courieriot.stellargallery.data.MediaItem
import com.courieriot.stellargallery.sorter.TrainerAnswer
import com.courieriot.stellargallery.sorter.TrainerCard
import com.courieriot.stellargallery.ui.theme.Magenta
import com.courieriot.stellargallery.ui.theme.Neon
import com.courieriot.stellargallery.ui.theme.Panel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Quick yes/no questions. Swipe right = yes, left = no.
 * [matchAlbumId] null → photos the sorter is unsure about; otherwise "Match this album" for that album.
 */
@Composable
fun TrainerScreen(vm: MainViewModel, nav: NavController, matchAlbumId: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val media by vm.media.collectAsStateWithLifecycle()
    val sorterState by vm.sorterState.collectAsStateWithLifecycle()
    val byId = remember(media) { media.associateBy { it.id } }
    val matchName = matchAlbumId?.let { id -> sorterState.albums.firstOrNull { it.album.id == id }?.album?.name }

    val cards = remember { mutableStateListOf<TrainerCard>() }
    val answers = remember { mutableStateListOf<TrainerAnswer>() }
    var index by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var pickingExamples by remember { mutableStateOf(false) }
    val examples = remember { mutableStateListOf<Long>() }
    var picking by remember { mutableStateOf(false) }
    var fullView by remember { mutableStateOf<MediaItem?>(null) }
    var finished by remember { mutableStateOf(false) }
    val swipeLimit = with(LocalDensity.current) { 110.dp.toPx() }

    fun answeredIds(): Set<Long> = answers.mapTo(HashSet()) { it.photoId }

    fun loadMore() {
        loading = true
        scope.launch {
            val more = if (matchAlbumId == null) vm.trainerCards().filter { it.photoId !in answeredIds() }
            else vm.matchCards(matchAlbumId, examples.toList(), answeredIds())
            cards.clear(); cards.addAll(more)
            index = 0
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        if (matchAlbumId != null && vm.albumNeedsExamples(matchAlbumId)) {
            pickingExamples = true
            loading = false
        } else {
            loadMore()
        }
    }

    fun finish() {
        if (finished) return
        finished = true
        if (matchAlbumId != null && examples.isNotEmpty()) {
            vm.sorterAdd(matchAlbumId, examples.mapNotNull { byId[it] })
        }
        vm.sorterTeach(answers.toList())
        nav.popBackStack()
    }
    BackHandler(enabled = fullView == null) { finish() }

    val card = cards.getOrNull(index)
    val item = card?.let { byId[it.photoId] }
    LaunchedEffect(index, item, card) { if (card != null && item == null) index++ }

    fun answer(yes: Boolean) {
        val c = card ?: return
        answers += TrainerAnswer(c.photoId, c.albumId, yes)
        index++
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(if (matchName != null) "Match “$matchName”" else "Train the sorter", maxLines = 1) },
                navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.Filled.Close, "Done") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { pad ->
        when {
            pickingExamples -> ExamplePicker(
                vm = vm,
                albumName = matchName ?: "this album",
                modifier = Modifier.padding(pad),
                selected = examples.toSet(),
                onToggle = { id -> if (id in examples) examples.remove(id) else examples.add(id) },
                onDone = { pickingExamples = false; loadMore() },
            )
            else -> Column(
                Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp).navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when {
                    loading -> {
                        Spacer(Modifier.weight(1f)); CircularProgressIndicator(); Spacer(Modifier.weight(1f))
                    }
                    cards.isEmpty() && answers.isEmpty() -> {
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (matchAlbumId != null) "No more photos to check for this album right now."
                            else "Nothing to ask right now.\nThe sorter is either confident, or still scanning — check back later.",
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { finish() }) { Text("Done") }
                        Spacer(Modifier.weight(1f))
                    }
                    card == null -> {
                        Spacer(Modifier.weight(1f))
                        Text("Round done ✦", style = MaterialTheme.typography.headlineSmall, color = Neon)
                        Spacer(Modifier.height(8.dp))
                        Text("${answers.size} answers so far.", textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { loadMore() }) { Text("Keep going") }
                            Button(onClick = { finish() }) { Text("Save & close") }
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    else -> {
                        LinearProgressIndicator(
                            progress = { index.toFloat() / cards.size },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        )
                        Text("${index + 1} of ${cards.size}", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(10.dp))
                        Text("Does this belong in", style = MaterialTheme.typography.bodyMedium)
                        Text("“${card.albumName}”?", style = MaterialTheme.typography.titleLarge, color = Neon, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))

                        var dx by remember(index) { mutableFloatStateOf(0f) }
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .pointerInput(index) {
                                    detectHorizontalDragGestures(
                                        onDragEnd = {
                                            when {
                                                dx > swipeLimit -> answer(true)
                                                dx < -swipeLimit -> answer(false)
                                                else -> dx = 0f
                                            }
                                        },
                                        onDragCancel = { dx = 0f },
                                        onHorizontalDrag = { ch, d -> ch.consume(); dx += d },
                                    )
                                }
                                .graphicsLayer { translationX = dx; rotationZ = dx / 45f }
                                .clip(RoundedCornerShape(18.dp))
                                .background(Panel),
                        ) {
                            AsyncImage(
                                model = item?.let { mediaRequest(context, it, 1600) },
                                contentDescription = item?.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (item?.isVideo == true) {
                                Icon(
                                    Icons.Filled.PlayCircle, null, tint = Color.White.copy(alpha = 0.8f),
                                    modifier = Modifier.align(Alignment.Center).size(64.dp),
                                )
                            }
                            val strength = (abs(dx) / swipeLimit).coerceIn(0f, 1f)
                            if (dx > 0) {
                                Text(
                                    "YES", color = Neon, fontSize = 28.sp,
                                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp).graphicsLayer { alpha = strength },
                                )
                            } else if (dx < 0) {
                                Text(
                                    "NO", color = Magenta, fontSize = 28.sp,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).graphicsLayer { alpha = strength },
                                )
                            }
                            // See original
                            OutlinedButton(
                                onClick = { fullView = item },
                                modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp),
                            ) {
                                Icon(Icons.Outlined.OpenInFull, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("See original")
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FilledIconButton(
                                onClick = { answer(false) },
                                modifier = Modifier.size(64.dp),
                                shape = CircleShape,
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Magenta.copy(alpha = 0.25f)),
                            ) { Icon(Icons.Filled.Close, "No", tint = Magenta) }
                            TextButton(onClick = { index++ }) { Text("Skip") }
                            FilledIconButton(
                                onClick = { answer(true) },
                                modifier = Modifier.size(64.dp),
                                shape = CircleShape,
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Neon.copy(alpha = 0.25f)),
                            ) { Icon(Icons.Filled.Check, "Yes", tint = Neon) }
                        }
                        TextButton(onClick = { picking = true }) { Text("It belongs somewhere else / new album…") }
                        Text(
                            "Swipe right for yes, left for no.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    if (picking && card != null) {
        SmartAlbumPicker(
            vm, title = "Where does it belong?", excludeId = card.albumId,
            onDismiss = { picking = false },
            onNew = { name ->
                answers += TrainerAnswer(card.photoId, card.albumId, false)
                item?.let { vm.sorterCreate(name, null, listOf(it)) }
                picking = false
                index++
            },
        ) { target ->
            answers += TrainerAnswer(card.photoId, card.albumId, false)
            answers += TrainerAnswer(card.photoId, target, true)
            picking = false
            index++
        }
    }

    fullView?.let { m -> FullViewOverlay(m) { fullView = null } }
}

/** Full-screen look at the original photo or video, without leaving the trainer. */
@Composable
private fun FullViewOverlay(item: MediaItem, onClose: () -> Unit) {
    BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (item.isVideo) {
            VideoPage(uri = item.uri, placeholder = null, active = true, onChromeChange = {})
        } else {
            ZoomableImage(model = item.uri, onTap = {})
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(6.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape),
        ) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
    }
}

/** Step 1 of "Match this album" when it has nothing to go on yet: pick a few examples. */
@Composable
private fun ExamplePicker(
    vm: MainViewModel,
    albumName: String,
    modifier: Modifier,
    selected: Set<Long>,
    onToggle: (Long) -> Unit,
    onDone: () -> Unit,
) {
    val media by vm.media.collectAsStateWithLifecycle()
    val columns by vm.gridColumns.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        Text(
            "Tap a few photos that belong in “$albumName”. 3–5 is plenty — then it finds more like them.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Box(Modifier.weight(1f)) {
            MediaGrid(
                items = media,
                columns = columns,
                groupByDate = true,
                selection = selected,
                emptyText = "No photos found.",
                onOpen = { idx -> media.getOrNull(idx)?.let { onToggle(it.id) } },
                onToggle = onToggle,
                onSelectionChange = { ids ->
                    // Drag-select: toggle anything that changed
                    (ids - selected).forEach(onToggle)
                    (selected - ids).forEach(onToggle)
                },
                onColumnsChange = vm::setGridColumns,
            )
        }
        Button(
            onClick = onDone,
            enabled = selected.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(),
        ) { Text(if (selected.isEmpty()) "Pick at least one" else "Find more like these (${selected.size})") }
    }
}
