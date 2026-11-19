package com.example.mediastudio.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediastudio.data.api.SupabaseCatalogItem
import com.example.mediastudio.data.api.SupabaseRepository
import com.example.mediastudio.data.model.ArchiveMovie
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import kotlin.math.ceil

enum class ContentTab {
    MOVIES, ANIMATION, SERIES, CONCERTS, FAVORITES
}

data class GenreOption(
    val title: String,
    val query: String,
    val defaultSort: String = "downloads desc",
    val isAdultCategory: Boolean = false,
    val isAnimationCategory: Boolean = false,
    val isSeriesCategory: Boolean = false,
    val isConcertCategory: Boolean = false,
    val isAuthorCollection: Boolean = false
)

data class RatedMovie(
    val movie: ArchiveMovie,
    val rating: String,
    val relevanceScore: Double = 0.0
)

private data class ArchiveSearchResponse(val response: ArchiveResponseBody?)
private data class ArchiveResponseBody(
    val numFound: Int?,
    val docs: List<RawArchiveDoc>?
)
private data class RawArchiveDoc(
    val identifier: String?,
    val title: Any?,
    val creator: Any?,
    val description: Any?,
    val collection: Any?,
    val subject: Any? = null,
    val runtime: Any?,
    @Json(name = "item_size") val itemSize: Any? = null,
    val year: Any? = null,
    @Json(name = "avg_rating") val avgRating: Any?,
    @Json(name = "num_reviews") val numReviews: Any?
)

private interface ArchiveSearchApi {
    @GET("advancedsearch.php")
    suspend fun searchMovies(
        @Query("q") query: String,
        @Query("fl[]") fields: List<String> = listOf(
            "identifier", "title", "creator", "description", "collection",
            "subject", "runtime", "item_size", "year", "avg_rating", "num_reviews"
        ),
        @Query("sort[]") sort: String = "downloads desc",
        @Query("rows") rows: Int = 150,
        @Query("page") page: Int = 1,
        @Query("output") output: String = "json"
    ): ArchiveSearchResponse
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("MediaStudioPrefs", Context.MODE_PRIVATE)

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val favoritesAdapter = moshi.adapter<List<ArchiveMovie>>(
        Types.newParameterizedType(List::class.java, ArchiveMovie::class.java)
    )

    private val api: ArchiveSearchApi = Retrofit.Builder()
        .baseUrl("https://archive.org/")
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(ArchiveSearchApi::class.java)

    private val adultEroticKeywords = listOf(
        "18+", "erotski", "erotic", "erotica", "erotsk", "erotik", "xxx", "porn", "porno",
        "pornography", "pornograf", "adult film", "adult movie", "adultvhs",
        "softcore", "hardcore", "playboy", "penthouse", "orgy", "orgij", "fetish", "fetiš",
        "nc-17", "alpha france", "nsfw", "nude", "naked", "golotinj", "striptiz",
        "masturbating", "vintage porn", "baise moi", "baise-moi", "fick mich",
        "schulmädchen", "sexfilm", "nimfoman", "nymphomaniac", "senzualn", "sensual",
        "bordel", "brothel", "voyeur", "tinto brass", "jess franco", "zalman king",
        "just jaeckin", "radley metzger", "walerian borowczyk",
        "emmanuelle", "caligula", "kaligula", "sanjari", "the dreamers", "lolita",
        "last tango in paris", "posljednji tango u parizu", "9½ weeks", "9 1/2 weeks",
        "nine 1/2 weeks", "devet i pol tjedana", "showgirls", "basic instinct",
        "sirove strasti", "eyes wide shut", "širom zatvorenih očiju",
        "blue is the warmest", "plava je najtoplija boja", "fifty shades",
        "pedeset nijansi", "365 days", "365 dana", "secretary 2002", "bitter moon",
        "gorki mjesec", "malèna", "malena", "salò", "belle de jour", "ljepotica dana",
        "henry & june", "henry i june", "unfaithful", "nevjerna", "body of evidence",
        "sliver", "wild things", "the handmaiden", "sluškinja", "realm of the senses",
        "empire des sens", "carstvo čula", "romance x", "anatomy of hell",
        "anatomija pakla", "lie with me", "jeune & jolie", "young & beautiful",
        "mlada i lijepa", "below her mouth", "room in rome", "soba u rimu",
        "habitación en roma", "shortbus", "the brown bunny", "shame 2011",
        "q desire", "love 2015", "histoire d'o", "story of o", "lady chatterley",
        "ai no corrida", "good girl bad girl"
    )

    private val blockedSpecificAdultKeywords = listOf(
        "hentai", "ecchi", "yaoi", "yuri", "adult anime", "gay porn", "boy love"
    )

    private val blockedSearchNoiseKeywords = listOf(
        "all 6", "movies 1 -", "movies 1-", "n64", "ps1", "ps2", "snes", "nes",
        "gameplay", "longplay", "walkthrough", "speedrun", "soundtrack", "audiobook",
        "radio drama", "fan film", "fan-film", "trailer", "teaser", "tv spot",
        "movie_trailers", "sneak peek", "featurette", "screen test", "test footage",
        "gag reel", "blooper", "unboxing", "reaction", "commercial", "promo"
    )

    private val animationKeywords = listOf(
        "animirani", "crtani", "crtić", "cartoon", "animation", "anime", "pixar", "disney",
        "looney tunes", "tom and jerry", "bugs bunny", "popeye", "ghibli", "chihiro",
        "koralina", "coraline", "angry birds", "mačak u čizmama", "puss in boots",
        "evangelion", "wolfwalkers", "klaus", "bojack", "phineas", "gumball", "dr.stone",
        "batman: crtana", "animated series", "south park", "simpsons"
    )

    val exactAuthorOrder = listOf(
        "Alfred Hitchcock", "Francis Ford Coppola", "Brian De Palma", "Sergio Leone", "Lars von Trier",
        "Paul Verhoeven", "Roman Polanski", "Werner Herzog", "Jean-Luc Godard", "David Cronenberg",
        "John Cassavetes", "Mira Nair", "Takeshi Kitano", "Freddie Francis", "Elvis Presley",
        "David Lean", "Ingmar Bergman", "Roger Corman", "David DeCoteau", "François Truffaut",
        "Pier Paolo Pasolini", "Budd Boetticher", "Wim Wenders", "Nicholas Roeg", "John Ford",
        "Mario Bava", "Akira Kurosawa", "Jacques Rivette", "Costa-Gavras", "Louis Malle",
        "Federico Fellini", "George A. Romero", "Otto Preminger", "Herschell Gordon Lewis",
        "Harmony Korine", "Orson Welles", "Norman Jewison", "Curtis Harrington", "Bernardo Bertolucci",
        "Ken Russell", "Krzysztof Kieślowski", "Alejandro Jodorowsky", "Ruggero Deodato",
        "Andy Milligan", "Ted V. Mikels", "Lina Wertmüller", "Carl Theodor Dreyer", "Greydon Clark",
        "Bill Zebub", "Sean Baker", "PTA Filmography", "Andy Warhol", "Leos Carax",
        "Volker Schlöndorff", "Albert Pyun", "Giuseppe Tornatore", "Bo Widerberg", "Jackie Collins",
        "Rick Sloane", "Errol Morris", "Gordon Hessler", "Russ Meyer", "Gaspar Noé"
    )

    private val authorArchiveIds = mapOf(
        "Alfred Hitchcock" to "filmography-alfred-hitchcock",
        "Francis Ford Coppola" to "filmography-francis-ford-coppola",
        "Brian De Palma" to "brian-de-palma-complete-filmography",
        "Sergio Leone" to "filmography-sergio-leone",
        "Lars von Trier" to "lars-von-trier-filmography",
        "Paul Verhoeven" to "filmography-paul-verhoeven",
        "Roman Polanski" to "roman-polanski-filmography",
        "Werner Herzog" to "filmography-werner-herzog",
        "Jean-Luc Godard" to "jean-luc-godard-the-complete-filmography",
        "David Cronenberg" to "filmography-david-cronenberg",
        "John Cassavetes" to "filmography-john-cassavetes",
        "Mira Nair" to "filmography-mira-nair",
        "Takeshi Kitano" to "filmography-takeshi-kitano",
        "Freddie Francis" to "1987.-dark-tower",
        "Elvis Presley" to "filmography-elvis-presley",
        "David Lean" to "lawrence.of.-arabia.-1962.1080p.-remastered.-blu-ray.-hevc.-eac-3-sartre",
        "Ingmar Bergman" to "filmography-ingmar-bergman",
        "Roger Corman" to "filmography-roger-corman",
        "David DeCoteau" to "filmography-david-decoteau",
        "François Truffaut" to "filmography-francois-truffaut",
        "Pier Paolo Pasolini" to "filmography-pier-paolo-pasolini",
        "Budd Boetticher" to "filmography-budd-boetticher",
        "Wim Wenders" to "filmography-wim-wenders",
        "Nicholas Roeg" to "walkabout-1971-remastered-1080p-blu-ray-hevc-h-265-bone",
        "John Ford" to "3-bad-men-1926_202607",
        "Mario Bava" to "filmography-mario-bava",
        "Akira Kurosawa" to "filmography-akira-kurosawa",
        "Jacques Rivette" to "jacques-rivette-the-complete-filmography",
        "Costa-Gavras" to "filmography-costa-gavras",
        "Louis Malle" to "filmography-louis-malle",
        "Federico Fellini" to "filmography-federico-fellini",
        "George A. Romero" to "filmography-george-a-romero",
        "Otto Preminger" to "filmography-otto-preminger",
        "Herschell Gordon Lewis" to "filmography-herschell-gordon-lewis",
        "Harmony Korine" to "filmography-harmony-korine",
        "Orson Welles" to "filmography-orson-welles",
        "Norman Jewison" to "filmography-norman-jewison",
        "Curtis Harrington" to "filmography-curtis-harrington",
        "Bernardo Bertolucci" to "1987.-the-last-emperor",
        "Ken Russell" to "filmography-ken-russell-no-features",
        "Krzysztof Kieślowski" to "filmography-krzysztof-kieslowski",
        "Alejandro Jodorowsky" to "filmography-alejandro-jodorowsky",
        "Ruggero Deodato" to "filmography-ruggero-deodato",
        "Andy Milligan" to "filmography-andy-milligan",
        "Ted V. Mikels" to "filmography-ted-v-mikels",
        "Lina Wertmüller" to "filmography-lina-wertmuller",
        "Carl Theodor Dreyer" to "filmography-carl-theodor-dreyer",
        "Greydon Clark" to "filmography-greydon-clark",
        "Bill Zebub" to "filmography-bill-zebub",
        "Sean Baker" to "the.-florida.-project.-2017.2160p.-webrip.-3500-mb.-ddp-5.1.x-264-galaxy-rg",
        "PTA Filmography" to "boogie-nights-1997_202604",
        "Andy Warhol" to "andy-warhol-complete-films-collection",
        "Leos Carax" to "filmography-leos-carax",
        "Volker Schlöndorff" to "filmography-volker-schlondorff",
        "Albert Pyun" to "nemesis.-1992.1080p.-blu-ray.-ddp-2.0.x-265.10bit-galaxy-rg-265",
        "Giuseppe Tornatore" to "filmography-giuseppe-tornatore",
        "Bo Widerberg" to "filmography-bo-widerberg",
        "Jackie Collins" to "filmography-jackie-collins",
        "Rick Sloane" to "filmography-rick-sloane",
        "Errol Morris" to "filmography-errol-morris",
        "Gordon Hessler" to "filmography-gordon-hessler",
        "Russ Meyer" to "filmography-russ-meyer",
        "Gaspar Noé" to "filmography-gaspar-noe"
    )

    fun getAuthorArchiveId(authorName: String): String {
        val sbAuthor = SupabaseRepository.getAuthors().find { it.name.equals(authorName, ignoreCase = true) }
        return sbAuthor?.archiveId?.takeIf { it.isNotBlank() }
            ?: authorArchiveIds[authorName]
            ?: ("filmography-" + authorName.lowercase().replace(" ", "-"))
    }

    private val fallbackAuthorImages = mapOf(
        "Alfred Hitchcock" to "https://upload.wikimedia.org/wikipedia/commons/thumb/6/67/Alfred_Hitchcock_by_Jack_Mitchell_1965_-_restored_1.jpg/400px-Alfred_Hitchcock_by_Jack_Mitchell_1965_-_restored_1.jpg",
        "Francis Ford Coppola" to "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e0/Francis_Ford_Coppola_1976.jpg/400px-Francis_Ford_Coppola_1976.jpg",
        "Brian De Palma" to "https://upload.wikimedia.org/wikipedia/commons/thumb/6/69/Brian_De_Palma_Venice_2007.jpg/400px-Brian_De_Palma_Venice_2007.jpg",
        "Sergio Leone" to "https://upload.wikimedia.org/wikipedia/commons/thumb/7/7b/Sergio_Leone_1984.jpg/400px-Sergio_Leone_1984.jpg",
        "Lars von Trier" to "https://upload.wikimedia.org/wikipedia/commons/thumb/0/03/Lars_von_Trier_-_2011_Cannes_Film_Festival.jpg/400px-Lars_von_Trier_-_2011_Cannes_Film_Festival.jpg",
        "Paul Verhoeven" to "https://upload.wikimedia.org/wikipedia/commons/thumb/c/cd/Paul_Verhoeven_%282016%29.jpg/400px-Paul_Verhoeven_%282016%29.jpg",
        "Roman Polanski" to "https://upload.wikimedia.org/wikipedia/commons/thumb/d/d3/Roman_Polanski_at_Cannes_2013.jpg/400px-Roman_Polanski_at_Cannes_2013.jpg",
        "Werner Herzog" to "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e9/Werner_Herzog_%282018%29.jpg/400px-Werner_Herzog_%282018%29.jpg",
        "Jean-Luc Godard" to "https://upload.wikimedia.org/wikipedia/commons/thumb/9/91/Jean-Luc_Godard_1968.jpg/400px-Jean-Luc_Godard_1968.jpg",
        "David Cronenberg" to "https://upload.wikimedia.org/wikipedia/commons/thumb/9/9a/David_Cronenberg_at_Cannes_2014.jpg/400px-David_Cronenberg_at_Cannes_2014.jpg",
        "John Cassavetes" to "https://upload.wikimedia.org/wikipedia/commons/thumb/b/b5/John_Cassavetes_%281969%29.jpg/400px-John_Cassavetes_%281969%29.jpg",
        "Mira Nair" to "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e6/Mira_Nair.jpg/400px-Mira_Nair.jpg",
        "Takeshi Kitano" to "https://upload.wikimedia.org/wikipedia/commons/thumb/5/5e/Takeshi_Kitano.jpg/400px-Takeshi_Kitano.jpg",
        "Elvis Presley" to "https://upload.wikimedia.org/wikipedia/commons/thumb/9/99/Elvis_Presley_promoting_Jailhouse_Rock.jpg/400px-Elvis_Presley_promoting_Jailhouse_Rock.jpg",
        "David Lean" to "https://upload.wikimedia.org/wikipedia/commons/thumb/0/07/David_Lean.jpg/400px-David_Lean.jpg",
        "Ingmar Bergman" to "https://upload.wikimedia.org/wikipedia/commons/thumb/f/f6/Ingmar_Bergman_1960.jpg/400px-Ingmar_Bergman_1960.jpg",
        "Roger Corman" to "https://upload.wikimedia.org/wikipedia/commons/thumb/0/06/Roger_Corman_2010.jpg/400px-Roger_Corman_2010.jpg",
        "François Truffaut" to "https://upload.wikimedia.org/wikipedia/commons/thumb/4/4c/Fran%C3%A7ois_Truffaut_%281980%29.jpg/400px-Fran%C3%A7ois_Truffaut_%281980%29.jpg",
        "Pier Paolo Pasolini" to "https://upload.wikimedia.org/wikipedia/commons/thumb/f/fd/Pier_Paolo_Pasolini_1960s.jpg/400px-Pier_Paolo_Pasolini_1960s.jpg",
        "Wim Wenders" to "https://upload.wikimedia.org/wikipedia/commons/thumb/e/e5/Wim_Wenders_-_Berlinale_2015.jpg/400px-Wim_Wenders_-_Berlinale_2015.jpg",
        "John Ford" to "https://upload.wikimedia.org/wikipedia/commons/thumb/2/22/John_Ford_1946.jpg/400px-John_Ford_1946.jpg",
        "Akira Kurosawa" to "https://upload.wikimedia.org/wikipedia/commons/thumb/3/39/Akira_Kurosawa_in_1953.jpg/400px-Akira_Kurosawa_in_1953.jpg",
        "Federico Fellini" to "https://upload.wikimedia.org/wikipedia/commons/thumb/8/87/Fellini.jpg/400px-Fellini.jpg",
        "Orson Welles" to "https://upload.wikimedia.org/wikipedia/commons/thumb/3/39/Orson_Welles_1937.jpg/400px-Orson_Welles_1937.jpg",
        "Bernardo Bertolucci" to "https://upload.wikimedia.org/wikipedia/commons/thumb/f/f6/Bernardo_Bertolucci_1975.jpg/400px-Bernardo_Bertolucci_1975.jpg",
        "Alejandro Jodorowsky" to "https://upload.wikimedia.org/wikipedia/commons/thumb/5/5f/Alejandro_Jodorowsky_at_Cannes.jpg/400px-Alejandro_Jodorowsky_at_Cannes.jpg",
        "Sean Baker" to "https://upload.wikimedia.org/wikipedia/commons/thumb/c/ca/Sean_Baker_2017.jpg/400px-Sean_Baker_2017.jpg",
        "PTA Filmography" to "https://upload.wikimedia.org/wikipedia/commons/thumb/0/05/Paul_Thomas_Anderson_2018.jpg/400px-Paul_Thomas_Anderson_2018.jpg",
        "Gaspar Noé" to "https://upload.wikimedia.org/wikipedia/commons/thumb/2/2b/Gaspar_No%C3%A9_Cannes_2015.jpg/400px-Gaspar_No%C3%A9_Cannes_2015.jpg"
    )

    var staticAuthorImages: Map<String, String> = fallbackAuthorImages
        private set

    private val movieBaseQuery = "mediatype:movies AND (collection:feature_films OR collection:opensource_movies OR collection:SciFi_Films OR collection:film_noir) AND -collection:television AND -subject:news AND -subject:animation AND -subject:cartoon"

    val movieGenreDefinitions = listOf(
        GenreOption("Top 10 danas", movieBaseQuery, "downloads desc"),
        GenreOption("Klasici i Najgledanije", movieBaseQuery, "downloads desc"),
        GenreOption("Filmografije i Kolekcije Autora", "", "", isAuthorCollection = true),
        GenreOption("Sci-Fi klasici", "$movieBaseQuery AND subject:(sci-fi OR \"science fiction\")"),
        GenreOption("Akcija i avantura", "$movieBaseQuery AND subject:(action OR adventure)"),
        GenreOption("Triler i misterija", "$movieBaseQuery AND subject:(thriller OR mystery OR noir)"),
        GenreOption("Komedija", "$movieBaseQuery AND subject:comedy"),
        GenreOption("Horor", "$movieBaseQuery AND subject:horror"),
        GenreOption("Drama", "$movieBaseQuery AND subject:drama"),
        GenreOption("Krimi", "$movieBaseQuery AND subject:(crime OR gangster)"),
        GenreOption("Romantični", "$movieBaseQuery AND subject:romance"),
        GenreOption("Vestern", "$movieBaseQuery AND subject:western"),
        GenreOption("Ratni", "$movieBaseQuery AND subject:war"),
        GenreOption(
            title = "18+ / Erotski",
            query = "mediatype:movies AND (collection:(adultvhs OR mensmagazines_post70s) OR subject:(erotica OR porn OR \"adult film\" OR nsfw OR \"vintage porn\" OR sex) OR creator:(\"alpha france\" OR \"jess franco\" OR \"tinto brass\"))",
            defaultSort = "downloads desc",
            isAdultCategory = true
        )
    )

    val animationGenreDefinitions = listOf(
        GenreOption("Top 10 animiranih", "mediatype:movies AND (subject:animation OR subject:cartoon)", "downloads desc", isAnimationCategory = true),
        GenreOption("Disney i Pixar klasici", "mediatype:movies AND (title:(disney OR pixar OR \"walt disney\" OR mickey))", "downloads desc", isAnimationCategory = true),
        GenreOption("Animirani filmovi", "mediatype:movies AND (subject:animation OR collection:animationandcartoons)", "downloads desc", isAnimationCategory = true),
        GenreOption("Klasični crtići", "mediatype:movies AND (title:(\"looney tunes\" OR \"tom and jerry\" OR popeye OR \"bugs bunny\" OR \"pink panther\"))", "downloads desc", isAnimationCategory = true),
        GenreOption("Animirane serije", "mediatype:movies AND (subject:animation OR subject:cartoon) AND subject:\"tv series\"", "downloads desc", isAnimationCategory = true, isSeriesCategory = true)
    )

    private val seriesBaseQuery = "mediatype:movies AND (collection:classic_tv_1990s OR collection:classic_tv_1980s OR collection:classic_tv_1970s OR subject:\"tv series\") AND -subject:news AND -subject:animation AND -subject:cartoon"

    val seriesGenreDefinitions = listOf(
        GenreOption("Top 10 serija danas", seriesBaseQuery, "downloads desc", isSeriesCategory = true),
        GenreOption("Krimi, detektivske i triler serije", "$seriesBaseQuery AND subject:(crime OR detective OR mystery OR thriller)", "downloads desc", isSeriesCategory = true),
        GenreOption("Sci-Fi i fantastika serije", "$seriesBaseQuery AND subject:(sci-fi OR \"science fiction\" OR fantasy)", "downloads desc", isSeriesCategory = true),
        GenreOption("Komedije i sitcomi", "$seriesBaseQuery AND subject:(comedy OR sitcom)", "downloads desc", isSeriesCategory = true),
        GenreOption("Avanturističke serije", "$seriesBaseQuery AND subject:adventure", "downloads desc", isSeriesCategory = true),
        GenreOption("Dramske i obiteljske serije", "$seriesBaseQuery AND subject:(drama OR family OR western)", "downloads desc", isSeriesCategory = true)
    )

    private val concertBaseQuery = "mediatype:movies AND collection:vhsconcerts_music"

    val concertGenreDefinitions = listOf(
        GenreOption("Top 10 Koncerti", concertBaseQuery, "downloads desc", isConcertCategory = true),
        GenreOption("Rock i Metal", "$concertBaseQuery AND subject:(rock OR metal OR punk)", "downloads desc", isConcertCategory = true),
        GenreOption("Pop glazba", "$concertBaseQuery AND subject:(pop OR \"pop music\")", "downloads desc", isConcertCategory = true),
        GenreOption("Klasika i Jazz", "$concertBaseQuery AND subject:(classical OR jazz OR blues)", "downloads desc", isConcertCategory = true)
    )

    private val _selectedTab = MutableStateFlow(ContentTab.MOVIES)
    val selectedTab: StateFlow<ContentTab> = _selectedTab

    private val _movieCategories = MutableStateFlow<List<Pair<GenreOption, List<ArchiveMovie>>>>(emptyList())
    val movieCategories: StateFlow<List<Pair<GenreOption, List<ArchiveMovie>>>> = _movieCategories

    private val _animationCategories = MutableStateFlow<List<Pair<GenreOption, List<ArchiveMovie>>>>(emptyList())
    val animationCategories: StateFlow<List<Pair<GenreOption, List<ArchiveMovie>>>> = _animationCategories

    private val _seriesCategories = MutableStateFlow<List<Pair<GenreOption, List<ArchiveMovie>>>>(emptyList())
    val seriesCategories: StateFlow<List<Pair<GenreOption, List<ArchiveMovie>>>> = _seriesCategories

    private val _concertCategories = MutableStateFlow<List<Pair<GenreOption, List<ArchiveMovie>>>>(emptyList())
    val concertCategories: StateFlow<List<Pair<GenreOption, List<ArchiveMovie>>>> = _concertCategories

    private val _heroMovies = MutableStateFlow<List<Pair<ArchiveMovie, Boolean>>>(emptyList())
    val heroMovies: StateFlow<List<Pair<ArchiveMovie, Boolean>>> = _heroMovies

    private val _activeHeroIndex = MutableStateFlow(0)
    val activeHeroIndex: StateFlow<Int> = _activeHeroIndex

    private var carouselJob: Job? = null

    private val _favoriteMovies = MutableStateFlow<List<ArchiveMovie>>(emptyList())
    val favoriteMovies: StateFlow<List<ArchiveMovie>> = _favoriteMovies

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _globalSearchResults = MutableStateFlow<List<RatedMovie>>(emptyList())
    val globalSearchResults: StateFlow<List<RatedMovie>> = _globalSearchResults

    private val _isSearchingGlobal = MutableStateFlow(false)
    val isSearchingGlobal: StateFlow<Boolean> = _isSearchingGlobal

    private val _currentSearchQuery = MutableStateFlow("")
    val currentSearchQuery: StateFlow<String> = _currentSearchQuery

    private var searchJob: Job? = null

    private val _selectedGenre = MutableStateFlow<GenreOption?>(null)
    val selectedGenre: StateFlow<GenreOption?> = _selectedGenre

    private val _genreMovies = MutableStateFlow<List<RatedMovie>>(emptyList())
    val genreMovies: StateFlow<List<RatedMovie>> = _genreMovies

    private val _isGenreLoading = MutableStateFlow(false)
    val isGenreLoading: StateFlow<Boolean> = _isGenreLoading

    private val _currentGenrePage = MutableStateFlow(1)
    val currentGenrePage: StateFlow<Int> = _currentGenrePage

    private val _totalGenrePages = MutableStateFlow(1)
    val totalGenrePages: StateFlow<Int> = _totalGenrePages

    private val _totalGenreMoviesCount = MutableStateFlow(0)
    val totalGenreMoviesCount: StateFlow<Int> = _totalGenreMoviesCount

    val pageSize = 60

    private var isAdultCategoryUnlocked = false
    private var unlockedAdultMovies: List<ArchiveMovie> = emptyList()

    init {
        SupabaseRepository.init(application)
        loadFavorites()

        if (SupabaseRepository.getCatalogItems().isNotEmpty()) {
            rebuildCategoriesFromSupabase()
            _isLoading.value = false
        }

        loadAllContent()
    }

    private fun loadFavorites() {
        val json = prefs.getString("favorites", "[]")
        if (json != null) {
            try {
                val list = favoritesAdapter.fromJson(json) ?: emptyList()
                _favoriteMovies.value = list
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun isFavorite(identifier: String): Boolean {
        return _favoriteMovies.value.any { it.identifier == identifier }
    }

    fun toggleFavorite(movie: ArchiveMovie) {
        val currentList = _favoriteMovies.value.toMutableList()
        val exists = currentList.any { it.identifier == movie.identifier }

        if (exists) {
            currentList.removeAll { it.identifier == movie.identifier }
        } else {
            currentList.add(movie)
        }

        _favoriteMovies.value = currentList
        prefs.edit().putString("favorites", favoritesAdapter.toJson(currentList)).apply()
        refreshHeroItems(currentList, _movieCategories.value, _seriesCategories.value, _concertCategories.value)
    }

    fun selectTab(tab: ContentTab) {
        _selectedTab.value = tab
    }

    fun setActiveHeroIndex(index: Int) {
        if (index in _heroMovies.value.indices) {
            _activeHeroIndex.value = index
            startHeroCarousel()
        }
    }

    private fun isBlockedSpecificAdult(item: SupabaseCatalogItem): Boolean {
        val blob = "${item.title} ${item.descriptionHr} ${item.archiveId} ${item.genres.joinToString(" ")}".lowercase()
        return blockedSpecificAdultKeywords.any { blob.contains(it) }
    }

    private fun isAdultOrEroticItem(item: SupabaseCatalogItem): Boolean {
        if (item.genres.any { g ->
                val gl = g.lowercase()
                gl.contains("18+") || gl.contains("erotsk") || gl.contains("erotic")
            }
        ) {
            return true
        }
        val titleAndId = "${item.title} ${item.archiveId}".lowercase()
        val fullBlob = "$titleAndId ${item.descriptionHr}".lowercase()
        return adultEroticKeywords.any { kw ->
            if (kw.length <= 5) {
                Regex("\\b${Regex.escape(kw)}\\b").containsMatchIn(titleAndId)
            } else {
                fullBlob.contains(kw)
            }
        }
    }

    private fun isAnimatedItem(item: SupabaseCatalogItem): Boolean {
        val animGenres = setOf(
            "animirani klasici",
            "disney i pixar klasici",
            "animirani filmovi",
            "klasični crtići",
            "animirane serije"
        )
        if (item.genres.any { it.lowercase() in animGenres }) return true
        val blob = "${item.title} ${item.archiveId}".lowercase()
        return animationKeywords.any { blob.contains(it) }
    }

    private fun isDisneyOrPixarItem(item: SupabaseCatalogItem): Boolean {
        if (item.genres.any { it.equals("Disney i Pixar klasici", ignoreCase = true) }) return true
        val blob = "${item.title} ${item.descriptionHr} ${item.archiveId}".lowercase()
        return listOf("disney", "pixar", "walt disney", "mickey mouse", "donald duck", "toy story", "finding nemo", "lion king", "frozen", "aladdin").any {
            blob.contains(it)
        }
    }

    private fun deduplicateCatalogItems(items: List<SupabaseCatalogItem>): List<SupabaseCatalogItem> {
        val seenTitleYears = mutableSetOf<String>()
        val seenTmdbTokens = mutableSetOf<String>()
        return items
            .sortedWith(
                compareBy<SupabaseCatalogItem> { it.archiveId.contains(",tmdb-") }
                    .thenByDescending { it.popularityScore }
            )
            .filter { item ->
                val tmdbPart = item.archiveId.substringAfter(",tmdb-", "").trim()
                if (tmdbPart.isNotEmpty() && !seenTmdbTokens.add(tmdbPart)) {
                    return@filter false
                }
                val cleanTitle = item.title.lowercase()
                    .replace(Regex("\\(\\d{4}\\)"), "")
                    .replace(Regex("[^a-z0-9čćžšđ]"), "")
                val titleKey = "${item.mediaType}:${cleanTitle}_${item.releaseYear}"
                seenTitleYears.add(titleKey)
            }
    }

    private fun loadAllContent() {
        viewModelScope.launch {
            if (_movieCategories.value.isEmpty()) {
                _isLoading.value = true
            }
            try {
                SupabaseRepository.syncFromSupabase()
                rebuildCategoriesFromSupabase()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun rebuildCategoriesFromSupabase() {
        val allItems = deduplicateCatalogItems(
            SupabaseRepository.getCatalogItems().filter { !isBlockedSpecificAdult(it) }
        )
        val sbAuthors = SupabaseRepository.getAuthors()

        val mergedAuthorPhotos = fallbackAuthorImages.toMutableMap()
        for (a in sbAuthors) {
            if (a.photoUrl.isNotBlank()) {
                mergedAuthorPhotos[a.name] = a.photoUrl
            }
        }
        staticAuthorImages = mergedAuthorPhotos

        val supabaseAdultMovies = allItems.filter { it.mediaType == "movie" && isAdultOrEroticItem(it) }
        val liveActionMovies = allItems.filter { it.mediaType == "movie" && !isAnimatedItem(it) && !isAdultOrEroticItem(it) }
        val animatedMoviesAndShorts = allItems.filter { it.mediaType == "movie" && isAnimatedItem(it) }
        val liveActionSeries = allItems.filter { it.mediaType == "series" && !isAnimatedItem(it) && !isAdultOrEroticItem(it) }
        val animatedSeries = allItems.filter { it.mediaType == "series" && isAnimatedItem(it) }
        val concerts = allItems.filter { it.mediaType == "concert" }

        val authorList = if (sbAuthors.isNotEmpty()) {
            sbAuthors.map { ArchiveMovie(identifier = "AUTHOR|${it.name}", title = it.name) }
        } else {
            exactAuthorOrder.map { ArchiveMovie(identifier = "AUTHOR|$it", title = it) }
        }

        val builtMovieCategories = movieGenreDefinitions.mapNotNull { genre ->
            when {
                genre.isAuthorCollection -> genre to authorList
                genre.isAdultCategory -> {
                    if (isAdultCategoryUnlocked) {
                        val combinedAdult = (supabaseAdultMovies.map { it.toArchiveMovie() } + unlockedAdultMovies)
                            .distinctBy { it.identifier }
                        if (combinedAdult.isNotEmpty()) genre to combinedAdult else null
                    } else {
                        null
                    }
                }
                genre.title == "Top 10 danas" -> {
                    val top10 = liveActionMovies.sortedByDescending { it.popularityScore }.take(10).map { it.toArchiveMovie() }
                    if (top10.isNotEmpty()) genre to top10 else null
                }
                genre.title == "Klasici i Najgledanije" -> {
                    val classics = liveActionMovies.sortedWith(
                        compareByDescending<SupabaseCatalogItem> { it.rating }
                            .thenByDescending { it.popularityScore }
                    ).take(25).map { it.toArchiveMovie() }
                    if (classics.isNotEmpty()) genre to classics else null
                }
                else -> {
                    val matching = liveActionMovies.filter { item ->
                        item.genres.any { it.equals(genre.title, ignoreCase = true) }
                    }.sortedByDescending { it.popularityScore }.take(25).map { it.toArchiveMovie() }
                    if (matching.isNotEmpty()) genre to matching else null
                }
            }
        }

        val shortCartoons = animatedMoviesAndShorts.filter { item ->
            item.genres.any { it.equals("Klasični crtići", ignoreCase = true) }
        }
        val featureAnimations = animatedMoviesAndShorts.filter { item ->
            item.genres.none { it.equals("Klasični crtići", ignoreCase = true) }
        }

        val builtAnimationCategories = animationGenreDefinitions.mapNotNull { genre ->
            when (genre.title) {
                "Top 10 animiranih" -> {
                    val top10 = featureAnimations.sortedByDescending { it.popularityScore }.take(10).map { it.toArchiveMovie() }
                    if (top10.isNotEmpty()) genre to top10 else null
                }
                "Disney i Pixar klasici" -> {
                    val disney = featureAnimations.filter { isDisneyOrPixarItem(it) }
                        .sortedByDescending { it.popularityScore }.take(25).map { it.toArchiveMovie() }
                    if (disney.isNotEmpty()) genre to disney else null
                }
                "Animirani filmovi" -> {
                    val otherAnim = featureAnimations.filter { !isDisneyOrPixarItem(it) }
                        .sortedByDescending { it.popularityScore }.take(25).map { it.toArchiveMovie() }
                    if (otherAnim.isNotEmpty()) genre to otherAnim else null
                }
                "Klasični crtići" -> {
                    val shorts = shortCartoons.sortedByDescending { it.popularityScore }.take(25).map { it.toArchiveMovie() }
                    if (shorts.isNotEmpty()) genre to shorts else null
                }
                "Animirane serije" -> {
                    val animSer = animatedSeries.sortedByDescending { it.popularityScore }.take(25).map { it.toArchiveMovie() }
                    if (animSer.isNotEmpty()) genre to animSer else null
                }
                else -> null
            }
        }

        val builtSeriesCategories = seriesGenreDefinitions.mapNotNull { genre ->
            if (genre.title.startsWith("Top 10")) {
                val top10 = liveActionSeries.sortedByDescending { it.popularityScore }.take(10).map { it.toArchiveMovie() }
                if (top10.isNotEmpty()) genre to top10 else null
            } else {
                val matching = liveActionSeries.filter { item ->
                    item.genres.any { it.equals(genre.title, ignoreCase = true) }
                }.sortedByDescending { it.popularityScore }.take(25).map { it.toArchiveMovie() }
                if (matching.isNotEmpty()) genre to matching else null
            }
        }

        val builtConcertCategories = concertGenreDefinitions.mapNotNull { genre ->
            if (genre.title.startsWith("Top 10")) {
                val top10 = concerts.sortedByDescending { it.popularityScore }.take(10).map { it.toArchiveMovie() }
                if (top10.isNotEmpty()) genre to top10 else null
            } else {
                val matching = concerts.filter { item ->
                    item.genres.any { it.equals(genre.title, ignoreCase = true) }
                }.sortedByDescending { it.popularityScore }.take(25).map { it.toArchiveMovie() }
                if (matching.isNotEmpty()) genre to matching else null
            }
        }

        if (builtMovieCategories.isNotEmpty()) _movieCategories.value = builtMovieCategories
        _animationCategories.value = builtAnimationCategories
        if (builtSeriesCategories.isNotEmpty()) _seriesCategories.value = builtSeriesCategories
        if (builtConcertCategories.isNotEmpty()) _concertCategories.value = builtConcertCategories

        refreshHeroItems(_favoriteMovies.value, builtMovieCategories, builtSeriesCategories, builtConcertCategories)
    }

    private fun refreshHeroItems(
        favs: List<ArchiveMovie>,
        movies: List<Pair<GenreOption, List<ArchiveMovie>>>,
        series: List<Pair<GenreOption, List<ArchiveMovie>>>,
        concerts: List<Pair<GenreOption, List<ArchiveMovie>>>
    ) {
        val featuredFromDb = deduplicateCatalogItems(SupabaseRepository.getCatalogItems())
            .filter {
                it.isFeatured &&
                    it.posterUrl.isNotBlank() &&
                    !isBlockedSpecificAdult(it) &&
                    !isAdultOrEroticItem(it) &&
                    !isAnimatedItem(it)
            }
            .take(5)
            .map { it.toArchiveMovie() to (it.mediaType == "series") }

        val topFavs = favs.take(3).map {
            val isDbSeries = SupabaseRepository.getCatalogItem(it.identifier)?.mediaType == "series"
            it to (isDbSeries || it.title.lowercase().contains("season") || it.title.lowercase().contains("episode"))
        }
        val topM = (movies.firstOrNull { it.first.title.contains("Top 10") }?.second?.take(3) ?: emptyList()).map { it to false }
        val topS = (series.firstOrNull { it.first.title.contains("Top 10") }?.second?.take(2) ?: emptyList()).map { it to true }
        val topC = (concerts.firstOrNull()?.second?.take(1) ?: emptyList()).map { it to false }

        val combined = (topFavs + featuredFromDb + topM + topS + topC)
            .distinctBy { it.first.identifier }
            .take(8)

        _heroMovies.value = combined
        startHeroCarousel()
    }

    private fun startHeroCarousel() {
        carouselJob?.cancel()
        _activeHeroIndex.value = 0
        carouselJob = viewModelScope.launch {
            while (isActive && _heroMovies.value.isNotEmpty()) {
                delay(8000)
                _activeHeroIndex.value = (_activeHeroIndex.value + 1) % _heroMovies.value.size
            }
        }
    }

    fun updateSearchQuery(query: String) {
        performGlobalSearch(query)
    }

    private fun computeSearchRelevance(
        title: String,
        identifier: String,
        description: String,
        rawQuery: String,
        isFromSupabase: Boolean,
        baseQualityScore: Double
    ): Double {
        val qClean = rawQuery.lowercase().replace(Regex("[^a-z0-9čćžšđ\\s]"), " ").replace(Regex("\\s+"), " ").trim()
        val tClean = title.lowercase()
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("[^a-z0-9čćžšđ\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val idClean = identifier.lowercase().replace("_", " ").replace("-", " ")

        var score = baseQualityScore
        if (isFromSupabase) score += 3500.0

        when {
            tClean == qClean -> score += 20000.0
            tClean.startsWith("$qClean ") || tClean.startsWith("$qClean:") -> score += 15000.0
            tClean.startsWith(qClean) -> score += 13000.0
            tClean.contains(" $qClean ") || tClean.endsWith(" $qClean") || tClean.contains(qClean) -> score += 10500.0
            else -> {
                val words = qClean.split(" ").filter { it.length >= 2 }
                if (words.isNotEmpty() && words.all { tClean.contains(it) }) {
                    score += 6500.0
                } else if (idClean.contains(qClean)) {
                    score += 3000.0
                } else if (description.lowercase().contains(qClean)) {
                    score += 1000.0
                }
            }
        }

        val noisePenaltyTerms = listOf("holiday special", "making of", "behind the scenes", "interview", "review", "parody")
        if (noisePenaltyTerms.any { tClean.contains(it) }) {
            score -= 6000.0
        }

        return score
    }

    fun performGlobalSearch(query: String) {
        searchJob?.cancel()
        _currentSearchQuery.value = query
        val trimmed = query.trim()

        if (trimmed.length < 2) {
            _globalSearchResults.value = emptyList()
            _isSearchingGlobal.value = false
            return
        }

        searchJob = viewModelScope.launch {
            delay(300L)
            _isSearchingGlobal.value = true
            try {
                val qLower = trimmed.lowercase()
                val words = qLower.split(Regex("\\s+")).filter { it.isNotEmpty() }

                val sbMatches = deduplicateCatalogItems(SupabaseRepository.getCatalogItems())
                    .filter { item ->
                        !isBlockedSpecificAdult(item) &&
                            (isAdultCategoryUnlocked || !isAdultOrEroticItem(item)) && (
                            item.title.lowercase().contains(qLower) ||
                                item.descriptionHr.lowercase().contains(qLower) ||
                                item.archiveId.lowercase().contains(qLower) ||
                                (words.size > 1 && words.all { w -> item.title.lowercase().contains(w) })
                            )
                    }
                    .map { item ->
                        val rel = computeSearchRelevance(
                            title = item.title,
                            identifier = item.archiveId,
                            description = item.descriptionHr,
                            rawQuery = trimmed,
                            isFromSupabase = true,
                            baseQualityScore = item.popularityScore.coerceAtMost(3000.0)
                        )
                        RatedMovie(
                            movie = item.toArchiveMovie(),
                            rating = if (item.rating > 0.0) String.format("%.1f", item.rating) else "",
                            relevanceScore = rel
                        )
                    }

                val archiveMatches = if (sbMatches.size < 20 && trimmed.length >= 3) {
                    val safePhrase = trimmed.replace("\"", "").trim()
                    val exclusions = "-subject:trailer -subject:teaser -subject:promo -subject:commercial -subject:gameplay -collection:movie_trailers -collection:trailers"
                    val archiveQuery = if (words.size > 1) {
                        "mediatype:movies AND title:\"$safePhrase\" AND $exclusions"
                    } else {
                        "mediatype:movies AND title:$safePhrase* AND $exclusions"
                    }
                    fetchFromArchiveFallback(
                        query = archiveQuery,
                        rows = 80,
                        page = 1,
                        excludeAdult = !isAdultCategoryUnlocked,
                        searchQueryHint = trimmed,
                        enforceFullLength = true
                    )
                } else {
                    emptyList()
                }

                _globalSearchResults.value = (sbMatches + archiveMatches)
                    .distinctBy { it.movie.identifier }
                    .distinctBy { it.movie.title.lowercase().replace(Regex("\\(\\d{4}\\)"), "").trim() }
                    .sortedByDescending { it.relevanceScore }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isSearchingGlobal.value = false
            }
        }
    }

    fun openGenre(genre: GenreOption) {
        _selectedGenre.value = genre
        if (genre.isAdultCategory) {
            isAdultCategoryUnlocked = true
        }
        _genreMovies.value = emptyList()
        loadGenrePage(1)
    }

    fun loadGenrePage(page: Int) {
        val genre = _selectedGenre.value ?: return
        if (page < 1) return

        viewModelScope.launch {
            if (_genreMovies.value.isEmpty()) {
                _isGenreLoading.value = true
            }
            _currentGenrePage.value = page

            try {
                if (genre.isAuthorCollection) {
                    val sbAuthors = SupabaseRepository.getAuthors()
                    val list = if (sbAuthors.isNotEmpty()) {
                        sbAuthors.map { RatedMovie(ArchiveMovie("AUTHOR|${it.name}", it.name), "", 0.0) }
                    } else {
                        exactAuthorOrder.map { RatedMovie(ArchiveMovie("AUTHOR|$it", it), "", 0.0) }
                    }
                    _genreMovies.value = list
                    _totalGenreMoviesCount.value = list.size
                    _totalGenrePages.value = 1
                    return@launch
                }

                if (genre.isAdultCategory) {
                    val sbAdultRated = deduplicateCatalogItems(SupabaseRepository.getCatalogItems())
                        .filter { it.mediaType == "movie" && !isBlockedSpecificAdult(it) && isAdultOrEroticItem(it) }
                        .sortedByDescending { it.popularityScore }
                        .map { item ->
                            RatedMovie(
                                movie = item.toArchiveMovie(),
                                rating = if (item.rating > 0.0) String.format("%.1f", item.rating) else "",
                                relevanceScore = item.popularityScore + 2000.0
                            )
                        }

                    val archiveAdult = fetchFromArchiveFallback(
                        query = genre.query,
                        rows = 150,
                        page = page,
                        excludeAdult = false,
                        isAdultCategory = true
                    )

                    val combinedAdult = (if (page == 1) sbAdultRated + archiveAdult else archiveAdult)
                        .distinctBy { it.movie.identifier }
                        .distinctBy { it.movie.title.lowercase().replace(Regex("\\(\\d{4}\\)"), "").trim() }

                    _genreMovies.value = combinedAdult.take(pageSize)
                    _totalGenreMoviesCount.value = combinedAdult.size
                    _totalGenrePages.value = if (archiveAdult.size >= pageSize) page + 1 else page

                    if (combinedAdult.isNotEmpty()) {
                        unlockedAdultMovies = combinedAdult.map { it.movie }.take(25)
                        val currentWithoutAdult = _movieCategories.value.filterNot { it.first.isAdultCategory }
                        _movieCategories.value = currentWithoutAdult + (genre to unlockedAdultMovies)
                    }
                    return@launch
                }

                val sbMatches = findSupabaseItemsForGenre(genre)
                if (sbMatches.isNotEmpty()) {
                    val isTop10 = genre.title.startsWith("Top 10")
                    val allRated = sbMatches.map { item ->
                        RatedMovie(
                            movie = item.toArchiveMovie(),
                            rating = if (item.rating > 0.0) String.format("%.1f", item.rating) else "",
                            relevanceScore = item.popularityScore
                        )
                    }

                    if (isTop10) {
                        _genreMovies.value = allRated.take(10)
                        _totalGenreMoviesCount.value = 10
                        _totalGenrePages.value = 1
                    } else {
                        val total = allRated.size
                        val pages = ceil(total.toDouble() / pageSize.toDouble()).toInt().coerceAtLeast(1)
                        val fromIdx = ((page - 1) * pageSize).coerceAtMost(total)
                        val toIdx = (fromIdx + pageSize).coerceAtMost(total)

                        _genreMovies.value = allRated.subList(fromIdx, toIdx)
                        _totalGenreMoviesCount.value = total
                        _totalGenrePages.value = pages
                    }
                } else {
                    val results = fetchFromArchiveFallback(
                        query = genre.query,
                        rows = 150,
                        page = page,
                        excludeAdult = true,
                        isAdultCategory = false
                    )
                    _genreMovies.value = results.take(pageSize)
                    _totalGenreMoviesCount.value = results.size
                    _totalGenrePages.value = if (results.size >= pageSize) page + 1 else page
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isGenreLoading.value = false
            }
        }
    }

    private fun findSupabaseItemsForGenre(genre: GenreOption): List<SupabaseCatalogItem> {
        val all = deduplicateCatalogItems(
            SupabaseRepository.getCatalogItems().filter { !isBlockedSpecificAdult(it) }
        )

        if (genre.isAnimationCategory) {
            val animMovies = all.filter { it.mediaType == "movie" && isAnimatedItem(it) }
            val shortCartoons = animMovies.filter { it.genres.any { g -> g.equals("Klasični crtići", ignoreCase = true) } }
            val featureAnim = animMovies.filter { it.genres.none { g -> g.equals("Klasični crtići", ignoreCase = true) } }
            val animSeries = all.filter { it.mediaType == "series" && isAnimatedItem(it) }

            return when (genre.title) {
                "Top 10 animiranih" -> featureAnim.sortedByDescending { it.popularityScore }
                "Disney i Pixar klasici" -> featureAnim.filter { isDisneyOrPixarItem(it) }.sortedByDescending { it.popularityScore }
                "Animirani filmovi" -> featureAnim.filter { !isDisneyOrPixarItem(it) }.sortedByDescending { it.popularityScore }
                "Klasični crtići" -> shortCartoons.sortedByDescending { it.popularityScore }
                "Animirane serije" -> animSeries.sortedByDescending { it.popularityScore }
                else -> animMovies.sortedByDescending { it.popularityScore }
            }
        }

        val targetType = when {
            genre.isConcertCategory -> "concert"
            genre.isSeriesCategory -> "series"
            else -> "movie"
        }
        val typedItems = all.filter {
            it.mediaType == targetType &&
                (targetType == "concert" || (!isAnimatedItem(it) && !isAdultOrEroticItem(it)))
        }

        return when {
            genre.title.startsWith("Top 10") -> typedItems.sortedByDescending { it.popularityScore }
            genre.title == "Klasici i Najgledanije" -> typedItems.sortedWith(
                compareByDescending<SupabaseCatalogItem> { it.rating }.thenByDescending { it.popularityScore }
            )
            else -> typedItems.filter { item ->
                item.genres.any { it.equals(genre.title, ignoreCase = true) }
            }.sortedByDescending { it.popularityScore }
        }
    }

    private fun flattenArchiveField(value: Any?): String {
        return when (value) {
            is String -> value.trim()
            is List<*> -> value.joinToString(" ") { it?.toString()?.trim() ?: "" }.trim()
            else -> value?.toString()?.trim() ?: ""
        }
    }

    private suspend fun fetchFromArchiveFallback(
        query: String,
        rows: Int,
        page: Int,
        excludeAdult: Boolean,
        isAdultCategory: Boolean = false,
        searchQueryHint: String = "",
        enforceFullLength: Boolean = false
    ): List<RatedMovie> {
        return try {
            val docs = api.searchMovies(query = query, rows = rows, page = page).response?.docs ?: emptyList()
            docs.mapNotNull { doc ->
                val id = doc.identifier?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = when (val t = doc.title) {
                    is String -> t.trim()
                    is List<*> -> t.firstOrNull()?.toString()?.trim() ?: id
                    else -> id
                }
                val desc = flattenArchiveField(doc.description)
                val subjectStr = flattenArchiveField(doc.subject)
                val collectionStr = flattenArchiveField(doc.collection)
                val combined = "${id.lowercase()} ${title.lowercase()} ${desc.lowercase()} ${subjectStr.lowercase()} ${collectionStr.lowercase()}"

                if (blockedSpecificAdultKeywords.any { combined.contains(it) }) return@mapNotNull null
                if (excludeAdult && adultEroticKeywords.any { kw ->
                        if (kw.length <= 5) Regex("\\b${Regex.escape(kw)}\\b").containsMatchIn(combined)
                        else combined.contains(kw)
                    }
                ) return@mapNotNull null
                if (isAdultCategory && title.lowercase().contains("adult swim")) return@mapNotNull null
                if (!isAdultCategory && blockedSearchNoiseKeywords.any { combined.contains(it) }) return@mapNotNull null

                val runtimeSeconds = parseRuntimeSeconds(doc.runtime)
                val itemSizeBytes = parseItemSizeBytes(doc.itemSize)

                if (isAdultCategory && runtimeSeconds in 1..600) return@mapNotNull null

                if (enforceFullLength) {
                    val looksLikeSeries = Regex("(?i)\\b(s\\d{1,2}e\\d{1,2}|season\\s*\\d+|episode\\s*\\d+|\\d+x\\d+|tv\\s*series)\\b")
                        .containsMatchIn(combined)
                    val minSeconds = if (looksLikeSeries) 18 * 60 else 50 * 60

                    if (runtimeSeconds >= 0) {
                        if (runtimeSeconds < minSeconds) return@mapNotNull null
                    } else {
                        val minSizeBytes = if (looksLikeSeries) 90_000_000L else 250_000_000L
                        if (itemSizeBytes in 1L until minSizeBytes) return@mapNotNull null
                    }
                }

                val ratingVal = when (val r = doc.avgRating) {
                    is Number -> r.toDouble()
                    is String -> r.toDoubleOrNull() ?: 0.0
                    else -> 0.0
                }
                val ratingStr = if (ratingVal > 0.0) String.format("%.1f", ratingVal) else ""

                val score = if (searchQueryHint.isNotBlank()) {
                    computeSearchRelevance(
                        title = title,
                        identifier = id,
                        description = desc,
                        rawQuery = searchQueryHint,
                        isFromSupabase = false,
                        baseQualityScore = ratingVal * 100.0
                    )
                } else {
                    ratingVal * 100.0
                }

                RatedMovie(
                    movie = ArchiveMovie(identifier = id, title = title),
                    rating = ratingStr,
                    relevanceScore = score
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseRuntimeSeconds(runtimeValue: Any?): Int {
        val raw = when (runtimeValue) {
            is String -> runtimeValue.trim()
            is List<*> -> runtimeValue.firstOrNull()?.toString()?.trim() ?: ""
            is Number -> runtimeValue.toString()
            else -> ""
        }
        if (raw.isEmpty()) return -1
        if (raw.contains(":")) {
            val parts = raw.split(":").mapNotNull { it.trim().toDoubleOrNull()?.toInt() }
            return when (parts.size) {
                3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                2 -> parts[0] * 60 + parts[1]
                else -> -1
            }
        }
        val numeric = raw.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: return -1
        return if (numeric <= 300.0) (numeric * 60.0).toInt() else numeric.toInt()
    }

    private fun parseItemSizeBytes(sizeValue: Any?): Long {
        return when (sizeValue) {
            is Number -> sizeValue.toLong()
            is String -> sizeValue.trim().toLongOrNull() ?: 0L
            is List<*> -> sizeValue.firstOrNull()?.toString()?.trim()?.toLongOrNull() ?: 0L
            else -> 0L
        }
    }

    private fun SupabaseCatalogItem.toArchiveMovie(): ArchiveMovie {
        return ArchiveMovie(
            identifier = archiveId,
            title = title,
            year = if (releaseYear > 0) releaseYear.toString() else null,
            description = descriptionHr.takeIf { it.isNotBlank() }
        )
    }
}
