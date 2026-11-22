package com.example.mediastudio

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.mediastudio.data.model.ArchiveMovie
import com.example.mediastudio.ui.screens.DetailsScreen
import com.example.mediastudio.ui.screens.GenreScreen
import com.example.mediastudio.ui.screens.HomeScreen
import com.example.mediastudio.ui.screens.PlayerScreen
import com.example.mediastudio.ui.screens.SearchScreen
import com.example.mediastudio.ui.screens.SeriesScreen
import com.example.mediastudio.ui.viewmodel.HomeViewModel
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Osigurava da sistemska navigacijska traka na mobitelu nikad ne prekriva sadržaj aplikacije
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // Ograničava potrošnju RAM-a za plakate na Fire TV Sticku pri brzom listanju
        val imageLoader = ImageLoader.Builder(applicationContext)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .allowHardware(false)
            .crossfade(false)
            .memoryCache {
                MemoryCache.Builder(applicationContext)
                    .maxSizePercent(0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(applicationContext.cacheDir, "poster_image_cache"))
                    .maxSizeBytes(48L * 1024L * 1024L)
                    .build()
            }
            .build()
        Coil.setImageLoader(imageLoader)

        setContent {
            MaterialTheme {
                val homeViewModel: HomeViewModel = viewModel()

                var currentScreen by remember { mutableIntStateOf(0) }
                var previousScreen by remember { mutableIntStateOf(0) }
                var playerReturnScreen by remember { mutableIntStateOf(1) }
                var selectedMovie by remember { mutableStateOf<ArchiveMovie?>(null) }
                var previousAuthor by remember { mutableStateOf<ArchiveMovie?>(null) }
                var selectedMovieYear by remember { mutableStateOf("") }
                var selectedEpisodeTitle by remember { mutableStateOf("") }
                var selectedEpisodeUrl by remember { mutableStateOf("") }

                BackHandler(enabled = currentScreen != 0 && currentScreen != 2) {
                    when (currentScreen) {
                        1 -> {
                            if (previousAuthor != null && selectedMovie?.identifier != previousAuthor?.identifier) {
                                selectedMovie = previousAuthor
                                previousAuthor = null
                            } else {
                                currentScreen = previousScreen
                                previousAuthor = null
                            }
                        }
                        3 -> currentScreen = 0
                        4 -> currentScreen = previousScreen
                        5 -> currentScreen = 0
                    }
                }

                val screenModifier = if (currentScreen == 2) {
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                } else {
                    Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0E0E0E))
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                }

                Box(modifier = screenModifier) {
                    when (currentScreen) {
                        0 -> HomeScreen(
                            viewModel = homeViewModel,
                            onMovieClick = { movie ->
                                selectedMovie = movie
                                previousAuthor = null
                                selectedEpisodeUrl = ""
                                previousScreen = 0
                                currentScreen = 1
                            },
                            onSeriesClick = { series ->
                                selectedMovie = series
                                previousAuthor = null
                                previousScreen = 0
                                currentScreen = 4
                            },
                            onGenreSelect = { genre ->
                                homeViewModel.openGenre(genre)
                                currentScreen = 3
                            },
                            onOpenSearch = {
                                currentScreen = 5
                            }
                        )
                        1 -> selectedMovie?.let { movie ->
                            DetailsScreen(
                                movie = movie,
                                viewModel = homeViewModel,
                                onPlayClick = { playTarget ->
                                    if (playTarget.startsWith("http")) {
                                        selectedEpisodeUrl = playTarget
                                        selectedEpisodeTitle = movie.title
                                        selectedMovieYear = ""
                                    } else {
                                        selectedMovieYear = playTarget
                                        selectedEpisodeUrl = ""
                                    }
                                    playerReturnScreen = 1
                                    currentScreen = 2
                                },
                                onMovieSelectFromList = { specificMovie ->
                                    previousAuthor = selectedMovie
                                    selectedMovie = specificMovie
                                    selectedEpisodeUrl = ""
                                    currentScreen = 1
                                },
                                onBackClick = {
                                    if (previousAuthor != null && selectedMovie?.identifier != previousAuthor?.identifier) {
                                        selectedMovie = previousAuthor
                                        previousAuthor = null
                                    } else {
                                        currentScreen = previousScreen
                                        previousAuthor = null
                                    }
                                }
                            )
                        }
                        2 -> selectedMovie?.let { movie ->
                            PlayerScreen(
                                identifier = movie.identifier,
                                movieTitle = if (selectedEpisodeUrl.isNotEmpty()) selectedEpisodeTitle else movie.title,
                                movieYear = selectedMovieYear,
                                directVideoUrl = selectedEpisodeUrl,
                                onNavigateBack = {
                                    currentScreen = playerReturnScreen
                                }
                            )
                        }
                        3 -> GenreScreen(
                            viewModel = homeViewModel,
                            onMovieClick = { item ->
                                selectedMovie = item
                                previousAuthor = null
                                previousScreen = 3
                                if (homeViewModel.selectedGenre.value?.isSeriesCategory == true) {
                                    currentScreen = 4
                                } else {
                                    selectedEpisodeUrl = ""
                                    currentScreen = 1
                                }
                            },
                            onBackClick = { currentScreen = 0 }
                        )
                        4 -> selectedMovie?.let { series ->
                            SeriesScreen(
                                series = series,
                                viewModel = homeViewModel,
                                onEpisodeClick = { episode ->
                                    selectedEpisodeTitle = episode.subtitleSearchQuery.ifBlank { "${series.title} - ${episode.title}" }
                                    selectedEpisodeUrl = episode.fileUrl
                                    playerReturnScreen = 4
                                    currentScreen = 2
                                },
                                onBackClick = { currentScreen = previousScreen }
                            )
                        }
                        5 -> SearchScreen(
                            viewModel = homeViewModel,
                            onMovieClick = { movie ->
                                selectedMovie = movie
                                previousAuthor = null
                                selectedEpisodeUrl = ""
                                previousScreen = 5
                                currentScreen = 1
                            },
                            onSeriesClick = { series ->
                                selectedMovie = series
                                previousAuthor = null
                                previousScreen = 5
                                currentScreen = 4
                            },
                            onBackClick = {
                                currentScreen = 0
                            }
                        )
                    }
                }
            }
        }
    }
}
