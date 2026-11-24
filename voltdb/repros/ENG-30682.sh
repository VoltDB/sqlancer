#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30682
-- summary: A node that joins elastically while @SetYamlConfiguration changes are made misses some of them for good
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR. 3 nodes, k=1, 4 sites per host. @SetYamlConfiguration sets
# elastic.duration 100, elastic.throughput 1, flushinterval.minimum 2000, then back to 50, 2, 500; then 2 nodes are added
# elastically (voltdb init with the original deployment, voltdb start --add) while the settings are toggled between the
# two every 0.3 s; afterwards they are set to 50, 2, 500.
# Prints the settings as each node reports them; exit 0 if the nodes report different ones (the bug), 1 if not.
set -u
V=$VOLTDB_HOME; W=$WORKDIR/cfgresize; P=$PORT_BASE
rm -rf "$W"; mkdir -p "$W"
export VOLTDB_OPTS="${VOLTDB_OPTS:-} -XX:ErrorFile=$W/hs_err_pid%p.log"
port() { echo $((P + 10 * $1 + $2)); }
hosts="localhost:$(port 0 2),localhost:$(port 1 2),localhost:$(port 2 2)"
printf '<?xml version="1.0"?>\n<deployment><cluster sitesperhost="4" kfactor="1"/><httpd enabled="false"/></deployment>\n' >"$W/d.xml"
start() { # start I MODE
    "$V/bin/voltdb" init --force -D "$W/n$1" -C "$W/d.xml" ${VOLTDB_LICENSE:+--license "$VOLTDB_LICENSE"} >"$W/init$1.log" 2>&1 || exit 2
    (nohup "$V/bin/voltdb" start $2 -D "$W/n$1" ${3:+-c 3} -H "$hosts" --client=$(port $1 0) --admin=$(port $1 1) \
        --internal=$(port $1 2) --zookeeper=$(port $1 3) --replication=$(port $1 4) --metrics=$(port $1 5) \
        >"$W/start$1.log" 2>&1 & echo $! >"$W/n$1.pid")
}
for i in 0 1 2; do start $i "" c; done
up() { grep -q "Server completed initialization" "$W/start$1.log" 2>/dev/null; }
for _ in $(seq 1 180); do up 0 && up 1 && up 2 && break; sleep 1; done
cleanup() { for i in 0 1 2 3 4; do kill -9 "$(cat "$W/n$i.pid" 2>/dev/null)" 2>/dev/null; done; }
trap cleanup EXIT
sql() { "$V/bin/sqlcmd" --port=$(port 0 0) 2>/dev/null; }
show() { # the three settings as each live node reports them; cfg.out collects them all
    : >"$W/cfg.out"
    for i in 0 1 2 3 4; do
        nc -z localhost "$(port $i 0)" 2>/dev/null || continue
        printf "exec @GetYamlConfiguration 'deployment.systemsettings';\n" | "$V/bin/sqlcmd" --port=$(port $i 0) 2>/dev/null >"$W/cfg$i.out"
        cat "$W/cfg$i.out" >>"$W/cfg.out"
        echo "$1, node $i: $(grep -A2 'elastic:' "$W/cfg$i.out" | tr -s ' \n' ' ') $(grep -A6 'flushinterval:' "$W/cfg$i.out" | grep 'minimum' | tr -s ' ')"
    done
}
show "start"
printf "exec @SetYamlConfiguration '' 'deployment.systemsettings.elastic.duration=100';\nexec @SetYamlConfiguration '' 'deployment.systemsettings.elastic.throughput=1';\nexec @SetYamlConfiguration '' 'deployment.systemsettings.flushinterval.minimum=2000';\n" | sql >/dev/null
show "set to 100, 1, 2000"
printf "exec @SetYamlConfiguration '' 'deployment.systemsettings.elastic.duration=50';\nexec @SetYamlConfiguration '' 'deployment.systemsettings.elastic.throughput=2';\nexec @SetYamlConfiguration '' 'deployment.systemsettings.flushinterval.minimum=500';\n" | sql >/dev/null
show "set back to 50, 2, 500"
toggle() { # toggles X/Y every 0.3 s until $W/stop exists, then sets Y
    local x=1
    while [ ! -e "$W/stop" ]; do
        if [ $x = 1 ]; then v="100 1 2000"; else v="50 2 500"; fi
        set -- $v
        printf "exec @SetYamlConfiguration '' 'deployment.systemsettings.elastic.duration=$1';\nexec @SetYamlConfiguration '' 'deployment.systemsettings.elastic.throughput=$2';\nexec @SetYamlConfiguration '' 'deployment.systemsettings.flushinterval.minimum=$3';\n" | sql >>"$W/toggle.out" 2>&1
        x=$((1 - x)); sleep 0.3
    done
}
final() {
    printf "exec @SetYamlConfiguration '' 'deployment.systemsettings.elastic.duration=50';\nexec @SetYamlConfiguration '' 'deployment.systemsettings.elastic.throughput=2';\nexec @SetYamlConfiguration '' 'deployment.systemsettings.flushinterval.minimum=500';\n" | sql >"$W/final.out"
}
toggle & T=$!
start 3 --add; start 4 --add
for _ in $(seq 1 300); do up 3 && up 4 && break; sleep 1; done
touch "$W/stop"; wait $T; rm -f "$W/stop"; final
show "just after the final set"
sleep 20
show "after adding 2 nodes (20 s later)"
# each node's three settings; the bug: a node that joined during the changes reports others than the rest, for good
vals() { grep -A2 'elastic:' "$1" | tr -s ' \n' ' '; grep -A6 'flushinterval:' "$1" | grep 'minimum' | tr -d ' '; }
distinct=$(for f in "$W"/cfg[0-4].out; do vals "$f" | tr -d '\n'; echo; done | sort -u | wc -l | tr -d ' ')
echo "distinct settings among the nodes: $distinct"
[ "$distinct" -gt 1 ] && exit 0
exit 1
