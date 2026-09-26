package com.bilal.simplevideoeditorapp.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.IOException
import kotlin.compareTo

/**
 * Parses text like "1:30" (1 minute 30 seconds) or a plain number of seconds like "45"
 * into milliseconds. Returns null if the text isn't a valid time.
 */
private fun parseTimeToMillis(input: String): Long? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null

    return try {
        if (trimmed.contains(":")) {
            val parts = trimmed.split(":")
            if (parts.size != 2) return null
            val minutes = parts[0].trim().toLong()
            val seconds = parts[1].trim().toLong()
            if (minutes < 0 || seconds !in 0..59) return null
            (minutes * 60 + seconds) * 1000
        } else {
            val seconds = trimmed.toLong()
            if (seconds < 0) return null
            seconds * 1000
        }
    } catch (e: NumberFormatException) {
        null
    }
}

/**
 * Works out which part of the video to KEEP, based on the ONE time he entered and
 * the toggle:
 *  - "From Start": everything from 0:00 up to the time he entered gets REMOVED.
 *     What's kept is: [enteredTime -> end of video].
 *  - "From End": everything from the time he entered to the end of the video gets
 *     REMOVED. What's kept is: [start of video -> enteredTime].
 *
 * Returns (startMs, endMs) of the segment to keep, or null if the time doesn't make sense.
 */
fun computeClipRangeMs(
    cutTimeInput: String,
    cutFromStart: Boolean,
    videoDurationMs: Long
): Pair<Long, Long>? {
    if (videoDurationMs <= 0) return null

    val cutTimeMs = parseTimeToMillis(cutTimeInput) ?: return null

    // The entered time has to fall strictly inside the video for the cut to make sense.
    if (cutTimeMs <= 0 || cutTimeMs >= videoDurationMs) return null

    return if (cutFromStart) {
        // Remove [0 -> cutTimeMs], keep [cutTimeMs -> end]
        cutTimeMs to videoDurationMs
    } else {
        // Remove [cutTimeMs -> end], keep [0 -> cutTimeMs]
        0L to cutTimeMs
    }
}

/**
 * Copies a finished video from Claude's private temp file into the phone's public
 * Videos/Movies folder, so it shows up in the Gallery/Photos app like any other video.
 * Deletes the temp file once the copy succeeds. Runs on a background thread and calls
 * back on the main thread.
 */
private fun saveVideoToMoviesFolder(
    context: Context,
    tempFile: File,
    displayName: String,
    onSuccess: (Uri) -> Unit,
    onError: (String) -> Unit
) {
    Thread {
        try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/Simple Video Editor"
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

            val videoUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create a new entry in the Videos folder.")

            resolver.openOutputStream(videoUri)?.use { outputStream ->
                tempFile.inputStream().use { inputStream -> inputStream.copyTo(outputStream) }
            } ?: throw IOException("Could not open the new video file to write to.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(videoUri, values, null, null)
            }

            tempFile.delete()

            Handler(Looper.getMainLooper()).post { onSuccess(videoUri) }
        } catch (e: Exception) {
            tempFile.delete()
            Handler(Looper.getMainLooper()).post {
                onError(e.message ?: "Something went wrong while saving the video.")
            }
        }
    }.start()
}

/**
 * Cuts the video down to [startMs, endMs] and ALWAYS writes the result to a brand-new
 * file in the phone's Videos folder - the original video at [sourceUri] is never
 * modified or deleted.
 */
@UnstableApi
@OptIn(UnstableApi::class)
fun exportTrimmedVideo(
    context: Context,
    sourceUri: Uri,
    startMs: Long,
    endMs: Long,
    onSuccess: (Uri) -> Unit,
    onError: (String) -> Unit
) {
    // Media3 Transformer needs to write to a plain file path, so we render into our
    // own private cache first, then copy the finished file into the public Videos folder.
    val fileName = "trimmed_${System.currentTimeMillis()}.mp4"
    val tempFile = File(context.cacheDir, fileName)

    val clippingConfiguration = MediaItem.ClippingConfiguration.Builder()
        .setStartPositionMs(startMs)
        .setEndPositionMs(endMs)
        .build()

    val mediaItem = MediaItem.Builder()
        .setUri(sourceUri)
        .setClippingConfiguration(clippingConfiguration)
        .build()

    val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()

    val transformer = Transformer.Builder(context)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                saveVideoToMoviesFolder(context, tempFile, fileName, onSuccess, onError)
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                onError(exportException.message ?: "Something went wrong while cutting the video.")
            }
        })
        .build()

    transformer.start(editedMediaItem, tempFile.absolutePath)
}

/**
 * Joins two videos back-to-back (first, then second) into one brand-new file in the
 * phone's Videos folder. Neither of the two original videos is modified or deleted.
 */
@UnstableApi
@OptIn(UnstableApi::class)
fun mergeVideos(
    context: Context,
    firstUri: Uri,
    secondUri: Uri,
    onSuccess: (Uri) -> Unit,
    onError: (String) -> Unit
) {
    val fileName = "merged_${System.currentTimeMillis()}.mp4"
    val tempFile = File(context.cacheDir, fileName)

    val firstItem = EditedMediaItem.Builder(MediaItem.fromUri(firstUri)).build()
    val secondItem = EditedMediaItem.Builder(MediaItem.fromUri(secondUri)).build()

    // Playing the two items one after another, in one output file.
    val sequence = EditedMediaItemSequence(listOf(firstItem, secondItem))
    val composition = Composition.Builder(sequence).build()

    val transformer = Transformer.Builder(context)
        .addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                saveVideoToMoviesFolder(context, tempFile, fileName, onSuccess, onError)
            }

            override fun onError(
                composition: Composition,
                exportResult: ExportResult,
                exportException: ExportException
            ) {
                onError(exportException.message ?: "Something went wrong while joining the videos.")
            }
        })
        .build()

    transformer.start(composition, tempFile.absolutePath)
}