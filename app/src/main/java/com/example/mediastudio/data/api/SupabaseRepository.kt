package com.example.mediastudio.data.api

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

data class SupabaseEpisodeItem(
    val title: String,
    val url: String
)

data class SupabaseCatalogItem(
    val id: Long,
    val archiveId: String,
    val tmdbId: Int,
    val title: String,
    val mediaType: String,
    val genres: List<String>,
    val releaseYear: Int,
    val rating: Double,
    val popularityScore: Double,
    val descriptionHr: String,
    val posterUrl: String,
    val backdropUrl: String,
    val streamUrl: String,
    val episodes: List<SupabaseEpisodeItem>,
    val isFeatured: Boolean,
    val isValid: Boolean = true,
    val tmdbRuntime: Int = 0,
    val archiveRuntime: Int = 0
)

data class SupabaseAuthor(
    val id: Long,
    val name: String,
    val archiveId: String,
    val bioHr: String,
    val photoUrl: String,
    val sortOrder: Int
)

data class SupabaseAuthorMovie(
    val id: Long,
    val authorId: Long,
    val title: String,
    val releaseYear: Int,
    val streamUrl: String,
    val posterUrl: String,
    val descriptionHr: String
)

object SupabaseRepository {
    private const val SUPABASE_REST_URL = "https://jpjezjcbayontfwywvay.supabase.co/rest/v1"
    private const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_d8MgyOYxbVSNcU6niaAGAQ_hlCwfwh5"

    private var prefs: SharedPreferences? = null

    @Volatile
    private var catalogItems: List<SupabaseCatalogItem> = emptyList()

    @Volatile
    private var authors: List<SupabaseAuthor> = emptyList()

    @Volatile
    private var authorMovies: List<SupabaseAuthorMovie> = emptyList()

    private val posterByArchiveId = mutableMapOf<String, String>()
    private val posterByTitle = mutableMapOf<String, String>()
    private val itemByArchiveId = mutableMapOf<String, SupabaseCatalogItem>()
    private val itemByStreamUrl = mutableMapOf<String, SupabaseCatalogItem>()
    private val authorMovieByStreamUrl = mutableMapOf<String, SupabaseAuthorMovie>()

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences("SupabaseCatalogCache", Context.MODE_PRIVATE)
            loadFromDiskCache()
        }
    }

    private fun loadFromDiskCache() {
        val p = prefs ?: return
        p.getString("catalog_json", null)?.let { jsonStr ->
            try {
                updateCatalogMemory(parseCatalogArray(JSONArray(jsonStr)))
            } catch (_: Exception) {}
        }
        p.getString("authors_json", null)?.let { jsonStr ->
            try {
                authors = parseAuthorsArray(JSONArray(jsonStr))
            } catch (_: Exception) {}
        }
        p.getString("author_movies_json", null)?.let { jsonStr ->
            try {
                updateAuthorMoviesMemory(parseAuthorMoviesArray(JSONArray(jsonStr)))
            } catch (_: Exception) {}
        }
    }

    suspend fun syncFromSupabase(): Boolean = withContext(Dispatchers.IO) {
        var anySuccess = false

        val catalogArr = fetchJsonArray("catalog_items?select=*&is_valid=eq.true&order=popularity_score.desc&limit=1500")
        if (catalogArr != null && catalogArr.length() > 0) {
            prefs?.edit()?.putString("catalog_json", catalogArr.toString())?.apply()
            updateCatalogMemory(parseCatalogArray(catalogArr))
            anySuccess = true
        }

        val authorsArr = fetchJsonArray("authors?select=*&order=sort_order.asc&limit=200")
        if (authorsArr != null && authorsArr.length() > 0) {
            prefs?.edit()?.putString("authors_json", authorsArr.toString())?.apply()
            authors = parseAuthorsArray(authorsArr)
            anySuccess = true
        }

        val authorMoviesArr = fetchJsonArray("author_movies?select=*&order=release_year.desc&limit=3000")
        if (authorMoviesArr != null && authorMoviesArr.length() > 0) {
            prefs?.edit()?.putString("author_movies_json", authorMoviesArr.toString())?.apply()
            updateAuthorMoviesMemory(parseAuthorMoviesArray(authorMoviesArr))
            anySuccess = true
        }

        anySuccess || catalogItems.isNotEmpty()
    }

    private fun updateCatalogMemory(items: List<SupabaseCatalogItem>) {
        val validItems = items.filter { it.isValid }
        catalogItems = validItems
        itemByArchiveId.clear()
        itemByStreamUrl.clear()
        posterByArchiveId.clear()

        for (item in validItems) {
            itemByArchiveId[item.archiveId] = item
            if (item.streamUrl.isNotBlank()) {
                itemByStreamUrl[item.streamUrl] = item
            }
            if (item.posterUrl.isNotBlank()) {
                posterByArchiveId[item.archiveId] = item.posterUrl
                posterByTitle[item.title.lowercase().trim()] = item.posterUrl
            }
        }
    }

    private fun updateAuthorMoviesMemory(movies: List<SupabaseAuthorMovie>) {
        authorMovies = movies
        for (m in movies) {
            if (m.streamUrl.isNotBlank()) {
                authorMovieByStreamUrl[m.streamUrl] = m
            }
            if (m.posterUrl.isNotBlank()) {
                posterByTitle[m.title.lowercase().trim()] = m.posterUrl
            }
        }
    }

    fun getCatalogItems(): List<SupabaseCatalogItem> = catalogItems

    fun getAuthors(): List<SupabaseAuthor> = authors

    fun getAuthorWithMovies(authorName: String): Pair<SupabaseAuthor?, List<SupabaseAuthorMovie>> {
        val author = authors.find { it.name.equals(authorName, ignoreCase = true) }
        val movies = if (author != null) {
            authorMovies.filter { it.authorId == author.id }
                .sortedByDescending { it.releaseYear }
        } else {
            emptyList()
        }
        return author to movies
    }

    fun getCatalogItem(archiveId: String): SupabaseCatalogItem? {
        val cleanId = archiveId.split(",").first().trim()
        return itemByArchiveId[cleanId]
    }

    fun findByStreamUrl(streamUrl: String): Pair<String, String>? {
        authorMovieByStreamUrl[streamUrl]?.let {
            return it.posterUrl to it.descriptionHr
        }
        itemByStreamUrl[streamUrl]?.let {
            return it.posterUrl to it.descriptionHr
        }
        return null
    }

    fun getCachedPoster(archiveId: String, title: String): String? {
        val cleanId = archiveId.split(",").first().trim()
        posterByArchiveId[cleanId]?.takeIf { it.isNotBlank() }?.let { return it }

        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").lowercase().trim()
        posterByTitle[cleanTitle]?.takeIf { it.isNotBlank() }?.let { return it }

        return null
    }

    private fun fetchJsonArray(endpointWithQuery: String): JSONArray? {
        return try {
            val url = URL("$SUPABASE_REST_URL/$endpointWithQuery")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                JSONArray(body)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseCatalogArray(arr: JSONArray): List<SupabaseCatalogItem> {
        val list = ArrayList<SupabaseCatalogItem>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val isValid = obj.optBoolean("is_valid", true)
            if (!isValid) continue

            val genresArr = obj.optJSONArray("genres")
            val genres = mutableListOf<String>()
            if (genresArr != null) {
                for (g in 0 until genresArr.length()) {
                    genresArr.optString(g)?.takeIf { it.isNotBlank() }?.let { genres.add(it) }
                }
            }

            val epsArr = obj.optJSONArray("episodes")
            val episodes = mutableListOf<SupabaseEpisodeItem>()
            if (epsArr != null) {
                for (e in 0 until epsArr.length()) {
                    val epObj = epsArr.optJSONObject(e) ?: continue
                    val epUrl = epObj.optString("url", "")
                    if (epUrl.isNotBlank()) {
                        episodes.add(
                            SupabaseEpisodeItem(
                                title = epObj.optString("title", "Epizoda ${e + 1}"),
                                url = epUrl
                            )
                        )
                    }
                }
            }

            list.add(
                SupabaseCatalogItem(
                    id = obj.optLong("id"),
                    archiveId = obj.optString("archive_id", ""),
                    tmdbId = obj.optInt("tmdb_id", 0),
                    title = obj.optString("title", ""),
                    mediaType = obj.optString("media_type", "movie"),
                    genres = genres,
                    releaseYear = obj.optInt("release_year", 0),
                    rating = obj.optDouble("rating", 0.0),
                    popularityScore = obj.optDouble("popularity_score", 0.0),
                    descriptionHr = obj.optString("description_hr", ""),
                    posterUrl = obj.optString("poster_url", ""),
                    backdropUrl = obj.optString("backdrop_url", ""),
                    streamUrl = obj.optString("stream_url", ""),
                    episodes = episodes,
                    isFeatured = obj.optBoolean("is_featured", false),
                    isValid = isValid,
                    tmdbRuntime = obj.optInt("tmdb_runtime", 0),
                    archiveRuntime = obj.optInt("archive_runtime", 0)
                )
            )
        }
        return list
    }

    private fun parseAuthorsArray(arr: JSONArray): List<SupabaseAuthor> {
        val list = ArrayList<SupabaseAuthor>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(
                SupabaseAuthor(
                    id = obj.optLong("id"),
                    name = obj.optString("name", ""),
                    archiveId = obj.optString("archive_id", ""),
                    bioHr = obj.optString("bio_hr", ""),
                    photoUrl = obj.optString("photo_url", ""),
                    sortOrder = obj.optInt("sort_order", 999)
                )
            )
        }
        return list
    }

    private fun parseAuthorMoviesArray(arr: JSONArray): List<SupabaseAuthorMovie> {
        val list = ArrayList<SupabaseAuthorMovie>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(
                SupabaseAuthorMovie(
                    id = obj.optLong("id"),
                    authorId = obj.optLong("author_id"),
                    title = obj.optString("title", ""),
                    releaseYear = obj.optInt("release_year", 0),
                    streamUrl = obj.optString("stream_url", ""),
                    posterUrl = obj.optString("poster_url", ""),
                    descriptionHr = obj.optString("description_hr", "")
                )
            )
        }
        return list
    }
}
