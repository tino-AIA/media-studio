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
import com.example.mediastudio.data.api.OpenSubtitlesModule
import com.example.mediastudio.data.api.SubtitleOption
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
    val videoUrl = "https://archive.org/download/$identifier/format=h.264"

    var availableSubtitles by remember { mutableStateOf<List<SubtitleOption>>(emptyList()) }
    var selectedSubtitle by remember { mutableStateOf<SubtitleOption?>(null) }
    var videoFps by remember { mutableStateOf(0f) }
    var autoMatchedDone by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }
    var areControlsVisible by remember { mutableStateOf(true) }

    BackHandler {
        if (showSubtitleDialog) {
            showSubtitleDialog = false
        } else {
            onNavigateBack()
        }
    }

    // ExoPlayer s korakom premotavanja od 15 sekundi naprijed/natrag
    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setSeekBackIncrementMs(15000L)
            .setSeekForwardIncrementMs(15000L)
            .build().apply {
                setMediaItem(MediaItem.fromUri(videoUrl))
                prepare()
                playWhenReady = true
            }
    }

    fun applySubtitle(option: SubtitleOption?) {
        selectedSubtitle = option
        scope.launch {
            val currentPos = exoPlayer.currentPosition
            val wasPlaying = exoPlayer.isPlaying

            val mediaItemBuilder = MediaItem.Builder().setUri(videoUrl)

            if (option != null) {
                val localUri: Uri? = OpenSubtitlesModule.downloadAndFixEncoding(context, option.fileId)
                if (localUri != null) {
                    val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(localUri)
                        .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                        .setLanguage("hr")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                    mediaItemBuilder.setSubtitleConfigurations(listOf(subtitleConfig))
                }
            }

            exoPlayer.setMediaItem(mediaItemBuilder.build(), currentPos)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = wasPlaying
        }
    }

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

    LaunchedEffect(movieTitle) {
        availableSubtitles = OpenSubtitlesModule.searchAvailableSubtitles(movieTitle)
    }

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
        // Video Player (ima Play/Pause, -15s, +15s i timeline traku)
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

        // Gornja traka s dodatnim gumbima (prikazuje se samo kad su kontrole vidljive)
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
                // Lijeva strana: Gumb za povratak + Naslov filma
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

                // Desna strana: Od početka, Stop, Titlovi (CC)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Gumb: Kreni od početka
                    OutlinedButton(
                        onClick = {
                            exoPlayer.seekTo(0L)
                            exoPlayer.playWhenReady = true
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Od početka",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Početak", fontSize = 13.sp)
                    }

                    // Gumb: Stop (zaustavlja reprodukciju i izlazi iz playera)
                    OutlinedButton(
                        onClick = {
                            exoPlayer.stop()
                            onNavigateBack()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop", fontSize = 13.sp)
                    }

                    // Gumb: Odabir titlova (CC)
                    if (availableSubtitles.isNotEmpty()) {
                        Button(
                            onClick = { showSubtitleDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Red.copy(alpha = 0.85f)
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "CC (${availableSubtitles.size})",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }

        // Prozor (Dialog) s listom svih pronađenih titlova
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
