package com.example.mediastudio.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.mediastudio.data.api.ArchiveMetadataModule
import com.example.mediastudio.data.api.OpenSubtitlesModule
import com.example.mediastudio.data.api.SrtCue
import com.example.mediastudio.data.api.SubtitleOption
import com.example.mediastudio.data.api.VideoQualityOption
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun PlayerScreen(
    identifier: String,
    movieTitle: String,
    movieYear: String = "",
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fallbackVideoUrl = "https://archive.org/download/$identifier/format=h.264"

    // Stanja za kvalitete videa
    var availableQualities by remember { mutableStateOf<List<VideoQualityOption>>(emptyList()) }
    var selectedQuality by remember { mutableStateOf<VideoQualityOption?>(null) }
    var showQualityDialog by remember { mutableStateOf(false) }

    // Stanja za brze Compose titlove (bez prekidanja videa)
    var availableSubtitles by remember { mutableStateOf<List<SubtitleOption>>(emptyList()) }
    var selectedSubtitle by remember { mutableStateOf<SubtitleOption?>(null) }
    var parsedCues by remember { mutableStateOf<List<SrtCue>>(emptyList()) }
    var currentSubtitleText by remember { mutableStateOf("") }
    var subtitleOffsetMs by remember { mutableLongStateOf(0L) }
    var isLoadingSubtitles by remember { mutableStateOf(false) }

    var videoFps by remember { mutableStateOf(0f) }
    var autoMatchedDone by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }
    var areControlsVisible by remember { mutableStateOf(true) }

    BackHandler {
        when {
            showQualityDialog -> showQualityDialog = false
            showSubtitleDialog -> showSubtitleDialog = false
            else -> onNavigateBack()
        }
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(15000L)
            .setSeekForwardIncrementMs(15000L)
            .build()
    }

    // Promjena kvalitete videa (jedina situacija koja mijenja video stream)
    fun applyQuality(option: VideoQualityOption) {
        selectedQuality = option
        val pos = if (exoPlayer.currentPosition > 0) exoPlayer.currentPosition else 0L
        val wasPlaying = if (exoPlayer.playbackState == Player.STATE_IDLE) true else exoPlayer.isPlaying
        exoPlayer.setMediaItem(MediaItem.fromUri(option.url), pos)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = wasPlaying
    }

    // Trenutna promjena titla u memoriji - video stream se uopće ne dira!
    fun applySubtitle(option: SubtitleOption?) {
        selectedSubtitle = option
        subtitleOffsetMs = 0L
        if (option == null) {
            parsedCues = emptyList()
            currentSubtitleText = ""
            return
        }
        scope.launch {
            isLoadingSubtitles = true
            val cues = OpenSubtitlesModule.downloadAndParseSubtitles(option.fileId)
            parsedCues = cues
            isLoadingSubtitles = false
        }
    }

    // Petlja koja prati trenutno vrijeme videa i ispisuje odgovarajući redak titla
    LaunchedEffect(parsedCues, subtitleOffsetMs) {
        if (parsedCues.isEmpty()) {
            currentSubtitleText = ""
            return@LaunchedEffect
        }
        while (isActive) {
            val posWithOffset = exoPlayer.currentPosition + subtitleOffsetMs
            val activeCue = parsedCues.firstOrNull { posWithOffset in it.startMs..it.endMs }
            currentSubtitleText = activeCue?.text ?: ""
            delay(100L)
        }
    }

    // 1. Dohvat kvaliteta videa i pokretanje najbolje rezolucije
    LaunchedEffect(identifier) {
        val qualities = ArchiveMetadataModule.fetchAvailableQualities(identifier)
        availableQualities = qualities
        val bestQuality = qualities.firstOrNull()
        selectedQuality = bestQuality
        val startUrl = bestQuality?.url ?: fallbackVideoUrl
        exoPlayer.setMediaItem(MediaItem.fromUri(startUrl), 0L)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    // 2. Osluškivanje FPS-a videa
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val detectedFps = exoPlayer.videoFormat?.frameRate ?: 0f
                if (detectedFps > 0f && videoFps == 0f) {
                    videoFps = detectedFps
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // 3. Dohvat liste titlova s OpenSubtitlesa
    LaunchedEffect(movieTitle, movieYear) {
        availableSubtitles = OpenSubtitlesModule.searchAvailableSubtitles(movieTitle, movieYear)
    }

    // 4. Automatski odabir najboljeg titla po FPS-u
    LaunchedEffect(availableSubtitles, videoFps) {
        if (!autoMatchedDone && availableSubtitles.isNotEmpty()) {
            val bestMatch = if (videoFps > 0f) {
                availableSubtitles.firstOrNull { it.fps > 0f && abs(it.fps - videoFps) < 0.1f }
                    ?: availableSubtitles.first()
            } else {
                availableSubtitles.first()
            }
            autoMatchedDone = true
            applySubtitle(bestMatch)
        }
    }

    DisposableEffect(Unit) {
        val activity = context.findActivity()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = true
                    setShowRewindButton(true)
                    setShowFastForwardButton(true)
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            areControlsVisible = (visibility == View.VISIBLE)
                        }
                    )
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Prikaz teksta titla preko videa (automatski se podiže iznad trake kad su kontrole vidljive)
        if (currentSubtitleText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = 32.dp,
                        end = 32.dp,
                        bottom = if (areControlsVisible) 88.dp else 28.dp
                    )
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = currentSubtitleText,
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    lineHeight = 25.sp
                )
            }
        }

        // Gornja kontrolna traka
        if (areControlsVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)
                        )
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Lijevo: Povratak i naslov
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Povratak",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = movieTitle,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Desno: Sinkronizacija titla, Početak, Stop, Rezolucija, Titlovi (CC)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Ručna sinkronizacija titlova (-0.5s / +0.5s) kad je titl aktivan
                    if (selectedSubtitle != null) {
                        OutlinedButton(
                            onClick = { subtitleOffsetMs -= 500L },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("-0.5s", fontSize = 11.sp)
                        }

                        if (subtitleOffsetMs != 0L) {
                            val offsetSec = subtitleOffsetMs / 1000.0
                            Text(
                                text = "${if (offsetSec > 0) "+" else ""}${"%.1f".format(offsetSec)}s",
                                color = Color(0xFF4CAF50),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        OutlinedButton(
                            onClick = { subtitleOffsetMs += 500L },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("+0.5s", fontSize = 11.sp)
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            exoPlayer.seekTo(0L)
                            exoPlayer.playWhenReady = true
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Početak", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Početak", fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            exoPlayer.stop()
                            onNavigateBack()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Stop", fontSize = 11.sp)
                    }

                    if (availableQualities.isNotEmpty()) {
                        val currentResShort = selectedQuality?.label?.substringBefore(" ") ?: "Auto"
                        OutlinedButton(
                            onClick = { showQualityDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = currentResShort,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }

                    if (availableSubtitles.isNotEmpty()) {
                        Button(
                            onClick = { showSubtitleDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.85f)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (isLoadingSubtitles) "..." else "CC (${availableSubtitles.size})",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }

        // Prozor (Dialog) za odabir kvalitete slike
        if (showQualityDialog) {
            AlertDialog(
                onDismissRequest = { showQualityDialog = false },
                containerColor = Color(0xFF1A1A1A),
                title = {
                    Text("Kvaliteta videa", color = Color.White, fontWeight = FontWeight.Bold)
                },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                        items(availableQualities) { quality ->
                            val isSelected = selectedQuality?.url == quality.url
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        applyQuality(quality)
                                        showQualityDialog = false
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = Color.Red)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = quality.label,
                                    color = if (isSelected) Color.White else Color.LightGray,
                                    fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showQualityDialog = false }) {
                        Text("Zatvori", color = Color.White)
                    }
                }
            )
        }

        // Prozor (Dialog) za odabir titlova
        if (showSubtitleDialog) {
            AlertDialog(
                onDismissRequest = { showSubtitleDialog = false },
                containerColor = Color(0xFF1A1A1A),
                title = {
                    Column {
                        Text("Odaberi titl", color = Color.White, fontWeight = FontWeight.Bold)
                        if (videoFps > 0f) {
                            Text(
                                text = "Detektiran video: ${"%.2f".format(videoFps)} FPS",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                text = {
                    LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        applySubtitle(null)
                                        showSubtitleDialog = false
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedSubtitle == null,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = Color.Red)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Bez titlova", color = Color.White)
                            }
                        }

                        items(availableSubtitles) { option ->
                            val isSelected = selectedSubtitle?.fileId == option.fileId
                            val fpsMatches = videoFps > 0f && option.fps > 0f && abs(option.fps - videoFps) < 0.1f

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        applySubtitle(option)
                                        showSubtitleDialog = false
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = Color.Red)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = option.title,
                                        color = if (isSelected) Color.White else Color.LightGray,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (fpsMatches) {
                                        Text(
                                            text = "✓ Odgovara FPS-u videa",
                                            color = Color(0xFF4CAF50),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSubtitleDialog = false }) {
                        Text("Zatvori", color = Color.White)
                    }
                }
            )
        }
    }
}
