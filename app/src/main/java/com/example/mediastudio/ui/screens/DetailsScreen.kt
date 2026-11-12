package com.example.mediastudio.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.example.mediastudio.data.api.ArchiveMetadataModule
import com.example.mediastudio.data.api.MovieFullInfo
import com.example.mediastudio.data.model.ArchiveMovie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URL
import java.net.URLEncoder

private suspend fun translateToCroatian(text: String): String = withContext(Dispatchers.IO) {
    if (text.isBlank()) return@withContext text
    try {
        val cleanInput = text.take(1500)
        val encoded = URLEncoder.encode(cleanInput, "UTF-8")
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=hr&dt=t&q=$encoded"
        val response = URL(url).readText()
        val jsonArray = JSONArray(response)
        val sentences = jsonArray.getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until sentences.length()) {
            sb.append(sentences.getJSONArray(i).getString(0))
        }
        sb.toString().ifBlank { text }
    } catch (e: Exception) {
        e.printStackTrace()
        text
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(
    movie: ArchiveMovie,
    onPlayClick: (String) -> Unit,
    onBackClick: () -> Unit
) {
    var fullInfo by remember { mutableStateOf<MovieFullInfo?>(null) }
    var translatedDescription by remember { mutableStateOf("") }
    var isLoadingInfo by remember { mutableStateOf(true) }

    LaunchedEffect(movie.identifier) {
        val info = ArchiveMetadataModule.fetchMovieFullInfo(movie.identifier)
        fullInfo = info
        translatedDescription = translateToCroatian(info.description)
        isLoadingInfo = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Detalji filma", color = Color.White, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Nazad", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            // Pozadinska zamagljena slika
            Image(
                painter = rememberAsyncImagePainter("https://archive.org/services/img/${movie.identifier}"),
                contentDescription = movie.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.25f
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Black),
                            startY = 0f
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // Poster filma sa sivim obrubom
                Image(
                    painter = rememberAsyncImagePainter("https://archive.org/services/img/${movie.identifier}"),
                    contentDescription = movie.title,
                    modifier = Modifier
                        .width(160.dp)
                        .height(230.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.5.dp, Color.Gray.copy(alpha = 0.65f), RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Naslov
                Text(
                    text = movie.title,
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (isLoadingInfo) {
                    CircularProgressIndicator(color = Color.Red, modifier = Modifier.padding(16.dp))
                } else {
                    fullInfo?.let { info ->
                        // Godina i Redatelj
                        val metaLine = listOfNotNull(
                            info.year.takeIf { it.isNotBlank() }?.let { "Godina: $it" },
                            info.director.takeIf { it.isNotBlank() }?.let { "Redatelj: $it" }
                        ).joinToString("  •  ")

                        if (metaLine.isNotBlank()) {
                            Text(
                                text = metaLine,
                                color = Color(0xFFBBBBBB),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        // Dostupne kvalitete videa
                        if (info.qualities.isNotEmpty()) {
                            Text(
                                text = "Dostupno: ${info.qualities.joinToString(", ") { it.label }}",
                                color = Color(0xFF4CAF50),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        // Prevedeni opis radnje na hrvatski
                        Text(
                            text = translatedDescription.ifBlank { info.description },
                            color = Color.LightGray,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Gumb za pokretanje
                Button(
                    onClick = { onPlayClick(fullInfo?.year ?: "") },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("▶ POKRENI FILM", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}
