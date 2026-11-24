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
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import com.example.mediastudio.data.update.UpdateCheckResult
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onUpdateFound: (AppReleaseInfo) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val currentVersionName = remember { AppUpdateManager.getCurrentVersionName() }
    val currentVersionCode = remember { AppUpdateManager.getCurrentVersionCode() }

    var show18Plus by remember { mutableStateOf(AppUpdateManager.is18PlusEnabled(context)) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }

    val checkButtonFocusRequester = remember { FocusRequester() }

    BackHandler {
        onBackClick()
    }

    LaunchedEffect(Unit) {
        try {
            checkButtonFocusRequester.requestFocus()
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
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    val backInteraction = remember { MutableInteractionSource() }
                    val isBackFocused by backInteraction.collectIsFocusedAsState()
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .focusProperties { left = FocusRequester.Cancel }
                            .background(
                                color = if (isBackFocused) Color.Red else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .border(
                                width = if (isBackFocused) 2.5.dp else 0.dp,
                                color = if (isBackFocused) Color.White else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable(interactionSource = backInteraction, indication = null) { onBackClick() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Povratak", tint = Color.White)
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
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 18+ kvačica vidljivosti
            val adultInteraction = remember { MutableInteractionSource() }
            val isAdultFocused by adultInteraction.collectIsFocusedAsState()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = if (isAdultFocused) Color(0xFF262626) else Color(0xFF161616),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .border(
                        width = if (isAdultFocused) 3.dp else 1.dp,
                        color = if (isAdultFocused) Color.White else Color(0xFF2D2D2D),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable(interactionSource = adultInteraction, indication = null) {
                        val nextState = !show18Plus
                        show18Plus = nextState
                        AppUpdateManager.set18PlusEnabled(context, nextState)
                    }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "18+ sadržaj (vidljivost)",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (show18Plus) {
                            "Omogućen prikaz 18+ kategorija i sadržaja."
                        } else {
                            "Sakrivene 18+ kategorije i sadržaji."
                        },
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }

                Icon(
                    imageVector = if (show18Plus) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = if (show18Plus) Color(0xFFFF5252) else Color.LightGray,
                    modifier = Modifier.size(30.dp)
                )
            }

            // Verzija i provjera ažuriranja
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161616), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF2D2D2D), RoundedCornerShape(12.dp))
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Ažuriranje aplikacije",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Trenutna verzija aplikacije: $currentVersionName (kod $currentVersionCode)",
                    color = Color.LightGray,
                    fontSize = 15.sp
                )

                val checkInteraction = remember { MutableInteractionSource() }
                val isCheckFocused by checkInteraction.collectIsFocusedAsState()

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .focusRequester(checkButtonFocusRequester)
                        .background(
                            color = when {
                                isCheckFocused -> Color.Red
                                else -> Color(0xFF990000)
                            },
                            shape = RoundedCornerShape(10.dp)
                        )
                        .border(
                            width = if (isCheckFocused) 3.dp else 1.dp,
                            color = if (isCheckFocused) Color.White else Color.Transparent,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable(
                            enabled = !isCheckingUpdate,
                            interactionSource = checkInteraction,
                            indication = null
                        ) {
                            isCheckingUpdate = true
                            statusMessage = "Provjeravam dostupnost nove verzije..."
                            scope.launch {
                                val res = AppUpdateManager.checkForUpdate()
                                isCheckingUpdate = false
                                when (res) {
                                    is UpdateCheckResult.UpdateAvailable -> {
                                        statusMessage = "Dostupna je verzija ${res.release.versionName}."
                                        onUpdateFound(res.release)
                                    }
                                    is UpdateCheckResult.UpToDate -> {
                                        statusMessage = "Imate najnoviju verziju aplikacije ($currentVersionName)."
                                    }
                                    is UpdateCheckResult.Error -> {
                                        statusMessage = res.message
                                    }
                                }
                            }
                        }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(
                                color = Color.White,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Default.SystemUpdate,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = if (isCheckingUpdate) "Provjera u tijeku..." else "Provjeri dostupnost ažuriranja",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }

                if (statusMessage.isNotBlank()) {
                    Text(
                        text = statusMessage,
                        color = Color(0xFFDDDDDD),
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
