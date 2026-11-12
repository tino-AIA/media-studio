package com.example.mediastudio.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ArchiveSearchResponse(
    @Json(name = "response") val response: SearchBody
)

@JsonClass(generateAdapter = true)
data class SearchBody(
    @Json(name = "docs") val docs: List<ArchiveMovie>
)

@JsonClass(generateAdapter = true)
data class ArchiveMovie(
    @Json(name = "identifier") val identifier: String,
    @Json(name = "title") val title: String,
    @Json(name = "year") val year: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "downloads") val downloads: Int? = 0
)
