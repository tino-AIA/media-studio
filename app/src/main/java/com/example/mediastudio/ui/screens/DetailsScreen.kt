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
import androidx.compose.material.icons.filled.PlayArrow
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
    onPlayClick: (String) -> Unit,
    onBackClick: () -> Unit
) {
    var movieInfo by remember { mutableStateOf<MovieFullInfo?>(null) }
    var translatedDescription by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    val playButtonFocusRequester = remember { FocusRequester() }
    val playInteractionSource = remember { MutableInteractionSource() }
    val isPlayFocused by playInteractionSource.collectIsFocusedAsState()

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
            playButtonFocusRequester.requestFocus()
        } catch (_: Exception) {
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Detalji filma", color = Color.White) },
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

                    if (info.qualities.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Dostupno: ${info.qualities.joinToString(", ") { it.label }}",
                            color = Color(0xFF4CAF50),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
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
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Gumb POKRENI FILM s jasnim bijelim fokusnim okvirom za daljinski
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
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
                            onPlayClick(movieInfo?.year ?: "")
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
                            text = "POKRENI FILM",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
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
