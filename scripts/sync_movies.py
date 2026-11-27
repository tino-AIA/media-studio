import inspect
import os
import random
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from urllib.parse import unquote

import requests

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import sync_common as sc
from sync_common import (
    EXTENDED_CUT_TAG_RE,
    NON_LATIN_TITLE_RE,
    TMDB_API_KEY,
    TMDB_BASE,
    TMDB_IMG_BACKDROP,
    TMDB_IMG_POSTER,
    clean_filename_to_title,
    compute_aspect_tier,
    contains_blocked_regional_or_arabic,
    contains_hentai_or_adult,
    determine_primary_genres,
    extract_year_from_text,
    fetch_existing_catalog_state,
    get_archive_item_details,
    get_tmdb_movie_details,
    has_multiple_tmdb_versions,
    is_foreign_dub_without_english,
    is_future_release,
    normalize_for_exact_title_compare,
    pick_best_video_file,
    safe_get,
    state_lock,
    supabase_patch_row,
    supabase_upsert,
    validate_movie_match,
    verify_stream_url_playable,
)

# Ubrzanje mrežnih poziva: recikliranje HTTP/TLS konekcija (Keep-Alive)
_HTTP_SESSION = requests.Session()
_HTTP_ADAPTER = requests.adapters.HTTPAdapter(pool_connections=16, pool_maxsize=16, max_retries=1)
_HTTP_SESSION.mount("https://", _HTTP_ADAPTER)
_HTTP_SESSION.mount("http://", _HTTP_ADAPTER)

if hasattr(sc, "session"):
    sc.session = _HTTP_SESSION

MAX_MOVIES_SYNC_SECONDS = 34 * 60
PHASE1_AUDIT_MAX_SECONDS = 3 * 60
PHASE2_DIRECT_MOVIES_MAX_SECONDS = 18 * 60
PHASE3_CURATED_AND_EXYU_MAX_SECONDS = 6 * 60

BATCH_SIZE_CANDIDATES = 25
MIN_REMAINING_SECONDS_FOR_NEXT_BATCH = 60

MAX_VERSIONS_PER_MOVIE = 2
MAX_AUDIT_SAMPLE_PER_RUN = 300
MAX_VHS_UPGRADE_CHECKS_PER_RUN = 10
MAX_DYNAMIC_BUNDLES_PER_RUN = 15
MAX_VIDEOS_PER_BUNDLE = 75
TMDB_SEED_SAMPLE_PER_RUN = 40

_REJECTED_BATCH_BUFFER = []
_REJECTED_BUFFER_SIZE = 150


def flush_rejected_buffer(force=False):
    to_send = []
    with state_lock:
        if len(_REJECTED_BATCH_BUFFER) >= _REJECTED_BUFFER_SIZE or (force and _REJECTED_BATCH_BUFFER):
            to_send = list(_REJECTED_BATCH_BUFFER)
            _REJECTED_BATCH_BUFFER.clear()
    if to_send:
        supabase_upsert("catalog_items", to_send, "archive_id")


def record_rejected_archive_id(ident, title, rel_year, existing_archive_ids):
    if not ident or ident.startswith("http"):
        return
    if isinstance(rel_year, list):
        rel_year = rel_year[0] if rel_year else 0
    try:
        clean_year = int(str(rel_year)[:4]) if rel_year else 0
    except (ValueError, TypeError):
        clean_year = 0

    should_queue = False
    with state_lock:
        if ident not in existing_archive_ids:
            existing_archive_ids.add(ident)
            should_queue = True
    if should_queue:
        row = {
            "archive_id": ident,
            "tmdb_id": 0,
            "title": str(title or ident)[:180],
            "media_type": "movie",
            "genres": [],
            "release_year": clean_year,
            "rating": 0.0,
            "popularity_score": -1000.0,
            "description_hr": "",
            "poster_url": "",
            "backdrop_url": "",
            "stream_url": "",
            "is_featured": False,
            "is_valid": False,
            "tmdb_runtime": 0,
            "archive_runtime": 0,
        }
        with state_lock:
            _REJECTED_BATCH_BUFFER.append(row)
        flush_rejected_buffer(force=False)


def queue_series_candidate_for_tv_sync(ident, raw_title):
    base_ident = str(ident or "").split(",tmdb-")[0].strip()
    if not base_ident or base_ident.startswith("http") or base_ident.startswith("tv_queue:"):
        return
    clean_t, parsed_y = clean_filename_to_title(str(raw_title or base_ident), fallback_to_stem=True)
    row = {
        "archive_id": f"tv_queue:{base_ident}",
        "tmdb_id": 0,
        "title": str(clean_t or raw_title or base_ident)[:180],
        "media_type": "series",
        "genres": [],
        "release_year": int(parsed_y or 0),
        "rating": 0.0,
        "popularity_score": 5000.0,
        "description_hr": base_ident,
        "poster_url": "",
        "backdrop_url": "",
        "stream_url": "",
        "is_featured": False,
        "is_valid": False,
        "tmdb_runtime": 0,
        "archive_runtime": 0,
    }
    supabase_upsert("catalog_items", [row], "archive_id")


# ==============================================================================
# HIJERARHIJSKO STABLO IZ archive_movies_layers_2.md (pročišćeno bez TV/glazbe/šuma)
# ==============================================================================
APPROVED_HIERARCHY_NODES = [
    ("moviesandfilms", 4),
    ("feature_films", 5),
    ("feature_films_unsorted", 5),
    ("SciFi_Horror", 3),
    ("Comedy_Films", 3),
    ("silent_films", 3),
    ("Film_Noir", 3),
    ("TheVideoCellarCollection", 3),
    ("feature_films_picfixer", 3),
    ("colorized-movies", 3),
    ("german_cinema", 2),
    ("film_scifi", 3),
    ("mid-century-german-film", 3),
    ("collectie_filmcollectief", 3),
    ("laserdiscs", 3),
    ("KINOfilm", 2),
    ("thecosmonaut", 2),
    ("subtitled_movies", 2),
    ("globiansfilmfestival", 2),
    ("cliffhangers", 2),
    ("misfits-of-film-3am-films", 2),
    ("dvdtray", 4),
    ("dvdtray_inbox", 4),
    ("vhsmovies", 4),
    ("vhsanddvdcollector", 3),
    ("the-vista-group-video", 3),
    ("video-home-system", 3),
    ("vhsmisc", 3),
    ("vhsvault_inbox", 2),
    ("animationandcartoons", 3),
    ("classic_cartoons", 2),
    ("vintage_cartoons", 2),
    ("animation_unsorted", 3),
    ("russiananimationcartoons", 2),
    ("saturdaymorningcartoons", 2),
    ("anime", 3),
    ("anime_miscellaneous", 3),
    ("televisionmovies", 3),
    ("popcornproject", 2),
    ("popcornmaker", 3),
    ("foreignlanguagevideos", 2),
    ("SciFi_Films", 3),
    ("westerns", 3),
    ("war_films", 3),
    ("horror_films", 3),
    ("romance_films", 3),
    ("thriller_films", 3),
    ("action_adventure_films", 3),
    ("drama_films", 3),
    ("mystery_films", 3),
]

APPROVED_NODE_IDS = [b for b, _ in APPROVED_HIERARCHY_NODES]

LARGE_FLAT_BUCKETS = [
    "opensource_movies",
    "additional_collections_video",
    "vhsvault",
    "moviesandfilms",
    "feature_films",
    "dvdtray",
]

DECADE_RANGES = [
    (1930, 1959),
    (1960, 1969),
    (1970, 1979),
    (1980, 1989),
    (1990, 1999),
    (2000, 2009),
    (2010, 2019),
    (2020, 2026),
]

EXCLUDED_SUBLAYER_RE = re.compile(
    r"(?i)(?:news|cnn|fox[\s._-]*news|msnbc|cspan|election|parliament|senate|"
    r"twitch|tiktok|vlog|podcast|sermon|church|prayer|university|lecture|course|"
    r"olympic|championship|tournament|sports|commercial|trailer|gameplay|speedrun|"
    r"foia|radars|military[\s._-]*data|visual[\s._-]*novel|stock[\s._-]*footage|home[\s._-]*movies|"
    r"karaoke|soundtrack|audio|openings|instructional|elvisvideoclips)"
)

PINNED_MULTI_FILE_BUNDLES = [
    "dvdisocollection_202605",
    "james-bond-collection",
    "ISOanimationMoviesDVD",
]

BAD_BUNDLE_PREFIXES = (
    "disc-8_20230612,tmdb-",
    "little-people_202111,tmdb-",
    "dadsarmythecompletecollection,tmdb-",
    "asian-beauties,tmdb-",
    "FFTGTG_Web_DVD,tmdb-",
    "oldhackerinfosecvideos,tmdb-",
    "Grundeinkommen,tmdb-",
    "Opti-Archive,tmdb-",
)

BAD_TITLE_MARKER_RE = re.compile(
    r"(?i)(?:dts\s*blu-ray|music\s*demo\s*disc|demo\s*disc\s*\d+|screen\s*snapshots|"
    r"observational\s*series|persian\s*series|barney'?s?\s+|my\s*little\s*pony\s*live|"
    r"bear\s*in\s*the\s*big\s*blue\s*house\s*live|asian\s*beauties|krotoplaxx|^untitled\b|"
    r"mickey\s*mouse\s*club|hardy\s*boys|broncos\s*vs|seahawks?|soultrain|manlyphall)"
)

TV_NEWS_OR_SPORTS_ID_RE = re.compile(
    r"(?i)(?:^(?:tv-|cspan\d*_|foxnews|msnbc|cnn_|bbcnews|aljazeera)|"
    r"\b(?:broncos|seahawks|nfl|nba|mlb|nhl|ufc|wwe)\b)"
)

STRICT_EPISODE_RE = re.compile(
    r"(?i)(?:\b\d{1,2}x(?!264\b|265\b|480\b|540\b|576\b|720\b|1080\b)\d{2,3}\b|\bs\d{1,2}e\d{1,3}\b)"
)

EXPLICIT_BUNDLE_FILE_TMDB_MAP = {
    ("james-bond-collection", "james bond 1-33.mp4"): 646,
    ("james-bond-collection", "james bond 1-3.mp4"): 657,
    ("james-bond-collection", "james bond 1-31.mp4"): 658,
    ("james-bond-collection", "james bond 4-61.mp4"): 660,
    ("james-bond-collection", "james bond 4-62.mp4"): 667,
    ("james-bond-collection", "james bond 4-6.mp4"): 668,
    ("james-bond-collection", "james bond 7-91.mp4"): 681,
    ("james-bond-collection", "james bond 7-92.mp4"): 253,
    ("james-bond-collection", "james bond 7-9.mp4"): 682,
    ("james-bond-collection", "james bond 10-121.mp4"): 691,
    ("james-bond-collection", "james bond 10-122.mp4"): 698,
    ("james-bond-collection", "james bond 10-12.mp4"): 699,
    ("james-bond-collection", "james bond 16-18.mp4"): 709,
    ("james-bond-collection", "james bond 16-182.mp4"): 710,
    ("james-bond-collection", "james bond 16-183.mp4"): 714,
    ("james-bond-collection", "james bond 19-211.mp4"): 36643,
    ("james-bond-collection", "james bond 19-212.mp4"): 36669,
    ("james-bond-collection", "james bond 19-21.mp4"): 36557,
    ("dvdisocollection_202605", "austinpowers/austinpowers.mp4"): 816,
    ("dvdisocollection_202605", "austinpowers2/austinpowers2.mp4"): 817,
    ("dvdisocollection_202605", "goldmember/goldmember28.mp4"): 818,
    ("dvdisocollection_202605", "blazing_saddles_special_editio/blazing_saddles_special_editio.mp4"): 11072,
    ("dvdisocollection_202605", "jaws/jaws1.mp4"): 578,
    ("dvdisocollection_202605", "madmax/madmax.mp4"): 9659,
    ("dvdisocollection_202605", "madmax/madmax2.mp4"): 8810,
    ("dvdisocollection_202605", "madmax/madmax3.mp4"): 9355,
    ("dvdisocollection_202605", "mousehunt/mousehunt.mp4"): 11584,
    ("dvdisocollection_202605", "priscilla_qotd_p4/priscilla_qotd_p4.mp4"): 2759,
    ("dvdisocollection_202605", "star_wars_episode_v/star_wars_episode_v.mp4"): 1891,
    ("dvdisocollection_202605", "the_exorcist_2000/the_exorcist_2000.mp4"): 9552,
    ("isoanimationmoviesdvd", "aladindisco1.mp4"): 812,
    ("isoanimationmoviesdvd", "et.mp4"): 601,
    ("isoanimationmoviesdvd", "las locuras del emperador.mp4"): 11688,
    ("isoanimationmoviesdvd", "monstruossa.mp4"): 585,
}

BUNDLE_NOISE_RE = re.compile(
    r"(?i)(?:olympic|berkeley|lecture|course|seminar|webcast|podcast|news|cnn|fox[\s._-]*news|"
    r"twitch|tiktok|vlog|sermon|church|prayer|symphony|orchestra|sonata|championship|tournament|"
    r"football|basketball|swimming|gymnastics|athletics|gameplay|speedrun|walkthrough|"
    r"apollo\d*|onboard[\s._-]*film|natureexplorers|youtube[\s._-]*channel|fox[\s._-]*kids|"
    r"tgif|woc\b|vh1|mtv|pbs|bbc|cbs|nbc|abc[\s._-]*beakman|visual[\s._-]*novel|"
    r"\b\d+\s*of\s*\d+\b|series\b|season\b|episode\b|election[\s._-]*coverage|"
    r"templeos|foia|norad|infosec|hacker|fansub|blackadder|dadsarmy|pbj-otter|"
    r"asian[\s._-]*beauties|demo[\s._-]*disc|little[\s._-]*people|barney|debates\d*|"
    r"openlibrary|opti-archive)"
)

EXPLICIT_JUNK_RE = re.compile(
    r"(?i)(?:\b(?:trailer|sample|teaser|camrip|telesync|telecine|"
    r"screener|dvdscr|workprint|featurette|parody|rifftrax|mockbuster|"
    r"commentary|walkthrough|longplay|gameplay|playthrough|speedrun|"
    r"ps2|ps3|ps4|gamecube|xbox|nintendo|wii|pcsx2|dolphin|emulator)\b|"
    r"watch[\s._-]*along|audio[\s._-]*commentary|director'?s?[\s._-]*commentary|"
    r"holiday[\s._-]+special|making[\s._-]+of|"
    r"behind[\s._-]+the[\s._-]+scenes|fan[\s._-]*(?:film|edit)|"
    r"pencil[\s._-]*test|shot[\s._-]*demo|test[\s._]*footage|no[\s._-]+memory[\s._-]+card)"
)

GENERIC_CAMERA_OR_RIP_FILENAME_RE = re.compile(
    r"(?i)^(?:img|vid|mov|dsc|dcim|title|dvd[\s._-]*video|video|screen[\s._-]*recording|"
    r"snapsave|rumble|original|m4v\d+|nveexport|[a-z]\d+[\s._-]*t\d+|\d+[\s._-]*\d+|"
    r"[0-9a-f]{12,}|[a-z0-9]{10,})$"
)

SPECIAL_EDITION_RE = re.compile(
    r"(?i)\b(?:remastered|restored|colorized|extended|unrated|uncut|redux|"
    r"director'?s?\s*cut|special\s*edition|alternative\s*edition|criterion)\b"
)

DUNE_1984_MARKER_RE = re.compile(
    r"(?i)\b(?:1984|lynch|redux|fanedit|complete[\s._-]*saga|alternative[\s._-]*edition)\b"
)

EX_YU_LANGS = getattr(sc, "EX_YU_ORIGINAL_LANGS", {"hr", "sr", "sh", "bs", "sl", "mk"})
_GENRES_ACCEPTS_ORIG_LANG = "orig_lang" in inspect.signature(determine_primary_genres).parameters

EX_YU_DETECTION_KEYWORDS = (
    "zagreb film", "jadran film", "avala film", "centar film", "sutjeska film",
    "bosna film", "vardar film", "triglav film", "vesna film", "dunav film",
    "zvezda film", "adriatic film", "croatia film", "rtv zagreb", "rtv beograd",
    "tv titograd", "rtv sarajevo", "rtv ljubljana", "yugoslavia", "jugoslavija",
    "jugoslovenski", "jugoslavenski", "hrvatski film", "srpski film",
    "domaci film", "domaći film", "ex-yu", "ex yu", "baltazar", "balthazar",
    "šegrt hlapić", "segrt hlapic", "čudesna šuma", "cudesna suma",
    "čarobnjakov šešir", "carobnjakov sesir", "surogat"
)

_STOP_WORDS = {"the", "a", "an", "of", "and", "in", "on", "to", "for", "part", "ii", "iii", "iv", "i", "s", "full", "movie", "512kb", "1080p", "720p", "480p", "mp4", "mkv", "ia", "xvid", "divx", "1cd", "2cd", "unknown", "bluray", "bdrip", "webrip", "dvdrip"}
_SHORT_NUM_TOKENS = {"8", "9", "12"}


def _tokenize_words(text):
    if not text:
        return set()
    decoded = unquote(str(text)).replace("½", " 1 2 ").replace("¼", " 1 4 ").replace("¾", " 3 4 ")
    spaced = re.sub(r"([a-z])([A-Z])", r"\1 \2", decoded)
    spaced = re.sub(r"([a-zA-Z])(\d)", r"\1 \2", spaced)
    cleaned = re.sub(r"[\W_]+", " ", spaced.lower()).strip()
    tokens = [w for w in cleaned.split() if w not in _STOP_WORDS]
    long_tokens = {w for w in tokens if len(w) >= 3 or w in _SHORT_NUM_TOKENS}
    if long_tokens:
        return long_tokens
    return {w for w in tokens if len(w) >= 2}


def _word_prefixes(words):
    return {w[:4] for w in words if len(w) >= 4} | {w for w in words if len(w) < 4}


def extract_clean_film_year_from_filename(text):
    if not text:
        return 0
    s = unquote(str(text))
    s = re.sub(r"\b20\d{2}[-_]\d{2}(?:[-_]\d{2})?\b", " ", s)
    s = re.sub(r"\b20\d{6}\b", " ", s)
    s = re.sub(r"_20\d{4,6}\b", " ", s)
    s = re.sub(r"(?i)\b(?:1080p|720p|480p|2160p|x264|x265|h264|h265)\b", " ", s)
    m = re.search(r"(?:^|[\s._(\[-])(19\d{2}|20\d{2})(?:$|[\s._)\]-])", s)
    return int(m.group(1)) if m else 0


def is_explicit_junk_item(ident, raw_title, extra_text=""):
    blob = f"{ident} {raw_title} {extra_text}"
    if EX_YU_DETECTION_KEYWORDS and any(kw in blob.lower() for kw in EX_YU_DETECTION_KEYWORDS):
        return False
    if STRICT_EPISODE_RE.search(blob) or TV_NEWS_OR_SPORTS_ID_RE.search(str(ident or "")) or TV_NEWS_OR_SPORTS_ID_RE.search(str(raw_title or "")):
        return True
    return bool(EXPLICIT_JUNK_RE.search(blob))


def is_runtime_mismatch(tmdb_mins, arch_mins, is_short_allowed=False, size_mb=0):
    t_mins = int(tmdb_mins or 0)
    a_mins = int(arch_mins or 0)
    s_mb = float(size_mb or 0)

    if a_mins <= 0:
        if not is_short_allowed and t_mins >= 75 and 0 < s_mb < 350:
            return True
        return False

    if t_mins >= 65:
        min_ok = int(t_mins * 0.72)
        max_ok = int(t_mins * 1.30)
        return not (min_ok <= a_mins <= max_ok)
    if not is_short_allowed and a_mins < 48:
        return True
    return False


def verify_multilayer_title_and_file(chosen_filename, ident, arch_md, expected_titles, rel_year=0, details=None):
    arch_title = str((arch_md or {}).get("title") or "")
    arch_creator = str((arch_md or {}).get("creator") or "")
    arch_desc = str((arch_md or {}).get("description") or "")

    if rel_year > 0:
        file_y = extract_clean_film_year_from_filename(chosen_filename or "")
        title_y = extract_clean_film_year_from_filename(arch_title)
        explicit_y = file_y or title_y
        if explicit_y > 0 and abs(explicit_y - rel_year) > 1:
            return False

    if STRICT_EPISODE_RE.search(f"{arch_title} {chosen_filename or ''}"):
        return False

    expected_words = set()
    for t in expected_titles:
        expected_words |= _tokenize_words(t)
    expected_prefixes = _word_prefixes(expected_words)

    arch_meta_words = _tokenize_words(f"{arch_title} {ident}")
    arch_meta_prefixes = _word_prefixes(arch_meta_words)
    if expected_prefixes and arch_meta_prefixes and not (expected_prefixes & arch_meta_prefixes):
        desc_words = _tokenize_words(f"{arch_desc} {arch_creator}")
        desc_prefixes = _word_prefixes(desc_words)
        year_ok = (rel_year > 0 and str(rel_year) in f"{arch_title} {chosen_filename or ''}")
        if not (year_ok and (expected_prefixes & desc_prefixes)):
            return False

    if not chosen_filename:
        return True

    stem = unquote(os.path.splitext(os.path.basename(chosen_filename))[0]).strip()
    stem_tokens = _tokenize_words(stem)

    if not stem_tokens or GENERIC_CAMERA_OR_RIP_FILENAME_RE.match(stem):
        if not expected_prefixes or not (expected_prefixes & arch_meta_prefixes):
            return False
        return True

    shortest_expected = min((_tokenize_words(t) for t in expected_titles if _tokenize_words(t)), key=len, default=set())
    if len(shortest_expected) == 1 and len(stem_tokens) >= 4:
        credit_blob = f"{arch_desc} {arch_creator}".lower()
        credits_ok = False
        if details:
            for person in (details.get("directors") or []) + (details.get("cast") or [])[:4]:
                surname = str(person).strip().split()[-1].lower() if str(person).strip() else ""
                if len(surname) >= 4 and surname in credit_blob:
                    credits_ok = True
                    break
        if not credits_ok:
            return False

    ref_words = expected_words | _tokenize_words(f"{ident} {arch_title}")
    ref_prefixes = _word_prefixes(ref_words)
    if not ref_prefixes:
        return True

    stem_prefixes = _word_prefixes(stem_tokens)
    return bool(stem_prefixes & ref_prefixes)


def is_mismatched_modern_dune(tmdb_id, *texts):
    if int(tmdb_id or 0) not in (438631, 693134):
        return False
    blob = " ".join(str(t) for t in texts if t)
    return bool(DUNE_1984_MARKER_RE.search(blob))


def estimate_source_quality_tier(ident, stream_url="", title=""):
    blob = f"{ident} {stream_url} {title}"
    return compute_aspect_tier(blob)


def is_special_or_remastered_edition(ident, stream_url="", title=""):
    blob = f"{ident} {stream_url} {title}"
    return bool(EXTENDED_CUT_TAG_RE.search(blob) or SPECIAL_EDITION_RE.search(blob))


def build_movie_version_maps(all_movie_rows):
    version_counts = {}
    best_tiers = {}
    for row in all_movie_rows:
        if not row.get("is_valid", True):
            continue
        mid = int(row.get("tmdb_id") or 0)
        if mid <= 0:
            continue
        version_counts[mid] = version_counts.get(mid, 0) + 1
        tier = estimate_source_quality_tier(
            row.get("archive_id") or "",
            row.get("stream_url") or "",
            row.get("title") or ""
        )
        if tier > best_tiers.get(mid, -1):
            best_tiers[mid] = tier
    return version_counts, best_tiers


def can_accept_movie_candidate(mid, tmdb_version_counts, tmdb_best_tier, candidate_tier=None, is_special=False):
    if mid <= 0:
        return True
    count = tmdb_version_counts.get(mid, 0)
    if count == 0:
        return True
    current_best = tmdb_best_tier.get(mid, 1)
    if current_best == 0:
        if candidate_tier is None:
            return True
        return candidate_tier > 0 or is_special
    if count >= MAX_VERSIONS_PER_MOVIE:
        return False
    if candidate_tier is None:
        return False
    return (candidate_tier > current_best) or is_special


def retire_vhs_rows_for_tmdb(mid, new_arch_id, rel_year, all_movie_rows, tmdb_version_counts):
    if mid <= 0 or (rel_year > 0 and rel_year < 1955):
        return 0
    rows_to_retire = []
    with state_lock:
        for row in all_movie_rows:
            if not row.get("is_valid", True):
                continue
            if int(row.get("tmdb_id") or 0) != mid:
                continue
            r_ident = row.get("archive_id") or ""
            if not r_ident or r_ident == new_arch_id:
                continue
            base_ident = r_ident.split(",tmdb-")[0].strip()
            if base_ident in CURATED_ARCHIVE_IDS:
                continue
            r_tier = estimate_source_quality_tier(r_ident, row.get("stream_url") or "", row.get("title") or "")
            if r_tier == 0:
                row["is_valid"] = False
                if tmdb_version_counts.get(mid, 0) > 0:
                    tmdb_version_counts[mid] -= 1
                rows_to_retire.append((row["id"], row.get("title") or "", r_ident))

    for row_id, r_title, r_ident in rows_to_retire:
        supabase_patch_row("catalog_items", row_id, {"is_valid": False})
        print(f"  🗑 Uklonjen stari VHS/4:3 rip nakon pronalaska bolje verzije: {r_title} ({r_ident})", flush=True)

    return len(rows_to_retire)


def check_ex_yu(*texts, orig_lang=""):
    if orig_lang and orig_lang.lower() in EX_YU_LANGS:
        return True
    blob = " ".join(str(t) for t in texts if t).lower()
    return any(kw in blob for kw in EX_YU_DETECTION_KEYWORDS)


def resolve_movie_genres(details, final_title, hint_genre=None):
    if _GENRES_ACCEPTS_ORIG_LANG:
        genres = determine_primary_genres(
            details["genre_ids"],
            details["companies"],
            details["orig_title"],
            final_title,
            details["overview_hr"],
            hint_genre,
            orig_lang=details.get("original_language", "")
        )
    else:
        genres = determine_primary_genres(
            details["genre_ids"],
            details["companies"],
            details["orig_title"],
            final_title,
            details["overview_hr"],
            hint_genre
        )
    orig_lang = details.get("original_language", "")
    is_ex_yu = (
        hint_genre in ("Ex-Yu filmovi", "Ex-Yu klasici", "Ex-Yu crtići")
        or check_ex_yu(details["orig_title"], final_title, " ".join(details["companies"]), orig_lang=orig_lang)
    )
    if is_ex_yu:
        is_anim = 16 in (details.get("genre_ids") or [])
        tag = "Ex-Yu crtići" if is_anim else "Ex-Yu filmovi"
        if tag not in genres:
            genres = [tag] + genres if not is_anim else genres + [tag]
    return genres[:3]


def fetch_child_collections(parent_col_id, rows=60):
    data = safe_get(
        "https://archive.org/advancedsearch.php",
        params={
            "q": f"collection:{parent_col_id} AND mediatype:collection",
            "fl[]": ["identifier", "title", "downloads"],
            "sort[]": "downloads desc",
            "rows": rows,
            "page": 1,
            "output": "json",
        },
        timeout=10,
    )
    children = []
    for doc in (data or {}).get("response", {}).get("docs", []):
        ident = (doc.get("identifier") or "").strip()
        if not ident or ident.lower() == parent_col_id.lower():
            continue
        raw_t = doc.get("title") or ident
        if isinstance(raw_t, list):
            raw_t = raw_t[0]
        children.append((ident, str(raw_t)))
    return children


def map_approved_movie_subtrees(deadline_ts):
    if time.monotonic() > deadline_ts:
        return []

    seen = {b.lower() for b in APPROVED_NODE_IDS}
    sub_layer = []

    with ThreadPoolExecutor(max_workers=4) as pool:
        for children in pool.map(lambda cid: fetch_child_collections(cid, rows=50), random.sample(APPROVED_NODE_IDS, k=min(12, len(APPROVED_NODE_IDS)))):
            for ident, title in children:
                low = ident.lower()
                if low in seen:
                    continue
                if EXCLUDED_SUBLAYER_RE.search(f"{ident} {title}"):
                    continue
                seen.add(low)
                sub_layer.append((ident, title))

    return sub_layer


EX_YU_ARCHIVE_QUERIES = [
    'mediatype:(movies OR web) AND language:(Croatian OR Serbian OR "Serbo-Croatian" OR Bosnian OR Slovenian OR Macedonian OR hrv OR srp OR scr OR bos OR slv OR mac) AND -subject:news AND -subject:trailer',
    'mediatype:(movies OR web) AND (subject:(Yugoslavia OR Yugoslav OR "Yugoslav cinema" OR "Zagreb Film" OR "Jadran Film" OR "Avala Film" OR "Centar Film" OR "Sutjeska Film" OR "Bosna Film" OR "Vardar Film" OR "Triglav Film" OR "Croatia Film" OR "ex-yu" OR "domaci film" OR "domaći film") OR creator:("Zagreb Film" OR "Jadran Film" OR "Avala Film" OR "Centar Film" OR "Sutjeska Film" OR "Bosna Film" OR "Vardar Film" OR "Triglav Film" OR "Croatia Film")) AND -subject:news AND -subject:trailer',
]

EX_YU_MOVIE_SEEDS = [
    "Ko to tamo peva", "Maratonci trče počasni krug", "Balkanski špijun",
    "Dom za vešanje", "Otac na službenom putu", "Underground",
    "Crna mačka, beli mačor", "Sjećaš li se Dolly Bell?", "Bitka na Neretvi",
    "Valter brani Sarajevo", "Sutjeska", "Kozara", "Kapetan Leši", "Most",
    "Otpisani", "Povratak otpisanih", "Tko pjeva zlo ne misli", "Rondo",
    "Breza", "H-8", "Vlak u snijegu", "Družba Pere Kvržice", "Vuk samotnjak",
    "Kapetan Mikula Mali", "Glembajevi", "Kiklop", "Samo jednom se ljubi",
    "U raljama života", "Štefica Cvek u raljama života", "Kako je počeo rat na mom otoku",
    "Maršal", "Karaula", "Metastaze", "Ljudožder vegetarijanac", "Ničija zemlja",
    "Gori vatra", "Grbavica", "Lepota poroka", "Čudo neviđeno", "U ime naroda",
    "Davitelj protiv davitelja", "Nacionalna klasa", "Specijalno vaspitanje",
    "Varljivo leto '68", "Sabirni centar", "Mi nismo anđeli", "Lepa sela lepo gore",
    "Rane", "Munje!", "Kad porastem biću Kengur", "Tesna koža", "Žikina dinastija",
    "Lude godine", "Majstori, majstori", "Čovjek koga treba ubiti", "Izgubljeni zavičaj",
    "Banović Strahinja", "Okupacija u 26 slika", "Pad Italije", "Večernja zvona",
    "Kuduz", "Praznik u Sarajevu", "Gluvi barut", "Hajde da se volimo",
    "Čudesna šuma", "Čarobnjakov šešir", "Čudnovate zgode šegrta Hlapića",
    "Gosti iz galaksije", "Izbavitelj", "Ritam zločina", "Surogat",
    "Gastarbajteri", "Lažni car", "Zelena čoha", "Profesionalac"
]

TMDB_DISCOVER_GENRE_SEEDS = [
    ("37", "Vestern", 25),
    ("27", "Horor", 35),
    ("10752", "Ratni", 25),
    ("878", "Sci-Fi klasici", 35),
    ("80", "Krimi", 40),
    ("53", "Triler i misterija", 40),
    ("9648", "Triler i misterija", 35),
    ("35", "Komedija", 45),
    ("10749", "Romantični", 30),
    ("28", "Akcija i avantura", 40),
    ("12", "Akcija i avantura", 40),
    ("18", "Drama", 50),
]

CULT_AND_ANIMATION_SEEDS = [
    "Dune: Part One", "Dune: Part Two", "Star Wars: Episode IV - A New Hope",
    "The Empire Strikes Back", "Return of the Jedi",
    "2001: A Space Odyssey", "2010: The Year We Make Contact", "Hair", "Footloose",
    "Dirty Dancing", "Grease", "Saturday Night Fever", "Flashdance", "Fame",
    "The Blues Brothers", "The Rocky Horror Picture Show", "West Side Story",
    "Singin' in the Rain", "The Sound of Music", "Cabaret", "All That Jazz",
    "Blade Runner", "Alien", "Aliens", "The Terminator", "Terminator 2: Judgment Day",
    "Back to the Future", "Back to the Future Part II", "Back to the Future Part III",
    "Raiders of the Lost Ark", "Indiana Jones and the Temple of Doom",
    "Indiana Jones and the Last Crusade", "The Matrix", "Jurassic Park",
    "E.T. the Extra-Terrestrial", "Close Encounters of the Third Kind",
    "A Clockwork Orange", "The Shining", "Full Metal Jacket", "Dr. Strangelove",
    "Barry Lyndon", "Eyes Wide Shut", "Apocalypse Now", "The Godfather",
    "The Godfather Part II", "Goodfellas", "Taxi Driver", "Raging Bull", "Casino",
    "Pulp Fiction", "Reservoir Dogs", "Jackie Brown", "Scarface", "Carlito's Way",
    "Heat", "The Shawshank Redemption", "Forrest Gump", "Schindler's List",
    "Fight Club", "Se7en", "The Silence of the Lambs", "Gladiator", "Braveheart",
    "Ghostbusters", "The Fifth Element", "Total Recall", "RoboCop", "Starship Troopers",
    "Mad Max", "Mad Max 2", "Stalker", "Solaris", "Metropolis", "Brazil",
    "Twelve Monkeys", "The Thing", "Escape from New York", "They Live",
    "Big Trouble in Little China", "The Abyss", "Predator", "Die Hard",
    "Lethal Weapon", "First Blood", "Rocky", "Rocky II", "Platoon", "The Deer Hunter",
    "One Flew Over the Cuckoo's Nest", "Amadeus", "Chinatown", "The French Connection",
    "Lawrence of Arabia", "The Bridge on the River Kwai", "Doctor Zhivago",
    "Ben-Hur", "Spartacus", "Once Upon a Time in the West", "The Good, the Bad and the Ugly",
    "A Fistful of Dollars", "For a Few Dollars More", "Unforgiven", "True Grit",
    "Spider-Man: Into the Spider-Verse", "Spider-Man: Across the Spider-Verse",
    "Snow White and the Seven Dwarfs", "Pinocchio", "Fantasia", "Dumbo", "Bambi",
    "Cinderella", "Alice in Wonderland", "Peter Pan", "Lady and the Tramp",
    "Sleeping Beauty", "One Hundred and One Dalmatians", "The Sword in the Stone",
    "The Jungle Book", "The Aristocats", "Robin Hood", "The Rescuers",
    "The Fox and the Hound", "The Great Mouse Detective", "Oliver & Company",
    "The Little Mermaid", "Beauty and the Beast", "Aladdin", "The Lion King",
    "Pocahontas", "The Hunchback of Notre Dame", "Hercules", "Mulan", "Tarzan",
    "The Emperor's New Groove", "Atlantis: The Lost Empire", "Lilo & Stitch",
    "Treasure Planet", "Brother Bear", "Tangled", "Wreck-It Ralph", "Frozen",
    "Big Hero 6", "Zootopia", "Moana", "Encanto", "The Nightmare Before Christmas",
    "Who Framed Roger Rabbit", "Mary Poppins", "Toy Story", "A Bug's Life",
    "Toy Story 2", "Monsters, Inc.", "Finding Nemo", "The Incredibles", "Cars",
    "Ratatouille", "WALL-E", "Up", "Toy Story 3", "Brave", "Inside Out",
    "Finding Dory", "Coco", "Incredibles 2", "Toy Story 4", "Soul", "Luca",
    "Spirited Away", "My Neighbor Totoro", "Princess Mononoke", "Howl's Moving Castle",
    "Kiki's Delivery Service", "Castle in the Sky", "Ponyo", "Grave of the Fireflies",
    "Shrek", "Shrek 2", "Madagascar", "Kung Fu Panda", "How to Train Your Dragon",
    "Ice Age", "Despicable Me", "The Iron Giant", "Coraline", "Corpse Bride",
    "Anastasia", "The Prince of Egypt", "The Land Before Time", "An American Tail",
    "All Dogs Go to Heaven", "The Secret of NIMH", "Akira", "Ghost in the Shell",
    "Watership Down", "Animal Farm", "Heavy Metal"
]

CURATED_MOVIE_EXCEPTIONS = [
    {
        "archive_query": "identifier:dune-david-lynch-1984",
        "tmdb_id": 841,
        "custom_title": "",
        "min_mins": 115,
    },
    {
        "archive_query": "identifier:dune-1984-alternative-edition-redux-fanedit-m*",
        "tmdb_id": 841,
        "custom_title": "Dina - Alternative Edition Redux",
        "min_mins": 150,
    },
    {
        "archive_query": "identifier:DuneTheCompleteSagaVimeo",
        "tmdb_id": 841,
        "custom_title": "Dina - The Complete Saga",
        "min_mins": 150,
    },
]

CURATED_ARCHIVE_IDS = {
    "dune-david-lynch-1984",
    "DuneTheCompleteSagaVimeo",
}


def run_batched_with_deadline(items, worker_fn, deadline_ts, max_workers=6, batch_size=25):
    item_list = list(items)
    for start_idx in range(0, len(item_list), batch_size):
        if time.monotonic() > deadline_ts:
            return True
        chunk = item_list[start_idx:start_idx + batch_size]
        with ThreadPoolExecutor(max_workers=max_workers) as pool:
            futures = [pool.submit(worker_fn, x) for x in chunk]
            for fut in as_completed(futures):
                try:
                    fut.result()
                except Exception:
                    pass
    return time.monotonic() > deadline_ts


def extract_archive_title_candidates(raw_title, ident):
    raw_str = str(raw_title or "").strip()
    year_in_title = extract_clean_film_year_from_filename(raw_str) or extract_clean_film_year_from_filename(ident)

    trimmed_sep = re.split(r"\s+(?:-|–|—|\||/)\s+", raw_str, maxsplit=1)[0].strip()
    trimmed_credit = re.split(
        r"(?i)\s+(?:directed\s+by|dir\.?|starring|feat\.?|with)\b",
        trimmed_sep,
        maxsplit=1
    )[0].strip()

    clean_ident_str = re.sub(r"(?i)^popcornarchive[-_]+", "", ident)
    clean_ident_str = re.sub(r"([a-z])([A-Z])", r"\1 \2", clean_ident_str).replace("-", " ").replace("_", " ")

    candidates = []
    for variant in (trimmed_credit, trimmed_sep, raw_str, clean_ident_str):
        cleaned, y = clean_filename_to_title(variant)
        if cleaned and len(cleaned) >= 2 and cleaned not in candidates:
            candidates.append(cleaned)
        if not year_in_title and y:
            year_in_title = y

    return candidates, year_in_title


def sync_curated_movie_exceptions(
    all_movie_rows,
    existing_archive_ids,
    seen_tmdb_ids,
    seen_title_years,
    tmdb_version_counts,
    tmdb_best_tier
):
    valid_archive_ids = {
        r.get("archive_id") for r in all_movie_rows
        if r.get("is_valid", True) and r.get("archive_id")
    }
    curated_tmdb_to_mark = set()

    for item in CURATED_MOVIE_EXCEPTIONS:
        data = safe_get(
            "https://archive.org/advancedsearch.php",
            params={
                "q": item["archive_query"],
                "fl[]": ["identifier", "title", "downloads"],
                "rows": 3,
                "output": "json"
            }
        )
        docs = (data or {}).get("response", {}).get("docs", [])
        if not docs:
            continue

        ident = docs[0].get("identifier") or ""
        if not ident or ident in valid_archive_ids:
            continue

        details = get_tmdb_movie_details(item["tmdb_id"])
        if not details:
            continue

        videos, _ = get_archive_item_details(ident, min_size_mb=250, allow_mkv=True)
        min_mins = item.get("min_mins", 115)
        long_videos = [
            v for v in videos
            if v.get("duration_mins", 0) >= min_mins
            or (v.get("duration_mins", 0) == 0 and v.get("size_mb", 0) >= 900)
        ]
        if not long_videos:
            continue

        best = pick_best_video_file(long_videos)
        if not verify_stream_url_playable(best["stream_url"]):
            continue

        final_title = item["custom_title"].strip() or details["hr_title"]
        rel_year = extract_year_from_text(details["release_date"]) or 1984
        genres = resolve_movie_genres(details, final_title, "Sci-Fi klasici")
        mid = item["tmdb_id"]
        row = {
            "archive_id": ident,
            "tmdb_id": mid,
            "title": final_title,
            "media_type": "movie",
            "genres": genres,
            "release_year": rel_year,
            "rating": round(details["vote_average"], 1),
            "popularity_score": float(details["popularity"]) * 100.0 + 3500.0,
            "description_hr": details["overview_hr"],
            "poster_url": f"{TMDB_IMG_POSTER}{details['poster_path']}" if details["poster_path"] else "",
            "backdrop_url": f"{TMDB_IMG_BACKDROP}{details['backdrop_path']}" if details["backdrop_path"] else "",
            "stream_url": best["stream_url"],
            "is_featured": False,
            "is_valid": True,
            "tmdb_runtime": details["runtime"],
            "archive_runtime": best["duration_mins"]
        }
        supabase_upsert("catalog_items", [row], "archive_id")
        existing_archive_ids.add(ident)
        valid_archive_ids.add(ident)
        curated_tmdb_to_mark.add(mid)
        tmdb_version_counts[mid] = tmdb_version_counts.get(mid, 0) + 1
        tier = estimate_source_quality_tier(ident, best["stream_url"], final_title)
        if tier > tmdb_best_tier.get(mid, -1):
            tmdb_best_tier[mid] = tier
        norm_t = normalize_for_exact_title_compare(final_title)
        if norm_t:
            seen_title_years.add((norm_t, rel_year))
        print(f"  + Odobreno izdanje: {row['title']} ({best['duration_mins']} min) -> {', '.join(genres)}", flush=True)

    seen_tmdb_ids.update(curated_tmdb_to_mark)


def is_episodic_or_multi_movie_container(videos):
    if not videos:
        return False
    ep_hits = 0
    distinct_stems = set()
    for v in videos:
        fn = v.get("filename") or ""
        if STRICT_EPISODE_RE.search(fn):
            ep_hits += 1
        stem = re.sub(r"(?i)\.ia$", "", os.path.splitext(os.path.basename(fn))[0]).lower()
        if stem:
            distinct_stems.add(stem)
    return ep_hits >= 2 or len(distinct_stems) > 4


def find_best_archive_movie_match(details, year, exclude_ids, deadline_ts=None, min_aspect_tier=0):
    if deadline_ts is not None and time.monotonic() > deadline_ts:
        return None

    orig_title = details["orig_title"]
    hr_title = details["hr_title"]
    en_title = details.get("en_title") or orig_title
    orig_lang = details.get("original_language", "")
    is_ex_yu = orig_lang in EX_YU_LANGS
    is_doc = 99 in (details.get("genre_ids") or [])
    mid = int(details.get("tmdb_id") or 0)

    search_terms = []
    for t in (orig_title, hr_title, en_title):
        clean_q = re.sub(r"[^\w\s]", " ", str(t)).strip()
        if len(clean_q) >= 2 and clean_q not in search_terms:
            search_terms.append(clean_q)
    if not search_terms:
        return None

    is_anim = 16 in (details.get("genre_ids") or [])
    min_mb = 80 if is_anim else (130 if is_ex_yu else 180)
    require_credits = has_multiple_tmdb_versions(orig_title)
    year_clause = f" AND year:[{year - 1} TO {year + 1}]" if year > 0 else ""

    docs_by_id = {}
    for term in search_terms[:2]:
        if deadline_ts is not None and time.monotonic() > deadline_ts:
            return None
        query = (
            f'title:"{term}"{year_clause} AND mediatype:(movies OR web) '
            f'AND -subject:trailer AND -subject:news '
            f'AND -subject:"video games" AND -subject:gameplay '
            f'AND -title:"holiday special" AND -title:"making of" '
            f'AND -title:commentary AND -title:walkthrough AND -title:longplay AND -title:gameplay'
        )
        data = safe_get(
            "https://archive.org/advancedsearch.php",
            params={
                "q": query,
                "fl[]": ["identifier", "title", "downloads"],
                "sort[]": "downloads desc",
                "rows": 4,
                "output": "json"
            }
        )
        for doc in (data or {}).get("response", {}).get("docs", []):
            ident = doc.get("identifier")
            if ident and ident not in docs_by_id:
                docs_by_id[ident] = doc

    valid_candidates = []
    expected_title_list = list({orig_title, hr_title, en_title})
    for ident, doc in docs_by_id.items():
        if deadline_ts is not None and time.monotonic() > deadline_ts:
            break
        if not ident or ident in exclude_ids or ident.lower().startswith(("youtube-", "yt-")):
            continue
        raw_doc_title = doc.get("title") or ident
        if isinstance(raw_doc_title, list):
            raw_doc_title = raw_doc_title[0]
        if is_mismatched_modern_dune(mid, ident, raw_doc_title):
            continue
        if not is_doc and is_explicit_junk_item(ident, raw_doc_title):
            continue

        videos, arch_md = get_archive_item_details(ident, min_size_mb=min_mb, allow_mkv=True)
        if not videos or is_episodic_or_multi_movie_container(videos):
            continue
        best = pick_best_video_file(videos)
        arch_mins = best["duration_mins"]
        if is_mismatched_modern_dune(mid, ident, raw_doc_title, best["filename"], arch_md.get("title")):
            continue
        aspect_tier = max(
            best.get("aspect_tier", 1),
            estimate_source_quality_tier(ident, best["stream_url"], str(raw_doc_title))
        )
        if aspect_tier < min_aspect_tier:
            continue

        if not is_doc and is_explicit_junk_item(ident, arch_md.get("title") or raw_doc_title, best["filename"]):
            continue

        if is_runtime_mismatch(details.get("runtime", 0), arch_mins, is_short_allowed=is_doc, size_mb=best["size_mb"]):
            continue

        if not verify_multilayer_title_and_file(best["filename"], ident, arch_md, expected_title_list, year, details=details):
            continue

        ok, _ = validate_movie_match(
            ident,
            arch_md,
            arch_mins,
            details,
            expected_title_list,
            year,
            require_credits=require_credits,
            chosen_filename=best["filename"],
            chosen_size_mb=best["size_mb"]
        )
        if ok and verify_stream_url_playable(best["stream_url"]):
            downloads = float(doc.get("downloads") or 0)
            valid_candidates.append((aspect_tier, best["size_mb"], downloads, ident, best, arch_mins))
            if year < 1955 or aspect_tier == 2:
                break

    if not valid_candidates:
        return None

    if year >= 1955:
        valid_candidates.sort(key=lambda x: (-x[0], -x[1], -x[2]))
    else:
        valid_candidates.sort(key=lambda x: (-x[2], -x[1]))

    _, _, _, best_ident, best_file, best_mins = valid_candidates[0]
    return best_ident, best_file, best_mins


def try_rematch_row_by_filename_and_year(row, expected_year):
    ident = row.get("archive_id") or ""
    stream_url = row.get("stream_url") or ""
    raw_stem = unquote(os.path.splitext(os.path.basename(stream_url))[0]) if stream_url else ident
    clean_q, parsed_y = clean_filename_to_title(raw_stem)
    if not clean_q or len(clean_q) < 2:
        clean_q, _ = clean_filename_to_title(ident.split(",tmdb-")[0])
    target_y = expected_year or parsed_y
    if not clean_q or target_y <= 0:
        return None

    for lang_code in ("hr-HR", "en-US"):
        res = safe_get(
            f"{TMDB_BASE}/search/movie",
            params={
                "api_key": TMDB_API_KEY,
                "query": clean_q,
                "year": target_y,
                "language": lang_code,
                "include_adult": "false",
            },
        )
        hits = (res or {}).get("results", [])
        for h in hits[:4]:
            hy = extract_year_from_text(h.get("release_date") or "")
            if hy > 0 and abs(hy - target_y) <= 1:
                details = get_tmdb_movie_details(int(h["id"]))
                if details and details.get("poster_path"):
                    return details
    return None


def audit_and_repair_existing_movies(
    all_movie_rows,
    existing_archive_ids,
    seen_tmdb_ids,
    seen_title_years,
    tmdb_version_counts,
    tmdb_best_tier,
    phase_deadline_ts
):
    restored_count = 0
    for r in all_movie_rows:
        if r.get("is_valid", True):
            continue
        mid = int(r.get("tmdb_id") or 0)
        s_url = r.get("stream_url") or ""
        ident = r.get("archive_id") or ""
        title = r.get("title") or ""
        if mid > 0 and s_url.startswith("http"):
            if any(ident.startswith(p) for p in BAD_BUNDLE_PREFIXES) or BAD_TITLE_MARKER_RE.search(title) or BAD_TITLE_MARKER_RE.search(s_url):
                continue
            if TV_NEWS_OR_SPORTS_ID_RE.search(ident) or STRICT_EPISODE_RE.search(s_url):
                continue
            if re.search(r"(?i)\b(?:complete[\s._-]*series|season[\s._-]*\d+|s\d{1,2}e\d{1,3})\b", f"{ident} {title}"):
                continue
            if is_explicit_junk_item(ident, title, s_url):
                continue
            supabase_patch_row("catalog_items", r["id"], {"is_valid": True})
            r["is_valid"] = True
            seen_tmdb_ids.add(mid)
            tmdb_version_counts[mid] = tmdb_version_counts.get(mid, 0) + 1
            restored_count += 1

    if restored_count > 0:
        print(f"  ♻ Vraćeno {restored_count} pravih filmova u katalog koji su ranije bili pogrešno skriveni.", flush=True)

    valid_rows = [r for r in all_movie_rows if r.get("is_valid", True)]
    sample_rows = valid_rows[-120:]
    if len(valid_rows) > 120:
        older_pool = valid_rows[:-120]
        sample_rows = sample_rows + random.sample(older_pool, k=min(MAX_AUDIT_SAMPLE_PER_RUN - 120, len(older_pool)))

    print(f"1/4 Brza inkrementalna provjera {len(sample_rows)}/{len(valid_rows)} filmova u bazi...", flush=True)
    replaced_count = [0]
    rematched_count = [0]
    removed_count = [0]
    translated_count = [0]
    ex_yu_tagged_count = [0]
    hd_upgraded_count = [0]

    def mark_row_invalid(row, tmdb_id, reason_msg):
        supabase_patch_row("catalog_items", row["id"], {"is_valid": False})
        row["is_valid"] = False
        with state_lock:
            if tmdb_id > 0:
                seen_tmdb_ids.discard(tmdb_id)
                if tmdb_version_counts.get(tmdb_id, 0) > 0:
                    tmdb_version_counts[tmdb_id] -= 1
            removed_count[0] += 1
        print(reason_msg, flush=True)

    def inspect_movie_row(row):
        if time.monotonic() > phase_deadline_ts:
            return
        row_id = row["id"]
        ident = row.get("archive_id") or ""
        title = row.get("title") or ""
        stream_url = row.get("stream_url") or ""
        tmdb_id = int(row.get("tmdb_id") or 0)
        rel_year = int(row.get("release_year") or 0)
        tmdb_rt = int(row.get("tmdb_runtime") or 0)
        arch_rt = int(row.get("archive_runtime") or 0)
        existing_genres = list(row.get("genres") or [])
        is_author_promoted = ",tmdb-" in ident
        base_ident = ident.split(",tmdb-")[0].strip()

        if any(ident.startswith(p) for p in BAD_BUNDLE_PREFIXES) or BAD_TITLE_MARKER_RE.search(title) or BAD_TITLE_MARKER_RE.search(stream_url):
            mark_row_invalid(row, tmdb_id, f"  ✕ Uklonjen ne-filmski zapis: {title} ({ident})")
            return

        is_series_marker = bool(
            STRICT_EPISODE_RE.search(stream_url)
            or re.search(r"(?i)\b(?:complete[\s._-]*series|season[\s._-]*\d+|s\d{1,2}e\d{1,3})\b", f"{base_ident} {title}")
        )
        if TV_NEWS_OR_SPORTS_ID_RE.search(base_ident) or is_series_marker:
            if is_series_marker and not TV_NEWS_OR_SPORTS_ID_RE.search(base_ident):
                queue_series_candidate_for_tv_sync(base_ident, title)
                mark_row_invalid(row, tmdb_id, f"  ✕ Uklonjena serija iz filmova (proslijeđeno u serije): {title} ({ident})")
            else:
                mark_row_invalid(row, tmdb_id, f"  ✕ Uklonjena TV emisija/sport iz filmova: {title} ({ident})")
            return

        if rel_year > 0 and stream_url:
            clean_file_y = extract_clean_film_year_from_filename(os.path.basename(stream_url)) or extract_clean_film_year_from_filename(base_ident)
            if clean_file_y > 0 and abs(clean_file_y - rel_year) > 1:
                new_details = try_rematch_row_by_filename_and_year(row, clean_file_y)
                if new_details:
                    new_mid = int(new_details["tmdb_id"])
                    new_year = extract_year_from_text(new_details["release_date"]) or clean_file_y
                    new_genres = resolve_movie_genres(new_details, new_details["hr_title"])
                    supabase_patch_row(
                        "catalog_items",
                        row_id,
                        {
                            "tmdb_id": new_mid,
                            "title": new_details["hr_title"],
                            "release_year": new_year,
                            "genres": new_genres,
                            "rating": round(new_details["vote_average"], 1),
                            "description_hr": new_details["overview_hr"],
                            "poster_url": f"{TMDB_IMG_POSTER}{new_details['poster_path']}",
                            "backdrop_url": f"{TMDB_IMG_BACKDROP}{new_details['backdrop_path']}" if new_details["backdrop_path"] else "",
                            "tmdb_runtime": new_details["runtime"],
                        },
                    )
                    with state_lock:
                        seen_tmdb_ids.add(new_mid)
                        rematched_count[0] += 1
                    print(
                        f"  🔧 Ispravljen film na pravi naslov i godinu: {title} ({rel_year}) -> {new_details['hr_title']} ({new_year})",
                        flush=True,
                    )
                    return

        is_cartoon_row = any(
            any(k in g.lower() for k in ("animiran", "crtić", "crtic", "cartoon", "disney", "looney"))
            for g in existing_genres
        )
        if tmdb_id <= 0 and is_cartoon_row:
            return

        is_ex_yu_row = check_ex_yu(title, ident, row.get("description_hr")) or any(
            g in ("Ex-Yu filmovi", "Ex-Yu klasici", "Ex-Yu crtići") for g in existing_genres
        )
        if is_ex_yu_row and not any(
            g in ("Ex-Yu filmovi", "Ex-Yu klasici", "Ex-Yu crtići") for g in existing_genres
        ):
            tag_to_add = "Ex-Yu crtići" if is_cartoon_row else "Ex-Yu filmovi"
            supabase_patch_row("catalog_items", row_id, {"genres": existing_genres + [tag_to_add]})
            with state_lock:
                ex_yu_tagged_count[0] += 1

        is_curated = base_ident in CURATED_ARCHIVE_IDS or "alternative-edition-redux" in base_ident.lower()
        is_doc_row = any("dokumentar" in g.lower() for g in existing_genres)

        if tmdb_id <= 0 and not is_ex_yu_row and not is_curated:
            mark_row_invalid(row, tmdb_id, f"  ✕ Uklonjen nepoznat zapis bez TMDB-a: {title} ({base_ident})")
            return

        if not is_curated:
            if is_explicit_junk_item(base_ident, "", stream_url):
                mark_row_invalid(row, tmdb_id, f"  ✕ Uklonjen gameplay/commentary/junk zapis iz baze: {title} ({base_ident})")
                return
            if is_runtime_mismatch(tmdb_rt, arch_rt, is_short_allowed=(is_doc_row or is_cartoon_row)):
                mark_row_invalid(
                    row,
                    tmdb_id,
                    f"  ✕ Uklonjen zapis zbog nesklada trajanja ({arch_rt} min vs TMDB {tmdb_rt} min): {title} ({base_ident})"
                )
                return

        if is_mismatched_modern_dune(tmdb_id, base_ident, stream_url, title):
            mark_row_invalid(row, tmdb_id, f"  ✕ Uklonjen pogrešan upar Dine (1984. pod 2021./2024.): {title} ({base_ident})")
            return

        has_non_latin_title = bool(NON_LATIN_TITLE_RE.search(title))
        looks_blocked_text = contains_blocked_regional_or_arabic(title, ident, stream_url)
        suspect_dub = is_foreign_dub_without_english({}, base_ident, stream_url, orig_lang="en")

        if not has_non_latin_title and not looks_blocked_text and not suspect_dub:
            return

        arch_md = {}
        if not is_author_promoted and base_ident:
            md_res = safe_get(f"https://archive.org/metadata/{base_ident}/metadata", timeout=8)
            arch_md = (md_res or {}).get("result") or {}

        details = get_tmdb_movie_details(tmdb_id) if tmdb_id > 0 else None
        if not details:
            if looks_blocked_text or is_foreign_dub_without_english(arch_md, base_ident, stream_url, orig_lang=""):
                mark_row_invalid(row, tmdb_id, f"  ✕ Uklonjena strana sinkronizacija: {title}")
            return

        orig_lang = details.get("original_language", "")
        if contains_blocked_regional_or_arabic(
            details["orig_title"], details["hr_title"], title, orig_lang=orig_lang
        ):
            mark_row_invalid(row, tmdb_id, f"  ✕ Uklonjen blokirani jezik ({orig_lang}): {title}")
            return

        actual_dub = is_foreign_dub_without_english(arch_md, base_ident, stream_url, orig_lang=orig_lang)
        if actual_dub:
            final_year = rel_year or extract_year_from_text(details["release_date"])
            with state_lock:
                exclude_snapshot = set(existing_archive_ids)
            replacement = find_best_archive_movie_match(details, final_year, exclude_snapshot, phase_deadline_ts)
            if replacement:
                new_ident, new_file, new_mins = replacement
                can_use_new_id = False
                with state_lock:
                    if new_ident not in existing_archive_ids:
                        existing_archive_ids.add(new_ident)
                        can_use_new_id = True
                if can_use_new_id:
                    supabase_patch_row(
                        "catalog_items",
                        row_id,
                        {
                            "archive_id": new_ident,
                            "title": details["hr_title"],
                            "stream_url": new_file["stream_url"],
                            "archive_runtime": new_mins
                        }
                    )
                    new_tier = estimate_source_quality_tier(new_ident, new_file["stream_url"], details["hr_title"])
                    with state_lock:
                        if new_tier > tmdb_best_tier.get(tmdb_id, -1):
                            tmdb_best_tier[tmdb_id] = new_tier
                        replaced_count[0] += 1
                    print(f"  🔊 Zamijenjen strani audio originalnim: {details['hr_title']} ({final_year}) -> {new_ident}", flush=True)
                    return

            mark_row_invalid(row, tmdb_id, f"  ✕ Uklonjena strana sinkronizacija bez originala: {details['hr_title']} ({final_year})")
            return

        if has_non_latin_title and details["hr_title"] and not NON_LATIN_TITLE_RE.search(details["hr_title"]):
            supabase_patch_row(
                "catalog_items",
                row_id,
                {
                    "title": details["hr_title"],
                    "description_hr": details["overview_hr"]
                }
            )
            norm_t = normalize_for_exact_title_compare(details["hr_title"])
            if norm_t:
                with state_lock:
                    seen_title_years.add((norm_t, rel_year))
                    translated_count[0] += 1
            print(f"  🌐 Preveden naslov filma: {title} -> {details['hr_title']}", flush=True)

    run_batched_with_deadline(sample_rows, inspect_movie_row, phase_deadline_ts, max_workers=6, batch_size=50)

    for row in sample_rows:
        if not row.get("is_valid", True):
            continue
        mid = int(row.get("tmdb_id") or 0)
        rel_year = int(row.get("release_year") or 0)
        if mid > 0 and rel_year >= 1955 and tmdb_best_tier.get(mid, 0) > 0:
            r_tier = estimate_source_quality_tier(row.get("archive_id") or "", row.get("stream_url") or "", row.get("title") or "")
            if r_tier == 0:
                retire_vhs_rows_for_tmdb(mid, "", rel_year, all_movie_rows, tmdb_version_counts)

    vhs_candidates = [
        r for r in sample_rows
        if r.get("is_valid", True)
        and int(r.get("tmdb_id") or 0) > 0
        and int(r.get("release_year") or 0) >= 1955
        and tmdb_best_tier.get(int(r["tmdb_id"]), 1) == 0
    ][:MAX_VHS_UPGRADE_CHECKS_PER_RUN]

    if vhs_candidates and time.monotonic() < phase_deadline_ts:
        print(f"  Traženje bolje verzije (umjesto VHS/4:3 ripa) za {len(vhs_candidates)} filmova...", flush=True)

        def try_upgrade_vhs_row(row):
            if time.monotonic() > phase_deadline_ts:
                return
            mid = int(row.get("tmdb_id") or 0)
            rel_year = int(row.get("release_year") or 0)
            with state_lock:
                if not can_accept_movie_candidate(mid, tmdb_version_counts, tmdb_best_tier, candidate_tier=1):
                    return
                exclude_snapshot = set(existing_archive_ids)

            details = get_tmdb_movie_details(mid)
            if not details or not details["poster_path"]:
                return

            match = find_best_archive_movie_match(
                details,
                rel_year or extract_year_from_text(details["release_date"]),
                exclude_snapshot,
                phase_deadline_ts,
                min_aspect_tier=1
            )
            if not match:
                return

            new_ident, new_file, new_mins = match
            pop_score = float(details["popularity"]) * 100.0 + (rel_year * 2) + 500.0
            if save_verified_movie_row(
                new_ident,
                details,
                rel_year,
                new_file["stream_url"],
                new_mins,
                pop_score,
                seen_tmdb_ids,
                seen_title_years,
                existing_archive_ids,
                tmdb_version_counts,
                tmdb_best_tier,
                all_movie_rows=all_movie_rows,
                size_mb=new_file.get("size_mb", 0)
            ):
                with state_lock:
                    hd_upgraded_count[0] += 1

        run_batched_with_deadline(vhs_candidates, try_upgrade_vhs_row, phase_deadline_ts, max_workers=4, batch_size=10)

    print(
        f"  Provjera završena: {rematched_count[0]} ispravljeno na pravi film, "
        f"{replaced_count[0]} zamijenjeno originalom, "
        f"{hd_upgraded_count[0]} zamijenjeno boljom kopijom umjesto VHS-a, "
        f"{removed_count[0]} uklonjeno, {translated_count[0]} prevedeno, "
        f"{ex_yu_tagged_count[0]} označeno kao Ex-Yu.",
        flush=True
    )


def save_verified_movie_row(
    arch_id,
    details,
    rel_year,
    stream_url,
    arch_mins,
    pop_score,
    seen_tmdb_ids,
    seen_title_years,
    existing_archive_ids,
    tmdb_version_counts,
    tmdb_best_tier,
    hint_genre=None,
    all_movie_rows=None,
    size_mb=0
):
    mid = int(details["tmdb_id"])
    if is_mismatched_modern_dune(mid, arch_id, stream_url, details["hr_title"]):
        return False
    if is_explicit_junk_item(arch_id, details["hr_title"], stream_url):
        return False
    is_doc = 99 in (details.get("genre_ids") or [])
    if is_runtime_mismatch(details.get("runtime", 0), arch_mins, is_short_allowed=is_doc, size_mb=size_mb):
        return False

    norm_t = normalize_for_exact_title_compare(details["hr_title"])
    ty_key = (norm_t, rel_year)
    cand_tier = estimate_source_quality_tier(arch_id, stream_url, details["hr_title"])
    special_flag = is_special_or_remastered_edition(arch_id, stream_url, details["hr_title"])

    with state_lock:
        if arch_id in existing_archive_ids:
            return False
        if not can_accept_movie_candidate(mid, tmdb_version_counts, tmdb_best_tier, cand_tier, special_flag):
            return False
        had_only_vhs = (tmdb_best_tier.get(mid, -1) == 0 and cand_tier > 0)
        current_count = tmdb_version_counts.get(mid, 0)
        if current_count == 0 and norm_t and ty_key in seen_title_years:
            return False

        seen_tmdb_ids.add(mid)
        existing_archive_ids.add(arch_id)
        tmdb_version_counts[mid] = current_count + 1
        if cand_tier > tmdb_best_tier.get(mid, -1):
            tmdb_best_tier[mid] = cand_tier
        if norm_t:
            seen_title_years.add(ty_key)

    genres = resolve_movie_genres(details, details["hr_title"], hint_genre)
    adjusted_pop = pop_score + (400.0 if cand_tier == 2 else (-300.0 if cand_tier == 0 else 0.0))
    row = {
        "archive_id": arch_id,
        "tmdb_id": mid,
        "title": details["hr_title"],
        "media_type": "movie",
        "genres": genres,
        "release_year": rel_year,
        "rating": round(details["vote_average"], 1),
        "popularity_score": adjusted_pop,
        "description_hr": details["overview_hr"],
        "poster_url": f"{TMDB_IMG_POSTER}{details['poster_path']}",
        "backdrop_url": f"{TMDB_IMG_BACKDROP}{details['backdrop_path']}" if details["backdrop_path"] else "",
        "stream_url": stream_url,
        "is_featured": details["vote_average"] >= 7.8 and 16 not in details["genre_ids"],
        "is_valid": True,
        "tmdb_runtime": details["runtime"],
        "archive_runtime": arch_mins
    }
    supabase_upsert("catalog_items", [row], "archive_id")

    retired_vhs = 0
    if had_only_vhs and all_movie_rows is not None:
        retired_vhs = retire_vhs_rows_for_tmdb(mid, arch_id, rel_year, all_movie_rows, tmdb_version_counts)

    ver_tag = " [zamjena za VHS]" if retired_vhs > 0 else (" [2. verzija]" if current_count >= 1 else "")
    print(f"  + Film{ver_tag}: {row['title']} ({rel_year}) -> {', '.join(genres)}", flush=True)
    return True


def mine_direct_movies_from_branches(
    sub_layers,
    existing_archive_ids,
    seen_tmdb_ids,
    seen_title_years,
    tmdb_version_counts,
    tmdb_best_tier,
    deadline_ts,
    all_movie_rows=None
):
    print("2/4 Prioritetno rudarenje pojedinačnih filmova iz hijerarhije stabla (paketi po 25)...", flush=True)
    if time.monotonic() > deadline_ts:
        print("  ⏱ Preskočeno zbog dostignutog vremenskog limita.", flush=True)
        return

    known_cols = set(APPROVED_NODE_IDS)
    extra_sub_cols = []
    for ident, _ in sub_layers:
        if ident not in known_cols:
            known_cols.add(ident)
            extra_sub_cols.append((ident, 2))

    all_nodes = list(APPROVED_HIERARCHY_NODES) + extra_sub_cols
    collections_to_scan = random.sample(all_nodes, k=min(18, len(all_nodes)))
    archive_docs = {}

    def fetch_query_docs(task_item):
        if time.monotonic() > deadline_ts:
            return []
        query, sort_order, page, rows_cnt = task_item
        time.sleep(0.15)
        data = safe_get(
            "https://archive.org/advancedsearch.php",
            params={
                "q": query,
                "fl[]": ["identifier", "title", "year", "downloads"],
                "sort[]": sort_order,
                "rows": rows_cnt,
                "page": page,
                "output": "json"
            },
            timeout=18,
        )
        return (data or {}).get("response", {}).get("docs", [])

    tasks = []
    for col, max_p in collections_to_scan:
        if col == "popcornmaker":
            base_q = 'mediatype:(movies OR web) AND collection:popcornmaker AND title:(19* OR 20*) AND -subject:trailer AND -subject:news AND -subject:"video games"'
        else:
            base_q = f'mediatype:movies AND collection:{col} AND -subject:trailer AND -subject:news AND -subject:"video games"'
        page_pick = random.randint(1, max_p + 4)
        tasks.append((base_q, "downloads desc", page_pick, 100))
        tasks.append((base_q, "addeddate desc", random.randint(1, 2), 100))

    sampled_decades = random.sample(DECADE_RANGES, k=2)
    for flat_col in random.sample(LARGE_FLAT_BUCKETS, k=4):
        for y_from, y_to in sampled_decades:
            dec_q = (
                f'mediatype:movies AND collection:{flat_col} AND year:[{y_from} TO {y_to}] '
                f'AND item_size:[350000000 TO 150000000000] '
                f'AND -subject:trailer AND -subject:news AND -subject:"video games"'
            )
            tasks.append((dec_q, "downloads desc", random.randint(1, 4), 100))

    random.shuffle(tasks)

    with ThreadPoolExecutor(max_workers=4) as pool:
        for docs in pool.map(fetch_query_docs, tasks):
            for doc in docs:
                ident = doc.get("identifier")
                if ident and ident not in existing_archive_ids and ident not in archive_docs:
                    archive_docs[ident] = doc

    remaining_pool = []
    skipped_pre = 0
    for doc in archive_docs.values():
        ident = doc.get("identifier") or ""
        if not ident or ident in existing_archive_ids or ident.lower().startswith(("youtube-", "yt-")):
            skipped_pre += 1
            continue
        raw_title = doc.get("title") or ident
        if isinstance(raw_title, list):
            raw_title = raw_title[0]
        if is_explicit_junk_item(ident, raw_title) or BUNDLE_NOISE_RE.search(f"{ident} {raw_title}") or BAD_TITLE_MARKER_RE.search(str(raw_title)):
            record_rejected_archive_id(ident, raw_title, doc.get("year"), existing_archive_ids)
            skipped_pre += 1
            continue
        if contains_hentai_or_adult(ident, raw_title) or contains_blocked_regional_or_arabic(ident, raw_title):
            record_rejected_archive_id(ident, raw_title, doc.get("year"), existing_archive_ids)
            skipped_pre += 1
            continue
        title_candidates, parsed_y = extract_archive_title_candidates(str(raw_title), ident)
        if not title_candidates:
            record_rejected_archive_id(ident, raw_title, doc.get("year"), existing_archive_ids)
            skipped_pre += 1
            continue
        primary_norm = normalize_for_exact_title_compare(title_candidates[0])
        cand_tier = estimate_source_quality_tier(ident, "", str(raw_title))
        special_flag = is_special_or_remastered_edition(ident, "", str(raw_title))
        if primary_norm and parsed_y > 0 and (primary_norm, parsed_y) in seen_title_years:
            if cand_tier < 1 and not special_flag:
                skipped_pre += 1
                continue
        remaining_pool.append((doc, title_candidates, parsed_y))

    flush_rejected_buffer(force=True)

    remaining_pool.sort(
        key=lambda e: (0 if e[2] > 0 else 1, -float(e[0].get("downloads") or 0))
    )
    remaining_pool = remaining_pool[:500]

    print(
        f"  Ukupno {len(remaining_pool)} novih pojedinačnih kandidata u redu iz {len(collections_to_scan)} grana/podgrana "
        f"(preskočeno {skipped_pre} postojećih/odbačenih)...",
        flush=True
    )

    total_added = [0]
    batch_num = 0
    cursor = 0
    stats = {
        "no_tmdb_match": 0,
        "already_in_db": 0,
        "no_video_file": 0,
        "validation_rejected": 0,
        "junk_recorded": 0,
    }

    def inc_stat(key):
        with state_lock:
            stats[key] += 1

    while cursor < len(remaining_pool) and time.monotonic() < deadline_ts:
        batch_num += 1
        batch_slice = remaining_pool[cursor:cursor + BATCH_SIZE_CANDIDATES]
        cursor += len(batch_slice)
        print(
            f"  ▶ Paket #{batch_num}: obrada {len(batch_slice)} kandidata (preostalo u redu: {len(remaining_pool) - cursor})...",
            flush=True
        )

        batch_added = [0]

        def process_archive_doc(entry):
            if time.monotonic() > deadline_ts:
                return
            doc, title_candidates, parsed_y = entry
            ident = doc.get("identifier") or ""
            raw_title = doc.get("title") or ident
            if isinstance(raw_title, list):
                raw_title = raw_title[0]

            if is_explicit_junk_item(ident, raw_title):
                record_rejected_archive_id(ident, raw_title, parsed_y, existing_archive_ids)
                inc_stat("junk_recorded")
                return

            cand_tier = estimate_source_quality_tier(ident, "", str(raw_title))
            special_flag = is_special_or_remastered_edition(ident, "", str(raw_title))

            results = []
            for cand_q in title_candidates[:2]:
                if time.monotonic() > deadline_ts:
                    return
                if parsed_y > 0:
                    res_y = safe_get(
                        f"{TMDB_BASE}/search/movie",
                        params={"api_key": TMDB_API_KEY, "query": cand_q, "year": parsed_y, "language": "hr-HR", "include_adult": "false"}
                    )
                    results = (res_y or {}).get("results", [])
                if not results:
                    res_any = safe_get(
                        f"{TMDB_BASE}/search/movie",
                        params={"api_key": TMDB_API_KEY, "query": cand_q, "language": "hr-HR", "include_adult": "false"}
                    )
                    results = (res_any or {}).get("results", [])
                if results:
                    break

            if not results:
                record_rejected_archive_id(ident, raw_title, parsed_y, existing_archive_ids)
                inc_stat("no_tmdb_match")
                return

            cand_norms = {normalize_for_exact_title_compare(c) for c in title_candidates}
            cand_norms.discard("")
            chosen_hit = None
            for hit in results[:5]:
                if is_future_release(hit.get("release_date") or ""):
                    continue
                hit_orig_norm = normalize_for_exact_title_compare(hit.get("original_title") or "")
                hit_hr_norm = normalize_for_exact_title_compare(hit.get("title") or "")
                hit_year = extract_year_from_text(hit.get("release_date") or "")
                if parsed_y > 0 and hit_year > 0 and abs(hit_year - parsed_y) > 1:
                    continue
                if cand_norms and (hit_orig_norm in cand_norms or hit_hr_norm in cand_norms):
                    chosen_hit = hit
                    break

            if chosen_hit is None:
                first_hit = results[0]
                first_year = extract_year_from_text(first_hit.get("release_date") or "")
                if not is_future_release(first_hit.get("release_date") or "") and (
                    parsed_y == 0 or first_year == 0 or abs(first_year - parsed_y) <= 1
                ):
                    chosen_hit = first_hit

            if chosen_hit is None:
                record_rejected_archive_id(ident, raw_title, parsed_y, existing_archive_ids)
                inc_stat("no_tmdb_match")
                return

            mid = int(chosen_hit["id"])
            if is_mismatched_modern_dune(mid, ident, raw_title):
                record_rejected_archive_id(ident, raw_title, parsed_y, existing_archive_ids)
                inc_stat("validation_rejected")
                return
            with state_lock:
                if not can_accept_movie_candidate(mid, tmdb_version_counts, tmdb_best_tier, cand_tier, special_flag):
                    inc_stat("already_in_db")
                    return

            details = get_tmdb_movie_details(mid)
            if not details or not details["poster_path"]:
                record_rejected_archive_id(ident, raw_title, parsed_y, existing_archive_ids)
                inc_stat("no_tmdb_match")
                return

            is_doc = 99 in (details.get("genre_ids") or [])
            is_anim = 16 in (details.get("genre_ids") or [])
            videos, arch_md = get_archive_item_details(ident, min_size_mb=(80 if is_anim else 160), allow_mkv=True)
            if not videos or is_episodic_or_multi_movie_container(videos):
                if videos and STRICT_EPISODE_RE.search(" ".join(v.get("filename") or "" for v in videos)):
                    queue_series_candidate_for_tv_sync(ident, raw_title)
                record_rejected_archive_id(ident, raw_title, parsed_y, existing_archive_ids)
                inc_stat("no_video_file")
                return
            best = pick_best_video_file(videos)
            arch_mins = best["duration_mins"]

            if is_mismatched_modern_dune(mid, ident, raw_title, best["filename"], arch_md.get("title")):
                record_rejected_archive_id(ident, raw_title, parsed_y, existing_archive_ids)
                inc_stat("validation_rejected")
                return

            if not is_doc and is_explicit_junk_item(ident, arch_md.get("title") or raw_title, best["filename"]):
                record_rejected_archive_id(ident, raw_title, parsed_y, existing_archive_ids)
                inc_stat("junk_recorded")
                return

            if is_runtime_mismatch(details.get("runtime", 0), arch_mins, is_short_allowed=is_doc, size_mb=best["size_mb"]):
                record_rejected_archive_id(ident, raw_title, parsed_y, existing_archive_ids)
                inc_stat("validation_rejected")
                return

            rel_year = extract_year_from_text(details["release_date"]) or parsed_y
            expected_titles = [details["orig_title"], details["hr_title"], details.get("en_title", "")] + title_candidates
            if not verify_multilayer_title_and_file(best["filename"], ident, arch_md, expected_titles, rel_year, details=details):
                record_rejected_archive_id(ident, raw_title, parsed_y, existing_archive_ids)
                inc_stat("validation_rejected")
                return

            final_tier = max(cand_tier, best.get("aspect_tier", 1), estimate_source_quality_tier(ident, best["stream_url"], str(raw_title)))
            final_special = special_flag or is_special_or_remastered_edition(ident, best["stream_url"], str(raw_title))
            with state_lock:
                if not can_accept_movie_candidate(mid, tmdb_version_counts, tmdb_best_tier, final_tier, final_special):
                    inc_stat("already_in_db")
                    return

            require_credits = has_multiple_tmdb_versions(details["orig_title"])
            ok, _ = validate_movie_match(
                ident,
                arch_md,
                arch_mins,
                details,
                expected_titles,
                rel_year,
                require_credits=require_credits,
                chosen_filename=best["filename"],
                chosen_size_mb=best["size_mb"]
            )
            if not ok or not verify_stream_url_playable(best["stream_url"]):
                record_rejected_archive_id(ident, raw_title, parsed_y, existing_archive_ids)
                inc_stat("validation_rejected")
                return

            downloads = float(doc.get("downloads") or 0)
            pop_score = max(details["popularity"] * 100.0, min(downloads / 10.0, 15000.0)) + (rel_year * 2)
            if save_verified_movie_row(
                ident, details, rel_year, best["stream_url"], arch_mins, pop_score,
                seen_tmdb_ids, seen_title_years, existing_archive_ids,
                tmdb_version_counts, tmdb_best_tier, all_movie_rows=all_movie_rows,
                size_mb=best["size_mb"]
            ):
                with state_lock:
                    batch_added[0] += 1
                    total_added[0] += 1

        with ThreadPoolExecutor(max_workers=6) as pool:
            futures = [pool.submit(process_archive_doc, entry) for entry in batch_slice]
            for fut in as_completed(futures):
                try:
                    fut.result()
                except Exception:
                    pass

        flush_rejected_buffer(force=True)
        print(
            f"  ✓ Paket #{batch_num} ({len(batch_slice)}) završen: dodano {batch_added[0]} filmova (ukupno dodano: {total_added[0]}).",
            flush=True
        )

        remaining_secs = deadline_ts - time.monotonic()
        if cursor >= len(remaining_pool):
            break
        if remaining_secs < MIN_REMAINING_SECONDS_FOR_NEXT_BATCH:
            print(
                f"  ⏱ Dostignut vremenski budžet za korak 2/4 nakon {batch_num} paketa "
                f"(dodano {total_added[0]} pojedinačnih filmova) — prijelaz na korak 3/4...",
                flush=True
            )
            break

    print(
        f"  Ukupno dodano iz pojedinačnih zapisa: {total_added[0]} filmova "
        f"(odbačeno i zapamćeno: {stats['junk_recorded'] + stats['no_tmdb_match'] + stats['no_video_file'] + stats['validation_rejected']}, "
        f"već u bazi: {stats['already_in_db']}).",
        flush=True
    )


def sync_ex_yu_and_cult_movies(
    existing_archive_ids,
    seen_tmdb_ids,
    seen_title_years,
    tmdb_version_counts,
    tmdb_best_tier,
    phase_deadline_ts,
    all_movie_rows=None
):
    print("3/4 Sinkronizacija Ex-Yu filmova, kultnih klasika i TMDB žanrova...", flush=True)
    if time.monotonic() > phase_deadline_ts:
        print("  ⏱ Preskočeno zbog dostignutog vremenskog limita.", flush=True)
        return

    ex_yu_docs = {}
    for base_q in EX_YU_ARCHIVE_QUERIES:
        if time.monotonic() > phase_deadline_ts:
            break
        data = safe_get(
            "https://archive.org/advancedsearch.php",
            params={
                "q": base_q,
                "fl[]": ["identifier", "title", "year", "downloads"],
                "sort[]": "downloads desc",
                "rows": 80,
                "page": random.randint(1, 3),
                "output": "json"
            },
            timeout=15,
        )
        for doc in (data or {}).get("response", {}).get("docs", []):
            ident = doc.get("identifier")
            if ident and ident not in existing_archive_ids and ident not in ex_yu_docs:
                ex_yu_docs[ident] = doc

    added_ex_yu = [0]

    def process_ex_yu_doc(doc):
        if time.monotonic() > phase_deadline_ts:
            return
        ident = doc.get("identifier") or ""
        if not ident or ident.lower().startswith(("youtube-", "yt-")):
            return
        raw_title = doc.get("title") or ident
        if isinstance(raw_title, list):
            raw_title = raw_title[0]
        if is_explicit_junk_item(ident, raw_title) or contains_hentai_or_adult(ident, raw_title) or contains_blocked_regional_or_arabic(ident, raw_title):
            record_rejected_archive_id(ident, raw_title, doc.get("year"), existing_archive_ids)
            return

        title_candidates, parsed_y = extract_archive_title_candidates(str(raw_title), ident)
        if not title_candidates:
            record_rejected_archive_id(ident, raw_title, doc.get("year"), existing_archive_ids)
            return

        results = []
        for cand_q in title_candidates[:2]:
            if time.monotonic() > phase_deadline_ts:
                return
            for lang_code in ("hr-HR", "en-US"):
                params = {
                    "api_key": TMDB_API_KEY,
                    "query": cand_q,
                    "language": lang_code,
                    "include_adult": "false"
                }
                if parsed_y > 0:
                    params["year"] = parsed_y
                res = safe_get(f"{TMDB_BASE}/search/movie", params=params)
                results = (res or {}).get("results", [])
                if not results and parsed_y > 0:
                    params.pop("year")
                    res = safe_get(f"{TMDB_BASE}/search/movie", params=params)
                    results = (res or {}).get("results", [])
                if results:
                    break
            if results:
                break

        if not results:
            record_rejected_archive_id(ident, raw_title, parsed_y, existing_archive_ids)
            return

        cand_norms = {normalize_for_exact_title_compare(c) for c in title_candidates}
        cand_norms.discard("")
        chosen_hit = None
        for hit in results[:5]:
            if is_future_release(hit.get("release_date") or ""):
                continue
            hit_orig_norm = normalize_for_exact_title_compare(hit.get("original_title") or "")
            hit_hr_norm = normalize_for_exact_title_compare(hit.get("title") or "")
            hit_year = extract_year_from_text(hit.get("release_date") or "")
            if parsed_y > 0 and hit_year > 0 and abs(hit_year - parsed_y) > 1:
                continue
            if cand_norms and (hit_orig_norm in cand_norms or hit_hr_norm in cand_norms):
                chosen_hit = hit
                break

        if chosen_hit is None:
            first_hit = results[0]
            first_year = extract_year_from_text(first_hit.get("release_date") or "")
            if not is_future_release(first_hit.get("release_date") or "") and (
                parsed_y == 0 or first_year == 0 or abs(first_year - parsed_y) <= 1
            ):
                chosen_hit = first_hit

        if chosen_hit is None:
            record_rejected_archive_id(ident, raw_title, parsed_y, existing_archive_ids)
            return

        mid = int(chosen_hit["id"])
        cand_tier = estimate_source_quality_tier(ident, "", str(raw_title))
        special_flag = is_special_or_remastered_edition(ident, "", str(raw_title))
        with state_lock:
            if not can_accept_movie_candidate(mid, tmdb_version_counts, tmdb_best_tier, cand_tier, special_flag):
                record_rejected_archive_id(ident, raw_title, parsed_y, existing_archive_ids)
                return

        details = get_tmdb_movie_details(mid)
        if not details or not details["poster_path"]:
            record_rejected_archive_id(ident, raw_title, parsed_y, existing_archive_ids)
            return

        orig_lang = details.get("original_language", "")
        is_confirmed_ex_yu = (
            orig_lang in EX_YU_LANGS
            or check_ex_yu(ident, raw_title, details["orig_title"], details["hr_title"], " ".join(details["companies"]))
        )
        if not is_confirmed_ex_yu:
            record_rejected_archive_id(ident, raw_title, parsed_y, existing_archive_ids)
            return

        is_anim = 16 in (details.get("genre_ids") or [])
        videos, arch_md = get_archive_item_details(ident, min_size_mb=(70 if is_anim else 130), allow_mkv=True)
        if not videos or is_episodic_or_multi_movie_container(videos):
            record_rejected_archive_id(ident, raw_title, parsed_y, existing_archive_ids)
            return
        best = pick_best_video_file(videos)
        arch_mins = best["duration_mins"]

        if is_runtime_mismatch(details.get("runtime", 0), arch_mins, is_short_allowed=is_anim, size_mb=best["size_mb"]):
            record_rejected_archive_id(ident, raw_title, parsed_y, existing_archive_ids)
            return

        rel_year = extract_year_from_text(details["release_date"]) or parsed_y
        expected_titles = [details["orig_title"], details["hr_title"], details.get("en_title", "")] + title_candidates
        ok, _ = validate_movie_match(
            ident,
            arch_md,
            arch_mins,
            details,
            expected_titles,
            rel_year,
            require_credits=False,
            chosen_filename=best["filename"],
            chosen_size_mb=best["size_mb"]
        )
        if not ok or not verify_stream_url_playable(best["stream_url"]):
            record_rejected_archive_id(ident, raw_title, parsed_y, existing_archive_ids)
            return

        downloads = float(doc.get("downloads") or 0)
        pop_score = max(details["popularity"] * 100.0, min(downloads / 10.0, 12000.0)) + (rel_year * 2) + 1500.0
        hint = "Ex-Yu crtići" if is_anim else "Ex-Yu filmovi"
        if save_verified_movie_row(
            ident, details, rel_year, best["stream_url"], arch_mins, pop_score,
            seen_tmdb_ids, seen_title_years, existing_archive_ids,
            tmdb_version_counts, tmdb_best_tier, hint_genre=hint, all_movie_rows=all_movie_rows,
            size_mb=best["size_mb"]
        ):
            with state_lock:
                added_ex_yu[0] += 1

    if ex_yu_docs and time.monotonic() < phase_deadline_ts:
        ex_yu_list = list(ex_yu_docs.values())[:60]
        print(f"  Provjera {len(ex_yu_list)} Ex-Yu Archive kandidata...", flush=True)
        run_batched_with_deadline(
            ex_yu_list, process_ex_yu_doc, phase_deadline_ts, max_workers=6, batch_size=20
        )
        flush_rejected_buffer(force=True)

    if time.monotonic() > phase_deadline_ts:
        print(f"  Dodano {added_ex_yu[0]} Ex-Yu filmova (vremenski budžet koraka 3/4 iskorišten).", flush=True)
        return

    all_seeds = EX_YU_MOVIE_SEEDS + CULT_AND_ANIMATION_SEEDS
    sampled_seeds = random.sample(all_seeds, k=min(TMDB_SEED_SAMPLE_PER_RUN, len(all_seeds)))
    candidates = {}

    def fetch_seed(seed_title):
        if time.monotonic() > phase_deadline_ts:
            return []
        res = safe_get(f"{TMDB_BASE}/search/movie", {"api_key": TMDB_API_KEY, "query": seed_title, "language": "hr-HR", "include_adult": "false"})
        return (res or {}).get("results", [])[:2]

    with ThreadPoolExecutor(max_workers=8) as pool:
        for res_list in pool.map(fetch_seed, sampled_seeds):
            for m in res_list:
                mid = int(m["id"])
                if can_accept_movie_candidate(mid, tmdb_version_counts, tmdb_best_tier) and mid not in candidates and not is_future_release(m.get("release_date") or ""):
                    candidates[mid] = (m, None)

    discover_page = random.randint(1, 5)
    for tmdb_genre_id, hr_genre_label, min_votes in random.sample(TMDB_DISCOVER_GENRE_SEEDS, k=3):
        if time.monotonic() > phase_deadline_ts:
            break
        data = safe_get(
            f"{TMDB_BASE}/discover/movie",
            {
                "api_key": TMDB_API_KEY,
                "language": "hr-HR",
                "with_genres": tmdb_genre_id,
                "without_genres": "16,99",
                "include_adult": "false",
                "vote_count.gte": min_votes,
                "sort_by": "vote_count.desc",
                "page": discover_page
            }
        )
        for m in (data or {}).get("results", []):
            mid = int(m["id"])
            if can_accept_movie_candidate(mid, tmdb_version_counts, tmdb_best_tier) and mid not in candidates and not is_future_release(m.get("release_date") or ""):
                if not contains_blocked_regional_or_arabic(m.get("original_title"), m.get("title"), orig_lang=m.get("original_language", "")):
                    candidates[mid] = (m, hr_genre_label)

    cand_items = list(candidates.values())[:70]
    print(f"  Brza provjera uzorka od {len(cand_items)} TMDB kandidata na Archive.org...", flush=True)
    added_tmdb = [0]

    def process_tmdb_cand(item):
        if time.monotonic() > phase_deadline_ts:
            return
        m, hint_genre = item
        mid = int(m["id"])
        with state_lock:
            if not can_accept_movie_candidate(mid, tmdb_version_counts, tmdb_best_tier):
                return
            min_req_tier = 1 if tmdb_best_tier.get(mid, -1) == 0 else (2 if tmdb_version_counts.get(mid, 0) > 0 else 0)

        rel_date = m.get("release_date") or ""
        year = int(rel_date[:4]) if len(rel_date) >= 4 and rel_date[:4].isdigit() else 0
        details = get_tmdb_movie_details(mid)
        if not details or not details["poster_path"]:
            return
        if details["runtime"] > 0 and details["runtime"] < 55:
            return

        with state_lock:
            exclude_snapshot = set(existing_archive_ids)
        match = find_best_archive_movie_match(details, year, exclude_snapshot, phase_deadline_ts, min_aspect_tier=min_req_tier)
        if match:
            best_ident, best_file, best_mins = match
            pop_score = float(m.get("popularity") or 10.0) * 100.0 + (year * 2)
            if save_verified_movie_row(
                best_ident, details, year, best_file["stream_url"], best_mins, pop_score,
                seen_tmdb_ids, seen_title_years, existing_archive_ids,
                tmdb_version_counts, tmdb_best_tier, hint_genre, all_movie_rows=all_movie_rows,
                size_mb=best_file.get("size_mb", 0)
            ):
                with state_lock:
                    added_tmdb[0] += 1

    run_batched_with_deadline(
        cand_items, process_tmdb_cand, phase_deadline_ts, max_workers=6, batch_size=20
    )

    print(f"  Dodano {added_ex_yu[0]} Ex-Yu i {added_tmdb[0]} kultnih/žanrovskih filmova.", flush=True)


def discover_layered_multi_file_bundles(sub_layers, existing_archive_ids, deadline_ts):
    ordered_collections = random.sample(
        APPROVED_NODE_IDS + [ident for ident, _ in sub_layers] + LARGE_FLAT_BUCKETS,
        k=min(15, len(APPROVED_NODE_IDS))
    )

    discovered = []
    seen_ids = set(PINNED_MULTI_FILE_BUNDLES)

    bundle_kw_clause = '(format:"ISO Image" OR title:(iso OR dvd OR pack OR collection OR trilogy OR quadrilogy OR anthology OR boxset OR "box set" OR movies OR films OR cartoons OR marathon OR classics))'

    for col_id in ordered_collections:
        if time.monotonic() > deadline_ts or len(discovered) >= MAX_DYNAMIC_BUNDLES_PER_RUN:
            break
        q = (
            f'mediatype:movies AND collection:{col_id} AND {bundle_kw_clause} '
            f'AND item_size:[4500000000 TO 150000000000] '
            f'AND -subject:"tv series" AND -subject:television AND -subject:news AND -subject:sports AND -subject:music'
        )
        data = safe_get(
            "https://archive.org/advancedsearch.php",
            params={
                "q": q,
                "fl[]": ["identifier", "title", "downloads"],
                "sort[]": random.choice(["downloads desc", "addeddate desc"]),
                "rows": 25,
                "page": random.randint(1, 3),
                "output": "json",
            },
            timeout=14,
        )
        for doc in (data or {}).get("response", {}).get("docs", []):
            ident = (doc.get("identifier") or "").strip()
            if not ident or ident in seen_ids or ident in existing_archive_ids or f"bundle_checked:{ident}" in existing_archive_ids:
                continue
            raw_t = doc.get("title") or ident
            if isinstance(raw_t, list):
                raw_t = raw_t[0]
            if BUNDLE_NOISE_RE.search(f"{ident} {raw_t}") or is_explicit_junk_item(ident, raw_t) or contains_hentai_or_adult(ident, raw_t) or contains_blocked_regional_or_arabic(ident, raw_t):
                record_rejected_archive_id(f"bundle_checked:{ident}", raw_t, 0, existing_archive_ids)
                continue
            seen_ids.add(ident)
            discovered.append(ident)
            if len(discovered) >= MAX_DYNAMIC_BUNDLES_PER_RUN:
                break

    flush_rejected_buffer(force=True)
    return discovered


def sync_multi_file_movie_bundles(
    sub_layers,
    existing_archive_ids,
    seen_tmdb_ids,
    seen_title_years,
    tmdb_version_counts,
    tmdb_best_tier,
    deadline_ts,
    all_movie_rows=None
):
    print("4/4 Završno izvlačenje preostalih filmova iz ugniježđenih multi-file paketa...", flush=True)
    if time.monotonic() > deadline_ts:
        print("  ⏱ Preskočeno zbog dostignutog vremenskog limita.", flush=True)
        return

    dynamic_bundles = discover_layered_multi_file_bundles(sub_layers, existing_archive_ids, deadline_ts)
    all_bundles = list(PINNED_MULTI_FILE_BUNDLES) + dynamic_bundles
    print(f"  Pronađeno {len(dynamic_bundles)} novih multi-file kandidata u hijerarhijskim granama.", flush=True)

    added_total = [0]

    for bundle_ident in all_bundles:
        if time.monotonic() > deadline_ts:
            break

        is_pinned = bundle_ident in PINNED_MULTI_FILE_BUNDLES
        videos, arch_md = get_archive_item_details(bundle_ident, min_size_mb=120, allow_mkv=True)
        if not videos:
            if not is_pinned:
                record_rejected_archive_id(f"bundle_checked:{bundle_ident}", bundle_ident, 0, existing_archive_ids)
            continue

        if not is_pinned and len(videos) > MAX_VIDEOS_PER_BUNDLE * 2:
            record_rejected_archive_id(f"bundle_checked:{bundle_ident}", bundle_ident, 0, existing_archive_ids)
            continue

        raw_bundle_title = arch_md.get("title") or bundle_ident
        if isinstance(raw_bundle_title, list):
            raw_bundle_title = raw_bundle_title[0]
        if not is_pinned and (BUNDLE_NOISE_RE.search(f"{bundle_ident} {raw_bundle_title}") or BAD_TITLE_MARKER_RE.search(str(raw_bundle_title))):
            record_rejected_archive_id(f"bundle_checked:{bundle_ident}", raw_bundle_title, 0, existing_archive_ids)
            continue

        bundle_year = extract_clean_film_year_from_filename(str(arch_md.get("year") or arch_md.get("date") or raw_bundle_title or bundle_ident))

        deduped_files = {}
        for v in videos:
            fname = v.get("filename") or ""
            mins = int(v.get("duration_mins") or 0)
            if mins > 0 and mins < 55:
                continue
            if is_explicit_junk_item(bundle_ident, "", fname) or BAD_TITLE_MARKER_RE.search(fname):
                continue
            clean_t, year = clean_filename_to_title(fname, fallback_to_stem=True)
            if not clean_t or len(clean_t) < 2 or BAD_TITLE_MARKER_RE.search(clean_t):
                continue
            stem_key = re.sub(r"(?i)\.ia$", "", fname.rsplit("/", 1)[-1].rsplit(".", 1)[0]).lower()
            is_mp4 = fname.lower().endswith(".mp4")
            tier = int(v.get("aspect_tier", 1))
            score = (tier, 0 if (not v.get("is_derivative") and is_mp4) else 1, v.get("size_mb", 0))
            if stem_key not in deduped_files or score > deduped_files[stem_key][0]:
                deduped_files[stem_key] = (score, v, clean_t, year)

        if not is_pinned and not (3 <= len(deduped_files) <= MAX_VIDEOS_PER_BUNDLE):
            record_rejected_archive_id(f"bundle_checked:{bundle_ident}", bundle_ident, 0, existing_archive_ids)
            continue

        sorted_entries = sorted(deduped_files.values(), key=lambda e: (e[1].get("filename") or "").lower())
        print(f"  • {bundle_ident}: pronađeno {len(sorted_entries)} dugometražnih video zapisa za provjeru...", flush=True)
        bundle_added = [0]

        def process_bundle_entry(entry):
            if time.monotonic() > deadline_ts:
                return
            _, v, clean_t, parsed_y = entry
            fname = v["filename"]
            stream_url = v["stream_url"]
            arch_mins = int(v.get("duration_mins") or 0)

            explicit_mid = EXPLICIT_BUNDLE_FILE_TMDB_MAP.get(
                (bundle_ident.lower(), unquote(fname).strip().lower()), 0
            )

            if explicit_mid <= 0:
                if contains_hentai_or_adult(fname, clean_t) or contains_blocked_regional_or_arabic(fname, clean_t):
                    return

                search_y = parsed_y if parsed_y > 0 else (bundle_year if 1900 <= bundle_year <= 2020 else 0)
                results = []
                for lang_code in ("hr-HR", "en-US"):
                    params = {
                        "api_key": TMDB_API_KEY,
                        "query": clean_t,
                        "language": lang_code,
                        "include_adult": "false"
                    }
                    if search_y > 0:
                        params["year"] = search_y
                    res = safe_get(f"{TMDB_BASE}/search/movie", params=params)
                    results = (res or {}).get("results", [])
                    if not results and search_y > 0 and parsed_y == 0:
                        params.pop("year")
                        res = safe_get(f"{TMDB_BASE}/search/movie", params=params)
                        results = (res or {}).get("results", [])
                    if results:
                        break

                if not results:
                    return

                clean_norm = normalize_for_exact_title_compare(clean_t)
                chosen_hit = None
                for hit in results[:5]:
                    if is_future_release(hit.get("release_date") or ""):
                        continue
                    hit_orig_norm = normalize_for_exact_title_compare(hit.get("original_title") or "")
                    hit_hr_norm = normalize_for_exact_title_compare(hit.get("title") or "")
                    hit_year = extract_year_from_text(hit.get("release_date") or "")
                    if parsed_y > 0 and hit_year > 0 and abs(hit_year - parsed_y) > 1:
                        continue
                    if bundle_year > 0 and parsed_y == 0 and hit_year > 0 and abs(hit_year - bundle_year) > 3:
                        continue
                    if clean_norm and (
                        clean_norm == hit_orig_norm
                        or clean_norm == hit_hr_norm
                        or (len(clean_norm) >= 5 and (clean_norm in hit_orig_norm or hit_orig_norm in clean_norm))
                    ):
                        chosen_hit = hit
                        break

                if chosen_hit is None:
                    return
                mid = int(chosen_hit["id"])
            else:
                mid = explicit_mid

            catalog_arch_id = f"{bundle_ident},tmdb-{mid}"
            cand_tier = max(int(v.get("aspect_tier", 1)), estimate_source_quality_tier(fname, stream_url, clean_t))
            special_flag = is_special_or_remastered_edition(fname, stream_url, clean_t)

            with state_lock:
                if catalog_arch_id in existing_archive_ids:
                    return
                if not can_accept_movie_candidate(mid, tmdb_version_counts, tmdb_best_tier, cand_tier, special_flag):
                    return

            details = get_tmdb_movie_details(mid)
            if not details or not details["poster_path"]:
                return
            if details.get("adult") or (details.get("status") and details["status"] != "Released"):
                return
            if is_future_release(details.get("release_date") or ""):
                return
            if BAD_TITLE_MARKER_RE.search(details["hr_title"]) or BAD_TITLE_MARKER_RE.search(details["orig_title"]):
                return

            orig_lang = details.get("original_language", "")
            if explicit_mid <= 0:
                if contains_blocked_regional_or_arabic(details["orig_title"], details["hr_title"], orig_lang=orig_lang):
                    return
                if is_foreign_dub_without_english(arch_md, bundle_ident, fname, orig_lang):
                    return

            is_doc = 99 in (details.get("genre_ids") or [])
            if is_doc and explicit_mid <= 0:
                return
            if is_runtime_mismatch(details.get("runtime", 0), arch_mins, is_short_allowed=False, size_mb=v.get("size_mb", 0)):
                return

            rel_year = extract_year_from_text(details["release_date"]) or parsed_y
            if not verify_stream_url_playable(stream_url):
                return

            pop_score = float(details["popularity"]) * 100.0 + (rel_year * 2) + 300.0
            if save_verified_movie_row(
                catalog_arch_id,
                details,
                rel_year,
                stream_url,
                arch_mins,
                pop_score,
                seen_tmdb_ids,
                seen_title_years,
                existing_archive_ids,
                tmdb_version_counts,
                tmdb_best_tier,
                hint_genre=None,
                all_movie_rows=all_movie_rows,
                size_mb=v.get("size_mb", 0)
            ):
                with state_lock:
                    bundle_added[0] += 1
                    added_total[0] += 1

        timed_out = run_batched_with_deadline(
            sorted_entries, process_bundle_entry, deadline_ts, max_workers=6, batch_size=25
        )
        print(f"    ✓ {bundle_ident}: dodano {bundle_added[0]} filmova.", flush=True)
        if not is_pinned and not timed_out:
            record_rejected_archive_id(f"bundle_checked:{bundle_ident}", bundle_ident, 0, existing_archive_ids)

    flush_rejected_buffer(force=True)
    print(f"  Ukupno dodano iz multi-file paketa: {added_total[0]} filmova.", flush=True)


def run_movies_sync():
    start_ts = time.monotonic()
    deadline_ts = start_ts + MAX_MOVIES_SYNC_SECONDS
    print("=== [FILMOVI] Pokretanje brze hijerarhijske sinkronizacije igranih, animiranih i Ex-Yu filmova ===", flush=True)
    existing_archive_ids, seen_tmdb_ids, seen_title_years, all_movie_rows = fetch_existing_catalog_state("movie")
    tmdb_version_counts, tmdb_best_tier = build_movie_version_maps(all_movie_rows)
    print(f"  U bazi već postoji {len(seen_tmdb_ids)} potvrđenih filmova ({sum(tmdb_version_counts.values())} verzija).", flush=True)

    phase1_deadline = min(deadline_ts, time.monotonic() + PHASE1_AUDIT_MAX_SECONDS)
    audit_and_repair_existing_movies(
        all_movie_rows, existing_archive_ids, seen_tmdb_ids, seen_title_years,
        tmdb_version_counts, tmdb_best_tier, phase1_deadline
    )
    sync_curated_movie_exceptions(
        all_movie_rows, existing_archive_ids, seen_tmdb_ids, seen_title_years,
        tmdb_version_counts, tmdb_best_tier
    )

    sub_layers = map_approved_movie_subtrees(deadline_ts)
    print(
        f"  Mapirano {len(APPROVED_NODE_IDS)} odobrenih čvorova + {len(sub_layers)} dubljih podgrana.",
        flush=True
    )

    phase2_deadline = min(deadline_ts, time.monotonic() + PHASE2_DIRECT_MOVIES_MAX_SECONDS)
    mine_direct_movies_from_branches(
        sub_layers, existing_archive_ids, seen_tmdb_ids, seen_title_years,
        tmdb_version_counts, tmdb_best_tier, phase2_deadline, all_movie_rows=all_movie_rows
    )

    phase3_deadline = min(deadline_ts, time.monotonic() + PHASE3_CURATED_AND_EXYU_MAX_SECONDS)
    sync_ex_yu_and_cult_movies(
        existing_archive_ids, seen_tmdb_ids, seen_title_years,
        tmdb_version_counts, tmdb_best_tier, phase3_deadline, all_movie_rows=all_movie_rows
    )

    sync_multi_file_movie_bundles(
        sub_layers, existing_archive_ids, seen_tmdb_ids, seen_title_years,
        tmdb_version_counts, tmdb_best_tier, deadline_ts, all_movie_rows=all_movie_rows
    )

    flush_rejected_buffer(force=True)
    elapsed_mins = (time.monotonic() - start_ts) / 60.0
    print(f"=== [FILMOVI] Završeno za {elapsed_mins:.1f} min! Ukupno filmova u katalogu: {len(seen_tmdb_ids)} ({sum(tmdb_version_counts.values())} verzija) ===", flush=True)


if __name__ == "__main__":
    run_movies_sync()
