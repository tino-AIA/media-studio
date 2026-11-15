package com.example.mediastudio.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Menu
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
    val seriesCategories by viewModel.seriesCategories.collectAsState()
    val favoriteMovies by viewModel.favoriteMovies.collectAsState()
    val heroMovies by viewModel.heroMovies.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    
    var showExitDialog by remember { mutableStateOf(false) }

    // Stanje za pametnu HBO Max pozadinu (mijenja se ovisno o fokusu)
    var currentHeroMovie by remember { mutableStateOf<Pair<ArchiveMovie, Boolean>?>(null) }
    var currentHeroPosterUrl by remember { mutableStateOf("") }

    // Postavi prvi film iz Istaknutog kao početnu pozadinu kad se sve učita
    LaunchedEffect(heroMovies) {
        if (currentHeroMovie == null && heroMovies.isNotEmpty()) {
            currentHeroMovie = heroMovies.first() to (selectedTab == ContentTab.SERIES)
        }
    }

    LaunchedEffect(currentHeroMovie) {
        currentHeroMovie?.let { (movie, isSeries) ->
            val fetchedUrl = PosterFetcher.fetchPoster(movie.title, isSeries, movie.identifier.split(",").first())
            currentHeroPosterUrl = fetchedUrl.ifBlank { "https://archive.org/services/img/${movie.identifier.split(",").first()}" }
        }
    }

    BackHandler {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else {
            showExitDialog = true
        }
    }

    val activeCategories = if (selectedTab == ContentTab.MOVIES) movieCategories else seriesCategories
    val activeDrawerList = if (selectedTab == ContentTab.MOVIES) viewModel.movieGenreDefinitions else viewModel.seriesGenreDefinitions
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
                        text = if (isSeriesTab) "Kategorije serija" else "Žanrovi filmova",
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
        // GLOBALNA HBO MAX POZADINA S GRADIENTIMA
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (currentHeroPosterUrl.isNotEmpty()) {
                Image(
                    painter = rememberAsyncImagePainter(currentHeroPosterUrl),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(0.35f) // Polu-prozirno za kino efekt
                )
            }
            
            // Tamni gradijent lijevo (kako bi se vidio tekst)
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
            // Tamni gradijent dolje (za prijelaz prema redovima)
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

            // GLAVNI SADRŽAJ (Proziran Scaffold kako bi pozadina "disala")
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
                            TvHighlightIconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                contentDescription = "Izbornik žanrova"
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
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            TabButton(
                                title = "🎬 Filmovi",
                                isSelected = selectedTab == ContentTab.MOVIES,
                                onClick = { viewModel.selectTab(ContentTab.MOVIES) },
                                modifier = Modifier.weight(1f)
                            )
                            TabButton(
                                title = "📺 Serije i kolekcije",
                                isSelected = selectedTab == ContentTab.SERIES,
                                onClick = { viewModel.selectTab(ContentTab.SERIES) },
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
                containerColor = Color.Transparent // OBAVEZNO prozirno!
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
                                val isProbablySeries = movie.title.lowercase().contains("season") || movie.title.lowercase().contains("episode")
                                MovieCard(
                                    movie = movie,
                                    isSeries = isProbablySeries,
                                    onMovieClick = { item -> if (isProbablySeries) onSeriesClick(item) else onMovieClick(item) },
                                    onFocus = { focusedMov, isSer -> currentHeroMovie = focusedMov to isSer }
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
                        // 1. HERO SECTON (Ispisuje naslov filma u pozadini velikim slovima)
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(300.dp)
                                    .padding(start = 24.dp, bottom = 12.dp),
                                verticalArrangement = Arrangement.Bottom
                            ) {
                                currentHeroMovie?.let { hero ->
                                    Text(
                                        text = hero.first.title,
                                        color = Color.White,
                                        fontSize = 42.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.width(650.dp)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (hero.second) "SERIJA" else "FILM",
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
                                }
                            }
                        }

                        // 2. RED "ISTAKNUTO" S HERO FILMOVIMA
                        if (heroMovies.isNotEmpty()) {
                            item {
                                MovieCategoryRow(
                                    genre = GenreOption("🌟 Istaknuto (Favorites & Top)", ""),
                                    movies = heroMovies.map { it.first },
                                    isNetflixTop10 = false,
                                    isSeries = isSeriesTab,
                                    onMovieClick = { item -> if (isSeriesTab) onSeriesClick(item) else onMovieClick(item) },
                                    onHeaderClick = { }, // Ne radi ništa na klik jer je to mix žanrova
                                    onFocus = { focusedMov, isSer -> currentHeroMovie = focusedMov to isSer }
                                )
                            }
                        }

                        // 3. OSTALI REDOVI (Žanrovi)
                        items(activeCategories) { (genre, itemsList) ->
                            val isTop10 = genre.title.startsWith("Top 10")
                            MovieCategoryRow(
                                genre = genre,
                                movies = itemsList,
                                isNetflixTop10 = isTop10,
                                isSeries = isSeriesTab,
                                onMovieClick = { item ->
                                    if (isSeriesTab) onSeriesClick(item) else onMovieClick(item)
                                },
                                onHeaderClick = { onGenreSelect(genre) },
                                onFocus = { focusedMov, isSer -> currentHeroMovie = focusedMov to isSer }
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
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
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
    onMovieClick: (ArchiveMovie) -> Unit,
    onHeaderClick: () -> Unit,
    onFocus: (ArchiveMovie, Boolean) -> Unit
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
            if (genre.title != "🌟 Istaknuto (Favorites & Top)") {
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
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isNetflixTop10) 16.dp else 12.dp)
        ) {
            itemsIndexed(movies) { index, movie ->
                if (isNetflixTop10) {
                    NetflixTop10Card(movie = movie, rankNumber = index + 1, isSeries = isSeries, onMovieClick = onMovieClick, onFocus = onFocus)
                } else {
                    MovieCard(movie = movie, isSeries = isSeries, onMovieClick = onMovieClick, onFocus = onFocus)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetflixTop10Card(movie: ArchiveMovie, rankNumber: Int, isSeries: Boolean, onMovieClick: (ArchiveMovie) -> Unit, onFocus: (ArchiveMovie, Boolean) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.08f else 1f, label = "top10_scale")
    val borderColor = if (isFocused) Color.White else Color.Gray.copy(alpha = 0.65f)
    val borderWidth = if (isFocused) 3.5.dp else 1.5.dp
    
    val firstArchiveId = movie.identifier.split(",").first()
    var posterUrl by remember { mutableStateOf("") }
    val fallbackUrl = "https://archive.org/services/img/$firstArchiveId"

    LaunchedEffect(isFocused) {
        if (isFocused) onFocus(movie, isSeries)
    }

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
fun MovieCard(movie: ArchiveMovie, isSeries: Boolean, onMovieClick: (ArchiveMovie) -> Unit, onFocus: (ArchiveMovie, Boolean) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.08f else 1f, label = "card_scale")
    val borderColor = if (isFocused) Color.White else Color.Gray.copy(alpha = 0.65f)
    val borderWidth = if (isFocused) 3.5.dp else 1.5.dp
    
    val firstArchiveId = movie.identifier.split(",").first()
    var posterUrl by remember { mutableStateOf("") }
    val fallbackUrl = "https://archive.org/services/img/$firstArchiveId"

    LaunchedEffect(isFocused) {
        if (isFocused) onFocus(movie, isSeries)
    }

    LaunchedEffect(movie.title) {
        val fetchedUrl = PosterFetcher.fetchPoster(movie.title, isSeries, firstArchiveId)
        posterUrl = fetchedUrl.ifBlank { fallbackUrl }
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
