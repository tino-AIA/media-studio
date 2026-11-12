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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.mediastudio.data.api.OpenSubtitlesModule

fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun PlayerScreen(identifier: String, movieTitle: String, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val videoUrl = "https://archive.org/download/$identifier/format=h.264"

    var subtitleUrl by remember { mutableStateOf<String?>(null) }
    var isFetchingSubtitle by remember { mutableStateOf(true) }

    BackHandler {
        onNavigateBack()
    }

    // U pozadini tražimo hrvatski titl prije nego što se player učita
    LaunchedEffect(movieTitle) {
        subtitleUrl = OpenSubtitlesModule.fetchCroatianSubtitleUrl(movieTitle)
        isFetchingSubtitle = false
    }

    DisposableEffect(Unit) {
        val activity = context.findActivity()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    if (isFetchingSubtitle) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = Color.Red)
        }
    } else {
        val exoPlayer = remember(subtitleUrl) {
            ExoPlayer.Builder(context).build().apply {
                val mediaItemBuilder = MediaItem.Builder().setUri(videoUrl)

                // Ako je pronađen hrvatski titl, dodajemo ga u player
                if (subtitleUrl != null) {
                    val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
                        .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                        .setLanguage("hr")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                    mediaItemBuilder.setSubtitleConfigurations(listOf(subtitleConfig))
                }

                setMediaItem(mediaItemBuilder.build())
                prepare()
                playWhenReady = true
            }
        }

        DisposableEffect(exoPlayer) {
            onDispose {
                exoPlayer.release()
            }
        }

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
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        )
    }
}
