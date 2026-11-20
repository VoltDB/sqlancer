#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30681
-- summary: JDBC export to VoltDB with createtable delivers no rows: its tables have no primary key but rows go by UPSERT, and a FLOAT column fails the CREATE TABLE (DOUBLE PRECISION)
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR. Two one-node clusters: a sink, and a source whose JDBC export
# target writes into the sink (jdbc:voltdb, createtable at its default, true). The source exports a stream with a
# FLOAT column (SF) and one without (SN), a row each; after 60 s, exit 0 if the sink lacks either row (the bug: no table
# SF, and SN's row refused, "Unsupported UPSERT table without primary key"), 1 if it has both, 2 on a setup failure.
set -u
V=$VOLTDB_HOME; W=$WORKDIR/jdbcfloat; P=$PORT_BASE
rm -rf "$W"; mkdir -p "$W"
export VOLTDB_OPTS="${VOLTDB_OPTS:-} -XX:ErrorFile=$W/hs_err_pid%p.log"
port() { echo $((P + 10 * $1 + $2)); }
cat >"$W/sink.xml" <<XML
<?xml version="1.0"?>
<deployment><cluster sitesperhost="2" kfactor="0"/><httpd enabled="false"/></deployment>
XML
cat >"$W/source.xml" <<XML
<?xml version="1.0"?>
<deployment>
    <cluster sitesperhost="2" kfactor="0"/>
    <httpd enabled="false"/>
    <export>
        <configuration target="j" enabled="true" type="jdbc">
            <property name="jdbcurl">jdbc:voltdb://localhost:$(port 0 0)</property>
            <property name="jdbcuser">u</property>
            <property name="ignoregenerations">true</property>
            <property name="skipinternals">true</property>
        </configuration>
    </export>
</deployment>
XML
start() { # start N NAME
    "$V/bin/voltdb" init --force -D "$W/$2" -C "$W/$2.xml" ${VOLTDB_LICENSE:+--license "$VOLTDB_LICENSE"} >"$W/init-$2.log" 2>&1 || exit 2
    (nohup "$V/bin/voltdb" start -D "$W/$2" -H "localhost:$(port $1 2)" --client=$(port $1 0) --admin=$(port $1 1) \
        --internal=$(port $1 2) --zookeeper=$(port $1 3) --replication=$(port $1 4) --metrics=$(port $1 5) \
        >"$W/start-$2.log" 2>&1 &
        echo $! >"$W/$2.pid")
    for _ in $(seq 1 180); do grep -q "Server completed initialization" "$W/start-$2.log" 2>/dev/null && return; sleep 1; done
    exit 2
}
cleanup() { for n in sink source; do kill -9 "$(cat "$W/$n.pid" 2>/dev/null)" 2>/dev/null; done; }
trap cleanup EXIT
start 0 sink
start 1 source
sql() { "$V/bin/sqlcmd" --servers=localhost --port=$(port $1 0) --stop-on-error=false 2>&1; }
printf "CREATE STREAM sf PARTITION ON COLUMN k EXPORT TO TARGET j (k INTEGER NOT NULL, f FLOAT);\nCREATE STREAM sn PARTITION ON COLUMN k EXPORT TO TARGET j (k INTEGER NOT NULL, b BIGINT);\nINSERT INTO sf VALUES (1, 1.5);\nINSERT INTO sn VALUES (1, 2);\n" |
    sql 1 >"$W/source.out"
sleep 60
printf "SELECT COUNT(*) FROM SN;\nSELECT COUNT(*) FROM SF;\n" | sql 0 | tee "$W/sink.out"
grep -m1 -h -o 'DDL Error: "type not found[^"]*"' "$W"/source/voltdbroot/log/volt.log
grep -m1 -h -o 'Unsupported UPSERT table without primary key[^)]*' "$W"/source/voltdbroot/log/volt.log
[ "$(grep -cE '^ *1 *$' "$W/sink.out")" -eq 2 ] && exit 1
exit 0
