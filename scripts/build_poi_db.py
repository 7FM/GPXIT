#!/usr/bin/env python3
"""
Build a compact SQLite POI database from an OSM .pbf extract.

Pipeline:
  1. Run `osmium tags-filter -R` on the upstream PBF (Geofabrik Germany etc.)
     to keep only objects tagged as one of the four POI categories the app
     cares about — plus the nodes referenced by any matched way, so the
     way centroids can still be computed.
  2. Run `osmium export -f geojsonseq` on the filtered PBF to get one
     GeoJSON feature per line (with node locations resolved into actual
     geometries).
  3. This script reads that geojsonseq and writes a SQLite file with a
     single `pois` table keyed by a type code (0=GROCERY, 1=BAKERY,
     2=WATER, 3=TOILET), plus a (lat, lon) index for bbox queries.

Schema is intentionally minimal — the Android app only needs
{type, lat, lon, name} for rendering and a way to look up all POIs inside
a bbox. No R-tree, so the DB works on any Android SQLite build.

Usage: build_poi_db.py <input.geojsonseq> <output.db>
"""

import json
import os
import sqlite3
import sys


GROCERY_SHOPS = {
    "supermarket", "convenience", "grocery", "general", "kiosk",
    "deli", "greengrocer", "food", "farm", "butcher",
    "organic", "health_food", "frozen_food",
}
BAKERY_SHOPS = {"bakery", "pastry"}

TYPE_GROCERY = 0
TYPE_BAKERY = 1
TYPE_WATER = 2
TYPE_TOILET = 3


def classify(props):
    shop = props.get("shop", "")
    amenity = props.get("amenity", "")
    man_made = props.get("man_made", "")
    drinking = props.get("drinking_water", "")
    if shop in BAKERY_SHOPS:
        return TYPE_BAKERY
    if shop in GROCERY_SHOPS:
        return TYPE_GROCERY
    if (
        amenity in ("drinking_water", "water_point")
        or drinking == "yes"
        or (man_made == "water_tap" and drinking != "no")
    ):
        return TYPE_WATER
    if amenity == "toilets":
        return TYPE_TOILET
    return None


def centroid(geometry):
    t = geometry.get("type")
    coords = geometry.get("coordinates")
    if t == "Point":
        return coords[1], coords[0]  # lat, lon
    if t == "LineString":
        lats = [p[1] for p in coords]
        lons = [p[0] for p in coords]
        return sum(lats) / len(lats), sum(lons) / len(lons)
    if t == "Polygon":
        ring = coords[0]
        lats = [p[1] for p in ring]
        lons = [p[0] for p in ring]
        return sum(lats) / len(lats), sum(lons) / len(lons)
    if t == "MultiPolygon":
        ring = coords[0][0]
        lats = [p[1] for p in ring]
        lons = [p[0] for p in ring]
        return sum(lats) / len(lats), sum(lons) / len(lons)
    return None


def parse_osm_id(id_str):
    """osmium geojsonseq uses 'n123' / 'w456' / 'r789' for the feature id."""
    if not id_str:
        return 0, 0
    prefix, _, rest = id_str.partition("/")
    if not rest:
        prefix, rest = id_str[0], id_str[1:]
    try:
        num = int(rest)
    except ValueError:
        return 0, 0
    if prefix.startswith("n") or prefix == "node":
        return 0, num
    if prefix.startswith("w") or prefix == "way":
        return 1, num
    if prefix.startswith("r") or prefix == "relation":
        return 2, num
    return 0, num


def main():
    if len(sys.argv) != 3:
        print("usage: build_poi_db.py <input.geojsonseq> <output.db>", file=sys.stderr)
        return 2

    input_path = sys.argv[1]
    output_path = sys.argv[2]

    if os.path.exists(output_path):
        os.remove(output_path)

    conn = sqlite3.connect(output_path)
    conn.executescript(
        """
        PRAGMA journal_mode = OFF;
        PRAGMA synchronous = OFF;

        CREATE TABLE pois (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            osm_id INTEGER NOT NULL,
            osm_type INTEGER NOT NULL,  -- 0=node, 1=way, 2=relation
            type INTEGER NOT NULL,      -- see TYPE_* constants
            lat REAL NOT NULL,
            lon REAL NOT NULL,
            name TEXT
        );

        CREATE TABLE meta (
            key TEXT PRIMARY KEY,
            value TEXT
        );
        """
    )
    cur = conn.cursor()

    inserted = 0
    skipped = 0
    seen = set()  # (osm_type, osm_id) to dedupe way/relation double-counts

    with open(input_path, "rb") as f:
        for raw in f:
            # geojsonseq uses U+001E (record separator) before each feature.
            raw = raw.lstrip(b"\x1e").strip()
            if not raw:
                continue
            try:
                feat = json.loads(raw)
            except Exception:
                skipped += 1
                continue

            props = feat.get("properties", {}) or {}
            t = classify(props)
            if t is None:
                skipped += 1
                continue

            geom = feat.get("geometry")
            if not geom:
                skipped += 1
                continue
            c = centroid(geom)
            if c is None:
                skipped += 1
                continue
            lat, lon = c

            osm_type, osm_id = parse_osm_id(feat.get("id", ""))
            key = (osm_type, osm_id)
            if key in seen:
                continue
            seen.add(key)

            name = props.get("name")
            if name is not None:
                name = name.strip() or None

            cur.execute(
                "INSERT INTO pois (osm_id, osm_type, type, lat, lon, name) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                (osm_id, osm_type, t, lat, lon, name),
            )
            inserted += 1
            if inserted % 20000 == 0:
                print(f"Inserted {inserted} POIs...", file=sys.stderr)

    conn.commit()

    # Index after bulk insert (much faster).
    cur.executescript(
        """
        CREATE INDEX idx_pois_spatial ON pois (lat, lon);
        CREATE INDEX idx_pois_type_lat ON pois (type, lat);
        """
    )

    # Stamp the DB with a build timestamp so the app can detect whether
    # what's on disk is newer than the user's copy.
    from datetime import datetime, timezone
    cur.execute(
        "INSERT INTO meta (key, value) VALUES (?, ?)",
        ("built_at", datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")),
    )
    cur.execute(
        "INSERT INTO meta (key, value) VALUES (?, ?)",
        ("schema_version", "1"),
    )
    conn.commit()
    conn.execute("VACUUM")
    conn.close()

    print(f"Done: {inserted} POIs inserted, {skipped} features skipped.")
    print(f"Output: {output_path} ({os.path.getsize(output_path)} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
