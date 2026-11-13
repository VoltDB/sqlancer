#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30683
-- summary: During a rolling upgrade from 16.0.0, @SetYamlConfiguration through an upgraded 16.1 node fails ("requires exactly two parameters")
-- manual: needs OLD_VOLTDB_HOME, a 16.0.0 kit, besides the kit under test
HEADER
# The runner's env: VOLTDB_HOME (the newer kit), OLD_VOLTDB_HOME (the release before it), PORT_BASE, WORKDIR. 3 nodes,
# k=1, started on OLD_VOLTDB_HOME; node 2 is upgraded in service (voltadmin stop, then voltdb start on VOLTDB_HOME, which
# rejoins it), as in a rolling upgrade. Then @SetYamlConfiguration changes elastic.duration through each node. Exit 0 if
# a call fails (the bug), 1 if each succeeds.
set -u
V=$VOLTDB_HOME; O=$OLD_VOLTDB_HOME; W=$WORKDIR/cfgmixed; P=$PORT_BASE
rm -rf "$W"; mkdir -p "$W"
export VOLTDB_OPTS="${VOLTDB_OPTS:-} -XX:ErrorFile=$W/hs_err_pid%p.log"
port() { echo $((P + 10 * $1 + $2)); }
hosts="localhost:$(port 0 2),localhost:$(port 1 2),localhost:$(port 2 2)"
printf '<?xml version="1.0"?>\n<deployment><cluster sitesperhost="4" kfactor="1"/><httpd enabled="false"/></deployment>\n' >"$W/d.xml"
start() { # start KIT I LOG
    (nohup "$1/bin/voltdb" start -D "$W/n$2" -c 3 -H "$hosts" --client=$(port $2 0) --admin=$(port $2 1) \
        --internal=$(port $2 2) --zookeeper=$(port $2 3) --replication=$(port $2 4) --metrics=$(port $2 5) \
        >"$W/$3" 2>&1 & echo $! >"$W/n$2.pid")
}
for i in 0 1 2; do
    "$O/bin/voltdb" init --force -D "$W/n$i" -C "$W/d.xml" ${VOLTDB_LICENSE:+--license "$VOLTDB_LICENSE"} >"$W/init$i.log" 2>&1 || exit 2
    start "$O" $i start$i.log
done
for _ in $(seq 1 180); do grep -q "Server completed initialization" "$W/start0.log" "$W/start1.log" "$W/start2.log" 2>/dev/null &&
    [ "$(grep -l "Server completed initialization" "$W"/start[012].log | wc -l)" -eq 3 ] && break; sleep 1; done
cleanup() { for i in 0 1 2; do kill -9 "$(cat "$W/n$i.pid" 2>/dev/null)" 2>/dev/null; done; }
trap cleanup EXIT
ip=$(echo "exec @SystemInformation OVERVIEW;" | "$V/bin/sqlcmd" --port=$(port 0 0) 2>/dev/null | awk '$2 == "IPADDRESS" { print $3; exit }')
pid=$(cat "$W/n2.pid")
for _ in $(seq 1 20); do
    "$V/bin/voltadmin" stop -H "localhost:$(port 0 1)" "$ip:$(port 2 2)" >>"$W/stop.log" 2>&1
    for _ in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break 2; sleep 1; done
done
start "$V" 2 upgrade2.log
for _ in $(seq 1 300); do grep -q "Server completed initialization" "$W/upgrade2.log" 2>/dev/null && break; sleep 1; done
grep -q "Server completed initialization" "$W/upgrade2.log" || { echo "node 2 did not rejoin on the new kit"; exit 2; }
echo "versions: $(echo "exec @SystemInformation OVERVIEW;" | "$V/bin/sqlcmd" --port=$(port 0 0) 2>/dev/null | awk '$2 == "VERSION" { print $1 ": " $3 }' | tr '\n' ' ')"
failed=0
for i in 0 1 2; do
    out=$(printf "exec @SetYamlConfiguration '' 'deployment.systemsettings.elastic.duration=$((100 + i))';\n" | "$V/bin/sqlcmd" --port=$(port $i 0) 2>&1 | grep -v '^Warning\|exists in your path\|But this script\|/bin$\|^ *$' | tail -2 | tr '\n' ' ')
    echo "through node $i: $out"
    echo "$out" | grep -q -i 'error\|requires' && failed=1
done
[ "$failed" = 1 ] && exit 0
exit 1
