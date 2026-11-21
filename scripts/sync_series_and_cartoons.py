import os
import re
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed

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
    fetch_existing_catalog_state,
    format_duration_text,
    get_archive_item_details,
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

CLASSIC_SHORT_CARTOON_QUERIES = DISNEY_SHORT_CARTOON_QUERIES + [
    "Tom and Jerry", "Looney Tunes", "Merrie Melodies", "Bugs Bunny",
    "Popeye", "Woody Woodpecker", "Pink Panther",
    "Droopy", "Betty Boop", "Felix the Cat", "Casper the Friendly Ghost",
    "Superman", "Mighty Mouse", "Road Runner", "Daffy Duck",
    "Porky Pig", "Tweety", "Yogi Bear", "Huckleberry Hound",
    "Top Cat", "The Flintstones", "The Jetsons", "Scooby-Doo",
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
    "The Flintstones", "The Simpsons", "Futurama", "Dragon Ball", "Dragon Ball Z", "Pokémon",
]


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
        en_overview = ((en_tv or {}).get("overview") or "").strip()
        overview_hr = translate_to_croatian(en_overview) if en_overview else ""

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


def aggregate_series_from_archive(
    tmdb_id,
    orig_name,
    hr_name,
    missing_seasons=None,
    tv_basic=None,
    min_episodes_required=2
):
    if contains_hentai_or_adult(orig_name, hr_name) or contains_blocked_regional_or_arabic(orig_name, hr_name):
        return []
    clean_q = re.sub(r"[^\w\s]", " ", orig_name).strip()
    if len(clean_q) < 3:
        return []

    queries = [(f'title:"{clean_q}" AND mediatype:movies AND -subject:trailer AND -subject:news AND -subject:review', 75)]
    if missing_seasons:
        for s_target in sorted(set(missing_seasons))[:6]:
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
                "fl[]": ["identifier", "title", "downloads"],
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
        return []

    valid_core_titles = {extract_series_core_title(orig_name), extract_series_core_title(hr_name)}
    valid_core_titles.discard("")

    if tv_basic is None:
        tv_basic = safe_get(f"{TMDB_BASE}/tv/{tmdb_id}", {"api_key": TMDB_API_KEY, "language": "en-US"})
    total_seasons_hint = int((tv_basic or {}).get("number_of_seasons") or 1)
    tv_orig_lang = str((tv_basic or {}).get("original_language") or "en")

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
            if is_foreign_dub_without_english(arch_md, ident, m["filename"], orig_lang=tv_orig_lang):
                continue

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
                    "is_derivative": m["is_derivative"],
                    "aspect_tier": m.get("aspect_tier", 1)
                })

    if len(raw_candidates) < min_episodes_required:
        return []

    episode_guide, _, _, _ = fetch_tmdb_series_guide(tmdb_id, detected_seasons)
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
        aspect_bonus = cand.get("aspect_tier", 1) * 3000.0
        orig_bonus = 2000.0 if (not cand["is_derivative"] and is_mp4) else (1500.0 if not cand["is_derivative"] else 500.0)
        quality_score = cand["size_mb"] + aspect_bonus + orig_bonus
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
    if len(sorted_episodes) < min_episodes_required:
        return []
    if min_episodes_required >= 2 and not verify_stream_url_playable(sorted_episodes[0]["url"]):
        return []
    return sorted_episodes


def refresh_incomplete_series(existing_series_rows):
    """Provjerava postojeće serije u bazi i dopunjuje sezone/epizode koje nedostaju."""
    candidates = [
        r for r in existing_series_rows
        if r.get("is_valid", True) and (r.get("tmdb_id") or 0) > 0
    ]
    print(f"2/4 Provjera i dopuna sezona/epizoda za {len(candidates)} postojećih serija...", flush=True)
    updated_count = [0]

    def check_and_replenish(row):
        tmdb_id = int(row.get("tmdb_id") or 0)
        if tmdb_id <= 0:
            return None

        raw_eps = row.get("episodes") or []
        existing_map = {}
        season_counts = {}
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

        total_tmdb_eps = int(tv_basic.get("number_of_episodes") or 0)
        if total_tmdb_eps > 0 and len(existing_map) >= total_tmdb_eps:
            return None

        missing_seasons = []
        for s_info in (tv_basic.get("seasons") or []):
            s_no = int(s_info.get("season_number") or 0)
            ep_cnt = int(s_info.get("episode_count") or 0)
            if s_no >= 1 and ep_cnt > 0 and season_counts.get(s_no, 0) < ep_cnt:
                missing_seasons.append(s_no)

        if not missing_seasons and total_tmdb_eps <= 0:
            return None

        orig_name = tv_basic.get("original_name") or tv_basic.get("name") or row.get("title") or ""
        hr_name = row.get("title") or orig_name

        fresh_episodes = aggregate_series_from_archive(
            tmdb_id,
            orig_name,
            hr_name,
            missing_seasons=missing_seasons,
            tv_basic=tv_basic,
            min_episodes_required=1
        )
        if not fresh_episodes:
            return None

        merged_map = dict(existing_map)
        added_new = 0
        for ep in fresh_episodes:
            key = (int(ep["season"]), int(ep["episode"]))
            if key not in merged_map and verify_stream_url_playable(ep["url"]):
                merged_map[key] = ep
                added_new += 1

        if added_new == 0:
            return None

        merged_list = [
            merged_map[k]
            for k in sorted(merged_map.keys(), key=lambda item: (item[0], item[1]))
        ]
        seasons_now = len({ep["season"] for ep in merged_list})
        supabase_patch_row(
            "catalog_items",
            row["id"],
            {
                "episodes": merged_list,
                "stream_url": merged_list[0]["url"]
            }
        )
        with state_lock:
            updated_count[0] += 1
        return f"  ↻ Dopunjena serija: {row['title']} ({len(existing_map)} -> {len(merged_list)} ep. / {seasons_now} sez.)"

    with ThreadPoolExecutor(max_workers=12) as pool:
        futures = [pool.submit(check_and_replenish, r) for r in candidates]
        for fut in as_completed(futures):
            msg = fut.result()
            if msg:
                print(msg, flush=True)

    print(f"  Dopunjeno ukupno {updated_count[0]} postojećih serija novim sezonama/epizodama.", flush=True)


def sync_short_cartoons(existing_archive_ids):
    print("1/4 Sinkronizacija Disney i klasičnih kratkih crtića...", flush=True)
    added_counter = [0]
    seen_short_titles = set()

    disney_tokens = {
        "disney", "mickey", "donald duck", "goofy", "pluto", "silly symphon",
        "steamboat willie", "oswald", "winnie the pooh", "scrooge", "chip and dale",
        "chip 'n' dale", "minnie mouse", "daisy duck"
    }

    candidate_docs = {}
    for cartoon_query in CLASSIC_SHORT_CARTOON_QUERIES:
        is_disney_q = cartoon_query in DISNEY_SHORT_CARTOON_QUERIES
        if is_disney_q:
            query = f'title:"{cartoon_query}" AND mediatype:movies AND (subject:animation OR subject:cartoon OR collection:animationandcartoons OR collection:classic_cartoons OR collection:opensource_movies OR collection:community_media) AND -subject:trailer'
        else:
            query = f'title:"{cartoon_query}" AND mediatype:movies AND (subject:animation OR subject:cartoon OR collection:animationandcartoons OR collection:classic_cartoons) AND -subject:trailer'

        data = safe_get(
            "https://archive.org/advancedsearch.php",
            params={
                "q": query,
                "fl[]": ["identifier", "title", "year", "downloads", "description"],
                "sort[]": "downloads desc",
                "rows": 45,
                "output": "json"
            }
        )
        for doc in (data or {}).get("response", {}).get("docs", []):
            ident = doc.get("identifier")
            if ident and ident not in existing_archive_ids and ident not in candidate_docs:
                candidate_docs[ident] = (doc, is_disney_q)

    def process_short_doc(item):
        doc, is_disney_q = item
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
        if is_foreign_dub_without_english(arch_md, ident, best["filename"], orig_lang="en"):
            return

        dur = best["duration_mins"]
        if dur > 0 and (dur < 2 or dur > 35):
            return

        if not verify_stream_url_playable(best["stream_url"]):
            return

        desc = doc.get("description") or ""
        if isinstance(desc, list):
            desc = " ".join(str(x) for x in desc)

        blob_check = f"{ident} {raw_title} {clean_t}".lower()
        is_disney_item = is_disney_q or any(tok in blob_check for tok in disney_tokens)
        genres = ["Animirani klasici", "Klasični crtići", "Disney kratki crtići"] if is_disney_item else ["Animirani klasici", "Klasični crtići"]

        row = {
            "archive_id": ident,
            "title": clean_t,
            "media_type": "movie",
            "genres": genres,
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
        with state_lock:
            added_counter[0] += 1

    with ThreadPoolExecutor(max_workers=14) as pool:
        list(pool.map(process_short_doc, candidate_docs.values()))

    print(f"  Dodano {added_counter[0]} novih Disney i klasičnih kratkih crtića.", flush=True)


def sync_all_series(seen_tmdb_series):
    print("3/4 Duboka TMDB pretraga svih žanrova serija...", flush=True)
    candidate_shows = {}

    for endpoint in ["tv/popular", "tv/top_rated"]:
        for page in range(1, 13):
            data = safe_get(f"{TMDB_BASE}/{endpoint}", {"api_key": TMDB_API_KEY, "language": "hr-HR", "page": page})
            for s in (data or {}).get("results", []):
                if s["id"] not in seen_tmdb_series:
                    candidate_shows[s["id"]] = s

    for tv_genre in TMDB_TV_DISCOVER_GENRE_IDS:
        for sort_by in ["popularity.desc", "vote_count.desc"]:
            for page in range(1, 9):
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
        for page in range(1, 8):
            data = safe_get(
                f"{TMDB_BASE}/discover/tv",
                {
                    "api_key": TMDB_API_KEY,
                    "language": "hr-HR",
                    "with_genres": tv_genre,
                    "first_air_date.gte": "2000-01-01",
                    "vote_count.gte": 25,
                    "sort_by": "popularity.desc",
                    "page": page
                }
            )
            for s in (data or {}).get("results", []):
                if s["id"] not in seen_tmdb_series:
                    candidate_shows[s["id"]] = s

    def fetch_tv_seed(seed_title):
        res = safe_get(f"{TMDB_BASE}/search/tv", {"api_key": TMDB_API_KEY, "query": seed_title, "language": "hr-HR"})
        return ((res or {}).get("results") or [None])[0]

    with ThreadPoolExecutor(max_workers=14) as pool:
        for first in pool.map(fetch_tv_seed, CLASSIC_TV_SEED_TITLES):
            if first and first["id"] not in seen_tmdb_series:
                candidate_shows[first["id"]] = first

    print("4/4 Rudarenje TV kolekcija na Archive.org...", flush=True)
    discovered_cores = {}
    for col in ARCHIVE_TV_COLLECTIONS:
        query = f"mediatype:movies AND collection:{col} AND -subject:trailer AND -subject:news"
        for page in [1, 2, 3, 4]:
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

    with ThreadPoolExecutor(max_workers=14) as pool:
        for hit in pool.map(fetch_tv_seed, list(discovered_cores.keys())):
            if hit and hit["id"] not in seen_tmdb_series:
                candidate_shows[hit["id"]] = hit

    print(f"  Ukupno {len(candidate_shows)} novih serija za provjeru na Archive.org...", flush=True)

    def process_series(item):
        tv_id, s = item
        with state_lock:
            if tv_id in seen_tmdb_series:
                return None

        orig_name = s.get("original_name") or s.get("name") or ""
        hr_name = s.get("name") or orig_name
        overview = (s.get("overview") or "").strip()

        if contains_hentai_or_adult(orig_name, hr_name, overview) or \
           contains_blocked_regional_or_arabic(orig_name, hr_name, overview, orig_lang=s.get("original_language", "")):
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
            en_ov = ((en_tv or {}).get("overview") or "").strip()
            overview = translate_to_croatian(en_ov) if en_ov else ""

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
        return f"  + Serija: {row['title']} ({seasons_found} sez. / {len(episodes)} ep.) -> {genres[0]}"

    with ThreadPoolExecutor(max_workers=14) as pool:
        futures = [pool.submit(process_series, item) for item in candidate_shows.items()]
        for fut in as_completed(futures):
            msg = fut.result()
            if msg:
                print(msg, flush=True)


def run_series_and_cartoons_sync():
    print("=== [SERIJE I CRTIĆI] Pokretanje brze sinkronizacije serija i kratkih crtića ===", flush=True)
    existing_archive_ids, _, _, _ = fetch_existing_catalog_state("movie")
    _, seen_tmdb_series, _, existing_series_rows = fetch_existing_catalog_state("series")
    print(f"  U bazi već postoji {len(seen_tmdb_series)} potvrđenih serija.", flush=True)
    sync_short_cartoons(existing_archive_ids)
    refresh_incomplete_series(existing_series_rows)
    sync_all_series(seen_tmdb_series)
    print(f"=== [SERIJE I CRTIĆI] Završeno! Ukupno serija u katalogu: {len(seen_tmdb_series)} ===", flush=True)


if __name__ == "__main__":
    run_series_and_cartoons_sync()
