Data cached here comes from [OpenStreetMap](https://www.openstreetmap.org/copyright)
via the [Overpass API](https://overpass-api.de/), © OpenStreetMap
contributors, licensed [ODbL](https://opendatacommons.org/licenses/odbl/).

- `bengaluru-central-traffic-signals.json` — raw Overpass response for
  `node["highway"="traffic_signals"](12.95,77.57,13.00,77.65)` (central
  Bengaluru: MG Road/Indiranagar corridor), fetched 2026-09-17. 405 nodes.
  Cached because the public Overpass instance is occasionally slow/rate-limited
  or returns a 504 — re-fetch with
  `scripts/import-osm-traffic-signals.py --south 12.95 --west 77.57 --north 13.00 --east 77.65`
  (omit `--from-file`) if you want fresher data instead.
