package com.example.mediastudio.ui.screens

import android.app.Activity
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
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
import com.example.mediastudio.ui.viewmodel.ContentTab
import com.example.mediastudio.ui.viewmodel.GenreOption
import com.example.mediastudio.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onMovieClick: (ArchiveMovie) -> Unit,
    onSeriesClick: (ArchiveMovie) -> Unit,
    onGenreSelect: (GenreOption) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit
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
    val lastFocusedMovieId by viewModel.lastFocusedMovieId.collectAsState()
    val lastFocusedRowTitle by viewModel.lastFocusedRowTitle.collectAsState()

    val authorImages = viewModel.staticAuthorImages

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val menuFocusRequester = remember { FocusRequester() }
    val searchFocusRequester = remember { FocusRequester() }
    val drawerFirstItemFocusRequester = remember { FocusRequester() }

    val moviesTabFR = remember { FocusRequester() }
    val animTabFR = remember { FocusRequester() }
    val seriesTabFR = remember { FocusRequester() }
    val concertsTabFR = remember { FocusRequester() }
    val favoritesTabFR = remember { FocusRequester() }
    val heroPlayFocusRequester = remember { FocusRequester() }
    val heroDetailsFocusRequester = remember { FocusRequester() }
    val firstRowFocusRequester = remember { FocusRequester() }
    val firstFavoriteFocusRequester = remember { FocusRequester() }

    val activeTabFR = when (selectedTab) {
        ContentTab.MOVIES -> moviesTabFR
        ContentTab.ANIMATION -> animTabFR
        ContentTab.SERIES -> seriesTabFR
        ContentTab.CONCERTS -> concertsTabFR
        ContentTab.FAVORITES -> favoritesTabFR
    }

    var showExitDialog by remember { mutableStateOf(false) }
    var hasRequestedInitialFocus by remember { mutableStateOf(false) }
    var isAnyElementFocused by remember { mutableStateOf(false) }

    val currentHeroMovie = heroMovies.getOrNull(activeHeroIndex)
    var currentHeroPosterUrl by remember { mutableStateOf("") }

    LaunchedEffect(currentHeroMovie) {
        currentHeroMovie?.let { (movie, isSeries) ->
            val firstId = movie.identifier.split(",").first()
            val sbItem = SupabaseRepository.getCatalogItem(movie.identifier)
            val sbBackdrop = sbItem?.backdropUrl.orEmpty()
            val sbPoster = sbItem?.posterUrl.orEmpty()
            currentHeroPosterUrl = sbBackdrop.ifBlank {
                sbPoster.ifBlank {
                    PosterFetcher.fetchPoster(movie.title, isSeries, movie.identifier)
                        .ifBlank { "https://archive.org/services/img/$firstId" }
                }
            }
        }
    }

    val isSeriesTab = selectedTab == ContentTab.SERIES
    val isDrawerOpen = drawerState.isOpen

    LaunchedEffect(isLoading) {
        if (!isLoading && !isDrawerOpen && !hasRequestedInitialFocus && lastFocusedMovieId.isNullOrBlank()) {
            delay(100L)
            if (!isAnyElementFocused) {
                try {
                    if (currentHeroMovie != null && selectedTab != ContentTab.FAVORITES) {
                        heroPlayFocusRequester.requestFocus()
                    } else {
                        activeTabFR.requestFocus()
                    }
                } catch (_: Exception) {
                    try {
                        activeTabFR.requestFocus()
                    } catch (_: Exception) {
                    }
                }
            }
            hasRequestedInitialFocus = true
        }
    }

    LaunchedEffect(isDrawerOpen) {
        if (isDrawerOpen) {
            delay(80L)
            try {
                drawerFirstItemFocusRequester.requestFocus()
            } catch (_: Exception) {
            }
        }
    }

    BackHandler {
        if (isDrawerOpen) {
            scope.launch {
                drawerState.close()
                delay(50L)
                try {
                    menuFocusRequester.requestFocus()
                } catch (_: Exception) {
                }
            }
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

    val hasHeroVisible = currentHeroMovie != null && selectedTab != ContentTab.FAVORITES
    val hasRowsVisible = activeCategories.isNotEmpty() && selectedTab != ContentTab.FAVORITES

    val homeListState = rememberLazyListState(
        initialFirstVisibleItemIndex = viewModel.savedHomeScrollIndex,
        initialFirstVisibleItemScrollOffset = viewModel.savedHomeScrollOffset
    )
    val favGridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = viewModel.savedHomeScrollIndex,
        initialFirstVisibleItemScrollOffset = viewModel.savedHomeScrollOffset
    )

    val tabDownKeyModifier = Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
            when {
                selectedTab == ContentTab.FAVORITES && favoriteMovies.isNotEmpty() -> {
                    scope.launch {
                        if (favGridState.firstVisibleItemIndex > 0 || favGridState.firstVisibleItemScrollOffset > 0) {
                            favGridState.scrollToItem(0)
                            delay(40L)
                        }
                        try {
                            firstFavoriteFocusRequester.requestFocus()
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                hasHeroVisible -> {
                    scope.launch {
                        if (homeListState.firstVisibleItemIndex > 0 || homeListState.firstVisibleItemScrollOffset > 0) {
                            homeListState.scrollToItem(0)
                            delay(40L)
                        }
                        try {
                            heroPlayFocusRequester.requestFocus()
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                hasRowsVisible -> {
                    scope.launch {
                        try {
                            firstRowFocusRequester.requestFocus()
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                else -> false
            }
        } else {
            false
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = isDrawerOpen,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(240.dp),
                drawerContainerColor = Color(0xFF121212)
            ) {
                if (isDrawerOpen) {
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 6.dp)
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight) {
                                    scope.launch {
                                        drawerState.close()
                                        delay(50L)
                                        try {
                                            menuFocusRequester.requestFocus()
                                        } catch (_: Exception) {
                                        }
                                    }
                                    true
                                } else {
                                    false
                                }
                            }
                    ) {
                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = "Izbornik",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(10.dp)
                        )
                        Divider(color = Color.DarkGray)
                        Spacer(modifier = Modifier.height(6.dp))

                        DrawerFocusableItem(
                            title = "⭐ Moji Favoriti",
                            isAdult = false,
                            modifier = Modifier.focusRequester(drawerFirstItemFocusRequester),
                            onClick = {
                                scope.launch {
                                    drawerState.close()
                                    viewModel.selectTab(ContentTab.FAVORITES)
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = when (selectedTab) {
                                ContentTab.ANIMATION -> "Kategorije crtića"
                                ContentTab.SERIES -> "Kategorije serija"
                                ContentTab.CONCERTS -> "Kategorije glazbe"
                                else -> "Žanrovi filmova"
                            },
                            color = Color.Gray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 10.dp, bottom = 4.dp)
                        )

                        activeDrawerList
                            .filterNot { it.title.startsWith("18+") }
                            .forEach { genre ->
                                DrawerFocusableItem(
                                    title = genre.title,
                                    isAdult = false,
                                    onClick = {
                                        scope.launch {
                                            drawerState.close()
                                            onGenreSelect(genre)
                                        }
                                    }
                                )
                            }

                        Spacer(modifier = Modifier.height(10.dp))
                        Divider(color = Color.DarkGray)
                        Spacer(modifier = Modifier.height(6.dp))

                        DrawerFocusableItem(
                            title = "⚙ Postavke",
                            isAdult = false,
                            onClick = {
                                scope.launch {
                                    drawerState.close()
                                    onOpenSettings()
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0A))
                .onFocusChanged { isAnyElementFocused = it.hasFocus }
        ) {
            Scaffold(
                topBar = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0D0D0D))
                            .padding(top = 3.dp, bottom = 3.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 3.dp)
                                .focusGroup(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            HomeTvHighlightIconButton(
                                onClick = { scope.launch { drawerState.open() } },
                                modifier = Modifier
                                    .focusRequester(menuFocusRequester)
                                    .focusProperties {
                                        up = FocusRequester.Cancel
                                        left = FocusRequester.Cancel
                                        right = searchFocusRequester
                                        down = activeTabFR
                                    }
                            ) {
                                Icon(
                                    Icons.Default.Menu,
                                    contentDescription = "Menu",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Text(
                                text = "Media Studio",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            SearchTriggerBar(
                                onClick = onOpenSearch,
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(searchFocusRequester)
                                    .focusProperties {
                                        up = FocusRequester.Cancel
                                        left = menuFocusRequester
                                        right = FocusRequester.Cancel
                                        down = activeTabFR
                                    }
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 3.dp)
                                .focusGroup(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TabButton(
                                title = "🎬 Filmovi",
                                isSelected = selectedTab == ContentTab.MOVIES,
                                onClick = { viewModel.selectTab(ContentTab.MOVIES) },
                                modifier = Modifier
                                    .weight(1f)
                                    .then(tabDownKeyModifier)
                                    .focusRequester(moviesTabFR)
                                    .focusProperties {
                                        left = FocusRequester.Cancel
                                        right = animTabFR
                                        up = menuFocusRequester
                                    }
                            )
                            TabButton(
                                title = "🎨 Animirani",
                                isSelected = selectedTab == ContentTab.ANIMATION,
                                onClick = { viewModel.selectTab(ContentTab.ANIMATION) },
                                modifier = Modifier
                                    .weight(1f)
                                    .then(tabDownKeyModifier)
                                    .focusRequester(animTabFR)
                                    .focusProperties {
                                        left = moviesTabFR
                                        right = seriesTabFR
                                        up = menuFocusRequester
                                    }
                            )
                            TabButton(
                                title = "📺 Serije",
                                isSelected = selectedTab == ContentTab.SERIES,
                                onClick = { viewModel.selectTab(ContentTab.SERIES) },
                                modifier = Modifier
                                    .weight(1f)
                                    .then(tabDownKeyModifier)
                                    .focusRequester(seriesTabFR)
                                    .focusProperties {
                                        left = animTabFR
                                        right = concertsTabFR
                                        up = searchFocusRequester
                                    }
                            )
                            TabButton(
                                title = "🎵 Koncerti",
                                isSelected = selectedTab == ContentTab.CONCERTS,
                                onClick = { viewModel.selectTab(ContentTab.CONCERTS) },
                                modifier = Modifier
                                    .weight(1f)
                                    .then(tabDownKeyModifier)
                                    .focusRequester(concertsTabFR)
                                    .focusProperties {
                                        left = seriesTabFR
                                        right = favoritesTabFR
                                        up = searchFocusRequester
                                    }
                            )
                            TabButton(
                                title = "⭐ Favoriti",
                                isSelected = selectedTab == ContentTab.FAVORITES,
                                onClick = { viewModel.selectTab(ContentTab.FAVORITES) },
                                modifier = Modifier
                                    .weight(1f)
                                    .then(tabDownKeyModifier)
                                    .focusRequester(favoritesTabFR)
                                    .focusProperties {
                                        left = concertsTabFR
                                        right = FocusRequester.Cancel
                                        up = searchFocusRequester
                                    }
                            )
                        }
                    }
                },
                containerColor = Color.Transparent
            ) { paddingValues ->
                if (isLoading && selectedTab != ContentTab.FAVORITES) {
                    Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.Red, modifier = Modifier.size(36.dp))
                    }
                } else if (selectedTab == ContentTab.FAVORITES) {
                    if (favoriteMovies.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                            Text("Nemate spremljenih favorita.", color = Color.Gray, fontSize = 14.sp)
                        }
                    } else {
                        DisposableEffect(favGridState) {
                            onDispose {
                                viewModel.savedHomeScrollIndex = favGridState.firstVisibleItemIndex
                                viewModel.savedHomeScrollOffset = favGridState.firstVisibleItemScrollOffset
                                try {
                                    Coil.imageLoader(context).memoryCache?.clear()
                                } catch (_: Exception) {
                                }
                            }
                        }

                        LaunchedEffect(favoriteMovies, lastFocusedMovieId) {
                            val targetId = lastFocusedMovieId
                            if (favoriteMovies.isNotEmpty() && !targetId.isNullOrBlank()) {
                                val targetIdx = favoriteMovies.indexOfFirst { it.identifier == targetId }
                                if (targetIdx >= 0) {
                                    val isVisible = favGridState.layoutInfo.visibleItemsInfo.any { it.index == targetIdx }
                                    if (!isVisible) {
                                        favGridState.scrollToItem(targetIdx)
                                    }
                                }
                            }
                        }

                        LazyVerticalGrid(
                            state = favGridState,
                            columns = GridCells.Adaptive(minSize = 100.dp),
                            contentPadding = PaddingValues(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 52.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(paddingValues)
                                .clipToBounds()
                        ) {
                            itemsIndexed(
                                items = favoriteMovies,
                                key = { _, m -> m.identifier }
                            ) { index, movie ->
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
                                    externalFocusRequester = if (index == 0) firstFavoriteFocusRequester else null,
                                    shouldRestoreFocus = movie.identifier == lastFocusedMovieId,
                                    onFocusRestored = { viewModel.clearLastFocusedMovie() },
                                    onMovieClick = { item ->
                                        viewModel.savedHomeScrollIndex = favGridState.firstVisibleItemIndex
                                        viewModel.savedHomeScrollOffset = favGridState.firstVisibleItemScrollOffset
                                        viewModel.rememberSelectedItem(item, "FAVORITES")
                                        if (isProbablySeries) onSeriesClick(item) else onMovieClick(item)
                                    }
                                )
                            }
                        }
                    }
                } else {
                    DisposableEffect(homeListState) {
                        onDispose {
                            viewModel.savedHomeScrollIndex = homeListState.firstVisibleItemIndex
                            viewModel.savedHomeScrollOffset = homeListState.firstVisibleItemScrollOffset
                            try {
                                Coil.imageLoader(context).memoryCache?.clear()
                            } catch (_: Exception) {
                            }
                        }
                    }

                    LaunchedEffect(activeCategories, lastFocusedRowTitle) {
                        val targetRow = lastFocusedRowTitle
                        if (activeCategories.isNotEmpty() && !targetRow.isNullOrBlank()) {
                            val rowIdx = activeCategories.indexOfFirst { it.first.title == targetRow }
                            if (rowIdx >= 0) {
                                val lazyColIdx = rowIdx + 1
                                val isVisible = homeListState.layoutInfo.visibleItemsInfo.any { it.index == lazyColIdx }
                                if (!isVisible) {
                                    homeListState.scrollToItem(lazyColIdx)
                                }
                            }
                        }
                    }

                    LazyColumn(
                        state = homeListState,
                        contentPadding = PaddingValues(top = 0.dp, bottom = 52.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .clipToBounds()
                    ) {
                        item(key = "hero_banner") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(156.dp)
                                    .background(Color(0xFF0A0A0A))
                            ) {
                                // Naglašeni sadržaj prikazan samo u desnom dijelu hero ekrana uz fade-to-black prijelaz prema lijevo
                                if (currentHeroPosterUrl.isNotEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(0.58f)
                                            .align(Alignment.CenterEnd)
                                    ) {
                                        val heroBgRequest = remember(currentHeroPosterUrl) {
                                            ImageRequest.Builder(context)
                                                .data(currentHeroPosterUrl)
                                                .size(480, 270)
                                                .scale(Scale.FILL)
                                                .bitmapConfig(Bitmap.Config.RGB_565)
                                                .allowHardware(true)
                                                .build()
                                        }
                                        Image(
                                            painter = rememberAsyncImagePainter(heroBgRequest),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            alignment = Alignment.TopCenter,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .alpha(0.78f)
                                        )

                                        // Horizontalni fade-to-black prijelaz od sredine ekrana prema lijevo
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(
                                                    Brush.horizontalGradient(
                                                        0.0f to Color(0xFF0A0A0A),
                                                        0.38f to Color(0xFF0A0A0A).copy(alpha = 0.72f),
                                                        0.75f to Color(0xFF0A0A0A).copy(alpha = 0.18f),
                                                        1.0f to Color.Transparent
                                                    )
                                                )
                                        )

                                        // Blago stapanje donjeg i gornjeg ruba s crnom pozadinom
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(
                                                    Brush.verticalGradient(
                                                        0.0f to Color(0xFF0A0A0A).copy(alpha = 0.45f),
                                                        0.25f to Color.Transparent,
                                                        0.78f to Color.Transparent,
                                                        1.0f to Color(0xFF0A0A0A)
                                                    )
                                                )
                                        )
                                    }
                                }

                                currentHeroMovie?.let { (movie, isSeries) ->
                                    Column(
                                        modifier = Modifier
                                            .align(Alignment.CenterStart)
                                            .padding(start = 20.dp, end = 16.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = movie.title,
                                            color = Color.White,
                                            fontSize = 21.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            maxLines = 1,
                                            lineHeight = 24.sp,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.fillMaxWidth(0.56f)
                                        )

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = if (isSeries) "SERIJA" else "FILM",
                                                color = Color.LightGray,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier
                                                    .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                                                    .padding(horizontal = 5.dp, vertical = 1.5.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "Istaknuto na Media Studiju",
                                                color = Color.Gray,
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }

                                        Row(
                                            modifier = Modifier
                                                .padding(top = 2.dp)
                                                .focusGroup(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            HeroButton(
                                                text = "Gledaj odmah",
                                                icon = Icons.Default.PlayArrow,
                                                isPrimary = true,
                                                modifier = Modifier
                                                    .focusRequester(heroPlayFocusRequester)
                                                    .focusProperties {
                                                        left = FocusRequester.Cancel
                                                        right = heroDetailsFocusRequester
                                                        up = activeTabFR
                                                    }
                                                    .onFocusChanged { state ->
                                                        if (state.isFocused &&
                                                            (homeListState.firstVisibleItemIndex > 0 || homeListState.firstVisibleItemScrollOffset > 0)
                                                        ) {
                                                            scope.launch { homeListState.scrollToItem(0) }
                                                        }
                                                    },
                                                onClick = {
                                                    viewModel.clearLastFocusedMovie()
                                                    if (isSeries) onSeriesClick(movie) else onMovieClick(movie)
                                                }
                                            )
                                            HeroButton(
                                                text = "Detalji",
                                                icon = Icons.Default.Info,
                                                isPrimary = false,
                                                modifier = Modifier
                                                    .focusRequester(heroDetailsFocusRequester)
                                                    .focusProperties {
                                                        left = heroPlayFocusRequester
                                                        right = FocusRequester.Cancel
                                                        up = activeTabFR
                                                    }
                                                    .onFocusChanged { state ->
                                                        if (state.isFocused &&
                                                            (homeListState.firstVisibleItemIndex > 0 || homeListState.firstVisibleItemScrollOffset > 0)
                                                        ) {
                                                            scope.launch { homeListState.scrollToItem(0) }
                                                        }
                                                    },
                                                onClick = {
                                                    viewModel.clearLastFocusedMovie()
                                                    if (isSeries) onSeriesClick(movie) else onMovieClick(movie)
                                                }
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(bottom = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        heroMovies.forEachIndexed { index, _ ->
                                            Box(
                                                modifier = Modifier
                                                    .size(if (index == activeHeroIndex) 6.dp else 4.5.dp)
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

                        items(
                            count = activeCategories.size,
                            key = { idx -> activeCategories[idx].first.title }
                        ) { index ->
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
                                firstItemFocusRequester = if (index == 0) firstRowFocusRequester else null,
                                lastFocusedMovieId = lastFocusedMovieId,
                                lastFocusedRowTitle = lastFocusedRowTitle,
                                onMovieClick = { item ->
                                    viewModel.savedHomeScrollIndex = homeListState.firstVisibleItemIndex
                                    viewModel.savedHomeScrollOffset = homeListState.firstVisibleItemScrollOffset
                                    viewModel.rememberSelectedItem(item, genre.title)
                                    if (isRowSeries) onSeriesClick(item) else onMovieClick(item)
                                },
                                onHeaderClick = { onGenreSelect(genre) }
                            )
                        }
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
                    fontSize = 18.sp
                )
            },
            text = {
                Text(
                    text = "Da li ste sigurni da želite izaći iz aplikacije Media Studio?",
                    color = Color.LightGray,
                    fontSize = 14.sp
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
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val bgColor = if (isFocused) Color.White else if (isPrimary) Color.White else Color(0xFF2A2A2A).copy(alpha = 0.85f)
    val contentColor = if (isFocused) Color.Black else if (isPrimary) Color.Black else Color.White
    val borderColor = if (isFocused) Color(0xFFE50914).copy(alpha = 0.9f) else Color.Transparent

    Row(
        modifier = modifier
            .height(32.dp)
            .background(color = bgColor, shape = RoundedCornerShape(16.dp))
            .border(width = if (isFocused) 1.8.dp else 0.dp, color = borderColor, shape = RoundedCornerShape(16.dp))
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = text,
            color = contentColor,
            fontWeight = FontWeight.Bold,
            fontSize = 12.5.sp
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
                shape = RoundedCornerShape(7.dp)
            )
            .border(
                width = if (isFocused) 1.8.dp else 0.8.dp,
                color = if (isFocused) Color.White.copy(alpha = 0.88f) else Color.White.copy(alpha = 0.25f),
                shape = RoundedCornerShape(7.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
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
            .size(36.dp)
            .background(
                color = if (isFocused) Color.Red else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = if (isFocused) 1.8.dp else 0.dp,
                color = if (isFocused) Color.White.copy(alpha = 0.88f) else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
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
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .background(
                color = if (isFocused) Color(0xFF2B2B2B) else Color.Transparent,
                shape = RoundedCornerShape(7.dp)
            )
            .border(
                width = if (isFocused) 1.8.dp else 0.dp,
                color = if (isFocused) Color.White.copy(alpha = 0.88f) else Color.Transparent,
                shape = RoundedCornerShape(7.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(
            text = title,
            color = if (isAdult) Color(0xFFFF6B6B) else Color.White,
            fontSize = 13.sp,
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
            .height(36.dp)
            .background(
                color = if (isFocused) Color(0xFF2A2A2A) else Color(0xFF161616),
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = if (isFocused) 1.8.dp else 0.8.dp,
                color = if (isFocused) Color.White.copy(alpha = 0.88f) else Color.White.copy(alpha = 0.22f),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = "Pretraži",
            tint = if (isFocused) Color.White else Color.Gray,
            modifier = Modifier.size(17.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Pretraži filmove i serije...",
            color = if (isFocused) Color.White else Color.Gray,
            fontSize = 12.sp,
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
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = if (isFocused) 1.8.dp else 0.8.dp,
                color = if (isFocused) Color.White.copy(alpha = 0.88f) else if (isSelected) Color.Red else Color.White.copy(alpha = 0.20f),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 5.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 11.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MovieCategoryRow(
    genre: GenreOption,
    movies: List<ArchiveMovie>,
    isNetflixTop10: Boolean = false,
    isSeries: Boolean,
    viewModel: HomeViewModel,
    authorImages: Map<String, String>,
    modifier: Modifier = Modifier,
    firstItemFocusRequester: FocusRequester? = null,
    lastFocusedMovieId: String? = null,
    lastFocusedRowTitle: String? = null,
    onMovieClick: (ArchiveMovie) -> Unit,
    onHeaderClick: () -> Unit
) {
    if (movies.isEmpty()) return
    val displayTitle = if (isNetflixTop10) "🔥 ${genre.title}" else genre.title
    val headerInteraction = remember { MutableInteractionSource() }
    val isHeaderFocused by headerInteraction.collectIsFocusedAsState()
    val rowListState = rememberLazyListState()
    val isTargetRow = lastFocusedRowTitle == genre.title

    LaunchedEffect(movies, lastFocusedMovieId, isTargetRow) {
        val targetId = lastFocusedMovieId
        if (isTargetRow && !targetId.isNullOrBlank()) {
            val targetIdx = movies.indexOfFirst { it.identifier == targetId }
            if (targetIdx >= 0) {
                val isVisible = rowListState.layoutInfo.visibleItemsInfo.any { it.index == targetIdx }
                if (!isVisible) {
                    rowListState.scrollToItem(targetIdx)
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 1.dp)
                .focusProperties { left = FocusRequester.Cancel }
                .background(
                    color = if (isHeaderFocused) Color(0xFF222222) else Color.Transparent,
                    shape = RoundedCornerShape(7.dp)
                )
                .border(
                    width = if (isHeaderFocused) 1.8.dp else 0.dp,
                    color = if (isHeaderFocused) Color.White.copy(alpha = 0.88f) else Color.Transparent,
                    shape = RoundedCornerShape(7.dp)
                )
                .clickable(interactionSource = headerInteraction, indication = null) { onHeaderClick() }
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = displayTitle,
                color = Color.White,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Svi",
                color = if (isHeaderFocused) Color.White else Color.Gray,
                fontSize = 11.5.sp,
                fontWeight = if (isHeaderFocused) FontWeight.Bold else FontWeight.Normal
            )
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Otvori žanr",
                tint = if (isHeaderFocused) Color.White else Color.Gray,
                modifier = Modifier.size(17.dp)
            )
        }

        LazyRow(
            state = rowListState,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(if (isNetflixTop10) 12.dp else 10.dp),
            modifier = Modifier.focusGroup()
        ) {
            itemsIndexed(
                items = movies,
                key = { _, m -> m.identifier }
            ) { index, movie ->
                val shouldRestore = isTargetRow && movie.identifier == lastFocusedMovieId
                val edgeModifier = if (index == 0) {
                    Modifier.focusProperties { left = FocusRequester.Cancel }
                } else {
                    Modifier
                }
                val itemFR = if (index == 0) firstItemFocusRequester else null

                if (isNetflixTop10) {
                    NetflixTop10Card(
                        movie = movie,
                        rankNumber = index + 1,
                        isSeries = isSeries,
                        modifier = edgeModifier,
                        externalFocusRequester = itemFR,
                        shouldRestoreFocus = shouldRestore,
                        onFocusRestored = { viewModel.clearLastFocusedMovie() },
                        onMovieClick = onMovieClick
                    )
                } else {
                    MovieCard(
                        movie = movie,
                        isSeries = isSeries,
                        viewModel = viewModel,
                        authorImages = authorImages,
                        modifier = edgeModifier,
                        externalFocusRequester = itemFR,
                        shouldRestoreFocus = shouldRestore,
                        onFocusRestored = { viewModel.clearLastFocusedMovie() },
                        onMovieClick = onMovieClick
                    )
                }
            }
        }
    }
}

@Composable
fun NetflixTop10Card(
    movie: ArchiveMovie,
    rankNumber: Int,
    isSeries: Boolean,
    modifier: Modifier = Modifier,
    externalFocusRequester: FocusRequester? = null,
    shouldRestoreFocus: Boolean = false,
    onFocusRestored: () -> Unit = {},
    onMovieClick: (ArchiveMovie) -> Unit
) {
    val context = LocalContext.current
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
    val internalFocusRequester = remember { FocusRequester() }
    val activeFocusRequester = externalFocusRequester ?: internalFocusRequester

    LaunchedEffect(shouldRestoreFocus) {
        if (shouldRestoreFocus) {
            delay(60L)
            try {
                activeFocusRequester.requestFocus()
                onFocusRestored()
            } catch (_: Exception) {
            }
        }
    }

    val firstArchiveId = movie.identifier.split(",").first()
    val fallbackUrl = "https://archive.org/services/img/$firstArchiveId"
    var posterUrl by remember(movie.identifier) {
        mutableStateOf(SupabaseRepository.getCatalogItem(movie.identifier)?.posterUrl.orEmpty())
    }

    LaunchedEffect(movie.identifier, movie.title) {
        val sbPoster = SupabaseRepository.getCatalogItem(movie.identifier)?.posterUrl.orEmpty()
        if (sbPoster.isNotBlank()) {
            posterUrl = sbPoster
        } else {
            val fetchedUrl = PosterFetcher.fetchPoster(movie.title, isSeries, movie.identifier)
            posterUrl = fetchedUrl.ifBlank { fallbackUrl }
        }
    }

    Column(
        modifier = modifier
            .width(136.dp)
            .graphicsLayer {
                scaleX = scaleVal
                scaleY = scaleVal
            }
            .focusRequester(activeFocusRequester)
            .clickable(interactionSource = interactionSource, indication = null) {
                onMovieClick(movie)
            }
            .padding(bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .width(136.dp)
                .height(142.dp)
        ) {
            Card(
                modifier = Modifier
                    .width(96.dp)
                    .height(138.dp)
                    .align(Alignment.CenterEnd)
                    .border(borderWidth, borderBrush, RoundedCornerShape(9.dp)),
                shape = RoundedCornerShape(9.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = if (isFocused) 8.dp else 0.dp)
            ) {
                val activeUrl = posterUrl.ifBlank { fallbackUrl }
                val imageRequest = remember(activeUrl) {
                    ImageRequest.Builder(context)
                        .data(activeUrl)
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
                    Image(
                        painter = painter,
                        contentDescription = movie.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    if (painter.state is AsyncImagePainter.State.Error) {
                        val fallbackReq = remember(fallbackUrl) {
                            ImageRequest.Builder(context)
                                .data(fallbackUrl)
                                .size(140, 210)
                                .scale(Scale.FILL)
                                .bitmapConfig(Bitmap.Config.RGB_565)
                                .allowHardware(true)
                                .build()
                        }
                        Image(
                            painter = rememberAsyncImagePainter(fallbackReq),
                            contentDescription = movie.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }

            val numberFontSize = if (rankNumber == 10) 68.sp else 76.sp
            val numberLetterSpacing = if (rankNumber == 10) (-6).sp else 0.sp

            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = 0.dp, y = 6.dp)
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
                            width = 6f,
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
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = movie.title,
            color = if (isFocused) Color.White else Color.LightGray,
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(136.dp).padding(start = 24.dp)
        )
    }
}

@Composable
fun MovieCard(
    movie: ArchiveMovie,
    isSeries: Boolean,
    viewModel: HomeViewModel,
    authorImages: Map<String, String>,
    modifier: Modifier = Modifier,
    externalFocusRequester: FocusRequester? = null,
    shouldRestoreFocus: Boolean = false,
    onFocusRestored: () -> Unit = {},
    onMovieClick: (ArchiveMovie) -> Unit
) {
    val context = LocalContext.current
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
    val internalFocusRequester = remember { FocusRequester() }
    val activeFocusRequester = externalFocusRequester ?: internalFocusRequester

    LaunchedEffect(shouldRestoreFocus) {
        if (shouldRestoreFocus) {
            delay(60L)
            try {
                activeFocusRequester.requestFocus()
                onFocusRestored()
            } catch (_: Exception) {
            }
        }
    }

    val isAuthor = movie.identifier.startsWith("AUTHOR|")
    var posterUrl by remember(movie.identifier) {
        mutableStateOf(
            if (isAuthor) {
                val authorName = movie.identifier.removePrefix("AUTHOR|")
                SupabaseRepository.getAuthors()
                    .find { it.name.equals(authorName, ignoreCase = true) }
                    ?.photoUrl
                    ?.takeIf { it.isNotBlank() }
                    ?: viewModel.staticAuthorImages[authorName]
                    ?: authorImages[authorName].orEmpty()
            } else {
                SupabaseRepository.getCatalogItem(movie.identifier)?.posterUrl.orEmpty()
            }
        )
    }

    LaunchedEffect(movie.identifier, movie.title) {
        if (isAuthor) {
            val authorName = movie.identifier.removePrefix("AUTHOR|")
            val resolved = SupabaseRepository.getAuthors()
                .find { it.name.equals(authorName, ignoreCase = true) }
                ?.photoUrl
                ?.takeIf { it.isNotBlank() }
                ?: viewModel.staticAuthorImages[authorName]
                ?: authorImages[authorName].orEmpty()
            posterUrl = resolved
        } else {
            val sbPoster = SupabaseRepository.getCatalogItem(movie.identifier)?.posterUrl.orEmpty()
            if (sbPoster.isNotBlank()) {
                posterUrl = sbPoster
            } else {
                val firstArchiveId = movie.identifier.split(",").first()
                val fallbackUrl = "https://archive.org/services/img/$firstArchiveId"
                val fetchedUrl = PosterFetcher.fetchPoster(movie.title, isSeries, movie.identifier)
                posterUrl = fetchedUrl.ifBlank { fallbackUrl }
            }
        }
    }

    Column(
        modifier = modifier
            .width(100.dp)
            .graphicsLayer {
                scaleX = scaleVal
                scaleY = scaleVal
            }
            .focusRequester(activeFocusRequester)
            .clickable(interactionSource = interactionSource, indication = null) {
                onMovieClick(movie)
            }
            .padding(bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier
                .width(100.dp)
                .height(142.dp)
                .border(borderWidth, borderBrush, RoundedCornerShape(9.dp)),
            shape = RoundedCornerShape(9.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = if (isFocused) 8.dp else 0.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C1C))
        ) {
            if (posterUrl.isNotEmpty()) {
                val imageRequest = remember(posterUrl) {
                    ImageRequest.Builder(context)
                        .data(posterUrl)
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
                        if (isAuthor) {
                            Box(
                                modifier = Modifier.fillMaxSize().background(Color(0xFF1F1F1F)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = movie.title,
                                    tint = Color.Gray,
                                    modifier = Modifier.size(44.dp)
                                )
                            }
                        } else {
                            val fallbackId = movie.identifier.split(",").first()
                            val fallback = "https://archive.org/services/img/$fallbackId"
                            val fallbackReq = remember(fallback) {
                                ImageRequest.Builder(context)
                                    .data(fallback)
                                    .size(140, 210)
                                    .scale(Scale.FILL)
                                    .bitmapConfig(Bitmap.Config.RGB_565)
                                    .allowHardware(true)
                                    .build()
                            }
                            Image(
                                painter = rememberAsyncImagePainter(fallbackReq),
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
            } else if (isAuthor) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF1F1F1F)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = movie.title,
                        tint = Color.Gray,
                        modifier = Modifier.size(44.dp)
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = movie.title,
            color = if (isFocused) Color.White else Color.LightGray,
            fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Normal,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(100.dp)
        )
    }
}
