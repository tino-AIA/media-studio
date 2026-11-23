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

    // Samo lagana polja potrebna za kartice i filtriranje (bez opisnih tekstova, backdropa i epizoda)
    private const val CATALOG_ULTRA_LITE_COLUMNS =
        "id,archive_id,tmdb_id,title,media_type,genres,release_year,rating,popularity_score," +
            "poster_url,stream_url,is_featured,is_valid,tmdb_runtime,archive_runtime"

    private var filesDirRef: File? = null
    private var cacheDirRef: File? = null

    @Volatile
    private var catalogItems: List<SupabaseCatalogItem> = emptyList()

    @Volatile
    private var authors: List<SupabaseAuthor> = emptyList()

    private val posterByArchiveId = ConcurrentHashMap<String, String>()
    private val posterByTitle = ConcurrentHashMap<String, String>()
    private val itemByArchiveId = ConcurrentHashMap<String, SupabaseCatalogItem>()
    private val itemByStreamUrl = ConcurrentHashMap<String, SupabaseCatalogItem>()

    // Ograničene priručne memorije koje se učitavaju tek na klik i redovito čiste
    private val episodesByArchiveId = ConcurrentHashMap<String, List<SupabaseEpisodeItem>>()
    private val descriptionByArchiveId = ConcurrentHashMap<String, String>()
    private val authorMoviesByAuthorId = ConcurrentHashMap<Long, List<SupabaseAuthorMovie>>()
    private val authorMovieByStreamUrl = ConcurrentHashMap<String, SupabaseAuthorMovie>()

    fun init(context: Context) {
        if (filesDirRef == null) {
            val appCtx = context.applicationContext
            val fDir = appCtx.filesDir
            filesDirRef = fDir
            cacheDirRef = appCtx.cacheDir
            listOf(
                "supabase_catalog_cache.json",
                "supabase_catalog_lite_v2.json",
                "supabase_author_movies_cache.json"
            ).forEach { oldName ->
                try {
                    File(fDir, oldName).delete()
                } catch (_: Exception) {
                }
            }
            loadFromDiskCache()
        }
    }

    private fun readCacheFile(fileName: String): String? {
        val dir = filesDirRef ?: return null
        val file = File(dir, fileName)
        return try {
            if (file.exists() && file.length() > 2) file.readText(Charsets.UTF_8) else null
        } catch (_: Exception) {
            null
        }
    }

    private fun writeCacheFile(fileName: String, content: String) {
        val dir = filesDirRef ?: return
        try {
            File(dir, fileName).writeText(content, Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    private fun loadFromDiskCache() {
        readCacheFile("supabase_catalog_ultralite_v3.json")?.let { jsonStr ->
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
    }

    suspend fun syncFromSupabase(): Boolean = withContext(Dispatchers.IO) {
        var anySuccess = false

        val catalogArr = fetchAllPaginated(
            "catalog_items?select=$CATALOG_ULTRA_LITE_COLUMNS&is_valid=eq.true&order=popularity_score.desc"
        )
        if (catalogArr != null && catalogArr.length() > 0) {
            writeCacheFile("supabase_catalog_ultralite_v3.json", catalogArr.toString())
            updateCatalogMemory(parseCatalogArray(catalogArr))
            anySuccess = true
        }

        val authorsArr = fetchAllPaginated("authors?select=*&order=sort_order.asc")
        if (authorsArr != null && authorsArr.length() > 0) {
            writeCacheFile("supabase_authors_cache.json", authorsArr.toString())
            authors = parseAuthorsArray(authorsArr)
            anySuccess = true
        }

        anySuccess || catalogItems.isNotEmpty()
    }

    suspend fun loadItemDescription(archiveId: String): String = withContext(Dispatchers.IO) {
        val cleanId = archiveId.substringBefore(",tmdb-").trim()
        descriptionByArchiveId[archiveId]?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        descriptionByArchiveId[cleanId]?.takeIf { it.isNotBlank() }?.let { return@withContext it }

        try {
            val encodedId = URLEncoder.encode(archiveId, "UTF-8")
            var arr = fetchJsonArrayPage("catalog_items?select=description_hr&archive_id=eq.$encodedId&limit=1", 0, 0)
            if ((arr == null || arr.length() == 0) && cleanId != archiveId) {
                val encodedClean = URLEncoder.encode(cleanId, "UTF-8")
                arr = fetchJsonArrayPage("catalog_items?select=description_hr&archive_id=eq.$encodedClean&limit=1", 0, 0)
            }
            val desc = arr?.optJSONObject(0)?.optString("description_hr", "")?.trim().orEmpty()
            if (desc.isNotEmpty()) {
                if (descriptionByArchiveId.size > 50) descriptionByArchiveId.clear()
                descriptionByArchiveId[archiveId] = desc
                descriptionByArchiveId[cleanId] = desc
            }
            desc
        } catch (_: Exception) {
            ""
        }
    }

    suspend fun loadAuthorMoviesById(authorId: Long): List<SupabaseAuthorMovie> = withContext(Dispatchers.IO) {
        authorMoviesByAuthorId[authorId]?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        try {
            val arr = fetchAllPaginated("author_movies?select=*&author_id=eq.$authorId&order=release_year.desc")
            val parsed = if (arr != null) parseAuthorMoviesArray(arr) else emptyList()
            if (parsed.isNotEmpty()) {
                if (authorMoviesByAuthorId.size > 8) {
                    authorMoviesByAuthorId.clear()
                    authorMovieByStreamUrl.clear()
                }
                authorMoviesByAuthorId[authorId] = parsed
                for (m in parsed) {
                    if (m.streamUrl.isNotBlank()) {
                        authorMovieByStreamUrl[m.streamUrl] = m
                    }
                    if (m.posterUrl.isNotBlank()) {
                        val lowerTitle = m.title.lowercase().trim()
                        posterByTitle[lowerTitle] = m.posterUrl
                        posterByTitle[lowerTitle.replace(Regex("\\(\\d{4}\\)"), "").trim()] = m.posterUrl
                    }
                }
            }
            parsed
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun loadSeriesEpisodes(archiveId: String): List<SupabaseEpisodeItem> = withContext(Dispatchers.IO) {
        val cleanId = archiveId.substringBefore(",tmdb-").trim()
        episodesByArchiveId[archiveId]?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        episodesByArchiveId[cleanId]?.takeIf { it.isNotEmpty() }?.let { return@withContext it }

        try {
            val encodedFull = URLEncoder.encode(archiveId, "UTF-8")
            var arr = fetchJsonArrayPage("catalog_items?select=episodes&archive_id=eq.$encodedFull&limit=1", 0, 0)
            if ((arr == null || arr.length() == 0) && cleanId != archiveId) {
                val encodedClean = URLEncoder.encode(cleanId, "UTF-8")
                arr = fetchJsonArrayPage("catalog_items?select=episodes&archive_id=eq.$encodedClean&limit=1", 0, 0)
            }
            val obj = arr?.optJSONObject(0)
            val parsed = parseEpisodesArray(obj?.optJSONArray("episodes"))
            if (parsed.isNotEmpty()) {
                if (episodesByArchiveId.size > 12) episodesByArchiveId.clear()
                episodesByArchiveId[archiveId] = parsed
                episodesByArchiveId[cleanId] = parsed
            }
            parsed
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Oslobađa nakupljene opise, epizode i stare privremene datoteke s diska kako bi Firestick radio stabilno.
     */
    fun trimMemoryCaches() {
        episodesByArchiveId.clear()
        descriptionByArchiveId.clear()
        if (authorMoviesByAuthorId.size > 3) {
            authorMoviesByAuthorId.clear()
            authorMovieByStreamUrl.clear()
        }
        val cDir = cacheDirRef ?: return
        try {
            val now = System.currentTimeMillis()
            val maxAgeMs = 20 * 60 * 1000L
            cDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("parallel_stream_")) {
                    f.deleteRecursively()
                } else if (f.isFile && (now - f.lastModified()) > maxAgeMs) {
                    f.delete()
                }
            }
        } catch (_: Exception) {
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
                val lowerTitle = item.title.lowercase().trim()
                posterByTitle[lowerTitle] = item.posterUrl
                val strippedTitle = lowerTitle.replace(Regex("\\(\\d{4}\\)"), "").trim()
                if (strippedTitle.isNotEmpty()) {
                    posterByTitle[strippedTitle] = item.posterUrl
                }
            }
        }

        itemByArchiveId.clear()
        itemByArchiveId.putAll(nextByArchive)
        itemByStreamUrl.clear()
        itemByStreamUrl.putAll(nextByStream)
        posterByArchiveId.clear()
        posterByArchiveId.putAll(nextPosterById)
    }

    fun getCatalogItems(): List<SupabaseCatalogItem> = catalogItems

    fun getAuthors(): List<SupabaseAuthor> = authors

    fun getAuthorWithMovies(authorName: String): Pair<SupabaseAuthor?, List<SupabaseAuthorMovie>> {
        val author = authors.find { it.name.equals(authorName, ignoreCase = true) }
        val movies = if (author != null) {
            authorMoviesByAuthorId[author.id].orEmpty().sortedByDescending { it.releaseYear }
        } else {
            emptyList()
        }
        return author to movies
    }

    fun getCatalogItem(archiveId: String): SupabaseCatalogItem? {
        val cleanId = archiveId.substringBefore(",tmdb-").trim()
        val base = itemByArchiveId[archiveId] ?: itemByArchiveId[cleanId] ?: return null
        val cachedEps = episodesByArchiveId[base.archiveId] ?: episodesByArchiveId[cleanId]
        val cachedDesc = descriptionByArchiveId[base.archiveId] ?: descriptionByArchiveId[cleanId]
        return base.copy(
            episodes = if (!cachedEps.isNullOrEmpty()) cachedEps else base.episodes,
            descriptionHr = cachedDesc?.takeIf { it.isNotBlank() } ?: base.descriptionHr
        )
    }

    fun findCatalogItemByStreamUrl(streamUrl: String): SupabaseCatalogItem? {
        val base = itemByStreamUrl[streamUrl] ?: return null
        val cachedEps = episodesByArchiveId[base.archiveId]
        val cachedDesc = descriptionByArchiveId[base.archiveId]
        return base.copy(
            episodes = if (!cachedEps.isNullOrEmpty()) cachedEps else base.episodes,
            descriptionHr = cachedDesc?.takeIf { it.isNotBlank() } ?: base.descriptionHr
        )
    }

    fun findByStreamUrl(streamUrl: String): Pair<String, String>? {
        authorMovieByStreamUrl[streamUrl]?.let {
            return it.posterUrl to it.descriptionHr
        }
        itemByStreamUrl[streamUrl]?.let {
            val desc = descriptionByArchiveId[it.archiveId] ?: it.descriptionHr
            return it.posterUrl to desc
        }
        return null
    }

    fun getCachedPoster(archiveId: String, title: String): String? {
        posterByArchiveId[archiveId]?.takeIf { it.isNotBlank() }?.let { return it }

        val cleanId = archiveId.substringBefore(",tmdb-").trim()
        posterByArchiveId[cleanId]?.takeIf { it.isNotBlank() }?.let { return it }

        val lowerTitle = title.lowercase().trim()
        posterByTitle[lowerTitle]?.takeIf { it.isNotBlank() }?.let { return it }

        val cleanTitle = lowerTitle.replace(Regex("\\(\\d{4}\\)"), "").trim()
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
