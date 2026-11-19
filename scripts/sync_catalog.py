import os
import re
import time
import urllib.parse
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
    "User-Agent": "MediaStudioCatalogSync/1.0 (AndroidTV App; contact@mediastudio.local)"
})

SB_HEADERS = {
    "apikey": SUPABASE_SERVICE_KEY,
    "Content-Type": "application/json",
    "Prefer": "resolution=merge-duplicates,return=representation"
}
if not SUPABASE_SERVICE_KEY.startswith("sb_"):
    SB_HEADERS["Authorization"] = f"Bearer {SUPABASE_SERVICE_KEY}"

TMDB_GENRE_MAP = {
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
    37: "Vestern",
    10759: "Avanturističke serije",
    10765: "Sci-Fi i fantastika serije"
}

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
                time.sleep(3 * (attempt + 1))
        except Exception:
            time.sleep(1.2)
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


def clean_filename_to_title(filename):
    name = re.sub(r"\.(mp4|mkv|avi|ogv|mov)$", "", filename, flags=re.IGNORECASE)
    year_match = re.search(r"\b(19\d{2}|20\d{2})\b", name)
    year = int(year_match.group(1)) if year_match else 0

    clean = re.sub(r"[._]+", " ", name)
    clean = re.sub(
        r"(?i)\b(1080p|2160p|4k|720p|480p|360p|bluray|blu-ray|bdrip|brrip|webrip|web-dl|webdl|dvdrip|hdrip|x264|x265|h264|h265|hevc|aac|ac3|dts|ia|remastered|unrated|extended)\b",
        "",
        clean,
    )
    if year:
        clean = re.sub(rf"\(?\b{year}\b\)?", "", clean)
    clean = re.sub(r"\(\s*\)|\[\s*\]", "", clean)
    clean = re.sub(r"^\s*\d+[\.\-\s]+", "", clean)
    clean = re.sub(r"\s+", " ").strip(" -")
    return clean.strip(), year


def get_archive_mp4_files(identifier, min_size_mb=40):
    time.sleep(0.25)
    meta = safe_get(f"https://archive.org/metadata/{identifier}")
    if not meta or meta.get("is_dark"):
        return []

    files = meta.get("files", [])
    mp4s = []
    for f in files:
        fname = f.get("name", "")
        if not fname.lower().endswith(".mp4"):
            continue
        if any(skip in fname.lower() for skip in ["trailer", "sample", "teaser", "preview"]):
            continue
        try:
            size_mb = int(f.get("size", 0)) / (1024 * 1024)
        except ValueError:
            size_mb = 0
        if size_mb < min_size_mb:
            continue
        encoded_name = urllib.parse.quote(fname)
        stream_url = f"https://archive.org/download/{identifier}/{encoded_name}"
        mp4s.append({
            "filename": fname,
            "size_mb": size_mb,
            "stream_url": stream_url
        })
    return mp4s


def search_archive_for_title(title, year, is_series=False):
    time.sleep(0.25)
    clean_q = re.sub(r"[^\w\s]", " ", title).strip()
    if len(clean_q) < 2:
        return None, None, []
    year_clause = f" AND year:[{year - 1} TO {year + 1}]" if year > 0 else ""
    query = f'title:"{clean_q}"{year_clause} AND mediatype:movies AND -subject:trailer AND -subject:news'
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
    docs = (data or {}).get("response", {}).get("docs", [])
    for doc in docs:
        ident = doc.get("identifier")
        if not ident:
            continue
        mp4s = get_archive_mp4_files(ident, min_size_mb=40 if is_series else 180)
        if not mp4s:
            continue
        if is_series:
            mp4s.sort(key=lambda x: x["filename"])
            episodes = [
                {"title": clean_filename_to_title(m["filename"])[0] or f"Epizoda {i+1}", "url": m["stream_url"]}
                for i, m in enumerate(mp4s)
            ]
            return ident, mp4s[0]["stream_url"], episodes
        else:
            best = max(mp4s, key=lambda x: x["size_mb"])
            return ident, best["stream_url"], []
    return None, None, []


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
    overview = (item.get("overview") or "").strip()
    if not overview:
        en_res = safe_get(f"{TMDB_BASE}/movie/{item['id']}", params={"api_key": TMDB_API_KEY, "language": "en-US"})
        overview = ((en_res or {}).get("overview") or "").strip()

    genres = list({TMDB_GENRE_MAP[g] for g in item.get("genre_ids", []) if g in TMDB_GENRE_MAP})
    rel_date = item.get("release_date") or ""
    rel_year = int(rel_date[:4]) if len(rel_date) >= 4 and rel_date[:4].isdigit() else year

    return {
        "tmdb_id": item["id"],
        "title": item.get("title") or item.get("original_title") or title,
        "release_year": rel_year,
        "rating": round(float(item.get("vote_average") or 0.0), 1),
        "popularity_score": float(item.get("popularity") or 10.0) * 100.0,
        "description_hr": overview,
        "poster_url": f"{TMDB_IMG_POSTER}{item['poster_path']}" if item.get("poster_path") else "",
        "backdrop_url": f"{TMDB_IMG_BACKDROP}{item['backdrop_path']}" if item.get("backdrop_path") else "",
        "genres": genres
    }


def cleanup_dead_items():
    print("1/5 Provjera postojećih linkova u bazi...", flush=True)
    r = session.get(
        f"{SUPABASE_URL}/rest/v1/catalog_items?select=id,archive_id&order=updated_at.asc&limit=100",
        headers=SB_HEADERS,
        timeout=15
    )
    if r.status_code != 200:
        print(f"[Upozorenje] Provjera tablice vratila status {r.status_code}: {r.text[:200]}", flush=True)
        return
    for row in r.json():
        mp4s = get_archive_mp4_files(row["archive_id"], min_size_mb=20)
        if not mp4s:
            session.delete(f"{SUPABASE_URL}/rest/v1/catalog_items?id=eq.{row['id']}", headers=SB_HEADERS)
            print(f"  Uklonjen nedostupan naslov: {row['archive_id']}", flush=True)


def sync_tmdb_catalog():
    print("2/5 Sinkronizacija TMDB filmova i serija s Archive.org provjerom...", flush=True)
    seen_tmdb = set()
    for endpoint in ["movie/popular", "movie/top_rated"]:
        for page in range(1, 13):
            data = safe_get(f"{TMDB_BASE}/{endpoint}", {"api_key": TMDB_API_KEY, "language": "hr-HR", "page": page})
            for m in (data or {}).get("results", []):
                if m["id"] in seen_tmdb:
                    continue
                seen_tmdb.add(m["id"])
                orig_title = m.get("original_title") or m.get("title") or ""
                rel_date = m.get("release_date") or ""
                year = int(rel_date[:4]) if len(rel_date) >= 4 and rel_date[:4].isdigit() else 0
                if year > 2026:
                    continue

                arch_id, stream_url, _ = search_archive_for_title(orig_title, year, is_series=False)
                if not arch_id:
                    continue

                overview = (m.get("overview") or "").strip()
                if not overview:
                    en = safe_get(f"{TMDB_BASE}/movie/{m['id']}", {"api_key": TMDB_API_KEY, "language": "en-US"})
                    overview = ((en or {}).get("overview") or "").strip()

                genres = list({TMDB_GENRE_MAP[g] for g in m.get("genre_ids", []) if g in TMDB_GENRE_MAP})
                row = {
                    "archive_id": arch_id,
                    "tmdb_id": m["id"],
                    "title": m.get("title") or orig_title,
                    "media_type": "movie",
                    "genres": genres,
                    "release_year": year,
                    "rating": round(float(m.get("vote_average") or 0.0), 1),
                    "popularity_score": float(m.get("popularity") or 10.0) * 100.0 + (year * 2),
                    "description_hr": overview,
                    "poster_url": f"{TMDB_IMG_POSTER}{m['poster_path']}" if m.get("poster_path") else "",
                    "backdrop_url": f"{TMDB_IMG_BACKDROP}{m['backdrop_path']}" if m.get("backdrop_path") else "",
                    "stream_url": stream_url,
                    "is_featured": float(m.get("vote_average") or 0) >= 7.8
                }
                supabase_upsert("catalog_items", [row], "archive_id")
                print(f"  + Film: {row['title']} ({year})", flush=True)

    seen_tv = set()
    for endpoint in ["tv/popular", "tv/top_rated"]:
        for page in range(1, 9):
            data = safe_get(f"{TMDB_BASE}/{endpoint}", {"api_key": TMDB_API_KEY, "language": "hr-HR", "page": page})
            for s in (data or {}).get("results", []):
                if s["id"] in seen_tv:
                    continue
                seen_tv.add(s["id"])
                orig_name = s.get("original_name") or s.get("name") or ""
                first_air = s.get("first_air_date") or ""
                year = int(first_air[:4]) if len(first_air) >= 4 and first_air[:4].isdigit() else 0

                arch_id, stream_url, episodes = search_archive_for_title(orig_name, year, is_series=True)
                if not arch_id:
                    continue

                overview = (s.get("overview") or "").strip()
                genres = list({TMDB_GENRE_MAP[g] for g in s.get("genre_ids", []) if g in TMDB_GENRE_MAP})
                row = {
                    "archive_id": arch_id,
                    "tmdb_id": s["id"],
                    "title": s.get("name") or orig_name,
                    "media_type": "series",
                    "genres": genres,
                    "release_year": year,
                    "rating": round(float(s.get("vote_average") or 0.0), 1),
                    "popularity_score": float(s.get("popularity") or 10.0) * 100.0,
                    "description_hr": overview,
                    "poster_url": f"{TMDB_IMG_POSTER}{s['poster_path']}" if s.get("poster_path") else "",
                    "backdrop_url": f"{TMDB_IMG_BACKDROP}{s['backdrop_path']}" if s.get("backdrop_path") else "",
                    "stream_url": stream_url,
                    "episodes": episodes,
                    "is_featured": float(s.get("vote_average") or 0) >= 8.0
                }
                supabase_upsert("catalog_items", [row], "archive_id")
                print(f"  + Serija: {row['title']} ({len(episodes)} ep.)", flush=True)


def sync_top_archive_movies():
    print("3/5 Dodavanje top najgledanijih filmova s Archive.org...", flush=True)
    query = "mediatype:movies AND collection:(feature_films OR opensource_movies OR SciFi_Films OR laserdiscarchive OR film_noir) AND -subject:news"
    data = safe_get(
        "https://archive.org/advancedsearch.php",
        params={
            "q": query,
            "fl[]": ["identifier", "title", "year", "downloads"],
            "sort[]": "downloads desc",
            "rows": 200,
            "output": "json"
        }
    )
    for doc in (data or {}).get("response", {}).get("docs", []):
        ident = doc.get("identifier")
        raw_title = doc.get("title") or ident
        if isinstance(raw_title, list):
            raw_title = raw_title[0]
        clean_t, parsed_y = clean_filename_to_title(str(raw_title))
        if not clean_t or len(clean_t) < 2:
            continue

        mp4s = get_archive_mp4_files(ident, min_size_mb=180)
        if not mp4s:
            continue
        best = max(mp4s, key=lambda x: x["size_mb"])

        enriched = tmdb_enrich_movie(clean_t, parsed_y)
        row = {
            "archive_id": ident,
            "tmdb_id": enriched["tmdb_id"] if enriched else None,
            "title": enriched["title"] if enriched else clean_t,
            "media_type": "movie",
            "genres": enriched["genres"] if enriched else ["Klasici i Najgledanije"],
            "release_year": enriched["release_year"] if enriched else parsed_y,
            "rating": enriched["rating"] if enriched else 0.0,
            "popularity_score": enriched["popularity_score"] if enriched else float(doc.get("downloads") or 0) / 1000.0,
            "description_hr": enriched["description_hr"] if enriched else "",
            "poster_url": enriched["poster_url"] if enriched else f"https://archive.org/services/img/{ident}",
            "backdrop_url": enriched["backdrop_url"] if enriched else "",
            "stream_url": best["stream_url"],
            "is_featured": False
        }
        supabase_upsert("catalog_items", [row], "archive_id")


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
            mp4s = get_archive_mp4_files(ident, min_size_mb=100)
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
                "is_featured": False
            }
            supabase_upsert("catalog_items", [row], "archive_id")


def fetch_wiki_bio(author_name):
    search_name = "Paul Thomas Anderson" if author_name == "PTA Filmography" else author_name
    encoded = urllib.parse.quote(search_name.replace(" ", "_"))
    for lang in ["hr", "en"]:
        data = safe_get(f"https://{lang}.wikipedia.org/api/rest_v1/page/summary/{encoded}")
        if data and data.get("extract"):
            return data["extract"].strip()
    return ""


def sync_authors():
    print("5/5 Sinkronizacija 63 autora i njihovih očišćenih filmografija...", flush=True)
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

        mp4s = get_archive_mp4_files(archive_id, min_size_mb=15)
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
    cleanup_dead_items()
    sync_tmdb_catalog()
    sync_top_archive_movies()
    sync_concerts()
    sync_authors()
    print("Sinkronizacija uspješno završena!", flush=True)
