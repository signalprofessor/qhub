"""Terrain PF for a simulated GNSS outage using only initial GNSS state thereafter."""
import bisect
import math
import mmap
import random
from pathlib import Path

from backend.particle_filter import _systematic_resample
from backend.terrain import EAST, NORTH, SIZE, SOUTH, WEST, sample_height, sweref99

PARTICLE_COUNT = 1000
INITIAL_POSITION_STD_METRES = 3.0
INITIAL_SPEED_STD_METRES_PER_SECOND = 1.0
SPEED_RW_STD_METRES_PER_SECOND_PER_SQRT_SECOND = 0.7
TERRAIN_HEIGHT_STD_METRES = 1.5
RESAMPLE_ESS_FRACTION = 0.5
GYRO_BIAS_CALIBRATION_SECONDS = 5.0
RANDOM_SEED = 6472500535001


def _mul(a, b):
    w, x, y, z = a; W, X, Y, Z = b
    return (w*W-x*X-y*Y-z*Z, w*X+x*W+y*Z-z*Y,
            w*Y-x*Z+y*W+z*X, w*Z+x*Y-y*X+z*W)


def _increment(omega, dt):
    norm = math.sqrt(sum(value * value for value in omega))
    angle = norm * dt
    if angle < 1e-14:
        return (1.0, 0.0, 0.0, 0.0)
    scale = math.sin(angle / 2) / norm
    return (math.cos(angle / 2), *(value * scale for value in omega))


def _yaw(quaternion):
    w, x, y, z = quaternion
    return math.degrees(math.atan2(2 * (w*z + x*y), 1 - 2 * (y*y + z*z)))


def _wrap(degrees):
    return (degrees + 180) % 360 - 180


def _gyro_trace(events):
    samples = []
    for event in events:
        payload = event.get("payload", {})
        if event.get("eventType") == "navigation.imu_batch" and payload.get("sensor") == "gyroscope":
            samples.extend(payload.get("samples", []))
    samples.sort(key=lambda item: item[0])
    if len(samples) < 2:
        return [], []
    end = samples[0][0] + GYRO_BIAS_CALIBRATION_SECONDS * 1e9
    calibration = [sample for sample in samples if sample[0] <= end]
    residual_bias = [sum(sample[i] - (sample[i+3] or 0.0) for sample in calibration) / len(calibration)
                     for i in (1, 2, 3)]
    quaternion = (1.0, 0.0, 0.0, 0.0)
    times, yaw_unwrapped = [], []
    previous_yaw = 0.0
    unwrapped = 0.0
    for first, second in zip(samples, samples[1:]):
        dt = max(0.0, min(0.1, (second[0] - first[0]) / 1e9))
        a = [first[i] - (first[i+3] or 0.0) - residual_bias[i-1] for i in (1, 2, 3)]
        b = [second[i] - (second[i+3] or 0.0) - residual_bias[i-1] for i in (1, 2, 3)]
        omega = [(x + y) / 2 for x, y in zip(a, b)]
        quaternion = _mul(quaternion, _increment(omega, dt))
        norm = math.sqrt(sum(value * value for value in quaternion))
        quaternion = tuple(value / norm for value in quaternion)
        current_yaw = _yaw(quaternion)
        unwrapped += _wrap(current_yaw - previous_yaw)
        previous_yaw = current_yaw
        times.append(second[0]); yaw_unwrapped.append(unwrapped)
    return times, yaw_unwrapped


def _interpolate(times, values, target):
    index = bisect.bisect_left(times, target)
    if index <= 0: return values[0]
    if index >= len(times): return values[-1]
    fraction = (target - times[index-1]) / (times[index] - times[index-1])
    return values[index-1] + fraction * (values[index] - values[index-1])


def run_gyro_particle_filter(events, vertical_estimates, cache_path, ground_clearance_metres=0.8,
                             particle_count=PARTICLE_COUNT):
    height_by_sequence = {item["sequence"]: item["heightMeters"] for item in vertical_estimates}
    times, yaw = _gyro_trace(events)
    if not times:
        return []
    usable = []
    for event in events:
        if event.get("eventType") != "navigation.gnss" or event["sequence"] not in height_by_sequence:
            continue
        payload = event.get("payload", {})
        values = (payload.get("latitudeDegrees"), payload.get("longitudeDegrees"),
                  payload.get("sensorElapsedRealtimeNanos"))
        if not all(type(value) in (int, float) and math.isfinite(value) for value in values): continue
        east, north = sweref99(values[0], values[1])
        if WEST <= east < EAST and SOUTH < north <= NORTH:
            usable.append((event, east, north))
    start_index = next((i for i, (event, _, _) in enumerate(usable)
                        if event["payload"].get("speedMetersPerSecond", 0) >= 3
                        and event["payload"].get("bearingAccuracyDegrees", 999) <= 20), None)
    if start_index is None:
        return []
    usable = usable[start_index:]
    first, first_east, first_north = usable[0]
    first_payload = first["payload"]
    first_time = first_payload["sensorElapsedRealtimeNanos"]
    first_yaw = _interpolate(times, yaw, first_time)
    initial_heading = float(first_payload["bearingDegrees"])
    initial_speed = float(first_payload["speedMetersPerSecond"])
    rng = random.Random(RANDOM_SEED)
    particles = [(first_east + rng.gauss(0, INITIAL_POSITION_STD_METRES),
                  first_north + rng.gauss(0, INITIAL_POSITION_STD_METRES),
                  max(0.0, initial_speed + rng.gauss(0, INITIAL_SPEED_STD_METRES_PER_SECOND)))
                 for _ in range(particle_count)]
    weights = [1 / particle_count] * particle_count
    initial_particles = [[round(e, 1), round(n, 1)] for e, n, _ in particles]
    frames, cumulative = [], []
    previous_millis = first["timestamp"]["utcEpochMillis"]
    previous_heading = math.radians(initial_heading)
    cumulative_east = cumulative_north = 0.0
    cache_path = Path(cache_path)
    with cache_path.open("rb") as source, mmap.mmap(source.fileno(), 0, access=mmap.ACCESS_READ) as data:
        for index, (event, true_east, true_north) in enumerate(usable):
            payload = event["payload"]
            millis = event["timestamp"]["utcEpochMillis"]
            dt = max(0.0, min(5.0, (millis - previous_millis) / 1000)) if index else 0.0
            previous_millis = millis
            relative_yaw = _interpolate(times, yaw, payload["sensorElapsedRealtimeNanos"]) - first_yaw
            heading = math.radians(initial_heading - relative_yaw)
            de0 = .5 * initial_speed * (math.sin(previous_heading) + math.sin(heading)) * dt
            dn0 = .5 * initial_speed * (math.cos(previous_heading) + math.cos(heading)) * dt
            cumulative_east += de0; cumulative_north += dn0
            cumulative.append((millis, cumulative_east, cumulative_north, true_east, true_north))
            dr_east, dr_north = first_east + cumulative_east, first_north + cumulative_north
            target = millis - 30_000; anchor = 0
            for candidate, item in enumerate(cumulative):
                if item[0] <= target: anchor = candidate
                else: break
            anchor_millis, anchor_de, anchor_dn, anchor_e, anchor_n = cumulative[anchor]
            dr30_east, dr30_north = anchor_e + cumulative_east-anchor_de, anchor_n + cumulative_north-anchor_dn
            if index:
                speed_std = SPEED_RW_STD_METRES_PER_SECOND_PER_SQRT_SECOND * math.sqrt(dt)
                updated = []
                for east, north, speed in particles:
                    new_speed = max(0.0, speed + rng.gauss(0, speed_std))
                    de = .5 * (speed*math.sin(previous_heading) + new_speed*math.sin(heading)) * dt
                    dn = .5 * (speed*math.cos(previous_heading) + new_speed*math.cos(heading)) * dt
                    updated.append((east+de, north+dn, new_speed))
                particles = updated
            previous_heading = heading
            observed_ground = height_by_sequence[event["sequence"]] - ground_clearance_metres
            logs = []
            for (east, north, _), prior in zip(particles, weights):
                terrain = sample_height(data, east, north)
                likelihood = -80.0 if terrain is None else -.5*((observed_ground-terrain)/TERRAIN_HEIGHT_STD_METRES)**2
                logs.append(math.log(max(prior, 1e-300)) + likelihood)
            maximum = max(logs); weights = [math.exp(value-maximum) for value in logs]; total = sum(weights)
            weights = [value/total for value in weights] if total > 0 else [1/particle_count]*particle_count
            mean_e = sum(p[0]*w for p,w in zip(particles, weights)); mean_n = sum(p[1]*w for p,w in zip(particles, weights)); mean_s = sum(p[2]*w for p,w in zip(particles, weights))
            map_index = max(range(particle_count), key=weights.__getitem__); map_e, map_n, _ = particles[map_index]
            ess = 1/sum(w*w for w in weights); max_weight=max(weights); resampled=ess<particle_count*RESAMPLE_ESS_FRACTION
            frames.append({"utcEpochMillis":millis,"sequence":event["sequence"],"trueEast":round(true_east,2),"trueNorth":round(true_north,2),"meanEast":round(mean_e,2),"meanNorth":round(mean_n,2),"mapEast":round(map_e,2),"mapNorth":round(map_n,2),"meanSpeedMetersPerSecond":round(mean_s,2),"gyroHeadingDegrees":round(math.degrees(heading)%360,2),"mmseErrorMeters":round(math.hypot(mean_e-true_east,mean_n-true_north),2),"mapErrorMeters":round(math.hypot(map_e-true_east,map_n-true_north),2),"drStartEast":round(dr_east,2),"drStartNorth":round(dr_north,2),"drStartErrorMeters":round(math.hypot(dr_east-true_east,dr_north-true_north),2),"dr30East":round(dr30_east,2),"dr30North":round(dr30_north,2),"dr30ErrorMeters":round(math.hypot(dr30_east-true_east,dr30_north-true_north),2),"dr30HorizonSeconds":round((millis-anchor_millis)/1000,2),"effectiveParticleCount":round(ess,1),"resampled":resampled,"particles":[[round(e,1),round(n,1),round(w/max_weight,4),round(s,2)] for (e,n,s),w in zip(particles,weights)]})
            if resampled:
                particles = _systematic_resample(particles, weights, rng); weights=[1/particle_count]*particle_count
    if frames: frames[0]["initialParticles"] = initial_particles
    return frames


def parameters():
    return {"particleCount":PARTICLE_COUNT,"initialPositionStdMeters":INITIAL_POSITION_STD_METRES,
            "initialSpeedStdMetersPerSecond":INITIAL_SPEED_STD_METRES_PER_SECOND,
            "speedRandomWalkStdMetersPerSecondPerSqrtSecond":SPEED_RW_STD_METRES_PER_SECOND_PER_SQRT_SECOND,
            "terrainHeightStdMeters":TERRAIN_HEIGHT_STD_METRES,"gyroBiasCalibrationSeconds":GYRO_BIAS_CALIBRATION_SECONDS,
            "resampleEssFraction":RESAMPLE_ESS_FRACTION,"randomSeed":RANDOM_SEED}
