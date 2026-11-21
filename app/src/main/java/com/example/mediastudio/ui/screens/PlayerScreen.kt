package com.example.mediastudio.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.mediastudio.data.api.ArchiveMetadataModule
import com.example.mediastudio.data.api.SupabaseRepository
import com.example.mediastudio.data.api.VideoQualityOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.roundToLong

private const val SUBTITLES_SUPABASE_URL = "https://jpjezjcbayontfwywvay.supabase.co/rest/v1"
private const val SUBTITLES_SUPABASE_KEY = "sb_publishable_d8MgyOYxbVSNcU6niaAGAQ_hlCwfwh5"

data class SrtCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class PlayerSubtitleOption(
    val fileName: String,
    val language: String,
    val downloadUrl: String,
    val isGzipped: Boolean = false,
    val fps: Float = 0f,
    val downloads: Int = 0
)

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    identifier: String,
    movieTitle: String,
    movieYear: String = "",
    directVideoUrl: String = "",
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val fallbackVideoUrl = "https://archive.org/download/$identifier/$identifier.mp4"

    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var showControls by remember { mutableStateOf(false) }
    var controlsInteractionTick by remember { mutableIntStateOf(0) }

    var videoResizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var detectedVideoFps by remember { mutableFloatStateOf(0f) }

    var activeStreamUrl by remember { mutableStateOf("") }
    var mp4FallbackUrlOverride by remember { mutableStateOf<String?>(null) }
    var triedMp4Fallback by remember { mutableStateOf(false) }
    var playbackErrorMessage by remember { mutableStateOf("") }

    var quickHudMessage by remember { mutableStateOf("") }
    var quickHudTick by remember { mutableIntStateOf(0) }
    var isContinuousSeeking by remember { mutableStateOf(false) }

    var availableQualities by remember { mutableStateOf<List<VideoQualityOption>>(emptyList()) }
    var selectedQuality by remember { mutableStateOf<VideoQualityOption?>(null) }
    var showQualityDialog by remember { mutableStateOf(false) }

    var showSubtitleDialog by remember { mutableStateOf(false) }
    var subtitleList by remember { mutableStateOf<List<PlayerSubtitleOption>>(emptyList()) }
    var isLoadingSubtitles by remember { mutableStateOf(false) }
    var isTranslatingSubtitles by remember { mutableStateOf(false) }
    var activeSubtitleName by remember { mutableStateOf<String?>(null) }
    var activeSubtitleFps by remember { mutableFloatStateOf(0f) }
    var rawActiveCues by remember { mutableStateOf<List<SrtCue>>(emptyList()) }
    var parsedCues by remember { mutableStateOf<List<SrtCue>>(emptyList()) }
    var appliedFpsConversionLabel by remember { mutableStateOf("") }
    var lastNotifiedFpsKey by remember { mutableStateOf("") }
    var currentSubtitleText by remember { mutableStateOf("") }
    var subtitleOffsetMs by remember { mutableLongStateOf(0L) }
    var cachedAutoTranslatedCues by remember { mutableStateOf<List<SrtCue>>(emptyList()) }

    var showAudioDialog by remember { mutableStateOf(false) }
    var hasMultipleAudioTracks by remember { mutableStateOf(false) }
    var hasNotifiedMultiAudio by remember { mutableStateOf(false) }

    val isEpisodicContent = remember(availableQualities) {
        availableQualities.any {
            it.label.contains(
                Regex("(?i)(\\bs\\d{1,2}\\s*e\\d{1,3}\\b|\\b\\d{1,2}x\\d{1,3}\\b|\\bepisode\\b|\\bepizoda\\b|\\bseason\\b|\\bsezona\\b)")
            )
        }
    }

    val rootFocusRequester = remember { FocusRequester() }
    val centerPauseFocusRequester = remember { FocusRequester() }

    val exoPlayer = remember {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,
                50_000,
                1_500,
                3_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) MediaStudio/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        val player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
            .build()

        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setPreferredAudioLanguages("hr", "hrv", "en", "eng")
            .build()

        player
    }

    // Automatska konverzija vremenskih oznaka titla čim se znaju FPS titla i FPS videa
    LaunchedEffect(rawActiveCues, activeSubtitleFps, detectedVideoFps, duration, activeSubtitleName) {
        if (rawActiveCues.isEmpty()) {
            parsedCues = emptyList()
            appliedFpsConversionLabel = ""
            return@LaunchedEffect
        }
        val (converted, convLabel) = convertCuesForVideoFps(
            cues = rawActiveCues,
            subFps = activeSubtitleFps,
            videoFps = detectedVideoFps,
            videoDurationMs = duration
        )
        parsedCues = converted
        appliedFpsConversionLabel = convLabel
        if (convLabel.isNotEmpty()) {
            val notifyKey = "${activeSubtitleName.orEmpty()}|$convLabel"
            if (notifyKey != lastNotifiedFpsKey) {
                lastNotifiedFpsKey = notifyKey
                quickHudMessage = "🎞️ Titl prilagođen: $convLabel"
                quickHudTick++
            }
        }
    }

    BackHandler {
        when {
            showQualityDialog -> showQualityDialog = false
            showSubtitleDialog -> showSubtitleDialog = false
            showAudioDialog -> showAudioDialog = false
            showControls -> {
                showControls = false
                try {
                    rootFocusRequester.requestFocus()
                } catch (_: Exception) {}
            }
            exoPlayer.isPlaying -> {
                exoPlayer.pause()
                isPlaying = false
                quickHudMessage = "⏸ Pauzirano (pritisni Back ponovno za izlaz)"
                quickHudTick++
            }
            else -> {
                exoPlayer.stop()
                onNavigateBack()
            }
        }
    }

    LaunchedEffect(identifier, directVideoUrl) {
        videoResizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        detectedVideoFps = 0f
        hasMultipleAudioTracks = false
        hasNotifiedMultiAudio = false
        triedMp4Fallback = false
        mp4FallbackUrlOverride = null
        playbackErrorMessage = ""
        isBuffering = true

        activeSubtitleName = null
        activeSubtitleFps = 0f
        rawActiveCues = emptyList()
        parsedCues = emptyList()
        appliedFpsConversionLabel = ""
        lastNotifiedFpsKey = ""
        currentSubtitleText = ""
        subtitleOffsetMs = 0L
        subtitleList = emptyList()
        cachedAutoTranslatedCues = emptyList()
        isTranslatingSubtitles = false

        val cleanArchiveId = if (identifier.startsWith("http")) {
            identifier.substringAfter("archive.org/download/", "").substringBefore("/").ifBlank { identifier }
        } else {
            identifier.substringBefore(",tmdb-").trim()
        }
        val subtitleDbKey = if (identifier.startsWith("http")) {
            identifier.substringAfter("archive.org/download/", identifier).substringBeforeLast(".")
        } else {
            cleanArchiveId
        }
        val safeCacheSlug = subtitleDbKey.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(90)
        val localCacheFile = File(context.cacheDir, "sub_hr_$safeCacheSlug.json")

        val sbMeta = SupabaseRepository.getCatalogItem(identifier)
        val effectiveYear = movieYear.ifBlank {
            sbMeta?.releaseYear?.takeIf { it > 0 }?.toString() ?: ""
        }

        // 1. Pokreni video stream i dohvati Archive.org FPS metapodatke
        val initialUrl = if (directVideoUrl.isNotEmpty()) {
            availableQualities = emptyList()
            selectedQuality = null
            directVideoUrl
        } else {
            val qualities = ArchiveMetadataModule.fetchAvailableQualities(cleanArchiveId)
            availableQualities = qualities
            val bestQuality = qualities.firstOrNull()
            selectedQuality = bestQuality
            bestQuality?.url ?: fallbackVideoUrl
        }

        val upgradedOriginalUrl = if (initialUrl.lowercase().contains(".ia.mp4")) {
            findOriginalMultiAudioUrl(initialUrl)
        } else {
            null
        }

        if (upgradedOriginalUrl != null && upgradedOriginalUrl != initialUrl) {
            mp4FallbackUrlOverride = initialUrl
            activeStreamUrl = upgradedOriginalUrl
            exoPlayer.setMediaItem(MediaItem.fromUri(upgradedOriginalUrl), 0L)
        } else {
            activeStreamUrl = initialUrl
            exoPlayer.setMediaItem(MediaItem.fromUri(initialUrl), 0L)
        }
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true

        // 2. Automatska provjera lokalne predmemorije -> Supabase baze -> pametni odabir po FPS-u i prijevod
        launch {
            val archiveFpsHint = fetchArchiveVideoFps(cleanArchiveId)
            if (detectedVideoFps <= 0f && archiveFpsHint > 0f) {
                detectedVideoFps = archiveFpsHint
            }
            val targetFpsForPick = if (detectedVideoFps > 0f) detectedVideoFps else archiveFpsHint

            val localCached = readCachedTranslatedCues(localCacheFile)
            if (localCached != null && localCached.first.isNotEmpty()) {
                val (cachedCues, cachedFps) = localCached
                val dbOptionName = "🇭🇷 Baza titlova ($movieTitle)"
                val dbOption = PlayerSubtitleOption(
                    fileName = dbOptionName,
                    language = "HR-DB",
                    downloadUrl = "cached://$safeCacheSlug",
                    fps = cachedFps
                )
                cachedAutoTranslatedCues = cachedCues
                activeSubtitleName = dbOptionName
                activeSubtitleFps = cachedFps
                rawActiveCues = cachedCues
                subtitleList = listOf(dbOption)
                quickHudMessage = "💬 Titlovi učitani iz baze"
                quickHudTick++

                val extraSubs = searchAllSubtitles(cleanArchiveId, movieTitle, effectiveYear)
                subtitleList = listOf(dbOption) + extraSubs
                return@launch
            }

            val cloudSub = fetchSubtitleFromSupabase(subtitleDbKey)
            if (cloudSub != null && cloudSub.second.isNotEmpty()) {
                val (sourceName, dbCues) = cloudSub
                val dbFps = extractFpsFromText(sourceName)
                writeCachedTranslatedCues(localCacheFile, dbCues, dbFps)
                val cleanDisplaySource = sourceName.replace(Regex("\\s*\\[\\d+(?:\\.\\d+)?fps\\]"), "").trim()
                val dbOptionName = "🇭🇷 Baza titlova ($cleanDisplaySource)"
                val dbOption = PlayerSubtitleOption(
                    fileName = dbOptionName,
                    language = "HR-DB",
                    downloadUrl = "cached://$safeCacheSlug",
                    fps = dbFps
                )
                cachedAutoTranslatedCues = dbCues
                activeSubtitleName = dbOptionName
                activeSubtitleFps = dbFps
                rawActiveCues = dbCues
                subtitleList = listOf(dbOption)
                quickHudMessage = "💬 Titlovi učitani iz baze"
                quickHudTick++

                val extraSubs = searchAllSubtitles(cleanArchiveId, movieTitle, effectiveYear)
                subtitleList = listOf(dbOption) + extraSubs
                return@launch
            }

            isLoadingSubtitles = true
            val foundSubs = searchAllSubtitles(cleanArchiveId, movieTitle, effectiveYear)
            isLoadingSubtitles = false

            if (foundSubs.isEmpty()) {
                subtitleList = emptyList()
                quickHudMessage = "💬 Nema pronađenih titlova"
                quickHudTick++
                return@launch
            }

            val latestVideoFps = if (detectedVideoFps > 0f) detectedVideoFps else targetFpsForPick
            val bestSub = pickBestSubtitleOption(foundSubs, latestVideoFps)
            val rawSrt = downloadSubtitleText(bestSub)
            val initialCues = parseSrtContent(rawSrt)

            if (initialCues.isEmpty()) {
                subtitleList = foundSubs
                quickHudMessage = "💬 Nema ispravnih titlova"
                quickHudTick++
                return@launch
            }

            val fpsTag = if (bestSub.fps > 0f) " • ${formatFpsShort(bestSub.fps)} fps" else ""

            if (isCroatianOrRegionalSubtitle(bestSub, initialCues)) {
                subtitleList = foundSubs
                activeSubtitleName = bestSub.fileName
                activeSubtitleFps = bestSub.fps
                rawActiveCues = initialCues
                quickHudMessage = "💬 Titlovi: [${bestSub.language}$fpsTag] ${bestSub.fileName}"
                quickHudTick++
            } else {
                val autoOptionName = "🇭🇷 Baza titlova (${bestSub.fileName})"
                val autoOption = PlayerSubtitleOption(
                    fileName = autoOptionName,
                    language = "HR-DB",
                    downloadUrl = "cached://$safeCacheSlug",
                    fps = bestSub.fps
                )

                subtitleList = foundSubs
                activeSubtitleName = bestSub.fileName
                activeSubtitleFps = bestSub.fps
                rawActiveCues = initialCues
                isTranslatingSubtitles = true
                quickHudMessage = "🌐 Prevodim titlove na hrvatski..."
                quickHudTick++

                val translatedCues = translateCuesToCroatian(initialCues, localCacheFile, bestSub.fps)
                isTranslatingSubtitles = false

                if (translatedCues.isNotEmpty()) {
                    cachedAutoTranslatedCues = translatedCues
                    subtitleList = listOf(autoOption) + foundSubs
                    val storedSourceName = if (bestSub.fps > 0f) {
                        "${bestSub.fileName} [${formatFpsShort(bestSub.fps)}fps]"
                    } else {
                        bestSub.fileName
                    }
                    launch {
                        saveSubtitleToSupabase(subtitleDbKey, storedSourceName, translatedCues)
                    }

                    if (activeSubtitleName == bestSub.fileName) {
                        activeSubtitleName = autoOptionName
                        activeSubtitleFps = bestSub.fps
                        rawActiveCues = translatedCues
                        quickHudMessage = "✅ Prevedeno i spremljeno u bazu"
                        quickHudTick++
                    }
                }
            }
        }
    }

    LaunchedEffect(quickHudTick) {
        if (quickHudMessage.isNotEmpty()) {
            val durationMs = when {
                quickHudMessage.startsWith("🔊") -> 4500L
                quickHudMessage.startsWith("💬") || quickHudMessage.startsWith("🌐") ||
                    quickHudMessage.startsWith("✅") || quickHudMessage.startsWith("🎞️") -> 3500L
                else -> 2000L
            }
            delay(durationMs)
            if (!isContinuousSeeking) {
                quickHudMessage = ""
            }
        }
    }

    LaunchedEffect(showControls, controlsInteractionTick, isPlaying, showQualityDialog, showSubtitleDialog, showAudioDialog) {
        if (showControls && isPlaying && !showQualityDialog && !showSubtitleDialog && !showAudioDialog) {
            delay(5000L)
            showControls = false
            try {
                rootFocusRequester.requestFocus()
            } catch (_: Exception) {
            }
        } else if (!showControls) {
            try {
                rootFocusRequester.requestFocus()
            } catch (_: Exception) {
            }
        }
    }

    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    playbackErrorMessage = ""
                    duration = exoPlayer.duration.coerceAtLeast(0L)
                    val formatFps = exoPlayer.videoFormat?.frameRate ?: 0f
                    if (formatFps in 15f..120f) {
                        detectedVideoFps = formatFps
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!triedMp4Fallback && (mp4FallbackUrlOverride != null || activeStreamUrl.contains("archive.org/download/"))) {
                    triedMp4Fallback = true
                    isBuffering = true
                    coroutineScope.launch {
                        val mp4Alternative = mp4FallbackUrlOverride ?: findCompatibleMp4Fallback(activeStreamUrl, movieTitle)
                        if (mp4Alternative != null && mp4Alternative != activeStreamUrl) {
                            activeStreamUrl = mp4Alternative
                            exoPlayer.setMediaItem(MediaItem.fromUri(mp4Alternative), 0L)
                            exoPlayer.prepare()
                            exoPlayer.playWhenReady = true
                        } else {
                            isBuffering = false
                            playbackErrorMessage = "Video zapis nije javno dostupan na Archive.org-u ili format nije podržan."
                        }
                    }
                } else {
                    isBuffering = false
                    playbackErrorMessage = "Greška pri učitavanju videa (${error.errorCodeName})."
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val videoGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                for (vg in videoGroups) {
                    for (i in 0 until vg.length) {
                        if (vg.isTrackSelected(i)) {
                            val fps = vg.getTrackFormat(i).frameRate
                            if (fps in 15f..120f) {
                                detectedVideoFps = fps
                            }
                        }
                    }
                }

                val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                val totalAudioTracks = audioGroups.sumOf { it.length }
                hasMultipleAudioTracks = totalAudioTracks > 1

                if (totalAudioTracks > 1 && !hasNotifiedMultiAudio) {
                    hasNotifiedMultiAudio = true
                    quickHudMessage = "🔊 Dostupno $totalAudioTracks audio jezika (OK → 🔊 Audio)"
                    quickHudTick++
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(exoPlayer, parsedCues, subtitleOffsetMs) {
        while (true) {
            val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
            currentPosition = pos
            if (exoPlayer.duration > 0) duration = exoPlayer.duration

            if (parsedCues.isNotEmpty()) {
                val adjustedPos = pos - subtitleOffsetMs
                val activeCue = parsedCues.firstOrNull { adjustedPos in it.startMs..it.endMs }
                currentSubtitleText = activeCue?.text ?: ""
            } else {
                currentSubtitleText = ""
            }
            delay(200L)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                val native = keyEvent.nativeKeyEvent
                val keyCode = native.keyCode

                if (showQualityDialog || showSubtitleDialog || showAudioDialog) {
                    return@onPreviewKeyEvent false
                }

                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0) {
                        if (!showControls) {
                            showControls = true
                            controlsInteractionTick++
                            return@onPreviewKeyEvent true
                        }
                    }
                    if (showControls) {
                        controlsInteractionTick++
                    }
                    return@onPreviewKeyEvent false
                }

                if (!showControls && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                    val isForward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    if (native.action == KeyEvent.ACTION_DOWN) {
                        val stepMs = if (native.repeatCount == 0) 15_000L else 12_000L
                        isContinuousSeeking = native.repeatCount > 0
                        val targetPos = if (isForward) {
                            (exoPlayer.currentPosition + stepMs).coerceAtMost(duration.coerceAtLeast(0L))
                        } else {
                            (exoPlayer.currentPosition - stepMs).coerceAtLeast(0L)
                        }
                        exoPlayer.seekTo(targetPos)
                        currentPosition = targetPos
                        quickHudMessage = if (isContinuousSeeking) {
                            "${if (isForward) "⏩" else "⏪"} ${formatTime(targetPos)} / ${formatTime(duration)}"
                        } else {
                            "${if (isForward) "+15s ↻" else "↺ -15s"}  (${formatTime(targetPos)})"
                        }
                        quickHudTick++
                        return@onPreviewKeyEvent true
                    } else if (native.action == KeyEvent.ACTION_UP) {
                        isContinuousSeeking = false
                        quickHudTick++
                        return@onPreviewKeyEvent true
                    }
                }

                if (!showControls && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                    if (native.action == KeyEvent.ACTION_DOWN) {
                        showControls = true
                        controlsInteractionTick++
                        return@onPreviewKeyEvent true
                    }
                }

                if (showControls && native.action == KeyEvent.ACTION_DOWN) {
                    controlsInteractionTick++
                }
                false
            }
            .clickable {
                if (!showControls) {
                    showControls = true
                    controlsInteractionTick++
                } else {
                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                    controlsInteractionTick++
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = videoResizeMode
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { playerView ->
                if (playerView.resizeMode != videoResizeMode) {
                    playerView.resizeMode = videoResizeMode
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isBuffering) {
            CircularProgressIndicator(
                color = Color.Red,
                modifier = Modifier.align(Alignment.Center).size(52.dp)
            )
        }

        if (playbackErrorMessage.isNotEmpty() && !isBuffering) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 36.dp)
                    .background(Color(0xFF1A1A1A), RoundedCornerShape(12.dp))
                    .border(1.5.dp, Color.Red, RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 18.dp)
            ) {
                Text(
                    text = playbackErrorMessage,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }
        }

        if (quickHudMessage.isNotEmpty() && !showControls) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (currentSubtitleText.isNotEmpty()) 84.dp else 26.dp)
                    .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(10.dp))
                    .border(1.5.dp, Color.White.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(
                    text = quickHudMessage,
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (currentSubtitleText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (showControls) 95.dp else 36.dp, start = 32.dp, end = 32.dp)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = currentSubtitleText,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }
        }

        if (showControls) {
            LaunchedEffect(Unit) {
                try {
                    centerPauseFocusRequester.requestFocus()
                } catch (_: Exception) {
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        PlayerFocusableIconButton(
                            onClick = {
                                exoPlayer.stop()
                                onNavigateBack()
                            },
                            contentDescription = "Natrag"
                        ) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Natrag", tint = Color.White)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = movieTitle,
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (parsedCues.isNotEmpty()) {
                            PlayerChipButton(
                                label = "-0.5s",
                                onClick = {
                                    subtitleOffsetMs -= 500L
                                    controlsInteractionTick++
                                }
                            )
                            PlayerChipButton(
                                label = "+0.5s",
                                onClick = {
                                    subtitleOffsetMs += 500L
                                    controlsInteractionTick++
                                }
                            )
                        }

                        PlayerChipButton(
                            label = "↺ Početak",
                            onClick = {
                                exoPlayer.seekTo(0L)
                                exoPlayer.play()
                                controlsInteractionTick++
                            }
                        )

                        PlayerChipButton(
                            label = "■ Stop",
                            onClick = {
                                exoPlayer.pause()
                                exoPlayer.seekTo(0L)
                                controlsInteractionTick++
                            }
                        )

                        val formatLabel = when (videoResizeMode) {
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "📐 Zoom 16:9"
                            AspectRatioFrameLayout.RESIZE_MODE_FILL -> "📐 Rastegni"
                            else -> "📐 Original"
                        }
                        PlayerChipButton(
                            label = formatLabel,
                            isHighlighted = videoResizeMode != AspectRatioFrameLayout.RESIZE_MODE_FIT,
                            onClick = {
                                videoResizeMode = when (videoResizeMode) {
                                    AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                                controlsInteractionTick++
                            }
                        )

                        if (hasMultipleAudioTracks) {
                            PlayerChipButton(
                                label = "🔊 Audio",
                                isHighlighted = true,
                                onClick = {
                                    controlsInteractionTick++
                                    showAudioDialog = true
                                }
                            )
                        }

                        if (availableQualities.size > 1) {
                            PlayerChipButton(
                                label = if (isEpisodicContent) "📺 Epizode" else "⚙ Verzije",
                                onClick = {
                                    controlsInteractionTick++
                                    showQualityDialog = true
                                }
                            )
                        }

                        PlayerChipButton(
                            label = when {
                                isTranslatingSubtitles -> "CC 🌐..."
                                activeSubtitleName != null -> "CC ✓"
                                else -> "CC Titlovi"
                            },
                            isHighlighted = activeSubtitleName != null,
                            onClick = {
                                controlsInteractionTick++
                                showSubtitleDialog = true
                                if (subtitleList.isEmpty() && !isLoadingSubtitles) {
                                    isLoadingSubtitles = true
                                    coroutineScope.launch {
                                        subtitleList = searchAllSubtitles(identifier.substringBefore(",tmdb-"), movieTitle, movieYear)
                                        isLoadingSubtitles = false
                                    }
                                }
                            }
                        )
                    }
                }

                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlayerFocusableIconButton(
                        onClick = {
                            exoPlayer.seekTo(0L)
                            controlsInteractionTick++
                        },
                        contentDescription = "Početak",
                        sizeDp = 52
                    ) {
                        Icon(Icons.Default.SkipPrevious, contentDescription = "Početak", tint = Color.White, modifier = Modifier.size(32.dp))
                    }

                    PlayerFocusableIconButton(
                        onClick = {
                            exoPlayer.seekTo((currentPosition - 15000L).coerceAtLeast(0L))
                            controlsInteractionTick++
                        },
                        contentDescription = "Natrag 15s",
                        sizeDp = 52
                    ) {
                        Icon(Icons.Default.Replay10, contentDescription = "Natrag 15s", tint = Color.White, modifier = Modifier.size(32.dp))
                    }

                    PlayerFocusableIconButton(
                        onClick = {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            controlsInteractionTick++
                        },
                        contentDescription = "Pauza / Nastavi",
                        sizeDp = 64,
                        isPrimary = true,
                        modifier = Modifier.focusRequester(centerPauseFocusRequester)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.Black,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    PlayerFocusableIconButton(
                        onClick = {
                            exoPlayer.seekTo((currentPosition + 15000L).coerceAtMost(duration))
                            controlsInteractionTick++
                        },
                        contentDescription = "Naprijed 15s",
                        sizeDp = 52
                    ) {
                        Icon(Icons.Default.Forward10, contentDescription = "Naprijed 15s", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 24.dp, vertical = 14.dp)
                ) {
                    Slider(
                        value = if (duration > 0) currentPosition.toFloat() else 0f,
                        onValueChange = {
                            exoPlayer.seekTo(it.toLong())
                            controlsInteractionTick++
                        },
                        valueRange = 0f..(if (duration > 0) duration.toFloat() else 1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.Red,
                            inactiveTrackColor = Color.Gray
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val syncText = if (subtitleOffsetMs != 0L) "  |  Titl pomak: ${subtitleOffsetMs / 1000.0}s" else ""
                        val fpsText = when {
                            appliedFpsConversionLabel.isNotEmpty() -> "  |  Titl FPS: $appliedFpsConversionLabel"
                            detectedVideoFps > 0f -> "  |  Video: ${formatFpsShort(detectedVideoFps)} fps"
                            else -> ""
                        }
                        Text(
                            text = "${formatTime(currentPosition)} / ${formatTime(duration)}$syncText$fpsText",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        if (showAudioDialog) {
            AlertDialog(
                onDismissRequest = { showAudioDialog = false },
                containerColor = Color(0xFF1A1A1A),
                title = { Text("Odaberi audio jezik", color = Color.White) },
                text = {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val audioGroups = exoPlayer.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                        var counter = 0
                        audioGroups.forEach { group ->
                            for (trackIndex in 0 until group.length) {
                                counter++
                                val trackNum = counter
                                val format = group.getTrackFormat(trackIndex)
                                val displayLabel = formatAudioTrackLabel(format.language, format.label, trackNum)
                                val isSelected = group.isTrackSelected(trackIndex)

                                item {
                                    DialogFocusableRow(
                                        label = displayLabel,
                                        isSelected = isSelected,
                                        onClick = {
                                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                                .buildUpon()
                                                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
                                                .build()
                                            showAudioDialog = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAudioDialog = false }) {
                        Text("Zatvori", color = Color.Red)
                    }
                }
            )
        }

        if (showQualityDialog) {
            val dialogTitle = if (isEpisodicContent) "Odaberi epizodu" else "Odaberi verziju / rezoluciju"

            AlertDialog(
                onDismissRequest = { showQualityDialog = false },
                containerColor = Color(0xFF1A1A1A),
                title = { Text(dialogTitle, color = Color.White) },
                text = {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(availableQualities) { quality ->
                            DialogFocusableRow(
                                label = quality.label,
                                isSelected = quality == selectedQuality,
                                onClick = {
                                    val savedPos = exoPlayer.currentPosition
                                    selectedQuality = quality
                                    activeStreamUrl = quality.url
                                    detectedVideoFps = 0f
                                    exoPlayer.setMediaItem(MediaItem.fromUri(quality.url), savedPos)
                                    exoPlayer.prepare()
                                    exoPlayer.playWhenReady = true
                                    showQualityDialog = false
                                }
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showQualityDialog = false }) {
                        Text("Zatvori", color = Color.Red)
                    }
                }
            )
        }

        if (showSubtitleDialog) {
            val videoFpsHint = if (detectedVideoFps > 0f) " (Film: ${formatFpsShort(detectedVideoFps)} fps)" else ""
            AlertDialog(
                onDismissRequest = { showSubtitleDialog = false },
                containerColor = Color(0xFF1A1A1A),
                title = { Text("Odaberi titlove$videoFpsHint", color = Color.White) },
                text = {
                    if (isLoadingSubtitles) {
                        Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color.Red)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                DialogFocusableRow(
                                    label = "Isključi titlove",
                                    isSelected = activeSubtitleName == null,
                                    onClick = {
                                        activeSubtitleName = null
                                        activeSubtitleFps = 0f
                                        rawActiveCues = emptyList()
                                        parsedCues = emptyList()
                                        appliedFpsConversionLabel = ""
                                        currentSubtitleText = ""
                                        subtitleOffsetMs = 0L
                                        showSubtitleDialog = false
                                    }
                                )
                            }

                            if (rawActiveCues.isNotEmpty() && activeSubtitleName != null && !activeSubtitleName!!.startsWith("🇭🇷 Baza titlova") && !isTranslatingSubtitles) {
                                item {
                                    DialogFocusableRow(
                                        label = "🌐 Prevedi na hrvatski i spremi u bazu",
                                        isSelected = false,
                                        onClick = {
                                            val sourceName = activeSubtitleName ?: "titl"
                                            val sourceFps = activeSubtitleFps
                                            val cuesToTranslate = rawActiveCues
                                            showSubtitleDialog = false
                                            coroutineScope.launch {
                                                isTranslatingSubtitles = true
                                                quickHudMessage = "🌐 Prevodim titlove na hrvatski..."
                                                quickHudTick++
                                                val dbKey = if (identifier.startsWith("http")) {
                                                    identifier.substringAfter("archive.org/download/", identifier).substringBeforeLast(".")
                                                } else {
                                                    identifier.substringBefore(",tmdb-").trim()
                                                }
                                                val safeSlug = dbKey.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(90)
                                                val cacheFile = File(context.cacheDir, "sub_hr_$safeSlug.json")
                                                val translated = translateCuesToCroatian(cuesToTranslate, cacheFile, sourceFps)
                                                isTranslatingSubtitles = false
                                                if (translated.isNotEmpty()) {
                                                    val autoName = "🇭🇷 Baza titlova ($sourceName)"
                                                    cachedAutoTranslatedCues = translated
                                                    subtitleList = listOf(
                                                        PlayerSubtitleOption(
                                                            fileName = autoName,
                                                            language = "HR-DB",
                                                            downloadUrl = "cached://$safeSlug",
                                                            fps = sourceFps
                                                        )
                                                    ) + subtitleList.filterNot { it.fileName == autoName }
                                                    activeSubtitleName = autoName
                                                    activeSubtitleFps = sourceFps
                                                    rawActiveCues = translated
                                                    val storedSourceName = if (sourceFps > 0f) {
                                                        "$sourceName [${formatFpsShort(sourceFps)}fps]"
                                                    } else {
                                                        sourceName
                                                    }
                                                    launch {
                                                        saveSubtitleToSupabase(dbKey, storedSourceName, translated)
                                                    }
                                                    quickHudMessage = "✅ Prevedeno i spremljeno u bazu"
                                                    quickHudTick++
                                                }
                                            }
                                        }
                                    )
                                }
                            }

                            if (subtitleList.isEmpty()) {
                                item {
                                    Text("Nema pronađenih titlova za ovaj film.", color = Color.Gray, fontSize = 14.sp)
                                }
                            } else {
                                items(subtitleList) { sub ->
                                    val fpsBadge = if (sub.fps > 0f) " • ${formatFpsShort(sub.fps)} fps" else ""
                                    DialogFocusableRow(
                                        label = "[${sub.language}$fpsBadge] ${sub.fileName}",
                                        isSelected = activeSubtitleName == sub.fileName,
                                        onClick = {
                                            activeSubtitleName = sub.fileName
                                            activeSubtitleFps = sub.fps
                                            subtitleOffsetMs = 0L
                                            lastNotifiedFpsKey = ""
                                            showSubtitleDialog = false
                                            if (sub.downloadUrl.startsWith("cached://") && cachedAutoTranslatedCues.isNotEmpty()) {
                                                rawActiveCues = cachedAutoTranslatedCues
                                            } else {
                                                coroutineScope.launch {
                                                    val srtContent = downloadSubtitleText(sub)
                                                    rawActiveCues = parseSrtContent(srtContent)
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSubtitleDialog = false }) {
                        Text("Zatvori", color = Color.Red)
                    }
                }
            )
        }
    }
}

private suspend fun fetchArchiveVideoFps(archiveId: String): Float = withContext(Dispatchers.IO) {
    if (archiveId.isBlank()) return@withContext 0f
    try {
        val metaJson = JSONObject(URL("https://archive.org/metadata/$archiveId").readText())
        val files = metaJson.optJSONArray("files") ?: return@withContext 0f
        var bestFps = 0f
        var bestSize = 0L
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            val name = f.optString("name").lowercase()
            if (!name.endsWith(".mp4") && !name.endsWith(".mkv") && !name.endsWith(".avi")) continue
            val fps = f.optString("fps").toFloatOrNull() ?: 0f
            val size = f.optString("size").toLongOrNull() ?: 0L
            if (fps in 15f..120f && size >= bestSize) {
                bestSize = size
                bestFps = fps
            }
        }
        bestFps
    } catch (_: Exception) {
        0f
    }
}

private fun extractFpsFromText(text: String): Float {
    if (text.isBlank()) return 0f
    val bracketMatch = Regex("\\[(\\d{2}(?:\\.\\d+)?)\\s*fps\\]", RegexOption.IGNORE_CASE).find(text)
    if (bracketMatch != null) {
        val v = bracketMatch.groupValues[1].toFloatOrNull() ?: 0f
        if (v in 15f..120f) return v
    }
    val lower = text.lowercase()
    return when {
        Regex("23[.,]976|23[.,]98").containsMatchIn(lower) -> 23.976f
        Regex("\\b24(?:[.,]000?)?\\s*fps\\b|\\b24p\\b").containsMatchIn(lower) -> 24.0f
        Regex("\\b25(?:[.,]000?)?\\s*fps\\b|\\b25p\\b|\\bpal\\b").containsMatchIn(lower) -> 25.0f
        Regex("29[.,]97(?:0)?|\\bntsc\\b").containsMatchIn(lower) -> 29.97f
        Regex("\\b30(?:[.,]000?)?\\s*fps\\b").containsMatchIn(lower) -> 30.0f
        Regex("\\b50(?:[.,]000?)?\\s*fps\\b").containsMatchIn(lower) -> 50.0f
        Regex("59[.,]94").containsMatchIn(lower) -> 59.94f
        Regex("\\b60(?:[.,]000?)?\\s*fps\\b").containsMatchIn(lower) -> 60.0f
        else -> 0f
    }
}

private fun normalizeRuntimeFps(fps: Float): Float {
    if (fps <= 0f) return 0f
    return when {
        abs(fps - 23.976f) < 0.15f || abs(fps - 29.97f) < 0.15f || abs(fps - 59.94f) < 0.2f -> 23.976f
        abs(fps - 24.0f) < 0.15f || abs(fps - 30.0f) < 0.15f || abs(fps - 60.0f) < 0.2f -> 24.0f
        abs(fps - 25.0f) < 0.15f || abs(fps - 50.0f) < 0.2f -> 25.0f
        else -> fps
    }
}

private fun formatFpsShort(fps: Float): String {
    if (fps <= 0f) return ""
    return when {
        abs(fps - 23.976f) < 0.05f -> "23.98"
        abs(fps - 29.97f) < 0.05f -> "29.97"
        abs(fps % 1f) < 0.05f -> fps.toInt().toString()
        else -> String.format(Locale.US, "%.2f", fps)
    }
}

private fun convertCuesForVideoFps(
    cues: List<SrtCue>,
    subFps: Float,
    videoFps: Float,
    videoDurationMs: Long
): Pair<List<SrtCue>, String> {
    if (cues.isEmpty()) return emptyList<SrtCue>() to ""

    val normSub = normalizeRuntimeFps(subFps)
    val normVid = normalizeRuntimeFps(videoFps)

    if (normSub > 0f && normVid > 0f && abs(normSub - normVid) >= 0.5f) {
        val factor = normSub.toDouble() / normVid.toDouble()
        val scaled = cues.map { cue ->
            cue.copy(
                startMs = (cue.startMs * factor).roundToLong(),
                endMs = (cue.endMs * factor).roundToLong()
            )
        }
        return scaled to "${formatFpsShort(normSub)} → ${formatFpsShort(normVid)} fps"
    }

    // Rezervna detekcija: 23.98 fps titl bez oznake FPS-a koji prelazi trajanje 25 fps videa
    if (normSub == 0f && normVid == 25.0f && videoDurationMs > 600_000L) {
        val lastEnd = cues.last().endMs
        if (lastEnd > (videoDurationMs * 1.025).toLong() && lastEnd < (videoDurationMs * 1.055).toLong()) {
            val factor = 23.976 / 25.0
            val scaled = cues.map { cue ->
                cue.copy(
                    startMs = (cue.startMs * factor).roundToLong(),
                    endMs = (cue.endMs * factor).roundToLong()
                )
            }
            return scaled to "23.98 → 25 fps"
        }
    }

    return cues to ""
}

private suspend fun fetchSubtitleFromSupabase(archiveId: String): Pair<String, List<SrtCue>>? = withContext(Dispatchers.IO) {
    try {
        val encodedId = URLEncoder.encode(archiveId, "UTF-8")
        val url = URL("$SUBTITLES_SUPABASE_URL/movie_subtitles?select=source_filename,cues_json&archive_id=eq.$encodedId&limit=1")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("apikey", SUBTITLES_SUPABASE_KEY)
        conn.setRequestProperty("Accept", "application/json")
        conn.connectTimeout = 6000
        conn.readTimeout = 8000
        if (conn.responseCode == 200) {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            if (arr.length() > 0) {
                val obj = arr.getJSONObject(0)
                val sourceName = obj.optString("source_filename", "HR").ifBlank { "HR" }
                val cuesArr = obj.optJSONArray("cues_json")
                    ?: obj.optString("cues_json").takeIf { it.startsWith("[") }?.let { JSONArray(it) }
                if (cuesArr != null) {
                    val cues = jsonArrayToCues(cuesArr)
                    if (cues.isNotEmpty()) {
                        return@withContext sourceName to cues
                    }
                }
            }
        }
        null
    } catch (_: Exception) {
        null
    }
}

private suspend fun saveSubtitleToSupabase(
    archiveId: String,
    sourceFilename: String,
    cues: List<SrtCue>
) = withContext(Dispatchers.IO) {
    if (archiveId.isBlank() || cues.isEmpty()) return@withContext
    try {
        val payload = JSONObject().apply {
            put("archive_id", archiveId)
            put("source_filename", sourceFilename.take(180))
            put("cues_json", cuesToJsonArray(cues))
        }
        val url = URL("$SUBTITLES_SUPABASE_URL/movie_subtitles?on_conflict=archive_id")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("apikey", SUBTITLES_SUPABASE_KEY)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        conn.responseCode
    } catch (_: Exception) {
    }
}

private fun cuesToJsonArray(cues: List<SrtCue>): JSONArray {
    val arr = JSONArray()
    for (c in cues) {
        val obj = JSONObject()
        obj.put("s", c.startMs)
        obj.put("e", c.endMs)
        obj.put("t", c.text)
        arr.put(obj)
    }
    return arr
}

private fun jsonArrayToCues(arr: JSONArray): List<SrtCue> {
    val list = ArrayList<SrtCue>(arr.length())
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val text = obj.optString("t")
        if (text.isNotEmpty()) {
            list.add(
                SrtCue(
                    startMs = obj.optLong("s"),
                    endMs = obj.optLong("e"),
                    text = text
                )
            )
        }
    }
    return list
}

private fun readCachedTranslatedCues(cacheFile: File): Pair<List<SrtCue>, Float>? {
    return try {
        if (!cacheFile.exists() || cacheFile.length() < 10L) return null
        val raw = cacheFile.readText(Charsets.UTF_8).trim()
        if (raw.startsWith("{")) {
            val root = JSONObject(raw)
            val fps = root.optDouble("fps", 0.0).toFloat()
            val arr = root.optJSONArray("cues") ?: return null
            jsonArrayToCues(arr) to fps
        } else {
            jsonArrayToCues(JSONArray(raw)) to 0f
        }
    } catch (_: Exception) {
        null
    }
}

private fun writeCachedTranslatedCues(cacheFile: File, cues: List<SrtCue>, fps: Float = 0f) {
    try {
        val root = JSONObject().apply {
            put("fps", fps.toDouble())
            put("cues", cuesToJsonArray(cues))
        }
        cacheFile.writeText(root.toString(), Charsets.UTF_8)
    } catch (_: Exception) {
    }
}

private fun pickBestSubtitleOption(
    subs: List<PlayerSubtitleOption>,
    targetVideoFps: Float
): PlayerSubtitleOption {
    val normVideoFps = normalizeRuntimeFps(targetVideoFps)

    fun languageTier(sub: PlayerSubtitleOption): Int {
        val lang = sub.language.uppercase()
        val fname = sub.fileName.lowercase()
        return when {
            lang == "HRV" || Regex("(?i)\\b(hr|hrv|cro|croatian)\\b").containsMatchIn(fname) -> 0
            lang == "SRP" || lang == "BOS" || Regex("(?i)\\b(sr|srp|serbian|bos|bosnian)\\b").containsMatchIn(fname) -> 1
            lang == "ARCHIVE" -> 2
            lang == "ENG" -> 3
            else -> 4
        }
    }

    fun fpsMatchScore(sub: PlayerSubtitleOption): Int {
        val normSubFps = normalizeRuntimeFps(sub.fps)
        if (normVideoFps <= 0f) {
            return if (normSubFps > 0f) 500 else 300
        }
        if (normSubFps <= 0f) return 400
        return when {
            abs(normSubFps - normVideoFps) < 0.15f -> 1000
            abs(normSubFps - normVideoFps) < 0.5f -> 850
            else -> 200
        }
    }

    return subs.minWithOrNull(
        compareBy<PlayerSubtitleOption> { languageTier(it) }
            .thenByDescending { fpsMatchScore(it) }
            .thenByDescending { it.downloads }
    ) ?: subs.first()
}

private fun isCroatianOrRegionalSubtitle(sub: PlayerSubtitleOption, cues: List<SrtCue>): Boolean {
    val lang = sub.language.uppercase()
    if (lang in setOf("HRV", "SRP", "BOS", "HR", "SR", "BS")) return true
    val fname = sub.fileName.lowercase()
    if (Regex("(?i)\\b(hr|hrv|cro|croatian|sr|srp|serbian|bos|bosnian)\\b").containsMatchIn(fname)) {
        return true
    }
    val sample = cues.take(20).joinToString(" ") { it.text }.lowercase()
    if (Regex("[čćžšđ]").containsMatchIn(sample)) return true
    val regionalMarkers = listOf(" sam ", " nije ", " kako ", " ovdje ", " zašto ", " dobro ", " molim ", " hvala ")
    return regionalMarkers.count { sample.contains(it) } >= 2
}

private suspend fun translateCuesToCroatian(
    cues: List<SrtCue>,
    cacheFile: File,
    sourceFps: Float = 0f
): List<SrtCue> = withContext(Dispatchers.IO) {
    if (cues.isEmpty()) return@withContext emptyList()
    val translatedTexts = MutableList(cues.size) { cues[it].text }
    val batches = cues.indices.chunked(18)
    val semaphore = Semaphore(5)

    coroutineScope {
        batches.map { indices ->
            async {
                semaphore.withPermit {
                    val payload = indices.joinToString("\n") { idx ->
                        val cleanLine = cues[idx].text.replace("\n", " ").trim()
                        "[[$idx]] $cleanLine"
                    }
                    val translatedBatch = requestGoogleTranslateBatch(payload)
                    if (translatedBatch.isNotBlank()) {
                        val markerRegex = Regex("\\[\\s*\\[\\s*(\\d+)\\s*\\]\\s*\\]\\s*([\\s\\S]*?)(?=\\[\\s*\\[\\s*\\d+\\s*\\]\\s*\\]|$)")
                        for (match in markerRegex.findAll(translatedBatch)) {
                            val idx = match.groupValues[1].toIntOrNull()
                            val text = match.groupValues[2].trim()
                            if (idx != null && idx in translatedTexts.indices && text.isNotEmpty()) {
                                translatedTexts[idx] = text
                            }
                        }
                    }
                }
            }
        }.awaitAll()
    }

    val result = cues.mapIndexed { index, cue ->
        cue.copy(text = translatedTexts[index])
    }

    writeCachedTranslatedCues(cacheFile, result, sourceFps)
    result
}

private fun requestGoogleTranslateBatch(text: String): String {
    return try {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=hr&dt=t&q=$encoded"
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.connectTimeout = 7000
        conn.readTimeout = 8000
        if (conn.responseCode != 200) return ""
        val response = conn.inputStream.bufferedReader().use { it.readText() }
        val jsonArray = JSONArray(response)
        val parts = jsonArray.getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONArray(i)?.optString(0) ?: ""
            sb.append(part)
        }
        sb.toString()
    } catch (_: Exception) {
        ""
    }
}

private suspend fun findOriginalMultiAudioUrl(iaMp4Url: String): String? = withContext(Dispatchers.IO) {
    try {
        val afterDownload = iaMp4Url.substringAfter("archive.org/download/", "")
        val itemId = afterDownload.substringBefore("/")
        val encodedFile = afterDownload.substringAfter("/", "")
        if (itemId.isBlank() || encodedFile.isBlank()) return@withContext null

        val decodedFile = URLDecoder.decode(encodedFile, "UTF-8")
        val stem = decodedFile.replace(Regex("(?i)\\.ia\\.mp4$"), "")
        if (stem.isBlank() || stem == decodedFile) return@withContext null

        val metaJson = JSONObject(URL("https://archive.org/metadata/$itemId").readText())
        if (metaJson.optBoolean("is_dark", false) || metaJson.optBoolean("is_restricted", false)) {
            return@withContext null
        }
        val files = metaJson.optJSONArray("files") ?: return@withContext null

        var bestOriginalUrl: String? = null
        var bestPriority = Int.MAX_VALUE

        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            val name = f.optString("name")
            val lower = name.lowercase()
            if (lower.endsWith(".ia.mp4") || f.optString("source") == "derivative") continue
            if (!lower.endsWith(".mkv") && !lower.endsWith(".mp4")) continue

            val nameWithoutExt = name.substringBeforeLast(".")
            if (nameWithoutExt.equals(stem, ignoreCase = true) ||
                nameWithoutExt.substringAfterLast("/").equals(stem.substringAfterLast("/"), ignoreCase = true)
            ) {
                val priority = if (lower.endsWith(".mp4")) 0 else 1
                if (priority < bestPriority) {
                    bestPriority = priority
                    val encoded = name.split("/").joinToString("/") { part ->
                        URLEncoder.encode(part, "UTF-8").replace("+", "%20")
                    }
                    bestOriginalUrl = "https://archive.org/download/$itemId/$encoded"
                }
            }
        }
        bestOriginalUrl
    } catch (_: Exception) {
        null
    }
}

private suspend fun findCompatibleMp4Fallback(failedUrl: String, titleHint: String): String? = withContext(Dispatchers.IO) {
    try {
        val afterDownload = failedUrl.substringAfter("archive.org/download/", "")
        val itemId = afterDownload.substringBefore("/")
        if (itemId.isBlank()) return@withContext null

        val metaJson = JSONObject(URL("https://archive.org/metadata/$itemId").readText())
        if (metaJson.optBoolean("is_dark", false) || metaJson.optBoolean("is_restricted", false)) {
            return@withContext null
        }
        val files = metaJson.optJSONArray("files") ?: return@withContext null

        val failedStem = failedUrl.substringAfterLast("/").substringBeforeLast(".")
            .replace("%20", " ").lowercase()
        val cleanHint = titleHint.replace(Regex("\\(\\d{4}\\)"), "").trim().lowercase()

        var bestMp4Url: String? = null
        var bestScore = -1L

        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            val name = f.optString("name")
            if (!name.endsWith(".mp4", ignoreCase = true)) continue
            val size = f.optString("size").toLongOrNull() ?: 0L
            if (size < 20_000_000L) continue

            val lower = name.lowercase()
            var score = size / 1_000_000L
            if (failedStem.isNotBlank() && lower.contains(failedStem.take(18))) score += 10_000L
            if (cleanHint.length >= 4 && lower.contains(cleanHint)) score += 5_000L

            if (score > bestScore) {
                bestScore = score
                val encoded = name.split("/").joinToString("/") { part ->
                    URLEncoder.encode(part, "UTF-8").replace("+", "%20")
                }
                bestMp4Url = "https://archive.org/download/$itemId/$encoded"
            }
        }
        bestMp4Url
    } catch (_: Exception) {
        null
    }
}

private fun formatAudioTrackLabel(languageCode: String?, rawLabel: String?, trackNumber: Int): String {
    val code = languageCode?.trim()?.lowercase() ?: ""
    val friendlyLang = when (code) {
        "en", "eng" -> "Engleski"
        "hr", "hrv" -> "Hrvatski"
        "sr", "srp" -> "Srpski"
        "bs", "bos" -> "Bosanski"
        "pt", "por" -> "Portugalski"
        "es", "spa" -> "Španjolski"
        "fr", "fra", "fre" -> "Francuski"
        "de", "deu", "ger" -> "Njemački"
        "it", "ita" -> "Italijanski"
        "ru", "rus" -> "Ruski"
        "ja", "jpn" -> "Japanski"
        "ko", "kor" -> "Korejski"
        "", "und" -> "Nepoznati jezik"
        else -> Locale(code).getDisplayLanguage(Locale("hr")).replaceFirstChar { it.uppercase() }
    }
    val extra = rawLabel?.trim()?.takeIf { it.isNotBlank() && !it.equals(friendlyLang, ignoreCase = true) }
    return if (extra != null) {
        "Traka $trackNumber: $friendlyLang ($extra)"
    } else {
        "Traka $trackNumber: $friendlyLang"
    }
}

@Composable
private fun PlayerChipButton(
    label: String,
    isHighlighted: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .background(
                color = when {
                    isFocused -> Color.Red
                    isHighlighted -> Color(0xFFB71C1C)
                    else -> Color.Black.copy(alpha = 0.7f)
                },
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = if (isFocused) 3.dp else 1.5.dp,
                color = if (isFocused) Color.White else Color.Gray,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun PlayerFocusableIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    sizeDp: Int = 46,
    isPrimary: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .background(
                color = when {
                    isPrimary -> Color.White
                    isFocused -> Color.Red
                    else -> Color.Black.copy(alpha = 0.55f)
                },
                shape = CircleShape
            )
            .border(
                width = if (isFocused) 3.5.dp else 1.5.dp,
                color = if (isFocused) Color.White else Color.Gray.copy(alpha = 0.7f),
                shape = CircleShape
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun DialogFocusableRow(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = when {
                    isFocused -> Color.Red
                    isSelected -> Color(0xFF2E2E2E)
                    else -> Color(0xFF222222)
                },
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = if (isFocused) 2.5.dp else 1.dp,
                color = if (isFocused) Color.White else Color.DarkGray,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            color = Color.White,
            fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Normal,
            fontSize = 14.sp
        )
    }
}

private suspend fun searchAllSubtitles(
    identifier: String,
    movieTitle: String,
    movieYear: String
): List<PlayerSubtitleOption> = withContext(Dispatchers.IO) {
    val results = mutableListOf<PlayerSubtitleOption>()

    try {
        val metaJson = JSONObject(URL("https://archive.org/metadata/$identifier").readText())
        val files = metaJson.optJSONArray("files")
        if (files != null) {
            for (i in 0 until files.length()) {
                val f = files.optJSONObject(i) ?: continue
                val name = f.optString("name")
                if (name.endsWith(".srt", ignoreCase = true)) {
                    val encoded = name.split("/").joinToString("/") { part ->
                        URLEncoder.encode(part, "UTF-8").replace("+", "%20")
                    }
                    val shortName = name.substringAfterLast("/")
                    results.add(
                        PlayerSubtitleOption(
                            fileName = shortName,
                            language = "Archive",
                            downloadUrl = "https://archive.org/download/$identifier/$encoded",
                            isGzipped = false,
                            fps = extractFpsFromText(shortName),
                            downloads = 500
                        )
                    )
                }
            }
        }
    } catch (_: Exception) {
    }

    try {
        val cleanTitle = movieTitle
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("\\b(19\\d{2}|20\\d{2})\\b"), "")
            .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()

        val encodedQuery = URLEncoder.encode(cleanTitle, "UTF-8")
        val langs = listOf("hrv", "srp", "bos", "eng")
        for (lang in langs) {
            try {
                val apiUrl = "https://rest.opensubtitles.org/search/query-$encodedQuery/sublanguageid-$lang"
                val conn = URL(apiUrl).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "TemporaryUserAgent")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                if (conn.responseCode == 200) {
                    val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                    for (i in 0 until arr.length().coerceAtMost(8)) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val subFile = obj.optString("SubFileName")
                        val dlLink = obj.optString("SubDownloadLink")
                        val apiFps = obj.optString("MovieFPS").toFloatOrNull()?.takeIf { it in 15f..120f } ?: 0f
                        val resolvedFps = if (apiFps > 0f) apiFps else extractFpsFromText(subFile)
                        val dlCount = obj.optString("SubDownloadsCnt").toIntOrNull() ?: 0
                        if (subFile.isNotBlank() && dlLink.isNotBlank()) {
                            results.add(
                                PlayerSubtitleOption(
                                    fileName = subFile,
                                    language = lang.uppercase(),
                                    downloadUrl = dlLink,
                                    isGzipped = dlLink.endsWith(".gz", ignoreCase = true),
                                    fps = resolvedFps,
                                    downloads = dlCount
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    } catch (_: Exception) {
    }

    results.distinctBy { it.downloadUrl }
}

private suspend fun downloadSubtitleText(sub: PlayerSubtitleOption): String = withContext(Dispatchers.IO) {
    try {
        val conn = URL(sub.downloadUrl).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "TemporaryUserAgent")
        conn.connectTimeout = 7000
        conn.readTimeout = 7000
        val bytes = conn.inputStream.use { it.readBytes() }

        val rawText = if (sub.isGzipped) {
            GZIPInputStream(ByteArrayInputStream(bytes)).readBytes()
        } else {
            bytes
        }

        var text = String(rawText, Charsets.UTF_8)
        if (text.contains('\uFFFD')) {
            text = String(rawText, Charset.forName("windows-1250"))
        }
        text
    } catch (e: Exception) {
        e.printStackTrace()
        ""
    }
}

private fun parseSrtContent(rawSrt: String): List<SrtCue> {
    if (rawSrt.isBlank()) return emptyList()
    val normalized = rawSrt.replace("\r\n", "\n").replace("\r", "\n")
    val blocks = normalized.split(Regex("\n\\s*\n"))
    val cues = mutableListOf<SrtCue>()
    val timeRegex = Regex("(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})")

    for (block in blocks) {
        val lines = block.trim().lines()
        if (lines.size < 2) continue
        val timeLineIndex = lines.indexOfFirst { it.contains("-->") }
        if (timeLineIndex == -1) continue

        val match = timeRegex.find(lines[timeLineIndex]) ?: continue
        val startMs = toMillis(
            match.groupValues[1].toLong(),
            match.groupValues[2].toLong(),
            match.groupValues[3].toLong(),
            match.groupValues[4].toLong()
        )
        val endMs = toMillis(
            match.groupValues[5].toLong(),
            match.groupValues[6].toLong(),
            match.groupValues[7].toLong(),
            match.groupValues[8].toLong()
        )
        val text = lines.drop(timeLineIndex + 1)
            .joinToString("\n")
            .replace(Regex("<[^>]*>"), "")
            .trim()

        if (text.isNotEmpty()) {
            cues.add(SrtCue(startMs, endMs, text))
        }
    }
    return cues
}

private fun toMillis(h: Long, m: Long, s: Long, ms: Long): Long {
    return h * 3600000L + m * 60000L + s * 1000L + ms
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSeconds = ms / 1000
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
    else String.format("%02d:%02d", minutes, seconds)
}
