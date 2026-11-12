package com.example.mediastudio.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediastudio.data.api.RetrofitClient
import com.example.mediastudio.data.model.ArchiveMovie
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {
    private val _topMonthlyMovies = MutableStateFlow<List<ArchiveMovie>>(emptyList())
    val topMonthlyMovies: StateFlow<List<ArchiveMovie>> = _topMonthlyMovies

    private val _trendingMovies = MutableStateFlow<List<ArchiveMovie>>(emptyList())
    val trendingMovies: StateFlow<List<ArchiveMovie>> = _trendingMovies

    private val _sciFiMovies = MutableStateFlow<List<ArchiveMovie>>(emptyList())
    val sciFiMovies: StateFlow<List<ArchiveMovie>> = _sciFiMovies

    private val _actionMovies = MutableStateFlow<List<ArchiveMovie>>(emptyList())
    val actionMovies: StateFlow<List<ArchiveMovie>> = _actionMovies

    private val _thrillerMovies = MutableStateFlow<List<ArchiveMovie>>(emptyList())
    val thrillerMovies: StateFlow<List<ArchiveMovie>> = _thrillerMovies

    private val _comedyMovies = MutableStateFlow<List<ArchiveMovie>>(emptyList())
    val comedyMovies: StateFlow<List<ArchiveMovie>> = _comedyMovies

    private val _horrorMovies = MutableStateFlow<List<ArchiveMovie>>(emptyList())
    val horrorMovies: StateFlow<List<ArchiveMovie>> = _horrorMovies

    private val _dramaMovies = MutableStateFlow<List<ArchiveMovie>>(emptyList())
    val dramaMovies: StateFlow<List<ArchiveMovie>> = _dramaMovies

    private val _westernMovies = MutableStateFlow<List<ArchiveMovie>>(emptyList())
    val westernMovies: StateFlow<List<ArchiveMovie>> = _westernMovies

    private val _documentaryMovies = MutableStateFlow<List<ArchiveMovie>>(emptyList())
    val documentaryMovies: StateFlow<List<ArchiveMovie>> = _documentaryMovies

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        loadAllCategories()
    }

    private fun loadAllCategories() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val baseQuery = "collection:feature_films AND mediatype:movies"

                val topMonthDef = async { fetchSafe(baseQuery, sort = "month desc", rows = 10) }
                val trendingDef = async { fetchSafe(baseQuery, sort = "downloads desc", rows = 20) }
                val sciFiDef = async { fetchSafe("$baseQuery AND subject:(sci-fi OR \"science fiction\")") }
                val actionDef = async { fetchSafe("$baseQuery AND subject:(action OR adventure)") }
                val thrillerDef = async { fetchSafe("$baseQuery AND subject:(thriller OR mystery OR noir)") }
                val comedyDef = async { fetchSafe("$baseQuery AND subject:(comedy)") }
                val horrorDef = async { fetchSafe("$baseQuery AND subject:(horror)") }
                val dramaDef = async { fetchSafe("$baseQuery AND subject:(drama)") }
                val westernDef = async { fetchSafe("$baseQuery AND subject:(western)") }
                val docDef = async { fetchSafe("mediatype:movies AND subject:(documentary)", rows = 20) }

                _topMonthlyMovies.value = topMonthDef.await()
                _trendingMovies.value = trendingDef.await()
                _sciFiMovies.value = sciFiDef.await()
                _actionMovies.value = actionDef.await()
                _thrillerMovies.value = thrillerDef.await()
                _comedyMovies.value = comedyDef.await()
                _horrorMovies.value = horrorDef.await()
                _dramaMovies.value = dramaDef.await()
                _westernMovies.value = westernDef.await()
                _documentaryMovies.value = docDef.await()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun fetchSafe(
        query: String,
        sort: String = "downloads desc",
        rows: Int = 20
    ): List<ArchiveMovie> {
        return try {
            RetrofitClient.api.searchMovies(query = query, sort = sort, rows = rows).response?.docs ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
