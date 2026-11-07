#!/usr/bin/env bash
#
# Starts a VoltDB cluster on this machine, runs SQLancer's VoltDB provider against it for a set time, stops the
# cluster, and saves everything to one results directory: a manifest, the SQLancer output and logs, every node's
# server log, the failing databases' logs (the reproductions) and a PASS/FAIL summary.
#
# Usage: voltdb/run-sqlancer.sh --voltdb <dir> [options] [-- <provider options>]
# Run with --help for the options. Exit code: 0 PASS, 1 FAIL, 2 the run could not be set up.

set -u

usage() {
    cat <<'EOF'
Usage: voltdb/run-sqlancer.sh --voltdb <dir> [options] [-- <provider options>]

  --voltdb DIR       VoltDB kit or build, holding bin/voltdb (default: $VOLTDB_HOME)
  --license FILE     license for voltdb init, which an enterprise kit needs (default: none)
  --nodes N          nodes, all on this machine (default 1)
  --kfactor K        k-safety (default 0 for one node, 1 for more)
  --sites N          sites per host (default 8)
  --oracle LIST      comma-separated oracles: NOREC, TLP_WHERE, TLP_DISTINCT, TLP_HAVING,
                     TLP_AGGREGATE, PQS, PARAMS, VIEWS, LIMIT, SMOKE (default NOREC,TLP_WHERE)
  --duration SECS    how long SQLancer runs (default 1800)
  --seed N           random seed (default: the current time; recorded in the manifest either way)
  --threads N        SQLancer threads (default 4)
  --queries N        oracle checks per generated database (default 100)
  --port-base N      first of the ports the cluster uses, 10 per node (default 52000)
  --results DIR      where the run's directory is created (default ./sqlancer-results)
  --use-reducer      reduce each failure with SQLancer's statement reducer
  --kill-interval S  every S seconds, kill a node other than node 0 with kill -9 and restart it,
                     which rejoins it to the cluster (default 0: never). Needs --kfactor 1 or more.
  --keep-cluster     leave the cluster running and its data in place after the run
  -h, --help         this help

Options after -- go to the provider, after "voltdb --oracle ...", e.g. -- --avoid-known-bugs false.
EOF
}

die() {
    echo "run-sqlancer: $*" >&2
    exit 2
}

script_dir=$(cd "$(dirname "$0")" && pwd)
repo_dir=$(dirname "$script_dir")

voltdb_home=${VOLTDB_HOME:-}
license=
nodes=1
kfactor=
sites=8
oracles=NOREC,TLP_WHERE
duration=1800
seed=
threads=4
queries=100
port_base=52000
results_root=$PWD/sqlancer-results
use_reducer=false
keep_cluster=false
kill_interval=0
provider_args=()
command_line="$0 $*"

while [ $# -gt 0 ]; do
    case $1 in
    --voltdb) voltdb_home=$2; shift 2 ;;
    --license) license=$2; shift 2 ;;
    --nodes) nodes=$2; shift 2 ;;
    --kfactor) kfactor=$2; shift 2 ;;
    --sites) sites=$2; shift 2 ;;
    --oracle) oracles=$2; shift 2 ;;
    --duration) duration=$2; shift 2 ;;
    --seed) seed=$2; shift 2 ;;
    --threads) threads=$2; shift 2 ;;
    --queries) queries=$2; shift 2 ;;
    --port-base) port_base=$2; shift 2 ;;
    --results) results_root=$2; shift 2 ;;
    --use-reducer) use_reducer=true; shift ;;
    --keep-cluster) keep_cluster=true; shift ;;
    --kill-interval) kill_interval=$2; shift 2 ;;
    -h | --help) usage; exit 0 ;;
    --) shift; provider_args=("$@"); break ;;
    *) usage >&2; die "unknown option: $1" ;;
    esac
done

[ -n "$voltdb_home" ] || die "--voltdb (or VOLTDB_HOME) is required"
[ -x "$voltdb_home/bin/voltdb" ] || die "no bin/voltdb in $voltdb_home"
license_args=()
if [ -n "$license" ]; then
    [ -r "$license" ] || die "cannot read the license $license"
    license_args=(--license "$license")
fi
for n in "$nodes" "$sites" "$duration" "$threads" "$queries" "$port_base" "$kill_interval"; do
    case $n in '' | *[!0-9]*) die "not a number: '$n'" ;; esac
done
[ "$nodes" -ge 1 ] || die "--nodes must be at least 1"
if [ -z "$kfactor" ]; then
    if [ "$nodes" -eq 1 ]; then kfactor=0; else kfactor=1; fi
fi
[ "$kfactor" -lt "$nodes" ] || die "--kfactor must be less than --nodes"
[ -n "$seed" ] || seed=$(date +%s)
if [ "$kill_interval" -gt 0 ]; then
    [ "$kfactor" -ge 1 ] || die "--kill-interval needs --kfactor 1 or more"
    # the provider expects the errors a node failure causes (see VoltDBErrors)
    provider_args=(--node-failures true ${provider_args[@]+"${provider_args[@]}"})
fi

java=java
[ -n "${JAVA_HOME:-}" ] && java=$JAVA_HOME/bin/java

jar=$(ls "$repo_dir"/target/sqlancer-*.jar 2>/dev/null | head -1)
if [ -z "$jar" ]; then
    echo "Building SQLancer (mvn package -DskipTests)"
    (cd "$repo_dir" && mvn -q package -DskipTests) || die "the build failed"
    jar=$(ls "$repo_dir"/target/sqlancer-*.jar | head -1)
fi

# Ports: node i uses port_base + 10*i + 0..5 for client, admin, internal, zookeeper, replication and metrics
port() { echo $((port_base + 10 * $1 + $2)); }
client_port() { port "$1" 0; }
admin_port() { port "$1" 1; }
internal_port() { port "$1" 2; }

port_in_use() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

for ((i = 0; i < nodes; i++)); do
    for j in 0 1 2 3 4 5; do
        p=$(port $i $j)
        port_in_use "$p" && die "port $p is in use; pick another --port-base"
    done
done

run_id=sqlancer-$(date -u +%Y%m%dT%H%M%SZ)-seed$seed
results=$results_root/$run_id
cluster=$results/cluster
mkdir -p "$cluster" "$results/sqlancer" "$results/servers" "$results/reproductions" || die "cannot create $results"
results=$(cd "$results" && pwd)
cluster=$results/cluster

log() { echo "[$(date '+%H:%M:%S')] $*" | tee -a "$results/run.log"; }

cluster_started=false
sqlancer_pid=

stop_cluster() {
    $cluster_started || return 0
    cluster_started=false
    if $keep_cluster; then
        log "Leaving the cluster running (--keep-cluster): client port $(client_port 0), data in $cluster"
        return 0
    fi
    log "Stopping the cluster"
    # by port only once this run's node 0 is known to be up, so that it cannot reach another server on that port
    if grep -q "Server completed initialization" "$cluster/node0/voltdbroot/log/volt.log" 2>/dev/null; then
        "$voltdb_home/bin/voltadmin" shutdown --force -H "localhost:$(admin_port 0)" >>"$results/run.log" 2>&1
    fi
    # only processes started for this run carry its directory on their command line
    for _ in $(seq 1 30); do
        pgrep -f "$cluster/" >/dev/null || break
        sleep 1
    done
    pkill -f "$cluster/" 2>/dev/null
}

trap 'stop_cluster' EXIT
interrupted() {
    log "Interrupted"
    [ -n "$sqlancer_pid" ] && kill "$sqlancer_pid" 2>/dev/null
    echo "RESULT: ERROR (interrupted)" >"$results/summary.txt"
    exit 2
}
trap interrupted INT TERM

# --- manifest ----------------------------------------------------------------------------------------------------

sqlancer_commit=$(git -C "$repo_dir" rev-parse HEAD 2>/dev/null || echo unknown)
sqlancer_dirty=$(git -C "$repo_dir" status --porcelain 2>/dev/null | grep -v '^??' | wc -l | tr -d ' ')
{
    echo "run:              $run_id"
    echo "started:          $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    echo "host:             $(hostname) ($(uname -sm))"
    echo "command:          $command_line"
    echo "sqlancer jar:     $jar"
    echo "sqlancer commit:  $sqlancer_commit$([ "$sqlancer_dirty" = 0 ] || echo " (plus $sqlancer_dirty uncommitted changes)")"
    echo "voltdb:           $voltdb_home"
    echo "voltdb version:   $("$voltdb_home/bin/voltdb" --version 2>&1 | tail -1)"
    echo "license:          ${license:-none}"
    echo "java:             $("$java" -version 2>&1 | head -1)"
    echo "nodes:            $nodes"
    echo "kfactor:          $kfactor"
    echo "sites per host:   $sites"
    echo "client ports:     $(for ((i = 0; i < nodes; i++)); do printf '%s ' "$(client_port $i)"; done)"
    echo "oracles:          $oracles"
    echo "duration:         $duration s"
    echo "seed:             $seed"
    echo "threads:          $threads"
    echo "queries per db:   $queries"
    echo "use reducer:      $use_reducer"
    echo "kill interval:    $([ "$kill_interval" -gt 0 ] && echo "$kill_interval s" || echo never)"
    echo "provider options: ${provider_args[*]:-}"
} >"$results/manifest.txt"
cat "$results/manifest.txt"

# --- cluster -----------------------------------------------------------------------------------------------------

cat >"$cluster/deployment.xml" <<EOF
<?xml version="1.0"?>
<deployment>
    <cluster sitesperhost="$sites" kfactor="$kfactor"/>
    <httpd enabled="false"/>
</deployment>
EOF

log "Starting $nodes node(s), k=$kfactor, $sites sites per host"
for ((i = 0; i < nodes; i++)); do
    node=$cluster/node$i
    "$voltdb_home/bin/voltdb" init --force -D "$node" -C "$cluster/deployment.xml" ${license_args[@]+"${license_args[@]}"} >"$cluster/init$i.log" 2>&1 ||
        die "voltdb init failed for node $i; see $cluster/init$i.log"
done
cluster_started=true
# start_node I LOG: starts node I, or restarts it after a failure, which rejoins it to the running cluster
start_node() {
    # in the foreground under nohup: "voltdb start --background" allows one server per user (a shared PID file).
    # In a subshell, so that the server is not one of this script's jobs and its kill -9 is not reported.
    (nohup "$voltdb_home/bin/voltdb" start -D "$cluster/node$1" -c "$nodes" -H "localhost:$(internal_port 0)" \
        --client="$(client_port $1)" --admin="$(admin_port $1)" --internal="$(internal_port $1)" \
        --zookeeper="$(port $1 3)" --replication="$(port $1 4)" --metrics="$(port $1 5)" \
        >"$2" 2>&1 &)
}
for ((i = 0; i < nodes; i++)); do
    start_node $i "$cluster/start$i.log"
done

ready=0
for _ in $(seq 1 300); do
    ready=0
    for ((i = 0; i < nodes; i++)); do
        grep -q "Server completed initialization" "$cluster/node$i/voltdbroot/log/volt.log" 2>/dev/null &&
            ready=$((ready + 1))
    done
    [ "$ready" -eq "$nodes" ] && break
    sleep 1
done
if [ "$ready" -ne "$nodes" ]; then
    log "The cluster did not start within 300 s ($ready of $nodes nodes ready)"
    for ((i = 0; i < nodes; i++)); do
        mkdir -p "$results/servers/node$i"
        cp "$cluster/start$i.log" "$results/servers/node$i/" 2>/dev/null
        cp -R "$cluster/node$i/voltdbroot/log/." "$results/servers/node$i/" 2>/dev/null
    done
    echo "RESULT: ERROR (the cluster did not start)" >"$results/summary.txt"
    exit 2
fi
log "Cluster ready: $(grep -h -m1 "^Build:" "$cluster/init0.log" 2>/dev/null)"

# --- SQLancer ----------------------------------------------------------------------------------------------------

sqlancer_args=(--num-threads "$threads" --num-tries "$threads" --timeout-seconds "$duration" --num-queries "$queries"
    --random-seed "$seed" --host localhost --port "$(client_port 0)" --username "" --password ""
    --database-prefix db)
$use_reducer && sqlancer_args+=(--use-reducer)
# --- node failures -----------------------------------------------------------------------------------------------

# Every kill_interval seconds: kill -9 a random node other than node 0, which SQLancer connects to, wait until its
# client port closes, restart it and wait until it has rejoined. Each event is a line of chaos.log; a rejoin that
# does not complete within 600 s ends the loop and fails the run. The loop stops when $results/chaos.stop exists,
# after the node it is handling has rejoined, so the run ends with every node up.
chaos_loop() {
    local n=0 i pid t0
    while true; do
        for _ in $(seq 1 "$kill_interval"); do
            [ -e "$results/chaos.stop" ] && return 0
            sleep 1
        done
        i=$((1 + RANDOM % (nodes - 1)))
        n=$((n + 1))
        pid=$(pgrep -f -- "voltdbroot $cluster/node$i( |\$)" | head -1)
        if [ -z "$pid" ]; then
            echo "$(date '+%H:%M:%S') kill $n: node$i is not running" >>"$results/chaos.log"
            return 1
        fi
        kill -9 "$pid"
        t0=$(date +%s)
        echo "$(date '+%H:%M:%S') kill $n: node$i (pid $pid)" >>"$results/chaos.log"
        while port_in_use "$(client_port $i)"; do sleep 1; done
        # the server is "java ... voltdbroot <dir>"; the survivors must notice the failure before it comes back
        sleep 5
        start_node "$i" "$cluster/restart$i-$n.log"
        for _ in $(seq 1 600); do
            grep -q "Server completed initialization" "$cluster/restart$i-$n.log" 2>/dev/null && break
            sleep 1
        done
        if ! grep -q "Server completed initialization" "$cluster/restart$i-$n.log" 2>/dev/null; then
            echo "$(date '+%H:%M:%S') rejoin $n: node$i did not rejoin within 600 s" >>"$results/chaos.log"
            return 1
        fi
        echo "$(date '+%H:%M:%S') rejoin $n: node$i, $(($(date +%s) - t0)) s after the kill" >>"$results/chaos.log"
    done
}

chaos_pid=
if [ "$kill_interval" -gt 0 ]; then
    : >"$results/chaos.log"
    chaos_loop &
    chaos_pid=$!
    log "Killing and rejoining a node other than node 0 every $kill_interval s (chaos.log)"
fi

log "Running SQLancer for $duration s: oracles $oracles, seed $seed, $threads threads"
# in the background, so that an interrupt runs the trap at once instead of after SQLancer finishes
(cd "$results/sqlancer" && exec "$java" -jar "$jar" "${sqlancer_args[@]}" voltdb --oracle "$oracles" \
    ${provider_args[@]+"${provider_args[@]}"} >out.log 2>&1) &
sqlancer_pid=$!
wait "$sqlancer_pid"
sqlancer_exit=$?
sqlancer_pid=
log "SQLancer exited with $sqlancer_exit"

chaos_failed=false
kills=0
rejoins=0
if [ -n "$chaos_pid" ]; then
    touch "$results/chaos.stop"
    wait "$chaos_pid" || chaos_failed=true
    kills=$(grep -c " kill " "$results/chaos.log")
    rejoins=$(grep -c " rejoin [0-9]*: node[0-9]*, " "$results/chaos.log")
    log "Node failures: $kills kills, $rejoins rejoins$($chaos_failed && echo ", the last one FAILED")"
fi

# --- collect -----------------------------------------------------------------------------------------------------

down=()
for ((i = 0; i < nodes; i++)); do
    port_in_use "$(client_port $i)" || down+=("node$i")
done
stop_cluster
for ((i = 0; i < nodes; i++)); do
    mkdir -p "$results/servers/node$i"
    cp "$cluster/start$i.log" "$cluster/init$i.log" "$cluster"/restart$i-*.log "$results/servers/node$i/" 2>/dev/null
    cp -R "$cluster/node$i/voltdbroot/log/." "$results/servers/node$i/" 2>/dev/null
done
cp "$cluster/deployment.xml" "$results/"
$keep_cluster || rm -rf "$cluster"

failures=()
for f in "$results"/sqlancer/logs/voltdb/*.log; do
    [ -e "$f" ] || continue
    case $f in *-cur.log) continue ;; esac
    failures+=("$f")
    cp "$f" "$results/reproductions/"
done
# the reducer opens a log for every thread; only a failing one has anything in it
for f in "$results"/sqlancer/logs/voltdb/reduce/*.log; do
    [ -s "$f" ] && cp "$f" "$results/reproductions/"
done
fatal=$(cat "$results"/servers/node*/volt.log 2>/dev/null | grep -c " FATAL ")

# --- summary -----------------------------------------------------------------------------------------------------

result=PASS
[ "$sqlancer_exit" -eq 0 ] && [ ${#failures[@]} -eq 0 ] && [ ${#down[@]} -eq 0 ] && [ "$fatal" -eq 0 ] &&
    ! $chaos_failed || result=FAIL
{
    echo "RESULT: $result"
    echo "run: $run_id"
    echo "progress: $(grep "Executed" "$results/sqlancer/out.log" | tail -1)"
    echo "sqlancer exit code: $sqlancer_exit"
    echo "failed databases: ${#failures[@]}"
    echo "nodes down at the end: ${down[*]:-none}"
    echo "FATAL lines in server logs: $fatal"
    [ "$kill_interval" -gt 0 ] && echo "node failures: $kills kills, $rejoins rejoins$($chaos_failed && echo ", a rejoin FAILED (chaos.log)")"
    for f in ${failures[@]+"${failures[@]}"}; do
        echo
        echo "== reproductions/$(basename "$f")"
        # the oracle's account of the failure, or the error and its root cause
        if grep -q "^-- On the database" "$f"; then
            grep -A3 "^-- On the database" "$f" | cut -c1-400
        else
            grep -m1 -E "^--java" "$f" | cut -c1-400
            grep -E "^--Caused by" "$f" | tail -1 | cut -c1-400
        fi
    done
} >"$results/summary.txt"

echo
cat "$results/summary.txt"
echo
echo "Results: $results"
[ "$result" = PASS ]
