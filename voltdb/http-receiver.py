"""An HTTP export target's receiver (run-sqlancer.sh --export). Usage:
http-receiver.py PORT FILE [HOLD [MODE [SECRET ALGORITHM NAME]]]

Appends one line per exported row to FILE: the method and path, a tab, and the row's columns as form pairs ending in
&I=<the row's I>, which run-sqlancer.sh counts. While the file HOLD exists, every request is refused with 503 and not
recorded: the export client keeps the rows and sends them again later (milestone 45).

MODE (milestone 47) is how the target sends rows:
  form    each request is one row as form pairs, in the body (POST, PUT) or the query (GET)
  signed  form, signed with the HMAC ALGORITHM (HmacSHA1, HmacSHA256) of SECRET in the pair NAME; a request whose
          signature does not match is refused with 403 and recorded as a BADSIG line, which fails the run
  batch   each request is a batch of rows as CSV lines (POST, PUT), the row's I in the last column
"""
import base64, csv, hashlib, hmac, http.server, io, os, sys, threading, urllib.parse

port, path = int(sys.argv[1]), sys.argv[2]
hold = sys.argv[3] if len(sys.argv) > 3 and sys.argv[3] else None
mode = sys.argv[4] if len(sys.argv) > 4 else 'form'
secret, algorithm, sig_name = (sys.argv[5], sys.argv[6], sys.argv[7]) if mode == 'signed' else (None, None, None)
lock = threading.Lock()


def signature_ok(method, host, upath, pairs):
    """VoltDB's HttpExportClient signs METHOD\\nhost\\npath\\n<the pairs, sorted by name, as sent>, and appends
    NAME=<the percent-encoded base64 of the HMAC>."""
    sent = [p for p in pairs if p.split('=', 1)[0] == sig_name]
    if len(sent) != 1:
        return False
    rest = [p for p in pairs if p.split('=', 1)[0] != sig_name]
    base = '%s\n%s\n%s\n%s' % (method, host, upath or '/', '&'.join(rest))
    digest = hashlib.sha1 if algorithm == 'HmacSHA1' else hashlib.sha256
    mac = base64.b64encode(hmac.new(secret.encode(), base.encode(), digest).digest()).decode()
    return urllib.parse.unquote(sent[0].split('=', 1)[1]) == mac


class H(http.server.BaseHTTPRequestHandler):
    def _handle(self):
        n = int(self.headers.get('Content-Length') or 0)
        body = self.rfile.read(n).decode('utf-8', 'replace')
        if hold and os.path.exists(hold):
            self.send_response(503); self.end_headers()
            return
        upath, _, query = self.path.partition('?')
        lines = []
        status = 200
        if mode == 'batch':
            for row in csv.reader(io.StringIO(body)):
                if row:
                    lines.append('%s %s\t%s&I=%s' % (self.command, upath, ','.join(row[:-1]).replace('&', ' '), row[-1]))
        else:
            data = query if self.command == 'GET' else body
            pairs = [p for p in data.split('&') if p]
            if mode == 'signed' and not signature_ok(self.command, 'localhost', upath, pairs):
                lines.append('BADSIG %s %s\t%s' % (self.command, upath, data))
                status = 403
            else:
                lines.append('%s %s\t%s' % (self.command, upath, '&'.join(pairs)))
        with lock, open(path, 'a') as f:
            for line in lines:
                f.write(line.replace('\n', '\\n') + '\n')
        self.send_response(status); self.end_headers()

    do_POST = do_PUT = do_GET = _handle

    def log_message(self, *a):
        pass


http.server.ThreadingHTTPServer(('127.0.0.1', port), H).serve_forever()
