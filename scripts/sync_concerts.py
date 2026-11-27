import os
import random
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor

import requests

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import sync_common as sc
from sync_common import (
    clean_filename_to_title,
    compute_aspect_tier,
    contains_blocked_regional_or_arabic,
    contains_hentai_or_adult,
    fetch_existing_catalog_state,
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

_HTTP_SESSION = requests.Session()
_HTTP_ADAPTER = requests.adapters.HTTPAdapter(pool_connections=32, pool_maxsize=32, max_retries=1)
_HTTP_SESSION.mount("https://", _HTTP_ADAPTER)
_HTTP_SESSION.mount("http://", _HTTP_ADAPTER)
if hasattr(sc, "session"):
    sc.session = _HTTP_SESSION

MAX_CONCERTS_SYNC_SECONDS = 48 * 60
MAX_VHS_UPGRADE_CHECKS_PER_RUN = 25
MAX_CONCERT_CANDIDATES_PER_RUN = 600

CONCERT_JUNK_RE = re.compile(
    r"(?i)(?:\b(?:trailer|teaser|sample|interview|review|reaction|karaoke|cover\s+band|"
    r"tribute\s+band|lesson|tutorial|unboxing|promo|commercial|podcast|audio\s+only|"
    r"electric\s+sheep|vj\s+loop|burlesque)\b|"
    r"making[\s._-]+of|behind[\s._-]+the[\s._-]+scenes)"
)

CONCERT_ROCK_METAL_ARTISTS = [
    "Queen", "Pink Floyd", "Led Zeppelin", "AC/DC", "Metallica", "Iron Maiden",
    "Black Sabbath", "Deep Purple", "Guns N' Roses", "Nirvana", "Pearl Jam",
    "Soundgarden", "Alice in Chains", "Foo Fighters", "Red Hot Chili Peppers", "U2",
    "The Rolling Stones", "The Beatles", "The Who", "Jimi Hendrix", "The Doors",
    "Eric Clapton", "Dire Straits", "Bruce Springsteen", "Bon Jovi", "Van Halen",
    "Aerosmith", "Kiss", "Scorpions", "Judas Priest", "Motorhead", "Megadeth",
    "Slayer", "Pantera", "Sepultura", "System of a Down", "Korn", "Slipknot",
    "Linkin Park", "Rammstein", "Nightwish", "Tool", "Rage Against the Machine",
    "Green Day", "The Offspring", "Ramones", "Sex Pistols", "The Clash", "The Cure",
    "Depeche Mode", "Joy Division", "New Order", "Talking Heads", "R.E.M.",
    "Radiohead", "Oasis", "Blur", "Muse", "Coldplay", "INXS", "Simple Minds",
    "Tears for Fears", "Sting", "The Police", "Phil Collins", "Genesis", "Peter Gabriel",
    "David Bowie", "Lou Reed", "Iggy Pop", "Patti Smith", "Santana", "Bob Dylan",
    "Neil Young", "Fleetwood Mac", "Eagles", "Lynyrd Skynyrd", "ZZ Top", "Rush",
    "Yes", "King Crimson", "Jethro Tull", "Whitesnake", "Def Leppard", "Motley Crue",
    "Ozzy Osbourne", "Alice Cooper", "Faith No More", "Beastie Boys",
    "Creedence Clearwater Revival", "Janis Joplin", "Cream", "The Yardbirds",
    "The Kinks", "The Animals", "The Byrds", "Grateful Dead", "Jefferson Airplane",
    "Crosby, Stills, Nash & Young", "Simon & Garfunkel", "Tom Petty", "Bob Seger",
    "Journey", "Foreigner", "Toto", "Boston", "Kansas", "Styx", "REO Speedwagon",
    "Heart", "Pat Benatar", "Joan Jett", "Blondie", "The Pretenders",
    "Siouxsie and the Banshees", "Bauhaus", "The Sisters of Mercy", "The Smiths",
    "Morrissey", "Pixies", "Sonic Youth", "The Smashing Pumpkins", "Stone Temple Pilots",
    "Bush", "Audioslave", "Chris Cornell", "Temple of the Dog", "Nine Inch Nails",
    "Marilyn Manson", "Ministry", "Rob Zombie", "White Zombie", "Disturbed",
    "Godsmack", "Avenged Sevenfold", "Five Finger Death Punch", "Trivium",
    "Lamb of God", "Machine Head", "Gojira", "Meshuggah", "Opeth", "Mastodon",
    "Dream Theater", "Symphony X", "Helloween", "Blind Guardian", "Gamma Ray",
    "Stratovarius", "Sonata Arctica", "Epica", "Within Temptation", "Therion",
    "Sabaton", "Powerwolf", "Amon Amarth", "Arch Enemy", "In Flames",
    "Children of Bodom", "Dark Tranquillity", "At the Gates", "Kreator", "Sodom",
    "Destruction", "Testament", "Exodus", "Anthrax", "Overkill", "Death",
    "Cannibal Corpse", "Morbid Angel", "Deicide", "Obituary", "Venom",
    "Celtic Frost", "Bathory", "Mayhem", "Emperor", "Dimmu Borgir", "Cradle of Filth",
    "Type O Negative", "Danzig", "Misfits", "Bad Religion", "NOFX", "Pennywise",
    "Rancid", "Social Distortion", "Blink-182", "Sum 41", "Rise Against",
    "Billy Talent", "Bad Brains", "Black Flag", "Dead Kennedys", "Fugazi",
    "Hüsker Dü", "The Replacements", "Dinosaur Jr.", "Pavement", "Weezer", "Beck",
    "The White Stripes", "Jack White", "The Black Keys", "The Strokes",
    "Arctic Monkeys", "Franz Ferdinand", "Interpol", "The Killers", "Kings of Leon",
    "Arcade Fire", "Vampire Weekend", "Tame Impala", "Queens of the Stone Age",
    "Kyuss", "Monster Magnet", "Clutch", "Fu Manchu", "Electric Wizard", "Sleep",
    "Cathedral", "Candlemass", "Dio", "Rainbow", "Thin Lizzy", "Uriah Heep",
    "Nazareth", "Status Quo", "Slade", "T. Rex", "Roxy Music", "Bryan Ferry",
    "Gary Moore", "Rory Gallagher", "Joe Bonamassa", "Jeff Beck", "Stevie Ray Vaughan",
    "The Allman Brothers Band", "The Doobie Brothers", "Steely Dan", "Chicago",
    "Peter Frampton", "Ted Nugent", "Sammy Hagar", "Bad Company", "Humble Pie",
    "Small Faces", "Faces", "Rod Stewart", "Joe Cocker", "Leon Russell",
    "Van Morrison", "Elvis Costello", "Joe Jackson", "Nick Cave", "PJ Harvey",
    "Björk", "Portishead", "Massive Attack", "The Prodigy", "The Chemical Brothers",
    "Faithless", "Garbage", "No Doubt", "Alanis Morissette", "The Cranberries",
    "Sinéad O'Connor", "Tori Amos", "Kate Bush", "Florence and the Machine",
    "Evanescence", "Paramore", "Halestorm", "Ghost", "Volbeat", "Alter Bridge",
    "Creed", "Nickelback", "3 Doors Down", "Staind", "Deftones", "Incubus",
    "Jane's Addiction", "Primus", "Living Colour", "Suicidal Tendencies", "Body Count",
    "Savatage", "Manowar", "Saxon", "Accept", "W.A.S.P.", "Twisted Sister",
    "Quiet Riot", "Skid Row", "Cinderella", "Poison", "Warrant", "Tesla",
    "Extreme", "Mr. Big", "Europe", "Marillion", "Porcupine Tree", "Steven Wilson",
    "Camel", "Caravan", "Gentle Giant", "Emerson, Lake & Palmer", "Alan Parsons Project",
    "Supertramp", "Electric Light Orchestra", "10cc", "The Moody Blues", "Procol Harum",
    "Hawkwind", "Blue Öyster Cult", "Cheap Trick", "The Cars",
]

CONCERT_POP_ARTISTS = [
    "Michael Jackson", "Madonna", "Prince", "Whitney Houston", "George Michael",
    "Wham!", "Tina Turner", "Elton John", "Freddie Mercury", "Kylie Minogue",
    "Robbie Williams", "Take That", "Spice Girls", "Backstreet Boys", "NSYNC",
    "Britney Spears", "Christina Aguilera", "Beyoncé", "Destiny's Child", "Rihanna",
    "Lady Gaga", "Katy Perry", "Taylor Swift", "Adele", "Amy Winehouse", "Sade",
    "Celine Dion", "Mariah Carey", "Janet Jackson", "Cher", "ABBA", "Bee Gees",
    "Modern Talking", "Pet Shop Boys", "Erasure", "Duran Duran", "Spandau Ballet",
    "A-ha", "Eurythmics", "Annie Lennox", "Cyndi Lauper", "Lionel Richie",
    "Stevie Wonder", "Marvin Gaye", "Diana Ross", "Donna Summer", "Barry White",
    "Earth, Wind & Fire", "Kool & the Gang", "Bob Marley", "UB40", "Shakira",
    "Ricky Martin", "Enrique Iglesias", "Julio Iglesias", "Eros Ramazzotti",
    "Laura Pausini", "Zucchero", "Gipsy Kings", "Buena Vista Social Club",
    "Justin Timberlake", "Bruno Mars", "Ed Sheeran", "Justin Bieber", "Ariana Grande",
    "Dua Lipa", "Billie Eilish", "Selena Gomez", "Miley Cyrus", "Demi Lovato",
    "Shawn Mendes", "Harry Styles", "One Direction", "Maroon 5", "The Weeknd",
    "Drake", "Usher", "Alicia Keys", "John Legend", "Ne-Yo", "Chris Brown",
    "Mary J. Blige", "TLC", "En Vogue", "Salt-N-Pepa", "Toni Braxton",
    "Boyz II Men", "Luther Vandross", "Teddy Pendergrass", "Aretha Franklin",
    "Otis Redding", "Sam Cooke", "James Brown", "Al Green", "Curtis Mayfield",
    "Isaac Hayes", "Sly and the Family Stone", "Parliament", "Funkadelic", "Chic",
    "Nile Rodgers", "Gloria Gaynor", "Village People", "Boney M.", "Sandra",
    "Kim Wilde", "Samantha Fox", "Sabrina", "Rick Astley", "Jason Donovan",
    "Bananarama", "Culture Club", "Boy George", "Simply Red", "Lisa Stansfield",
    "Roxette", "Ace of Base", "Aqua", "Vengaboys", "Haddaway", "Snap!",
    "2 Unlimited", "La Bouche", "Culture Beat", "Dr. Alban", "Technotronic",
    "Black Box", "C+C Music Factory", "Paula Abdul", "Debbie Gibson", "Tiffany",
    "New Kids on the Block", "Westlife", "Boyzone", "Sugababes", "Girls Aloud",
    "Little Mix", "Fifth Harmony", "The Pussycat Dolls", "Black Eyed Peas", "Fergie",
    "Gwen Stefani", "Nelly Furtado", "Pink", "Kelly Clarkson", "Avril Lavigne",
    "Sia", "Lana Del Rey", "Lorde", "Halsey", "Camila Cabello", "Ellie Goulding",
    "Jessie J", "Sam Smith", "Lewis Capaldi", "George Ezra", "James Blunt",
    "Dido", "Natalie Imbruglia", "Sheryl Crow", "Norah Jones", "Katie Melua",
    "Michael Bublé", "Tom Jones", "Engelbert Humperdinck", "Barbra Streisand",
    "Liza Minnelli", "Bette Midler", "Olivia Newton-John", "Dolly Parton",
    "Shania Twain", "Faith Hill", "LeAnn Rimes", "Carrie Underwood", "Gloria Estefan",
    "Miami Sound Machine", "Selena", "Juanes", "Luis Miguel", "Alejandro Sanz",
    "Carlos Vives", "Marc Anthony", "Jennifer Lopez", "Pitbull", "Daddy Yankee",
    "Maluma", "Bad Bunny", "J Balvin", "Rosalía", "Bryan Adams", "Richard Marx",
    "Michael Bolton", "Kenny G", "Chris Isaak", "Seal", "Terence Trent D'Arby",
    "Des'ree", "Gabrielle", "Corinne Bailey Rae", "Duffy", "Joss Stone",
    "Paloma Faith", "Emeli Sandé", "Leona Lewis", "Rita Ora", "Charli XCX",
    "Carly Rae Jepsen", "Kesha", "Christina Perri", "Colbie Caillat", "Jason Mraz",
    "John Mayer", "Jack Johnson", "James Morrison", "Paolo Nutini", "Mika",
    "Scissor Sisters", "Jamiroquai", "The Brand New Heavies", "Incognito",
    "Level 42", "ABC", "The Human League", "Orchestral Manoeuvres in the Dark",
    "Ultravox", "Alphaville", "Camouflage", "Falco", "Nena", "Kraftwerk",
    "Moby", "Daft Punk", "Justice", "Calvin Harris", "David Guetta", "Avicii",
    "Tiësto", "Armin van Buuren", "Swedish House Mafia", "Disclosure", "Rudimental",
    "Clean Bandit", "Years & Years", "Billy Joel", "Paul McCartney", "Ringo Starr",
    "Carole King", "James Taylor", "Cat Stevens", "Joni Mitchell", "Neil Diamond",
    "Barry Manilow", "Paul Simon", "Art Garfunkel", "Hall & Oates", "Huey Lewis and the News",
    "Kenny Loggins", "Christopher Cross", "Bonnie Tyler", "Belinda Carlisle",
    "Bangles", "The Go-Go's", "Enya", "Loreena McKennitt", "Sarah McLachlan",
    "Jewel", "Tracy Chapman", "Suzanne Vega", "Neneh Cherry", "All Saints",
    "Atomic Kitten", "Steps", "S Club 7", "East 17", "98 Degrees", "Hanson",
    "Savage Garden", "Darren Hayes", "Train", "OneRepublic", "Imagine Dragons",
    "Twenty One Pilots", "The Script", "Bastille", "Foster the People", "MGMT",
    "Empire of the Sun", "M83", "Röyksopp", "Goldfrapp", "Moloko", "Róisín Murphy",
    "Dannii Minogue", "Sophie Ellis-Bextor", "Nelly", "Pharrell Williams",
    "Timbaland", "Missy Elliott", "Aaliyah", "Brandy",
    "Monica", "Ashanti", "Ciara", "Kelly Rowland", "Solange", "Erykah Badu",
    "Lauryn Hill", "Jill Scott", "Maxwell", "D'Angelo", "Frank Ocean", "SZA",
    "H.E.R.", "Daniel Caesar", "Khalid", "Post Malone", "Doja Cat", "Olivia Rodrigo",
]

CONCERT_CLASSICAL_JAZZ_ARTISTS = [
    "Miles Davis", "John Coltrane", "Louis Armstrong", "Duke Ellington", "Ella Fitzgerald",
    "Billie Holiday", "Nina Simone", "Frank Sinatra", "Tony Bennett", "Nat King Cole",
    "Ray Charles", "B.B. King", "Muddy Waters", "Buddy Guy", "Herbie Hancock",
    "Chick Corea", "Keith Jarrett", "Pat Metheny", "Oscar Peterson", "Chet Baker",
    "Stan Getz", "Dave Brubeck", "Charlie Parker", "Dizzy Gillespie", "Thelonious Monk",
    "Count Basie", "Glenn Miller", "Benny Goodman", "Luciano Pavarotti", "Plácido Domingo",
    "José Carreras", "Three Tenors", "Andrea Bocelli", "Maria Callas", "Sarah Brightman",
    "Herbert von Karajan", "Leonard Bernstein", "Yo-Yo Ma", "Itzhak Perlman",
    "Vladimir Horowitz", "Arthur Rubinstein", "Glenn Gould", "Ennio Morricone",
    "Vangelis", "Jean-Michel Jarre", "Diana Krall", "Jamie Cullum", "Wynton Marsalis",
    "Branford Marsalis", "Sonny Rollins", "Wayne Shorter", "Jaco Pastorius",
    "Weather Report", "Return to Forever", "Mahavishnu Orchestra", "Marcus Miller",
    "Stanley Clarke", "George Benson", "Al Jarreau", "Bobby McFerrin", "Quincy Jones",
]

ROCK_METAL_LOOKUP = {a.lower() for a in CONCERT_ROCK_METAL_ARTISTS} | {
    "rock", "metal", "punk", "grunge", "hard rock", "heavy metal", "thrash", "indie",
    "alternative", "woodstock", "glastonbury", "monsters of rock", "download festival",
    "acdc", "guns n roses", "motörhead", "rem", "motley crue"
}

POP_LOOKUP = {a.lower() for a in CONCERT_POP_ARTISTS} | {
    "pop", "dance", "disco", "r&b", "soul", "funk", "reggae", "latin", "synthpop",
    "eurodance", "live aid", "mtv", "beyonce", "celine dion", "michael buble",
    "emeli sande", "tiesto", "roisin murphy"
}

CLASSICAL_JAZZ_LOOKUP = {a.lower() for a in CONCERT_CLASSICAL_JAZZ_ARTISTS} | {
    "jazz", "blues", "classical", "symphony", "orchestra", "opera", "tenor", "soprano",
    "philharmonic", "quartet", "concerto", "sonata", "mozart", "beethoven", "bach",
    "vivaldi", "tchaikovsky", "chopin", "verdi", "puccini", "montreux jazz",
    "bb king", "placido domingo", "jose carreras"
}


def estimate_concert_quality_tier(ident, stream_url="", title=""):
    return compute_aspect_tier(f"{ident} {stream_url} {title}")


def is_junk_concert(ident, title):
    return bool(CONCERT_JUNK_RE.search(f"{ident} {title}"))


def looks_like_english_text(text):
    if not text or len(text.strip()) < 14:
        return False
    padded = f" {text.lower()} "
    markers = (
        " the ", " and ", " with ", " from ", " live ", " concert ",
        " recorded ", " performed ", " tour ", " featuring ", " setlist ",
        " of ", " in ", " at "
    )
    hits = sum(1 for m in markers if m in padded)
    has_hr = any(ch in text for ch in "čćžšđČĆŽŠĐ")
    return hits >= 2 and not has_hr


def translate_concert_description(raw_desc):
    cleaned = re.sub(r"<[^>]+>", " ", str(raw_desc or "")).strip()
    cleaned = re.sub(r"\s+", " ", cleaned)[:900]
    if not cleaned:
        return ""
    if looks_like_english_text(cleaned):
        tr = translate_to_croatian(cleaned)
        return (tr or cleaned).strip()[:1000]
    return cleaned[:1000]


def classify_concert_genre(title, description="", subject="", hint_genre=None):
    blob = f"{title} {subject} {description}".lower()
    for kw in CLASSICAL_JAZZ_LOOKUP:
        if len(kw) >= 4 and re.search(rf"\b{re.escape(kw)}\b", blob):
            return "Klasika i Jazz"
    if hint_genre:
        return hint_genre
    for kw in ROCK_METAL_LOOKUP:
        if len(kw) >= 4 and re.search(rf"\b{re.escape(kw)}\b", blob):
            return "Rock i Metal"
    for kw in POP_LOOKUP:
        if len(kw) >= 4 and re.search(rf"\b{re.escape(kw)}\b", blob):
            return "Pop glazba"
    if any(w in blob for w in ["festival", "band", "live at", "live in", "arena", "stadium"]):
        return "Rock i Metal"
    return "Pop glazba"


def build_concert_state_maps(all_concert_rows):
    best_tier_by_norm = {}
    rows_by_norm = {}
    for row in all_concert_rows:
        if not row.get("is_valid", True):
            continue
        title = row.get("title") or ""
        norm_c = normalize_for_exact_title_compare(title)
        if not norm_c:
            continue
        tier = estimate_concert_quality_tier(
            row.get("archive_id") or "",
            row.get("stream_url") or "",
            title
        )
        rows_by_norm.setdefault(norm_c, []).append(row)
        if tier > best_tier_by_norm.get(norm_c, -1):
            best_tier_by_norm[norm_c] = tier
    return best_tier_by_norm, rows_by_norm


def retire_vhs_concert_rows(norm_c, keep_arch_id, rows_by_norm):
    to_retire = []
    with state_lock:
        for row in rows_by_norm.get(norm_c, []):
            if not row.get("is_valid", True):
                continue
            r_ident = row.get("archive_id") or ""
            if not r_ident or r_ident == keep_arch_id:
                continue
            r_tier = estimate_concert_quality_tier(r_ident, row.get("stream_url") or "", row.get("title") or "")
            if r_tier == 0:
                row["is_valid"] = False
                to_retire.append((row["id"], row.get("title") or "", r_ident))

    for row_id, r_title, r_ident in to_retire:
        supabase_patch_row("catalog_items", row_id, {"is_valid": False})
        print(f"  🗑 Uklonjen stari VHS koncert nakon pronalaska bolje verzije: {r_title} ({r_ident})", flush=True)

    return len(to_retire)


def find_better_concert_on_archive(title, year, exclude_ids, deadline_ts):
    if time.monotonic() > deadline_ts:
        return None
    clean_q = re.sub(r"[^\w\s]", " ", str(title)).strip()
    if len(clean_q) < 4:
        return None

    year_clause = f" AND year:[{year - 1} TO {year + 1}]" if year > 0 else ""
    query = (
        f'title:"{clean_q}"{year_clause} AND mediatype:movies '
        f'AND -collection:vhsconcerts_music AND -subject:trailer AND -subject:interview'
    )
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
    target_norm = normalize_for_exact_title_compare(title)
    candidates = []

    for doc in (data or {}).get("response", {}).get("docs", []):
        if time.monotonic() > deadline_ts:
            break
        ident = doc.get("identifier") or ""
        if not ident or ident in exclude_ids or ident.lower().startswith(("youtube-", "yt-")):
            continue
        raw_t = doc.get("title") or ident
        if isinstance(raw_t, list):
            raw_t = raw_t[0]
        if is_junk_concert(ident, raw_t):
            continue

        cand_clean, cand_y = clean_filename_to_title(str(raw_t))
        cand_norm = normalize_for_exact_title_compare(cand_clean)
        if not cand_norm or cand_norm != target_norm:
            continue
        if year > 0 and cand_y > 0 and abs(cand_y - year) > 1:
            continue

        videos, arch_md = get_archive_item_details(ident, min_size_mb=90, allow_mkv=True)
        if not videos:
            continue
        best = pick_best_video_file(videos)
        if is_foreign_dub_without_english(arch_md, ident, best["filename"], orig_lang="en"):
            continue
        dur = best["duration_mins"]
        if dur > 0 and dur < 20:
            continue

        tier = max(
            best.get("aspect_tier", 1),
            estimate_concert_quality_tier(ident, best["stream_url"], str(raw_t))
        )
        if tier < 1:
            continue

        if verify_stream_url_playable(best["stream_url"]):
            downloads = float(doc.get("downloads") or 0)
            candidates.append((tier, best["size_mb"], downloads, ident, best, dur))
            if tier == 2:
                break

    if not candidates:
        return None
    candidates.sort(key=lambda x: (-x[0], -x[1], -x[2]))
    _, _, _, best_ident, best_file, best_dur = candidates[0]
    return best_ident, best_file, best_dur


def audit_and_upgrade_existing_concerts(all_concert_rows, existing_archive_ids, best_tier_by_norm, rows_by_norm, deadline_ts):
    valid_rows = [r for r in all_concert_rows if r.get("is_valid", True)]
    print(f"1/2 Provjera kvalitete i zamjena VHS ripova za {len(valid_rows)} postojećih koncerata...", flush=True)

    dup_retired = 0
    for norm_c, r_list in list(rows_by_norm.items()):
        if best_tier_by_norm.get(norm_c, 0) > 0 and len(r_list) > 1:
            better_row = next(
                (r for r in r_list if r.get("is_valid", True) and estimate_concert_quality_tier(
                    r.get("archive_id") or "", r.get("stream_url") or "", r.get("title") or ""
                ) > 0),
                None
            )
            if better_row:
                dup_retired += retire_vhs_concert_rows(norm_c, better_row.get("archive_id") or "", rows_by_norm)

    vhs_pool = [
        r for r in valid_rows
        if r.get("is_valid", True)
        and estimate_concert_quality_tier(
            r.get("archive_id") or "", r.get("stream_url") or "", r.get("title") or ""
        ) == 0
    ]
    vhs_candidates = random.sample(vhs_pool, k=min(MAX_VHS_UPGRADE_CHECKS_PER_RUN, len(vhs_pool))) if vhs_pool else []

    upgraded_count = [0]
    if vhs_candidates and time.monotonic() < deadline_ts:
        print(f"  Traženje bolje kopije (umjesto VHS ripa) za {len(vhs_candidates)} koncerata...", flush=True)

        def upgrade_concert_row(row):
            if time.monotonic() > deadline_ts:
                return
            title = row.get("title") or ""
            rel_year = int(row.get("release_year") or 0)
            norm_c = normalize_for_exact_title_compare(title)
            if not norm_c:
                return

            with state_lock:
                if best_tier_by_norm.get(norm_c, 0) > 0:
                    return
                exclude_snapshot = set(existing_archive_ids)

            match = find_better_concert_on_archive(title, rel_year, exclude_snapshot, deadline_ts)
            if not match:
                return

            new_ident, new_file, new_dur = match
            new_tier = max(
                new_file.get("aspect_tier", 1),
                estimate_concert_quality_tier(new_ident, new_file["stream_url"], title)
            )
            with state_lock:
                if new_ident in existing_archive_ids or best_tier_by_norm.get(norm_c, 0) >= new_tier:
                    return
                existing_archive_ids.add(new_ident)
                best_tier_by_norm[norm_c] = new_tier

            new_row = {
                "archive_id": new_ident,
                "title": title,
                "media_type": "concert",
                "genres": row.get("genres") or ["Rock i Metal"],
                "release_year": rel_year,
                "popularity_score": float(row.get("popularity_score") or 500.0) + (600.0 if new_tier == 2 else 300.0),
                "description_hr": row.get("description_hr") or "",
                "poster_url": f"https://archive.org/services/img/{new_ident}",
                "stream_url": new_file["stream_url"],
                "is_featured": bool(row.get("is_featured", False)),
                "is_valid": True,
                "tmdb_runtime": 0,
                "archive_runtime": new_dur
            }
            supabase_upsert("catalog_items", [new_row], "archive_id")
            with state_lock:
                rows_by_norm.setdefault(norm_c, []).append(new_row)
                upgraded_count[0] += 1

            retire_vhs_concert_rows(norm_c, new_ident, rows_by_norm)
            print(f"  + Koncert [zamjena za VHS]: {title} -> {new_ident}", flush=True)

        with ThreadPoolExecutor(max_workers=6) as pool:
            list(pool.map(upgrade_concert_row, vhs_candidates))

    print(
        f"  Provjera koncerata završena: {upgraded_count[0]} zamijenjeno boljom verzijom, "
        f"{dup_retired} starih VHS duplikata uklonjeno.",
        flush=True
    )


def run_concerts_sync():
    start_ts = time.monotonic()
    deadline_ts = start_ts + MAX_CONCERTS_SYNC_SECONDS
    print("=== [KONCERTI] Pokretanje hijerarhijske sinkronizacije koncerata (HD/16:9 + zamjena VHS ripova) ===", flush=True)

    existing_archive_ids, _, _, all_concert_rows = fetch_existing_catalog_state("concert")
    best_tier_by_norm, rows_by_norm = build_concert_state_maps(all_concert_rows)
    print(f"  U bazi već postoji {len(best_tier_by_norm)} jedinstvenih koncerata.", flush=True)

    audit_and_upgrade_existing_concerts(
        all_concert_rows, existing_archive_ids, best_tier_by_norm, rows_by_norm, deadline_ts
    )

    print("2/2 Pretraga novih cjelovitih koncerata iz hijerarhije stabla i korisničkih uploadova...", flush=True)
    candidate_docs = {}

    # Koncertni čvorovi s hijerarhijske karte (archive_movies_layers_2.md)
    collection_queries = [
        ("mediatype:movies AND collection:live_music_video AND -subject:trailer AND -subject:interview AND -subject:news", 4, None),
        ("mediatype:movies AND collection:concerts AND -subject:trailer AND -subject:interview AND -subject:news", 5, None),
        ("mediatype:movies AND collection:vhsconcerts_music AND -subject:trailer AND -subject:interview AND -subject:news", 6, None), # [1.6.13] 3,856
        ("mediatype:movies AND collection:harry-styles-concert-videos AND -subject:trailer", 3, "Pop glazba"), # [1.2.32] 634
        ("mediatype:movies AND collection:moonalicevideo AND -subject:trailer", 2, "Rock i Metal"),           # [1.2.36] 191
        ("mediatype:movies AND collection:goldenrod-festival-archive AND -subject:trailer", 2, "Rock i Metal"), # [1.2.41] 169
        ("mediatype:movies AND collection:quarantine-happy-hour-music AND -subject:trailer", 2, "Pop glazba"), # [1.2.39] 460
        ("mediatype:movies AND collection:12minuteslive AND -subject:trailer", 2, "Rock i Metal"),            # [1.2.29] 113
        ("mediatype:movies AND collection:access-to-bluegrass AND -subject:trailer", 2, "Klasika i Jazz"),    # [1.2.54] 288
        ('mediatype:movies AND collection:artsandmusicvideos AND title:(concert OR live OR tour) AND -subject:trailer', 4, None), # [1.2] 35,984
        ('mediatype:movies AND collection:opensource_movies AND item_size:[350000000 TO 150000000000] AND title:(concert OR "live at" OR "live in" OR wembley OR "world tour") AND title:(1080p OR 720p OR bluray OR dvd) AND -subject:trailer', 3, None),
        ('mediatype:movies AND collection:additional_collections_video AND item_size:[350000000 TO 150000000000] AND title:(concert OR "live at" OR "live in" OR wembley OR "world tour") AND -subject:trailer', 3, None),
    ]

    for q, max_pages, hint in collection_queries:
        if time.monotonic() > deadline_ts:
            break
        pages = list(range(1, max_pages + 1)) + [random.randint(max_pages + 1, max_pages + 5)]
        for page in pages:
            data = safe_get(
                "https://archive.org/advancedsearch.php",
                params={
                    "q": q,
                    "fl[]": ["identifier", "title", "description", "subject", "downloads"],
                    "sort[]": "downloads desc",
                    "rows": 120,
                    "page": page,
                    "output": "json"
                }
            )
            docs = (data or {}).get("response", {}).get("docs", [])
            if not docs:
                break
            for doc in docs:
                ident = doc.get("identifier")
                if ident and ident not in existing_archive_ids and ident not in candidate_docs:
                    candidate_docs[ident] = (doc, hint)

    artist_tasks = []
    seen_seed_names = set()
    for artist in CONCERT_ROCK_METAL_ARTISTS:
        al = artist.strip().lower()
        if al and al not in seen_seed_names:
            seen_seed_names.add(al)
            artist_tasks.append((artist.strip(), "Rock i Metal"))
    for artist in CONCERT_POP_ARTISTS:
        al = artist.strip().lower()
        if al and al not in seen_seed_names:
            seen_seed_names.add(al)
            artist_tasks.append((artist.strip(), "Pop glazba"))
    for artist in CONCERT_CLASSICAL_JAZZ_ARTISTS:
        al = artist.strip().lower()
        if al and al not in seen_seed_names:
            seen_seed_names.add(al)
            artist_tasks.append((artist.strip(), "Klasika i Jazz"))

    sampled_artists = random.sample(artist_tasks, k=min(140, len(artist_tasks)))

    def search_artist_concerts(task):
        if time.monotonic() > deadline_ts:
            return ([], task[1])
        artist, hint_genre = task
        clean_artist = artist.replace('"', "").strip()
        if len(clean_artist) <= 3:
            q = f'mediatype:movies AND title:"{clean_artist}" AND (title:(live OR concert OR tour OR wembley) OR collection:concerts OR collection:vhsconcerts_music OR subject:concert) AND -subject:trailer'
        else:
            q = f'mediatype:movies AND title:"{clean_artist}" AND (title:(live OR concert OR tour OR unplugged OR wembley OR budokan OR arena OR stadium) OR collection:live_music_video OR collection:concerts OR collection:vhsconcerts_music OR subject:concert) AND -subject:trailer AND -subject:interview'
        data = safe_get(
            "https://archive.org/advancedsearch.php",
            params={
                "q": q,
                "fl[]": ["identifier", "title", "description", "subject", "downloads"],
                "sort[]": "downloads desc",
                "rows": 12,
                "output": "json"
            }
        )
        return ((data or {}).get("response", {}).get("docs", []), hint_genre)

    with ThreadPoolExecutor(max_workers=14) as pool:
        for docs, hint_genre in pool.map(search_artist_concerts, sampled_artists):
            for doc in docs:
                ident = doc.get("identifier")
                if ident and ident not in existing_archive_ids and ident not in candidate_docs:
                    candidate_docs[ident] = (doc, hint_genre)

    prioritized_candidates = sorted(
        candidate_docs.values(),
        key=lambda item: (
            -estimate_concert_quality_tier(
                item[0].get("identifier") or "",
                "",
                str(item[0].get("title") or "")
            ),
            -float(item[0].get("downloads") or 0)
        )
    )[:MAX_CONCERT_CANDIDATES_PER_RUN]

    print(f"  Provjera {len(prioritized_candidates)} novih kandidata koncerata (sortirano po kvaliteti)...", flush=True)
    added_counter = [0]

    def process_concert_doc(item):
        if time.monotonic() > deadline_ts:
            return
        doc, hint_genre = item
        ident = doc.get("identifier") or ""
        if not ident or ident.lower().startswith(("youtube-", "yt-")):
            return

        raw_title = doc.get("title") or ident
        if isinstance(raw_title, list):
            raw_title = raw_title[0]

        if is_junk_concert(ident, raw_title):
            return

        desc = doc.get("description") or ""
        if isinstance(desc, list):
            desc = " ".join(str(x) for x in desc)

        subj = doc.get("subject") or ""
        if isinstance(subj, list):
            subj = " ".join(str(x) for x in subj)

        if contains_hentai_or_adult(ident, raw_title, desc) or \
           contains_blocked_regional_or_arabic(ident, raw_title, desc):
            return

        clean_t, parsed_y = clean_filename_to_title(str(raw_title))
        if not clean_t or len(clean_t) < 3:
            return

        norm_c = normalize_for_exact_title_compare(clean_t)
        if not norm_c:
            return

        est_tier = estimate_concert_quality_tier(ident, "", str(raw_title))
        with state_lock:
            if ident in existing_archive_ids:
                return
            current_tier = best_tier_by_norm.get(norm_c, -1)
            if current_tier > 0 or (current_tier == 0 and est_tier == 0):
                return

        videos, arch_md = get_archive_item_details(ident, min_size_mb=65, allow_mkv=True)
        if not videos:
            return
        best = pick_best_video_file(videos)
        if is_foreign_dub_without_english(arch_md, ident, best["filename"], orig_lang="en"):
            return

        dur = best["duration_mins"]
        if dur > 0 and dur < 20:
            return

        final_tier = max(
            est_tier,
            best.get("aspect_tier", 1),
            estimate_concert_quality_tier(ident, best["stream_url"], str(raw_title))
        )

        with state_lock:
            if ident in existing_archive_ids:
                return
            current_tier = best_tier_by_norm.get(norm_c, -1)
            if current_tier > 0 or (current_tier == 0 and final_tier == 0):
                return
            had_only_vhs = (current_tier == 0 and final_tier > 0)
            best_tier_by_norm[norm_c] = final_tier
            existing_archive_ids.add(ident)

        if not verify_stream_url_playable(best["stream_url"]):
            return

        genre_label = classify_concert_genre(clean_t, desc, subj, hint_genre)
        hr_desc = translate_concert_description(desc)
        pop_bonus = 600.0 if final_tier == 2 else (-200.0 if final_tier == 0 else 0.0)
        row = {
            "archive_id": ident,
            "title": clean_t,
            "media_type": "concert",
            "genres": [genre_label],
            "release_year": parsed_y,
            "popularity_score": float(doc.get("downloads") or 0) + pop_bonus,
            "description_hr": hr_desc,
            "poster_url": f"https://archive.org/services/img/{ident}",
            "stream_url": best["stream_url"],
            "is_featured": False,
            "is_valid": True,
            "tmdb_runtime": 0,
            "archive_runtime": dur
        }
        supabase_upsert("catalog_items", [row], "archive_id")

        if had_only_vhs:
            with state_lock:
                rows_by_norm.setdefault(norm_c, []).append(row)
            retire_vhs_concert_rows(norm_c, ident, rows_by_norm)

        with state_lock:
            added_counter[0] += 1
            count_now = added_counter[0]
        if count_now % 25 == 0 or had_only_vhs:
            tag = " [zamjena za VHS]" if had_only_vhs else ""
            print(f"  + Koncert{tag} (#{count_now}): {clean_t} -> {genre_label}", flush=True)

    with ThreadPoolExecutor(max_workers=14) as pool:
        list(pool.map(process_concert_doc, prioritized_candidates))

    elapsed_mins = (time.monotonic() - start_ts) / 60.0
    print(f"=== [KONCERTI] Završeno za {elapsed_mins:.1f} min! Dodano {added_counter[0]} novih koncerata. ===", flush=True)


if __name__ == "__main__":
    run_concerts_sync()
