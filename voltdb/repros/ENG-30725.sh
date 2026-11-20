#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30725
-- summary: A client that disconnects after its streaming snapshot ended removes the participant nodes of the stream in progress, which then never ends and blocks every later stream
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR (and VOLTDB_LICENSE, if any). Three nodes (k=0, 4 sites each), the
# default streaming throttle (100 ms between a host's chunks). streaming/DisconnectRepro.java: client A streams a
# snapshot and stays connected; 65 s later client B streams a snapshot of 50 one-row tables (about 20 s), and 3 s into
# it A disconnects; then client C streams. Exit 0 if B's or C's stream fails (the bug), 1 if both complete. CONTROL=1
# keeps A connected.
set -u
here=$(cd "$(dirname "$0")" && pwd)
V=$VOLTDB_HOME
C=$WORKDIR/disconnect
B=$PORT_BASE
port() { echo $((B + 10 * $1 + $2)); }
rm -rf "$C"; mkdir -p "$C/classes"
cat > "$C/d.xml" <<X
<?xml version="1.0"?>
<deployment><cluster sitesperhost="4" kfactor="0"/><httpd enabled="false"/></deployment>
X
H="localhost:$(port 0 2),localhost:$(port 1 2),localhost:$(port 2 2)"
for i in 0 1 2; do
  "$V/bin/voltdb" init --force ${VOLTDB_LICENSE:+--license "$VOLTDB_LICENSE"} -D "$C/n$i" -C "$C/d.xml" > "$C/init$i.log" 2>&1
  (nohup "$V/bin/voltdb" start -c 3 -D "$C/n$i" -H "$H" --client=$(port $i 0) --admin=$(port $i 1) --internal=$(port $i 2) \
      --zookeeper=$(port $i 3) --replication=$(port $i 4) --metrics=$(port $i 5) > "$C/start$i.log" 2>&1 & echo $! > "$C/n$i.pid")
done
cleanup() {
  "$V/bin/voltadmin" shutdown --force -H localhost:$(port 0 1) > /dev/null 2>&1; sleep 5
  for i in 0 1 2; do kill -9 "$(cat "$C/n$i.pid")" 2>/dev/null; done
}
trap cleanup EXIT
for i in 0 1 2; do
  for _ in $(seq 1 180); do grep -q "Server completed initialization" "$C/start$i.log" 2>/dev/null && break; sleep 1; done
  grep -q "Server completed initialization" "$C/start$i.log" || { echo "node $i did not start"; tail -20 "$C/start$i.log"; exit 2; }
done
cp="$V/voltdb/*:$V/lib/*"
javac -cp "$cp" -d "$C/classes" "$here/streaming/DisconnectRepro.java" || exit 2
# the client prints its streaming debug lines ("... ") to stderr (ENG-30721)
java -cp "$cp:$C/classes" DisconnectRepro localhost:$(port 0 0),localhost:$(port 1 0),localhost:$(port 2 0) 50 \
    ${CONTROL:+control} 2> "$C/client.err" | grep -v SLF4J
status=${PIPESTATUS[0]}
grep -h "orphaned participant\|not found during deregistration" "$C"/n*/voltdbroot/log/volt.log | sed -E 's/^(.{23}).*STREAMING_SNAPSHOT: /\1 /' | head -6
exit "$status"
