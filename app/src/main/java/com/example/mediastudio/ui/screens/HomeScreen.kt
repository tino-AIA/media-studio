package com.example.mediastudio.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.example.mediastudio.data.api.PosterFetcher
import com.example.mediastudio.data.api.SupabaseRepository
import com.example.mediastudio.data.model.ArchiveMovie
import com.example.mediastudio.ui.viewmodel.ContentTab
import com.example.mediastudio.ui.viewmodel.GenreOption
import com.example.mediastudio.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onMovieClick: (ArchiveMovie) -> Unit,
    onSeriesClick: (ArchiveMovie) -> Unit,
    onGenreSelect: (GenreOption) -> Unit,
    onOpenSearch: () -> Unit
) {
    val context = LocalContext.current
    val selectedTab by viewModel.selectedTab.collectAsState()
    val movieCategories by viewModel.movieCategories.collectAsState()
    val animationCategories by viewModel.animationCategories.collectAsState()
    val seriesCategories by viewModel.seriesCategories.collectAsState()
    val concertCategories by viewModel.concertCategories.collectAsState()

    val favoriteMovies by viewModel.favoriteMovies.collectAsState()
    val heroMovies by viewModel.heroMovies.collectAsState()
    val activeHeroIndex by viewModel.activeHeroIndex.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val authorImages = viewModel.staticAuthorImages

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var showExitDialog by remember { mutableStateOf(false) }

    val currentHeroMovie = heroMovies.getOrNull(activeHeroIndex)
    var currentHeroPosterUrl by remember { mutableStateOf("") }

    LaunchedEffect(currentHeroMovie) {
        currentHeroMovie?.let { (movie, isSeries) ->
            val firstId = movie.identifier.split(",").first()
            val fetchedUrl = PosterFetcher.fetchPoster(movie.title, isSeries, firstId)
            currentHeroPosterUrl = fetchedUrl.ifBlank { "https://archive.org/services/img/$firstId" }
        }
    }

    BackHandler {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else {
            showExitDialog = true
        }
    }

    val activeCategories = when (selectedTab) {
        ContentTab.MOVIES -> movieCategories
        ContentTab.ANIMATION -> animationCategories
        ContentTab.SERIES -> seriesCategories
        ContentTab.CONCERTS -> concertCategories
        else -> emptyList()
    }

    val activeDrawerList = when (selectedTab) {
        ContentTab.MOVIES -> viewModel.movieGenreDefinitions
        ContentTab.ANIMATION -> viewModel.animationGenreDefinitions
        ContentTab.SERIES -> viewModel.seriesGenreDefinitions
        ContentTab.CONCERTS -> viewModel.concertGenreDefinitions
        else -> emptyList()
    }

    val isSeriesTab = selectedTab == ContentTab.SERIES

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(280.dp),
                drawerContainerColor = Color(0xFF121212)
            ) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "Izbornik",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(12.dp)
                    )
                    Divider(color = Color.DarkGray)
                    Spacer(modifier = Modifier.height(8.dp))

                    DrawerFocusableItem(
                        title = "⭐ Moji Favoriti",
                        isAdult = false,
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                viewModel.selectTab(ContentTab.FAVORITES)
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = when (selectedTab) {
                            ContentTab.ANIMATION -> "Kategorije crtića"
                            ContentTab.SERIES -> "Kategorije serija"
                            ContentTab.CONCERTS -> "Kategorije glazbe"
                            else -> "Žanrovi filmova"
                        },
                        color = Color.Gray,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 12.dp, bottom = 4.dp)
                    )

                    activeDrawerList.forEach { genre ->
                        DrawerFocusableItem(
                            title = genre.title,
                            isAdult = genre.title.startsWith("18+"),
                            onClick = {
                                scope.launch {
                                    drawerState.close()
                                    onGenreSelect(genre)
                                }
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (currentHeroPosterUrl.isNotEmpty()) {
                Image(
                    painter = rememberAsyncImagePainter(currentHeroPosterUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(0.35f)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color(0xFF0F0F0F), Color(0xFF0F0F0F).copy(alpha = 0.7f), Color.Transparent),
                            startX = 0f,
                            endX = 1200f
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0xFF0A0A0A)),
                            startY = 200f
                        )
                    )
            )

            Scaffold(
                topBar = {
                    Column(
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .focusProperties { up = FocusRequester.Cancel }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            HomeTvHighlightIconButton(
                                onClick = { scope.launch { drawerState.open() } }
                            ) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
                            }

                            Text(
                                text = "Media Studio",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 21.sp
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            SearchTriggerBar(
                                onClick = onOpenSearch,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            TabButton(
                                title = "🎬 Filmovi",
                                isSelected = selectedTab == ContentTab.MOVIES,
                                onClick = { viewModel.selectTab(ContentTab.MOVIES) },
                                modifier = Modifier.weight(1f)
                            )
                            TabButton(
                                title = "🎨 Animirani",
                                isSelected = selectedTab == ContentTab.ANIMATION,
                                onClick = { viewModel.selectTab(ContentTab.ANIMATION) },
                                modifier = Modifier.weight(1f)
                            )
                            TabButton(
                                title = "📺 Serije",
                                isSelected = selectedTab == ContentTab.SERIES,
                                onClick = { viewModel.selectTab(ContentTab.SERIES) },
                                modifier = Modifier.weight(1f)
                            )
                            TabButton(
                                title = "🎵 Koncerti",
                                isSelected = selectedTab == ContentTab.CONCERTS,
                                onClick = { viewModel.selectTab(ContentTab.CONCERTS) },
                                modifier = Modifier.weight(1f)
                            )
                            TabButton(
                                title = "⭐ Favoriti",
                                isSelected = selectedTab == ContentTab.FAVORITES,
                                onClick = { viewModel.selectTab(ContentTab.FAVORITES) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                },
                containerColor = Color.Transparent
            ) { paddingValues ->
                if (isLoading && selectedTab != ContentTab.FAVORITES) {
                    Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.Red)
                    }
                } else if (selectedTab == ContentTab.FAVORITES) {
                    if (favoriteMovies.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                            Text("Nemate spremljenih favorita.", color = Color.Gray, fontSize = 16.sp)
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 120.dp),
                            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 40.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxSize().padding(paddingValues)
                        ) {
                            items(favoriteMovies) { movie ->
                                val isDbSeries = SupabaseRepository.getCatalogItem(movie.identifier)?.mediaType == "series"
                                val isProbablySeries = isDbSeries ||
                                    movie.identifier.startsWith("tmdb-tv-") ||
                                    movie.title.lowercase().contains("season") ||
                                    movie.title.lowercase().contains("episode")
                                MovieCard(
                                    movie = movie,
                                    isSeries = isProbablySeries,
                                    viewModel = viewModel,
                                    authorImages = authorImages,
                                    onMovieClick = { item -> if (isProbablySeries) onSeriesClick(item) else onMovieClick(item) }
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                    ) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(380.dp)
                                    .padding(start = 24.dp, bottom = 24.dp, end = 24.dp)
                            ) {
                                currentHeroMovie?.let { (movie, isSeries) ->
                                    Column(
                                        modifier = Modifier.align(Alignment.BottomStart),
                                        verticalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        Text(
                                            text = movie.title,
                                            color = Color.White,
                                            fontSize = 38.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            maxLines = 2,
                                            lineHeight = 42.sp,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.width(650.dp)
                                        )

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = if (isSeries) "SERIJA" else "FILM",
                                                color = Color.LightGray,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier
                                                    .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Text(
                                                text = "Istaknuto na Media Studiju",
                                                color = Color.Gray,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }

                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            HeroButton(
                                                text = "Gledaj odmah",
                                                icon = Icons.Default.PlayArrow,
                                                isPrimary = true,
                                                onClick = { if (isSeries) onSeriesClick(movie) else onMovieClick(movie) }
                                            )
                                            HeroButton(
                                                text = "Detalji",
                                                icon = Icons.Default.Info,
                                                isPrimary = false,
                                                onClick = { if (isSeries) onSeriesClick(movie) else onMovieClick(movie) }
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .offset(y = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        heroMovies.forEachIndexed { index, _ ->
                                            Box(
                                                modifier = Modifier
                                                    .size(if (index == activeHeroIndex) 8.dp else 6.dp)
                                                    .background(
                                                        color = if (index == activeHeroIndex) Color.White else Color.Gray.copy(alpha = 0.5f),
                                                        shape = CircleShape
                                                    )
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        items(count = activeCategories.size) { index ->
                            val (genre, itemsList) = activeCategories[index]
                            val isTop10 = genre.title.startsWith("Top 10") || genre.title.startsWith("Najgledaniji")
                            val isRowSeries = isSeriesTab || genre.isSeriesCategory
                            MovieCategoryRow(
                                genre = genre,
                                movies = itemsList,
                                isNetflixTop10 = isTop10,
                                isSeries = isRowSeries,
                                viewModel = viewModel,
                                authorImages = authorImages,
                                onMovieClick = { item ->
                                    if (isRowSeries) onSeriesClick(item) else onMovieClick(item)
                                },
                                onHeaderClick = { onGenreSelect(genre) }
                            )
                        }
                        item { Spacer(modifier = Modifier.height(40.dp)) }
                    }
                }
            }
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            containerColor = Color(0xFF1A1A1A),
            title = {
                Text(
                    text = "Izlaz iz aplikacije",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            },
            text = {
                Text(
                    text = "Da li ste sigurni da želite izaći iz aplikacije Media Studio?",
                    color = Color.LightGray,
                    fontSize = 16.sp
                )
            },
            confirmButton = {
                DialogFocusableButton(
                    text = "Da, izađi",
                    isPrimary = true,
                    onClick = { (context as? Activity)?.finish() }
                )
            },
            dismissButton = {
                DialogFocusableButton(
                    text = "Odustani",
                    isPrimary = false,
                    onClick = { showExitDialog = false }
                )
            }
        )
    }
}

@Composable
private fun HeroButton(
    text: String,
    icon: ImageVector,
    isPrimary: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val bgColor = if (isFocused) Color.White else if (isPrimary) Color.White else Color(0xFF2A2A2A).copy(alpha = 0.8f)
    val contentColor = if (isFocused) Color.Black else if (isPrimary) Color.Black else Color.White
    val borderColor = if (isFocused) Color.Red else Color.Transparent

    Row(
        modifier = Modifier
            .height(44.dp)
            .background(color = bgColor, shape = RoundedCornerShape(22.dp))
            .border(width = if (isFocused) 3.dp else 0.dp, color = borderColor, shape = RoundedCornerShape(22.dp))
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            color = contentColor,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
    }
}

@Composable
private fun DialogFocusableButton(
    text: String,
    isPrimary: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .background(
                color = when {
                    isFocused -> Color.Red
                    isPrimary -> Color(0xFF990000)
                    else -> Color(0xFF2B2B2B)
                },
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = if (isFocused) 2.5.dp else 1.dp,
                color = if (isFocused) Color.White else Color.DarkGray,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 24.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
    }
}

@Composable
private fun HomeTvHighlightIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
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

@Composable
private fun DrawerFocusableItem(
    title: String,
    isAdult: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(
                color = if (isFocused) Color(0xFF2B2B2B) else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = if (isFocused) 2.5.dp else 0.dp,
                color = if (isFocused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = title,
            color = if (isAdult) Color(0xFFFF6B6B) else Color.White,
            fontSize = 15.sp,
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun SearchTriggerBar(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Row(
        modifier = modifier
            .height(44.dp)
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
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = "Pretraži",
            tint = if (isFocused) Color.White else Color.Gray,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "Pretraži filmove i serije...",
            color = if (isFocused) Color.White else Color.Gray,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TabButton(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .background(
                color = if (isSelected) Color.Red else if (isFocused) Color(0xFF2B2B2B) else Color(0xFF1A1A1A),
                shape = RoundedCornerShape(10.dp)
            )
            .border(
                width = if (isFocused) 3.dp else 1.dp,
                color = if (isFocused) Color.White else if (isSelected) Color.Red else Color.DarkGray,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 6.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun MovieCategoryRow(
    genre: GenreOption,
    movies: List<ArchiveMovie>,
    isNetflixTop10: Boolean = false,
    isSeries: Boolean,
    viewModel: HomeViewModel,
    authorImages: Map<String, String>,
    onMovieClick: (ArchiveMovie) -> Unit,
    onHeaderClick: () -> Unit
) {
    if (movies.isEmpty()) return
    val displayTitle = if (isNetflixTop10) "🔥 ${genre.title}" else genre.title
    val headerInteraction = remember { MutableInteractionSource() }
    val isHeaderFocused by headerInteraction.collectIsFocusedAsState()

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .background(
                    color = if (isHeaderFocused) Color(0xFF222222) else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                )
                .border(
                    width = if (isHeaderFocused) 2.5.dp else 0.dp,
                    color = if (isHeaderFocused) Color.White else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                )
                .clickable(interactionSource = headerInteraction, indication = null) { onHeaderClick() }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = displayTitle,
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Svi",
                    color = if (isHeaderFocused) Color.White else Color.Gray,
                    fontSize = 13.sp,
                    fontWeight = if (isHeaderFocused) FontWeight.Bold else FontWeight.Normal
                )
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Otvori žanr",
                    tint = if (isHeaderFocused) Color.White else Color.Gray,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isNetflixTop10) 16.dp else 12.dp)
        ) {
            itemsIndexed(movies) { index, movie ->
                if (isNetflixTop10) {
                    NetflixTop10Card(movie = movie, rankNumber = index + 1, isSeries = isSeries, onMovieClick = onMovieClick)
                } else {
                    MovieCard(movie = movie, isSeries = isSeries, viewModel = viewModel, authorImages = authorImages, onMovieClick = onMovieClick)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetflixTop10Card(movie: ArchiveMovie, rankNumber: Int, isSeries: Boolean, onMovieClick: (ArchiveMovie) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.08f else 1f, label = "top10_scale")
    val borderColor = if (isFocused) Color.White else Color.Gray.copy(alpha = 0.65f)
    val borderWidth = if (isFocused) 3.5.dp else 1.5.dp

    val firstArchiveId = movie.identifier.split(",").first()
    var posterUrl by remember { mutableStateOf("") }
    val fallbackUrl = "https://archive.org/services/img/$firstArchiveId"

    LaunchedEffect(movie.title) {
        val fetchedUrl = PosterFetcher.fetchPoster(movie.title, isSeries, firstArchiveId)
        posterUrl = fetchedUrl.ifBlank { fallbackUrl }
    }

    Column(modifier = Modifier.width(162.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(162.dp)
                .height(175.dp)
                .scale(scale)
        ) {
            Card(
                modifier = Modifier
                    .width(115.dp)
                    .height(170.dp)
                    .align(Alignment.CenterEnd)
                    .border(borderWidth, borderColor, RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                interactionSource = interactionSource,
                onClick = { onMovieClick(movie) }
            ) {
                val painter = rememberAsyncImagePainter(
                    model = posterUrl.ifBlank { fallbackUrl }
                )

                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (painter.state is AsyncImagePainter.State.Loading) {
                        CircularProgressIndicator(color = Color.Gray, modifier = Modifier.size(24.dp))
                    }
                    Image(
                        painter = painter,
                        contentDescription = movie.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    if (painter.state is AsyncImagePainter.State.Error) {
                        Image(
                            painter = rememberAsyncImagePainter(fallbackUrl),
                            contentDescription = movie.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }

            val numberFontSize = if (rankNumber == 10) 86.sp else 96.sp
            val numberLetterSpacing = if (rankNumber == 10) (-7).sp else 0.sp

            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = 0.dp, y = 8.dp)
            ) {
                Text(
                    text = "$rankNumber",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = numberFontSize,
                        fontWeight = FontWeight.Black,
                        letterSpacing = numberLetterSpacing,
                        drawStyle = Stroke(
                            miter = 10f,
                            width = 8f,
                            join = StrokeJoin.Round
                        )
                    )
                )
                Text(
                    text = "$rankNumber",
                    style = TextStyle(
                        color = Color(0xFFE50914),
                        fontSize = numberFontSize,
                        fontWeight = FontWeight.Black,
                        letterSpacing = numberLetterSpacing
                    )
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = movie.title,
            color = if (isFocused) Color.White else Color.LightGray,
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(162.dp).padding(start = 28.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieCard(movie: ArchiveMovie, isSeries: Boolean, viewModel: HomeViewModel, authorImages: Map<String, String>, onMovieClick: (ArchiveMovie) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.08f else 1f, label = "card_scale")
    val borderColor = if (isFocused) Color.White else Color.Gray.copy(alpha = 0.65f)
    val borderWidth = if (isFocused) 3.5.dp else 1.5.dp

    val isAuthor = movie.identifier.startsWith("AUTHOR|")
    var posterUrl by remember { mutableStateOf("") }

    LaunchedEffect(movie.title) {
        if (isAuthor) {
            val authorName = movie.title
            val archiveId = viewModel.getAuthorArchiveId(authorName)
            val mappedImage = viewModel.staticAuthorImages[authorName] ?: ""
            posterUrl = mappedImage.ifEmpty { "https://archive.org/services/img/$archiveId" }
        } else {
            val firstArchiveId = movie.identifier.split(",").first()
            val fallbackUrl = "https://archive.org/services/img/$firstArchiveId"
            val fetchedUrl = PosterFetcher.fetchPoster(movie.title, isSeries, firstArchiveId)
            posterUrl = fetchedUrl.ifBlank { fallbackUrl }
        }
    }

    Column(modifier = Modifier.width(120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Card(
            modifier = Modifier
                .width(120.dp)
                .height(175.dp)
                .scale(scale)
                .border(borderWidth, borderColor, RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            interactionSource = interactionSource,
            onClick = { onMovieClick(movie) }
        ) {
            if (posterUrl.isNotEmpty()) {
                val painter = rememberAsyncImagePainter(model = posterUrl)
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (painter.state is AsyncImagePainter.State.Loading) {
                        CircularProgressIndicator(color = Color.Gray, modifier = Modifier.size(24.dp))
                    }
                    Image(
                        painter = painter,
                        contentDescription = movie.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    if (painter.state is AsyncImagePainter.State.Error) {
                        val fallbackId = if (isAuthor) viewModel.getAuthorArchiveId(movie.title) else movie.identifier.split(",").first()
                        val fallback = "https://archive.org/services/img/$fallbackId"
                        Image(
                            painter = rememberAsyncImagePainter(fallback),
                            contentDescription = movie.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray))
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = movie.title,
            color = if (isFocused) Color.White else Color.LightGray,
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(120.dp)
        )
    }
}
