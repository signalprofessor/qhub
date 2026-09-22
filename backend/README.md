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

## Deployment boundary

`eastwing.signalprofessor.com` is a DNS name, not a running backend. Deployment needs a host able to run a persistent Python process and retain a database file, plus HTTPS and backups. Do not point the domain at this development server or expose port 8000 publicly. The API token must be set in the host's secret environment, never committed or placed in a URL. The Android app does not send events to this server yet; that connection is the next milestone after hosting is chosen.

Run tests: `python3 -m unittest discover -s backend/tests -v`.
