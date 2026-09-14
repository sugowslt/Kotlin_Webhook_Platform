import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock
from time import sleep


DELAY_MILLISECONDS = int(os.environ.get("WEBHOOK_DELAY_MILLIS", "250"))
if DELAY_MILLISECONDS < 0:
    raise ValueError("WEBHOOK_DELAY_MILLIS must not be negative")


class WebhookHandler(BaseHTTPRequestHandler):
    failed_once = set()
    failed_once_lock = Lock()
    held_once = set()
    held_once_lock = Lock()
    output_lock = Lock()

    def do_GET(self):
        if self.path != "/health":
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"status":"UP"}')

    def do_POST(self):
        if self.path not in (
            "/webhooks",
            "/webhooks/delay",
            "/webhooks/fail-once",
            "/webhooks/hold-once",
        ):
            self.send_error(404)
            return
        content_length = int(self.headers.get("Content-Length", "0"))
        payload = self.rfile.read(content_length).decode("utf-8")
        parsed_payload = json.loads(payload)
        record = {
            "deliveryId": self.headers.get("X-HookRelay-Delivery"),
            "timestamp": self.headers.get("X-HookRelay-Timestamp"),
            "signature": self.headers.get("X-HookRelay-Signature"),
            "payload": parsed_payload,
        }

        status_code = 204
        should_hold = False
        should_delay = self.path == "/webhooks/delay"
        if self.path == "/webhooks/fail-once":
            request_key = str(parsed_payload.get("orderId", record["deliveryId"]))
            with self.failed_once_lock:
                if request_key not in self.failed_once:
                    self.failed_once.add(request_key)
                    status_code = 400
        elif self.path == "/webhooks/hold-once":
            request_key = str(parsed_payload.get("orderId", record["deliveryId"]))
            with self.held_once_lock:
                if request_key not in self.held_once:
                    self.held_once.add(request_key)
                    should_hold = True

        record["responseStatus"] = "HELD" if should_hold else status_code
        if should_delay:
            record["delayMillis"] = DELAY_MILLISECONDS
        with self.output_lock:
            print(json.dumps(record, ensure_ascii=False), flush=True)
        if should_hold:
            sleep(60)
        elif should_delay:
            sleep(DELAY_MILLISECONDS / 1000)
        try:
            self.send_response(status_code)
            self.end_headers()
        except (BrokenPipeError, ConnectionResetError):
            return

    def log_message(self, format, *args):
        return


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", 8081), WebhookHandler)
    print("webhook receiver listening on 8081", flush=True)
    server.serve_forever()
