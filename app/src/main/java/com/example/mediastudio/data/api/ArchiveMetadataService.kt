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
    val episodeNumber: Int,
    val title: String,
    val fileUrl: String,
    val itemIdentifier: String = "",
    val durationText: String = ""
)

data class SeriesFullInfo(
    val description: String,
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

    suspend fun fetchSeriesFullInfo(identifier: String): SeriesFullInfo = withContext(Dispatchers.IO) {
        try {
            val jsonStr = URL("https://archive.org/metadata/$identifier").readText()
            val root = JSONObject(jsonStr)
            val metadata = root.optJSONObject("metadata")
            val rawDesc = parseCleanDescription(metadata)
            val translatedDesc = translateToCroatianSentenceBySentence(rawDesc)

            val filesArray = root.optJSONArray("files")
            val rawEpisodes = mutableListOf<Pair<String, SeriesEpisode>>()

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

                    val rawTitle = fileObj.optString("title").takeIf { it.isNotBlank() }
                        ?: name.substringAfterLast("/").substringBeforeLast(".")
                            .replace("_", " ")
                            .replace("-", " ")
                            .trim()

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
                        name.lowercase() to SeriesEpisode(
                            episodeNumber = 0,
                            title = rawTitle,
                            fileUrl = fileUrl,
                            itemIdentifier = identifier,
                            durationText = durationText
                        )
                    )
                }
            }

            if (rawEpisodes.isEmpty()) {
                val searchUrl = "https://archive.org/advancedsearch.php?q=collection:$identifier+AND+mediatype:movies&fl[]=identifier&fl[]=title&fl[]=runtime&sort[]=title+asc&rows=100&output=json"
                val searchJson = JSONObject(URL(searchUrl).readText())
                val docs = searchJson.optJSONObject("response")?.optJSONArray("docs")

                if (docs != null) {
                    for (i in 0 until docs.length()) {
                        val doc = docs.optJSONObject(i) ?: continue
                        val epId = doc.optString("identifier").takeIf { it.isNotBlank() } ?: continue
                        val epTitle = doc.optString("title").takeIf { it.isNotBlank() }
                            ?: epId.replace("_", " ").replace("-", " ")
                        val runtime = doc.optString("runtime")

                        val bestUrl = fetchAvailableQualities(epId).firstOrNull()?.url
                            ?: "https://archive.org/download/$epId/$epId.mp4"

                        rawEpisodes.add(
                            epTitle.lowercase() to SeriesEpisode(
                                episodeNumber = 0,
                                title = epTitle,
                                fileUrl = bestUrl,
                                itemIdentifier = epId,
                                durationText = runtime
                            )
                        )
                    }
                }
            }

            val finalEpisodes = rawEpisodes
                .sortedWith(compareBy<Pair<String, SeriesEpisode>> { it.first }
                    .thenBy { if (it.second.fileUrl.endsWith(".mp4", true)) 0 else 1 })
                .distinctBy { it.second.title.lowercase() }
                .mapIndexed { idx, pair ->
                    pair.second.copy(episodeNumber = idx + 1)
                }

            SeriesFullInfo(
                description = translatedDesc,
                episodes = finalEpisodes
            )
        } catch (e: Exception) {
            e.printStackTrace()
            SeriesFullInfo("Opis nije moguće učitati.", emptyList())
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
