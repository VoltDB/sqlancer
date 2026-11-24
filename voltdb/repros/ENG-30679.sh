#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30679
-- summary: XDCR silently diverges on an insert raced by an upsert and delete of the same key on the other cluster, with every conflict logged as consistent (C)
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR (and VOLTDB_LICENSE, if any). Starts an XDCR pair of 1-node
# clusters with run-sqlancer.sh --xdcr --keep-cluster (a 5-second SMOKE run), then replays xdcr/XdcrRepro.java's
# sequence through the two clusters 3 times, each on a fresh DR table. Exit 0 if the clusters differ on a table and
# the conflict log has no divergence (D) for it (the bug), 1 if they converge or the divergence is reported.
set -u
here=$(cd "$(dirname "$0")" && pwd)
W=$WORKDIR/xdcrdiverge; P=$PORT_BASE
rm -rf "$W"; mkdir -p "$W/classes"
"$here/../run-sqlancer.sh" --voltdb "$VOLTDB_HOME" ${VOLTDB_LICENSE:+--license "$VOLTDB_LICENSE"} --results "$W/run" \
    --nodes 1 --port-base "$P" --xdcr --oracle SMOKE --duration 5 --keep-cluster >"$W/run.log" 2>&1
cluster=$(ls -d "$W"/run/sqlancer-*/cluster 2>/dev/null | head -1)
[ -n "$cluster" ] || { echo "the XDCR pair did not start; run-sqlancer.sh said:"; tail -30 "$W/run.log"; exit 2; }
cleanup() { pkill -9 -f "$cluster/" 2>/dev/null; }
trap cleanup EXIT
cp="$VOLTDB_HOME/voltdb/*:$VOLTDB_HOME/lib/*"
javac -cp "$cp" -d "$W/classes" "$here/xdcr/XdcrRepro.java" || exit 2
# run-sqlancer.sh's ports: client of node 0 at PORT_BASE, of the XDCR peer's node 0 at PORT_BASE + 200
java -cp "$cp:$W/classes" XdcrRepro "$P" "$((P + 200))" "$cluster" 3 2>&1 | grep -v SLF4J
exit "${PIPESTATUS[0]}"
