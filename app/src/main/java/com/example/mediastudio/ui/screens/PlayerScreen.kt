package com.example.mediastudio.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

// Helper funkcija za sigurno dohvaćanje Activityja iz trenutnog Contexta
fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun PlayerScreen(identifier: String, onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    
    // Univerzalni URL za streaming H.264 formata sa Archive.org
    val videoUrl = "https://archive.org/download/$identifier/format=h.264"

    // Presrećemo "Back" tipku na mobitelu kako bi ugasili player i vratili se nazad
    BackHandler {
        onNavigateBack()
    }

    // Inicijalizacija ExoPlayera
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(videoUrl)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true // Automatski kreni s reprodukcijom
        }
    }

    // Uklapanje klasičnog Android View-a u Compose
    AndroidView(
        factory = {
            PlayerView(context).apply {
                player = exoPlayer
                useController = true // Prikaži kontrole (play, pause, timeline)
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

    // Kad izađemo iz ekrana (npr. korisnik stisne Back), vraćamo orijentaciju i oslobađamo resurse
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        
        // 1. Postavi ekran na vodoravno (Landscape) kada se otvori Player
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        onDispose {
            // 2. Vrati ekran na standardno ponašanje (slobodno okretanje) kada korisnik izađe
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            
            // 3. Oslobodi memoriju koju je player koristio
            exoPlayer.release()
        }
    }
}
