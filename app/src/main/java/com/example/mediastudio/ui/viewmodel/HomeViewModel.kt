package com.example.mediastudio.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediastudio.data.model.ArchiveMovie
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import kotlin.math.ceil
import kotlin.math.ln

enum class ContentTab {
    MOVIES, SERIES
}

data class GenreOption(
    val title: String,
    val query: String,
    val defaultSort: String = "downloads desc",
    val isAdultCategory: Boolean = false,
    val isAnimationCategory: Boolean = false,
    val isSeriesCategory: Boolean = false
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
    val runtime: Any?,
    @Json(name = "item_size") val itemSize: Any?,
    @Json(name = "avg_rating") val avgRating: Any?,
    @Json(name = "num_reviews") val numReviews: Any?
)

private interface ArchiveSearchApi {
    @GET("advancedsearch.php")
    suspend fun searchMovies(
        @Query("q") query: String,
        @Query("fl[]") fields: List<String> = listOf(
            "identifier", "title", "creator", "description", "collection",
            "runtime", "item_size", "avg_rating", "num_reviews"
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
        "orgy", "fetish", "nc-17", "alpha france", "alpha-france", "alphafrance",
        "servante perverse"
    )

    private val junkNonMovieKeywords = listOf(
        "speedrun", "longplay", "walkthrough", "playthrough", "gameplay",
        "snes", "nes", "ps1", "ps2", "n64", "gameboy", "arcade",
        "lecture", "seminar", "webinar", "conference", "symposium", "tutorial", "course",
        "electricsheep", "capcut", "beat-edits", "dvd cover", "dvd covers", "site tour",
        "youtube videos", "kaczynski", "writings collection", "manifesto"
    )

    private val notableCinemaKeywords = listOf(
        "universal", "warner", "paramount", "mgm", "metro-goldwyn", "rko", "columbia",
        "20th century", "united artists", "disney", "fleischer", "republic pictures",
        "looney", "merrie melodies", "popeye", "superman", "felix the cat", "casper",
        "alpha france", "alpha-france", "alphafrance", "nagisa oshima", "jess franco",
        "lucio fulci", "max pecas", "joe d'amato", "tinto brass", "radley metzger",
        "hitchcock", "chaplin", "buster keaton", "fritz lang", "orson welles", "john ford",
        "howard hawks", "frank capra", "billy wilder", "george romero", "roger corman",
        "cary grant", "john wayne", "humphrey bogart", "james stewart", "vincent price",
        "bela lugosi", "boris karloff", "peter lorre", "gary cooper", "ingrid bergman",
        "audrey hepburn", "christopher lee", "peter cushing", "stan laurel", "oliver hardy",
        "imdb", "academy award", "oscar"
    )

    private val safeFilter = "-subject:(erotic OR erotica OR xxx OR porn) " +
            "AND -title:(erotic OR erotica OR xxx OR porn OR \"realm of the senses\" OR \"bad girl\") " +
            "AND -description:(pornography OR hardcore OR \"explicit sex\") " +
            "AND -collection:(adultvhs OR mensmagazines_post70s)"

    private val noJunkFilter = "-title:(speedrun OR longplay OR walkthrough OR gameplay OR snes OR lecture OR seminar OR electricsheep OR capcut OR \"dvd cover\" OR youtube OR kaczynski) " +
            "AND -subject:(speedrun OR gameplay OR lecture OR education)"

    private val featureMoviesBase = "mediatype:movies AND collection:feature_films AND item_size:[380000000 TO *] AND -collection:(movie_trailers OR previews) AND $noJunkFilter AND $safeFilter"

    val movieGenreDefinitions = listOf(
        GenreOption("Top 10 prošlog tjedna", "mediatype:movies AND collection:feature_films AND -collection:(movie_trailers OR previews) AND $noJunkFilter AND $safeFilter", "week desc"),
        GenreOption("Najgledanije", featureMoviesBase, "downloads desc"),
        GenreOption("Sci-Fi klasici", "$featureMoviesBase AND (subject:sci-fi OR subject:\"science fiction\" OR title:planet OR title:space OR collection:sci_fi_horror)"),
        GenreOption("Akcija i avantura", "$featureMoviesBase AND (subject:action OR subject:adventure OR subject:martial OR subject:swashbuckler)"),
        GenreOption("Triler i misterija", "$featureMoviesBase AND (subject:thriller OR subject:mystery OR subject:noir OR subject:suspense OR collection:film_noir)"),
        GenreOption("Komedija", "$featureMoviesBase AND (subject:comedy OR subject:comedies OR subject:humor OR collection:comedy_films)"),
        GenreOption("Horor", "$featureMoviesBase AND (subject:horror OR subject:monster OR subject:zombie OR subject:vampire OR subject:slasher)"),
        GenreOption("Drama", "$featureMoviesBase AND (subject:drama OR subject:melodrama)"),
        GenreOption("Krimi", "$featureMoviesBase AND (subject:crime OR subject:gangster OR subject:detective OR subject:heist)"),
        GenreOption("Romantični", "$featureMoviesBase AND (subject:romance OR subject:romantic OR subject:love)"),
        GenreOption("Vestern", "$featureMoviesBase AND (subject:western OR subject:westerns OR subject:cowboy)"),
        GenreOption("Ratni", "$featureMoviesBase AND (subject:war OR subject:military OR subject:\"world war\")"),
        GenreOption(
            title = "Animirani klasici",
            query = "mediatype:movies AND -collection:(movie_trailers OR previews) AND $noJunkFilter AND $safeFilter AND (subject:animation OR subject:cartoon OR subject:animated OR collection:animationandcartoons)",
            defaultSort = "downloads desc",
            isAnimationCategory = true
        ),
        GenreOption("Dokumentarni", "$featureMoviesBase AND (subject:documentary OR subject:documentaries)"),
        GenreOption(
            title = "18+ / Erotski",
            query = "mediatype:movies AND item_size:[250000000 TO *] AND $noJunkFilter AND (" +
                    "collection:(adultvhs OR mensmagazines_post70s) OR " +
                    "subject:(erotic OR erotica OR softcore OR sexploitation OR \"adult film\") OR " +
                    "title:(\"alpha france\" OR \"alpha-france\" OR \"realm of the senses\" OR \"empire des sens\" OR \"servante perverse\" OR \"bad girl\" OR \"emmanuelle\" OR \"caligula\" OR \"salon kitty\") OR " +
                    "creator:(\"alpha france\" OR \"jess franco\" OR \"tinto brass\" OR \"max pecas\" OR \"nagisa oshima\" OR \"joe d'amato\" OR \"radley metzger\") OR " +
                    "description:(\"alpha france\" OR \"empire des sens\" OR \"realm of the senses\")" +
                    ") AND -title:\"adult swim\" AND -subject:(cartoon OR lecture)",
            defaultSort = "downloads desc",
            isAdultCategory = true
        )
    )

    private val seriesBase = "mediatype:movies AND (subject:(\"tv series\" OR \"television series\" OR \"cartoon collection\") OR title:(\"season\" OR \"complete series\" OR \"cartoon collection\" OR \"episodes\")) AND $noJunkFilter AND $safeFilter"

    val seriesGenreDefinitions = listOf(
        GenreOption("Top 10 serija prošlog tjedna", seriesBase, "week desc", isSeriesCategory = true),
        GenreOption("Animirane kolekcije i crtići", "mediatype:movies AND (title:(\"cartoon collection\" OR \"looney tunes\" OR \"popeye\" OR \"tom and jerry\" OR \"pink panther\" OR \"flintstones\" OR \"scooby\") OR collection:animationandcartoons) AND $noJunkFilter AND $safeFilter", "downloads desc", isAnimationCategory = true, isSeriesCategory = true),
        GenreOption("Krimi, detektivske i triler serije", "$seriesBase AND (subject:(crime OR detective OR mystery OR thriller OR noir) OR title:(sherlock OR hitchcock OR dragnet OR perry mason))", "downloads desc", isSeriesCategory = true),
        GenreOption("Sci-Fi i fantastika serije", "$seriesBase AND (subject:(sci-fi OR \"science fiction\" OR fantasy) OR title:(twilight zone OR flash gordon OR star trek OR outer limits))", "downloads desc", isSeriesCategory = true),
        GenreOption("Komedije i sitcomi", "$seriesBase AND (subject:(comedy OR sitcom) OR title:(three stooges OR lucy OR beverly hillbillies OR laurel and hardy))", "downloads desc", isSeriesCategory = true),
        GenreOption("Vestern i avanturističke serije", "$seriesBase AND (subject:(western OR adventure) OR title:(bonanza OR lone ranger OR zorro OR robin hood))", "downloads desc", isSeriesCategory = true)
    )

    private val _selectedTab = MutableStateFlow(ContentTab.MOVIES)
    val selectedTab: StateFlow<ContentTab> = _selectedTab

    private val _movieCategories = MutableStateFlow<List<Pair<GenreOption, List<ArchiveMovie>>>>(emptyList())
    val movieCategories: StateFlow<List<Pair<GenreOption, List<ArchiveMovie>>>> = _movieCategories

    private val _seriesCategories = MutableStateFlow<List<Pair<GenreOption, List<ArchiveMovie>>>>(emptyList())
    val seriesCategories: StateFlow<List<Pair<GenreOption, List<ArchiveMovie>>>> = _seriesCategories

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _globalSearchResults = MutableStateFlow<List<RatedMovie>>(emptyList())
    val globalSearchResults: StateFlow<List<RatedMovie>> = _globalSearchResults

    private val _isSearchingGlobal = MutableStateFlow(false)
    val isSearchingGlobal: StateFlow<Boolean> = _isSearchingGlobal

    private var searchJob: Job? = null

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
        loadAllContent()
    }

    fun selectTab(tab: ContentTab) {
        _selectedTab.value = tab
    }

    fun performGlobalSearch(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 3) {
            _globalSearchResults.value = emptyList()
            _isSearchingGlobal.value = false
            return
        }

        searchJob = viewModelScope.launch {
            delay(450L)
            _isSearchingGlobal.value = true
            try {
                val words = trimmed.split(Regex("\\s+")).filter { it.length >= 2 }
                val tokenClause = if (words.isNotEmpty()) {
                    words.joinToString(" AND ") { w -> "(title:$w* OR creator:$w* OR description:$w*)" }
                } else {
                    "(title:\"$trimmed\" OR creator:\"$trimmed\")"
                }

                val archiveQuery = "mediatype:movies AND item_size:[150000000 TO *] AND $noJunkFilter AND ($tokenClause)"
                val (results, _) = fetchAndRankByRelevance(
                    query = archiveQuery,
                    apiSort = "downloads desc",
                    rows = 80,
                    page = 1,
                    excludeAdult = false,
                    isAdultCategory = false,
                    isAnimationCategory = true,
                    preserveApiOrder = false
                )
                _globalSearchResults.value = results
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isSearchingGlobal.value = false
            }
        }
    }

    private fun isAdultContent(id: String, title: String): Boolean {
        val combined = "${id.lowercase()} ${title.lowercase()}"
        return blockedAdultKeywords.any { keyword -> combined.contains(keyword) }
    }

    private fun loadAllContent() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val movieDeferred = movieGenreDefinitions.map { genre ->
                    async {
                        val isWeeklyTop = genre.title.startsWith("Top 10")
                        val (results, _) = fetchAndRankByRelevance(
                            query = genre.query,
                            apiSort = genre.defaultSort,
                            rows = if (isWeeklyTop) 120 else 70,
                            page = 1,
                            excludeAdult = !genre.isAdultCategory,
                            isAdultCategory = genre.isAdultCategory,
                            isAnimationCategory = genre.isAnimationCategory,
                            preserveApiOrder = isWeeklyTop
                        )
                        genre to results.map { it.movie }.take(if (isWeeklyTop) 10 else 20)
                    }
                }

                val seriesDeferred = seriesGenreDefinitions.map { genre ->
                    async {
                        val isWeeklyTop = genre.title.startsWith("Top 10")
                        val (results, _) = fetchAndRankByRelevance(
                            query = genre.query,
                            apiSort = genre.defaultSort,
                            rows = if (isWeeklyTop) 120 else 70,
                            page = 1,
                            excludeAdult = true,
                            isAdultCategory = false,
                            isAnimationCategory = genre.isAnimationCategory,
                            preserveApiOrder = isWeeklyTop
                        )
                        genre to results.map { it.movie }.take(if (isWeeklyTop) 10 else 20)
                    }
                }

                _movieCategories.value = movieDeferred.map { it.await() }.filter { it.second.isNotEmpty() }
                _seriesCategories.value = seriesDeferred.map { it.await() }.filter { it.second.isNotEmpty() }
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
                val isWeeklyTop = genre.title.startsWith("Top 10")
                val fetchRows = if (isWeeklyTop) 120 else 80
                val targetLimit = if (isWeeklyTop) 10 else pageSize

                val (results, totalFound) = fetchAndRankByRelevance(
                    query = genre.query,
                    apiSort = if (isWeeklyTop) "week desc" else "downloads desc",
                    rows = fetchRows,
                    page = page,
                    excludeAdult = !genre.isAdultCategory,
                    isAdultCategory = genre.isAdultCategory,
                    isAnimationCategory = genre.isAnimationCategory,
                    preserveApiOrder = isWeeklyTop
                )

                _genreMovies.value = results.take(targetLimit)

                if (isWeeklyTop) {
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
        isAnimationCategory: Boolean,
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

                if (junkNonMovieKeywords.any { kw -> fullMetaLower.contains(kw) }) {
                    return@mapNotNull null
                }

                if (excludeAdult && isAdultContent(id, finalTitle)) {
                    return@mapNotNull null
                }

                if (isAdultCategory && titleLower.contains("adult swim")) {
                    return@mapNotNull null
                }

                val isTrailerOrClip = titleLower.contains("trailer") ||
                        titleLower.contains("teaser") ||
                        titleLower.contains("tv spot")
                if (isTrailerOrClip) return@mapNotNull null

                val runtimeMinutes = parseRuntimeMinutes(doc.runtime)
                if (runtimeMinutes in 1..25 && !isAnimationCategory && !preserveApiOrder && !isAdultCategory) {
                    return@mapNotNull null
                }

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

                val itemSizeMb = parseSizeMb(doc.itemSize)
                var score = 0.0

                if (collectionText.lowercase().contains("feature_films")) score += 90.0
                if (runtimeMinutes >= 65) score += 170.0
                if (itemSizeMb >= 600) score += 140.0
                if (itemSizeMb in 1..150 && !isAnimationCategory) score -= 240.0

                if (ratingVal > 0.0 && reviewsVal > 0) {
                    score += ratingVal * ln(reviewsVal.toDouble() + 1.5) * 8.0
                }

                val studioMatches = notableCinemaKeywords.count { kw -> fullMetaLower.contains(kw) }
                score += studioMatches * 35.0

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

    private fun parseRuntimeMinutes(runtimeValue: Any?): Int {
        val raw = extractText(runtimeValue).trim()
        if (raw.isEmpty()) return 0

        if (raw.contains(":")) {
            val parts = raw.split(":").mapNotNull { it.trim().toDoubleOrNull()?.toInt() }
            return when (parts.size) {
                3 -> parts[0] * 60 + parts[1]
                2 -> parts[0]
                else -> 0
            }
        }

        val numeric = raw.toDoubleOrNull() ?: return 0
        return if (numeric > 300) (numeric / 60.0).toInt() else numeric.toInt()
    }

    private fun parseSizeMb(sizeValue: Any?): Long {
        val bytes = when (sizeValue) {
            is Number -> sizeValue.toLong()
            is String -> sizeValue.trim().toLongOrNull() ?: 0L
            else -> 0L
        }
        return bytes / (1024L * 1024L)
    }

    private fun extractText(value: Any?): String {
        return when (value) {
            is String -> value.trim()
            is List<*> -> value.filterNotNull().joinToString(" ").trim()
            else -> ""
        }
    }
}
