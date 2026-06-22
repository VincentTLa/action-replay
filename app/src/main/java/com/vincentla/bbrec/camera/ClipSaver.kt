package com.vincentla.bbrec.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Insert an in-app cache file into MediaStore.Movies/BBRec, returning the gallery Uri.
 *
 * ponytail: copy via streams. If the source files get large enough that copy
 * dominates, switch to `openFileDescriptor("rw")` and have the muxer write
 * directly into the MediaStore-owned fd.
 */
object ClipSaver {

    private const val SUBDIR = "Movies/BBRec"

    private val nameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun publish(context: Context, source: File, label: String): Uri? {
        val timestamp = nameFormat.format(Date())
        val displayName = "${label}_$timestamp.mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, SUBDIR)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        val uri = resolver.insert(collection, values) ?: return null

        resolver.openFileDescriptor(uri, "w")?.use { pfd: ParcelFileDescriptor ->
            source.inputStream().use { input ->
                java.io.FileOutputStream(pfd.fileDescriptor).use { out ->
                    input.copyTo(out)
                }
            }
        } ?: return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }

        source.delete()
        return uri
    }
}
