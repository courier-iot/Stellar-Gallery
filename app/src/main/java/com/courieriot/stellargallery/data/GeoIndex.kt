package com.courieriot.stellargallery.data

import android.content.Context
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

data class GeoPhoto(val id: Long, val lat: Double, val lng: Double)

/** Reads GPS positions from photo EXIF (needs the "media location" permission). Results are cached. */
object GeoIndex {
    suspend fun index(
        context: Context,
        items: List<MediaItem>,
        hasLocationPermission: Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<GeoPhoto> = withContext(Dispatchers.IO) {
        val photos = items.filter { !it.isVideo }
        val cacheFile = File(context.filesDir, "geo.cache")
        val cache = HashMap<String, String>()
        runCatching {
            cacheFile.forEachLine { line ->
                val i = line.indexOf('=')
                if (i > 0) cache[line.substring(0, i)] = line.substring(i + 1)
            }
        }
        val out = ArrayList<GeoPhoto>()
        photos.forEachIndexed { i, m ->
            ensureActive()
            val key = "${m.id}:${m.size}"
            val cached = cache[key]
            val value = cached ?: run {
                val ll = readLatLng(context, m, hasLocationPermission)
                val v = if (ll == null) "x" else "${ll.first},${ll.second}"
                if (hasLocationPermission) cache[key] = v
                v
            }
            if (value != "x") {
                val parts = value.split(',')
                val lat = parts.getOrNull(0)?.toDoubleOrNull()
                val lng = parts.getOrNull(1)?.toDoubleOrNull()
                if (lat != null && lng != null && !(lat == 0.0 && lng == 0.0)) out += GeoPhoto(m.id, lat, lng)
            }
            if (i % 25 == 0) onProgress(i, photos.size)
        }
        onProgress(photos.size, photos.size)
        if (hasLocationPermission) runCatching {
            cacheFile.bufferedWriter().use { w -> cache.forEach { (k, v) -> w.write("$k=$v\n") } }
        }
        out
    }

    private fun readLatLng(context: Context, m: MediaItem, original: Boolean): Pair<Double, Double>? = runCatching {
        val uri = if (original) MediaStore.setRequireOriginal(m.uri) else m.uri
        context.contentResolver.openInputStream(uri)?.use { input ->
            ExifInterface(input).latLong?.let { it[0] to it[1] }
        }
    }.getOrNull()
}
