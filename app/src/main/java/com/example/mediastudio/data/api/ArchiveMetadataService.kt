package com.example.mediastudio.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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

object PosterFetcher {
    private val cache = mutableMapOf<String, String>()
    private const val TMDB_API_KEY = "3e9f4c3a726ea7247a32d164627d2c38" 
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"

    suspend fun fetchPoster(title: String, isSeries: Boolean, archiveId: String): String = withContext(Dispatchers.IO) {
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").replace(Regex("(?i)season\\s*\\d+.*"), "").trim()
        val cacheKey = "${isSeries}_$cleanTitle"
        if (cache.containsKey(cacheKey)) return@withContext cache[cacheKey]!!

        val fallback = "https://archive.org/services/img/$archiveId"
        if (cleanTitle.isBlank() || cleanTitle.length < 3) return@withContext fallback

        try {
            val encoded = URLEncoder.encode(cleanTitle, "UTF-8")
            
            // 1. Pokušaj preko TMDB
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

            // 2. Ako je serija, a TMDB nije našao, pokušaj TVmaze
            if (isSeries) {
                val tvMazeUrl = URL("https://api.tvmaze.com/singlesearch/shows?q=$encoded")
                val tvMazeConn = tvMazeUrl.openConnection() as HttpURLConnection
                tvMazeConn.setRequestProperty("User-Agent", USER_AGENT)
                tvMazeConn.connectTimeout = 2500
                if (tvMazeConn.responseCode == 200) {
                    val tvMazeJson = JSONObject(tvMazeConn.inputStream.bufferedReader().use { it.readText() })
                    val img = tvMazeJson.optJSONObject("image")?.optString("original")
                    if (!img.isNullOrBlank()) {
                        cache[cacheKey] = img
                        return@withContext img
                    }
                }
            }

            // 3. Pokušaj iTunes API kao zadnju šansu
            val itunesType = if (isSeries) "tvSeason" else "movie"
            val itunesUrl = URL("https://itunes.apple.com/search?term=$encoded&entity=$itunesType&limit=1")
            val itunesConn = itunesUrl.openConnection() as HttpURLConnection
            itunesConn.setRequestProperty("User-Agent", USER_AGENT)
            itunesConn.connectTimeout = 2500
            if (itunesConn.responseCode == 200) {
                val itunesJson = JSONObject(itunesConn.inputStream.bufferedReader().use { it.readText() })
                val results = itunesJson.optJSONArray("results")
                if (results != null && results.length() > 0) {
                    val artwork = results.optJSONObject(0)?.optString("artworkUrl100")
                    if (!artwork.isNullOrBlank()) {
                        val highRes = artwork.replace("100x100bb", "600x900bb")
                        cache[cacheKey] = highRes
                        return@withContext highRes
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        
        cache[cacheKey] = fallback
        fallback
    }
}

object ArchiveMetadataModule {
    private val videoExtensions = listOf(".mp4", ".mkv", ".avi", ".m4v", ".mov", ".ogv", ".webm")

    suspend fun fetchMovieFullInfo(identifier: String): MovieFullInfo = withContext(Dispatchers.IO) {
        try {
            val jsonStr = URL("https://archive.org/metadata/$identifier").readText()
            val root = JSONObject(jsonStr)
            val metadata = root.optJSONObject("metadata")

            val cleanDesc = parseCleanDescription(metadata)

            val rawDate = metadata?.optString("date", "")?.takeIf { it.isNotEmpty() }
                ?: metadata?.optString("year", "") ?: ""
            val year = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(rawDate)?.value ?: ""

            val rawDirector = when (val c = metadata?.opt("director") ?: metadata?.opt("creator")) {
                is String -> c
                is JSONArray -> (0 until c.length()).joinToString(", ") { c.optString(it) }
                else -> ""
            }

            val filesArray = root.optJSONArray("files")
            val qualities = mutableListOf<VideoQualityOption>()

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
                        val height = fileObj.optString("height").toIntOrNull() ?: 0
                        val sizeBytes = fileObj.optString("size").toLongOrNull() ?: 0L
                        val sizeMb = sizeBytes / (1024 * 1024)

                        if (sizeMb < 5 && height == 0) continue

                        val resLabel = when {
                            height >= 1080 -> "1080p Full HD"
                            height >= 720 -> "720p HD"
                            height >= 480 -> "480p SD"
                            height > 0 -> "${height}p"
                            else -> "Standard"
                        }

                        val sizeText = if (sizeMb >= 1024) {
                            String.format("%.1f GB", sizeMb / 1024.0)
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

                        qualities.add(
                            VideoQualityOption(
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
                .distinctBy { it.label }
                .sortedWith(compareByDescending<VideoQualityOption> { it.height }.thenByDescending { it.sizeBytes })

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
                                    format.contains("Matroska", ignoreCase = true) ||
                                    format.contains("Ogg Video", ignoreCase = true)

                            if (!isVideo) continue
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

                            val cleanSeriesName = cleanSeriesTitleForSearch(seriesTitleHint.ifBlank { showTitle })
                            val codeTag = if (seasonNum > 0 && epNum > 0) String.format("S%02dE%02d", seasonNum, epNum) else ""

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
                                String.format("%d:%02d", mins, secs)
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
                    } else {
                        val searchUrl = "https://archive.org/advancedsearch.php?q=collection:$cleanId+AND+mediatype:movies&fl[]=identifier&fl[]=title&fl[]=runtime&sort[]=title+asc&rows=120&output=json"
                        val searchJson = JSONObject(URL(searchUrl).readText())
                        val docs = searchJson.optJSONObject("response")?.optJSONArray("docs")

                        if (docs != null) {
                            for (i in 0 until docs.length()) {
                                val doc = docs.optJSONObject(i) ?: continue
                                val epId = doc.optString("identifier").takeIf { it.isNotBlank() } ?: continue
                                val epTitle = doc.optString("title").takeIf { it.isNotBlank() }
                                    ?: epId.replace("_", " ").replace("-", " ")
                                val runtime = doc.optString("runtime")

                                val (seasonNum, epNum) = parseSeasonAndEpisode("$epId $epTitle $showTitle")
                                val bestUrl = fetchAvailableQualities(epId).firstOrNull()?.url
                                    ?: "https://archive.org/download/$epId/$epId.mp4"

                                rawEpisodes.add(
                                    SeriesEpisode(
                                        seasonNumber = if (seasonNum > 0) seasonNum else 1,
                                        episodeNumber = epNum,
                                        title = epTitle,
                                        subtitleSearchQuery = epTitle,
                                        fileUrl = bestUrl,
                                        itemIdentifier = epId,
                                        durationText = runtime
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }

            val distinctEpisodes = rawEpisodes
                .sortedWith(
                    compareBy<SeriesEpisode> { it.seasonNumber }
                        .thenBy { if (it.episodeNumber > 0) it.episodeNumber else Int.MAX_VALUE }
                        .thenBy { it.title.lowercase() }
                        .thenBy { if (it.fileUrl.endsWith(".mp4", true) && !it.fileUrl.contains(".ia.mp4")) 0 else 1 }
                )
                .distinctBy { "${it.seasonNumber}_${it.episodeNumber}_${it.title.lowercase()}" }

            val groupedBySeason = distinctEpisodes
                .groupBy { it.seasonNumber }
                .toSortedMap()
                .map { (seasonNum, epsInSeason) ->
                    val numberedEps = epsInSeason.mapIndexed { idx, ep ->
                        ep.copy(episodeNumber = if (ep.episodeNumber > 0) ep.episodeNumber else idx + 1)
                    }.sortedBy { it.episodeNumber }

                    val seasonLabel = if (distinctEpisodes.map { it.seasonNumber }.distinct().size == 1 && seasonNum == 1) {
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
            .replace(Regex("[^a-zA-Z0-9\\s']"), " ")
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
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
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
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
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
        } catch (e: Exception) {
            text
        }
    }
}
