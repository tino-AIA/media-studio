import os
import re
import threading
import time
import urllib.parse
from datetime import datetime, timezone
import requests
from requests.adapters import HTTPAdapter

TMDB_API_KEY = os.environ["TMDB_API_KEY"].strip()
RAW_SB_URL = os.environ["SUPABASE_URL"].strip().rstrip("/")
SUPABASE_URL = RAW_SB_URL[:-8] if RAW_SB_URL.endswith("/rest/v1") else RAW_SB_URL
SUPABASE_SERVICE_KEY = os.environ["SUPABASE_SERVICE_KEY"].strip()

TMDB_BASE = "https://api.themoviedb.org/3"
TMDB_IMG_POSTER = "https://image.tmdb.org/t/p/w500"
TMDB_IMG_BACKDROP = "https://image.tmdb.org/t/p/w1280"

session = requests.Session()
adapter = HTTPAdapter(pool_connections=48, pool_maxsize=48, max_retries=1)
session.mount("https://", adapter)
session.mount("http://", adapter)
session.headers.update({
    "User-Agent": "MediaStudioCatalogSync/4.0 (AndroidTV App; contact@mediastudio.local)"
})

state_lock = threading.Lock()
_translation_cache = {}

SB_HEADERS = {
    "apikey": SUPABASE_SERVICE_KEY,
    "Content-Type": "application/json",
    "Prefer": "resolution=merge-duplicates,return=representation"
}
if not SUPABASE_SERVICE_KEY.startswith("sb_"):
    SB_HEADERS["Authorization"] = f"Bearer {SUPABASE_SERVICE_KEY}"

BLOCKED_ORIGINAL_LANGS = {
    "ar", "hi", "ta", "te", "ml", "bn", "ur", "pa", "mr", "gu", "kn", "si", "ne", "fa",
    "id", "ms", "jv", "su", "tl"
}

BLOCKED_REGIONAL_AND_ARABIC_KEYWORDS = [
    "bollywood", "tollywood", "kollywood", "hindi", "telugu", "tamil", "urdu",
    "punjabi", "desi", "bengali", "malayalam", "marathi", "gujarati", "kannada",
    "arabic", "arab", "islam", "quran", "khutba", "dourous", "muslim", "sheikh",
    "hindi dubbed", "dual audio hindi", "urdu dubbed",
    "indonesian", "malay", "tagalog", "bahasa", "sinetron"
]

ARABIC_OR_INDIC_SCRIPT_RE = re.compile(r"[\u0600-\u06FF\u0750-\u077F\u0900-\u0D7F]")
NON_LATIN_TITLE_RE = re.compile(r"[^\u0000-\u024F\u1E00-\u1EFF\s\d\W]")

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
    "documentary about", "making of", "totally rad show", "half in the bag",
    "redlettermedia", "nostalgia critic", "cinemasins", "honest trailers", "watchmojo"
] + HENTAI_AND_ADULT_KEYWORDS + BLOCKED_REGIONAL_AND_ARABIC_KEYWORDS

EXPLICIT_DUB_KEYWORDS = [
    "dublado", "dublagem", "doblado", "doblaje", "ozvuchka", "дубляж", "озвучка",
    "russian dub", "spanish dub", "portuguese dub", "portugues dub", "french dub",
    "german dub", "italian dub", "hindi dubbed", "truefrench", "vostfr",
    "audio latino", "latino", "castellano", "espanol", "español", "sub espanol", "sub español",
    "lektor pl", "lektor polski", "polski lektor", "dubbing pl", "polski dubbing", "polish dub", "pldub",
    "staffel", "german audio", "deutsch dub"
]

NON_ENGLISH_LANG_TOKENS = {
    "por", "portuguese", "portugues", "português",
    "spa", "spanish", "espanol", "español", "castellano", "latino", "temporada",
    "rus", "russian", "русский",
    "fre", "fra", "french", "francais", "français", "saison",
    "ger", "deu", "german", "deutsch", "staffel", "folge",
    "ita", "italian", "italiano", "stagione",
    "hin", "hindi", "ara", "arabic", "tur", "turkish",
    "pol", "polish", "polski", "lektor", "ind", "indonesian", "may", "malay"
}

RELEASE_TAG_PATTERN = re.compile(
    r"(?i)\b("
    r"1080p|2160p|4k|720p|480p|360p|240p|bluray|blu-ray|bdrip|brrip|webrip|web-dl|webdl|"
    r"dvdrip|hdrip|vhs|vhdrip|laserdisc|ldrip|hdtv|x264|x265|h264|h265|hevc|avc|xvid|divx|"
    r"aac|ac3|dts|ddp5\s*1|ddp2\s*0|10bit|8bit|mp4|mkv|avi|mov|ia|remastered|restored|"
    r"colorized|extended|unrated|theatrical|directors\s*cut|director's\s*cut|uncut|"
    r"alternative\s*edition|redux|fanedit|fan\s*edit|the\s*complete\s*saga|complete\s*saga|"
    r"special\s*edition|tv\s*version|vimeo|flac|subs|"
    r"full\s*movie|movie|film|subtitles|subbed|dubbed|eng|english|multi|dual\s*audio|"
    r"yify|yts|rarbg|galaxyrg|galaxy-rg|etrg|sparks|fgt|evo|bone|sartre|anoxmous|juggs|"
    r"shaanig|mkvcage|pahe|psa|criterion|complete\s*series|complete\s*season|tv\s*series|"
    r"the\s*original\s*series|original\s*series|tos|no\s*subs|widescreen|fullscreen"
    r")\b"
)

VARIANT_TAG_RE = re.compile(
    r"(?i)[\(\[]\s*("
    r"the\s+complete\s+novel|complete\s+novel|the\s+complete\s+saga|complete\s+saga|"
    r"alternative\s+edition(?:\s+redux)?|redux|fanedit|fan\s+edit|special\s+edition|tv\s+version|"
    r"director'?s?\s*cut|theatrical\s*cut|theatrical|vimeo|mkv-flac-subs|"
    r"extended\s*cut|extended|final\s*cut|workprint|unrated|uncut|tv\s*cut|us\s*cut|eu\s*cut|uk\s*cut|"
    r"restored|remastered|colorized|part\s*\d+|pt\.?\s*\d+|cd\s*\d+|disc\s*\d+"
    r")\s*[\)\]]"
)

EXTENDED_CUT_TAG_RE = re.compile(
    r"(?i)\b("
    r"redux|extended|complete\s+saga|complete\s+novel|alternative\s+edition|"
    r"director'?s?\s*cut|tv\s*cut|tv\s*version|uncut|special\s+edition|fanedit|fan\s*edit"
    r")\b"
)

WIDESCREEN_TAG_RE = re.compile(
    r"(?i)\b(widescreen|16x9|16_9|16-9|1080p|2160p|4k|720p|bluray|blu-ray|bdrip|brrip|web-dl|webdl|webrip)\b"
)

FULLSCREEN_4_3_TAG_RE = re.compile(
    r"(?i)\b(fullscreen|full[\s._-]*screen|4x3|4_3|4-3|pan[\s._-]*(?:and|&)[\s._-]*scan|vhs|vhdrip|laserdisc|ldrip)\b"
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


def safe_get(url, params=None, timeout=12):
    for attempt in range(3):
        try:
            r = session.get(url, params=params, timeout=timeout)
            if r.status_code == 200:
                return r.json()
            if r.status_code == 429:
                time.sleep(2.0 * (attempt + 1))
        except Exception:
            time.sleep(0.4)
    return None


def translate_to_croatian(text):
    if not text or len(text.strip()) < 2:
        return text
    key = text[:300]
    with state_lock:
        if key in _translation_cache:
            return _translation_cache[key]
    try:
        r = session.get(
            "https://translate.googleapis.com/translate_a/single",
            params={"client": "gtx", "sl": "auto", "tl": "hr", "dt": "t", "q": text[:1500]},
            timeout=8
        )
        if r.status_code == 200:
            data = r.json()
            translated = "".join(part[0] for part in (data[0] or []) if part and part[0]).strip()
            if translated:
                with state_lock:
                    _translation_cache[key] = translated
                return translated
    except Exception:
        pass
    return text


def verify_stream_url_playable(url):
    if not url or not url.startswith("http"):
        return False
    try:
        r = session.get(
            url,
            headers={"Range": "bytes=0-1"},
            allow_redirects=True,
            stream=True,
            timeout=8
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
    conflict_cols = [c.strip() for c in conflict_col.split(",") if c.strip()]
    if conflict_cols:
        deduped_map = {}
        for row in rows:
            key = tuple(
                str(row.get(c)).strip().lower() if isinstance(row.get(c), str) else row.get(c)
                for c in conflict_cols
            )
            deduped_map[key] = row
        rows = list(deduped_map.values())

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


def fetch_existing_catalog_state(media_type_filter=None):
    """Dohvaća sve postojeće zapise iz Supabasea (uz paginaciju po 1000) bez brisanja valjanih stavki."""
    existing_archive_ids = set()
    seen_tmdb_ids = set()
    seen_title_years = set()
    all_rows = []

    offset = 0
    page_size = 1000
    while True:
        headers = dict(SB_HEADERS)
        headers["Range"] = f"{offset}-{offset + page_size - 1}"
        query_url = f"{SUPABASE_URL}/rest/v1/catalog_items?select=id,archive_id,tmdb_id,title,media_type,release_year,genres,description_hr,stream_url,episodes,is_valid&order=id.asc"
        if media_type_filter:
            query_url += f"&media_type=eq.{media_type_filter}"
        try:
            r = session.get(query_url, headers=headers, timeout=20)
            if r.status_code not in (200, 206):
                break
            batch = r.json()
            if not batch:
                break
            all_rows.extend(batch)
            if len(batch) < page_size:
                break
            offset += page_size
        except Exception:
            break

    for row in all_rows:
        ident = row.get("archive_id") or ""
        if ident:
            existing_archive_ids.add(ident)
        if not row.get("is_valid", True):
            continue
        if contains_hentai_or_adult(row.get("title"), row.get("description_hr"), ident) or \
           contains_blocked_regional_or_arabic(row.get("title"), row.get("description_hr"), ident):
            supabase_patch_row("catalog_items", row["id"], {"is_valid": False})
            continue

        tmdb_id = row.get("tmdb_id") or 0
        if tmdb_id > 0:
            seen_tmdb_ids.add(tmdb_id)
        norm_t = normalize_for_exact_title_compare(row.get("title") or "")
        if norm_t:
            seen_title_years.add((norm_t, row.get("release_year") or 0))

    return existing_archive_ids, seen_tmdb_ids, seen_title_years, all_rows


def contains_hentai_or_adult(*texts):
    blob = " ".join(str(t) for t in texts if t).lower()
    return any(kw in blob for kw in HENTAI_AND_ADULT_KEYWORDS)


def contains_blocked_regional_or_arabic(*texts, orig_lang=""):
    if orig_lang and orig_lang.lower() in BLOCKED_ORIGINAL_LANGS:
        return True
    blob = " ".join(str(t) for t in texts if t)
    if ARABIC_OR_INDIC_SCRIPT_RE.search(blob):
        return True
    blob_lower = blob.lower()
    return any(re.search(rf"\b{re.escape(kw)}\b", blob_lower) for kw in BLOCKED_REGIONAL_AND_ARABIC_KEYWORDS)


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


def compute_aspect_tier(filename, width=0, height=0):
    """2 = potvrđeni 16:9 / widescreen / HD, 1 = nepoznat omjer, 0 = potvrđeni 4:3 / fullscreen / VHS."""
    if width > 0 and height > 0:
        ratio = width / float(height)
        if ratio >= 1.55:
            return 2
        if ratio < 1.48:
            return 0

    lower = (filename or "").lower()
    if WIDESCREEN_TAG_RE.search(lower):
        return 2
    if FULLSCREEN_4_3_TAG_RE.search(lower):
        return 0
    return 1


def pick_best_video_file(video_files):
    """Daje snažan prioritet 16:9 / widescreen verzijama, zatim izvornim .mp4/.mkv datotekama."""
    def sort_key(f):
        fname_lower = f["filename"].lower()
        is_deriv = f.get("is_derivative", False)
        aspect_tier = f.get("aspect_tier", compute_aspect_tier(fname_lower, f.get("width", 0), f.get("height", 0)))
        if not is_deriv and fname_lower.endswith(".mp4"):
            format_tier = 0
        elif not is_deriv:
            format_tier = 1
        else:
            format_tier = 2
        return (-aspect_tier, format_tier, -f["size_mb"])

    ordered = sorted(video_files, key=sort_key)
    for candidate in ordered:
        if verify_stream_url_playable(candidate["stream_url"]):
            return candidate
    return ordered[0]


def determine_primary_genres(genre_ids, companies, orig_title, hr_title, overview, hint_genre=None):
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
    s = VARIANT_TAG_RE.sub(" ", s)
    s = re.sub(r"[._]+", " ", s)
    s = re.sub(r"(?i)^(?:walt\s+disney(?:'s|\s+pictures|\s+presents)?|disney(?:'s)?|pixar(?:'s)?|dreamworks(?:'s)?|studio\s+ghibli)\s*[-:]?\s*", "", s)
    s = re.sub(r"[\(\[\{]\s*(19\d{2}|20\d{2})\s*[\)\]\}]", " ", s)
    s = RELEASE_TAG_PATTERN.sub(" ", s)
    s = re.sub(r"\b(19\d{2}|20\d{2})\b\s*$", " ", s)
    s = re.sub(r"[^\w\s]", " ", s.lower())
    s = re.sub(r"\s+", " ", s).strip()
    s = re.sub(r"^(the|a|an)\s+", "", s).strip()
    return s


def titles_match_flexibly(arch_norm, norm_candidates):
    """Dopušta točno poklapanje ili poklapanje kad Archive naslov sadrži podnaslov / ime redatelja."""
    if not arch_norm or not norm_candidates:
        return False
    if arch_norm in norm_candidates:
        return True
    for cand in norm_candidates:
        if len(cand) >= 4 and (arch_norm.startswith(cand + " ") or arch_norm.endswith(" " + cand)):
            return True
    return False


def extract_year_from_text(text):
    m = re.search(r"\b(19\d{2}|20\d{2})\b", str(text))
    return int(m.group(1)) if m else 0


def clean_filename_to_title(filename, fallback_to_stem=False):
    base = filename.rsplit("/", 1)[-1]
    name = re.sub(r"\.(mp4|mkv|avi|ogv|mov)$", "", base, flags=re.IGNORECASE)

    paren_year = re.search(r"[\(\[]\s*(19\d{2}|20\d{2})\s*[\)\]]", name)
    lead_year = re.match(r"^\s*(19\d{2}|20\d{2})(?=[\s\-_.:])", name)
    all_years = re.findall(r"\b(19\d{2}|20\d{2})\b", name)

    if paren_year:
        year = int(paren_year.group(1))
    elif lead_year:
        year = int(lead_year.group(1))
    elif all_years:
        year = int(all_years[-1])
    else:
        year = 0

    clean = re.sub(r"\[[^\]]*\]", " ", name)
    clean = re.sub(r"\(\([^)]*\)\)", " ", clean)
    clean = VARIANT_TAG_RE.sub(" ", clean)
    clean = re.sub(r"[._]+", " ", clean)

    if year:
        clean = re.sub(rf"[\(\[]\s*{year}\s*[\)\]]", " ", clean)
        clean = re.sub(rf"^\s*\(?\b{year}\b\)?[\s\-_.:)]*(?:\d{{1,2}}[\s\-_.:)]+)?(?=[A-Za-zČĆŽŠĐ])", " ", clean)
        clean = re.sub(rf"(?<=\S)\s+\b{year}\b\s*$", " ", clean)

    clean = RELEASE_TAG_PATTERN.sub(" ", clean)
    clean = re.sub(r"\(\s*(?:cut|version|edition)?\s*\)|\[\s*\]", " ", clean, flags=re.IGNORECASE)
    clean = re.sub(r"^\s*\d{1,3}\s*[\.\-\)\]]+\s*", "", clean)
    clean = re.sub(r"^\s*[12]\s+(?=[A-ZČĆŽŠĐ])", "", clean)
    clean = re.sub(r"\s+", " ", clean).strip(" -–—.")
    if not clean and fallback_to_stem:
        clean = re.sub(r"[._]+", " ", name).strip()
    return clean.strip(), year


def get_archive_item_details(identifier, min_size_mb=35, allow_mkv=False):
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

        try:
            width = int(float(f.get("width") or 0))
        except (ValueError, TypeError):
            width = 0
        try:
            height = int(float(f.get("height") or 0))
        except (ValueError, TypeError):
            height = 0
        aspect_ratio = round(width / float(height), 3) if (width > 0 and height > 0) else 0.0

        file_mins = parse_duration_minutes(f.get("length")) or meta_runtime
        encoded_name = "/".join(urllib.parse.quote(p) for p in fname.split("/"))
        stream_url = f"https://archive.org/download/{identifier}/{encoded_name}"
        videos.append({
            "filename": fname,
            "size_mb": size_mb,
            "duration_mins": file_mins,
            "stream_url": stream_url,
            "is_derivative": ".ia.mp4" in fname.lower() or f.get("source") == "derivative",
            "width": width,
            "height": height,
            "aspect_ratio": aspect_ratio,
            "aspect_tier": compute_aspect_tier(fname, width, height)
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

    en_data = None
    orig_title = str(data.get("original_title") or data.get("title") or "").strip()
    hr_title = str(data.get("title") or orig_title).strip()

    if NON_LATIN_TITLE_RE.search(hr_title):
        en_data = safe_get(f"{TMDB_BASE}/movie/{tmdb_id}", {"api_key": TMDB_API_KEY, "language": "en-US"})
        en_title = str((en_data or {}).get("title") or "").strip()
        if en_title and not NON_LATIN_TITLE_RE.search(en_title):
            translated_title = translate_to_croatian(en_title)
            hr_title = translated_title if (translated_title and not NON_LATIN_TITLE_RE.search(translated_title)) else en_title
            if NON_LATIN_TITLE_RE.search(orig_title):
                orig_title = en_title
        else:
            translated_orig = translate_to_croatian(hr_title)
            if translated_orig and not NON_LATIN_TITLE_RE.search(translated_orig):
                hr_title = translated_orig

    overview = (data.get("overview") or "").strip()
    if not overview:
        if en_data is None:
            en_data = safe_get(f"{TMDB_BASE}/movie/{tmdb_id}", {"api_key": TMDB_API_KEY, "language": "en-US"})
        en_overview = ((en_data or {}).get("overview") or "").strip()
        if en_overview:
            overview = translate_to_croatian(en_overview)
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
        "orig_title": orig_title,
        "hr_title": hr_title,
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


def has_multiple_tmdb_tv_versions(orig_name):
    res = safe_get(f"{TMDB_BASE}/search/tv", {"api_key": TMDB_API_KEY, "query": orig_name, "language": "en-US"})
    target_norm = normalize_for_exact_title_compare(orig_name)
    years = set()
    for r in (res or {}).get("results", []):
        if normalize_for_exact_title_compare(r.get("original_name") or r.get("name") or "") == target_norm:
            y = extract_year_from_text(r.get("first_air_date") or "")
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
    chosen_filename="",
    chosen_size_mb=0
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
    if contains_blocked_regional_or_arabic(ident_lower, raw_arch_title, raw_arch_desc, *expected_titles, orig_lang=orig_lang):
        return False, "blocked_regional_or_arabic"

    if is_foreign_dub_without_english(archive_md, identifier, chosen_filename, orig_lang):
        return False, "foreign_dub_without_english"

    title_and_id_meta = f"{ident_lower} {raw_arch_title.lower()}"
    if any(kw in title_and_id_meta for kw in SPAM_OR_FAKE_KEYWORDS):
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

    if norm_candidates and (
        not titles_match_flexibly(arch_norm_title, norm_candidates)
        and not titles_match_flexibly(arch_norm_ident, norm_candidates)
    ):
        return False, f"title_mismatch ('{arch_norm_title}' != {list(norm_candidates)})"

    arch_year = 0
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

    is_anim = 16 in (tmdb_details.get("genre_ids") or []) if tmdb_details else False
    is_extended_cut = bool(EXTENDED_CUT_TAG_RE.search(f"{ident_lower} {raw_arch_title.lower()} {chosen_filename.lower()}"))

    if tmdb_runtime > 0:
        if archive_mins > 0:
            min_allowed = int(tmdb_runtime * 0.85)
            max_multiplier = 1.45 if is_extended_cut else 1.18
            max_allowed = int(tmdb_runtime * max_multiplier)
            if archive_mins < min_allowed or archive_mins > max_allowed:
                return False, f"runtime_mismatch (archive={archive_mins}m vs tmdb={tmdb_runtime}m)"
        else:
            min_size_when_no_runtime = 120 if is_anim else 350
            if chosen_size_mb < min_size_when_no_runtime:
                return False, f"too_small_without_runtime ({chosen_size_mb:.0f}MB)"
    else:
        if archive_mins > 0 and archive_mins < 65:
            return False, f"too_short_no_tmdb_runtime ({archive_mins}m)"
        if archive_mins <= 0 and chosen_size_mb < 350:
            return False, f"too_small_no_runtime ({chosen_size_mb:.0f}MB)"

    year_confirmed = (expected_year > 0 and arch_year > 0 and abs(arch_year - expected_year) <= 1)
    must_check_credits = (require_credits and not year_confirmed) or (expected_year >= 2024)
    if must_check_credits and tmdb_details:
        if not credits_match_archive_metadata(
            archive_md, identifier, tmdb_details.get("directors", []), tmdb_details.get("cast", [])
        ):
            return False, "credits_not_verified_in_metadata"

    return True, "ok"
