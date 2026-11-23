import os
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from sync_common import (
    RELEASE_TAG_PATTERN,
    SPAM_OR_FAKE_KEYWORDS,
    TMDB_API_KEY,
    TMDB_BASE,
    TMDB_IMG_BACKDROP,
    TMDB_IMG_POSTER,
    clean_filename_to_title,
    contains_blocked_regional_or_arabic,
    contains_hentai_or_adult,
    extract_year_from_text,
    fetch_existing_catalog_state,
    format_duration_text,
    get_archive_item_details,
    has_multiple_tmdb_tv_versions,
    is_ex_yu_content,
    is_foreign_dub_without_english,
    normalize_for_exact_title_compare,
    pick_best_video_file,
    safe_get,
    state_lock,
    supabase_patch_row,
    supabase_upsert,
    translate_to_croatian,
    verify_stream_url_playable,
)

MAX_SYNC_DURATION_SECONDS = 195 * 60

ARCHIVE_TV_COLLECTIONS = [
    "classic_tv",
    "classic_tv_1990s",
    "classic_tv_1980s",
    "classic_tv_1970s",
    "classic_tv_1960s",
    "television_inbox",
    "televisionmovies",
    "animationandcartoons",
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
    99: "Dramske i obiteljske serije",
}

TMDB_TV_DISCOVER_GENRE_IDS = ["80", "9648", "10765", "10759", "35", "18", "10751", "37", "16", "10762"]

DISNEY_SHORT_CARTOON_QUERIES = [
    "Walt Disney", "Mickey Mouse", "Donald Duck", "Goofy", "Pluto",
    "Silly Symphonies", "Silly Symphony", "Chip and Dale", "Chip 'n' Dale",
    "Steamboat Willie", "Oswald the Lucky Rabbit", "Winnie the Pooh",
    "Scrooge McDuck", "Huey Dewey Louie", "Minnie Mouse", "Daisy Duck",
]

EX_YU_SHORT_CARTOON_QUERIES = [
    "Profesor Baltazar", "Professor Balthazar", "Zagreb Film", "Surogat",
    "Dušan Vukotić", "Dusan Vukotic", "Mali leteći medvjedići", "Mali leteci medvjedici",
    "Cipelići", "Cipelici", "Šegrt Hlapić", "Segrt Hlapic",
    "Čudesna šuma", "Cudesna suma", "Čarobnjakov šešir", "Carobnjakov sesir",
    "Inspektor Maska", "crtani film sinhronizovano", "crtani film sinkronizirano",
]

CLASSIC_SHORT_CARTOON_QUERIES = DISNEY_SHORT_CARTOON_QUERIES + EX_YU_SHORT_CARTOON_QUERIES + [
    "Tom and Jerry", "Looney Tunes", "Merrie Melodies", "Bugs Bunny",
    "Popeye", "Woody Woodpecker", "Pink Panther",
    "Droopy", "Betty Boop", "Felix the Cat", "Casper the Friendly Ghost",
    "Superman", "Mighty Mouse", "Road Runner", "Daffy Duck",
    "Porky Pig", "Tweety", "Yogi Bear", "Huckleberry Hound",
    "Top Cat", "The Flintstones", "The Jetsons", "Scooby-Doo",
]

EX_YU_TV_SEED_TITLES = [
    "Gruntovčani", "Naše malo misto", "Velo misto", "Smogovci",
    "Kapelski kresovi", "Kuda idu divlje svinje", "Prosjaci i sinovi",
    "U registraturi", "Putovanje u Vučjak", "Nepokoreni grad",
    "Bolji život", "Vruć vetar", "Kamiondžije", "Povratak otpisanih",
    "Otpisani", "Grlom u jagode", "Sivi dom", "Pozorište u kući",
    "Policajac sa Petlovog brda", "Srećni ljudi", "Porodično blago",
    "Otvorena vrata", "Vratiće se rode", "Lud, zbunjen, normalan",
    "Bitange i princeze", "Odmori se, zaslužio si", "Stipe u gostima",
    "Crno-bijeli svijet", "Novine", "Profesor Baltazar", "Mali leteći medvjedići",
]

CLASSIC_TV_SEED_TITLES = EX_YU_TV_SEED_TITLES + [
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
    "The Flintstones", "The Simpsons", "Futurama", "Dragon Ball", "Dragon Ball Z", "Pokémon",
]


def looks_like_english_text(text):
    if not text or len(text.strip()) < 12:
        return False
    padded = f" {text.lower()} "
    markers = (
        " the ", " and ", " with ", " from ", " into ", " their ",
        " his ", " her ", " show ", " story ", " when ", " where ",
        " which ", " while ", " after ", " before ", " through ", " against ",
        " of ", " in ", " to "
    )
    hits = sum(1 for m in markers if m in padded)
    has_hr = any(ch in text for ch in "čćžšđČĆŽŠĐ")
    return hits >= 2 and not has_hr


def ensure_croatian_overview(text, tmdb_id=0):
    cleaned = (text or "").strip()
    if not cleaned and tmdb_id:
        en_tv = safe_get(f"{TMDB_BASE}/tv/{tmdb_id}", {"api_key": TMDB_API_KEY, "language": "en-US"})
        cleaned = ((en_tv or {}).get("overview") or "").strip()
    if not cleaned:
        return ""
    if looks_like_english_text(cleaned):
        translated = translate_to_croatian(cleaned)
        return (translated or cleaned).strip()
    return cleaned


def determine_tv_primary_genres(genre_ids, orig_name, hr_name):
    animated_or_kids_keywords = [
        "dora", "barney", "peppa", "paw patrol", "spongebob", "scooby",
        "flintstones", "simpsons", "futurama", "batman: the animated", "ducktales",
        "avatar: the last airbender", "x-men: the animated", "animated",
        "superman: the animated", "spider-man", "rescue rangers", "talespin",
        "darkwing duck", "gargoyles", "ninja turtles", "he-man", "transformers",
        "inspector gadget", "animaniacs", "pinky and the brain", "tiny toon",
        "rugrats", "hey arnold", "dexter", "powerpuff", "johnny bravo",
        "samurai jack", "dragon ball", "pokémon", "pokemon",
        "profesor baltazar", "professor balthazar", "mali leteći medvjedići", "cipelići"
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


def extract_series_core_title(raw_title):
    s = str(raw_title).rsplit("/", 1)[-1]
    s = re.sub(r"\.(mp4|mkv|avi|ogv|mov)$", "", s, flags=re.IGNORECASE)
    s = re.split(r"\s+(?:-|–|—|\||/)\s+", s, maxsplit=1)[0].strip()
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


def parse_season_and_episodes(item_title, filename, total_seasons_hint=0):
    combined = f"{item_title} / {filename}"

    multi_m = re.search(r"(?i)\bS(\d{1,2})\s*[-_.]?\s*E(\d{1,3})\s*[-_.]?\s*E?(\d{1,3})\b", combined)
    if multi_m:
        s = int(multi_m.group(1))
        e1 = int(multi_m.group(2))
        e2 = int(multi_m.group(3))
        if 1 <= s <= 50 and 1 <= e1 < e2 <= (e1 + 2) and e2 <= 300:
            return s, list(range(e1, e2 + 1))

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
                return s, [e]

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
        return season_num, [ep_num]
    if total_seasons_hint == 1 and ep_num > 0:
        return 1, [ep_num]
    return 0, []


def fetch_tmdb_series_guide(tmdb_id, season_numbers, tv_details=None):
    if tv_details is None:
        tv_details = safe_get(f"{TMDB_BASE}/tv/{tmdb_id}", {"api_key": TMDB_API_KEY, "language": "hr-HR"})
    if not tv_details:
        return {}, {}, 0, "", 1

    overview_hr = ensure_croatian_overview(tv_details.get("overview") or "", tmdb_id=tmdb_id)

    runtimes = tv_details.get("episode_run_time") or []
    avg_runtime = int(runtimes[0]) if runtimes else 0
    num_seasons = int(tv_details.get("number_of_seasons") or 1)
    today_str = datetime.now(timezone.utc).strftime("%Y-%m-%d")

    episode_guide = {}
    aired_eps_by_season = {}
    seasons_to_fetch = sorted({s for s in (set(season_numbers) | {1}) if 1 <= s <= num_seasons})

    for s_num in seasons_to_fetch:
        hr_season = safe_get(f"{TMDB_BASE}/tv/{tmdb_id}/season/{s_num}", {"api_key": TMDB_API_KEY, "language": "hr-HR"})
        en_season = safe_get(f"{TMDB_BASE}/tv/{tmdb_id}/season/{s_num}", {"api_key": TMDB_API_KEY, "language": "en-US"})

        en_map = {}
        aired_set = set()
        for ep in (en_season or {}).get("episodes", []):
            ep_no = ep.get("episode_number")
            if not ep_no or ep_no < 1:
                continue
            air_d = str(ep.get("air_date") or "").strip()
            if air_d and air_d <= today_str:
                aired_set.add(int(ep_no))
            en_map[ep_no] = (
                (ep.get("name") or "").strip(),
                int(ep.get("runtime") or avg_runtime)
            )

        if not aired_set and en_map:
            aired_set = {int(k) for k in en_map.keys() if k and k >= 1}
        if aired_set:
            aired_eps_by_season[s_num] = aired_set

        for ep in (hr_season or {}).get("episodes", []):
            ep_no = ep.get("episode_number")
            if not ep_no or ep_no < 1:
                continue
            hr_name = (ep.get("name") or "").strip()
            en_name, ep_rt = en_map.get(ep_no, ("", avg_runtime))
            final_ep_name = hr_name if (hr_name and not hr_name.lower().startswith("epizoda ")) else (en_name or hr_name)
            episode_guide[(s_num, ep_no)] = (final_ep_name, en_name, ep_rt or avg_runtime)

        if not hr_season and en_map:
            for ep_no, (en_name, ep_rt) in en_map.items():
                episode_guide[(s_num, ep_no)] = (en_name, en_name, ep_rt or avg_runtime)

    return episode_guide, aired_eps_by_season, avg_runtime, overview_hr, num_seasons


def filter_consecutive_complete_seasons(episodes_list, aired_eps_by_season):
    s1_required = aired_eps_by_season.get(1) or set()
    if not s1_required:
        return []

    by_season = {}
    for ep in episodes_list:
        s = int(ep.get("season") or 0)
        e = int(ep.get("episode") or 0)
        if s >= 1 and e >= 1:
            by_season.setdefault(s, {})[e] = ep

    result = []
    max_s = max(max(by_season.keys(), default=0), max(aired_eps_by_season.keys(), default=0))
    for s_num in range(1, max_s + 1):
        req_eps = aired_eps_by_season.get(s_num) or set()
        found_map = by_season.get(s_num) or {}
        if not req_eps or not req_eps.issubset(set(found_map.keys())):
            break
        for e_no in sorted(req_eps):
            result.append(found_map[e_no])

    return result


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


def year_conflicts_with_series_era(explicit_year, first_year, last_year):
    if explicit_year <= 0 or first_year <= 0:
        return False
    end_y = last_year if last_year >= first_year else (first_year + 12)
    return explicit_year < (first_year - 1) or explicit_year > (end_y + 1)


def aggregate_series_from_archive(
    tmdb_id,
    orig_name,
    hr_name,
    missing_seasons=None,
    tv_basic=None,
    require_complete_s1=True,
    reject_reason_out=None
):
    if contains_hentai_or_adult(orig_name, hr_name) or contains_blocked_regional_or_arabic(orig_name, hr_name):
        if reject_reason_out is not None:
            reject_reason_out[0] = "blocked"
        return []
    clean_q = re.sub(r"[^\w\s]", " ", orig_name).strip()
    if len(clean_q) < 2:
        if reject_reason_out is not None:
            reject_reason_out[0] = "no_archive_match"
        return []

    if tv_basic is None:
        tv_basic = safe_get(f"{TMDB_BASE}/tv/{tmdb_id}", {"api_key": TMDB_API_KEY, "language": "en-US"})

    first_air_year = extract_year_from_text((tv_basic or {}).get("first_air_date") or "")
    last_air_year = 2026 if (tv_basic or {}).get("in_production") else extract_year_from_text((tv_basic or {}).get("last_air_date") or "")
    total_seasons_hint = int((tv_basic or {}).get("number_of_seasons") or 1)
    total_tmdb_eps = int((tv_basic or {}).get("number_of_episodes") or 0)
    tv_orig_lang = str((tv_basic or {}).get("original_language") or "en")

    season_ep_limits = {}
    for s_info in ((tv_basic or {}).get("seasons") or []):
        s_no = int(s_info.get("season_number") or 0)
        ep_cnt = int(s_info.get("episode_count") or 0)
        if s_no >= 1 and ep_cnt > 0:
            season_ep_limits[s_no] = ep_cnt

    if 1 not in season_ep_limits:
        if reject_reason_out is not None:
            reject_reason_out[0] = "no_archive_match"
        return []

    if len(clean_q) <= 3 and first_air_year > 0:
        queries = [(
            f'title:"{clean_q}" AND year:[{first_air_year - 1} TO {max(first_air_year + 2, last_air_year)}] '
            f'AND mediatype:movies AND -subject:trailer AND -subject:news',
            45
        )]
    else:
        queries = [
            (
                f'title:"{clean_q}" AND (title:"Season 1" OR title:"S01" OR title:"1x" OR title:"Complete") '
                f'AND mediatype:movies AND -subject:trailer AND -subject:news',
                60
            ),
            (
                f'title:"{clean_q}" AND mediatype:movies AND -subject:trailer AND -subject:news AND -subject:review',
                40
            )
        ]
        if first_air_year > 0:
            queries.append((
                f'title:"{clean_q}" AND year:[{first_air_year - 1} TO {first_air_year + 2}] AND mediatype:movies AND -subject:trailer',
                25
            ))

    if missing_seasons:
        for s_target in sorted(set(missing_seasons))[:4]:
            s_query = (
                f'title:"{clean_q}" AND (title:"Season {s_target}" OR title:"S{s_target:02d}" OR title:"Series {s_target}") '
                f'AND mediatype:movies AND -subject:trailer AND -subject:news'
            )
            queries.append((s_query, 25))

    docs_by_id = {}
    for q, rows_limit in queries:
        data = safe_get(
            "https://archive.org/advancedsearch.php",
            params={
                "q": q,
                "fl[]": ["identifier", "title", "year", "downloads"],
                "sort[]": "downloads desc",
                "rows": rows_limit,
                "output": "json"
            }
        )
        for doc in (data or {}).get("response", {}).get("docs", []):
            ident = doc.get("identifier") or ""
            if ident and ident not in docs_by_id:
                docs_by_id[ident] = doc

    docs = list(docs_by_id.values())
    if not docs:
        if reject_reason_out is not None:
            reject_reason_out[0] = "no_archive_match"
        return []

    valid_core_titles = {extract_series_core_title(orig_name), extract_series_core_title(hr_name)}
    valid_core_titles.discard("")

    raw_candidates = []
    detected_seasons = set()
    unique_se_pairs = set()
    target_se_cap = max(total_tmdb_eps, 220) if total_tmdb_eps > 0 else 220

    for doc in docs:
        if len(unique_se_pairs) >= target_se_cap:
            break

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

        doc_explicit_year = extract_year_from_text(doc_title) or extract_year_from_text(doc.get("year") or "")
        if year_conflicts_with_series_era(doc_explicit_year, first_air_year, last_air_year):
            continue

        videos, arch_md = get_archive_item_details(ident, min_size_mb=15, allow_mkv=True)
        if not videos:
            continue

        item_title_meta = arch_md.get("title") or doc_title
        if isinstance(item_title_meta, list):
            item_title_meta = item_title_meta[0]

        meta_core = extract_series_core_title(item_title_meta)
        if meta_core and not series_core_matches(meta_core, valid_core_titles):
            continue

        meta_explicit_year = extract_year_from_text(item_title_meta) or extract_year_from_text(arch_md.get("year") or "")
        if year_conflicts_with_series_era(meta_explicit_year, first_air_year, last_air_year):
            continue

        item_parsed = []
        max_ep_by_season = {}
        for m in videos:
            if is_foreign_dub_without_english(arch_md, ident, m["filename"], orig_lang=tv_orig_lang):
                continue

            file_year = extract_year_from_text(m["filename"])
            if year_conflicts_with_series_era(file_year, first_air_year, last_air_year):
                continue

            fname_prefix_match = re.match(
                r"(?i)^(.*?)(?:\bS\d{1,2}\s*[-_.]?\s*E\d{1,3}|\b\d{1,2}x\d{1,3}|\bSeason\s*\d{1,2}|\bSeries\s*\d{1,2})",
                m["filename"].rsplit("/", 1)[-1]
            )
            if fname_prefix_match:
                prefix_core = extract_series_core_title(fname_prefix_match.group(1))
                if len(prefix_core) >= 3 and not series_core_matches(prefix_core, valid_core_titles):
                    continue

            s_num, ep_nums = parse_season_and_episodes(str(item_title_meta), m["filename"], total_seasons_hint)
            if 1 <= s_num <= total_seasons_hint and ep_nums:
                max_ep_by_season[s_num] = max(max_ep_by_season.get(s_num, 0), max(ep_nums))
                item_parsed.append((s_num, ep_nums, m))

        season_overflow = any(
            s_no in season_ep_limits and max_ep > (season_ep_limits[s_no] + 2)
            for s_no, max_ep in max_ep_by_season.items()
        )
        if season_overflow:
            continue

        for s_num, ep_nums, m in item_parsed:
            detected_seasons.add(s_num)
            for e_num in ep_nums:
                unique_se_pairs.add((s_num, e_num))
            raw_candidates.append({
                "season": s_num,
                "episodes": ep_nums,
                "filename": m["filename"],
                "size_mb": m["size_mb"],
                "duration_mins": m["duration_mins"],
                "stream_url": m["stream_url"],
                "is_derivative": m["is_derivative"],
                "aspect_tier": m.get("aspect_tier", 1)
            })

    if not raw_candidates or (require_complete_s1 and 1 not in detected_seasons):
        if reject_reason_out is not None:
            reject_reason_out[0] = "incomplete_s1" if raw_candidates else "no_archive_match"
        return []

    episode_guide, aired_eps_by_season, _, _, _ = fetch_tmdb_series_guide(tmdb_id, detected_seasons)
    if not episode_guide or 1 not in aired_eps_by_season:
        if reject_reason_out is not None:
            reject_reason_out[0] = "no_archive_match"
        return []

    best_by_se = {}

    for cand in raw_candidates:
        s_num = cand["season"]
        ep_list = list(cand["episodes"])
        dur = cand["duration_mins"]

        if len(ep_list) == 1 and dur > 0:
            first_key = (s_num, ep_list[0])
            second_key = (s_num, ep_list[0] + 1)
            if first_key in episode_guide and second_key in episode_guide:
                rt1 = episode_guide[first_key][2]
                if rt1 > 0 and int(rt1 * 1.70) <= dur <= int(rt1 * 2.35):
                    ep_list = [ep_list[0], ep_list[0] + 1]

        valid_keys = [
            (s_num, e_no) for e_no in ep_list
            if (s_num, e_no) in episode_guide
        ]
        if not valid_keys:
            continue

        first_hr_name, first_en_name, _ = episode_guide[valid_keys[0]]
        if len(valid_keys) == 1 and filename_episode_title_conflicts(cand["filename"], first_hr_name, first_en_name):
            continue

        total_expected_rt = sum(episode_guide[k][2] for k in valid_keys if episode_guide[k][2] > 0)
        if dur > 0:
            if dur < 10:
                continue
            if total_expected_rt > 0 and (dur < int(total_expected_rt * 0.65) or dur > int(total_expected_rt * 1.80)):
                continue

        is_mp4 = cand["filename"].lower().endswith(".mp4")
        aspect_bonus = cand.get("aspect_tier", 1) * 3000.0
        orig_bonus = 2000.0 if (not cand["is_derivative"] and is_mp4) else (1500.0 if not cand["is_derivative"] else 500.0)
        quality_score = cand["size_mb"] + aspect_bonus + orig_bonus
        per_ep_dur = int(round(dur / float(len(valid_keys)))) if dur > 0 else 0

        for key in valid_keys:
            official_hr_name, _, _ = episode_guide[key]
            if key not in best_by_se or quality_score > best_by_se[key]["quality_score"]:
                clean_file_title, _ = clean_filename_to_title(cand["filename"])
                ep_title_clean = official_hr_name if official_hr_name else (clean_file_title or f"Epizoda {key[1]}")
                code_prefix = f"S{key[0]:02d}E{key[1]:02d}"
                display_title = f"{code_prefix} - {ep_title_clean}" if not ep_title_clean.upper().startswith(code_prefix) else ep_title_clean

                best_by_se[key] = {
                    "season": key[0],
                    "episode": key[1],
                    "title": display_title,
                    "url": cand["stream_url"],
                    "duration": format_duration_text(per_ep_dur),
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

    if require_complete_s1:
        sorted_episodes = filter_consecutive_complete_seasons(sorted_episodes, aired_eps_by_season)
        if not sorted_episodes:
            if reject_reason_out is not None:
                reject_reason_out[0] = "incomplete_s1"
            return []

    if sorted_episodes and not verify_stream_url_playable(sorted_episodes[0]["url"]):
        if reject_reason_out is not None:
            reject_reason_out[0] = "unplayable_stream"
        return []
    return sorted_episodes


def series_has_foreign_dub_episodes(row):
    urls_to_check = [row.get("stream_url") or ""]
    for ep in (row.get("episodes") or [])[:12]:
        if isinstance(ep, dict) and ep.get("url"):
            urls_to_check.append(ep["url"])
    for u in urls_to_check:
        if u and is_foreign_dub_without_english({}, u, u, orig_lang="en"):
            return True
    return False


def format_series_display_title(hr_name, orig_name, year):
    clean_base = re.sub(r"\s*\(\s*(19\d{2}|20\d{2})\s*\)\s*$", "", str(hr_name or orig_name)).strip()
    if year > 0 and has_multiple_tmdb_tv_versions(orig_name):
        return f"{clean_base} ({year})"
    return clean_base


def refresh_incomplete_series(existing_series_rows, deadline_ts):
    candidates = [
        r for r in existing_series_rows
        if (r.get("tmdb_id") or 0) > 0
    ]
    print(f"2/4 Provjera jezika, sezona i opisa za {len(candidates)} postojećih serija...", flush=True)
    updated_count = [0]

    def check_and_replenish(row):
        if time.monotonic() > deadline_ts:
            return None
        tmdb_id = int(row.get("tmdb_id") or 0)
        if tmdb_id <= 0:
            return None

        has_dub = series_has_foreign_dub_episodes(row)
        raw_eps = row.get("episodes") or []
        existing_map = {}
        season_counts = {}
        if not has_dub:
            for ep in raw_eps:
                if not isinstance(ep, dict):
                    continue
                s = int(ep.get("season") or 0)
                e = int(ep.get("episode") or 0)
                if s > 0 and e > 0 and ep.get("url"):
                    existing_map[(s, e)] = ep
                    season_counts[s] = season_counts.get(s, 0) + 1

        tv_basic = safe_get(f"{TMDB_BASE}/tv/{tmdb_id}", {"api_key": TMDB_API_KEY, "language": "en-US"})
        if not tv_basic:
            return None

        orig_name = tv_basic.get("original_name") or tv_basic.get("name") or row.get("title") or ""
        first_year = extract_year_from_text(tv_basic.get("first_air_date") or "") or int(row.get("release_year") or 0)
        desired_title = format_series_display_title(row.get("title") or orig_name, orig_name, first_year)

        meta_patch = {}
        if first_year > 0 and int(row.get("release_year") or 0) != first_year:
            meta_patch["release_year"] = first_year
        if desired_title and desired_title != (row.get("title") or ""):
            meta_patch["title"] = desired_title

        current_desc = (row.get("description_hr") or "").strip()
        if not current_desc or looks_like_english_text(current_desc):
            hr_desc = ensure_croatian_overview(current_desc, tmdb_id=tmdb_id)
            if hr_desc and hr_desc != current_desc:
                meta_patch["description_hr"] = hr_desc

        missing_seasons = []
        for s_info in (tv_basic.get("seasons") or []):
            s_no = int(s_info.get("season_number") or 0)
            ep_cnt = int(s_info.get("episode_count") or 0)
            if s_no >= 1 and ep_cnt > 0 and season_counts.get(s_no, 0) < ep_cnt:
                missing_seasons.append(s_no)

        merged_map = dict(existing_map)
        if has_dub or missing_seasons:
            fresh_episodes = aggregate_series_from_archive(
                tmdb_id,
                orig_name,
                desired_title,
                missing_seasons=missing_seasons,
                tv_basic=tv_basic,
                require_complete_s1=False
            )
            for ep in fresh_episodes:
                key = (int(ep["season"]), int(ep["episode"]))
                if key not in merged_map and verify_stream_url_playable(ep["url"]):
                    merged_map[key] = ep

        seasons_to_validate = sorted({s for (s, _) in merged_map.keys()} | {1})
        _, aired_eps_by_season, _, _, _ = fetch_tmdb_series_guide(tmdb_id, seasons_to_validate)

        if not aired_eps_by_season or 1 not in aired_eps_by_season:
            if meta_patch:
                supabase_patch_row("catalog_items", row["id"], meta_patch)
                return f"  ↻ Ažuriran opis/metapodaci serije: {desired_title}"
            return None

        all_candidate_eps = [
            merged_map[k]
            for k in sorted(merged_map.keys(), key=lambda item: (item[0], item[1]))
        ]
        valid_consecutive_eps = filter_consecutive_complete_seasons(all_candidate_eps, aired_eps_by_season)

        if not valid_consecutive_eps:
            if row.get("is_valid", True) or meta_patch:
                patch_payload = dict(meta_patch)
                patch_payload["is_valid"] = False
                supabase_patch_row("catalog_items", row["id"], patch_payload)
                return f"  ✕ Skrivena nepotpuna serija (nedostaje cijela 1. sezona): {desired_title}"
            return None

        old_keys = [(int(e.get("season") or 0), int(e.get("episode") or 0)) for e in raw_eps if isinstance(e, dict)]
        new_keys = [(int(e["season"]), int(e["episode"])) for e in valid_consecutive_eps]
        was_invalid = not row.get("is_valid", True)

        if was_invalid or old_keys != new_keys or meta_patch:
            seasons_now = len({ep["season"] for ep in valid_consecutive_eps})
            patch_payload = dict(meta_patch)
            patch_payload["episodes"] = valid_consecutive_eps
            patch_payload["stream_url"] = valid_consecutive_eps[0]["url"]
            patch_payload["is_valid"] = True
            supabase_patch_row("catalog_items", row["id"], patch_payload)
            with state_lock:
                updated_count[0] += 1
            return f"  ↻ Ažurirana serija: {desired_title} ({len(valid_consecutive_eps)} ep. / {seasons_now} kompletnih sez.)"

        return None

    with ThreadPoolExecutor(max_workers=10) as pool:
        futures = [pool.submit(check_and_replenish, r) for r in candidates]
        for fut in as_completed(futures):
            msg = fut.result()
            if msg:
                print(msg, flush=True)

    print(f"  Ažurirano ukupno {updated_count[0]} postojećih serija.", flush=True)


def sync_short_cartoons(existing_archive_ids, deadline_ts):
    print("1/4 Sinkronizacija Disney, Ex-Yu i klasičnih kratkih crtića...", flush=True)
    added_counter = [0]
    seen_short_titles = set()

    disney_tokens = {
        "disney", "mickey", "donald duck", "goofy", "pluto", "silly symphon",
        "steamboat willie", "oswald", "winnie the pooh", "scrooge", "chip and dale",
        "chip 'n' dale", "minnie mouse", "daisy duck"
    }
    ex_yu_set = set(EX_YU_SHORT_CARTOON_QUERIES)

    candidate_docs = {}
    for cartoon_query in CLASSIC_SHORT_CARTOON_QUERIES:
        if time.monotonic() > deadline_ts:
            break
        is_disney_q = cartoon_query in DISNEY_SHORT_CARTOON_QUERIES
        is_ex_yu_q = cartoon_query in ex_yu_set

        if is_ex_yu_q:
            query = f'title:"{cartoon_query}" AND mediatype:(movies OR web) AND -subject:trailer AND -subject:news'
        elif is_disney_q:
            query = f'title:"{cartoon_query}" AND mediatype:movies AND (subject:animation OR subject:cartoon OR collection:animationandcartoons OR collection:classic_cartoons OR collection:opensource_movies OR collection:community_media) AND -subject:trailer'
        else:
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
            ident = doc.get("identifier")
            if ident and ident not in existing_archive_ids and ident not in candidate_docs:
                candidate_docs[ident] = (doc, is_disney_q, is_ex_yu_q)

    def process_short_doc(item):
        if time.monotonic() > deadline_ts:
            return
        doc, is_disney_q, is_ex_yu_q = item
        ident = doc.get("identifier") or ""
        if not ident or ident.lower().startswith(("youtube-", "yt-")):
            return
        raw_title = doc.get("title") or ident
        if isinstance(raw_title, list):
            raw_title = raw_title[0]
        if contains_hentai_or_adult(ident, raw_title, doc.get("description")) or \
           contains_blocked_regional_or_arabic(ident, raw_title, doc.get("description")):
            return

        clean_t, parsed_y = clean_filename_to_title(str(raw_title), fallback_to_stem=True)
        if not clean_t or len(clean_t) < 2:
            return

        norm_s = normalize_for_exact_title_compare(clean_t)
        if not norm_s:
            return
        with state_lock:
            if norm_s in seen_short_titles:
                return
            seen_short_titles.add(norm_s)

        videos, arch_md = get_archive_item_details(ident, min_size_mb=4, allow_mkv=True)
        if not videos:
            return
        best = pick_best_video_file(videos)
        blob_check = f"{ident} {raw_title} {clean_t}".lower()
        is_ex_yu_item = is_ex_yu_q or is_ex_yu_content("", None, blob_check)

        if not is_ex_yu_item and is_foreign_dub_without_english(arch_md, ident, best["filename"], orig_lang="en"):
            return

        dur = best["duration_mins"]
        max_allowed_dur = 95 if is_ex_yu_item else 35
        if dur > 0 and (dur < 2 or dur > max_allowed_dur):
            return

        if not verify_stream_url_playable(best["stream_url"]):
            return

        desc = doc.get("description") or ""
        if isinstance(desc, list):
            desc = " ".join(str(x) for x in desc)
        hr_desc = ensure_croatian_overview(str(desc)[:800])

        is_disney_item = is_disney_q or any(tok in blob_check for tok in disney_tokens)
        genres = when_genres = (
            ["Animirani klasici", "Ex-Yu crtići", "Klasični crtići"]
            if is_ex_yu_item
            else (
                ["Animirani klasici", "Klasični crtići", "Disney kratki crtići"]
                if is_disney_item
                else ["Animirani klasici", "Klasični crtići"]
            )
        )

        row = {
            "archive_id": ident,
            "title": clean_t,
            "media_type": "movie",
            "genres": genres,
            "release_year": parsed_y,
            "popularity_score": float(doc.get("downloads") or 0),
            "description_hr": hr_desc,
            "poster_url": f"https://archive.org/services/img/{ident}",
            "stream_url": best["stream_url"],
            "is_featured": False,
            "is_valid": True,
            "tmdb_runtime": dur,
            "archive_runtime": dur
        }
        supabase_upsert("catalog_items", [row], "archive_id")
        with state_lock:
            added_counter[0] += 1

    with ThreadPoolExecutor(max_workers=10) as pool:
        list(pool.map(process_short_doc, candidate_docs.values()))

    print(f"  Dodano {added_counter[0]} novih Disney, Ex-Yu i klasičnih crtića.", flush=True)


def sync_all_series(seen_tmdb_series, deadline_ts):
    print("3/4 Duboka TMDB pretraga svih žanrova serija...", flush=True)
    candidate_shows = {}
    priority_ids = set()

    def fetch_tv_seeds_all_versions(seed_title):
        res = safe_get(f"{TMDB_BASE}/search/tv", {"api_key": TMDB_API_KEY, "query": seed_title, "language": "hr-HR"})
        target_norm = normalize_for_exact_title_compare(seed_title)
        matches = []
        for idx, item in enumerate((res or {}).get("results") or []):
            cand_norm = normalize_for_exact_title_compare(item.get("original_name") or item.get("name") or "")
            if idx == 0 or (target_norm and cand_norm == target_norm):
                matches.append(item)
        return matches

    print("4/4 Rudarenje TV kolekcija na Archive.org...", flush=True)
    discovered_cores = {}
    for col in ARCHIVE_TV_COLLECTIONS:
        if time.monotonic() > deadline_ts:
            break
        query = f"mediatype:movies AND collection:{col} AND -subject:trailer AND -subject:news"
        for page in [1, 2, 3]:
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
                if not raw_t or contains_hentai_or_adult(raw_t, doc.get("identifier")) or \
                   contains_blocked_regional_or_arabic(raw_t, doc.get("identifier")):
                    continue
                core = extract_series_core_title(raw_t)
                if len(core) >= 3 and core not in discovered_cores:
                    discovered_cores[core] = str(raw_t)

    with ThreadPoolExecutor(max_workers=12) as pool:
        for hits in pool.map(fetch_tv_seeds_all_versions, list(discovered_cores.keys())):
            for hit in hits:
                if hit and hit["id"] not in seen_tmdb_series:
                    candidate_shows[hit["id"]] = hit
                    priority_ids.add(hit["id"])

    with ThreadPoolExecutor(max_workers=12) as pool:
        for hits in pool.map(fetch_tv_seeds_all_versions, CLASSIC_TV_SEED_TITLES):
            for hit in hits:
                if hit and hit["id"] not in seen_tmdb_series:
                    candidate_shows[hit["id"]] = hit
                    priority_ids.add(hit["id"])

    for endpoint in ["tv/popular", "tv/top_rated"]:
        for page in range(1, 8):
            data = safe_get(f"{TMDB_BASE}/{endpoint}", {"api_key": TMDB_API_KEY, "language": "hr-HR", "page": page})
            for s in (data or {}).get("results", []):
                if s["id"] not in seen_tmdb_series and s["id"] not in candidate_shows:
                    candidate_shows[s["id"]] = s

    for tv_genre in TMDB_TV_DISCOVER_GENRE_IDS:
        for page in range(1, 5):
            data = safe_get(
                f"{TMDB_BASE}/discover/tv",
                {
                    "api_key": TMDB_API_KEY,
                    "language": "hr-HR",
                    "with_genres": tv_genre,
                    "first_air_date.gte": "1960-01-01",
                    "first_air_date.lte": "1999-12-31",
                    "vote_count.gte": 15,
                    "sort_by": "popularity.desc",
                    "page": page
                }
            )
            for s in (data or {}).get("results", []):
                if s["id"] not in seen_tmdb_series and s["id"] not in candidate_shows:
                    candidate_shows[s["id"]] = s

    ordered_candidates = sorted(
        candidate_shows.items(),
        key=lambda item: (
            0 if item[0] in priority_ids else 1,
            -float(item[1].get("popularity") or 0.0)
        )
    )

    total_candidates = len(ordered_candidates)
    print(f"  Ukupno {total_candidates} novih serija za provjeru na Archive.org...", flush=True)

    checked_counter = [0]
    added_series_counter = [0]
    time_limit_notified = [False]
    stats = {
        "no_archive_match": 0,
        "incomplete_s1": 0,
        "unplayable_stream": 0,
        "blocked": 0,
    }

    def inc_stat(key):
        with state_lock:
            stats[key] = stats.get(key, 0) + 1

    def process_series(item):
        if time.monotonic() > deadline_ts:
            with state_lock:
                if not time_limit_notified[0]:
                    time_limit_notified[0] = True
                    print("  ⏱ Dostignut sigurnosni vremenski limit — uredno spremanje i završetak...", flush=True)
            return None

        tv_id, s = item
        with state_lock:
            checked_counter[0] += 1
            c_now = checked_counter[0]
            if c_now % 100 == 0:
                print(f"  ... Provjereno {c_now}/{total_candidates} kandidata (dodano {added_series_counter[0]} serija)...", flush=True)
            if tv_id in seen_tmdb_series:
                return None

        orig_name = s.get("original_name") or s.get("name") or ""
        hr_name = s.get("name") or orig_name
        overview = (s.get("overview") or "").strip()

        if contains_hentai_or_adult(orig_name, hr_name, overview) or \
           contains_blocked_regional_or_arabic(orig_name, hr_name, overview, orig_lang=s.get("original_language", "")):
            inc_stat("blocked")
            return None

        first_air = s.get("first_air_date") or ""
        year = int(first_air[:4]) if len(first_air) >= 4 and first_air[:4].isdigit() else 0

        reason_holder = ["no_archive_match"]
        episodes = aggregate_series_from_archive(
            tv_id, orig_name, hr_name, require_complete_s1=True, reject_reason_out=reason_holder
        )
        if not episodes:
            inc_stat(reason_holder[0])
            return None

        with state_lock:
            if tv_id in seen_tmdb_series:
                return None
            seen_tmdb_series.add(tv_id)
            added_series_counter[0] += 1

        overview_hr = ensure_croatian_overview(overview, tmdb_id=tv_id)

        g_ids = s.get("genre_ids", [])
        genres = determine_tv_primary_genres(g_ids, orig_name, hr_name)
        seasons_found = len({ep["season"] for ep in episodes})
        final_title = format_series_display_title(hr_name, orig_name, year)

        row = {
            "archive_id": f"tmdb-tv-{tv_id}",
            "tmdb_id": tv_id,
            "title": final_title,
            "media_type": "series",
            "genres": genres,
            "release_year": year,
            "rating": round(float(s.get("vote_average") or 0.0), 1),
            "popularity_score": float(s.get("popularity") or 10.0) * 100.0 + (len(episodes) * 15),
            "description_hr": overview_hr,
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
        return f"  + Serija: {row['title']} ({seasons_found} kompletnih sez. / {len(episodes)} ep.) -> {genres[0]}"

    with ThreadPoolExecutor(max_workers=10) as pool:
        futures = [pool.submit(process_series, item) for item in ordered_candidates]
        for fut in as_completed(futures):
            msg = fut.result()
            if msg:
                print(msg, flush=True)

    print(
        f"  Dodano {added_series_counter[0]} novih serija "
        f"(bez zapisa na Archive.org: {stats['no_archive_match']}, nepotpuna 1. sezona: {stats['incomplete_s1']}, "
        f"nedostupan stream: {stats['unplayable_stream']}).",
        flush=True
    )


def run_series_and_cartoons_sync():
    start_ts = time.monotonic()
    deadline_ts = start_ts + MAX_SYNC_DURATION_SECONDS
    print("=== [SERIJE I CRTIĆI] Pokretanje sinkronizacije serija, Ex-Yu i klasičnih crtića ===", flush=True)
    existing_archive_ids, _, _, _ = fetch_existing_catalog_state("movie")
    _, seen_tmdb_series, _, existing_series_rows = fetch_existing_catalog_state("series")
    print(f"  U bazi već postoji {len(seen_tmdb_series)} potvrđenih serija.", flush=True)
    sync_short_cartoons(existing_archive_ids, deadline_ts)
    refresh_incomplete_series(existing_series_rows, deadline_ts)
    sync_all_series(seen_tmdb_series, deadline_ts)
    elapsed_mins = (time.monotonic() - start_ts) / 60.0
    print(f"=== [SERIJE I CRTIĆI] Završeno za {elapsed_mins:.1f} min! Ukupno serija u katalogu: {len(seen_tmdb_series)} ===", flush=True)


if __name__ == "__main__":
    run_series_and_cartoons_sync()
