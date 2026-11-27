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
    supabase_upsert,
    validate_movie_match,
    verify_stream_url_playable,
)

_HTTP_SESSION = requests.Session()
_HTTP_ADAPTER = requests.adapters.HTTPAdapter(pool_connections=32, pool_maxsize=32, max_retries=1)
_HTTP_SESSION.mount("https://", _HTTP_ADAPTER)
_HTTP_SESSION.mount("http://", _HTTP_ADAPTER)
if hasattr(sc, "session"):
    sc.session = _HTTP_SESSION

MAX_CM_SYNC_SECONDS = 36 * 60
PHASE1_DIRECT_MAX_SECONDS = 25 * 60
BATCH_SIZE_CANDIDATES = 50
MIN_REMAINING_SECONDS = 90

MAX_BUNDLES_PER_RUN = 25
MAX_VIDEOS_PER_BUNDLE = 75
MAX_VERSIONS_PER_MOVIE = 2

_REJECTED_BATCH_BUFFER = []
_REJECTED_BUFFER_SIZE = 200


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


# Pravi korisnički bazeni s milijunima uploadova:
# [1.1] Community Video (opensource_movies: 2.74M) + [1.4] Additional Collections - Video (1.91M)
USER_UPLOAD_COLLECTIONS = [
    "opensource_movies",
    "additional_collections_video",
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

# Obavezni filmski signali u upitima kako bismo odmah preskočili amaterske/privatne snimke
FILM_SIGNAL_CLAUSES = [
    'title:(1080p OR 720p OR bluray OR "blu-ray" OR bdrip OR brrip OR dvdrip OR webrip OR remastered OR restored)',
    'title:("full movie" OR vhs OR criterion OR uncut OR extended OR "director\'s cut" OR colorized)',
    'description:("directed by" OR starring OR "feature film" OR "motion picture")',
]

CM_NOISE_RE = re.compile(
    r"(?i)(?:news|cnn|fox[\s._-]*news|msnbc|cspan|election|parliament|senate|council|city[\s._-]*of|"
    r"board[\s._-]*of|commissioners|zoning|planning[\s._-]*board|town[\s._-]*hall|public[\s._-]*hearing|"
    r"government|municipal|peg[\s._-]*tv|public[\s._-]*access|community[\s._-]*tv|channel\s*\d+|"
    r"twitch|tiktok|vlog|podcast|sermon|church|prayer|ministry|worship|bible|gospel|mosque|temple|"
    r"university|college|high[\s._-]*school|school[\s._-]*board|lecture|course|seminar|graduation|commencement|"
    r"olympic|championship|tournament|sports|football|basketball|baseball|hockey|soccer|wrestling|"
    r"commercial|gameplay|speedrun|walkthrough|foia|radars|police|sheriff|fire[\s._-]*dept)"
)

TV_NEWS_OR_SPORTS_ID_RE = re.compile(
    r"(?i)(?:^(?:tv-|cspan\d*_|foxnews|msnbc|cnn_|bbcnews|aljazeera)|"
    r"\b(?:broncos|seahawks|nfl|nba|mlb|nhl|ufc|wwe)\b)"
)

STRICT_EPISODE_RE = re.compile(
    r"(?i)(?:\b\d{1,2}x(?!264\b|265\b|480\b|540\b|576\b|720\b|1080\b)\d{2,3}\b|\bs\d{1,2}e\d{1,3}\b)"
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

BAD_TITLE_MARKER_RE = re.compile(
    r"(?i)(?:dts\s*blu-ray|music\s*demo\s*disc|demo\s*disc\s*\d+|screen\s*snapshots|"
    r"observational\s*series|persian\s*series|barney'?s?\s+|my\s*little\s*pony\s*live|"
    r"bear\s*in\s*the\s*big\s*blue\s*house\s*live|asian\s*beauties|krotoplaxx|^untitled\b|"
    r"mickey\s*mouse\s*club|hardy\s*boys|broncos\s*vs|seahawks?|soultrain|manlyphall)"
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

# Prepoznavanje 18+ kolekcija (Vivamax, Kekko Kamen, pinku, softcore / erotski naslovi)
ADULT_18_PLUS_COLLECTION_RE = re.compile(
    r"(?i)(?:\bvivamax\b|\bkekko[\s._-]*kamen\b|\bkekko[\s._-]*camen\b|\bpinku\b|"
    r"\bschool[\s._-]*of[\s._-]*the[\s._-]*holy[\s._-]*beast\b|\bškola[\s._-]*besramnika\b|"
    r"\bsoftcore\b|\berotic\b|\bhentai\b|\badult[\s._-]*movie\b|\b18\+\b)"
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


def is_18_plus_item(*texts):
    blob = " ".join(str(t) for t in texts if t)
    return bool(ADULT_18_PLUS_COLLECTION_RE.search(blob) or contains_hentai_or_adult(blob))


def apply_18_plus_tag_if_needed(genres, *texts, orig_lang=""):
    if is_18_plus_item(*texts) or str(orig_lang or "").lower() == "tl":
        clean = [g for g in genres if not str(g).startswith("18+")]
        return ["18+"] + clean[:2]
    return genres


def is_explicit_junk_item(ident, raw_title, extra_text=""):
    blob = f"{ident} {raw_title} {extra_text}"
    if EX_YU_DETECTION_KEYWORDS and any(kw in blob.lower() for kw in EX_YU_DETECTION_KEYWORDS):
        return False
    if CM_NOISE_RE.search(blob):
        return True
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
    is_ex_yu = check_ex_yu(details["orig_title"], final_title, " ".join(details["companies"]), orig_lang=orig_lang)
    if is_ex_yu:
        is_anim = 16 in (details.get("genre_ids") or [])
        tag = "Ex-Yu crtići" if is_anim else "Ex-Yu filmovi"
        if tag not in genres:
            genres = [tag] + genres if not is_anim else genres + [tag]
    return genres[:3]


def extract_archive_title_candidates(raw_title, ident):
    raw_str = str(raw_title or "").strip()
    year_in_title = extract_clean_film_year_from_filename(raw_str) or extract_clean_film_year_from_filename(ident)

    trimmed_sep = re.split(r"\s+(?:-|–|—|\||/)\s+", raw_str, maxsplit=1)[0].strip()
    trimmed_credit = re.split(
        r"(?i)\s+(?:directed\s+by|dir\.?|starring|feat\.?|with)\b",
        trimmed_sep,
        maxsplit=1
    )[0].strip()

    clean_ident_str = re.sub(r"([a-z])([A-Z])", r"\1 \2", ident).replace("-", " ").replace("_", " ")

    candidates = []
    for variant in (trimmed_credit, trimmed_sep, raw_str, clean_ident_str):
        cleaned, y = clean_filename_to_title(variant)
        if cleaned and len(cleaned) >= 2 and cleaned not in candidates:
            candidates.append(cleaned)
        if not year_in_title and y:
            year_in_title = y

    return candidates, year_in_title


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
    size_mb=0,
    force_18_plus=False
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

    genres = resolve_movie_genres(details, details["hr_title"])
    orig_lang = details.get("original_language", "")
    genres = apply_18_plus_tag_if_needed(
        genres, arch_id, stream_url, details["hr_title"], details["orig_title"],
        orig_lang=orig_lang
    )
    if (force_18_plus or details.get("adult")) and "18+" not in genres:
        genres = ["18+"] + genres[:2]

    is_18_plus = any(str(g).startswith("18+") for g in genres)
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
        "is_featured": (not is_18_plus) and details["vote_average"] >= 7.8 and 16 not in details["genre_ids"],
        "is_valid": True,
        "tmdb_runtime": details["runtime"],
        "archive_runtime": arch_mins
    }
    supabase_upsert("catalog_items", [row], "archive_id")
    ver_tag = " [18+]" if is_18_plus else (" [2. verzija]" if current_count >= 1 else "")
    print(f"  + Film (User Uploads){ver_tag}: {row['title']} ({rel_year}) -> {', '.join(genres)}", flush=True)
    return True


def run_community_media_sync():
    start_ts = time.monotonic()
    deadline_ts = start_ts + MAX_CM_SYNC_SECONDS
    print("=== [USER UPLOADS] Rudarenje korisničkih filmskih uploadova (opensource_movies + additional_collections_video) ===", flush=True)

    existing_archive_ids, seen_tmdb_ids, seen_title_years, all_movie_rows = fetch_existing_catalog_state("movie")
    tmdb_version_counts, tmdb_best_tier = build_movie_version_maps(all_movie_rows)
    print(f"  U bazi već postoji {len(seen_tmdb_ids)} potvrđenih filmova ({sum(tmdb_version_counts.values())} verzija).", flush=True)

    # 1/2 Ciljana pretraga dugometražnih filmova s pozitivnim filmskim signalima
    print("1/2 Pretraga pojedinačnih filmova s filmskim oznakama (paketi po 50)...", flush=True)
    tasks = []
    sampled_decades = random.sample(DECADE_RANGES, k=3)

    for col in USER_UPLOAD_COLLECTIONS:
        for sig in FILM_SIGNAL_CLAUSES:
            q = (
                f'collection:{col} AND mediatype:movies AND item_size:[350000000 TO 150000000000] '
                f'AND {sig} AND -subject:news AND -subject:trailer AND -subject:sports'
            )
            page_pick = random.randint(1, 6)
            tasks.append((q, "downloads desc", page_pick, 100))
            tasks.append((q, "addeddate desc", random.randint(1, 3), 100))

        for y_from, y_to in sampled_decades:
            dec_q = (
                f'collection:{col} AND mediatype:movies AND year:[{y_from} TO {y_to}] '
                f'AND item_size:[400000000 TO 150000000000] '
                f'AND {FILM_SIGNAL_CLAUSES[0]} AND -subject:news AND -subject:trailer'
            )
            tasks.append((dec_q, "downloads desc", random.randint(1, 5), 100))

    random.shuffle(tasks)
    archive_docs = {}

    def fetch_docs(task_item):
        if time.monotonic() > deadline_ts:
            return []
        q, sort_order, page, rows_cnt = task_item
        data = safe_get(
            "https://archive.org/advancedsearch.php",
            params={
                "q": q,
                "fl[]": ["identifier", "title", "year", "downloads"],
                "sort[]": sort_order,
                "rows": rows_cnt,
                "page": page,
                "output": "json",
            },
            timeout=12,
        )
        return (data or {}).get("response", {}).get("docs", [])

    with ThreadPoolExecutor(max_workers=10) as pool:
        for docs in pool.map(fetch_docs, tasks):
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
        if is_explicit_junk_item(ident, raw_title) or BAD_TITLE_MARKER_RE.search(str(raw_title)):
            record_rejected_archive_id(ident, raw_title, doc.get("year"), existing_archive_ids)
            skipped_pre += 1
            continue
        if contains_blocked_regional_or_arabic(ident, raw_title) and not is_18_plus_item(ident, raw_title):
            record_rejected_archive_id(ident, raw_title, doc.get("year"), existing_archive_ids)
            skipped_pre += 1
            continue
        title_candidates, parsed_y = extract_archive_title_candidates(str(raw_title), ident)
        if not title_candidates:
            record_rejected_archive_id(ident, raw_title, doc.get("year"), existing_archive_ids)
            skipped_pre += 1
            continue
        remaining_pool.append((doc, title_candidates, parsed_y))

    flush_rejected_buffer(force=True)
    remaining_pool.sort(key=lambda e: (0 if e[2] > 0 else 1, -float(e[0].get("downloads") or 0)))
    remaining_pool = remaining_pool[:800]
    print(f"  Pronađeno {len(remaining_pool)} novih kandidata s filmskim oznakama (preskočeno {skipped_pre} postojećih/šuma).", flush=True)

    phase1_deadline = min(deadline_ts, time.monotonic() + PHASE1_DIRECT_MAX_SECONDS)
    total_added = [0]
    cursor = 0
    batch_num = 0

    while cursor < len(remaining_pool) and time.monotonic() < phase1_deadline:
        batch_num += 1
        batch_slice = remaining_pool[cursor:cursor + BATCH_SIZE_CANDIDATES]
        cursor += len(batch_slice)
        print(f"  ▶ Paket #{batch_num}: obrada {len(batch_slice)} kandidata (preostalo: {len(remaining_pool) - cursor})...", flush=True)
        batch_added = [0]

        def process_doc(entry):
            if time.monotonic() > phase1_deadline:
                return
            doc, title_candidates, parsed_y = entry
            ident = doc.get("identifier") or ""
            raw_title = doc.get("title") or ident
            if isinstance(raw_title, list):
                raw_title = raw_title[0]

            cand_tier = estimate_source_quality_tier(ident, "", str(raw_title))
            special_flag = is_special_or_remastered_edition(ident, "", str(raw_title))
            adult_flag = is_18_plus_item(ident, raw_title)

            results = []
            for cand_q in title_candidates[:2]:
                if time.monotonic() > phase1_deadline:
                    return
                if parsed_y > 0:
                    res_y = safe_get(
                        f"{TMDB_BASE}/search/movie",
                        params={"api_key": TMDB_API_KEY, "query": cand_q, "year": parsed_y, "language": "hr-HR", "include_adult": "true"}
                    )
                    results = (res_y or {}).get("results", [])
                if not results:
                    res_any = safe_get(
                        f"{TMDB_BASE}/search/movie",
                        params={"api_key": TMDB_API_KEY, "query": cand_q, "language": "hr-HR", "include_adult": "true"}
                    )
                    results = (res_any or {}).get("results", [])
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
            with state_lock:
                if not can_accept_movie_candidate(mid, tmdb_version_counts, tmdb_best_tier, cand_tier, special_flag):
                    return

            details = get_tmdb_movie_details(mid)
            if not details or not details["poster_path"]:
                record_rejected_archive_id(ident, raw_title, parsed_y, existing_archive_ids)
                return

            is_doc = 99 in (details.get("genre_ids") or [])
            is_anim = 16 in (details.get("genre_ids") or [])
            videos, arch_md = get_archive_item_details(ident, min_size_mb=(80 if is_anim else 160), allow_mkv=True)
            if not videos or is_episodic_or_multi_movie_container(videos):
                record_rejected_archive_id(ident, raw_title, parsed_y, existing_archive_ids)
                return

            best = pick_best_video_file(videos)
            arch_mins = best["duration_mins"]

            if not is_doc and is_explicit_junk_item(ident, arch_md.get("title") or raw_title, best["filename"]):
                record_rejected_archive_id(ident, raw_title, parsed_y, existing_archive_ids)
                return

            if is_runtime_mismatch(details.get("runtime", 0), arch_mins, is_short_allowed=is_doc, size_mb=best["size_mb"]):
                record_rejected_archive_id(ident, raw_title, parsed_y, existing_archive_ids)
                return

            rel_year = extract_year_from_text(details["release_date"]) or parsed_y
            expected_titles = [details["orig_title"], details["hr_title"], details.get("en_title", "")] + title_candidates
            if not verify_multilayer_title_and_file(best["filename"], ident, arch_md, expected_titles, rel_year, details=details):
                record_rejected_archive_id(ident, raw_title, parsed_y, existing_archive_ids)
                return

            adult_flag = adult_flag or is_18_plus_item(
                ident, raw_title, best["filename"], details["orig_title"], details["hr_title"]
            ) or str(details.get("original_language") or "").lower() == "tl"

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
                return

            downloads = float(doc.get("downloads") or 0)
            pop_score = max(details["popularity"] * 100.0, min(downloads / 10.0, 15000.0)) + (rel_year * 2)
            if save_verified_movie_row(
                ident, details, rel_year, best["stream_url"], arch_mins, pop_score,
                seen_tmdb_ids, seen_title_years, existing_archive_ids,
                tmdb_version_counts, tmdb_best_tier, size_mb=best["size_mb"],
                force_18_plus=adult_flag
            ):
                with state_lock:
                    batch_added[0] += 1
                    total_added[0] += 1

        with ThreadPoolExecutor(max_workers=12) as pool:
            futures = [pool.submit(process_doc, entry) for entry in batch_slice]
            for fut in as_completed(futures):
                try:
                    fut.result()
                except Exception:
                    pass

        flush_rejected_buffer(force=True)
        print(f"  ✓ Paket #{batch_num} ({len(batch_slice)}) završen: dodano {batch_added[0]} filmova.", flush=True)

        if phase1_deadline - time.monotonic() < MIN_REMAINING_SECONDS:
            break

    # 2/2 Ugniježđeni multi-file paketi unutar opensource_movies i additional_collections_video
    print("2/2 Provjera multi-file paketa unutar korisničkih bazena...", flush=True)
    bundle_ids = []
    for col in USER_UPLOAD_COLLECTIONS:
        if time.monotonic() > deadline_ts or len(bundle_ids) >= MAX_BUNDLES_PER_RUN:
            break
        q_bundles = (
            f'collection:{col} AND mediatype:movies '
            f'AND (format:"ISO Image" OR title:(iso OR dvd OR pack OR collection OR trilogy OR boxset OR movies OR films)) '
            f'AND item_size:[4500000000 TO 150000000000] '
            f'AND -subject:"tv series" AND -subject:news AND -subject:sports'
        )
        data = safe_get(
            "https://archive.org/advancedsearch.php",
            params={"q": q_bundles, "fl[]": ["identifier", "title"], "sort[]": "downloads desc", "rows": 35, "page": random.randint(1, 5), "output": "json"},
            timeout=12,
        )
        for doc in (data or {}).get("response", {}).get("docs", []):
            b_id = (doc.get("identifier") or "").strip()
            raw_t = doc.get("title") or b_id
            if isinstance(raw_t, list):
                raw_t = raw_t[0]
            if not b_id or b_id in existing_archive_ids or f"bundle_checked:{b_id}" in existing_archive_ids:
                continue
            if CM_NOISE_RE.search(f"{b_id} {raw_t}") or is_explicit_junk_item(b_id, raw_t):
                record_rejected_archive_id(f"bundle_checked:{b_id}", raw_t, 0, existing_archive_ids)
                continue
            bundle_ids.append(b_id)
            if len(bundle_ids) >= MAX_BUNDLES_PER_RUN:
                break

    bundle_added_total = 0
    for b_id in bundle_ids:
        if time.monotonic() > deadline_ts:
            break
        videos, arch_md = get_archive_item_details(b_id, min_size_mb=120, allow_mkv=True)
        if not videos or len(videos) > MAX_VIDEOS_PER_BUNDLE * 2:
            record_rejected_archive_id(f"bundle_checked:{b_id}", b_id, 0, existing_archive_ids)
            continue
        bundle_title_meta = arch_md.get("title") or b_id
        if isinstance(bundle_title_meta, list):
            bundle_title_meta = " ".join(str(x) for x in bundle_title_meta)
        bundle_is_18_plus = is_18_plus_item(b_id, bundle_title_meta)

        deduped = {}
        for v in videos:
            fname = v.get("filename") or ""
            mins = int(v.get("duration_mins") or 0)
            if (mins > 0 and mins < 55) or is_explicit_junk_item(b_id, "", fname):
                continue
            clean_t, y = clean_filename_to_title(fname, fallback_to_stem=True)
            if not clean_t or len(clean_t) < 2:
                continue
            stem_key = re.sub(r"(?i)\.ia$", "", fname.rsplit("/", 1)[-1].rsplit(".", 1)[0]).lower()
            if stem_key not in deduped:
                deduped[stem_key] = (v, clean_t, y)
        if not (3 <= len(deduped) <= MAX_VIDEOS_PER_BUNDLE):
            record_rejected_archive_id(f"bundle_checked:{b_id}", b_id, 0, existing_archive_ids)
            continue

        for v, clean_t, parsed_y in deduped.values():
            if time.monotonic() > deadline_ts:
                break
            params = {"api_key": TMDB_API_KEY, "query": clean_t, "language": "hr-HR", "include_adult": "true"}
            if parsed_y > 0:
                params["year"] = parsed_y
            res = safe_get(f"{TMDB_BASE}/search/movie", params=params)
            hits = (res or {}).get("results", [])
            if not hits:
                continue
            mid = int(hits[0]["id"])
            cat_id = f"{b_id},tmdb-{mid}"
            if cat_id in existing_archive_ids or not can_accept_movie_candidate(mid, tmdb_version_counts, tmdb_best_tier):
                continue
            details = get_tmdb_movie_details(mid)
            if not details or not details["poster_path"]:
                continue

            orig_lang = str(details.get("original_language") or "").lower()
            entry_is_18_plus = (
                bundle_is_18_plus
                or orig_lang == "tl"
                or is_18_plus_item(b_id, v.get("filename") or "", clean_t, details["hr_title"], details["orig_title"])
            )

            # Blokirane jezike i strane sinkronizacije izbacujemo, OSIM ako pripadaju 18+ kolekcijama (npr. Vivamax tl / Kekko Kamen ja)
            if not entry_is_18_plus:
                if contains_blocked_regional_or_arabic(details["orig_title"], details["hr_title"], orig_lang=orig_lang):
                    continue
                if is_foreign_dub_without_english(arch_md, b_id, v.get("filename") or "", orig_lang):
                    continue

            arch_mins = int(v.get("duration_mins") or 0)
            if is_runtime_mismatch(details.get("runtime", 0), arch_mins, is_short_allowed=False, size_mb=v.get("size_mb", 0)):
                continue
            rel_year = extract_year_from_text(details["release_date"]) or parsed_y
            if not verify_stream_url_playable(v["stream_url"]):
                continue
            pop_score = float(details["popularity"]) * 100.0 + (rel_year * 2) + 300.0
            if save_verified_movie_row(
                cat_id, details, rel_year, v["stream_url"], arch_mins, pop_score,
                seen_tmdb_ids, seen_title_years, existing_archive_ids,
                tmdb_version_counts, tmdb_best_tier, size_mb=v.get("size_mb", 0),
                force_18_plus=entry_is_18_plus
            ):
                bundle_added_total += 1
        record_rejected_archive_id(f"bundle_checked:{b_id}", b_id, 0, existing_archive_ids)

    flush_rejected_buffer(force=True)
    elapsed_mins = (time.monotonic() - start_ts) / 60.0
    print(
        f"=== [USER UPLOADS] Završeno za {elapsed_mins:.1f} min! "
        f"Dodano {total_added[0]} pojedinačnih + {bundle_added_total} iz paketa. "
        f"Ukupno filmova u katalogu: {len(seen_tmdb_ids)} ===",
        flush=True,
    )


if __name__ == "__main__":
    run_community_media_sync()
