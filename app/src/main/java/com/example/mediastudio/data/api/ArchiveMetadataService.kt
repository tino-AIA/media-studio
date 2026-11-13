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

    suspend fun fetchSeriesFullInfo(identifier: String, seriesTitleHint: String = ""): SeriesFullInfo = withContext(Dispatchers.IO) {
        try {
            val jsonStr = URL("https://archive.org/metadata/$identifier").readText()
            val root = JSONObject(jsonStr)
            val metadata = root.optJSONObject("metadata")

            val showTitle = metadata?.optString("title")?.takeIf { it.isNotBlank() }
                ?: seriesTitleHint.ifBlank { identifier.replace("_", " ").replace("-", " ") }

            val rawDesc = parseCleanDescription(metadata)
            val translatedDesc = translateToCroatianSentenceBySentence(rawDesc)

            // Dohvat službenih TVmaze / IMDb naziva epizoda za seriju (mapa (sezona, epizoda) -> službeni naziv)
            val officialEpisodeTitles = fetchOfficialEpisodeTitles(showTitle)

            val filesArray = root.optJSONArray("files")
            val rawEpisodes = mutableListOf<SeriesEpisode>()

            if (filesArray != null) {
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

                    val (seasonNum, epNum) = parseSeasonAndEpisode("$name $rawTitle")

                    // Ako imamo službeni TVmaze/IMDb naziv za ovu sezonu i epizodu, koristimo ga
                    val officialName = if (seasonNum > 0 && epNum > 0) {
                        officialEpisodeTitles[seasonNum to epNum]
                    } else null

                    val cleanSeriesName = cleanSeriesTitleForSearch(showTitle)
                    val codeTag = if (seasonNum > 0 && epNum > 0) {
                        String.format("S%02dE%02d", seasonNum, epNum)
                    } else ""

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
                    val fileUrl = "https://archive.org/download/$identifier/$encodedName"

                    rawEpisodes.add(
                        SeriesEpisode(
                            seasonNumber = if (seasonNum > 0) seasonNum else 1,
                            episodeNumber = epNum,
                            title = displayTitle,
                            subtitleSearchQuery = subQuery.trim(),
                            fileUrl = fileUrl,
                            itemIdentifier = identifier,
                            durationText = durationText
                        )
                    )
                }
            }

            // Ako je riječ o kolekciji s više zasebnih stavki (collection:identifier)
            if (rawEpisodes.isEmpty()) {
                val searchUrl = "https://archive.org/advancedsearch.php?q=collection:$identifier+AND+mediatype:movies&fl[]=identifier&fl[]=title&fl[]=runtime&sort[]=title+asc&rows=120&output=json"
                val searchJson = JSONObject(URL(searchUrl).readText())
                val docs = searchJson.optJSONObject("response")?.optJSONArray("docs")

                if (docs != null) {
                    for (i in 0 until docs.length()) {
                        val doc = docs.optJSONObject(i) ?: continue
                        val epId = doc.optString("identifier").takeIf { it.isNotBlank() } ?: continue
                        val epTitle = doc.optString("title").takeIf { it.isNotBlank() }
                            ?: epId.replace("_", " ").replace("-", " ")
                        val runtime = doc.optString("runtime")

                        val (seasonNum, epNum) = parseSeasonAndEpisode("$epId $epTitle")
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

            // Grupiramo po sezonama i unutar svake sezone sortiramo epizode od 1. nadalje
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
                description = translatedDesc,
                seasons = groupedBySeason,
                episodes = allFlatEpisodes
            )
        } catch (e: Exception) {
            e.printStackTrace()
            SeriesFullInfo("Opis nije moguće učitati.", emptyList(), emptyList())
        }
    }

    // Parsira S01E02, 1x02, Season 1 Episode 2, S1 E2, E01 itd.
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
        val epOnly = Regex("(?i)(?:Episode|Ep\\.?)\\s*(\\d{1,3})\\b").find(text)
        if (epOnly != null) {
            return 1 to (epOnly.groupValues[1].toIntOrNull() ?: 0)
        }
        return 1 to 0
    }

    private fun cleanSeriesTitleForSearch(rawTitle: String): String {
        return rawTitle
            .replace(Regex("(?i)(complete series|full series|season\\s*\\d+|seasons\\s*\\d+.*|uncensored|collection|colorized|\\(\\d{4}\\))"), "")
            .replace(Regex("[^a-zA-Z0-9\\s']"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // Dohvaća službene TVmaze / IMDb nazive epizoda po sezonama i broju epizode
    private fun fetchOfficialEpisodeTitles(rawSeriesTitle: String): Map<Pair<Int, Int>, String> {
        return try {
            val cleanName = cleanSeriesTitleForSearch(rawSeriesTitle)
            if (cleanName.length < 3) return emptyMap()
            val encoded = URLEncoder.encode(cleanName, "UTF-8")
            val searchUrl = "https://api.tvmaze.com/singlesearch/shows?q=$encoded&embed=episodes"
            val conn = URL(searchUrl).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "MediaStudio/1.0")
            conn.connectTimeout = 4500
            conn.readTimeout = 4500
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
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

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
