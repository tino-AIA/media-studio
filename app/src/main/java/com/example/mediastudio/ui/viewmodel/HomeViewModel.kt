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

private data class ArchiveSearchResponse(val response: ArchiveResponseBody?)
private data class ArchiveResponseBody(val docs: List<RawArchiveDoc>?)
private data class RawArchiveDoc(
    val identifier: String?,
    val title: Any?
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

    private val _crimeMovies = MutableStateFlow<List<ArchiveMovie>>(emptyList())
    val crimeMovies: StateFlow<List<ArchiveMovie>> = _crimeMovies

    private val _romanceMovies = MutableStateFlow<List<ArchiveMovie>>(emptyList())
    val romanceMovies: StateFlow<List<ArchiveMovie>> = _romanceMovies

    private val _westernMovies = MutableStateFlow<List<ArchiveMovie>>(emptyList())
    val westernMovies: StateFlow<List<ArchiveMovie>> = _westernMovies

    private val _warMovies = MutableStateFlow<List<ArchiveMovie>>(emptyList())
    val warMovies: StateFlow<List<ArchiveMovie>> = _warMovies

    private val _animationMovies = MutableStateFlow<List<ArchiveMovie>>(emptyList())
    val animationMovies: StateFlow<List<ArchiveMovie>> = _animationMovies

    private val _documentaryMovies = MutableStateFlow<List<ArchiveMovie>>(emptyList())
    val documentaryMovies: StateFlow<List<ArchiveMovie>> = _documentaryMovies

    private val _adultMovies = MutableStateFlow<List<ArchiveMovie>>(emptyList())
    val adultMovies: StateFlow<List<ArchiveMovie>> = _adultMovies

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        loadAllCategories()
    }

    private fun loadAllCategories() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val safeFilter = "-subject:(erotic OR erotica OR adult OR xxx OR sex OR nudity) AND -title:(erotic OR sex OR nude)"
                val base = "collection:feature_films AND mediatype:movies AND $safeFilter"

                val topMonthDef = async { fetchSafe(base, sort = "month desc", rows = 15) }
                val trendingDef = async { fetchSafe(base, sort = "downloads desc", rows = 20) }
                val sciFiDef = async { fetchSafe("$base AND (subject:sci-fi OR subject:\"science fiction\" OR title:planet OR title:space)") }
                val actionDef = async { fetchSafe("$base AND (subject:action OR subject:adventure OR subject:martial)") }
                val thrillerDef = async { fetchSafe("$base AND (subject:thriller OR subject:mystery OR subject:noir OR subject:suspense)") }
                val comedyDef = async { fetchSafe("mediatype:movies AND $safeFilter AND (subject:comedy OR subject:comedies OR subject:humor OR collection:comedy_films)") }
                val horrorDef = async { fetchSafe("$base AND (subject:horror OR subject:monster OR subject:zombie OR subject:vampire)") }
                val dramaDef = async { fetchSafe("$base AND (subject:drama OR subject:melodrama)") }
                val crimeDef = async { fetchSafe("$base AND (subject:crime OR subject:gangster OR subject:detective)") }
                val romanceDef = async { fetchSafe("$base AND (subject:romance OR subject:romantic OR subject:love)") }
                val westernDef = async { fetchSafe("$base AND (subject:western OR subject:westerns OR subject:cowboy)") }
                val warDef = async { fetchSafe("$base AND (subject:war OR subject:military OR subject:\"world war\")") }
                
                // Poznati studiji i hit klasici za animirane (Disney, Fleischer, Warner Bros / Looney Tunes, Popeye, Superman, Gulliver)
                val animQuery = "mediatype:movies AND $safeFilter AND (subject:animation OR subject:cartoon OR collection:animationandcartoons) AND (title:Disney OR title:Mickey OR title:Donald OR title:Popeye OR title:Superman OR title:Looney OR title:Bugs OR title:Betty OR title:Gulliver OR title:Tom OR title:Woody)"
                val animDef = async { fetchSafe(animQuery, sort = "downloads desc", rows = 25) }
                
                val docDef = async { fetchSafe("mediatype:movies AND $safeFilter AND (subject:documentary OR subject:documentaries)") }
                
                // Zasebna 18+ / erotska kategorija
                val adultDef = async { fetchSafe("mediatype:movies AND (subject:erotic OR subject:erotica OR subject:adult)") }

                _topMonthlyMovies.value = topMonthDef.await().take(10)
                _trendingMovies.value = trendingDef.await()
                _sciFiMovies.value = sciFiDef.await()
                _actionMovies.value = actionDef.await()
                _thrillerMovies.value = thrillerDef.await()
                _comedyMovies.value = comedyDef.await()
                _horrorMovies.value = horrorDef.await()
                _dramaMovies.value = dramaDef.await()
                _crimeMovies.value = crimeDef.await()
                _romanceMovies.value = romanceDef.await()
                _westernMovies.value = westernDef.await()
                _warMovies.value = warDef.await()
                _animationMovies.value = animDef.await()
                _documentaryMovies.value = docDef.await()
                _adultMovies.value = adultDef.await()
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
            val docs = api.searchMovies(query = query, sort = sort, rows = rows).response?.docs ?: emptyList()
            docs.mapNotNull { doc ->
                val id = doc.identifier?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val rawTitle = when (val t = doc.title) {
                    is String -> t.trim()
                    is List<*> -> t.filterNotNull().joinToString(" ").trim()
                    else -> ""
                }
                val finalTitle = rawTitle.ifBlank {
                    id.replace("_", " ")
                        .replace("-", " ")
                        .replaceFirstChar { it.uppercase() }
                }
                ArchiveMovie(identifier = id, title = finalTitle)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
