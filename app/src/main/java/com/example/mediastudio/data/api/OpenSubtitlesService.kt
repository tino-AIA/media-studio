package com.example.mediastudio.data.api

import android.content.Context
import android.net.Uri
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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

data class SubtitleOption(
    val fileId: Int,
    val title: String,
    val fps: Float
)

interface OpenSubtitlesApi {
    @Headers(
        "Api-Key: GZaTvvntJdvb7Awbymax5vI0Kwb0Wnjo",
        "User-Agent: Mediastudio123 v1.0",
        "Accept: application/json",
        "Content-Type: application/json"
    )
    @GET("subtitles")
    suspend fun searchSubtitles(
        @Query("query") query: String,
        @Query("languages") languages: String = "hr,sr,bs"
    ): SubtitleSearchResponse

    @Headers(
        "Api-Key: GZaTvvntJdvb7Awbymax5vI0Kwb0Wnjo",
        "User-Agent: Mediastudio123 v1.0",
        "Accept: application/json",
        "Content-Type: application/json"
    )
    @POST("download")
    suspend fun getDownloadLink(
        @Body request: DownloadRequest
    ): DownloadResponse
}

object OpenSubtitlesModule {
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    val api: OpenSubtitlesApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.opensubtitles.com/api/v1/")
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(OpenSubtitlesApi::class.java)
    }

    suspend fun searchAvailableSubtitles(rawTitle: String): List<SubtitleOption> {
        return try {
            // Čistimo naslov od zagrada, godina i suvišnih znakova radi boljeg pronalaska
            val cleanTitle = rawTitle
                .replace(Regex("\\(.*?\\)"), "")
                .replace(Regex("\\[.*?\\]"), "")
                .replace(Regex("-\\s*\\d{4}"), "")
                .trim()

            val response = api.searchSubtitles(query = cleanTitle)
            
            response.data?.mapNotNull { item ->
                val attr = item.attributes ?: return@mapNotNull null
                val file = attr.files?.firstOrNull() ?: return@mapNotNull null
                val lang = attr.language?.uppercase() ?: "HR"
                val fpsValue = attr.fps ?: 0f
                val releaseName = attr.release ?: file.fileName ?: "Titl"
                
                SubtitleOption(
                    fileId = file.fileId,
                    title = "[$lang] $releaseName (${if (fpsValue > 0) "$fpsValue FPS" else "Auto FPS"})",
                    fps = fpsValue
                )
            } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun downloadAndFixEncoding(context: Context, fileId: Int): Uri? = withContext(Dispatchers.IO) {
        try {
            val downloadResult = api.getDownloadLink(DownloadRequest(fileId))
            val link = downloadResult.link ?: return@withContext null

            val bytes = URL(link).readBytes()
            val utf8String = String(bytes, Charsets.UTF_8)
            val finalText = if (utf8String.contains("\uFFFD")) {
                String(bytes, Charset.forName("Windows-1250"))
            } else {
                utf8String
            }

            val tempFile = File(context.cacheDir, "active_subtitle.srt")
            tempFile.writeText(finalText, Charsets.UTF_8)
            Uri.fromFile(tempFile)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
