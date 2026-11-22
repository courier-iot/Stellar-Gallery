package com.courieriot.stellargallery.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * Makes share-ready copies of photos with location and camera details removed.
 * JPEG/PNG/WebP keep their pixels untouched (only the tags are cleared); other formats are re-saved as JPEG.
 * Videos are shared as-is.
 */
object MetadataStripper {
    private val tagsToRemove = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_SATELLITES,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_ARTIST, ExifInterface.TAG_IMAGE_UNIQUE_ID,
        ExifInterface.TAG_CAMERA_OWNER_NAME, ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_USER_COMMENT, ExifInterface.TAG_MAKER_NOTE,
    )

    private val exifWritable = setOf("image/jpeg", "image/png", "image/webp")

    fun shareDir(context: Context): File = File(context.cacheDir, "share").apply { mkdirs() }

    fun clearOld(context: Context) {
        val cutoff = System.currentTimeMillis() - 6 * 60 * 60 * 1000L
        shareDir(context).listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    /** Blocking. Returns share URIs, one per item. Falls back to the original URI if a copy fails. */
    fun prepare(context: Context, items: List<MediaItem>): List<Uri> {
        clearOld(context)
        val dir = shareDir(context)
        val authority = context.packageName + ".files"
        return items.map { item ->
            if (item.isVideo) return@map item.uri
            runCatching {
                val base = item.name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "photo" }
                val out: File
                if (item.mimeType.lowercase() in exifWritable) {
                    val ext = item.name.substringAfterLast('.', "jpg")
                    out = File(dir, "${base}_${item.id}.$ext")
                    context.contentResolver.openInputStream(item.uri)!!.use { input ->
                        FileOutputStream(out).use { input.copyTo(it) }
                    }
                    val exif = ExifInterface(out.absolutePath)
                    tagsToRemove.forEach { exif.setAttribute(it, null) }
                    exif.saveAttributes()
                } else {
                    out = File(dir, "${base}_${item.id}.jpg")
                    val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, item.uri))
                    FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                }
                FileProvider.getUriForFile(context, authority, out)
            }.getOrDefault(item.uri)
        }
    }
}
