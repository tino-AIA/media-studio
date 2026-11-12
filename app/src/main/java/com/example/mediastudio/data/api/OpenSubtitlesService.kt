package com.example.mediastudio.data.api

import android.content.Context
import android.net.Uri
import com.squareup.moshi.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.*
import java.io.File
import java.net.URL
import java.nio.charset.Charset

data class SubtitleSearchResponse(val data: List<SubtitleData>?)
data class SubtitleData(val attributes: SubtitleAttributes?)
data class SubtitleAttributes(
    val release: String?,
    val fps: Float?,
    val language: String?,
    val files: List<SubtitleFile>?
)
data class SubtitleFile(
    @Json(name = "file_id") val fileId: Int,
    @Json(name = "file_name") val fileName: String?
)

data class DownloadRequest(@Json(name = "file_id") val fileId: Int)
data class DownloadResponse(val link: String?)

// Naš pojednostavljeni model za prikaz u izborniku playera
data class SubtitleOption(
    val fileId: Int,
    val title: String,
    val fps: Float
)

interface OpenSubtitlesApi {
    @Headers(
        "Api-Key: GZaTvvntJdvb7Awbymax5vI0Kwb0Wnjo",
        "User-Agent: MediaStudio v1.0",
        "Content-Type: application/json"
    )
    @GET("subtitles")
    suspend fun searchSubtitles(
        @Query("query") query: String,
        @Query("languages") languages: String = "hr,sr,bs" // Uključujemo i srodne jezike za bolji izbor
    ): SubtitleSearchResponse

    @Headers(
        "Api-Key: GZaTvvntJdvb7Awbymax5vI0Kwb0Wnjo",
        "User-Agent: MediaStudio v1.0",
        "Content-Type: application/json"
    )
    @POST("download")
    suspend fun getDownloadLink(
        @Body request: DownloadRequest
    ): DownloadResponse
}

object OpenSubtitlesModule {
    val api: OpenSubtitlesApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.opensubtitles.com/api/v1/")
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(OpenSubtitlesApi::class.java)
    }

    // 1. Dohvaća listu dostupnih titlova s informacijom o FPS-u
    suspend fun searchAvailableSubtitles(rawTitle: String): List<SubtitleOption> {
        return try {
            val cleanTitle = rawTitle.replace(Regex("\\(.*?\\)"), "").trim()
            val response = api.searchSubtitles(query = cleanTitle)
            
            response.data?.mapNotNull { item ->
                val attr = item.attributes ?: return@mapNotNull null
                val file = attr.files?.firstOrNull() ?: return@mapNotNull null
                val lang = attr.language?.uppercase() ?: "HR"
                val fpsValue = attr.fps ?: 0f
                val releaseName = attr.release ?: file.fileName ?: "Nepoznato izdanje"
                
                SubtitleOption(
                    fileId = file.fileId,
                    title = "[$lang] $releaseName (${if (fpsValue > 0) "$fpsValue FPS" else "FPS nepoznat"})",
                    fps = fpsValue
                )
            } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // 2. Preuzima odabrani titl, popravlja čćžšđ (Windows-1250 -> UTF-8) i sprema u cache
    suspend fun downloadAndFixEncoding(context: Context, fileId: Int): Uri? = withContext(Dispatchers.IO) {
        try {
            val downloadResult = api.getDownloadLink(DownloadRequest(fileId))
            val link = downloadResult.link ?: return@withContext null

            val bytes = URL(link).readBytes()
            
            // Provjera je li datoteka već UTF-8 ili je stariji Windows-1250 format
            val utf8String = String(bytes, Charsets.UTF_8)
            val finalText = if (utf8String.contains("\uFFFD")) {
                // Ako sadrži znak zamjene (), dekodiramo kao Windows-1250
                String(bytes, Charset.forName("Windows-1250"))
            } else {
                utf8String
            }

            // Spremanje čistog UTF-8 titla u privremenu memoriju aplikacije
            val tempFile = File(context.cacheDir, "active_subtitle.srt")
            tempFile.writeText(finalText, Charsets.UTF_8)
            
            Uri.fromFile(tempFile)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
