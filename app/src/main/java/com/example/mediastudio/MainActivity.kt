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
import com.example.mediastudio.ui.screens.HomeScreen
import com.example.mediastudio.ui.screens.PlayerScreen
import com.example.mediastudio.ui.viewmodel.HomeViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val homeViewModel: HomeViewModel = viewModel()
                
                // Pamtimo koji je film odabran. Ako je null, prikazujemo početni ekran.
                var selectedMovieId by remember { mutableStateOf<String?>(null) }

                if (selectedMovieId == null) {
                    HomeScreen(
                        viewModel = homeViewModel,
                        onMovieClick = { identifier -> 
                            // Kada korisnik klikne film, spremamo njegov ID
                            selectedMovieId = identifier 
                        }
                    )
                } else {
                    // Ako imamo ID filma, otvaramo Player
                    PlayerScreen(
                        identifier = selectedMovieId!!,
                        onNavigateBack = { selectedMovieId = null } // Za povratak nazad
                    )
                }
            }
        }
    }
}
