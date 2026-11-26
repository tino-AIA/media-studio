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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import coil.Coil
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Scale
import com.example.mediastudio.data.api.PosterFetcher
import com.example.mediastudio.data.api.SupabaseRepository
import com.example.mediastudio.data.model.ArchiveMovie
import com.example.mediastudio.ui.viewmodel.HomeViewModel
import com.example.mediastudio.ui.viewmodel.RatedMovie
import kotlinx.coroutines.delay

private fun Modifier.throttleGenreVerticalDpad(minIntervalMs: Long = 105L): Modifier = composed {
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun GenreScreen(
    viewModel: HomeViewModel,
    onMovieClick: (ArchiveMovie) -> Unit,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val selectedGenre by viewModel.selectedGenre.collectAsState()
    val movies by viewModel.genreMovies.collectAsState()
    val isLoading by viewModel.isGenreLoading.collectAsState()
    val currentPage by viewModel.currentGenrePage.collectAsState()
    val totalPages by viewModel.totalGenrePages.collectAsState()
    val totalCount by viewModel.totalGenreMoviesCount.collectAsState()
    val lastFocusedMovieId by viewModel.lastFocusedMovieId.collectAsState()

    val isSeries = selectedGenre?.isSeriesCategory == true
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = viewModel.savedGenreScrollIndex,
        initialFirstVisibleItemScrollOffset = viewModel.savedGenreScrollOffset
    )

    val firstItemFocusRequester = remember { FocusRequester() }
    var targetPageForFirstFocus by remember { mutableIntStateOf(0) }

    DisposableEffect(gridState) {
        onDispose {
            viewModel.savedGenreScrollIndex = gridState.firstVisibleItemIndex
            viewModel.savedGenreScrollOffset = gridState.firstVisibleItemScrollOffset
            try {
                Coil.imageLoader(context).memoryCache?.clear()
            } catch (_: Exception) {
            }
        }
    }

    BackHandler {
        onBackClick()
    }

    LaunchedEffect(movies, isLoading, currentPage, targetPageForFirstFocus) {
        if (targetPageForFirstFocus > 0 &&
            !isLoading &&
            currentPage == targetPageForFirstFocus &&
            movies.isNotEmpty()
        ) {
            gridState.scrollToItem(0)
            delay(80L)
            try {
                firstItemFocusRequester.requestFocus()
            } catch (_: Exception) {
            }
            targetPageForFirstFocus = 0
        }
    }

    LaunchedEffect(movies, lastFocusedMovieId) {
        val targetId = lastFocusedMovieId
        if (targetPageForFirstFocus == 0 && movies.isNotEmpty() && !targetId.isNullOrBlank()) {
            val targetIndex = movies.indexOfFirst { it.movie.identifier == targetId }
            if (targetIndex >= 0) {
                val isAlreadyVisible = gridState.layoutInfo.visibleItemsInfo.any { it.index == targetIndex }
                if (!isAlreadyVisible) {
                    gridState.scrollToItem(targetIndex)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.focusProperties {
                    up = FocusRequester.Cancel
                    left = FocusRequester.Cancel
                },
                title = {
                    Column {
                        Text(
                            text = selectedGenre?.title ?: "Žanr",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                        if (totalCount > 0) {
                            Text(
                                text = "Stranica $currentPage od $totalPages • Ukupno $totalCount naslova",
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    TvHighlightIconButton(
                        onClick = onBackClick,
                        contentDescription = "Povratak",
                        modifier = Modifier.focusProperties { left = FocusRequester.Cancel }
                    ) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Povratak",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        bottomBar = {
            if (totalPages > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF121212))
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PaginationNavButton(
                        text = "◀ Prethodna",
                        enabled = currentPage > 1 && !isLoading,
                        isPrimary = false,
                        modifier = Modifier.focusProperties { left = FocusRequester.Cancel },
                        onClick = {
                            val nextPage = currentPage - 1
                            targetPageForFirstFocus = nextPage
                            viewModel.savedGenreScrollIndex = 0
                            viewModel.savedGenreScrollOffset = 0
                            viewModel.clearLastFocusedMovie()
                            try {
                                Coil.imageLoader(context).memoryCache?.clear()
                            } catch (_: Exception) {
                            }
                            viewModel.loadGenrePage(nextPage)
                        }
                    )

                    Text(
                        text = "$currentPage / $totalPages",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )

                    PaginationNavButton(
                        text = "Sljedeća ▶",
                        enabled = currentPage < totalPages && !isLoading,
                        isPrimary = true,
                        modifier = Modifier.focusProperties { right = FocusRequester.Cancel },
                        onClick = {
                            val nextPage = currentPage + 1
                            targetPageForFirstFocus = nextPage
                            viewModel.savedGenreScrollIndex = 0
                            viewModel.savedGenreScrollOffset = 0
                            viewModel.clearLastFocusedMovie()
                            try {
                                Coil.imageLoader(context).memoryCache?.clear()
                            } catch (_: Exception) {
                            }
                            viewModel.loadGenrePage(nextPage)
                        }
                    )
                }
            }
        },
        containerColor = Color.Black
    ) { paddingValues ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.Red, modifier = Modifier.size(36.dp))
            }
        } else if (movies.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text("Nema pronađenih naslova na ovoj stranici.", color = Color.Gray, fontSize = 14.sp)
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 100.dp),
                contentPadding = PaddingValues(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 58.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .clipToBounds()
                    .throttleGenreVerticalDpad()
            ) {
                itemsIndexed(
                    items = movies,
                    key = { _, rated -> rated.movie.identifier }
                ) { index, ratedMovie ->
                    val authorPhoto = if (ratedMovie.movie.identifier.startsWith("AUTHOR|")) {
                        val authorName = ratedMovie.movie.identifier.removePrefix("AUTHOR|")
                        SupabaseRepository.getAuthors()
                            .find { it.name.equals(authorName, ignoreCase = true) }
                            ?.photoUrl
                            ?.takeIf { it.isNotBlank() }
                            ?: viewModel.staticAuthorImages[authorName].orEmpty()
                    } else {
                        ""
                    }

                    RatedMovieGridCard(
                        ratedMovie = ratedMovie,
                        isSeries = isSeries,
                        authorPhotoUrl = authorPhoto,
                        modifier = if (index == 0) {
                            Modifier.focusRequester(firstItemFocusRequester)
                        } else {
                            Modifier
                        },
                        shouldRestoreFocus = ratedMovie.movie.identifier == lastFocusedMovieId,
                        onFocusRestored = { viewModel.clearLastFocusedMovie() },
                        onMovieClick = { clickedMovie ->
                            viewModel.savedGenreScrollIndex = gridState.firstVisibleItemIndex
                            viewModel.savedGenreScrollOffset = gridState.firstVisibleItemScrollOffset
                            viewModel.rememberSelectedItem(clickedMovie)
                            onMovieClick(clickedMovie)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PaginationNavButton(
    text: String,
    enabled: Boolean,
    isPrimary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .background(
                color = when {
                    !enabled -> Color(0xFF1A1A1A)
                    isFocused -> Color(0xFFFF1E27)
                    isPrimary -> Color.Red
                    else -> Color(0xFF262626)
                },
                shape = RoundedCornerShape(7.dp)
            )
            .border(
                width = if (isFocused && enabled) 1.8.dp else 0.8.dp,
                color = if (isFocused && enabled) Color.White.copy(alpha = 0.88f) else Color.White.copy(alpha = 0.25f),
                shape = RoundedCornerShape(7.dp)
            )
            .clickable(enabled = enabled, interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 15.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) Color.White else Color.DarkGray,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
    }
}

@Composable
fun RatedMovieGridCard(
    ratedMovie: RatedMovie,
    isSeries: Boolean,
    authorPhotoUrl: String = "",
    modifier: Modifier = Modifier,
    shouldRestoreFocus: Boolean = false,
    onFocusRestored: () -> Unit = {},
    onMovieClick: (ArchiveMovie) -> Unit
) {
    val context = LocalContext.current
    val movie = ratedMovie.movie
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scaleVal = if (isFocused) 1.035f else 1f
    val borderWidth = if (isFocused) 1.8.dp else 0.8.dp
    val borderBrush = if (isFocused) {
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.92f),
                Color.White.copy(alpha = 0.50f)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.22f),
                Color.White.copy(alpha = 0.10f)
            )
        )
    }
    val cardFocusRequester = remember { FocusRequester() }

    LaunchedEffect(shouldRestoreFocus) {
        if (shouldRestoreFocus) {
            delay(60L)
            try {
                cardFocusRequester.requestFocus()
                onFocusRestored()
            } catch (_: Exception) {
            }
        }
    }

    val isAuthor = movie.identifier.startsWith("AUTHOR|")
    val firstArchiveId = movie.identifier.split(",").first()
    var posterUrl by remember(movie.identifier, authorPhotoUrl) {
        mutableStateOf(
            if (isAuthor) authorPhotoUrl
            else SupabaseRepository.getCatalogItem(movie.identifier)?.posterUrl.orEmpty()
        )
    }
    val fallbackUrl = if (isAuthor) "" else "https://archive.org/services/img/$firstArchiveId"

    LaunchedEffect(movie.identifier, movie.title, authorPhotoUrl) {
        if (isAuthor) {
            val authorName = movie.identifier.removePrefix("AUTHOR|")
            val resolved = authorPhotoUrl.ifBlank {
                SupabaseRepository.getAuthors()
                    .find { it.name.equals(authorName, ignoreCase = true) }
                    ?.photoUrl
                    .orEmpty()
            }
            posterUrl = resolved
        } else {
            val sbPoster = SupabaseRepository.getCatalogItem(movie.identifier)?.posterUrl.orEmpty()
            if (sbPoster.isNotBlank()) {
                posterUrl = sbPoster
            } else {
                val fetchedUrl = PosterFetcher.fetchPoster(movie.title, isSeries, movie.identifier)
                posterUrl = fetchedUrl.ifBlank { fallbackUrl }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scaleVal
                scaleY = scaleVal
            }
            .focusRequester(cardFocusRequester)
            .clickable(interactionSource = interactionSource, indication = null) {
                onMovieClick(movie)
            }
            .padding(bottom = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(146.dp)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .border(borderWidth, borderBrush, RoundedCornerShape(9.dp)),
                shape = RoundedCornerShape(9.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = if (isFocused) 8.dp else 0.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1C))
            ) {
                val activeImageUrl = posterUrl.ifBlank { fallbackUrl }
                if (activeImageUrl.isBlank()) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color(0xFF1F1F1F)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = movie.title,
                            tint = Color.Gray,
                            modifier = Modifier.size(46.dp)
                        )
                    }
                } else {
                    val imageRequest = remember(activeImageUrl) {
                        ImageRequest.Builder(context)
                            .data(activeImageUrl)
                            .size(140, 210)
                            .scale(Scale.FILL)
                            .bitmapConfig(Bitmap.Config.RGB_565)
                            .allowHardware(true)
                            .build()
                    }
                    val painter = rememberAsyncImagePainter(model = imageRequest)
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (painter.state is AsyncImagePainter.State.Loading) {
                            CircularProgressIndicator(color = Color.Gray, modifier = Modifier.size(18.dp))
                        }
                        if (painter.state is AsyncImagePainter.State.Error) {
                            if (isAuthor || fallbackUrl.isBlank()) {
                                Box(
                                    modifier = Modifier.fillMaxSize().background(Color(0xFF1F1F1F)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = movie.title,
                                        tint = Color.Gray,
                                        modifier = Modifier.size(46.dp)
                                    )
                                }
                            } else {
                                val fallbackRequest = remember(fallbackUrl) {
                                    ImageRequest.Builder(context)
                                        .data(fallbackUrl)
                                        .size(140, 210)
                                        .scale(Scale.FILL)
                                        .bitmapConfig(Bitmap.Config.RGB_565)
                                        .allowHardware(true)
                                        .build()
                                }
                                Image(
                                    painter = rememberAsyncImagePainter(fallbackRequest),
                                    contentDescription = movie.title,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        } else {
                            Image(
                                painter = painter,
                                contentDescription = movie.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                alignment = if (isAuthor) Alignment.TopCenter else Alignment.Center
                            )
                        }
                    }
                }
            }

            if (ratedMovie.rating.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(5.dp))
                        .border(0.8.dp, Color(0xFFFFC107).copy(alpha = 0.85f), RoundedCornerShape(5.dp))
                        .padding(horizontal = 5.dp, vertical = 1.5.dp)
                ) {
                    Text(
                        text = "★ ${ratedMovie.rating}",
                        color = Color(0xFFFFC107),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = movie.title,
            color = if (isFocused) Color.White else Color.LightGray,
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
