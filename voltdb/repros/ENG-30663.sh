#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30663
-- manual: a race: reproduced in 1 of 2 local runs (at the 2nd kill), and 1 of 3 without the join view's DML; run it with --only ENG-30663, more than once
-- summary: A surviving node crashes when another node is killed during a catalog update ("@UpdateCore's prepared catalog is null", or an EE abort in getStats())
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR. 3 nodes, k=1, 8 sites per host. A view over a join of a
# replicated and a partitioned table, with two clients inserting into and deleting from both; beside them, DDL clients
# each loop CREATE TABLE, CREATE INDEX and DROP TABLE on their own tables, while a random node is killed with kill -9 every KILL_EVERY seconds
# and rejoined, up to KILLS times. Exit 0 if a node crashes, a survivor or the one that just rejoined (the bug: "prepared catalog is null", or the EE's
# "getStats() called with ... an invalid locator"), 3 if the cluster wedges another way (a rejoin that does not finish
# in 600 s, as ENG-30612), 1 if every kill and rejoin goes through.
set -u
V=$VOLTDB_HOME; W=$WORKDIR/killddl; P=$PORT_BASE
KILLS=${KILLS:-60}; KILL_EVERY=${KILL_EVERY:-8}
rm -rf "$W"; mkdir -p "$W"
port() { echo $((P + 10 * $1 + $2)); }
hosts="localhost:$(port 0 2),localhost:$(port 1 2),localhost:$(port 2 2)"
cat >"$W/d.xml" <<XML
<?xml version="1.0"?>
<deployment>
    <cluster sitesperhost="8" kfactor="1"/>
    <httpd enabled="false"/>
    <commandlog enabled="true" synchronous="false"/>
</deployment>
XML
start() { # start I: (re)start node I; a restart rejoins
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
# client C: loops DDL on its own tables through node C % 3, on and on, through every node failure
ddl() {
    local c=$1 k=0
    while [ ! -e "$W/stop" ]; do
        k=$((k + 1))
        printf 'CREATE TABLE c%s_t%s (a INTEGER NOT NULL, b VARCHAR(16), c BIGINT, PRIMARY KEY (a));\nPARTITION TABLE c%s_t%s ON COLUMN a;\nCREATE INDEX c%s_i%s ON c%s_t%s (b, c);\nCREATE VIEW c%s_v%s (b, n) AS SELECT b, COUNT(*) FROM c%s_t%s GROUP BY b;\nDROP TABLE c%s_t%s IF EXISTS CASCADE;\n' \
            $c $k $c $k $c $k $c $k $c $k $c $k $c $k |
            "$V/bin/sqlcmd" --servers=localhost --port=$(port $((c % 3)) 0) --stop-on-error=false >/dev/null 2>&1
    done
}
printf 'CREATE TABLE jr (a VARBINARY(16) NOT NULL, b VARCHAR(16) NOT NULL, PRIMARY KEY (a, b));\nCREATE TABLE jp (k BIGINT NOT NULL, a VARBINARY(16) NOT NULL, PRIMARY KEY (a, k));\nPARTITION TABLE jp ON COLUMN k;\nCREATE VIEW jv (n, m) AS SELECT COUNT(*), COUNT(jp.k) FROM jp JOIN jr ON jp.a >= jr.a;\nCREATE VIEW jv1 (b, n) AS SELECT b, COUNT(*) FROM jr GROUP BY b;\n' | "$V/bin/sqlcmd" --port=$(port 0 0) >/dev/null 2>&1
dml() {
    local c=$1 k=0
    while [ ! -e "$W/stop" ]; do
        k=$((k + 1))
        { for j in 1 2 3 4 5; do printf "INSERT INTO jr VALUES (x'%02X', 'b%s');\nINSERT INTO jp VALUES (%s, x'%02X');\n" $(((k * 5 + j) % 256)) $((k % 7)) $((k * 5 + j)) $(((k + j) % 256)); done
          printf "DELETE FROM jr WHERE a <> x'AE' AND b LIKE 'b%s';\nDELETE FROM jp WHERE k %% 3 = %s;\n" $((k % 7)) $((k % 3)); } |
            "$V/bin/sqlcmd" --servers=localhost --port=$(port $((c % 3)) 0) --stop-on-error=false >/dev/null 2>&1
    done
}
for c in 0 1; do dml $c & done
for c in $(seq 0 $((${CLIENTS:-4} - 1))); do ddl $c & done
# a survivor's crash: either signature, in its volt.log or its start log (the EE's abort is only in the latter)
SIG="prepared catalog is null|invalid locator|libc\+\+abi|encountered an unexpected error and will die|A fatal error has been detected|Could not find Index"
crashed() { grep -lE "$SIG" "$W"/n*/voltdbroot/log/volt.log "$W"/start*.log 2>/dev/null | head -1; }
for n in $(seq 1 "$KILLS"); do
    sleep "$KILL_EVERY"
    i=$((RANDOM % 3))
    kill -9 "$(cat "$W/n$i.pid")"
    echo "$(date '+%H:%M:%S') kill $n: node $i" | tee -a "$W/kills.log"
    while nc -z localhost "$(port $i 0)" 2>/dev/null; do sleep 0.5; done
    sleep 5
    f=$(crashed); if [ -n "$f" ]; then echo "after kill $n: $f"; grep -m1 -E "$SIG" "$f" | cut -c1-200; exit 0; fi
    start $i $n
    for _ in $(seq 1 600); do
        up $i $n && break
        f=$(crashed); [ -n "$f" ] && break
        sleep 1
    done
    f=$(crashed); if [ -n "$f" ]; then echo "after kill $n: $f"; grep -m1 -E "$SIG" "$f" | cut -c1-200; exit 0; fi
    up $i $n || { echo "kill $n: node $i did not rejoin within 600 s"; grep -h "FATAL" "$W"/n*/voltdbroot/log/volt.log | head -3 | cut -c1-200; exit 3; }
    # "Server completed initialization" comes before the rejoin's data transfer ends; killing another node before
    # that loses partitions at k=1
    for _ in $(seq 1 600); do
        [ "$(grep -c "Node rejoin completed" "$W/n$i/voltdbroot/log/volt.log" 2>/dev/null)" -ge "$(grep -c "kill [0-9]*: node $i\$" "$W/kills.log")" ] && break
        sleep 1
    done
    echo "$(date '+%H:%M:%S') rejoin $n: node $i"
done
echo "$KILLS kills and rejoins, no crash"
exit 1
