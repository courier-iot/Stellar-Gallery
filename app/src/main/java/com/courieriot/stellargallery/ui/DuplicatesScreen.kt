@file:OptIn(ExperimentalMaterial3Api::class)

package com.courieriot.stellargallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.courieriot.stellargallery.MainViewModel
import com.courieriot.stellargallery.data.DuplicateGroup
import com.courieriot.stellargallery.ui.theme.Magenta
import com.courieriot.stellargallery.ui.theme.Neon
import com.courieriot.stellargallery.ui.theme.Panel

@Composable
fun DuplicatesScreen(vm: MainViewModel, nav: NavController) {
    val context = LocalContext.current
    val state by vm.dupes.collectAsStateWithLifecycle()
    val media by vm.media.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { if (state.result == null && !state.running) vm.scanDuplicates() }

    // Drop items that were deleted since the scan.
    val groups: List<DuplicateGroup> = remember(state.result, media) {
        val live = media.mapTo(HashSet()) { it.id }
        state.result.orEmpty().mapNotNull { g ->
            val items = g.items.filter { it.id in live }
            if (items.size < 2) null else DuplicateGroup(items, if (g.keep.id in live) g.keep else items.first())
        }
    }
    var toDelete by remember(groups) {
        mutableStateOf(groups.flatMap { g -> g.items.filter { it.id != g.keep.id }.map { it.id } }.toSet())
    }
    val deleteItems = remember(groups, toDelete) { groups.flatMap { it.items }.filter { it.id in toDelete } }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Duplicates") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    if (!state.running) IconButton(onClick = { vm.scanDuplicates() }) { Icon(Icons.Outlined.Refresh, "Scan again") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                state.running -> Column(
                    Modifier.fillMaxWidth().padding(24.dp).align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Comparing photos… ${state.done} of ${state.total}")
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The first scan takes a while. Later scans are much faster.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                groups.isEmpty() -> EmptyState("No duplicates found ✨")
                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                            Column(Modifier.padding(16.dp).fillMaxWidth()) {
                                Text("${groups.size} sets of look-alike photos", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "The best copy (highest resolution) is kept. Tap photos to change what gets removed.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(12.dp))
                                Button(onClick = { vm.trash(deleteItems) }, enabled = deleteItems.isNotEmpty()) {
                                    Text("Move ${deleteItems.size} to bin · frees ${formatSize(context, deleteItems.sumOf { it.size })}")
                                }
                            }
                        }
                    }
                    items(groups, key = { it.items.first().id }) { g ->
                        Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(10.dp),
                            ) {
                                items(g.items, key = { it.id }) { m ->
                                    val marked = m.id in toDelete
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(
                                            Modifier
                                                .size(110.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .border(2.dp, if (marked) Magenta else Neon, RoundedCornerShape(10.dp))
                                                .clickable { toDelete = if (marked) toDelete - m.id else toDelete + m.id },
                                        ) {
                                            AsyncImage(
                                                model = ImageRequest.Builder(context).data(m.uri).size(330).build(),
                                                contentDescription = m.name,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize().background(Panel),
                                            )
                                            if (marked) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
                                            Text(
                                                if (marked) "REMOVE" else "KEEP",
                                                color = if (marked) Magenta else Neon,
                                                fontSize = 10.sp,
                                                modifier = Modifier
                                                    .align(Alignment.TopStart)
                                                    .padding(4.dp)
                                                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 4.dp),
                                            )
                                            if (m.id == g.keep.id) {
                                                Icon(
                                                    Icons.Filled.CheckCircle, "Best copy", tint = Neon,
                                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp),
                                                )
                                            }
                                        }
                                        Text(
                                            "${m.width}×${m.height} · ${formatSize(context, m.size)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
