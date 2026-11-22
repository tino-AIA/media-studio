package com.example.mediastudio.ui.screens

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

@OptIn(ExperimentalMaterial3Api::class)
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
    var isLoading by remember { mutableStateOf(true) }

    val firstArchiveId = series.identifier.split(",").first()
    val initialSbPoster = remember(series.identifier) {
        SupabaseRepository.getCatalogItem(series.identifier)?.posterUrl.orEmpty()
    }
    var posterUrl by remember(series.identifier) {
        mutableStateOf(initialSbPoster.ifBlank { "https://archive.org/services/img/$firstArchiveId" })
    }

    val resolvedYear = remember(series.identifier, series.year, series.title) {
        series.year?.takeIf { it.isNotBlank() && it != "0" }
            ?: SupabaseRepository.getCatalogItem(series.identifier)?.releaseYear?.takeIf { it > 0 }?.toString()
            ?: Regex("\\(\\s*(19\\d{2}|20\\d{2})\\s*\\)").find(series.title)?.groupValues?.get(1)
            ?: ""
    }

    val favInteractionSource = remember { MutableInteractionSource() }
    val isFavFocused by favInteractionSource.collectIsFocusedAsState()

    val isFavorite = viewModel.favoriteMovies.collectAsState().value.any { it.identifier == series.identifier }

    BackHandler {
        onBackClick()
    }

    LaunchedEffect(series.identifier) {
        isLoading = true
        val sbPoster = SupabaseRepository.getCatalogItem(series.identifier)?.posterUrl.orEmpty()
        posterUrl = sbPoster.ifBlank {
            PosterFetcher.fetchPoster(series.title, isSeries = true, series.identifier)
                .ifBlank { "https://archive.org/services/img/$firstArchiveId" }
        }
        val dbDesc = SupabaseRepository.loadItemDescription(series.identifier)
        SupabaseRepository.loadSeriesEpisodes(series.identifier)
        val fullInfo = ArchiveMetadataModule.fetchSeriesFullInfo(series.identifier, series.title)
        seriesDescription = fullInfo.description.takeIf {
            it.isNotBlank() && !it.startsWith("Opis za ovaj naslov")
        } ?: dbDesc.ifBlank { fullInfo.description }
        seasons = fullInfo.seasons
        selectedSeasonIndex = 0
        isLoading = false
    }

    val activeSeason = seasons.getOrNull(selectedSeasonIndex)
    val visibleEpisodes = activeSeason?.episodes ?: emptyList()

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
                        contentDescription = "Povratak"
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
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 64.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .clipToBounds()
                    .throttleSeriesVerticalDpad()
            ) {
                item(key = "series_header") {
                    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val posterRequest = remember(posterUrl) {
                                ImageRequest.Builder(context)
                                    .data(posterUrl)
                                    .size(200, 290)
                                    .scale(Scale.FILL)
                                    .bitmapConfig(Bitmap.Config.RGB_565)
                                    .allowHardware(false)
                                    .build()
                            }
                            Image(
                                painter = rememberAsyncImagePainter(posterRequest),
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
                                if (resolvedYear.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Godina: $resolvedYear",
                                        color = Color(0xFFFFC107),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = if (visibleEpisodes.isNotEmpty())
                                        "Odaberi sezonu i pokreni željenu epizodu."
                                    else
                                        "Ova kolekcija nema izravno povezanih video datoteka.",
                                    color = Color.LightGray,
                                    fontSize = 13.sp
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                Box(
                                    modifier = Modifier
                                        .height(44.dp)
                                        .background(
                                            color = if (isFavFocused) Color(0xFF333333) else Color(0xFF1A1A1A),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .border(
                                            width = if (isFavFocused) 3.5.dp else 1.dp,
                                            color = if (isFavFocused) Color.White else Color.DarkGray,
                                            shape = RoundedCornerShape(8.dp)
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
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = if (isFavorite) "U FAVORITIMA" else "DODAJ U FAVORITE",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }

                        if (seriesDescription.isNotBlank()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = seriesDescription,
                                color = Color(0xFFCCCCCC),
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
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
private fun SeasonChipButton(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
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
