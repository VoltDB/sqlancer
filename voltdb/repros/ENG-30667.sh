#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30667
-- summary: After a rejoin, a view without GROUP BY over a join of replicated tables holds its one row twice on the rejoined node
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR. 3 nodes, k=1, 2 sites per host. Two replicated tables, a view
# over their join without GROUP BY (one row: the count); node 2 is killed and rejoined, and the view is read through
# each node. Exit 0 if a read returns more than one row (the bug), 1 if every read returns one.
set -u
V=$VOLTDB_HOME; W=$WORKDIR/rejoinview; P=$PORT_BASE
rm -rf "$W"; mkdir -p "$W"
port() { echo $((P + 10 * $1 + $2)); }
hosts="localhost:$(port 0 2),localhost:$(port 1 2),localhost:$(port 2 2)"
cat >"$W/d.xml" <<XML
<?xml version="1.0"?>
<deployment>
    <cluster sitesperhost="${SITES:-2}" kfactor="${KFACTOR:-1}"/>
    <httpd enabled="false"/>
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
trap 'for i in 0 1 2; do kill -9 "$(cat "$W/n$i.pid" 2>/dev/null)" 2>/dev/null; done' EXIT
sql() { "$V/bin/sqlcmd" --port="$1" --stop-on-error=false 2>&1; }
printf '%s\n' \
    "CREATE TABLE a (x TIMESTAMP, y INTEGER);" \
    "CREATE TABLE b (d DATE, z INTEGER);" \
    "CREATE VIEW v (n, m) AS SELECT COUNT(*), MIN(b.d) FROM b JOIN a ON b.d <> a.x;" \
    "INSERT INTO a VALUES (NULL, 1);" | sql $(port 0 0) >"$W/setup.out"
count() { echo "SELECT * FROM v;" | sql "$(port $1 0)" | grep -E '^\(Returned [0-9]+ rows' | sed -E 's/^\(Returned ([0-9]+) rows.*/\1/'; }
echo "before: $(count 0) $(count 1) $(count 2) row(s) through nodes 0, 1, 2"
kill -9 "$(cat "$W/n2.pid")"
while nc -z localhost "$(port 2 0)" 2>/dev/null; do sleep 0.5; done
sleep 5
start 2 1
for _ in $(seq 1 300); do grep -q "Node rejoin completed" "$W/n2/voltdbroot/log/volt.log" 2>/dev/null && break; sleep 1; done
sleep 3
bad=0
for i in 0 1 2; do
    for _ in 1 2 3; do
        c=$(count $i)
        echo "after the rejoin, through node $i: $c row(s)"
        [ "${c:-0}" -gt 1 ] && bad=1
    done
done
[ $bad = 1 ] && exit 0
exit 1
