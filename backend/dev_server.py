"""Start a local-only telemetry receiver with a fresh trial token."""
import secrets
from http.server import ThreadingHTTPServer
from pathlib import Path

from backend.server import initialize, make_handler

DATABASE = Path(__file__).resolve().parent / "data" / "local.sqlite3"
PORT = 8000


def main():
    token = secrets.token_urlsafe(32)
    initialize(DATABASE)
    server = ThreadingHTTPServer(("127.0.0.1", PORT), make_handler(DATABASE, token))
    print("Qhub local backend running on Mac port 8000", flush=True)
    print("Enter this one-time token in the Pixel app:", flush=True)
    print(token, flush=True)
    print("Keep this terminal open. Press Ctrl+C to stop.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
