package com.courieriot.stellargallery.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

data class DuplicateGroup(val items: List<MediaItem>, val keep: MediaItem)

/**
 * Finds identical and near-identical photos using a 64-bit difference hash of each thumbnail.
 * Hashes are cached so re-scans are fast.
 */
object DuplicateFinder {
    private const val THRESHOLD = 4 // max differing bits to count as "the same picture"

    suspend fun scan(
        context: Context,
        items: List<MediaItem>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val photos = items.filter { !it.isVideo }
        val n = photos.size
        val cacheFile = File(context.filesDir, "dhash.cache")
        val cache = HashMap<String, Long>()
        runCatching {
            cacheFile.forEachLine { line ->
                val i = line.lastIndexOf(',')
                if (i > 0) line.substring(i + 1).toLongOrNull()?.let { cache[line.substring(0, i)] = it }
            }
        }

        val hashes = LongArray(n)
        val valid = BooleanArray(n)
        photos.forEachIndexed { i, m ->
            ensureActive()
            val key = "${m.id}:${m.size}"
            val h = cache[key] ?: hashOf(context, m)?.also { cache[key] = it }
            if (h != null) {
                val bits = java.lang.Long.bitCount(h)
                // Skip flat images (all black/white) — they'd all match each other.
                if (bits in 3..61) { hashes[i] = h; valid[i] = true }
            }
            if (i % 25 == 0) onProgress(i, n)
        }
        onProgress(n, n)
        runCatching {
            cacheFile.bufferedWriter().use { w -> cache.forEach { (k, v) -> w.write("$k,$v\n") } }
        }

        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }
            return r
        }
        for (i in 0 until n) {
            if (!valid[i]) continue
            if (i % 200 == 0) ensureActive()
            val hi = hashes[i]
            for (j in i + 1 until n) {
                if (valid[j] && java.lang.Long.bitCount(hi xor hashes[j]) <= THRESHOLD) {
                    val a = find(i); val b = find(j)
                    if (a != b) parent[b] = a
                }
            }
        }

        val groups = HashMap<Int, MutableList<MediaItem>>()
        for (i in 0 until n) if (valid[i]) groups.getOrPut(find(i)) { ArrayList() }.add(photos[i])
        groups.values
            .filter { it.size >= 2 }
            .map { g ->
                val keep = g.maxWith(compareBy<MediaItem>({ it.width.toLong() * it.height }, { it.size }))
                DuplicateGroup(g.sortedByDescending { it.dateTaken }, keep)
            }
            .sortedByDescending { g -> g.items.sumOf { it.size } }
    }

    private fun hashOf(context: Context, m: MediaItem): Long? = runCatching {
        val raw = context.contentResolver.loadThumbnail(m.uri, Size(96, 96), null)
        val soft = if (raw.config == Bitmap.Config.HARDWARE) raw.copy(Bitmap.Config.ARGB_8888, false) else raw
        val s = Bitmap.createScaledBitmap(soft, 9, 8, true)
        var h = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                if (lum(s.getPixel(x, y)) > lum(s.getPixel(x + 1, y))) h = h or (1L shl bit)
                bit++
            }
        }
        h
    }.getOrNull()

    private fun lum(c: Int): Int = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
}
