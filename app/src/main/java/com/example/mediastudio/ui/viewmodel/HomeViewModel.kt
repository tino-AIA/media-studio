package com.example.mediastudio.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediastudio.data.api.NetworkModule
import com.example.mediastudio.data.model.ArchiveMovie
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {

    private val _trendingMovies = MutableStateFlow<List<ArchiveMovie>>(emptyList())
    val trendingMovies: StateFlow<List<ArchiveMovie>> = _trendingMovies.asStateFlow()

    private val _sciFiMovies = MutableStateFlow<List<ArchiveMovie>>(emptyList())
    val sciFiMovies: StateFlow<List<ArchiveMovie>> = _sciFiMovies.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        fetchMovies()
    }

    private fun fetchMovies() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Preuzimanje igranih filmova (Najgledanije)
                val trendingResponse = NetworkModule.api.searchMovies("mediatype:movies AND collection:feature_films")
                _trendingMovies.value = trendingResponse.response.docs

                // Preuzimanje Sci-Fi filmova
                val sciFiResponse = NetworkModule.api.searchMovies("mediatype:movies AND subject:\"Sci-Fi\"")
                _sciFiMovies.value = sciFiResponse.response.docs
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }
}
