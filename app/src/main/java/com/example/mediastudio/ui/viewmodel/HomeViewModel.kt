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
import kotlin.math.ceil
import kotlin.math.ln

data class GenreOption(
    val title: String,
    val query: String,
    val defaultSort: String = "downloads desc",
    val isAdultCategory: Boolean = false,
    val isAnimationCategory: Boolean = false
)

data class RatedMovie(
    val movie: ArchiveMovie,
    val rating: String,
    val relevanceScore: Double = 0.0
)

private data class ArchiveSearchResponse(val response: ArchiveResponseBody?)
private data class ArchiveResponseBody(
    val numFound: Int?,
    val docs: List<RawArchiveDoc>?
)
private data class RawArchiveDoc(
    val identifier: String?,
    val title: Any?,
    val creator: Any?,
    val description: Any?,
    val collection: Any?,
    @Json(name = "avg_rating") val avgRating: Any?,
    @Json(name = "num_reviews") val numReviews: Any?
)

private interface ArchiveSearchApi {
    @GET("advancedsearch.php")
    suspend fun searchMovies(
        @Query("q") query: String,
        @Query("fl[]") fields: List<String> = listOf(
            "identifier", "title", "creator", "description", "collection", "avg_rating", "num_reviews"
        ),
        @Query("sort[]") sort: String = "downloads desc",
        @Query("rows") rows: Int = 60,
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

    private val blockedAdultKeywords = listOf(
        "erotic", "erotica", "xxx", "porn", "porno", "pornography",
        "realm of the senses", "empire des sens", "good girl bad girl",
        "tammy stones", "softcore", "hardcore", "playboy", "penthouse",
        "orgy", "fetish", "nc-17"
    )

    // Poznati studiji, redatelji i glumci za podizanje relevantnosti pravih filmova na vrh
    private val notableCinemaKeywords = listOf(
        "universal", "warner", "paramount", "mgm", "metro-goldwyn", "rko", "columbia",
        "20th century", "united artists", "disney", "fleischer", "republic pictures",
        "looney", "merrie melodies", "popeye", "superman", "felix the cat", "casper",
        "hitchcock", "chaplin", "buster keaton", "fritz lang", "orson welles", "john ford",
        "howard hawks", "frank capra", "billy wilder", "george romero", "roger corman",
        "cary grant", "john wayne", "humphrey bogart", "james stewart", "vincent price",
        "bela lugosi", "boris karloff", "peter lorre", "gary cooper", "ingrid bergman",
        "audrey hepburn", "christopher lee", "peter cushing", "stan laurel", "oliver hardy",
        "imdb", "academy award", "oscar"
    )

    private val safeFilter = "-subject:(erotic OR erotica OR xxx OR porn) " +
            "AND -title:(erotic OR erotica OR xxx OR porn OR \"realm of the senses\" OR \"bad girl\") " +
            "AND -description:(pornography OR hardcore OR \"explicit sex\")"

    // Cijela baza igranih filmova (uključuje feature_films, publicdomainmovies, vhsvault, film_noir itd.)
    private val moviesBase = "mediatype:movies AND -collection:(movie_trailers OR previews) AND $safeFilter"

    val genreDefinitions = listOf(
        GenreOption("Top 10 ovog mjeseca", "collection:feature_films AND $moviesBase", "month desc"),
        GenreOption("Najgledanije", moviesBase, "downloads desc"),
        GenreOption("Sci-Fi klasici", "$moviesBase AND (subject:sci-fi OR subject:\"science fiction\" OR title:planet OR title:space OR collection:sci_fi_horror)"),
        GenreOption("Akcija i avantura", "$moviesBase AND (subject:action OR subject:adventure OR subject:martial OR subject:swashbuckler)"),
        GenreOption("Triler i misterija", "$moviesBase AND (subject:thriller OR subject:mystery OR subject:noir OR subject:suspense OR collection:film_noir)"),
        GenreOption("Komedija", "$moviesBase AND (subject:comedy OR subject:comedies OR subject:humor OR collection:comedy_films)"),
        GenreOption("Horor", "$moviesBase AND (subject:horror OR subject:monster OR subject:zombie OR subject:vampire OR subject:slasher)"),
        GenreOption("Drama", "$moviesBase AND (subject:drama OR subject:melodrama)"),
        GenreOption("Krimi", "$moviesBase AND (subject:crime OR subject:gangster OR subject:detective OR subject:heist)"),
        GenreOption("Romantični", "$moviesBase AND (subject:romance OR subject:romantic OR subject:love)"),
        GenreOption("Vestern", "$moviesBase AND (subject:western OR subject:westerns OR subject:cowboy)"),
        GenreOption("Ratni", "$moviesBase AND (subject:war OR subject:military OR subject:\"world war\")"),
        GenreOption(
            title = "Animirani klasici",
            query = "$moviesBase AND (subject:animation OR subject:cartoon OR subject:animated OR collection:animationandcartoons)",
            defaultSort = "downloads desc",
            isAnimationCategory = true
        ),
        GenreOption("Dokumentarni", "$moviesBase AND (subject:documentary OR subject:documentaries)"),
        GenreOption(
            title = "18+ / Erotski",
            query = "mediatype:movies AND (subject:erotic OR subject:erotica OR subject:adult OR subject:softcore OR subject:sexploitation OR title:\"realm of the senses\" OR title:\"empire des sens\" OR title:\"bad girl\" OR title:\"alpha france\" OR creator:\"alpha france\" OR description:\"alpha france\") AND -title:\"adult swim\" AND -subject:(animation OR anime OR cartoon)",
            defaultSort = "downloads desc",
            isAdultCategory = true
        )
    )

    private val _homeCategories = MutableStateFlow<List<Pair<GenreOption, List<ArchiveMovie>>>>(emptyList())
    val homeCategories: StateFlow<List<Pair<GenreOption, List<ArchiveMovie>>>> = _homeCategories

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    // Stanja za zasebni ekran žanra s paginacijom (60 filmova po stranici)
    private val _selectedGenre = MutableStateFlow<GenreOption?>(null)
    val selectedGenre: StateFlow<GenreOption?> = _selectedGenre

    private val _genreMovies = MutableStateFlow<List<RatedMovie>>(emptyList())
    val genreMovies: StateFlow<List<RatedMovie>> = _genreMovies

    private val _isGenreLoading = MutableStateFlow(false)
    val isGenreLoading: StateFlow<Boolean> = _isGenreLoading

    private val _currentGenrePage = MutableStateFlow(1)
    val currentGenrePage: StateFlow<Int> = _currentGenrePage

    private val _totalGenrePages = MutableStateFlow(1)
    val totalGenrePages: StateFlow<Int> = _totalGenrePages

    private val _totalGenreMoviesCount = MutableStateFlow(0)
    val totalGenreMoviesCount: StateFlow<Int> = _totalGenreMoviesCount

    val pageSize = 60

    init {
        loadAllCategories()
    }

    private fun isAdultContent(id: String, title: String): Boolean {
        val combined = "${id.lowercase()} ${title.lowercase()}"
        return blockedAdultKeywords.any { keyword -> combined.contains(keyword) }
    }

    private fun loadAllCategories() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val deferredList = genreDefinitions.map { genre ->
                    async {
                        val isMonthlyTop = genre.title == "Top 10 ovog mjeseca"
                        val fetchRows = if (isMonthlyTop) 45 else 45
                        val targetLimit = if (isMonthlyTop) 10 else 20

                        val (results, _) = fetchAndRankByRelevance(
                            query = genre.query,
                            apiSort = genre.defaultSort,
                            rows = fetchRows,
                            page = 1,
                            excludeAdult = !genre.isAdultCategory,
                            isAdultCategory = genre.isAdultCategory,
                            preserveApiOrder = isMonthlyTop
                        )
                        genre to results.map { it.movie }.take(targetLimit)
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

    fun openGenre(genre: GenreOption) {
        _selectedGenre.value = genre
        loadGenrePage(1)
    }

    fun loadGenrePage(page: Int) {
        val genre = _selectedGenre.value ?: return
        if (page < 1) return

        viewModelScope.launch {
            _isGenreLoading.value = true
            _currentGenrePage.value = page
            try {
                val isMonthlyTop = genre.title == "Top 10 ovog mjeseca"
                val fetchRows = if (isMonthlyTop) 45 else 70
                val targetLimit = if (isMonthlyTop) 10 else pageSize

                val (results, totalFound) = fetchAndRankByRelevance(
                    query = genre.query,
                    apiSort = if (isMonthlyTop) "month desc" else "downloads desc",
                    rows = fetchRows,
                    page = page,
                    excludeAdult = !genre.isAdultCategory,
                    isAdultCategory = genre.isAdultCategory,
                    preserveApiOrder = isMonthlyTop
                )

                _genreMovies.value = results.take(targetLimit)

                if (isMonthlyTop) {
                    _totalGenreMoviesCount.value = 10
                    _totalGenrePages.value = 1
                } else {
                    _totalGenreMoviesCount.value = totalFound
                    val calculatedPages = ceil(totalFound.toDouble() / pageSize.toDouble()).toInt().coerceAtLeast(1)
                    _totalGenrePages.value = calculatedPages.coerceAtMost(100)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isGenreLoading.value = false
            }
        }
    }

    private suspend fun fetchAndRankByRelevance(
        query: String,
        apiSort: String,
        rows: Int,
        page: Int,
        excludeAdult: Boolean,
        isAdultCategory: Boolean,
        preserveApiOrder: Boolean
    ): Pair<List<RatedMovie>, Int> {
        return try {
            val responseBody = api.searchMovies(query = query, sort = apiSort, rows = rows, page = page).response
            val docs = responseBody?.docs ?: emptyList()
            val numFound = responseBody?.numFound ?: docs.size

            val scoredList = docs.mapNotNull { doc ->
                val id = doc.identifier?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val rawTitle = extractText(doc.title)
                val finalTitle = rawTitle.ifBlank {
                    id.replace("_", " ").replace("-", " ").replaceFirstChar { it.uppercase() }
                }

                val creatorText = extractText(doc.creator)
                val descText = extractText(doc.description)
                val collectionText = extractText(doc.collection)
                val titleLower = finalTitle.lowercase()
                val fullMetaLower = "$id $finalTitle $creatorText $descText $collectionText".lowercase()

                // 1. Zaštita od 18+ u standardnim kategorijama
                if (excludeAdult && isAdultContent(id, finalTitle)) {
                    return@mapNotNull null
                }

                // 2. U 18+ kategoriji izbacujemo Adult Swim, anime i crtiće
                if (isAdultCategory) {
                    val isAnimeOrCartoon = fullMetaLower.contains("adult swim") ||
                            fullMetaLower.contains("anime") ||
                            fullMetaLower.contains("cartoon") ||
                            fullMetaLower.contains("animation")
                    if (isAnimeOrCartoon) return@mapNotNull null
                }

                // 3. Provjeravamo samo naslov za trailere/najave kako ne bismo odbacili filmove koji u opisu spominju trailer
                val isTrailerOrClip = titleLower.contains("trailer") ||
                        titleLower.contains("teaser") ||
                        titleLower.contains("tv spot")
                if (isTrailerOrClip) return@mapNotNull null

                val ratingVal = when (val r = doc.avgRating) {
                    is Number -> r.toDouble()
                    is String -> r.toDoubleOrNull() ?: 0.0
                    else -> 0.0
                }
                val reviewsVal = when (val rev = doc.numReviews) {
                    is Number -> rev.toInt()
                    is String -> rev.toIntOrNull() ?: 0
                    else -> 0
                }

                // Bonus za dugometražne igrane filmove
                var score = if (collectionText.lowercase().contains("feature_films")) 50.0 else 0.0

                // Ocjena ponderirana brojem recenzija
                if (ratingVal > 0.0 && reviewsVal > 0) {
                    score += ratingVal * ln(reviewsVal.toDouble() + 1.5) * 8.0
                }

                // Bonus za poznate studije, redatelje, glumce i IMDb reference
                val studioMatches = notableCinemaKeywords.count { kw -> fullMetaLower.contains(kw) }
                score += studioMatches * 18.0

                if (descText.length > 80) score += 10.0
                if (creatorText.isNotBlank()) score += 8.0

                val ratingStr = if (ratingVal > 0.0 && reviewsVal >= 2) {
                    String.format("%.1f", ratingVal)
                } else {
                    ""
                }

                RatedMovie(
                    movie = ArchiveMovie(identifier = id, title = finalTitle),
                    rating = ratingStr,
                    relevanceScore = score
                )
            }

            val finalSorted = if (preserveApiOrder) {
                scoredList
            } else {
                scoredList.sortedByDescending { it.relevanceScore }
            }
            finalSorted to numFound
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList<RatedMovie>() to 0
        }
    }

    private fun extractText(value: Any?): String {
        return when (value) {
            is String -> value.trim()
            is List<*> -> value.filterNotNull().joinToString(" ").trim()
            else -> ""
        }
    }
}
