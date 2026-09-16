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
                               [--changes-file PATH]

With --changes-file, a Markdown list of the coverage changes compared to the
previous output is written to PATH (empty if only the KPublicTransport commit
changed). The scheduled update workflow uses it to decide whether to open a PR.
"""

import argparse
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


def describe_changes(old, new):
    """Markdown list of per-backend, per-tier differences between two outputs."""
    lines = []
    old_backends = (old or {}).get("backends", {})
    new_backends = new["backends"]
    for backend_id in sorted(set(old_backends) | set(new_backends)):
        if backend_id not in old_backends:
            lines.append(f"- `{backend_id}`: added")
            continue
        if backend_id not in new_backends:
            lines.append(f"- `{backend_id}`: removed")
            continue
        old_cov = old_backends[backend_id]["coverage"]
        new_cov = new_backends[backend_id]["coverage"]
        for tier in TIERS.values():
            before, after = old_cov.get(tier), new_cov.get(tier)
            if before == after:
                continue
            if before is None or after is None:
                lines.append(f"- `{backend_id}` {tier}: {'added' if before is None else 'removed'}")
                continue
            details = []
            gained = sorted(set(after["regions"]) - set(before["regions"]))
            lost = sorted(set(before["regions"]) - set(after["regions"]))
            if gained:
                details.append("regions +" + ", ".join(gained))
            if lost:
                details.append("regions −" + ", ".join(lost))
            if before["polygons"] != after["polygons"]:
                points = lambda cov: sum(len(p) for p in cov["polygons"])
                details.append(f"area changed ({points(before)} → {points(after)} points)")
            lines.append(f"- `{backend_id}` {tier}: " + "; ".join(details))
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("kpt_root", help="KPublicTransport checkout")
    parser.add_argument("output", nargs="?", default=DEFAULT_OUTPUT)
    parser.add_argument("--changes-file")
    args = parser.parse_args()
    kpt_root = args.kpt_root
    output = args.output
    networks_dir = os.path.join(kpt_root, "src", "lib", "networks")

    previous = None
    if os.path.exists(output):
        with open(output) as f:
            previous = json.load(f)

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

    if args.changes_file:
        changes = describe_changes(previous, result)
        with open(args.changes_file, "w") as f:
            f.write(changes + ("\n" if changes else ""))
        print(changes or "No coverage changes.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
