#!/usr/bin/env python3
"""
Imports OpenStreetMap traffic signal nodes into the running EVAS backend
via its own REST API (POST /api/traffic-signals) — no direct DB access,
this exercises the real endpoint. Source data comes from the public
Overpass API (the same data overpass-turbo.eu's UI queries, fetched here
via a plain HTTP request instead of a manual browser export).

Usage:
    python3 scripts/import-osm-traffic-signals.py \
        --south 12.95 --west 77.57 --north 13.00 --east 77.65

Re-running is safe: signals already imported (matched by the OSM node id
embedded in `code`, e.g. "TS-OSM-26529522") hit the unique-code conflict
(409) and are reported as skipped, not duplicated.
"""
import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

OVERPASS_URL = "https://overpass-api.de/api/interpreter"


def fetch_traffic_signals(south, west, north, east):
    query = (
        f'[out:json][timeout:60];'
        f'node["highway"="traffic_signals"]({south},{west},{north},{east});'
        f'out body;'
    )
    data = urllib.parse.urlencode({"data": query}).encode()
    req = urllib.request.Request(
        OVERPASS_URL,
        data=data,
        headers={"User-Agent": "emergency-vehicle-alert-system/1.0 (dev/testing)"},
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.load(resp)["elements"]


def import_signal(api_base, osm_id, lat, lon):
    body = json.dumps({
        "code": f"TS-OSM-{osm_id}",
        "location": {"lat": lat, "lng": lon},
    }).encode()
    req = urllib.request.Request(
        f"{api_base}/api/traffic-signals",
        data=body,
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=10):
            return "created"
    except urllib.error.HTTPError as e:
        if e.code == 409:
            return "skipped"
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--south", type=float)
    parser.add_argument("--west", type=float)
    parser.add_argument("--north", type=float)
    parser.add_argument("--east", type=float)
    parser.add_argument("--from-file", help="Skip Overpass and load a previously-fetched Overpass JSON response instead (Overpass can be flaky/rate-limited).")
    parser.add_argument("--api-base", default="http://localhost:8080")
    args = parser.parse_args()

    if args.from_file:
        print(f"Loading cached Overpass response from {args.from_file}...")
        with open(args.from_file) as f:
            signals = json.load(f)["elements"]
    else:
        if None in (args.south, args.west, args.north, args.east):
            parser.error("--south/--west/--north/--east are required unless --from-file is given")
        print(f"Querying Overpass for traffic signals in ({args.south},{args.west},{args.north},{args.east})...")
        signals = fetch_traffic_signals(args.south, args.west, args.north, args.east)
    print(f"Fetched {len(signals)} traffic signal nodes from OSM.")

    created = skipped = failed = 0
    for el in signals:
        try:
            result = import_signal(args.api_base, el["id"], el["lat"], el["lon"])
            if result == "created":
                created += 1
            else:
                skipped += 1
        except Exception as e:
            print(f"  FAILED for OSM node {el['id']}: {e}", file=sys.stderr)
            failed += 1

    print(f"Done: {created} created, {skipped} skipped (already existed), {failed} failed.")


if __name__ == "__main__":
    main()
