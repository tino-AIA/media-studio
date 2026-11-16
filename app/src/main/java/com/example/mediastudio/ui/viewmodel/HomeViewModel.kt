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

data class CachedCategory(val genreTitle: String, val movies: List<ArchiveMovie>)
data class CachedHero(val movie: ArchiveMovie, val isSeries: Boolean)
data class AppCache(
    val movieCategories: List<CachedCategory>,
    val seriesCategories: List<CachedCategory>,
    val concertCategories: List<CachedCategory>,
    val heroMovies: List<CachedHero>
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
        @Query("rows") rows: Int = 150,
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
    
    private val cacheAdapter = moshi.adapter(AppCache::class.java)

    private val api: ArchiveSearchApi = Retrofit.Builder()
        .baseUrl("https://archive.org/")
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(ArchiveSearchApi::class.java)

    private val blockedAdultKeywords = listOf(
        "erotic", "erotica", "xxx", "porn", "porno", "pornography", "adult film",
        "realm of the senses", "empire des sens", "good girl bad girl", "softcore",
        "hardcore", "playboy", "penthouse", "orgy", "fetish", "nc-17", "alpha france",
        "nsfw", "nude", "naked", "masturbating", "vintage porn", "erotik film",
        "baise moi", "fick mich", "schulmädchen", "sexfilm", "adultvhs"
    )

    private val blockedSpecificAdultKeywords = listOf(
        "hentai", "yaoi", "gay", "boy love", "bl", "gay porn", "homosexual"
    )

    private val junkNonMovieKeywords = listOf(
        "speedrun", "gameplay", "walkthrough", "playthrough", "podcast", "tutorial",
        "news broadcast", "entertainment tonight", "news report", "breaking news", "newscast",
        "islam", "quran", "khutba", "dourous", "muslim", "allah", "sheikh",
        "bollywood", "tollywood", "hindi", "telugu", "tamil", "urdu", "punjabi", "desi",
        "bengali", "malayalam", "marathi", "gujarati", "arab", "arabic",
        "amateur", "vlog", "raw footage", "home movie", "home video", "commercials",
        "mtv raps", "mtv hits", "mtv trl", "compilation", "mixtape", "dvd transfer", "vhv transfer"
    )

    private val notableCinemaKeywords = listOf(
        "universal", "warner", "paramount", "mgm", "20th century", "disney", "columbia",
        "hbo", "showtime", "cinemax", "lucasfilm", "marvel", "dc comics", "criterion",
        "bbc", "channel 4", "film noir", "cult classic", "masterpiece",
        "kurosawa", "ghibli", "miyazaki", "tarantino", "scorsese", "spielberg", "hitchcock",
        "coppola", "cameron", "nolan", "kubrick", "ridley scott", "george lucas", "peter jackson",
        "marlon brando", "al pacino", "robert de niro", "jack nicholson", "morgan freeman",
        "tom hanks", "leonardo dicaprio", "brad pitt", "tom cruise", "harrison ford", "keanu reeves",
        "imdb", "oscar", "bafta", "cannes", "blockbuster", "academy award", "hollywood"
    )

    private val legendaryTitles = listOf(
        "godfather", "matrix", "avatar", "star wars", "lord of the rings", "jurassic park",
        "terminator", "alien", "back to the future", "indiana jones", "die hard", "rocky",
        "star trek", "everybody loves raymond", "3rd rock from the sun", "third rock from the sun",
        "seinfeld", "friends", "the simpsons", "x-files", "sopranos", "the wire", "breaking bad",
        "doctor who", "twilight zone", "batman", "superman", "spider-man", "james bond", "harry potter",
        "alf", "roots", "monty python", "aeon flux", "hitchhiker's guide", "shogun", "galactica",
        "v the series", "alien nation"
    )
    
    private val movieBaseQuery = "mediatype:movies AND (collection:feature_films OR collection:SciFi_Films OR collection:colorized-and-upscaled-movies OR collection:laserdiscarchive OR collection:vhsvault OR collection:vhs_movies OR collection:miscellaneous_feature_films OR collection:popcornproject OR collection:vimeo-video OR collection:comedy_films OR collection:film_noir) AND -subject:news"

    val movieGenreDefinitions = listOf(
        GenreOption("Top 10 danas", movieBaseQuery, "downloads desc"),
        GenreOption("Klasici i Najgledanije", movieBaseQuery, "downloads desc"),
        GenreOption("Sci-Fi klasici", "$movieBaseQuery AND (subject:sci-fi OR subject:\"science fiction\" OR collection:sci_fi_horror)"),
        GenreOption("Akcija i avantura", "$movieBaseQuery AND (subject:action OR subject:adventure OR subject:martial)"),
        GenreOption("Triler i misterija", "$movieBaseQuery AND (subject:thriller OR subject:mystery OR subject:noir OR subject:suspense)"),
        GenreOption("Komedija", "$movieBaseQuery AND (subject:comedy OR collection:comedy_films)"),
        GenreOption("Horor", "$movieBaseQuery AND (subject:horror OR subject:vampire OR subject:zombie)"),
        GenreOption("Drama", "$movieBaseQuery AND (subject:drama OR subject:melodrama)"),
        GenreOption("Krimi", "$movieBaseQuery AND (subject:crime OR subject:gangster OR subject:detective)"),
        GenreOption("Romantični", "$movieBaseQuery AND (subject:romance OR subject:romantic)"),
        GenreOption("Vestern", "$movieBaseQuery AND subject:western"),
        GenreOption("Ratni", "$movieBaseQuery AND (subject:war OR subject:military)"),
        GenreOption("Animirani klasici", "mediatype:movies AND (subject:animation OR subject:cartoon OR collection:animationandcartoons)", isAnimationCategory = true),
        GenreOption("Disney, WB i klasični crtići", "mediatype:movies AND (title:(disney OR \"warner bros\" OR looney OR merrie OR popeye OR mickey OR \"bugs bunny\"))", "downloads desc", isAnimationCategory = true),
        GenreOption(
            title = "18+ / Erotski",
            query = "mediatype:movies AND (collection:(adultvhs OR mensmagazines_post70s) OR subject:(erotica OR porn OR \"adult film\" OR nsfw OR \"vintage porn\") OR creator:(\"alpha france\" OR \"jess franco\" OR \"tinto brass\"))",
            defaultSort = "downloads desc",
            isAdultCategory = true
        )
    )

    private val seriesBaseQuery = "mediatype:movies AND (collection:classic_tv_1990s OR collection:classic_tv_1980s OR collection:classic_tv_1970s OR collection:television_inbox OR collection:televisionmovies OR subject:\"tv series\" OR subject:\"television series\")"

    val seriesGenreDefinitions = listOf(
        GenreOption("Top 10 serija danas", seriesBaseQuery, "week desc", isSeriesCategory = true),
        GenreOption("Krimi, detektivske i triler serije", "$seriesBaseQuery AND (subject:crime OR subject:detective OR subject:mystery OR subject:thriller)", "downloads desc", isSeriesCategory = true),
        GenreOption("Sci-Fi i fantastika serije", "$seriesBaseQuery AND (subject:sci-fi OR subject:\"science fiction\" OR subject:fantasy)", "downloads desc", isSeriesCategory = true),
        GenreOption("Komedije i sitcomi", "$seriesBaseQuery AND (subject:comedy OR subject:sitcom)", "downloads desc", isSeriesCategory = true),
        GenreOption("Avanturističke serije", "$seriesBaseQuery AND subject:adventure", "downloads desc", isSeriesCategory = true)
    )

    private val concertBaseQuery = "mediatype:movies AND collection:vhs_concerts_and_music"

    val concertGenreDefinitions = listOf(
        GenreOption("Top 10 Koncerti", concertBaseQuery, "week desc", isConcertCategory = true),
        GenreOption("Rock i Metal", "$concertBaseQuery AND subject:(rock OR metal OR punk OR grunge OR \"hard rock\")", "downloads desc", isConcertCategory = true),
        GenreOption("Pop glazba", "$concertBaseQuery AND subject:(pop OR \"pop music\")", "downloads desc", isConcertCategory = true),
        GenreOption("Klasika i Jazz", "$concertBaseQuery AND subject:(classical OR jazz OR blues OR orchestra OR symphony)", "downloads desc", isConcertCategory = true)
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

    private val _currentSearchQuery = MutableStateFlow("")
    val currentSearchQuery: StateFlow<String> = _currentSearchQuery

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
    
    private var lastLoadedCache: AppCache? = null

    init {
        loadFavorites()
        loadCacheAndFetchNew()
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

    private fun loadCacheAndFetchNew() {
        val cachedJson = prefs.getString("app_content_cache", null)
        if (cachedJson != null) {
            try {
                val cache = cacheAdapter.fromJson(cachedJson)
                if (cache != null) {
                    lastLoadedCache = cache 
                    
                    val mMovies = cache.movieCategories.mapNotNull { c -> 
                        movieGenreDefinitions.find { it.title == c.genreTitle }?.let { it to c.movies } 
                    }
                    val mSeries = cache.seriesCategories.mapNotNull { c -> 
                        seriesGenreDefinitions.find { it.title == c.genreTitle }?.let { it to c.movies } 
                    }
                    val mConcerts = cache.concertCategories.mapNotNull { c -> 
                        concertGenreDefinitions.find { it.title == c.genreTitle }?.let { it to c.movies } 
                    }
                    
                    if (mMovies.isNotEmpty()) _movieCategories.value = mMovies
                    if (mSeries.isNotEmpty()) _seriesCategories.value = mSeries
                    if (mConcerts.isNotEmpty()) _concertCategories.value = mConcerts
                    
                    if (cache.heroMovies.isNotEmpty()) {
                        _heroMovies.value = cache.heroMovies.map { it.movie to it.isSeries }
                        startHeroCarousel()
                    }
                    _isLoading.value = false 
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        loadAllContent()
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
        refreshHeroItems(currentList, _movieCategories.value, _seriesCategories.value, _concertCategories.value)
    }

    fun selectTab(tab: ContentTab) {
        _selectedTab.value = tab
    }
    
    fun updateSearchQuery(query: String) {
        _currentSearchQuery.value = query
        performGlobalSearch(query)
    }

    fun performGlobalSearch(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        _currentSearchQuery.value = trimmed

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

                val archiveQuery = "mediatype:movies AND ($tokenClause)"
                val (results, _) = fetchAndRankByRelevance(
                    query = archiveQuery,
                    apiSort = "downloads desc",
                    rows = 150,
                    page = 1,
                    excludeAdult = false,
                    isAdultCategory = false,
                    isAnimationCategory = true,
                    isSeriesCategory = _selectedTab.value == ContentTab.SERIES,
                    isConcertCategory = _selectedTab.value == ContentTab.CONCERTS,
                    searchQueryForRanking = trimmed
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

    private fun isBannedSpecificAdultContent(id: String, title: String, desc: String): Boolean {
        val combined = "${id.lowercase()} ${title.lowercase()} ${desc.lowercase()}"
        return blockedSpecificAdultKeywords.any { keyword -> combined.contains(keyword) }
    }

    private fun loadAllContent() {
        viewModelScope.launch {
            if (_movieCategories.value.isEmpty()) {
                _isLoading.value = true
            }
            
            try {
                val movieDeferred = movieGenreDefinitions.map { genre ->
                    async {
                        val isWeeklyTop = genre.title.startsWith("Top 10")
                        val (results, _) = fetchAndRankByRelevance(
                            query = genre.query,
                            apiSort = genre.defaultSort,
                            rows = if (isWeeklyTop) 300 else 150, 
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
                            rows = if (isWeeklyTop) 300 else 150,
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
                        val isWeeklyTop = genre.title.startsWith("Top 10")
                        val (results, _) = fetchAndRankByRelevance(
                            query = genre.query,
                            apiSort = genre.defaultSort,
                            rows = if (isWeeklyTop) 300 else 150,
                            page = 1,
                            excludeAdult = true,
                            isAdultCategory = false,
                            isAnimationCategory = false,
                            isSeriesCategory = false,
                            isConcertCategory = true
                        )
                        genre to results.map { it.movie }.take(if (isWeeklyTop) 10 else 20)
                    }
                }

                val loadedMovies = movieDeferred.map { it.await() }.filter { it.second.isNotEmpty() }
                val loadedSeries = seriesDeferred.map { it.await() }.filter { it.second.isNotEmpty() }
                val loadedConcerts = concertDeferred.map { it.await() }.filter { it.second.isNotEmpty() }

                _movieCategories.value = loadedMovies
                _seriesCategories.value = loadedSeries
                _concertCategories.value = loadedConcerts

                refreshHeroItems(_favoriteMovies.value, loadedMovies, loadedSeries, loadedConcerts)
                
                val newCache = AppCache(
                    movieCategories = loadedMovies.map { CachedCategory(it.first.title, it.second) },
                    seriesCategories = loadedSeries.map { CachedCategory(it.first.title, it.second) },
                    concertCategories = loadedConcerts.map { CachedCategory(it.first.title, it.second) },
                    heroMovies = _heroMovies.value.map { CachedHero(it.first, it.second) }
                )
                lastLoadedCache = newCache 
                prefs.edit().putString("app_content_cache", cacheAdapter.toJson(newCache)).apply()
                
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
        
        val cachedContent = when {
            genre.isConcertCategory -> _concertCategories.value.find { it.first.title == genre.title }?.second
            genre.isSeriesCategory -> _seriesCategories.value.find { it.first.title == genre.title }?.second
            else -> _movieCategories.value.find { it.first.title == genre.title }?.second
        }

        if (cachedContent != null && cachedContent.isNotEmpty()) {
            _genreMovies.value = cachedContent.map { RatedMovie(it, "", 0.0) }
            _totalGenreMoviesCount.value = cachedContent.size
            _totalGenrePages.value = 1
        } else {
            _genreMovies.value = emptyList()
        }
        
        loadGenrePage(1)
    }

    fun loadGenrePage(page: Int) {
        val genre = _selectedGenre.value ?: return
        if (page < 1) return

        viewModelScope.launch {
            if (_genreMovies.value.isEmpty()) {
                _isGenreLoading.value = true
            }
            
            _currentGenrePage.value = page
            try {
                val isWeeklyTop = genre.title.startsWith("Top 10")
                val fetchRows = if (isWeeklyTop) 300 else 150
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
        isConcertCategory: Boolean,
        searchQueryForRanking: String? = null
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
                val fullMetaLower = "$id $finalTitle$creatorText $descText$collectionText".lowercase()

                if (junkNonMovieKeywords.any { kw -> fullMetaLower.contains(kw) }) return@mapNotNull null
                
                if (isBannedSpecificAdultContent(id, finalTitle, descText)) return@mapNotNull null
                if (excludeAdult && isAdultContent(id, finalTitle)) return@mapNotNull null
                if (isAdultCategory && titleLower.contains("adult swim")) return@mapNotNull null

                val isTrailerOrClip = titleLower.contains("trailer") || titleLower.contains("teaser") || titleLower.contains("tv spot")
                if (isTrailerOrClip) return@mapNotNull null

                val runtimeMinutes = parseRuntimeMinutes(doc.runtime)
                if (runtimeMinutes in 1..29 && !isAnimationCategory && !isSeriesCategory) {
                    return@mapNotNull null
                }
                
                if (isConcertCategory) {
                    if (runtimeMinutes in 1..39) return@mapNotNull null 
                    if (titleLower.contains("season ") || titleLower.contains("episode ") || fullMetaLower.contains("tv series")) return@mapNotNull null
                    if (titleLower.contains("news") || titleLower.contains("documentary") || titleLower.contains("music video")) return@mapNotNull null
                }

                if (!isSeriesCategory && (titleLower.contains("season ") || titleLower.contains("episode "))) return@mapNotNull null

                var score = 0.0

                if (searchQueryForRanking != null && searchQueryForRanking.isNotBlank()) {
                    if (titleLower.contains(searchQueryForRanking.lowercase())) {
                        score += 10000.0 
                    }
                }

                val legendaryMatches = legendaryTitles.count { titleLower.contains(it) }
                score += legendaryMatches * 5000.0 

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

                if (ratingVal > 0.0) {
                    score += ratingVal * 1500.0 
                    if (reviewsVal > 0) score += ln(reviewsVal.toDouble() + 1.0) * 400.0 
                } else {
                    score += 500.0
                }

                val premiumHits = notableCinemaKeywords.count { fullMetaLower.contains(it) }
                score += premiumHits * 2500.0 

                if (collectionText.lowercase().contains("laserdiscarchive")) score += 3000.0
                if (collectionText.lowercase().contains("colorized-and-upscaled-movies")) score += 3000.0
                if (collectionText.lowercase().contains("vhsvault")) score += 2000.0
                if (collectionText.lowercase().contains("feature_films")) score += 1500.0
                if (collectionText.lowercase().contains("televisionmovies")) score += 1500.0
                if (collectionText.lowercase().contains("classic_tv")) score += 1500.0
                
                if (runtimeMinutes >= 75) score += 1000.0
                if (runtimeMinutes >= 100) score += 1500.0 
                
                if (isConcertCategory && itemSizeMb < 400) return@mapNotNull null 
                if (itemSizeMb >= 800) score += 1000.0
                if (itemSizeMb >= 2000) score += 1500.0 

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
