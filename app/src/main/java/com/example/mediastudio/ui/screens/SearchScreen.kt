package com.example.mediastudio.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mediastudio.data.model.ArchiveMovie
import com.example.mediastudio.ui.viewmodel.ContentTab
import com.example.mediastudio.ui.viewmodel.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: HomeViewModel,
    onMovieClick: (ArchiveMovie) -> Unit,
    onSeriesClick: (ArchiveMovie) -> Unit,
    onBackClick: () -> Unit
) {
    val searchResults by viewModel.globalSearchResults.collectAsState()
    val isSearching by viewModel.isSearchingGlobal.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()

    var query by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val firstKeyFocusRequester = remember { FocusRequester() }

    val keys = listOf(
        "A", "B", "C", "D", "E", "F",
        "G", "H", "I", "J", "K", "L",
        "M", "N", "O", "P", "Q", "R",
        "S", "T", "U", "V", "W", "X",
        "Y", "Z", "0", "1", "2", "3",
        "4", "5", "6", "7", "8", "9"
    )

    BackHandler {
        if (query.isNotEmpty()) {
            query = ""
            viewModel.performGlobalSearch("")
        } else {
            onBackClick()
        }
    }

    LaunchedEffect(Unit) {
        try {
            firstKeyFocusRequester.requestFocus()
        } catch (_: Exception) {
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Pretraži Archive.org",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    TvHighlightIconButton(
                        onClick = onBackClick,
                        contentDescription = "Natrag"
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Natrag", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
            )
        },
        containerColor = Color.Black
    ) { paddingValues ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // LIJEVI STUPAC: Netflix TV tipkovnica za daljinski upravljač
            Column(
                modifier = Modifier
                    .width(330.dp)
                    .fillMaxHeight()
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        viewModel.performGlobalSearch(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Upiši naziv filma ili serije...", color = Color.Gray, fontSize = 14.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Red) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.Gray
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            viewModel.performGlobalSearch(query)
                            focusManager.clearFocus()
                        },
                        onNext = {
                            viewModel.performGlobalSearch(query)
                            focusManager.clearFocus()
                        },
                        onDone = {
                            viewModel.performGlobalSearch(query)
                            focusManager.clearFocus()
                        }
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Gumbi RAZMAK, BRIŠI, OČISTI
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NetflixKeyButton(
                        label = "RAZMAK",
                        modifier = Modifier.weight(1.2f),
                        onClick = {
                            query += " "
                            viewModel.performGlobalSearch(query)
                        }
                    )
                    NetflixKeyButton(
                        label = "⌫ BRIŠI",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (query.isNotEmpty()) {
                                query = query.dropLast(1)
                                viewModel.performGlobalSearch(query)
                            }
                        }
                    )
                    NetflixKeyButton(
                        label = "OČISTI",
                        modifier = Modifier.weight(0.9f),
                        onClick = {
                            query = ""
                            viewModel.performGlobalSearch("")
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Mreža slova i brojeva 6x6
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    keys.chunked(6).forEachIndexed { rowIndex, rowKeys ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowKeys.forEachIndexed { colIndex, keyChar ->
                                val mod = if (rowIndex == 0 && colIndex == 0) {
                                    Modifier.weight(1f).focusRequester(firstKeyFocusRequester)
                                } else {
                                    Modifier.weight(1f)
                                }
                                NetflixKeyButton(
                                    label = keyChar,
                                    modifier = mod,
                                    onClick = {
                                        query += keyChar.lowercase()
                                        viewModel.performGlobalSearch(query)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // DESNI STUPAC: Rezultati pretrage uživo
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                when {
                    query.trim().length < 2 -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Odaberi slova na tipkovnici lijevo",
                                color = Color.LightGray,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Rezultati s cijelog Archive.org prikazuju se automatski",
                                color = Color.Gray,
                                fontSize = 14.sp
                            )
                        }
                    }
                    isSearching -> {
                        CircularProgressIndicator(
                            color = Color.Red,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    searchResults.isEmpty() -> {
                        Text(
                            text = "Nema pronađenih naslova za \"$query\"",
                            color = Color.Gray,
                            fontSize = 16.sp,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    else -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = "Rezultati za \"${query.trim()}\" (${searchResults.size})",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(bottom = 10.dp)
                            )
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = 125.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(searchResults) { ratedItem ->
                                    RatedMovieGridCard(
                                        ratedMovie = ratedItem,
                                        onMovieClick = { item ->
                                            if (selectedTab == ContentTab.SERIES) onSeriesClick(item) else onMovieClick(item)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetflixKeyButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .height(42.dp)
            .background(
                color = if (isFocused) Color.Red else Color(0xFF1F1F1F),
                shape = RoundedCornerShape(6.dp)
            )
            .border(
                width = if (isFocused) 2.5.dp else 1.dp,
                color = if (isFocused) Color.White else Color.DarkGray,
                shape = RoundedCornerShape(6.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = if (label.length > 2) 11.sp else 15.sp,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
