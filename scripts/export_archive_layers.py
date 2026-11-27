import os
import re
import sys
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sync_common import safe_get

ROOT_ID = "movies"
OUTPUT_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "archive_movies_layers.md")

# Očite grane na Layer 1 koje odmah odbacujemo (vijesti, sport, igrice, predavanja, NASA, religija)
EXCLUDED_ROOT_BRANCHES = {
    "tvarchive",
    "tvnews",
    "chingrimestvnewsarchive",
    "gamevideos",
    "nasa",
    "vlogs",
    "computersandtechvideos",
    "culturalandacademicfilms",
    "theinternetarchive",
    "sports",
    "podcasts-video",
    "education",
    "spiritualityandreligion",
    "archivev2",
    "prelinger",
    "ephemera",
    "musicvideobin",
    "video_blender",
    "broadcast_ready",
}

# Iznimke unutar odbačenih grana koje ipak želimo mapirati (npr. Community Media Archive unutar News & Public Affairs)
FORCE_INCLUDE_SUBNODES = {
    "newsandpublicaffairs": ["community_media"],
    "television": ["televisionmovies", "classic_tv", "childrenstelevision"],
}

NOISE_SUBNODE_RE = re.compile(
    r"(?i)(?:^tv-|cspan|foxnews|msnbc|cnn|bbcnews|aljazeera|news|election|"
    r"twitch|tiktok|vlog|podcast|sermon|church|prayer|university|lecture|course|"
    r"olympic|championship|tournament|sports|commercial|trailer|gameplay|speedrun)"
)


def count_video_items(collection_id):
    """Vraća točan broj stavki (mediatype:movies) unutar zadanog čvora/mape."""
    data = safe_get(
        "https://archive.org/advancedsearch.php",
        params={
            "q": f"collection:{collection_id} AND mediatype:movies",
            "rows": 0,
            "output": "json",
        },
        timeout=12,
    )
    return int((data or {}).get("response", {}).get("numFound") or 0)


def get_direct_subcollections(parent_id, rows=150):
    """Dohvaća neposrednu djecu (mediatype:collection) za zadani čvor."""
    data = safe_get(
        "https://archive.org/advancedsearch.php",
        params={
            "q": f"collection:{parent_id} AND mediatype:collection",
            "fl[]": ["identifier", "title", "collection", "downloads"],
            "sort[]": "downloads desc",
            "rows": rows,
            "page": 1,
            "output": "json",
        },
        timeout=15,
    )
    docs = (data or {}).get("response", {}).get("docs", [])
    results = []
    for doc in docs:
        ident = (doc.get("identifier") or "").strip()
        if not ident or ident.lower() == parent_id.lower():
            continue
        cols = doc.get("collection") or []
        if isinstance(cols, str):
            cols = [cols]
        is_direct = bool(cols and cols[0].lower() == parent_id.lower())
        raw_t = doc.get("title") or ident
        if isinstance(raw_t, list):
            raw_t = raw_t[0]
        results.append({
            "id": ident,
            "title": str(raw_t).strip(),
            "downloads": int(doc.get("downloads") or 0),
            "is_direct": is_direct,
        })
    return results


def fetch_node_metadata(col_id):
    """Dohvaća naziv i broj video zapisa za pojedini čvor."""
    data = safe_get(
        "https://archive.org/advancedsearch.php",
        params={
            "q": f"identifier:{col_id}",
            "fl[]": ["identifier", "title", "downloads"],
            "rows": 1,
            "output": "json",
        },
        timeout=10,
    )
    docs = (data or {}).get("response", {}).get("docs", [])
    title = col_id
    downloads = 0
    if docs:
        raw_t = docs[0].get("title") or col_id
        title = raw_t[0] if isinstance(raw_t, list) else str(raw_t)
        downloads = int(docs[0].get("downloads") or 0)
    return {
        "id": col_id,
        "title": title.strip(),
        "downloads": downloads,
        "is_direct": True,
    }


def build_hierarchical_movie_map():
    print("1/4 Dohvaćanje korijena (Layer 1: /details/movies)...", flush=True)
    root_video_count = count_video_items(ROOT_ID)
    raw_l1 = get_direct_subcollections(ROOT_ID, rows=150)
    l1_all = [x for x in raw_l1 if x["is_direct"]] or raw_l1[:60]

    approved_l1 = []
    excluded_l1 = []
    seen_ids = {ROOT_ID.lower()}

    for node in l1_all:
        low = node["id"].lower()
        if low in seen_ids:
            continue
        seen_ids.add(low)
        if low in EXCLUDED_ROOT_BRANCHES or (low in FORCE_INCLUDE_SUBNODES and low != "television"):
            excluded_l1.append(node)
        else:
            approved_l1.append(node)

    # Dodaj iznimke (npr. Community Media Archive iz News & Public Affairs) u odobreni popis
    for parent_key, child_ids in FORCE_INCLUDE_SUBNODES.items():
        for cid in child_ids:
            if cid.lower() not in seen_ids:
                seen_ids.add(cid.lower())
                approved_l1.append(fetch_node_metadata(cid))

    print(f"2/4 Širenje odobrenih grana u dubinu (Layer 1.x -> 1.x.y -> 1.x.y.z)...", flush=True)

    # Razina 1.x -> 1.x.y (Layer 2)
    l2_by_parent = {}
    with ThreadPoolExecutor(max_workers=12) as pool:
        for parent, children in zip(approved_l1, pool.map(lambda n: get_direct_subcollections(n["id"], rows=100), approved_l1)):
            valid_children = []
            for c in children:
                low = c["id"].lower()
                if not c["is_direct"] or low in seen_ids:
                    continue
                if NOISE_SUBNODE_RE.search(f"{c['id']} {c['title']}"):
                    continue
                seen_ids.add(low)
                valid_children.append(c)
            l2_by_parent[parent["id"]] = valid_children

    all_l2 = [c for sublist in l2_by_parent.values() for c in sublist]

    # Razina 1.x.y -> 1.x.y.z (Layer 3)
    l3_by_parent = {}
    if all_l2:
        with ThreadPoolExecutor(max_workers=12) as pool:
            for parent, children in zip(all_l2, pool.map(lambda n: get_direct_subcollections(n["id"], rows=80), all_l2)):
                valid_children = []
                for c in children:
                    low = c["id"].lower()
                    if not c["is_direct"] or low in seen_ids:
                        continue
                    if NOISE_SUBNODE_RE.search(f"{c['id']} {c['title']}"):
                        continue
                    seen_ids.add(low)
                    valid_children.append(c)
                l3_by_parent[parent["id"]] = valid_children

    all_l3 = [c for sublist in l3_by_parent.values() for c in sublist]

    # Razina 1.x.y.z -> 1.x.y.z.w (Layer 4 za duboke grane)
    l4_by_parent = {}
    if all_l3:
        with ThreadPoolExecutor(max_workers=12) as pool:
            for parent, children in zip(all_l3[:150], pool.map(lambda n: get_direct_subcollections(n["id"], rows=50), all_l3[:150])):
                valid_children = []
                for c in children:
                    low = c["id"].lower()
                    if not c["is_direct"] or low in seen_ids:
                        continue
                    if NOISE_SUBNODE_RE.search(f"{c['id']} {c['title']}"):
                        continue
                    seen_ids.add(low)
                    valid_children.append(c)
                l4_by_parent[parent["id"]] = valid_children

    all_l4 = [c for sublist in l4_by_parent.values() for c in sublist]

    # 3/4 Brojanje video stavki (mediatype:movies) u svakom mapiranom čvoru
    all_nodes = approved_l1 + all_l2 + all_l3 + all_l4
    print(f"3/4 Brojanje video stavki za {len(all_nodes)} mapiranih čvorova...", flush=True)
    video_counts = {}
    with ThreadPoolExecutor(max_workers=16) as pool:
        for node, cnt in zip(all_nodes, pool.map(lambda n: count_video_items(n["id"]), all_nodes)):
            video_counts[node["id"]] = cnt

    # 4/4 Generiranje preglednog hijerarhijskog izvještaja
    lines = [
        "# Hijerarhijska mapa `/details/movies` s brojem video stavki po slojevima",
        "",
        f"- **Layer 1 (Korijen `movies`)**: ukupno `{root_video_count:,}` video stavki",
        f"- **Odobrene grane (Layer 1.x)**: `{len(approved_l1)}` | **Layer 1.x.y**: `{len(all_l2)}` | **Layer 1.x.y.z**: `{len(all_l3)}` | **Layer 4**: `{len(all_l4)}`",
        "",
        "## Odbačene grane na Layer 1 (neće se skenirati)",
    ]
    for ex in excluded_l1:
        lines.append(f"- ~~{ex['title']}~~ (`{ex['id']}`)")
    lines.append("")
    lines.append("---")
    lines.append("")

    for i1, n1 in enumerate(approved_l1, start=1):
        c1 = video_counts.get(n1["id"], 0)
        lines.append(f"## [1.{i1}] {n1['title']} (`{n1['id']}`) — **{c1:,} video stavki**")
        children_l2 = l2_by_parent.get(n1["id"], [])
        if not children_l2:
            lines.append("  - *(ravna mapa — svi video zapisi i multi-file paketi nalaze se izravno u ovom čvoru)*")
        for i2, n2 in enumerate(children_l2, start=1):
            c2 = video_counts.get(n2["id"], 0)
            lines.append(f"  - **[1.{i1}.{i2}] {n2['title']}** (`{n2['id']}`) — **{c2:,} video stavki**")
            for i3, n3 in enumerate(l3_by_parent.get(n2["id"], []), start=1):
                c3 = video_counts.get(n3["id"], 0)
                lines.append(f"    - **[1.{i1}.{i2}.{i3}] {n3['title']}** (`{n3['id']}`) — **{c3:,} video stavki**")
                for i4, n4 in enumerate(l4_by_parent.get(n3["id"], []), start=1):
                    c4 = video_counts.get(n4["id"], 0)
                    lines.append(f"      - **[1.{i1}.{i2}.{i3}.{i4}] {n4['title']}** (`{n4['id']}`) — **{c4:,} video stavki**")
        lines.append("")

    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))

    print(f"4/4 Gotovo! Karta spremljena u: {OUTPUT_FILE}", flush=True)


if __name__ == "__main__":
    build_hierarchical_movie_map()
