#!/usr/bin/env python3
"""Dev-time generator for the data files under ../data.

Inputs (download into a directory and pass it as the first argument):
  - cities15000.txt, countryInfo.txt, admin1CodesASCII.txt  (https://download.geonames.org/export/dump/)
  - zone.tab                                                (https://github.com/eggert/tz)
  - land50.geojson  = ne_50m_land.geojson                   (https://github.com/nvkelso/natural-earth-vector)

Outputs:
  data/cities.tsv     name, ascii name, country code, admin1 name, lat, lon, population, zone id
  data/countries.tsv  iso2, name, continent, capital, zones (comma separated, zone.tab order)
  data/landmask.txt   180 rows x 360 columns (1 degree cells, row 0 = 89.5N), one hex string per row
"""
import json
import os
import sys

src = sys.argv[1]
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "data")


def path(name):
    return os.path.join(src, name)


# ---------------------------------------------------------------- admin1 names
admin1 = {}
with open(path("admin1CodesASCII.txt"), encoding="utf-8") as f:
    for line in f:
        p = line.rstrip("\n").split("\t")
        admin1[p[0]] = p[2]

# ---------------------------------------------------------------- cities
rows = []
with open(path("cities15000.txt"), encoding="utf-8") as f:
    for line in f:
        p = line.rstrip("\n").split("\t")
        pop = int(p[14] or 0)
        fcode = p[7]
        if not (pop >= 100000 or fcode == "PPLC" or (fcode == "PPLA" and pop >= 15000)):
            continue
        name, ascii_name, cc, a1, tz = p[1], p[2], p[8], p[10], p[17]
        if not tz:
            continue
        region = admin1.get(cc + "." + a1, "")
        rows.append((name, ascii_name if ascii_name != name else "", cc, region,
                     "%.3f" % float(p[4]), "%.3f" % float(p[5]), str(pop), tz))
rows.sort(key=lambda r: -int(r[6]))
with open(os.path.join(out, "cities.tsv"), "w", encoding="utf-8") as f:
    f.write("# GeoNames cities (pop >= 100k, capitals). CC-BY 4.0 https://www.geonames.org/\n")
    for r in rows:
        f.write("\t".join(r) + "\n")
print("cities:", len(rows))

# ---------------------------------------------------------------- countries
zones = {}
with open(path("zone.tab"), encoding="utf-8") as f:
    for line in f:
        if line.startswith("#"):
            continue
        p = line.rstrip("\n").split("\t")
        zones.setdefault(p[0], []).append(p[2])

countries = []
with open(path("countryInfo.txt"), encoding="utf-8") as f:
    for line in f:
        if line.startswith("#"):
            continue
        p = line.rstrip("\n").split("\t")
        iso, name, capital, continent = p[0], p[4], p[5], p[8]
        z = zones.get(iso)
        if not z:
            continue
        countries.append((iso, name, continent, capital, ",".join(z)))
with open(os.path.join(out, "countries.tsv"), "w", encoding="utf-8") as f:
    f.write("# Countries (GeoNames countryInfo, CC-BY 4.0) with IANA zone.tab zones\n")
    for c in countries:
        f.write("\t".join(c) + "\n")
print("countries:", len(countries))

# ---------------------------------------------------------------- land mask
with open(path("land50.geojson"), encoding="utf-8") as f:
    gj = json.load(f)

rings = []  # (minx, miny, maxx, maxy, points)
for feat in gj["features"]:
    g = feat["geometry"]
    polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]
    for poly in polys:
        for ring in poly:  # outer + holes; even-odd rule handles holes
            xs = [pt[0] for pt in ring]
            ys = [pt[1] for pt in ring]
            rings.append((min(xs), min(ys), max(xs), max(ys), ring))


def inside(x, y):
    hit = False
    for minx, miny, maxx, maxy, ring in rings:
        if x < minx or x > maxx or y < miny or y > maxy:
            continue
        n = len(ring)
        j = n - 1
        for i in range(n):
            xi, yi = ring[i]
            xj, yj = ring[j]
            if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
                hit = not hit
            j = i
    return hit


W, H = 360, 180
with open(os.path.join(out, "landmask.txt"), "w") as f:
    f.write("# Natural Earth 1:50m land, rasterised to 1 degree cells (public domain)\n")
    for row in range(H):
        lat = 89.5 - row
        bits = 0
        for col in range(W):
            lon = -179.5 + col
            if inside(lon, lat):
                bits |= 1 << (W - 1 - col)
        f.write("%090x\n" % bits)
print("landmask done")
