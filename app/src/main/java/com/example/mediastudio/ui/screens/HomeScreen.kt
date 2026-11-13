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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.example.mediastudio.data.model.ArchiveMovie
import com.example.mediastudio.ui.viewmodel.ContentTab
import com.example.mediastudio.ui.viewmodel.GenreOption
import com.example.mediastudio.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onMovieClick: (ArchiveMovie) -> Unit,
    onSeriesClick: (ArchiveMovie) -> Unit,
    onGenreSelect: (GenreOption) -> Unit
) {
    val selectedTab by viewModel.selectedTab.collectAsState()
    val movieCategories by viewModel.movieCategories.collectAsState()
    val seriesCategories by viewModel.seriesCategories.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val globalSearchResults by viewModel.globalSearchResults.collectAsState()
    val isSearchingGlobal by viewModel.isSearchingGlobal.collectAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }

    BackHandler(enabled = drawerState.isOpen || searchQuery.isNotEmpty()) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else if (searchQuery.isNotEmpty()) {
            searchQuery = ""
            viewModel.performGlobalSearch("")
        }
    }

    val activeCategories = if (selectedTab == ContentTab.MOVIES) movieCategories else seriesCategories
    val activeDrawerList = if (selectedTab == ContentTab.MOVIES) viewModel.movieGenreDefinitions else viewModel.seriesGenreDefinitions

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(260.dp),
                drawerContainerColor = Color(0xFF121212)
            ) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = if (selectedTab == ContentTab.MOVIES) "Žanrovi filmova" else "Kategorije serija",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(16.dp)
                    )
                    Divider(color = Color.DarkGray)

                    activeDrawerList.forEach { genre ->
                        NavigationDrawerItem(
                            label = {
                                Text(
                                    text = genre.title,
                                    color = if (genre.title.startsWith("18+")) Color(0xFFFF6B6B) else Color.White,
                                    fontSize = 15.sp
                                )
                            },
                            selected = false,
                            onClick = {
                                scope.launch {
                                    drawerState.close()
                                    onGenreSelect(genre)
                                }
                            },
                            colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent)
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text("Media Studio", color = Color.White, fontWeight = FontWeight.Bold) },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                    )

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            viewModel.performGlobalSearch(it)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        placeholder = { Text("Pretraži cijeli Archive.org (npr. Alpha, Chaplin...)", color = Color.Gray) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.Gray) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = {
                                    searchQuery = ""
                                    viewModel.performGlobalSearch("")
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Očisti", tint = Color.Gray)
                                }
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.Red,
                            unfocusedBorderColor = Color.DarkGray
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TabButton(
                            title = "🎬 Filmovi",
                            isSelected = selectedTab == ContentTab.MOVIES,
                            onClick = {
                                searchQuery = ""
                                viewModel.performGlobalSearch("")
                                viewModel.selectTab(ContentTab.MOVIES)
                            },
                            modifier = Modifier.weight(1f)
                        )
                        TabButton(
                            title = "📺 Serije i kolekcije",
                            isSelected = selectedTab == ContentTab.SERIES,
                            onClick = {
                                searchQuery = ""
                                viewModel.performGlobalSearch("")
                                viewModel.selectTab(ContentTab.SERIES)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            containerColor = Color.Black
        ) { paddingValues ->
            if (searchQuery.trim().length >= 3) {
                if (isSearchingGlobal) {
                    Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.Red)
                    }
                } else if (globalSearchResults.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                        Text("Nema rezultata za \"$searchQuery\"", color = Color.Gray)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 120.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize().padding(paddingValues)
                    ) {
                        items(globalSearchResults) { ratedItem ->
                            RatedMovieGridCard(
                                ratedMovie = ratedItem,
                                onMovieClick = { item ->
                                    if (selectedTab == ContentTab.SERIES) onSeriesClick(item) else onMovieClick(item)
                                }
                            )
                        }
                    }
                }
            } else if (isLoading) {
                Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.Red)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    items(activeCategories) { (genre, itemsList) ->
                        val isTop10 = genre.title.startsWith("Top 10")
                        MovieCategoryRow(
                            genre = genre,
                            movies = itemsList,
                            isNetflixTop10 = isTop10,
                            onMovieClick = { item ->
                                if (selectedTab == ContentTab.SERIES) onSeriesClick(item) else onMovieClick(item)
                            },
                            onHeaderClick = { onGenreSelect(genre) }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(30.dp)) }
                }
            }
        }
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
                color = if (isSelected) Color.Red else Color(0xFF1A1A1A),
                shape = RoundedCornerShape(10.dp)
            )
            .border(
                width = if (isFocused) 2.5.dp else 1.dp,
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
                .border(
                    width = if (isHeaderFocused) 2.dp else 0.dp,
                    color = if (isHeaderFocused) Color.White else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                )
                .clickable(interactionSource = headerInteraction, indication = null) { onHeaderClick() }
                .padding(horizontal = 6.dp, vertical = 6.dp),
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
                    fontSize = 13.sp
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
                    NetflixTop10Card(movie = movie, rankNumber = index + 1, onMovieClick = onMovieClick)
                } else {
                    MovieCard(movie = movie, onMovieClick = onMovieClick)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetflixTop10Card(movie: ArchiveMovie, rankNumber: Int, onMovieClick: (ArchiveMovie) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.08f else 1f, label = "top10_scale")
    val borderColor = if (isFocused) Color.White else Color.Gray.copy(alpha = 0.65f)
    val borderWidth = if (isFocused) 3.dp else 1.5.dp

    Column(modifier = Modifier.width(162.dp)) {
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
                Image(
                    painter = rememberAsyncImagePainter("https://archive.org/services/img/${movie.identifier}"),
                    contentDescription = movie.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
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
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 44.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieCard(movie: ArchiveMovie, onMovieClick: (ArchiveMovie) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.08f else 1f, label = "card_scale")
    val borderColor = if (isFocused) Color.White else Color.Gray.copy(alpha = 0.65f)
    val borderWidth = if (isFocused) 3.dp else 1.5.dp

    Column(modifier = Modifier.width(120.dp)) {
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
            Image(
                painter = rememberAsyncImagePainter("https://archive.org/services/img/${movie.identifier}"),
                contentDescription = movie.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = movie.title,
            color = if (isFocused) Color.White else Color.LightGray,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
