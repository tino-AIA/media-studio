package com.example.mediastudio.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class VideoQualityOption(
    val label: String,
    val url: String,
    val height: Int,
    val sizeBytes: Long
)

data class MovieFullInfo(
    val qualities: List<VideoQualityOption>,
    val description: String,
    val year: String,
    val director: String
)

data class SeriesEpisode(
    val seasonNumber: Int = 1,
    val episodeNumber: Int,
    val title: String,
    val subtitleSearchQuery: String = "",
    val fileUrl: String,
    val itemIdentifier: String = "",
    val durationText: String = ""
)

data class SeriesSeason(
    val seasonNumber: Int,
    val seasonTitle: String,
    val episodes: List<SeriesEpisode>
)

data class SeriesFullInfo(
    val description: String,
    val seasons: List<SeriesSeason>,
    val episodes: List<SeriesEpisode>
)

private data class TmdbSeriesValidationMeta(
    val originalName: String,
    val overviewHr: String,
    val requiredEpisodesBySeason: Map<Int, Int>
)

object PosterFetcher {
    private val cache = mutableMapOf<String, String>()
    private const val TMDB_API_KEY = "0eb677c7b027f3fc1502856e48f4da97"
    private const val OMDB_API_KEY = "thewdb"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"

    suspend fun fetchPoster(title: String, isSeries: Boolean, archiveId: String): String = withContext(Dispatchers.IO) {
        SupabaseRepository.getCachedPoster(archiveId, title)?.let { sbPoster ->
            return@withContext sbPoster
        }

        var cleanTitle = title
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("(?i)\\b(video|hd|sd|hq|uncut|director's\\s*cut|restored|remastered|colorized|complete|series|full|uncensored|collection|vol\\.?\\s*\\d+|part\\s*\\d+|episode\\s*\\d+)\\b.*"), "")
            .replace(Regex("(?i)season\\s*\\d+.*"), "")
            .replace(Regex("[^\\p{L}\\p{N}\\s']"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (cleanTitle.length < 3) {
            cleanTitle = archiveId.replace("_", " ").replace("-", " ")
                .replace(Regex("(?i)\\b(video|hd|sd|hq|uncut|director's\\s*cut|restored|remastered|colorized|complete|series|full|uncensored|collection|vol|part)\\b.*"), "")
                .replace(Regex("\\d{4}"), "")
                .trim()
        }

        val cacheKey = "${isSeries}_$cleanTitle"
        if (cache.containsKey(cacheKey)) return@withContext cache[cacheKey]!!

        val fallback = "https://archive.org/services/img/$archiveId"
        if (cleanTitle.isBlank() || cleanTitle.length < 2) return@withContext fallback

        try {
            val encoded = URLEncoder.encode(cleanTitle, "UTF-8")
            val type = if (isSeries) "tv" else "movie"
            val url = URL("https://api.themoviedb.org/3/search/$type?api_key=$TMDB_API_KEY&query=$encoded")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val results = json.optJSONArray("results")
                if (results != null && results.length() > 0) {
                    val posterPath = results.optJSONObject(0)?.optString("poster_path")
                    if (!posterPath.isNullOrBlank() && posterPath != "null") {
                        val fullUrl = "https://image.tmdb.org/t/p/w500$posterPath"
                        cache[cacheKey] = fullUrl
                        return@withContext fullUrl
                    }
                }
            }

            val omdbType = if (isSeries) "series" else "movie"
            val omdbUrl = URL("https://www.omdbapi.com/?t=$encoded&type=$omdbType&apikey=$OMDB_API_KEY")
            val omdbConn = omdbUrl.openConnection() as HttpURLConnection
            omdbConn.setRequestProperty("User-Agent", USER_AGENT)
            omdbConn.connectTimeout = 2500
            if (omdbConn.responseCode == 200) {
                val omdbJson = JSONObject(omdbConn.inputStream.bufferedReader().use { it.readText() })
                val poster = omdbJson.optString("Poster")
                if (poster.isNotBlank() && poster != "N/A" && poster.startsWith("http")) {
                    val httpsPoster = poster.replace("http://", "https://")
                    cache[cacheKey] = httpsPoster
                    return@withContext httpsPoster
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        cache[cacheKey] = fallback
        fallback
    }
}

object ArchiveMetadataModule {
    private const val TMDB_API_KEY = "0eb677c7b027f3fc1502856e48f4da97"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
    private val videoExtensions = listOf(".mp4", ".mkv", ".avi", ".m4v", ".mov", ".ogv", ".webm")
    private val junkClipRegex = Regex(
        "(?i)(trailer|sample|teaser|preview|promo|pencil[\\s._-]*test|shot[\\s._-]*demo|transformation|test[\\s._-]*footage|commentary|subtlet|review)"
    )

    private fun computeSmoothnessScore(name: String, height: Int, sizeBytes: Long, isDeriv: Boolean): Long {
        val sizeMb = sizeBytes / (1024L * 1024L)
        var score = 0L

        score += when {
            height in 720..1080 -> 400L
            height >= 480 -> 260L
            height > 1080 -> 180L
            else -> 120L
        }

        score += when {
            sizeMb in 300L..1450L -> 500L
            sizeMb in 150L..299L -> 250L
            sizeMb in 1451L..1950L -> 150L
            sizeMb > 1950L -> -350L
            else -> 0L
        }

        if (isDeriv && sizeMb > 1600L) {
            score += 200L
        } else if (!isDeriv && sizeMb > 2200L) {
            score -= 250L
        }

        return score
    }

    suspend fun fetchMovieFullInfo(identifier: String): MovieFullInfo = withContext(Dispatchers.IO) {
        SupabaseRepository.getCatalogItem(identifier)?.let { sbItem ->
            if (sbItem.streamUrl.isNotBlank()) {
                val rawDesc = sbItem.descriptionHr.ifBlank { "Opis za ovaj naslov trenutno nije dostupan." }
                val finalDesc = if (looksLikeEnglish(rawDesc)) {
                    translateToCroatianSentenceBySentence(rawDesc)
                } else {
                    rawDesc
                }
                return@withContext MovieFullInfo(
                    qualities = listOf(
                        VideoQualityOption(
                            label = "HD / Standard",
                            url = sbItem.streamUrl,
                            height = 720,
                            sizeBytes = 0L
                        )
                    ),
                    description = finalDesc,
                    year = if (sbItem.releaseYear > 0) sbItem.releaseYear.toString() else "",
                    director = ""
                )
            }
        }

        try {
            val jsonStr = URL("https://archive.org/metadata/$identifier").readText()
            val root = JSONObject(jsonStr)
            val metadata = root.optJSONObject("metadata")

            val rawDesc = parseCleanDescription(metadata)
            val cleanDesc = if (looksLikeEnglish(rawDesc)) {
                translateToCroatianSentenceBySentence(rawDesc)
            } else {
                rawDesc
            }

            val rawDate = metadata?.optString("date", "")?.takeIf { it.isNotEmpty() }
                ?: metadata?.optString("year", "") ?: ""
            val year = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(rawDate)?.value ?: ""

            val rawDirector = when (val c = metadata?.opt("director") ?: metadata?.opt("creator")) {
                is String -> c
                is JSONArray -> (0 until c.length()).joinToString(", ") { c.optString(it) }
                else -> ""
            }

            val filesArray = root.optJSONArray("files")
            val qualities = mutableListOf<Pair<Long, VideoQualityOption>>()

            if (filesArray != null) {
                for (i in 0 until filesArray.length()) {
                    val fileObj = filesArray.optJSONObject(i) ?: continue
                    val name = fileObj.optString("name")
                    val format = fileObj.optString("format")

                    val isVideoFile = videoExtensions.any { name.endsWith(it, ignoreCase = true) } ||
                        format.contains("MPEG4", ignoreCase = true) ||
                        format.contains("h.264", ignoreCase = true) ||
                        format.contains("Matroska", ignoreCase = true)

                    if (isVideoFile) {
                        if (junkClipRegex.containsMatchIn(name)) continue
                        val height = fileObj.optString("height").toIntOrNull() ?: 0
                        val sizeBytes = fileObj.optString("size").toLongOrNull() ?: 0L
                        val sizeMb = sizeBytes / (1024 * 1024)

                        if (sizeMb < 15 && height == 0) continue

                        val isDeriv = name.lowercase(Locale.ROOT).endsWith(".ia.mp4") ||
                            fileObj.optString("source").equals("derivative", ignoreCase = true)

                        val resLabel = when {
                            height >= 1080 -> "1080p Full HD"
                            height >= 720 -> "720p HD"
                            height >= 480 -> "480p SD"
                            height > 0 -> "${height}p"
                            else -> "Standard"
                        }

                        val sizeText = if (sizeMb >= 1024) {
                            String.format(Locale.US, "%.1f GB", sizeMb / 1024.0)
                        } else if (sizeMb > 0) {
                            "$sizeMb MB"
                        } else {
                            ""
                        }

                        val fullLabel = if (sizeText.isNotEmpty()) "$resLabel ($sizeText)" else resLabel
                        val encodedName = name.split("/").joinToString("/") { part ->
                            URLEncoder.encode(part, "UTF-8").replace("+", "%20")
                        }
                        val url = "https://archive.org/download/$identifier/$encodedName"
                        val score = computeSmoothnessScore(name, height, sizeBytes, isDeriv)

                        qualities.add(
                            score to VideoQualityOption(
                                label = fullLabel,
                                url = url,
                                height = height,
                                sizeBytes = sizeBytes
                            )
                        )
                    }
                }
            }

            val sortedQualities = qualities
                .sortedByDescending { it.first }
                .map { it.second }
                .distinctBy { it.label }

            MovieFullInfo(
                qualities = sortedQualities,
                description = cleanDesc,
                year = year,
                director = rawDirector.trim()
            )
        } catch (e: Exception) {
            e.printStackTrace()
            MovieFullInfo(emptyList(), "Opis nije moguće učitati.", "", "")
        }
    }

    suspend fun fetchAvailableQualities(identifier: String): List<VideoQualityOption> {
        return fetchMovieFullInfo(identifier).qualities
    }

    suspend fun fetchSeriesFullInfo(identifiersStr: String, seriesTitleHint: String = ""): SeriesFullInfo = withContext(Dispatchers.IO) {
        SupabaseRepository.getCatalogItem(identifiersStr)?.let { sbSeries ->
            if (sbSeries.episodes.isNotEmpty()) {
                val resolvedTmdbId = sbSeries.tmdbId.takeIf { it > 0 }
                    ?: Regex("(?i)tmdb-tv-(\\d+)").find(sbSeries.archiveId)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 0

                val tmdbMeta = if (resolvedTmdbId > 0) {
                    fetchTmdbSeriesValidationMeta(resolvedTmdbId)
                } else {
                    null
                }

                val groupedBySeasonRaw = sbSeries.episodes
                    .filter { it.season >= 1 && it.url.isNotBlank() }
                    .groupBy { it.season }

                val validSeasonNumbers = mutableListOf<Int>()
                if (tmdbMeta != null && tmdbMeta.requiredEpisodesBySeason.isNotEmpty()) {
                    val maxSeasonToCheck = maxOf(
                        groupedBySeasonRaw.keys.maxOrNull() ?: 0,
                        tmdbMeta.requiredEpisodesBySeason.keys.maxOrNull() ?: 0
                    )
                    for (sNum in 1..maxSeasonToCheck) {
                        val reqCount = tmdbMeta.requiredEpisodesBySeason[sNum] ?: break
                        val epsForSeason = groupedBySeasonRaw[sNum].orEmpty()
                        val distinctEpNums = epsForSeason.mapIndexed { idx, ep ->
                            if (ep.episode > 0) ep.episode else idx + 1
                        }.toSet()
                        if (reqCount <= 0 || distinctEpNums.size < reqCount) {
                            break
                        }
                        validSeasonNumbers.add(sNum)
                    }
                } else if (groupedBySeasonRaw.containsKey(1)) {
                    var sNum = 1
                    while (groupedBySeasonRaw.containsKey(sNum)) {
                        validSeasonNumbers.add(sNum)
                        sNum++
                    }
                }

                val subtitleSeriesName = (tmdbMeta?.originalName?.takeIf { it.isNotBlank() } ?: sbSeries.title)
                    .replace(Regex("\\(\\s*(19\\d{2}|20\\d{2})\\s*\\)"), "")
                    .trim()

                val seasonsList = validSeasonNumbers.map { seasonNum ->
                    val epsInSeason = groupedBySeasonRaw[seasonNum].orEmpty()
                        .sortedBy { if (it.episode > 0) it.episode else Int.MAX_VALUE }
                        .distinctBy { if (it.episode > 0) it.episode else it.url.hashCode() }
                        .mapIndexed { idx, ep ->
                            val epNum = if (ep.episode > 0) ep.episode else idx + 1
                            SeriesEpisode(
                                seasonNumber = seasonNum,
                                episodeNumber = epNum,
                                title = ep.title,
                                subtitleSearchQuery = String.format(Locale.US, "%s S%02dE%02d", subtitleSeriesName, seasonNum, epNum),
                                fileUrl = ep.url,
                                itemIdentifier = sbSeries.archiveId,
                                durationText = ep.duration
                            )
                        }
                    val label = if (validSeasonNumbers.size == 1 && seasonNum == 1) "Epizode" else "Sezona $seasonNum"
                    SeriesSeason(
                        seasonNumber = seasonNum,
                        seasonTitle = label,
                        episodes = epsInSeason
                    )
                }

                val rawDesc = sbSeries.descriptionHr.ifBlank { tmdbMeta?.overviewHr.orEmpty() }
                val translatedDesc = when {
                    rawDesc.isBlank() -> if (seasonsList.isEmpty()) {
                        "Sezona 1 se trenutno prikuplja i uskoro će biti dostupna."
                    } else {
                        "Opis nije dostupan."
                    }
                    looksLikeEnglish(rawDesc) -> translateToCroatianSentenceBySentence(rawDesc)
                    else -> rawDesc
                }

                val flatEpisodes = seasonsList.flatMap { it.episodes }
                return@withContext SeriesFullInfo(
                    description = translatedDesc,
                    seasons = seasonsList,
                    episodes = flatEpisodes
                )
            }
        }

        try {
            val ids = identifiersStr.split(",")
            var firstDesc = ""
            val rawEpisodes = mutableListOf<SeriesEpisode>()
            val officialEpisodeTitles = fetchOfficialEpisodeTitles(seriesTitleHint)

            for (id in ids) {
                val cleanId = id.trim()
                if (cleanId.isEmpty()) continue

                try {
                    val jsonStr = URL("https://archive.org/metadata/$cleanId").readText()
                    val root = JSONObject(jsonStr)
                    val metadata = root.optJSONObject("metadata")

                    val showTitle = metadata?.optString("title")?.takeIf { it.isNotBlank() }
                        ?: seriesTitleHint.ifBlank { cleanId.replace("_", " ").replace("-", " ") }

                    if (firstDesc.isEmpty()) {
                        val rawDesc = parseCleanDescription(metadata)
                        firstDesc = translateToCroatianSentenceBySentence(rawDesc)
                    }

                    val filesArray = root.optJSONArray("files")
                    if (filesArray != null && filesArray.length() > 0) {
                        for (i in 0 until filesArray.length()) {
                            val fileObj = filesArray.optJSONObject(i) ?: continue
                            val name = fileObj.optString("name")
                            val source = fileObj.optString("source")
                            val format = fileObj.optString("format")

                            val isVideo = videoExtensions.any { name.endsWith(it, ignoreCase = true) } ||
                                format.contains("MPEG4", ignoreCase = true) ||
                                format.contains("h.264", ignoreCase = true) ||
                                format.contains("Matroska", ignoreCase = true) ||
                                format.contains("Ogg Video", ignoreCase = true)

                            if (!isVideo) continue
                            if (junkClipRegex.containsMatchIn(name)) continue
                            if (source.equals("derivative", ignoreCase = true) && name.contains("_512kb", ignoreCase = true)) continue

                            val sizeBytes = fileObj.optString("size").toLongOrNull() ?: 0L
                            if (sizeBytes in 1..1_500_000L) continue

                            val baseFileName = name.substringAfterLast("/")
                                .substringBeforeLast(".")
                                .removeSuffix(".ia")
                                .replace("_", " ")
                                .replace("-", " ")
                                .trim()

                            val rawTitle = fileObj.optString("title")
                                .removeSuffix(".ia")
                                .trim()
                                .takeIf { it.isNotBlank() } ?: baseFileName

                            val (seasonNum, epNum) = parseSeasonAndEpisode("$name $rawTitle $showTitle $cleanId")
                            val officialName = if (seasonNum > 0 && epNum > 0) officialEpisodeTitles[seasonNum to epNum] else null

                            val cleanSeriesName = cleanSeriesTitleForSearch(showTitle.ifBlank { seriesTitleHint })
                            val codeTag = if (seasonNum > 0 && epNum > 0) String.format(Locale.US, "S%02dE%02d", seasonNum, epNum) else ""

                            val displayTitle = when {
                                officialName != null && codeTag.isNotEmpty() -> "$codeTag - $officialName"
                                officialName != null -> officialName
                                else -> rawTitle
                            }

                            val subQuery = when {
                                codeTag.isNotEmpty() -> "$cleanSeriesName $codeTag"
                                officialName != null -> "$cleanSeriesName $officialName"
                                else -> "$cleanSeriesName $rawTitle"
                            }

                            val lengthSec = fileObj.optString("length").toDoubleOrNull()?.toInt() ?: 0
                            val durationText = if (lengthSec > 0) {
                                val mins = lengthSec / 60
                                val secs = lengthSec % 60
                                String.format(Locale.US, "%d:%02d", mins, secs)
                            } else {
                                ""
                            }

                            val encodedName = name.split("/").joinToString("/") { part ->
                                URLEncoder.encode(part, "UTF-8").replace("+", "%20")
                            }
                            val fileUrl = "https://archive.org/download/$cleanId/$encodedName"

                            rawEpisodes.add(
                                SeriesEpisode(
                                    seasonNumber = if (seasonNum > 0) seasonNum else 1,
                                    episodeNumber = epNum,
                                    title = displayTitle,
                                    subtitleSearchQuery = subQuery.trim(),
                                    fileUrl = fileUrl,
                                    itemIdentifier = cleanId,
                                    durationText = durationText
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val distinctEpisodes = rawEpisodes
                .sortedWith(
                    compareBy<SeriesEpisode> { it.seasonNumber }
                        .thenBy { if (it.episodeNumber > 0) it.episodeNumber else Int.MAX_VALUE }
                        .thenBy { it.title.lowercase(Locale.ROOT) }
                        .thenBy { if (it.fileUrl.endsWith(".mp4", true) && !it.fileUrl.contains(".ia.mp4")) 0 else 1 }
                )
                .distinctBy { "${it.seasonNumber}_${it.episodeNumber}_${it.title.lowercase(Locale.ROOT)}" }

            val allSeasonNums = distinctEpisodes.map { it.seasonNumber }.distinct().sorted()
            val consecutiveSeasons = mutableListOf<Int>()
            if (allSeasonNums.contains(1)) {
                var s = 1
                while (allSeasonNums.contains(s)) {
                    consecutiveSeasons.add(s)
                    s++
                }
            }

            val groupedBySeason = consecutiveSeasons.map { seasonNum ->
                val epsInSeason = distinctEpisodes.filter { it.seasonNumber == seasonNum }
                val numberedEps = epsInSeason.mapIndexed { idx, ep ->
                    ep.copy(episodeNumber = if (ep.episodeNumber > 0) ep.episodeNumber else idx + 1)
                }.sortedBy { it.episodeNumber }

                val seasonLabel = if (consecutiveSeasons.size == 1 && seasonNum == 1) {
                    "Epizode"
                } else {
                    "Sezona $seasonNum"
                }
                SeriesSeason(
                    seasonNumber = seasonNum,
                    seasonTitle = seasonLabel,
                    episodes = numberedEps
                )
            }

            val allFlatEpisodes = groupedBySeason.flatMap { it.episodes }

            SeriesFullInfo(
                description = firstDesc,
                seasons = groupedBySeason,
                episodes = allFlatEpisodes
            )
        } catch (e: Exception) {
            e.printStackTrace()
            SeriesFullInfo("Opis nije moguće učitati.", emptyList(), emptyList())
        }
    }

    private fun fetchTmdbSeriesValidationMeta(tmdbId: Int): TmdbSeriesValidationMeta? {
        return try {
            val hrUrl = URL("https://api.themoviedb.org/3/tv/$tmdbId?api_key=$TMDB_API_KEY&language=hr-HR")
            val conn = hrUrl.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 3500
            conn.readTimeout = 4000
            if (conn.responseCode != 200) return null

            val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val origName = root.optString("original_name").ifBlank { root.optString("name") }.trim()
            var overview = root.optString("overview").trim()

            if (overview.isBlank()) {
                val enUrl = URL("https://api.themoviedb.org/3/tv/$tmdbId?api_key=$TMDB_API_KEY&language=en-US")
                val enConn = enUrl.openConnection() as HttpURLConnection
                enConn.setRequestProperty("User-Agent", USER_AGENT)
                enConn.connectTimeout = 3000
                enConn.readTimeout = 3500
                if (enConn.responseCode == 200) {
                    val enRoot = JSONObject(enConn.inputStream.bufferedReader().use { it.readText() })
                    val enOv = enRoot.optString("overview").trim()
                    if (enOv.isNotBlank()) {
                        overview = translateToCroatianSentenceBySentence(enOv)
                    }
                }
            } else if (looksLikeEnglish(overview)) {
                overview = translateToCroatianSentenceBySentence(overview)
            }

            val lastAiredObj = root.optJSONObject("last_episode_to_air")
            val lastAiredSeason = lastAiredObj?.optInt("season_number", 0) ?: 0
            val lastAiredEp = lastAiredObj?.optInt("episode_number", 0) ?: 0

            val requiredMap = mutableMapOf<Int, Int>()
            val seasonsArr = root.optJSONArray("seasons")
            if (seasonsArr != null) {
                for (i in 0 until seasonsArr.length()) {
                    val sObj = seasonsArr.optJSONObject(i) ?: continue
                    val sNum = sObj.optInt("season_number", 0)
                    val epCount = sObj.optInt("episode_count", 0)
                    if (sNum < 1 || epCount <= 0) continue

                    val requiredForSeason = when {
                        lastAiredSeason > 0 && sNum < lastAiredSeason -> epCount
                        lastAiredSeason > 0 && sNum == lastAiredSeason -> {
                            if (lastAiredEp > 0) minOf(epCount, lastAiredEp) else epCount
                        }
                        lastAiredSeason > 0 && sNum > lastAiredSeason -> 0
                        else -> epCount
                    }
                    if (requiredForSeason > 0) {
                        requiredMap[sNum] = requiredForSeason
                    }
                }
            }

            TmdbSeriesValidationMeta(
                originalName = origName,
                overviewHr = overview,
                requiredEpisodesBySeason = requiredMap
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSeasonAndEpisode(text: String): Pair<Int, Int> {
        val patterns = listOf(
            Regex("(?i)\\bS(\\d{1,2})\\s*[-_.]?\\s*E(\\d{1,3})\\b"),
            Regex("(?i)\\b(\\d{1,2})x(\\d{1,3})\\b"),
            Regex("(?i)Season\\s*(\\d{1,2}).*?Episode\\s*(\\d{1,3})"),
            Regex("(?i)\\bS(\\d{1,2})\\b.*?\\bEp?\\.?\\s*(\\d{1,3})\\b")
        )
        for (regex in patterns) {
            val m = regex.find(text)
            if (m != null) {
                val s = m.groupValues[1].toIntOrNull() ?: 1
                val e = m.groupValues[2].toIntOrNull() ?: 0
                return s to e
            }
        }
        val seasonOnly = Regex("(?i)Season\\s*(\\d{1,2})\\b").find(text)
        val s = seasonOnly?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val epOnly = Regex("(?i)(?:Episode|Ep\\.?|Part)\\s*(\\d{1,3})\\b").find(text)
        val e = epOnly?.groupValues?.get(1)?.toIntOrNull() ?: 0

        return s to e
    }

    private fun cleanSeriesTitleForSearch(rawTitle: String): String {
        return rawTitle
            .replace(Regex("(?i)(complete series|full series|season\\s*\\d+|seasons\\s*\\d+.*|uncensored|collection|colorized|\\(\\d{4}\\))"), "")
            .replace(Regex("(?i)presents\\s*\\d+\\s*episodes?.*"), "Presents")
            .replace(Regex("[^\\p{L}\\p{N}\\s'!]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun fetchOfficialEpisodeTitles(rawSeriesTitle: String): Map<Pair<Int, Int>, String> {
        return try {
            val cleanName = cleanSeriesTitleForSearch(rawSeriesTitle)
            if (cleanName.length < 3) return emptyMap()
            val encoded = URLEncoder.encode(cleanName, "UTF-8")
            val searchUrl = "https://api.tvmaze.com/singlesearch/shows?q=$encoded&embed=episodes"
            val conn = URL(searchUrl).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            if (conn.responseCode != 200) return emptyMap()

            val root = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val episodesArr = root.optJSONObject("_embedded")?.optJSONArray("episodes") ?: return emptyMap()
            val map = mutableMapOf<Pair<Int, Int>, String>()
            for (i in 0 until episodesArr.length()) {
                val epObj = episodesArr.optJSONObject(i) ?: continue
                val s = epObj.optInt("season", 0)
                val e = epObj.optInt("number", 0)
                val name = epObj.optString("name", "").trim()
                if (s > 0 && e > 0 && name.isNotBlank()) {
                    map[s to e] = name
                }
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun looksLikeEnglish(text: String): Boolean {
        if (text.length < 15) return false
        val padded = " ${text.lowercase(Locale.ROOT)} "
        val markers = listOf(
            " the ", " and ", " with ", " from ", " into ", " their ",
            " his ", " her ", " show ", " story ", " when ", " where ",
            " which ", " while ", " after ", " before ", " through ", " against ",
            " of ", " in ", " to "
        )
        val hits = markers.count { padded.contains(it) }
        val hasHrChars = text.any { it in "čćžšđČĆŽŠĐ" }
        return hits >= 2 && !hasHrChars
    }

    private fun parseCleanDescription(metadata: JSONObject?): String {
        val rawDesc = when (val d = metadata?.opt("description")) {
            is String -> d
            is JSONArray -> (0 until d.length()).joinToString("\n") { d.optString(it) }
            else -> ""
        }
        return rawDesc
            .replace(Regex("<[^>]*>"), " ")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&apos;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { "Opis za ovaj naslov trenutno nije dostupan." }
    }

    private fun translateToCroatianSentenceBySentence(text: String): String {
        if (text.isBlank() || text.startsWith("Opis za ovaj naslov")) return text
        return try {
            val limitedText = text.take(2200)
            val sentences = limitedText.split(Regex("(?<=[.!?])\\s+"))
            val translatedBuilder = StringBuilder()

            for (sentence in sentences) {
                val trimmed = sentence.trim()
                if (trimmed.isEmpty()) continue
                val encoded = URLEncoder.encode(trimmed, "UTF-8")
                val urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=hr&dt=t&q=$encoded"
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.connectTimeout = 4000
                conn.readTimeout = 4000

                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(response)
                val parts = jsonArray.getJSONArray(0)
                for (i in 0 until parts.length()) {
                    translatedBuilder.append(parts.getJSONArray(i).getString(0))
                }
                translatedBuilder.append(" ")
            }
            translatedBuilder.toString().trim().ifBlank { text }
        } catch (_: Exception) {
            text
        }
    }
}
