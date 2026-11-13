"""An HTTP export target's receiver: appends each request's path and body, one line each, to a file. Usage:
http-receiver.py PORT FILE [HOLD] (run-sqlancer.sh --export). While the file HOLD exists, every request is refused
with 503 and not recorded: the export client keeps the rows and sends them again later (milestone 45)."""
import http.server, os, sys, threading
port, path = int(sys.argv[1]), sys.argv[2]
hold = sys.argv[3] if len(sys.argv) > 3 else None
lock = threading.Lock()
class H(http.server.BaseHTTPRequestHandler):
    def _handle(self):
        n = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(n).decode('utf-8', 'replace').replace('\n', '\\n')
        if hold and os.path.exists(hold):
            self.send_response(503); self.end_headers()
            return
        with lock, open(path, 'a') as f:
            f.write(self.command + ' ' + self.path + '\t' + body + '\n')
        self.send_response(200); self.end_headers()
    do_POST = do_PUT = do_GET = _handle
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(('127.0.0.1', port), H).serve_forever()
