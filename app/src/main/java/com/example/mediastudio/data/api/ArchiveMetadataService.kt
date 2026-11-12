package com.example.mediastudio.data.api

import android.net.Uri
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path

data class ArchiveMetadataResponse(
    val files: List<ArchiveFileItem>?
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

interface ArchiveMetadataApi {
    @GET("metadata/{identifier}")
    suspend fun getMetadata(@Path("identifier") identifier: String): ArchiveMetadataResponse
}

object ArchiveMetadataModule {
    private val api: ArchiveMetadataApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://archive.org/")
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(ArchiveMetadataApi::class.java)
    }

    suspend fun fetchAvailableQualities(identifier: String): List<VideoQualityOption> {
        val fallbackUrl = "https://archive.org/download/$identifier/format=h.264"
        return try {
            val response = api.getMetadata(identifier)
            val allFiles = response.files ?: return listOf(VideoQualityOption("Auto", fallbackUrl, 0))

            // Filtriramo video datoteke
            val videoFiles = allFiles.filter { file ->
                val name = file.name?.lowercase() ?: ""
                val isVideoExt = name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi")
                val isDerivativeOrOriginal = !(file.format?.lowercase()?.contains("metadata") ?: false)
                isVideoExt && isDerivativeOrOriginal
            }

            if (videoFiles.isEmpty()) {
                return listOf(VideoQualityOption("Auto", fallbackUrl, 0))
            }

            // Ignoriramo kratke trailere/sampleove (< 15 MB) ako postoje pravi dugometražni fajlovi
            val largeFiles = videoFiles.filter { (it.size?.toLongOrNull() ?: 0L) > 15_000_000L }
            val targetFiles = largeFiles.ifEmpty { videoFiles }

            val options = targetFiles.mapNotNull { file ->
                val fileName = file.name ?: return@mapNotNull null
                val heightInt = file.height?.toIntOrNull() ?: guessHeightFromName(fileName)
                val sizeBytes = file.size?.toLongOrNull() ?: 0L
                val sizeFormatted = formatFileSize(sizeBytes)

                val resLabel = if (heightInt > 0) "${heightInt}p" else "SD"
                val displayLabel = if (sizeFormatted.isNotEmpty()) "$resLabel ($sizeFormatted)" else resLabel

                // Kodiramo naziv datoteke za siguran URL (npr. razmaci postaju %20)
                val encodedFileName = Uri.encode(fileName).replace("%2F", "/")
                val fullUrl = "https://archive.org/download/$identifier/$encodedFileName"

                VideoQualityOption(
                    label = displayLabel,
                    url = fullUrl,
                    height = heightInt
                )
            }
                .distinctBy { it.url }
                .sortedByDescending { it.height } // Najviša rezolucija ide prva

            options.ifEmpty { listOf(VideoQualityOption("Auto", fallbackUrl, 0)) }
        } catch (e: Exception) {
            e.printStackTrace()
            listOf(VideoQualityOption("Auto", fallbackUrl, 0))
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
        return if (mb >= 1024.0) {
            String.format("%.1f GB", mb / 1024.0)
        } else {
            String.format("%.0f MB", mb)
        }
    }
}
