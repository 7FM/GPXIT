#!/usr/bin/env python3
"""
Build a compact SQLite POI database from an OSM .pbf extract.

Pipeline:
  1. Run `osmium tags-filter` on the upstream PBF (Geofabrik Germany etc.)
     to keep only objects tagged as one of the POI categories the app
     cares about — plus the nodes referenced by any matched way, so the
     way centroids can still be computed.
  2. Run `osmium export -f geojsonseq` on the filtered PBF to get one
     GeoJSON feature per line (with node locations resolved into actual
     geometries).
  3. Optionally do the same for administrative boundaries carrying an
     ISO 3166 code, exported as polygons (`--regions`).
  4. This script reads those geojsonseq files and writes a SQLite file with
     a `pois` table keyed by a type code (see TYPE_* below), plus a
     (lat, lon) index for bbox queries.

Opening hours are stored verbatim (the app parses and evaluates them).
Evaluating `PH` / `SH` rules needs to know which days are holidays where
the POI is, so each POI is tagged with the holiday region it lies in
(ISO 3166-2 subdivision such as `DE-HE`, or just the country `DE` when
the holidays package knows no subdivisions there), and the holidays of
every such region are precomputed into the `holidays` table. That keeps
all country-specific holiday logic out of the app.

Schema is intentionally simple. No R-tree, so the DB works on any Android
SQLite build. Columns are only ever added, so app versions that predate a
column keep working with newer databases.

Usage: build_poi_db.py <pois.geojsonseq> <output.db> [--regions <boundaries.geojsonseq>]
"""

import argparse
import inspect
import json
import os
import sqlite3
import sys
from datetime import date, datetime, timezone


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
TYPE_BIKE_REPAIR = 4

# holidays.kind values, shared with the app (PoiDatabase.kt).
HOLIDAY_PUBLIC = 0
# A public holiday in only part of the region (e.g. Assumption Day in the
# predominantly catholic municipalities of Bavaria).
HOLIDAY_PUBLIC_PARTIAL = 1
HOLIDAY_SCHOOL = 2

# Holidays are precomputed for this many years around the build date. The
# app treats days outside the covered range as "unknown", so this bounds how
# stale a downloaded dataset may get before PH rules stop being evaluated.
HOLIDAY_YEARS_BEFORE = 1
HOLIDAY_YEARS_AFTER = 3


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
    if amenity == "bicycle_repair_station":
        return TYPE_BIKE_REPAIR
    # A bike shop that also offers repairs — common OSM tagging is
    # shop=bicycle with service:bicycle:repair=yes. We catch both the
    # bare shop=bicycle (most have repair service anyway) and the
    # explicit service tag.
    if shop == "bicycle":
        return TYPE_BIKE_REPAIR
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


def read_geojsonseq(path):
    with open(path, "rb") as f:
        for raw in f:
            # geojsonseq uses U+001E (record separator) before each feature.
            raw = raw.lstrip(b"\x1e").strip()
            if not raw:
                continue
            try:
                yield json.loads(raw)
            except Exception:
                yield None


def clean_text(value):
    if value is None:
        return None
    return value.strip() or None


def read_pois(path):
    """Returns (pois, skipped). Each POI is a dict ready for insertion."""
    pois = []
    skipped = 0
    # Dedup by rounded (type, lat, lon) — catches cases where the same
    # POI is emitted as both a tagged node and a polygon. Using the OSM
    # id would be more precise but `osmium export` omits the id field
    # from its feature objects by default. The copies are not always
    # tagged identically, so merge instead of keeping the first blindly.
    by_key = {}
    for feat in read_geojsonseq(path):
        if feat is None:
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

        name = clean_text(props.get("name"))
        opening_hours = clean_text(props.get("opening_hours"))

        key = (t, round(lat, 5), round(lon, 5))
        existing = by_key.get(key)
        if existing is not None:
            if existing["name"] is None:
                existing["name"] = name
            if existing["opening_hours"] is None:
                existing["opening_hours"] = opening_hours
            continue

        osm_type, osm_id = parse_osm_id(feat.get("id", ""))
        poi = {
            "osm_id": osm_id,
            "osm_type": osm_type,
            "type": t,
            "lat": lat,
            "lon": lon,
            "name": name,
            "opening_hours": opening_hours,
            "region": None,
        }
        by_key[key] = poi
        pois.append(poi)
    return pois, skipped


def holiday_region_code(iso_code, supported):
    """Maps an ISO 3166 code from an OSM boundary to the holidays package.

    Returns (code, is_subdivision) or None when the holidays package knows
    nothing about the country. `supported` is holidays.list_supported_countries().
    """
    country, _, subdiv = iso_code.upper().partition("-")
    if country not in supported:
        return None
    if subdiv and subdiv in supported[country]:
        return f"{country}-{subdiv}", True
    return country, False


def read_regions(path, supported):
    """Reads admin boundaries, returns a list of (holiday_code, rank, geometry).

    Higher rank wins when a POI lies in several regions: a subdivision the
    holidays package knows beats a bare country, and deeper admin levels
    beat shallower ones (e.g. a French département over its région).
    """
    from shapely.geometry import shape

    regions = []
    for feat in read_geojsonseq(path):
        if feat is None:
            continue
        props = feat.get("properties", {}) or {}
        if props.get("boundary") != "administrative":
            continue
        iso = (
            props.get("ISO3166-2")
            or props.get("ISO3166-1:alpha2")
            or props.get("ISO3166-1")
        )
        if not iso:
            continue
        mapped = holiday_region_code(iso, supported)
        if mapped is None:
            continue
        code, is_subdivision = mapped
        try:
            admin_level = int(props.get("admin_level", "0"))
        except ValueError:
            admin_level = 0
        geometry = feat.get("geometry")
        if not geometry or geometry.get("type") not in ("Polygon", "MultiPolygon"):
            continue
        try:
            geom = shape(geometry)
        except Exception as e:
            print(f"Skipping region {iso}: {e}", file=sys.stderr)
            continue
        rank = (1 if is_subdivision else 0, admin_level)
        regions.append((code, rank, geom))
    return regions


def assign_regions(pois, regions):
    import numpy as np
    import shapely

    if not regions or not pois:
        return
    # Index the points and query with each region, not the other way round:
    # shapely prepares the query geometry, which makes the point-in-polygon
    # tests fast. Querying a tree of boundary polygons with points tests each
    # candidate against the raw polygon (hundreds of thousands of vertices
    # for a German state) and is orders of magnitude slower.
    tree = shapely.STRtree(shapely.points(
        np.array([p["lon"] for p in pois]),
        np.array([p["lat"] for p in pois]),
    ))
    best_rank = {}
    for code, rank, geom in regions:
        for pi in tree.query(geom, predicate="intersects").tolist():
            if pi not in best_rank or rank > best_rank[pi]:
                best_rank[pi] = rank
                pois[pi]["region"] = code


def english_or_default(cls):
    languages = getattr(cls, "supported_languages", ())
    for lang in ("en_US", "en_GB", "en"):
        if lang in languages:
            return lang
    return None


def compute_holidays(code, first_year, last_year):
    """Returns (rows, public_range, school_range) for one holiday region.

    rows are (kind, epoch_day, name); ranges are (first_epoch_day,
    last_epoch_day) or None when there is no data for that kind.
    """
    import holidays

    country, _, subdiv = code.partition("-")
    years = range(first_year, last_year + 1)
    cls = type(holidays.country_holidays(country))
    categories = set(getattr(cls, "supported_categories", ()) or ())
    options = {}
    if "include_sundays" in inspect.signature(cls.__init__).parameters:
        # Sweden counts every Sunday as a public holiday by default. In
        # opening hours, PH means the actual holidays.
        options["include_sundays"] = False

    def fetch(category):
        return cls(
            subdiv=subdiv or None,
            years=years,
            categories=(category,),
            language=english_or_default(cls),
            **options,
        )

    rows = []
    public = fetch("public")
    for day, name in public.items():
        rows.append((HOLIDAY_PUBLIC, day.toordinal(), name))
    if "catholic" in categories:
        for day, name in fetch("catholic").items():
            if day not in public:
                rows.append((HOLIDAY_PUBLIC_PARTIAL, day.toordinal(), name))

    public_range = (
        date(first_year, 1, 1).toordinal(),
        date(last_year, 12, 31).toordinal(),
    )

    school_range = None
    if "school" in categories:
        school = fetch("school")
        # School holidays are data, not rules: the package only knows the
        # years the authorities have published. Outside the known holidays
        # we can't tell, so the covered range ends at the last one.
        if school:
            for day, name in school.items():
                rows.append((HOLIDAY_SCHOOL, day.toordinal(), name))
            school_range = (
                date(min(school.keys()).year, 1, 1).toordinal(),
                max(school.keys()).toordinal(),
            )
    return rows, public_range, school_range


# Python ordinals count from 0001-01-01 = 1, the app uses epoch days
# (1970-01-01 = 0), like java.time.LocalDate.toEpochDay().
EPOCH_ORDINAL = date(1970, 1, 1).toordinal()


def to_epoch_day(ordinal):
    return ordinal - EPOCH_ORDINAL


def write_db(output_path, pois, holiday_regions, build_time):
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
            name TEXT,
            opening_hours TEXT,         -- raw OSM opening_hours value
            region_id INTEGER           -- regions.id, NULL if unknown
        );

        -- Holiday regions: an ISO 3166-2 subdivision (DE-HE) or a country (DE).
        -- The *_first_day / *_last_day columns give the epoch-day range the
        -- holidays table covers for that region; NULL means no data at all.
        CREATE TABLE regions (
            id INTEGER PRIMARY KEY,
            code TEXT NOT NULL UNIQUE,
            ph_first_day INTEGER,
            ph_last_day INTEGER,
            sh_first_day INTEGER,
            sh_last_day INTEGER
        );

        CREATE TABLE holidays (
            region_id INTEGER NOT NULL,
            kind INTEGER NOT NULL,      -- see HOLIDAY_* constants
            day INTEGER NOT NULL,       -- epoch day
            name TEXT,
            PRIMARY KEY (region_id, kind, day)
        ) WITHOUT ROWID;

        CREATE TABLE meta (
            key TEXT PRIMARY KEY,
            value TEXT
        );
        """
    )
    cur = conn.cursor()

    region_ids = {}
    for code in sorted(holiday_regions):
        rows, ph_range, sh_range = holiday_regions[code]
        cur.execute(
            "INSERT INTO regions (code, ph_first_day, ph_last_day, sh_first_day, sh_last_day) "
            "VALUES (?, ?, ?, ?, ?)",
            (
                code,
                to_epoch_day(ph_range[0]) if ph_range else None,
                to_epoch_day(ph_range[1]) if ph_range else None,
                to_epoch_day(sh_range[0]) if sh_range else None,
                to_epoch_day(sh_range[1]) if sh_range else None,
            ),
        )
        region_id = cur.lastrowid
        region_ids[code] = region_id
        cur.executemany(
            "INSERT OR IGNORE INTO holidays (region_id, kind, day, name) VALUES (?, ?, ?, ?)",
            [(region_id, kind, to_epoch_day(day), name) for kind, day, name in rows],
        )

    cur.executemany(
        "INSERT INTO pois (osm_id, osm_type, type, lat, lon, name, opening_hours, region_id) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        (
            (
                p["osm_id"], p["osm_type"], p["type"], p["lat"], p["lon"],
                p["name"], p["opening_hours"], region_ids.get(p["region"]),
            )
            for p in pois
        ),
    )
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
    cur.executemany(
        "INSERT INTO meta (key, value) VALUES (?, ?)",
        [
            ("built_at", build_time.strftime("%Y-%m-%dT%H:%M:%SZ")),
            ("schema_version", "2"),
        ],
    )
    conn.commit()
    conn.execute("VACUUM")
    conn.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("pois", help="POI features (osmium export geojsonseq)")
    parser.add_argument("output", help="SQLite file to write")
    parser.add_argument(
        "--regions",
        help="administrative boundaries with ISO 3166 codes (osmium export "
             "geojsonseq, polygons). Without it, no holiday data is written.",
    )
    args = parser.parse_args()

    build_time = datetime.now(timezone.utc)

    pois, skipped = read_pois(args.pois)
    with_hours = sum(1 for p in pois if p["opening_hours"])
    print(f"Read {len(pois)} POIs ({with_hours} with opening hours), "
          f"{skipped} features skipped.")

    holiday_regions = {}
    if args.regions:
        import holidays

        supported = holidays.list_supported_countries()
        regions = read_regions(args.regions, supported)
        print(f"Read {len(regions)} holiday regions.")
        assign_regions(pois, regions)
        located = sum(1 for p in pois if p["region"])
        print(f"Assigned a holiday region to {located} of {len(pois)} POIs.")

        first_year = build_time.year - HOLIDAY_YEARS_BEFORE
        last_year = build_time.year + HOLIDAY_YEARS_AFTER
        for code in sorted({p["region"] for p in pois if p["region"]}):
            holiday_regions[code] = compute_holidays(code, first_year, last_year)
            rows, _, sh_range = holiday_regions[code]
            print(f"  {code}: {len(rows)} holiday rows"
                  f"{'' if sh_range else ' (no school holidays)'}")

    write_db(args.output, pois, holiday_regions, build_time)

    print(f"Done: {len(pois)} POIs inserted.")
    print(f"Output: {args.output} ({os.path.getsize(args.output)} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
