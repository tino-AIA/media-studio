package com.example.mediastudio.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediastudio.data.model.ArchiveMovie
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import kotlin.math.ceil
import kotlin.math.ln

enum class ContentTab {
    MOVIES, SERIES, CONCERTS, FAVORITES
}

data class GenreOption(
    val title: String,
    val query: String,
    val defaultSort: String = "downloads desc",
    val isAdultCategory: Boolean = false,
    val isAnimationCategory: Boolean = false,
    val isSeriesCategory: Boolean = false,
    val isConcertCategory: Boolean = false
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
    @Json(name = "num_reviews") val numReviews: Any?,
    val language: Any?
)

private interface ArchiveSearchApi {
    @GET("advancedsearch.php")
    suspend fun searchMovies(
        @Query("q") query: String,
        @Query("fl[]") fields: List<String> = listOf(
            "identifier", "title", "creator", "description", "collection",
            "runtime", "item_size", "avg_rating", "num_reviews", "language"
        ),
        @Query("sort[]") sort: String = "downloads desc",
        @Query("rows") rows: Int = 120,
        @Query("page") page: Int = 1,
        @Query("output") output: String = "json"
    ): ArchiveSearchResponse
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    
    private val prefs = application.getSharedPreferences("MediaStudioPrefs", Context.MODE_PRIVATE)
    
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val favoritesAdapter = moshi.adapter<List<ArchiveMovie>>(
        Types.newParameterizedType(List::class.java, ArchiveMovie::class.java)
    )

    private val api: ArchiveSearchApi = Retrofit.Builder()
        .baseUrl("https://archive.org/")
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(ArchiveSearchApi::class.java)

    // 18+ Filter Keywords (from previous steps)
    private val blockedAdultKeywords = listOf(
        "erotic", "erotica", "xxx", "porn", "porno", "pornography",
        "realm of the senses", "empire des sens", "good girl bad girl",
        "tammy stones", "softcore", "hardcore", "playboy", "penthouse",
        "orgy", "fetish", "nc-17", "alpha france", "alpha-france", "alphafrance",
        "servante perverse", "nsfw", "nude", "naked", "sex", "masturbating", 
        "hot", "amateur home video", "vintage porn", "erotik film", "baise moi", 
        "fick mich", "schulmädchen"
    )

    // Junk Filter Keywords
    private val junkNonMovieKeywords = listOf(
        "speedrun", "longplay", "walkthrough", "playthrough", "gameplay",
        "snes", "nes", "ps1", "ps2", "n64", "gameboy", "arcade",
        "lecture", "seminar", "webinar", "conference", "symposium", "tutorial", "course",
        "electricsheep", "capcut", "beat-edits", "dvd cover", "dvd covers", "site tour",
        "youtube videos", "kaczynski", "writings collection", "manifesto", "home movie", "home movies",
        "news broadcast", "entertainment tonight", "news &", "amateur clip", "raw footage", "vlog",
        "late night with", "jimmy fallon", "talk show", "interview", "podcast", "news report", "breaking news",
        "news cast", "newscast", "weather report", "commercials", "ads", "advertisements"
    )

    // Premium Studios & Franchises for Ranking Boost
    private val notableCinemaKeywords = listOf(
        "universal", "warner", "paramount", "mgm", "metro-goldwyn", "rko", "columbia",
        "20th century", "united artists", "disney", "fleischer", "republic pictures",
        "toho", "kurosawa", "mizoguchi", "ozu", "ghibli", "miyazaki", "anime", 
        "imdb", "academy award", "oscar", "bafta", "cannes", "criterion", "blockbuster",
        "star trek", "star wars", "batman", "superman", "spider-man", "james bond",
        "indiana jones", "lord of the rings", "harry potter", "marvel", "dc comics"
    )

    // Desired Languages for Premium Content
    private val premiumLanguages = listOf("eng", "english", "fre", "french", "ger", "german", "spa", "spanish", "ita", "italian", "jpn", "japanese")

    // --- SIMPLIFIED QUERIES ---
    // We rely on Kotlin logic to filter and rank, so the API query just needs to grab a large pool of potential movies.
    private val baseMovieQuery = "mediatype:movies AND item_size:[400000000 TO *] AND -collection:(movie_trailers OR previews OR short_format_films)"

    val movieGenreDefinitions = listOf(
        GenreOption("Top 10 danas", "mediatype:movies AND item_size:[600000000 TO *] AND -collection:(movie_trailers OR previews)", "downloads desc"),
        GenreOption("Klasici i Najgledanije", baseMovieQuery, "downloads desc"),
        GenreOption("Sci-Fi klasici", "$baseMovieQuery AND (subject:sci-fi OR subject:\"science fiction\" OR collection:sci_fi_horror)"),
        GenreOption("Akcija i avantura", "$baseMovieQuery AND (subject:action OR subject:adventure)"),
        GenreOption("Triler i misterija", "$baseMovieQuery AND (subject:thriller OR subject:mystery OR subject:noir OR collection:film_noir)"),
        GenreOption("Komedija", "$baseMovieQuery AND (subject:comedy OR collection:comedy_films)"),
        GenreOption("Horor", "$baseMovieQuery AND (subject:horror OR subject:vampire OR subject:zombie)"),
        GenreOption("Drama", "$baseMovieQuery AND subject:drama"),
        GenreOption("Krimi", "$baseMovieQuery AND (subject:crime OR subject:gangster OR subject:detective)"),
        GenreOption("Romantični", "$baseMovieQuery AND (subject:romance OR subject:romantic)"),
        GenreOption("Vestern", "$baseMovieQuery AND subject:western"),
        GenreOption("Ratni", "$baseMovieQuery AND (subject:war OR subject:military)"),
        GenreOption("Animirani klasici", "$baseMovieQuery AND (subject:animation OR subject:cartoon OR collection:animationandcartoons)", isAnimationCategory = true),
        GenreOption("Dokumentarni", "$baseMovieQuery AND subject:documentary"),
        
        GenreOption(
            title = "18+ / Erotski",
            query = "mediatype:movies AND item_size:[300000000 TO *] AND (" +
                    "collection:(adultvhs OR mensmagazines_post70s) OR " +
                    "subject:(erotic OR erotica OR softcore OR sexploitation OR \"adult film\" OR porn OR \"vintage porn\" OR nsfw OR \"erotic cinema\" OR \"classic adult\" OR \"retro porn\" OR \"cult adult\" OR \"adult vhs\") OR " +
                    "creator:(\"alpha france\" OR \"jess franco\" OR \"tinto brass\" OR \"max pecas\" OR \"nagisa oshima\" OR \"joe d'amato\" OR \"radley metzger\" OR \"eurociné\" OR \"ernst hofbauer\" OR \"virginie despentes\")" +
                    ") AND -title:\"adult swim\" AND -subject:(cartoon OR lecture)",
            defaultSort = "downloads desc",
            isAdultCategory = true
        )
    )

    private val seriesBase = "mediatype:movies AND item_size:[200000000 TO *] AND (subject:(\"tv series\" OR \"television series\") OR title:(season OR \"complete series\" OR episodes))"

    val seriesGenreDefinitions = listOf(
        GenreOption("Top 10 serija danas", seriesBase, "downloads desc", isSeriesCategory = true),
        GenreOption("Disney, WB i klasični crtići", "$seriesBase AND (title:(disney OR \"warner bros\" OR looney OR merrie OR popeye OR mickey))", "downloads desc", isAnimationCategory = true, isSeriesCategory = true),
        GenreOption("Krimi, detektivske i triler serije", "$seriesBase AND (subject:(crime OR detective OR mystery OR thriller OR noir))", "downloads desc", isSeriesCategory = true),
        GenreOption("Sci-Fi i fantastika serije", "$seriesBase AND (subject:(sci-fi OR \"science fiction\" OR fantasy))", "downloads desc", isSeriesCategory = true),
        GenreOption("Komedije i sitcomi", "$seriesBase AND (subject:(comedy OR sitcom))", "downloads desc", isSeriesCategory = true),
        GenreOption("Vestern i avanturističke serije", "$seriesBase AND (subject:(western OR adventure))", "downloads desc", isSeriesCategory = true)
    )

    private val concertBase = "mediatype:movies AND item_size:[200000000 TO *] AND (collection:(vhs_concerts_and_music OR music_video) OR subject:(concert OR \"live concert\" OR band OR tour OR mtv))"

    val concertGenreDefinitions = listOf(
        GenreOption("Najgledaniji Koncerti", concertBase, "downloads desc", isConcertCategory = true),
        GenreOption("Najnovije (Live)", concertBase, "addeddate desc", isConcertCategory = true),
        GenreOption("Rock i Metal", "$concertBase AND subject:(rock OR metal OR punk OR grunge)", "downloads desc", isConcertCategory = true),
        GenreOption("Pop glazba", "$concertBase AND subject:(pop OR \"pop music\")", "downloads desc", isConcertCategory = true),
        GenreOption("Klasika i Jazz", "$concertBase AND subject:(classical OR jazz OR blues OR orchestra OR symphony)", "downloads desc", isConcertCategory = true)
    )

    private val _selectedTab = MutableStateFlow(ContentTab.MOVIES)
    val selectedTab: StateFlow<ContentTab> = _selectedTab

    private val _movieCategories = MutableStateFlow<List<Pair<GenreOption, List<ArchiveMovie>>>>(emptyList())
    val movieCategories: StateFlow<List<Pair<GenreOption, List<ArchiveMovie>>>> = _movieCategories

    private val _seriesCategories = MutableStateFlow<List<Pair<GenreOption, List<ArchiveMovie>>>>(emptyList())
    val seriesCategories: StateFlow<List<Pair<GenreOption, List<ArchiveMovie>>>> = _seriesCategories

    private val _concertCategories = MutableStateFlow<List<Pair<GenreOption, List<ArchiveMovie>>>>(emptyList())
    val concertCategories: StateFlow<List<Pair<GenreOption, List<ArchiveMovie>>>> = _concertCategories

    private val _heroMovies = MutableStateFlow<List<Pair<ArchiveMovie, Boolean>>>(emptyList())
    val heroMovies: StateFlow<List<Pair<ArchiveMovie, Boolean>>> = _heroMovies

    private val _activeHeroIndex = MutableStateFlow(0)
    val activeHeroIndex: StateFlow<Int> = _activeHeroIndex

    private var carouselJob: Job? = null

    private val _favoriteMovies = MutableStateFlow<List<ArchiveMovie>>(emptyList())
    val favoriteMovies: StateFlow<List<ArchiveMovie>> = _favoriteMovies

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
        loadFavorites()
        loadAllContent()
    }

    private fun loadFavorites() {
        val json = prefs.getString("favorites", "[]")
        if (json != null) {
            try {
                val list = favoritesAdapter.fromJson(json) ?: emptyList()
                _favoriteMovies.value = list
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun isFavorite(identifier: String): Boolean {
        return _favoriteMovies.value.any { it.identifier == identifier }
    }

    fun toggleFavorite(movie: ArchiveMovie) {
        val currentList = _favoriteMovies.value.toMutableList()
        val exists = currentList.any { it.identifier == movie.identifier }
        
        if (exists) {
            currentList.removeAll { it.identifier == movie.identifier }
        } else {
            currentList.add(movie)
        }
        
        _favoriteMovies.value = currentList
        prefs.edit().putString("favorites", favoritesAdapter.toJson(currentList)).apply()
        
        refreshHeroItems(
            currentList, 
            _movieCategories.value, 
            _seriesCategories.value, 
            _concertCategories.value
        )
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

                val archiveQuery = "mediatype:movies AND item_size:[150000000 TO *] AND ($tokenClause)"
                val (results, _) = fetchAndRankByRelevance(
                    query = archiveQuery,
                    apiSort = "downloads desc",
                    rows = 120,
                    page = 1,
                    excludeAdult = false,
                    isAdultCategory = false,
                    isAnimationCategory = true,
                    isSeriesCategory = _selectedTab.value == ContentTab.SERIES,
                    isConcertCategory = _selectedTab.value == ContentTab.CONCERTS
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
        val combined = "${id.lowercase()}${title.lowercase()}"
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
                            rows = if (isWeeklyTop) 200 else 150, // Fetch more to allow Kotlin to filter heavily
                            page = 1,
                            excludeAdult = !genre.isAdultCategory,
                            isAdultCategory = genre.isAdultCategory,
                            isAnimationCategory = genre.isAnimationCategory,
                            isSeriesCategory = false,
                            isConcertCategory = false
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
                            rows = if (isWeeklyTop) 200 else 150,
                            page = 1,
                            excludeAdult = true,
                            isAdultCategory = false,
                            isAnimationCategory = genre.isAnimationCategory,
                            isSeriesCategory = true,
                            isConcertCategory = false
                        )
                        genre to results.map { it.movie }.take(if (isWeeklyTop) 10 else 20)
                    }
                }

                val concertDeferred = concertGenreDefinitions.map { genre ->
                    async {
                        val (results, _) = fetchAndRankByRelevance(
                            query = genre.query,
                            apiSort = genre.defaultSort,
                            rows = 150,
                            page = 1,
                            excludeAdult = true,
                            isAdultCategory = false,
                            isAnimationCategory = false,
                            isSeriesCategory = false,
                            isConcertCategory = true
                        )
                        genre to results.map { it.movie }.take(20)
                    }
                }

                val loadedMovies = movieDeferred.map { it.await() }.filter { it.second.isNotEmpty() }
                val loadedSeries = seriesDeferred.map { it.await() }.filter { it.second.isNotEmpty() }
                val loadedConcerts = concertDeferred.map { it.await() }.filter { it.second.isNotEmpty() }

                _movieCategories.value = loadedMovies
                _seriesCategories.value = loadedSeries
                _concertCategories.value = loadedConcerts

                refreshHeroItems(_favoriteMovies.value, loadedMovies, loadedSeries, loadedConcerts)
                
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun refreshHeroItems(
        favs: List<ArchiveMovie>,
        movies: List<Pair<GenreOption, List<ArchiveMovie>>>,
        series: List<Pair<GenreOption, List<ArchiveMovie>>>,
        concerts: List<Pair<GenreOption, List<ArchiveMovie>>>
    ) {
        val topFavs = favs.take(4).map { it to (it.title.lowercase().contains("season") || it.title.lowercase().contains("episode")) }
        val topM = (movies.firstOrNull { it.first.title.contains("Top 10") }?.second?.take(3) ?: emptyList()).map { it to false }
        val topS = (series.firstOrNull { it.first.title.contains("Top 10") }?.second?.take(3) ?: emptyList()).map { it to true }
        val topC = (concerts.firstOrNull()?.second?.take(1) ?: emptyList()).map { it to false }

        val combined = (topFavs + topM + topS + topC).distinctBy { it.first.identifier }
        _heroMovies.value = combined
        
        startHeroCarousel()
    }

    private fun startHeroCarousel() {
        carouselJob?.cancel()
        _activeHeroIndex.value = 0
        carouselJob = viewModelScope.launch {
            while (isActive && _heroMovies.value.isNotEmpty()) {
                delay(8000) 
                _activeHeroIndex.value = (_activeHeroIndex.value + 1) % _heroMovies.value.size
            }
        }
    }

    fun setActiveHeroIndex(index: Int) {
        if (index in _heroMovies.value.indices) {
            _activeHeroIndex.value = index
            startHeroCarousel() 
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
                val fetchRows = if (isWeeklyTop) 200 else 150
                val targetLimit = if (isWeeklyTop) 10 else pageSize

                val (results, totalFound) = fetchAndRankByRelevance(
                    query = genre.query,
                    apiSort = if (isWeeklyTop) "week desc" else "downloads desc",
                    rows = fetchRows,
                    page = page,
                    excludeAdult = !genre.isAdultCategory,
                    isAdultCategory = genre.isAdultCategory,
                    isAnimationCategory = genre.isAnimationCategory,
                    isSeriesCategory = genre.isSeriesCategory,
                    isConcertCategory = genre.isConcertCategory
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
        isSeriesCategory: Boolean,
        isConcertCategory: Boolean
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
                val langText = extractText(doc.language).lowercase()
                val titleLower = finalTitle.lowercase()
                val fullMetaLower = "$id $finalTitle$creatorText $descText$collectionText".lowercase()

                // HARD FILTERS (If it fails these, drop it immediately)
                
                // 1. Junk / News / Shorts
                if (junkNonMovieKeywords.any { kw -> fullMetaLower.contains(kw) }) return@mapNotNull null
                
                // 2. Adult Content filtering
                if (excludeAdult && isAdultContent(id, finalTitle)) return@mapNotNull null
                if (isAdultCategory && titleLower.contains("adult swim")) return@mapNotNull null

                // 3. Language Check (Prefer English, European, Japanese for Movies/Series. Ignore for Concerts/Adult)
                if (!isConcertCategory && !isAdultCategory && langText.isNotBlank()) {
                    val isPremiumLang = premiumLanguages.any { langText.contains(it) }
                    // If language is specified and it's not a premium language, we might want to drop it, 
                    // but for now let's just heavily penalize it in the scoring instead of dropping, 
                    // unless it explicitly matches Bollywood/regional tags.
                    if (fullMetaLower.contains("bollywood") || fullMetaLower.contains("tollywood") || fullMetaLower.contains("hindi")) {
                        return@mapNotNull null
                    }
                }

                // 4. Runtime (Must be > 30 mins)
                val runtimeMinutes = parseRuntimeMinutes(doc.runtime)
                if (runtimeMinutes in 1..29 && !isAnimationCategory) { // Allow short animations (Looney Tunes etc)
                    return@mapNotNull null
                }
                
                // Cross-category contamination blocks
                if (!isSeriesCategory && (titleLower.contains("season ") || titleLower.contains("episode "))) return@mapNotNull null
                if (isConcertCategory && (titleLower.contains("season ") || titleLower.contains("episode ") || fullMetaLower.contains("tv series"))) return@mapNotNull null

                // --- SCORING SYSTEM ---
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

                // IMDB / Rating Boost
                if (ratingVal > 0.0) {
                    score += ratingVal * 1000.0 
                    if (reviewsVal > 0) {
                        score += ln(reviewsVal.toDouble() + 1.0) * 300.0 
                    }
                } else {
                    score += 500.0
                }
                if (fullMetaLower.contains("imdb")) score += 1500.0

                // Premium Source Collections Boost (From the images you provided)
                if (collectionText.lowercase().contains("feature_films")) score += 4000.0
                if (collectionText.lowercase().contains("miscellaneous_feature_films")) score += 4000.0
                if (collectionText.lowercase().contains("moviesandfilms")) score += 4000.0
                if (collectionText.lowercase().contains("popcornproject")) score += 4000.0
                if (collectionText.lowercase().contains("vimeo-video")) score += 3500.0
                if (collectionText.lowercase().contains("televisionmovies")) score += 3500.0
                if (collectionText.lowercase().contains("classic_tv_1990s")) score += 3500.0
                if (collectionText.lowercase().contains("classic_tv_1980s")) score += 3500.0
                if (collectionText.lowercase().contains("television_inbox")) score += 2000.0
                if (collectionText.lowercase().contains("dvdtray")) score += 3500.0
                if (collectionText.lowercase().contains("laserdiscarchive")) score += 4500.0
                if (collectionText.lowercase().contains("colorized-and-upscaled-movies")) score += 4500.0
                if (collectionText.lowercase().contains("sci_fi_horror")) score += 3000.0
                if (collectionText.lowercase().contains("comedy_films")) score += 3000.0

                // Franchise & Studio Boost
                val studioMatches = notableCinemaKeywords.count { kw -> fullMetaLower.contains(kw) }
                score += studioMatches * 1000.0 

                // Runtime Boost (Reward full-length features)
                if (runtimeMinutes >= 70) score += 1000.0
                if (runtimeMinutes >= 100) score += 1500.0 
                
                // File Size Boost (Reward high quality rips)
                if (itemSizeMb >= 800) score += 1000.0
                if (itemSizeMb >= 2000) score += 1500.0 // 2GB+ usually means great quality

                val ratingStr = if (ratingVal > 0.0 && reviewsVal >= 1) String.format("%.1f", ratingVal) else ""

                RatedMovie(
                    movie = ArchiveMovie(identifier = id, title = finalTitle),
                    rating = ratingStr,
                    relevanceScore = score
                )
            }

            val finalSorted = if (isSeriesCategory) {
                val grouped = scoredList.groupBy { cleanSeriesTitleForGrouping(it.movie.title) }
                val mergedList = grouped.map { (baseTitle, group) ->
                    val mergedIds = group.joinToString(",") { it.movie.identifier }
                    val bestItem = group.maxByOrNull { it.relevanceScore } ?: group.first()
                    RatedMovie(
                        movie = ArchiveMovie(identifier = mergedIds, title = baseTitle),
                        rating = bestItem.rating,
                        relevanceScore = bestItem.relevanceScore
                    )
                }
                mergedList.sortedByDescending { it.relevanceScore }
            } else {
                scoredList.sortedByDescending { it.relevanceScore }
            }
            
            finalSorted to numFound
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList<RatedMovie>() to 0
        }
    }

    private fun cleanSeriesTitleForGrouping(rawTitle: String): String {
        return rawTitle
            .replace(Regex("(?i)(complete series|full series|season\\s*\\d+|seasons\\s*\\d+.*|uncensored|collection|colorized|\\(\\d{4}\\))"), "")
            .replace(Regex("(?i)\\b(part\\s*\\d+|vol\\.?\\s*\\d+|series\\s*\\d+)\\b.*"), "")
            .replace(Regex("(?i)\\bthe\\s+\\d+(st|nd|rd|th)\\s+season\\b.*"), "") 
            .replace(Regex("(?i)complete\\b"), "")
            .replace(Regex("(?i)presents\\s*\\d+\\s*episodes?.*"), "Presents")
            .replace(Regex("(?i)\\bS\\d{1,2}E\\d{1,3}\\b.*"), "") 
            .replace(Regex("(?i)\\bEpisode[s]?\\s*\\d+.*"), "") 
            .replace(Regex("(?i)\\d+\\s*episodes?.*"), "")
            .replace(Regex("(?i)all\\s*\\d+\\s*eps.*"), "") 
            .replace(Regex("[^a-zA-Z0-9\\s']"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { rawTitle }
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
