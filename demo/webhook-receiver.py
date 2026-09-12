import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class WebhookHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/health":
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"status":"UP"}')

    def do_POST(self):
        if self.path != "/webhooks":
            self.send_error(404)
            return
        content_length = int(self.headers.get("Content-Length", "0"))
        payload = self.rfile.read(content_length).decode("utf-8")
        record = {
            "deliveryId": self.headers.get("X-HookRelay-Delivery"),
            "timestamp": self.headers.get("X-HookRelay-Timestamp"),
            "signature": self.headers.get("X-HookRelay-Signature"),
            "payload": json.loads(payload),
        }
        print(json.dumps(record, ensure_ascii=False), flush=True)
        self.send_response(204)
        self.end_headers()

    def log_message(self, format, *args):
        return


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", 8081), WebhookHandler)
    print("webhook receiver listening on 8081", flush=True)
    server.serve_forever()
