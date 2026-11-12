package com.example.mediastudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediastudio.data.model.ArchiveMovie
import com.example.mediastudio.ui.screens.DetailsScreen
import com.example.mediastudio.ui.screens.HomeScreen
import com.example.mediastudio.ui.screens.PlayerScreen
import com.example.mediastudio.ui.viewmodel.HomeViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val homeViewModel: HomeViewModel = viewModel()
                
                var currentScreen by remember { mutableStateOf(0) }
                var selectedMovie by remember { mutableStateOf<ArchiveMovie?>(null) }
                var selectedMovieYear by remember { mutableStateOf("") }

                when (currentScreen) {
                    0 -> HomeScreen(
                        viewModel = homeViewModel,
                        onMovieClick = { movie -> 
                            selectedMovie = movie
                            currentScreen = 1
                        }
                    )
                    1 -> selectedMovie?.let { movie ->
                        DetailsScreen(
                            movie = movie,
                            onPlayClick = { year ->
                                selectedMovieYear = year
                                currentScreen = 2
                            },
                            onBackClick = { currentScreen = 0 }
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
                }
            }
        }
    }
}
