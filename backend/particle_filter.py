"""Deterministic first-step terrain-aided particle filter for offline replay.

State is horizontal SWEREF 99 TM position only. GNSS speed and bearing drive the
motion model; GNSS position initializes and evaluates but never updates weights.
"""
import math
import mmap
import random
from pathlib import Path

from backend.terrain import EAST, NORTH, SIZE, SOUTH, WEST, sample_height, sweref99

PARTICLE_COUNT = 1000
INITIAL_STD_METRES = 75.0
POSITION_RW_STD_METRES_PER_SQRT_SECOND = 1.5
TERRAIN_HEIGHT_STD_METRES = 1.5
RESAMPLE_ESS_FRACTION = 0.5
RANDOM_SEED = 6472500535000


def _systematic_resample(particles, weights, rng):
    count = len(particles)
    start = rng.random() / count
    positions = [start + i / count for i in range(count)]
    cumulative = weights[0]
    source = 0
    result = []
    for position in positions:
        while position > cumulative and source < count - 1:
            source += 1
            cumulative += weights[source]
        result.append(particles[source])
    return result


def run_particle_filter(events, vertical_estimates, cache_path, particle_count=PARTICLE_COUNT):
    gnss = []
    for event in events:
        if event.get("eventType") != "navigation.gnss":
            continue
        payload = event.get("payload", {})
        lat, lon = payload.get("latitudeDegrees"), payload.get("longitudeDegrees")
        if not all(type(value) in (int, float) and math.isfinite(value) for value in (lat, lon)):
            continue
        east, north = sweref99(lat, lon)
        gnss.append((event, east, north))
    height_by_sequence = {item["sequence"]: item["heightMeters"] for item in vertical_estimates}
    usable = [(event, east, north) for event, east, north in gnss
              if WEST <= east < EAST and SOUTH < north <= NORTH and event["sequence"] in height_by_sequence]
    if not usable:
        return []
    rng = random.Random(RANDOM_SEED)
    first, first_east, first_north = usable[0]
    particles = []
    for _ in range(particle_count):
        de, dn = rng.gauss(0, INITIAL_STD_METRES), rng.gauss(0, INITIAL_STD_METRES)
        angle = (math.atan2(dn, de) + math.pi) / (2 * math.pi)
        lineage = min(7, int(angle * 8))
        particles.append((first_east + de, first_north + dn, lineage))
    initial_particles = [[round(east, 1), round(north, 1), lineage]
                         for east, north, lineage in particles]
    frames = []
    previous_millis = first["timestamp"]["utcEpochMillis"]
    cache_path = Path(cache_path)
    if cache_path.stat().st_size != SIZE * SIZE * 4:
        raise ValueError("DEM cache has unexpected size")
    with cache_path.open("rb") as source, mmap.mmap(source.fileno(), 0, access=mmap.ACCESS_READ) as data:
        for index, (event, true_east, true_north) in enumerate(usable):
            payload = event["payload"]
            millis = event["timestamp"]["utcEpochMillis"]
            dt = max(0.0, min(5.0, (millis - previous_millis) / 1000)) if index else 0.0
            previous_millis = millis
            speed = payload.get("speedMetersPerSecond")
            bearing = payload.get("bearingDegrees")
            speed = float(speed) if type(speed) in (int, float) and math.isfinite(speed) else 0.0
            bearing = float(bearing) if type(bearing) in (int, float) and math.isfinite(bearing) else 0.0
            heading = math.radians(bearing)
            de = speed * dt * math.sin(heading)
            dn = speed * dt * math.cos(heading)
            process_std = POSITION_RW_STD_METRES_PER_SQRT_SECOND * math.sqrt(dt)
            if index:
                particles = [(east + de + rng.gauss(0, process_std),
                              north + dn + rng.gauss(0, process_std), lineage)
                             for east, north, lineage in particles]
            observed_ground = height_by_sequence[event["sequence"]] - 0.4
            log_weights = []
            for east, north, _ in particles:
                terrain = sample_height(data, east, north)
                if terrain is None:
                    log_weights.append(-80.0)
                else:
                    residual = observed_ground - terrain
                    log_weights.append(-0.5 * (residual / TERRAIN_HEIGHT_STD_METRES) ** 2)
            maximum = max(log_weights)
            weights = [math.exp(value - maximum) for value in log_weights]
            total = sum(weights)
            if not math.isfinite(total) or total <= 0:
                weights = [1 / particle_count] * particle_count
            else:
                weights = [value / total for value in weights]
            mean_east = sum(p[0] * w for p, w in zip(particles, weights))
            mean_north = sum(p[1] * w for p, w in zip(particles, weights))
            ess = 1 / sum(weight * weight for weight in weights)
            resampled = ess < particle_count * RESAMPLE_ESS_FRACTION
            if resampled:
                particles = _systematic_resample(particles, weights, rng)
            lineage_count = len({particle[2] for particle in particles})
            frames.append({
                "utcEpochMillis": millis,
                "sequence": event["sequence"],
                "trueEast": round(true_east, 2), "trueNorth": round(true_north, 2),
                "meanEast": round(mean_east, 2), "meanNorth": round(mean_north, 2),
                "positionErrorMeters": round(math.hypot(mean_east - true_east, mean_north - true_north), 2),
                "effectiveParticleCount": round(ess, 1), "resampled": resampled,
                "survivingLineageGroups": lineage_count,
                "particles": [[round(east, 1), round(north, 1), lineage]
                              for east, north, lineage in particles],
            })
    if frames:
        frames[0]["initialParticles"] = initial_particles
    return frames


def parameters():
    return {"particleCount": PARTICLE_COUNT, "initialStdMeters": INITIAL_STD_METRES,
            "positionRandomWalkStdMetersPerSqrtSecond": POSITION_RW_STD_METRES_PER_SQRT_SECOND,
            "terrainHeightStdMeters": TERRAIN_HEIGHT_STD_METRES,
            "resampleEssFraction": RESAMPLE_ESS_FRACTION, "randomSeed": RANDOM_SEED}
