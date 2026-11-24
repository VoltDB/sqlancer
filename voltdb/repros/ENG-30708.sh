#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30708
-- summary: After an elastic join, @SwapTables then a truncating DELETE of one of the pair makes the next join's index snapshot fail and the join hang; the next snapshot crashes every node
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR. 3 nodes, k=1, 2 sites per host, command logging on (the
# default). Twin partitioned tables XA and XB shaped like SQLancer's SWAP tables, 60,000 rows each (enough that
# DELETE FROM truncates the table, swapping in a new table object, instead of deleting row by row).
#   1. add 2 nodes (elastic join; it leaves an elastic index stream on each table, which is never removed)
#   2. @SwapTables XA XB: because XA has that stream, the swap points each table's "table for stream indexing" at the
#      other (PersistentTable::swapTableState); nothing clears it
#   3. DELETE FROM XB (a truncate): the old XB object is replaced; XA still points at it (and keeps it alive)
#   4. remove the 2 nodes (elastic remove; succeeds)
#   5. add 2 nodes again: the index snapshot streams XA through the dead table, whose elastic context was never
#      activated: "Elastic streaming was invoked without proper activation", "Failed to take snapshot JOIN_..._index",
#      and the join stays at "Building index for data migration" for good
#   6. @SnapshotSave: "COW context activation is not allowed while elastic indexing is in progress", every original
#      node crashes ("Failed to activate snapshot stream on table XA"), the joining nodes stop on partition detection
# Exit 0 if the second join hangs after its index snapshot failed (the bug), 1 if it finishes, 2 on a setup failure.
# Without the swap (truncate only), or without the truncate (swap only), the second join finishes. The remove is not
# needed (adding 2 more nodes instead of steps 4 and 5 fails the same way); it is here to keep the cluster at 5 nodes.
# DROP TABLE XB and CREATE it again in place of the DELETE fails the same way. Reproduced in 5 of 5 local runs.
set -u
V=$VOLTDB_HOME; W=$WORKDIR/eng30708; P=$PORT_BASE
rm -rf "$W"; mkdir -p "$W"
export VOLTDB_OPTS="${VOLTDB_OPTS:-} -XX:ErrorFile=$W/hs_err_pid%p.log"
port() { echo $((P + 10 * $1 + $2)); }
hosts="localhost:$(port 0 2),localhost:$(port 1 2),localhost:$(port 2 2)"
printf '<?xml version="1.0"?>\n<deployment><cluster sitesperhost="2" kfactor="1"/><httpd enabled="false"/></deployment>\n' >"$W/d.xml"
start() { # start I [ARGS...]
    local i=$1; shift
    rm -rf "$W/n$i"
    "$V/bin/voltdb" init --force -D "$W/n$i" -C "$W/d.xml" ${VOLTDB_LICENSE:+--license "$VOLTDB_LICENSE"} >"$W/init$i.log" 2>&1 || exit 2
    (nohup "$V/bin/voltdb" start "$@" -D "$W/n$i" -H "$hosts" --client=$(port $i 0) --admin=$(port $i 1) \
        --internal=$(port $i 2) --zookeeper=$(port $i 3) --replication=$(port $i 4) --metrics=$(port $i 5) \
        >"$W/start$i.log" 2>&1 & echo $! >"$W/n$i.pid")
}
up() { grep -q "Server completed initialization" "$W/start$1.log" 2>/dev/null; }
alive() { kill -0 "$(cat "$W/n$1.pid" 2>/dev/null)" 2>/dev/null; }
cleanup() { for i in 0 1 2 3 4; do kill -9 "$(cat "$W/n$i.pid" 2>/dev/null)" 2>/dev/null; done; }
trap cleanup EXIT
for i in 0 1 2; do start $i -c 3; done
for _ in $(seq 1 180); do up 0 && up 1 && up 2 && break; sleep 1; done
up 0 && up 1 && up 2 || { echo "the cluster did not start"; exit 2; }
sql() { "$V/bin/sqlcmd" --port=$(port 0 0) 2>&1; }
logs() { cat "$W"/n[012]/voltdbroot/log/volt.log 2>/dev/null; }
# a finished join or remove: the coordinator removes this blocker at the end of each
ops() { logs | grep -c "Remove action blocker /db/action_blockers/elastic_join_no_rejoin"; }
waitops() { # waitops N SECS: until N operations have finished
    for _ in $(seq 1 "$2"); do [ "$(ops)" -ge "$1" ] && return 0; sleep 1; done; return 1
}
for t in XA XB; do
    echo "CREATE TABLE $t (k INTEGER NOT NULL, id SMALLINT NOT NULL, v VARCHAR(12), b VARBINARY(6), f FLOAT, d DECIMAL,
        t TIMESTAMP, PRIMARY KEY (k, id)); PARTITION TABLE $t ON COLUMN k;
        CREATE UNIQUE INDEX ${t}_KV ON $t (k, v); CREATE INDEX ${t}_FT ON $t (f, t);" | sql >"$W/ddl$t.log"
done
seq 1 60000 | awk '{print $1",1,v"$1",01,"$1".5,"$1",2026-01-01 00:00:00.000"}' >"$W/rows.csv"
for t in XA XB; do
    (cd "$W" && "$V/bin/csvloader" --port $(port 0 0) -f "$W/rows.csv" $t >"$W/csv$t.log" 2>&1)
done
echo "rows: $(echo 'SELECT COUNT(*) FROM XA; SELECT COUNT(*) FROM XB;' | sql | grep -E '^ *[0-9]+ *$' | tr -s ' \n' ' ')"
grow() { start 3 --add; start 4 --add; }
grow
waitops 1 240 || { echo "the first join did not finish"; exit 2; }
echo "join 1 finished"
echo "@SwapTables: $(echo 'exec @SwapTables XA XB;' | sql | grep -E '^ *[0-9]+ *$' | tr -d ' ') rows moved"
echo "DELETE FROM XB: $(echo 'DELETE FROM XB;' | sql | grep -o 'Returned [0-9]* rows' | head -1); truncations: $(logs | grep -c 'Transferring table stream after truncation of table XB')"
for _ in $(seq 1 60); do
    o=$("$V/bin/voltadmin" resize --test -H localhost:$(port 0 1) 2>&1 | tail -1)
    echo "$o" | grep -qE "Host Ids: \[[0-9]+, [0-9]+\]" && ! echo "$o" | grep -qE "\[(0|1|2)[],]" && break
    sleep 3
done
"$V/bin/voltadmin" resize -H localhost:$(port 0 1) >"$W/shrink.log" 2>&1
for _ in $(seq 1 240); do alive 3 || alive 4 || break; sleep 1; done
alive 3 || alive 4 && { echo "the remove did not finish: $(tail -1 "$W/shrink.log")"; exit 2; }
echo "remove finished"
sleep 3
n=$(ops)
grow
waitops $((n + 1)) 150; finished=$?
logs | grep -E "without proper activation|no streams to serialize|Failed to take snapshot" | cut -c1-160 | sort -u | head -6
if [ $finished -eq 0 ]; then
    echo "join 2 finished"
    echo "rows: $(echo 'SELECT COUNT(*) FROM XA; SELECT COUNT(*) FROM XB;' | sql | grep -E '^ *[0-9]+ *$' | tr -s ' \n' ' ')"
    exit 1
fi
echo "join 2 did not finish in 150 s; its last step: $(logs | grep -E 'ELASTIC: (Elastic add starting step|Building index)' | tail -1 | sed 's/.*ELASTIC: //')"
# the next snapshot finds elastic indexing still in progress
echo "exec @SnapshotSave '$W/snap' 'after' 1;" | sql >"$W/save.log" 2>&1
sleep 10
logs | grep -E "COW context activation|Failed to activate snapshot stream" | cut -c1-140 | sort -u | head -4
echo "after @SnapshotSave: $(for i in 0 1 2 3 4; do alive $i && echo -n "node $i up, " || echo -n "node $i down, "; done)"
exit 0
