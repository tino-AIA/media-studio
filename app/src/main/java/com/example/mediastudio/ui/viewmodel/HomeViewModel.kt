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
    
    private val _actionMovies = MutableStateFlow<List<ArchiveMovie>>(emptyList())
    val actionMovies: StateFlow<List<ArchiveMovie>> = _actionMovies.asStateFlow()

    private val _thrillerMovies = MutableStateFlow<List<ArchiveMovie>>(emptyList())
    val thrillerMovies: StateFlow<List<ArchiveMovie>> = _thrillerMovies.asStateFlow()

    private val _comedyMovies = MutableStateFlow<List<ArchiveMovie>>(emptyList())
    val comedyMovies: StateFlow<List<ArchiveMovie>> = _comedyMovies.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        fetchMovies()
    }

    private fun fetchMovies() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Najgledaniji dugometražni filmovi
                val trendingResponse = NetworkModule.api.searchMovies("mediatype:movies AND collection:feature_films")
                _trendingMovies.value = trendingResponse.response.docs

                // Sci-Fi
                val sciFiResponse = NetworkModule.api.searchMovies("mediatype:movies AND collection:feature_films AND subject:\"Sci-Fi\"")
                _sciFiMovies.value = sciFiResponse.response.docs
                
                // Akcija
                val actionResponse = NetworkModule.api.searchMovies("mediatype:movies AND collection:feature_films AND subject:\"Action\"")
                _actionMovies.value = actionResponse.response.docs

                // Triler
                val thrillerResponse = NetworkModule.api.searchMovies("mediatype:movies AND collection:feature_films AND subject:\"Thriller\"")
                _thrillerMovies.value = thrillerResponse.response.docs

                // Komedija
                val comedyResponse = NetworkModule.api.searchMovies("mediatype:movies AND collection:feature_films AND subject:\"Comedy\"")
                _comedyMovies.value = comedyResponse.response.docs

            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }
}
