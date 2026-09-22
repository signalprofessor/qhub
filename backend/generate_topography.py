"""Generate local elevation contours from the 2.5 km EastWing GeoTIFF DEM.

Requires Pillow and NumPy for this one-time preparation. The HTTP backend itself
remains standard-library-only. Neither the source DEM nor the PNG is committed.
"""
import argparse
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

DEFAULT_OUTPUT = Path(__file__).resolve().parent / "data" / "contours.png"


def draw_contours(heights, interval=5):
    """Draw transparent 5 m contours, with every 10 m line emphasized."""
    image = Image.new("RGBA", (heights.shape[1], heights.shape[0]), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    finite = np.isfinite(heights) & (heights > -1000)
    if not finite.any():
        return image
    start = int(np.ceil(np.min(heights[finite]) / interval) * interval)
    stop = int(np.floor(np.max(heights[finite]) / interval) * interval)
    tl, tr = heights[:-1, :-1], heights[:-1, 1:]
    bl, br = heights[1:, :-1], heights[1:, 1:]
    cell_valid = finite[:-1, :-1] & finite[:-1, 1:] & finite[1:, :-1] & finite[1:, 1:]
    for level in range(start, stop + 1, interval):
        top = (tl >= level) != (tr >= level)
        right = (tr >= level) != (br >= level)
        bottom = (bl >= level) != (br >= level)
        left = (tl >= level) != (bl >= level)
        rows, cols = np.nonzero(cell_valid & (top | right | bottom | left))
        major = level % 10 == 0
        color = (112, 59, 33, 230) if major else (133, 78, 43, 185)
        for row, col in zip(rows, cols):
            a, b, c, d = map(float, (tl[row, col], tr[row, col], br[row, col], bl[row, col]))
            edges = []
            if top[row, col]:
                edges.append((col + (level - a) / (b - a), row))
            if right[row, col]:
                edges.append((col + 1, row + (level - b) / (c - b)))
            if bottom[row, col]:
                edges.append((col + (level - d) / (c - d), row + 1))
            if left[row, col]:
                edges.append((col, row + (level - a) / (d - a)))
            if len(edges) == 2:
                draw.line(edges, fill=color, width=2 if major else 1)
            elif len(edges) == 4:
                # A saddle point has two branches; keep them separate.
                draw.line(edges[:2], fill=color, width=2 if major else 1)
                draw.line(edges[2:], fill=color, width=2 if major else 1)
    return image


def generate(source, output):
    with Image.open(source) as dem:
        if dem.size != (2500, 2500) or dem.mode != "F":
            raise ValueError("expected the 2500 x 2500 floating-point DEM")
        heights = np.asarray(dem, dtype=np.float32)[::2, ::2]
    image = draw_contours(heights)
    output.parent.mkdir(parents=True, exist_ok=True)
    image.save(output, optimize=True)
    print(f"Saved {output} ({image.width} x {image.height} pixels; 5 m contours)")


def main():
    parser = argparse.ArgumentParser(description="Create local 5 m elevation contours from the EastWing DEM")
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    generate(args.source, args.output)


if __name__ == "__main__":
    main()
