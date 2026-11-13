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

                // Presreće sistemsku tipku Back na mobitelu i Firestick daljinskom upravljaču
                BackHandler(enabled = currentScreen != 0) {
                    when (currentScreen) {
                        2 -> currentScreen = 1 // Iz playera natrag u detalje filma
                        1 -> currentScreen = previousScreen // Iz detalja natrag u žanr ili na početni ekran
                        3 -> currentScreen = 0 // Iz ekrana žanra natrag na početni ekran
                    }
                }

                when (currentScreen) {
                    0 -> HomeScreen(
                        viewModel = homeViewModel,
                        onMovieClick = { movie -> 
                            selectedMovie = movie
                            previousScreen = 0
                            currentScreen = 1
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
                                currentScreen = 2
                            },
                            onBackClick = { currentScreen = previousScreen }
                        )
                    }
                    2 -> selectedMovie?.let { movie ->
                        PlayerScreen(
                            identifier = movie.identifier,
                            movieTitle = movie.title,
                            movieYear = selectedMovieYear,
                            onNavigateBack = { currentScreen = 1 }
                        )
                    }
                    3 -> GenreScreen(
                        viewModel = homeViewModel,
                        onMovieClick = { movie ->
                            selectedMovie = movie
                            previousScreen = 3
                            currentScreen = 1
                        },
                        onBackClick = { currentScreen = 0 }
                    )
                }
            }
        }
    }
}
