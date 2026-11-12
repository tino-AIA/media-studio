package com.example.mediastudio.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.mediastudio.data.api.ArchiveMetadataModule
import com.example.mediastudio.data.api.OpenSubtitlesModule
import com.example.mediastudio.data.api.SubtitleOption
import com.example.mediastudio.data.api.VideoQualityOption
import kotlinx.coroutines.launch
import kotlin.math.abs

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun PlayerScreen(identifier: String, movieTitle: String, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fallbackVideoUrl = "https://archive.org/download/$identifier/format=h.264"

    // Stanja za kvalitete videa
    var availableQualities by remember { mutableStateOf<List<VideoQualityOption>>(emptyList()) }
    var selectedQuality by remember { mutableStateOf<VideoQualityOption?>(null) }
    var showQualityDialog by remember { mutableStateOf(false) }

    // Stanja za titlove
    var availableSubtitles by remember { mutableStateOf<List<SubtitleOption>>(emptyList()) }
    var selectedSubtitle by remember { mutableStateOf<SubtitleOption?>(null) }
    var activeSubtitleUri by remember { mutableStateOf<Uri?>(null) }
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

    // Zajednička funkcija koja ažurira player pri promjeni kvalitete ILI titla bez gubitka pozicije
    fun updateMediaSource(
        quality: VideoQualityOption?,
        subUri: Uri?,
        startPosition: Long = exoPlayer.currentPosition,
        playWhenReadyState: Boolean = true
    ) {
        val targetUrl = quality?.url ?: fallbackVideoUrl
        val mediaItemBuilder = MediaItem.Builder().setUri(targetUrl)

        if (subUri != null) {
            val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(subUri)
                .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                .setLanguage("hr")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            mediaItemBuilder.setSubtitleConfigurations(listOf(subtitleConfig))
        }

        exoPlayer.setMediaItem(mediaItemBuilder.build(), startPosition)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = playWhenReadyState
    }

    fun applyQuality(option: VideoQualityOption) {
        selectedQuality = option
        val pos = if (exoPlayer.currentPosition > 0) exoPlayer.currentPosition else 0L
        val wasPlaying = if (exoPlayer.playbackState == Player.STATE_IDLE) true else exoPlayer.isPlaying
        updateMediaSource(option, activeSubtitleUri, pos, wasPlaying)
    }

    fun applySubtitle(option: SubtitleOption?) {
        selectedSubtitle = option
        scope.launch {
            val pos = exoPlayer.currentPosition
            val wasPlaying = exoPlayer.isPlaying
            val localUri = if (option != null) {
                OpenSubtitlesModule.downloadAndFixEncoding(context, option.fileId)
            } else {
                null
            }
            activeSubtitleUri = localUri
            updateMediaSource(selectedQuality, localUri, pos, wasPlaying)
        }
    }

    // 1. Dohvaćamo sve dostupne kvalitete s Archive.org i automatski pokrećemo najbolju
    LaunchedEffect(identifier) {
        val qualities = ArchiveMetadataModule.fetchAvailableQualities(identifier)
        availableQualities = qualities
        val bestQuality = qualities.firstOrNull()
        selectedQuality = bestQuality
        updateMediaSource(bestQuality, activeSubtitleUri, 0L, true)
    }

    // 2. Osluškujemo stvarni FPS videa za usklađivanje titlova
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

    // 3. Dohvaćamo popis titlova s OpenSubtitlesa
    LaunchedEffect(movieTitle) {
        availableSubtitles = OpenSubtitlesModule.searchAvailableSubtitles(movieTitle)
    }

    // 4. Automatski odabiremo titl koji odgovara FPS-u videa
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
                    .padding(horizontal = 16.dp, vertical = 12.dp),
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
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = movieTitle,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Desno: Početak, Stop, Kvaliteta (HD/SD), Titlovi (CC)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            exoPlayer.seekTo(0L)
                            exoPlayer.playWhenReady = true
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Početak", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Početak", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            exoPlayer.stop()
                            onNavigateBack()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop", fontSize = 12.sp)
                    }

                    // Gumb za odabir rezolucije / kvalitete videa
                    if (availableQualities.isNotEmpty()) {
                        val currentResShort = selectedQuality?.label?.substringBefore(" ") ?: "Auto"
                        OutlinedButton(
                            onClick = { showQualityDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = currentResShort,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }

                    // Gumb za odabir titlova (CC)
                    if (availableSubtitles.isNotEmpty()) {
                        Button(
                            onClick = { showSubtitleDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.85f)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "CC (${availableSubtitles.size})",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
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
