import os
import re
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from sync_common import (
    clean_filename_to_title,
    contains_blocked_regional_or_arabic,
    contains_hentai_or_adult,
    fetch_existing_catalog_state,
    get_archive_item_details,
    normalize_for_exact_title_compare,
    pick_best_video_file,
    safe_get,
    state_lock,
    supabase_upsert,
    verify_stream_url_playable,
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


def run_concerts_sync():
    print("=== [KONCERTI] Pokretanje sinkronizacije koncerata (300 Rock/Metal + 300 Pop + Klasika/Jazz) ===", flush=True)
    existing_archive_ids, _, seen_title_years, _ = fetch_existing_catalog_state("concert")
    seen_concert_titles = {t for t, _ in seen_title_years}
    print(f"  U bazi već postoji {len(existing_archive_ids)} potvrđenih koncerata (ostaju netaknuti).", flush=True)

    candidate_docs = {}
    collection_queries = [
        ("mediatype:movies AND collection:vhsconcerts_music AND -subject:trailer AND -subject:interview AND -subject:news", 12, None),
        ("mediatype:movies AND collection:concerts AND -subject:trailer AND -subject:interview AND -subject:news", 6, None),
        ("mediatype:movies AND collection:live_music_video AND -subject:trailer AND -subject:news", 4, None),
    ]

    for q, max_pages, hint in collection_queries:
        for page in range(1, max_pages + 1):
            data = safe_get(
                "https://archive.org/advancedsearch.php",
                params={
                    "q": q,
                    "fl[]": ["identifier", "title", "description", "subject", "downloads"],
                    "sort[]": "downloads desc",
                    "rows": 200,
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

    def search_artist_concerts(task):
        artist, hint_genre = task
        clean_artist = artist.replace('"', "").strip()
        if len(clean_artist) <= 3:
            q = f'mediatype:movies AND title:"{clean_artist}" AND (title:(live OR concert OR tour OR wembley) OR collection:vhsconcerts_music OR subject:concert) AND -subject:trailer'
        else:
            q = f'mediatype:movies AND title:"{clean_artist}" AND (title:(live OR concert OR tour OR unplugged OR wembley OR budokan OR arena OR stadium) OR collection:vhsconcerts_music OR collection:concerts OR subject:concert) AND -subject:trailer AND -subject:interview'
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

    with ThreadPoolExecutor(max_workers=16) as pool:
        for docs, hint_genre in pool.map(search_artist_concerts, artist_tasks):
            for doc in docs:
                ident = doc.get("identifier")
                if ident and ident not in existing_archive_ids and ident not in candidate_docs:
                    candidate_docs[ident] = (doc, hint_genre)

    print(f"  Pronađeno {len(candidate_docs)} novih kandidata koncerata za provjeru...", flush=True)
    added_counter = [0]

    def process_concert_doc(item):
        doc, hint_genre = item
        ident = doc.get("identifier") or ""
        if not ident or ident.lower().startswith(("youtube-", "yt-")):
            return

        raw_title = doc.get("title") or ident
        if isinstance(raw_title, list):
            raw_title = raw_title[0]

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

        with state_lock:
            if norm_c in seen_concert_titles or ident in existing_archive_ids:
                return
            seen_concert_titles.add(norm_c)
            existing_archive_ids.add(ident)

        videos, _ = get_archive_item_details(ident, min_size_mb=65, allow_mkv=True)
        if not videos:
            return
        best = pick_best_video_file(videos)
        dur = best["duration_mins"]
        if dur > 0 and dur < 20:
            return

        if not verify_stream_url_playable(best["stream_url"]):
            return

        genre_label = classify_concert_genre(clean_t, desc, subj, hint_genre)
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
            "archive_runtime": dur
        }
        supabase_upsert("catalog_items", [row], "archive_id")
        with state_lock:
            added_counter[0] += 1
            count_now = added_counter[0]
        if count_now % 50 == 0:
            print(f"  + Spremljeno {count_now} novih koncerata (zadnji: {clean_t} -> {genre_label})", flush=True)

    with ThreadPoolExecutor(max_workers=16) as pool:
        list(pool.map(process_concert_doc, candidate_docs.values()))

    print(f"=== [KONCERTI] Završeno! Dodano {added_counter[0]} novih cjelovitih koncerata. ===", flush=True)


if __name__ == "__main__":
    run_concerts_sync()
