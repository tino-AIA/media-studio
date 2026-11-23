import os
import re
import sys
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from sync_common import (
    NON_LATIN_TITLE_RE,
    TMDB_API_KEY,
    TMDB_BASE,
    TMDB_IMG_BACKDROP,
    TMDB_IMG_POSTER,
    clean_filename_to_title,
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

ARCHIVE_FEATURE_COLLECTIONS = [
    ("feature_films", 10),
    ("opensource_movies", 8),
    ("SciFi_Films", 8),
    ("film_noir", 6),
    ("comedy_films", 6),
    ("vhsvault", 6),
    ("laserdiscarchive", 5),
    ("colorized-and-upscaled-movies", 5),
    ("miscellaneous_feature_films", 6),
    ("westerns", 6),
    ("war_films", 6),
    ("horror_films", 6),
    ("romance_films", 6),
    ("thriller_films", 6),
    ("action_adventure_films", 6),
    ("drama_films", 6),
    ("mystery_films", 6),
    ("animationandcartoons", 6),
]

TMDB_DISCOVER_GENRE_SEEDS = [
    ("37", "Vestern", 20),
    ("27", "Horor", 30),
    ("10752", "Ratni", 20),
    ("878", "Sci-Fi klasici", 30),
    ("80", "Krimi", 35),
    ("53", "Triler i misterija", 35),
    ("9648", "Triler i misterija", 30),
    ("35", "Komedija", 40),
    ("10749", "Romantični", 25),
    ("28", "Akcija i avantura", 35),
    ("12", "Akcija i avantura", 35),
    ("18", "Drama", 45),
]

CULT_AND_ANIMATION_SEEDS = [
    "Dune", "Star Wars", "The Empire Strikes Back", "Return of the Jedi",
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


def extract_archive_title_candidates(raw_title, ident):
    """Izvlači čiste varijante naziva filma i stvarnu godinu iz naslova/ID-a s Archive.org-a."""
    raw_str = str(raw_title or "").strip()
    year_in_title = extract_year_from_text(raw_str) or extract_year_from_text(ident)

    # Ukloni sve nakon razdjelnika s redateljem, glumcima ili opisom
    trimmed_sep = re.split(r"\s+(?:-|–|—|\||/)\s+", raw_str, maxsplit=1)[0].strip()
    trimmed_credit = re.split(
        r"(?i)\s+(?:directed\s+by|dir\.?|starring|feat\.?|with)\b",
        trimmed_sep,
        maxsplit=1
    )[0].strip()

    candidates = []
    for variant in (trimmed_credit, trimmed_sep, raw_str, ident.replace("-", " ").replace("_", " ")):
        cleaned, y = clean_filename_to_title(variant)
        if cleaned and len(cleaned) >= 2 and cleaned not in candidates:
            candidates.append(cleaned)
        if not year_in_title and y:
            year_in_title = y

    return candidates, year_in_title


def sync_curated_movie_exceptions(all_movie_rows, existing_archive_ids, seen_tmdb_ids, seen_title_years):
    """Ubacuje ručno odobrene verzije filmova (uključujući produžena izdanja) u Sci-Fi klasike i katalog."""
    print("0/3 Provjera ručno odobrenih izdanja filmova...", flush=True)
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
        genres = determine_primary_genres(
            details["genre_ids"],
            details["companies"],
            details["orig_title"],
            final_title,
            details["overview_hr"],
            "Sci-Fi klasici"
        )
        row = {
            "archive_id": ident,
            "tmdb_id": item["tmdb_id"],
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
        curated_tmdb_to_mark.add(item["tmdb_id"])
        norm_t = normalize_for_exact_title_compare(final_title)
        if norm_t:
            seen_title_years.add((norm_t, rel_year))
        print(f"  + Odobreno izdanje: {row['title']} ({best['duration_mins']} min) -> {', '.join(genres)}", flush=True)

    seen_tmdb_ids.update(curated_tmdb_to_mark)


def find_best_archive_movie_match(details, year, exclude_ids):
    """Traži najkvalitetniju izvornu/englesku verziju filma na Archive.org-u."""
    orig_title = details["orig_title"]
    hr_title = details["hr_title"]
    clean_q = re.sub(r"[^\w\s]", " ", orig_title).strip()
    if len(clean_q) < 2:
        return None

    is_anim = 16 in (details.get("genre_ids") or [])
    min_mb = 80 if is_anim else 180
    require_credits = has_multiple_tmdb_versions(orig_title)
    year_clause = f" AND year:[{year - 1} TO {year + 1}]" if year > 0 else ""
    query = f'title:"{clean_q}"{year_clause} AND mediatype:movies AND -subject:trailer AND -subject:news AND -subject:review'

    data = safe_get(
        "https://archive.org/advancedsearch.php",
        params={
            "q": query,
            "fl[]": ["identifier", "title", "downloads"],
            "sort[]": "downloads desc",
            "rows": 8,
            "output": "json"
        }
    )
    valid_candidates = []
    for doc in (data or {}).get("response", {}).get("docs", []):
        ident = doc.get("identifier")
        if not ident or ident in exclude_ids or ident.lower().startswith(("youtube-", "yt-")):
            continue
        videos, arch_md = get_archive_item_details(ident, min_size_mb=min_mb, allow_mkv=True)
        if not videos:
            continue
        best = pick_best_video_file(videos)
        arch_mins = best["duration_mins"]

        ok, _ = validate_movie_match(
            ident,
            arch_md,
            arch_mins,
            details,
            [orig_title, hr_title],
            year,
            require_credits=require_credits,
            chosen_filename=best["filename"],
            chosen_size_mb=best["size_mb"]
        )
        if ok and verify_stream_url_playable(best["stream_url"]):
            aspect_tier = best.get("aspect_tier", 1)
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


def audit_and_repair_existing_movies(all_movie_rows, existing_archive_ids, seen_tmdb_ids, seen_title_years):
    """Provjerava postojeće filmove u bazi: zamjenjuje strane sinkronizacije originalom i prevodi nelatinične naslove."""
    valid_rows = [r for r in all_movie_rows if r.get("is_valid", True)]
    print(f"1/3 Provjera jezika i naslova za {len(valid_rows)} postojećih filmova u bazi...", flush=True)
    replaced_count = [0]
    removed_count = [0]
    translated_count = [0]

    def inspect_movie_row(row):
        row_id = row["id"]
        ident = row.get("archive_id") or ""
        title = row.get("title") or ""
        stream_url = row.get("stream_url") or ""
        tmdb_id = int(row.get("tmdb_id") or 0)
        rel_year = int(row.get("release_year") or 0)
        is_author_promoted = ",tmdb-" in ident
        base_ident = ident.split(",tmdb-")[0].strip()

        arch_md = {}
        if not is_author_promoted and base_ident:
            md_res = safe_get(f"https://archive.org/metadata/{base_ident}/metadata", timeout=8)
            arch_md = (md_res or {}).get("result") or {}

        has_non_latin_title = bool(NON_LATIN_TITLE_RE.search(title))
        looks_blocked_text = contains_blocked_regional_or_arabic(
            title, ident, arch_md.get("title"), arch_md.get("language")
        )
        suspect_dub = is_foreign_dub_without_english(arch_md, base_ident, stream_url, orig_lang="en")

        if not has_non_latin_title and not looks_blocked_text and not suspect_dub:
            return

        if tmdb_id <= 0:
            if looks_blocked_text or is_foreign_dub_without_english(arch_md, base_ident, stream_url, orig_lang=""):
                supabase_patch_row("catalog_items", row_id, {"is_valid": False})
                with state_lock:
                    removed_count[0] += 1
                print(f"  ✕ Uklonjena strana sinkronizacija: {title}", flush=True)
            return

        details = get_tmdb_movie_details(tmdb_id)
        if not details:
            return

        orig_lang = details.get("original_language", "")
        if contains_blocked_regional_or_arabic(
            details["orig_title"], details["hr_title"], title, orig_lang=orig_lang
        ):
            supabase_patch_row("catalog_items", row_id, {"is_valid": False})
            with state_lock:
                seen_tmdb_ids.discard(tmdb_id)
                removed_count[0] += 1
            print(f"  ✕ Uklonjen blokirani jezik ({orig_lang}): {title}", flush=True)
            return

        actual_dub = is_foreign_dub_without_english(arch_md, base_ident, stream_url, orig_lang=orig_lang)
        if actual_dub:
            final_year = rel_year or extract_year_from_text(details["release_date"])
            with state_lock:
                exclude_snapshot = set(existing_archive_ids)
            replacement = find_best_archive_movie_match(details, final_year, exclude_snapshot)
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
                    with state_lock:
                        replaced_count[0] += 1
                    print(f"  🔊 Zamijenjen strani audio originalnim: {details['hr_title']} ({final_year}) -> {new_ident}", flush=True)
                    return

            supabase_patch_row("catalog_items", row_id, {"is_valid": False})
            with state_lock:
                seen_tmdb_ids.discard(tmdb_id)
                removed_count[0] += 1
            print(f"  ✕ Uklonjena strana sinkronizacija bez originala: {details['hr_title']} ({final_year})", flush=True)
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

    with ThreadPoolExecutor(max_workers=12) as pool:
        list(pool.map(inspect_movie_row, valid_rows))

    print(
        f"  Provjera završena: {replaced_count[0]} zamijenjeno originalom, "
        f"{removed_count[0]} uklonjeno, {translated_count[0]} prevedeno.",
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
    hint_genre=None
):
    mid = details["tmdb_id"]
    norm_t = normalize_for_exact_title_compare(details["hr_title"])
    ty_key = (norm_t, rel_year)

    with state_lock:
        if mid in seen_tmdb_ids or arch_id in existing_archive_ids:
            return False
        if norm_t and ty_key in seen_title_years:
            return False
        seen_tmdb_ids.add(mid)
        existing_archive_ids.add(arch_id)
        if norm_t:
            seen_title_years.add(ty_key)

    genres = determine_primary_genres(
        details["genre_ids"],
        details["companies"],
        details["orig_title"],
        details["hr_title"],
        details["overview_hr"],
        hint_genre
    )
    row = {
        "archive_id": arch_id,
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
        "stream_url": stream_url,
        "is_featured": details["vote_average"] >= 7.8 and 16 not in details["genre_ids"],
        "is_valid": True,
        "tmdb_runtime": details["runtime"],
        "archive_runtime": arch_mins
    }
    supabase_upsert("catalog_items", [row], "archive_id")
    print(f"  + Film: {row['title']} ({rel_year}) -> {', '.join(genres)}", flush=True)
    return True


def mine_archive_collections(existing_archive_ids, seen_tmdb_ids, seen_title_years):
    print("2/3 Rudarenje glavnih i žanrovskih Archive.org filmskih kolekcija...", flush=True)
    archive_docs = {}

    def fetch_col_page(col_page):
        col, page = col_page
        query = f"mediatype:movies AND collection:{col} AND -collection:television AND -subject:trailer AND -subject:news"
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
        return (data or {}).get("response", {}).get("docs", [])

    tasks = [(col, p) for col, max_p in ARCHIVE_FEATURE_COLLECTIONS for p in range(1, max_p + 1)]
    with ThreadPoolExecutor(max_workers=10) as pool:
        for docs in pool.map(fetch_col_page, tasks):
            for doc in docs:
                ident = doc.get("identifier")
                if ident and ident not in existing_archive_ids and ident not in archive_docs:
                    archive_docs[ident] = doc

    total_docs = len(archive_docs)
    print(f"  Pronađeno {total_docs} novih Archive.org stavki za obradu...", flush=True)

    added = [0]
    processed = [0]
    stats = {
        "no_tmdb_match": 0,
        "already_in_db": 0,
        "no_video_file": 0,
        "validation_rejected": 0,
    }

    def inc_stat(key):
        with state_lock:
            stats[key] += 1

    def process_archive_doc(doc):
        ident = doc.get("identifier") or ""
        with state_lock:
            processed[0] += 1
            p_now = processed[0]
            if p_now % 500 == 0:
                print(
                    f"  ... Obrađeno {p_now}/{total_docs} stavki (dodano {added[0]} filmova)...",
                    flush=True
                )

        if not ident or ident.lower().startswith(("youtube-", "yt-")):
            return

        raw_title = doc.get("title") or ident
        if isinstance(raw_title, list):
            raw_title = raw_title[0]
        if contains_hentai_or_adult(ident, raw_title) or contains_blocked_regional_or_arabic(ident, raw_title):
            return

        title_candidates, parsed_y = extract_archive_title_candidates(str(raw_title), ident)
        if not title_candidates:
            return

        # Pretraži TMDB po čistim varijantama naziva (prvo s godinom iz naslova/ID-a, zatim bez godine)
        results = []
        for cand_q in title_candidates[:2]:
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
            inc_stat("no_tmdb_match")
            return

        # Odaberi najboljeg TMDB kandidata koji se poklapa po nazivu i godini
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
            inc_stat("no_tmdb_match")
            return

        mid = chosen_hit["id"]
        with state_lock:
            if mid in seen_tmdb_ids:
                inc_stat("already_in_db")
                return

        details = get_tmdb_movie_details(mid)
        if not details or not details["poster_path"]:
            inc_stat("no_tmdb_match")
            return

        is_anim = 16 in (details.get("genre_ids") or [])
        videos, arch_md = get_archive_item_details(ident, min_size_mb=(80 if is_anim else 160), allow_mkv=True)
        if not videos:
            inc_stat("no_video_file")
            return
        best = pick_best_video_file(videos)
        arch_mins = best["duration_mins"]

        rel_year = extract_year_from_text(details["release_date"]) or parsed_y
        require_credits = has_multiple_tmdb_versions(details["orig_title"])
        ok, _ = validate_movie_match(
            ident,
            arch_md,
            arch_mins,
            details,
            [details["orig_title"], details["hr_title"]] + title_candidates,
            rel_year,
            require_credits=require_credits,
            chosen_filename=best["filename"],
            chosen_size_mb=best["size_mb"]
        )
        if not ok or not verify_stream_url_playable(best["stream_url"]):
            inc_stat("validation_rejected")
            return

        downloads = float(doc.get("downloads") or 0)
        pop_score = max(details["popularity"] * 100.0, min(downloads / 10.0, 15000.0)) + (rel_year * 2)
        if save_verified_movie_row(
            ident, details, rel_year, best["stream_url"], arch_mins, pop_score,
            seen_tmdb_ids, seen_title_years, existing_archive_ids
        ):
            with state_lock:
                added[0] += 1

    with ThreadPoolExecutor(max_workers=10) as pool:
        list(pool.map(process_archive_doc, archive_docs.values()))

    print(
        f"  Dodano {added[0]} filmova iz Archive.org kolekcija "
        f"(već u bazi: {stats['already_in_db']}, bez TMDB podudaranja: {stats['no_tmdb_match']}, "
        f"bez video datoteke: {stats['no_video_file']}, odbačeno provjerom: {stats['validation_rejected']}).",
        flush=True
    )


def search_tmdb_cult_and_genres(existing_archive_ids, seen_tmdb_ids, seen_title_years):
    print("3/3 Ciljana pretraga kultnih klasika, animiranih filmova i TMDB žanrova...", flush=True)
    candidates = {}

    def fetch_seed(seed_title):
        res = safe_get(f"{TMDB_BASE}/search/movie", {"api_key": TMDB_API_KEY, "query": seed_title, "language": "hr-HR", "include_adult": "false"})
        return (res or {}).get("results", [])[:2]

    with ThreadPoolExecutor(max_workers=12) as pool:
        for res_list in pool.map(fetch_seed, CULT_AND_ANIMATION_SEEDS):
            for m in res_list:
                mid = m["id"]
                if mid not in seen_tmdb_ids and mid not in candidates and not is_future_release(m.get("release_date") or ""):
                    candidates[mid] = (m, None)

    for tmdb_genre_id, hr_genre_label, min_votes in TMDB_DISCOVER_GENRE_SEEDS:
        for page in range(1, 6):
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
                    "page": page
                }
            )
            for m in (data or {}).get("results", []):
                mid = m["id"]
                if mid not in seen_tmdb_ids and mid not in candidates and not is_future_release(m.get("release_date") or ""):
                    if not contains_blocked_regional_or_arabic(m.get("original_title"), m.get("title"), orig_lang=m.get("original_language", "")):
                        candidates[mid] = (m, hr_genre_label)

    print(f"  Provjera {len(candidates)} TMDB kandidata na Archive.org...", flush=True)
    added = [0]

    def process_candidate(item):
        m, hint_genre = item
        mid = m["id"]
        with state_lock:
            if mid in seen_tmdb_ids:
                return

        rel_date = m.get("release_date") or ""
        year = int(rel_date[:4]) if len(rel_date) >= 4 and rel_date[:4].isdigit() else 0
        details = get_tmdb_movie_details(mid)
        if not details or not details["poster_path"]:
            return
        if details["runtime"] > 0 and details["runtime"] < 55:
            return

        with state_lock:
            exclude_snapshot = set(existing_archive_ids)
        match = find_best_archive_movie_match(details, year, exclude_snapshot)
        if match:
            best_ident, best_file, best_mins = match
            pop_score = float(m.get("popularity") or 10.0) * 100.0 + (year * 2)
            if save_verified_movie_row(
                best_ident, details, year, best_file["stream_url"], best_mins, pop_score,
                seen_tmdb_ids, seen_title_years, existing_archive_ids, hint_genre
            ):
                with state_lock:
                    added[0] += 1

    with ThreadPoolExecutor(max_workers=12) as pool:
        list(pool.map(process_candidate, candidates.values()))

    print(f"  Dodano {added[0]} kultnih i žanrovskih filmova.", flush=True)


def run_movies_sync():
    print("=== [FILMOVI] Pokretanje brze sinkronizacije igranih i animiranih filmova ===", flush=True)
    existing_archive_ids, seen_tmdb_ids, seen_title_years, all_movie_rows = fetch_existing_catalog_state("movie")
    print(f"  U bazi već postoji {len(seen_tmdb_ids)} potvrđenih filmova.", flush=True)
    audit_and_repair_existing_movies(all_movie_rows, existing_archive_ids, seen_tmdb_ids, seen_title_years)
    sync_curated_movie_exceptions(all_movie_rows, existing_archive_ids, seen_tmdb_ids, seen_title_years)
    mine_archive_collections(existing_archive_ids, seen_tmdb_ids, seen_title_years)
    search_tmdb_cult_and_genres(existing_archive_ids, seen_tmdb_ids, seen_title_years)
    print(f"=== [FILMOVI] Završeno! Ukupno filmova u katalogu: {len(seen_tmdb_ids)} ===", flush=True)


if __name__ == "__main__":
    run_movies_sync()
