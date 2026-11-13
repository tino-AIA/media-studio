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
