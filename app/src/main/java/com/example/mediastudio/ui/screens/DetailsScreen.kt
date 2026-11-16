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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(
    movie: ArchiveMovie,
    viewModel: HomeViewModel,
    onPlayClick: (String) -> Unit,
    onBackClick: () -> Unit
) {
    var movieInfo by remember { mutableStateOf<MovieFullInfo?>(null) }
    var translatedDescription by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    val playButtonFocusRequester = remember { FocusRequester() }
    val firstEpisodeFocusRequester = remember { FocusRequester() }
    
    val playInteractionSource = remember { MutableInteractionSource() }
    val isPlayFocused by playInteractionSource.collectIsFocusedAsState()

    val favInteractionSource = remember { MutableInteractionSource() }
    val isFavFocused by favInteractionSource.collectIsFocusedAsState()

    val isFavorite = viewModel.favoriteMovies.collectAsState().value.any { it.identifier == movie.identifier }

    BackHandler {
        onBackClick()
    }

    LaunchedEffect(movie.identifier) {
        isLoading = true
        val info = ArchiveMetadataModule.fetchMovieFullInfo(movie.identifier)
        movieInfo = info
        translatedDescription = translateToCroatian(info.description)
        isLoading = false
        try {
            if (info.qualities.size > 1) {
                firstEpisodeFocusRequester.requestFocus()
            } else {
                playButtonFocusRequester.requestFocus()
            }
        } catch (_: Exception) {
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (movieInfo?.qualities?.size ?: 0 > 1) "Kolekcija / Serija" else "Detalji filma", color = Color.White) },
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
            Image(
                painter = rememberAsyncImagePainter("https://archive.org/services/img/${movie.identifier}"),
                contentDescription = movie.title,
                modifier = Modifier
                    .width(165.dp)
                    .height(235.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .border(2.dp, Color.Gray, RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = movie.title,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            if (isLoading) {
                Spacer(modifier = Modifier.height(20.dp))
                CircularProgressIndicator(color = Color.Red)
            } else {
                movieInfo?.let { info ->
                    val metaParts = buildList {
                        if (info.year.isNotEmpty()) add("Godina: ${info.year}")
                        if (info.director.isNotEmpty()) add("Redatelj: ${info.director}")
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

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = translatedDescription.ifEmpty { info.description },
                        color = Color(0xFFDDDDDD),
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    if (info.qualities.size > 1) {
                        Text(
                            text = "Odaberi naslov iz kolekcije (${info.qualities.size}):",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            textAlign = TextAlign.Start
                        )
                        
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .padding(bottom = 12.dp)
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

                        info.qualities.forEachIndexed { index, quality ->
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
                                        onPlayClick(quality.url)
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
                                    Column {
                                        Text(
                                            text = quality.label.substringBeforeLast(".").replace("_", " "),
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.weight(1f))
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White)
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
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
                                        onPlayClick(info.year)
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
                    }
                }
                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }
}

private suspend fun translateToCroatian(text: String): String = withContext(Dispatchers.IO) {
    if (text.isBlank() || text.startsWith("Opis za ovaj naslov")) return@withContext text
    try {
        val limitedText = text.take(2200)
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
