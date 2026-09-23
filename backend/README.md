# EastWing Qhub backend: first milestone

This is a small HTTP receiver for Qhub event envelopes. It accepts the Android app's existing NDJSON format and stores each event in a SQLite database file. A database is simply the server's durable, queryable record; SQLite needs no separate database account or daemon.

## Run locally

Requires Python 3.10+; no third-party packages.

1. From the repository root, create a secret token: `python3 -c 'import secrets; print(secrets.token_urlsafe(32))'`.
2. Set `QHUB_API_TOKEN` to that value and `QHUB_DB_PATH` to a local database path outside the repository if desired.
3. Run `python3 -m backend.server`. It listens only on `127.0.0.1:8000` by default.
4. Check `http://127.0.0.1:8000/health` in a browser.

Upload an exported mission file from a terminal (replace the token and file path locally):

```sh
curl -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/x-ndjson" \
  --data-binary @/path/to/mission.ndjson \
  http://127.0.0.1:8000/v1/events
```

The app splits completed mission logs into batches of at most 500 events and 900 kB; its aggregate receipt reports `received`, `inserted`, `duplicates`, and `batches`. The backend receipt for each batch reports `received`, `inserted`, and `duplicates`. Repeating the same upload is safe: existing events are counted as duplicates. Query `GET /v1/sessions` with the same Authorization header to see session counts; `GET /health` is public and contains no mission data.

## Pixel-to-Mac development test

This test is local; it does not contact the public subdomain. Install the latest debug APK on the Pixel and connect it by USB with USB debugging enabled.

1. In a Mac terminal, run `python3 -m backend.dev_server` from the repository root. Leave it open and note the fresh token shown there.
2. In another Mac terminal, run `/Users/fregu23/Library/Android/sdk/platform-tools/adb devices` and confirm the Pixel is listed as `device`. Then run `/Users/fregu23/Library/Android/sdk/platform-tools/adb reverse tcp:8000 tcp:8000`.
3. In the app, stop a mission, enter the displayed token, and tap **Send last mission to Mac**. The receipt should report the number of inserted events. Sending the same mission again should report them as duplicates.
4. On the Mac, stop the server with Ctrl+C. Data remains in `backend/data/local.sqlite3`, which is ignored by Git.

The USB tunnel lets the Pixel's `127.0.0.1:8000` reach the Mac's local server. Only the debug APK permits cleartext traffic to this loopback address; a public deployment must use HTTPS. No token is saved in the Android app.

## Opt-in live telemetry trial (0.6.0 or newer debug APK)

With the Pixel attached by USB, start `python3 -m backend.dev_server --port 8001` on the Mac, then run `adb reverse tcp:8000 tcp:8001` using Android SDK platform-tools. Enter the new token in the app. Tap **Turn live telemetry ON**, then **Start mission**. As GNSS events arrive, the live status shows sent and failed counts. Stop the mission and turn live telemetry off.

Live sending is best-effort and works only while the app is in the foreground. It does not retry failed events or backfill earlier events. The local NDJSON log remains complete; use **Send last mission to Mac** after stopping the mission to recover any events that failed to send live. The backend recognizes duplicates, so this recovery upload is safe.

## Local telemetry dashboard

While the local backend is running, open `http://127.0.0.1:8001/dashboard` on the Mac (or use the port chosen for `backend.dev_server`). Enter the current token shown by the server. The page lists stored sessions and refreshes the selected session once per second, showing the latest position, speed, heading if available, horizontal accuracy, altitude, measurement time, and event count. An old session is labelled as having no recent event; the page does not imply that a mission is still running.

This page is read-only. Its trajectory and DEM outline use EPSG:3006 coordinates. Use **Map on** to load OpenStreetMap background images for orientation, **Topography on** to overlay locally generated 5 m elevation contours (heavier every 10 m) from `6472500_535000.tif`, and **Fit** to return to the 2.5 km DEM square. Drag to pan; use + and − to zoom. Map and Topography can be used separately or together. Only enabling Map contacts the OpenStreetMap tile service: the viewed tile area and browser network address become visible to that service, but no backend token or event JSON is sent. Map images are a temporary local-development choice, not a committed public-hosting dependency. Keep the map attribution visible and follow the [OSM tile policy](https://operations.osmfoundation.org/policies/tiles/).

Generate the terrain overlay once, from the repository root, with Python plus Pillow and NumPy:

```sh
python3 -m backend.generate_topography /path/to/6472500_535000.tif
```

This writes `backend/data/contours.png` beside the local SQLite database. The PNG and original DEM are not committed. The backend itself still requires only Python's standard library. The topography image is available only after a valid backend token is provided. The DEM's GeoTIFF tags specify EPSG:3006, a 1 m pixel size, and upper-left E 535000/N 6472500; the 2500 × 2500 pixel footprint is E 535000–537500 and N 6470000–6472500. The contour image is a visual derivative, not an elevation source for Ternav.

The token stays in page memory and is cleared on reload. The API endpoints `GET /v1/sessions`, `GET /v1/latest?sessionId=...`, and `GET /v1/session-events?sessionId=...` require it; loading the page itself exposes no mission data.

## Current security boundary

This local trial is **not end-to-end encrypted**. The Android debug app sends the bearer token and NDJSON over plain HTTP to `127.0.0.1:8000`; `adb reverse` carries that connection to the Mac's loopback-only backend. The token controls access but does not encrypt traffic. The dashboard likewise uses local HTTP. Mission NDJSON files on the Pixel and the Mac SQLite database are not encrypted by this application. Both app and dashboard keep the token in memory for their current use, rather than persisting it in app preferences or browser storage. OpenStreetMap background images use HTTPS when explicitly enabled, but that protects only those third-party image requests, not the telemetry path.

Before any public or wireless deployment, use HTTPS/TLS, stronger production authentication and secret handling, and an explicit data-at-rest/retention policy. HTTPS would encrypt transport between phone/browser and backend; it is not end-to-end encryption in the sense of keeping data unreadable to the backend, which must read telemetry to store and display it.

## Deployment boundary

`eastwing.signalprofessor.com` is a DNS name, not a running backend. Deployment needs a host able to run a persistent Python process and retain a database file, plus HTTPS and backups. Do not point the domain at this development server or expose port 8000 publicly. The API token must be set in the host's secret environment, never committed or placed in a URL. The Android debug app can send a completed mission over the USB tunnel; public HTTPS deployment and durable map hosting are later milestones.

Run tests: `python3 -m unittest discover -s backend/tests -v`.

The dashboard height plot shows GNSS altitude, an experimental barometric relative-height estimate, and DEM terrain height for GNSS fixes inside the tile. The barometric curve uses the first GNSS altitude as a display offset and the first pressure sample as a pressure reference (`8434.5 ln(p0/p)` metres). It is not a calibrated absolute height. The DEM line is bilinearly sampled from the original 1 m raster, with gaps outside the tile or over no-data pixels; the contour overlay is not used as an elevation source. GNSS and DEM vertical reference frames have not been reconciled, so their difference is not yet a validated phone-above-ground height.

Prepare the private local DEM cache once, using a Python environment with Pillow and NumPy:

```sh
python3 -m backend.prepare_dem /path/to/6472500_535000.tif
```

This verifies 2500 × 2500 float32 data, 1 m pixels, origin E 535000/N 6472500, and EPSG:3006, then writes `backend/data/6472500_535000.f32` (25 MB, Git-ignored). The running HTTP server uses only Python's standard library to memory-map that cache and bilinearly sample four pixels per fix. Restart the server after upgrading its code. The authenticated `GET /v1/session-terrain?sessionId=...` endpoint returns a height or `null` for each valid GNSS fix; it does not expose the raster itself. If the cache is absent, the dashboard keeps showing GNSS/barometric height and labels DEM as unavailable.


## Experimental vertical EKF

The authenticated `GET /v1/session-vertical-filter?sessionId=...` endpoint runs a causal offline EKF with state `[h, hdot, b]`: phone height in metres, vertical speed, and pressure bias in hPa. GNSS measures `h`; Android `verticalAccuracyMeters` supplies its standard deviation when available, with a 20 m fallback. Pressure uses the nonlinear standard-atmosphere model `1013.25 exp(-h/8434.5) + b`, linearized at each update. The initial experimental parameters are pressure noise 0.05 hPa, vertical-acceleration process noise 0.25 m/s², and pressure-bias random-walk standard deviation 1 hPa per square-root hour. These are hypotheses to tune, not calibrated sensor specifications.

For the experimental tile `6472500_535000`, GNSS altitude is transformed to the DEM vertical frame with a fixed 32.5 m offset calibrated from the 2026-09-22 drive. This is tile calibration, not an independent geoid model. The dashboard plots `EKF phone height − 0.4 m` against DEM terrain height and reports the mean and sample standard deviation of `(EKF height − 0.4 m − DEM)` inside the tile. DEM is never passed to the EKF as a measurement. The fixed 0.4 m ground clearance is a temporary test value intended to be replaced by laser distance.

Both GNSS and pressure updates use a 3-sigma normalized-innovation gate: an update is rejected when `|residual| > 3 sqrt(H P Hᵀ + R)`. Prediction continues and the dashboard reports accepted and rejected counts separately. Rejection therefore depends on the complete uncertainty model, not merely whether a raw sample looks visually isolated.


## First terrain particle-filter replay

The authenticated `GET /v1/session-particle-filter?sessionId=...` endpoint deterministically replays 1,000 horizontal-position particles inside the DEM tile. State is `[E, N]` in SWEREF 99 TM. The first usable in-tile GNSS position initializes an isotropic 75 m standard-deviation cloud; subsequent GNSS positions are evaluation truth only. GNSS speed and bearing propagate particles in this intentionally simplified first model, with 1.5 m/√s independent horizontal random walk. `EKF phone height − 0.4 m` is compared with bilinearly interpolated DEM height using a 1.5 m Gaussian likelihood. Systematic resampling occurs below ESS 500. A fixed random seed makes tuning comparisons repeatable.

The dashboard Canvas renders all 1,000 particles over the local topographic contours, the untouched initial cloud, posterior weight by opacity, MMSE and MAP trails/estimates, GNSS evaluation truth, ESS, both position errors, and resampling events. Importance weights are propagated recursively and reset to uniform only after systematic resampling. Two motion-only controls use the identical trapezoidal integration of GNSS east/north velocity components: open-loop dead reckoning from the first in-tile GNSS position, and a rolling 30-second prediction anchored at the true GNSS position 30 seconds earlier. These baselines separate short-horizon motion-model information from terrain updates. The initial implementation is diagnostic, not yet an onboard navigation output; it deliberately exposes convergence and divergence rather than hiding them.
