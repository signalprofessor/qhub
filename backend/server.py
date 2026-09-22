"""Minimal Qhub telemetry receiver. Standard-library only; deploy behind HTTPS."""
import argparse
import hmac
import json
import os
import sqlite3
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

MAX_BODY_BYTES = 1_000_000
MAX_EVENTS = 1_000
STATIC_DIRECTORY = Path(__file__).resolve().parent / "static"
STATIC_FILES = {
    "/dashboard": ("dashboard.html", "text/html; charset=utf-8"),
    "/dashboard.css": ("dashboard.css", "text/css; charset=utf-8"),
    "/dashboard.js": ("dashboard.js", "text/javascript; charset=utf-8"),
}


class InvalidBatch(ValueError):
    pass


class EventConflict(ValueError):
    pass


def connect(db_path):
    connection = sqlite3.connect(db_path, timeout=10)
    connection.execute("PRAGMA busy_timeout = 10000")
    return connection


def initialize(db_path):
    Path(db_path).parent.mkdir(parents=True, exist_ok=True)
    with connect(db_path) as db:
        db.execute("""
            CREATE TABLE IF NOT EXISTS events (
                event_id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                sequence INTEGER NOT NULL,
                event_type TEXT NOT NULL,
                utc_epoch_millis INTEGER NOT NULL,
                raw_json TEXT NOT NULL,
                received_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE(session_id, sequence)
            )
        """)
        db.execute("CREATE INDEX IF NOT EXISTS events_by_session ON events(session_id, sequence)")


def validate_event(event):
    if not isinstance(event, dict):
        raise InvalidBatch("event must be a JSON object")
    if type(event.get("schemaVersion")) is not int or event["schemaVersion"] != 1:
        raise InvalidBatch("unsupported schemaVersion")
    for field in ("eventId", "deviceId", "sessionId", "source", "eventType"):
        value = event.get(field)
        if not isinstance(value, str) or not value.strip() or len(value) > 200:
            raise InvalidBatch(f"invalid {field}")
    if type(event.get("sequence")) is not int or not 0 <= event["sequence"] <= 9_223_372_036_854_775_807:
        raise InvalidBatch("invalid sequence")
    timestamp = event.get("timestamp")
    if not isinstance(timestamp, dict):
        raise InvalidBatch("invalid timestamp")
    for field in ("monotonicNanos", "utcEpochMillis"):
        value = timestamp.get(field)
        if type(value) is not int or not 0 <= value <= 9_223_372_036_854_775_807:
            raise InvalidBatch(f"invalid {field}")
    if not isinstance(event.get("payload"), dict):
        raise InvalidBatch("invalid payload")
    return event


def parse_ndjson(body):
    try:
        text = body.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise InvalidBatch("body must be UTF-8") from exc
    lines = [line for line in text.splitlines() if line.strip()]
    if not lines or len(lines) > MAX_EVENTS:
        raise InvalidBatch("batch must contain 1 to 1000 events")
    events = []
    for number, line in enumerate(lines, 1):
        try:
            events.append(validate_event(json.loads(line, parse_constant=lambda value: (_ for _ in ()).throw(InvalidBatch(f"invalid JSON constant: {value}")))))
        except (json.JSONDecodeError, InvalidBatch) as exc:
            raise InvalidBatch(f"line {number}: {exc}") from exc
    return events


def ingest(db_path, events):
    inserted = 0
    duplicate = 0
    with connect(db_path) as db:
        db.execute("BEGIN IMMEDIATE")
        for event in events:
            canonical = json.dumps(event, sort_keys=True, separators=(",", ":"), allow_nan=False)
            existing = db.execute(
                "SELECT raw_json FROM events WHERE event_id = ? OR (session_id = ? AND sequence = ?)",
                (event["eventId"], event["sessionId"], event["sequence"]),
            ).fetchone()
            if existing:
                if existing[0] != canonical:
                    raise EventConflict("event ID or session sequence already holds different data")
                duplicate += 1
                continue
            db.execute(
                """INSERT INTO events
                   (event_id, session_id, device_id, sequence, event_type, utc_epoch_millis, raw_json)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
                (event["eventId"], event["sessionId"], event["deviceId"], event["sequence"],
                 event["eventType"], event["timestamp"]["utcEpochMillis"], canonical),
            )
            inserted += 1
    return {"received": len(events), "inserted": inserted, "duplicates": duplicate}


def sessions(db_path):
    with connect(db_path) as db:
        rows = db.execute(
            """SELECT session_id, device_id, COUNT(*), MIN(sequence), MAX(sequence),
                      MAX(utc_epoch_millis)
               FROM events GROUP BY session_id, device_id
               ORDER BY MAX(utc_epoch_millis) DESC LIMIT 100"""
        ).fetchall()
    return [
        {"sessionId": row[0], "deviceId": row[1], "eventCount": row[2],
         "firstSequence": row[3], "lastSequence": row[4], "lastUtcEpochMillis": row[5]}
        for row in rows
    ]


def latest_event(db_path, session_id):
    with connect(db_path) as db:
        row = db.execute(
            "SELECT raw_json FROM events WHERE session_id = ? ORDER BY sequence DESC LIMIT 1",
            (session_id,),
        ).fetchone()
    return json.loads(row[0]) if row else None


def make_handler(db_path, token):
    class Handler(BaseHTTPRequestHandler):
        def respond(self, status, data):
            body = json.dumps(data, separators=(",", ":")).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(body)

        def serve_static(self, path):
            filename, content_type = STATIC_FILES[path]
            body = (STATIC_DIRECTORY / filename).read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header(
                "Content-Security-Policy",
                "default-src 'none'; script-src 'self'; style-src 'self'; "
                "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
            )
            self.end_headers()
            self.wfile.write(body)

        def authorized(self):
            provided = self.headers.get("Authorization", "")
            if not hmac.compare_digest(provided.encode("utf-8"), f"Bearer {token}".encode("utf-8")):
                self.respond(401, {"error": "unauthorized"})
                return False
            return True

        def do_GET(self):
            request = urlsplit(self.path)
            path = request.path
            if path in STATIC_FILES:
                self.serve_static(path)
            elif path == "/health":
                self.respond(200, {"status": "ok"})
            elif path == "/v1/sessions":
                if self.authorized():
                    self.respond(200, {"sessions": sessions(db_path)})
            elif path == "/v1/latest":
                if not self.authorized():
                    return
                ids = parse_qs(request.query, keep_blank_values=True).get("sessionId", [])
                if len(ids) != 1 or not ids[0] or len(ids[0]) > 200:
                    self.respond(400, {"error": "one sessionId is required"})
                    return
                event = latest_event(db_path, ids[0])
                if event is None:
                    self.respond(404, {"error": "session not found"})
                else:
                    self.respond(200, {"event": event})
            else:
                self.respond(404, {"error": "not found"})

        def do_POST(self):
            if urlsplit(self.path).path != "/v1/events":
                self.respond(404, {"error": "not found"})
                return
            if not self.authorized():
                return
            if self.headers.get_content_type() not in ("application/x-ndjson", "application/ndjson"):
                self.respond(415, {"error": "Content-Type must be application/x-ndjson"})
                return
            try:
                length = int(self.headers.get("Content-Length", ""))
            except ValueError:
                self.respond(411, {"error": "Content-Length required"})
                return
            if not 0 < length <= MAX_BODY_BYTES:
                self.respond(413, {"error": "body must be 1 to 1000000 bytes"})
                return
            try:
                events = parse_ndjson(self.rfile.read(length))
                receipt = ingest(db_path, events)
            except InvalidBatch as exc:
                self.respond(400, {"error": str(exc)})
                return
            except EventConflict as exc:
                self.respond(409, {"error": str(exc)})
                return
            self.respond(200, receipt)

    return Handler


def main():
    parser = argparse.ArgumentParser(description="Qhub telemetry receiver")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
    args = parser.parse_args()
    token = os.environ.get("QHUB_API_TOKEN", "")
    if len(token) < 32:
        parser.error("set QHUB_API_TOKEN to a random value of at least 32 characters")
    db_path = os.environ.get("QHUB_DB_PATH", "data/qhub.sqlite3")
    initialize(db_path)
    server = ThreadingHTTPServer((args.host, args.port), make_handler(db_path, token))
    print(f"Qhub receiver listening on {args.host}:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
