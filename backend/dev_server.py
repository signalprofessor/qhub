"""Start a local-only telemetry receiver with a fresh trial token."""
import argparse
import secrets
from http.server import ThreadingHTTPServer
from pathlib import Path

from backend.server import initialize, make_handler

DATABASE = Path(__file__).resolve().parent / "data" / "local.sqlite3"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8000)
    args = parser.parse_args()
    token = secrets.token_hex(8)  # 16 easy-to-type characters, for localhost trials only
    initialize(DATABASE)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(DATABASE, token))
    print(f"Qhub local backend running on Mac port {args.port}", flush=True)
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
