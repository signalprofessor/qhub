import http.client
import json
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path

from backend.server import initialize, make_handler


def event(sequence=0, event_id="event-0"):
    return {
        "schemaVersion": 1,
        "eventId": event_id,
        "deviceId": "device-1",
        "sessionId": "session-1",
        "sequence": sequence,
        "source": "navigation",
        "eventType": "navigation.gnss",
        "timestamp": {"monotonicNanos": 1000 + sequence, "utcEpochMillis": 2000 + sequence},
        "payload": {"latitudeDegrees": 58.0, "longitudeDegrees": 15.0},
    }


class BackendHttpTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db_path = str(Path(self.tmp.name) / "events.sqlite3")
        initialize(self.db_path)
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.db_path, "test-token"))
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join()
        self.tmp.cleanup()

    def request(self, method, path, body=None, token="test-token"):
        connection = http.client.HTTPConnection("127.0.0.1", self.httpd.server_port)
        headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/x-ndjson"}
        connection.request(method, path, body=body, headers=headers)
        response = connection.getresponse()
        result = response.status, json.loads(response.read())
        connection.close()
        return result

    def test_health_and_auth(self):
        self.assertEqual(self.request("GET", "/health")[0], 200)
        self.assertEqual(self.request("GET", "/v1/sessions", token="wrong")[0], 401)
        self.assertEqual(self.request("GET", "/v1/sessions", token="tökén")[0], 401)

    def test_ingest_retry_and_summary(self):
        body = (json.dumps(event()) + "\n" + json.dumps(event(1, "event-1")) + "\n").encode()
        self.assertEqual(self.request("POST", "/v1/events", body)[1],
                         {"received": 2, "inserted": 2, "duplicates": 0})
        self.assertEqual(self.request("POST", "/v1/events", body)[1],
                         {"received": 2, "inserted": 0, "duplicates": 2})
        status, result = self.request("GET", "/v1/sessions")
        self.assertEqual(status, 200)
        self.assertEqual(result["sessions"][0]["eventCount"], 2)

    def test_dashboard_is_static_and_contains_no_event_data(self):
        connection = http.client.HTTPConnection("127.0.0.1", self.httpd.server_port)
        connection.request("GET", "/dashboard")
        response = connection.getresponse()
        body = response.read().decode()
        self.assertEqual(response.status, 200)
        self.assertIn("Mission telemetry", body)
        self.assertIn("default-src 'none'", response.getheader("Content-Security-Policy"))
        self.assertNotIn("test-token", body)
        connection.close()
        connection = http.client.HTTPConnection("127.0.0.1", self.httpd.server_port)
        connection.request("GET", "/dashboard.js")
        response = connection.getresponse()
        self.assertEqual(response.status, 200)
        self.assertIn(b"/v1/latest", response.read())
        connection.close()

    def test_topography_requires_token_and_is_not_public(self):
        self.assertEqual(self.request("GET", "/contours.png", token="wrong")[0], 401)
        self.assertEqual(self.request("GET", "/contours.png")[0], 404)
        image_path = Path(self.tmp.name) / "contours.png"
        image_path.write_bytes(b"\x89PNG\r\n\x1a\nlocal test")
        connection = http.client.HTTPConnection("127.0.0.1", self.httpd.server_port)
        connection.request("GET", "/contours.png", headers={"Authorization": "Bearer test-token"})
        response = connection.getresponse()
        self.assertEqual(response.status, 200)
        self.assertEqual(response.getheader("Content-Type"), "image/png")
        self.assertTrue(response.read().startswith(b"\x89PNG"))
        connection.close()

    def test_latest_event_requires_token_and_session(self):
        body = (json.dumps(event()) + "\n" + json.dumps(event(1, "event-1")) + "\n").encode()
        self.assertEqual(self.request("POST", "/v1/events", body)[0], 200)
        self.assertEqual(self.request("GET", "/v1/latest?sessionId=session-1", token="wrong")[0], 401)
        self.assertEqual(self.request("GET", "/v1/latest")[0], 400)
        self.assertEqual(self.request("GET", "/v1/latest?sessionId=missing")[0], 404)
        status, result = self.request("GET", "/v1/latest?sessionId=session-1")
        self.assertEqual(status, 200)
        self.assertEqual(result["event"]["sequence"], 1)
        self.assertEqual(result["event"]["eventId"], "event-1")

    def test_session_history_requires_token_and_orders_events(self):
        body = (json.dumps(event(1, "event-1")) + "\n" +
                json.dumps(event(0, "event-0")) + "\n").encode()
        self.assertEqual(self.request("POST", "/v1/events", body)[0], 200)
        self.assertEqual(self.request("GET", "/v1/session-events?sessionId=session-1", token="wrong")[0], 401)
        self.assertEqual(self.request("GET", "/v1/session-events")[0], 400)
        self.assertEqual(self.request("GET", "/v1/session-events?sessionId=missing")[0], 404)
        status, result = self.request("GET", "/v1/session-events?sessionId=session-1")
        self.assertEqual(status, 200)
        self.assertEqual([item["sequence"] for item in result["events"]], [0, 1])

    def test_conflict_rolls_back_batch(self):
        body = (json.dumps(event(1, "event-1")) + "\n" +
                json.dumps(event(1, "different-id")) + "\n").encode()
        self.assertEqual(self.request("POST", "/v1/events", body)[0], 409)
        self.assertEqual(self.request("GET", "/v1/sessions")[1]["sessions"], [])

    def test_invalid_batch_rejected(self):
        self.assertEqual(self.request("POST", "/v1/events", b'{"bad":true}\n')[0], 400)
        self.assertEqual(self.request("GET", "/v1/sessions")[1]["sessions"], [])


if __name__ == "__main__":
    unittest.main()
