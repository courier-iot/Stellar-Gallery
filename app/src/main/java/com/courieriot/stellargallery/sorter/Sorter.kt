package com.courieriot.stellargallery.sorter

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import com.courieriot.stellargallery.BuildConfig
import com.courieriot.stellargallery.data.MediaItem
import com.courieriot.stellargallery.data.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.exp

enum class AlbumStatus { SUGGESTED, APPROVED, DISMISSED }

data class SmartAlbum(
    val id: String,
    val name: String,
    /** Starter category key, or null for your own / discovered albums. */
    val key: String?,
    /** Typed description (used when Search & sort is installed). */
    val prompt: String?,
    val status: AlbumStatus,
    val includes: Set<Long> = emptySet(),
    val excludes: Set<Long> = emptySet(),
)

data class SmartAlbumView(val album: SmartAlbum, val ids: List<Long>)

/** A "is this right?" question for the trainer. */
data class TrainerCard(val photoId: Long, val albumId: String, val albumName: String)

data class TrainerAnswer(val photoId: Long, val albumId: String, val yes: Boolean)

sealed interface SearchModelState {
    data object NotInstalled : SearchModelState
    data class Downloading(val progress: Float) : SearchModelState
    data object Ready : SearchModelState
    data class Failed(val message: String) : SearchModelState
}

data class SorterState(
    val modelError: String? = null,
    val scanning: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val sortedCount: Int = 0,
    val photoCount: Int = 0,
    val albums: List<SmartAlbumView> = emptyList(),
    val search: SearchModelState = SearchModelState.NotInstalled,
    val unsureCount: Int = 0,
    val simplified: Boolean = true,
)

/**
 * The photo sorter. Learns from:
 *  - starter category descriptions (built into the app),
 *  - photos you add to / remove from smart albums (nearest-neighbour learning),
 *  - typed descriptions when Search & sort is installed.
 * Nothing leaves the phone; files are never moved.
 */
class Sorter(private val context: Context) {
    private val engine = SorterEngine(context)
    private val dir = File(context.filesDir, "sorter").apply { mkdirs() }
    private val albumsFile = File(dir, "albums.json")
    private val embFile: File get() = File(dir, "emb-${engine.modelId}.bin")
    @Volatile private var trainerPool: List<TrainerCard> = emptyList()

    /** Simplified folders: broader albums (People, Pets & animals…) instead of detailed ones. */
    @Volatile var simplified: Boolean = true
        private set

    val state = MutableStateFlow(SorterState())

    private val scanMutex = Mutex()
    private val classifyMutex = Mutex()
    private val albumsLock = Any()
    private var albums: List<SmartAlbum> = loadAlbums()
    private val embeddings = ConcurrentHashMap<Long, FloatArray>()
    private val embSizes = ConcurrentHashMap<Long, Long>()
    private var embLoaded = false
    @Volatile private var media: List<MediaItem> = emptyList()
    private val promptVecs = ConcurrentHashMap<String, FloatArray>()

    init {
        state.update {
            it.copy(
                search = if (engine.searchModelInstalled()) SearchModelState.Ready else SearchModelState.NotInstalled,
                albums = albums.filter { a -> visible(a) }.map { a -> SmartAlbumView(a, emptyList()) },
            )
        }
    }

    // ------------------------------------------------------------------ scanning

    /** Embeds any new photos, then re-sorts. Safe to call often; only new items are processed. */
    suspend fun scan(items: List<MediaItem>) = withContext(Dispatchers.Default) {
        scanMutex.withLock {
            media = items
            val err = engine.loadVision()
            if (err != null) {
                state.update { it.copy(modelError = err, scanning = false) }
                return@withLock
            }
            state.update { it.copy(modelError = null) }
            if (!embLoaded) { loadEmbeddings(); embLoaded = true }
            ensureStarterAlbums()

            val todo = items.filter { embSizes[it.id] != it.size }
            if (todo.isNotEmpty()) {
                state.update { it.copy(scanning = true, done = 0, total = todo.size) }
                val pending = ArrayList<Pair<MediaItem, FloatArray>>()
                try {
                    todo.forEachIndexed { i, m ->
                        ensureActive()
                        val v = runCatching { thumbnail(m)?.let { engine.embedImage(it) } }.getOrNull()
                        if (v != null && v.size == engine.dim) {
                            embeddings[m.id] = v
                            embSizes[m.id] = m.size
                            pending.add(m to v)
                        } else {
                            // Remember unreadable items so we don't retry them every time.
                            embSizes[m.id] = m.size
                        }
                        if (i % 8 == 0) state.update { it.copy(done = i) }
                        if (pending.size >= 64) { appendEmbeddings(pending); pending.clear() }
                        if (i > 0 && i % 400 == 0) classify(suggestClusters = false)
                    }
                } finally {
                    appendEmbeddings(pending)
                    state.update { it.copy(scanning = false, done = 0, total = 0) }
                }
                compactEmbeddings(items)
            }
            classify(suggestClusters = true)
        }
    }

    private fun thumbnail(m: MediaItem): Bitmap? {
        val raw = context.contentResolver.loadThumbnail(m.uri, Size(320, 320), null)
        return if (raw.config == Bitmap.Config.HARDWARE) raw.copy(Bitmap.Config.ARGB_8888, false) else raw
    }

    // ------------------------------------------------------------------ sorting

    /** Re-sorts using current embeddings and what the sorter has learned. Fast (no image reading). */
    suspend fun classify(suggestClusters: Boolean = false) = withContext(Dispatchers.Default) {
        classifyMutex.withLock {
            val items = media
            val current = synchronized(albumsLock) { albums }
            val shown = current.filter { visible(it) }
            val active = shown.filter { it.status != AlbumStatus.DISMISSED }
            val byKey = current.associateBy { it.key }

            // Text anchors: every starter category, pointed at its album for the current mode.
            // Dismissed ones still take part, so their photos don't leak into other albums.
            class Anchor(val key: String?, val album: SmartAlbum?, val vec: FloatArray)
            val anchors = ArrayList<Anchor>()
            engine.categories.forEach { c ->
                val target = if (simplified) byKey["g:" + c.group] else byKey[c.key]
                anchors += Anchor(c.key, target, c.emb)
            }
            active.filter { it.key == null && !it.prompt.isNullOrBlank() }
                .forEach { a -> promptVector(a.prompt!!)?.let { anchors += Anchor(null, a, it) } }

            // What you taught it: photos added (+) and removed (−)
            val explicit = HashMap<Long, SmartAlbum>()
            active.sortedBy { if (it.status == AlbumStatus.APPROVED) 0 else 1 }.forEach { a ->
                a.includes.forEach { id -> explicit.putIfAbsent(id, a) }
            }
            class Example(val vec: FloatArray, val album: SmartAlbum, val sign: Float)
            val examples = ArrayList<Example>()
            active.forEach { a ->
                a.includes.forEach { id -> embeddings[id]?.let { examples += Example(it, a, 1f) } }
                a.excludes.forEach { id -> embeddings[id]?.let { examples += Example(it, a, -1f) } }
            }

            val result = LinkedHashMap<String, MutableList<Long>>()
            val sorted = HashSet<Long>()
            val unsure = ArrayList<Pair<TrainerCard, Float>>()
            var photoCount = 0
            val minProb = engine.minProb
            val minMargin = engine.minMargin
            for (m in items) {
                ensureActive()
                val v = embeddings[m.id] ?: continue
                photoCount++
                var target: SmartAlbum? = explicit[m.id]
                val blocked = HashSet<String>()

                // 1) Learned from your examples (nearest neighbours, including "not this album" answers)
                if (target == null && examples.isNotEmpty()) {
                    val near = examples.asSequence()
                        .map { it to dot(v, it.vec) }
                        .filter { it.second >= 0.75f }
                        .sortedByDescending { it.second }
                        .take(7)
                        .toList()
                    val votes = HashMap<String, Float>()
                    near.forEach { (ex, sim) ->
                        votes[ex.album.id] = (votes[ex.album.id] ?: 0f) + ex.sign * sim
                        if (ex.sign < 0 && sim >= 0.88f) blocked += ex.album.id
                    }
                    val best = votes.filter { it.value > 0f && it.key !in blocked }.maxByOrNull { it.value }
                    val top = near.firstOrNull()
                    if (best != null) {
                        val album = near.first { it.first.album.id == best.key }.first.album
                        val strongSingle = top != null && top.first.sign > 0 && top.first.album.id == best.key && top.second >= 0.90f
                        if ((best.value >= 1.4f || strongSingle) && m.id !in album.excludes) target = album
                    }
                }

                // 2) Starter categories + your descriptions, with folder clues
                if (target == null && anchors.isNotEmpty()) {
                    val hints = folderHints(m)
                    val logits = FloatArray(anchors.size) { i ->
                        engine.scale * dot(v, anchors[i].vec) + (anchors[i].key?.let { hints[it] } ?: 0f)
                    }
                    val maxL = logits.max()
                    var sum = 0f
                    for (i in logits.indices) { logits[i] = exp((logits[i] - maxL).toDouble()).toFloat(); sum += logits[i] }
                    // Add up by album (in simplified mode several categories feed one album)
                    val perAlbum = HashMap<String, Float>()
                    val albumOf = HashMap<String, SmartAlbum>()
                    for (i in logits.indices) {
                        val a = anchors[i].album ?: continue
                        perAlbum[a.id] = (perAlbum[a.id] ?: 0f) + logits[i] / sum
                        albumOf[a.id] = a
                    }
                    val ranked = perAlbum.entries.sortedByDescending { it.value }
                    val first = ranked.getOrNull(0)
                    if (first != null) {
                        val p1 = first.value
                        val p2 = ranked.getOrNull(1)?.value ?: 0f
                        val a = albumOf.getValue(first.key)
                        val usable = a.status != AlbumStatus.DISMISSED && visible(a) && m.id !in a.excludes && a.id !in blocked
                        if (usable && p1 >= minProb && p1 - p2 >= minMargin) {
                            target = a
                            if (p1 < 0.7f) unsure += TrainerCard(m.id, a.id, a.name) to (p1 - minProb)
                        } else if (usable && p1 >= 0.2f) {
                            unsure += TrainerCard(m.id, a.id, a.name) to (minProb - p1)
                        }
                    }
                }

                if (target != null) {
                    result.getOrPut(target.id) { ArrayList() }.add(m.id)
                    sorted += m.id
                }
            }

            if (suggestClusters) suggestClusters(items, sorted, result)

            trainerPool = unsure.sortedBy { kotlin.math.abs(it.second) }.take(300).map { it.first }
            val latest = synchronized(albumsLock) { albums }
            state.update {
                it.copy(
                    sortedCount = sorted.size,
                    photoCount = photoCount,
                    unsureCount = unsure.size,
                    simplified = simplified,
                    albums = latest.filter { a -> visible(a) }
                        .map { a -> SmartAlbumView(a, if (a.status == AlbumStatus.DISMISSED) emptyList() else result[a.id].orEmpty()) },
                )
            }
        }
    }

    /** Up to [count] photos the sorter is least sure about, to ask you about. */
    fun trainerCards(count: Int = 20): List<TrainerCard> {
        val pool = trainerPool
        if (pool.isEmpty()) return emptyList()
        // Mix the closest calls with some variety across albums.
        val byAlbum = pool.groupBy { it.albumId }.values.map { it.toMutableList() }
        val out = ArrayList<TrainerCard>()
        while (out.size < count && byAlbum.any { it.isNotEmpty() }) {
            byAlbum.forEach { l -> if (l.isNotEmpty() && out.size < count) out += l.removeAt(0) }
        }
        return out
    }

    /**
     * "Match this album": the photos most like the album (from its examples, description or category)
     * that you haven't answered yet. [extra] are examples picked just now.
     */
    suspend fun matchCards(
        albumId: String,
        extra: Collection<Long> = emptyList(),
        skip: Set<Long> = emptySet(),
        count: Int = 25,
    ): List<TrainerCard> = withContext(Dispatchers.Default) {
        val a = synchronized(albumsLock) { albums }.firstOrNull { it.id == albumId } ?: return@withContext emptyList()
        val vecs = (a.includes + extra).mapNotNull { embeddings[it] }
        var center: FloatArray? = null
        if (vecs.isNotEmpty()) {
            val sum = FloatArray(vecs[0].size)
            vecs.forEach { v -> for (i in sum.indices) sum[i] += v[i] }
            center = SorterEngine.normalize(sum)
        } else if (!a.prompt.isNullOrBlank()) {
            center = promptVector(a.prompt)
        } else if (a.key != null) {
            val cats = if (a.key.startsWith("g:")) engine.categories.filter { "g:" + it.group == a.key }
            else engine.categories.filter { it.key == a.key }
            if (cats.isNotEmpty()) {
                val sum = FloatArray(cats[0].emb.size)
                cats.forEach { c -> for (i in sum.indices) sum[i] += c.emb[i] }
                center = SorterEngine.normalize(sum)
            }
        }
        val c = center ?: return@withContext emptyList()
        val taken = HashSet<Long>().apply { addAll(a.includes); addAll(a.excludes); addAll(extra); addAll(skip) }
        media.asSequence()
            .filter { it.id !in taken }
            .mapNotNull { m -> embeddings[m.id]?.let { m.id to dot(c, it) } }
            .sortedByDescending { it.second }
            .take(count)
            .map { TrainerCard(it.first, a.id, a.name) }
            .toList()
    }

    /** True when an album has nothing to match from yet (no examples, description or category). */
    fun needsExamples(albumId: String): Boolean {
        val a = synchronized(albumsLock) { albums }.firstOrNull { it.id == albumId } ?: return true
        return a.includes.none { embeddings.containsKey(it) } && a.prompt.isNullOrBlank() && a.key == null
    }

    /** Applies a batch of trainer answers at once. */
    suspend fun teach(answers: List<TrainerAnswer>) {
        if (answers.isEmpty()) return
        change { l ->
            var list = l
            answers.forEach { ans ->
                list = list.map { a ->
                    when {
                        a.id == ans.albumId && ans.yes -> a.copy(includes = a.includes + ans.photoId, excludes = a.excludes - ans.photoId)
                        a.id == ans.albumId -> a.copy(includes = a.includes - ans.photoId, excludes = a.excludes + ans.photoId)
                        ans.yes -> a.copy(includes = a.includes - ans.photoId)
                        else -> a
                    }
                }
            }
            list
        }
    }

    /** Finds bunches of very similar unsorted photos and offers them as new albums. */
    private fun suggestClusters(items: List<MediaItem>, sorted: MutableSet<Long>, result: MutableMap<String, MutableList<Long>>) {
        val pendingGroups = synchronized(albumsLock) { albums.count { it.id.startsWith("grp_") && it.status == AlbumStatus.SUGGESTED } }
        if (pendingGroups >= 6) return
        val pool = items.asSequence().filter { it.id !in sorted && embeddings.containsKey(it.id) }.take(1500).toList()
        val used = HashSet<Long>()
        val newAlbums = ArrayList<SmartAlbum>()
        for (seed in pool) {
            if (newAlbums.size + pendingGroups >= 6) break
            if (seed.id in used) continue
            val sv = embeddings[seed.id] ?: continue
            val members = pool.filter { it.id !in used && embeddings[it.id]?.let { v -> dot(sv, v) >= 0.83f } == true }
            if (members.size >= 8) {
                members.forEach { used += it.id }
                val month = Instant.ofEpochMilli(seed.dateTaken).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM yyyy"))
                val a = SmartAlbum(
                    id = "grp_" + UUID.randomUUID().toString().take(8),
                    name = "Similar photos · $month",
                    key = null, prompt = null,
                    status = AlbumStatus.SUGGESTED,
                    includes = members.map { it.id }.toSet(),
                )
                newAlbums += a
                result[a.id] = members.map { it.id }.toMutableList()
                sorted += a.includes
            }
        }
        if (newAlbums.isNotEmpty()) mutate { it + newAlbums }
    }

    private fun ensureStarterAlbums() {
        val cats = engine.categories
        if (cats.isEmpty()) return
        mutate { list ->
            val missingCats = cats.filter { c -> list.none { it.key == c.key } }
                .map { c -> SmartAlbum("cat_" + c.key, c.name, c.key, null, AlbumStatus.SUGGESTED) }
            val missingGroups = engine.groups.filter { g -> list.none { it.key == "g:" + g.key } }
                .map { g -> SmartAlbum("fold_" + g.key, g.name, "g:" + g.key, null, AlbumStatus.SUGGESTED) }
            if (missingCats.isEmpty() && missingGroups.isEmpty()) list else list + missingCats + missingGroups
        }
    }

    /** Starter albums belong to either detailed or simplified mode; your own albums always show. */
    private fun visible(a: SmartAlbum): Boolean {
        val k = a.key ?: return true
        return if (k.startsWith("g:")) simplified else !simplified
    }

    /** Sets the mode before the first sort (no re-sort). */
    fun initSimplified(on: Boolean) {
        simplified = on
        state.update { st ->
            st.copy(simplified = on, albums = synchronized(albumsLock) { albums }.filter { visible(it) }.map { SmartAlbumView(it, emptyList()) })
        }
    }

    suspend fun setSimplified(on: Boolean) {
        simplified = on
        state.update { it.copy(simplified = on) }
        classify()
    }

    /** Folder clues, added to the model's scores (positive = more likely). */
    private fun folderHints(m: MediaItem): Map<String, Float> {
        val path = (m.relativePath + "/" + m.bucketName).lowercase()
        val hints = HashMap<String, Float>()
        fun add(keys: List<String>, v: Float) = keys.forEach { hints[it] = (hints[it] ?: 0f) + v }
        val realWorld = listOf("pets", "animals", "selfies", "people", "groups", "food", "drinks", "nature", "sky",
            "water", "plants", "city", "home", "vehicles", "sports", "events")
        when {
            "screenshot" in path -> {
                add(listOf("screenshots"), 2.0f); add(listOf("chats"), 1.5f)
                add(realWorld, -1.0f)
            }
            path.startsWith("dcim/camera") || m.bucketName.equals("camera", true) -> {
                add(listOf("screenshots", "chats"), -4f)
                add(listOf("memes", "comics", "games", "anime"), -3f)
                add(listOf("maps"), -2f); add(listOf("art"), -1.5f)
            }
        }
        if (listOf("ifunny", "meme", "reddit", "9gag", "imgur").any { it in path }) {
            add(listOf("memes"), 2.0f); add(listOf("comics"), 1.0f)
        }
        return hints
    }

    private fun promptVector(prompt: String): FloatArray? {
        promptVecs[prompt]?.let { return it }
        val a = engine.embedText(prompt) ?: return null
        val b = engine.embedText("a photo of $prompt") ?: return null
        val v = SorterEngine.normalize(FloatArray(a.size) { a[it] + b[it] })
        promptVecs[prompt] = v
        return v
    }

    // ------------------------------------------------------------------ teaching & managing

    suspend fun approve(id: String) = change { l -> l.map { if (it.id == id) it.copy(status = AlbumStatus.APPROVED) else it } }
    suspend fun unapprove(id: String) = change { l -> l.map { if (it.id == id) it.copy(status = AlbumStatus.SUGGESTED) else it } }
    suspend fun dismiss(id: String) = change { l -> l.map { if (it.id == id) it.copy(status = AlbumStatus.DISMISSED) else it } }
    suspend fun restore(id: String) = change { l -> l.map { if (it.id == id) it.copy(status = AlbumStatus.SUGGESTED) else it } }
    suspend fun rename(id: String, name: String) = change { l -> l.map { if (it.id == id) it.copy(name = name.trim().take(40)) else it } }

    /** Starter albums are dismissed (they can come back); your own albums are deleted. */
    suspend fun delete(id: String) = change { l ->
        l.mapNotNull { a ->
            when {
                a.id != id -> a
                a.key != null -> a.copy(status = AlbumStatus.DISMISSED, includes = emptySet(), excludes = emptySet())
                else -> null
            }
        }
    }

    suspend fun create(name: String, prompt: String?): String {
        val id = "own_" + UUID.randomUUID().toString().take(8)
        change { l -> l + SmartAlbum(id, name.trim().take(40).ifBlank { "My album" }, null, prompt?.trim()?.ifBlank { null }, AlbumStatus.APPROVED) }
        return id
    }

    /** "These belong in this album." */
    suspend fun addPhotos(albumId: String, ids: Collection<Long>) = change { l ->
        l.map { a ->
            if (a.id == albumId) a.copy(includes = a.includes + ids, excludes = a.excludes - ids.toSet())
            else a.copy(includes = a.includes - ids.toSet())
        }
    }

    /** "These don't belong here." */
    suspend fun removePhotos(albumId: String, ids: Collection<Long>) = change { l ->
        l.map { a -> if (a.id == albumId) a.copy(includes = a.includes - ids.toSet(), excludes = a.excludes + ids) else a }
    }

    suspend fun movePhotos(fromId: String, toId: String, ids: Collection<Long>) = change { l ->
        l.map { a ->
            when (a.id) {
                fromId -> a.copy(includes = a.includes - ids.toSet(), excludes = a.excludes + ids)
                toId -> a.copy(includes = a.includes + ids, excludes = a.excludes - ids.toSet())
                else -> a.copy(includes = a.includes - ids.toSet())
            }
        }
    }

    suspend fun resetLearning() {
        change { emptyList() }
        ensureStarterAlbums()
        classify()
    }

    private suspend fun change(block: (List<SmartAlbum>) -> List<SmartAlbum>) {
        mutate(block)
        classify()
    }

    private fun mutate(block: (List<SmartAlbum>) -> List<SmartAlbum>) {
        synchronized(albumsLock) {
            albums = block(albums)
            saveAlbums(albums)
        }
    }

    // ------------------------------------------------------------------ typed search

    /** Ranks photos by how well they match [query]. Empty when Search & sort isn't installed. */
    suspend fun search(query: String, items: List<MediaItem>): List<Long> = withContext(Dispatchers.Default) {
        if (query.isBlank() || state.value.search != SearchModelState.Ready) return@withContext emptyList()
        val q = promptVector(query.trim()) ?: return@withContext emptyList()
        val scored = items.mapNotNull { m -> embeddings[m.id]?.let { m.id to dot(q, it) } }.sortedByDescending { it.second }
        val best = scored.firstOrNull()?.second ?: return@withContext emptyList()
        scored.filter { it.second >= maxOf(0.15f, best - 0.07f) }.take(300).map { it.first }
    }

    suspend fun installSearchModel() = withContext(Dispatchers.IO) {
        if (state.value.search is SearchModelState.Downloading) return@withContext
        state.update { it.copy(search = SearchModelState.Downloading(0f)) }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val files = listOf(
            "stellar-sorter-tokenizer.json" to engine.tokenizerFile,
            "stellar-sorter-text.onnx" to engine.textModelFile,
        )
        val bases = listOf(
            "${UpdateChecker.REPO_URL}/releases/download/v${BuildConfig.VERSION_NAME}/",
            "${UpdateChecker.REPO_URL}/releases/latest/download/",
        )
        val ok = runCatching {
            files.forEachIndexed { index, (name, dest) ->
                var done = false
                var lastError = "not found"
                for (base in bases) {
                    if (done) break
                    val req = Request.Builder().url(base + name).header("User-Agent", "StellarGallery").build()
                    runCatching {
                        client.newCall(req).execute().use { r ->
                            if (!r.isSuccessful) { lastError = "server answered ${r.code}"; return@use }
                            val body = r.body ?: return@use
                            val total = body.contentLength().coerceAtLeast(1)
                            val part = File(dest.parentFile, dest.name + ".part")
                            body.byteStream().use { input ->
                                FileOutputStream(part).use { out ->
                                    val buf = ByteArray(128 * 1024)
                                    var read = 0L
                                    while (true) {
                                        ensureActive()
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        out.write(buf, 0, n)
                                        read += n
                                        val p = (index + (read.toFloat() / total).coerceAtMost(1f)) / files.size
                                        state.update { it.copy(search = SearchModelState.Downloading(p)) }
                                    }
                                }
                            }
                            if (!part.renameTo(dest)) { dest.delete(); part.renameTo(dest) }
                            done = true
                        }
                    }.onFailure { lastError = it.message ?: "download failed" }
                }
                if (!done) error(lastError)
            }
            engine.unloadText()
            if (!engine.loadText()) error("the downloaded model didn't load")
            engine.embedText("test") ?: error("the downloaded model didn't run")
            true
        }.getOrElse { t ->
            engine.unloadText()
            engine.textModelFile.delete(); engine.tokenizerFile.delete()
            state.update { it.copy(search = SearchModelState.Failed("Download failed: ${t.message}")) }
            false
        }
        if (ok) {
            promptVecs.clear()
            state.update { it.copy(search = SearchModelState.Ready) }
            classify()
        }
    }

    suspend fun removeSearchModel() = withContext(Dispatchers.IO) {
        engine.unloadText()
        engine.searchDir.listFiles()?.forEach { it.delete() }
        promptVecs.clear()
        state.update { it.copy(search = SearchModelState.NotInstalled) }
        classify()
    }

    // ------------------------------------------------------------------ storage

    private fun loadAlbums(): List<SmartAlbum> = runCatching {
        val arr = JSONArray(albumsFile.readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            fun ids(k: String): Set<Long> = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getLong(it) }.toSet() } ?: emptySet()
            SmartAlbum(
                id = o.getString("id"),
                name = o.getString("name"),
                key = o.optString("key").ifBlank { null },
                prompt = o.optString("prompt").ifBlank { null },
                status = runCatching { AlbumStatus.valueOf(o.getString("status")) }.getOrDefault(AlbumStatus.SUGGESTED),
                includes = ids("inc"),
                excludes = ids("exc"),
            )
        }
    }.getOrDefault(emptyList())

    private fun saveAlbums(list: List<SmartAlbum>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { a ->
                arr.put(
                    JSONObject()
                        .put("id", a.id).put("name", a.name)
                        .put("key", a.key ?: "").put("prompt", a.prompt ?: "")
                        .put("status", a.status.name)
                        .put("inc", JSONArray(a.includes.toList()))
                        .put("exc", JSONArray(a.excludes.toList())),
                )
            }
            val tmp = File(dir, "albums.tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(albumsFile)) { albumsFile.delete(); tmp.renameTo(albumsFile) }
        }
    }

    private fun loadEmbeddings() {
        // Embeddings from an older model don't match — remove them.
        dir.listFiles()?.filter { it.name.startsWith("emb") && it.name.endsWith(".bin") && it.name != embFile.name }?.forEach { it.delete() }
        if (!embFile.exists()) return
        runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(embFile), 256 * 1024)).use { input ->
                while (true) {
                    val id = try { input.readLong() } catch (e: EOFException) { break }
                    val size = input.readLong()
                    val d = input.readShort().toInt()
                    val v = FloatArray(d) { input.readFloat() }
                    if (d == engine.dim) { embeddings[id] = v; embSizes[id] = size }
                }
            }
        }
    }

    private fun appendEmbeddings(list: List<Pair<MediaItem, FloatArray>>) {
        if (list.isEmpty()) return
        runCatching {
            DataOutputStream(BufferedOutputStream(FileOutputStream(embFile, true))).use { out ->
                list.forEach { (m, v) -> writeOne(out, m.id, m.size, v) }
            }
        }
    }

    private fun compactEmbeddings(items: List<MediaItem>) {
        val live = items.mapTo(HashSet()) { it.id }
        val stale = embeddings.keys.filter { it !in live }
        if (stale.isEmpty() && embFile.length() < (embeddings.size + 50L) * (18 + 4L * engine.dim) * 2) return
        stale.forEach { embeddings.remove(it); embSizes.remove(it) }
        runCatching {
            val tmp = File(dir, "emb.tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
                embeddings.forEach { (id, v) -> writeOne(out, id, embSizes[id] ?: 0L, v) }
            }
            if (!tmp.renameTo(embFile)) { embFile.delete(); tmp.renameTo(embFile) }
        }
    }

    private fun writeOne(out: DataOutputStream, id: Long, size: Long, v: FloatArray) {
        out.writeLong(id); out.writeLong(size); out.writeShort(v.size)
        v.forEach { out.writeFloat(it) }
    }

    private fun dot(a: FloatArray, b: FloatArray) = SorterEngine.dot(a, b)
}
