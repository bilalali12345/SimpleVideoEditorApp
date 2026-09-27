package com.bilal.simplevideoeditorapp

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.bilal.simplevideoeditorapp.ui.theme.SimpleVideoEditorAppTheme
import com.bilal.simplevideoeditorapp.util.TrimMode
import com.bilal.simplevideoeditorapp.util.computeClipRangeMs
import com.bilal.simplevideoeditorapp.util.computeMiddleCutBoundsMs
import com.bilal.simplevideoeditorapp.util.exportDenoisedVideo
import com.bilal.simplevideoeditorapp.util.exportMiddleTrimmedVideo
import com.bilal.simplevideoeditorapp.util.exportTrimmedVideo
import com.bilal.simplevideoeditorapp.util.mergeVideos



class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SimpleVideoEditorAppTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    VideoEditorApp(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

/**
 * Every "screen" the app can be on, with just the data that screen needs.
 * Still no navigation library / view models - just one variable that switches
 * between a handful of simple states.
 */
sealed class AppScreen {
    object Home : AppScreen()
    data class Trim(val uri: Uri) : AppScreen()
    object PickFirstForMerge : AppScreen()
    data class PickSecondForMerge(val firstUri: Uri) : AppScreen()
    data class ReadyToMerge(val firstUri: Uri, val secondUri: Uri) : AppScreen()
    data class Denoise(val uri: Uri) : AppScreen()
}

@Composable
fun VideoEditorApp(modifier: Modifier = Modifier) {
    var screen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }

    when (val current = screen) {
        is AppScreen.Home -> HomeScreen(
            modifier = modifier,
            onPickedVideoToTrim = { uri -> screen = AppScreen.Trim(uri) },
            onStartMerge = { screen = AppScreen.PickFirstForMerge },
            onPickedVideoToDenoise = { uri -> screen = AppScreen.Denoise(uri) }
        )

        is AppScreen.Trim -> VideoTrimScreen(
            modifier = modifier,
            videoUri = current.uri,
            onPickDifferentVideo = { screen = AppScreen.Home },
            // When he chooses to preview the freshly cut video, we just swap
            // which file is being shown - the original file on disk is never touched.
            onSwitchToVideo = { newUri -> screen = AppScreen.Trim(newUri) }
        )

        AppScreen.PickFirstForMerge -> VideoPickerScreen(
            modifier = modifier,
            title = "Merge Videos",
            subtitle = "Choose the FIRST video (it will play first)",
            onVideoPicked = { uri -> screen = AppScreen.PickSecondForMerge(uri) }
        )

        is AppScreen.PickSecondForMerge -> VideoPickerScreen(
            modifier = modifier,
            title = "Merge Videos",
            subtitle = "Now choose the SECOND video (it will play right after)",
            onVideoPicked = { uri -> screen = AppScreen.ReadyToMerge(current.firstUri, uri) }
        )

        is AppScreen.ReadyToMerge -> MergeScreen(
            modifier = modifier,
            firstUri = current.firstUri,
            secondUri = current.secondUri,
            onCancel = { screen = AppScreen.Home },
            // Straight into the trim screen with the merged result, in case he
            // wants to tidy up the join afterwards.
            onMergedVideoReady = { mergedUri -> screen = AppScreen.Trim(mergedUri) }
        )

        is AppScreen.Denoise -> DenoiseScreen(
            modifier = modifier,
            videoUri = current.uri,
            onCancel = { screen = AppScreen.Home },
            // Same idea as merge: land on the trim screen with the cleaned result,
            // in case he wants to also cut it down afterwards.
            onCleanedVideoReady = { cleanedUri -> screen = AppScreen.Trim(cleanedUri) }
        )
    }
}

// ---------------------------------------------------------------------------------
// HOME: choose what he wants to do
// ---------------------------------------------------------------------------------
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onPickedVideoToTrim: (Uri) -> Unit,
    onStartMerge: () -> Unit,
    onPickedVideoToDenoise: (Uri) -> Unit
) {
    val trimPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onPickedVideoToTrim(it) }
    }

    val denoisePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onPickedVideoToDenoise(it) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.VideoLibrary,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Video Editor",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "What would you like to do?",
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(40.dp))

        Button(
            onClick = { trimPickerLauncher.launch("video/*") },
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(imageVector = Icons.Default.ContentCut, contentDescription = null)
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = "Trim a Video", fontSize = 20.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = onStartMerge,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(imageVector = Icons.Default.VideoLibrary, contentDescription = null)
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = "Merge Two Videos", fontSize = 20.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedButton(
            onClick = { denoisePickerLauncher.launch("video/*") },
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            // Reusing an icon we already know compiles, rather than guessing at a
            // new one and risking another "unresolved reference" round-trip.
            Icon(imageVector = Icons.Default.VideoLibrary, contentDescription = null)
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = "Remove Background Noise", fontSize = 20.sp)
        }
    }
}

// ---------------------------------------------------------------------------------
// Reusable: Pick a single video (used for trim-from-Trim-screen re-pick, and both
// steps of the merge flow)
// ---------------------------------------------------------------------------------
@Composable
fun VideoPickerScreen(
    modifier: Modifier = Modifier,
    title: String = "Video Trimmer",
    subtitle: String = "Choose a video to get started",
    buttonText: String = "Select Video",
    onVideoPicked: (Uri) -> Unit
) {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onVideoPicked(it) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.VideoLibrary,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = title,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = subtitle,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(40.dp))

        // Big, easy-to-tap button
        Button(
            onClick = { launcher.launch("video/*") },
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(imageVector = Icons.Default.VideoLibrary, contentDescription = null)
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = buttonText, fontSize = 20.sp)
        }
    }
}

// ---------------------------------------------------------------------------------
// MERGE: both videos picked, ready to join them
// ---------------------------------------------------------------------------------
@Composable
fun MergeScreen(
    modifier: Modifier = Modifier,
    firstUri: Uri,
    secondUri: Uri,
    onCancel: () -> Unit,
    onMergedVideoReady: (Uri) -> Unit
) {
    val context = LocalContext.current
    var isProcessing by remember { mutableStateOf(false) }
    var mergedVideoUri by remember { mutableStateOf<Uri?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.VideoLibrary,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Ready to Merge",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Both videos are selected. The first one will play, then the second.",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(40.dp))

        Button(
            onClick = {
                isProcessing = true
                mergeVideos(
                    context = context,
                    firstUri = firstUri,
                    secondUri = secondUri,
                    onSuccess = { newUri ->
                        isProcessing = false
                        mergedVideoUri = newUri
                    },
                    onError = { message ->
                        isProcessing = false
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                )
            },
            enabled = !isProcessing,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(text = "Merge Videos", fontSize = 20.sp)
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = onCancel,
            enabled = !isProcessing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Cancel", fontSize = 16.sp)
        }
    }

    if (isProcessing) {
        AlertDialog(
            onDismissRequest = { /* not dismissable while working */ },
            confirmButton = {},
            title = { Text("Merging Your Videos...") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("This may take a moment. Please don't close the app.")
                }
            }
        )
    }

    // The merge is done. Both ORIGINAL videos are untouched - this is a brand-new file.
    if (mergedVideoUri != null) {
        AlertDialog(
            onDismissRequest = { mergedVideoUri = null },
            title = { Text(text = "All Done!", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Your merged video has been saved as a new file. " +
                            "Both original videos were not changed and are still safe."
                )
            },
            confirmButton = {
                Button(onClick = {
                    val newUri = mergedVideoUri!!
                    mergedVideoUri = null
                    onMergedVideoReady(newUri)
                }) {
                    Text("Play Merged Video")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    mergedVideoUri = null
                    onCancel()
                }) {
                    Text("Done")
                }
            }
        )
    }
}

// ---------------------------------------------------------------------------------
// DENOISE: one video picked, ready to clean up its audio
// ---------------------------------------------------------------------------------
@Composable
fun DenoiseScreen(
    modifier: Modifier = Modifier,
    videoUri: Uri,
    onCancel: () -> Unit,
    onCleanedVideoReady: (Uri) -> Unit
) {
    val context = LocalContext.current
    var isProcessing by remember { mutableStateOf(false) }
    var cleanedVideoUri by remember { mutableStateOf<Uri?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.VideoLibrary,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Ready to Clean Up the Audio",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "This removes steady background noise (fans, wind, hum) from the video's sound. " +
                    "It won't work miracles on loud noise or other voices.",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(40.dp))

        Button(
            onClick = {
                isProcessing = true
                exportDenoisedVideo(
                    context = context,
                    sourceUri = videoUri,
                    onSuccess = { newUri ->
                        isProcessing = false
                        cleanedVideoUri = newUri
                    },
                    onError = { message ->
                        isProcessing = false
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                )
            },
            enabled = !isProcessing,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(text = "Remove Background Noise", fontSize = 20.sp)
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = onCancel,
            enabled = !isProcessing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "Cancel", fontSize = 16.sp)
        }
    }

    if (isProcessing) {
        AlertDialog(
            onDismissRequest = { /* not dismissable while working */ },
            confirmButton = {},
            title = { Text("Cleaning Up the Audio...") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("This can take a little while on longer videos. Please don't close the app.")
                }
            }
        )
    }

    // The clean-up is done. The ORIGINAL video is untouched - this is a brand-new file.
    if (cleanedVideoUri != null) {
        AlertDialog(
            onDismissRequest = { cleanedVideoUri = null },
            title = { Text(text = "All Done!", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "A cleaned-up copy has been saved as a new file. " +
                            "Your original video was not changed and is still safe."
                )
            },
            confirmButton = {
                Button(onClick = {
                    val newUri = cleanedVideoUri!!
                    cleanedVideoUri = null
                    onCleanedVideoReady(newUri)
                }) {
                    Text("Play Cleaned Video")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    cleanedVideoUri = null
                    onCancel()
                }) {
                    Text("Done")
                }
            }
        )
    }
}

// ---------------------------------------------------------------------------------
// SCREEN 2: Play + Trim
// ---------------------------------------------------------------------------------
@Composable
fun VideoTrimScreen(
    modifier: Modifier = Modifier,
    videoUri: Uri,
    onPickDifferentVideo: () -> Unit,
    onSwitchToVideo: (Uri) -> Unit
) {
    val context = LocalContext.current

    var showTrimDialog by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var isProcessing by remember { mutableStateOf(false) }
    var trimmedVideoUri by remember { mutableStateOf<Uri?>(null) }

    // Keep the player alive across recompositions, release it when we leave the screen
    val exoPlayer = remember(videoUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Trim Your Video",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // Video preview
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true // shows built-in seek bar too
                }
            },
            update = { view -> view.player = exoPlayer },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(androidx.compose.ui.graphics.Color.Black)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Simple, large play/pause button (in addition to the player's own controls)
        Button(
            onClick = {
                if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                isPlaying = !isPlaying
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = if (isPlaying) "Pause" else "Play", fontSize = 18.sp)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Big "Trim" button - opens the popup
        Button(
            onClick = { showTrimDialog = true },
            enabled = !isProcessing,
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary
            )
        ) {
            Icon(imageVector = Icons.Default.ContentCut, contentDescription = null)
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = "Cut Video", fontSize = 20.sp)
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = onPickDifferentVideo,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isProcessing
        ) {
            Text(text = "Choose a Different Video", fontSize = 16.sp)
        }
    }

    if (showTrimDialog) {
        TrimPopup(
            onDismiss = { showTrimDialog = false },
            onConfirm = { mode, primaryTime, secondaryTime ->
                showTrimDialog = false
                val durationMs = exoPlayer.duration

                when (mode) {
                    TrimMode.FROM_START, TrimMode.FROM_END -> {
                        val range = computeClipRangeMs(
                            cutTimeInput = primaryTime,
                            cutFromStart = mode == TrimMode.FROM_START,
                            videoDurationMs = durationMs
                        )
                        if (range == null) {
                            Toast.makeText(
                                context,
                                "Please double check the time you entered.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            isProcessing = true
                            exportTrimmedVideo(
                                context = context,
                                sourceUri = videoUri,
                                startMs = range.first,
                                endMs = range.second,
                                onSuccess = { newUri ->
                                    isProcessing = false
                                    trimmedVideoUri = newUri
                                },
                                onError = { message ->
                                    isProcessing = false
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                    }

                    TrimMode.FROM_MIDDLE -> {
                        val bounds = computeMiddleCutBoundsMs(
                            removeStartInput = primaryTime,
                            removeEndInput = secondaryTime,
                            videoDurationMs = durationMs
                        )
                        if (bounds == null) {
                            Toast.makeText(
                                context,
                                "Please double check the two times you entered.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            isProcessing = true
                            exportMiddleTrimmedVideo(
                                context = context,
                                sourceUri = videoUri,
                                removeStartMs = bounds.first,
                                removeEndMs = bounds.second,
                                videoDurationMs = durationMs,
                                onSuccess = { newUri ->
                                    isProcessing = false
                                    trimmedVideoUri = newUri
                                },
                                onError = { message ->
                                    isProcessing = false
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                }
                            )
                        }
                    }
                }
            }
        )
    }

    // Simple, non-dismissable "working on it" indicator while the cut is being made.
    if (isProcessing) {
        AlertDialog(
            onDismissRequest = { /* not dismissable while working */ },
            confirmButton = {},
            title = { Text("Cutting Your Video...") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("This may take a moment. Please don't close the app.")
                }
            }
        )
    }

    // The cut is done. The ORIGINAL video is untouched - this is always a brand-new file.
    if (trimmedVideoUri != null) {
        AlertDialog(
            onDismissRequest = { trimmedVideoUri = null },
            title = { Text(text = "All Done!", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Your trimmed video has been saved as a new file. " +
                            "Your original video was not changed and is still safe."
                )
            },
            confirmButton = {
                Button(onClick = {
                    val newUri = trimmedVideoUri!!
                    trimmedVideoUri = null
                    onSwitchToVideo(newUri)
                }) {
                    Text("Play New Video")
                }
            },
            dismissButton = {
                TextButton(onClick = { trimmedVideoUri = null }) {
                    Text("Done")
                }
            }
        )
    }
}


// ---------------------------------------------------------------------------------
// POPUP: Enter the cut point + choose which side gets removed
// ---------------------------------------------------------------------------------
@Composable
fun TrimPopup(
    onDismiss: () -> Unit,
    onConfirm: (mode: TrimMode, primaryTime: String, secondaryTime: String) -> Unit
) {
    var trimMode by remember { mutableStateOf(TrimMode.FROM_START) }
    var primaryTime by remember { mutableStateOf("") }   // start-of-video field, or single cut point
    var secondaryTime by remember { mutableStateOf("") } // only used in FROM_MIDDLE mode

    val isReadyToConfirm = when (trimMode) {
        TrimMode.FROM_START, TrimMode.FROM_END -> primaryTime.isNotBlank()
        TrimMode.FROM_MIDDLE -> primaryTime.isNotBlank() && secondaryTime.isNotBlank()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Cut Video", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    text = "What part do you want to remove?",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Three big, stacked options - easier to tap correctly than a row of three.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToggleOptionButton(
                        text = "The Beginning",
                        selected = trimMode == TrimMode.FROM_START,
                        onClick = { trimMode = TrimMode.FROM_START },
                        modifier = Modifier.fillMaxWidth()
                    )
                    ToggleOptionButton(
                        text = "The Ending",
                        selected = trimMode == TrimMode.FROM_END,
                        onClick = { trimMode = TrimMode.FROM_END },
                        modifier = Modifier.fillMaxWidth()
                    )
                    ToggleOptionButton(
                        text = "A Part in the Middle",
                        selected = trimMode == TrimMode.FROM_MIDDLE,
                        onClick = { trimMode = TrimMode.FROM_MIDDLE },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (trimMode) {
                    TrimMode.FROM_START -> {
                        OutlinedTextField(
                            value = primaryTime,
                            onValueChange = { primaryTime = it },
                            label = { Text("Cut off the beginning, up to:") },
                            placeholder = { Text("0:10") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Everything from 0:00 to this time will be removed. The rest is kept.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    TrimMode.FROM_END -> {
                        OutlinedTextField(
                            value = primaryTime,
                            onValueChange = { primaryTime = it },
                            label = { Text("Cut off the ending, starting at:") },
                            placeholder = { Text("0:50") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Everything from this time to the end will be removed. The rest is kept.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    TrimMode.FROM_MIDDLE -> {
                        OutlinedTextField(
                            value = primaryTime,
                            onValueChange = { primaryTime = it },
                            label = { Text("Start of the part to remove:") },
                            placeholder = { Text("0:20") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = secondaryTime,
                            onValueChange = { secondaryTime = it },
                            label = { Text("End of the part to remove:") },
                            placeholder = { Text("0:35") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "The video between these two times will be removed, and the two remaining pieces joined together.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Use minutes:seconds, like 1:30, or just seconds, like 45.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(trimMode, primaryTime, secondaryTime) },
                enabled = isReadyToConfirm
            ) {
                Text("Cut", fontSize = 16.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", fontSize = 16.sp)
            }
        }
    )
}

@Composable
fun ToggleOptionButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier.height(48.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(text = text, fontSize = 15.sp)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(48.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(text = text, fontSize = 15.sp)
        }
    }
}