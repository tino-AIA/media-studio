package com.example.mediastudio.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

    BackHandler {
        if (showSubtitleDialog) {
            showSubtitleDialog = false
        } else {
            onNavigateBack()
        }
    }

    // Inicijalizacija ExoPlayera
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUrl))
            prepare()
            playWhenReady = true
        }
    }

    // Pomoćna funkcija za zamjenu titla bez gubitka trenutne pozicije u filmu
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

    // 1. Osluškujemo stvarni FPS videa čim krene stream
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

    // 2. Dohvaćamo listu dostupnih titlova s OpenSubtitlesa
    LaunchedEffect(movieTitle) {
        availableSubtitles = OpenSubtitlesModule.searchAvailableSubtitles(movieTitle)
    }

    // 3. Automatski odabiremo titl koji najbolje odgovara po FPS-u (čim imamo i listu i FPS videa)
    LaunchedEffect(availableSubtitles, videoFps) {
        if (!autoMatchedDone && availableSubtitles.isNotEmpty()) {
            val bestMatch = if (videoFps > 0f) {
                // Tražimo titl čiji je FPS unutar 0.1 razlike od videa (npr. 23.976 vs 23.98)
                availableSubtitles.firstOrNull { it.fps > 0f && abs(it.fps - videoFps) < 0.1f }
                    ?: availableSubtitles.first()
            } else {
                availableSubtitles.first()
            }
            autoMatchedDone = true
            applySubtitle(bestMatch)
        }
    }

    // Orijentacija ekrana (Landscape u playeru, vraćanje na staro pri izlazu)
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
        // Video Player
        AndroidView(
            factory = {
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = true
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Plutajući gumb u gornjem desnom kutu za ručni odabir titlova (radi i na daljinski)
        if (availableSubtitles.isNotEmpty()) {
            Button(
                onClick = { showSubtitleDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black.copy(alpha = 0.7f)
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Text(
                    text = "CC (${availableSubtitles.size})",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
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
                        // Opcija za gašenje titlova
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

                        // Lista svih pronađenih titlova
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
