"""Experimental three-state vertical EKF for offline mission analysis.

State: phone altitude h [m], vertical velocity dh/dt [m/s], pressure bias b [hPa].
The DEM is intentionally not used by this filter.
"""
import math

SEA_LEVEL_PRESSURE_HPA = 1013.25
SCALE_HEIGHT_METRES = 8434.5
GNSS_FALLBACK_STD_METRES = 20.0
PRESSURE_STD_HPA = 0.05
ACCELERATION_STD_METRES_PER_SECOND2 = 0.25
BIAS_RW_STD_HPA_PER_SQRT_HOUR = 10.0


def pressure_model(height):
    return SEA_LEVEL_PRESSURE_HPA * math.exp(-height / SCALE_HEIGHT_METRES)


def _scalar_update(x, p, residual, h, variance):
    ph = [sum(p[i][j] * h[j] for j in range(3)) for i in range(3)]
    innovation_variance = sum(h[i] * ph[i] for i in range(3)) + variance
    if not math.isfinite(innovation_variance) or innovation_variance <= 0:
        return x, p
    k = [value / innovation_variance for value in ph]
    x = [x[i] + k[i] * residual for i in range(3)]
    # Joseph covariance update maintains symmetry/positive semidefiniteness.
    ikh = [[(1.0 if i == j else 0.0) - k[i] * h[j] for j in range(3)] for i in range(3)]
    left = [[sum(ikh[i][m] * p[m][j] for m in range(3)) for j in range(3)] for i in range(3)]
    updated = [[sum(left[i][m] * ikh[j][m] for m in range(3)) + k[i] * variance * k[j]
                for j in range(3)] for i in range(3)]
    return x, updated


def _predict(x, p, dt):
    x = [x[0] + dt * x[1], x[1], x[2]]
    f = [[1.0, dt, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]]
    fp = [[sum(f[i][m] * p[m][j] for m in range(3)) for j in range(3)] for i in range(3)]
    p = [[sum(fp[i][m] * f[j][m] for m in range(3)) for j in range(3)] for i in range(3)]
    qa = ACCELERATION_STD_METRES_PER_SECOND2 ** 2
    p[0][0] += qa * dt ** 3 / 3
    p[0][1] += qa * dt ** 2 / 2
    p[1][0] += qa * dt ** 2 / 2
    p[1][1] += qa * dt
    p[2][2] += BIAS_RW_STD_HPA_PER_SQRT_HOUR ** 2 * dt / 3600
    return x, p


def filter_session(events):
    """Return causal EKF estimates after each usable GNSS or pressure event."""
    latest_height = None
    latest_pressure = None
    x = p = None
    previous_nanos = None
    estimates = []
    for event in events:
        kind = event.get("eventType")
        payload = event.get("payload", {})
        if kind == "navigation.gnss":
            value = payload.get("altitudeMeters")
            if type(value) in (int, float) and math.isfinite(value):
                latest_height = float(value)
        elif kind == "navigation.pressure":
            value = payload.get("pressureHectopascals")
            if type(value) in (int, float) and math.isfinite(value) and value > 0:
                latest_pressure = float(value)
        else:
            continue
        nanos = event["timestamp"]["monotonicNanos"]
        if x is None:
            if latest_height is None or latest_pressure is None:
                continue
            x = [latest_height, 0.0, latest_pressure - pressure_model(latest_height)]
            p = [[400.0, 0.0, 0.0], [0.0, 4.0, 0.0], [0.0, 0.0, 9.0]]
            previous_nanos = nanos
        else:
            dt = max(0.0, min(10.0, (nanos - previous_nanos) / 1_000_000_000))
            x, p = _predict(x, p, dt)
            previous_nanos = nanos
            if kind == "navigation.gnss" and latest_height is not None:
                accuracy = payload.get("verticalAccuracyMeters")
                sigma = float(accuracy) if type(accuracy) in (int, float) and math.isfinite(accuracy) else GNSS_FALLBACK_STD_METRES
                sigma = min(100.0, max(3.0, sigma))
                x, p = _scalar_update(x, p, latest_height - x[0], [1.0, 0.0, 0.0], sigma ** 2)
            elif kind == "navigation.pressure" and latest_pressure is not None:
                expected = pressure_model(x[0])
                jacobian = [-expected / SCALE_HEIGHT_METRES, 0.0, 1.0]
                x, p = _scalar_update(x, p, latest_pressure - (expected + x[2]), jacobian, PRESSURE_STD_HPA ** 2)
        estimates.append({"sequence": event["sequence"],
                          "utcEpochMillis": event["timestamp"]["utcEpochMillis"],
                          "heightMeters": round(x[0], 4),
                          "verticalSpeedMetersPerSecond": round(x[1], 4),
                          "pressureBiasHectopascals": round(x[2], 5),
                          "heightStdMeters": round(math.sqrt(max(0.0, p[0][0])), 4)})
    return estimates


def parameters():
    return {"gnssFallbackStdMeters": GNSS_FALLBACK_STD_METRES,
            "pressureStdHectopascals": PRESSURE_STD_HPA,
            "accelerationStdMetersPerSecond2": ACCELERATION_STD_METRES_PER_SECOND2,
            "biasRandomWalkStdHectopascalsPerSqrtHour": BIAS_RW_STD_HPA_PER_SQRT_HOUR,
            "seaLevelPressureHectopascals": SEA_LEVEL_PRESSURE_HPA,
            "scaleHeightMeters": SCALE_HEIGHT_METRES}
