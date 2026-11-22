package com.courieriot.stellargallery.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.BaseColumns
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

class MediaRepository(context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    private val filesUri: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val imagesUri: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val videoUri: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    val observeUri: Uri get() = filesUri

    suspend fun load(trashed: Boolean): List<MediaItem> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            BaseColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.IS_FAVORITE,
            MediaStore.MediaColumns.DATE_EXPIRES,
        )
        val typeCol = MediaStore.Files.FileColumns.MEDIA_TYPE
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "($typeCol=? OR $typeCol=?)")
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
                ),
            )
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, if (trashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_EXCLUDE)
        }

        val out = ArrayList<MediaItem>()
        resolver.query(filesUri, projection, args, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(BaseColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iMime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val iType = c.getColumnIndexOrThrow(typeCol)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iW = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val iH = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val iDur = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            val iBucket = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
            val iBucketName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val iRel = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val iFav = c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_FAVORITE)
            val iExp = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_EXPIRES)

            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val isVideo = c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val mime = c.getString(iMime) ?: if (isVideo) "video/*" else "image/*"
                val taken = c.getLong(iTaken).takeIf { it > 0 }
                    ?: (c.getLong(iMod).takeIf { it > 0 } ?: c.getLong(iAdded)) * 1000L
                out += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(if (isVideo) videoUri else imagesUri, id),
                    name = c.getString(iName) ?: "item_$id",
                    mimeType = mime,
                    dateTaken = taken,
                    size = c.getLong(iSize),
                    width = c.getInt(iW),
                    height = c.getInt(iH),
                    duration = if (isVideo) c.getLong(iDur) else 0L,
                    bucketId = c.getLong(iBucket),
                    bucketName = c.getString(iBucketName) ?: "Other",
                    relativePath = c.getString(iRel) ?: "",
                    isFavorite = c.getInt(iFav) == 1,
                    dateExpires = c.getLong(iExp),
                )
            }
        }
        out
    }

    // ---- System consent requests (the OS shows its own confirm dialog) ----

    fun trashRequest(uris: List<Uri>, trash: Boolean): IntentSender =
        MediaStore.createTrashRequest(resolver, uris, trash).intentSender

    fun favoriteRequest(uris: List<Uri>, favorite: Boolean): IntentSender =
        MediaStore.createFavoriteRequest(resolver, uris, favorite).intentSender

    fun deleteRequest(uris: List<Uri>): IntentSender =
        MediaStore.createDeleteRequest(resolver, uris).intentSender

    fun writeRequest(uris: List<Uri>): IntentSender =
        MediaStore.createWriteRequest(resolver, uris).intentSender

    // ---- File operations ----

    /** Moves items by changing their folder. Call after a write request was approved. */
    suspend fun moveItems(items: List<MediaItem>, relativePath: String): Int = withContext(Dispatchers.IO) {
        var ok = 0
        items.forEach { item ->
            runCatching {
                val v = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath) }
                if (resolver.update(item.uri, v, null, null) > 0) ok++
            }
        }
        ok
    }

    suspend fun copyItem(item: MediaItem, relativePath: String): Uri? = withContext(Dispatchers.IO) {
        resolver.openInputStream(item.uri)?.use { input ->
            insertStream(item.name, item.mimeType, relativePath, input)
        }
    }

    suspend fun saveBitmap(bitmap: Bitmap, name: String): Uri? = withContext(Dispatchers.IO) {
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        insertStream(name, "image/jpeg", EDITS_PATH, bytes.inputStream())
    }

    /** Writes a new image/video into MediaStore. Blocking — call from an IO thread. Does not close [input]. */
    fun insertStream(name: String, mime: String, relativePath: String, input: InputStream): Uri? {
        val isVideo = mime.startsWith("video/")
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        try {
            val out = resolver.openOutputStream(uri) ?: error("Couldn't open output")
            out.use { input.copyTo(it, 256 * 1024) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    fun fileSize(uri: Uri): Long =
        runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1L

    fun openInput(uri: Uri): InputStream? = resolver.openInputStream(uri)

    companion object {
        const val EDITS_PATH = "Pictures/Stellar Gallery/"
        const val RECEIVED_PATH = "Pictures/Stellar Gallery/Received/"
        const val RESTORED_PATH = "Pictures/Stellar Gallery/Restored/"
    }
}
