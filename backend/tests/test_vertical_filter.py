import math
import unittest

from backend.vertical_filter import filter_session, pressure_model


def event(sequence, seconds, kind, payload):
    return {"sequence": sequence, "eventType": kind, "payload": payload,
            "timestamp": {"monotonicNanos": int(seconds * 1e9),
                          "utcEpochMillis": int(seconds * 1000)}}


class VerticalFilterTest(unittest.TestCase):
    def test_tracks_height_with_pressure_bias_drift_and_no_dem(self):
        events = []
        sequence = 0
        for second in range(121):
            height = 80 + 4 * math.sin(second / 20)
            bias = 2 + second * 2 / 3600
            events.append(event(sequence, second, "navigation.gnss", {
                "altitudeMeters": height + (3 if second % 2 else -3),
                "verticalAccuracyMeters": 20.0}))
            sequence += 1
            events.append(event(sequence, second + 0.01, "navigation.pressure", {
                "pressureHectopascals": pressure_model(height) + bias}))
            sequence += 1
        estimates = filter_session(events)
        self.assertGreater(len(estimates), 200)
        self.assertAlmostEqual(estimates[-1]["heightMeters"], 80 + 4 * math.sin(6), delta=1.5)
        self.assertAlmostEqual(estimates[-1]["pressureBiasHectopascals"], 2 + 120 * 2 / 3600, delta=0.3)
        self.assertNotIn("terrainMeters", estimates[-1])

    def test_requires_both_gnss_altitude_and_pressure(self):
        events = [event(0, 0, "navigation.gnss", {"altitudeMeters": 80.0})]
        self.assertEqual(filter_session(events), [])
