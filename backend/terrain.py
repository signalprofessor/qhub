"""Read a local 1 m EastWing DEM cache and sample terrain in EPSG:3006.

The cache is generated once from the source GeoTIFF; no geodata is committed.
"""
import math
import mmap
import struct
from pathlib import Path

WEST, EAST = 535000.0, 537500.0
SOUTH, NORTH = 6470000.0, 6472500.0
SIZE = 2500
CACHE_NAME = "6472500_535000.f32"
# Calibrated from the 2026-09-22 drive; replace with a geoid model when available.
GNSS_DATUM_OFFSET_METRES = 32.5


def sweref99(latitude, longitude):
    """WGS84 degrees to SWEREF 99 TM metres (EPSG:3006)."""
    if not all(math.isfinite(x) for x in (latitude, longitude)) or not (-90 <= latitude <= 90):
        raise ValueError("invalid latitude or longitude")
    a, f, k = 6378137.0, 1 / 298.257223563, 0.9996
    e2 = f * (2 - f)
    ep2 = e2 / (1 - e2)
    phi = math.radians(latitude)
    lam = math.radians(longitude - 15)
    sin, cos, tan = math.sin(phi), math.cos(phi), math.tan(phi)
    n = a / math.sqrt(1 - e2 * sin * sin)
    t, c, A = tan * tan, ep2 * cos * cos, lam * cos
    m = a * ((1 - e2 / 4 - 3 * e2 ** 2 / 64 - 5 * e2 ** 3 / 256) * phi
             - (3 * e2 / 8 + 3 * e2 ** 2 / 32 + 45 * e2 ** 3 / 1024) * math.sin(2 * phi)
             + (15 * e2 ** 2 / 256 + 45 * e2 ** 3 / 1024) * math.sin(4 * phi)
             - 35 * e2 ** 3 / 3072 * math.sin(6 * phi))
    east = 500000 + k * n * (A + (1 - t + c) * A ** 3 / 6
                            + (5 - 18 * t + t * t + 72 * c - 58 * ep2) * A ** 5 / 120)
    north = k * (m + n * tan * (A * A / 2
                               + (5 - t + 9 * c + 4 * c * c) * A ** 4 / 24
                               + (61 - 58 * t + t * t + 600 * c - 330 * ep2) * A ** 6 / 720))
    return east, north


def sample_height(data, east, north):
    """Bilinear pixel-centre sample; None outside footprint or over no-data."""
    if not all(math.isfinite(x) for x in (east, north)):
        return None
    if not (WEST <= east < EAST and SOUTH < north <= NORTH):
        return None
    col = min(SIZE - 1, max(0.0, east - WEST - 0.5))
    row = min(SIZE - 1, max(0.0, NORTH - north - 0.5))
    c0, r0 = math.floor(col), math.floor(row)
    c1, r1 = min(c0 + 1, SIZE - 1), min(r0 + 1, SIZE - 1)
    def pixel(r, c):
        return struct.unpack_from("<f", data, 4 * (r * SIZE + c))[0]
    a, b, c, d = pixel(r0, c0), pixel(r0, c1), pixel(r1, c0), pixel(r1, c1)
    if not all(math.isfinite(x) and x > -1000 for x in (a, b, c, d)):
        return None
    dx, dy = col - c0, row - r0
    return a * (1 - dx) * (1 - dy) + b * dx * (1 - dy) + c * (1 - dx) * dy + d * dx * dy


def sampled_session(events, cache_path):
    """Return DEM heights keyed to GNSS event sequence and timestamp."""
    cache_path = Path(cache_path)
    if cache_path.stat().st_size != SIZE * SIZE * 4:
        raise ValueError("DEM cache has unexpected size")
    heights = []
    with cache_path.open("rb") as source, mmap.mmap(source.fileno(), 0, access=mmap.ACCESS_READ) as data:
        for event in events:
            if event["eventType"] != "navigation.gnss":
                continue
            payload = event["payload"]
            lat, lon = payload.get("latitudeDegrees"), payload.get("longitudeDegrees")
            if type(lat) not in (int, float) or type(lon) not in (int, float):
                continue
            try:
                east, north = sweref99(lat, lon)
            except ValueError:
                continue
            height = sample_height(data, east, north)
            heights.append({"sequence": event["sequence"],
                            "utcEpochMillis": event["timestamp"]["utcEpochMillis"],
                            "terrainMeters": round(height, 3) if height is not None else None})
    return heights
