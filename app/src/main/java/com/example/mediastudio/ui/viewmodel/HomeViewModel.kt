package com.example.mediastudio.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediastudio.data.model.ArchiveMovie
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

private data class ArchiveSearchResponse(
    val response: ArchiveResponseBody?
)

private data class ArchiveResponseBody(
    val docs: List<ArchiveMovie>?
)

private interface ArchiveSearchApi {
    @GET("advancedsearch.php")
    suspend fun searchMovies(
        @Query("q") query: String,
        @Query("fl[]") fields: List<String> = listOf("identifier", "title"),
        @Query("sort[]") sort: String = "downloads desc",
        @Query("rows") rows: Int = 20,
        @Query("page") page: Int = 1,
        @Query("output") output: String = "json"
    ): ArchiveSearchResponse
}

class HomeViewModel : ViewModel() {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val api: ArchiveSearchApi = Retrofit.Builder()
        .baseUrl("https://archive.org/")
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(ArchiveSearchApi::class.java)

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
            api.searchMovies(query = query, sort = sort, rows = rows).response?.docs ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
