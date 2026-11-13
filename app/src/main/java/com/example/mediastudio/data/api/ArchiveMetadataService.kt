package com.example.mediastudio.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

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
    val durationText: String
)

object ArchiveMetadataModule {
    suspend fun fetchMovieFullInfo(identifier: String): MovieFullInfo = withContext(Dispatchers.IO) {
        try {
            val jsonStr = URL("https://archive.org/metadata/$identifier").readText()
            val root = JSONObject(jsonStr)
            val metadata = root.optJSONObject("metadata")

            val rawDesc = when (val d = metadata?.opt("description")) {
                is String -> d
                is org.json.JSONArray -> (0 until d.length()).joinToString("\n") { d.optString(it) }
                else -> ""
            }
            val cleanDesc = rawDesc
                .replace(Regex("<[^>]*>"), " ")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&apos;", "'")
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifEmpty { "Opis za ovaj naslov trenutno nije dostupan." }

            val rawDate = metadata?.optString("date", "")?.takeIf { it.isNotEmpty() }
                ?: metadata?.optString("year", "") ?: ""
            val year = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(rawDate)?.value ?: ""

            val rawDirector = when (val c = metadata?.opt("director") ?: metadata?.opt("creator")) {
                is String -> c
                is org.json.JSONArray -> (0 until c.length()).joinToString(", ") { c.optString(it) }
                else -> ""
            }

            val filesArray = root.optJSONArray("files")
            val qualities = mutableListOf<VideoQualityOption>()

            if (filesArray != null) {
                for (i in 0 until filesArray.length()) {
                    val fileObj = filesArray.optJSONObject(i) ?: continue
                    val name = fileObj.optString("name")
                    val format = fileObj.optString("format")

                    if (name.endsWith(".mp4", ignoreCase = true) ||
                        format.contains("MPEG4", ignoreCase = true) ||
                        format.contains("h.264", ignoreCase = true)
                    ) {
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
                            java.net.URLEncoder.encode(part, "UTF-8").replace("+", "%20")
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

    suspend fun fetchSeriesEpisodes(identifier: String): List<SeriesEpisode> = withContext(Dispatchers.IO) {
        try {
            val jsonStr = URL("https://archive.org/metadata/$identifier").readText()
            val root = JSONObject(jsonStr)
            val filesArray = root.optJSONArray("files") ?: return@withContext emptyList()

            val rawEpisodes = mutableListOf<Pair<String, SeriesEpisode>>()

            for (i in 0 until filesArray.length()) {
                val fileObj = filesArray.optJSONObject(i) ?: continue
                val name = fileObj.optString("name")
                val source = fileObj.optString("source")

                if (!name.endsWith(".mp4", ignoreCase = true)) continue
                if (source.equals("derivative", ignoreCase = true) && name.contains("_512kb", ignoreCase = true)) continue

                val sizeBytes = fileObj.optString("size").toLongOrNull() ?: 0L
                if (sizeBytes < 5_000_000L) continue

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
                    java.net.URLEncoder.encode(part, "UTF-8").replace("+", "%20")
                }
                val fileUrl = "https://archive.org/download/$identifier/$encodedName"

                rawEpisodes.add(
                    name.lowercase() to SeriesEpisode(
                        episodeNumber = 0,
                        title = rawTitle,
                        fileUrl = fileUrl,
                        durationText = durationText
                    )
                )
            }

            rawEpisodes
                .distinctBy { it.second.title.lowercase() }
                .sortedBy { it.first }
                .mapIndexed { idx, pair ->
                    pair.second.copy(episodeNumber = idx + 1)
                }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
