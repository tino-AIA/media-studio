package com.example.mediastudio.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Scale
import com.example.mediastudio.data.api.ArchiveMetadataModule
import com.example.mediastudio.data.api.MovieFullInfo
import com.example.mediastudio.data.api.PosterFetcher
import com.example.mediastudio.data.api.SupabaseRepository
import com.example.mediastudio.data.api.VideoQualityOption
import com.example.mediastudio.data.model.ArchiveMovie
import com.example.mediastudio.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(
    movie: ArchiveMovie,
    viewModel: HomeViewModel,
    onPlayClick: (String) -> Unit,
    onMovieSelectFromList: ((ArchiveMovie) -> Unit)? = null,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    var movieInfo by remember { mutableStateOf<MovieFullInfo?>(null) }
    var translatedDescription by remember { mutableStateOf("") }
    var imageUrl by remember { mutableStateOf("") }
    var resolvedStreamUrl by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var isSupabaseAuthorList by remember { mutableStateOf(false) }

    val playButtonFocusRequester = remember { FocusRequester() }
    val firstEpisodeFocusRequester = remember { FocusRequester() }

    val playInteractionSource = remember { MutableInteractionSource() }
    val isPlayFocused by playInteractionSource.collectIsFocusedAsState()

    val favInteractionSource = remember { MutableInteractionSource() }
    val isFavFocused by favInteractionSource.collectIsFocusedAsState()

    val isFavorite = viewModel.favoriteMovies.collectAsState().value.any { it.identifier == movie.identifier }
    val isAuthorMode = movie.identifier.startsWith("AUTHOR|")
    val isDirectLinkMode = movie.identifier.startsWith("http")

    BackHandler {
        onBackClick()
    }

    LaunchedEffect(movie.identifier) {
        isLoading = true
        isSupabaseAuthorList = false
        resolvedStreamUrl = ""

        if (isAuthorMode) {
            val authorName = movie.identifier.substringAfter("AUTHOR|")
            val sbAuthor = SupabaseRepository.getAuthors().find { it.name.equals(authorName, ignoreCase = true) }
            val sbMovies = if (sbAuthor != null) {
                SupabaseRepository.loadAuthorMoviesById(sbAuthor.id)
            } else {
                emptyList()
            }

            if (sbAuthor != null && sbMovies.isNotEmpty()) {
                isSupabaseAuthorList = true
                imageUrl = sbAuthor.photoUrl.ifBlank { viewModel.staticAuthorImages[authorName] ?: "" }
                translatedDescription = sbAuthor.bioHr.ifBlank { fetchWikiInfo(authorName) }
                val options = sbMovies.map { m ->
                    val label = if (m.releaseYear > 0) "${m.title} (${m.releaseYear})" else m.title
                    VideoQualityOption(
                        label = label,
                        url = m.streamUrl,
                        height = 720,
                        sizeBytes = 0L
                    )
                }
                movieInfo = MovieFullInfo(
                    qualities = options,
                    description = translatedDescription,
                    year = "",
                    director = authorName
                )
            } else {
                val archiveId = viewModel.getAuthorArchiveId(authorName)
                imageUrl = viewModel.staticAuthorImages[authorName] ?: ""
                translatedDescription = fetchWikiInfo(authorName)
                movieInfo = ArchiveMetadataModule.fetchMovieFullInfo(archiveId)
            }
        } else if (isDirectLinkMode) {
            resolvedStreamUrl = movie.identifier
            val sbMatch = SupabaseRepository.findByStreamUrl(movie.identifier)
            val sbCatalogMatch = SupabaseRepository.getCatalogItems().find { it.streamUrl == movie.identifier }
            if (sbMatch != null) {
                imageUrl = sbMatch.first
                val dbDesc = sbMatch.second.ifBlank {
                    sbCatalogMatch?.let {
                        it.descriptionHr.ifBlank { SupabaseRepository.loadItemDescription(it.archiveId) }
                    }.orEmpty()
                }
                translatedDescription = dbDesc.ifBlank {
                    fetchMoviePlotFallback(movie.title, sbCatalogMatch?.releaseYear ?: 0)
                }
            } else if (sbCatalogMatch != null) {
                imageUrl = sbCatalogMatch.posterUrl
                val dbDesc = sbCatalogMatch.descriptionHr.ifBlank {
                    SupabaseRepository.loadItemDescription(sbCatalogMatch.archiveId)
                }
                translatedDescription = dbDesc.ifBlank {
                    fetchMoviePlotFallback(sbCatalogMatch.title, sbCatalogMatch.releaseYear)
                }
            } else {
                imageUrl = PosterFetcher.fetchPoster(movie.title, isSeries = false, movie.identifier)
                translatedDescription = fetchMoviePlotFallback(movie.title, 0)
            }
            movieInfo = null
        } else {
            val sbItem = SupabaseRepository.getCatalogItem(movie.identifier)
            if (sbItem != null) {
                resolvedStreamUrl = sbItem.streamUrl.takeIf { it.startsWith("http") } ?: ""
                imageUrl = sbItem.posterUrl.ifBlank {
                    PosterFetcher.fetchPoster(movie.title, isSeries = false, movie.identifier)
                }
                val yearStr = if (sbItem.releaseYear > 0) sbItem.releaseYear.toString() else (movie.year ?: "")
                val dbDescription = sbItem.descriptionHr.ifBlank {
                    SupabaseRepository.loadItemDescription(movie.identifier)
                }

                if (movie.identifier.contains(",tmdb-")) {
                    val authorSlug = movie.identifier.substringBefore(",tmdb-")
                    val matchedAuthor = SupabaseRepository.getAuthors()
                        .find { it.archiveId.equals(authorSlug, ignoreCase = true) }?.name ?: ""
                    val desc = dbDescription.ifBlank {
                        fetchMoviePlotFallback(sbItem.title, sbItem.releaseYear)
                    }
                    translatedDescription = desc
                    movieInfo = MovieFullInfo(
                        qualities = emptyList(),
                        description = desc,
                        year = yearStr,
                        director = matchedAuthor
                    )
                } else {
                    val info = ArchiveMetadataModule.fetchMovieFullInfo(movie.identifier)
                    movieInfo = info.copy(year = yearStr.ifEmpty { info.year })
                    val cleanArchiveDesc = if (info.description.startsWith("Opis za ovaj naslov")) "" else translateToCroatian(info.description)
                    translatedDescription = dbDescription
                        .ifBlank { cleanArchiveDesc }
                        .ifBlank { fetchMoviePlotFallback(sbItem.title, sbItem.releaseYear) }
                }
            } else {
                imageUrl = PosterFetcher.fetchPoster(movie.title, isSeries = false, movie.identifier)
                val info = ArchiveMetadataModule.fetchMovieFullInfo(movie.identifier)
                movieInfo = info
                val cleanArchiveDesc = if (info.description.startsWith("Opis za ovaj naslov")) "" else translateToCroatian(info.description)
                translatedDescription = cleanArchiveDesc
                    .ifBlank { fetchMoviePlotFallback(movie.title, info.year.toIntOrNull() ?: 0) }
            }
        }

        isLoading = false
        try {
            val hasCollectionList = !isDirectLinkMode &&
                ((isAuthorMode && (movieInfo?.qualities?.isNotEmpty() == true)) || (movieInfo?.qualities?.size ?: 0) > 1)
            if (hasCollectionList) {
                firstEpisodeFocusRequester.requestFocus()
            } else {
                playButtonFocusRequester.requestFocus()
            }
        } catch (_: Exception) {}
    }

    val showCollectionList = !isDirectLinkMode &&
        ((isAuthorMode && (movieInfo?.qualities?.isNotEmpty() == true)) || (movieInfo?.qualities?.size ?: 0) > 1)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (isAuthorMode || showCollectionList) "Kolekcija Autora" else "Detalji filma",
                        color = Color.White
                    )
                },
                navigationIcon = {
                    TvHighlightIconButton(
                        onClick = onBackClick,
                        contentDescription = "Povratak"
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Povratak", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0A0A).copy(alpha = 0.96f))
            )
        },
        containerColor = Color.Black
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .clipToBounds()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (imageUrl.isNotEmpty()) {
                val detailImageRequest = remember(imageUrl) {
                    ImageRequest.Builder(context)
                        .data(imageUrl)
                        .size(360, 510)
                        .scale(Scale.FILL)
                        .bitmapConfig(Bitmap.Config.RGB_565)
                        .allowHardware(false)
                        .build()
                }
                Image(
                    painter = rememberAsyncImagePainter(detailImageRequest),
                    contentDescription = movie.title,
                    modifier = Modifier
                        .width(180.dp)
                        .height(255.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(2.dp, Color.Gray, RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.height(16.dp))
            } else {
                Spacer(modifier = Modifier.height(10.dp))
            }

            Text(
                text = if (isAuthorMode) movie.identifier.substringAfter("AUTHOR|") else movie.title,
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            if (isLoading) {
                Spacer(modifier = Modifier.height(20.dp))
                CircularProgressIndicator(color = Color.Red)
            } else {
                val currentInfo = movieInfo

                if (!isAuthorMode && !isDirectLinkMode && currentInfo != null) {
                    val metaParts = buildList {
                        if (currentInfo.year.isNotEmpty()) add("Godina: ${currentInfo.year}")
                        if (currentInfo.director.isNotEmpty()) add("Redatelj: ${currentInfo.director}")
                    }
                    if (metaParts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = metaParts.joinToString("  •  "),
                            color = Color.LightGray,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = translatedDescription.ifEmpty { currentInfo?.description ?: "Opis za ovaj naslov trenutno nije dostupan." },
                    color = Color(0xFFDDDDDD),
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                if (currentInfo != null && showCollectionList) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .height(50.dp)
                            .padding(bottom = 24.dp)
                            .background(
                                color = if (isFavFocused) Color(0xFF333333) else Color(0xFF1A1A1A),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .border(
                                width = if (isFavFocused) 3.5.dp else 1.dp,
                                color = if (isFavFocused) Color.White else Color.DarkGray,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable(interactionSource = favInteractionSource, indication = null) {
                                viewModel.toggleFavorite(movie)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = null,
                                tint = if (isFavorite) Color.Red else Color.White,
                                modifier = Modifier.size(24.dp)
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

                    val formattedQualities = if (isSupabaseAuthorList) {
                        currentInfo.qualities.map { quality ->
                            Triple(quality, quality.label, 0)
                        }
                    } else {
                        currentInfo.qualities.map { quality ->
                            val decodedUrl = try {
                                URLDecoder.decode(quality.url.substringAfterLast("/"), "UTF-8")
                            } catch (e: Exception) {
                                quality.label
                            }

                            val originalName = decodedUrl.substringBeforeLast(".")
                            val yearMatch = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(originalName)
                            val extractedYear = yearMatch?.value ?: ""

                            var cleanTitle = originalName.replace(Regex("(?i)(_|-|\\.)"), " ")
                            cleanTitle = cleanTitle.replace(Regex("(?i)\\b(1080p|720p|480p|360p|240p|hevc|h264|x264|mp4|mkv|aac|webrip|bluray|dvdrip|xvid|divx|brrip|web|dl|hd|sd)\\b"), "")

                            if (extractedYear.isNotEmpty()) {
                                cleanTitle = cleanTitle.replace(Regex("\\b$extractedYear\\b"), "")
                            }

                            cleanTitle = cleanTitle.replace(Regex("\\(\\s*\\)|\\[\\s*\\]"), "")
                            cleanTitle = cleanTitle.replace(Regex("^\\s*[0-9]+[\\.\\-]?\\s*"), "")
                            cleanTitle = cleanTitle.replace(Regex("\\s+"), " ").trim()

                            val finalFormattedTitle = if (extractedYear.isNotEmpty() && cleanTitle.isNotEmpty()) {
                                "$cleanTitle ($extractedYear)"
                            } else if (cleanTitle.isNotEmpty()) {
                                cleanTitle
                            } else {
                                quality.label
                            }

                            Triple(quality, finalFormattedTitle, extractedYear.toIntOrNull() ?: 0)
                        }.sortedByDescending { it.third }
                    }

                    formattedQualities.forEachIndexed { index, (quality, finalFormattedTitle, _) ->
                        val epInteractionSource = remember { MutableInteractionSource() }
                        val isEpFocused by epInteractionSource.collectIsFocusedAsState()
                        val mod = if (index == 0) Modifier.focusRequester(firstEpisodeFocusRequester) else Modifier

                        Box(
                            modifier = mod
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .background(
                                    color = if (isEpFocused) Color(0xFF2E2E2E) else Color(0xFF1A1A1A),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .border(
                                    width = if (isEpFocused) 3.dp else 1.dp,
                                    color = if (isEpFocused) Color.White else Color.DarkGray,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable(interactionSource = epInteractionSource, indication = null) {
                                    val directDownloadUrl = quality.url.replace("/details/", "/download/")
                                    if (onMovieSelectFromList != null) {
                                        onMovieSelectFromList(
                                            ArchiveMovie(
                                                identifier = directDownloadUrl,
                                                title = finalFormattedTitle
                                            )
                                        )
                                    }
                                }
                                .padding(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${index + 1}.",
                                    color = Color.Red,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    modifier = Modifier.width(36.dp)
                                )
                                Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text(
                                        text = finalFormattedTitle,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                }
                                Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Otvori detalje", tint = Color.White)
                            }
                        }
                    }
                } else if (currentInfo != null || isDirectLinkMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(0.9f),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                                .focusRequester(playButtonFocusRequester)
                                .background(
                                    color = if (isPlayFocused) Color(0xFFFF1E27) else Color(0xFFE50914),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .border(
                                    width = if (isPlayFocused) 3.5.dp else 1.dp,
                                    color = if (isPlayFocused) Color.White else Color.Transparent,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable(interactionSource = playInteractionSource, indication = null) {
                                    if (resolvedStreamUrl.isNotEmpty()) {
                                        onPlayClick(resolvedStreamUrl)
                                    } else if (isDirectLinkMode) {
                                        onPlayClick(movie.identifier)
                                    } else {
                                        onPlayClick(currentInfo?.year ?: "")
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "POKRENI",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(54.dp)
                                .background(
                                    color = if (isFavFocused) Color(0xFF333333) else Color(0xFF1A1A1A),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .border(
                                    width = if (isFavFocused) 3.5.dp else 1.dp,
                                    color = if (isFavFocused) Color.White else Color.DarkGray,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable(interactionSource = favInteractionSource, indication = null) {
                                    viewModel.toggleFavorite(movie)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = null,
                                    tint = if (isFavorite) Color.Red else Color.White,
                                    modifier = Modifier.size(24.dp)
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
                    }
                } else if (isAuthorMode) {
                    Text(
                        text = "Nažalost, nismo uspjeli pronaći filmove za ovog autora u arhivi.",
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 20.dp)
                    )
                }
                Spacer(modifier = Modifier.height(64.dp))
            }
        }
    }
}

private suspend fun fetchMoviePlotFallback(title: String, year: Int): String = withContext(Dispatchers.IO) {
    try {
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        val queries = buildList {
            if (year > 0) add("$cleanTitle ($year film)")
            add("$cleanTitle (film)")
            add(cleanTitle)
        }

        for (q in queries) {
            val encoded = URLEncoder.encode(q, "UTF-8")
            val hrUrl = URL("https://hr.wikipedia.org/w/api.php?action=query&titles=$encoded&prop=extracts&exintro=1&explaintext=1&format=json&redirects=1")
            val hrConn = hrUrl.openConnection() as HttpURLConnection
            hrConn.setRequestProperty("User-Agent", "Mozilla/5.0")
            hrConn.connectTimeout = 5000
            hrConn.readTimeout = 5000
            val hrJson = JSONObject(hrConn.inputStream.bufferedReader().use { it.readText() })
            val hrPages = hrJson.getJSONObject("query").getJSONObject("pages")
            val hrId = hrPages.keys().next()
            if (hrId != "-1") {
                val extract = hrPages.getJSONObject(hrId).optString("extract").trim()
                if (extract.length > 40) return@withContext extract.take(900)
            }
        }

        for (q in queries) {
            val encoded = URLEncoder.encode(q, "UTF-8")
            val enUrl = URL("https://en.wikipedia.org/w/api.php?action=query&titles=$encoded&prop=extracts&exintro=1&explaintext=1&format=json&redirects=1")
            val enConn = enUrl.openConnection() as HttpURLConnection
            enConn.setRequestProperty("User-Agent", "Mozilla/5.0")
            enConn.connectTimeout = 5000
            enConn.readTimeout = 5000
            val enJson = JSONObject(enConn.inputStream.bufferedReader().use { it.readText() })
            val enPages = enJson.getJSONObject("query").getJSONObject("pages")
            val enId = enPages.keys().next()
            if (enId != "-1") {
                val extract = enPages.getJSONObject(enId).optString("extract").trim()
                if (extract.length > 40) return@withContext translateToCroatian(extract.take(900))
            }
        }
    } catch (_: Exception) {
    }
    "Opis za ovaj naslov trenutno nije dostupan."
}

private suspend fun fetchWikiInfo(authorName: String): String = withContext(Dispatchers.IO) {
    try {
        val searchName = if (authorName == "PTA Filmography") "Paul Thomas Anderson" else authorName
        val encodedName = URLEncoder.encode(searchName, "UTF-8")

        var url = URL("https://hr.wikipedia.org/w/api.php?action=query&titles=$encodedName&prop=extracts&exintro=1&explaintext=1&format=json&redirects=1")
        var conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        var response = conn.inputStream.bufferedReader().use { it.readText() }

        var jsonObject = JSONObject(response)
        var pages = jsonObject.getJSONObject("query").getJSONObject("pages")
        var pageId = pages.keys().next()

        if (pageId != "-1") {
            val page = pages.getJSONObject(pageId)
            if (page.has("extract")) {
                val extract = page.getString("extract").trim()
                if (extract.isNotEmpty()) return@withContext extract
            }
        }

        url = URL("https://en.wikipedia.org/w/api.php?action=query&titles=$encodedName&prop=extracts&exintro=1&explaintext=1&format=json&redirects=1")
        conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        response = conn.inputStream.bufferedReader().use { it.readText() }

        jsonObject = JSONObject(response)
        pages = jsonObject.getJSONObject("query").getJSONObject("pages")
        pageId = pages.keys().next()

        if (pageId != "-1") {
            val page = pages.getJSONObject(pageId)
            if (page.has("extract")) {
                val englishExtract = page.getString("extract").trim()
                return@withContext translateToCroatian(englishExtract)
            }
        }
        return@withContext "Biografija nije pronađena na Wikipediji."
    } catch (e: Exception) {
        return@withContext "Greška prilikom preuzimanja biografije."
    }
}

private suspend fun translateToCroatian(text: String): String = withContext(Dispatchers.IO) {
    if (text.isBlank() || text.startsWith("Opis za ovaj naslov")) return@withContext text
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
    } catch (e: Exception) {
        e.printStackTrace()
        text
    }
}

@Composable
private fun TvHighlightIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String?,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .size(44.dp)
            .background(
                color = if (isFocused) Color.Red else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .border(
                width = if (isFocused) 2.5.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
