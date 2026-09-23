import tempfile
import unittest
from pathlib import Path

from backend.particle_filter import run_particle_filter
from backend.gyro_particle_filter import run_gyro_particle_filter
from backend.gyro_accel_particle_filter import run_gyro_accel_particle_filter
from backend.ins_particle_filter import run_ins_particle_filter
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


    def test_gyro_speed_replay_uses_batched_raw_gyro(self):
        events = []
        samples = [[int(i * 1e7), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 3] for i in range(901)]
        events.append({"sequence": 0, "eventType": "navigation.imu_batch",
                       "timestamp": {"monotonicNanos": 0, "utcEpochMillis": 0},
                       "payload": {"sensor": "gyroscope", "samples": samples}})
        for sequence, second in enumerate((6, 7, 8), start=1):
            event = gnss(sequence, second)
            event["payload"].update({"sensorElapsedRealtimeNanos": int(second * 1e9),
                                     "speedMetersPerSecond": 3.0, "bearingDegrees": 0.0,
                                     "bearingAccuracyDegrees": 1.0})
            events.append(event)
        vertical = [{"sequence": i, "heightMeters": 0.8} for i in range(1, 4)]
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory) / CACHE_NAME
            with cache.open("wb") as file:
                file.truncate(SIZE * SIZE * 4)
            first = run_gyro_particle_filter(events, vertical, cache, 0.8, particle_count=100)
            second = run_gyro_particle_filter(events, vertical, cache, 0.8, particle_count=100)
        self.assertEqual(first, second)
        self.assertEqual(len(first), 3)
        self.assertEqual(len(first[0]["particles"]), 100)
        self.assertIn("meanSpeedMetersPerSecond", first[-1])


    def test_ins_velocity_replay_marginalizes_velocity(self):
        samples = [[int(i * 1e7), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 3] for i in range(901)]
        accel = [[int(i * 1e7), 0.0, 0.0, 9.80665, None, None, None, 3] for i in range(901)]
        events = [
            {"sequence": 0, "eventType": "navigation.imu_batch", "timestamp": {"monotonicNanos": 0, "utcEpochMillis": 0}, "payload": {"sensor": "gyroscope", "samples": samples}},
            {"sequence": 1, "eventType": "navigation.imu_batch", "timestamp": {"monotonicNanos": 0, "utcEpochMillis": 0}, "payload": {"sensor": "accelerometer", "samples": accel}},
        ]
        for sequence, second in enumerate((6, 7, 8), start=2):
            event = gnss(sequence, second)
            event["payload"].update({"sensorElapsedRealtimeNanos": int(second * 1e9), "speedMetersPerSecond": 3.0, "bearingDegrees": 0.0, "bearingAccuracyDegrees": 1.0})
            events.append(event)
        vertical = [{"sequence": i, "heightMeters": 0.8} for i in range(2, 5)]
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory) / CACHE_NAME
            with cache.open("wb") as file:
                file.truncate(SIZE * SIZE * 4)
            replay = run_ins_particle_filter(events, vertical, cache, .8, particle_count=100)
        self.assertEqual(len(replay), 3)
        self.assertEqual(len(replay[0]["particles"]), 100)
        self.assertIn("meanSpeedMetersPerSecond", replay[-1])


    def test_zero_crab_replay_uses_gyro_and_longitudinal_acceleration(self):
        gyro = [[int(i * 1e7), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 3] for i in range(901)]
        accel = [[int(i * 1e7), 0.0, 0.0, 9.80665, None, None, None, 3] for i in range(901)]
        events = [
            {"sequence": 0, "eventType": "navigation.imu_batch", "timestamp": {"monotonicNanos": 0, "utcEpochMillis": 0}, "payload": {"sensor": "gyroscope", "samples": gyro}},
            {"sequence": 1, "eventType": "navigation.imu_batch", "timestamp": {"monotonicNanos": 0, "utcEpochMillis": 0}, "payload": {"sensor": "accelerometer", "samples": accel}},
        ]
        for sequence, second in enumerate((6, 7, 8), start=2):
            event = gnss(sequence, second)
            event["payload"].update({"sensorElapsedRealtimeNanos": int(second * 1e9), "speedMetersPerSecond": 3.0, "bearingDegrees": 0.0, "bearingAccuracyDegrees": 1.0})
            events.append(event)
        vertical = [{"sequence": i, "heightMeters": 0.8} for i in range(2, 5)]
        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory) / CACHE_NAME
            with cache.open("wb") as file:
                file.truncate(SIZE * SIZE * 4)
            replay = run_gyro_accel_particle_filter(events, vertical, cache, .8, particle_count=100)
        self.assertEqual(len(replay), 3)
        self.assertEqual(len(replay[0]["particles"]), 100)
        self.assertEqual(replay[0]["gyroHeadingDegrees"], 0.0)
        self.assertIn("meanAccelerationBiasMetersPerSecond2", replay[-1])
        self.assertLessEqual(replay[-1]["minSpeedMetersPerSecond"], replay[-1]["meanSpeedMetersPerSecond"])
        self.assertGreaterEqual(replay[-1]["maxSpeedMetersPerSecond"], replay[-1]["meanSpeedMetersPerSecond"])
