@file:OptIn(ExperimentalMaterial3Api::class)

package com.courieriot.stellargallery.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.courieriot.stellargallery.MainViewModel
import com.courieriot.stellargallery.share.TransferProgress
import com.courieriot.stellargallery.ui.theme.Magenta
import com.courieriot.stellargallery.ui.theme.Neon
import com.courieriot.stellargallery.ui.theme.Panel

@Composable
fun ShareScreen(vm: MainViewModel, nav: NavController) {
    val context = LocalContext.current
    val share = vm.share
    val queue = remember { vm.sendQueue }
    var sendMode by rememberSaveable { mutableStateOf(queue.isNotEmpty()) }
    val peers by share.peers.collectAsStateWithLifecycle()
    val incoming by share.incoming.collectAsStateWithLifecycle()
    val progress by share.progress.collectAsStateWithLifecycle()
    val busy by share.busy.collectAsStateWithLifecycle()
    val pairing by share.pairingCode.collectAsStateWithLifecycle()
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val code = result.contents ?: return@rememberLauncherForActivityResult
        val peer = share.peerFromCode(code)
        if (peer == null) vm.toast("That isn't a Stellar Gallery pairing code")
        else share.send(peer, queue)
    }

    DisposableEffect(sendMode) {
        share.clearProgress()
        if (sendMode) share.startDiscovery() else share.startReceiving()
        onDispose { if (sendMode) share.stopDiscovery() else share.stopReceiving() }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Wi-Fi share") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ModeChip("Receive", !sendMode, enabled = !busy) { sendMode = false }
                ModeChip("Send", sendMode, enabled = !busy) { sendMode = true }
            }
            Spacer(Modifier.height(12.dp))
            progress?.let { ProgressCard(it) }

            if (!sendMode) {
                Column(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val qr = remember(pairing) { pairing?.let { runCatching { qrBitmap(it, 512) }.getOrNull() } }
                    if (qr != null) {
                        Image(
                            qr.asImageBitmap(), "Pairing code",
                            modifier = Modifier
                                .size(190.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White)
                                .padding(8.dp),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Can't see this phone? Scan this code from the sending phone.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Radar()
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Visible as “${share.deviceName}”", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Keep this screen open. On the other phone, select photos and choose “Send over Wi-Fi”. " +
                            "Both phones need to be on the same Wi-Fi (or one phone's hotspot).",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Received photos go to Pictures/Stellar Gallery/Received.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (queue.isEmpty()) {
                EmptyState("Nothing to send yet.\nIn the gallery, select photos and choose “Send over Wi-Fi”.")
            } else {
                Text(
                    "Sending ${queue.size} item(s) · ${formatSize(context, queue.sumOf { it.size })}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(queue.take(30), key = { it.id }) { m ->
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(m.uri).size(200).build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(Panel),
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Nearby phones", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(enabled = !busy, onClick = {
                        scanLauncher.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("Scan the code on the receiving phone")
                                .setBeepEnabled(false)
                                .setOrientationLocked(false),
                        )
                    }) { Text("Scan QR") }
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                Text(
                    "Already-received items are skipped, so you can resend after an interrupted transfer.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (peers.isEmpty()) {
                    Text(
                        "Looking for phones with Stellar Gallery's Receive screen open…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(peers, key = { it.serviceName }) { peer ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Panel),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !busy) { share.send(peer, queue) },
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.PhoneAndroid, null, tint = Neon)
                                Spacer(Modifier.size(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(peer.displayName, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        peer.host.hostAddress ?: "",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text("Send", color = Neon)
                            }
                        }
                    }
                }
            }
        }
    }

    incoming?.let { req ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Incoming photos") },
            text = { Text("${req.sender} wants to send you ${req.count} item(s) (${formatSize(context, req.totalBytes)}).") },
            confirmButton = { Button(onClick = { req.answer.complete(true) }) { Text("Accept") } },
            dismissButton = { TextButton(onClick = { req.answer.complete(false) }) { Text("Decline") } },
        )
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Neon.copy(alpha = 0.18f),
            selectedLabelColor = Neon,
        ),
    )
}

@Composable
private fun ProgressCard(p: TransferProgress) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(p.label, style = MaterialTheme.typography.titleSmall)
            p.error?.let { Text(it, color = Magenta, style = MaterialTheme.typography.labelMedium) }
            if (!p.finished) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Radar() {
    val t = rememberInfiniteTransition(label = "radar")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "phase",
    )
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(200.dp)) {
            val r = size.minDimension / 2f
            for (i in 0..2) {
                val f = (phase + i / 3f) % 1f
                drawCircle(Neon.copy(alpha = (1f - f) * 0.7f), radius = r * f, style = Stroke(2.dp.toPx()))
            }
            drawCircle(Magenta, radius = 9.dp.toPx())
        }
        Icon(Icons.Outlined.PhoneAndroid, null, tint = Color.White, modifier = Modifier.size(28.dp))
    }
}

private fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i ->
        if (matrix[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}
