"""Generate a local hillshade from the EastWing 2.5 km GeoTIFF DEM.

Requires Pillow and NumPy for this one-time preparation; the HTTP backend itself
remains standard-library-only. The source DEM and output PNG are not committed.
"""
import argparse
from pathlib import Path

import numpy as np
from PIL import Image

DEFAULT_OUTPUT = Path(__file__).resolve().parent / "data" / "hillshade.png"


def generate(source, output):
    with Image.open(source) as dem:
        if dem.size != (2500, 2500) or dem.mode != "F":
            raise ValueError("expected the 2500 x 2500 floating-point DEM")
        heights = np.asarray(dem, dtype=np.float32)[::2, ::2]
    valid = np.isfinite(heights) & (heights > -1000)
    if not valid.all():
        heights = np.where(valid, heights, np.nanmedian(heights[valid]))
    dy, dx = np.gradient(heights, 2.0)  # sampled pixels are two metres apart
    # Light from northwest. The image is visual aid, never an elevation input.
    nx, ny, nz = -dx, -dy, np.ones_like(dx)
    norm = np.sqrt(nx * nx + ny * ny + nz * nz)
    light = np.array([-0.45, -0.45, 0.77], dtype=np.float32)
    shade = np.clip((nx * light[0] + ny * light[1] + nz * light[2]) / norm, 0, 1)
    low, high = np.percentile(heights[valid], [2, 98])
    relief = np.clip((heights - low) / max(high - low, 1), 0, 1)
    grey = np.clip(45 + 125 * shade + 45 * relief, 0, 255).astype(np.uint8)
    image = np.empty((*grey.shape, 4), dtype=np.uint8)
    image[..., 0] = grey
    image[..., 1] = np.clip(grey.astype(np.int16) + 15, 0, 255)
    image[..., 2] = np.clip(grey.astype(np.int16) + 4, 0, 255)
    image[..., 3] = np.where(valid, 255, 0).astype(np.uint8)
    output.parent.mkdir(parents=True, exist_ok=True)
    Image.fromarray(image, "RGBA").save(output, optimize=True)
    print(f"Saved {output} ({image.shape[1]} x {image.shape[0]} pixels)")


def main():
    parser = argparse.ArgumentParser(description="Create local terrain shading from the EastWing DEM")
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    generate(args.source, args.output)


if __name__ == "__main__":
    main()
