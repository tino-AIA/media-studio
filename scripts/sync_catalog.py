import os
import re
import threading
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import requests

TMDB_API_KEY = os.environ["TMDB_API_KEY"].strip()
RAW_SB_URL = os.environ["SUPABASE_URL"].strip().rstrip("/")
SUPABASE_URL = RAW_SB_URL[:-8] if RAW_SB_URL.endswith("/rest/v1") else RAW_SB_URL
SUPABASE_SERVICE_KEY = os.environ["SUPABASE_SERVICE_KEY"].strip()

TMDB_BASE = "https://api.themoviedb.org/3"
TMDB_IMG_POSTER = "https://image.tmdb.org/t/p/w500"
TMDB_IMG_BACKDROP = "https://image.tmdb.org/t/p/w1280"

session = requests.Session()
session.headers.update({
    "User-Agent": "MediaStudioCatalogSync/2.5 (AndroidTV App; contact@mediastudio.local)"
})

state_lock = threading.Lock()

SB_HEADERS = {
    "apikey": SUPABASE_SERVICE_KEY,
    "Content-Type": "application/json",
    "Prefer": "resolution=merge-duplicates,return=representation"
}
if not SUPABASE_SERVICE_KEY.startswith("sb_"):
    SB_HEADERS["Authorization"] = f"Bearer {SUPABASE_SERVICE_KEY}"

HENTAI_AND_ADULT_KEYWORDS = [
    "hentai", "ecchi", "yaoi", "yuri", "erotic", "erotica", "xxx", "porn", "porno",
    "pornography", "adult anime", "adult film", "softcore", "hardcore", "nsfw",
    "nude", "naked", "sex", "uncensored hentai", "overflow", "bible black",
    "la blue girl", "urotsukidoji", "redo of healer", "high school dxd", "interspecies reviewers"
]

SPAM_OR_FAKE_KEYWORDS = [
    "joyblix", "for the full movie or to download", "superfans", "bestof", "best of",
    "ending explained", "movie review", "film review", "reaction", "gameplay",
    "walkthrough", "playthrough", "vtuber", "podcast", "trailer", "teaser",
    "behind the scenes", "fan made", "fan-made", "parody", "recap", "soundtrack only",
    "promo", "commercial", "bloopers", "deleted scenes", "starting soon", "twitch",
    "live stream", "livestream", "stage play", "theater production", "homelessness",
    "documentary about", "making of"
] + HENTAI_AND_ADULT_KEYWORDS

EXPLICIT_DUB_KEYWORDS = [
    "dublado", "dublagem", "doblado", "doblaje", "ozvuchka", "дубляж", "озвучка",
    "russian dub", "spanish dub", "portuguese dub", "portugues dub", "french dub",
    "german dub", "italian dub", "hindi dubbed", "truefrench"
]

NON_ENGLISH_LANG_TOKENS = {
    "por", "portuguese", "portugues", "português",
    "spa", "spanish", "espanol", "español", "castellano", "latino",
    "rus", "russian", "русский",
    "fre", "fra", "french", "francais", "français",
    "ger", "deu", "german", "deutsch",
    "ita", "italian", "italiano",
    "hin", "hindi", "ara", "arabic", "tur", "turkish", "pol", "polish"
}

RELEASE_TAG_PATTERN = re.compile(
    r"(?i)\b("
    r"1080p|2160p|4k|720p|480p|360p|240p|bluray|blu-ray|bdrip|brrip|webrip|web-dl|webdl|"
    r"dvdrip|hdrip|vhs|vhdrip|laserdisc|ldrip|hdtv|x264|x265|h264|h265|hevc|avc|xvid|divx|"
    r"aac|ac3|dts|ddp5\s*1|ddp2\s*0|10bit|8bit|mp4|mkv|avi|mov|ia|remastered|restored|"
    r"colorized|extended|unrated|theatrical|directors\s*cut|director's\s*cut|uncut|"
    r"full\s*movie|movie|film|subtitles|subbed|dubbed|eng|english|multi|dual\s*audio|"
    r"yify|yts|rarbg|galaxyrg|galaxy-rg|etrg|sparks|fgt|evo|bone|sartre|anoxmous|juggs|"
    r"shaanig|mkvcage|pahe|psa|criterion|complete\s*series|complete\s*season|tv\s*series|"
    r"the\s*original\s*series|original\s*series|tos|no\s*subs|us\s*cut|eu\s*cut|uk\s*cut"
    r")\b"
)

TMDB_MOVIE_GENRE_MAP = {
    37: "Vestern",
    27: "Horor",
    10752: "Ratni",
    878: "Sci-Fi klasici",
    80: "Krimi",
    53: "Triler i misterija",
    9648: "Triler i misterija",
    35: "Komedija",
    10749: "Romantični",
    28: "Akcija i avantura",
    12: "Akcija i avantura",
    18: "Drama",
    36: "Drama"
}

TMDB_DISCOVER_GENRE_SEEDS = [
    ("37", "Vestern", 15),
    ("27", "Horor", 25),
    ("10752", "Ratni", 15),
    ("878", "Sci-Fi klasici", 25),
    ("80", "Krimi", 30),
    ("53", "Triler i misterija", 30),
    ("9648", "Triler i misterija", 25),
    ("35", "Komedija", 35),
    ("10749", "Romantični", 20),
    ("28", "Akcija i avantura", 30),
    ("12", "Akcija i avantura", 30),
    ("18", "Drama", 40)
]

ARCHIVE_FEATURE_COLLECTIONS = [
    "feature_films",
    "film_noir",
    "SciFi_Films",
    "comedy_films",
    "opensource_movies",
    "laserdiscarchive",
    "colorized-and-upscaled-movies",
    "vhsvault",
    "miscellaneous_feature_films",
    "westerns",
    "war_films",
    "horror_films",
    "romance_films",
    "thriller_films",
    "action_adventure_films",
    "drama_films",
    "mystery_films"
]

ARCHIVE_TV_COLLECTIONS = [
    "classic_tv",
    "classic_tv_1990s",
    "classic_tv_1980s",
    "classic_tv_1970s",
    "classic_tv_1960s",
    "television_inbox",
    "televisionmovies",
    "animationandcartoons"
]

TMDB_TV_GENRE_MAP = {
    16: "Animirane serije",
    10762: "Animirane serije",
    80: "Krimi, detektivske i triler serije",
    9648: "Krimi, detektivske i triler serije",
    53: "Krimi, detektivske i triler serije",
    10765: "Sci-Fi i fantastika serije",
    878: "Sci-Fi i fantastika serije",
    14: "Sci-Fi i fantastika serije",
    10759: "Avanturističke serije",
    12: "Avanturističke serije",
    28: "Avanturističke serije",
    10768: "Avanturističke serije",
    35: "Komedije i sitcomi",
    18: "Dramske i obiteljske serije",
    10751: "Dramske i obiteljske serije",
    37: "Dramske i obiteljske serije",
    10766: "Dramske i obiteljske serije",
    99: "Dramske i obiteljske serije"
}

TMDB_TV_DISCOVER_GENRE_IDS = ["80", "9648", "10765", "10759", "35", "18", "10751", "37", "16", "10762"]

CLASSIC_SHORT_CARTOON_QUERIES = [
    "Tom and Jerry", "Looney Tunes", "Merrie Melodies", "Bugs Bunny",
    "Popeye the Sailor", "Woody Woodpecker", "Pink Panther cartoon",
    "Mickey Mouse cartoon", "Donald Duck cartoon", "Silly Symphonies",
    "Droopy cartoon", "Betty Boop", "Felix the Cat", "Casper the Friendly Ghost",
    "Superman cartoon", "Mighty Mouse", "Road Runner", "Daffy Duck",
    "Porky Pig", "Tweety", "Goofy cartoon", "Pluto cartoon", "Yogi Bear",
    "Huckleberry Hound", "Top Cat", "The Flintstones", "The Jetsons", "Scooby-Doo"
]

CLASSIC_TV_SEED_TITLES = [
    "Columbo", "Murder, She Wrote", "Agatha Christie's Poirot", "Agatha Christie's Marple",
    "Sherlock", "The Adventures of Sherlock Holmes", "Inspector Morse", "Midsomer Murders",
    "Taggart", "Cracker", "Prime Suspect", "A Touch of Frost", "Jonathan Creek",
    "Father Brown", "Grantchester", "Vera", "Shetland", "Luther", "Line of Duty",
    "Broadchurch", "The Fall", "Twin Peaks", "The X-Files", "Millennium",
    "Criminal Minds", "CSI: Crime Scene Investigation", "NCIS", "Bones", "Castle",
    "The Mentalist", "Monk", "Psych", "White Collar", "Burn Notice", "Person of Interest",
    "24", "Prison Break", "Dexter", "Hannibal", "True Detective", "Fargo", "Mindhunter",
    "The Wire", "The Shield", "Justified", "Bosch", "Miami Vice", "Magnum, P.I.",
    "Kojak", "The Streets of San Francisco", "Hawaii Five-O", "Ironside", "Quincy, M.E.",
    "Matlock", "Perry Mason", "Diagnosis: Murder", "Hart to Hart", "Remington Steele",
    "Moonlighting", "Simon & Simon", "Hunter", "Cagney & Lacey", "Hill Street Blues",
    "NYPD Blue", "Law & Order", "The Rockford Files", "Mannix", "Cannon",
    "Barnaby Jones", "77 Sunset Strip", "Peter Gunn", "The Untouchables", "Dragnet",
    "Alfred Hitchcock Presents", "The Alfred Hitchcock Hour", "Tales of the Unexpected",
    "The Twilight Zone", "The Outer Limits", "Star Trek", "Star Trek: The Next Generation",
    "Star Trek: Deep Space Nine", "Star Trek: Voyager", "Star Trek: Enterprise",
    "Doctor Who", "Torchwood", "Battlestar Galactica", "Buck Rogers in the 25th Century",
    "Babylon 5", "Farscape", "Stargate SG-1", "Stargate Atlantis", "Stargate Universe",
    "Firefly", "The Expanse", "Westworld", "Black Mirror", "Stranger Things", "Lost",
    "Fringe", "Heroes", "Supernatural", "Buffy the Vampire Slayer", "Angel", "Charmed",
    "Smallville", "The Flash", "Arrow", "Gotham", "Lucifer", "The Vampire Diaries",
    "True Blood", "Grimm", "Once Upon a Time", "Merlin", "Roswell", "Dark Angel",
    "Dollhouse", "Altered Carbon", "12 Monkeys", "Continuum", "Orphan Black", "Travelers",
    "Warehouse 13", "Eureka", "Sanctuary", "Andromeda", "Lexx", "Red Dwarf",
    "Blake's 7", "Space: 1999", "UFO", "The Prisoner", "Land of the Giants",
    "Lost in Space", "The Time Tunnel", "Voyage to the Bottom of the Sea", "V",
    "Alien Nation", "Quantum Leap", "Sliders", "The Invaders",
    "The Six Million Dollar Man", "The Bionic Woman", "The Incredible Hulk", "Wonder Woman",
    "Tales from the Crypt", "Are You Afraid of the Dark?", "Goosebumps", "Eerie, Indiana",
    "The A-Team", "Knight Rider", "MacGyver", "Airwolf", "Street Hawk", "Starsky & Hutch",
    "The Dukes of Hazzard", "The Fall Guy", "T.J. Hooker", "CHiPs", "Charlie's Angels",
    "Mission: Impossible", "The Man from U.N.C.L.E.", "The Avengers", "The Saint",
    "The Persuaders!", "Robin of Sherwood", "The Adventures of Robin Hood", "Ivanhoe",
    "Zorro", "Tarzan", "Hercules: The Legendary Journeys", "Xena: Warrior Princess",
    "Highlander: The Series", "Conan the Adventurer", "Relic Hunter", "The Lost World",
    "BeastMaster", "Sinbad", "Walker, Texas Ranger", "Renegade", "Baywatch",
    "SeaQuest DSV", "21 Jump Street", "The Greatest American Hero", "Alias", "Nikita",
    "La Femme Nikita", "Spartacus", "Vikings", "The Last Kingdom", "Black Sails",
    "Marco Polo", "Game of Thrones", "The Witcher", "Band of Brothers", "The Pacific",
    "Combat!", "The Rat Patrol", "Tour of Duty", "Flash Gordon", "Thunderbirds",
    "Captain Scarlet and the Mysterons", "Stingray", "Fireball XL5", "Joe 90",
    "The Sopranos", "Breaking Bad", "Better Call Saul", "Mad Men", "Boardwalk Empire",
    "Peaky Blinders", "Downton Abbey", "The Crown", "Rome", "Deadwood", "Hell on Wheels",
    "Bonanza", "Gunsmoke", "Rawhide", "The Virginian", "The High Chaparral",
    "The Big Valley", "The Lone Ranger", "The Rifleman", "Maverick",
    "Have Gun - Will Travel", "Wanted: Dead or Alive", "Dr. Quinn, Medicine Woman",
    "Little House on the Prairie", "The Waltons", "Highway to Heaven",
    "Touched by an Angel", "7th Heaven", "Dallas", "Dynasty", "Falcon Crest",
    "Beverly Hills, 90210", "Melrose Place", "Dawson's Creek", "The O.C.",
    "One Tree Hill", "Gilmore Girls", "Gossip Girl", "Pretty Little Liars",
    "Desperate Housewives", "ER", "House", "Grey's Anatomy", "The Good Doctor",
    "The West Wing", "House of Cards", "Suits", "The Good Wife", "Boston Legal",
    "Ally McBeal", "Six Feet Under", "Friday Night Lights", "Parenthood", "This Is Us",
    "Roots", "Shogun", "North and South", "The Thorn Birds", "I, Claudius",
    "Brideshead Revisited", "Pride and Prejudice", "Upstairs, Downstairs", "Poldark",
    "All Creatures Great and Small", "Lassie", "Flipper", "Freaks and Geeks",
    "'Allo 'Allo!", "Only Fools and Horses", "On the Buses", "You Rang, M'Lord?",
    "Fawlty Towers", "Blackadder", "Mr. Bean", "Keeping Up Appearances",
    "Are You Being Served?", "Open All Hours", "Porridge", "Some Mothers Do 'Ave 'Em",
    "The Benny Hill Show", "Monty Python's Flying Circus", "The Young Ones", "Father Ted",
    "Men Behaving Badly", "The Vicar of Dibley", "Absolutely Fabulous",
    "The IT Crowd", "Peep Show", "The Office", "Extras", "Bottom", "Yes Minister",
    "Yes, Prime Minister", "Mind Your Language", "Last of the Summer Wine",
    "Rising Damp", "Steptoe and Son", "George and Mildred",
    "ALF", "3rd Rock from the Sun", "Everybody Loves Raymond", "Seinfeld", "Friends",
    "Married... with Children", "Cheers", "Frasier", "M*A*S*H", "The Golden Girls",
    "Roseanne", "The Fresh Prince of Bel-Air", "Home Improvement", "Full House",
    "Step by Step", "Family Matters", "Saved by the Bell", "Boy Meets World",
    "The Cosby Show", "Different Strokes", "Who's the Boss?", "Growing Pains",
    "Night Court", "Taxi", "WKRP in Cincinnati", "Happy Days", "Laverne & Shirley",
    "Three's Company", "All in the Family", "The Jeffersons", "Sanford and Son",
    "Good Times", "The Mary Tyler Moore Show", "The Bob Newhart Show",
    "Bewitched", "I Dream of Jeannie", "I Love Lucy", "The Dick Van Dyke Show",
    "The Andy Griffith Show", "The Beverly Hillbillies", "Green Acres", "Get Smart",
    "The Addams Family", "The Munsters", "Gilligan's Island", "Hogan's Heroes",
    "Benson", "Soap", "Perfect Strangers", "Mork & Mindy", "That '70s Show",
    "The King of Queens", "Malcolm in the Middle", "Scrubs", "NewsRadio",
    "Will & Grace", "30 Rock", "Parks and Recreation", "Community", "Brooklyn Nine-Nine",
    "How I Met Your Mother", "The Big Bang Theory", "Two and a Half Men", "Modern Family",
    "Batman: The Animated Series", "Superman: The Animated Series", "Spider-Man",
    "X-Men: The Animated Series", "DuckTales", "Chip 'n Dale: Rescue Rangers", "TaleSpin",
    "Darkwing Duck", "Gargoyles", "Avatar: The Last Airbender", "Teenage Mutant Ninja Turtles",
    "He-Man and the Masters of the Universe", "The Transformers", "Inspector Gadget",
    "Animaniacs", "Pinky and the Brain", "Tiny Toon Adventures", "Rugrats", "Hey Arnold!",
    "Dexter's Laboratory", "The Powerpuff Girls", "Johnny Bravo", "Samurai Jack",
    "Dora the Explorer", "Dora and Friends: Into the City!", "Barney & Friends",
    "Peppa Pig", "PAW Patrol", "SpongeBob SquarePants", "Scooby-Doo, Where Are You!",
    "The Flintstones", "The Simpsons", "Futurama", "Dragon Ball", "Dragon Ball Z", "Pokémon"
]

AUTHORS = [
    ("Alfred Hitchcock", "filmography-alfred-hitchcock"),
    ("Francis Ford Coppola", "filmography-francis-ford-coppola"),
    ("Brian De Palma", "brian-de-palma-complete-filmography"),
    ("Sergio Leone", "filmography-sergio-leone"),
    ("Lars von Trier", "lars-von-trier-filmography"),
    ("Paul Verhoeven", "filmography-paul-verhoeven"),
    ("Roman Polanski", "roman-polanski-filmography"),
    ("Werner Herzog", "filmography-werner-herzog"),
    ("Jean-Luc Godard", "jean-luc-godard-the-complete-filmography"),
    ("David Cronenberg", "filmography-david-cronenberg"),
    ("John Cassavetes", "filmography-john-cassavetes"),
    ("Mira Nair", "filmography-mira-nair"),
    ("Takeshi Kitano", "filmography-takeshi-kitano"),
    ("Freddie Francis", "1987.-dark-tower"),
    ("Elvis Presley", "filmography-elvis-presley"),
    ("David Lean", "lawrence.of.-arabia.-1962.1080p.-remastered.-blu-ray.-hevc.-eac-3-sartre"),
    ("Ingmar Bergman", "filmography-ingmar-bergman"),
    ("Roger Corman", "filmography-roger-corman"),
    ("David DeCoteau", "filmography-david-decoteau"),
    ("François Truffaut", "filmography-francois-truffaut"),
    ("Pier Paolo Pasolini", "filmography-pier-paolo-pasolini"),
    ("Budd Boetticher", "filmography-budd-boetticher"),
    ("Wim Wenders", "filmography-wim-wenders"),
    ("Nicholas Roeg", "walkabout-1971-remastered-1080p-blu-ray-hevc-h-265-bone"),
    ("John Ford", "3-bad-men-1926_202607"),
    ("Mario Bava", "filmography-mario-bava"),
    ("Akira Kurosawa", "filmography-akira-kurosawa"),
    ("Jacques Rivette", "jacques-rivette-the-complete-filmography"),
    ("Costa-Gavras", "filmography-costa-gavras"),
    ("Louis Malle", "filmography-louis-malle"),
    ("Federico Fellini", "filmography-federico-fellini"),
    ("George A. Romero", "filmography-george-a-romero"),
    ("Otto Preminger", "filmography-otto-preminger"),
    ("Herschell Gordon Lewis", "filmography-herschell-gordon-lewis"),
    ("Harmony Korine", "filmography-harmony-korine"),
    ("Orson Welles", "filmography-orson-welles"),
    ("Norman Jewison", "filmography-norman-jewison"),
    ("Curtis Harrington", "filmography-curtis-harrington"),
    ("Bernardo Bertolucci", "1987.-the-last-emperor"),
    ("Ken Russell", "filmography-ken-russell-no-features"),
    ("Krzysztof Kieślowski", "filmography-krzysztof-kieslowski"),
    ("Alejandro Jodorowsky", "filmography-alejandro-jodorowsky"),
    ("Ruggero Deodato", "filmography-ruggero-deodato"),
    ("Andy Milligan", "filmography-andy-milligan"),
    ("Ted V. Mikels", "filmography-ted-v-mikels"),
    ("Lina Wertmüller", "filmography-lina-wertmuller"),
    ("Carl Theodor Dreyer", "filmography-carl-theodor-dreyer"),
    ("Greydon Clark", "filmography-greydon-clark"),
    ("Bill Zebub", "filmography-bill-zebub"),
    ("Sean Baker", "the.-florida.-project.-2017.2160p.-webrip.-3500-mb.-ddp-5.1.x-264-galaxy-rg"),
    ("PTA Filmography", "boogie-nights-1997_202604"),
    ("Andy Warhol", "andy-warhol-complete-films-collection"),
    ("Leos Carax", "filmography-leos-carax"),
    ("Volker Schlöndorff", "filmography-volker-schlondorff"),
    ("Albert Pyun", "nemesis.-1992.1080p.-blu-ray.-ddp-2.0.x-265.10bit-galaxy-rg-265"),
    ("Giuseppe Tornatore", "filmography-giuseppe-tornatore"),
    ("Bo Widerberg", "filmography-bo-widerberg"),
    ("Jackie Collins", "filmography-jackie-collins"),
    ("Rick Sloane", "filmography-rick-sloane"),
    ("Errol Morris", "filmography-errol-morris"),
    ("Gordon Hessler", "filmography-gordon-hessler"),
    ("Russ Meyer", "filmography-russ-meyer"),
    ("Gaspar Noé", "filmography-gaspar-noe"),
]


def safe_get(url, params=None, timeout=15):
    for attempt in range(3):
        try:
            r = session.get(url, params=params, timeout=timeout)
            if r.status_code == 200:
                return r.json()
            if r.status_code == 429:
                time.sleep(2.5 * (attempt + 1))
        except Exception:
            time.sleep(1.0)
    return None


def verify_stream_url_playable(url):
    if not url or not url.startswith("http"):
        return False
    try:
        r = session.get(
            url,
            headers={"Range": "bytes=0-1"},
            allow_redirects=True,
            stream=True,
            timeout=10
        )
        status = r.status_code
        ctype = (r.headers.get("Content-Type") or "").lower()
        r.close()
        if status in (200, 206) and "text/html" not in ctype:
            return True
    except Exception:
        pass
    return False


def supabase_upsert(table, rows, conflict_col):
    if not rows:
        return []
    url = f"{SUPABASE_URL}/rest/v1/{table}?on_conflict={conflict_col}"
    try:
        r = session.post(url, headers=SB_HEADERS, json=rows, timeout=20)
        if r.status_code in (200, 201):
            return r.json()
        print(f"[Supabase greška {table}] {r.status_code}: {r.text[:250]}", flush=True)
    except Exception as e:
        print(f"[Supabase iznimka {table}] {e}", flush=True)
    return []


def supabase_patch_row(table, row_id, payload):
    url = f"{SUPABASE_URL}/rest/v1/{table}?id=eq.{row_id}"
    try:
        r = session.patch(url, headers=SB_HEADERS, json=payload, timeout=15)
        if r.status_code not in (200, 204):
            print(f"[Supabase PATCH greška {table} id={row_id}] {r.status_code}: {r.text[:200]}", flush=True)
    except Exception as e:
        print(f"[Supabase PATCH iznimka] {e}", flush=True)


def supabase_delete_author_movies(author_db_id):
    url = f"{SUPABASE_URL}/rest/v1/author_movies?author_id=eq.{author_db_id}"
    try:
        session.delete(url, headers=SB_HEADERS, timeout=15)
    except Exception as e:
        print(f"[Supabase DELETE author_movies] {e}", flush=True)


def contains_hentai_or_adult(*texts):
    blob = " ".join(str(t) for t in texts if t).lower()
    return any(kw in blob for kw in HENTAI_AND_ADULT_KEYWORDS)


def is_foreign_dub_without_english(archive_md, identifier, filename="", orig_lang=""):
    parts = [identifier, filename]
    for field in ["title", "description", "subject", "language"]:
        val = archive_md.get(field)
        if isinstance(val, list):
            parts.extend(str(x) for x in val)
        elif val:
            parts.append(str(val))
    blob = " ".join(parts).lower()

    has_english_or_dual = bool(re.search(r"\b(eng|english|dual[\s\-_]?audio|multi[\s\-_]?audio)\b", blob))
    if has_english_or_dual:
        return False

    if any(kw in blob for kw in EXPLICIT_DUB_KEYWORDS):
        return True

    if orig_lang == "en":
        lang_field = archive_md.get("language")
        lang_list = lang_field if isinstance(lang_field, list) else ([lang_field] if lang_field else [])
        lang_tokens = {
            tok for item in lang_list for tok in re.split(r"[^a-zA-Zčćžšđãõáéíóúñ]+", str(item).lower()) if tok
        }
        if lang_tokens and not (lang_tokens & {"eng", "english"}) and (lang_tokens & NON_ENGLISH_LANG_TOKENS):
            return True

        words = set(re.split(r"[^a-zA-Zčćžšđãõáéíóúñ]+", f"{identifier} {filename}".lower()))
        if words & NON_ENGLISH_LANG_TOKENS:
            return True

    return False


def pick_best_video_file(video_files):
    """Daje prednost izvornim .mp4 i .mkv datotekama (više audio traka) i automatski pada na .ia.mp4."""
    def sort_key(f):
        fname_lower = f["filename"].lower()
        is_deriv = f.get("is_derivative", False)
        if not is_deriv and fname_lower.endswith(".mp4"):
            tier = 0
        elif not is_deriv:
            tier = 1
        else:
            tier = 2
        return (tier, -f["size_mb"])

    ordered = sorted(video_files, key=sort_key)
    for candidate in ordered:
        if verify_stream_url_playable(candidate["stream_url"]):
            return candidate
    return ordered[0]


def determine_primary_genres(genre_ids, companies, orig_title, hr_title, overview, hint_genre=None):
    """Dodjeljuje sve stvarne TMDB žanrove filma (do 3) kako bi svaka žanrovska biblioteka imala 250+ filmova."""
    if 16 in genre_ids:
        company_blob = " ".join(str(c).lower() for c in companies)
        text_blob = f"{orig_title} {hr_title} {overview} {company_blob}".lower()
        is_disney_or_pixar = any(
            kw in text_blob for kw in ["disney", "pixar", "walt disney", "mickey mouse", "donald duck"]
        )
        sub_genre = "Disney i Pixar klasici" if is_disney_or_pixar else "Animirani filmovi"
        return ["Animirani klasici", sub_genre]

    mapped_ordered = []
    for gid in genre_ids:
        g_name = TMDB_MOVIE_GENRE_MAP.get(gid)
        if g_name and g_name not in mapped_ordered:
            mapped_ordered.append(g_name)

    if hint_genre and hint_genre not in mapped_ordered:
        mapped_ordered.append(hint_genre)

    if not mapped_ordered:
        return ["Drama"]

    return mapped_ordered[:3]


def determine_tv_primary_genres(genre_ids, orig_name, hr_name):
    animated_or_kids_keywords = [
        "dora", "barney", "peppa", "paw patrol", "spongebob", "scooby",
        "flintstones", "simpsons", "futurama", "batman: the animated", "ducktales",
        "avatar: the last airbender", "x-men: the animated", "animated",
        "superman: the animated", "spider-man", "rescue rangers", "talespin",
        "darkwing duck", "gargoyles", "ninja turtles", "he-man", "transformers",
        "inspector gadget", "animaniacs", "pinky and the brain", "tiny toon",
        "rugrats", "hey arnold", "dexter", "powerpuff", "johnny bravo",
        "samurai jack", "dragon ball", "pokémon", "pokemon"
    ]
    blob = f"{orig_name} {hr_name}".lower()
    if 16 in genre_ids or 10762 in genre_ids or any(kw in blob for kw in animated_or_kids_keywords):
        return ["Animirane serije"]

    mapped = []
    for g in genre_ids:
        label = TMDB_TV_GENRE_MAP.get(g)
        if label and label not in mapped:
            mapped.append(label)

    if not mapped:
        return ["Dramske i obiteljske serije"]

    for priority_genre in [
        "Sci-Fi i fantastika serije",
        "Krimi, detektivske i triler serije",
        "Avanturističke serije",
        "Komedije i sitcomi",
        "Dramske i obiteljske serije"
    ]:
        if priority_genre in mapped:
            return [priority_genre]

    return [mapped[0]]


def parse_duration_minutes(raw_val):
    if raw_val is None:
        return 0
    if isinstance(raw_val, list):
        raw_val = raw_val[0] if raw_val else ""
    s = str(raw_val).strip().lower()
    if not s:
        return 0
    if ":" in s:
        parts = [p for p in s.split(":") if p.replace(".", "", 1).isdigit()]
        try:
            if len(parts) == 3:
                return int(round(float(parts[0]) * 60 + float(parts[1]) + float(parts[2]) / 60.0))
            if len(parts) == 2:
                return int(round(float(parts[0]) + float(parts[1]) / 60.0))
        except ValueError:
            return 0
    m_min = re.search(r"(\d+(?:\.\d+)?)\s*min", s)
    if m_min:
        return int(round(float(m_min.group(1))))
    try:
        num = float(re.sub(r"[^\d.]", "", s))
        return int(round(num / 60.0)) if num > 300 else int(round(num))
    except ValueError:
        return 0


def format_duration_text(mins):
    if mins <= 0:
        return ""
    if mins >= 60:
        return f"{mins // 60}h {mins % 60:02d}m"
    return f"{mins} min"


def normalize_for_exact_title_compare(raw_title):
    s = re.sub(r"\.(mp4|mkv|avi|ogv|mov)$", "", str(raw_title), flags=re.IGNORECASE)
    s = s.replace("&", " and ")
    s = re.sub(r"\[[^\]]*\]", " ", s)
    s = re.sub(r"[._]+", " ", s)
    s = re.sub(r"[\(\[\{]\s*(19\d{2}|20\d{2})\s*[\)\]\}]", " ", s)
    s = RELEASE_TAG_PATTERN.sub(" ", s)
    s = re.sub(r"\b(19\d{2}|20\d{2})\b\s*$", " ", s)
    s = re.sub(r"[^\w\s]", " ", s.lower())
    s = re.sub(r"\s+", " ", s).strip()
    s = re.sub(r"^(the|a|an)\s+", "", s).strip()
    return s


def extract_series_core_title(raw_title):
    s = str(raw_title).rsplit("/", 1)[-1]
    s = re.sub(r"\.(mp4|mkv|avi|ogv|mov)$", "", s, flags=re.IGNORECASE)
    s = s.replace("&", " and ")
    s = re.sub(r"(?i)\bdr\.?\s+", "doctor ", s)
    s = re.sub(r"(?i)\bm['’]lord\b", "my lord", s)
    s = re.sub(r"[._]+", " ", s)
    s = re.sub(r"(?i)\bS\d{1,2}\s*[-_.]?\s*E\d{1,3}.*$", "", s)
    s = re.sub(r"(?i)\b\d{1,2}x\d{1,3}.*$", "", s)
    s = re.sub(r"(?i)\b(?:Season|Sezona|Series|Book|Part)\s*\d{1,2}.*$", "", s)
    s = re.sub(r"(?i)\bS\d{1,2}\b.*$", "", s)
    s = re.sub(r"(?i)\bComplete\s+(?:Series|Seasons?|Collection).*$", "", s)
    s = re.sub(r"[\(\[\{]\s*(19\d{2}|20\d{2})\s*[\)\]\}]", " ", s)
    s = RELEASE_TAG_PATTERN.sub(" ", s)
    s = re.sub(r"\b(19\d{2}|20\d{2})\b\s*$", " ", s)
    s = re.sub(r"[^\w\s]", " ", s.lower())
    s = re.sub(r"\s+", " ", s).strip()
    s = re.sub(r"^(the|a|an)\s+", "", s).strip()
    return s


def series_core_matches(candidate_core, valid_cores):
    if not candidate_core:
        return False
    if candidate_core in valid_cores:
        return True
    for vc in valid_cores:
        if len(vc) >= 6 and candidate_core.startswith(vc + " "):
            return True
    return False


def extract_year_from_text(text):
    m = re.search(r"\b(19\d{2}|20\d{2})\b", str(text))
    return int(m.group(1)) if m else 0


def clean_filename_to_title(filename):
    base = filename.rsplit("/", 1)[-1]
    name = re.sub(r"\.(mp4|mkv|avi|ogv|mov)$", "", base, flags=re.IGNORECASE)
    year_match = re.search(r"\b(19\d{2}|20\d{2})\b", name)
    year = int(year_match.group(1)) if year_match else 0

    clean = re.sub(r"\[[^\]]*\]", " ", name)
    clean = re.sub(r"\(\([^)]*\)\)", " ", clean)
    clean = re.sub(r"[._]+", " ", clean)
    clean = RELEASE_TAG_PATTERN.sub("", clean)
    if year:
        clean = re.sub(rf"\(?\b{year}\b\)?", "", clean)
    clean = re.sub(r"\(\s*\)|\[\s*\]", "", clean)
    clean = re.sub(r"^\s*\d+[\.\-\s]+", "", clean)
    clean = re.sub(r"\s+", " ", clean).strip(" -")
    return clean.strip(), year


def get_archive_item_details(identifier, min_size_mb=35, allow_mkv=False):
    time.sleep(0.03)
    meta = safe_get(f"https://archive.org/metadata/{identifier}")
    if not meta or meta.get("is_dark") or meta.get("is_restricted"):
        return [], {}

    md = meta.get("metadata") or {}
    if str(md.get("access-restricted-item") or "").lower() == "true":
        return [], {}

    meta_runtime = parse_duration_minutes(md.get("runtime"))
    files = meta.get("files", [])
    videos = []

    allowed_exts = (".mp4", ".mkv") if allow_mkv else (".mp4",)

    for f in files:
        fname = f.get("name", "")
        if not fname.lower().endswith(allowed_exts):
            continue
        if any(skip in fname.lower() for skip in ["trailer", "sample", "teaser", "preview", "bestof", "superfans", "promo"]):
            continue
        try:
            size_mb = int(f.get("size", 0)) / (1024 * 1024)
        except ValueError:
            size_mb = 0
        if size_mb < min_size_mb:
            continue

        file_mins = parse_duration_minutes(f.get("length")) or meta_runtime
        encoded_name = "/".join(urllib.parse.quote(p) for p in fname.split("/"))
        stream_url = f"https://archive.org/download/{identifier}/{encoded_name}"
        videos.append({
            "filename": fname,
            "size_mb": size_mb,
            "duration_mins": file_mins,
            "stream_url": stream_url,
            "is_derivative": ".ia.mp4" in fname.lower() or f.get("source") == "derivative"
        })

    return videos, md


def get_tmdb_movie_details(tmdb_id):
    if not tmdb_id:
        return None
    data = safe_get(
        f"{TMDB_BASE}/movie/{tmdb_id}",
        {"api_key": TMDB_API_KEY, "language": "hr-HR", "append_to_response": "credits"}
    )
    if not data:
        return None

    credits = data.get("credits") or {}
    directors = [
        c.get("name", "") for c in credits.get("crew", [])
        if c.get("job") == "Director" and c.get("name")
    ]
    all_crew = [
        c.get("name", "") for c in credits.get("crew", [])
        if c.get("name")
    ]
    cast = [
        c.get("name", "") for c in (credits.get("cast") or [])[:12]
        if c.get("name")
    ]
    genre_ids = [g.get("id") for g in (data.get("genres") or []) if g.get("id")]
    companies = [c.get("name", "") for c in (data.get("production_companies") or []) if c.get("name")]

    overview = (data.get("overview") or "").strip()
    if not overview:
        en_data = safe_get(f"{TMDB_BASE}/movie/{tmdb_id}", {"api_key": TMDB_API_KEY, "language": "en-US"})
        overview = ((en_data or {}).get("overview") or "").strip()
    if not overview:
        parts = []
        rel_y = extract_year_from_text(data.get("release_date") or "")
        if rel_y:
            parts.append(f"Godina: {rel_y}.")
        if directors:
            parts.append(f"Redatelj: {', '.join(directors)}.")
        if cast:
            parts.append(f"Uloge: {', '.join(cast[:4])}.")
        overview = " ".join(parts)

    return {
        "tmdb_id": tmdb_id,
        "runtime": int(data.get("runtime") or 0),
        "status": str(data.get("status") or ""),
        "release_date": str(data.get("release_date") or ""),
        "orig_title": str(data.get("original_title") or data.get("title") or ""),
        "hr_title": str(data.get("title") or data.get("original_title") or ""),
        "original_language": str(data.get("original_language") or ""),
        "overview_hr": overview,
        "poster_path": data.get("poster_path") or "",
        "backdrop_path": data.get("backdrop_path") or "",
        "vote_average": float(data.get("vote_average") or 0.0),
        "popularity": float(data.get("popularity") or 10.0),
        "directors": directors,
        "all_crew": all_crew,
        "cast": cast,
        "genre_ids": genre_ids,
        "companies": companies,
        "adult": bool(data.get("adult", False))
    }


def is_future_release(release_date_str):
    if not release_date_str or len(release_date_str) < 10:
        return False
    try:
        rel_dt = datetime.strptime(release_date_str[:10], "%Y-%m-%d").replace(tzinfo=timezone.utc)
        return rel_dt > datetime.now(timezone.utc)
    except ValueError:
        return False


def has_multiple_tmdb_versions(orig_title):
    res = safe_get(f"{TMDB_BASE}/search/movie", {"api_key": TMDB_API_KEY, "query": orig_title, "language": "en-US"})
    target_norm = normalize_for_exact_title_compare(orig_title)
    years = set()
    for r in (res or {}).get("results", []):
        if normalize_for_exact_title_compare(r.get("original_title") or r.get("title") or "") == target_norm:
            y = extract_year_from_text(r.get("release_date") or "")
            if y > 0:
                years.add(y)
    return len(years) > 1


def credits_match_archive_metadata(archive_md, identifier, directors, cast):
    parts = [identifier]
    for field in ["title", "description", "creator", "subject"]:
        val = archive_md.get(field)
        if isinstance(val, list):
            parts.extend(str(x) for x in val)
        elif val:
            parts.append(str(val))
    blob = re.sub(r"[^\w\s]", " ", " ".join(parts).lower())
    blob_words = set(blob.split())

    for person in (directors + cast):
        tokens = [t for t in re.sub(r"[^\w\s]", " ", person.lower()).split() if len(t) >= 4]
        if tokens and tokens[-1] in blob_words:
            return True
    return False


def validate_movie_match(
    identifier,
    archive_md,
    archive_mins,
    tmdb_details,
    expected_titles,
    expected_year,
    require_credits=False,
    chosen_filename=""
):
    ident_lower = identifier.lower()
    if ident_lower.startswith(("youtube-", "yt-")):
        return False, "youtube_rip"

    raw_arch_title = archive_md.get("title") or ""
    if isinstance(raw_arch_title, list):
        raw_arch_title = raw_arch_title[0] if raw_arch_title else ""
    raw_arch_title = str(raw_arch_title)

    raw_arch_desc = archive_md.get("description") or ""
    if isinstance(raw_arch_desc, list):
        raw_arch_desc = " ".join(str(x) for x in raw_arch_desc)

    if tmdb_details and tmdb_details.get("adult"):
        return False, "adult_flag"

    if contains_hentai_or_adult(ident_lower, raw_arch_title, raw_arch_desc, *expected_titles):
        return False, "hentai_or_adult_blocked"

    orig_lang = tmdb_details.get("original_language", "") if tmdb_details else ""
    if is_foreign_dub_without_english(archive_md, identifier, chosen_filename, orig_lang):
        return False, "foreign_dub_without_english"

    combined_meta = f"{ident_lower} {raw_arch_title.lower()} {str(raw_arch_desc).lower()}"
    if any(kw in combined_meta for kw in SPAM_OR_FAKE_KEYWORDS):
        return False, "spam_or_non_movie_keyword"

    tmdb_runtime = tmdb_details["runtime"] if tmdb_details else 0
    tmdb_status = tmdb_details["status"] if tmdb_details else ""
    tmdb_rel = tmdb_details["release_date"] if tmdb_details else ""

    if tmdb_status and tmdb_status != "Released":
        return False, f"unreleased_status ({tmdb_status})"

    if is_future_release(tmdb_rel):
        return False, f"future_release ({tmdb_rel})"

    norm_candidates = {normalize_for_exact_title_compare(t) for t in expected_titles if t}
    norm_candidates.discard("")
    arch_norm_title = normalize_for_exact_title_compare(raw_arch_title or identifier)
    arch_norm_ident = normalize_for_exact_title_compare(identifier)

    if norm_candidates and (arch_norm_title not in norm_candidates and arch_norm_ident not in norm_candidates):
        return False, f"title_mismatch ('{arch_norm_title}' != {list(norm_candidates)})"

    if expected_year > 0:
        arch_year = (
            extract_year_from_text(raw_arch_title)
            or extract_year_from_text(archive_md.get("year") or archive_md.get("date") or "")
            or extract_year_from_text(identifier)
        )
        if arch_year > 0:
            max_diff = 0 if expected_year >= 2024 else 1
            if abs(arch_year - expected_year) > max_diff:
                return False, f"year_mismatch (archive={arch_year} vs tmdb={expected_year})"

    if tmdb_runtime > 0:
        if archive_mins <= 0:
            return False, f"unknown_archive_runtime (tmdb={tmdb_runtime}m)"
        min_allowed = int(tmdb_runtime * 0.90)
        max_allowed = int(tmdb_runtime * 1.12)
        if archive_mins < min_allowed or archive_mins > max_allowed:
            return False, f"runtime_mismatch (archive={archive_mins}m vs tmdb={tmdb_runtime}m)"
    else:
        if archive_mins < 70:
            return False, f"too_short_no_tmdb_runtime ({archive_mins}m)"

    must_check_credits = require_credits or (expected_year >= 2024)
    if must_check_credits and tmdb_details:
        if not credits_match_archive_metadata(
            archive_md, identifier, tmdb_details.get("directors", []), tmdb_details.get("cast", [])
        ):
            return False, "credits_not_verified_in_metadata"

    return True, "ok"


def audit_and_dedupe_catalog():
    """Uklanja duplikate (npr. dupli Psiho/Pijanist), proširuje žanrove postojećih filmova i čuva potvrđene serije."""
    print("1/8 Revizija baze (brisanje duplikata, proširenje žanrova postojećih filmova)...", flush=True)
    r = session.get(
        f"{SUPABASE_URL}/rest/v1/catalog_items?select=id,archive_id,tmdb_id,title,media_type,release_year,genres,description_hr,stream_url,episodes,is_valid&limit=3500",
        headers=SB_HEADERS,
        timeout=20
    )
    if r.status_code != 200:
        print(f"[Upozorenje] Dohvat tablice vratio status {r.status_code}: {r.text[:200]}", flush=True)
        return set(), set(), set(), []

    rows = r.json()
    existing_archive_ids = {row["archive_id"] for row in rows if row.get("archive_id")}
    seen_tmdb_movies = set()
    seen_title_years = set()
    seen_tmdb_series = set()
    movies_to_verify = []
    series_to_verify = []
    author_promoted_rows = []
    rejected_count = 0

    # Sortiraj tako da izravni zapisi imaju prednost ispred autorskih ",tmdb-" duplikata
    sorted_rows = sorted(rows, key=lambda x: (",tmdb-" in (x.get("archive_id") or ""), x.get("id") or 0))

    for row in sorted_rows:
        ident = row["archive_id"]
        media_type = row.get("media_type") or "movie"
        tmdb_id = row.get("tmdb_id") or 0

        if not row.get("is_valid", True):
            continue

        if contains_hentai_or_adult(row.get("title"), row.get("description_hr"), ident):
            supabase_patch_row("catalog_items", row["id"], {"is_valid": False})
            rejected_count += 1
            continue

        if media_type == "series":
            tv_id = tmdb_id
            if not tv_id and ident.startswith("tmdb-tv-"):
                try:
                    tv_id = int(ident.replace("tmdb-tv-", ""))
                except ValueError:
                    tv_id = 0
            if not tv_id or tv_id in seen_tmdb_series:
                supabase_patch_row("catalog_items", row["id"], {"is_valid": False})
                rejected_count += 1
                continue
            seen_tmdb_series.add(tv_id)
            eps = row.get("episodes") or []
            if len(eps) < 2 or not (row.get("stream_url") or "").lower().endswith(".mp4"):
                series_to_verify.append((row, tv_id))
            continue

        if media_type != "movie":
            continue

        if "Klasični crtići" in (row.get("genres") or []):
            continue

        title_year_key = (normalize_for_exact_title_compare(row.get("title") or ""), row.get("release_year") or 0)

        if tmdb_id > 0 and tmdb_id in seen_tmdb_movies:
            supabase_patch_row("catalog_items", row["id"], {"is_valid": False})
            rejected_count += 1
            print(f"  [FALSE - duplikat tmdb_id={tmdb_id}] {row['title']} ({ident})", flush=True)
            continue

        if title_year_key[0] and title_year_key in seen_title_years:
            supabase_patch_row("catalog_items", row["id"], {"is_valid": False})
            rejected_count += 1
            print(f"  [FALSE - duplikat naslova] {row['title']} ({ident})", flush=True)
            continue

        if ",tmdb-" in ident:
            if tmdb_id > 0:
                seen_tmdb_movies.add(tmdb_id)
            if title_year_key[0]:
                seen_title_years.add(title_year_key)
            author_promoted_rows.append(row)
            continue

        if tmdb_id > 0:
            movies_to_verify.append(row)
            if title_year_key[0]:
                seen_title_years.add(title_year_key)

    def verify_existing_movie(row):
        ident = row["archive_id"]
        tmdb_id = row.get("tmdb_id") or 0
        current_url = row.get("stream_url") or ""
        current_desc = (row.get("description_hr") or "").strip()

        tmdb_info = get_tmdb_movie_details(tmdb_id)

        if current_url.lower().endswith(".mp4") and current_desc and tmdb_info:
            primary_genres = determine_primary_genres(
                tmdb_info["genre_ids"],
                tmdb_info["companies"],
                tmdb_info["orig_title"],
                tmdb_info["hr_title"],
                tmdb_info["overview_hr"]
            )
            if primary_genres != (row.get("genres") or []):
                supabase_patch_row("catalog_items", row["id"], {"genres": primary_genres})
            return True, None

        orig_lang = tmdb_info.get("original_language", "") if tmdb_info else ""
        videos, arch_md = get_archive_item_details(ident, min_size_mb=20, allow_mkv=True)
        if not videos:
            supabase_patch_row("catalog_items", row["id"], {"is_valid": False})
            return False, f"  [FALSE - nedostupna stavka] {row['title']} ({ident})"

        best = pick_best_video_file(videos)
        if is_foreign_dub_without_english(arch_md, ident, best["filename"], orig_lang):
            supabase_patch_row("catalog_items", row["id"], {"is_valid": False})
            return False, f"  [FALSE - strana sinkronizacija bez EN] {row['title']} ({ident})"

        if not verify_stream_url_playable(best["stream_url"]):
            supabase_patch_row("catalog_items", row["id"], {"is_valid": False})
            return False, f"  [FALSE - neispravan stream] {row['title']} ({ident})"

        patch_payload = {}
        if best["stream_url"] != current_url:
            patch_payload["stream_url"] = best["stream_url"]
        if tmdb_info:
            if not current_desc and tmdb_info["overview_hr"]:
                patch_payload["description_hr"] = tmdb_info["overview_hr"]
            primary_genres = determine_primary_genres(
                tmdb_info["genre_ids"],
                tmdb_info["companies"],
                tmdb_info["orig_title"],
                tmdb_info["hr_title"],
                tmdb_info["overview_hr"]
            )
            if primary_genres != (row.get("genres") or []):
                patch_payload["genres"] = primary_genres

        if patch_payload:
            supabase_patch_row("catalog_items", row["id"], patch_payload)
        return True, None

    with ThreadPoolExecutor(max_workers=10) as pool:
        future_map = {pool.submit(verify_existing_movie, r_item): r_item for r_item in movies_to_verify}
        for fut in as_completed(future_map):
            r_item = future_map[fut]
            ok, msg = fut.result()
            if ok:
                seen_tmdb_movies.add(r_item["tmdb_id"])
            else:
                rejected_count += 1
                if msg:
                    print(msg, flush=True)

    for row, tv_id in series_to_verify:
        tv_details = safe_get(f"{TMDB_BASE}/tv/{tv_id}", {"api_key": TMDB_API_KEY, "language": "hr-HR"})
        if not tv_details:
            continue
        orig_name = tv_details.get("original_name") or tv_details.get("name") or row["title"]
        hr_name = tv_details.get("name") or orig_name
        g_ids = [g.get("id") for g in (tv_details.get("genres") or []) if g.get("id")]
        episodes = aggregate_series_from_archive(tv_id, orig_name, hr_name)
        if len(episodes) < 2:
            supabase_patch_row("catalog_items", row["id"], {"is_valid": False, "episodes": []})
            seen_tmdb_series.discard(tv_id)
            rejected_count += 1
        else:
            supabase_patch_row("catalog_items", row["id"], {
                "is_valid": True,
                "title": hr_name,
                "genres": determine_tv_primary_genres(g_ids, orig_name, hr_name),
                "stream_url": episodes[0]["url"],
                "episodes": episodes
            })

    print(
        f"  Revizija završena: {len(seen_tmdb_movies)} filmova, {len(seen_tmdb_series)} serija potvrđeno, {rejected_count} duplikata/neispravnih uklonjeno.",
        flush=True
    )
    return existing_archive_ids, seen_tmdb_movies, seen_tmdb_series, author_promoted_rows


def author_matches_tmdb_credits(author_name, details):
    if not details:
        return False
    search_person = "Paul Thomas Anderson" if author_name == "PTA Filmography" else author_name
    tokens = [t for t in re.sub(r"[^\w\s]", " ", search_person.lower()).split() if len(t) >= 4]
    if not tokens:
        return False
    surname = tokens[-1]
    people = (details.get("directors") or []) + (details.get("all_crew") or []) + (details.get("cast") or [])
    for p in people:
        p_words = set(re.sub(r"[^\w\s]", " ", str(p).lower()).split())
        if surname in p_words:
            return True
    return False


def title_closely_matches(clean_title, orig_title, hr_title):
    c_norm = normalize_for_exact_title_compare(clean_title)
    if len(c_norm) < 2:
        return False
    for target in (orig_title, hr_title):
        t_norm = normalize_for_exact_title_compare(target)
        if not t_norm:
            continue
        if c_norm == t_norm:
            return True
        if len(c_norm) >= 4 and len(t_norm) >= 4 and (c_norm in t_norm or t_norm in c_norm):
            return True
    return False


def tmdb_enrich_author_movie(title, year=0, author_name=""):
    letters_only = re.sub(r"[^a-zA-Zčćžšđа-яА-Я]", "", title)
    if len(letters_only) < 2:
        return None

    params = {"api_key": TMDB_API_KEY, "query": title, "language": "hr-HR", "include_adult": "false"}
    if year > 0:
        params["year"] = year
    res = safe_get(f"{TMDB_BASE}/search/movie", params=params)
    results = (res or {}).get("results", [])

    if not results and year > 0:
        params.pop("year")
        res = safe_get(f"{TMDB_BASE}/search/movie", params=params)
        results = (res or {}).get("results", [])

    if not results:
        return None

    best_match = None
    best_details = None

    for candidate in results[:5]:
        rel_date = candidate.get("release_date") or ""
        cand_year = int(rel_date[:4]) if len(rel_date) >= 4 and rel_date[:4].isdigit() else 0
        if year > 0 and cand_year > 0 and abs(cand_year - year) > 2:
            continue

        details = get_tmdb_movie_details(candidate["id"])
        if not details:
            continue

        orig_t = details["orig_title"]
        hr_t = details["hr_title"]
        credit_ok = author_matches_tmdb_credits(author_name, details)
        title_ok = title_closely_matches(title, orig_t, hr_t)

        if credit_ok and (title_ok or (year > 0 and cand_year > 0 and abs(cand_year - year) <= 1)):
            best_match = candidate
            best_details = details
            break

        if title_ok and year > 0 and cand_year > 0 and abs(cand_year - year) <= 1 and best_match is None:
            best_match = candidate
            best_details = details

    if not best_match or not best_details:
        return None

    rel_date = best_details["release_date"] or best_match.get("release_date") or ""
    rel_year = int(rel_date[:4]) if len(rel_date) >= 4 and rel_date[:4].isdigit() else year
    poster_path = best_details["poster_path"] or best_match.get("poster_path") or ""

    return {
        "tmdb_id": best_match["id"],
        "title": best_details["hr_title"] or best_match.get("title") or title,
        "release_year": rel_year,
        "description_hr": best_details["overview_hr"],
        "poster_url": f"{TMDB_IMG_POSTER}{poster_path}" if poster_path else "",
        "details": best_details
    }


def fetch_wiki_bio(author_name):
    search_name = "Paul Thomas Anderson" if author_name == "PTA Filmography" else author_name
    encoded = urllib.parse.quote(search_name.replace(" ", "_"))
    for lang in ["hr", "en"]:
        data = safe_get(f"https://{lang}.wikipedia.org/api/rest_v1/page/summary/{encoded}")
        if data and data.get("extract"):
            return data["extract"].strip()
    return ""


def sync_authors(existing_archive_ids, seen_tmdb_movies, author_promoted_rows):
    print("2/8 Sinkronizacija i čišćenje 63 kolekcije autora...", flush=True)
    promoted_total = 0

    for idx, (author_name, archive_id) in enumerate(AUTHORS, start=1):
        search_person = "Paul Thomas Anderson" if author_name == "PTA Filmography" else author_name
        person_res = safe_get(f"{TMDB_BASE}/search/person", {"api_key": TMDB_API_KEY, "query": search_person, "language": "hr-HR"})
        person = ((person_res or {}).get("results") or [None])[0]

        photo_url = ""
        bio_hr = ""
        if person:
            if person.get("profile_path"):
                photo_url = f"{TMDB_IMG_POSTER}{person['profile_path']}"
            details = safe_get(f"{TMDB_BASE}/person/{person['id']}", {"api_key": TMDB_API_KEY, "language": "hr-HR"})
            bio_hr = ((details or {}).get("biography") or "").strip()

        if not bio_hr:
            bio_hr = fetch_wiki_bio(author_name)

        saved_authors = supabase_upsert(
            "authors",
            [{
                "name": author_name,
                "archive_id": archive_id,
                "bio_hr": bio_hr,
                "photo_url": photo_url,
                "sort_order": idx
            }],
            "name"
        )
        if not saved_authors:
            continue
        author_db_id = saved_authors[0]["id"]

        videos, arch_md = get_archive_item_details(archive_id, min_size_mb=15, allow_mkv=True)
        if not videos:
            print(f"  [{idx}/{len(AUTHORS)}] {author_name}: kolekcija nedostupna/ograničena", flush=True)
            continue

        deduped = {}
        for m in videos:
            clean_t, year = clean_filename_to_title(m["filename"])
            if not clean_t:
                continue
            key = (clean_t.lower(), year)
            is_mp4 = m["filename"].lower().endswith(".mp4")
            score = m["size_mb"] + (1000.0 if is_mp4 else 0.0) + (500.0 if not m["is_derivative"] else 0.0)
            if key not in deduped or score > deduped[key]["score"]:
                deduped[key] = {
                    "title": clean_t,
                    "year": year,
                    "filename": m["filename"],
                    "stream_url": m["stream_url"],
                    "size_mb": m["size_mb"],
                    "duration_mins": m["duration_mins"],
                    "score": score
                }

        unique_movie_rows = {}
        promoted_for_author = 0
        valid_catalog_arch_ids = set()

        for item in sorted(deduped.values(), key=lambda x: x["year"], reverse=True):
            enriched = tmdb_enrich_author_movie(item["title"], item["year"], author_name)
            final_title = enriched["title"] if enriched else item["title"]
            final_year = enriched["release_year"] if (enriched and enriched["release_year"]) else item["year"]
            dedup_key = (final_title.lower(), final_year)
            if dedup_key not in unique_movie_rows:
                unique_movie_rows[dedup_key] = {
                    "author_id": author_db_id,
                    "title": final_title,
                    "release_year": final_year,
                    "stream_url": item["stream_url"],
                    "poster_url": enriched["poster_url"] if enriched else "",
                    "description_hr": enriched["description_hr"] if enriched else ""
                }

            tmdb_det = enriched.get("details") if enriched else None
            mid = enriched["tmdb_id"] if enriched else 0
            if tmdb_det and mid > 0:
                catalog_arch_id = f"{archive_id},tmdb-{mid}"
                tmdb_rt = tmdb_det["runtime"]
                file_rt = item["duration_mins"]
                is_feature_length = (file_rt >= 65) or (file_rt == 0 and tmdb_rt >= 65 and item["size_mb"] >= 250)
                runtime_consistent = (
                    file_rt == 0 or tmdb_rt == 0 or (int(tmdb_rt * 0.85) <= file_rt <= int(tmdb_rt * 1.15))
                )
                year_consistent = (
                    item["year"] == 0 or final_year == 0 or abs(item["year"] - final_year) <= 1
                )
                not_adult_or_dub = (
                    not tmdb_det["adult"]
                    and tmdb_det["status"] == "Released"
                    and not is_future_release(tmdb_det["release_date"])
                    and not contains_hentai_or_adult(tmdb_det["orig_title"], tmdb_det["hr_title"], tmdb_det["overview_hr"])
                    and not is_foreign_dub_without_english(arch_md, archive_id, item["filename"], tmdb_det["original_language"])
                )

                if (
                    is_feature_length
                    and runtime_consistent
                    and year_consistent
                    and not_adult_or_dub
                    and tmdb_det["poster_path"]
                    and verify_stream_url_playable(item["stream_url"])
                ):
                    valid_catalog_arch_ids.add(catalog_arch_id)
                    if mid not in seen_tmdb_movies:
                        genres = determine_primary_genres(
                            tmdb_det["genre_ids"],
                            tmdb_det["companies"],
                            tmdb_det["orig_title"],
                            tmdb_det["hr_title"],
                            tmdb_det["overview_hr"]
                        )
                        cat_row = {
                            "archive_id": catalog_arch_id,
                            "tmdb_id": mid,
                            "title": tmdb_det["hr_title"],
                            "media_type": "movie",
                            "genres": genres,
                            "release_year": final_year,
                            "rating": round(tmdb_det["vote_average"], 1),
                            "popularity_score": tmdb_det["popularity"] * 100.0 + (final_year * 2),
                            "description_hr": tmdb_det["overview_hr"],
                            "poster_url": f"{TMDB_IMG_POSTER}{tmdb_det['poster_path']}",
                            "backdrop_url": f"{TMDB_IMG_BACKDROP}{tmdb_det['backdrop_path']}" if tmdb_det["backdrop_path"] else "",
                            "stream_url": item["stream_url"],
                            "is_featured": tmdb_det["vote_average"] >= 8.0 and 16 not in tmdb_det["genre_ids"],
                            "is_valid": True,
                            "tmdb_runtime": tmdb_rt,
                            "archive_runtime": file_rt
                        }
                        supabase_upsert("catalog_items", [cat_row], "archive_id")
                        seen_tmdb_movies.add(mid)
                        existing_archive_ids.add(catalog_arch_id)
                        promoted_for_author += 1
                        promoted_total += 1

        for old_row in author_promoted_rows:
            if old_row["archive_id"].startswith(f"{archive_id},tmdb-") and old_row["archive_id"] not in valid_catalog_arch_ids:
                supabase_patch_row("catalog_items", old_row["id"], {"is_valid": False})

        supabase_delete_author_movies(author_db_id)
        movie_rows = list(unique_movie_rows.values())
        if movie_rows:
            supabase_upsert("author_movies", movie_rows, "author_id,title,release_year")

        print(
            f"  [{idx}/{len(AUTHORS)}] {author_name}: {len(movie_rows)} filmova (+{promoted_for_author} u žanrove)",
            flush=True
        )

    print(f"  Ukupno promovirano {promoted_total} autorskih filmova u glavne žanrove.", flush=True)


def search_verified_archive_for_movie(tmdb_details, year):
    if not tmdb_details:
        return None, None, 0
    if tmdb_details.get("adult"):
        return None, None, 0
    if tmdb_details["status"] and tmdb_details["status"] != "Released":
        return None, None, 0
    if is_future_release(tmdb_details["release_date"]):
        return None, None, 0
    if tmdb_details["runtime"] > 0 and tmdb_details["runtime"] < 60:
        return None, None, 0

    orig_title = tmdb_details["orig_title"]
    hr_title = tmdb_details["hr_title"]
    if contains_hentai_or_adult(orig_title, hr_title, tmdb_details["overview_hr"]):
        return None, None, 0

    clean_q = re.sub(r"[^\w\s]", " ", orig_title).strip()
    if len(clean_q) < 2:
        return None, None, 0

    require_credits = has_multiple_tmdb_versions(orig_title)
    year_clause = f" AND year:{year}" if year >= 2024 else (f" AND year:[{year - 1} TO {year + 1}]" if year > 0 else "")
    query = f'title:"{clean_q}"{year_clause} AND mediatype:movies AND -subject:trailer AND -subject:news AND -subject:review'

    data = safe_get(
        "https://archive.org/advancedsearch.php",
        params={
            "q": query,
            "fl[]": ["identifier", "title", "downloads"],
            "sort[]": "downloads desc",
            "rows": 12,
            "output": "json"
        }
    )
    docs = (data or {}).get("response", {}).get("docs", [])
    expected_titles = [orig_title, hr_title]

    for doc in docs:
        ident = doc.get("identifier")
        if not ident or ident.lower().startswith(("youtube-", "yt-")):
            continue
        videos, arch_md = get_archive_item_details(ident, min_size_mb=220, allow_mkv=True)
        if not videos:
            continue
        best = pick_best_video_file(videos)
        arch_mins = best["duration_mins"]

        ok, _ = validate_movie_match(
            ident,
            arch_md,
            arch_mins,
            tmdb_details,
            expected_titles,
            year,
            require_credits=require_credits,
            chosen_filename=best["filename"]
        )
        if ok and verify_stream_url_playable(best["stream_url"]):
            return ident, best["stream_url"], arch_mins

    return None, None, 0


def sync_tmdb_movies_by_genre(seen_tmdb_movies):
    """Duboka pretraga TMDB-a (25 stranica po svakom žanru) za punjenje svih žanrova na 250+ naslova."""
    print("3/8 Duboka pretraga TMDB-a po svim žanrovima (cilj: 250+ filmova po žanru)...", flush=True)
    candidates = {}

    for sort_by in ["vote_count.desc", "popularity.desc"]:
        for page in range(1, 26):
            data = safe_get(
                f"{TMDB_BASE}/discover/movie",
                {
                    "api_key": TMDB_API_KEY,
                    "language": "hr-HR",
                    "without_genres": "16,99",
                    "include_adult": "false",
                    "vote_count.gte": 300,
                    "sort_by": sort_by,
                    "page": page
                }
            )
            for m in (data or {}).get("results", []):
                mid = m["id"]
                if mid not in seen_tmdb_movies and mid not in candidates and not is_future_release(m.get("release_date") or ""):
                    candidates[mid] = (m, None)

    for page in range(1, 21):
        data = safe_get(f"{TMDB_BASE}/movie/top_rated", {"api_key": TMDB_API_KEY, "language": "hr-HR", "page": page})
        for m in (data or {}).get("results", []):
            mid = m["id"]
            if mid not in seen_tmdb_movies and mid not in candidates and not is_future_release(m.get("release_date") or ""):
                candidates[mid] = (m, None)

    # Duboka pretraga po svakom pojedinačnom žanru (25 stranica x 3 poretka)
    for tmdb_genre_id, hr_genre_label, min_votes in TMDB_DISCOVER_GENRE_SEEDS:
        for sort_by in ["popularity.desc", "vote_count.desc", "vote_average.desc"]:
            for page in range(1, 26):
                data = safe_get(
                    f"{TMDB_BASE}/discover/movie",
                    {
                        "api_key": TMDB_API_KEY,
                        "language": "hr-HR",
                        "with_genres": tmdb_genre_id,
                        "without_genres": "16,99",
                        "include_adult": "false",
                        "vote_count.gte": min_votes,
                        "sort_by": sort_by,
                        "page": page
                    }
                )
                for m in (data or {}).get("results", []):
                    mid = m["id"]
                    if mid not in seen_tmdb_movies and mid not in candidates and not is_future_release(m.get("release_date") or ""):
                        candidates[mid] = (m, hr_genre_label)

    # Animirani dugometražni filmovi
    for sort_by in ["popularity.desc", "vote_count.desc"]:
        for page in range(1, 21):
            data = safe_get(
                f"{TMDB_BASE}/discover/movie",
                {
                    "api_key": TMDB_API_KEY,
                    "language": "hr-HR",
                    "with_genres": "16",
                    "include_adult": "false",
                    "vote_count.gte": 80,
                    "sort_by": sort_by,
                    "page": page
                }
            )
            for m in (data or {}).get("results", []):
                mid = m["id"]
                if mid not in seen_tmdb_movies and mid not in candidates and not is_future_release(m.get("release_date") or ""):
                    candidates[mid] = (m, None)

    print(f"  Ukupno {len(candidates)} novih TMDB kandidata za provjeru na Archive.org...", flush=True)

    def process_movie(item):
        m, hint_genre = item
        mid = m["id"]
        rel_date = m.get("release_date") or ""
        year = int(rel_date[:4]) if len(rel_date) >= 4 and rel_date[:4].isdigit() else 0
        details = get_tmdb_movie_details(mid)
        if not details:
            return None

        arch_id, stream_url, arch_mins = search_verified_archive_for_movie(details, year)
        if not arch_id:
            return None

        with state_lock:
            if mid in seen_tmdb_movies:
                return None
            seen_tmdb_movies.add(mid)

        genre_ids = details["genre_ids"] or m.get("genre_ids", [])
        genres = determine_primary_genres(
            genre_ids, details["companies"], details["orig_title"], details["hr_title"], details["overview_hr"], hint_genre
        )
        row = {
            "archive_id": arch_id,
            "tmdb_id": mid,
            "title": details["hr_title"],
            "media_type": "movie",
            "genres": genres,
            "release_year": year,
            "rating": round(float(m.get("vote_average") or 0.0), 1),
            "popularity_score": float(m.get("popularity") or 10.0) * 100.0 + (year * 2),
            "description_hr": details["overview_hr"],
            "poster_url": f"{TMDB_IMG_POSTER}{m['poster_path']}" if m.get("poster_path") else "",
            "backdrop_url": f"{TMDB_IMG_BACKDROP}{m['backdrop_path']}" if m.get("backdrop_path") else "",
            "stream_url": stream_url,
            "is_featured": float(m.get("vote_average") or 0) >= 7.8 and 16 not in genre_ids,
            "is_valid": True,
            "tmdb_runtime": details["runtime"],
            "archive_runtime": arch_mins
        }
        supabase_upsert("catalog_items", [row], "archive_id")
        return f"  + TMDB->Archive: {row['title']} ({year}) -> {', '.join(genres)}"

    with ThreadPoolExecutor(max_workers=10) as pool:
        futures = [pool.submit(process_movie, item) for item in candidates.values()]
        for fut in as_completed(futures):
            msg = fut.result()
            if msg:
                print(msg, flush=True)


def sync_archive_feature_collections(existing_archive_ids, seen_tmdb_movies):
    print("4/8 Izravno rudarenje žanrovskih kolekcija na Archive.org...", flush=True)
    archive_docs = {}

    for col in ARCHIVE_FEATURE_COLLECTIONS + ["animationandcartoons"]:
        query = f"mediatype:movies AND collection:{col} AND -collection:television AND -subject:trailer AND -subject:news"
        for page in [1, 2, 3, 4, 5]:
            data = safe_get(
                "https://archive.org/advancedsearch.php",
                params={
                    "q": query,
                    "fl[]": ["identifier", "title", "year", "downloads"],
                    "sort[]": "downloads desc",
                    "rows": 200,
                    "page": page,
                    "output": "json"
                }
            )
            for doc in (data or {}).get("response", {}).get("docs", []):
                ident = doc.get("identifier")
                if ident and ident not in existing_archive_ids and ident not in archive_docs:
                    archive_docs[ident] = doc

    print(f"  Pronađeno {len(archive_docs)} novih Archive.org stavki za uparivanje s TMDB-om...", flush=True)

    def process_archive_doc(doc):
        ident = doc.get("identifier") or ""
        if not ident or ident.lower().startswith(("youtube-", "yt-")):
            return None

        raw_title = doc.get("title") or ident
        if isinstance(raw_title, list):
            raw_title = raw_title[0]
        if contains_hentai_or_adult(ident, raw_title):
            return None

        clean_t, parsed_y = clean_filename_to_title(str(raw_title))
        if not parsed_y:
            parsed_y = extract_year_from_text(doc.get("year") or ident)
        if not clean_t or len(clean_t) < 2:
            return None

        params = {"api_key": TMDB_API_KEY, "query": clean_t, "language": "hr-HR", "include_adult": "false"}
        if parsed_y > 0:
            params["year"] = parsed_y
        res = safe_get(f"{TMDB_BASE}/search/movie", params=params)
        results = (res or {}).get("results", [])
        if not results and parsed_y > 0:
            params.pop("year")
            res = safe_get(f"{TMDB_BASE}/search/movie", params=params)
            results = (res or {}).get("results", [])
        if not results:
            return None

        hit = results[0]
        mid = hit["id"]
        with state_lock:
            if mid in seen_tmdb_movies:
                return None

        details = get_tmdb_movie_details(mid)
        if not details or not details["poster_path"]:
            return None

        videos, arch_md = get_archive_item_details(ident, min_size_mb=200, allow_mkv=True)
        if not videos:
            return None
        best = pick_best_video_file(videos)
        arch_mins = best["duration_mins"]

        rel_year = extract_year_from_text(details["release_date"]) or parsed_y
        require_credits = has_multiple_tmdb_versions(details["orig_title"])
        ok, _ = validate_movie_match(
            ident,
            arch_md,
            arch_mins,
            details,
            [details["orig_title"], details["hr_title"]],
            rel_year,
            require_credits=require_credits,
            chosen_filename=best["filename"]
        )
        if not ok or not verify_stream_url_playable(best["stream_url"]):
            return None

        with state_lock:
            if mid in seen_tmdb_movies:
                return None
            seen_tmdb_movies.add(mid)
            existing_archive_ids.add(ident)

        genres = determine_primary_genres(
            details["genre_ids"], details["companies"], details["orig_title"], details["hr_title"], details["overview_hr"]
        )
        downloads = float(doc.get("downloads") or 0)
        pop_score = max(details["popularity"] * 100.0, min(downloads / 10.0, 15000.0)) + (rel_year * 2)

        row = {
            "archive_id": ident,
            "tmdb_id": mid,
            "title": details["hr_title"],
            "media_type": "movie",
            "genres": genres,
            "release_year": rel_year,
            "rating": round(details["vote_average"], 1),
            "popularity_score": pop_score,
            "description_hr": details["overview_hr"],
            "poster_url": f"{TMDB_IMG_POSTER}{details['poster_path']}",
            "backdrop_url": f"{TMDB_IMG_BACKDROP}{details['backdrop_path']}" if details["backdrop_path"] else "",
            "stream_url": best["stream_url"],
            "is_featured": details["vote_average"] >= 8.0 and 16 not in details["genre_ids"],
            "is_valid": True,
            "tmdb_runtime": details["runtime"],
            "archive_runtime": arch_mins
        }
        supabase_upsert("catalog_items", [row], "archive_id")
        return f"  + Archive->TMDB: {row['title']} ({rel_year}) -> {', '.join(genres)}"

    with ThreadPoolExecutor(max_workers=10) as pool:
        futures = [pool.submit(process_archive_doc, doc) for doc in archive_docs.values()]
        for fut in as_completed(futures):
            msg = fut.result()
            if msg:
                print(msg, flush=True)


def sync_classic_short_cartoons():
    print("5/8 Sinkronizacija klasičnih kratkih crtića...", flush=True)
    added_shorts = 0

    for cartoon_query in CLASSIC_SHORT_CARTOON_QUERIES:
        query = f'title:"{cartoon_query}" AND mediatype:movies AND (subject:animation OR subject:cartoon OR collection:animationandcartoons OR collection:classic_cartoons) AND -subject:trailer'
        data = safe_get(
            "https://archive.org/advancedsearch.php",
            params={
                "q": query,
                "fl[]": ["identifier", "title", "year", "downloads", "description"],
                "sort[]": "downloads desc",
                "rows": 35,
                "output": "json"
            }
        )
        for doc in (data or {}).get("response", {}).get("docs", []):
            ident = doc.get("identifier") or ""
            if not ident or ident.lower().startswith(("youtube-", "yt-")):
                continue
            raw_title = doc.get("title") or ident
            if isinstance(raw_title, list):
                raw_title = raw_title[0]
            if contains_hentai_or_adult(ident, raw_title, doc.get("description")):
                continue

            clean_t, parsed_y = clean_filename_to_title(str(raw_title))
            if not clean_t or len(clean_t) < 2:
                continue

            mp4s, _ = get_archive_item_details(ident, min_size_mb=15)
            if not mp4s:
                continue
            best = max(mp4s, key=lambda x: x["size_mb"])
            dur = best["duration_mins"]
            if dur > 0 and (dur < 4 or dur > 35):
                continue

            desc = doc.get("description") or ""
            if isinstance(desc, list):
                desc = " ".join(str(x) for x in desc)

            row = {
                "archive_id": ident,
                "title": clean_t,
                "media_type": "movie",
                "genres": ["Animirani klasici", "Klasični crtići"],
                "release_year": parsed_y,
                "popularity_score": float(doc.get("downloads") or 0),
                "description_hr": str(desc)[:800],
                "poster_url": f"https://archive.org/services/img/{ident}",
                "stream_url": best["stream_url"],
                "is_featured": False,
                "is_valid": True,
                "tmdb_runtime": dur,
                "archive_runtime": dur
            }
            supabase_upsert("catalog_items", [row], "archive_id")
            added_shorts += 1

    print(f"  Obrađeno {added_shorts} klasičnih kratkih crtića.", flush=True)


def parse_season_and_episode(item_title, filename, total_seasons_hint=0):
    combined = f"{item_title} / {filename}"
    for pat in [
        r"(?i)\bS(\d{1,2})\s*[-_.]?\s*E(\d{1,3})\b",
        r"(?i)\b(\d{1,2})x(\d{1,3})\b",
        r"(?i)Season\s*(\d{1,2}).*?(?:Episode|Ep\.?|E)\s*(\d{1,3})\b",
        r"(?i)Sezona\s*(\d{1,2}).*?(?:Epizoda|Ep\.?|E)\s*(\d{1,3})\b",
        r"(?i)Series\s*(\d{1,2}).*?(?:Episode|Ep\.?|E)\s*(\d{1,3})\b"
    ]:
        m = re.search(pat, combined)
        if m:
            s = int(m.group(1))
            e = int(m.group(2))
            if 1 <= s <= 50 and 1 <= e <= 300:
                return s, e

    season_num = 0
    for s_pat in [
        r"(?i)\bSeason\s*(\d{1,2})\b",
        r"(?i)\bSezona\s*(\d{1,2})\b",
        r"(?i)\bSeries\s*(\d{1,2})\b",
        r"(?i)\bS(\d{1,2})\b"
    ]:
        sm = re.search(s_pat, f"{item_title} / {filename.rsplit('/', 1)[0] if '/' in filename else ''}")
        if sm:
            val = int(sm.group(1))
            if 1 <= val <= 50:
                season_num = val
                break

    base_fname = filename.rsplit("/", 1)[-1]
    ep_num = 0
    for e_pat in [
        r"(?i)\b(?:Episode|Epizoda|Ep\.?|E|Part)\s*(\d{1,3})\b",
        r"^\s*0*(\d{1,2})\s*[-_.]+\s*[A-Za-z]"
    ]:
        em = re.search(e_pat, base_fname)
        if em:
            val = int(em.group(1))
            if 1 <= val <= 300:
                ep_num = val
                break

    if season_num > 0 and ep_num > 0:
        return season_num, ep_num
    if total_seasons_hint == 1 and ep_num > 0:
        return 1, ep_num
    return 0, 0


def fetch_tmdb_series_guide(tmdb_id, season_numbers):
    tv_details = safe_get(f"{TMDB_BASE}/tv/{tmdb_id}", {"api_key": TMDB_API_KEY, "language": "hr-HR"})
    if not tv_details:
        return {}, 0, "", 1

    overview_hr = (tv_details.get("overview") or "").strip()
    if not overview_hr:
        en_tv = safe_get(f"{TMDB_BASE}/tv/{tmdb_id}", {"api_key": TMDB_API_KEY, "language": "en-US"})
        overview_hr = ((en_tv or {}).get("overview") or "").strip()

    runtimes = tv_details.get("episode_run_time") or []
    avg_runtime = int(runtimes[0]) if runtimes else 0
    num_seasons = int(tv_details.get("number_of_seasons") or 1)

    episode_guide = {}
    for s_num in sorted(set(season_numbers)):
        if s_num < 1 or s_num > num_seasons:
            continue
        hr_season = safe_get(f"{TMDB_BASE}/tv/{tmdb_id}/season/{s_num}", {"api_key": TMDB_API_KEY, "language": "hr-HR"})
        en_season = safe_get(f"{TMDB_BASE}/tv/{tmdb_id}/season/{s_num}", {"api_key": TMDB_API_KEY, "language": "en-US"})

        en_map = {}
        for ep in (en_season or {}).get("episodes", []):
            en_map[ep.get("episode_number")] = (
                (ep.get("name") or "").strip(),
                int(ep.get("runtime") or avg_runtime)
            )

        for ep in (hr_season or {}).get("episodes", []):
            ep_no = ep.get("episode_number")
            hr_name = (ep.get("name") or "").strip()
            en_name, ep_rt = en_map.get(ep_no, ("", avg_runtime))
            final_ep_name = hr_name if (hr_name and not hr_name.lower().startswith("epizoda ")) else (en_name or hr_name)
            episode_guide[(s_num, ep_no)] = (final_ep_name, en_name, ep_rt or avg_runtime)

        if not hr_season and en_map:
            for ep_no, (en_name, ep_rt) in en_map.items():
                episode_guide[(s_num, ep_no)] = (en_name, en_name, ep_rt or avg_runtime)

    return episode_guide, avg_runtime, overview_hr, num_seasons


def filename_episode_title_conflicts(filename, hr_ep_name, en_ep_name):
    base = filename.rsplit("/", 1)[-1]
    base = re.sub(r"\.(mp4|mkv|avi|ogv|mov)$", "", base, flags=re.IGNORECASE)
    m = re.search(r"(?i)(?:\bS\d{1,2}\s*[-_.]?\s*E\d{1,3}|\b\d{1,2}x\d{1,3}|\bEpisode\s*\d{1,3})\s*[-_.\s]+(.+)$", base)
    if not m:
        return False
    tail = re.sub(r"[._]+", " ", m.group(1))
    tail = RELEASE_TAG_PATTERN.sub(" ", tail)
    tail_words = [w for w in re.sub(r"[^\w\s]", " ", tail.lower()).split() if len(w) >= 3]
    if len(tail_words) < 2:
        return False

    official_blob = re.sub(r"[^\w\s]", " ", f"{hr_ep_name} {en_ep_name}".lower())
    official_words = set(official_blob.split())
    if not official_words:
        return False

    return not any(w in official_words for w in tail_words)


def aggregate_series_from_archive(tmdb_id, orig_name, hr_name):
    if contains_hentai_or_adult(orig_name, hr_name):
        return []
    clean_q = re.sub(r"[^\w\s]", " ", orig_name).strip()
    if len(clean_q) < 3:
        return []

    query = f'title:"{clean_q}" AND mediatype:movies AND -subject:trailer AND -subject:news AND -subject:review'
    data = safe_get(
        "https://archive.org/advancedsearch.php",
        params={
            "q": query,
            "fl[]": ["identifier", "title", "downloads"],
            "sort[]": "downloads desc",
            "rows": 85,
            "output": "json"
        }
    )
    docs = (data or {}).get("response", {}).get("docs", [])
    if not docs:
        return []

    valid_core_titles = {extract_series_core_title(orig_name), extract_series_core_title(hr_name)}
    valid_core_titles.discard("")

    tv_basic = safe_get(f"{TMDB_BASE}/tv/{tmdb_id}", {"api_key": TMDB_API_KEY, "language": "en-US"})
    total_seasons_hint = int((tv_basic or {}).get("number_of_seasons") or 1)

    raw_candidates = []
    detected_seasons = set()

    for doc in docs:
        ident = doc.get("identifier") or ""
        if not ident or ident.lower().startswith(("youtube-", "yt-")):
            continue
        doc_title = doc.get("title") or ident
        if isinstance(doc_title, list):
            doc_title = " ".join(str(x) for x in doc_title)

        norm_doc = re.sub(r"[^\w\s]", " ", f"{doc_title} {ident}".lower())
        if any(kw in norm_doc for kw in SPAM_OR_FAKE_KEYWORDS):
            continue

        doc_core = extract_series_core_title(doc_title)
        if not series_core_matches(doc_core, valid_core_titles):
            continue

        videos, arch_md = get_archive_item_details(ident, min_size_mb=35, allow_mkv=True)
        if not videos:
            continue

        item_title_meta = arch_md.get("title") or doc_title
        if isinstance(item_title_meta, list):
            item_title_meta = item_title_meta[0]

        meta_core = extract_series_core_title(item_title_meta)
        if meta_core and not series_core_matches(meta_core, valid_core_titles):
            continue

        for m in videos:
            fname_prefix_match = re.match(
                r"(?i)^(.*?)(?:\bS\d{1,2}\s*[-_.]?\s*E\d{1,3}|\b\d{1,2}x\d{1,3}|\bSeason\s*\d{1,2}|\bSeries\s*\d{1,2})",
                m["filename"].rsplit("/", 1)[-1]
            )
            if fname_prefix_match:
                prefix_core = extract_series_core_title(fname_prefix_match.group(1))
                if len(prefix_core) >= 3 and not series_core_matches(prefix_core, valid_core_titles):
                    continue

            s_num, e_num = parse_season_and_episode(str(item_title_meta), m["filename"], total_seasons_hint)
            if 1 <= s_num <= total_seasons_hint and e_num > 0:
                detected_seasons.add(s_num)
                raw_candidates.append({
                    "season": s_num,
                    "episode": e_num,
                    "filename": m["filename"],
                    "size_mb": m["size_mb"],
                    "duration_mins": m["duration_mins"],
                    "stream_url": m["stream_url"],
                    "is_derivative": m["is_derivative"]
                })

    if len(raw_candidates) < 2:
        return []

    episode_guide, avg_runtime, _, _ = fetch_tmdb_series_guide(tmdb_id, detected_seasons)
    if not episode_guide:
        return []

    best_by_se = {}

    for cand in raw_candidates:
        key = (cand["season"], cand["episode"])
        if key not in episode_guide:
            continue

        official_hr_name, official_en_name, expected_rt = episode_guide[key]
        if filename_episode_title_conflicts(cand["filename"], official_hr_name, official_en_name):
            continue

        dur = cand["duration_mins"]
        if dur > 0:
            if dur < 10:
                continue
            if expected_rt > 0 and (dur < int(expected_rt * 0.65) or dur > int(expected_rt * 1.75)):
                continue

        is_mp4 = cand["filename"].lower().endswith(".mp4")
        quality_score = cand["size_mb"] + (1000.0 if is_mp4 else 0.0) + (500.0 if not cand["is_derivative"] else 0.0)
        if key not in best_by_se or quality_score > best_by_se[key]["quality_score"]:
            clean_file_title, _ = clean_filename_to_title(cand["filename"])
            ep_title_clean = official_hr_name if official_hr_name else (clean_file_title or f"Epizoda {cand['episode']}")
            code_prefix = f"S{cand['season']:02d}E{cand['episode']:02d}"
            display_title = f"{code_prefix} - {ep_title_clean}" if not ep_title_clean.upper().startswith(code_prefix) else ep_title_clean

            best_by_se[key] = {
                "season": cand["season"],
                "episode": cand["episode"],
                "title": display_title,
                "url": cand["stream_url"],
                "duration": format_duration_text(dur),
                "quality_score": quality_score
            }

    sorted_episodes = [
        {
            "season": v["season"],
            "episode": v["episode"],
            "title": v["title"],
            "url": v["url"],
            "duration": v["duration"]
        }
        for _, v in sorted(best_by_se.items(), key=lambda item: (item[0][0], item[0][1]))
    ]
    if len(sorted_episodes) < 2 or not verify_stream_url_playable(sorted_episodes[0]["url"]):
        return []
    return sorted_episodes


def sync_tmdb_series(seen_tmdb_series):
    print("6/8 Duboka TMDB pretraga svih žanrova serija...", flush=True)
    candidate_shows = {}

    for endpoint in ["tv/popular", "tv/top_rated"]:
        for page in range(1, 18):
            data = safe_get(f"{TMDB_BASE}/{endpoint}", {"api_key": TMDB_API_KEY, "language": "hr-HR", "page": page})
            for s in (data or {}).get("results", []):
                if s["id"] not in seen_tmdb_series:
                    candidate_shows[s["id"]] = s

    for tv_genre in TMDB_TV_DISCOVER_GENRE_IDS:
        for sort_by in ["popularity.desc", "vote_count.desc"]:
            for page in range(1, 13):
                data = safe_get(
                    f"{TMDB_BASE}/discover/tv",
                    {
                        "api_key": TMDB_API_KEY,
                        "language": "hr-HR",
                        "with_genres": tv_genre,
                        "first_air_date.gte": "1960-01-01",
                        "first_air_date.lte": "1999-12-31",
                        "vote_count.gte": 10,
                        "sort_by": sort_by,
                        "page": page
                    }
                )
                for s in (data or {}).get("results", []):
                    if s["id"] not in seen_tmdb_series:
                        candidate_shows[s["id"]] = s

    for tv_genre in TMDB_TV_DISCOVER_GENRE_IDS:
        for sort_by in ["popularity.desc", "vote_count.desc"]:
            for page in range(1, 11):
                data = safe_get(
                    f"{TMDB_BASE}/discover/tv",
                    {
                        "api_key": TMDB_API_KEY,
                        "language": "hr-HR",
                        "with_genres": tv_genre,
                        "first_air_date.gte": "2000-01-01",
                        "vote_count.gte": 25,
                        "sort_by": sort_by,
                        "page": page
                    }
                )
                for s in (data or {}).get("results", []):
                    if s["id"] not in seen_tmdb_series:
                        candidate_shows[s["id"]] = s

    for seed_title in CLASSIC_TV_SEED_TITLES:
        res = safe_get(f"{TMDB_BASE}/search/tv", {"api_key": TMDB_API_KEY, "query": seed_title, "language": "hr-HR"})
        first = ((res or {}).get("results") or [None])[0]
        if first and first["id"] not in seen_tmdb_series:
            candidate_shows[first["id"]] = first

    print(f"  Ukupno {len(candidate_shows)} novih TMDB serija za provjeru na Archive.org...", flush=True)

    def process_series(item):
        tv_id, s = item
        with state_lock:
            if tv_id in seen_tmdb_series:
                return None

        orig_name = s.get("original_name") or s.get("name") or ""
        hr_name = s.get("name") or orig_name
        overview = (s.get("overview") or "").strip()

        if contains_hentai_or_adult(orig_name, hr_name, overview):
            return None

        first_air = s.get("first_air_date") or ""
        year = int(first_air[:4]) if len(first_air) >= 4 and first_air[:4].isdigit() else 0

        episodes = aggregate_series_from_archive(tv_id, orig_name, hr_name)
        if len(episodes) < 2:
            return None

        with state_lock:
            if tv_id in seen_tmdb_series:
                return None
            seen_tmdb_series.add(tv_id)

        if not overview:
            en_tv = safe_get(f"{TMDB_BASE}/tv/{tv_id}", {"api_key": TMDB_API_KEY, "language": "en-US"})
            overview = ((en_tv or {}).get("overview") or "").strip()

        g_ids = s.get("genre_ids", [])
        genres = determine_tv_primary_genres(g_ids, orig_name, hr_name)

        seasons_found = len({ep["season"] for ep in episodes})
        row = {
            "archive_id": f"tmdb-tv-{tv_id}",
            "tmdb_id": tv_id,
            "title": hr_name,
            "media_type": "series",
            "genres": genres,
            "release_year": year,
            "rating": round(float(s.get("vote_average") or 0.0), 1),
            "popularity_score": float(s.get("popularity") or 10.0) * 100.0 + (len(episodes) * 15),
            "description_hr": overview,
            "poster_url": f"{TMDB_IMG_POSTER}{s['poster_path']}" if s.get("poster_path") else "",
            "backdrop_url": f"{TMDB_IMG_BACKDROP}{s['backdrop_path']}" if s.get("backdrop_path") else "",
            "stream_url": episodes[0]["url"],
            "episodes": episodes,
            "is_featured": float(s.get("vote_average") or 0) >= 8.0 and "Animirane serije" not in genres,
            "is_valid": True,
            "tmdb_runtime": 0,
            "archive_runtime": 0
        }
        supabase_upsert("catalog_items", [row], "archive_id")
        return f"  + TMDB->Serija: {row['title']} ({seasons_found} sez. / {len(episodes)} ep.) -> {genres[0]}"

    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = [pool.submit(process_series, item) for item in candidate_shows.items()]
        for fut in as_completed(futures):
            msg = fut.result()
            if msg:
                print(msg, flush=True)


def sync_archive_tv_collections(seen_tmdb_series):
    print("7/8 Paralelno rudarenje TV kolekcija na Archive.org...", flush=True)
    discovered_cores = {}

    for col in ARCHIVE_TV_COLLECTIONS:
        query = f"mediatype:movies AND collection:{col} AND -subject:trailer AND -subject:news"
        for page in [1, 2, 3, 4, 5]:
            data = safe_get(
                "https://archive.org/advancedsearch.php",
                params={
                    "q": query,
                    "fl[]": ["identifier", "title", "downloads"],
                    "sort[]": "downloads desc",
                    "rows": 150,
                    "page": page,
                    "output": "json"
                }
            )
            for doc in (data or {}).get("response", {}).get("docs", []):
                raw_t = doc.get("title") or ""
                if isinstance(raw_t, list):
                    raw_t = raw_t[0]
                if not raw_t or contains_hentai_or_adult(raw_t, doc.get("identifier")):
                    continue
                core = extract_series_core_title(raw_t)
                if len(core) >= 3 and core not in discovered_cores:
                    discovered_cores[core] = str(raw_t)

    print(f"  Pronađeno {len(discovered_cores)} potencijalnih naslova serija u TV kolekcijama...", flush=True)

    def process_discovered_core(core_title):
        res = safe_get(f"{TMDB_BASE}/search/tv", {"api_key": TMDB_API_KEY, "query": core_title, "language": "hr-HR"})
        hit = ((res or {}).get("results") or [None])[0]
        if not hit:
            return None

        tv_id = hit["id"]
        with state_lock:
            if tv_id in seen_tmdb_series:
                return None

        orig_name = hit.get("original_name") or hit.get("name") or ""
        hr_name = hit.get("name") or orig_name
        valid_cores = {extract_series_core_title(orig_name), extract_series_core_title(hr_name)}
        if not series_core_matches(core_title, valid_cores):
            return None

        episodes = aggregate_series_from_archive(tv_id, orig_name, hr_name)
        if len(episodes) < 2:
            return None

        with state_lock:
            if tv_id in seen_tmdb_series:
                return None
            seen_tmdb_series.add(tv_id)

        first_air = hit.get("first_air_date") or ""
        year = int(first_air[:4]) if len(first_air) >= 4 and first_air[:4].isdigit() else 0
        overview = (hit.get("overview") or "").strip()
        if not overview:
            en_tv = safe_get(f"{TMDB_BASE}/tv/{tv_id}", {"api_key": TMDB_API_KEY, "language": "en-US"})
            overview = ((en_tv or {}).get("overview") or "").strip()

        g_ids = hit.get("genre_ids", [])
        genres = determine_tv_primary_genres(g_ids, orig_name, hr_name)
        seasons_found = len({ep["season"] for ep in episodes})

        row = {
            "archive_id": f"tmdb-tv-{tv_id}",
            "tmdb_id": tv_id,
            "title": hr_name,
            "media_type": "series",
            "genres": genres,
            "release_year": year,
            "rating": round(float(hit.get("vote_average") or 0.0), 1),
            "popularity_score": float(hit.get("popularity") or 10.0) * 100.0 + (len(episodes) * 15),
            "description_hr": overview,
            "poster_url": f"{TMDB_IMG_POSTER}{hit['poster_path']}" if hit.get("poster_path") else "",
            "backdrop_url": f"{TMDB_IMG_BACKDROP}{hit['backdrop_path']}" if hit.get("backdrop_path") else "",
            "stream_url": episodes[0]["url"],
            "episodes": episodes,
            "is_featured": float(hit.get("vote_average") or 0) >= 8.0 and "Animirane serije" not in genres,
            "is_valid": True,
            "tmdb_runtime": 0,
            "archive_runtime": 0
        }
        supabase_upsert("catalog_items", [row], "archive_id")
        return f"  + ArchiveTV->Serija: {row['title']} ({seasons_found} sez. / {len(episodes)} ep.) -> {genres[0]}"

    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = [pool.submit(process_discovered_core, c) for c in discovered_cores]
        for fut in as_completed(futures):
            msg = fut.result()
            if msg:
                print(msg, flush=True)


def sync_concerts():
    print("8/8 Proširena sinkronizacija cjelovitih koncerata...", flush=True)
    concert_base = (
        "mediatype:movies AND "
        "(collection:(vhsconcerts_music OR concerts OR live_music_video OR musicvideos) OR "
        "subject:(concert OR \"live concert\" OR \"live performance\") OR "
        "title:(\"live at\" OR \"live in\")) AND "
        "-subject:trailer AND -subject:interview AND -subject:news"
    )
    concert_queries = [
        ("Rock i Metal", f"{concert_base} AND (subject:(rock OR metal OR punk OR grunge) OR title:(rock OR metal OR punk))"),
        ("Pop glazba", f"{concert_base} AND (subject:(pop OR \"pop music\" OR dance OR \"r&b\" OR disco) OR title:(pop OR tour))"),
        ("Klasika i Jazz", f"{concert_base} AND (subject:(classical OR jazz OR blues OR soul OR symphony OR orchestra) OR title:(jazz OR symphony OR orchestra))")
    ]

    seen_concert_titles = set()
    added_concerts = 0

    for genre_label, query in concert_queries:
        for page in [1, 2]:
            data = safe_get(
                "https://archive.org/advancedsearch.php",
                params={
                    "q": query,
                    "fl[]": ["identifier", "title", "description", "downloads"],
                    "sort[]": "downloads desc",
                    "rows": 100,
                    "page": page,
                    "output": "json"
                }
            )
            for doc in (data or {}).get("response", {}).get("docs", []):
                ident = doc.get("identifier") or ""
                if not ident or ident.lower().startswith(("youtube-", "yt-")):
                    continue

                raw_title = doc.get("title") or ident
                if isinstance(raw_title, list):
                    raw_title = raw_title[0]
                if contains_hentai_or_adult(ident, raw_title, doc.get("description")):
                    continue

                clean_t, parsed_y = clean_filename_to_title(str(raw_title))
                if not clean_t or len(clean_t) < 3:
                    continue

                norm_c = normalize_for_exact_title_compare(clean_t)
                if not norm_c or norm_c in seen_concert_titles:
                    continue

                videos, _ = get_archive_item_details(ident, min_size_mb=120, allow_mkv=True)
                if not videos:
                    continue
                best = pick_best_video_file(videos)
                dur = best["duration_mins"]
                if dur > 0 and dur < 25:
                    continue

                seen_concert_titles.add(norm_c)
                desc = doc.get("description") or ""
                if isinstance(desc, list):
                    desc = " ".join(str(x) for x in desc)

                row = {
                    "archive_id": ident,
                    "title": clean_t,
                    "media_type": "concert",
                    "genres": [genre_label],
                    "release_year": parsed_y,
                    "popularity_score": float(doc.get("downloads") or 0),
                    "description_hr": str(desc)[:1000],
                    "poster_url": f"https://archive.org/services/img/{ident}",
                    "stream_url": best["stream_url"],
                    "is_featured": False,
                    "is_valid": True,
                    "tmdb_runtime": 0,
                    "archive_runtime": dur
                }
                supabase_upsert("catalog_items", [row], "archive_id")
                added_concerts += 1

    print(f"  Obrađeno {added_concerts} cjelovitih koncerata.", flush=True)


if __name__ == "__main__":
    existing_ids, seen_tmdb_m, seen_tmdb_s, author_promos = audit_and_dedupe_catalog()
    sync_authors(existing_ids, seen_tmdb_m, author_promos)
    sync_tmdb_movies_by_genre(seen_tmdb_m)
    sync_archive_feature_collections(existing_ids, seen_tmdb_m)
    sync_classic_short_cartoons()
    sync_tmdb_series(seen_tmdb_s)
    sync_archive_tv_collections(seen_tmdb_s)
    sync_concerts()
    print("Sinkronizacija proširenog kataloga uspješno završena!", flush=True)
