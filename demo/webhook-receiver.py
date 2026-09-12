import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock


class WebhookHandler(BaseHTTPRequestHandler):
    failed_once = set()
    failed_once_lock = Lock()

    def do_GET(self):
        if self.path != "/health":
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"status":"UP"}')

    def do_POST(self):
        if self.path not in ("/webhooks", "/webhooks/fail-once"):
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
        if self.path == "/webhooks/fail-once":
            request_key = str(parsed_payload.get("orderId", record["deliveryId"]))
            with self.failed_once_lock:
                if request_key not in self.failed_once:
                    self.failed_once.add(request_key)
                    status_code = 400

        record["responseStatus"] = status_code
        print(json.dumps(record, ensure_ascii=False), flush=True)
        self.send_response(status_code)
        self.end_headers()

    def log_message(self, format, *args):
        return


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", 8081), WebhookHandler)
    print("webhook receiver listening on 8081", flush=True)
    server.serve_forever()
