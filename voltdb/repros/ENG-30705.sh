#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30705
-- summary: A task that ON ERROR STOP has stopped runs again after the node that ran it fails
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR. 3 nodes, k=1, 4 sites per host. A task on the database, EVERY 200
# MILLISECONDS ... ON ERROR STOP, runs a procedure that inserts one fixed key into a table with a primary key: its first
# run inserts the row, its second fails on the key, and the task stops (@Statistics TASK: ERROR). The row is then
# deleted and the node that ran the task (its HOST_ID in @Statistics TASK) is killed with kill -9. Exit 0 if the row is
# back 15 s later, i.e. the stopped task ran again on another node (the bug); 1 if the table stays empty; 2 on an error.
set -u
V=$VOLTDB_HOME; W=$WORKDIR/taskrevive; P=$PORT_BASE
rm -rf "$W"; mkdir -p "$W"
export VOLTDB_OPTS="${VOLTDB_OPTS:-} -XX:ErrorFile=$W/hs_err_pid%p.log"
port() { echo $((P + 10 * $1 + $2)); }
hosts="localhost:$(port 0 2),localhost:$(port 1 2),localhost:$(port 2 2)"
printf '<?xml version="1.0"?>\n<deployment><cluster sitesperhost="4" kfactor="1"/><httpd enabled="false"/></deployment>\n' >"$W/d.xml"
for i in 0 1 2; do
    "$V/bin/voltdb" init --force -D "$W/n$i" -C "$W/d.xml" ${VOLTDB_LICENSE:+--license "$VOLTDB_LICENSE"} >"$W/init$i.log" 2>&1 || exit 2
    (nohup "$V/bin/voltdb" start -D "$W/n$i" -c 3 -H "$hosts" --client=$(port $i 0) --admin=$(port $i 1) \
        --internal=$(port $i 2) --zookeeper=$(port $i 3) --replication=$(port $i 4) --metrics=$(port $i 5) \
        >"$W/start$i.log" 2>&1 & echo $! >"$W/n$i.pid")
done
cleanup() { for i in 0 1 2; do kill -9 "$(cat "$W/n$i.pid" 2>/dev/null)" 2>/dev/null; done; }
trap cleanup EXIT
up() { grep -q "Server completed initialization" "$W/start$1.log" 2>/dev/null; }
for _ in $(seq 1 180); do up 0 && up 1 && up 2 && break; sleep 1; done
up 0 && up 1 && up 2 || exit 2
sql() { "$V/bin/sqlcmd" --port=$(port $1 0) --output-format=csv --output-skip-metadata 2>&1; }
printf '%s\n' "CREATE TABLE s (k BIGINT NOT NULL, PRIMARY KEY (k));" \
    "CREATE PROCEDURE ins AS INSERT INTO s VALUES (?);" \
    "CREATE TASK t ON SCHEDULE EVERY 200 MILLISECONDS PROCEDURE ins WITH (7) ON ERROR STOP;" | sql 0 >"$W/ddl.out"
sleep 3
stats=$(echo "exec @Statistics TASK 0;" | sql 0)
echo "$stats" >"$W/stats-before.csv"
# the row of the scheduler that ran the task: TASK_NAME t, state ERROR
hid=$(echo "$stats" | tr -d '"' | awk -F, 'toupper($5) == "T" && $6 == "ERROR" { print $2; exit }')
[ -n "$hid" ] || { echo "no task in ERROR state: $stats"; exit 2; }
echo "DELETE FROM s;" | sql 0 >/dev/null
# the node whose internal port the host id reports
victim=
info=$(echo "exec @SystemInformation OVERVIEW;" | sql 0 | tr -d '"')
for i in 0 1 2; do
    h=$(echo "$info" | awk -F, -v p="$(port $i 2)" '$2 == "INTERNALPORT" && $3 == p { print $1 }')
    [ "$h" = "$hid" ] && victim=$i
done
[ -n "$victim" ] || exit 2
kill -9 "$(cat "$W/n$victim.pid")"
echo "task stopped in ERROR on host $hid (node$victim); row deleted; node$victim killed"
sleep 15
other=$(((victim + 1) % 3))
rows=$(echo "SELECT COUNT(*) FROM s;" | sql $other | tr -d '" ' | grep -E '^[0-9]+$' | tail -1)
echo "exec @Statistics TASK 0;" | sql $other >"$W/stats-after.csv"
echo "rows in s 15 s after the failover: $rows; @Statistics TASK now: $(tr -d '"' <"$W/stats-after.csv" | awk -F, 'toupper($5) == "T" { print "host " $2 " " $6 }' | tr '\n' ' ')"
[ "${rows:-0}" -gt 0 ]
