package com.courieriot.stellargallery

import android.app.Application
import android.content.IntentSender
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.courieriot.stellargallery.data.Album
import com.courieriot.stellargallery.data.MediaItem
import com.courieriot.stellargallery.data.MediaRepository
import com.courieriot.stellargallery.data.Prefs
import com.courieriot.stellargallery.data.SortOrder
import com.courieriot.stellargallery.data.UpdateChecker
import com.courieriot.stellargallery.share.ShareManager
import com.courieriot.stellargallery.vault.Vault
import com.courieriot.stellargallery.vault.VaultItem
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

class PendingRequest(val sender: IntentSender, val onResult: (Boolean) -> Unit) {
    var launched = false
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo = MediaRepository(app)
    private val prefs = Prefs(app)
    val vault = Vault(app)
    val share = ShareManager(app, repo)

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

    val media: StateFlow<List<MediaItem>> = combine(rawMedia, _sortOrder) { list, sort -> sortList(list, sort) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val favorites: StateFlow<List<MediaItem>> = media.map { l -> l.filter { it.isFavorite } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val albums: StateFlow<List<Album>> = rawMedia.map { buildAlbums(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val trash: StateFlow<List<MediaItem>> = _trash

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection

    private val _pendingRequest = MutableStateFlow<PendingRequest?>(null)
    val pendingRequest: StateFlow<PendingRequest?> = _pendingRequest

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    /** Hand-offs between screens. */
    var editTarget: MediaItem? = null
    var sendQueue: List<MediaItem> = emptyList()

    // ---------------- Vault ----------------

    private val _vaultUnlocked = MutableStateFlow(false)
    val vaultUnlocked: StateFlow<Boolean> = _vaultUnlocked
    private val _vaultItems = MutableStateFlow<List<VaultItem>>(emptyList())
    val vaultItems: StateFlow<List<VaultItem>> = _vaultItems
    private val _vaultBusy = MutableStateFlow<String?>(null)
    val vaultBusy: StateFlow<String?> = _vaultBusy
    private val _vaultBiometric = MutableStateFlow(vault.biometricEnabled)
    val vaultBiometric: StateFlow<Boolean> = _vaultBiometric

    // ---------------- Updates ----------------

    private val _updateAvailable = MutableStateFlow<UpdateChecker.Release?>(null)
    val updateAvailable: StateFlow<UpdateChecker.Release?> = _updateAvailable

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { scheduleRefresh() }
    }

    init {
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
        // Drop selections that no longer exist.
        val ids = rawMedia.value.mapTo(HashSet()) { it.id } + _trash.value.map { it.id }
        _selection.value = _selection.value.filterTo(HashSet()) { it in ids }
    }

    fun listFor(source: String): Flow<List<MediaItem>> = when {
        source == "fav" -> favorites
        source == "trash" -> trash
        source.startsWith("a") -> {
            val id = source.drop(1).toLongOrNull()
            media.map { l -> l.filter { it.bucketId == id } }
        }
        else -> media
    }

    fun snapshotFor(source: String): List<MediaItem> = when {
        source == "fav" -> favorites.value
        source == "trash" -> trash.value
        source.startsWith("a") -> {
            val id = source.drop(1).toLongOrNull()
            media.value.filter { it.bucketId == id }
        }
        else -> media.value
    }

    // ---------------- Settings ----------------

    fun setSortOrder(s: SortOrder) { prefs.sortOrder = s; _sortOrder.value = s }
    fun setGridColumns(c: Int) { prefs.gridColumns = c; _gridColumns.value = c }
    fun setGroupByDate(b: Boolean) { prefs.groupByDate = b; _groupByDate.value = b }
    fun setCheckUpdates(b: Boolean) { prefs.checkUpdates = b; _checkUpdates.value = b }

    // ---------------- Selection ----------------

    fun toggleSelection(id: Long) {
        _selection.value = _selection.value.let { if (id in it) it - id else it + id }
    }
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

    // ---------------- Actions ----------------

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

    // ---------------- Vault ----------------

    fun vaultHasPin(): Boolean = vault.hasPin()

    fun hideInVault(items: List<MediaItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            _vaultBusy.value = "Encrypting " + plural(items.size, "item") + "…"
            val added = withContext(Dispatchers.IO) {
                items.mapNotNull { item -> runCatching { item to vault.add(item) }.getOrNull() }
            }
            _vaultBusy.value = null
            if (added.isEmpty()) { toast("Couldn't hide those items"); return@launch }
            // Remove the originals (Android asks the user to confirm).
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
            unlockInternal()
        }
    }

    fun changeVaultPin(pin: String) {
        viewModelScope.launch {
            withContext(Dispatchers.Default) { vault.setPin(pin) }
            toast("Vault PIN changed")
        }
    }

    fun unlockWithPin(pin: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.Default) { vault.checkPin(pin) }
            if (ok) unlockInternal()
            onResult(ok)
        }
    }

    fun unlockWithBiometric() { viewModelScope.launch { unlockInternal() } }

    private suspend fun unlockInternal() {
        _vaultItems.value = withContext(Dispatchers.IO) { runCatching { vault.items() }.getOrDefault(emptyList()) }
        _vaultUnlocked.value = true
    }

    fun lockVault() {
        if (!_vaultUnlocked.value) return
        _vaultUnlocked.value = false
        _vaultItems.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) { vault.clearTemp() }
    }

    private suspend fun reloadVault() {
        if (_vaultUnlocked.value) {
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

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"
}
