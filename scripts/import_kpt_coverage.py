#!/usr/bin/env python3
"""
Vendor transit backend coverage areas from KPublicTransport.

KPublicTransport (https://invent.kde.org/libraries/kpublictransport) describes
for each backend where it has realtime data, a regular timetable, or at least
some coverage — as ISO 3166 codes plus GeoJSON areas. Those files are CC0, so we
copy the ones for our backends into an app asset. TransitBackendRegistry reads
it to decide which backends to ask for a given place.

Only outer polygon rings are kept, like KPublicTransport itself does.

Usage: import_kpt_coverage.py <kpublictransport checkout> [output json]
"""

import json
import os
import subprocess
import sys

# GPXIT backend id (see TransitBackendRegistry.kt) -> KPublicTransport network
# config in src/lib/networks/. Adding a backend means adding it here and there.
MAPPING = {
    "db": "de_db",
    "dsb": "dk_dsb",
    "se": "se_resrobot",
    "tlem": "gb_traveline",
    "transitous": "un_transitous",
}

TIERS = {
    "realtimeCoverage": "realtime",
    "regularCoverage": "regular",
    "anyCoverage": "any",
}

DEFAULT_OUTPUT = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "app", "src", "main", "assets", "transit", "coverage.json",
))


def outer_rings(geometry):
    kind = geometry.get("type")
    if kind == "Polygon":
        return [geometry["coordinates"][0]]
    if kind == "MultiPolygon":
        return [polygon[0] for polygon in geometry["coordinates"]]
    if kind == "FeatureCollection":
        return [ring for feature in geometry["features"] for ring in outer_rings(feature["geometry"])]
    if kind == "Feature":
        return outer_rings(geometry["geometry"])
    raise ValueError(f"unsupported GeoJSON type {kind}")


def read_tier(networks_dir, tier):
    if "areaFile" in tier:
        with open(os.path.join(networks_dir, "geometry", tier["areaFile"])) as f:
            geometry = json.load(f)
    elif "area" in tier:
        geometry = tier["area"]
    else:
        geometry = None
    polygons = []
    if geometry is not None:
        for ring in outer_rings(geometry):
            polygons.append([[round(lon, 5), round(lat, 5)] for lon, lat in ring])
    return {"regions": sorted(tier.get("region", [])), "polygons": polygons}


def main():
    if len(sys.argv) not in (2, 3):
        print(__doc__, file=sys.stderr)
        return 2
    kpt_root = sys.argv[1]
    output = sys.argv[2] if len(sys.argv) == 3 else DEFAULT_OUTPUT
    networks_dir = os.path.join(kpt_root, "src", "lib", "networks")

    commit = subprocess.run(
        ["git", "-C", kpt_root, "rev-parse", "HEAD"],
        capture_output=True, text=True, check=True,
    ).stdout.strip()

    backends = {}
    for backend_id, network in MAPPING.items():
        with open(os.path.join(networks_dir, network + ".json")) as f:
            config = json.load(f)
        coverage = {}
        for key, tier_name in TIERS.items():
            if key in config.get("coverage", {}):
                coverage[tier_name] = read_tier(networks_dir, config["coverage"][key])
        if not coverage:
            raise ValueError(f"{network} has no coverage")
        backends[backend_id] = {"network": network, "coverage": coverage}

    result = {
        "source": "https://invent.kde.org/libraries/kpublictransport",
        "commit": commit,
        "license": "CC0-1.0",
        "backends": backends,
    }
    os.makedirs(os.path.dirname(output), exist_ok=True)
    with open(output, "w") as f:
        json.dump(result, f, separators=(",", ":"), sort_keys=True)
        f.write("\n")
    print(f"Wrote {output} ({os.path.getsize(output)} bytes) from {commit}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
