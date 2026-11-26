package com.example.mediastudio.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mediastudio.data.update.AppReleaseInfo
import com.example.mediastudio.data.update.AppUpdateManager

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onUpdateFound: (AppReleaseInfo) -> Unit,
    on18PlusChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current

    var show18Plus by remember { mutableStateOf(AppUpdateManager.is18PlusEnabled(context)) }
    val adultFocusRequester = remember { FocusRequester() }

    BackHandler {
        onBackClick()
    }

    LaunchedEffect(Unit) {
        try {
            adultFocusRequester.requestFocus()
        } catch (_: Exception) {
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Postavke",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                navigationIcon = {
                    val backInteraction = remember { MutableInteractionSource() }
                    val isBackFocused by backInteraction.collectIsFocusedAsState()
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .focusProperties { left = FocusRequester.Cancel }
                            .background(
                                color = if (isBackFocused) Color.Red else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .border(
                                width = if (isBackFocused) 1.8.dp else 0.dp,
                                color = if (isBackFocused) Color.White.copy(alpha = 0.88f) else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable(interactionSource = backInteraction, indication = null) { onBackClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "Povratak",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0A0A0A))
            )
        },
        containerColor = Color(0xFF0E0E0E)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val adultInteraction = remember { MutableInteractionSource() }
            val isAdultFocused by adultInteraction.collectIsFocusedAsState()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(adultFocusRequester)
                    .background(
                        color = if (isAdultFocused) Color(0xFF262626) else Color(0xFF161616),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .border(
                        width = if (isAdultFocused) 1.8.dp else 0.8.dp,
                        color = if (isAdultFocused) Color.White.copy(alpha = 0.88f) else Color.White.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clickable(interactionSource = adultInteraction, indication = null) {
                        val nextState = !show18Plus
                        show18Plus = nextState
                        AppUpdateManager.set18PlusEnabled(context, nextState)
                        on18PlusChanged(nextState)
                    }
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "18+ sadržaj (vidljivost)",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = if (show18Plus) {
                            "Omogućen prikaz 18+ kategorija i sadržaja."
                        } else {
                            "Sakrivene 18+ kategorije i sadržaji."
                        },
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }

                Icon(
                    imageVector = if (show18Plus) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = if (show18Plus) Color(0xFFFF5252) else Color.LightGray,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}
