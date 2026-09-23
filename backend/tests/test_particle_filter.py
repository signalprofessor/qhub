import tempfile
import unittest
from pathlib import Path

from backend.particle_filter import run_particle_filter
from backend.terrain import CACHE_NAME, SIZE


def gnss(sequence, second):
    return {"sequence": sequence, "eventType": "navigation.gnss",
            "timestamp": {"monotonicNanos": int(second * 1e9), "utcEpochMillis": int(second * 1000)},
            "payload": {"latitudeDegrees": 58.380583904641014,
                        "longitudeDegrees": 15.61986762111991,
                        "speedMetersPerSecond": 0.0, "bearingDegrees": 0.0}}


class ParticleFilterTest(unittest.TestCase):
    def test_replay_is_deterministic_and_preserves_all_particles(self):
        events = [gnss(0, 0), gnss(1, 1), gnss(2, 2)]
        vertical = [{"sequence": i, "heightMeters": 0.4} for i in range(3)]
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory) / CACHE_NAME
            with cache.open("wb") as file:
                file.truncate(SIZE * SIZE * 4)  # sparse, valid flat 0 m DEM
            first = run_particle_filter(events, vertical, cache, particle_count=100)
            second = run_particle_filter(events, vertical, cache, particle_count=100)
        self.assertEqual(first, second)
        self.assertEqual(len(first), 3)
        self.assertEqual(len(first[0]["initialParticles"]), 100)
        self.assertTrue(all(len(frame["particles"]) == 100 for frame in first))
        self.assertTrue(all(frame["effectiveParticleCount"] == 100 for frame in first))
        self.assertTrue(all(not frame["resampled"] for frame in first))
        self.assertTrue(all(frame["drStartErrorMeters"] == 0 for frame in first))
        self.assertTrue(all(frame["dr30ErrorMeters"] == 0 for frame in first))
        self.assertTrue(all("mapErrorMeters" in frame and "mmseErrorMeters" in frame for frame in first))
