package com.example.mediastudio.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.example.mediastudio.data.api.ArchiveMetadataModule
import com.example.mediastudio.data.api.VideoQualityOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

data class SrtCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class PlayerSubtitleOption(
    val fileName: String,
    val language: String,
    val downloadUrl: String,
    val isGzipped: Boolean = false
)

@Composable
fun PlayerScreen(
    identifier: String,
    movieTitle: String,
    movieYear: String = "",
    directVideoUrl: String = "",
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val fallbackVideoUrl = "https://archive.org/download/$identifier/$identifier.mp4"

    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var showControls by remember { mutableStateOf(false) }
    var controlsInteractionTick by remember { mutableIntStateOf(0) }

    // Brzi statusni HUD (za -15s / +15s / kontinuirani seek / poruku za Back) bez otvaranja kontrola
    var quickHudMessage by remember { mutableStateOf("") }
    var quickHudTick by remember { mutableIntStateOf(0) }
    var isContinuousSeeking by remember { mutableStateOf(false) }

    // Stanja za kvalitetu videa
    var availableQualities by remember { mutableStateOf<List<VideoQualityOption>>(emptyList()) }
    var selectedQuality by remember { mutableStateOf<VideoQualityOption?>(null) }
    var showQualityDialog by remember { mutableStateOf(false) }

    // Stanja za titlove (.srt)
    var showSubtitleDialog by remember { mutableStateOf(false) }
    var subtitleList by remember { mutableStateOf<List<PlayerSubtitleOption>>(emptyList()) }
    var isLoadingSubtitles by remember { mutableStateOf(false) }
    var activeSubtitleName by remember { mutableStateOf<String?>(null) }
    var parsedCues by remember { mutableStateOf<List<SrtCue>>(emptyList()) }
    var currentSubtitleText by remember { mutableStateOf("") }
    var subtitleOffsetMs by remember { mutableLongStateOf(0L) }

    val rootFocusRequester = remember { FocusRequester() }
    val centerPauseFocusRequester = remember { FocusRequester() }

    // Optimizirani buffer (brzi start nakon 1.5s + duboki buffer od 50s za spore Archive.org servere)
    val exoPlayer = remember {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000, // minBufferMs
                50_000, // maxBufferMs
                1_500,  // bufferForPlaybackMs (kreće odmah nakon 1.5s)
                3_000   // bufferForPlaybackAfterRebufferMs
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) MediaStudio/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
            .build()
    }

    // Tipka BACK: prvi pritisak pauzira video, drugi pritisak izlazi iz playera
    BackHandler {
        when {
            showQualityDialog -> showQualityDialog = false
            showSubtitleDialog -> showSubtitleDialog = false
            exoPlayer.isPlaying -> {
                exoPlayer.pause()
                isPlaying = false
                quickHudMessage = "⏸ Pauzirano (pritisni Back ponovno za izlaz)"
                quickHudTick++
            }
            else -> {
                exoPlayer.stop()
                onNavigateBack()
            }
        }
    }

    LaunchedEffect(identifier, directVideoUrl) {
        if (directVideoUrl.isNotEmpty()) {
            availableQualities = emptyList()
            selectedQuality = null
            exoPlayer.setMediaItem(MediaItem.fromUri(directVideoUrl), 0L)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        } else {
            val qualities = ArchiveMetadataModule.fetchAvailableQualities(identifier)
            availableQualities = qualities
            val bestQuality = qualities.firstOrNull()
            selectedQuality = bestQuality
            val startUrl = bestQuality?.url ?: fallbackVideoUrl
            exoPlayer.setMediaItem(MediaItem.fromUri(startUrl), 0L)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    // Automatsko skrivanje quick HUD poruke nakon 2 sekunde
    LaunchedEffect(quickHudTick) {
        if (quickHudMessage.isNotEmpty()) {
            delay(2000L)
            if (!isContinuousSeeking) {
                quickHudMessage = ""
            }
        }
    }

    // Automatsko skrivanje kontrola playera nakon 5 sekundi neaktivnosti
    LaunchedEffect(showControls, controlsInteractionTick, isPlaying, showQualityDialog, showSubtitleDialog) {
        if (showControls && isPlaying && !showQualityDialog && !showSubtitleDialog) {
            delay(5000L)
            showControls = false
            try {
                rootFocusRequester.requestFocus()
            } catch (_: Exception) {
            }
        } else if (!showControls) {
            try {
                rootFocusRequester.requestFocus()
            } catch (_: Exception) {
            }
        }
    }

    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    duration = exoPlayer.duration.coerceAtLeast(0L)
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(exoPlayer, parsedCues, subtitleOffsetMs) {
        while (true) {
            val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
            currentPosition = pos
            if (exoPlayer.duration > 0) duration = exoPlayer.duration

            if (parsedCues.isNotEmpty()) {
                val adjustedPos = pos - subtitleOffsetMs
                val activeCue = parsedCues.firstOrNull { adjustedPos in it.startMs..it.endMs }
                currentSubtitleText = activeCue?.text ?: ""
            } else {
                currentSubtitleText = ""
            }
            delay(200L)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                val native = keyEvent.nativeKeyEvent
                val keyCode = native.keyCode

                if (showQualityDialog || showSubtitleDialog) {
                    return@onPreviewKeyEvent false
                }

                // 1. SREDINA KRUGA (DPAD_CENTER / ENTER):
                // - Prvi pritisak (kad su kontrole skrivene) -> prikaže kontrole playera
                // - Sljedeći pritisak (kad su kontrole vidljive) -> pauza / nastavak
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                        if (!showControls) {
                            showControls = true
                            controlsInteractionTick++
                            return@onPreviewKeyEvent true
                        }
                    }
                    if (showControls) {
                        controlsInteractionTick++
                    }
                    return@onPreviewKeyEvent false
                }

                // 2. LIJEVO / DESNO NA KRUGU DOK SU KONTROLE SKRIVENE:
                // - Kratki pritisak (repeatCount == 0): skok 15 sekundi (-15s / +15s) bez kontrola
                // - Zadržavanje (repeatCount > 0): kontinuirani seek videa bez prikaza kontrola
                if (!showControls && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    val isForward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    if (native.action == KeyEvent.ACTION_DOWN) {
                        val stepMs = if (native.repeatCount == 0) 15_000L else 12_000L
                        isContinuousSeeking = native.repeatCount > 0
                        val targetPos = if (isForward) {
                            (exoPlayer.currentPosition + stepMs).coerceAtMost(duration.coerceAtLeast(0L))
                        } else {
                            (exoPlayer.currentPosition - stepMs).coerceAtLeast(0L)
                        }
                        exoPlayer.seekTo(targetPos)
                        currentPosition = targetPos
                        quickHudMessage = if (isContinuousSeeking) {
                            "${if (isForward) "⏩" else "⏪"} ${formatTime(targetPos)} / ${formatTime(duration)}"
                        } else {
                            "${if (isForward) "+15s ↻" else "↺ -15s"}  (${formatTime(targetPos)})"
                        }
                        quickHudTick++
                        return@onPreviewKeyEvent true
                    } else if (native.action == KeyEvent.ACTION_UP) {
                        isContinuousSeeking = false
                        quickHudTick++
                        return@onPreviewKeyEvent true
                    }
                }

                // 3. GORE / DOLJE DOK SU KONTROLE SKRIVENE -> prikaže kontrole
                if (!showControls && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                    if (native.action == KeyEvent.ACTION_DOWN) {
                        showControls = true
                        controlsInteractionTick++
                        return@onPreviewKeyEvent true
                    }
                }

                if (showControls && native.action == KeyEvent.ACTION_DOWN) {
                    controlsInteractionTick++
                }
                false
            }
            .clickable {
                if (!showControls) {
                    showControls = true
                    controlsInteractionTick++
                } else {
                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                    controlsInteractionTick++
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isBuffering) {
            CircularProgressIndicator(
                color = Color.Red,
                modifier = Modifier.align(Alignment.Center).size(52.dp)
            )
        }

        // Brzi HUD za -15s / +15s / kontinuirani seek / Pauzirano (bez otvaranja kontrola)
        if (quickHudMessage.isNotEmpty() && !showControls) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 26.dp)
                    .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(10.dp))
                    .border(1.5.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(
                    text = quickHudMessage,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Prikaz aktivnog titla na dnu ekrana
        if (currentSubtitleText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (showControls) 95.dp else 36.dp, start = 32.dp, end = 32.dp)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = currentSubtitleText,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Kontrole playera s jasnim bijelim fokusnim okvirom na svakom polju
        if (showControls) {
            LaunchedEffect(Unit) {
                try {
                    centerPauseFocusRequester.requestFocus()
                } catch (_: Exception) {
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            ) {
                // Gornja traka: Naziv + gumbi s fokusnim okvirom
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        PlayerFocusableIconButton(
                            onClick = {
                                exoPlayer.stop()
                                onNavigateBack()
                            },
                            contentDescription = "Natrag"
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Natrag", tint = Color.White)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = movieTitle,
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (parsedCues.isNotEmpty()) {
                            PlayerChipButton(
                                label = "-0.5s",
                                onClick = {
                                    subtitleOffsetMs -= 500L
                                    controlsInteractionTick++
                                }
                            )
                            PlayerChipButton(
                                label = "+0.5s",
                                onClick = {
                                    subtitleOffsetMs += 500L
                                    controlsInteractionTick++
                                }
                            )
                        }

                        PlayerChipButton(
                            label = "↺ Početak",
                            onClick = {
                                exoPlayer.seekTo(0L)
                                exoPlayer.play()
                                controlsInteractionTick++
                            }
                        )

                        PlayerChipButton(
                            label = "■ Stop",
                            onClick = {
                                exoPlayer.pause()
                                exoPlayer.seekTo(0L)
                                controlsInteractionTick++
                            }
                        )

                        if (availableQualities.isNotEmpty()) {
                            PlayerChipButton(
                                label = selectedQuality?.label?.substringBefore(" ") ?: "HD",
                                onClick = { showQualityDialog = true }
                            )
                        }

                        PlayerChipButton(
                            label = if (activeSubtitleName != null) "CC ✓" else "CC Titlovi",
                            isHighlighted = activeSubtitleName != null,
                            onClick = {
                                showSubtitleDialog = true
                                if (subtitleList.isEmpty()) {
                                    isLoadingSubtitles = true
                                    coroutineScope.launch {
                                        subtitleList = searchAllSubtitles(identifier, movieTitle, movieYear)
                                        isLoadingSubtitles = false
                                    }
                                }
                            }
                        )
                    }
                }

                // Središnje kontrole s bijelim fokusnim okvirom
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlayerFocusableIconButton(
                        onClick = {
                            exoPlayer.seekTo(0L)
                            controlsInteractionTick++
                        },
                        contentDescription = "Početak",
                        sizeDp = 52
                    ) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Početak", tint = Color.White, modifier = Modifier.size(32.dp))
                    }

                    PlayerFocusableIconButton(
                        onClick = {
                            exoPlayer.seekTo((currentPosition - 15000L).coerceAtLeast(0L))
                            controlsInteractionTick++
                        },
                        contentDescription = "Natrag 15s",
                        sizeDp = 52
                    ) {
                        Icon(Icons.Default.Replay10, contentDescription = "Natrag 15s", tint = Color.White, modifier = Modifier.size(32.dp))
                    }

                    PlayerFocusableIconButton(
                        onClick = {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            controlsInteractionTick++
                        },
                        contentDescription = "Pauza / Nastavi",
                        sizeDp = 64,
                        isPrimary = true,
                        modifier = Modifier.focusRequester(centerPauseFocusRequester)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.Black,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    PlayerFocusableIconButton(
                        onClick = {
                            exoPlayer.seekTo((currentPosition + 15000L).coerceAtMost(duration))
                            controlsInteractionTick++
                        },
                        contentDescription = "Naprijed 15s",
                        sizeDp = 52
                    ) {
                        Icon(Icons.Default.Forward10, contentDescription = "Naprijed 15s", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }

                // Donji progress bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 24.dp, vertical = 14.dp)
                ) {
                    Slider(
                        value = if (duration > 0) currentPosition.toFloat() else 0f,
                        onValueChange = {
                            exoPlayer.seekTo(it.toLong())
                            controlsInteractionTick++
                        },
                        valueRange = 0f..(if (duration > 0) duration.toFloat() else 1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.Red,
                            inactiveTrackColor = Color.Gray
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val syncText = if (subtitleOffsetMs != 0L) "  |  Titl pomak: ${subtitleOffsetMs / 1000.0}s" else ""
                        Text(
                            text = "${formatTime(currentPosition)} / ${formatTime(duration)}$syncText",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Dijalog za izbor rezolucije
        if (showQualityDialog) {
            AlertDialog(
                onDismissRequest = { showQualityDialog = false },
                containerColor = Color(0xFF1A1A1A),
                title = { Text("Odaberi rezoluciju videa", color = Color.White) },
                text = {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(availableQualities) { quality ->
                            DialogFocusableRow(
                                label = quality.label,
                                isSelected = quality == selectedQuality,
                                onClick = {
                                    val savedPos = exoPlayer.currentPosition
                                    selectedQuality = quality
                                    exoPlayer.setMediaItem(MediaItem.fromUri(quality.url), savedPos)
                                    exoPlayer.prepare()
                                    exoPlayer.playWhenReady = true
                                    showQualityDialog = false
                                }
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showQualityDialog = false }) {
                        Text("Zatvori", color = Color.Red)
                    }
                }
            )
        }

        // Dijalog za izbor .srt titlova
        if (showSubtitleDialog) {
            AlertDialog(
                onDismissRequest = { showSubtitleDialog = false },
                containerColor = Color(0xFF1A1A1A),
                title = { Text("Odaberi titlove (.srt)", color = Color.White) },
                text = {
                    if (isLoadingSubtitles) {
                        Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color.Red)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 300.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                DialogFocusableRow(
                                    label = "Isključi titlove",
                                    isSelected = activeSubtitleName == null,
                                    onClick = {
                                        activeSubtitleName = null
                                        parsedCues = emptyList()
                                        currentSubtitleText = ""
                                        subtitleOffsetMs = 0L
                                        showSubtitleDialog = false
                                    }
                                )
                            }
                            if (subtitleList.isEmpty()) {
                                item {
                                    Text("Nema pronađenih titlova za ovaj film.", color = Color.Gray, fontSize = 14.sp)
                                }
                            } else {
                                items(subtitleList) { sub ->
                                    DialogFocusableRow(
                                        label = "[${sub.language}] ${sub.fileName}",
                                        isSelected = activeSubtitleName == sub.fileName,
                                        onClick = {
                                            activeSubtitleName = sub.fileName
                                            subtitleOffsetMs = 0L
                                            showSubtitleDialog = false
                                            coroutineScope.launch {
                                                val srtContent = downloadSubtitleText(sub)
                                                parsedCues = parseSrtContent(srtContent)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSubtitleDialog = false }) {
                        Text("Zatvori", color = Color.Red)
                    }
                }
            )
        }
    }
}

@Composable
private fun PlayerChipButton(
    label: String,
    isHighlighted: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .background(
                color = when {
                    isFocused -> Color.Red
                    isHighlighted -> Color(0xFFB71C1C)
                    else -> Color.Black.copy(alpha = 0.7f)
                },
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = if (isFocused) 3.dp else 1.5.dp,
                color = if (isFocused) Color.White else Color.Gray,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun PlayerFocusableIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    sizeDp: Int = 46,
    isPrimary: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .background(
                color = when {
                    isPrimary -> Color.White
                    isFocused -> Color.Red
                    else -> Color.Black.copy(alpha = 0.55f)
                },
                shape = CircleShape
            )
            .border(
                width = if (isFocused) 3.5.dp else 1.5.dp,
                color = if (isFocused) Color.White else Color.Gray.copy(alpha = 0.7f),
                shape = CircleShape
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun DialogFocusableRow(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = when {
                    isFocused -> Color.Red
                    isSelected -> Color(0xFF2E2E2E)
                    else -> Color(0xFF222222)
                },
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = if (isFocused) 2.5.dp else 1.dp,
                color = if (isFocused) Color.White else Color.DarkGray,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Normal,
            fontSize = 14.sp
        )
    }
}

// Samostalni dohvat titlova iz Archive.org metapodataka i OpenSubtitles baze
private suspend fun searchAllSubtitles(
    identifier: String,
    movieTitle: String,
    movieYear: String
): List<PlayerSubtitleOption> = withContext(Dispatchers.IO) {
    val results = mutableListOf<PlayerSubtitleOption>()

    // 1. Provjeri postoje li ugrađeni .srt titlovi u samoj Archive.org mapi filma
    try {
        val metaJson = JSONObject(URL("https://archive.org/metadata/$identifier").readText())
        val files = metaJson.optJSONArray("files")
        if (files != null) {
            for (i in 0 until files.length()) {
                val f = files.optJSONObject(i) ?: continue
                val name = f.optString("name")
                if (name.endsWith(".srt", ignoreCase = true)) {
                    val encoded = name.split("/").joinToString("/") { part ->
                        URLEncoder.encode(part, "UTF-8").replace("+", "%20")
                    }
                    results.add(
                        PlayerSubtitleOption(
                            fileName = name.substringAfterLast("/"),
                            language = "Archive",
                            downloadUrl = "https://archive.org/download/$identifier/$encoded",
                            isGzipped = false
                        )
                    )
                }
            }
        }
    } catch (_: Exception) {
    }

    // 2. Pretraži OpenSubtitles REST bazu (HR, SR, BS, EN)
    try {
        val cleanTitle = movieTitle
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("\\b(19\\d{2}|20\\d{2})\\b"), "")
            .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()

        val encodedQuery = URLEncoder.encode(cleanTitle, "UTF-8")
        val langs = listOf("hrv", "srp", "bos", "eng")
        for (lang in langs) {
            try {
                val apiUrl = "https://rest.opensubtitles.org/search/query-$encodedQuery/sublanguageid-$lang"
                val conn = URL(apiUrl).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "TemporaryUserAgent")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                if (conn.responseCode == 200) {
                    val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                    for (i in 0 until arr.length().coerceAtMost(5)) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val subFile = obj.optString("SubFileName")
                        val dlLink = obj.optString("SubDownloadLink")
                        if (subFile.isNotBlank() && dlLink.isNotBlank()) {
                            results.add(
                                PlayerSubtitleOption(
                                    fileName = subFile,
                                    language = lang.uppercase(),
                                    downloadUrl = dlLink,
                                    isGzipped = dlLink.endsWith(".gz", ignoreCase = true)
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    } catch (_: Exception) {
    }

    results.distinctBy { it.downloadUrl }
}

private suspend fun downloadSubtitleText(sub: PlayerSubtitleOption): String = withContext(Dispatchers.IO) {
    try {
        val conn = URL(sub.downloadUrl).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "TemporaryUserAgent")
        conn.connectTimeout = 7000
        conn.readTimeout = 7000
        val bytes = conn.inputStream.use { it.readBytes() }
        if (sub.isGzipped) {
            GZIPInputStream(ByteArrayInputStream(bytes)).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            String(bytes, Charsets.UTF_8)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        ""
    }
}

private fun parseSrtContent(rawSrt: String): List<SrtCue> {
    if (rawSrt.isBlank()) return emptyList()
    val normalized = rawSrt.replace("\r\n", "\n").replace("\r", "\n")
    val blocks = normalized.split(Regex("\n\\s*\n"))
    val cues = mutableListOf<SrtCue>()
    val timeRegex = Regex("(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})")

    for (block in blocks) {
        val lines = block.trim().lines()
        if (lines.size < 2) continue
        val timeLineIndex = lines.indexOfFirst { it.contains("-->") }
        if (timeLineIndex == -1) continue

        val match = timeRegex.find(lines[timeLineIndex]) ?: continue
        val startMs = toMillis(
            match.groupValues[1].toLong(),
            match.groupValues[2].toLong(),
            match.groupValues[3].toLong(),
            match.groupValues[4].toLong()
        )
        val endMs = toMillis(
            match.groupValues[5].toLong(),
            match.groupValues[6].toLong(),
            match.groupValues[7].toLong(),
            match.groupValues[8].toLong()
        )
        val text = lines.drop(timeLineIndex + 1)
            .joinToString("\n")
            .replace(Regex("<[^>]*>"), "")
            .trim()

        if (text.isNotEmpty()) {
            cues.add(SrtCue(startMs, endMs, text))
        }
    }
    return cues
}

private fun toMillis(h: Long, m: Long, s: Long, ms: Long): Long {
    return h * 3600000L + m * 60000L + s * 1000L + ms
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSeconds = ms / 1000
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
    else String.format("%02d:%02d", minutes, seconds)
}
