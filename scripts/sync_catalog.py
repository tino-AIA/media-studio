import os
import re
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
    "User-Agent": "MediaStudioCatalogSync/1.4 (AndroidTV App; contact@mediastudio.local)"
})

SB_HEADERS = {
    "apikey": SUPABASE_SERVICE_KEY,
    "Content-Type": "application/json",
    "Prefer": "resolution=merge-duplicates,return=representation"
}
if not SUPABASE_SERVICE_KEY.startswith("sb_"):
    SB_HEADERS["Authorization"] = f"Bearer {SUPABASE_SERVICE_KEY}"

SPAM_OR_FAKE_KEYWORDS = [
    "joyblix", "for the full movie or to download", "superfans", "bestof", "best of",
    "ending explained", "movie review", "film review", "reaction", "gameplay",
    "walkthrough", "playthrough", "vtuber", "podcast", "trailer", "teaser",
    "behind the scenes", "fan made", "fan-made", "parody", "recap", "soundtrack only",
    "promo", "commercial", "bloopers", "deleted scenes", "starting soon", "twitch",
    "live stream", "livestream", "stage play", "theater production", "homelessness",
    "documentary about", "making of"
]

RELEASE_TAG_PATTERN = re.compile(
    r"(?i)\b("
    r"1080p|2160p|4k|720p|480p|360p|240p|bluray|blu-ray|bdrip|brrip|webrip|web-dl|webdl|"
    r"dvdrip|hdrip|vhs|vhdrip|laserdisc|ldrip|hdtv|x264|x265|h264|h265|hevc|avc|xvid|divx|"
    r"aac|ac3|dts|ddp5\s*1|ddp2\s*0|10bit|8bit|mp4|mkv|avi|mov|ia|remastered|restored|"
    r"colorized|extended|unrated|theatrical|directors\s*cut|director's\s*cut|uncut|"
    r"full\s*movie|movie|film|subtitles|subbed|dubbed|eng|english|multi|dual\s*audio|"
    r"yify|yts|rarbg|galaxyrg|galaxy-rg|etrg|sparks|fgt|evo|bone|sartre|anoxmous|juggs|"
    r"shaanig|mkvcage|pahe|psa|criterion"
    r")\b"
)

TMDB_MOVIE_GENRE_MAP = {
    28: "Akcija i avantura",
    12: "Akcija i avantura",
    16: "Animirani klasici",
    35: "Komedija",
    80: "Krimi",
    18: "Drama",
    14: "Sci-Fi klasici",
    36: "Drama",
    27: "Horor",
    9648: "Triler i misterija",
    10749: "Romantični",
    878: "Sci-Fi klasici",
    53: "Triler i misterija",
    10752: "Ratni",
    37: "Vestern"
}

TMDB_TV_GENRE_MAP = {
    80: "Krimi, detektivske i triler serije",
    9648: "Krimi, detektivske i triler serije",
    53: "Krimi, detektivske i triler serije",
    10765: "Sci-Fi i fantastika serije",
    878: "Sci-Fi i fantastika serije",
    14: "Sci-Fi i fantastika serije",
    35: "Komedije i sitcomi",
    10759: "Avanturističke serije",
    12: "Avanturističke serije",
    28: "Avanturističke serije",
    10768: "Avanturističke serije"
}

CLASSIC_TV_SEED_TITLES = [
    "The Twilight Zone", "Alfred Hitchcock Presents", "Columbo", "The X-Files",
    "Star Trek", "Star Trek: The Next Generation", "Doctor Who", "Twin Peaks",
    "Mr. Bean", "Only Fools and Horses", "Fawlty Towers", "Sherlock",
    "Band of Brothers", "Freaks and Geeks", "The Sopranos", "Seinfeld",
    "3rd Rock from the Sun", "Everybody Loves Raymond", "ALF", "Battlestar Galactica",
    "Buck Rogers in the 25th Century", "The Outer Limits", "Tales from the Crypt",
    "Agatha Christie's Poirot", "Blackadder", "Monty Python's Flying Circus",
    "Batman", "The Addams Family", "The Munsters", "I Love Lucy",
    "The Saint", "The Prisoner", "Space: 1999", "Red Dwarf",
    "Babylon 5", "Farscape", "Stargate SG-1", "Buffy the Vampire Slayer", "Firefly"
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
    """Uklanja samo godinu i tehničke oznake ripa, zadržavajući sve sadržajne riječi naslova."""
    s = re.sub(r"\.(mp4|mkv|avi|ogv|mov)$", "", str(raw_title), flags=re.IGNORECASE)
    s = re.sub(r"[._]+", " ", s)
    s = re.sub(r"[\(\[\{]\s*(19\d{2}|20\d{2})\s*[\)\]\}]", " ", s)
    s = RELEASE_TAG_PATTERN.sub(" ", s)
    s = re.sub(r"\b(19\d{2}|20\d{2})\b\s*$", " ", s)
    s = re.sub(r"[^\w\s]", " ", s.lower())
    s = re.sub(r"\s+", " ", s).strip()
    s = re.sub(r"^(the|a|an)\s+", "", s).strip()
    return s


def extract_year_from_text(text):
    m = re.search(r"\b(19\d{2}|20\d{2})\b", str(text))
    return int(m.group(1)) if m else 0


def clean_filename_to_title(filename):
    base = filename.rsplit("/", 1)[-1]
    name = re.sub(r"\.(mp4|mkv|avi|ogv|mov)$", "", base, flags=re.IGNORECASE)
    year_match = re.search(r"\b(19\d{2}|20\d{2})\b", name)
    year = int(year_match.group(1)) if year_match else 0

    clean = re.sub(r"[._]+", " ", name)
    clean = RELEASE_TAG_PATTERN.sub("", clean)
    if year:
        clean = re.sub(rf"\(?\b{year}\b\)?", "", clean)
    clean = re.sub(r"\(\s*\)|\[\s*\]", "", clean)
    clean = re.sub(r"^\s*\d+[\.\-\s]+", "", clean)
    clean = re.sub(r"\s+", " ", clean).strip(" -")
    return clean.strip(), year


def get_archive_item_details(identifier, min_size_mb=35):
    time.sleep(0.15)
    meta = safe_get(f"https://archive.org/metadata/{identifier}")
    if not meta or meta.get("is_dark"):
        return [], {}

    md = meta.get("metadata") or {}
    meta_runtime = parse_duration_minutes(md.get("runtime"))
    files = meta.get("files", [])
    mp4s = []

    for f in files:
        fname = f.get("name", "")
        if not fname.lower().endswith(".mp4"):
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
        mp4s.append({
            "filename": fname,
            "size_mb": size_mb,
            "duration_mins": file_mins,
            "stream_url": stream_url,
            "is_derivative": ".ia.mp4" in fname.lower() or f.get("source") == "derivative"
        })

    return mp4s, md


def get_tmdb_movie_details(tmdb_id):
    if not tmdb_id:
        return None
    data = safe_get(
        f"{TMDB_BASE}/movie/{tmdb_id}",
        {"api_key": TMDB_API_KEY, "language": "hr-HR", "append_to_response": "credits"}
    )
    if not data:
        return None
    overview = (data.get("overview") or "").strip()
    if not overview:
        en_data = safe_get(f"{TMDB_BASE}/movie/{tmdb_id}", {"api_key": TMDB_API_KEY, "language": "en-US"})
        overview = ((en_data or {}).get("overview") or "").strip()

    credits = data.get("credits") or {}
    directors = [
        c.get("name", "") for c in credits.get("crew", [])
        if c.get("job") == "Director" and c.get("name")
    ]
    cast = [
        c.get("name", "") for c in (credits.get("cast") or [])[:6]
        if c.get("name")
    ]

    return {
        "runtime": int(data.get("runtime") or 0),
        "status": str(data.get("status") or ""),
        "release_date": str(data.get("release_date") or ""),
        "orig_title": str(data.get("original_title") or data.get("title") or ""),
        "hr_title": str(data.get("title") or data.get("original_title") or ""),
        "overview_hr": overview,
        "directors": directors,
        "cast": cast
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
    """Provjerava postoji li više različitih filmova s istim naslovom (npr. Titanic 1997 i Titanic 1953)."""
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
    """Provjerava nalazi li se prezime redatelja ili glavnog glumca u metapodacima stavke."""
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
    require_credits=False
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

    # 1. Točno podudaranje očišćenog naslova (blokira "Rocky Horror" za "Rocky" i "Hope in the Heartland" za "Heartland")
    norm_candidates = {normalize_for_exact_title_compare(t) for t in expected_titles if t}
    norm_candidates.discard("")
    arch_norm_title = normalize_for_exact_title_compare(raw_arch_title or identifier)
    arch_norm_ident = normalize_for_exact_title_compare(identifier)

    if norm_candidates and (arch_norm_title not in norm_candidates and arch_norm_ident not in norm_candidates):
        return False, f"title_mismatch ('{arch_norm_title}' != {list(norm_candidates)})"

    # 2. Stroga provjera godine izdanja
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

    # 3. Stroga provjera trajanja (90% - 112% TMDB trajanja)
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

    # 4. Za novije filmove (2024+) i filmove s više verzija (npr. Titanic), obvezna potvrda redatelja ili glumca
    must_check_credits = require_credits or (expected_year >= 2024)
    if must_check_credits and tmdb_details:
        if not credits_match_archive_metadata(
            archive_md, identifier, tmdb_details.get("directors", []), tmdb_details.get("cast", [])
        ):
            return False, "credits_not_verified_in_metadata"

    return True, "ok"


def audit_single_movie_row(row):
    ident = row["archive_id"]
    tmdb_id = row.get("tmdb_id") or 0
    mp4s, arch_md = get_archive_item_details(ident, min_size_mb=20)
    if not mp4s:
        supabase_patch_row("catalog_items", row["id"], {"is_valid": False, "archive_runtime": 0})
        return False, f"[FALSE - nedostupan] {row['title']}"

    best = max(mp4s, key=lambda x: x["size_mb"])
    arch_mins = best["duration_mins"]
    tmdb_info = get_tmdb_movie_details(tmdb_id) if tmdb_id else None

    expected_titles = [row["title"]]
    expected_year = row.get("release_year") or 0
    require_credits = False
    if tmdb_info:
        expected_titles.extend([tmdb_info["orig_title"], tmdb_info["hr_title"]])
        if not expected_year:
            expected_year = extract_year_from_text(tmdb_info["release_date"])
        require_credits = has_multiple_tmdb_versions(tmdb_info["orig_title"])

    ok, reason = validate_movie_match(
        ident, arch_md, arch_mins, tmdb_info, expected_titles, expected_year, require_credits=require_credits
    )
    supabase_patch_row("catalog_items", row["id"], {
        "is_valid": ok,
        "tmdb_runtime": tmdb_info["runtime"] if tmdb_info else 0,
        "archive_runtime": arch_mins
    })
    if not ok:
        return False, f"[FALSE - {reason}] {row['title']} ({ident})"
    return True, None


def audit_existing_catalog():
    print("1/5 Stroga revizija postojećih filmova (naslov, trajanje, godina, redatelj/glumci)...", flush=True)
    r = session.get(
        f"{SUPABASE_URL}/rest/v1/catalog_items?select=id,archive_id,tmdb_id,title,media_type,release_year&media_type=eq.movie&limit=2500",
        headers=SB_HEADERS,
        timeout=20
    )
    if r.status_code != 200:
        print(f"[Upozorenje] Dohvat tablice vratio status {r.status_code}: {r.text[:200]}", flush=True)
        return

    rows = r.json()
    valid_count = 0
    invalid_count = 0

    with ThreadPoolExecutor(max_workers=5) as pool:
        futures = [pool.submit(audit_single_movie_row, row) for row in rows]
        for fut in as_completed(futures):
            ok, msg = fut.result()
            if ok:
                valid_count += 1
            else:
                invalid_count += 1
                if msg:
                    print(f"  {msg}", flush=True)

    print(f"  Rezultat revizije filmova: {valid_count} potvrđenih, {invalid_count} označeno kao FALSE.", flush=True)


def search_verified_archive_for_movie(tmdb_details, year):
    if not tmdb_details:
        return None, None, 0
    if tmdb_details["status"] and tmdb_details["status"] != "Released":
        return None, None, 0
    if is_future_release(tmdb_details["release_date"]):
        return None, None, 0
    if tmdb_details["runtime"] > 0 and tmdb_details["runtime"] < 60:
        return None, None, 0

    orig_title = tmdb_details["orig_title"]
    hr_title = tmdb_details["hr_title"]
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
            "rows": 10,
            "output": "json"
        }
    )
    docs = (data or {}).get("response", {}).get("docs", [])
    expected_titles = [orig_title, hr_title]

    for doc in docs:
        ident = doc.get("identifier")
        if not ident or ident.lower().startswith(("youtube-", "yt-")):
            continue
        mp4s, arch_md = get_archive_item_details(ident, min_size_mb=220)
        if not mp4s:
            continue
        best = max(mp4s, key=lambda x: x["size_mb"])
        arch_mins = best["duration_mins"]

        ok, _ = validate_movie_match(
            ident, arch_md, arch_mins, tmdb_details, expected_titles, year, require_credits=require_credits
        )
        if ok:
            return ident, best["stream_url"], arch_mins

    return None, None, 0


def sync_tmdb_movies():
    print("2/5 Sinkronizacija provjerenih TMDB filmova...", flush=True)
    seen_tmdb = set()
    candidates = []

    for endpoint in ["movie/popular", "movie/top_rated"]:
        for page in range(1, 13):
            data = safe_get(f"{TMDB_BASE}/{endpoint}", {"api_key": TMDB_API_KEY, "language": "hr-HR", "page": page})
            for m in (data or {}).get("results", []):
                if m["id"] in seen_tmdb:
                    continue
                seen_tmdb.add(m["id"])
                rel_date = m.get("release_date") or ""
                if is_future_release(rel_date):
                    continue
                candidates.append(m)

    def process_movie(m):
        rel_date = m.get("release_date") or ""
        year = int(rel_date[:4]) if len(rel_date) >= 4 and rel_date[:4].isdigit() else 0
        details = get_tmdb_movie_details(m["id"])
        if not details:
            return None

        arch_id, stream_url, arch_mins = search_verified_archive_for_movie(details, year)
        if not arch_id:
            return None

        genres = list({TMDB_MOVIE_GENRE_MAP[g] for g in m.get("genre_ids", []) if g in TMDB_MOVIE_GENRE_MAP})
        row = {
            "archive_id": arch_id,
            "tmdb_id": m["id"],
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
            "is_featured": float(m.get("vote_average") or 0) >= 7.8,
            "is_valid": True,
            "tmdb_runtime": details["runtime"],
            "archive_runtime": arch_mins
        }
        supabase_upsert("catalog_items", [row], "archive_id")
        return f"  + Film: {row['title']} ({year}) [{arch_mins}m / TMDB {details['runtime']}m]"

    with ThreadPoolExecutor(max_workers=5) as pool:
        futures = [pool.submit(process_movie, m) for m in candidates]
        for fut in as_completed(futures):
            msg = fut.result()
            if msg:
                print(msg, flush=True)


def parse_season_and_episode(item_title, filename, total_seasons_hint=0):
    combined = f"{item_title} / {filename}"
    for pat in [
        r"(?i)\bS(\d{1,2})\s*[-_.]?\s*E(\d{1,3})\b",
        r"(?i)\b(\d{1,2})x(\d{1,3})\b",
        r"(?i)Season\s*(\d{1,2}).*?(?:Episode|Ep\.?|E)\s*(\d{1,3})\b",
        r"(?i)Sezona\s*(\d{1,2}).*?(?:Epizoda|Ep\.?|E)\s*(\d{1,3})\b"
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
        if s_num < 1 or s_num > 50:
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
            episode_guide[(s_num, ep_no)] = (final_ep_name, ep_rt or avg_runtime)

        if not hr_season and en_map:
            for ep_no, (en_name, ep_rt) in en_map.items():
                episode_guide[(s_num, ep_no)] = (en_name, ep_rt or avg_runtime)

    return episode_guide, avg_runtime, overview_hr, num_seasons


def aggregate_series_from_archive(tmdb_id, orig_name, hr_name):
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
            "rows": 40,
            "output": "json"
        }
    )
    docs = (data or {}).get("response", {}).get("docs", [])
    if not docs:
        return []

    norm_series = re.sub(r"[^\w\s]", " ", orig_name.lower()).strip()
    norm_series_words = [w for w in norm_series.split() if len(w) >= 2]
    phrase_regex = re.compile(rf"\b{re.escape(norm_series)}\b")

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

        if not phrase_regex.search(norm_doc):
            if len(norm_series_words) < 2 or not all(w in norm_doc.split() for w in norm_series_words):
                continue

        mp4s, arch_md = get_archive_item_details(ident, min_size_mb=35)
        if not mp4s:
            continue

        item_title_meta = arch_md.get("title") or doc_title
        if isinstance(item_title_meta, list):
            item_title_meta = item_title_meta[0]

        for m in mp4s:
            s_num, e_num = parse_season_and_episode(str(item_title_meta), m["filename"], total_seasons_hint)
            if s_num > 0 and e_num > 0:
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
    best_by_se = {}

    for cand in raw_candidates:
        key = (cand["season"], cand["episode"])
        official_name, expected_rt = episode_guide.get(key, ("", avg_runtime))
        dur = cand["duration_mins"]

        if dur > 0:
            if dur < 12:
                continue
            if expected_rt > 0 and (dur < int(expected_rt * 0.65) or dur > int(expected_rt * 1.8)):
                continue

        quality_score = cand["size_mb"] + (500.0 if not cand["is_derivative"] else 0.0)
        if key not in best_by_se or quality_score > best_by_se[key]["quality_score"]:
            clean_file_title, _ = clean_filename_to_title(cand["filename"])
            ep_title_clean = official_name if official_name else (clean_file_title or f"Epizoda {cand['episode']}")
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
    return sorted_episodes if len(sorted_episodes) >= 2 else []


def sync_tmdb_series():
    print("3/5 Okupljanje i slaganje serija po sezonama i epizodama...", flush=True)
    candidate_shows = {}

    for endpoint in ["tv/popular", "tv/top_rated"]:
        for page in range(1, 9):
            data = safe_get(f"{TMDB_BASE}/{endpoint}", {"api_key": TMDB_API_KEY, "language": "hr-HR", "page": page})
            for s in (data or {}).get("results", []):
                candidate_shows[s["id"]] = s

    for seed_title in CLASSIC_TV_SEED_TITLES:
        res = safe_get(f"{TMDB_BASE}/search/tv", {"api_key": TMDB_API_KEY, "query": seed_title, "language": "hr-HR"})
        first = ((res or {}).get("results") or [None])[0]
        if first:
            candidate_shows[first["id"]] = first

    def process_series(item):
        tv_id, s = item
        orig_name = s.get("original_name") or s.get("name") or ""
        hr_name = s.get("name") or orig_name
        first_air = s.get("first_air_date") or ""
        year = int(first_air[:4]) if len(first_air) >= 4 and first_air[:4].isdigit() else 0

        episodes = aggregate_series_from_archive(tv_id, orig_name, hr_name)
        if len(episodes) < 2:
            return None

        overview = (s.get("overview") or "").strip()
        if not overview:
            en_tv = safe_get(f"{TMDB_BASE}/tv/{tv_id}", {"api_key": TMDB_API_KEY, "language": "en-US"})
            overview = ((en_tv or {}).get("overview") or "").strip()

        genres = list({TMDB_TV_GENRE_MAP[g] for g in s.get("genre_ids", []) if g in TMDB_TV_GENRE_MAP})
        if not genres:
            genres = ["Krimi, detektivske i triler serije"]

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
            "is_featured": float(s.get("vote_average") or 0) >= 8.0,
            "is_valid": True,
            "tmdb_runtime": 0,
            "archive_runtime": 0
        }
        supabase_upsert("catalog_items", [row], "archive_id")
        return f"  + Serija: {row['title']} ({seasons_found} sez. / {len(episodes)} ep.)"

    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = [pool.submit(process_series, item) for item in candidate_shows.items()]
        for fut in as_completed(futures):
            msg = fut.result()
            if msg:
                print(msg, flush=True)


def sync_concerts():
    print("4/5 Sinkronizacija koncerata...", flush=True)
    concert_queries = [
        ("Rock i Metal", "mediatype:movies AND collection:vhsconcerts_music AND subject:(rock OR metal OR punk)"),
        ("Pop glazba", "mediatype:movies AND collection:vhsconcerts_music AND subject:(pop OR \"pop music\")"),
        ("Klasika i Jazz", "mediatype:movies AND collection:vhsconcerts_music AND subject:(classical OR jazz OR blues)")
    ]
    for genre_label, query in concert_queries:
        data = safe_get(
            "https://archive.org/advancedsearch.php",
            params={
                "q": query,
                "fl[]": ["identifier", "title", "description", "downloads"],
                "sort[]": "downloads desc",
                "rows": 30,
                "output": "json"
            }
        )
        for doc in (data or {}).get("response", {}).get("docs", []):
            ident = doc.get("identifier")
            raw_title = doc.get("title") or ident
            if isinstance(raw_title, list):
                raw_title = raw_title[0]
            clean_t, parsed_y = clean_filename_to_title(str(raw_title))
            if not clean_t:
                continue
            mp4s, _ = get_archive_item_details(ident, min_size_mb=100)
            if not mp4s:
                continue
            best = max(mp4s, key=lambda x: x["size_mb"])
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
                "archive_runtime": best["duration_mins"]
            }
            supabase_upsert("catalog_items", [row], "archive_id")


def tmdb_enrich_movie(title, year=0):
    params = {"api_key": TMDB_API_KEY, "query": title, "language": "hr-HR"}
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

    item = results[0]
    details = get_tmdb_movie_details(item["id"])
    overview = details["overview_hr"] if details else (item.get("overview") or "").strip()
    rel_date = item.get("release_date") or ""
    rel_year = int(rel_date[:4]) if len(rel_date) >= 4 and rel_date[:4].isdigit() else year

    return {
        "tmdb_id": item["id"],
        "title": item.get("title") or item.get("original_title") or title,
        "release_year": rel_year,
        "description_hr": overview,
        "poster_url": f"{TMDB_IMG_POSTER}{item['poster_path']}" if item.get("poster_path") else ""
    }


def fetch_wiki_bio(author_name):
    search_name = "Paul Thomas Anderson" if author_name == "PTA Filmography" else author_name
    encoded = urllib.parse.quote(search_name.replace(" ", "_"))
    for lang in ["hr", "en"]:
        data = safe_get(f"https://{lang}.wikipedia.org/api/rest_v1/page/summary/{encoded}")
        if data and data.get("extract"):
            return data["extract"].strip()
    return ""


def sync_authors():
    print("5/5 Sinkronizacija 63 autora...", flush=True)
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

        mp4s, _ = get_archive_item_details(archive_id, min_size_mb=15)
        deduped = {}
        for m in mp4s:
            clean_t, year = clean_filename_to_title(m["filename"])
            if not clean_t:
                continue
            key = (clean_t.lower(), year)
            if key not in deduped or m["size_mb"] > deduped[key]["size_mb"]:
                deduped[key] = {
                    "title": clean_t,
                    "year": year,
                    "stream_url": m["stream_url"],
                    "size_mb": m["size_mb"]
                }

        unique_movie_rows = {}
        for item in sorted(deduped.values(), key=lambda x: x["year"], reverse=True):
            enriched = tmdb_enrich_movie(item["title"], item["year"])
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

        movie_rows = list(unique_movie_rows.values())
        if movie_rows:
            supabase_upsert("author_movies", movie_rows, "author_id,title,release_year")
        print(f"  [{idx}/{len(AUTHORS)}] {author_name}: {len(movie_rows)} filmova", flush=True)


if __name__ == "__main__":
    audit_existing_catalog()
    sync_tmdb_movies()
    sync_tmdb_series()
    sync_concerts()
    sync_authors()
    print("Sinkronizacija uspješno završena!", flush=True)
