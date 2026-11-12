#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30678
-- summary: After a node rejoins, @SnapshotRestore with duplicatesPath leaves the duplicate rows out of the table and writes none of them to the duplicates files
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR. 3 nodes, k=1, 4 sites per host, command log on. A replicated table R
# with 20 rows and a native snapshot of it alone (pathType SNAP_AUTO). Each round restores R, with a duplicates path,
# into R as it is (every saved row a duplicate), and counts the distinct rows the nodes' duplicates files got: 20 are
# expected. Between rounds a random node is killed and rejoined. Exit 0 if a round's duplicates are missing (after
# 70 s), 1 if every round's arrive.
set -u
V=$VOLTDB_HOME; W=$WORKDIR/dupsrejoin; P=$PORT_BASE
ROUNDS=${ROUNDS:-6}
rm -rf "$W"; mkdir -p "$W"
export VOLTDB_OPTS="${VOLTDB_OPTS:-} -XX:ErrorFile=$W/hs_err_pid%p.log"
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
start() {
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
cleanup() { for i in 0 1 2; do kill -9 "$(cat "$W/n$i.pid" 2>/dev/null)" 2>/dev/null; done; }
trap cleanup EXIT
sql() { "$V/bin/sqlcmd" --servers=localhost --port=$(port $1 0) --stop-on-error=false; }
{
    printf 'CREATE TABLE r (k INTEGER NOT NULL, v VARCHAR(16), PRIMARY KEY (k));\n'
    for k in $(seq 1 20); do printf "INSERT INTO r VALUES (%s, 'v%s');\n" $k $k; done
    printf "exec @SnapshotSave '{\"path\":\"\",\"pathType\":\"SNAP_AUTO\",\"nonce\":\"s1\",\"block\":true,\"format\":\"native\",\"tables\":[\"R\"]}';\n"
} | sql 0 >"$W/setup.log" 2>&1
dups() { cat "$W"/n*/voltdbroot/snapshots/R-duplicates-*.csv 2>/dev/null | sort -u | grep -c .; }
for n in $(seq 1 "$ROUNDS"); do
    rm -f "$W"/n*/voltdbroot/snapshots/R-duplicates-*.csv
    printf "exec @SnapshotRestore '{\"path\":\"\",\"pathType\":\"SNAP_AUTO\",\"nonce\":\"s1\",\"tables\":[\"R\"],\"duplicatesPath\":\"dups\"}';\n" |
        sql $((n % 3)) >>"$W/restore.log" 2>&1
    got=0
    for _ in $(seq 1 70); do got=$(dups); [ "$got" -ge 20 ] && break; sleep 1; done
    echo "$(date '+%H:%M:%S') round $n (restore through node $((n % 3))): $got of 20 duplicates in the files"
    if [ "$got" -lt 20 ]; then exit 0; fi
    i=$((RANDOM % 3))
    kill -9 "$(cat "$W/n$i.pid")"
    echo "$(date '+%H:%M:%S') kill: node $i"
    while nc -z localhost "$(port $i 0)" 2>/dev/null; do sleep 0.5; done
    sleep 3
    start $i $n
    k=$(grep -c "kill: node $i" "$W/kills.log" 2>/dev/null); echo "kill: node $i" >>"$W/kills.log"
    for _ in $(seq 1 300); do
        [ "$(grep -c "Node rejoin completed" "$W/n$i/voltdbroot/log/volt.log" 2>/dev/null)" -gt "${k:-0}" ] && break
        sleep 1
    done
    echo "$(date '+%H:%M:%S') rejoined: node $i"
done
echo "every round's duplicates arrived"
exit 1
