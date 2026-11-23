#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30758
-- summary: After an elastic remove, a stale replica-change callback appoints a removed site as a removed partition's leader; the MP repair blocker it creates stays, refusing DDL
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR; needs a JDK (javac, and the jdk.jdi module).
# 3 nodes, k=1, 8 sites per host, each with a JDWP agent; 2 nodes are added elastically, then removed (voltadmin
# resize). Two things stand in for the sighting's timing (an instrumented build under SQLancer's load):
#  - the two removed hosts must fail far enough apart: the first is killed (kill -9) as the remove starts its
#    "Finalizing data migration" step, about 50 ms before it sends both the poison pill, so the second dies ~100 ms
#    later (an elastic remove's own shutdown kills both within ms; delaying the second with SIGSTOP or CPU throttling
#    does not help, as the first one's failure agreement then waits for it and both ZooKeeper sessions close together);
#  - the LeaderAppointer must be slow: elastic/SlowAppointer.java holds its Babysitters thread for 500 ms at the start
#    of LeaderAppointer.isClusterKSafe, only in callbacks of partitions led by the first removed host (JDI breakpoint,
#    that thread only), so the callback reads the replicas while only the second removed host is alive and its
#    isClusterKSafe runs after that host is gone too, cleans up the removed partitions, and returns; the callback then
#    appoints the second host's (dead) site for the partition that no longer exists and creates the MP repair blocker,
#    after the last MP repair.
# Then DDL is tried every 5 s for 60 s. Exit 0 if it is still refused ("leader promotion or transaction repair are in
# progress") after 60 s (the bug), 1 if it succeeds.
set -u
V=$VOLTDB_HOME; W=$WORKDIR/elasticremove; P=$PORT_BASE
here=$(cd "$(dirname "$0")" && pwd)
rm -rf "$W"; mkdir -p "$W/classes"
javac -d "$W/classes" "$here/elastic/SlowAppointer.java" || exit 2
export VOLTDB_OPTS="${VOLTDB_OPTS:-} -XX:ErrorFile=$W/hs_err_pid%p.log"
port() { echo $((P + 10 * $1 + $2)); }
hosts="localhost:$(port 0 2),localhost:$(port 1 2),localhost:$(port 2 2)"
printf '<?xml version="1.0"?>\n<deployment><cluster sitesperhost="8" kfactor="1"/><httpd enabled="false"/></deployment>\n' >"$W/d.xml"
start() { # start SLOT MODE: node SLOT in $W/nSLOT; the original nodes (MODE -c 3) listen for a debugger on port SLOT,6
    local jdwp=""
    [ "$2" != --add ] && jdwp="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=localhost:$(port $1 6)"
    "$V/bin/voltdb" init --force -D "$W/n$1" -C "$W/d.xml" ${VOLTDB_LICENSE:+--license "$VOLTDB_LICENSE"} >"$W/init$1.log" 2>&1 || exit 2
    (VOLTDB_OPTS="$VOLTDB_OPTS $jdwp" nohup "$V/bin/voltdb" start $2 -D "$W/n$1" -H "$hosts" --client=$(port $1 0) \
        --admin=$(port $1 1) --internal=$(port $1 2) --zookeeper=$(port $1 3) --replication=$(port $1 4) \
        --metrics=$(port $1 5) >"$W/start$1.log" 2>&1 & echo $! >"$W/n$1.pid")
}
up() { grep -q "Server completed initialization" "$W/start$1.log" 2>/dev/null; }
log() { echo "$W/n$1/voltdbroot/log/volt.log"; }
hostid() { grep -m1 -o "Host id of this node is: [0-9]*" "$(log $1)" | grep -o "[0-9]*$"; }
cleanup() { for i in 0 1 2 3 4; do kill -9 "$(cat "$W/n$i.pid" 2>/dev/null)" 2>/dev/null; done; }
trap cleanup EXIT
for i in 0 1 2; do start $i "-c 3"; done
for _ in $(seq 1 180); do up 0 && up 1 && up 2 && break; sleep 1; done
up 0 && up 1 && up 2 || { echo "the cluster did not start"; exit 2; }
start 3 --add; start 4 --add
for _ in $(seq 1 180); do up 3 && up 4 && break; sleep 1; done
up 3 && up 4 || { echo "the 2 nodes did not join"; exit 2; }
# VoltDB publishes the new topology some seconds after the join; until then a remove would take original nodes
for _ in $(seq 1 60); do
    plan=$("$V/bin/voltadmin" resize --test -H "localhost:$(port 0 1)" 2>&1 | tail -1)
    echo "$plan" | grep -q "Host Ids: \[" && ! echo "$plan" | grep -qE "Host Ids: \[(.*[ ,\[])?(0|1|2)[],]" && break
    sleep 3
done
echo "planned removal: $plan"
# the LeaderAppointer: the original node that appointed the joined partitions' leaders
app=$(for i in 0 1 2; do echo "$(grep -c 'LeaderAppointer\] *Appointing' "$(log $i)") $i"; done | sort -n | tail -1 | cut -d' ' -f2)
first=$(hostid 3); second=$(hostid 4)
echo "LeaderAppointer on node $app (host $(hostid $app)); host $first is killed first, host $second gets the poison pill"
java -cp "$W/classes" SlowAppointer "$(port $app 6)" 500 120 "$first" >"$W/jdi.log" 2>&1 &
J=$!
for _ in $(seq 1 50); do grep -q attached "$W/jdi.log" && break; sleep 0.2; done
grep -q attached "$W/jdi.log" || { cat "$W/jdi.log"; exit 2; }
( tail -n 0 -F "$(log 0)" "$(log 1)" "$(log 2)" 2>/dev/null | grep --line-buffered -m1 "starting step: Finalizing data migration" >/dev/null
  kill -9 "$(cat "$W/n3.pid")" ) &
K=$!
"$V/bin/voltadmin" resize -H "localhost:$(port 0 1)" 2>&1 | tail -1
for _ in $(seq 1 300); do kill -0 "$(cat "$W/n3.pid")" 2>/dev/null || kill -0 "$(cat "$W/n4.pid")" 2>/dev/null || break; sleep 1; done
pkill -f "tail -n 0 -F $W/" 2>/dev/null; wait $K 2>/dev/null
kill $J 2>/dev/null; wait $J 2>/dev/null
grep -E "holding|passing" "$W/jdi.log"
# the appointer's view of the removal
grep -hE "Host ($first|$second) failed\. Cluster|cleanup up partition info|Babysitters.*(Replica changes|Appointing)|mp_repair_blocker|MP repair task .* completed" \
    "$(log $app)" | awk -v t="$(grep -h -m1 "Host $first failed\. Cluster" "$(log $app)" | cut -c1-23)" '$0 >= t' | cut -c12-220
refused=""
for _ in $(seq 1 13); do
    out=$(printf "CREATE TABLE eng30758 (a INTEGER NOT NULL);\nDROP TABLE eng30758;\n" | "$V/bin/sqlcmd" --port=$(port 0 0) 2>&1)
    if echo "$out" | grep -q "leader promotion or transaction repair are in progress"; then
        refused=$(echo "$out" | grep -m1 "leader promotion")
        sleep 5
    else
        refused=""
        break
    fi
done
if [ -n "$refused" ]; then
    echo "DDL still refused 60 s after the remove: $refused"
    exit 0
fi
echo "DDL succeeded after the remove"
exit 1
