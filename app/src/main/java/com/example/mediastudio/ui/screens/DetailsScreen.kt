package com.example.mediastudio.ui.screens

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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.example.mediastudio.data.api.ArchiveMetadataModule
import com.example.mediastudio.data.api.MovieFullInfo
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
    var movieInfo by remember { mutableStateOf<MovieFullInfo?>(null) }
    var translatedDescription by remember { mutableStateOf("") }
    var imageUrl by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

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
        if (isAuthorMode) {
            val authorName = movie.identifier.substringAfter("AUTHOR|")
            val archiveId = viewModel.getAuthorArchiveId(authorName)
            
            imageUrl = ""
            translatedDescription = fetchWikiInfo(authorName)
            movieInfo = ArchiveMetadataModule.fetchMovieFullInfo(archiveId)
        } else if (isDirectLinkMode) {
            imageUrl = ""
            translatedDescription = "Odabrani naslov iz kolekcije autora spreman je za reprodukciju."
            movieInfo = null
        } else {
            imageUrl = "https://archive.org/services/img/${movie.identifier}"
            val info = ArchiveMetadataModule.fetchMovieFullInfo(movie.identifier)
            movieInfo = info
            translatedDescription = translateToCroatian(info.description)
        }
        
        isLoading = false
        try {
            if (movieInfo?.qualities?.size ?: 0 > 1 && !isDirectLinkMode) {
                firstEpisodeFocusRequester.requestFocus()
            } else {
                playButtonFocusRequester.requestFocus()
            }
        } catch (_: Exception) {}
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (isAuthorMode || (movieInfo?.qualities?.size ?: 0 > 1 && !isDirectLinkMode)) "Kolekcija Autora" else "Detalji filma",
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Black
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!isAuthorMode && !isDirectLinkMode) {
                if (imageUrl.isNotEmpty()) {
                    Image(
                        painter = rememberAsyncImagePainter(imageUrl),
                        contentDescription = movie.title,
                        modifier = Modifier
                            .width(180.dp)
                            .height(255.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(2.dp, Color.Gray, RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(modifier = Modifier.width(180.dp).height(255.dp).background(Color.DarkGray))
                }
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
                    text = translatedDescription.ifEmpty { currentInfo?.description ?: "Nema dostupnog opisa." },
                    color = Color(0xFFDDDDDD),
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                )

                Spacer(modifier = Modifier.height(24.dp))

                if (currentInfo != null && currentInfo.qualities.size > 1 && !isDirectLinkMode) {
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

                    val formattedQualities = currentInfo.qualities.map { quality ->
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
                                    if (isDirectLinkMode) {
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
                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }
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
