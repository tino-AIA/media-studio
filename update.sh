#!/bin/bash
set -e

cat << 'EOF' > app/src/main/java/com/example/mediastudio/data/api/ArchiveMetadataService.kt
package com.example.mediastudio.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

data class VideoQualityOption(
    val label: String,
    val url: String,
    val height: Int,
    val sizeBytes: Long
)

data class MovieFullInfo(
    val qualities: List<VideoQualityOption>,
    val description: String,
    val year: String,
    val director: String
)

data class SeriesEpisode(
    val episodeNumber: Int,
    val title: String,
    val fileUrl: String,
    val durationText: String
)

object ArchiveMetadataModule {
    suspend fun fetchMovieFullInfo(identifier: String): MovieFullInfo = withContext(Dispatchers.IO) {
        try {
            val jsonStr = URL("https://archive.org/metadata/$identifier").readText()
            val root = JSONObject(jsonStr)
            val metadata = root.optJSONObject("metadata")

            val rawDesc = when (val d = metadata?.opt("description")) {
                is String -> d
                is org.json.JSONArray -> (0 until d.length()).joinToString("\n") { d.optString(it) }
                else -> ""
            }
            val cleanDesc = rawDesc
                .replace(Regex("<[^>]*>"), " ")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&apos;", "'")
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifEmpty { "Opis za ovaj naslov trenutno nije dostupan." }

            val rawDate = metadata?.optString("date", "")?.takeIf { it.isNotEmpty() }
                ?: metadata?.optString("year", "") ?: ""
            val year = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(rawDate)?.value ?: ""

            val rawDirector = when (val c = metadata?.opt("director") ?: metadata?.opt("creator")) {
                is String -> c
                is org.json.JSONArray -> (0 until c.length()).joinToString(", ") { c.optString(it) }
                else -> ""
            }

            val filesArray = root.optJSONArray("files")
            val qualities = mutableListOf<VideoQualityOption>()

            if (filesArray != null) {
                for (i in 0 until filesArray.length()) {
                    val fileObj = filesArray.optJSONObject(i) ?: continue
                    val name = fileObj.optString("name")
                    val format = fileObj.optString("format")

                    if (name.endsWith(".mp4", ignoreCase = true) ||
                        format.contains("MPEG4", ignoreCase = true) ||
                        format.contains("h.264", ignoreCase = true)
                    ) {
                        val height = fileObj.optString("height").toIntOrNull() ?: 0
                        val sizeBytes = fileObj.optString("size").toLongOrNull() ?: 0L
                        val sizeMb = sizeBytes / (1024 * 1024)

                        if (sizeMb < 5 && height == 0) continue

                        val resLabel = when {
                            height >= 1080 -> "1080p Full HD"
                            height >= 720 -> "720p HD"
                            height >= 480 -> "480p SD"
                            height > 0 -> "${height}p"
                            else -> "Standard"
                        }

                        val sizeText = if (sizeMb >= 1024) {
                            String.format("%.1f GB", sizeMb / 1024.0)
                        } else if (sizeMb > 0) {
                            "$sizeMb MB"
                        } else {
                            ""
                        }

                        val fullLabel = if (sizeText.isNotEmpty()) "$resLabel ($sizeText)" else resLabel
                        val encodedName = name.split("/").joinToString("/") { part ->
                            java.net.URLEncoder.encode(part, "UTF-8").replace("+", "%20")
                        }
                        val url = "https://archive.org/download/$identifier/$encodedName"

                        qualities.add(
                            VideoQualityOption(
                                label = fullLabel,
                                url = url,
                                height = height,
                                sizeBytes = sizeBytes
                            )
                        )
                    }
                }
            }

            val sortedQualities = qualities
                .distinctBy { it.label }
                .sortedWith(compareByDescending<VideoQualityOption> { it.height }.thenByDescending { it.sizeBytes })

            MovieFullInfo(
                qualities = sortedQualities,
                description = cleanDesc,
                year = year,
                director = rawDirector.trim()
            )
        } catch (e: Exception) {
            e.printStackTrace()
            MovieFullInfo(emptyList(), "Opis nije moguće učitati.", "", "")
        }
    }

    suspend fun fetchAvailableQualities(identifier: String): List<VideoQualityOption> {
        return fetchMovieFullInfo(identifier).qualities
    }

    suspend fun fetchSeriesEpisodes(identifier: String): List<SeriesEpisode> = withContext(Dispatchers.IO) {
        try {
            val jsonStr = URL("https://archive.org/metadata/$identifier").readText()
            val root = JSONObject(jsonStr)
            val filesArray = root.optJSONArray("files") ?: return@withContext emptyList()

            val rawEpisodes = mutableListOf<Pair<String, SeriesEpisode>>()

            for (i in 0 until filesArray.length()) {
                val fileObj = filesArray.optJSONObject(i) ?: continue
                val name = fileObj.optString("name")
                val source = fileObj.optString("source")

                if (!name.endsWith(".mp4", ignoreCase = true)) continue
                if (source.equals("derivative", ignoreCase = true) && name.contains("_512kb", ignoreCase = true)) continue

                val sizeBytes = fileObj.optString("size").toLongOrNull() ?: 0L
                if (sizeBytes < 5_000_000L) continue

                val rawTitle = fileObj.optString("title").takeIf { it.isNotBlank() }
                    ?: name.substringAfterLast("/").substringBeforeLast(".")
                        .replace("_", " ")
                        .replace("-", " ")
                        .trim()

                val lengthSec = fileObj.optString("length").toDoubleOrNull()?.toInt() ?: 0
                val durationText = if (lengthSec > 0) {
                    val mins = lengthSec / 60
                    val secs = lengthSec % 60
                    String.format("%d:%02d", mins, secs)
                } else {
                    ""
                }

                val encodedName = name.split("/").joinToString("/") { part ->
                    java.net.URLEncoder.encode(part, "UTF-8").replace("+", "%20")
                }
                val fileUrl = "https://archive.org/download/$identifier/$encodedName"

                rawEpisodes.add(
                    name.lowercase() to SeriesEpisode(
                        episodeNumber = 0,
                        title = rawTitle,
                        fileUrl = fileUrl,
                        durationText = durationText
                    )
                )
            }

            rawEpisodes
                .distinctBy { it.second.title.lowercase() }
                .sortedBy { it.first }
                .mapIndexed { idx, pair ->
                    pair.second.copy(episodeNumber = idx + 1)
                }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
EOF

sed -i 's/sl=en&tl=hr/sl=auto\&tl=hr/g' app/src/main/java/com/example/mediastudio/ui/screens/DetailsScreen.kt

cat << 'EOF' > app/src/main/java/com/example/mediastudio/ui/screens/SeriesScreen.kt
package com.example.mediastudio.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.example.mediastudio.data.api.ArchiveMetadataModule
import com.example.mediastudio.data.api.SeriesEpisode
import com.example.mediastudio.data.model.ArchiveMovie

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScreen(
    series: ArchiveMovie,
    onEpisodeClick: (SeriesEpisode) -> Unit,
    onBackClick: () -> Unit
) {
    var episodes by remember { mutableStateOf<List<SeriesEpisode>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    BackHandler {
        onBackClick()
    }

    LaunchedEffect(series.identifier) {
        isLoading = true
        episodes = ArchiveMetadataModule.fetchSeriesEpisodes(series.identifier)
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = series.title,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!isLoading) {
                            Text(
                                text = "Pronađeno ${episodes.size} epizoda / nastavaka",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Povratak", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        containerColor = Color.Black
    ) { paddingValues ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.Red)
            }
        } else if (episodes.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text("Nema dostupnih epizoda u ovoj kolekciji.", color = Color.Gray)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize().padding(paddingValues)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            painter = rememberAsyncImagePainter("https://archive.org/services/img/${series.identifier}"),
                            contentDescription = series.title,
                            modifier = Modifier
                                .width(100.dp)
                                .height(140.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.5.dp, Color.Gray, RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = series.title,
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Odaberi epizodu s popisa za izravnu reprodukciju.",
                                color = Color.LightGray,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                items(episodes) { episode ->
                    EpisodeRowItem(episode = episode, onClick = { onEpisodeClick(episode) })
                }
            }
        }
    }
}

@Composable
private fun EpisodeRowItem(
    episode: SeriesEpisode,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = if (isFocused) Color(0xFF2A2A2A) else Color(0xFF161616),
                shape = RoundedCornerShape(10.dp)
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) Color.White else Color.DarkGray,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = "${episode.episodeNumber}.",
                color = Color.Red,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier.width(34.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = episode.title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (episode.durationText.isNotEmpty()) {
                    Text(
                        text = "Trajanje: ${episode.durationText}",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "Pokreni epizodu",
            tint = Color.White,
            modifier = Modifier.size(26.dp)
        )
    }
}
EOF

python3 -c '
p = "app/src/main/java/com/example/mediastudio/ui/screens/PlayerScreen.kt"
s = open(p).read()
s = s.replace("movieYear: String = \"\",\n    onNavigateBack: () -> Unit", "movieYear: String = \"\",\n    directVideoUrl: String = \"\",\n    onNavigateBack: () -> Unit")
old = """    LaunchedEffect(identifier) {
        val qualities = ArchiveMetadataModule.fetchAvailableQualities(identifier)
        availableQualities = qualities
        val bestQuality = qualities.firstOrNull()
        selectedQuality = bestQuality
        val startUrl = bestQuality?.url ?: fallbackVideoUrl
        exoPlayer.setMediaItem(MediaItem.fromUri(startUrl), 0L)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }"""
new = """    LaunchedEffect(identifier, directVideoUrl) {
        if (directVideoUrl.isNotEmpty()) {
            availableQualities = emptyList()
            selectedQuality = null
            exoPlayer.setMediaItem(MediaItem.fromUri(directVideoUrl), 0L)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        } else {
            val qualities = ArchiveMetadataModule.fetchAvailableQualities(identifier)
            availableQualities = qualities
            val bestQuality = qualities.firstOrNull()
            selectedQuality = bestQuality
            val startUrl = bestQuality?.url ?: fallbackVideoUrl
            exoPlayer.setMediaItem(MediaItem.fromUri(startUrl), 0L)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }"""
assert old in s, "PlayerScreen block not found"
open(p, "w").write(s.replace(old, new))
'

cat << 'EOF' > app/src/main/java/com/example/mediastudio/ui/viewmodel/HomeViewModel.kt
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
        "lecture", "seminar", "webinar", "conference", "symposium", "tutorial", "course"
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

    private val noJunkFilter = "-title:(speedrun OR longplay OR walkthrough OR gameplay OR snes OR lecture OR seminar) " +
            "AND -subject:(speedrun OR gameplay OR lecture OR education)"

    private val moviesBase = "mediatype:movies AND item_size:[450000000 TO *] AND -collection:(movie_trailers OR previews) AND $noJunkFilter AND $safeFilter"

    val movieGenreDefinitions = listOf(
        GenreOption("Top 10 prošlog tjedna", "collection:feature_films AND $moviesBase", "week desc"),
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
            query = "mediatype:movies AND -collection:(movie_trailers OR previews) AND $noJunkFilter AND $safeFilter AND (subject:animation OR subject:cartoon OR subject:animated OR collection:animationandcartoons)",
            defaultSort = "downloads desc",
            isAnimationCategory = true
        ),
        GenreOption("Dokumentarni", "$moviesBase AND (subject:documentary OR subject:documentaries)"),
        GenreOption(
            title = "18+ / Erotski",
            query = "mediatype:movies AND item_size:[500000000 TO *] AND $noJunkFilter AND (" +
                    "collection:(adultvhs OR mensmagazines_post70s) OR " +
                    "subject:(erotic OR erotica OR softcore OR sexploitation) OR " +
                    "identifier:(*alpha-france* OR *alphafrance* OR *realm-of-the-senses* OR *empire-des-sens* OR *servante-perverse*) OR " +
                    "title:(\"alpha france\" OR \"realm of the senses\" OR \"empire des sens\" OR \"bad girl\" OR \"jess franco\" OR \"tinto brass\") OR " +
                    "creator:(\"alpha france\" OR \"nagisa oshima\" OR \"jess franco\" OR \"max pecas\" OR \"tinto brass\")" +
                    ") AND -title:\"adult swim\" AND -subject:(animation OR anime OR cartoon OR education OR lecture)",
            defaultSort = "downloads desc",
            isAdultCategory = true
        )
    )

    private val seriesBase = "(collection:(classic_tv OR television OR television_inbox) OR subject:(\"tv series\" OR \"television series\" OR season OR episodes OR collection)) AND $noJunkFilter AND $safeFilter"

    val seriesGenreDefinitions = listOf(
        GenreOption("Top 10 serija prošlog tjedna", seriesBase, "week desc", isSeriesCategory = true),
        GenreOption("Animirane kolekcije i crtići", "(title:(\"cartoon collection\" OR \"looney tunes\" OR \"popeye\" OR \"tom and jerry\" OR \"pink panther\" OR \"flintstones\" OR \"scooby\") OR collection:animationandcartoons) AND mediatype:movies AND $safeFilter", "downloads desc", isAnimationCategory = true, isSeriesCategory = true),
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
                val safeQuery = trimmed.replace("\"", "")
                val archiveQuery = "mediatype:movies AND $noJunkFilter AND (title:\"$safeQuery\" OR title:$safeQuery* OR identifier:*${safeQuery.replace(" ", "-")}* OR creator:\"$safeQuery\")"
                val (results, _) = fetchAndRankByRelevance(
                    query = archiveQuery,
                    apiSort = "downloads desc",
                    rows = 60,
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
                            rows = 45,
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
                            rows = 40,
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
                val fetchRows = if (isWeeklyTop) 45 else 75
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

                if (isAdultCategory) {
                    val isAnimeOrCartoon = fullMetaLower.contains("adult swim") ||
                            fullMetaLower.contains("anime") ||
                            fullMetaLower.contains("cartoon") ||
                            fullMetaLower.contains("animation")
                    if (isAnimeOrCartoon) return@mapNotNull null
                }

                val isTrailerOrClip = titleLower.contains("trailer") ||
                        titleLower.contains("teaser") ||
                        titleLower.contains("tv spot")
                if (isTrailerOrClip) return@mapNotNull null

                val runtimeMinutes = parseRuntimeMinutes(doc.runtime)
                if (runtimeMinutes in 1..25 && !isAnimationCategory) {
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

                if (collectionText.lowercase().contains("feature_films")) score += 80.0
                if (runtimeMinutes >= 65) score += 160.0
                if (itemSizeMb >= 700) score += 100.0

                if (ratingVal > 0.0 && reviewsVal > 0) {
                    score += ratingVal * ln(reviewsVal.toDouble() + 1.5) * 8.0
                }

                val studioMatches = notableCinemaKeywords.count { kw -> fullMetaLower.contains(kw) }
                score += studioMatches * 22.0

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
EOF

cat << 'EOF' > app/src/main/java/com/example/mediastudio/ui/screens/HomeScreen.kt
package com.example.mediastudio.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.example.mediastudio.data.model.ArchiveMovie
import com.example.mediastudio.ui.viewmodel.ContentTab
import com.example.mediastudio.ui.viewmodel.GenreOption
import com.example.mediastudio.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onMovieClick: (ArchiveMovie) -> Unit,
    onSeriesClick: (ArchiveMovie) -> Unit,
    onGenreSelect: (GenreOption) -> Unit
) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val movieCategories by viewModel.movieCategories.collectAsState()
    val seriesCategories by viewModel.seriesCategories.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val globalSearchResults by viewModel.globalSearchResults.collectAsState()
    val isSearchingGlobal by viewModel.isSearchingGlobal.collectAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }

    BackHandler(enabled = drawerState.isOpen || searchQuery.isNotEmpty()) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else if (searchQuery.isNotEmpty()) {
            searchQuery = ""
            viewModel.performGlobalSearch("")
        }
    }

    val activeCategories = if (selectedTab == ContentTab.MOVIES) movieCategories else seriesCategories
    val activeDrawerList = if (selectedTab == ContentTab.MOVIES) viewModel.movieGenreDefinitions else viewModel.seriesGenreDefinitions

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(260.dp),
                drawerContainerColor = Color(0xFF121212)
            ) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = if (selectedTab == ContentTab.MOVIES) "Žanrovi filmova" else "Kategorije serija",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(16.dp)
                    )
                    Divider(color = Color.DarkGray)

                    activeDrawerList.forEach { genre ->
                        NavigationDrawerItem(
                            label = {
                                Text(
                                    text = genre.title,
                                    color = if (genre.title.startsWith("18+")) Color(0xFFFF6B6B) else Color.White,
                                    fontSize = 15.sp
                                )
                            },
                            selected = false,
                            onClick = {
                                scope.launch {
                                    drawerState.close()
                                    onGenreSelect(genre)
                                }
                            },
                            colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent)
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text("Media Studio", color = Color.White, fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                    )

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            viewModel.performGlobalSearch(it)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        placeholder = { Text("Pretraži cijeli Archive.org (npr. Alpha, Chaplin...)", color = Color.Gray) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.Gray) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = {
                                    searchQuery = ""
                                    viewModel.performGlobalSearch("")
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Očisti", tint = Color.Gray)
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.Red,
                            unfocusedBorderColor = Color.DarkGray
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TabButton(
                            title = "🎬 Filmovi",
                            isSelected = selectedTab == ContentTab.MOVIES,
                            onClick = {
                                searchQuery = ""
                                viewModel.performGlobalSearch("")
                                viewModel.selectTab(ContentTab.MOVIES)
                            },
                            modifier = Modifier.weight(1f)
                        )
                        TabButton(
                            title = "📺 Serije i kolekcije",
                            isSelected = selectedTab == ContentTab.SERIES,
                            onClick = {
                                searchQuery = ""
                                viewModel.performGlobalSearch("")
                                viewModel.selectTab(ContentTab.SERIES)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            containerColor = Color.Black
        ) { paddingValues ->
            if (searchQuery.trim().length >= 3) {
                if (isSearchingGlobal) {
                    Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.Red)
                    }
                } else if (globalSearchResults.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                        Text("Nema rezultata za \"$searchQuery\"", color = Color.Gray)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 120.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize().padding(paddingValues)
                    ) {
                        items(globalSearchResults) { ratedItem ->
                            RatedMovieGridCard(
                                ratedMovie = ratedItem,
                                onMovieClick = { item ->
                                    if (selectedTab == ContentTab.SERIES) onSeriesClick(item) else onMovieClick(item)
                                }
                            )
                        }
                    }
                }
            } else if (isLoading) {
                Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.Red)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    items(activeCategories) { (genre, itemsList) ->
                        val isTop10 = genre.title.startsWith("Top 10")
                        MovieCategoryRow(
                            genre = genre,
                            movies = itemsList,
                            isNetflixTop10 = isTop10,
                            onMovieClick = { item ->
                                if (selectedTab == ContentTab.SERIES) onSeriesClick(item) else onMovieClick(item)
                            },
                            onHeaderClick = { onGenreSelect(genre) }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(30.dp)) }
                }
            }
        }
    }
}

@Composable
private fun TabButton(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .background(
                color = if (isSelected) Color.Red else Color(0xFF1A1A1A),
                shape = RoundedCornerShape(10.dp)
            )
            .border(
                width = if (isFocused) 2.5.dp else 1.dp,
                color = if (isFocused) Color.White else if (isSelected) Color.Red else Color.DarkGray,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun MovieCategoryRow(
    genre: GenreOption,
    movies: List<ArchiveMovie>,
    isNetflixTop10: Boolean = false,
    onMovieClick: (ArchiveMovie) -> Unit,
    onHeaderClick: () -> Unit
) {
    if (movies.isEmpty()) return
    val displayTitle = if (isNetflixTop10) "🔥 ${genre.title}" else genre.title
    val headerInteraction = remember { MutableInteractionSource() }
    val isHeaderFocused by headerInteraction.collectIsFocusedAsState()

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .border(
                    width = if (isHeaderFocused) 2.dp else 0.dp,
                    color = if (isHeaderFocused) Color.White else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                )
                .clickable(interactionSource = headerInteraction, indication = null) { onHeaderClick() }
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = displayTitle,
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Svi",
                    color = if (isHeaderFocused) Color.White else Color.Gray,
                    fontSize = 13.sp
                )
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Otvori žanr",
                    tint = if (isHeaderFocused) Color.White else Color.Gray,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isNetflixTop10) 16.dp else 12.dp)
        ) {
            itemsIndexed(movies) { index, movie ->
                if (isNetflixTop10) {
                    NetflixTop10Card(movie = movie, rankNumber = index + 1, onMovieClick = onMovieClick)
                } else {
                    MovieCard(movie = movie, onMovieClick = onMovieClick)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetflixTop10Card(movie: ArchiveMovie, rankNumber: Int, onMovieClick: (ArchiveMovie) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.08f else 1f, label = "top10_scale")
    val borderColor = if (isFocused) Color.White else Color.Gray.copy(alpha = 0.65f)
    val borderWidth = if (isFocused) 3.dp else 1.5.dp

    Column(modifier = Modifier.width(162.dp)) {
        Box(
            modifier = Modifier
                .width(162.dp)
                .height(175.dp)
                .scale(scale)
        ) {
            Card(
                modifier = Modifier
                    .width(115.dp)
                    .height(170.dp)
                    .align(Alignment.CenterEnd)
                    .border(borderWidth, borderColor, RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                interactionSource = interactionSource,
                onClick = { onMovieClick(movie) }
            ) {
                Image(
                    painter = rememberAsyncImagePainter("https://archive.org/services/img/${movie.identifier}"),
                    contentDescription = movie.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            val numberFontSize = if (rankNumber == 10) 86.sp else 96.sp
            val numberLetterSpacing = if (rankNumber == 10) (-7).sp else 0.sp

            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = 0.dp, y = 8.dp)
            ) {
                Text(
                    text = "$rankNumber",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = numberFontSize,
                        fontWeight = FontWeight.Black,
                        letterSpacing = numberLetterSpacing,
                        drawStyle = Stroke(
                            miter = 10f,
                            width = 8f,
                            join = StrokeJoin.Round
                        )
                    )
                )
                Text(
                    text = "$rankNumber",
                    style = TextStyle(
                        color = Color(0xFFE50914),
                        fontSize = numberFontSize,
                        fontWeight = FontWeight.Black,
                        letterSpacing = numberLetterSpacing
                    )
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = movie.title,
            color = if (isFocused) Color.White else Color.LightGray,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 44.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieCard(movie: ArchiveMovie, onMovieClick: (ArchiveMovie) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.08f else 1f, label = "card_scale")
    val borderColor = if (isFocused) Color.White else Color.Gray.copy(alpha = 0.65f)
    val borderWidth = if (isFocused) 3.dp else 1.5.dp

    Column(modifier = Modifier.width(120.dp)) {
        Card(
            modifier = Modifier
                .width(120.dp)
                .height(175.dp)
                .scale(scale)
                .border(borderWidth, borderColor, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            interactionSource = interactionSource,
            onClick = { onMovieClick(movie) }
        ) {
            Image(
                painter = rememberAsyncImagePainter("https://archive.org/services/img/${movie.identifier}"),
                contentDescription = movie.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = movie.title,
            color = if (isFocused) Color.White else Color.LightGray,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
EOF

cat << 'EOF' > app/src/main/java/com/example/mediastudio/MainActivity.kt
package com.example.mediastudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mediastudio.data.model.ArchiveMovie
import com.example.mediastudio.ui.screens.DetailsScreen
import com.example.mediastudio.ui.screens.GenreScreen
import com.example.mediastudio.ui.screens.HomeScreen
import com.example.mediastudio.ui.screens.PlayerScreen
import com.example.mediastudio.ui.screens.SeriesScreen
import com.example.mediastudio.ui.viewmodel.HomeViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val homeViewModel: HomeViewModel = viewModel()
                
                var currentScreen by remember { mutableIntStateOf(0) }
                var previousScreen by remember { mutableIntStateOf(0) }
                var selectedMovie by remember { mutableStateOf<ArchiveMovie?>(null) }
                var selectedMovieYear by remember { mutableStateOf("") }
                var selectedEpisodeTitle by remember { mutableStateOf("") }
                var selectedEpisodeUrl by remember { mutableStateOf("") }

                BackHandler(enabled = currentScreen != 0) {
                    when (currentScreen) {
                        2 -> currentScreen = if (selectedEpisodeUrl.isNotEmpty()) 4 else 1
                        1 -> currentScreen = previousScreen
                        3 -> currentScreen = 0
                        4 -> currentScreen = previousScreen
                    }
                }

                when (currentScreen) {
                    0 -> HomeScreen(
                        viewModel = homeViewModel,
                        onMovieClick = { movie -> 
                            selectedMovie = movie
                            selectedEpisodeUrl = ""
                            previousScreen = 0
                            currentScreen = 1
                        },
                        onSeriesClick = { series ->
                            selectedMovie = series
                            previousScreen = 0
                            currentScreen = 4
                        },
                        onGenreSelect = { genre ->
                            homeViewModel.openGenre(genre)
                            currentScreen = 3
                        }
                    )
                    1 -> selectedMovie?.let { movie ->
                        DetailsScreen(
                            movie = movie,
                            onPlayClick = { year ->
                                selectedMovieYear = year
                                selectedEpisodeUrl = ""
                                currentScreen = 2
                            },
                            onBackClick = { currentScreen = previousScreen }
                        )
                    }
                    2 -> selectedMovie?.let { movie ->
                        PlayerScreen(
                            identifier = movie.identifier,
                            movieTitle = if (selectedEpisodeUrl.isNotEmpty()) selectedEpisodeTitle else movie.title,
                            movieYear = selectedMovieYear,
                            directVideoUrl = selectedEpisodeUrl,
                            onNavigateBack = {
                                currentScreen = if (selectedEpisodeUrl.isNotEmpty()) 4 else 1
                            }
                        )
                    }
                    3 -> GenreScreen(
                        viewModel = homeViewModel,
                        onMovieClick = { item ->
                            selectedMovie = item
                            previousScreen = 3
                            if (homeViewModel.selectedGenre.value?.isSeriesCategory == true) {
                                currentScreen = 4
                            } else {
                                selectedEpisodeUrl = ""
                                currentScreen = 1
                            }
                        },
                        onBackClick = { currentScreen = 0 }
                    )
                    4 -> selectedMovie?.let { series ->
                        SeriesScreen(
                            series = series,
                            onEpisodeClick = { episode ->
                                selectedEpisodeTitle = "${series.title} - ${episode.title}"
                                selectedEpisodeUrl = episode.fileUrl
                                currentScreen = 2
                            },
                            onBackClick = { currentScreen = previousScreen }
                        )
                    }
                }
            }
        }
    }
}
EOF

git add .
git commit -m "Dodane serije, live pretraga, tjedni Top 10 i prijevod svih jezika"
git push origin main