package com.example.mediastudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediastudio.data.model.ArchiveMovie
import com.example.mediastudio.ui.screens.DetailsScreen
import com.example.mediastudio.ui.screens.GenreScreen
import com.example.mediastudio.ui.screens.HomeScreen
import com.example.mediastudio.ui.screens.PlayerScreen
import com.example.mediastudio.ui.screens.SeriesScreen
import com.example.mediastudio.ui.viewmodel.HomeViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val homeViewModel: HomeViewModel = viewModel()
                
                var currentScreen by remember { mutableIntStateOf(0) }
                var previousScreen by remember { mutableIntStateOf(0) }
                var selectedMovie by remember { mutableStateOf<ArchiveMovie?>(null) }
                var selectedMovieYear by remember { mutableStateOf("") }
                var selectedEpisodeTitle by remember { mutableStateOf("") }
                var selectedEpisodeUrl by remember { mutableStateOf("") }

                BackHandler(enabled = currentScreen != 0) {
                    when (currentScreen) {
                        2 -> currentScreen = if (selectedEpisodeUrl.isNotEmpty()) 4 else 1
                        1 -> currentScreen = previousScreen
                        3 -> currentScreen = 0
                        4 -> currentScreen = previousScreen
                    }
                }

                when (currentScreen) {
                    0 -> HomeScreen(
                        viewModel = homeViewModel,
                        onMovieClick = { movie -> 
                            selectedMovie = movie
                            selectedEpisodeUrl = ""
                            previousScreen = 0
                            currentScreen = 1
                        },
                        onSeriesClick = { series ->
                            selectedMovie = series
                            previousScreen = 0
                            currentScreen = 4
                        },
                        onGenreSelect = { genre ->
                            homeViewModel.openGenre(genre)
                            currentScreen = 3
                        }
                    )
                    1 -> selectedMovie?.let { movie ->
                        DetailsScreen(
                            movie = movie,
                            onPlayClick = { year ->
                                selectedMovieYear = year
                                selectedEpisodeUrl = ""
                                currentScreen = 2
                            },
                            onBackClick = { currentScreen = previousScreen }
                        )
                    }
                    2 -> selectedMovie?.let { movie ->
                        PlayerScreen(
                            identifier = movie.identifier,
                            movieTitle = if (selectedEpisodeUrl.isNotEmpty()) selectedEpisodeTitle else movie.title,
                            movieYear = selectedMovieYear,
                            directVideoUrl = selectedEpisodeUrl,
                            onNavigateBack = {
                                currentScreen = if (selectedEpisodeUrl.isNotEmpty()) 4 else 1
                            }
                        )
                    }
                    3 -> GenreScreen(
                        viewModel = homeViewModel,
                        onMovieClick = { item ->
                            selectedMovie = item
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
                            onEpisodeClick = { episode ->
                                selectedEpisodeTitle = "${series.title} - ${episode.title}"
                                selectedEpisodeUrl = episode.fileUrl
                                currentScreen = 2
                            },
                            onBackClick = { currentScreen = previousScreen }
                        )
                    }
                }
            }
        }
    }
}
