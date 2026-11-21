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

data class PlayerVersionOption(
    val label: String,
    val url: String,
    val languageRank: Int = 1, // 0 = Engleski / Multi-audio, 1 = Standardno, 2 = Strana sinkronizacija
    val sizeBytes: Long = 0L,
    val height: Int = 0
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
    var resolvedMovieYear by remember { mutableStateOf(movieYear) }

    var activeStreamUrl by remember { mutableStateOf("") }
    var mp4FallbackUrlOverride by remember { mutableStateOf<String?>(null) }
    var triedMp4Fallback by remember { mutableStateOf(false) }
    var playbackErrorMessage by remember { mutableStateOf("") }

    var quickHudMessage by remember { mutableStateOf("") }
    var quickHudTick by remember { mutableIntStateOf(0) }
    var isContinuousSeeking by remember { mutableStateOf(false) }

    var availableVersions by remember { mutableStateOf<List<PlayerVersionOption>>(emptyList()) }
    var selectedVersion by remember { mutableStateOf<PlayerVersionOption?>(null) }
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

    val isEpisodicContent = remember(availableVersions) {
        availableVersions.any {
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
                30_000, // minBufferMs: 30s unaprijed
                90_000, // maxBufferMs: 90s unaprijed za glatku reprodukciju velikih HD datoteka
                3_500,  // bufferForPlaybackMs: 3.5s prije prvog kadra
                7_500   // bufferForPlaybackAfterRebufferMs: 7.5s zalihe nakon zastoja
            )
            .setBackBuffer(15_000, true)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(
                mapOf(
                    "Accept-Encoding" to "identity",
                    "Connection" to "keep-alive"
                )
            )

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

        availableVersions = emptyList()
        selectedVersion = null

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
        } else if (directVideoUrl.contains("archive.org/download/")) {
            directVideoUrl.substringAfter("archive.org/download/").substringBefore("/").ifBlank {
                identifier.substringBefore(",tmdb-").trim()
            }
        } else {
            identifier.substringBefore(",tmdb-").trim()
        }
        val subtitleDbKey = buildSubtitleDbKey(identifier, directVideoUrl)
        val safeCacheSlug = subtitleDbKey.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(90)
        val localCacheFile = File(context.cacheDir, "sub_hr_v4_$safeCacheSlug.json")

        val sbMeta = SupabaseRepository.getCatalogItem(identifier)
        val titleEmbeddedYear = Regex("\\(\\s*(19\\d{2}|20\\d{2})\\s*\\)").find(movieTitle)?.groupValues?.get(1).orEmpty()
        val effectiveYear = movieYear.ifBlank {
            sbMeta?.releaseYear?.takeIf { it > 0 }?.toString() ?: titleEmbeddedYear
        }
        resolvedMovieYear = effectiveYear

        // 1. Pokreni izvorni (najveći/najkvalitetniji) video stream odmah
        val initialUrl = if (directVideoUrl.isNotEmpty()) {
            directVideoUrl
        } else {
            val moduleQualities = ArchiveMetadataModule.fetchAvailableQualities(cleanArchiveId)
            val mapped = moduleQualities.map {
                PlayerVersionOption(
                    label = it.label,
                    url = it.url,
                    languageRank = classifyVersionLanguageRank("${it.label} ${it.url}")
                )
            }.sortedBy { it.languageRank }
            availableVersions = mapped
            val bestQuality = mapped.firstOrNull()
            selectedVersion = bestQuality
            bestQuality?.url ?: fallbackVideoUrl
        }

        val upgradedOriginalUrl = if (initialUrl.lowercase().contains(".ia.mp4")) {
            findOriginalMultiAudioUrl(initialUrl)
        } else {
            null
        }

        val streamToStart = if (upgradedOriginalUrl != null && upgradedOriginalUrl != initialUrl) {
            mp4FallbackUrlOverride = initialUrl
            upgradedOriginalUrl
        } else {
            initialUrl
        }
        activeStreamUrl = streamToStart
        exoPlayer.setMediaItem(MediaItem.fromUri(streamToStart), 0L)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true

        // 2. U pozadini učitaj sve verzije za ⚙ Verzije (automatski prebaci samo ako je trenutni strani audio, a postoji ENG)
        launch {
            val concertMode = isConcertTitleOrId(identifier, movieTitle)
            val expectedRuntimeMins = sbMeta?.tmdbRuntime?.takeIf { it > 0 } ?: 0
            val discoveredVersions = loadAllMovieVersions(
                cleanArchiveId = cleanArchiveId,
                currentUrl = activeStreamUrl,
                movieTitle = movieTitle,
                movieYear = effectiveYear,
                expectedRuntimeMins = expectedRuntimeMins,
                searchAlternativeItems = !concertMode && !identifier.startsWith("tmdb-tv-")
            )

            if (discoveredVersions.isNotEmpty()) {
                availableVersions = discoveredVersions
                val currentMatched = discoveredVersions.find {
                    urlsMatchArchiveFile(it.url, activeStreamUrl)
                }
                val bestOption = discoveredVersions.first()
                val currentRank = currentMatched?.languageRank ?: classifyVersionLanguageRank("$cleanArchiveId $activeStreamUrl")

                if (currentRank >= 2 && bestOption.languageRank < currentRank && !urlsMatchArchiveFile(bestOption.url, activeStreamUrl)) {
                    val resumePos = exoPlayer.currentPosition.coerceAtLeast(0L)
                    selectedVersion = bestOption
                    activeStreamUrl = bestOption.url
                    detectedVideoFps = 0f
                    exoPlayer.setMediaItem(MediaItem.fromUri(bestOption.url), resumePos)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                    quickHudMessage = "🔊 Odabrana engleska verzija (ostale u ⚙ Verzije)"
                    quickHudTick++
                } else {
                    selectedVersion = currentMatched ?: bestOption
                }
            }
        }

        // 3. Provjera koncerata (samo Archive.org .srt) vs filmova/serija
        launch {
            val concertMode = isConcertTitleOrId(identifier, movieTitle)
            if (concertMode) {
                val archiveOnlySubs = searchAllSubtitles(
                    identifier = cleanArchiveId,
                    movieTitle = movieTitle,
                    movieYear = effectiveYear,
                    directVideoUrl = directVideoUrl,
                    archiveOnly = true
                )
                subtitleList = archiveOnlySubs
                if (archiveOnlySubs.isNotEmpty()) {
                    val bestSub = archiveOnlySubs.first()
                    val rawSrt = downloadSubtitleText(bestSub)
                    val initialCues = parseSrtContent(rawSrt)
                    if (initialCues.isNotEmpty()) {
                        activeSubtitleName = bestSub.fileName
                        activeSubtitleFps = bestSub.fps
                        rawActiveCues = initialCues
                        quickHudMessage = "💬 Titlovi: [Archive] ${bestSub.fileName}"
                        quickHudTick++
                    }
                }
                return@launch
            }

            val archiveFpsHint = fetchArchiveVideoFps(cleanArchiveId, directVideoUrl)
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
                quickHudMessage = "💬 Titlovi učitani iz predmemorije"
                quickHudTick++

                launch {
                    if (fetchSubtitleFromSupabase(subtitleDbKey) == null) {
                        val storedName = if (cachedFps > 0f) {
                            "$movieTitle [${formatFpsShort(cachedFps)}fps]"
                        } else {
                            movieTitle
                        }
                        saveSubtitleToSupabase(subtitleDbKey, storedName, cachedCues)
                    }
                }

                val extraSubs = searchAllSubtitles(cleanArchiveId, movieTitle, effectiveYear, directVideoUrl = directVideoUrl)
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

                val extraSubs = searchAllSubtitles(cleanArchiveId, movieTitle, effectiveYear, directVideoUrl = directVideoUrl)
                subtitleList = listOf(dbOption) + extraSubs
                return@launch
            }

            isLoadingSubtitles = true
            val foundSubs = searchAllSubtitles(cleanArchiveId, movieTitle, effectiveYear, directVideoUrl = directVideoUrl)
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
                quickHudMessage = "🌐 Prevodim engleski titl na hrvatski..."
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
                    val savedInCloud = saveSubtitleToSupabase(subtitleDbKey, storedSourceName, translatedCues)

                    if (activeSubtitleName == bestSub.fileName) {
                        activeSubtitleName = autoOptionName
                        activeSubtitleFps = bestSub.fps
                        rawActiveCues = translatedCues
                        quickHudMessage = if (savedInCloud) {
                            "✅ Prevedeno na hrvatski i spremljeno u bazu"
                        } else {
                            "✅ Prevedeno na hrvatski"
                        }
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

                        if (availableVersions.size > 1) {
                            PlayerChipButton(
                                label = if (isEpisodicContent) "📺 Epizode" else "⚙ Verzije (${availableVersions.size})",
                                isHighlighted = availableVersions.size > 1 && !isEpisodicContent,
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
                                        val cleanId = if (identifier.startsWith("http")) {
                                            identifier.substringAfter("archive.org/download/", "").substringBefore("/").ifBlank { identifier }
                                        } else {
                                            identifier.substringBefore(",tmdb-").trim()
                                        }
                                        val archiveOnly = isConcertTitleOrId(identifier, movieTitle)
                                        subtitleList = searchAllSubtitles(
                                            identifier = cleanId,
                                            movieTitle = movieTitle,
                                            movieYear = resolvedMovieYear,
                                            directVideoUrl = directVideoUrl,
                                            archiveOnly = archiveOnly
                                        )
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
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 340.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(availableVersions) { version ->
                            val isSelected = version == selectedVersion || urlsMatchArchiveFile(version.url, activeStreamUrl)
                            DialogFocusableRow(
                                label = version.label,
                                isSelected = isSelected,
                                onClick = {
                                    val savedPos = exoPlayer.currentPosition
                                    selectedVersion = version
                                    activeStreamUrl = version.url
                                    detectedVideoFps = 0f
                                    exoPlayer.setMediaItem(MediaItem.fromUri(version.url), savedPos)
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
                                                val dbKey = buildSubtitleDbKey(identifier, directVideoUrl)
                                                val safeSlug = dbKey.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(90)
                                                val cacheFile = File(context.cacheDir, "sub_hr_v4_$safeSlug.json")
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
                                                    val savedInCloud = saveSubtitleToSupabase(dbKey, storedSourceName, translated)
                                                    quickHudMessage = if (savedInCloud) {
                                                        "✅ Prevedeno i spremljeno u bazu"
                                                    } else {
                                                        "✅ Prevedeno na hrvatski"
                                                    }
                                                    quickHudTick++
                                                }
                                            }
                                        }
                                    )
                                }
                            }

                            if (subtitleList.isEmpty()) {
                                item {
                                    Text("Nema pronađenih titlova za ovaj zapis.", color = Color.Gray, fontSize = 14.sp)
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

private fun urlsMatchArchiveFile(urlA: String, urlB: String): Boolean {
    if (urlA.isBlank() || urlB.isBlank()) return false
    if (urlA.equals(urlB, ignoreCase = true)) return true
    return try {
        val decA = URLDecoder.decode(urlA.substringAfter("archive.org/download/", urlA), "UTF-8").lowercase(Locale.ROOT)
        val decB = URLDecoder.decode(urlB.substringAfter("archive.org/download/", urlB), "UTF-8").lowercase(Locale.ROOT)
        decA == decB
    } catch (_: Exception) {
        false
    }
}

private fun classifyVersionLanguageRank(text: String): Int {
    val lower = text.lowercase(Locale.ROOT)
    val hasEnglishOrDual = Regex("\\b(eng|english|dual[\\s._-]?audio|multi[\\s._-]?audio|original)\\b").containsMatchIn(lower)
    if (hasEnglishOrDual) return 0

    val isForeignDub = Regex(
        "\\b(latino|audio[\\s._-]?latino|castellano|espanol|español|spanish|dublado|dublagem|doblado|doblaje|" +
            "portugues|portuguese|truefrench|french[\\s._-]?dub|german[\\s._-]?dub|italian[\\s._-]?dub|" +
            "russian[\\s._-]?dub|ozvuchka|hindi|arabic)\\b"
    ).containsMatchIn(lower)
    return if (isForeignDub) 2 else 1
}

private fun formatFileSizeHuman(bytes: Long): String {
    if (bytes <= 0L) return ""
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.0f MB", mb)
    }
}

private fun buildVersionDisplayLabel(
    filename: String,
    width: Int,
    height: Int,
    sizeBytes: Long,
    langRank: Int,
    extraTag: String = ""
): String {
    val cleanName = filename.substringAfterLast("/").substringBeforeLast(".")
        .replace(Regex("[._]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(42)
    val resTag = when {
        height >= 2000 || width >= 3800 -> "4K"
        height >= 1000 || width >= 1900 -> "1080p"
        height >= 700 || width >= 1260 -> "720p"
        height >= 460 -> "480p"
        else -> ""
    }
    val sizeTag = formatFileSizeHuman(sizeBytes)
    val langTag = when (langRank) {
        0 -> "ENG"
        2 -> "STRANI AUDIO"
        else -> ""
    }
    val metaParts = listOf(langTag, resTag, sizeTag, extraTag).filter { it.isNotBlank() }
    return if (metaParts.isNotEmpty()) {
        "$cleanName (${metaParts.joinToString(" • ")})"
    } else {
        cleanName
    }
}

private suspend fun loadAllMovieVersions(
    cleanArchiveId: String,
    currentUrl: String,
    movieTitle: String,
    movieYear: String,
    expectedRuntimeMins: Int,
    searchAlternativeItems: Boolean
): List<PlayerVersionOption> = withContext(Dispatchers.IO) {
    val collected = mutableListOf<PlayerVersionOption>()
    val seenUrls = mutableSetOf<String>()
    var detectedPrimaryRuntimeSecs = expectedRuntimeMins * 60

    fun addUniqueVersion(opt: PlayerVersionOption) {
        val normKey = try {
            URLDecoder.decode(opt.url.substringAfter("archive.org/download/", opt.url), "UTF-8").lowercase(Locale.ROOT)
        } catch (_: Exception) {
            opt.url.lowercase(Locale.ROOT)
        }
        if (seenUrls.add(normKey)) {
            collected.add(opt)
        }
    }

    // 1. Sve video datoteke unutar trenutnog Archive.org zapisa
    if (cleanArchiveId.isNotBlank() && !cleanArchiveId.startsWith("http")) {
        try {
            val metaJson = JSONObject(URL("https://archive.org/metadata/$cleanArchiveId").readText())
            val md = metaJson.optJSONObject("metadata")
            val itemMetaTitle = md?.opt("title")?.toString().orEmpty()
            val itemLangBlob = "$cleanArchiveId $itemMetaTitle ${md?.opt("language")?.toString().orEmpty()}"

            val files = metaJson.optJSONArray("files")
            if (files != null) {
                for (i in 0 until files.length()) {
                    val f = files.optJSONObject(i) ?: continue
                    val name = f.optString("name")
                    val lower = name.lowercase(Locale.ROOT)
                    if (!lower.endsWith(".mp4") && !lower.endsWith(".mkv") && !lower.endsWith(".avi")) continue
                    if (Regex("(?i)(trailer|sample|teaser|preview|promo)").containsMatchIn(lower)) continue

                    val sizeBytes = f.optString("size").toLongOrNull() ?: 0L
                    if (sizeBytes < 25_000_000L) continue

                    val lenSecs = f.optString("length").toDoubleOrNull()?.toInt() ?: 0
                    if (detectedPrimaryRuntimeSecs <= 0 && lenSecs > 1800) {
                        detectedPrimaryRuntimeSecs = lenSecs
                    }

                    val width = f.optString("width").toDoubleOrNull()?.toInt() ?: 0
                    val height = f.optString("height").toDoubleOrNull()?.toInt() ?: 0
                    val isDeriv = lower.endsWith(".ia.mp4") || f.optString("source") == "derivative"
                    val langRank = classifyVersionLanguageRank("$itemLangBlob $name")
                    val encoded = name.split("/").joinToString("/") { part ->
                        URLEncoder.encode(part, "UTF-8").replace("+", "%20")
                    }
                    val streamUrl = "https://archive.org/download/$cleanArchiveId/$encoded"
                    val label = buildVersionDisplayLabel(
                        filename = name,
                        width = width,
                        height = height,
                        sizeBytes = sizeBytes,
                        langRank = langRank,
                        extraTag = if (isDeriv) "IA" else ""
                    )
                    addUniqueVersion(
                        PlayerVersionOption(
                            label = label,
                            url = streamUrl,
                            languageRank = langRank,
                            sizeBytes = sizeBytes,
                            height = height
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    // 2. Dodaj rezultate iz ArchiveMetadataModule ako ih već nema
    try {
        for (q in ArchiveMetadataModule.fetchAvailableQualities(cleanArchiveId)) {
            addUniqueVersion(
                PlayerVersionOption(
                    label = q.label,
                    url = q.url,
                    languageRank = classifyVersionLanguageRank("${q.label} ${q.url}")
                )
            )
        }
    } catch (_: Exception) {
    }

    // 3. Potraži sve dostupne Archive.org uploade istog filma kako bi gledatelj mogao birati između svih verzija
    if (searchAlternativeItems) {
        val cleanLocalized = cleanMovieTitleForSubtitleSearch(movieTitle)
        val translatedEn = translateTitleToEnglish(cleanLocalized)
        val searchTitle = translatedEn.ifBlank { cleanLocalized }
        val targetYear = movieYear.toIntOrNull() ?: 0

        if (searchTitle.length >= 3) {
            try {
                val asciiQuery = searchTitle.replace(Regex("[^a-zA-Z0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim()
                val yearClause = if (targetYear > 0) " AND year:[${targetYear - 1} TO ${targetYear + 1}]" else ""
                val q = "title:\"$asciiQuery\"$yearClause AND mediatype:movies AND -subject:trailer AND -subject:review"
                val encodedQ = URLEncoder.encode(q, "UTF-8")
                val searchUrl = "https://archive.org/advancedsearch.php?q=$encodedQ&fl[]=identifier&fl[]=title&fl[]=downloads&sort[]=downloads+desc&rows=12&output=json"
                val conn = URL(searchUrl).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.connectTimeout = 5000
                conn.readTimeout = 6000
                if (conn.responseCode == 200) {
                    val docs = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        .optJSONObject("response")
                        ?.optJSONArray("docs")
                    val strictWords = normalizeTitleWordsForMatch(searchTitle)

                    if (docs != null) {
                        for (i in 0 until docs.length()) {
                            val doc = docs.optJSONObject(i) ?: continue
                            val altId = doc.optString("identifier")
                            if (altId.isBlank() || altId == cleanArchiveId || altId.lowercase().startsWith("youtube-")) continue
                            val altTitle = doc.optString("title")
                            if (!titlesMatchStrictly(strictWords, cleanMovieTitleForSubtitleSearch(altTitle))) continue

                            val altMeta = JSONObject(URL("https://archive.org/metadata/$altId").readText())
                            if (altMeta.optBoolean("is_dark", false) || altMeta.optBoolean("is_restricted", false)) continue
                            val altMd = altMeta.optJSONObject("metadata")
                            val altBlob = "$altId $altTitle ${altMd?.opt("language")?.toString().orEmpty()}"
                            val altFiles = altMeta.optJSONArray("files") ?: continue

                            for (j in 0 until altFiles.length()) {
                                val af = altFiles.optJSONObject(j) ?: continue
                                val rawName = af.optString("name")
                                val afName = rawName.lowercase(Locale.ROOT)
                                if (!afName.endsWith(".mp4") && !afName.endsWith(".mkv")) continue
                                if (Regex("(?i)(trailer|sample|teaser|preview|promo)").containsMatchIn(afName)) continue
                                val afSize = af.optString("size").toLongOrNull() ?: 0L
                                if (afSize < 250_000_000L) continue
                                val afSecs = af.optString("length").toDoubleOrNull()?.toInt() ?: 0
                                if (detectedPrimaryRuntimeSecs > 1800 && afSecs > 600) {
                                    if (afSecs < (detectedPrimaryRuntimeSecs * 0.85).toInt() || afSecs > (detectedPrimaryRuntimeSecs * 1.18).toInt()) {
                                        continue
                                    }
                                }

                                val width = af.optString("width").toDoubleOrNull()?.toInt() ?: 0
                                val height = af.optString("height").toDoubleOrNull()?.toInt() ?: 0
                                val isDeriv = afName.endsWith(".ia.mp4") || af.optString("source") == "derivative"
                                val langRank = classifyVersionLanguageRank("$altBlob $rawName")
                                val encoded = rawName.split("/").joinToString("/") { part ->
                                    URLEncoder.encode(part, "UTF-8").replace("+", "%20")
                                }
                                val altStreamUrl = "https://archive.org/download/$altId/$encoded"
                                val label = buildVersionDisplayLabel(
                                    filename = rawName,
                                    width = width,
                                    height = height,
                                    sizeBytes = afSize,
                                    langRank = langRank,
                                    extraTag = if (isDeriv) "IA" else "Archive"
                                )
                                addUniqueVersion(
                                    PlayerVersionOption(
                                        label = label,
                                        url = altStreamUrl,
                                        languageRank = langRank,
                                        sizeBytes = afSize,
                                        height = height
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    if (currentUrl.isNotBlank() && collected.none { urlsMatchArchiveFile(it.url, currentUrl) }) {
        val rank = classifyVersionLanguageRank("$cleanArchiveId $currentUrl")
        addUniqueVersion(
            PlayerVersionOption(
                label = buildVersionDisplayLabel(currentUrl, 0, 0, 0L, rank, "Trenutna"),
                url = currentUrl,
                languageRank = rank
            )
        )
    }

    collected.sortedWith(
        compareBy<PlayerVersionOption> { it.languageRank }
            .thenByDescending { it.height }
            .thenByDescending { it.sizeBytes }
    )
}

private fun buildSubtitleDbKey(identifier: String, directVideoUrl: String): String {
    if (directVideoUrl.contains("archive.org/download/")) {
        return directVideoUrl.substringAfter("archive.org/download/").substringBeforeLast(".")
    }
    if (identifier.startsWith("http")) {
        return identifier.substringAfter("archive.org/download/", identifier).substringBeforeLast(".")
    }
    return identifier.substringBefore(",tmdb-").trim()
}

private val CONCERT_DETECTION_RE = Regex(
    "(?i)(\\bin\\s+concert\\b|\\bconcert\\s+in\\b|\\bconcert\\s+at\\b|\\bconcert\\s+for\\b|" +
        "\\blive\\s+at\\b|\\blive\\s+in\\b|\\blive\\s+from\\b|\\blive\\s+on\\b|\\blive\\s+concert\\b|" +
        "\\bunplugged\\b|\\bworld\\s+tour\\b|\\bsymphony\\b|\\bphilharmonic\\b|\\bwoodstock\\b|" +
        "\\bwembley\\b|\\bglastonbury\\b|\\blive\\s+aid\\b|\\bmontreux\\b|\\bkoncert\\b)"
)

private fun isConcertTitleOrId(identifier: String, title: String): Boolean {
    val cleanId = identifier.substringAfterLast("/").replace(Regex("[._-]+"), " ")
    return CONCERT_DETECTION_RE.containsMatchIn(title) || CONCERT_DETECTION_RE.containsMatchIn(cleanId)
}

private suspend fun fetchArchiveVideoFps(archiveId: String, directVideoUrl: String = ""): Float = withContext(Dispatchers.IO) {
    if (archiveId.isBlank()) return@withContext 0f
    try {
        val targetFile = if (directVideoUrl.contains("archive.org/download/")) {
            URLDecoder.decode(directVideoUrl.substringAfterLast("/"), "UTF-8").lowercase(Locale.ROOT)
        } else {
            ""
        }
        val metaJson = JSONObject(URL("https://archive.org/metadata/$archiveId").readText())
        val files = metaJson.optJSONArray("files") ?: return@withContext 0f
        var bestFps = 0f
        var bestSize = 0L
        for (i in 0 until files.length()) {
            val f = files.optJSONObject(i) ?: continue
            val name = f.optString("name").lowercase(Locale.ROOT)
            if (!name.endsWith(".mp4") && !name.endsWith(".mkv") && !name.endsWith(".avi")) continue
            val fps = f.optString("fps").toFloatOrNull() ?: 0f
            if (fps !in 15f..120f) continue
            if (targetFile.isNotEmpty() && name.endsWith(targetFile)) {
                return@withContext fps
            }
            val size = f.optString("size").toLongOrNull() ?: 0L
            if (size >= bestSize) {
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
        if (!SUBTITLES_SUPABASE_KEY.startsWith("sb_")) {
            conn.setRequestProperty("Authorization", "Bearer $SUBTITLES_SUPABASE_KEY")
        }
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
): Boolean = withContext(Dispatchers.IO) {
    if (archiveId.isBlank() || cues.isEmpty()) return@withContext false
    try {
        val payload = JSONObject().apply {
            put("archive_id", archiveId)
            put("source_filename", sourceFilename.take(180))
            put("cues_json", cuesToJsonArray(cues))
        }
        val bodyBytes = payload.toString().toByteArray(Charsets.UTF_8)

        val url = URL("$SUBTITLES_SUPABASE_URL/movie_subtitles?on_conflict=archive_id")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("apikey", SUBTITLES_SUPABASE_KEY)
        if (!SUBTITLES_SUPABASE_KEY.startsWith("sb_")) {
            conn.setRequestProperty("Authorization", "Bearer $SUBTITLES_SUPABASE_KEY")
        }
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        conn.outputStream.use { it.write(bodyBytes) }
        val code = conn.responseCode
        code in 200..299
    } catch (_: Exception) {
        false
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
            lang == "HRV" || lang == "HR" || Regex("(?i)\\b(hr|hrv|cro|croatian)\\b").containsMatchIn(fname) -> 0
            lang == "SRP" || lang == "BOS" || lang == "SR" || lang == "BS" ||
                Regex("(?i)\\b(sr|srp|serbian|bos|bosnian)\\b").containsMatchIn(fname) -> 1
            lang == "ARCHIVE" -> 2
            lang == "ENG" || lang == "EN" -> 3
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

private fun cleanMovieTitleForSubtitleSearch(raw: String): String {
    return raw
        .replace(Regex("\\[[^\\]]*\\]"), " ")
        .replace(Regex("\\(\\s*(19\\d{2}|20\\d{2})\\s*\\)"), " ")
        .replace(
            Regex(
                "\\(\\s*(?:the\\s+complete\\s+novel|complete\\s+novel|director'?s?\\s*cut|theatrical\\s*cut|theatrical|" +
                    "extended\\s*cut|extended|final\\s*cut|workprint|unrated|uncut|tv\\s*cut|us\\s*cut|eu\\s*cut|uk\\s*cut|" +
                    "restored|remastered|colorized|part\\s*\\d+|pt\\.?\\s*\\d+|cd\\s*\\d+|disc\\s*\\d+|verzija\\s*\\d+|cut)\\s*\\)",
                RegexOption.IGNORE_CASE
            ),
            " "
        )
        .replace(Regex("\\b(1080p|720p|480p|2160p|4k|bluray|blu-ray|bdrip|brrip|webrip|web-dl|dvdrip|hdrip|x264|x265|hevc|aac|ac3|mp4|mkv|avi|widescreen|fullscreen|remastered|full movie)\\b", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\b(19\\d{2}|20\\d{2})\\b\\s*$"), " ")
        .replace(Regex("[._]+"), " ")
        .replace(Regex("^\\s*\\d{1,3}\\s*[.\\-)\\]]+\\s*"), "")
        .replace(Regex("^\\s*[12]\\s+(?=[A-ZČĆŽŠĐ])"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private fun normalizeTitleWordsForMatch(title: String): Set<String> {
    val stopWords = setOf("the", "a", "an", "of", "and", "in", "on", "to", "for")
    return title.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .split(Regex("\\s+"))
        .filter { it.length >= 2 && it !in stopWords }
        .toSet()
}

private fun titlesMatchStrictly(queryWords: Set<String>, candidateTitle: String): Boolean {
    if (queryWords.isEmpty()) return false
    val candWords = normalizeTitleWordsForMatch(candidateTitle)
    if (candWords.isEmpty()) return false
    val overlap = queryWords.intersect(candWords).size
    if (queryWords.size == 1) {
        return overlap == 1 && candWords.size <= 2
    }
    return overlap * 100 >= queryWords.size * 60
}

private fun translateTitleToEnglish(croatianTitle: String): String {
    if (croatianTitle.isBlank()) return ""
    return try {
        val encoded = URLEncoder.encode(croatianTitle, "UTF-8")
        val urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=hr&tl=en&dt=t&q=$encoded"
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        if (conn.responseCode != 200) return ""
        val response = conn.inputStream.bufferedReader().use { it.readText() }
        val parts = JSONArray(response).getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            sb.append(parts.optJSONArray(i)?.optString(0) ?: "")
        }
        cleanMovieTitleForSubtitleSearch(sb.toString())
    } catch (_: Exception) {
        ""
    }
}

private fun resolveImdbIdAndEnglishTitle(
    englishQueries: List<String>,
    localizedQuery: String,
    targetYear: Int
): Pair<String, String> {
    var resolvedImdbId = ""
    var resolvedEnglishTitle = ""

    val orderedQueries = (englishQueries.map { it to true } + listOf(localizedQuery to false))
        .filter { it.first.length >= 2 }
        .distinctBy { it.first.lowercase(Locale.ROOT) }

    for ((rawQuery, isEnglishCandidate) in orderedQueries) {
        val cleanQuery = cleanMovieTitleForSubtitleSearch(rawQuery)
        if (cleanQuery.length < 2) continue
        val queryWords = normalizeTitleWordsForMatch(cleanQuery)

        // 1. IMDb javni suggestion API
        try {
            val slug = cleanQuery.lowercase(Locale.ROOT)
                .replace(Regex("\\s+"), "_")
                .replace(Regex("[^\\p{L}\\p{N}_]"), "")
            if (slug.isNotBlank()) {
                val firstChar = slug.first().takeIf { it in 'a'..'z' } ?: 'x'
                val encodedSlug = URLEncoder.encode(slug, "UTF-8")
                val conn = URL("https://v3.sg.media-imdb.com/suggestion/$firstChar/$encodedSlug.json")
                    .openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val dArr = JSONObject(body).optJSONArray("d")
                    if (dArr != null) {
                        var bestScore = Int.MIN_VALUE
                        for (i in 0 until dArr.length()) {
                            val item = dArr.optJSONObject(i) ?: continue
                            val id = item.optString("id")
                            if (!id.startsWith("tt")) continue
                            val qid = item.optString("qid").lowercase()
                            if (qid !in setOf("feature", "movie", "tvmovie", "video")) continue

                            val label = item.optString("l")
                            if (isEnglishCandidate && !titlesMatchStrictly(queryWords, label)) {
                                continue
                            }

                            val y = item.optInt("y", 0)
                            if (targetYear > 0 && y > 0 && abs(y - targetYear) > 2) continue

                            val rank = item.optInt("rank", 999999)
                            var score = 0
                            if (qid == "feature") score += 450
                            else if (qid == "movie" || qid == "tvmovie") score += 200

                            if (targetYear > 0 && y > 0) {
                                val diff = abs(y - targetYear)
                                if (diff == 0) score += 600
                                else if (diff == 1) score += 300
                            }
                            if (label.equals(cleanQuery, ignoreCase = true)) score += 400
                            else if (queryWords.isNotEmpty() && normalizeTitleWordsForMatch(label).containsAll(queryWords)) score += 220

                            score -= (rank.coerceAtMost(500000) / 1500)

                            if (score > bestScore) {
                                bestScore = score
                                resolvedImdbId = id
                                if (label.isNotBlank()) resolvedEnglishTitle = label
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (resolvedImdbId.isNotBlank()) break

        // 2. Cinemeta katalog
        try {
            val encodedQ = URLEncoder.encode(cleanQuery, "UTF-8").replace("+", "%20")
            val conn = URL("https://v3-cinemeta.strem.io/catalog/movie/top/search=$encodedQ.json")
                .openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            if (conn.responseCode == 200) {
                val metas = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optJSONArray("metas")
                if (metas != null) {
                    for (i in 0 until metas.length().coerceAtMost(6)) {
                        val m = metas.optJSONObject(i) ?: continue
                        val imdb = m.optString("imdb_id").ifBlank { m.optString("id") }
                        if (!imdb.startsWith("tt")) continue
                        val name = m.optString("name")
                        if (isEnglishCandidate && !titlesMatchStrictly(queryWords, name)) {
                            continue
                        }
                        val relYear = Regex("(19\\d{2}|20\\d{2})").find(m.optString("releaseInfo"))?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        if (targetYear == 0 || relYear == 0 || abs(relYear - targetYear) <= 1) {
                            resolvedImdbId = imdb
                            if (name.isNotBlank()) resolvedEnglishTitle = name
                            break
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (resolvedImdbId.isNotBlank()) break
    }

    return resolvedImdbId to resolvedEnglishTitle
}

private suspend fun searchAllSubtitles(
    identifier: String,
    movieTitle: String,
    movieYear: String,
    directVideoUrl: String = "",
    archiveOnly: Boolean = false
): List<PlayerSubtitleOption> = withContext(Dispatchers.IO) {
    val results = mutableListOf<PlayerSubtitleOption>()
    var archiveEnglishTitle = ""
    var parsedYear = movieYear.toIntOrNull() ?: 0
    var detectedArchiveConcert = archiveOnly || isConcertTitleOrId(identifier, movieTitle)
    val isCollectionStream = directVideoUrl.contains("archive.org/download/")
    val targetVideoStem = if (isCollectionStream) {
        URLDecoder.decode(directVideoUrl.substringAfterLast("/").substringBeforeLast("."), "UTF-8")
            .lowercase(Locale.ROOT)
    } else {
        ""
    }

    // 1. Dohvati Archive.org .srt datoteke i provjeri radi li se o koncertu
    if (identifier.isNotBlank() && !identifier.startsWith("http")) {
        try {
            val metaJson = JSONObject(URL("https://archive.org/metadata/$identifier").readText())
            val md = metaJson.optJSONObject("metadata")
            if (md != null) {
                val rawArchTitle = when (val tObj = md.opt("title")) {
                    is JSONArray -> tObj.optString(0)
                    is String -> tObj
                    else -> ""
                }
                val subjectBlob = md.opt("subject")?.toString() ?: ""
                val collectionBlob = md.opt("collection")?.toString() ?: ""
                if (CONCERT_DETECTION_RE.containsMatchIn("$rawArchTitle $subjectBlob $collectionBlob")) {
                    detectedArchiveConcert = true
                }
                val isCollectionMetaTitle = Regex("(?i)\\b(filmography|complete\\s+films|collection|anthology)\\b")
                    .containsMatchIn(rawArchTitle)
                if (rawArchTitle.isNotBlank() && !isCollectionStream && !isCollectionMetaTitle) {
                    if (parsedYear == 0) {
                        parsedYear = Regex("(19\\d{2}|20\\d{2})").find(rawArchTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    }
                    archiveEnglishTitle = cleanMovieTitleForSubtitleSearch(rawArchTitle)
                }
                if (parsedYear == 0 && !isCollectionStream) {
                    val yRaw = md.optString("year").ifBlank { md.optString("date") }
                    parsedYear = Regex("(19\\d{2}|20\\d{2})").find(yRaw)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }
            }

            val files = metaJson.optJSONArray("files")
            if (files != null) {
                for (i in 0 until files.length()) {
                    val f = files.optJSONObject(i) ?: continue
                    val name = f.optString("name")
                    if (name.endsWith(".srt", ignoreCase = true)) {
                        val shortName = name.substringAfterLast("/")
                        val srtStem = shortName.substringBeforeLast(".").lowercase(Locale.ROOT)
                        if (targetVideoStem.isNotEmpty() && !srtStem.startsWith(targetVideoStem) && !targetVideoStem.startsWith(srtStem)) {
                            continue
                        }
                        val encoded = name.split("/").joinToString("/") { part ->
                            URLEncoder.encode(part, "UTF-8").replace("+", "%20")
                        }
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
    }

    if (detectedArchiveConcert) {
        return@withContext results.distinctBy { it.downloadUrl }
    }

    val cleanLocalizedTitle = cleanMovieTitleForSubtitleSearch(movieTitle)
    val translatedEnTitle = translateTitleToEnglish(cleanLocalizedTitle)
    val englishQueries = listOf(archiveEnglishTitle, translatedEnTitle)
        .filter { it.length >= 2 }
        .distinctBy { it.lowercase(Locale.ROOT) }

    val (imdbId, resolvedEnTitle) = resolveImdbIdAndEnglishTitle(englishQueries, cleanLocalizedTitle, parsedYear)
    val englishTitleForTextSearch = listOf(resolvedEnTitle, archiveEnglishTitle, translatedEnTitle, cleanLocalizedTitle)
        .firstOrNull { it.length >= 2 } ?: ""

    val allowedLangs = setOf("hrv", "srp", "bos", "eng", "hr", "sr", "bs", "en")

    // 2. OpenSubtitles v3 po točnom IMDb ID-u
    if (imdbId.startsWith("tt")) {
        try {
            val conn = URL("https://opensubtitles-v3.strem.io/subtitles/movie/$imdbId.json")
                .openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            conn.connectTimeout = 6500
            conn.readTimeout = 7500
            if (conn.responseCode == 200) {
                val subsArr = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    .optJSONArray("subtitles")
                if (subsArr != null) {
                    var idxCounter = 0
                    for (i in 0 until subsArr.length()) {
                        val obj = subsArr.optJSONObject(i) ?: continue
                        val rawLang = obj.optString("lang").trim().lowercase()
                        if (rawLang !in allowedLangs) continue
                        val dlUrl = obj.optString("url").trim()
                        if (!dlUrl.startsWith("http")) continue

                        idxCounter++
                        val normLang = when (rawLang) {
                            "hr", "hrv" -> "HRV"
                            "sr", "srp" -> "SRP"
                            "bs", "bos" -> "BOS"
                            else -> "ENG"
                        }
                        val urlTail = URLDecoder.decode(dlUrl.substringAfterLast("/").substringBefore("?"), "UTF-8")
                        val displayTitle = englishTitleForTextSearch.ifBlank { movieTitle }
                        val fileName = if (urlTail.endsWith(".srt", ignoreCase = true) && urlTail.length > 6) {
                            urlTail
                        } else {
                            "$displayTitle ($normLang #$idxCounter).srt"
                        }
                        val detectedFps = extractFpsFromText("$fileName $dlUrl")
                        results.add(
                            PlayerSubtitleOption(
                                fileName = fileName,
                                language = normLang,
                                downloadUrl = dlUrl,
                                isGzipped = dlUrl.endsWith(".gz", ignoreCase = true),
                                fps = detectedFps,
                                downloads = 300 - idxCounter.coerceAtMost(250)
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    // 3. OpenSubtitles REST API po IMDb ID-u
    val numericImdb = imdbId.removePrefix("tt").trimStart('0')
    val langs = listOf("hrv", "srp", "bos", "eng")
    if (numericImdb.isNotBlank() && numericImdb.all { it.isDigit() }) {
        for (lang in langs) {
            try {
                val apiUrl = "https://rest.opensubtitles.org/search/imdbid-$numericImdb/sublanguageid-$lang"
                val conn = URL(apiUrl).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "VLSub 0.10.2")
                conn.setRequestProperty("X-User-Agent", "VLSub 0.10.2")
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
    }

    // 4. Rezervna tekstualna pretraga uz strogu provjeru naziva datoteke (>= 60% riječi)
    if (results.none { it.language in setOf("HRV", "SRP", "BOS", "ENG") } && englishTitleForTextSearch.length >= 2) {
        val strictWords = normalizeTitleWordsForMatch(englishTitleForTextSearch)
        val asciiQuery = englishTitleForTextSearch
            .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
        if (asciiQuery.length >= 2 && strictWords.isNotEmpty()) {
            val encodedQuery = URLEncoder.encode(asciiQuery, "UTF-8")
            for (lang in langs) {
                try {
                    val apiUrl = "https://rest.opensubtitles.org/search/query-$encodedQuery/sublanguageid-$lang"
                    val conn = URL(apiUrl).openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", "VLSub 0.10.2")
                    conn.setRequestProperty("X-User-Agent", "VLSub 0.10.2")
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    if (conn.responseCode == 200) {
                        val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                        for (i in 0 until arr.length().coerceAtMost(6)) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val subFile = obj.optString("SubFileName")
                            val dlLink = obj.optString("SubDownloadLink")
                            if (subFile.isBlank() || dlLink.isBlank()) continue
                            if (!titlesMatchStrictly(strictWords, cleanMovieTitleForSubtitleSearch(subFile))) continue

                            val apiFps = obj.optString("MovieFPS").toFloatOrNull()?.takeIf { it in 15f..120f } ?: 0f
                            val resolvedFps = if (apiFps > 0f) apiFps else extractFpsFromText(subFile)
                            val dlCount = obj.optString("SubDownloadsCnt").toIntOrNull() ?: 0
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
                } catch (_: Exception) {
                }
            }
        }
    }

    results.distinctBy { it.downloadUrl }
}

private suspend fun downloadSubtitleText(sub: PlayerSubtitleOption): String = withContext(Dispatchers.IO) {
    try {
        val conn = URL(sub.downloadUrl).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        val bytes = conn.inputStream.use { it.readBytes() }

        val hasGzipHeader = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        val rawText = if (sub.isGzipped || hasGzipHeader) {
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
