package com.example.mediastudio.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.example.mediastudio.data.model.ArchiveMovie
import com.example.mediastudio.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel, onMovieClick: (ArchiveMovie) -> Unit) {
    val topMonthlyMovies by viewModel.topMonthlyMovies.collectAsState()
    val trendingMovies by viewModel.trendingMovies.collectAsState()
    val sciFiMovies by viewModel.sciFiMovies.collectAsState()
    val actionMovies by viewModel.actionMovies.collectAsState()
    val thrillerMovies by viewModel.thrillerMovies.collectAsState()
    val comedyMovies by viewModel.comedyMovies.collectAsState()
    val horrorMovies by viewModel.horrorMovies.collectAsState()
    val dramaMovies by viewModel.dramaMovies.collectAsState()
    val westernMovies by viewModel.westernMovies.collectAsState()
    val documentaryMovies by viewModel.documentaryMovies.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var searchQuery by remember { mutableStateOf("") }

    val categories = remember(
        topMonthlyMovies, trendingMovies, sciFiMovies, actionMovies,
        thrillerMovies, comedyMovies, horrorMovies, dramaMovies, westernMovies, documentaryMovies, searchQuery
    ) {
        listOf(
            "Top 10 ovog mjeseca" to topMonthlyMovies.take(10).filter { it.title.contains(searchQuery, true) },
            "Najgledanije" to trendingMovies.filter { it.title.contains(searchQuery, true) },
            "Sci-Fi klasici" to sciFiMovies.filter { it.title.contains(searchQuery, true) },
            "Akcija" to actionMovies.filter { it.title.contains(searchQuery, true) },
            "Triler" to thrillerMovies.filter { it.title.contains(searchQuery, true) },
            "Komedija" to comedyMovies.filter { it.title.contains(searchQuery, true) },
            "Horor" to horrorMovies.filter { it.title.contains(searchQuery, true) },
            "Drama" to dramaMovies.filter { it.title.contains(searchQuery, true) },
            "Vestern" to westernMovies.filter { it.title.contains(searchQuery, true) },
            "Dokumentarni" to documentaryMovies.filter { it.title.contains(searchQuery, true) }
        ).filter { it.second.isNotEmpty() }
    }

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
                        text = "Žanrovi",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(16.dp)
                    )
                    Divider(color = Color.DarkGray)

                    categories.forEachIndexed { index, (title, _) ->
                        NavigationDrawerItem(
                            label = { Text(title, color = Color.White, fontSize = 16.sp) },
                            selected = false,
                            onClick = {
                                scope.launch {
                                    drawerState.close()
                                    listState.animateScrollToItem(index)
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
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        placeholder = { Text("Pretraži filmove...", color = Color.Gray) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color.Red,
                            unfocusedBorderColor = Color.DarkGray
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                    )
                }
            },
            containerColor = Color.Black
        ) { paddingValues ->
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.Red)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    itemsIndexed(categories) { _, (title, movies) ->
                        val isTop10 = (title == "Top 10 ovog mjeseca")
                        MovieCategoryRow(
                            categoryTitle = if (isTop10) "🔥 $title" else title,
                            movies = movies,
                            isNetflixTop10 = isTop10,
                            onMovieClick = onMovieClick
                        )
                    }
                    item { Spacer(modifier = Modifier.height(30.dp)) }
                }
            }
        }
    }
}

@Composable
fun MovieCategoryRow(
    categoryTitle: String,
    movies: List<ArchiveMovie>,
    isNetflixTop10: Boolean = false,
    onMovieClick: (ArchiveMovie) -> Unit
) {
    if (movies.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(
            text = categoryTitle,
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
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

// Posebna Netflix-stil kartica s velikim brojem (1-10) uz lijevi rub postera
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetflixTop10Card(movie: ArchiveMovie, rankNumber: Int, onMovieClick: (ArchiveMovie) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.08f else 1f, label = "top10_scale")
    val borderColor = if (isFocused) Color.White else Color.Transparent

    Column(modifier = Modifier.width(155.dp)) {
        Box(
            modifier = Modifier
                .width(155.dp)
                .height(175.dp)
                .scale(scale)
        ) {
            // Veliki Netflix broj u pozadini lijevo
            Text(
                text = "$rankNumber",
                color = Color(0xFF2B2B2B),
                fontSize = 115.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = (-4).dp, y = 12.dp)
            )

            // Crveni obrub broja za bolju čitljivost
            Text(
                text = "$rankNumber",
                color = Color.Red.copy(alpha = 0.85f),
                fontSize = 110.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = (-2).dp, y = 10.dp)
            )

            // Poster filma pomaknut udesno preko broja
            Card(
                modifier = Modifier
                    .width(115.dp)
                    .height(170.dp)
                    .align(Alignment.CenterEnd)
                    .border(3.dp, borderColor, RoundedCornerShape(12.dp)),
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
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = movie.title,
            color = if (isFocused) Color.White else Color.LightGray,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 38.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieCard(movie: ArchiveMovie, onMovieClick: (ArchiveMovie) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.08f else 1f, label = "card_scale")
    val borderColor = if (isFocused) Color.White else Color.Transparent

    Column(modifier = Modifier.width(120.dp)) {
        Card(
            modifier = Modifier
                .width(120.dp)
                .height(175.dp)
                .scale(scale)
                .border(3.dp, borderColor, RoundedCornerShape(12.dp)),
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
