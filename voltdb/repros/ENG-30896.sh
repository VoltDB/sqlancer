#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30896
-- summary: sqlcmd --output-format=csv misprints a TIMESTAMP before 1970 whose microseconds are not whole milliseconds: 01:42:38.818363 as 01:42:38.819-637
HEADER
# Starts its own one-node server on PORT_BASE+0..5 and prints three TIMESTAMPs before 1970 with the kit's sqlcmd in
# csv (VoltTableUtil.toCSVWriter). Exit 0 if they come out as the next millisecond, a minus sign and the microseconds
# (the bug), 1 if they are printed as they are, 2 on error or anything else.
: "${VOLTDB_HOME:?}" "${PORT_BASE:?}" "${WORKDIR:?}"
p() { echo $((PORT_BASE + $1)); }
mkdir -p "$WORKDIR"
printf '<?xml version="1.0"?>\n<deployment>\n    <cluster sitesperhost="4" kfactor="0"/>\n    <httpd enabled="false"/>\n</deployment>\n' \
    >"$WORKDIR/deployment.xml"
"$VOLTDB_HOME/bin/voltdb" init --force ${VOLTDB_LICENSE:+--license "$VOLTDB_LICENSE"} -D "$WORKDIR/node" -C "$WORKDIR/deployment.xml" >"$WORKDIR/init.log" 2>&1 || exit 2
nohup "$VOLTDB_HOME/bin/voltdb" start -D "$WORKDIR/node" -H "localhost:$(p 2)" --client="$(p 0)" --admin="$(p 1)" \
    --internal="$(p 2)" --zookeeper="$(p 3)" --replication="$(p 4)" --metrics="$(p 5)" >"$WORKDIR/start.log" 2>&1 &
pid=$!
stop() {
    "$VOLTDB_HOME/bin/voltadmin" shutdown --force -H "localhost:$(p 1)" >/dev/null 2>&1 &
    local s=$!
    for _ in $(seq 1 30); do kill -0 $s 2>/dev/null || break; sleep 1; done
    kill -9 $s $pid 2>/dev/null
    wait $pid 2>/dev/null
}
trap stop EXIT
for _ in $(seq 1 180); do
    grep -q "Server completed initialization" "$WORKDIR/start.log" 2>/dev/null && break
    kill -0 $pid 2>/dev/null || { cat "$WORKDIR/start.log"; exit 2; }
    sleep 1
done
grep -q "Server completed initialization" "$WORKDIR/start.log" || { echo "server did not start"; exit 2; }
# the kit's bin first: its dbenv prints a notice on stdout when another voltdb comes first on PATH
export PATH="$VOLTDB_HOME/bin:$PATH"
sqlcmd="$VOLTDB_HOME/bin/sqlcmd --servers=localhost:$(p 0)"
$sqlcmd >"$WORKDIR/setup.log" 2>&1 <<'SQL' || { cat "$WORKDIR/setup.log"; exit 2; }
CREATE TABLE T (id INTEGER NOT NULL PRIMARY KEY, ts TIMESTAMP);
INSERT INTO T VALUES (1, '1866-11-23 01:42:38.818363');
INSERT INTO T VALUES (2, '1969-12-31 23:59:59.999999');
INSERT INTO T VALUES (3, '1969-12-31 23:59:59.000001');
SQL
out=$($sqlcmd --output-format=csv --output-skip-metadata --query="SELECT id, ts FROM T ORDER BY id;" 2>&1) || { echo "$out"; exit 2; }
echo "$out"
bug=$(printf '"1","1866-11-23 01:42:38.819-637"\n"2","1970-01-01 00:00:00.000-01"\n"3","1969-12-31 23:59:59.001-999"')
fixed=$(printf '"1","1866-11-23 01:42:38.818363"\n"2","1969-12-31 23:59:59.999999"\n"3","1969-12-31 23:59:59.000001"')
[ "$out" = "$bug" ] && exit 0
[ "$out" = "$fixed" ] && exit 1
exit 2
