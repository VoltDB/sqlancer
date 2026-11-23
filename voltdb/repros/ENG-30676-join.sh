#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30676
-- summary: @SnapshotRestore of a table during an elastic join wedges the cluster for good (sites wait in SnapshotRestore.executePlanFragment, the join never completes)
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR. ENG-30676.sh's restore loop with elastic joins instead of
# rejoins: 3 nodes, k=1, 4 sites per host, command log on. A partitioned table T with 200 rows and one native snapshot
# of it alone (pathType SNAP_AUTO). Two clients loop "DELETE FROM t; @SnapshotRestore of T" while 2 nodes are added
# elastically and removed again (voltadmin resize), up to ROUNDS times. Exit 0 if the cluster wedges (a site's queue
# blocked over 60 s, or a join that does not finish in 300 s; every node's jstack is saved), 1 if every join and remove
# goes through. Found by milestone 56's local SNAPSHOT resize trial: the join's own snapshot was then never taken.
set -u
V=$VOLTDB_HOME; W=$WORKDIR/restoreelasticjoin; P=$PORT_BASE; ROUNDS=${ROUNDS:-5}
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
start() { # start I MODE
    "$V/bin/voltdb" init --force -D "$W/n$1" -C "$W/d.xml" ${VOLTDB_LICENSE:+--license "$VOLTDB_LICENSE"} >"$W/init$1.log" 2>&1 || exit 2
    (nohup "$V/bin/voltdb" start $2 -D "$W/n$1" -H "$hosts" --client=$(port $1 0) --admin=$(port $1 1) \
        --internal=$(port $1 2) --zookeeper=$(port $1 3) --replication=$(port $1 4) --metrics=$(port $1 5) \
        >"$W/start$1.log" 2>&1 &
        echo $! >"$W/n$1.pid")
}
up() { grep -q "Server completed initialization" "$W/start$1.log" 2>/dev/null; }
for i in 0 1 2; do start $i "-c 3"; done
for _ in $(seq 1 180); do up 0 && up 1 && up 2 && break; sleep 1; done
up 0 && up 1 && up 2 || exit 2
cleanup() {
    touch "$W/stop"
    for i in 0 1 2 3 4; do kill -9 "$(cat "$W/n$i.pid" 2>/dev/null)" 2>/dev/null; done
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
restorer() {
    local c=$1
    while [ ! -e "$W/stop" ]; do
        printf "DELETE FROM t;\nexec @SnapshotRestore '{\"path\":\"\",\"pathType\":\"SNAP_AUTO\",\"nonce\":\"s1\",\"tables\":[\"T\"]}';\n" |
            sql $((c % 3)) >>"$W/restore$c.log" 2>&1
    done
}
for c in 0 1; do restorer $c & done
wedged() { grep -h "taking a long time -- over [6-9][0-9] seconds\|taking a long time -- over [0-9][0-9][0-9] seconds" "$W"/n*/voltdbroot/log/volt.log 2>/dev/null | head -1; }
for r in $(seq 1 "$ROUNDS"); do
    sleep 5
    echo "$(date '+%H:%M:%S') round $r: adding 2 nodes"
    rm -rf "$W/n3" "$W/n4"; start 3 --add; start 4 --add
    for _ in $(seq 1 300); do up 3 && up 4 && break; [ -n "$(wedged)" ] && break; sleep 1; done
    w=$(wedged)
    if [ -n "$w" ] || ! { up 3 && up 4; }; then
        echo "round $r: ${w:-the join did not finish in 300 s}" | cut -c1-260
        grep -h "Elastic add starting step" "$W/n0/voltdbroot/log/volt.log" | tail -1 | cut -c1-160
        for j in 0 1 2 3 4; do jstack "$(cat "$W/n$j.pid")" >"$W/jstack$j.txt" 2>&1; done
        exit 0
    fi
    echo "$(date '+%H:%M:%S') round $r: joined"
    for _ in $(seq 1 60); do
        plan=$("$V/bin/voltadmin" resize --test -H "localhost:$(port 0 1)" 2>&1 | tail -1)
        echo "$plan" | grep -q "Host Ids: \[" && ! echo "$plan" | grep -qE "Host Ids: \[(.*[ ,\[])?(0|1|2)[],]" && break
        sleep 3
    done
    "$V/bin/voltadmin" resize -H "localhost:$(port 0 1)" >"$W/resize$r.log" 2>&1
    for _ in $(seq 1 300); do kill -0 "$(cat "$W/n3.pid")" 2>/dev/null || kill -0 "$(cat "$W/n4.pid")" 2>/dev/null || break; [ -n "$(wedged)" ] && break; sleep 1; done
    w=$(wedged); [ -n "$w" ] && { echo "round $r, during the remove: $w" | cut -c1-260; for j in 0 1 2 3 4; do jstack "$(cat "$W/n$j.pid")" >"$W/jstack$j.txt" 2>&1; done; exit 0; }
    echo "$(date '+%H:%M:%S') round $r: removed ($(tail -1 "$W/resize$r.log"))"
done
echo "$ROUNDS joins and removes, the cluster never wedged"
exit 1
