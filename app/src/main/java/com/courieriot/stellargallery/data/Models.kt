package com.courieriot.stellargallery.data

import android.net.Uri

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val dateTaken: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val duration: Long,
    val bucketId: Long,
    val bucketName: String,
    val relativePath: String,
    val isFavorite: Boolean,
    /** Seconds since epoch; only set for items in the bin. */
    val dateExpires: Long,
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
}

data class Album(
    val bucketId: Long,
    val name: String,
    val relativePath: String,
    val cover: MediaItem,
    val count: Int,
)

enum class SortOrder(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    NAME("Name (A–Z)"),
    SIZE("Largest first"),
}
