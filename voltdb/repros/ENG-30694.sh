#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30694
-- summary: HTTP export in batch mode to an endpoint with no query string sends its first batch again and again and exports nothing more
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR. 1 node; an HTTP export target with batch.mode true, type csv,
# period 5s and the endpoint http://localhost:<port>/%t/%p/%g (no query string); a stream exporting to it; a small
# receiver answering 200 that appends each request's path and body to a file. One row is inserted, then 30 s later
# another. Exit 0 if the first row arrived more than twice or the second never arrived, and the server logged a
# NullPointerException in HttpExportDecoder.onBlockCompletion (the bug); 1 if each row arrived once; 2 on an error.
set -u
V=$VOLTDB_HOME; W=$WORKDIR/batchhttp; P=$PORT_BASE
rm -rf "$W"; mkdir -p "$W"
port() { echo $((P + $1)); }
cat >"$W/recv.py" <<'PY'
import http.server, sys
class H(http.server.BaseHTTPRequestHandler):
    def _h(self):
        n = int(self.headers.get('Content-Length') or 0)
        b = self.rfile.read(n).decode()
        with open(sys.argv[2], 'a') as f:
            f.write(self.command + ' ' + self.path + '\t' + b.replace('\n', '|') + '\n')
        self.send_response(200); self.end_headers()
    do_PUT = do_POST = _h
    def log_message(self, *a): pass
http.server.HTTPServer(('127.0.0.1', int(sys.argv[1])), H).serve_forever()
PY
(python3 -I "$W/recv.py" "$(port 7)" "$W/received.log" >"$W/recv.out" 2>&1 & echo $! >"$W/recv.pid")
cat >"$W/d.xml" <<XML
<?xml version="1.0"?>
<deployment><cluster sitesperhost="2" kfactor="0"/><httpd enabled="false"/>
<export><configuration target="h" enabled="true" type="http">
  <property name="endpoint">http://localhost:$(port 7)/%t/%p/%g</property>
  <property name="batch.mode">true</property><property name="type">csv</property>
  <property name="method">PUT</property><property name="period">5s</property>
</configuration></export></deployment>
XML
"$V/bin/voltdb" init --force -D "$W/n0" -C "$W/d.xml" ${VOLTDB_LICENSE:+--license "$VOLTDB_LICENSE"} >"$W/init.log" 2>&1 || exit 2
(nohup "$V/bin/voltdb" start -D "$W/n0" -H "localhost:$(port 2)" --client=$(port 0) --admin=$(port 1) --internal=$(port 2) \
    --zookeeper=$(port 3) --replication=$(port 4) --metrics=$(port 5) >"$W/start.log" 2>&1 & echo $! >"$W/n0.pid")
cleanup() { kill -9 "$(cat "$W/n0.pid" 2>/dev/null)" "$(cat "$W/recv.pid" 2>/dev/null)" 2>/dev/null; }
trap cleanup EXIT
for _ in $(seq 1 180); do grep -q "Server completed initialization" "$W/start.log" 2>/dev/null && break; sleep 1; done
grep -q "Server completed initialization" "$W/start.log" || exit 2
sql() { "$V/bin/sqlcmd" --port=$(port 0) 2>&1; }
printf "CREATE STREAM s PARTITION ON COLUMN k EXPORT TO TARGET h (k INTEGER NOT NULL, i BIGINT NOT NULL);\n" | sql >>"$W/sql.out"
printf "INSERT INTO s VALUES (1, 101);\n" | sql >>"$W/sql.out"
sleep 30
printf "INSERT INTO s VALUES (1, 102);\n" | sql >>"$W/sql.out"
sleep 30
first=$(grep -c ',"101"\|,101' "$W/received.log" 2>/dev/null)
second=$(grep -c ',"102"\|,102' "$W/received.log" 2>/dev/null)
npe=$(grep -c "NullPointerException.*queryString" "$W/n0/voltdbroot/log/volt.log" 2>/dev/null)
echo "the first row arrived $first times, the second $second times; NullPointerExceptions logged: $npe"
[ "$first" -ge 1 ] || exit 2
{ [ "$first" -gt 2 ] || [ "$second" -eq 0 ]; } && [ "$npe" -gt 0 ]
