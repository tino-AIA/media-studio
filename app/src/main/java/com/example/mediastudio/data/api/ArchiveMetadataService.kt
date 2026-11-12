package com.example.mediastudio.data.api

import android.net.Uri
import android.text.Html
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path

data class ArchiveMetadataResponse(
    val files: List<ArchiveFileItem>?,
    val metadata: ArchiveItemDetails?
)

data class ArchiveItemDetails(
    val title: Any?,
    val description: Any?,
    val year: Any?,
    val director: Any?,
    val creator: Any?,
    val subject: Any?
)

data class ArchiveFileItem(
    val name: String?,
    val format: String?,
    val size: String?,
    val height: String?,
    val width: String?
)

data class VideoQualityOption(
    val label: String,
    val url: String,
    val height: Int
)

data class MovieFullInfo(
    val description: String,
    val year: String,
    val director: String,
    val genres: String,
    val qualities: List<VideoQualityOption>
)

interface ArchiveMetadataApi {
    @GET("metadata/{identifier}")
    suspend fun getMetadata(@Path("identifier") identifier: String): ArchiveMetadataResponse
}

object ArchiveMetadataModule {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val api: ArchiveMetadataApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://archive.org/")
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ArchiveMetadataApi::class.java)
    }

    suspend fun fetchMovieFullInfo(identifier: String): MovieFullInfo {
        val fallbackUrl = "https://archive.org/download/$identifier/format=h.264"
        return try {
            val response = api.getMetadata(identifier)
            val meta = response.metadata

            // Čišćenje opisa od HTML tagova ako postoje
            val rawDesc = extractString(meta?.description)
            val cleanDesc = if (rawDesc.isNotBlank()) {
                Html.fromHtml(rawDesc, Html.FROM_HTML_MODE_COMPACT).toString().trim()
            } else {
                "Nema dostupnog opisa radnje za ovaj film."
            }

            val yearStr = extractString(meta?.year)
            val directorStr = extractString(meta?.director).ifBlank { extractString(meta?.creator) }
            val genresStr = extractString(meta?.subject)

            // Dohvat kvaliteta videa
            val allFiles = response.files ?: emptyList()
            val videoFiles = allFiles.filter { file ->
                val name = file.name?.lowercase() ?: ""
                val isVideoExt = name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi")
                val isNotMeta = !(file.format?.lowercase()?.contains("metadata") ?: false)
                isVideoExt && isNotMeta
            }

            val largeFiles = videoFiles.filter { (it.size?.toLongOrNull() ?: 0L) > 15_000_000L }
            val targetFiles = largeFiles.ifEmpty { videoFiles }

            val options = targetFiles.mapNotNull { file ->
                val fileName = file.name ?: return@mapNotNull null
                val heightInt = file.height?.toIntOrNull() ?: guessHeightFromName(fileName)
                val sizeFormatted = formatFileSize(file.size?.toLongOrNull() ?: 0L)
                val resLabel = if (heightInt > 0) "${heightInt}p" else "SD"
                val displayLabel = if (sizeFormatted.isNotEmpty()) "$resLabel ($sizeFormatted)" else resLabel
                val encodedFileName = Uri.encode(fileName).replace("%2F", "/")

                VideoQualityOption(
                    label = displayLabel,
                    url = "https://archive.org/download/$identifier/$encodedFileName",
                    height = heightInt
                )
            }.distinctBy { it.url }.sortedByDescending { it.height }

            MovieFullInfo(
                description = cleanDesc,
                year = yearStr,
                director = directorStr,
                genres = genresStr,
                qualities = options.ifEmpty { listOf(VideoQualityOption("Auto", fallbackUrl, 0)) }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            MovieFullInfo(
                description = "Opis trenutno nije dostupan.",
                year = "",
                director = "",
                genres = "",
                qualities = listOf(VideoQualityOption("Auto", fallbackUrl, 0))
            )
        }
    }

    suspend fun fetchAvailableQualities(identifier: String): List<VideoQualityOption> {
        return fetchMovieFullInfo(identifier).qualities
    }

    // Archive.org nekad vraća String, a nekad List<String> za isto polje
    private fun extractString(value: Any?): String {
        return when (value) {
            is String -> value
            is List<*> -> value.filterNotNull().joinToString(", ")
            else -> ""
        }
    }

    private fun guessHeightFromName(name: String): Int {
        val lower = name.lowercase()
        return when {
            lower.contains("1080") -> 1080
            lower.contains("720") -> 720
            lower.contains("480") -> 480
            lower.contains("360") -> 360
            else -> 0
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) String.format("%.1f GB", mb / 1024.0) else String.format("%.0f MB", mb)
    }
}
