package com.example.mediastudio.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediastudio.data.model.ArchiveMovie
import com.squareup.moshi.Json
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

data class GenreOption(
    val title: String,
    val query: String,
    val defaultSort: String = "avg_rating desc"
)

data class RatedMovie(
    val movie: ArchiveMovie,
    val rating: String
)

private data class ArchiveSearchResponse(val response: ArchiveResponseBody?)
private data class ArchiveResponseBody(val docs: List<RawArchiveDoc>?)
private data class RawArchiveDoc(
    val identifier: String?,
    val title: Any?,
    @Json(name = "avg_rating") val avgRating: Any?
)

private interface ArchiveSearchApi {
    @GET("advancedsearch.php")
    suspend fun searchMovies(
        @Query("q") query: String,
        @Query("fl[]") fields: List<String> = listOf("identifier", "title", "avg_rating"),
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

    private val safeFilter = "-subject:(erotic OR erotica OR adult OR xxx OR sex OR nudity) AND -title:(erotic OR sex OR nude)"
    private val baseQuery = "collection:feature_films AND mediatype:movies AND $safeFilter"

    // Popis svih žanrova i njihovih upita na jednom mjestu
    val genreDefinitions = listOf(
        GenreOption("Top 10 ovog mjeseca", baseQuery, "month desc"),
        GenreOption("Najgledanije", baseQuery, "downloads desc"),
        GenreOption("Sci-Fi klasici", "$baseQuery AND (subject:sci-fi OR subject:\"science fiction\" OR title:planet OR title:space)"),
        GenreOption("Akcija i avantura", "$baseQuery AND (subject:action OR subject:adventure OR subject:martial)"),
        GenreOption("Triler i misterija", "$baseQuery AND (subject:thriller OR subject:mystery OR subject:noir OR subject:suspense)"),
        GenreOption("Komedija", "mediatype:movies AND $safeFilter AND (subject:comedy OR subject:comedies OR subject:humor OR collection:comedy_films)"),
        GenreOption("Horor", "$baseQuery AND (subject:horror OR subject:monster OR subject:zombie OR subject:vampire)"),
        GenreOption("Drama", "$baseQuery AND (subject:drama OR subject:melodrama)"),
        GenreOption("Krimi", "$baseQuery AND (subject:crime OR subject:gangster OR subject:detective)"),
        GenreOption("Romantični", "$baseQuery AND (subject:romance OR subject:romantic OR subject:love)"),
        GenreOption("Vestern", "$baseQuery AND (subject:western OR subject:westerns OR subject:cowboy)"),
        GenreOption("Ratni", "$baseQuery AND (subject:war OR subject:military OR subject:\"world war\")"),
        GenreOption(
            "Animirani klasici",
            "mediatype:movies AND $safeFilter AND (subject:animation OR subject:cartoon OR collection:animationandcartoons) AND (title:Disney OR title:Mickey OR title:Donald OR title:Popeye OR title:Superman OR title:Looney OR title:Bugs OR title:Betty OR title:Gulliver OR title:Tom OR title:Woody)"
        ),
        GenreOption("Dokumentarni", "mediatype:movies AND $safeFilter AND (subject:documentary OR subject:documentaries)"),
        GenreOption("18+ / Erotski", "mediatype:movies AND (subject:erotic OR subject:erotica OR subject:adult)")
    )

    // Stanja za početni ekran
    private val _homeCategories = MutableStateFlow<List<Pair<GenreOption, List<ArchiveMovie>>>>(emptyList())
    val homeCategories: StateFlow<List<Pair<GenreOption, List<ArchiveMovie>>>> = _homeCategories

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    // Stanja za zasebni ekran odabranog žanra (sortirano po ocjeni)
    private val _selectedGenre = MutableStateFlow<GenreOption?>(null)
    val selectedGenre: StateFlow<GenreOption?> = _selectedGenre

    private val _genreMovies = MutableStateFlow<List<RatedMovie>>(emptyList())
    val genreMovies: StateFlow<List<RatedMovie>> = _genreMovies

    private val _isGenreLoading = MutableStateFlow(false)
    val isGenreLoading: StateFlow<Boolean> = _isGenreLoading

    init {
        loadAllCategories()
    }

    private fun loadAllCategories() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val deferredList = genreDefinitions.map { genre ->
                    async {
                        val rows = if (genre.title == "Top 10 ovog mjeseca") 10 else 20
                        val movies = fetchSafe(genre.query, sort = genre.defaultSort, rows = rows).map { it.movie }
                        genre to movies
                    }
                }
                _homeCategories.value = deferredList.map { it.await() }.filter { it.second.isNotEmpty() }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    // Otvara zasebni ekran žanra i sortira filmove po ocjenama (najviša ocjena prva)
    fun openGenre(genre: GenreOption) {
        _selectedGenre.value = genre
        viewModelScope.launch {
            _isGenreLoading.value = true
            _genreMovies.value = emptyList()
            try {
                // Sortiramo po ocjeni publike (avg_rating desc) i broju recenzija
                val sortOrder = if (genre.title == "Top 10 ovog mjeseca") "month desc" else "avg_rating desc"
                val rows = if (genre.title == "Top 10 ovog mjeseca") 10 else 60
                val results = fetchSafe(genre.query, sort = sortOrder, rows = rows)
                _genreMovies.value = results
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isGenreLoading.value = false
            }
        }
    }

    private suspend fun fetchSafe(
        query: String,
        sort: String = "downloads desc",
        rows: Int = 20
    ): List<RatedMovie> {
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

                val ratingVal = when (val r = doc.avgRating) {
                    is Number -> r.toDouble()
                    is String -> r.toDoubleOrNull() ?: 0.0
                    else -> 0.0
                }
                val ratingStr = if (ratingVal > 0.0) String.format("%.1f", ratingVal) else ""

                RatedMovie(
                    movie = ArchiveMovie(identifier = id, title = finalTitle),
                    rating = ratingStr
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
