#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30710
-- summary: A user removed by @SetYamlConfiguration can still log in when @UpdateClasses runs at the same time: the deployment drops the user, the catalog keeps it until the next catalog change
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR (and VOLTDB_LICENSE, if any). One node with security on (the
# administrator sqlancer). users/RemovedUserRepro.java: one client thread loads a one-class jar with @UpdateClasses and
# removes it, again and again; the main thread adds a user with @SetYamlConfiguration, removes it, checks that
# @GetYamlConfiguration no longer lists it, and logs in as it. Exit 0 if a removed user logs in (the bug), 1 if none
# does in 300 users. CONTROL=1 runs without @UpdateClasses (then no removed user logs in).
set -u
here=$(cd "$(dirname "$0")" && pwd)
V=$VOLTDB_HOME
C=$WORKDIR/removeduser
B=$PORT_BASE
rm -rf "$C"; mkdir -p "$C/src/repro" "$C/classes" "$C/jar"
cat > "$C/d.xml" <<X
<?xml version="1.0"?>
<deployment><cluster sitesperhost="4" kfactor="0"/><httpd enabled="false"/><security enabled="true" provider="hash"/>
<users><user name="sqlancer" password="sqlancer" roles="administrator"/></users></deployment>
X
"$V/bin/voltdb" init --force ${VOLTDB_LICENSE:+--license "$VOLTDB_LICENSE"} -D "$C/n0" -C "$C/d.xml" > "$C/init.log" 2>&1
(nohup "$V/bin/voltdb" start -D "$C/n0" -H localhost:$((B + 2)) --client=$B --admin=$((B + 1)) --internal=$((B + 2)) \
    --zookeeper=$((B + 3)) --replication=$((B + 4)) --metrics=$((B + 5)) > "$C/start.log" 2>&1 & echo $! > "$C/n0.pid")
cleanup() { "$V/bin/voltadmin" shutdown --force -H localhost:$((B + 1)) -u sqlancer -p sqlancer > /dev/null 2>&1; sleep 5; kill -9 "$(cat "$C/n0.pid")" 2>/dev/null; }
trap cleanup EXIT
for _ in $(seq 1 120); do grep -q "Server completed initialization" "$C/start.log" 2>/dev/null && break; sleep 1; done
grep -q "Server completed initialization" "$C/start.log" || { echo "the node did not start"; tail -20 "$C/start.log"; exit 2; }
# the jar @UpdateClasses loads: one class, repro.Helper
echo 'package repro; public class Helper { public static long f() { return 1; } }' > "$C/src/repro/Helper.java"
javac -d "$C/jar" "$C/src/repro/Helper.java" && jar cf "$C/repro.jar" -C "$C/jar" repro || exit 2
cp="$V/voltdb/*:$V/lib/*"
javac -cp "$cp" -d "$C/classes" "$here/users/RemovedUserRepro.java" || exit 2
java -cp "$cp:$C/classes" RemovedUserRepro localhost:$B "$C/repro.jar" 300 ${CONTROL:+control} 2>&1 | grep -v SLF4J
exit "${PIPESTATUS[0]}"
