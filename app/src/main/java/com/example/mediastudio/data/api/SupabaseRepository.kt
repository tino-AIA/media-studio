package com.example.mediastudio.data.api

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class SupabaseEpisodeItem(
    val season: Int = 1,
    val episode: Int = 0,
    val title: String,
    val url: String,
    val duration: String = ""
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

    private const val CATALOG_LITE_COLUMNS =
        "id,archive_id,tmdb_id,title,media_type,genres,release_year,rating,popularity_score," +
            "description_hr,poster_url,backdrop_url,stream_url,is_featured,is_valid,tmdb_runtime,archive_runtime"

    private var cacheDir: File? = null

    @Volatile
    private var catalogItems: List<SupabaseCatalogItem> = emptyList()

    @Volatile
    private var authors: List<SupabaseAuthor> = emptyList()

    @Volatile
    private var authorMovies: List<SupabaseAuthorMovie> = emptyList()

    private val posterByArchiveId = ConcurrentHashMap<String, String>()
    private val posterByTitle = ConcurrentHashMap<String, String>()
    private val itemByArchiveId = ConcurrentHashMap<String, SupabaseCatalogItem>()
    private val itemByStreamUrl = ConcurrentHashMap<String, SupabaseCatalogItem>()
    private val authorMovieByStreamUrl = ConcurrentHashMap<String, SupabaseAuthorMovie>()
    private val episodesByArchiveId = ConcurrentHashMap<String, List<SupabaseEpisodeItem>>()

    fun init(context: Context) {
        if (cacheDir == null) {
            val dir = context.applicationContext.filesDir
            cacheDir = dir
            // Obriši staru preveliku predmemoriju koja je sadržavala sve epizode u RAM-u
            try {
                File(dir, "supabase_catalog_cache.json").delete()
            } catch (_: Exception) {
            }
            loadFromDiskCache()
        }
    }

    private fun readCacheFile(fileName: String): String? {
        val dir = cacheDir ?: return null
        val file = File(dir, fileName)
        return try {
            if (file.exists() && file.length() > 2) file.readText(Charsets.UTF_8) else null
        } catch (_: Exception) {
            null
        }
    }

    private fun writeCacheFile(fileName: String, content: String) {
        val dir = cacheDir ?: return
        try {
            File(dir, fileName).writeText(content, Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    private fun loadFromDiskCache() {
        readCacheFile("supabase_catalog_lite_v2.json")?.let { jsonStr ->
            try {
                updateCatalogMemory(parseCatalogArray(JSONArray(jsonStr)))
            } catch (_: Exception) {
            }
        }
        readCacheFile("supabase_authors_cache.json")?.let { jsonStr ->
            try {
                authors = parseAuthorsArray(JSONArray(jsonStr))
            } catch (_: Exception) {
            }
        }
        readCacheFile("supabase_author_movies_cache.json")?.let { jsonStr ->
            try {
                updateAuthorMoviesMemory(parseAuthorMoviesArray(JSONArray(jsonStr)))
            } catch (_: Exception) {
            }
        }
    }

    suspend fun syncFromSupabase(): Boolean = withContext(Dispatchers.IO) {
        var anySuccess = false

        val catalogArr = fetchAllPaginated(
            "catalog_items?select=$CATALOG_LITE_COLUMNS&is_valid=eq.true&order=popularity_score.desc"
        )
        if (catalogArr != null && catalogArr.length() > 0) {
            writeCacheFile("supabase_catalog_lite_v2.json", catalogArr.toString())
            updateCatalogMemory(parseCatalogArray(catalogArr))
            anySuccess = true
        }

        val authorsArr = fetchAllPaginated("authors?select=*&order=sort_order.asc")
        if (authorsArr != null && authorsArr.length() > 0) {
            writeCacheFile("supabase_authors_cache.json", authorsArr.toString())
            authors = parseAuthorsArray(authorsArr)
            anySuccess = true
        }

        val authorMoviesArr = fetchAllPaginated("author_movies?select=*&order=release_year.desc")
        if (authorMoviesArr != null && authorMoviesArr.length() > 0) {
            writeCacheFile("supabase_author_movies_cache.json", authorMoviesArr.toString())
            updateAuthorMoviesMemory(parseAuthorMoviesArray(authorMoviesArr))
            anySuccess = true
        }

        anySuccess || catalogItems.isNotEmpty()
    }

    suspend fun loadSeriesEpisodes(archiveId: String): List<SupabaseEpisodeItem> = withContext(Dispatchers.IO) {
        val cleanId = archiveId.substringBefore(",tmdb-").trim()
        episodesByArchiveId[archiveId]?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        episodesByArchiveId[cleanId]?.takeIf { it.isNotEmpty() }?.let { return@withContext it }

        try {
            val encodedId = URLEncoder.encode(cleanId, "UTF-8")
            val arr = fetchJsonArrayPage("catalog_items?select=episodes&archive_id=eq.$encodedId&limit=1", 0, 0)
            val obj = arr?.optJSONObject(0)
            val parsed = parseEpisodesArray(obj?.optJSONArray("episodes"))
            if (parsed.isNotEmpty()) {
                episodesByArchiveId[archiveId] = parsed
                episodesByArchiveId[cleanId] = parsed
            }
            parsed
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun updateCatalogMemory(items: List<SupabaseCatalogItem>) {
        val validItems = items.filter { it.isValid }
        catalogItems = validItems

        val nextByArchive = HashMap<String, SupabaseCatalogItem>(validItems.size)
        val nextByStream = HashMap<String, SupabaseCatalogItem>(validItems.size)
        val nextPosterById = HashMap<String, String>(validItems.size)

        for (item in validItems) {
            nextByArchive[item.archiveId] = item
            val baseId = item.archiveId.substringBefore(",tmdb-").trim()
            if (baseId.isNotEmpty() && !nextByArchive.containsKey(baseId)) {
                nextByArchive[baseId] = item
            }

            if (item.streamUrl.isNotBlank()) {
                nextByStream[item.streamUrl] = item
            }
            if (item.posterUrl.isNotBlank()) {
                nextPosterById[item.archiveId] = item.posterUrl
                if (baseId.isNotEmpty() && !nextPosterById.containsKey(baseId)) {
                    nextPosterById[baseId] = item.posterUrl
                }
                posterByTitle[item.title.lowercase().trim()] = item.posterUrl
            }
        }

        itemByArchiveId.clear()
        itemByArchiveId.putAll(nextByArchive)
        itemByStreamUrl.clear()
        itemByStreamUrl.putAll(nextByStream)
        posterByArchiveId.clear()
        posterByArchiveId.putAll(nextPosterById)
    }

    private fun updateAuthorMoviesMemory(movies: List<SupabaseAuthorMovie>) {
        authorMovies = movies
        val nextByStream = HashMap<String, SupabaseAuthorMovie>(movies.size)
        for (m in movies) {
            if (m.streamUrl.isNotBlank()) {
                nextByStream[m.streamUrl] = m
            }
            if (m.posterUrl.isNotBlank()) {
                posterByTitle[m.title.lowercase().trim()] = m.posterUrl
            }
        }
        authorMovieByStreamUrl.clear()
        authorMovieByStreamUrl.putAll(nextByStream)
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
        val cleanId = archiveId.substringBefore(",tmdb-").trim()
        val base = itemByArchiveId[archiveId] ?: itemByArchiveId[cleanId] ?: return null
        val cachedEps = episodesByArchiveId[base.archiveId] ?: episodesByArchiveId[cleanId]
        return if (!cachedEps.isNullOrEmpty() && base.episodes.isEmpty()) {
            base.copy(episodes = cachedEps)
        } else {
            base
        }
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
        posterByArchiveId[archiveId]?.takeIf { it.isNotBlank() }?.let { return it }

        val cleanId = archiveId.substringBefore(",tmdb-").trim()
        posterByArchiveId[cleanId]?.takeIf { it.isNotBlank() }?.let { return it }

        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").lowercase().trim()
        posterByTitle[cleanTitle]?.takeIf { it.isNotBlank() }?.let { return it }

        return null
    }

    private fun fetchAllPaginated(endpointWithQuery: String): JSONArray? {
        val combined = JSONArray()
        val pageSize = 1000
        var offset = 0
        var anyRequestSucceeded = false

        while (true) {
            val batch = fetchJsonArrayPage(endpointWithQuery, offset, offset + pageSize - 1) ?: break
            anyRequestSucceeded = true
            for (i in 0 until batch.length()) {
                combined.put(batch.get(i))
            }
            if (batch.length() < pageSize) {
                break
            }
            offset += pageSize
            if (offset >= 20000) break
        }
        return if (anyRequestSucceeded) combined else null
    }

    private fun fetchJsonArrayPage(endpointWithQuery: String, rangeStart: Int, rangeEnd: Int): JSONArray? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("$SUPABASE_REST_URL/$endpointWithQuery")
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("apikey", SUPABASE_PUBLISHABLE_KEY)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Range-Unit", "items")
            conn.setRequestProperty("Range", "$rangeStart-$rangeEnd")
            conn.connectTimeout = 10000
            conn.readTimeout = 12000
            val code = conn.responseCode
            if (code == 200 || code == 206) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                JSONArray(body)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun parseEpisodesArray(epsArr: JSONArray?): List<SupabaseEpisodeItem> {
        if (epsArr == null || epsArr.length() == 0) return emptyList()
        val episodes = ArrayList<SupabaseEpisodeItem>(epsArr.length())
        for (e in 0 until epsArr.length()) {
            val epObj = epsArr.optJSONObject(e) ?: continue
            val epUrl = epObj.optString("url", "")
            if (epUrl.isNotBlank()) {
                episodes.add(
                    SupabaseEpisodeItem(
                        season = epObj.optInt("season", 1).coerceAtLeast(1),
                        episode = epObj.optInt("episode", e + 1),
                        title = epObj.optString("title", "Epizoda ${e + 1}"),
                        url = epUrl,
                        duration = epObj.optString("duration", "")
                    )
                )
            }
        }
        return episodes
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
                    episodes = parseEpisodesArray(obj.optJSONArray("episodes")),
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
