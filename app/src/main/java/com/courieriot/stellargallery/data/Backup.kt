package com.courieriot.stellargallery.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import java.util.concurrent.TimeUnit

data class BackupProgress(
    val running: Boolean,
    val done: Int,
    val total: Int,
    val label: String,
    val error: String? = null,
)

/**
 * Optional backup to your own computer / NAS over WebDAV (works with Nextcloud, many NAS boxes,
 * rclone serve webdav, etc). Uploads only items that haven't been backed up yet, one folder per album.
 */
class BackupManager(private val context: Context, private val prefs: Prefs, private val repo: MediaRepository) {
    val state = MutableStateFlow<BackupProgress?>(null)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    private val doneFile = File(context.filesDir, "backup.done")

    val isConfigured: Boolean get() = prefs.backupUrl.startsWith("http")

    fun isOnUnmeteredNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.activeNetwork != null && !cm.isActiveNetworkMetered
    }

    private fun base(): String = prefs.backupUrl.trim().trimEnd('/')

    private fun Request.Builder.auth(): Request.Builder {
        if (prefs.backupUser.isNotBlank()) header("Authorization", Credentials.basic(prefs.backupUser, prefs.backupPassword))
        return this
    }

    /** Returns null if the server answered OK, otherwise an error message. */
    suspend fun test(): String? = withContext(Dispatchers.IO) {
        if (!isConfigured) return@withContext "Enter a server address starting with http:// or https://"
        runCatching {
            val req = Request.Builder().url(base() + "/").method("PROPFIND", null).header("Depth", "0").auth().build()
            client.newCall(req).execute().use { r ->
                when {
                    r.isSuccessful || r.code == 207 -> null
                    r.code == 401 -> "Wrong username or password"
                    r.code == 404 -> "Folder not found on the server"
                    else -> "Server answered ${r.code}"
                }
            }
        }.getOrElse { it.message ?: "Couldn't reach the server" }
    }

    fun pendingCount(items: List<MediaItem>): Int {
        val done = loadDone()
        return items.count { key(it) !in done && (prefs.backupIncludeVideos || !it.isVideo) }
    }

    suspend fun run(items: List<MediaItem>) = withContext(Dispatchers.IO) {
        if (state.value?.running == true) return@withContext
        if (!isConfigured) { state.value = BackupProgress(false, 0, 0, "Backup isn't set up"); return@withContext }
        val done = loadDone()
        val todo = items.filter { key(it) !in done && (prefs.backupIncludeVideos || !it.isVideo) }
        if (todo.isEmpty()) { state.value = BackupProgress(false, 0, 0, "Everything is already backed up"); return@withContext }

        val madeFolders = HashSet<String>()
        var ok = 0
        var lastError: String? = null
        todo.forEachIndexed { i, item ->
            ensureActive()
            state.value = BackupProgress(true, i, todo.size, "Uploading ${item.name}")
            val folder = Uri.encode(item.bucketName.ifBlank { "Other" })
            val folderUrl = "${base()}/$folder"
            runCatching {
                if (madeFolders.add(folder)) {
                    val mk = Request.Builder().url("$folderUrl/").method("MKCOL", null).auth().build()
                    client.newCall(mk).execute().close() // 201 created, 405 already exists — both fine
                }
                val size = repo.fileSize(item.uri)
                val body = object : RequestBody() {
                    override fun contentType() = item.mimeType.toMediaTypeOrNull()
                    override fun contentLength(): Long = size
                    override fun writeTo(sink: BufferedSink) {
                        val input = context.contentResolver.openInputStream(item.uri) ?: error("Can't read ${item.name}")
                        input.use { sink.writeAll(it.source()) }
                    }
                }
                val put = Request.Builder().url("$folderUrl/${Uri.encode(item.name)}").put(body).auth().build()
                client.newCall(put).execute().use { r ->
                    if (r.isSuccessful) {
                        ok++
                        synchronized(this@BackupManager) { doneFile.appendText(key(item) + "\n") }
                    } else {
                        lastError = if (r.code == 401) "Wrong username or password" else "Server answered ${r.code}"
                    }
                }
            }.onFailure { lastError = it.message ?: "Upload failed" }
            if (lastError == "Wrong username or password") {
                state.value = BackupProgress(false, i, todo.size, "Backup stopped", lastError)
                return@withContext
            }
        }
        state.value = BackupProgress(
            running = false, done = ok, total = todo.size,
            label = "Backed up $ok of ${todo.size}",
            error = if (ok < todo.size) lastError else null,
        )
    }

    fun forgetHistory() { doneFile.delete() }

    private fun loadDone(): HashSet<String> =
        runCatching { doneFile.readLines().toHashSet() }.getOrDefault(HashSet())

    private fun key(m: MediaItem) = "${m.id}:${m.size}"
}
