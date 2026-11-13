#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30680
-- summary: A DELAY task on the database runs in two scheduling chains after the MP leader's node is killed while its procedure is in flight
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR. 3 nodes, k=1, 4 sites per host, command log on. A task ON SCHEDULE
# DELAY 20 MILLISECONDS on the database runs a procedure that inserts one row (its transaction's time), so one of its
# transactions is nearly always in flight. Then the node that leads the multi-partition initiator is killed with
# kill -9 and rejoined. A DELAY task starts its next run at least 20 ms after the last one ends, so two of its rows are
# never less than 20 ms apart. Exit 0 if, after the rejoin, more than a quarter of the gaps between successive rows are
# under 20 ms (a second chain), 1 if not; it also reports whether ALTER TASK DISABLE stops them all.
set -u
V=$VOLTDB_HOME; W=$WORKDIR/taskmpi; P=$PORT_BASE
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
sql() { "$V/bin/sqlcmd" --servers=localhost --port=$(port $1 0) --stop-on-error=false 2>/dev/null; }
printf "CREATE TABLE r (k BIGINT NOT NULL, ts TIMESTAMP);\nCREATE PROCEDURE p AS INSERT INTO r VALUES (SINCE_EPOCH(MICROS, NOW), NOW);\nCREATE TASK t ON SCHEDULE DELAY 20 MILLISECONDS PROCEDURE p ON ERROR LOG;\n" |
    sql 0 >"$W/setup.log"
# the share of gaps under 20 ms between the rows of the last 5 s, through node $1
gaps() {
    printf "SELECT ts FROM r WHERE ts > DATEADD(MILLISECOND, -5000, NOW) ORDER BY ts;\n" | sql "$1" |
        grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9:.]+' | python3 -c '
import sys, datetime
ts = [datetime.datetime.fromisoformat(l.strip()) for l in sys.stdin if l.strip()]
g = [(b - a).total_seconds() * 1000 for a, b in zip(ts, ts[1:])]
print(len(ts), sum(1 for x in g if x < 20))'
}
sleep 6
read -r rows short <<<"$(gaps 0)"
echo "before the kill: $rows rows in 5 s, $short gaps under 20 ms"
# the host of the multi-partition initiator's leader (partition 16383)
mp=$(printf "exec @Statistics TOPO 0;\n" | sql 0 | awk '$1 == 16383 { print $3 }' | grep -oE '^[0-9]+' | head -1)
echo "the MP leader is on host ${mp:-?}"
case "$mp" in 0|1|2) victim=$mp ;; *) victim=1 ;; esac
kill -9 "$(cat "$W/n$victim.pid")"
while nc -z localhost "$(port $victim 0)" 2>/dev/null; do sleep 0.5; done
sleep 3
start $victim 1
other=$(( (victim + 1) % 3 ))
for _ in $(seq 1 300); do grep -q "Node rejoin completed" "$W/n$victim/voltdbroot/log/volt.log" 2>/dev/null && break; sleep 1; done
sleep 6
read -r rows2 short2 <<<"$(gaps $other)"
echo "after killing host $victim and its rejoin: $rows2 rows in 5 s, $short2 gaps under 20 ms"
printf "exec @Statistics TASK 0;\n" | sql $other | grep -E 'TASK_NAME|DATABASE'
printf "ALTER TASK t DISABLE;\n" | sql $other >/dev/null
sleep 2
n1=$(printf "SELECT COUNT(*) FROM r;\n" | sql $other | grep -E '^ *[0-9]+ *$' | tr -d ' ')
sleep 3
n2=$(printf "SELECT COUNT(*) FROM r;\n" | sql $other | grep -E '^ *[0-9]+ *$' | tr -d ' ')
echo "after ALTER TASK t DISABLE: $n1 rows, 3 s later $n2"
[ "${rows2:-0}" -gt 0 ] && [ $(( ${short2:-0} * 4 )) -gt "$rows2" ] && exit 0
exit 1
