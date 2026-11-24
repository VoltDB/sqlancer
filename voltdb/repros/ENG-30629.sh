#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30629
-- summary: File export target retries a block for good when its output file name is taken, logging a stack trace each time and never exporting the block's rows
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR. Two nodes on this host whose file export target shares one
# absolute outdir; both export a partitioned stream within the same second, so they name the same file, and the second
# loops. Exit 0 if its "already exists" errors keep growing (the bug), 1 if they do not (fixed), 2 on a setup error.
V=$VOLTDB_HOME
C=$WORKDIR/eng30629
P=$PORT_BASE
rm -rf "$C"; mkdir -p "$C/export"
cat >"$C/d.xml" <<XML
<?xml version="1.0"?>
<deployment>
    <cluster sitesperhost="2" kfactor="0"/>
    <httpd enabled="false"/>
    <export>
        <configuration target="t" enabled="true" type="file">
            <property name="type">csv</property>
            <property name="nonce">n</property>
            <property name="outdir">$C/export</property>
            <property name="period">1</property>
        </configuration>
    </export>
</deployment>
XML
stop() { for i in 0 1; do kill -9 "$(cat "$C/n$i.pid" 2>/dev/null)" 2>/dev/null; done; }
trap stop EXIT
for i in 0 1; do
    b=$((P + 10 * i))
    "$V/bin/voltdb" init --force ${VOLTDB_LICENSE:+--license "$VOLTDB_LICENSE"} -D "$C/n$i" -C "$C/d.xml" >"$C/init$i.log" 2>&1 || exit 2
    "$V/bin/voltdb" start -D "$C/n$i" -c 2 -H "localhost:$((P + 2)),localhost:$((P + 12))" --client=$b --admin=$((b + 1)) \
        --internal=$((b + 2)) --zookeeper=$((b + 3)) --replication=$((b + 4)) --metrics=$((b + 5)) >"$C/start$i.log" 2>&1 &
    echo $! >"$C/n$i.pid"
done
up=false
for _ in $(seq 1 120); do
    grep -q "Server completed initialization" "$C/n0/voltdbroot/log/volt.log" 2>/dev/null && { up=true; break; }
    sleep 1
done
$up || exit 2
echo "CREATE STREAM s PARTITION ON COLUMN k EXPORT TO TARGET t (k INTEGER NOT NULL, v INTEGER);" |
    "$V/bin/sqlcmd" --port="$P" >"$C/ddl.out" 2>&1 || exit 2
for k in $(seq 1 40); do echo "INSERT INTO s VALUES ($k, $k);"; done | "$V/bin/sqlcmd" --port="$P" >/dev/null 2>&1
count() { cat "$C"/n*/voltdbroot/log/volt.log | grep -c "Output file for next period already exists"; }
sleep 10
a=$(count)
sleep 5
b=$(count)
echo "already-exists errors after 10 s: $a; after 15 s: $b"
stop
# the looping node's log grows by tens of MB a second; drop it before the runner keeps anything
rm -rf "$C"/n*/voltdbroot/log
[ "$b" -gt "$a" ] && exit 0
exit 1
