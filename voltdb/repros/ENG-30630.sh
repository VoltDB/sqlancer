#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30630
-- summary: XDCR: rows written to a DR table that the other cluster has already dropped and recreated pause the DR stream for good, and nothing else replicates in that direction
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR. Two one-node XDCR clusters A (id 1) and B (id 2). A drops a DR
# table and recreates it (replicated, where it was partitioned); B writes into its old table, then does the same. A's
# consumer pauses on the old schema and, once the catalogs match, stays paused: neither the recreated table nor a new
# one gets B's rows. Exit 0 if A never gets them (the bug), 1 if it does (fixed), 2 on a setup error.
set -u
V=$VOLTDB_HOME; W=$WORKDIR/eng30630; P=$PORT_BASE
rm -rf "$W"; mkdir -p "$W"
A=$P; B=$((P + 100))
for c in A B; do
    base=$([ $c = A ] && echo $A || echo $B); other=$([ $c = A ] && echo $B || echo $A); id=$([ $c = A ] && echo 1 || echo 2)
    cat >"$W/$c.xml" <<XML
<?xml version="1.0"?>
<deployment>
    <cluster sitesperhost="2" kfactor="0"/>
    <httpd enabled="false"/>
    <dr id="$id" role="xdcr">
        <connection source="localhost:$((other + 4))"/>
    </dr>
</deployment>
XML
    "$V/bin/voltdb" init --force -D "$W/$c" -C "$W/$c.xml" ${VOLTDB_LICENSE:+--license "$VOLTDB_LICENSE"} >"$W/init$c.log" 2>&1 || exit 2
    "$V/bin/voltdb" start -D "$W/$c" -H "localhost:$((base + 2))" --client=$base --admin=$((base + 1)) --internal=$((base + 2)) \
        --zookeeper=$((base + 3)) --replication=$((base + 4)) --metrics=$((base + 5)) >"$W/start$c.log" 2>&1 &
    echo $! >"$W/$c.pid"
done
stop() { kill -9 "$(cat "$W/A.pid")" "$(cat "$W/B.pid")" 2>/dev/null; }
trap stop EXIT
for c in A B; do
    for _ in $(seq 1 120); do grep -q "Server completed initialization" "$W/$c/voltdbroot/log/volt.log" 2>/dev/null && break; sleep 1; done
done
sql() { "$V/bin/sqlcmd" --port="$1" 2>&1 | grep -vE "^Warning|^  But|^    /|^$|^WARNING"; }
count() { echo "SELECT COUNT(*) FROM t;" | "$V/bin/sqlcmd" --port="$1" --output-format=csv --output-skip-metadata 2>/dev/null | tail -1 | tr -d '"'; }
# 1. a partitioned DR table on both, with a row written on B that reaches A
for p in $A $B; do printf 'CREATE TABLE t (a INTEGER NOT NULL, b INTEGER);\nPARTITION TABLE t ON COLUMN a;\nDR TABLE t;\n' | sql $p >/dev/null; done
sleep 15
echo "INSERT INTO t VALUES (0, 0);" | sql $B >/dev/null
for _ in $(seq 1 30); do [ "$(count $A)" = 1 ] && break; sleep 1; done
echo "before: A has $(count $A) row(s) of B's 1"
# XDCR must work before anything else does
[ "$(count $A)" = 1 ] || exit 2
# 2. A drops it and recreates it, replicated this time; B, not there yet, writes into its old partitioned table, and
# only then does the same
printf 'DROP TABLE t IF EXISTS CASCADE;\nCREATE TABLE t (a INTEGER NOT NULL, b INTEGER);\nDR TABLE t;\n' | sql $A >/dev/null
for k in 1 2 3 4 5; do echo "INSERT INTO t VALUES ($k, $k);"; done | sql $B >/dev/null
sleep 3
printf 'DROP TABLE t IF EXISTS CASCADE;\nCREATE TABLE t (a INTEGER NOT NULL, b INTEGER);\nDR TABLE t;\n' | sql $B >/dev/null
for k in 6 7 8; do echo "INSERT INTO t VALUES ($k, $k);"; done | sql $B >/dev/null
# 3. the schemas match again; A should catch up
# B's new table holds 6, 7 and 8; the rows B wrote into its old table went with it
for _ in $(seq 1 60); do [ "$(count $A)" = 3 ] && break; sleep 1; done
n=$(count $A)
# a table that was never dropped: does anything else still get from B to A?
for p in $A $B; do printf 'CREATE TABLE u (a INTEGER NOT NULL, PRIMARY KEY (a));\nDR TABLE u;\n' | sql $p >/dev/null; done
sleep 5
echo "INSERT INTO u VALUES (1);" | sql $B >/dev/null
sleep 30
u=$(echo "SELECT COUNT(*) FROM u;" | "$V/bin/sqlcmd" --port=$A --output-format=csv --output-skip-metadata 2>/dev/null | tail -1 | tr -d '"')
echo "a new table u: A has $u of B's 1 row; t: $(count $A) of 3"
paused=$(echo "exec @Statistics DRCONSUMERPARTITION 0;" | "$V/bin/sqlcmd" --port=$A 2>/dev/null | grep -c " true ")
echo "after 60 s: A has $n row(s) of the 3 in B's new table; consumer partitions reporting true (covered/paused): $paused"
grep -h "Pausing this DR stream" "$W/A/voltdbroot/log/volt.log" | head -1 | cut -c1-220
[ "$n" != 3 ] && exit 0
exit 1
