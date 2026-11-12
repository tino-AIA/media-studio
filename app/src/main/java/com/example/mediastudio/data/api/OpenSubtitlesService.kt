package com.example.mediastudio.data.api

import com.squareup.moshi.Json
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.*

// Modeli za pretragu
data class SubtitleSearchResponse(val data: List<SubtitleData>?)
data class SubtitleData(val attributes: SubtitleAttributes?)
data class SubtitleAttributes(val files: List<SubtitleFile>?)
data class SubtitleFile(@Json(name = "file_id") val fileId: Int)

// Modeli za preuzimanje linka
data class DownloadRequest(@Json(name = "file_id") val fileId: Int)
data class DownloadResponse(val link: String?)

interface OpenSubtitlesApi {
    @Headers(
        "Api-Key: GZaTvvntJdvb7Awbymax5vI0Kwb0Wnjo",
        "User-Agent: MediaStudio v1.0",
        "Content-Type: application/json"
    )
    @GET("subtitles")
    suspend fun searchSubtitles(
        @Query("query") query: String,
        @Query("languages") languages: String = "hr"
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

    // Pomoćna funkcija koja čisti naziv (npr. miče godine u zagradama) i traži titl
    suspend fun fetchCroatianSubtitleUrl(rawTitle: String): String? {
        return try {
            // Čistimo naziv od zagrada (npr. "Night of the Living Dead (1968)" -> "Night of the Living Dead")
            val cleanTitle = rawTitle.replace(Regex("\\(.*?\\)"), "").trim()
            
            val searchResult = api.searchSubtitles(query = cleanTitle, languages = "hr")
            val fileId = searchResult.data?.firstOrNull()?.attributes?.files?.firstOrNull()?.fileId
            
            if (fileId != null) {
                val downloadResult = api.getDownloadLink(DownloadRequest(fileId))
                downloadResult.link
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
