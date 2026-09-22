"""One-time conversion of the EastWing GeoTIFF to a local float32 DEM cache.

Requires Pillow and NumPy. The production HTTP server still uses stdlib only.
"""
import argparse
from pathlib import Path

import numpy as np
from PIL import Image

from backend.terrain import CACHE_NAME, EAST, NORTH, SIZE, WEST

DEFAULT_OUTPUT = Path(__file__).resolve().parent / "data" / CACHE_NAME


def prepare(source, output=DEFAULT_OUTPUT):
    with Image.open(source) as dem:
        if dem.size != (SIZE, SIZE) or dem.mode != "F":
            raise ValueError("expected the 2500 x 2500 float32 DEM")
        scale = dem.tag_v2.get(33550)
        tie = dem.tag_v2.get(33922)
        keys = dem.tag_v2.get(34735)
        if scale is None or tuple(scale[:2]) != (1.0, 1.0):
            raise ValueError("expected 1 m DEM pixel spacing")
        if tie is None or tuple(tie[:2]) != (0.0, 0.0) or tuple(tie[3:5]) != (WEST, NORTH):
            raise ValueError("unexpected DEM origin")
        if keys is None or 3006 not in keys:
            raise ValueError("expected EPSG:3006 DEM")
        heights = np.asarray(dem, dtype=np.float32)
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    np.asarray(heights, dtype="<f4").tofile(output)
    if output.stat().st_size != SIZE * SIZE * 4:
        raise ValueError("DEM cache size mismatch")
    print(f"Prepared {output} ({SIZE} x {SIZE}, float32, EPSG:3006)")


def main():
    parser = argparse.ArgumentParser(description="Prepare the EastWing local DEM height cache")
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    prepare(args.source, args.output)


if __name__ == "__main__":
    main()
