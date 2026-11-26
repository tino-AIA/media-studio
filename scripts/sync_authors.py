import re
import urllib.parse
from concurrent.futures import ThreadPoolExecutor, as_completed

from sync_common import (
    DUNE_1984_MARKER_RE,
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
    is_foreign_dub_without_english,
    is_future_release,
    normalize_for_exact_title_compare,
    pick_best_video_file,
    safe_get,
    state_lock,
    supabase_delete_author_movies,
    supabase_patch_row,
    supabase_upsert,
    validate_movie_match,
    verify_stream_url_playable,
)

author_enrich_cache = {}

MAX_VERSIONS_PER_MOVIE = 2

AUTHORS = [
    ("Alfred Hitchcock", "filmography-alfred-hitchcock"),
    ("Francis Ford Coppola", "filmography-francis-ford-coppola"),
    ("Brian De Palma", "brian-de-palma-complete-filmography"),
    ("Sergio Leone", "filmography-sergio-leone"),
    ("David Lynch", "filmography-david-lynch"),
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
    ("Paul Thomas Anderson", "boogie-nights-1997"),
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
    ("Woody Allen", "filmography-woody-allen"),
    ("Robert Altman", "filmography-robert-altman"),
    ("Sion Sono", "filmography-sion-sono"),
    ("Peter Greenaway", "filmography-peter-greenaway"),
    ("Alan Clarke", "filmography-alan-clarke"),
    # Prethodni val (12 redatelja)
    ("Martin Scorsese", "taxi-driver-1976-4-k-remastered-1080p.bluray.dts-hd.ma.x-264"),
    ("Steven Spielberg", "jaws-35mm-1080p-avc-aac-2.0"),
    ("Quentin Tarantino", "reservoir-dogs-1992_202605"),
    ("Stanley Kubrick", "2001-a-space-odyssey-1968_202407"),
    ("Christopher Nolan", "memento.-2000.1080p.-blu-ray.x-264.-yify_202510"),
    ("Ridley Scott", "blade-runner-1982_202601"),
    ("Braća Coen", "fargo-1996_202605"),
    ("Pedro Almodóvar", "all-about-my-mother"),
    ("Denis Villeneuve", "incendies.-2010.1080p"),
    ("James Cameron", "the-terminator-1984_202606"),
    ("Michael Haneke", "funny-games_202511"),
    # Novi val (9 autora)
    ("Tim Burton", "edward-scissorhands_202607"),
    ("Wes Anderson", "bottle.-rocket.-1996"),
    ("Spike Lee", "Clockers"),
    ("David Fincher", "fight-club-1999-35mm-4-k.-scan-1080p-fx-265"),
    ("Guillermo del Toro", "el-laberinto-del-fauno-2006"),
    ("Jerry Bruckheimer", "top-gun_202611"),
    ("Peter Jackson", "king-kong-ultimate-edition"),
    ("Robert Zemeckis", "back-to-the-future-part-ii-1989_202610"),
    ("Russo braća", "the-gray-man_2022"),
]

# Provjereni pojedinačni Archive.org zapisi za redatelje i producente
VERIFIED_AUTHOR_ARCHIVE_ITEMS = {
    "Martin Scorsese": [
        (33878, "boxcar-bertha-1972_202406"),
        (103, "taxi-driver-1976-4-k-remastered-1080p.bluray.dts-hd.ma.x-264"),
        (262, "the-king-of-comedy_202512"),
        (10843, "after-hours-t-00"),
        (11873, "pO65tJRUkNO4"),
        (11051, "The-Last-Temptation-of-Christ_1988_AI_1080p.BluRay.10b.HEVC.DTS-HD.MA.5.1_BLUDUA"),
        (1598, "cape.-fear.-1991.2160p.-4-k.-web.x-265.10bit.-aac-5.1-yts.-mx"),
        (524, "casino_202610"),
        (398978, "the-irishman-2019-720p-blu-ray"),
    ],
    "Steven Spielberg": [
        (839, "duel.-1971.1080p.-blu-ray.x-265"),
        (578, "jaws-35mm-1080p-avc-aac-2.0"),
        (840, "close-encounter-of-the-third-kind-1977-the-special-edition-001"),
        (601, "e.-t.-the-extra-terrestrial-1982-2002_202611"),
        (10110, "empire.of.the.-sun.-1987.1080p.-blu-ray.x-264.-aac-etrg"),
        (11352, "always-1989"),
        (329, "jurassic.-park.-1993.1080p.-uhd.-blu-ray.x-265.-dd-5.1-pahe.in"),
        (857, "saving-private-ryan_202607"),
        (640, "catch-me-if-you-can-2002_20261013"),
        (180, "minority-report_202511"),
        (74, "war-of-the-worlds_202608"),
        (17578, "the.-adventures.of.-tintin.-2011.1080p.-blu-ray.-h-264.-aac-rarbg"),
    ],
    "Quentin Tarantino": [
        (500, "reservoir-dogs-1992_202605"),
        (24, "kill-bill-vol.-1-2003"),
        (393, "kill-bill-vol.-2-2004_202605"),
        (1991, "death-proof-2007"),
        (16869, "inglourious-basterds-2009_202605"),
        (68718, "django-unchained-2012_202605"),
        (273248, "the-hateful-eight-extended-netflix"),
        (466272, "once-upon-a-time...-in-hollywood-d-1-t-00"),
    ],
    "Stanley Kubrick": [
        (11474, "turner_video_15342"),
        (247, "the.-killing.-1956"),
        (975, "paths-of-glory_202204"),
        (935, "dr.-fantastico"),
        (62, "2001-a-space-odyssey-1968_202407"),
        (185, "a-clockwork-orange-1971-2160p"),
        (3175, "barry.-lyndon.-1975.-1080p.-BRRip.-x264.-YIFY"),
        (694, "1980-stanley-kubrick-the-shining"),
        (600, "1987-full-metal-jacket-stanley-kubrick"),
        (345, "Eyes-Wide-Shut_1999_AI_1080p.BluRay.10b.HEVC.DTS-HD.MA.5.1_BLUDUAINE"),
    ],
    "Christopher Nolan": [
        (77, "memento.-2000.1080p.-blu-ray.x-264.-yify_202510"),
    ],
    "Ridley Scott": [
        (78, "blade-runner-1982_202601"),
        (3167, "black-rain-1989-1080p-pvca"),
        (1541, "vlc-record-2024-08-06-23h16m08s-dvd____D__-"),
        (1492, "1492-conquest-of-paradise-1992"),
        (855, "black.-hawk.-down.-2001.-extended.-1080p.-blu-ray.-ddp-5.1.x-265.10bit-galaxy-rg-265"),
        (9740, "hannibal--2001--1920x1040"),
        (1495, "kingdom-of-heaven_202404"),
        (70981, "prometheus-2012_202606"),
    ],
    "Braća Coen": [
        (11368, "8202084551199"),
        (378, "raising-arizona-sub-esp"),
        (275, "fargo-1996_202605"),
        (115, "the.-big.-lebowski.-1998.-remastered.-1080p.-blu-ray.-h-264.-aac-rarbg_202610"),
        (134, "2000-o-brother-where-art-thou"),
        (44264, "true-grit-2010_202604"),
        (591538, "the-tragedy-of-macbeth"),
    ],
    "Pedro Almodóvar": [
        (36697, "pepi-luci-bom-y-otras-chicas-del-monton-1980-dvd-rip-high-code"),
        (34554, "labyrinth.of.-passion.-1982.-spanish.-1080p.-webrip.x-265-vxt"),
        (30315, "entre-tinieblas-1983-dark-habits-bdrip-1080p-high-code"),
        (26569, "que-he-hecho-yo-para-merecer-esto"),
        (24645, "matador.-1986.-spanish.-1080p.-blu-ray.x-265-vxt"),
        (4274, "women-on-the-verge"),
        (8217, "tacones-lejanos-1991-high-heels-1080p-high-code"),
        (8226, "kika-1993"),
        (11362, "la-flor-de-mi-secreto-1995-bdrip-1080p-high-code"),
        (10388, "carne-tremula-1997"),
        (99, "all-about-my-mother"),
        (64, "hable-con-ella-2002"),
        (72, "la-mala-educacion-2004"),
        (219, "volver-2006-esp"),
        (21028, "los-abrazos-rotos-2009-broken-embraces-bdrip-1080p-high-code"),
        (63311, "the-skin-i-live-in-2011-esp"),
        (139567, "los-amantes-pasajeros-2013"),
    ],
    "Paul Thomas Anderson": [
        (4995, "boogie-nights-1997"),
        (8052, "hfdrwez-ko-xxh"),
        (334, "magnolia-1999"),
        (8051, "punchdrunklove2002"),
        (7345, "there-will-be-blood-2007-daniel-day-lewis-paul-dano-based-on-oil-by-upton-sinclair_202409"),
    ],
    "Denis Villeneuve": [
        (46738, "incendies.-2010.1080p"),
        (146233, "prisoners_202511"),
    ],
    "James Cameron": [
        (218, "the-terminator-1984_202606"),
        (679, "aliens-1986_202606"),
        (2756, "the-abyss-1989-hd"),
        (280, "terminator-2-judgment-day-1991-blu-ray-v-2-720p-fzmovies.net-4beb-52c-21b-25a-27018a-52684ae-967a-6d"),
        (36955, "true-lies-1994_202610"),
        (597, "titanic-1997-35mm-scan"),
    ],
    "Michael Haneke": [
        (26444, "the-seventh-continent-1989-pvca"),
        (42793, "71-fragmente-einer-chronologie-des-zufalls-michael-haneke-1994-trilogy-part-iii-"),
        (10234, "funny-games_202511"),
        (26564, "michael-haneke-1997-the-castle-vidomon"),
        (1717, "the-piano-teacher-2001"),
        (86837, "VILIBAZMIO-amour-2012"),
    ],
    "Tim Burton": [
        (162, "edward-scissorhands_202607"),
        (522, "ed.-wood.-1994.1080p.-blu-ray.x-264.-yify"),
        (869, "planet-of-the-apes-usa-canada-en-es-disc-1-2-disc-special-edition-nuon"),
        (587, "bigfish_dishondemand"),
        (118, "charlie-and-the-chocolate-factory-2005-720p-br-rip_202607"),
    ],
    "Wes Anderson": [
        (13685, "bottle.-rocket.-1996"),
        (9428, "the-royal-tenenbaums-disc-1"),
        (10315, "fantastic-mr.-fox-2009_202603"),
    ],
    "Spike Lee": [
        (20649, "Clockers"),
        (38410, "get-on-the-bus-spike-lee-1996"),
        (40814, "girl-61996.1080p-blu-ray.x-265-rarbg"),
        (13537, "bamboozled-2000-720p-w.subs-x-265-hevc"),
        (44755, "sucker-free-city"),
        (487558, "jskf9osvcDevolt758aLkERIAL"),
    ],
    "David Fincher": [
        (807, "Se7en.1995.Remastered.Edition.Blu-ray.1080p.VC-1.DTS-HD.MA.7.1.REMUX-FraMeSToR"),
        (2649, "the-game-1997-bdrip-ita-eng-1080p-x-265-paso-77"),
        (550, "fight-club-1999-35mm-4-k.-scan-1080p-fx-265"),
    ],
    "Guillermo del Toro": [
        (11655, "cronos-1993_202408"),
        (36586, "blade.-2.2002.720p.-brrip.-dual-audio.x-264.www.mastitorrents.com"),
        (1487, "Hellboy.2004.720p.BluRay.DUAL.x264"),
        (1417, "el-laberinto-del-fauno-2006"),
    ],
    "Jerry Bruckheimer": [
        (26175, "1975-farewell-my-lovely"),
        (29972, "march-or-die-1977"),
        (11062, "vlc-record-2025-09-04-21h36m55s-dvd____E__-"),
        (11524, "thief-1981-directors-cut-1080p-pvca"),
        (11454, "cat-people-1982_202603"),
        (42159, "thief.-of.-hearts.-1984"),
        (744, "top-gun_202611"),
        (10872, "the-ref-1994_202602"),
        (1701, "con-air-cw-5-17-26"),
        (95, "armageddon-1998-criterion-dvd-rip"),
        (9798, "enemy-of-the-state-1998_202408"),
        (6282, "coyote-ugly-1080p_202506"),
        (10637, "remember-the-titans-2000"),
        (855, "black.-hawk.-down.-2001.-extended.-1080p.-blu-ray.-ddp-5.1.x-265.10bit-galaxy-rg-265"),
        (676, "pearl-harbor-2001-directors-cut.-720p"),
        (8961, "bad-boys-ii-2003_202605"),
        (10628, "kangaroo-jack-double-feature-dvd"),
        (9477, "king-arthur_202101"),
        (2059, "national-treasure_202504"),
        (38700, "bad-boys-for-life-2020_202407"),
        (929590, "the-ministry-of-ungentlemanly-warfare"),
    ],
    "Peter Jackson": [
        (9964, "bad-taste-1987-full-movie"),
        (8216, "meet-the-feebles-1989_202406"),
        (763, "braindead-1992-classic-cult-comedy-1080p-upscale"),
        (1024, "heavenly-creatures_202507"),
        (254, "king-kong-ultimate-edition"),
    ],
    "Robert Zemeckis": [
        (27145, "i-wanna-hold-your-hand-1978"),
        (13684, "used-cars-1980"),
        (9326, "romancing-the-stone-1984-directed-by-robert-zemeckis"),
        (105, "hbo-showing-of-back-to-the-future"),
        (856, "Who-Framed-Roger-Rabbit-1988-English-Dub"),
        (165, "back-to-the-future-part-ii-1989_202610"),
        (196, "back-to-the-future-part-iii-1990_202610"),
        (8358, "Cast-Away_2000_AI_1080p.BluRay.10b.HEVC.FLAC.5.1_BLUDUAINE"),
        (2655, "what-lies-beneath_202210"),
        (2310, "beowulf-bagby"),
        (17979, "a-christmas-carol-animation-movies-for-kids"),
    ],
    "Russo braća": [
        (725201, "the-gray-man_2022"),
        (777443, "the-electric-state-movie"),
    ],
}

CURATED_AUTHOR_EXTRAS = {
    "David Lynch": [
        ("identifier:dune-david-lynch-1984", 841, "", 115),
        ("identifier:dune-1984-alternative-edition-redux-fanedit-m*", 841, "Dina - Alternative Edition Redux", 150),
        ("identifier:DuneTheCompleteSagaVimeo", 841, "Dina - The Complete Saga", 150),
    ]
}

CURATED_ARCHIVE_IDS = {
    "dune-david-lynch-1984",
    "DuneTheCompleteSagaVimeo",
}


def estimate_source_quality_tier(ident, stream_url="", title=""):
    return compute_aspect_tier(f"{ident} {stream_url} {title}")


def is_special_edition(ident, stream_url="", title=""):
    return bool(EXTENDED_CUT_TAG_RE.search(f"{ident} {stream_url} {title}"))


def is_mismatched_modern_dune(tmdb_id, *texts):
    if int(tmdb_id or 0) not in (438631, 693134):
        return False
    blob = " ".join(str(t) for t in texts if t)
    return bool(DUNE_1984_MARKER_RE.search(blob))


def build_movie_version_maps(all_rows):
    version_counts = {}
    best_tiers = {}
    for row in all_rows:
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


def can_promote_movie_version(mid, tmdb_version_counts, tmdb_best_tier, cand_tier, special_flag=False):
    if mid <= 0:
        return False
    count = tmdb_version_counts.get(mid, 0)
    if count == 0:
        return True
    current_best = tmdb_best_tier.get(mid, 1)
    if current_best == 0:
        return cand_tier > 0 or special_flag
    if count >= MAX_VERSIONS_PER_MOVIE:
        return False
    return (cand_tier > current_best) or special_flag


def retire_vhs_rows_for_tmdb(mid, new_arch_id, rel_year, all_rows, tmdb_version_counts):
    if mid <= 0 or (rel_year > 0 and rel_year < 1955):
        return 0
    rows_to_retire = []
    with state_lock:
        for row in all_rows:
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
        print(f"    🗑 Uklonjen stari VHS/4:3 rip nakon pronalaska bolje verzije autora: {r_title} ({r_ident})", flush=True)

    return len(rows_to_retire)


def extract_variant_label(filename):
    base = filename.rsplit("/", 1)[-1]
    name = re.sub(r"\.(mp4|mkv|avi|ogv|mov)$", "", base, flags=re.IGNORECASE)
    m = re.search(
        r"(?i)\b("
        r"the\s+complete\s+novel|complete\s+novel|the\s+complete\s+saga|complete\s+saga|"
        r"alternative\s+edition(?:\s+redux)?|redux|fanedit|fan\s*edit|special\s+edition|"
        r"director'?s?\s*cut|theatrical\s*cut|"
        r"extended\s*cut|extended|final\s*cut|workprint|unrated|uncut|tv\s*cut|"
        r"us\s*cut|eu\s*cut|uk\s*cut|restored|remastered|colorized|"
        r"part\s*\d+|pt\.?\s*\d+|cd\s*\d+|disc\s*\d+|1080p|720p"
        r")\b",
        name
    )
    if m:
        return re.sub(r"\s+", " ", m.group(1)).strip()
    return ""


def get_author_search_names(author_name):
    if author_name == "PTA Filmography":
        return ["Paul Thomas Anderson"]
    if author_name in ("Braća Coen", "Braća Coen (Joel i Ethan)"):
        return ["Joel Coen", "Ethan Coen"]
    if author_name in ("Russo braća", "Russo braća (Anthony i Joe Russo)"):
        return ["Anthony Russo", "Joe Russo"]
    return [author_name]


def author_matches_tmdb_credits(author_name, details):
    if not details:
        return False
    if author_name == "Jerry Bruckheimer":
        return True
    search_names = get_author_search_names(author_name)
    surnames = set()
    for s_person in search_names:
        tokens = [t for t in re.sub(r"[^\w\s]", " ", s_person.lower()).split() if len(t) >= 4]
        if tokens:
            surnames.add(tokens[-1])
    if not surnames:
        return False
    people = (details.get("directors") or []) + (details.get("all_crew") or []) + (details.get("cast") or [])
    for p in people:
        p_words = set(re.sub(r"[^\w\s]", " ", str(p).lower()).split())
        if surnames & p_words:
            return True
    return False


def title_closely_matches(clean_title, orig_title, hr_title):
    c_norm = normalize_for_exact_title_compare(clean_title)
    if len(c_norm) < 2:
        return False
    c_letters = re.sub(r"[^a-z0-9]", "", c_norm)
    for target in (orig_title, hr_title):
        t_norm = normalize_for_exact_title_compare(target)
        if not t_norm:
            continue
        if c_norm == t_norm:
            return True
        if len(c_norm) >= 4 and len(t_norm) >= 4 and (c_norm in t_norm or t_norm in c_norm):
            return True
        t_letters = re.sub(r"[^a-z0-9]", "", t_norm)
        if len(c_letters) >= 4 and len(t_letters) >= 4 and (c_letters in t_letters or t_letters in c_letters):
            return True
    return False


def tmdb_enrich_author_movie(title, year=0, author_name="", filmography_credits=None):
    cleaned_input = re.sub(r"^\s*\d{1,2}\s*[\.\-\)\]]*\s*(?=[A-Za-zČĆŽŠĐ])", "", title).strip()
    if len(re.sub(r"[^a-zA-Zčćžšđа-яА-Я0-9]", "", cleaned_input)) < 2:
        return None

    cache_key = (cleaned_input.lower(), year, author_name)
    with state_lock:
        if cache_key in author_enrich_cache:
            return author_enrich_cache[cache_key]

    query_variants = [cleaned_input]
    dehyphenated = re.sub(r"(?<=[a-zA-Z])-(?=[a-zA-Z]\b)", "", cleaned_input)
    if dehyphenated != cleaned_input:
        query_variants.append(dehyphenated)

    results = []
    for q_var in query_variants:
        params = {"api_key": TMDB_API_KEY, "query": q_var, "language": "hr-HR", "include_adult": "false"}
        if year > 0:
            params["year"] = year
        res = safe_get(f"{TMDB_BASE}/search/movie", params=params)
        results = (res or {}).get("results", [])
        if not results and year > 0:
            params.pop("year")
            res = safe_get(f"{TMDB_BASE}/search/movie", params=params)
            results = (res or {}).get("results", [])
        if results:
            break

    best_match = None
    best_details = None

    for candidate in (results or [])[:4]:
        cand_id = int(candidate.get("id") or 0)
        if is_mismatched_modern_dune(cand_id, cleaned_input, author_name):
            continue
        rel_date = candidate.get("release_date") or ""
        cand_year = int(rel_date[:4]) if len(rel_date) >= 4 and rel_date[:4].isdigit() else 0
        if year > 0 and cand_year > 0 and abs(cand_year - year) > 2:
            continue

        details = get_tmdb_movie_details(cand_id)
        if not details:
            continue

        orig_t = details["orig_title"]
        hr_t = details["hr_title"]
        credit_ok = author_matches_tmdb_credits(author_name, details)
        title_ok = title_closely_matches(cleaned_input, orig_t, hr_t)

        if credit_ok and (title_ok or (year > 0 and cand_year > 0 and abs(cand_year - year) <= 1)):
            best_match = candidate
            best_details = details
            break

        if title_ok and year > 0 and cand_year > 0 and abs(cand_year - year) <= 1 and best_match is None:
            best_match = candidate
            best_details = details

    if not best_details and filmography_credits and year > 0:
        first_word = re.split(r"[^a-zA-Z0-9čćžšđ]+", cleaned_input.lower())[0]
        if len(first_word) >= 3:
            for c in filmography_credits:
                c_rel = c.get("release_date") or ""
                c_year = int(c_rel[:4]) if len(c_rel) >= 4 and c_rel[:4].isdigit() else 0
                if c_year > 0 and abs(c_year - year) <= 1:
                    c_orig = (c.get("original_title") or "").lower()
                    c_title = (c.get("title") or "").lower()
                    if first_word in c_orig or first_word in c_title:
                        det = get_tmdb_movie_details(c["id"])
                        if det and author_matches_tmdb_credits(author_name, det):
                            best_match = c
                            best_details = det
                            break

    if not best_match or not best_details:
        with state_lock:
            author_enrich_cache[cache_key] = None
        return None

    rel_date = best_details["release_date"] or best_match.get("release_date") or ""
    rel_year = int(rel_date[:4]) if len(rel_date) >= 4 and rel_date[:4].isdigit() else year
    poster_path = best_details["poster_path"] or best_match.get("poster_path") or ""

    enriched_obj = {
        "tmdb_id": best_details["tmdb_id"],
        "title": best_details["hr_title"] or best_details["orig_title"] or cleaned_input,
        "release_year": rel_year,
        "description_hr": best_details["overview_hr"],
        "poster_url": f"{TMDB_IMG_POSTER}{poster_path}" if poster_path else "",
        "details": best_details
    }
    with state_lock:
        author_enrich_cache[cache_key] = enriched_obj
    return enriched_obj


def fetch_wiki_bio_and_photo(author_name):
    wiki_map = {
        "Braća Coen": "Coen brothers",
        "Braća Coen (Joel i Ethan)": "Coen brothers",
        "Russo braća": "Russo brothers",
        "Russo braća (Anthony i Joe Russo)": "Russo brothers",
        "PTA Filmography": "Paul Thomas Anderson",
    }
    search_name = wiki_map.get(author_name, author_name)
    encoded = urllib.parse.quote(search_name.replace(" ", "_"))
    bio = ""
    photo = ""
    for lang in ["hr", "en"]:
        data = safe_get(f"https://{lang}.wikipedia.org/api/rest_v1/page/summary/{encoded}")
        if data:
            if not bio and data.get("extract"):
                bio = data["extract"].strip()
            if not photo:
                thumb = (data.get("thumbnail") or {}).get("source") or (data.get("originalimage") or {}).get("source") or ""
                if thumb.startswith("http"):
                    photo = thumb
        if bio and photo:
            break
    return bio, photo


def compute_credits_score(credits_list):
    by_movie = {}
    for c in credits_list:
        mid = c.get("id")
        if not mid:
            continue
        vc = int(c.get("vote_count") or 0)
        pop = float(c.get("popularity") or 0.0)
        if mid not in by_movie or vc > by_movie[mid][0]:
            by_movie[mid] = (vc, pop)
    top_movies = sorted(by_movie.values(), key=lambda x: (-x[0], -x[1]))[:6]
    if not top_movies:
        return 0.0
    max_votes = top_movies[0][0]
    top_votes_sum = sum(x[0] for x in top_movies)
    top_pop_sum = sum(x[1] for x in top_movies)
    return (max_votes * 0.6) + (top_votes_sum * 0.25) + (top_pop_sum * 15.0)


def fetch_author_tmdb_profile(author_name, archive_id):
    """Dohvaća portret, biografiju, TMDB indeks poznatosti i popis filmova iz filmografije autora."""
    search_names = get_author_search_names(author_name)
    is_producer_author = author_name == "Jerry Bruckheimer"
    photo_url = ""
    bio_hr = ""
    fame_score = 0.0
    dedup_credits = {}

    for search_person in search_names:
        person_res = safe_get(
            f"{TMDB_BASE}/search/person",
            {"api_key": TMDB_API_KEY, "query": search_person, "language": "hr-HR"}
        )
        candidates = (person_res or {}).get("results") or []
        person = None
        if candidates:
            if is_producer_author:
                prod_only = [p for p in candidates if p.get("known_for_department") == "Production"]
                pool_list = prod_only if prod_only else candidates
            elif search_person not in {"Elvis Presley", "Jackie Collins"}:
                directors_only = [p for p in candidates if p.get("known_for_department") == "Directing"]
                pool_list = directors_only if directors_only else candidates
            else:
                pool_list = candidates
            person = max(pool_list, key=lambda p: float(p.get("popularity") or 0.0))

        if person:
            if not photo_url and person.get("profile_path"):
                photo_url = f"{TMDB_IMG_POSTER}{person['profile_path']}"
            details = safe_get(
                f"{TMDB_BASE}/person/{person['id']}",
                {"api_key": TMDB_API_KEY, "language": "hr-HR", "append_to_response": "movie_credits"}
            )
            if not bio_hr:
                bio_hr = ((details or {}).get("biography") or "").strip()
            person_pop = float((details or {}).get("popularity") or person.get("popularity") or 0.0)

            credits = (details or {}).get("movie_credits") or {}
            crew_list = credits.get("crew") or []
            cast_list = credits.get("cast") or []

            if is_producer_author:
                produced = [c for c in crew_list if c.get("job") in ("Producer", "Executive Producer")]
                prod_score = compute_credits_score(produced)
                fame_score = max(fame_score, (person_pop * 120.0) + prod_score)
                raw_pool = produced
            else:
                directed = [c for c in crew_list if c.get("job") == "Director"]
                directing_score = compute_credits_score(directed)
                other_score = compute_credits_score(crew_list + cast_list) * 0.65
                fame_score = max(fame_score, (person_pop * 120.0) + max(directing_score, other_score))
                raw_pool = directed if (directed and search_person not in {"Elvis Presley", "Jackie Collins"}) else (directed + crew_list + cast_list)

            for c in raw_pool:
                mid = c.get("id")
                if not mid or c.get("adult"):
                    continue
                rel_d = c.get("release_date") or ""
                if is_future_release(rel_d):
                    continue
                vc = int(c.get("vote_count") or 0)
                if mid not in dedup_credits or vc > int(dedup_credits[mid].get("vote_count") or 0):
                    dedup_credits[mid] = c

    filmography_credits = sorted(
        dedup_credits.values(),
        key=lambda x: (-int(x.get("vote_count") or 0), -float(x.get("popularity") or 0.0))
    )[:35]

    if not bio_hr or not photo_url:
        wiki_bio, wiki_photo = fetch_wiki_bio_and_photo(author_name)
        if not bio_hr:
            bio_hr = wiki_bio
        if not photo_url:
            photo_url = wiki_photo

    return {
        "name": author_name,
        "archive_id": archive_id,
        "photo_url": photo_url,
        "bio_hr": bio_hr,
        "fame_score": round(fame_score, 2),
        "filmography_credits": filmography_credits
    }


def search_archive_for_author_movie(author_name, tmdb_det, year, main_archive_id, existing_archive_ids):
    """Traži nedostajući film autora na Archive.org-u izvan njegove glavne kolekcije."""
    orig_title = tmdb_det["orig_title"]
    hr_title = tmdb_det["hr_title"]
    clean_q = re.sub(r"[^\w\s]", " ", orig_title).strip()
    if len(clean_q) < 2:
        return None

    is_anim = 16 in (tmdb_det.get("genre_ids") or [])
    min_mb = 80 if is_anim else 150
    year_clause = f" AND year:[{year - 1} TO {year + 1}]" if year > 0 else ""
    query = f'title:"{clean_q}"{year_clause} AND mediatype:movies AND -subject:trailer AND -subject:news AND -subject:review'

    data = safe_get(
        "https://archive.org/advancedsearch.php",
        params={
            "q": query,
            "fl[]": ["identifier", "title", "downloads"],
            "sort[]": "downloads desc",
            "rows": 5,
            "output": "json"
        }
    )
    valid_candidates = []
    for doc in (data or {}).get("response", {}).get("docs", []):
        ident = doc.get("identifier") or ""
        if not ident or ident == main_archive_id or ident in existing_archive_ids:
            continue
        if ident.lower().startswith(("youtube-", "yt-")):
            continue

        videos, arch_md = get_archive_item_details(ident, min_size_mb=min_mb, allow_mkv=True)
        if not videos:
            continue
        best = pick_best_video_file(videos)
        ok, _ = validate_movie_match(
            ident,
            arch_md,
            best["duration_mins"],
            tmdb_det,
            [orig_title, hr_title],
            year,
            require_credits=True,
            chosen_filename=best["filename"],
            chosen_size_mb=best["size_mb"]
        )
        if ok and author_matches_tmdb_credits(author_name, tmdb_det) and verify_stream_url_playable(best["stream_url"]):
            aspect_tier = best.get("aspect_tier", 1)
            downloads = float(doc.get("downloads") or 0)
            valid_candidates.append((aspect_tier, best["size_mb"], downloads, ident, best))
            if year < 1955 or aspect_tier == 2:
                break

    if not valid_candidates:
        return None

    if year >= 1955:
        valid_candidates.sort(key=lambda x: (-x[0], -x[1], -x[2]))
    else:
        valid_candidates.sort(key=lambda x: (-x[2], -x[1]))

    _, _, _, best_ident, best_file = valid_candidates[0]
    return best_ident, best_file


def run_authors_sync():
    print(f"=== [AUTORI] Rangiranje i duboka sinkronizacija {len(AUTHORS)} kolekcija autora ===", flush=True)
    existing_archive_ids, seen_tmdb_movies, _, all_rows = fetch_existing_catalog_state("movie")
    tmdb_version_counts, tmdb_best_tier = build_movie_version_maps(all_rows)
    author_promoted_rows = [r for r in all_rows if r.get("is_valid", True) and ",tmdb-" in (r.get("archive_id") or "")]
    catalog_by_tmdb = {
        int(r["tmdb_id"]): r
        for r in all_rows
        if r.get("is_valid", True) and (r.get("tmdb_id") or 0) > 0 and r.get("stream_url")
    }
    promoted_counter = [0]

    with ThreadPoolExecutor(max_workers=12) as pool:
        profiles = list(pool.map(lambda a: fetch_author_tmdb_profile(a[0], a[1]), AUTHORS))

    profiles.sort(key=lambda p: (-p["fame_score"], p["name"]))
    print("  Poredak autora po TMDB poznatosti (1 = najpoznatiji):", flush=True)
    for rank_idx, prof in enumerate(profiles, start=1):
        print(f"    {rank_idx:2d}. {prof['name']} (indeks: {prof['fame_score']:.0f})", flush=True)

    def promote_movie_to_catalog(catalog_arch_id, mid, tmdb_det, final_year, stream_url, file_rt, filename=""):
        mid = int(mid)
        if is_mismatched_modern_dune(mid, catalog_arch_id, stream_url, filename):
            return False

        cand_tier = estimate_source_quality_tier(catalog_arch_id, stream_url, f"{tmdb_det['hr_title']} {filename}")
        special_flag = is_special_edition(catalog_arch_id, stream_url, filename)

        with state_lock:
            if catalog_arch_id in existing_archive_ids:
                return False
            if not can_promote_movie_version(mid, tmdb_version_counts, tmdb_best_tier, cand_tier, special_flag):
                return False

            had_only_vhs = (tmdb_best_tier.get(mid, -1) == 0 and cand_tier > 0)
            seen_tmdb_movies.add(mid)
            existing_archive_ids.add(catalog_arch_id)
            tmdb_version_counts[mid] = tmdb_version_counts.get(mid, 0) + 1
            if cand_tier > tmdb_best_tier.get(mid, -1):
                tmdb_best_tier[mid] = cand_tier

        genres = determine_primary_genres(
            tmdb_det["genre_ids"],
            tmdb_det["companies"],
            tmdb_det["orig_title"],
            tmdb_det["hr_title"],
            tmdb_det["overview_hr"]
        )
        adjusted_pop = tmdb_det["popularity"] * 100.0 + (final_year * 2) + (400.0 if cand_tier == 2 else 0.0)
        cat_row = {
            "archive_id": catalog_arch_id,
            "tmdb_id": mid,
            "title": tmdb_det["hr_title"],
            "media_type": "movie",
            "genres": genres,
            "release_year": final_year,
            "rating": round(tmdb_det["vote_average"], 1),
            "popularity_score": adjusted_pop,
            "description_hr": tmdb_det["overview_hr"],
            "poster_url": f"{TMDB_IMG_POSTER}{tmdb_det['poster_path']}",
            "backdrop_url": f"{TMDB_IMG_BACKDROP}{tmdb_det['backdrop_path']}" if tmdb_det["backdrop_path"] else "",
            "stream_url": stream_url,
            "is_featured": tmdb_det["vote_average"] >= 8.0 and 16 not in tmdb_det["genre_ids"],
            "is_valid": True,
            "tmdb_runtime": tmdb_det["runtime"],
            "archive_runtime": file_rt
        }
        supabase_upsert("catalog_items", [cat_row], "archive_id")

        if had_only_vhs:
            retire_vhs_rows_for_tmdb(mid, catalog_arch_id, final_year, all_rows, tmdb_version_counts)

        with state_lock:
            promoted_counter[0] += 1
        return True

    def process_single_author(idx_prof):
        idx, prof = idx_prof
        author_name = prof["name"]
        archive_id = prof["archive_id"]

        saved_authors = supabase_upsert(
            "authors",
            [{
                "name": author_name,
                "archive_id": archive_id,
                "bio_hr": prof["bio_hr"],
                "photo_url": prof["photo_url"],
                "sort_order": idx
            }],
            "name"
        )
        if not saved_authors:
            return f"  [{idx}/{len(profiles)}] {author_name}: greška upisa autora"
        author_db_id = saved_authors[0]["id"]

        videos, arch_md = get_archive_item_details(archive_id, min_size_mb=15, allow_mkv=True)
        deduped = {}
        for m in videos:
            clean_t, year = clean_filename_to_title(m["filename"], fallback_to_stem=True)
            if not clean_t:
                continue
            stem_key = re.sub(r"(?i)\.ia$", "", m["filename"].rsplit("/", 1)[-1].rsplit(".", 1)[0]).lower()
            is_mp4 = m["filename"].lower().endswith(".mp4")
            orig_bonus = 2000.0 if (not m["is_derivative"] and is_mp4) else (1500.0 if not m["is_derivative"] else 500.0)
            score = m["size_mb"] + orig_bonus
            if stem_key not in deduped or score > deduped[stem_key]["score"]:
                deduped[stem_key] = {
                    "title": clean_t,
                    "year": year,
                    "filename": m["filename"],
                    "stream_url": m["stream_url"],
                    "size_mb": m["size_mb"],
                    "duration_mins": m["duration_mins"],
                    "score": score
                }

        unique_movie_rows = {}
        found_tmdb_ids = set()
        promoted_for_author = 0
        extra_found_count = 0
        valid_catalog_arch_ids = set()

        # Korak A: Filmovi iz glavne Archive.org kolekcije autora
        for item in sorted(deduped.values(), key=lambda x: x["year"], reverse=True):
            enriched = tmdb_enrich_author_movie(
                item["title"],
                item["year"],
                author_name,
                filmography_credits=prof.get("filmography_credits")
            )
            base_title = enriched["title"] if enriched else item["title"]
            final_year = enriched["release_year"] if (enriched and enriched["release_year"]) else item["year"]
            mid = enriched["tmdb_id"] if enriched else 0
            if mid > 0:
                found_tmdb_ids.add(mid)

            final_title = base_title
            dedup_key = (final_title.lower(), final_year)
            if dedup_key in unique_movie_rows:
                if unique_movie_rows[dedup_key]["stream_url"] == item["stream_url"]:
                    continue
                variant_tag = extract_variant_label(item["filename"])
                final_title = f"{base_title} ({variant_tag})" if variant_tag else f"{base_title} (verzija {len(unique_movie_rows) + 1})"
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

            tmdb_det = enriched.get("details") if enriched else None
            if tmdb_det and mid > 0:
                catalog_arch_id = f"{archive_id},tmdb-{mid}"
                tmdb_rt = tmdb_det["runtime"]
                file_rt = item["duration_mins"]
                is_feature_length = (file_rt >= 60) or (file_rt == 0 and tmdb_rt >= 60 and item["size_mb"] >= 120)
                runtime_consistent = (
                    file_rt == 0 or tmdb_rt == 0 or (int(tmdb_rt * 0.85) <= file_rt <= int(tmdb_rt * 1.18))
                )
                year_consistent = (
                    item["year"] == 0 or final_year == 0 or abs(item["year"] - final_year) <= 1
                )
                not_adult_or_dub = (
                    not tmdb_det["adult"]
                    and tmdb_det["status"] == "Released"
                    and not is_future_release(tmdb_det["release_date"])
                    and not contains_hentai_or_adult(tmdb_det["orig_title"], tmdb_det["hr_title"], tmdb_det["overview_hr"])
                    and not contains_blocked_regional_or_arabic(tmdb_det["orig_title"], tmdb_det["hr_title"], orig_lang=tmdb_det["original_language"])
                    and not is_foreign_dub_without_english(arch_md, archive_id, item["filename"], tmdb_det["original_language"])
                )

                if (
                    is_feature_length
                    and runtime_consistent
                    and year_consistent
                    and not_adult_or_dub
                    and tmdb_det["poster_path"]
                    and verify_stream_url_playable(item["stream_url"])
                ):
                    valid_catalog_arch_ids.add(catalog_arch_id)
                    if promote_movie_to_catalog(
                        catalog_arch_id, mid, tmdb_det, final_year, item["stream_url"], file_rt, item["filename"]
                    ):
                        promoted_for_author += 1

        # Korak B0: Provjereni Archive.org zapisi iz tablica pretrage autora
        for verified_tmdb_id, verified_arch_id in VERIFIED_AUTHOR_ARCHIVE_ITEMS.get(author_name, []):
            if verified_tmdb_id in found_tmdb_ids:
                continue
            tmdb_det = get_tmdb_movie_details(verified_tmdb_id)
            if not tmdb_det or not tmdb_det["poster_path"]:
                continue
            final_year = extract_year_from_text(tmdb_det["release_date"])
            title_hr = tmdb_det["hr_title"] or tmdb_det["orig_title"]
            dedup_key = (title_hr.lower(), final_year)
            if dedup_key in unique_movie_rows:
                found_tmdb_ids.add(verified_tmdb_id)
                continue

            vids, v_md = get_archive_item_details(verified_arch_id, min_size_mb=120, allow_mkv=True)
            if not vids:
                continue
            best_v = pick_best_video_file(vids)
            if is_foreign_dub_without_english(v_md, verified_arch_id, best_v["filename"], tmdb_det["original_language"]):
                continue
            if not verify_stream_url_playable(best_v["stream_url"]):
                continue

            found_tmdb_ids.add(verified_tmdb_id)
            unique_movie_rows[dedup_key] = {
                "author_id": author_db_id,
                "title": title_hr,
                "release_year": final_year,
                "stream_url": best_v["stream_url"],
                "poster_url": f"{TMDB_IMG_POSTER}{tmdb_det['poster_path']}",
                "description_hr": tmdb_det["overview_hr"]
            }
            extra_found_count += 1
            if promote_movie_to_catalog(
                verified_arch_id, verified_tmdb_id, tmdb_det, final_year, best_v["stream_url"], best_v["duration_mins"], best_v["filename"]
            ):
                promoted_for_author += 1

        # Korak B1: Ručno odobrena specijalna izdanja za autora (npr. sve 3 verzije Dine za Davida Lyncha)
        for query_str, extra_tmdb_id, custom_title, min_mins in CURATED_AUTHOR_EXTRAS.get(author_name, []):
            tmdb_det = get_tmdb_movie_details(extra_tmdb_id)
            if not tmdb_det:
                continue
            final_year = extract_year_from_text(tmdb_det["release_date"]) or 1984
            title_hr = custom_title.strip() or tmdb_det["hr_title"] or tmdb_det["orig_title"]
            dedup_key = (title_hr.lower(), final_year)
            if dedup_key in unique_movie_rows:
                found_tmdb_ids.add(extra_tmdb_id)
                continue

            ident_prefix = query_str.replace("identifier:", "").rstrip("*").lower()
            matched_row = next(
                (
                    r for r in all_rows
                    if r.get("is_valid", True)
                    and (r.get("archive_id") or "").lower().startswith(ident_prefix)
                    and r.get("stream_url")
                ),
                None
            )
            stream_url = matched_row["stream_url"] if matched_row else ""

            if not stream_url:
                search_res = safe_get(
                    "https://archive.org/advancedsearch.php",
                    params={"q": query_str, "fl[]": ["identifier"], "rows": 2, "output": "json"}
                )
                docs = (search_res or {}).get("response", {}).get("docs", [])
                if docs:
                    ext_id = docs[0].get("identifier") or ""
                    if ext_id:
                        vids, _ = get_archive_item_details(ext_id, min_size_mb=250, allow_mkv=True)
                        long_vids = [
                            v for v in vids
                            if v.get("duration_mins", 0) >= min_mins
                            or (v.get("duration_mins", 0) == 0 and v.get("size_mb", 0) >= 900)
                        ]
                        if long_vids:
                            best_v = pick_best_video_file(long_vids)
                            if verify_stream_url_playable(best_v["stream_url"]):
                                stream_url = best_v["stream_url"]

            if stream_url:
                found_tmdb_ids.add(extra_tmdb_id)
                unique_movie_rows[dedup_key] = {
                    "author_id": author_db_id,
                    "title": title_hr,
                    "release_year": final_year,
                    "stream_url": stream_url,
                    "poster_url": f"{TMDB_IMG_POSTER}{tmdb_det['poster_path']}" if tmdb_det["poster_path"] else "",
                    "description_hr": tmdb_det["overview_hr"]
                }
                extra_found_count += 1

        # Korak B2: Pretraga nedostajućih filmova autora u postojećoj bazi i na Archive.org-u
        for credit in prof.get("filmography_credits") or []:
            mid = int(credit.get("id") or 0)
            if mid <= 0 or mid in found_tmdb_ids:
                continue

            tmdb_det = get_tmdb_movie_details(mid)
            if not tmdb_det or not tmdb_det["poster_path"]:
                continue
            if tmdb_det["adult"] or (tmdb_det["status"] and tmdb_det["status"] != "Released"):
                continue
            if is_future_release(tmdb_det["release_date"]):
                continue
            if tmdb_det["runtime"] > 0 and tmdb_det["runtime"] < 55:
                continue
            if not author_matches_tmdb_credits(author_name, tmdb_det):
                continue

            final_year = extract_year_from_text(tmdb_det["release_date"])
            title_hr = tmdb_det["hr_title"] or tmdb_det["orig_title"]
            dedup_key = (title_hr.lower(), final_year)
            if dedup_key in unique_movie_rows:
                found_tmdb_ids.add(mid)
                continue

            existing_cat = catalog_by_tmdb.get(mid)
            if existing_cat and verify_stream_url_playable(existing_cat["stream_url"]):
                found_tmdb_ids.add(mid)
                unique_movie_rows[dedup_key] = {
                    "author_id": author_db_id,
                    "title": title_hr,
                    "release_year": final_year,
                    "stream_url": existing_cat["stream_url"],
                    "poster_url": f"{TMDB_IMG_POSTER}{tmdb_det['poster_path']}",
                    "description_hr": tmdb_det["overview_hr"]
                }
                extra_found_count += 1
                continue

            found_ext = search_archive_for_author_movie(author_name, tmdb_det, final_year, archive_id, existing_archive_ids)
            if found_ext:
                ext_ident, ext_file = found_ext
                found_tmdb_ids.add(mid)
                unique_movie_rows[dedup_key] = {
                    "author_id": author_db_id,
                    "title": title_hr,
                    "release_year": final_year,
                    "stream_url": ext_file["stream_url"],
                    "poster_url": f"{TMDB_IMG_POSTER}{tmdb_det['poster_path']}",
                    "description_hr": tmdb_det["overview_hr"]
                }
                extra_found_count += 1
                if promote_movie_to_catalog(
                    ext_ident, mid, tmdb_det, final_year, ext_file["stream_url"], ext_file["duration_mins"], ext_file["filename"]
                ):
                    promoted_for_author += 1

        for old_row in author_promoted_rows:
            if old_row["archive_id"].startswith(f"{archive_id},tmdb-") and old_row["archive_id"] not in valid_catalog_arch_ids:
                supabase_patch_row("catalog_items", old_row["id"], {"is_valid": False})

        supabase_delete_author_movies(author_db_id)
        movie_rows = sorted(unique_movie_rows.values(), key=lambda x: x["release_year"], reverse=True)
        if movie_rows:
            supabase_upsert("author_movies", movie_rows, "author_id,title,release_year")

        return (
            f"  [{idx}/{len(profiles)}] {author_name}: {len(movie_rows)} filmova "
            f"(+{extra_found_count} pronađeno izvan kolekcije, +{promoted_for_author} u žanrove)"
        )

    with ThreadPoolExecutor(max_workers=10) as pool:
        futures = [pool.submit(process_single_author, item) for item in enumerate(profiles, start=1)]
        for fut in as_completed(futures):
            print(fut.result(), flush=True)

    print(f"=== [AUTORI] Završeno! Promovirano {promoted_counter[0]} filmova u glavne žanrove. ===", flush=True)


if __name__ == "__main__":
    run_authors_sync()
