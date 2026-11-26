import os
import sys
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sync_common import safe_get

ROOT_ID = "movies"
OUTPUT_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "archive_movies_layers.md")


def get_direct_subcollections(parent_id, rows=120):
    """Dohvaća podkolekcije kojima je parent_id roditelj na Archive.org-u."""
    data = safe_get(
        "https://archive.org/advancedsearch.php",
        params={
            "q": f"collection:{parent_id} AND mediatype:collection",
            "fl[]": ["identifier", "title", "collection", "downloads", "item_size"],
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
        # Neposredno dijete ima parent_id na prvom mjestu liste 'collection' (ili u listi)
        is_direct = bool(cols and cols[0].lower() == parent_id.lower())
        raw_t = doc.get("title") or ident
        if isinstance(raw_t, list):
            raw_t = raw_t[0]
        downloads = int(doc.get("downloads") or 0)
        results.append({
            "id": ident,
            "title": str(raw_t).strip(),
            "downloads": downloads,
            "is_direct": is_direct,
            "parents": cols,
        })
    return results


def build_layer_tree():
    print("Dohvaćanje Layer 1 mapa ispod https://archive.org/details/movies ...", flush=True)
    raw_l1 = get_direct_subcollections(ROOT_ID, rows=150)
    # Razdvoji neposrednu djecu 'movies' (Layer 1) od dubljih
    layer1 = [x for x in raw_l1 if x["is_direct"]]
    if not layer1:
        layer1 = raw_l1[:60]

    print(f"  Pronađeno {len(layer1)} mapa u Layer 1. Dohvaćanje Layer 2...", flush=True)
    l2_map = {}
    with ThreadPoolExecutor(max_workers=10) as pool:
        for parent, children in zip(layer1, pool.map(lambda x: get_direct_subcollections(x["id"], rows=80), layer1)):
            direct_c = [c for c in children if c["is_direct"] and c["id"] != parent["id"]]
            l2_map[parent["id"]] = direct_c

    all_l2 = [c for sublist in l2_map.values() for c in sublist]
    print(f"  Pronađeno {len(all_l2)} mapa u Layer 2. Dohvaćanje Layer 3...", flush=True)
    l3_map = {}
    with ThreadPoolExecutor(max_workers=10) as pool:
        for parent, children in zip(all_l2[:120], pool.map(lambda x: get_direct_subcollections(x["id"], rows=50), all_l2[:120])):
            direct_c = [c for c in children if c["is_direct"] and c["id"] != parent["id"]]
            l3_map[parent["id"]] = direct_c

    lines = [
        "# Archive.org `/details/movies` — Struktura slojeva (Layer 1 -> Layer 2 -> Layer 3)",
        "",
        f"Ukupno Layer 1 grana: **{len(layer1)}** | Layer 2 podmapa: **{len(all_l2)}**",
        "",
    ]

    for idx, l1 in enumerate(layer1, start=1):
        lines.append(f"## {idx}. {l1['title']} (`{l1['id']}`) — pregleda: {l1['downloads']:,}")
        l2_children = l2_map.get(l1["id"], [])
        if not l2_children:
            lines.append("  - *(nema podmapa u Layer 2)*")
        for l2 in l2_children:
            lines.append(f"  - **[L2] {l2['title']}** (`{l2['id']}`) — pregleda: {l2['downloads']:,}")
            l3_children = l3_map.get(l2["id"], [])
            for l3 in l3_children:
                lines.append(f"    - **[L3] {l3['title']}** (`{l3['id']}`) — pregleda: {l3['downloads']:,}")
        lines.append("")

    report = "\n".join(lines)
    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        f.write(report)

    print(f"\nStruktura spremljena u: {OUTPUT_FILE}", flush=True)
    print("\n--- SAŽETAK LAYER 1 ---")
    for idx, l1 in enumerate(layer1, start=1):
        print(f"{idx:02d}. {l1['title']} ({l1['id']}) -> {len(l2_map.get(l1['id'], []))} L2 podmapa | pregleda: {l1['downloads']:,}")


if __name__ == "__main__":
    build_layer_tree()
