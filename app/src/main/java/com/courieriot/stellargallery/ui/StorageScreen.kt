@file:OptIn(ExperimentalMaterial3Api::class)

package com.courieriot.stellargallery.ui

import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.courieriot.stellargallery.ui.theme.Magenta
import com.courieriot.stellargallery.ui.theme.Neon
import com.courieriot.stellargallery.ui.theme.Panel
import com.courieriot.stellargallery.ui.theme.PanelHigh
import com.courieriot.stellargallery.ui.theme.Violet

@Composable
fun StorageScreen(vm: MainViewModel, nav: NavController) {
    val context = LocalContext.current
    val media by vm.media.collectAsStateWithLifecycle()
    val trash by vm.trash.collectAsStateWithLifecycle()

    val photoBytes = remember(media) { media.filter { !it.isVideo }.sumOf { it.size } }
    val videoBytes = remember(media) { media.filter { it.isVideo }.sumOf { it.size } }
    val binBytes = remember(trash) { trash.sumOf { it.size } }
    val (total, free) = remember {
        runCatching {
            val st = StatFs(Environment.getExternalStorageDirectory().path)
            st.totalBytes to st.availableBytes
        }.getOrDefault(0L to 0L)
    }
    val other = (total - free - photoBytes - videoBytes - binBytes).coerceAtLeast(0)
    val byAlbum = remember(media) {
        media.groupBy { it.bucketId }.map { (_, l) -> Triple(l.first().bucketName, l.size, l.sumOf { it.size }) }
            .sortedByDescending { it.third }
    }
    val largest = remember(media) { media.sortedByDescending { it.size }.take(25) }
    val maxAlbum = byAlbum.firstOrNull()?.third?.coerceAtLeast(1) ?: 1L

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Storage") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.padding(16.dp).fillMaxWidth()) {
                        Text("Phone storage", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${formatSize(context, total - free)} used of ${formatSize(context, total)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        if (total > 0) {
                            Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(PanelHigh)) {
                                listOf(photoBytes to Neon, videoBytes to Magenta, binBytes to Violet, other to Color(0xFF59607F))
                                    .forEach { (bytes, color) ->
                                        val f = bytes.toFloat() / total
                                        if (f > 0.002f) Box(Modifier.fillMaxHeight().weight(f).background(color))
                                    }
                                val freeF = free.toFloat() / total
                                if (freeF > 0.002f) Box(Modifier.fillMaxHeight().weight(freeF))
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Legend(Neon, "Photos (${media.count { !it.isVideo }})", formatSize(context, photoBytes))
                        Legend(Magenta, "Videos (${media.count { it.isVideo }})", formatSize(context, videoBytes))
                        Legend(Violet, "Bin (${trash.size})", formatSize(context, binBytes))
                        Legend(Color(0xFF59607F), "Apps & other files", formatSize(context, other))
                        Legend(PanelHigh, "Free", formatSize(context, free))
                        if (trash.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { vm.deleteForever(trash) }) {
                                Text("Empty bin · free ${formatSize(context, binBytes)}")
                            }
                        }
                    }
                }
            }
            item { Section2("Albums by size") }
            items(byAlbum) { (name, count, bytes) ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$count · ${formatSize(context, bytes)}", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(PanelHigh)) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth((bytes.toFloat() / maxAlbum).coerceIn(0.01f, 1f))
                                .background(Neon),
                        )
                    }
                }
            }
            item { Section2("Largest files") }
            items(largest, key = { "l_" + it.id }) { m ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            val src = vm.setCustomList("largest", largest.map { it.id })
                            nav.navigate("viewer/$src/${largest.indexOf(m)}")
                        }
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(m.uri).size(150).build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Panel),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(m.bucketName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(formatSize(context, m.size), color = Neon)
                }
            }
        }
    }
}

@Composable
private fun Section2(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Neon,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp),
    )
}

@Composable
private fun Legend(color: Color, label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
