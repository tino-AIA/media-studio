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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Scale
import com.example.mediastudio.data.api.ArchiveMetadataModule
import com.example.mediastudio.data.api.MovieFullInfo
import com.example.mediastudio.data.api.PosterFetcher
import com.example.mediastudio.data.api.SupabaseCatalogItem
import com.example.mediastudio.data.api.SupabaseRepository
import com.example.mediastudio.data.api.VideoQualityOption
import com.example.mediastudio.data.model.ArchiveMovie
import com.example.mediastudio.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

private const val DETAILS_TMDB_API_KEY = "8265bd1679663a7ea12ac168da84d2e8"

private enum class DetailKind {
    MOVIE_OR_FEATURE_ANIM,
    SHORT_CARTOON,
    CONCERT,
    AUTHOR_COLLECTION
}

private data class StructuredDetailSheet(
    val kind: DetailKind,
    val genre: String = "",
    val year: String = "",
    val director: String = "",
    val cast: String = "",
    val awards: String = "",
    val studio: String = "",
    val tourName: String = "",
    val bandActiveYears: String = "",
    val bandMembers: String = "",
    val synopsis: String = ""
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
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
    var detailSheet by remember { mutableStateOf<StructuredDetailSheet?>(null) }
    var imageUrl by remember { mutableStateOf("") }
    var resolvedStreamUrl by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var isSupabaseAuthorList by remember { mutableStateOf(false) }
    var lastSelectedAuthorItemUrl by remember(movie.identifier) { mutableStateOf<String?>(null) }

    val playButtonFocusRequester = remember { FocusRequester() }
    val firstEpisodeFocusRequester = remember { FocusRequester() }
    val selectedAuthorItemFocusRequester = remember { FocusRequester() }

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
        detailSheet = null

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
                val rawBio = sbAuthor.bioHr.ifBlank { fetchWikiTextHr(authorName) }
                val hrBio = cleanWikiMarkupHr(ensureHrText(rawBio))
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
                    description = hrBio,
                    year = "",
                    director = authorName
                )
                detailSheet = StructuredDetailSheet(
                    kind = DetailKind.AUTHOR_COLLECTION,
                    director = authorName,
                    synopsis = hrBio
                )
            } else {
                val archiveId = viewModel.getAuthorArchiveId(authorName)
                imageUrl = viewModel.staticAuthorImages[authorName] ?: ""
                val hrBio = cleanWikiMarkupHr(ensureHrText(fetchWikiTextHr(authorName)))
                val info = ArchiveMetadataModule.fetchMovieFullInfo(archiveId)
                movieInfo = info
                detailSheet = StructuredDetailSheet(
                    kind = DetailKind.AUTHOR_COLLECTION,
                    director = authorName,
                    synopsis = hrBio
                )
            }
        } else if (isDirectLinkMode) {
            resolvedStreamUrl = movie.identifier
            val sbMatch = SupabaseRepository.findByStreamUrl(movie.identifier)
            val sbCatalogMatch = SupabaseRepository.getCatalogItems().find { it.streamUrl == movie.identifier }
            val displayTitle = sbCatalogMatch?.title?.ifBlank { movie.title } ?: movie.title
            val yearHint = sbCatalogMatch?.releaseYear?.takeIf { it > 0 }?.toString() ?: (movie.year ?: "")

            if (sbMatch != null) {
                imageUrl = sbMatch.first
                val dbDesc = sbMatch.second.ifBlank {
                    sbCatalogMatch?.let {
                        it.descriptionHr.ifBlank { SupabaseRepository.loadItemDescription(it.archiveId) }
                    }.orEmpty()
                }
                val hrDesc = cleanWikiMarkupHr(
                    ensureHrText(dbDesc.ifBlank { fetchMoviePlotHr(displayTitle, yearHint.toIntOrNull() ?: 0) })
                )
                detailSheet = buildStructuredSheet(
                    title = displayTitle,
                    identifier = sbCatalogMatch?.archiveId ?: movie.identifier,
                    sbItem = sbCatalogMatch,
                    yearHint = yearHint,
                    directorHint = "",
                    rawDesc = hrDesc
                )
            } else if (sbCatalogMatch != null) {
                imageUrl = sbCatalogMatch.posterUrl
                val dbDesc = sbCatalogMatch.descriptionHr.ifBlank {
                    SupabaseRepository.loadItemDescription(sbCatalogMatch.archiveId)
                }
                val hrDesc = cleanWikiMarkupHr(
                    ensureHrText(dbDesc.ifBlank { fetchMoviePlotHr(displayTitle, sbCatalogMatch.releaseYear) })
                )
                detailSheet = buildStructuredSheet(
                    title = displayTitle,
                    identifier = sbCatalogMatch.archiveId,
                    sbItem = sbCatalogMatch,
                    yearHint = yearHint,
                    directorHint = "",
                    rawDesc = hrDesc
                )
            } else {
                imageUrl = PosterFetcher.fetchPoster(displayTitle, isSeries = false, movie.identifier)
                val hrDesc = cleanWikiMarkupHr(
                    ensureHrText(fetchMoviePlotHr(displayTitle, yearHint.toIntOrNull() ?: 0))
                )
                detailSheet = buildStructuredSheet(
                    title = displayTitle,
                    identifier = movie.identifier,
                    sbItem = null,
                    yearHint = yearHint,
                    directorHint = "",
                    rawDesc = hrDesc
                )
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
                    val hrDesc = cleanWikiMarkupHr(
                        ensureHrText(dbDescription.ifBlank { fetchMoviePlotHr(sbItem.title, sbItem.releaseYear) })
                    )
                    movieInfo = MovieFullInfo(
                        qualities = emptyList(),
                        description = hrDesc,
                        year = yearStr,
                        director = matchedAuthor
                    )
                    detailSheet = buildStructuredSheet(
                        title = sbItem.title,
                        identifier = movie.identifier,
                        sbItem = sbItem,
                        yearHint = yearStr,
                        directorHint = matchedAuthor,
                        rawDesc = hrDesc
                    )
                } else {
                    val info = ArchiveMetadataModule.fetchMovieFullInfo(movie.identifier)
                    val mergedYear = yearStr.ifEmpty { info.year }
                    val mergedDirector = info.director
                    movieInfo = info.copy(year = mergedYear)
                    val cleanArchiveDesc = if (info.description.startsWith("Opis za ovaj naslov")) "" else info.description
                    val hrDesc = cleanWikiMarkupHr(
                        ensureHrText(
                            dbDescription
                                .ifBlank { cleanArchiveDesc }
                                .ifBlank { fetchMoviePlotHr(sbItem.title.ifBlank { movie.title }, sbItem.releaseYear) }
                        )
                    )
                    detailSheet = buildStructuredSheet(
                        title = sbItem.title.ifBlank { movie.title },
                        identifier = movie.identifier,
                        sbItem = sbItem,
                        yearHint = mergedYear,
                        directorHint = mergedDirector,
                        rawDesc = hrDesc
                    )
                }
            } else {
                imageUrl = PosterFetcher.fetchPoster(movie.title, isSeries = false, movie.identifier)
                val info = ArchiveMetadataModule.fetchMovieFullInfo(movie.identifier)
                movieInfo = info
                val cleanArchiveDesc = if (info.description.startsWith("Opis za ovaj naslov")) "" else info.description
                val hrDesc = cleanWikiMarkupHr(
                    ensureHrText(
                        cleanArchiveDesc.ifBlank { fetchMoviePlotHr(movie.title, info.year.toIntOrNull() ?: 0) }
                    )
                )
                detailSheet = buildStructuredSheet(
                    title = movie.title,
                    identifier = movie.identifier,
                    sbItem = null,
                    yearHint = info.year,
                    directorHint = info.director,
                    rawDesc = hrDesc
                )
            }
        }

        isLoading = false
        delay(60L)
        try {
            val hasCollectionList = !isDirectLinkMode &&
                ((isAuthorMode && (movieInfo?.qualities?.isNotEmpty() == true)) || (movieInfo?.qualities?.size ?: 0) > 1)
            if (hasCollectionList) {
                if (lastSelectedAuthorItemUrl != null) {
                    selectedAuthorItemFocusRequester.requestFocus()
                } else {
                    firstEpisodeFocusRequester.requestFocus()
                }
            } else {
                playButtonFocusRequester.requestFocus()
            }
        } catch (_: Exception) {
        }
    }

    val showCollectionList = !isDirectLinkMode &&
        ((isAuthorMode && (movieInfo?.qualities?.isNotEmpty() == true)) || (movieInfo?.qualities?.size ?: 0) > 1)

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
                    Text(
                        text = if (isAuthorMode || showCollectionList) "Kolekcija autora" else "Detalji",
                        color = Color.White
                    )
                },
                navigationIcon = {
                    TvHighlightIconButton(
                        onClick = onBackClick,
                        contentDescription = "Povratak",
                        modifier = Modifier.focusProperties { left = FocusRequester.Cancel }
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
                val sheet = detailSheet
                val currentInfo = movieInfo

                if (sheet != null) {
                    Spacer(modifier = Modifier.height(14.dp))
                    StructuredInfoCard(sheet = sheet)
                }

                Spacer(modifier = Modifier.height(22.dp))

                if (currentInfo != null && showCollectionList) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .height(50.dp)
                            .padding(bottom = 24.dp)
                            .focusProperties {
                                left = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                            }
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
                            } catch (_: Exception) {
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
                        val directDownloadUrl = quality.url.replace("/details/", "/download/")
                        val isPreviouslySelected = lastSelectedAuthorItemUrl == directDownloadUrl
                        val focusMod = when {
                            isPreviouslySelected -> Modifier.focusRequester(selectedAuthorItemFocusRequester)
                            index == 0 -> Modifier.focusRequester(firstEpisodeFocusRequester)
                            else -> Modifier
                        }

                        Box(
                            modifier = focusMod
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .focusProperties {
                                    left = FocusRequester.Cancel
                                    right = FocusRequester.Cancel
                                }
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
                                    lastSelectedAuthorItemUrl = directDownloadUrl
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
                                .focusProperties { left = FocusRequester.Cancel }
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
                                .focusProperties { right = FocusRequester.Cancel }
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

@Composable
private fun StructuredInfoCard(sheet: StructuredDetailSheet) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF141414), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF2B2B2B), RoundedCornerShape(12.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (sheet.kind) {
            DetailKind.SHORT_CARTOON -> {
                DetailMetaLine(label = "Godina proizvodnje", value = sheet.year)
                DetailMetaLine(label = "Studio proizvodnje", value = sheet.studio)
                DetailMetaLine(label = "Žanr", value = sheet.genre)
            }

            DetailKind.CONCERT -> {
                val hasConcertSection = sheet.year.isNotBlank() || sheet.tourName.isNotBlank()
                val hasBandSection = sheet.genre.isNotBlank() || sheet.bandActiveYears.isNotBlank() || sheet.bandMembers.isNotBlank()

                if (hasConcertSection) {
                    Text(
                        text = "Podaci o koncertu",
                        color = Color(0xFFFF5252),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    DetailMetaLine(label = "Godina", value = sheet.year)
                    DetailMetaLine(label = "Naziv turneje", value = sheet.tourName)
                }

                if (hasBandSection) {
                    if (hasConcertSection) {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(
                        text = "Podaci o bendu",
                        color = Color(0xFFFF5252),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    DetailMetaLine(label = "Žanr", value = sheet.genre)
                    DetailMetaLine(label = "Djelovanje", value = sheet.bandActiveYears)
                    DetailMetaLine(label = "Članovi", value = sheet.bandMembers)
                }
            }

            DetailKind.MOVIE_OR_FEATURE_ANIM -> {
                DetailMetaLine(label = "Žanr", value = sheet.genre)
                DetailMetaLine(label = "Godina proizvodnje", value = sheet.year)
                DetailMetaLine(label = "Glavni glumci", value = sheet.cast)
                DetailMetaLine(label = "Redatelj", value = sheet.director)
                DetailMetaLine(label = "Nagrade", value = sheet.awards)
            }

            DetailKind.AUTHOR_COLLECTION -> {
                DetailMetaLine(label = "Autor", value = sheet.director)
            }
        }

        if (sheet.synopsis.isNotBlank()) {
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
                text = sheet.synopsis,
                color = Color(0xFFDDDDDD),
                fontSize = 14.sp,
                lineHeight = 21.sp
            )
        }
    }
}

@Composable
private fun DetailMetaLine(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
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

private fun translateGenreToCroatian(rawGenre: String): String {
    val g = rawGenre.trim()
    if (g.isEmpty()) return ""
    return when (g.lowercase()) {
        "action", "action & adventure" -> "Akcija i avantura"
        "adventure" -> "Avantura"
        "animation", "animated", "cartoon", "cartoons" -> "Animirani"
        "comedy" -> "Komedija"
        "crime" -> "Krimi"
        "documentary" -> "Dokumentarni"
        "drama" -> "Drama"
        "family" -> "Obiteljski"
        "fantasy" -> "Fantazija"
        "history" -> "Povijesni"
        "horror" -> "Horor"
        "music", "musical", "concert" -> "Glazba i koncerti"
        "mystery" -> "Misterija"
        "romance" -> "Romantični"
        "science fiction", "sci-fi" -> "Znanstvena fantastika"
        "thriller" -> "Triler"
        "war" -> "Ratni"
        "western" -> "Vestern"
        "rock" -> "Rock"
        "metal", "heavy metal" -> "Metal"
        "punk" -> "Punk"
        "pop" -> "Pop"
        "jazz" -> "Jazz"
        "blues" -> "Blues"
        "classical" -> "Klasična glazba"
        else -> g
    }
}

private fun formatGenresHr(genres: List<String>): String {
    val cleaned = genres
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("18+") }
        .map { translateGenreToCroatian(it) }
        .distinct()
    return cleaned.joinToString(", ")
}

private fun extractYearHr(yearHint: String, title: String, identifier: String): String {
    val direct = yearHint.trim().takeIf { it.matches(Regex("\\b(19\\d{2}|20\\d{2})\\b")) }
    if (direct != null) return direct
    val fromTitle = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(title)?.groupValues?.get(1)
    if (fromTitle != null) return fromTitle
    return Regex("\\b(19\\d{2}|20\\d{2})\\b").find(identifier)?.groupValues?.get(1).orEmpty()
}

private fun cleanWikiMarkupHr(text: String): String {
    if (text.isBlank()) return ""
    return text
        .replace(Regex("={2,}\\s*[^=]+\\s*={2,}"), " ")
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("(?i)\\b(?:follow|twitter\\.com|facebook\\.com|instagram\\.com|vimeo\\.com|uploader|upload date)\\b[^\\n]*"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
}

private fun extractAwardsFromTextHr(text: String): String {
    if (text.isBlank()) return ""
    val cleaned = cleanWikiMarkupHr(text)
    val awardKeywords = listOf(
        "oscar", "academy award", "zlatna palma", "palme d'or", "zlatni lav", "golden lion",
        "zlatni medvjed", "golden bear", "zlatna arena", "vladimir nazor", "emmy",
        "bafta", "zlatni globus", "golden globe", "saturn", "nagrada", "nagradu",
        "nagrađen", "nagrađena", "nominiran", "nominirana", "award"
    )
    val roleLikeWords = listOf(" kao ", " glas ", " uloga ", " uloge ", " sinkroniz")

    val hits = cleaned.split(Regex("(?<=[.!?])\\s+"))
        .map { it.trim() }
        .filter { s ->
            val sl = s.lowercase()
            awardKeywords.any { kw -> sl.contains(kw) } &&
                !roleLikeWords.any { rw -> sl.contains(rw) }
        }
        .distinct()

    return hits.take(2).joinToString(" ")
}

private fun extractStudioFromTextHr(title: String, identifier: String, desc: String, genres: List<String>): String {
    val combined = "$title $identifier $desc ${genres.joinToString(" ")}".lowercase()
    val knownStudios = listOf(
        "Zagreb Film" to listOf("zagreb film"),
        "Walt Disney" to listOf("walt disney", "disney"),
        "Pixar" to listOf("pixar"),
        "Warner Bros." to listOf("warner bros", "looney tunes", "merrie melodies"),
        "MGM" to listOf("mgm", "metro-goldwyn-mayer", "tom and jerry"),
        "Fleischer Studios" to listOf("fleischer", "popeye", "betty boop"),
        "Universal" to listOf("universal", "woody woodpecker"),
        "Studio Ghibli" to listOf("ghibli")
    )
    for ((studioHr, keys) in knownStudios) {
        if (keys.any { combined.contains(it) }) return studioHr
    }
    return ""
}

private fun extractTourFromTextHr(title: String, desc: String): String {
    val patterns = listOf(
        Regex("(?i)(?:naziv\\s+turneje|turneja|svjetska\\s+turneja|tour)\\s*[:\\-–]?\\s*([A-ZČĆŽŠĐ0-9][^\\n.;]{2,60})")
    )
    for (p in patterns) {
        val m = p.find("$title\n$desc")
        if (m != null) {
            val candidate = m.groupValues[1].trim()
            if (candidate.length >= 3 && !candidate.lowercase().startsWith("nije")) {
                return candidate
            }
        }
    }
    return ""
}

private fun extractBandActiveFromTextHr(desc: String): String {
    val m = Regex("(?i)(?:djelovanje|aktivan|aktivni|djeluje|djelovali|osnovan(?:i|a)?\\s+\\d{4})[^\\n.]{0,45}").find(desc)
    return m?.value?.trim().orEmpty()
}

private fun extractBandMembersFromTextHr(desc: String): String {
    val m = Regex("(?i)(?:članovi|članovi benda|postava|members)\\s*[:\\-–]?\\s*([^\\n.]{4,140})").find(desc)
    return m?.groupValues?.get(1)?.trim().orEmpty()
}

private fun extractCastFromTextHr(desc: String): String {
    val cleaned = cleanWikiMarkupHr(desc)
    val explicitMatch = Regex("(?i)(?:glavne uloge|glavni glumci|uloge|glume|starring|cast)\\s*[:\\-–]?\\s*([^\\n.]{4,180})").find(cleaned)
    if (explicitMatch != null) {
        return explicitMatch.groupValues[1].trim()
    }
    val voiceLines = cleaned.split(Regex("[\\n]+"))
        .map { it.trim() }
        .filter { line ->
            val ll = line.lowercase()
            ll.contains(" kao ") && ll.length in 6..90 && !ll.contains("nagrada") && !ll.contains("oscar")
        }
        .take(5)
    if (voiceLines.isNotEmpty()) {
        return voiceLines.joinToString(", ")
    }
    return ""
}

private suspend fun fetchTmdbMovieCreditsHr(tmdbId: Int, title: String, yearStr: String): Pair<String, String> = withContext(Dispatchers.IO) {
    try {
        var resolvedId = tmdbId
        if (resolvedId <= 0) {
            val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
            if (cleanTitle.length >= 2) {
                val encodedQ = URLEncoder.encode(cleanTitle, "UTF-8")
                val yearParam = yearStr.toIntOrNull()?.takeIf { it > 1880 }?.let { "&year=$it" } ?: ""
                val searchUrl = "https://api.themoviedb.org/3/search/movie?api_key=$DETAILS_TMDB_API_KEY&query=$encodedQ$yearParam&language=hr-HR"
                val conn = URL(searchUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                val obj = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val first = obj.optJSONArray("results")?.optJSONObject(0)
                resolvedId = first?.optInt("id", 0) ?: 0
            }
        }
        if (resolvedId <= 0) return@withContext "" to ""

        val url = "https://api.themoviedb.org/3/movie/$resolvedId/credits?api_key=$DETAILS_TMDB_API_KEY&language=hr-HR"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        val obj = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })

        val castArr = obj.optJSONArray("cast")
        val castNames = mutableListOf<String>()
        if (castArr != null) {
            for (i in 0 until minOf(castArr.length(), 6)) {
                val name = castArr.optJSONObject(i)?.optString("name", "")?.trim().orEmpty()
                if (name.isNotBlank() && name !in castNames) {
                    castNames.add(name)
                }
            }
        }

        val crewArr = obj.optJSONArray("crew")
        val directorNames = mutableListOf<String>()
        if (crewArr != null) {
            for (i in 0 until crewArr.length()) {
                val crewObj = crewArr.optJSONObject(i) ?: continue
                if (crewObj.optString("job", "") == "Director") {
                    val dName = crewObj.optString("name", "").trim()
                    if (dName.isNotBlank() && dName !in directorNames) {
                        directorNames.add(dName)
                    }
                }
            }
        }

        castNames.joinToString(", ") to directorNames.joinToString(", ")
    } catch (_: Exception) {
        "" to ""
    }
}

private suspend fun buildStructuredSheet(
    title: String,
    identifier: String,
    sbItem: SupabaseCatalogItem?,
    yearHint: String,
    directorHint: String,
    rawDesc: String
): StructuredDetailSheet = withContext(Dispatchers.IO) {
    val genresList = sbItem?.genres.orEmpty()
    val genreHr = formatGenresHr(genresList)
    val yearHr = extractYearHr(yearHint, title, identifier)
    val lowerBlob = "${title.lowercase()} ${identifier.lowercase()} ${genresList.joinToString(" ").lowercase()}"

    val isConcert = sbItem?.mediaType == "concert" ||
        lowerBlob.contains("concert") || lowerBlob.contains("koncert") ||
        lowerBlob.contains("live aid") || lowerBlob.contains("vhsconcerts")

    val isShortCartoon = !isConcert && (
        genresList.any { g ->
            g.equals("Klasični crtići", ignoreCase = true) ||
                g.equals("Disney kratki crtići", ignoreCase = true)
        } || (
            sbItem?.mediaType == "movie" &&
                sbItem.archiveRuntime in 1..39 &&
                (genresList.any { it.contains("crtić", true) || it.contains("animir", true) } ||
                    lowerBlob.contains("cartoon") || lowerBlob.contains("crtić"))
            )
        )

    val kind = when {
        isConcert -> DetailKind.CONCERT
        isShortCartoon -> DetailKind.SHORT_CARTOON
        else -> DetailKind.MOVIE_OR_FEATURE_ANIM
    }

    var directorHr = ensureHrText(directorHint)
    var castHr = extractCastFromTextHr(rawDesc)
    var awardsHr = extractAwardsFromTextHr(rawDesc)
    var studioHr = extractStudioFromTextHr(title, identifier, rawDesc, genresList)
    var tourHr = extractTourFromTextHr(title, rawDesc)
    var activeHr = extractBandActiveFromTextHr(rawDesc)
    var membersHr = extractBandMembersFromTextHr(rawDesc)
    var synopsisHr = rawDesc

    if (kind == DetailKind.MOVIE_OR_FEATURE_ANIM) {
        val (tmdbCast, tmdbDirector) = fetchTmdbMovieCreditsHr(sbItem?.tmdbId ?: 0, title, yearHr)
        if (castHr.isBlank() && tmdbCast.isNotBlank()) castHr = tmdbCast
        if (directorHr.isBlank() && tmdbDirector.isNotBlank()) directorHr = tmdbDirector
    }

    val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
    if (cleanTitle.isNotBlank()) {
        val wikiText = cleanWikiMarkupHr(ensureHrText(fetchWikiTextHr(cleanTitle)))
        if (castHr.isBlank()) castHr = extractCastFromTextHr(wikiText)
        if (awardsHr.isBlank()) awardsHr = extractAwardsFromTextHr(wikiText)
        if (studioHr.isBlank()) studioHr = extractStudioFromTextHr(title, identifier, wikiText, genresList)
        if (tourHr.isBlank()) tourHr = extractTourFromTextHr(title, wikiText)
        if (activeHr.isBlank()) activeHr = extractBandActiveFromTextHr(wikiText)
        if (membersHr.isBlank()) membersHr = extractBandMembersFromTextHr(wikiText)
        if (directorHr.isBlank() && kind == DetailKind.MOVIE_OR_FEATURE_ANIM) {
            val dirMatch = Regex("(?i)(?:redatelj|režirao|režirala|director)\\s*[:\\-–]?\\s*([A-ZČĆŽŠĐ][a-zčćžšđ]+(?:\\s+[A-ZČĆŽŠĐ][a-zčćžšđ]+){1,2})").find(wikiText)
            directorHr = dirMatch?.groupValues?.get(1)?.trim().orEmpty()
        }
        if (kind == DetailKind.CONCERT) {
            val artistPart = cleanTitle.substringBefore(" - ").trim()
            if (artistPart.isNotBlank() && !artistPart.equals(cleanTitle, ignoreCase = true)) {
                val artistWiki = cleanWikiMarkupHr(ensureHrText(fetchWikiTextHr(artistPart)))
                if (activeHr.isBlank()) activeHr = extractBandActiveFromTextHr(artistWiki)
                if (membersHr.isBlank()) membersHr = extractBandMembersFromTextHr(artistWiki)
            }
            if (synopsisHr.isBlank() && wikiText.isNotBlank()) {
                synopsisHr = wikiText.take(650)
            }
        }
    }

    StructuredDetailSheet(
        kind = kind,
        genre = genreHr,
        year = yearHr,
        director = directorHr,
        cast = castHr,
        awards = awardsHr,
        studio = studioHr,
        tourName = tourHr,
        bandActiveYears = activeHr,
        bandMembers = membersHr,
        synopsis = synopsisHr
    )
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

private suspend fun ensureHrText(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isBlank()) return ""
    return if (looksLikeEnglishText(trimmed)) translateToCroatian(trimmed) else trimmed
}

private suspend fun fetchMoviePlotHr(title: String, year: Int): String = withContext(Dispatchers.IO) {
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
    ""
}

private suspend fun fetchWikiTextHr(queryTitle: String): String = withContext(Dispatchers.IO) {
    try {
        val searchName = if (queryTitle == "PTA Filmography") "Paul Thomas Anderson" else queryTitle
        val encodedName = URLEncoder.encode(searchName, "UTF-8")

        val hrUrl = URL("https://hr.wikipedia.org/w/api.php?action=query&titles=$encodedName&prop=extracts&explaintext=1&format=json&redirects=1")
        val hrConn = hrUrl.openConnection() as HttpURLConnection
        hrConn.setRequestProperty("User-Agent", "Mozilla/5.0")
        hrConn.connectTimeout = 5000
        hrConn.readTimeout = 5000
        val hrJson = JSONObject(hrConn.inputStream.bufferedReader().use { it.readText() })
        val hrPages = hrJson.getJSONObject("query").getJSONObject("pages")
        val hrId = hrPages.keys().next()
        if (hrId != "-1") {
            val extract = hrPages.getJSONObject(hrId).optString("extract").trim()
            if (extract.isNotEmpty()) return@withContext extract.take(1800)
        }

        val enUrl = URL("https://en.wikipedia.org/w/api.php?action=query&titles=$encodedName&prop=extracts&explaintext=1&format=json&redirects=1")
        val enConn = enUrl.openConnection() as HttpURLConnection
        enConn.setRequestProperty("User-Agent", "Mozilla/5.0")
        enConn.connectTimeout = 5000
        enConn.readTimeout = 5000
        val enJson = JSONObject(enConn.inputStream.bufferedReader().use { it.readText() })
        val enPages = enJson.getJSONObject("query").getJSONObject("pages")
        val enId = enPages.keys().next()
        if (enId != "-1") {
            val extract = enPages.getJSONObject(enId).optString("extract").trim()
            if (extract.isNotEmpty()) return@withContext translateToCroatian(extract.take(1500))
        }
        ""
    } catch (_: Exception) {
        ""
    }
}

private suspend fun translateToCroatian(text: String): String = withContext(Dispatchers.IO) {
    if (text.isBlank()) return@withContext ""
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
