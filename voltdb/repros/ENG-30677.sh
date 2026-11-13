#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30677
-- summary: Writes to the replicated side of a join view crash the server while @SnapshotRestore and DDL run on other tables (SIGSEGV in ReplicatedMaterializedViewHandler)
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR. 3 nodes, k=1, 4 sites per host, command log on. A view over a join
# of a replicated table R and an empty partitioned table P. At once: a client inserting into and deleting from R;
# a client looping a snapshot of another table S and its restore (each restore asks for a truncation snapshot); a
# client looping CREATE TABLE / DROP TABLE (catalog updates). Exit 0 if a node crashes, 1 if none does in DURATION s.
set -u
V=$VOLTDB_HOME; W=$WORKDIR/viewstress; P=$PORT_BASE
DURATION=${DURATION:-300}
rm -rf "$W"; mkdir -p "$W"
# a crashed server's JVM error report goes here, not into the directory the script was started from
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
for i in 0 1 2; do
    "$V/bin/voltdb" init --force -D "$W/n$i" -C "$W/d.xml" ${VOLTDB_LICENSE:+--license "$VOLTDB_LICENSE"} >"$W/init$i.log" 2>&1 || exit 2
    (nohup "$V/bin/voltdb" start -D "$W/n$i" -c 3 -H "$hosts" --client=$(port $i 0) --admin=$(port $i 1) \
        --internal=$(port $i 2) --zookeeper=$(port $i 3) --replication=$(port $i 4) --metrics=$(port $i 5) \
        >"$W/start$i.log" 2>&1 &
        echo $! >"$W/n$i.pid")
done
up() { grep -q "Server completed initialization" "$W/start$1.log" 2>/dev/null; }
for _ in $(seq 1 180); do up 0 && up 1 && up 2 && break; sleep 1; done
up 0 && up 1 && up 2 || exit 2
cleanup() {
    touch "$W/stop"
    for i in 0 1 2; do kill -9 "$(cat "$W/n$i.pid" 2>/dev/null)" 2>/dev/null; done
    kill $(jobs -p) 2>/dev/null
}
trap cleanup EXIT
sql() { "$V/bin/sqlcmd" --servers=localhost --port=$(port $1 0) --stop-on-error=false; }
{
    printf 'CREATE TABLE r (c0 INTEGER NOT NULL, PRIMARY KEY (c0));\n'
    printf 'CREATE TABLE p (c0 VARCHAR(4) NOT NULL, c2 BIGINT, c5 INTEGER, PRIMARY KEY (c0));\nPARTITION TABLE p ON COLUMN c0;\n'
    printf 'CREATE VIEW v (c0, c2, n, m) AS SELECT p.c0, p.c2, COUNT(*), COUNT(p.c5) FROM r JOIN p ON r.c0 < p.c5 GROUP BY p.c0, p.c2;\n'
    printf 'CREATE TABLE s (k INTEGER NOT NULL, PRIMARY KEY (k));\n'
    for k in $(seq 1 50); do printf "INSERT INTO s VALUES (%s);\n" $k; done
} | sql 0 >"$W/setup.log" 2>&1
writer() {
    local k=0
    while [ ! -e "$W/stop" ]; do
        k=$((k + 1))
        { for j in $(seq 1 20); do printf "UPSERT INTO r VALUES (%s);\n" $(((k * 20 + j) % 5000)); done
          printf "DELETE FROM r WHERE c0 %% 7 = %s;\n" $((k % 7)); } | sql 1 >/dev/null 2>&1
    done
}
snapper() {
    local n=0
    while [ ! -e "$W/stop" ]; do
        n=$((n + 1))
        { printf "exec @SnapshotSave '{\"path\":\"\",\"pathType\":\"SNAP_AUTO\",\"nonce\":\"s%s\",\"block\":true,\"format\":\"native\",\"tables\":[\"S\"]}';\n" $n
          # NO_RESTORE=1: saves only
          [ -n "${NO_RESTORE:-}" ] || printf "DELETE FROM s;\nexec @SnapshotRestore '{\"path\":\"\",\"pathType\":\"SNAP_AUTO\",\"nonce\":\"s%s\",\"tables\":[\"S\"]}';\n" $n; } |
            sql 2 >/dev/null 2>&1
    done
}
ddl() {
    local n=0
    while [ ! -e "$W/stop" ]; do
        n=$((n + 1))
        printf 'CREATE TABLE z%s (a INTEGER);\nDROP TABLE z%s;\n' $n $n | sql 0 >/dev/null 2>&1
    done
}
# NO_SNAP=1 or NO_DDL=1 leaves that client out
writer & writer &
[ -z "${NO_SNAP:-}" ] && snapper &
[ -z "${NO_DDL:-}" ] && ddl &
SIG="A fatal error has been detected|encountered an unexpected error and will die"
end=$((SECONDS + DURATION))
while [ $SECONDS -lt $end ]; do
    sleep 5
    f=$(grep -lE "$SIG" "$W"/start*.log 2>/dev/null | head -1)
    if [ -n "$f" ]; then
        echo "crash: $f"; grep -h -A1 "Problematic frame" "$W"/start*.log | head -4
        exit 0
    fi
    for i in 0 1 2; do kill -0 "$(cat "$W/n$i.pid")" 2>/dev/null || { echo "node $i died"; exit 0; }; done
done
echo "no crash in $DURATION s"
exit 1
