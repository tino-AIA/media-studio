package com.example.mediastudio.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.example.mediastudio.data.model.ArchiveMovie
import com.example.mediastudio.ui.viewmodel.HomeViewModel
import com.example.mediastudio.ui.viewmodel.RatedMovie

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenreScreen(
    viewModel: HomeViewModel,
    onMovieClick: (ArchiveMovie) -> Unit,
    onBackClick: () -> Unit
) {
    val selectedGenre by viewModel.selectedGenre.collectAsState()
    val movies by viewModel.genreMovies.collectAsState()
    val isLoading by viewModel.isGenreLoading.collectAsState()
    val currentPage by viewModel.currentGenrePage.collectAsState()
    val totalPages by viewModel.totalGenrePages.collectAsState()
    val totalCount by viewModel.totalGenreMoviesCount.collectAsState()

    val gridState = rememberLazyGridState()

    BackHandler {
        onBackClick()
    }

    LaunchedEffect(currentPage) {
        gridState.scrollToItem(0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = selectedGenre?.title ?: "Žanr",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        if (totalCount > 0) {
                            Text(
                                text = "Stranica $currentPage od $totalPages • Ukupno $totalCount filmova",
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
        bottomBar = {
            if (totalPages > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF121212))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PaginationNavButton(
                        text = "◀ Prethodna",
                        enabled = currentPage > 1 && !isLoading,
                        isPrimary = false,
                        onClick = { viewModel.loadGenrePage(currentPage - 1) }
                    )

                    Text(
                        text = "$currentPage / $totalPages",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )

                    PaginationNavButton(
                        text = "Sljedeća ▶",
                        enabled = currentPage < totalPages && !isLoading,
                        isPrimary = true,
                        onClick = { viewModel.loadGenrePage(currentPage + 1) }
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
                CircularProgressIndicator(color = Color.Red)
            }
        } else if (movies.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text("Nema pronađenih naslova na ovoj stranici.", color = Color.Gray)
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 120.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize().padding(paddingValues)
            ) {
                items(movies) { ratedMovie ->
                    RatedMovieGridCard(ratedMovie = ratedMovie, onMovieClick = onMovieClick)
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
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .background(
                color = when {
                    !enabled -> Color(0xFF1A1A1A)
                    isFocused -> Color(0xFFFF1E27)
                    isPrimary -> Color.Red
                    else -> Color(0xFF262626)
                },
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = if (isFocused && enabled) 3.dp else 1.dp,
                color = if (isFocused && enabled) Color.White else Color.Gray.copy(alpha = 0.6f),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(enabled = enabled, interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) Color.White else Color.DarkGray,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RatedMovieGridCard(ratedMovie: RatedMovie, onMovieClick: (ArchiveMovie) -> Unit) {
    val movie = ratedMovie.movie
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.07f else 1f, label = "grid_card_scale")
    val borderColor = if (isFocused) Color.White else Color.Gray.copy(alpha = 0.65f)
    val borderWidth = if (isFocused) 3.5.dp else 1.5.dp

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(175.dp)
                .scale(scale)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .border(borderWidth, borderColor, RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                interactionSource = interactionSource,
                onClick = { onMovieClick(movie) }
            ) {
                Image(
                    painter = rememberAsyncImagePainter("https://archive.org/services/img/${movie.identifier}"),
                    contentDescription = movie.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            if (ratedMovie.rating.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                        .border(1.dp, Color(0xFFFFC107), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "★ ${ratedMovie.rating}",
                        color = Color(0xFFFFC107),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = movie.title,
            color = if (isFocused) Color.White else Color.LightGray,
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
