#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30895
-- summary: sqlcmd --output-format=csv writes every BOOLEAN as an empty field (TRUE, FALSE and NULL alike)
HEADER
# Starts its own one-node server on PORT_BASE+0..5 and prints a BOOLEAN NULL, TRUE and FALSE with the kit's sqlcmd in
# csv (VoltTableUtil.toCSVWriter). Exit 0 if the three fields are empty (the bug), 1 if they are \N, true and false,
# 2 on error or anything else.
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
CREATE TABLE B (id INTEGER NOT NULL PRIMARY KEY, b BOOLEAN);
INSERT INTO B VALUES (0, NULL);
INSERT INTO B VALUES (1, TRUE);
INSERT INTO B VALUES (2, FALSE);
SQL
out=$($sqlcmd --output-format=csv --output-skip-metadata --query="SELECT id, b FROM B ORDER BY id;" 2>&1) || { echo "$out"; exit 2; }
echo "$out"
bug=$(printf '"0",\n"1",\n"2",')
fixed=$(printf '"0","\\N"\n"1","true"\n"2","false"')
[ "$out" = "$bug" ] && exit 0
[ "$out" = "$fixed" ] && exit 1
exit 2
