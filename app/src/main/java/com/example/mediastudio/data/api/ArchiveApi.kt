package com.example.mediastudio.data.api

import com.example.mediastudio.data.model.ArchiveSearchResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface ArchiveApi {
    @GET("advancedsearch.php")
    suspend fun searchMovies(
        @Query("q") query: String,
        @Query("sort[]") sort: String = "-downloads",
        @Query("rows") rows: Int = 20,
        @Query("output") output: String = "json"
    ): ArchiveSearchResponse
}
