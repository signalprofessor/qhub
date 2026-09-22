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

    def test_datum_offset_and_three_sigma_outlier_gates(self):
        events = [
            event(0, 0, "navigation.gnss", {"altitudeMeters": 112.5, "verticalAccuracyMeters": 4.0}),
            event(1, .01, "navigation.pressure", {"pressureHectopascals": pressure_model(80.0) + 2.0}),
            event(2, 1, "navigation.gnss", {"altitudeMeters": 500.0, "verticalAccuracyMeters": 4.0}),
            event(3, 1.01, "navigation.pressure", {"pressureHectopascals": 900.0}),
        ]
        estimates = filter_session(events, gnss_datum_offset_metres=32.5)
        self.assertAlmostEqual(estimates[0]["heightMeters"], 80.0, places=2)
        gnss = next(item for item in estimates if item["measurementType"] == "gnss")
        pressure = next(item for item in estimates if item["measurementType"] == "pressure")
        self.assertFalse(gnss["measurementAccepted"])
        self.assertFalse(pressure["measurementAccepted"])
        self.assertGreater(gnss["normalizedInnovation"], 3)
        self.assertGreater(pressure["normalizedInnovation"], 3)

    def test_requires_both_gnss_altitude_and_pressure(self):
        events = [event(0, 0, "navigation.gnss", {"altitudeMeters": 80.0})]
        self.assertEqual(filter_session(events), [])
