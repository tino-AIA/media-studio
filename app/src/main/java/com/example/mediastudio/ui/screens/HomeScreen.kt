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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
    val trendingMovies by viewModel.trendingMovies.collectAsState()
    val sciFiMovies by viewModel.sciFiMovies.collectAsState()
    val actionMovies by viewModel.actionMovies.collectAsState()
    val thrillerMovies by viewModel.thrillerMovies.collectAsState()
    val comedyMovies by viewModel.comedyMovies.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState() // Za skrolanje do žanrova
    var searchQuery by remember { mutableStateOf("") }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(250.dp),
                drawerContainerColor = Color(0xFF121212)
            ) {
                Spacer(modifier = Modifier.height(24.dp))
                Text("Žanrovi", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
                Divider(color = Color.DarkGray)
                
                val genres = listOf("Najgledanije" to 0, "Sci-Fi" to 2, "Akcija" to 3, "Triler" to 4, "Komedija" to 5)
                genres.forEach { (genre, index) ->
                    NavigationDrawerItem(
                        label = { Text(genre, color = Color.White) },
                        selected = false,
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                listState.animateScrollToItem(index) // Skrola do odabranog reda
                            }
                        },
                        colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent)
                    )
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
                    // Tražilica ispod TopAppBara
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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
                    modifier = Modifier.fillMaxSize().padding(paddingValues)
                ) {
                    if (trendingMovies.isNotEmpty()) {
                        item { HeroMovieSection(movie = trendingMovies.first(), onMovieClick = onMovieClick) }
                        item { MovieCategoryRow("Najgledanije", trendingMovies.drop(1).filter { it.title.contains(searchQuery, true) }, onMovieClick) }
                    }
                    if (sciFiMovies.isNotEmpty()) {
                        item { MovieCategoryRow("Sci-Fi klasici", sciFiMovies.filter { it.title.contains(searchQuery, true) }, onMovieClick) }
                    }
                    if (actionMovies.isNotEmpty()) {
                        item { MovieCategoryRow("Akcija", actionMovies.filter { it.title.contains(searchQuery, true) }, onMovieClick) }
                    }
                    if (thrillerMovies.isNotEmpty()) {
                        item { MovieCategoryRow("Triler", thrillerMovies.filter { it.title.contains(searchQuery, true) }, onMovieClick) }
                    }
                    if (comedyMovies.isNotEmpty()) {
                        item { MovieCategoryRow("Komedija", comedyMovies.filter { it.title.contains(searchQuery, true) }, onMovieClick) }
                    }
                    item { Spacer(modifier = Modifier.height(30.dp)) }
                }
            }
        }
    }
}

@Composable
fun HeroMovieSection(movie: ArchiveMovie, onMovieClick: (ArchiveMovie) -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().height(400.dp)) {
        Image(
            painter = rememberAsyncImagePainter("https://archive.org/services/img/${movie.identifier}"),
            contentDescription = movie.title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(colors = listOf(Color.Transparent, Color.Black), startY = 200f)))
        Column(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(movie.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            
            val interactionSource = remember { MutableInteractionSource() }
            val isFocused by interactionSource.collectIsFocusedAsState()
            val scale by animateFloatAsState(if (isFocused) 1.1f else 1f, label = "button_scale")

            Button(
                onClick = { onMovieClick(movie) }, 
                interactionSource = interactionSource,
                modifier = Modifier.scale(scale),
                colors = ButtonDefaults.buttonColors(containerColor = if (isFocused) Color.LightGray else Color.White)
            ) {
                Text("ℹ Detalji filma", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun MovieCategoryRow(categoryTitle: String, movies: List<ArchiveMovie>, onMovieClick: (ArchiveMovie) -> Unit) {
    if (movies.isEmpty()) return // Sakrij red ako tražilica nema rezultata u ovom žanru
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(categoryTitle, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(movies) { movie ->
                MovieCard(movie = movie, onMovieClick = onMovieClick)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieCard(movie: ArchiveMovie, onMovieClick: (ArchiveMovie) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (isFocused) 1.1f else 1f, label = "card_scale")
    val borderColor = if (isFocused) Color.White else Color.Transparent

    Column(modifier = Modifier.width(115.dp)) {
        Card(
            modifier = Modifier.width(115.dp).height(170.dp).scale(scale).border(3.dp, borderColor, RoundedCornerShape(12.dp)),
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
        Text(movie.title, color = if (isFocused) Color.White else Color.LightGray, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
