import os
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from urllib.parse import quote, unquote

import openpyxl
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from sync_common import (
    TMDB_API_KEY,
    TMDB_BASE,
    extract_year_from_text,
    get_archive_item_details,
    is_foreign_dub_without_english,
    normalize_for_exact_title_compare,
    pick_best_video_file,
    safe_get,
    titles_match_flexibly,
)

DEFAULT_XLSX_PATH = "directors.xlsx"
DEFAULT_CSV_FALLBACK = "directors_traži.csv"
TARGET_SHEET_NAME = "traži"
USE_LOCAL_TABLE_IF_EXISTS = False

NEW_DIRECTORS = [
    {
        "#": 1,
        "Redatelj": "Lana i Lilly Wachowski",
        "Država": "SAD",
        "Ključni filmovi (od 1970.)": "Matrix, Matrix Reloaded, Cloud Atlas, V za osvetu"
    },
    {
        "#": 2,
        "Redatelj": "George Lucas",
        "Država": "SAD",
        "Ključni filmovi (od 1970.)": "Ratovi zvijezda: Nova nada, Američki grafiti, THX 1138"
    },
]

DIRECTOR_ALIASES = {
    "lana i lilly wachowski": ["Lana Wachowski", "Lilly Wachowski"],
    "sestre wachowski": ["Lana Wachowski", "Lilly Wachowski"],
    "wachowski": ["Lana Wachowski", "Lilly Wachowski"],
    "russo braća (anthony i joe russo)": ["Anthony Russo", "Joe Russo"],
    "russo braca (anthony i joe russo)": ["Anthony Russo", "Joe Russo"],
    "braća russo": ["Anthony Russo", "Joe Russo"],
    "braca russo": ["Anthony Russo", "Joe Russo"],
}

PRODUCER_ONLY_AUTHORS = {
    "jerry bruckheimer",
}

JUNK_FILTER_RE = re.compile(
    r"(?i)\b(trailer|teaser|sample|review|commentary|making[\s._-]*of|behind[\s._-]*the[\s._-]*scenes|"
    r"interview|featurette|promo|soundtrack|score|ost|parody|rifftrax|gameplay)\b"
)


def resolve_tmdb_person_queries(director_label):
    cleaned = director_label.strip()
    alias_hits = DIRECTOR_ALIASES.get(cleaned.lower())
    if alias_hits:
        return alias_hits
    no_parens = re.sub(r"\([^)]*\)", "", cleaned).strip()
    return [no_parens] if no_parens else [cleaned]


def fetch_director_filmography_tmdb(director_label):
    """Dohvaća sve dugometražne filmove za redatelja (ili producenta) s TMDB-a."""
    queries = resolve_tmdb_person_queries(director_label)
    is_producer = director_label.strip().lower() in PRODUCER_ONLY_AUTHORS
    allowed_jobs = {"Producer", "Executive Producer"} if is_producer else {"Director"}
    preferred_dept = "Production" if is_producer else "Directing"
    min_votes = 40 if is_producer else 10

    movies_by_id = {}

    for person_name in queries:
        search_res = safe_get(
            f"{TMDB_BASE}/search/person",
            {"api_key": TMDB_API_KEY, "query": person_name, "language": "en-US"}
        )
        hits = (search_res or {}).get("results") or []
        if not hits:
            continue

        dept_hits = [h for h in hits if h.get("known_for_department") == preferred_dept]
        chosen_person = (dept_hits or hits)[0]
        person_id = chosen_person.get("id")
        if not person_id:
            continue

        credits_en = safe_get(
            f"{TMDB_BASE}/person/{person_id}/movie_credits",
            {"api_key": TMDB_API_KEY, "language": "en-US"}
        )
        credits_hr = safe_get(
            f"{TMDB_BASE}/person/{person_id}/movie_credits",
            {"api_key": TMDB_API_KEY, "language": "hr-HR"}
        )

        hr_title_map = {}
        for c in (credits_hr or {}).get("crew") or []:
            if c.get("job") in allowed_jobs and c.get("id"):
                hr_title_map[int(c["id"])] = (c.get("title") or "").strip()

        for c in (credits_en or {}).get("crew") or []:
            if c.get("job") not in allowed_jobs:
                continue
            mid = int(c.get("id") or 0)
            if mid <= 0 or mid in movies_by_id:
                continue

            genre_ids = c.get("genre_ids") or []
            if 99 in genre_ids:
                continue
            rel_date = str(c.get("release_date") or "")
            year = extract_year_from_text(rel_date)
            if year <= 0 or year > 2026:
                continue
            if int(c.get("vote_count") or 0) < min_votes:
                continue

            en_title = (c.get("title") or "").strip()
            orig_title = (c.get("original_title") or en_title).strip()
            hr_title = hr_title_map.get(mid) or en_title

            movies_by_id[mid] = {
                "tmdb_id": mid,
                "en_title": en_title,
                "orig_title": orig_title,
                "hr_title": hr_title,
                "year": year,
                "popularity": float(c.get("popularity") or 0.0),
                "orig_lang": str(c.get("original_language") or ""),
            }

    sorted_movies = sorted(movies_by_id.values(), key=lambda x: (x["year"], -x["popularity"]))
    return sorted_movies


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
    aspect_txt = "16:9" if aspect_tier == 2 else ("4:3" if aspect_tier == 0 else "")
    ext = os.path.splitext(fname)[1].upper().lstrip(".") or "MP4"
    size_txt = f"{size_mb / 1024.0:.2f} GB" if size_mb >= 1024.0 else f"{size_mb:.0f} MB"

    parts = [p for p in [res, aspect_txt, ext, size_txt] if p]
    return " | ".join(parts)


def search_movie_on_archive(movie_meta, director_label):
    """Pretražuje Archive.org za konkretan film autora i vraća najbolji video zapis + kvalitetu."""
    en_title = movie_meta["en_title"]
    orig_title = movie_meta["orig_title"]
    hr_title = movie_meta["hr_title"]
    year = movie_meta["year"]
    orig_lang = movie_meta["orig_lang"]

    search_terms = []
    for t in (en_title, orig_title):
        clean_q = re.sub(r"[^\w\s]", " ", t).strip()
        if len(clean_q) >= 2 and clean_q not in search_terms:
            search_terms.append(clean_q)

    if not search_terms:
        return None

    norm_candidates = {
        normalize_for_exact_title_compare(t)
        for t in (en_title, orig_title, hr_title)
        if t
    }
    norm_candidates.discard("")

    docs_by_id = {}
    for term in search_terms[:2]:
        query = (
            f'title:"{term}" AND year:[{year - 1} TO {year + 1}] '
            f'AND mediatype:(movies OR web) '
            f'AND -subject:trailer AND -subject:news AND -subject:"video games"'
        )
        data = safe_get(
            "https://archive.org/advancedsearch.php",
            params={
                "q": query,
                "fl[]": ["identifier", "title", "year", "downloads"],
                "sort[]": "downloads desc",
                "rows": 8,
                "output": "json",
            },
            timeout=12,
        )
        for doc in (data or {}).get("response", {}).get("docs") or []:
            ident = doc.get("identifier") or ""
            if ident and ident not in docs_by_id:
                docs_by_id[ident] = doc

    if not docs_by_id:
        return None

    tmdb_details = safe_get(
        f"{TMDB_BASE}/movie/{movie_meta['tmdb_id']}",
        {"api_key": TMDB_API_KEY, "language": "en-US"},
        timeout=10,
    )
    tmdb_runtime = int((tmdb_details or {}).get("runtime") or 0)

    best_match = None
    best_rank = (-1, -1.0, -1.0)

    for ident, doc in docs_by_id.items():
        if ident.lower().startswith(("youtube-", "yt-")):
            continue
        raw_doc_title = doc.get("title") or ident
        if isinstance(raw_doc_title, list):
            raw_doc_title = raw_doc_title[0]
        raw_doc_title = str(raw_doc_title)

        if JUNK_FILTER_RE.search(f"{ident} {raw_doc_title}"):
            continue

        norm_doc = normalize_for_exact_title_compare(raw_doc_title)
        norm_id = normalize_for_exact_title_compare(ident)
        if not titles_match_flexibly(norm_doc, norm_candidates) and not titles_match_flexibly(norm_id, norm_candidates):
            continue

        videos, arch_md = get_archive_item_details(ident, min_size_mb=150, allow_mkv=True)
        if not videos:
            continue

        best_video = pick_best_video_file(videos)
        if JUNK_FILTER_RE.search(best_video.get("filename") or ""):
            continue

        if is_foreign_dub_without_english(arch_md, ident, best_video.get("filename") or "", orig_lang=orig_lang):
            continue

        arch_mins = int(best_video.get("duration_mins") or 0)
        if tmdb_runtime >= 60 and arch_mins > 0:
            if arch_mins < int(tmdb_runtime * 0.72) or arch_mins > int(tmdb_runtime * 1.32):
                continue
        elif arch_mins > 0 and arch_mins < 55:
            continue

        aspect_tier = int(best_video.get("aspect_tier", 1))
        size_mb = float(best_video.get("size_mb") or 0.0)
        downloads = float(doc.get("downloads") or 0.0)
        rank = (aspect_tier, size_mb, downloads)

        if rank > best_rank:
            best_rank = rank
            best_match = {
                "director": director_label,
                "tmdb_id": movie_meta["tmdb_id"],
                "hr_title": hr_title,
                "orig_title": orig_title,
                "year": year,
                "runtime_min": arch_mins or tmdb_runtime,
                "archive_id": ident,
                "archive_page": f"https://archive.org/details/{ident}",
                "stream_url": best_video["stream_url"],
                "quality": format_video_quality_badge(best_video),
                "size_mb": round(size_mb, 1),
            }

    return best_match


def load_directors_from_table(xlsx_path=DEFAULT_XLSX_PATH, csv_path=DEFAULT_CSV_FALLBACK):
    if USE_LOCAL_TABLE_IF_EXISTS:
        if os.path.exists(xlsx_path):
            try:
                return pd.read_excel(xlsx_path, sheet_name=TARGET_SHEET_NAME)
            except Exception as e:
                print(f"[Upozorenje] Ne mogu učitati list '{TARGET_SHEET_NAME}' iz {xlsx_path}: {e}", flush=True)
        if os.path.exists(csv_path):
            return pd.read_csv(csv_path)
    return pd.DataFrame(NEW_DIRECTORS)


def save_results_to_excel(xlsx_path, df_trazi, summary_rows, detailed_rows):
    """Upisuje rezultate izravno u list 'traži' i dodaje detaljni list 'Pronađeni_filmovi'."""
    summary_by_dir = {r["Redatelj"]: r for r in summary_rows}

    df_out = df_trazi.copy()
    df_out["TMDB filmova"] = df_out["Redatelj"].map(
        lambda d: summary_by_dir.get(str(d).strip(), {}).get("TMDB filmova", 0)
    )
    df_out["Pronađeno na Archive.org"] = df_out["Redatelj"].map(
        lambda d: summary_by_dir.get(str(d).strip(), {}).get("Pronađeno", 0)
    )
    df_out["Filmovi i kvaliteta na Archive.org"] = df_out["Redatelj"].map(
        lambda d: summary_by_dir.get(str(d).strip(), {}).get("Filmovi i kvaliteta", "Nema pronađenih zapisa")
    )
    df_out["Archive.org linkovi"] = df_out["Redatelj"].map(
        lambda d: summary_by_dir.get(str(d).strip(), {}).get("Linkovi", "")
    )

    df_details = pd.DataFrame(detailed_rows) if detailed_rows else pd.DataFrame(
        columns=["Redatelj", "Naslov (HR)", "Izvorni naslov", "Godina", "Trajanje (min)", "Kvaliteta videa", "Archive.org stranica", "Izravni video URL"]
    )

    if os.path.exists(xlsx_path):
        with pd.ExcelWriter(xlsx_path, engine="openpyxl", mode="a", if_sheet_exists="replace") as writer:
            df_out.to_excel(writer, sheet_name=TARGET_SHEET_NAME, index=False)
            df_details.to_excel(writer, sheet_name="Pronađeni_filmovi", index=False)
    else:
        with pd.ExcelWriter(xlsx_path, engine="openpyxl") as writer:
            df_out.to_excel(writer, sheet_name=TARGET_SHEET_NAME, index=False)
            df_details.to_excel(writer, sheet_name="Pronađeni_filmovi", index=False)

    wb = openpyxl.load_workbook(xlsx_path)
    if TARGET_SHEET_NAME in wb.sheetnames:
        ws = wb[TARGET_SHEET_NAME]
        col_widths = {"A": 6, "B": 32, "C": 16, "D": 42, "E": 15, "F": 22, "G": 65, "H": 55}
        for col_letter, width in col_widths.items():
            ws.column_dimensions[col_letter].width = width
        for row in ws.iter_rows(min_row=2):
            for cell in row:
                cell.alignment = openpyxl.styles.Alignment(wrap_text=True, vertical="top")
    if "Pronađeni_filmovi" in wb.sheetnames:
        ws2 = wb["Pronađeni_filmovi"]
        for col_letter, width in {"A": 30, "B": 32, "C": 32, "D": 10, "E": 14, "F": 32, "G": 45, "H": 55}.items():
            ws2.column_dimensions[col_letter].width = width
    wb.save(xlsx_path)

    df_out.to_csv("directors_traži_rezultati.csv", index=False, encoding="utf-8-sig")
    df_details.to_csv("directors_pronađeni_filmovi.csv", index=False, encoding="utf-8-sig")


def run_directors_archive_search():
    start_ts = time.monotonic()
    print("=== [REDATELJI] Pretraga TMDB filmografija i provjera kvalitete na Archive.org ===", flush=True)

    df_trazi = load_directors_from_table()
    if "Redatelj" not in df_trazi.columns:
        raise ValueError("Stupac 'Redatelj' nije pronađen u tablici.")

    summary_rows = []
    all_detailed_rows = []

    for _, row in df_trazi.iterrows():
        director_name = str(row["Redatelj"]).strip()
        if not director_name or director_name.lower() == "nan":
            continue

        print(f"\n🎬 Obrada autora: {director_name}...", flush=True)
        filmography = fetch_director_filmography_tmdb(director_name)
        print(f"   TMDB filmografija (igrani filmovi): {len(filmography)} naslova", flush=True)

        found_for_director = []
        with ThreadPoolExecutor(max_workers=6) as pool:
            futures = {pool.submit(search_movie_on_archive, m, director_name): m for m in filmography}
            for fut in as_completed(futures):
                match = fut.result()
                if match:
                    found_for_director.append(match)

        found_for_director.sort(key=lambda x: x["year"])

        if found_for_director:
            for item in found_for_director:
                print(
                    f"   ✓ {item['hr_title']} ({item['year']}) -> [{item['quality']}] | {item['archive_page']}",
                    flush=True
                )
                all_detailed_rows.append({
                    "Redatelj": director_name,
                    "Naslov (HR)": item["hr_title"],
                    "Izvorni naslov": item["orig_title"],
                    "Godina": item["year"],
                    "Trajanje (min)": item["runtime_min"],
                    "Kvaliteta videa": item["quality"],
                    "Archive.org stranica": item["archive_page"],
                    "Izravni video URL": item["stream_url"],
                })

            movies_summary_text = "\n".join(
                f"• {m['hr_title']} ({m['year']}) — {m['quality']}"
                for m in found_for_director
            )
            links_summary_text = "\n".join(m["archive_page"] for m in found_for_director)
        else:
            print("   ✕ Nema pronađenih dugometražnih filmova na Archive.org.", flush=True)
            movies_summary_text = "Nema pronađenih filmova na Archive.org"
            links_summary_text = ""

        summary_rows.append({
            "Redatelj": director_name,
            "TMDB filmova": len(filmography),
            "Pronađeno": len(found_for_director),
            "Filmovi i kvaliteta": movies_summary_text,
            "Linkovi": links_summary_text,
        })

    save_results_to_excel(DEFAULT_XLSX_PATH, df_trazi, summary_rows, all_detailed_rows)
    elapsed = time.monotonic() - start_ts
    print(
        f"\n=== Završeno za {elapsed:.1f} s! Pronađeno ukupno {len(all_detailed_rows)} filmova. "
        f"Rezultati su upisani u '{DEFAULT_XLSX_PATH}' (list '{TARGET_SHEET_NAME}' i 'Pronađeni_filmovi'). ===",
        flush=True
    )


if __name__ == "__main__":
    run_directors_archive_search()
