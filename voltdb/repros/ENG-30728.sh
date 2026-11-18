#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30728
-- summary: A deployment change by @SetYamlConfiguration resets the metrics system's procedure counts (voltdb_procedure_invoked_total), which @Statistics PROCEDURE keeps
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR (and VOLTDB_LICENSE, if any). One node with the metrics system on
# (<metrics enabled="true" interval="1s"/>). A procedure P is called 5 times; then @SetYamlConfiguration changes
# deployment.systemsettings.query.timeout, which touches no procedure. Exit 0 if /metrics' voltdb_procedure_invoked_total
# of P then falls below @Statistics PROCEDURE's invocations (the bug), 1 if the two still agree.
set -u
V=$VOLTDB_HOME
C=$WORKDIR/metricsreset
B=$PORT_BASE
rm -rf "$C"; mkdir -p "$C"
cat > "$C/d.xml" <<X
<?xml version="1.0"?>
<deployment><cluster sitesperhost="4" kfactor="0"/><httpd enabled="false"/><metrics enabled="true" interval="1s"/></deployment>
X
"$V/bin/voltdb" init --force ${VOLTDB_LICENSE:+--license "$VOLTDB_LICENSE"} -D "$C/n0" -C "$C/d.xml" > "$C/init.log" 2>&1
(nohup "$V/bin/voltdb" start -D "$C/n0" -H localhost:$((B + 2)) --client=$B --admin=$((B + 1)) --internal=$((B + 2)) \
    --zookeeper=$((B + 3)) --replication=$((B + 4)) --metrics=$((B + 5)) > "$C/start.log" 2>&1 & echo $! > "$C/n0.pid")
cleanup() { "$V/bin/voltadmin" shutdown --force -H localhost:$((B + 1)) > /dev/null 2>&1; sleep 5; kill -9 "$(cat "$C/n0.pid")" 2>/dev/null; }
trap cleanup EXIT
for _ in $(seq 1 120); do grep -q "Server completed initialization" "$C/start.log" 2>/dev/null && break; sleep 1; done
grep -q "Server completed initialization" "$C/start.log" || { echo "the node did not start"; tail -20 "$C/start.log"; exit 2; }
SQL="$V/bin/sqlcmd --port=$B --stop-on-error=false"
metric() {
  curl -s "localhost:$((B + 5))/metrics" | grep '^voltdb_procedure_invoked_total' | grep 'procedure_name="P"' \
    | awk '{s += $(NF - 1)} END {print s + 0}'
}
statistic() {
  echo "exec @Statistics PROCEDURE 0;" | $SQL 2>/dev/null | awk '$6 == "P" {s += $7} END {print s + 0}'
}
echo "CREATE TABLE T (k INTEGER NOT NULL, PRIMARY KEY (k)); PARTITION TABLE T ON COLUMN k;
CREATE PROCEDURE P PARTITION ON TABLE T COLUMN k AS INSERT INTO T VALUES (?);" | $SQL > /dev/null 2>&1
for i in 1 2 3 4 5; do echo "exec P $i;"; done | $SQL > /dev/null 2>&1
sleep 5
echo "after 5 calls of P: /metrics $(metric), @Statistics PROCEDURE $(statistic)"
echo "exec @SetYamlConfiguration '' 'deployment.systemsettings.query.timeout=20000';" | $SQL > /dev/null 2>&1
sleep 5
m=$(metric); s=$(statistic)
echo "after @SetYamlConfiguration (query.timeout=20000): /metrics $m, @Statistics PROCEDURE $s"
echo "exec P 6;" | $SQL > /dev/null 2>&1
sleep 5
echo "after one more call: /metrics $(metric), @Statistics PROCEDURE $(statistic)"
[ "$m" -lt "$s" ] && { echo "the deployment change reset /metrics' count of P (the bug)"; exit 0; }
echo "/metrics and @Statistics still agree"
exit 1
