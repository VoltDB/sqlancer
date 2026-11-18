#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30720
-- summary: Client2Impl.streamSnapshotAsync completes its future before the chunk consumer has been given every chunk: one host's end-of-tables message overtakes its earlier chunks
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR (and VOLTDB_LICENSE, if any). One node, 8 sites, no streaming
# throttle (streamingthrottle="0"; the default sends a host's chunks 100 ms apart, which hides the race). A partitioned
# table T of 5,000 rows; streaming/EarlyCompletionRepro.java streams a snapshot of it 30 times through
# Client2Impl.streamSnapshotSync, with a chunk consumer that spends 2 ms on each chunk, and counts the rows it was given
# when each call returned. Exit 0 if a stream returned before the consumer had every row (the bug), 1 if none did.
set -u
here=$(cd "$(dirname "$0")" && pwd)
V=$VOLTDB_HOME
C=$WORKDIR/earlycompletion
B=$PORT_BASE
rm -rf "$C"; mkdir -p "$C/classes"
cat > "$C/d.xml" <<X
<?xml version="1.0"?>
<deployment><cluster sitesperhost="8" kfactor="0"/><httpd enabled="false"/>
<systemsettings><snapshot streamingthrottle="0"/></systemsettings></deployment>
X
"$V/bin/voltdb" init --force ${VOLTDB_LICENSE:+--license "$VOLTDB_LICENSE"} -D "$C/n0" -C "$C/d.xml" > "$C/init.log" 2>&1
(nohup "$V/bin/voltdb" start -D "$C/n0" -H localhost:$((B + 2)) --client=$B --admin=$((B + 1)) --internal=$((B + 2)) \
    --zookeeper=$((B + 3)) --replication=$((B + 4)) --metrics=$((B + 5)) > "$C/start.log" 2>&1 & echo $! > "$C/n0.pid")
cleanup() { "$V/bin/voltadmin" shutdown --force -H localhost:$((B + 1)) > /dev/null 2>&1; sleep 5; kill -9 "$(cat "$C/n0.pid")" 2>/dev/null; }
trap cleanup EXIT
for _ in $(seq 1 120); do grep -q "Server completed initialization" "$C/start.log" 2>/dev/null && break; sleep 1; done
grep -q "Server completed initialization" "$C/start.log" || { echo "the node did not start"; tail -20 "$C/start.log"; exit 2; }
cp="$V/voltdb/*:$V/lib/*"
javac -cp "$cp" -d "$C/classes" "$here/streaming/EarlyCompletionRepro.java" || exit 2
# the client prints its streaming debug lines ("... ") to stderr
java -cp "$cp:$C/classes" EarlyCompletionRepro localhost:$B 5000 30 2> "$C/client.err" | grep -v SLF4J
exit "${PIPESTATUS[0]}"
