import os
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from urllib.parse import unquote

import openpyxl
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from sync_common import (
    TMDB_API_KEY,
    TMDB_BASE,
    extract_year_from_text,
    get_archive_item_details,
    get_tmdb_movie_details,
    is_future_release,
    safe_get,
    validate_movie_match,
    verify_stream_url_playable,
)

OUTPUT_XLSX = "fransize_rezultati.xlsx"
OUTPUT_CSV = "fransize_sve_verzije.csv"

# Definicija franšiza:
# - collection_ids: službene TMDB kolekcije (može ih biti više po franšizi)
# - keyword_ids: koristi se za MCU (9715 = Marvel Cinematic Universe) gdje nema jedne zajedničke kolekcije
FRANCHISES = [
    {"name": "Star Wars", "collection_ids": [10]},
    {"name": "Star Trek", "collection_ids": [151, 115575, 115570]},
    {"name": "Matrix", "collection_ids": [2344]},
    {"name": "Gospodar prstenova", "collection_ids": [119]},
    {"name": "Hobbit", "collection_ids": [121938]},
    {"name": "Umri muški", "collection_ids": [1570]},
    {"name": "Indiana Jones", "collection_ids": [84]},
    {"name": "Mad Max", "collection_ids": [8945]},
    {"name": "Avatar", "collection_ids": [87096]},
    {"name": "Mission: Impossible", "collection_ids": [87359]},
    {"name": "Marvel (MCU)", "keyword_ids": [9715]},
    {"name": "James Bond", "collection_ids": [645]},
    {"name": "Fast & Furious", "collection_ids": [9485]},
    {"name": "The Terminator", "collection_ids": [528]},
]

JUNK_ARCH_RE = re.compile(
    r"(?i)\b(trailer|teaser|sample|review|commentary|making[\s._-]*of|behind[\s._-]*the[\s._-]*scenes|"
    r"interview|featurette|promo|soundtrack|score|ost|parody|rifftrax|gameplay|longplay|walkthrough)\b"
)


def fetch_franchise_movies_from_tmdb(franchise_cfg):
    """Dohvaća sve objavljene dugometražne filmove za zadanu franšizu s TMDB-a."""
    franchise_name = franchise_cfg["name"]
    raw_movies = {}

    for col_id in franchise_cfg.get("collection_ids", []):
        col_data = safe_get(
            f"{TMDB_BASE}/collection/{col_id}",
            {"api_key": TMDB_API_KEY, "language": "hr-HR"},
        )
        for part in (col_data or {}).get("parts") or []:
            mid = int(part.get("id") or 0)
            if mid > 0 and mid not in raw_movies:
                raw_movies[mid] = part

    for kw_id in franchise_cfg.get("keyword_ids", []):
        page = 1
        while page <= 5:
            disc = safe_get(
                f"{TMDB_BASE}/discover/movie",
                {
                    "api_key": TMDB_API_KEY,
                    "with_keywords": kw_id,
                    "language": "hr-HR",
                    "sort_by": "release_date.asc",
                    "include_adult": "false",
                    "page": page,
                },
            )
            results = (disc or {}).get("results") or []
            if not results:
                break
            for item in results:
                mid = int(item.get("id") or 0)
                if mid > 0 and mid not in raw_movies:
                    raw_movies[mid] = item
            if page >= int((disc or {}).get("total_pages") or 1):
                break
            page += 1

    detailed_movies = []
    for mid, stub in raw_movies.items():
        rel_date = str(stub.get("release_date") or "")
        if not rel_date or is_future_release(rel_date):
            continue
        year = extract_year_from_text(rel_date)
        if year <= 0:
            continue

        det = get_tmdb_movie_details(mid)
        if not det:
            continue
        if det.get("adult") or (det.get("status") and det["status"] != "Released"):
            continue
        if 99 in (det.get("genre_ids") or []):
            continue
        if det.get("runtime", 0) > 0 and det["runtime"] < 60:
            continue

        det["franchise"] = franchise_name
        det["year"] = year
        detailed_movies.append(det)

    detailed_movies.sort(key=lambda x: (x["release_date"], x["orig_title"]))
    return detailed_movies


def format_video_quality_badge(video_info):
    width = int(video_info.get("width") or 0)
    height = int(video_info.get("height") or 0)
    size_mb = float(video_info.get("size_mb") or 0.0)
    fname = unquote(video_info.get("filename") or "")
    lower = fname.lower()

    if height >= 2000 or width >= 3800 or "2160p" in lower or "4k" in lower:
        res = "4K"
    elif height >= 1000 or width >= 1900 or "1080p" in lower:
        res = "1080p Full HD"
    elif height >= 700 or width >= 1260 or "720p" in lower:
        res = "720p HD"
    elif height >= 460 or "480p" in lower:
        res = "480p SD"
    elif height > 0:
        res = f"{height}p"
    else:
        res = "SD/Nepoznata rez."

    aspect_tier = int(video_info.get("aspect_tier", 1))
    aspect_txt = "16:9" if aspect_tier == 2 else ("4:3" if aspect_tier == 0 else "Omjer nepoznat")
    ext = os.path.splitext(fname)[1].upper().lstrip(".") or "MP4"
    size_txt = f"{size_mb / 1024.0:.2f} GB" if size_mb >= 1024.0 else f"{size_mb:.0f} MB"

    return f"{res} | {aspect_txt} | {ext} | {size_txt}"


def build_search_queries(tmdb_det):
    """Generira upite po izvornom, engleskom i hrvatskom naslovu (s podnaslovom i bez dvotočke)."""
    titles = []
    for raw_t in (tmdb_det.get("orig_title"), tmdb_det.get("en_title"), tmdb_det.get("hr_title")):
        t = str(raw_t or "").strip()
        if not t:
            continue
        cleaned = re.sub(r"[^\w\s]", " ", t)
        cleaned = re.sub(r"\s+", " ", cleaned).strip()
        if len(cleaned) >= 3 and cleaned not in titles:
            titles.append(cleaned)
        if ":" in t:
            before_colon = re.sub(r"[^\w\s]", " ", t.split(":", 1)[0]).strip()
            after_colon = re.sub(r"[^\w\s]", " ", t.split(":", 1)[1]).strip()
            if len(before_colon) >= 4 and before_colon not in titles:
                titles.append(before_colon)
            if len(after_colon) >= 5 and after_colon not in titles:
                titles.append(after_colon)
    return titles[:4]


def find_all_archive_versions_for_movie(tmdb_det):
    """Pronalazi SVE valjane verzije filma na Archive.org-u po kriterijima iz movies skripte."""
    year = tmdb_det["year"]
    is_anim = 16 in (tmdb_det.get("genre_ids") or [])
    min_mb = 90 if is_anim else 150
    expected_titles = [
        t for t in {tmdb_det.get("orig_title"), tmdb_det.get("en_title"), tmdb_det.get("hr_title")} if t
    ]

    docs_by_id = {}
    for term in build_search_queries(tmdb_det):
        query = (
            f'title:"{term}" AND year:[{year - 1} TO {year + 1}] '
            f'AND mediatype:(movies OR web) '
            f'AND -subject:trailer AND -subject:news AND -subject:review AND -subject:"video games"'
        )
        data = safe_get(
            "https://archive.org/advancedsearch.php",
            params={
                "q": query,
                "fl[]": ["identifier", "title", "year", "downloads"],
                "sort[]": "downloads desc",
                "rows": 15,
                "output": "json",
            },
            timeout=14,
        )
        for doc in (data or {}).get("response", {}).get("docs") or []:
            ident = doc.get("identifier") or ""
            if ident and ident not in docs_by_id:
                docs_by_id[ident] = doc

    found_versions = []
    seen_stream_urls = set()

    for ident, doc in docs_by_id.items():
        if ident.lower().startswith(("youtube-", "yt-")):
            continue
        raw_doc_title = doc.get("title") or ident
        if isinstance(raw_doc_title, list):
            raw_doc_title = raw_doc_title[0]
        if JUNK_ARCH_RE.search(f"{ident} {raw_doc_title}"):
            continue

        videos, arch_md = get_archive_item_details(ident, min_size_mb=min_mb, allow_mkv=True)
        if not videos:
            continue

        # Grupiraj po video datoteci unutar istog Archive.org zapisa (ako zapis ima npr. kino i extended verziju)
        # ali preskoči .ia.mp4 derivate ako postoji izvorna datoteka istog imena
        by_stem = {}
        for v in videos:
            if JUNK_ARCH_RE.search(v.get("filename") or ""):
                continue
            stem = re.sub(r"(?i)\.ia$", "", v["filename"].rsplit("/", 1)[-1].rsplit(".", 1)[0]).lower()
            is_mp4 = v["filename"].lower().endswith(".mp4")
            score = (int(v.get("aspect_tier", 1)), 0 if (not v.get("is_derivative") and is_mp4) else 1, v["size_mb"])
            if stem not in by_stem or score > by_stem[stem][0]:
                by_stem[stem] = (score, v)

        for _, chosen_v in by_stem.values():
            stream_url = chosen_v["stream_url"]
            if stream_url in seen_stream_urls:
                continue

            ok, reason = validate_movie_match(
                identifier=ident,
                archive_md=arch_md,
                archive_mins=int(chosen_v.get("duration_mins") or 0),
                tmdb_details=tmdb_det,
                expected_titles=expected_titles,
                expected_year=year,
                require_credits=False,
                chosen_filename=chosen_v["filename"],
                chosen_size_mb=float(chosen_v.get("size_mb") or 0.0),
            )
            if not ok:
                continue

            if not verify_stream_url_playable(stream_url):
                continue

            seen_stream_urls.add(stream_url)
            aspect_tier = int(chosen_v.get("aspect_tier", 1))
            size_mb = float(chosen_v.get("size_mb") or 0.0)
            downloads = int(float(doc.get("downloads") or 0))

            found_versions.append({
                "Franšiza": tmdb_det["franchise"],
                "TMDB ID": tmdb_det["tmdb_id"],
                "Naslov (HR)": tmdb_det["hr_title"],
                "Izvorni naslov": tmdb_det["orig_title"],
                "Godina": year,
                "TMDB ocjena": round(float(tmdb_det.get("vote_average") or 0.0), 1),
                "Trajanje (min)": int(chosen_v.get("duration_mins") or tmdb_det.get("runtime") or 0),
                "Kvaliteta videa": format_video_quality_badge(chosen_v),
                "Omjer slike": "16:9" if aspect_tier == 2 else ("4:3" if aspect_tier == 0 else "Nepoznat"),
                "Veličina (MB)": round(size_mb, 1),
                "Preuzimanja (Archive)": downloads,
                "Archive ID": ident,
                "Datoteka": unquote(chosen_v["filename"]),
                "Archive.org stranica": f"https://archive.org/details/{ident}",
                "Izravni video URL": stream_url,
                "_sort_key": (-aspect_tier, -size_mb, -downloads),
            })

    found_versions.sort(key=lambda x: x["_sort_key"])
    for idx, v in enumerate(found_versions, start=1):
        v["Verzija #"] = idx
        v.pop("_sort_key", None)

    return found_versions


def export_results(summary_rows, all_versions_rows):
    df_summary = pd.DataFrame(summary_rows)
    cols_order = [
        "Franšiza", "TMDB filmova", "Filmova s verzijama", "Ukupno verzija",
        "16:9 / HD verzija", "Nedostaju filmovi"
    ]
    df_summary = df_summary[cols_order]

    version_cols = [
        "Franšiza", "TMDB ID", "Naslov (HR)", "Izvorni naslov", "Godina",
        "Verzija #", "Kvaliteta videa", "Omjer slike", "Trajanje (min)",
        "Veličina (MB)", "TMDB ocjena", "Preuzimanja (Archive)",
        "Archive ID", "Datoteka", "Archive.org stranica", "Izravni video URL"
    ]
    df_versions = pd.DataFrame(all_versions_rows)[version_cols] if all_versions_rows else pd.DataFrame(columns=version_cols)

    with pd.ExcelWriter(OUTPUT_XLSX, engine="openpyxl") as writer:
        df_summary.to_excel(writer, sheet_name="Sažetak_franšiza", index=False)
        df_versions.to_excel(writer, sheet_name="Sve_verzije", index=False)

    wb = openpyxl.load_workbook(OUTPUT_XLSX)
    ws1 = wb["Sažetak_franšiza"]
    for col_letter, w in {"A": 24, "B": 15, "C": 20, "D": 16, "E": 18, "F": 60}.items():
        ws1.column_dimensions[col_letter].width = w
    for row in ws1.iter_rows(min_row=2):
        for cell in row:
            cell.alignment = openpyxl.styles.Alignment(wrap_text=True, vertical="top")

    ws2 = wb["Sve_verzije"]
    widths2 = {
        "A": 20, "B": 11, "C": 34, "D": 34, "E": 9,
        "F": 10, "G": 32, "H": 13, "I": 14,
        "J": 14, "K": 12, "L": 18,
        "M": 36, "N": 42, "O": 46, "P": 55
    }
    for col_letter, w in widths2.items():
        ws2.column_dimensions[col_letter].width = w
    wb.save(OUTPUT_XLSX)

    df_versions.to_csv(OUTPUT_CSV, index=False, encoding="utf-8-sig")


def run_franchise_search():
    start_ts = time.monotonic()
    print("=== [FRANŠIZE] TMDB katalog + pretraga SVIH verzija na Archive.org ===", flush=True)

    summary_rows = []
    all_versions = []

    for f_cfg in FRANCHISES:
        f_name = f_cfg["name"]
        print(f"\n🎬 Franšiza: {f_name}...", flush=True)
        movies = fetch_franchise_movies_from_tmdb(f_cfg)
        print(f"   TMDB pronašao {len(movies)} objavljenih filmova.", flush=True)

        franchise_versions = []
        movies_with_hits = set()

        with ThreadPoolExecutor(max_workers=6) as pool:
            futures = {pool.submit(find_all_archive_versions_for_movie, m): m for m in movies}
            for fut in as_completed(futures):
                m_det = futures[fut]
                vers = fut.result()
                if vers:
                    movies_with_hits.add(m_det["tmdb_id"])
                    franchise_versions.extend(vers)

        franchise_versions.sort(key=lambda x: (x["Godina"], x["Izvorni naslov"], x["Verzija #"]))

        for m in movies:
            m_vers = [v for v in franchise_versions if v["TMDB ID"] == m["tmdb_id"]]
            if m_vers:
                print(f"   ✓ {m['hr_title']} ({m['year']}) -> {len(m_vers)} verzija:", flush=True)
                for v in m_vers:
                    print(f"       #{v['Verzija #']} [{v['Kvaliteta videa']}] | {v['Archive.org stranica']}", flush=True)
            else:
                print(f"   ✕ {m['hr_title']} ({m['year']}) -> nema pronađenih verzija", flush=True)

        missing_titles = [
            f"{m['hr_title']} ({m['year']})"
            for m in movies
            if m["tmdb_id"] not in movies_with_hits
        ]
        hd_16_9_count = sum(1 for v in franchise_versions if v["Omjer slike"] == "16:9")

        summary_rows.append({
            "Franšiza": f_name,
            "TMDB filmova": len(movies),
            "Filmova s verzijama": len(movies_with_hits),
            "Ukupno verzija": len(franchise_versions),
            "16:9 / HD verzija": hd_16_9_count,
            "Nedostaju filmovi": ", ".join(missing_titles) if missing_titles else "Svi filmovi pronađeni!",
        })
        all_versions.extend(franchise_versions)

    export_results(summary_rows, all_versions)
    elapsed = time.monotonic() - start_ts
    print(
        f"\n=== Završeno za {elapsed:.1f} s! Pronađeno ukupno {len(all_versions)} verzija. "
        f"Spremljeno u '{OUTPUT_XLSX}' i '{OUTPUT_CSV}'. ===",
        flush=True,
    )


if __name__ == "__main__":
    run_franchise_search()
