import mmap
import struct
import tempfile
import unittest
from pathlib import Path

from backend.terrain import CACHE_NAME, SIZE, sample_height, sweref99


class TerrainTest(unittest.TestCase):
    def test_projection_reaches_dem_centre(self):
        east, north = sweref99(58.380583904641014, 15.61986762111991)
        self.assertAlmostEqual(east, 536250, places=3)
        self.assertAlmostEqual(north, 6471250, places=2)

    def test_pixel_centres_interpolation_and_bounds(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / CACHE_NAME
            with path.open("wb") as file:
                file.truncate(SIZE * SIZE * 4)
                for row, col, height in ((1250, 1250, 10), (1250, 1251, 20),
                                         (1251, 1250, 30), (1251, 1251, 40)):
                    file.seek(4 * (row * SIZE + col))
                    file.write(struct.pack("<f", height))
            with path.open("rb") as file, mmap.mmap(file.fileno(), 0, access=mmap.ACCESS_READ) as data:
                self.assertEqual(sample_height(data, 536250.5, 6471249.5), 10)
                self.assertEqual(sample_height(data, 536251, 6471249), 25)
                self.assertIsNone(sample_height(data, 537500, 6471249))
                self.assertIsNone(sample_height(data, 536251, 6470000))
