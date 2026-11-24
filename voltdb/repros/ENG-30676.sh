#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30676
-- summary: @SnapshotRestore of a table while a node rejoins wedges the cluster for good (sites wait in SnapshotRestore.executePlanFragment, the rejoin never completes)
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR. 3 nodes, k=1, 4 sites per host, command log on (VoltDB's
# defaults otherwise). A partitioned table T with 200 rows and one native snapshot of it alone, in each node's snapshot
# directory (pathType SNAP_AUTO). Two clients loop "DELETE FROM t; @SnapshotRestore of T", while a random node is
# killed with kill -9 every KILL_EVERY seconds and rejoined, up to KILLS times. Exit 0 if the cluster wedges (a site's
# queue blocked over 60 s, or a rejoin that does not finish in 300 s), 1 if every kill and rejoin goes through.
set -u
V=$VOLTDB_HOME; W=$WORKDIR/restorerejoin; P=$PORT_BASE
KILLS=${KILLS:-5}; KILL_EVERY=${KILL_EVERY:-10}
rm -rf "$W"; mkdir -p "$W"
port() { echo $((P + 10 * $1 + $2)); }
hosts="localhost:$(port 0 2),localhost:$(port 1 2),localhost:$(port 2 2)"
cat >"$W/d.xml" <<XML
<?xml version="1.0"?>
<deployment>
    <cluster sitesperhost="4" kfactor="1"/>
    <httpd enabled="false"/>
    <commandlog enabled="true" synchronous="false"/>
</deployment>
XML
start() { # start I N: (re)start node I; a restart rejoins
    (nohup "$V/bin/voltdb" start -D "$W/n$1" -c 3 -H "$hosts" --client=$(port $1 0) --admin=$(port $1 1) \
        --internal=$(port $1 2) --zookeeper=$(port $1 3) --replication=$(port $1 4) --metrics=$(port $1 5) \
        >"$W/start$1-$2.log" 2>&1 &
        echo $! >"$W/n$1.pid")
}
for i in 0 1 2; do
    "$V/bin/voltdb" init --force -D "$W/n$i" -C "$W/d.xml" ${VOLTDB_LICENSE:+--license "$VOLTDB_LICENSE"} >"$W/init$i.log" 2>&1 || exit 2
    start $i 0
done
up() { grep -q "Server completed initialization" "$W/start$1-$2.log" 2>/dev/null; }
for _ in $(seq 1 180); do up 0 0 && up 1 0 && up 2 0 && break; sleep 1; done
up 0 0 && up 1 0 && up 2 0 || exit 2
cleanup() {
    touch "$W/stop"
    for i in 0 1 2; do kill -9 "$(cat "$W/n$i.pid" 2>/dev/null)" 2>/dev/null; done
    kill $(jobs -p) 2>/dev/null
}
trap cleanup EXIT
sql() { "$V/bin/sqlcmd" --servers=localhost --port=$(port $1 0) --stop-on-error=false; }
{
    printf 'CREATE TABLE t (k INTEGER NOT NULL, v VARCHAR(16), PRIMARY KEY (k));\nPARTITION TABLE t ON COLUMN k;\n'
    for k in $(seq 1 200); do printf "INSERT INTO t VALUES (%s, 'v%s');\n" $k $k; done
    printf "exec @SnapshotSave '{\"path\":\"\",\"pathType\":\"SNAP_AUTO\",\"nonce\":\"s1\",\"block\":true,\"format\":\"native\",\"tables\":[\"T\"]}';\n"
} | sql 0 >"$W/setup.log" 2>&1
grep -q "SUCCESS" "$W/setup.log" || { echo "the snapshot failed"; exit 2; }
# client C: empties T and restores it from the snapshot, on and on, through node C % 3
restorer() {
    local c=$1
    while [ ! -e "$W/stop" ]; do
        printf "DELETE FROM t;\nexec @SnapshotRestore '{\"path\":\"\",\"pathType\":\"SNAP_AUTO\",\"nonce\":\"s1\",\"tables\":[\"T\"]}';\n" |
            sql $((c % 3)) >>"$W/restore$c.log" 2>&1
    done
}
for c in 0 1; do restorer $c & done
wedged() { grep -h "taking a long time -- over [6-9][0-9] seconds\|taking a long time -- over [0-9][0-9][0-9] seconds" "$W"/n*/voltdbroot/log/volt.log 2>/dev/null | head -1; }
for n in $(seq 1 "$KILLS"); do
    sleep "$KILL_EVERY"
    i=$((RANDOM % 3))
    kill -9 "$(cat "$W/n$i.pid")"
    echo "$(date '+%H:%M:%S') kill $n: node $i" | tee -a "$W/kills.log"
    while nc -z localhost "$(port $i 0)" 2>/dev/null; do sleep 0.5; done
    sleep 3
    start $i $n
    for _ in $(seq 1 300); do
        [ "$(grep -c "Node rejoin completed" "$W/n$i/voltdbroot/log/volt.log" 2>/dev/null)" -ge "$(grep -c "kill [0-9]*: node $i\$" "$W/kills.log")" ] && break
        w=$(wedged); [ -n "$w" ] && break
        sleep 1
    done
    w=$(wedged)
    if [ -n "$w" ]; then
        echo "after kill $n: $w" | cut -c1-260
        # every node's threads, for the ticket
        for j in 0 1 2; do jstack "$(cat "$W/n$j.pid")" >"$W/jstack$j.txt" 2>&1; done
        exit 0
    fi
    [ "$(grep -c "Node rejoin completed" "$W/n$i/voltdbroot/log/volt.log" 2>/dev/null)" -ge "$(grep -c "kill [0-9]*: node $i\$" "$W/kills.log")" ] ||
        { echo "kill $n: node $i did not rejoin within 300 s"; exit 0; }
    echo "$(date '+%H:%M:%S') rejoin $n: node $i"
done
echo "$KILLS kills and rejoins, the cluster never wedged"
exit 1
