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


def _velocity_components(payload):
    speed = payload.get("speedMetersPerSecond")
    bearing = payload.get("bearingDegrees")
    speed = float(speed) if type(speed) in (int, float) and math.isfinite(speed) else 0.0
    bearing = float(bearing) if type(bearing) in (int, float) and math.isfinite(bearing) else 0.0
    heading = math.radians(bearing)
    return speed * math.sin(heading), speed * math.cos(heading)


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
    particles = [(first_east + rng.gauss(0, INITIAL_STD_METRES),
                  first_north + rng.gauss(0, INITIAL_STD_METRES))
                 for _ in range(particle_count)]
    weights = [1 / particle_count] * particle_count
    initial_particles = [[round(east, 1), round(north, 1)] for east, north in particles]
    frames = []
    previous_millis = first["timestamp"]["utcEpochMillis"]
    previous_velocity = _velocity_components(first["payload"])
    cumulative_displacements = []
    cumulative_east = cumulative_north = 0.0
    cache_path = Path(cache_path)
    if cache_path.stat().st_size != SIZE * SIZE * 4:
        raise ValueError("DEM cache has unexpected size")
    with cache_path.open("rb") as source, mmap.mmap(source.fileno(), 0, access=mmap.ACCESS_READ) as data:
        for index, (event, true_east, true_north) in enumerate(usable):
            payload = event["payload"]
            millis = event["timestamp"]["utcEpochMillis"]
            dt = max(0.0, min(5.0, (millis - previous_millis) / 1000)) if index else 0.0
            previous_millis = millis
            current_velocity = _velocity_components(payload)
            de = 0.5 * (previous_velocity[0] + current_velocity[0]) * dt
            dn = 0.5 * (previous_velocity[1] + current_velocity[1]) * dt
            previous_velocity = current_velocity
            cumulative_east += de
            cumulative_north += dn
            cumulative_displacements.append((millis, cumulative_east, cumulative_north, true_east, true_north))
            dr_start_east = first_east + cumulative_east
            dr_start_north = first_north + cumulative_north
            target_millis = millis - 30_000
            anchor_index = 0
            for candidate in range(len(cumulative_displacements)):
                if cumulative_displacements[candidate][0] <= target_millis:
                    anchor_index = candidate
                else:
                    break
            anchor_millis, anchor_de, anchor_dn, anchor_east, anchor_north = cumulative_displacements[anchor_index]
            dr30_east = anchor_east + cumulative_east - anchor_de
            dr30_north = anchor_north + cumulative_north - anchor_dn
            process_std = POSITION_RW_STD_METRES_PER_SQRT_SECOND * math.sqrt(dt)
            if index:
                particles = [(east + de + rng.gauss(0, process_std),
                              north + dn + rng.gauss(0, process_std))
                             for east, north in particles]
            observed_ground = height_by_sequence[event["sequence"]] - 0.4
            log_weights = []
            for (east, north), prior_weight in zip(particles, weights):
                terrain = sample_height(data, east, north)
                log_likelihood = -80.0 if terrain is None else -0.5 * (
                    (observed_ground - terrain) / TERRAIN_HEIGHT_STD_METRES) ** 2
                log_weights.append(math.log(max(prior_weight, 1e-300)) + log_likelihood)
            maximum = max(log_weights)
            weights = [math.exp(value - maximum) for value in log_weights]
            total = sum(weights)
            weights = ([1 / particle_count] * particle_count if not math.isfinite(total) or total <= 0
                       else [value / total for value in weights])
            mean_east = sum(p[0] * w for p, w in zip(particles, weights))
            mean_north = sum(p[1] * w for p, w in zip(particles, weights))
            map_index = max(range(particle_count), key=weights.__getitem__)
            map_east, map_north = particles[map_index]
            ess = 1 / sum(weight * weight for weight in weights)
            maximum_weight = max(weights)
            resampled = ess < particle_count * RESAMPLE_ESS_FRACTION
            frames.append({
                "utcEpochMillis": millis, "sequence": event["sequence"],
                "trueEast": round(true_east, 2), "trueNorth": round(true_north, 2),
                "meanEast": round(mean_east, 2), "meanNorth": round(mean_north, 2),
                "mapEast": round(map_east, 2), "mapNorth": round(map_north, 2),
                "mmseErrorMeters": round(math.hypot(mean_east - true_east, mean_north - true_north), 2),
                "mapErrorMeters": round(math.hypot(map_east - true_east, map_north - true_north), 2),
                "drStartEast": round(dr_start_east, 2), "drStartNorth": round(dr_start_north, 2),
                "drStartErrorMeters": round(math.hypot(dr_start_east - true_east, dr_start_north - true_north), 2),
                "dr30East": round(dr30_east, 2), "dr30North": round(dr30_north, 2),
                "dr30ErrorMeters": round(math.hypot(dr30_east - true_east, dr30_north - true_north), 2),
                "dr30HorizonSeconds": round((millis - anchor_millis) / 1000, 2),
                "effectiveParticleCount": round(ess, 1), "resampled": resampled,
                "particles": [[round(east, 1), round(north, 1), round(weight / maximum_weight, 4)]
                              for (east, north), weight in zip(particles, weights)],
            })
            if resampled:
                particles = _systematic_resample(particles, weights, rng)
                weights = [1 / particle_count] * particle_count
    if frames:
        frames[0]["initialParticles"] = initial_particles
    return frames


def parameters():
    return {"particleCount": PARTICLE_COUNT, "initialStdMeters": INITIAL_STD_METRES,
            "positionRandomWalkStdMetersPerSqrtSecond": POSITION_RW_STD_METRES_PER_SQRT_SECOND,
            "terrainHeightStdMeters": TERRAIN_HEIGHT_STD_METRES,
            "resampleEssFraction": RESAMPLE_ESS_FRACTION, "randomSeed": RANDOM_SEED}
