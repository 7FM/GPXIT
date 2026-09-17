#!/usr/bin/env python3
"""
Index of the per-dataset POI files the app downloads (pois-index.json).

  poi_index.py entry --dataset <id> --db <id.db> --gz <id.db.gz>
                     --poly <extract.poly> -o <entry.json>
      Describes one freshly built dataset: download size and checksum,
      build time and POI count, and a simplified outline of its Geofabrik
      extract. The app matches routes against the outlines to offer the
      datasets a route needs.

  poi_index.py merge [--previous <old index.json>] -o <index.json> <entry.json>...
      Combines entries into the index, in poi_datasets.json order. A dataset
      without a new entry (its build job failed) keeps its previous entry,
      so the app still sees the file that is published for it.

The outline uses the polygon format of assets/transit/coverage.json, so the
app reads both with the same code.
"""

import argparse
import hashlib
import json
import os
import sqlite3
import sys
from datetime import datetime, timezone

DATASETS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "poi_datasets.json")

INDEX_VERSION = 1

# Degrees. Simplifying by ~1 km cuts the index to a size the app loads
# quickly; the app only uses the outlines to tell which countries a route
# passes through.
OUTLINE_TOLERANCE = 0.01


def load_datasets():
    with open(DATASETS_FILE, encoding="utf-8") as f:
        return json.load(f)["datasets"]


def read_poly(path):
    """Rings of an Osmosis .poly file as ([(lon, lat)...], is_hole) tuples."""
    rings = []
    with open(path, encoding="utf-8") as f:
        lines = [line.strip() for line in f]
    i = 1  # first line is the file's name
    while i < len(lines) and lines[i] != "END":
        is_hole = lines[i].startswith("!")
        i += 1
        ring = []
        while lines[i] != "END":
            lon, lat = lines[i].split()[:2]
            ring.append((float(lon), float(lat)))
            i += 1
        rings.append((ring, is_hole))
        i += 1
    return rings


def outline(poly_path):
    import shapely
    from shapely.geometry import Polygon

    rings = read_poly(poly_path)
    area = shapely.union_all([Polygon(r) for r, hole in rings if not hole])
    for r, hole in rings:
        if hole:
            area = area.difference(Polygon(r))
    simple = area.simplify(OUTLINE_TOLERANCE)
    polygons = []
    for part in getattr(simple, "geoms", [simple]):
        if part.is_empty:
            continue
        polygons.append([[round(x, 3), round(y, 3)] for x, y in part.exterior.coords])
    return polygons


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def make_entry(args):
    dataset = next((d for d in load_datasets() if d["id"] == args.dataset), None)
    if dataset is None:
        sys.exit(f"{args.dataset} is not in {DATASETS_FILE}")
    conn = sqlite3.connect(f"file:{args.db}?mode=ro", uri=True)
    meta = dict(conn.execute("SELECT key, value FROM meta"))
    pois = conn.execute("SELECT count(*) FROM pois").fetchone()[0]
    conn.close()
    if meta.get("dataset") != args.dataset:
        sys.exit(f"{args.db} was built for {meta.get('dataset')!r}, not {args.dataset!r}")
    entry = {
        "id": dataset["id"],
        "name": dataset["name"],
        "file": os.path.basename(args.gz),
        "size": os.path.getsize(args.gz),
        "db_size": os.path.getsize(args.db),
        "sha256": sha256(args.gz),
        "built_at": meta["built_at"],
        "pois": pois,
        "outline": {"regions": dataset["countries"], "polygons": outline(args.poly)},
    }
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(entry, f, separators=(",", ":"))
    print(f"{entry['id']}: {entry['pois']} POIs, {entry['size']} bytes, "
          f"outline with {sum(len(p) for p in entry['outline']['polygons'])} points")


def merge(args):
    entries = {}
    if args.previous and os.path.exists(args.previous):
        with open(args.previous, encoding="utf-8") as f:
            for entry in json.load(f).get("datasets", []):
                entries[entry["id"]] = entry
    rebuilt = []
    for path in args.entries:
        with open(path, encoding="utf-8") as f:
            entry = json.load(f)
        entries[entry["id"]] = entry
        rebuilt.append(entry["id"])
    datasets = [entries[d["id"]] for d in load_datasets() if d["id"] in entries]
    index = {
        "version": INDEX_VERSION,
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "datasets": datasets,
    }
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(index, f, separators=(",", ":"))
    kept = [d["id"] for d in datasets if d["id"] not in rebuilt]
    print(f"{len(datasets)} datasets, {len(rebuilt)} rebuilt"
          f"{', kept from the previous index: ' + ', '.join(kept) if kept else ''}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("entry", help="describe one built dataset")
    p.add_argument("--dataset", required=True)
    p.add_argument("--db", required=True)
    p.add_argument("--gz", required=True)
    p.add_argument("--poly", required=True)
    p.add_argument("-o", "--output", required=True)
    p.set_defaults(func=make_entry)

    p = sub.add_parser("merge", help="combine entries into the index")
    p.add_argument("--previous")
    p.add_argument("-o", "--output", required=True)
    p.add_argument("entries", nargs="*")
    p.set_defaults(func=merge)

    args = parser.parse_args()
    args.func(args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
