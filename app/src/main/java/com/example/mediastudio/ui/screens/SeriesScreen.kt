package com.example.mediastudio.ui.screens

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Scale
import com.example.mediastudio.data.api.ArchiveMetadataModule
import com.example.mediastudio.data.api.PosterFetcher
import com.example.mediastudio.data.api.SeriesEpisode
import com.example.mediastudio.data.api.SeriesSeason
import com.example.mediastudio.data.api.SupabaseRepository
import com.example.mediastudio.data.model.ArchiveMovie
import com.example.mediastudio.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val SERIES_TMDB_API_KEY = "8265bd1679663a7ea12ac168da84d2e8"

private fun Modifier.throttleSeriesVerticalDpad(minIntervalMs: Long = 105L): Modifier = composed {
    var lastVerticalStepTime by remember { mutableLongStateOf(0L) }
    this.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown &&
            (event.key == Key.DirectionUp || event.key == Key.DirectionDown)
        ) {
            val now = SystemClock.uptimeMillis()
            if (now - lastVerticalStepTime < minIntervalMs) {
                true
            } else {
                lastVerticalStepTime = now
                false
            }
        } else {
            false
        }
    }
}

private fun looksLikeEnglishText(text: String): Boolean {
    if (text.isBlank()) return false
    val padded = " ${text.lowercase()} "
    val englishMarkers = listOf(
        " the ", " and ", " with ", " from ", " into ", " their ",
        " his ", " her ", " show ", " story ", " when ", " where ",
        " which ", " while ", " after ", " before ", " through ", " against ",
        " of ", " in ", " to "
    )
    val hits = englishMarkers.count { padded.contains(it) }
    val hasCroatianChars = text.any { it in "čćžšđČĆŽŠĐ" }
    return hits >= 2 && !hasCroatianChars
}

private suspend fun translateSeriesDescriptionToCroatian(text: String): String = withContext(Dispatchers.IO) {
    if (text.isBlank() || text.startsWith("Opis za ovaj naslov") || !looksLikeEnglishText(text)) {
        return@withContext text
    }
    try {
        val limitedText = text.take(1500)
        val sentences = limitedText.split(Regex("(?<=[.!?])\\s+"))
        val translatedBuilder = StringBuilder()

        for (sentence in sentences) {
            val trimmed = sentence.trim()
            if (trimmed.isEmpty()) continue
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=hr&dt=t&q=$encoded"
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val jsonArray = JSONArray(response)
            val parts = jsonArray.getJSONArray(0)
            for (i in 0 until parts.length()) {
                translatedBuilder.append(parts.getJSONArray(i).getString(0))
            }
            translatedBuilder.append(" ")
        }
        translatedBuilder.toString().trim().ifBlank { text }
    } catch (_: Exception) {
        text
    }
}

private fun formatSeriesGenresHr(genres: List<String>): String {
    return genres
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("18+") }
        .distinct()
        .joinToString(", ")
}

private fun extractSeriesAwardsHr(text: String): String {
    if (text.isBlank()) return ""
    val keys = listOf(
        "emmy", "oscar", "zlatni globus", "golden globe", "bafta",
        "nagrada", "nagradu", "nagrađen", "nagrađena", "award"
    )
    return text.split(Regex("(?<=[.!?])\\s+"))
        .filter { s -> keys.any { k -> s.lowercase().contains(k) } }
        .map { it.trim() }
        .distinct()
        .take(2)
        .joinToString(" ")
}

private fun extractSeriesCastHr(text: String): String {
    val m = Regex("(?i)(?:glavne uloge|glavni glumci|uloge|glume|starring|cast)\\s*[:\\-–]?\\s*([^\\n.]{4,160})").find(text)
    return m?.groupValues?.get(1)?.trim().orEmpty()
}

private suspend fun fetchTmdbSeriesCastHr(tmdbId: Int, title: String): String = withContext(Dispatchers.IO) {
    try {
        var resolvedId = tmdbId
        if (resolvedId <= 0) {
            val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
            if (cleanTitle.length >= 2) {
                val encodedQ = URLEncoder.encode(cleanTitle, "UTF-8")
                val searchUrl = "https://api.themoviedb.org/3/search/tv?api_key=$SERIES_TMDB_API_KEY&query=$encodedQ&language=hr-HR"
                val conn = URL(searchUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val obj = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                resolvedId = obj.optJSONArray("results")?.optJSONObject(0)?.optInt("id", 0) ?: 0
            }
        }
        if (resolvedId <= 0) return@withContext ""

        val url = "https://api.themoviedb.org/3/tv/$resolvedId/credits?api_key=$SERIES_TMDB_API_KEY&language=hr-HR"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        val obj = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val castArr = obj.optJSONArray("cast") ?: return@withContext ""

        val names = mutableListOf<String>()
        for (i in 0 until minOf(castArr.length(), 6)) {
            val n = castArr.optJSONObject(i)?.optString("name", "")?.trim().orEmpty()
            if (n.isNotBlank() && n !in names) names.add(n)
        }
        names.joinToString(", ")
    } catch (_: Exception) {
        ""
    }
}

private suspend fun fetchSeriesWikiHr(title: String): String = withContext(Dispatchers.IO) {
    try {
        val clean = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        if (clean.isBlank()) return@withContext ""
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val url = URL("https://hr.wikipedia.org/w/api.php?action=query&titles=$encoded&prop=extracts&explaintext=1&format=json&redirects=1")
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val pages = json.getJSONObject("query").getJSONObject("pages")
        val pageId = pages.keys().next()
        if (pageId != "-1") {
            val extract = pages.getJSONObject(pageId).optString("extract").trim()
            if (extract.isNotEmpty()) return@withContext extract.take(1600)
        }
        ""
    } catch (_: Exception) {
        ""
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SeriesScreen(
    series: ArchiveMovie,
    viewModel: HomeViewModel,
    onEpisodeClick: (SeriesEpisode) -> Unit,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    var seasons by remember { mutableStateOf<List<SeriesSeason>>(emptyList()) }
    var selectedSeasonIndex by remember { mutableIntStateOf(0) }
    var seriesDescription by remember { mutableStateOf("") }
    var seriesGenreHr by remember { mutableStateOf("") }
    var seriesDirectorHr by remember { mutableStateOf("") }
    var seriesCastHr by remember { mutableStateOf("") }
    var seriesAwardsHr by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    val backButtonFocusRequester = remember { FocusRequester() }
    val infoCardFocusRequester = remember { FocusRequester() }
    val favButtonFocusRequester = remember { FocusRequester() }

    val firstArchiveId = series.identifier.split(",").first()
    val sbItem = remember(series.identifier) {
        SupabaseRepository.getCatalogItem(series.identifier)
    }
    var posterUrl by remember(series.identifier) {
        mutableStateOf(sbItem?.posterUrl.orEmpty().ifBlank { "https://archive.org/services/img/$firstArchiveId" })
    }

    val resolvedYear = remember(series.identifier, series.year, series.title) {
        series.year?.takeIf { it.isNotBlank() && it != "0" }
            ?: sbItem?.releaseYear?.takeIf { it > 0 }?.toString()
            ?: Regex("\\(\\s*(19\\d{2}|20\\d{2})\\s*\\)").find(series.title)?.groupValues?.get(1)
            ?: ""
    }

    val headerTitleWithYear = remember(series.title, resolvedYear) {
        if (resolvedYear.isNotBlank() && !series.title.contains(resolvedYear)) {
            "${series.title} ($resolvedYear)"
        } else {
            series.title
        }
    }

    val favInteractionSource = remember { MutableInteractionSource() }
    val isFavFocused by favInteractionSource.collectIsFocusedAsState()

    val infoInteractionSource = remember { MutableInteractionSource() }
    val isInfoFocused by infoInteractionSource.collectIsFocusedAsState()
    val infoScrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    val isFavorite = viewModel.favoriteMovies.collectAsState().value.any { it.identifier == series.identifier }

    BackHandler {
        onBackClick()
    }

    LaunchedEffect(series.identifier) {
        isLoading = true
        val item = SupabaseRepository.getCatalogItem(series.identifier)
        posterUrl = item?.posterUrl.orEmpty().ifBlank {
            PosterFetcher.fetchPoster(series.title, isSeries = true, series.identifier)
                .ifBlank { "https://archive.org/services/img/$firstArchiveId" }
        }
        seriesGenreHr = formatSeriesGenresHr(item?.genres.orEmpty())
        val dbDesc = SupabaseRepository.loadItemDescription(series.identifier)
        SupabaseRepository.loadSeriesEpisodes(series.identifier)
        val fullInfo = ArchiveMetadataModule.fetchSeriesFullInfo(series.identifier, series.title)
        val rawDesc = fullInfo.description.takeIf {
            it.isNotBlank() && !it.startsWith("Opis za ovaj naslov")
        } ?: dbDesc.ifBlank { fullInfo.description }
        val hrDesc = translateSeriesDescriptionToCroatian(rawDesc)
        seriesDescription = hrDesc

        val wikiHr = fetchSeriesWikiHr(series.title)
        val combinedText = "$hrDesc $wikiHr"
        seriesDirectorHr = ""
        val tmdbCast = fetchTmdbSeriesCastHr(item?.tmdbId ?: 0, series.title)
        seriesCastHr = tmdbCast.ifBlank { extractSeriesCastHr(combinedText) }
        seriesAwardsHr = extractSeriesAwardsHr(combinedText)

        seasons = fullInfo.seasons
        selectedSeasonIndex = 0
        isLoading = false
    }

    val activeSeason = seasons.getOrNull(selectedSeasonIndex)
    val visibleEpisodes = activeSeason?.episodes ?: emptyList()

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier
                    .focusProperties {
                        up = FocusRequester.Cancel
                        left = FocusRequester.Cancel
                    }
                    .onPreviewKeyEvent { event ->
                        event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp
                    },
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
                            val yearPrefix = if (resolvedYear.isNotBlank()) "$resolvedYear • " else ""
                            val totalEps = seasons.sumOf { it.episodes.size }
                            val seasonInfo = if (seasons.size > 1) "${seasons.size} sezona • " else ""
                            Text(
                                text = "${yearPrefix}${seasonInfo}Ukupno $totalEps epizoda",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    TvHighlightIconButton(
                        onClick = onBackClick,
                        contentDescription = "Povratak",
                        modifier = Modifier
                            .focusRequester(backButtonFocusRequester)
                            .focusProperties {
                                left = FocusRequester.Cancel
                                if (!isLoading) {
                                    down = infoCardFocusRequester
                                }
                            }
                    ) {
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
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 24.dp, top = 12.dp, end = 24.dp, bottom = 64.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .clipToBounds()
                    .throttleSeriesVerticalDpad()
            ) {
                item(key = "series_header") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = headerTitleWithYear,
                            color = Color.White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 14.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            val posterRequest = remember(posterUrl) {
                                ImageRequest.Builder(context)
                                    .data(posterUrl)
                                    .size(340, 490)
                                    .scale(Scale.FILL)
                                    .bitmapConfig(Bitmap.Config.RGB_565)
                                    .allowHardware(true)
                                    .build()
                            }
                            Image(
                                painter = rememberAsyncImagePainter(posterRequest),
                                contentDescription = series.title,
                                modifier = Modifier
                                    .width(175.dp)
                                    .height(255.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(2.dp, Color.Gray, RoundedCornerShape(14.dp)),
                                contentScale = ContentScale.Crop
                            )

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 255.dp, max = 330.dp)
                                    .focusRequester(infoCardFocusRequester)
                                    .focusProperties {
                                        left = FocusRequester.Cancel
                                        right = FocusRequester.Cancel
                                        up = backButtonFocusRequester
                                        down = favButtonFocusRequester
                                    }
                                    .background(
                                        color = if (isInfoFocused) Color(0xFF1B1B1B) else Color(0xFF141414),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .border(
                                        width = if (isInfoFocused) 3.dp else 1.dp,
                                        color = if (isInfoFocused) Color.White else Color(0xFF2B2B2B),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .onPreviewKeyEvent { event ->
                                        if (event.type == KeyEventType.KeyDown) {
                                            when (event.key) {
                                                Key.DirectionDown -> {
                                                    if (infoScrollState.value < infoScrollState.maxValue) {
                                                        coroutineScope.launch {
                                                            infoScrollState.animateScrollTo(
                                                                (infoScrollState.value + 140).coerceAtMost(infoScrollState.maxValue)
                                                            )
                                                        }
                                                        true
                                                    } else {
                                                        false
                                                    }
                                                }
                                                Key.DirectionUp -> {
                                                    if (infoScrollState.value > 0) {
                                                        coroutineScope.launch {
                                                            infoScrollState.animateScrollTo(
                                                                (infoScrollState.value - 140).coerceAtLeast(0)
                                                            )
                                                        }
                                                        true
                                                    } else {
                                                        false
                                                    }
                                                }
                                                else -> false
                                            }
                                        } else {
                                            false
                                        }
                                    }
                                    .focusable(interactionSource = infoInteractionSource)
                                    .verticalScroll(infoScrollState)
                                    .padding(horizontal = 18.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                SeriesDetailLine("Žanr", seriesGenreHr)
                                SeriesDetailLine("Godina proizvodnje", resolvedYear)
                                SeriesDetailLine("Glavni glumci", seriesCastHr)
                                SeriesDetailLine("Redatelj", seriesDirectorHr)
                                SeriesDetailLine("Nagrade", seriesAwardsHr)

                                if (seriesDescription.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Divider(color = Color(0xFF262626))
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Kratki sadržaj",
                                        color = Color(0xFFFF5252),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = seriesDescription,
                                        color = Color(0xFFDDDDDD),
                                        fontSize = 14.sp,
                                        lineHeight = 21.sp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .height(48.dp)
                                .focusRequester(favButtonFocusRequester)
                                .focusProperties {
                                    left = FocusRequester.Cancel
                                    right = FocusRequester.Cancel
                                    up = infoCardFocusRequester
                                }
                                .background(
                                    color = if (isFavFocused) Color(0xFF333333) else Color(0xFF1A1A1A),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .border(
                                    width = if (isFavFocused) 3.5.dp else 1.dp,
                                    color = if (isFavFocused) Color.White else Color.DarkGray,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable(interactionSource = favInteractionSource, indication = null) {
                                    viewModel.toggleFavorite(series)
                                }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = null,
                                    tint = if (isFavorite) Color.Red else Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isFavorite) "U FAVORITIMA" else "DODAJ U FAVORITE",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }

                        if (seasons.size > 1) {
                            Spacer(modifier = Modifier.height(16.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(seasons.size) { index ->
                                    val season = seasons[index]
                                    SeasonChipButton(
                                        title = "${season.seasonTitle} (${season.episodes.size})",
                                        isSelected = index == selectedSeasonIndex,
                                        modifier = if (index == 0) {
                                            Modifier.focusProperties { left = FocusRequester.Cancel }
                                        } else {
                                            Modifier
                                        },
                                        onClick = { selectedSeasonIndex = index }
                                    )
                                }
                            }
                        }
                    }
                }

                items(
                    items = visibleEpisodes,
                    key = { ep -> "${selectedSeasonIndex}_${ep.episodeNumber}_${ep.fileUrl}" }
                ) { episode ->
                    EpisodeRowItem(episode = episode, onClick = { onEpisodeClick(episode) })
                }
            }
        }
    }
}

@Composable
private fun SeriesDetailLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label: ",
            color = Color(0xFF9E9E9E),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SeasonChipButton(
    title: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .background(
                color = if (isSelected) Color.Red else if (isFocused) Color(0xFF2B2B2B) else Color(0xFF1A1A1A),
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = if (isFocused) 3.dp else 1.dp,
                color = if (isFocused) Color.White else if (isSelected) Color.Red else Color.DarkGray,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
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
            .focusProperties { left = FocusRequester.Cancel }
            .background(
                color = if (isFocused) Color(0xFF2A2A2A) else Color(0xFF161616),
                shape = RoundedCornerShape(10.dp)
            )
            .border(
                width = if (isFocused) 3.dp else 1.dp,
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
                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
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
