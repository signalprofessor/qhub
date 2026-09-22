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

The receipt reports `received`, `inserted`, and `duplicates`. Repeating the same upload is safe: existing events are counted as duplicates. Query `GET /v1/sessions` with the same Authorization header to see session counts; `GET /health` is public and contains no mission data.

## Pixel-to-Mac development test

This test is local; it does not contact the public subdomain. Install the 0.5.0 debug APK on the Pixel and connect it by USB with USB debugging enabled.

1. In a Mac terminal, run `python3 -m backend.dev_server` from the repository root. Leave it open and note the fresh token shown there.
2. In another Mac terminal, run `/Users/fregu23/Library/Android/sdk/platform-tools/adb devices` and confirm the Pixel is listed as `device`. Then run `/Users/fregu23/Library/Android/sdk/platform-tools/adb reverse tcp:8000 tcp:8000`.
3. In the app, stop a mission, enter the displayed token, and tap **Send last mission to Mac**. The receipt should report the number of inserted events. Sending the same mission again should report them as duplicates.
4. On the Mac, stop the server with Ctrl+C. Data remains in `backend/data/local.sqlite3`, which is ignored by Git.

The USB tunnel lets the Pixel's `127.0.0.1:8000` reach the Mac's local server. Only the debug APK permits cleartext traffic to this loopback address; a public deployment must use HTTPS. No token is saved in the Android app.

## Opt-in live telemetry trial (0.6.0 debug APK)

With the Pixel attached by USB, start `python3 -m backend.dev_server --port 8001` on the Mac, then run `adb reverse tcp:8000 tcp:8001` using Android SDK platform-tools. Enter the new token in the app. Tap **Turn live telemetry ON**, then **Start mission**. As GNSS events arrive, the live status shows sent and failed counts. Stop the mission and turn live telemetry off.

Live sending is best-effort and works only while the app is in the foreground. It does not retry failed events or backfill earlier events. The local NDJSON log remains complete; use **Send last mission to Mac** after stopping the mission to recover any events that failed to send live. The backend recognizes duplicates, so this recovery upload is safe.

## Deployment boundary

`eastwing.signalprofessor.com` is a DNS name, not a running backend. Deployment needs a host able to run a persistent Python process and retain a database file, plus HTTPS and backups. Do not point the domain at this development server or expose port 8000 publicly. The API token must be set in the host's secret environment, never committed or placed in a URL. The Android debug app can send a completed mission over the USB tunnel; automatic live telemetry and public HTTPS deployment are later milestones.

Run tests: `python3 -m unittest discover -s backend/tests -v`.
