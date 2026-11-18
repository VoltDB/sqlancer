#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30723
-- summary: A streaming snapshot whose table lists match no table never ends: the client waits forever, and cannot stream again
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR (and VOLTDB_LICENSE, if any). One node, a table R.
# streaming/NoTableRepro.java streams a snapshot with tables NO_SUCH_TABLE (SKIP=1: with skipTables R, every table of
# the database) and waits 60 s. Exit 0 if the stream neither succeeds nor fails (the bug: each host finds no file to
# stream and returns without sending its end-of-tables message), 1 if it does either.
set -u
here=$(cd "$(dirname "$0")" && pwd)
V=$VOLTDB_HOME
C=$WORKDIR/notable
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
javac -cp "$cp" -d "$C/classes" "$here/streaming/NoTableRepro.java" || exit 2
if [ -n "${SKIP:-}" ]; then lists="- R"; else lists="NO_SUCH_TABLE -"; fi
# the client prints its streaming debug lines ("... ") to stderr (ENG-30721)
java -cp "$cp:$C/classes" NoTableRepro localhost:$B $lists 2> "$C/client.err" | grep -v SLF4J
exit "${PIPESTATUS[0]}"
