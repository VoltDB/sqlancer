#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30711
-- summary: @UpdateClasses restarts a task that ON ERROR STOP stopped: it runs its procedure again
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR (and VOLTDB_LICENSE, if any). One node. A task runs a procedure
# whose second run fails on a primary key, ON ERROR STOP, every 200 ms; once @Statistics TASK shows it ERROR,
# @UpdateClasses loads a one-class jar. Exit 0 if the task then runs its procedure again (a second failure in the log;
# the bug), 1 if it stays stopped.
# CONTROL=1 makes an ordinary DDL change (CREATE TABLE) instead of @UpdateClasses.
set -u
V=$VOLTDB_HOME
C=$WORKDIR/taskrevive
B=$PORT_BASE
rm -rf "$C"; mkdir -p "$C/src/repro" "$C/jar"
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
SQL="$V/bin/sqlcmd --port=$B"
state() { echo "exec @Statistics TASK 0;" | $SQL 2>/dev/null | grep -E "\bXT\b" | grep -oE "RUNNING|ERROR|DISABLED|PAUSED" | head -1; }
echo "CREATE TABLE XS (k BIGINT NOT NULL, t VARCHAR(20), PRIMARY KEY (k));
CREATE PROCEDURE XU AS INSERT INTO XS VALUES (?, ?);
CREATE TASK XT ON SCHEDULE EVERY 200 MILLISECONDS PROCEDURE XU WITH (1, 'XT') ON ERROR STOP;" | $SQL > /dev/null 2>&1
for _ in $(seq 1 50); do [ "$(state)" = ERROR ] && break; sleep 0.2; done
echo "before: state $(state), rows $(echo 'SELECT COUNT(*) FROM XS;' | $SQL 2>/dev/null | grep -E '^ *[0-9]+ *$' | tr -d ' ')"
if [ -n "${CONTROL:-}" ]; then
  echo "CREATE TABLE OTHER (k BIGINT);" | $SQL > /dev/null 2>&1; echo "a DDL change (CREATE TABLE)"
else
  echo 'package repro; public class Helper { public static long f() { return 1; } }' > "$C/src/repro/Helper.java"
  javac -d "$C/jar" "$C/src/repro/Helper.java" && jar cf "$C/repro.jar" -C "$C/jar" repro || exit 2
  echo "load classes $C/repro.jar;" | $SQL 2>&1 | grep -v "^Warning\|^    /\|^  But\|^$" | tail -1
fi
sleep 2
after=$(state)
echo "after: state $after, rows $(echo 'SELECT COUNT(*) FROM XS;' | $SQL 2>/dev/null | grep -E '^ *[0-9]+ *$' | tr -d ' ')"
grep -h "XT" "$C/n0/voltdbroot/log/volt.log" | grep -i "task\|start\|stop" | cut -c1-200 | tail -5
runs=$(grep -c "XT: Procedure XU with parameters" "$C/n0/voltdbroot/log/volt.log")
[ "$runs" -gt 1 ] && { echo "the stopped task ran its procedure again: $runs failed runs (the bug)"; exit 0; }
echo "the stopped task stayed stopped: $runs failed run"
exit 1
