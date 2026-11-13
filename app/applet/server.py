import http.server
import socketserver

PORT = 3000

HTML = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>Cloudyte Android — E2EE Ready</title>
  <style>
    body { font-family: Roboto, sans-serif; background: #121212; color: #fff; display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; }
    .card { background: #1e1e1e; padding: 30px; border-radius: 12px; text-align: center; box-shadow: 0 8px 24px rgba(0,0,0,0.5); max-width: 400px; }
    h2 { color: #4caf50; margin-bottom: 10px; }
    p { color: #b0b0b0; font-size: 14px; line-height: 1.5; }
  </style>
</head>
<body>
  <div class="card">
    <h2>Android App Ready</h2>
    <p>Cloudyte Android E2EE app compiled successfully.<br/>APK: app/build/outputs/apk/debug/app-debug.apk</p>
  </div>
</body>
</html>
"""

class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body = HTML.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, format, *args):
        pass

class ReusableTCPServer(socketserver.TCPServer):
    allow_reuse_address = True

if __name__ == "__main__":
    with ReusableTCPServer(("0.0.0.0", PORT), Handler) as httpd:
        httpd.serve_forever()
