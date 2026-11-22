@file:OptIn(ExperimentalMaterial3Api::class)

package com.courieriot.stellargallery.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.courieriot.stellargallery.MainViewModel
import com.courieriot.stellargallery.data.GeoPhoto
import com.courieriot.stellargallery.ui.theme.Neon
import com.courieriot.stellargallery.ui.theme.Panel
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File
import kotlin.math.floor
import kotlin.math.pow

private class Cluster(val lat: Double, val lng: Double, val ids: List<Long>)

/** Groups nearby photos into one bubble, based on the current zoom level. */
private fun cluster(points: List<GeoPhoto>, zoom: Double): List<Cluster> {
    val cell = 360.0 / 2.0.pow(zoom) / 3.0
    return points.groupBy { floor(it.lat / cell) to floor(it.lng / cell) }.values.map { g ->
        Cluster(g.sumOf { it.lat } / g.size, g.sumOf { it.lng } / g.size, g.map { it.id })
    }
}

private fun bubble(context: Context, count: Int, accent: Int): BitmapDrawable {
    val d = context.resources.displayMetrics.density
    val size = ((if (count > 99) 46 else 38) * d).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0xCC0E1128.toInt()
    c.drawCircle(size / 2f, size / 2f, size / 2f - d, p)
    p.style = Paint.Style.STROKE
    p.strokeWidth = 2.5f * d
    p.color = accent
    c.drawCircle(size / 2f, size / 2f, size / 2f - 2 * d, p)
    p.style = Paint.Style.FILL
    p.color = android.graphics.Color.WHITE
    p.textSize = 13 * d
    p.typeface = Typeface.DEFAULT_BOLD
    p.textAlign = Paint.Align.CENTER
    val label = if (count > 999) "999+" else count.toString()
    c.drawText(label, size / 2f, size / 2f - (p.ascent() + p.descent()) / 2f, p)
    return BitmapDrawable(context.resources, bmp)
}

@Composable
fun MapScreen(vm: MainViewModel, nav: NavController) {
    val context = LocalContext.current
    val geo by vm.geo.collectAsStateWithLifecycle()
    val media by vm.media.collectAsStateWithLifecycle()
    val accent = Neon.toArgb()

    fun hasPerm() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_MEDIA_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.indexLocations(granted)
    }
    LaunchedEffect(Unit) {
        if (hasPerm()) vm.indexLocations(true) else permLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
    }

    val points = geo.result.orEmpty()
    val byDate = remember(media) { media.associate { it.id to it.dateTaken } }
    val onClusterTap by rememberUpdatedState<(List<Long>) -> Unit> { ids ->
        val sorted = ids.sortedByDescending { byDate[it] ?: 0L }
        val src = vm.setCustomList("map", sorted)
        nav.navigate("viewer/$src/0")
    }

    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERTED_COLORS)
            minZoomLevel = 2.0
            maxZoomLevel = 19.0
            controller.setZoom(3.0)
            overlays.add(CopyrightOverlay(context))
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { mapView.onResume() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { mapView.onPause() }
    DisposableEffect(Unit) { onDispose { mapView.onDetach() } }

    val render = remember(points, accent) {
        {
            mapView.overlays.removeAll { it is Marker }
            cluster(points, mapView.zoomLevelDouble).forEach { cl ->
                val m = Marker(mapView)
                m.position = GeoPoint(cl.lat, cl.lng)
                m.icon = bubble(context, cl.ids.size, accent)
                m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                m.setOnMarkerClickListener { _, _ -> onClusterTap(cl.ids); true }
                mapView.overlays.add(m)
            }
            mapView.invalidate()
        }
    }

    DisposableEffect(render) {
        val listener = object : MapListener {
            private var lastZoom = -1
            override fun onScroll(event: ScrollEvent?): Boolean = false
            override fun onZoom(event: ZoomEvent?): Boolean {
                val z = mapView.zoomLevelDouble.toInt()
                if (z != lastZoom) { lastZoom = z; render() }
                return false
            }
        }
        mapView.addMapListener(listener)
        render()
        onDispose { mapView.removeMapListener(listener) }
    }

    LaunchedEffect(points.isNotEmpty()) {
        if (points.isNotEmpty()) {
            val box = if (points.size == 1) {
                null
            } else {
                BoundingBox.fromGeoPoints(points.map { GeoPoint(it.lat, it.lng) })
            }
            mapView.post {
                if (box != null) runCatching { mapView.zoomToBoundingBox(box.increaseByScale(1.3f), false) }
                else { mapView.controller.setZoom(12.0); mapView.controller.setCenter(GeoPoint(points[0].lat, points[0].lng)) }
                render()
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Photo map") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            if (geo.running || (geo.result != null && points.isEmpty())) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Panel.copy(alpha = 0.95f)),
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp).fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        if (geo.running) {
                            Text("Finding photo locations… ${geo.done} of ${geo.total}")
                            LinearProgressIndicator(
                                progress = { if (geo.total == 0) 0f else geo.done.toFloat() / geo.total },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                        } else {
                            Text(
                                if (hasPerm()) "None of your photos have a location saved."
                                else "Allow access to photo locations to see them on the map.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}
