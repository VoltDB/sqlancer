#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30724
-- summary: After a streaming snapshot, Client2 keeps one request outstanding for about 60 s (the call timeout), so close() and drain() wait that long
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR (and VOLTDB_LICENSE, if any). One node, a table R of one row.
# streaming/CloseDelayRepro.java closes a client that streamed nothing, then streams a snapshot of R through another
# client and closes it. Exit 0 if that close takes 30 s or more (the bug: one request stays outstanding after the
# stream, and close() drains it), 1 if not.
set -u
here=$(cd "$(dirname "$0")" && pwd)
V=$VOLTDB_HOME
C=$WORKDIR/closedelay
B=$PORT_BASE
rm -rf "$C"; mkdir -p "$C/classes"
cat > "$C/d.xml" <<X
<?xml version="1.0"?>
<deployment><cluster sitesperhost="4" kfactor="0"/><httpd enabled="false"/></deployment>
X
"$V/bin/voltdb" init --force ${VOLTDB_LICENSE:+--license "$VOLTDB_LICENSE"} -D "$C/n0" -C "$C/d.xml" > "$C/init.log" 2>&1
(nohup "$V/bin/voltdb" start -D "$C/n0" -H localhost:$((B + 2)) --client=$B --admin=$((B + 1)) --internal=$((B + 2)) \
    --zookeeper=$((B + 3)) --replication=$((B + 4)) --metrics=$((B + 5)) > "$C/start.log" 2>&1 & echo $! > "$C/n0.pid")
cleanup() { "$V/bin/voltadmin" shutdown --force -H localhost:$((B + 1)) > /dev/null 2>&1; sleep 5; kill -9 "$(cat "$C/n0.pid")" 2>/dev/null; }
trap cleanup EXIT
for _ in $(seq 1 120); do grep -q "Server completed initialization" "$C/start.log" 2>/dev/null && break; sleep 1; done
grep -q "Server completed initialization" "$C/start.log" || { echo "the node did not start"; tail -20 "$C/start.log"; exit 2; }
cp="$V/voltdb/*:$V/lib/*"
javac -cp "$cp" -d "$C/classes" "$here/streaming/CloseDelayRepro.java" || exit 2
# the client prints its streaming debug lines ("... ") to stderr (ENG-30721)
java -cp "$cp:$C/classes" CloseDelayRepro localhost:$B 2> "$C/client.err" | grep -v SLF4J
exit "${PIPESTATUS[0]}"
