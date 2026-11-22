package com.courieriot.stellargallery

import android.app.Application
import android.app.WallpaperManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.database.ContentObserver
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.courieriot.stellargallery.data.Album
import com.courieriot.stellargallery.data.BackupManager
import com.courieriot.stellargallery.data.DuplicateFinder
import com.courieriot.stellargallery.data.DuplicateGroup
import com.courieriot.stellargallery.data.GeoIndex
import com.courieriot.stellargallery.data.GeoPhoto
import com.courieriot.stellargallery.data.MediaItem
import com.courieriot.stellargallery.data.MediaRepository
import com.courieriot.stellargallery.data.MetadataStripper
import com.courieriot.stellargallery.data.Prefs
import com.courieriot.stellargallery.data.SortOrder
import com.courieriot.stellargallery.data.UpdateChecker
import com.courieriot.stellargallery.share.ShareManager
import com.courieriot.stellargallery.sorter.SearchModelState
import com.courieriot.stellargallery.sorter.Sorter
import com.courieriot.stellargallery.sorter.SorterState
import com.courieriot.stellargallery.ui.theme.Accent
import com.courieriot.stellargallery.ui.theme.ThemeState
import com.courieriot.stellargallery.vault.Vault
import com.courieriot.stellargallery.vault.VaultItem
import com.courieriot.stellargallery.widget.PhotoWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class PendingRequest(val sender: IntentSender, val onResult: (Boolean) -> Unit) {
    var launched = false
}

data class ExternalMedia(val uri: Uri, val mime: String?) {
    val isVideo: Boolean get() = mime?.startsWith("video/") == true
}

enum class SearchType(val label: String) {
    ALL("All"), PHOTOS("Photos"), VIDEOS("Videos"), SCREENSHOTS("Screenshots"), FAVORITES("Favorites"),
}

data class SearchQuery(
    val text: String = "",
    val type: SearchType = SearchType.ALL,
    val fromMillis: Long? = null,
    val toMillis: Long? = null,
) {
    val isEmpty: Boolean get() = text.isBlank() && type == SearchType.ALL && fromMillis == null && toMillis == null
}

data class Memory(val yearsAgo: Int, val items: List<MediaItem>)

data class ScanState<T>(val running: Boolean, val done: Int, val total: Int, val result: T?)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo = MediaRepository(app)
    private val prefs = Prefs(app)
    val vault = Vault(app)
    val share = ShareManager(app, repo)
    val backup = BackupManager(app, prefs, repo)
    val sorter = Sorter(app)

    // ---------------- Media ----------------

    private val rawMedia = MutableStateFlow<List<MediaItem>>(emptyList())
    private val _trash = MutableStateFlow<List<MediaItem>>(emptyList())
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _sortOrder = MutableStateFlow(prefs.sortOrder)
    val sortOrder: StateFlow<SortOrder> = _sortOrder
    private val _gridColumns = MutableStateFlow(prefs.gridColumns)
    val gridColumns: StateFlow<Int> = _gridColumns
    private val _groupByDate = MutableStateFlow(prefs.groupByDate)
    val groupByDate: StateFlow<Boolean> = _groupByDate
    private val _checkUpdates = MutableStateFlow(prefs.checkUpdates)
    val checkUpdates: StateFlow<Boolean> = _checkUpdates

    // Appearance & behavior (v2)
    private val _accent = MutableStateFlow(prefs.accent)
    val accent: StateFlow<Accent> = _accent
    private val _pureBlack = MutableStateFlow(prefs.pureBlack)
    val pureBlack: StateFlow<Boolean> = _pureBlack
    private val _slideshowSeconds = MutableStateFlow(prefs.slideshowSeconds)
    val slideshowSeconds: StateFlow<Int> = _slideshowSeconds
    private val _stripMetadata = MutableStateFlow(prefs.stripMetadata)
    val stripMetadata: StateFlow<Boolean> = _stripMetadata
    private val _hideVaultEntry = MutableStateFlow(prefs.hideVaultEntry)
    val hideVaultEntry: StateFlow<Boolean> = _hideVaultEntry
    private val _showMemories = MutableStateFlow(prefs.showMemories)
    val showMemories: StateFlow<Boolean> = _showMemories
    private val _widgetFavoritesOnly = MutableStateFlow(prefs.widgetFavoritesOnly)
    val widgetFavoritesOnly: StateFlow<Boolean> = _widgetFavoritesOnly
    private val _backupOnLaunch = MutableStateFlow(prefs.backupOnLaunch)
    val backupOnLaunch: StateFlow<Boolean> = _backupOnLaunch
    private val _backupVideos = MutableStateFlow(prefs.backupIncludeVideos)
    val backupVideos: StateFlow<Boolean> = _backupVideos

    val media: StateFlow<List<MediaItem>> = combine(rawMedia, _sortOrder) { list, sort -> sortList(list, sort) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val favorites: StateFlow<List<MediaItem>> = media.map { l -> l.filter { it.isFavorite } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val albums: StateFlow<List<Album>> = rawMedia.map { buildAlbums(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val trash: StateFlow<List<MediaItem>> = _trash

    val memories: StateFlow<List<Memory>> = rawMedia.map { buildMemories(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Search
    private val _searchQuery = MutableStateFlow(SearchQuery())
    val searchQuery: StateFlow<SearchQuery> = _searchQuery
    private val smartHits = MutableStateFlow<List<Long>>(emptyList())
    val searchResults: StateFlow<List<MediaItem>> = combine(media, _searchQuery, smartHits) { l, q, hits -> filterSearch(l, q, hits) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private var smartSearchJob: Job? = null

    // Ad-hoc lists the viewer can open (duplicates, largest files, map cluster, memories)
    private val customIds = MutableStateFlow<Map<String, List<Long>>>(emptyMap())

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection

    private val _pendingRequest = MutableStateFlow<PendingRequest?>(null)
    val pendingRequest: StateFlow<PendingRequest?> = _pendingRequest

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    /** Hand-offs between screens. */
    var editTarget: MediaItem? = null
    var videoEditTarget: MediaItem? = null
    var sendQueue: List<MediaItem> = emptyList()

    // External "open with"
    private val _external = MutableStateFlow<ExternalMedia?>(null)
    val external: StateFlow<ExternalMedia?> = _external
    var launchedExternally = false
        private set

    // ---------------- Vault ----------------

    private val _vaultUnlocked = MutableStateFlow(false)
    val vaultUnlocked: StateFlow<Boolean> = _vaultUnlocked
    private val _vaultDecoy = MutableStateFlow(false)
    val vaultDecoy: StateFlow<Boolean> = _vaultDecoy
    private val _vaultItems = MutableStateFlow<List<VaultItem>>(emptyList())
    val vaultItems: StateFlow<List<VaultItem>> = _vaultItems
    private val _vaultBusy = MutableStateFlow<String?>(null)
    val vaultBusy: StateFlow<String?> = _vaultBusy
    private val _vaultBiometric = MutableStateFlow(vault.biometricEnabled)
    val vaultBiometric: StateFlow<Boolean> = _vaultBiometric

    // ---------------- Photo sorter ----------------

    private val _sorterEnabled = MutableStateFlow(prefs.sorterEnabled)
    val sorterEnabled: StateFlow<Boolean> = _sorterEnabled
    val sorterState: StateFlow<SorterState> = sorter.state
    private var sortJob: Job? = null
    @Volatile private var rescanPending = false
    private val _sorterSimplified = MutableStateFlow(prefs.sorterSimplified)
    val sorterSimplified: StateFlow<Boolean> = _sorterSimplified

    // ---------------- Tools ----------------

    private val _dupes = MutableStateFlow<ScanState<List<DuplicateGroup>>>(ScanState(false, 0, 0, null))
    val dupes: StateFlow<ScanState<List<DuplicateGroup>>> = _dupes
    private var dupJob: Job? = null

    private val _geo = MutableStateFlow<ScanState<List<GeoPhoto>>>(ScanState(false, 0, 0, null))
    val geo: StateFlow<ScanState<List<GeoPhoto>>> = _geo
    private var geoJob: Job? = null

    // ---------------- Updates ----------------

    private val _updateAvailable = MutableStateFlow<UpdateChecker.Release?>(null)
    val updateAvailable: StateFlow<UpdateChecker.Release?> = _updateAvailable

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { scheduleRefresh() }
    }

    private var autoBackupDone = false

    init {
        ThemeState.apply(prefs.accent, prefs.pureBlack)
        sorter.initSimplified(prefs.sorterSimplified)
        runCatching { app.contentResolver.registerContentObserver(repo.observeUri, true, observer) }
        if (prefs.checkUpdates) checkForUpdates(manual = false)
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().contentResolver.unregisterContentObserver(observer) }
        share.stopAll()
        vault.clearTemp()
    }

    // ---------------- Loading ----------------

    private var refreshJob: Job? = null

    fun scheduleRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            delay(350)
            refreshNow()
        }
    }

    fun refresh() { viewModelScope.launch { refreshNow() } }

    private suspend fun refreshNow() {
        runCatching {
            rawMedia.value = repo.load(trashed = false)
            _trash.value = repo.load(trashed = true)
        }
        _loading.value = false
        val ids = rawMedia.value.mapTo(HashSet()) { it.id } + _trash.value.map { it.id }
        _selection.value = _selection.value.filterTo(HashSet()) { it in ids }
        maybeAutoBackup()
        startSorting()
    }

    // ---------------- Photo sorter ----------------

    private fun startSorting() {
        if (!_sorterEnabled.value || media.value.isEmpty()) return
        if (sortJob?.isActive == true) { rescanPending = true; return }
        sortJob = viewModelScope.launch {
            do {
                rescanPending = false
                runCatching { sorter.scan(media.value) }
            } while (rescanPending && _sorterEnabled.value)
        }
    }

    fun setSorterEnabled(b: Boolean) {
        prefs.sorterEnabled = b
        _sorterEnabled.value = b
        if (b) startSorting() else { sortJob?.cancel(); rescanPending = false }
    }

    private fun sorterOp(msg: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { toast("Sorter: ${it.message}") }
            msg?.let { toast(it) }
        }
    }

    fun sorterApprove(id: String) = sorterOp("Added to your Albums tab") { sorter.approve(id) }
    fun sorterUnapprove(id: String) = sorterOp("Moved back to suggestions") { sorter.unapprove(id) }
    fun sorterDismiss(id: String) = sorterOp { sorter.dismiss(id) }
    fun sorterRestore(id: String) = sorterOp { sorter.restore(id) }
    fun sorterRename(id: String, name: String) = sorterOp { sorter.rename(id, name) }
    fun sorterDelete(id: String) = sorterOp("Smart album removed") { sorter.delete(id) }
    fun sorterCreate(name: String, prompt: String?, items: List<MediaItem> = emptyList()) = sorterOp("Smart album created") {
        val id = sorter.create(name, prompt)
        if (items.isNotEmpty()) sorter.addPhotos(id, items.map { it.id })
        clearSelection()
    }
    fun sorterAdd(albumId: String, items: List<MediaItem>) = sorterOp("Got it — the sorter will learn from these") {
        sorter.addPhotos(albumId, items.map { it.id }); clearSelection()
    }
    fun sorterRemove(albumId: String, items: List<MediaItem>) = sorterOp("Removed — the sorter will learn from this") {
        sorter.removePhotos(albumId, items.map { it.id }); clearSelection()
    }
    fun sorterMove(fromId: String, toId: String, items: List<MediaItem>) = sorterOp("Moved — the sorter will learn from this") {
        sorter.movePhotos(fromId, toId, items.map { it.id }); clearSelection()
    }
    fun setSorterSimplified(b: Boolean) {
        prefs.sorterSimplified = b
        _sorterSimplified.value = b
        sorterOp { sorter.setSimplified(b) }
    }

    fun trainerCards(): List<com.courieriot.stellargallery.sorter.TrainerCard> = sorter.trainerCards(20)

    suspend fun matchCards(albumId: String, extra: Collection<Long>, skip: Set<Long>) =
        runCatching { sorter.matchCards(albumId, extra, skip) }.getOrDefault(emptyList())

    fun albumNeedsExamples(albumId: String): Boolean = sorter.needsExamples(albumId)

    /** Creates a smart album, then hands its id back (e.g. to open "Match this album"). */
    fun sorterCreateThen(name: String, prompt: String?, items: List<MediaItem> = emptyList(), then: (String) -> Unit) {
        viewModelScope.launch {
            val id = runCatching {
                val id = sorter.create(name, prompt)
                if (items.isNotEmpty()) sorter.addPhotos(id, items.map { it.id })
                id
            }.getOrNull()
            if (id != null) then(id) else toast("Couldn't create the album")
        }
    }

    fun sorterTeach(answers: List<com.courieriot.stellargallery.sorter.TrainerAnswer>) =
        sorterOp(if (answers.isEmpty()) null else "Thanks — the sorter learned from ${answers.size} answers") { sorter.teach(answers) }

    fun sorterReset() = sorterOp("The sorter's learning was reset") { sorter.resetLearning() }
    fun installSearchModel() = sorterOp { sorter.installSearchModel() }
    fun removeSearchModel() = sorterOp("Search & sort removed") { sorter.removeSearchModel() }

    fun listFor(source: String): Flow<List<MediaItem>> = when {
        source == "all" -> media
        source == "fav" -> favorites
        source == "trash" -> trash
        source == "search" -> searchResults
        source.startsWith("c_") -> combine(media, customIds) { m, c -> resolveCustom(m, c[source.drop(2)]) }
        source.startsWith("smart_") -> {
            val id = source.removePrefix("smart_")
            combine(media, sorter.state) { m, s -> smartItems(m, s.albums.firstOrNull { it.album.id == id }?.ids) }
        }
        source.startsWith("alb_") -> {
            val id = source.removePrefix("alb_").toLongOrNull()
            media.map { l -> l.filter { it.bucketId == id } }
        }
        else -> media
    }

    fun snapshotFor(source: String): List<MediaItem> = when {
        source == "all" -> media.value
        source == "fav" -> favorites.value
        source == "trash" -> trash.value
        source == "search" -> searchResults.value
        source.startsWith("c_") -> resolveCustom(media.value, customIds.value[source.drop(2)])
        source.startsWith("smart_") -> {
            val id = source.removePrefix("smart_")
            smartItems(media.value, sorter.state.value.albums.firstOrNull { it.album.id == id }?.ids)
        }
        source.startsWith("alb_") -> {
            val id = source.removePrefix("alb_").toLongOrNull()
            media.value.filter { it.bucketId == id }
        }
        else -> media.value
    }

    private fun smartItems(all: List<MediaItem>, ids: List<Long>?): List<MediaItem> {
        if (ids.isNullOrEmpty()) return emptyList()
        val set = ids.toHashSet()
        return all.filter { it.id in set }
    }

    /** Stores a list of ids and returns the viewer source name for it. */
    fun setCustomList(key: String, ids: List<Long>): String {
        customIds.value = customIds.value + (key to ids)
        return "c_$key"
    }

    private fun resolveCustom(all: List<MediaItem>, ids: List<Long>?): List<MediaItem> {
        if (ids.isNullOrEmpty()) return emptyList()
        val byId = all.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    // ---------------- Settings ----------------

    fun setSortOrder(s: SortOrder) { prefs.sortOrder = s; _sortOrder.value = s }
    fun setGridColumns(c: Int) { val v = c.coerceIn(2, 6); prefs.gridColumns = v; _gridColumns.value = v }
    fun setGroupByDate(b: Boolean) { prefs.groupByDate = b; _groupByDate.value = b }
    fun setCheckUpdates(b: Boolean) { prefs.checkUpdates = b; _checkUpdates.value = b }
    fun setAccent(a: Accent) { prefs.accent = a; _accent.value = a; ThemeState.apply(a, _pureBlack.value) }
    fun setPureBlack(b: Boolean) { prefs.pureBlack = b; _pureBlack.value = b; ThemeState.apply(_accent.value, b) }
    fun setSlideshowSeconds(s: Int) { prefs.slideshowSeconds = s; _slideshowSeconds.value = s }
    fun setStripMetadata(b: Boolean) { prefs.stripMetadata = b; _stripMetadata.value = b }
    fun setHideVaultEntry(b: Boolean) { prefs.hideVaultEntry = b; _hideVaultEntry.value = b }
    fun setShowMemories(b: Boolean) { prefs.showMemories = b; _showMemories.value = b }
    fun setWidgetFavoritesOnly(b: Boolean) {
        prefs.widgetFavoritesOnly = b; _widgetFavoritesOnly.value = b
        PhotoWidget.requestUpdate(getApplication())
    }

    // ---------------- Search ----------------

    fun updateSearch(q: SearchQuery) {
        val textChanged = q.text != _searchQuery.value.text
        _searchQuery.value = q
        if (textChanged) scheduleSmartSearch(q.text)
    }

    private fun scheduleSmartSearch(text: String) {
        smartSearchJob?.cancel()
        if (text.isBlank() || !_sorterEnabled.value || sorter.state.value.search != SearchModelState.Ready) {
            smartHits.value = emptyList()
            return
        }
        smartSearchJob = viewModelScope.launch {
            delay(400)
            smartHits.value = runCatching { sorter.search(text, media.value) }.getOrDefault(emptyList())
        }
    }

    private fun filterSearch(list: List<MediaItem>, q: SearchQuery, hits: List<Long>): List<MediaItem> {
        if (q.isEmpty) return emptyList()
        val words = q.text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val nameMatches = list.filter { m ->
            val typeOk = when (q.type) {
                SearchType.ALL -> true
                SearchType.PHOTOS -> !m.isVideo
                SearchType.VIDEOS -> m.isVideo
                SearchType.FAVORITES -> m.isFavorite
                SearchType.SCREENSHOTS -> m.relativePath.contains("Screenshot", true) || m.bucketName.contains("Screenshot", true)
            }
            if (!typeOk) return@filter false
            if (q.fromMillis != null && m.dateTaken < q.fromMillis) return@filter false
            if (q.toMillis != null && m.dateTaken > q.toMillis) return@filter false
            if (words.isEmpty()) return@filter true
            val hay = (m.name + " " + m.bucketName + " " + m.relativePath + " " + m.mimeType).lowercase()
            words.all { hay.contains(it) }
        }
        if (hits.isEmpty() || words.isEmpty()) return nameMatches
        // Add "Search & sort" matches (by picture content) after the name matches, keeping type/date filters.
        val already = nameMatches.mapTo(HashSet()) { it.id }
        val byId = list.associateBy { it.id }
        val smart = hits.mapNotNull { id -> byId[id] }.filter { m ->
            m.id !in already &&
                when (q.type) {
                    SearchType.ALL -> true
                    SearchType.PHOTOS -> !m.isVideo
                    SearchType.VIDEOS -> m.isVideo
                    SearchType.FAVORITES -> m.isFavorite
                    SearchType.SCREENSHOTS -> m.relativePath.contains("Screenshot", true) || m.bucketName.contains("Screenshot", true)
                } &&
                (q.fromMillis == null || m.dateTaken >= q.fromMillis) &&
                (q.toMillis == null || m.dateTaken <= q.toMillis)
        }
        return nameMatches + smart
    }

    // ---------------- Selection ----------------

    fun toggleSelection(id: Long) {
        _selection.value = _selection.value.let { if (id in it) it - id else it + id }
    }
    fun setSelection(ids: Set<Long>) { _selection.value = ids }
    fun clearSelection() { _selection.value = emptySet() }
    fun selectAll(items: List<MediaItem>) { _selection.value = items.mapTo(HashSet()) { it.id } }

    // ---------------- Messages / consent ----------------

    fun toast(msg: String) { _messages.tryEmit(msg) }

    private fun request(build: () -> IntentSender, onResult: (Boolean) -> Unit) {
        val sender = runCatching(build).getOrElse {
            toast("Android refused that action")
            return
        }
        _pendingRequest.value = PendingRequest(sender, onResult)
    }

    fun onRequestResult(ok: Boolean) {
        val p = _pendingRequest.value
        _pendingRequest.value = null
        p?.onResult?.invoke(ok)
    }

    // ---------------- External "open with" ----------------

    fun openExternal(uri: Uri, mime: String?) {
        launchedExternally = true
        _external.value = ExternalMedia(uri, mime)
    }

    fun closeExternal() { _external.value = null }

    // ---------------- Actions ----------------

    /** Share through Android's share sheet, removing location/camera info first if enabled. */
    fun shareMedia(context: Context, items: List<MediaItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val uris = if (_stripMetadata.value) {
                withContext(Dispatchers.IO) { MetadataStripper.prepare(getApplication(), items) }
            } else items.map { it.uri }
            val type = when {
                items.none { it.isVideo } -> "image/*"
                items.all { it.isVideo } -> "video/*"
                else -> "*/*"
            }
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            intent.type = type
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newUri(context.contentResolver, "media", uris[0]).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            runCatching { context.startActivity(Intent.createChooser(intent, "Share")) }
        }
    }

    fun trash(items: List<MediaItem>) {
        if (items.isEmpty()) return
        request({ repo.trashRequest(items.map { it.uri }, true) }) { ok ->
            if (ok) { toast(plural(items.size, "item") + " moved to bin"); clearSelection(); scheduleRefresh() }
        }
    }

    fun restore(items: List<MediaItem>) {
        if (items.isEmpty()) return
        request({ repo.trashRequest(items.map { it.uri }, false) }) { ok ->
            if (ok) { toast("Restored " + plural(items.size, "item")); clearSelection(); scheduleRefresh() }
        }
    }

    fun deleteForever(items: List<MediaItem>) {
        if (items.isEmpty()) return
        request({ repo.deleteRequest(items.map { it.uri }) }) { ok ->
            if (ok) { toast("Deleted " + plural(items.size, "item")); clearSelection(); scheduleRefresh() }
        }
    }

    fun setFavorite(items: List<MediaItem>, favorite: Boolean) {
        if (items.isEmpty()) return
        request({ repo.favoriteRequest(items.map { it.uri }, favorite) }) { ok ->
            if (ok) { clearSelection(); scheduleRefresh() }
        }
    }

    fun copyTo(items: List<MediaItem>, relativePath: String) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            var n = 0
            items.forEach { item -> if (runCatching { repo.copyItem(item, relativePath) }.getOrNull() != null) n++ }
            toast("Copied " + plural(n, "item"))
            clearSelection()
            refreshNow()
        }
    }

    fun moveTo(items: List<MediaItem>, relativePath: String) {
        if (items.isEmpty()) return
        request({ repo.writeRequest(items.map { it.uri }) }) { ok ->
            if (ok) viewModelScope.launch {
                val n = repo.moveItems(items, relativePath)
                toast("Moved " + plural(n, "item"))
                clearSelection()
                refreshNow()
            }
        }
    }

    /** [which]: WallpaperManager.FLAG_SYSTEM, FLAG_LOCK, or both. */
    fun setWallpaper(item: MediaItem, which: Int) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val wm = WallpaperManager.getInstance(getApplication())
                    repo.openInput(item.uri)!!.use { wm.setStream(it, null, true, which) }
                }.isSuccess
            }
            toast(if (ok) "Wallpaper set" else "Couldn't set wallpaper")
        }
    }

    fun saveVideoFrame(item: MediaItem, positionMs: Long) {
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) {
                runCatching {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(getApplication(), item.uri)
                        val frame: Bitmap = r.getFrameAtTime(positionMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)!!
                        val base = item.name.substringBeforeLast('.')
                        repo.saveBitmap(frame, "${base}_frame_${positionMs / 1000}s.jpg")
                    } finally {
                        r.release()
                    }
                }.getOrNull()
            }
            toast(if (uri != null) "Frame saved to Pictures/Stellar Gallery" else "Couldn't grab that frame")
            if (uri != null) scheduleRefresh()
        }
    }

    /** Saves a trimmed video produced by the editor into Movies/Stellar Gallery. */
    fun importTrimmedVideo(file: File, name: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    file.inputStream().use { repo.insertStream(name, "video/mp4", MediaRepository.MOVIES_PATH, it) } != null
                }.getOrDefault(false).also { file.delete() }
            }
            toast(if (ok) "Trimmed copy saved to Movies/Stellar Gallery" else "Couldn't save the trimmed video")
            if (ok) scheduleRefresh()
        }
    }

    // ---------------- Tools ----------------

    fun scanDuplicates() {
        if (dupJob?.isActive == true) return
        val list = media.value
        dupJob = viewModelScope.launch {
            _dupes.value = ScanState(true, 0, list.count { !it.isVideo }, null)
            val groups = runCatching {
                DuplicateFinder.scan(getApplication(), list) { d, t -> _dupes.value = ScanState(true, d, t, null) }
            }.getOrDefault(emptyList())
            _dupes.value = ScanState(false, 0, 0, groups)
        }
    }

    fun indexLocations(hasPermission: Boolean) {
        if (geoJob?.isActive == true) return
        val list = media.value
        geoJob = viewModelScope.launch {
            _geo.value = ScanState(true, 0, list.count { !it.isVideo }, _geo.value.result)
            val pts = runCatching {
                GeoIndex.index(getApplication(), list, hasPermission) { d, t -> _geo.value = ScanState(true, d, t, _geo.value.result) }
            }.getOrDefault(emptyList())
            _geo.value = ScanState(false, 0, 0, pts)
        }
    }

    // ---------------- Backup ----------------

    fun setBackupConfig(url: String, user: String, password: String) {
        prefs.backupUrl = url.trim(); prefs.backupUser = user.trim(); prefs.backupPassword = password
    }
    fun backupUrl() = prefs.backupUrl
    fun backupUser() = prefs.backupUser
    fun backupPassword() = prefs.backupPassword
    fun setBackupOnLaunch(b: Boolean) { prefs.backupOnLaunch = b; _backupOnLaunch.value = b }
    fun setBackupVideos(b: Boolean) { prefs.backupIncludeVideos = b; _backupVideos.value = b }

    fun testBackup(onResult: (String?) -> Unit) { viewModelScope.launch { onResult(backup.test()) } }
    fun runBackup() { viewModelScope.launch { backup.run(media.value) } }

    private fun maybeAutoBackup() {
        if (autoBackupDone || !prefs.backupOnLaunch || !backup.isConfigured) return
        if (!backup.isOnUnmeteredNetwork() || media.value.isEmpty()) return
        autoBackupDone = true
        runBackup()
    }

    // ---------------- Vault ----------------

    fun vaultHasPin(): Boolean = vault.hasPin()
    fun vaultHasDecoy(): Boolean = vault.hasDecoy()

    fun hideInVault(items: List<MediaItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            _vaultBusy.value = "Encrypting " + plural(items.size, "item") + "…"
            val added = withContext(Dispatchers.IO) {
                items.mapNotNull { item -> runCatching { item to vault.add(item) }.getOrNull() }
            }
            _vaultBusy.value = null
            if (added.isEmpty()) { toast("Couldn't hide those items"); return@launch }
            request({ repo.deleteRequest(added.map { it.first.uri }) }) { ok ->
                viewModelScope.launch {
                    if (ok) {
                        toast(plural(added.size, "item") + " hidden in vault")
                        clearSelection()
                        scheduleRefresh()
                    } else {
                        withContext(Dispatchers.IO) { vault.delete(added.map { it.second }) }
                        toast("Cancelled — nothing was hidden")
                    }
                    reloadVault()
                }
            }
        }
    }

    fun setupVaultPin(pin: String) {
        viewModelScope.launch {
            withContext(Dispatchers.Default) { vault.setPin(pin) }
            unlockInternal(decoy = false)
        }
    }

    fun changeVaultPin(pin: String) {
        viewModelScope.launch {
            val clash = withContext(Dispatchers.Default) { vault.check(pin) == Vault.PinResult.DECOY }
            if (clash) { toast("That's your decoy PIN — pick a different one"); return@launch }
            withContext(Dispatchers.Default) { vault.setPin(pin) }
            toast("Vault PIN changed")
        }
    }

    fun setDecoyPin(pin: String?) {
        viewModelScope.launch {
            if (pin == null) {
                vault.clearDecoy(); toast("Decoy PIN removed"); return@launch
            }
            val clash = withContext(Dispatchers.Default) { vault.checkPin(pin) }
            if (clash) { toast("The decoy PIN must be different from your real PIN"); return@launch }
            withContext(Dispatchers.Default) { vault.setDecoyPin(pin) }
            toast("Decoy PIN set — it opens an empty vault")
        }
    }

    fun unlockWithPin(pin: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) { vault.check(pin) }
            when (r) {
                Vault.PinResult.REAL -> unlockInternal(decoy = false)
                Vault.PinResult.DECOY -> { vault.logAttempt("decoy"); unlockInternal(decoy = true) }
                Vault.PinResult.WRONG -> vault.logAttempt("wrong")
            }
            onResult(r != Vault.PinResult.WRONG)
        }
    }

    fun unlockWithBiometric() { viewModelScope.launch { unlockInternal(decoy = false) } }

    private suspend fun unlockInternal(decoy: Boolean) {
        _vaultDecoy.value = decoy
        if (decoy) {
            _vaultItems.value = emptyList()
        } else {
            val unseen = vault.unseenAttempts()
            if (unseen > 0) toast("$unseen failed or decoy unlock attempt(s) since last time — see Unlock log")
            vault.markAttemptsSeen()
            _vaultItems.value = withContext(Dispatchers.IO) { runCatching { vault.items() }.getOrDefault(emptyList()) }
        }
        _vaultUnlocked.value = true
    }

    fun vaultAttempts(): List<Vault.Attempt> = vault.attempts().reversed()
    fun clearVaultAttempts() { vault.clearAttempts() }

    fun lockVault() {
        if (!_vaultUnlocked.value) return
        _vaultUnlocked.value = false
        _vaultDecoy.value = false
        _vaultItems.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) { vault.clearTemp() }
    }

    private suspend fun reloadVault() {
        if (_vaultUnlocked.value && !_vaultDecoy.value) {
            _vaultItems.value = withContext(Dispatchers.IO) { vault.items() }
        }
    }

    fun setVaultBiometric(enabled: Boolean) {
        vault.biometricEnabled = enabled
        _vaultBiometric.value = enabled
    }

    fun restoreFromVault(items: List<VaultItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            _vaultBusy.value = "Restoring " + plural(items.size, "item") + "…"
            val n = withContext(Dispatchers.IO) {
                items.count { runCatching { vault.restore(it, repo) }.getOrDefault(false) }
            }
            _vaultBusy.value = null
            reloadVault()
            toast("Restored " + plural(n, "item") + " to Pictures/Stellar Gallery/Restored")
            scheduleRefresh()
        }
    }

    fun deleteFromVault(items: List<VaultItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { vault.delete(items) }
            reloadVault()
            toast("Deleted " + plural(items.size, "item") + " from vault")
        }
    }

    // ---------------- Updates ----------------

    fun checkForUpdates(manual: Boolean) {
        viewModelScope.launch {
            val r = UpdateChecker.latest()
            when {
                r == null -> if (manual) toast("Couldn't reach GitHub")
                UpdateChecker.isNewer(r.version, BuildConfig.VERSION_NAME) -> _updateAvailable.value = r
                manual -> toast("You're on the latest version (v${BuildConfig.VERSION_NAME})")
            }
        }
    }

    fun dismissUpdate() { _updateAvailable.value = null }

    // ---------------- Helpers ----------------

    private fun sortList(l: List<MediaItem>, s: SortOrder) = when (s) {
        SortOrder.NEWEST -> l.sortedByDescending { it.dateTaken }
        SortOrder.OLDEST -> l.sortedBy { it.dateTaken }
        SortOrder.NAME -> l.sortedBy { it.name.lowercase() }
        SortOrder.SIZE -> l.sortedByDescending { it.size }
    }

    private fun buildAlbums(list: List<MediaItem>): List<Album> =
        list.groupBy { it.bucketId }.map { (id, items) ->
            val cover = items.maxBy { it.dateTaken }
            Album(id, cover.bucketName, cover.relativePath, cover, items.size)
        }.sortedByDescending { it.cover.dateTaken }

    private fun buildMemories(list: List<MediaItem>): List<Memory> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        return list.mapNotNull { m ->
            val d = Instant.ofEpochMilli(m.dateTaken).atZone(zone).toLocalDate()
            if (d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth && d.year < today.year) {
                (today.year - d.year) to m
            } else null
        }.groupBy({ it.first }, { it.second })
            .map { (years, items) -> Memory(years, items.sortedBy { it.dateTaken }) }
            .sortedBy { it.yearsAgo }
    }

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"
}
