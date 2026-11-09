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
  --port-base N      first of the ports the cluster uses, 10 per node (default 30000, below the
                     ephemeral ports Linux and macOS give outgoing connections)
  --results DIR      where the run's directory is created (default ./sqlancer-results)
  --use-reducer      reduce each failure with SQLancer's statement reducer
  --kill-interval S  every S seconds, kill a random node, node 0 included, with kill -9 and restart it,
                     which rejoins it to the cluster (default 0: never). Needs --kfactor 1 or more.
  --resize-interval S  every S seconds, resize the cluster elastically, growing and shrinking by turns: add
                     k+1 nodes (voltdb start --add), then remove them (voltadmin resize) (default 0: never).
                     Not with --kill-interval.
  --crash-interval S every S seconds, kill every node at once with kill -9 and restart them all, which
                     recovers the database from its snapshots and synchronous command log (default 0:
                     never). Not with --kill-interval or --resize-interval.
  --xdcr             also start a second cluster of the same shape, linked to the first by XDCR (active-active
                     DR), and run the provider with --xdcr: each database writes through one of the two, its DDL
                     runs on both, and the XDCR oracle compares their rows. Not with --kill-interval,
                     --resize-interval or --crash-interval.
  --upgrade-from DIR start the cluster on this older VoltDB kit (normally the last release) and, at
                     --upgrade-at, upgrade it in service to --voltdb: one node at a time, voltadmin stop, then
                     voltdb start on the new kit, which rejoins. Needs --kfactor 1 or more. Not with
                     --kill-interval, --resize-interval, --crash-interval or --xdcr.
  --upgrade-java-home DIR  JAVA_HOME for the older kit's nodes (default: the environment's)
  --upgrade-at S     seconds into the run to start the upgrade (default: a third of --duration)
  --keep-cluster     leave the cluster running and its data in place after the run
  --mirror-voltdb DIR  also start one node of this other VoltDB kit (normally the last release) and run
                     the provider with --mirror: every change is repeated on it, and the DIFF oracle
                     compares the two clusters' rows
  --mirror-license FILE  license for the mirror's voltdb init (default: --license)
  --mirror-java-home DIR  JAVA_HOME for the mirror (default: the environment's); an older release may
                     not run on the newer JDK the cluster uses (16.0.0 needs Java 17 or 21)
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
# below Linux's (32768-60999) and macOS's (49152-65535) ephemeral ports: an outgoing connection can take a port in
# that range while a killed node is down (--kill-interval), and the node then cannot bind it when it restarts
port_base=30000
results_root=$PWD/sqlancer-results
use_reducer=false
keep_cluster=false
kill_interval=0
crash_interval=0
xdcr=false
upgrade_from=
upgrade_java_home=
upgrade_at=
resize_interval=0
mirror_home=
mirror_license=
mirror_java_home=
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
    --crash-interval) crash_interval=$2; shift 2 ;;
    --xdcr) xdcr=true; shift ;;
    --upgrade-from) upgrade_from=$2; shift 2 ;;
    --upgrade-java-home) upgrade_java_home=$2; shift 2 ;;
    --upgrade-at) upgrade_at=$2; shift 2 ;;
    --resize-interval) resize_interval=$2; shift 2 ;;
    --mirror-voltdb) mirror_home=$2; shift 2 ;;
    --mirror-license) mirror_license=$2; shift 2 ;;
    --mirror-java-home) mirror_java_home=$2; shift 2 ;;
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
mirror_license_args=()
if [ -n "$mirror_home" ]; then
    [ -x "$mirror_home/bin/voltdb" ] || die "no bin/voltdb in $mirror_home"
    [ -z "$mirror_java_home" ] || [ -x "$mirror_java_home/bin/java" ] || die "no bin/java in $mirror_java_home"
    mirror_license=${mirror_license:-$license}
    if [ -n "$mirror_license" ]; then
        [ -r "$mirror_license" ] || die "cannot read the license $mirror_license"
        mirror_license_args=(--license "$mirror_license")
    fi
fi
for n in "$nodes" "$sites" "$duration" "$threads" "$queries" "$port_base" "$kill_interval" "$resize_interval" "$crash_interval"; do
    case $n in '' | *[!0-9]*) die "not a number: '$n'" ;; esac
done
[ "$nodes" -ge 1 ] || die "--nodes must be at least 1"
if [ -z "$kfactor" ]; then
    if [ "$nodes" -eq 1 ]; then kfactor=0; else kfactor=1; fi
fi
[ "$kfactor" -lt "$nodes" ] || die "--kfactor must be less than --nodes"
[ -n "$seed" ] || seed=$(date +%s)
[ "$kill_interval" -eq 0 ] || [ "$resize_interval" -eq 0 ] || die "--kill-interval and --resize-interval do not mix"
[ "$crash_interval" -eq 0 ] || { [ "$kill_interval" -eq 0 ] && [ "$resize_interval" -eq 0 ]; } ||
    die "--crash-interval does not mix with --kill-interval or --resize-interval"
if [ -n "$upgrade_from" ]; then
    [ -x "$upgrade_from/bin/voltdb" ] || die "no bin/voltdb in $upgrade_from"
    [ "$kfactor" -ge 1 ] || die "--upgrade-from needs --kfactor 1 or more"
    [ "$kill_interval" -eq 0 ] && [ "$resize_interval" -eq 0 ] && [ "$crash_interval" -eq 0 ] && ! $xdcr ||
        die "--upgrade-from does not mix with --kill-interval, --resize-interval, --crash-interval or --xdcr"
    [ -n "$upgrade_at" ] || upgrade_at=$((duration / 3))
    case $upgrade_at in '' | *[!0-9]*) die "not a number: '$upgrade_at'" ;; esac
    # a stopped node drops the calls in flight on it, which the provider then expects (see VoltDBErrors)
    provider_args=(--upgrades true ${provider_args[@]+"${provider_args[@]}"})
fi
if $xdcr; then
    [ "$kill_interval" -eq 0 ] && [ "$resize_interval" -eq 0 ] && [ "$crash_interval" -eq 0 ] ||
        die "--xdcr does not mix with --kill-interval, --resize-interval or --crash-interval"
fi
if [ "$crash_interval" -gt 0 ]; then
    # a crash drops every call in flight and every connection; the provider skips what it interrupted and waits for
    # the cluster to recover (see VoltDBErrors)
    provider_args=(--crashes true ${provider_args[@]+"${provider_args[@]}"})
fi
if [ "$resize_interval" -gt 0 ]; then
    # a shrink drops the calls in flight on the hosts it removes, which the provider then expects (see VoltDBErrors)
    provider_args=(--resizes true ${provider_args[@]+"${provider_args[@]}"})
fi
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
# every node's address, as a comma-separated list: hosts() internal for the cluster's host list, hosts client for
# SQLancer's connections
hosts() {
    local list= i
    for ((i = 0; i < nodes; i++)); do
        list+="${list:+,}localhost:$("$1_port" $i)"
    done
    echo "$list"
}
# SQLancer connects to every node (--servers), so that it keeps running while any one of them is down; --host and
# --port remain for the run's other uses of SQLancer's options
if [ "$nodes" -gt 1 ]; then
    provider_args+=(--servers "$(hosts client)")
fi
admin_port() { port "$1" 1; }
internal_port() { port "$1" 2; }

port_in_use() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

# the mirror, if any, takes the port slot after the last node's; the k+1 nodes a resize adds take the slots after that
added=0
[ "$resize_interval" -gt 0 ] && added=$((kfactor + 1))
added_slot=$((nodes + 1))
# the XDCR peer's nodes take the slots from 20
xdcr_slot=20
for ((i = 0; i < added_slot + added; i++)); do
    [ "$i" -eq "$nodes" ] && [ -z "$mirror_home" ] && continue
    for j in 0 1 2 3 4 5; do
        p=$(port $i $j)
        port_in_use "$p" && die "port $p is in use; pick another --port-base"
    done
done
if $xdcr; then
    [ "$nodes" -le "$xdcr_slot" ] || die "--xdcr allows at most $xdcr_slot nodes"
    for ((i = xdcr_slot; i < xdcr_slot + nodes; i++)); do
        for j in 0 1 2 3 4 5; do
            p=$(port $i $j)
            port_in_use "$p" && die "port $p is in use; pick another --port-base"
        done
    done
fi
# the XDCR peer's addresses, like hosts(): xdcr_hosts client|internal|replication
xdcr_hosts() {
    local list= i k
    case $1 in client) k=0 ;; internal) k=2 ;; replication) k=4 ;; esac
    for ((i = 0; i < nodes; i++)); do
        list+="${list:+,}localhost:$(port $((xdcr_slot + i)) $k)"
    done
    echo "$list"
}
replication_hosts() {
    local list= i
    for ((i = 0; i < nodes; i++)); do
        list+="${list:+,}localhost:$(port $i 4)"
    done
    echo "$list"
}

run_id=sqlancer-$(date -u +%Y%m%dT%H%M%SZ)-seed$seed
results=$results_root/$run_id
cluster=$results/cluster
mkdir -p "$cluster" "$results/sqlancer" "$results/servers" "$results/reproductions" || die "cannot create $results"
results=$(cd "$results" && pwd)
if [ "$resize_interval" -gt 0 ]; then
    # DDL waits while this file exists, which shrink creates (the provider ignores it with --avoid-known-bugs false)
    provider_args+=(--ddl-hold "$results/ddl.hold")
fi
cluster=$results/cluster

log() { echo "[$(date '+%H:%M:%S')] $*" | tee -a "$results/run.log"; }

cluster_started=false
sqlancer_pid=

# within SECS COMMAND...: runs COMMAND, killing it after SECS seconds (macOS has no timeout(1))
# runs a command of the mirror's kit with the mirror's JAVA_HOME, if any
# upgrade_cmd CMD...: runs a command of the older kit with its JAVA_HOME (--upgrade-java-home), if any
upgrade_cmd() {
    if [ -n "$upgrade_from" ] && [ -n "$upgrade_java_home" ]; then
        (export JAVA_HOME=$upgrade_java_home PATH=$upgrade_java_home/bin:$PATH && exec "$@")
    else
        "$@"
    fi
}
mirror_cmd() {
    if [ -n "$mirror_java_home" ]; then
        JAVA_HOME=$mirror_java_home PATH=$mirror_java_home/bin:$PATH "$@"
    else
        "$@"
    fi
}

within() {
    local secs=$1
    shift
    "$@" &
    local pid=$! i
    for ((i = 0; i < secs; i++)); do
        kill -0 "$pid" 2>/dev/null || { wait "$pid"; return; }
        sleep 1
    done
    kill -9 "$pid" 2>/dev/null
    echo "run-sqlancer: gave up after $secs s: $*" >&2
    return 124
}

stop_cluster() {
    $cluster_started || return 0
    cluster_started=false
    if $keep_cluster; then
        log "Leaving the cluster running (--keep-cluster): client port $(client_port 0), data in $cluster"
        return 0
    fi
    log "Stopping the cluster"
    # by port only once this run's node 0 is known to be up, so that it cannot reach another server on that port
    # at most 120 s: on a wedged cluster (ENG-30612) the shutdown never returns, and the processes are killed below
    if grep -q "Server completed initialization" "$cluster/node0/voltdbroot/log/volt.log" 2>/dev/null; then
        within 120 "$voltdb_home/bin/voltadmin" shutdown --force -H "localhost:$(admin_port 0)" >>"$results/run.log" 2>&1
    fi
    if [ -n "$mirror_home" ] && grep -q "Server completed initialization" "$cluster/mirror/voltdbroot/log/volt.log" 2>/dev/null; then
        within 120 mirror_cmd "$mirror_home/bin/voltadmin" shutdown --force -H "localhost:$(admin_port $nodes)" >>"$results/run.log" 2>&1
    fi
    if $xdcr && grep -q "Server completed initialization" "$cluster/xdcr0/voltdbroot/log/volt.log" 2>/dev/null; then
        within 120 "$voltdb_home/bin/voltadmin" shutdown --force -H "localhost:$(admin_port $xdcr_slot)" >>"$results/run.log" 2>&1
    fi
    # only processes started for this run carry its directory on their command line, and their PIDs are recorded
    for _ in $(seq 1 30); do
        pgrep -f "$cluster/" >/dev/null || break
        sleep 1
    done
    pkill -f "$cluster/" 2>/dev/null
    for f in "$cluster"/node*.pid "$cluster"/add*.pid "$cluster"/mirror.pid; do
        [ -e "$f" ] && is_server "$(cat "$f")" && kill -9 "$(cat "$f")" 2>/dev/null
    done
}

# is_server PID: whether PID is a running VoltDB server (and not another process that reused the PID)
is_server() {
    ps -ww -o command= -p "$1" 2>/dev/null | grep -q "org.voltdb.VoltDB"
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
    [ -n "$mirror_home" ] && echo "mirror:           $mirror_home ($(mirror_cmd "$mirror_home/bin/voltdb" --version 2>&1 | tail -1)), 1 node, client port $(client_port $nodes)$([ -z "$mirror_java_home" ] || echo ", java $(mirror_cmd java -version 2>&1 | head -1)")"
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
    echo "xdcr:             $($xdcr && echo "a second cluster of $nodes node(s), k=$kfactor, client ports $(xdcr_hosts client)" || echo no)"
    echo "upgrade:          $([ -n "$upgrade_from" ] && echo "from $upgrade_from ($(upgrade_cmd "$upgrade_from/bin/voltdb" --version 2>&1 | tail -1)) at $upgrade_at s" || echo no)"
    echo "crash interval:   $([ "$crash_interval" -gt 0 ] && echo "$crash_interval s, every node killed and recovered" || echo never)"
    echo "resize interval:  $([ "$resize_interval" -gt 0 ] && echo "$resize_interval s, $added nodes added and removed by turns" || echo never)"
    echo "provider options: ${provider_args[*]:-}"
} >"$results/manifest.txt"
cat "$results/manifest.txt"

# --- cluster -----------------------------------------------------------------------------------------------------

# with --crash-interval, a synchronous command log (a transaction is acknowledged once it is on disk, so none that was
# acknowledged may be lost) and a snapshot every 2 minutes, which recovery starts from before replaying the log
durability=
if [ "$crash_interval" -gt 0 ]; then
    durability='
    <commandlog enabled="true" synchronous="true"/>
    <snapshot prefix="auto" frequency="2m" retain="2"/>'
fi
# with --xdcr, DR id 1 here and 2 on the peer, each the other's source
dr=
if $xdcr; then
    dr='
    <dr id="1" role="xdcr">
        <connection source="'$(xdcr_hosts replication)'"/>
    </dr>'
fi
cat >"$cluster/deployment.xml" <<EOF
<?xml version="1.0"?>
<deployment>
    <cluster sitesperhost="$sites" kfactor="$kfactor"/>
    <httpd enabled="false"/>$durability$dr
</deployment>
EOF

log "Starting $nodes node(s), k=$kfactor, $sites sites per host"
for ((i = 0; i < nodes; i++)); do
    node=$cluster/node$i
    upgrade_cmd "${upgrade_from:-$voltdb_home}/bin/voltdb" init --force -D "$node" -C "$cluster/deployment.xml" ${license_args[@]+"${license_args[@]}"} >"$cluster/init$i.log" 2>&1 ||
        die "voltdb init failed for node $i; see $cluster/init$i.log"
done
cluster_started=true
# the kit node i runs, and its JAVA_HOME (empty: the environment's); with --upgrade-from every node starts on the
# older kit and moves to --voltdb when it is upgraded
node_home=()
node_java=()
for ((i = 0; i < nodes; i++)); do
    node_home[i]=${upgrade_from:-$voltdb_home}
    node_java[i]=$([ -n "$upgrade_from" ] && echo "$upgrade_java_home")
done
# start_node I LOG: starts node I, or restarts it after a failure, which rejoins it to the running cluster
start_node() {
    # in the foreground under nohup: "voltdb start --background" allows one server per user (a shared PID file).
    # In a subshell, so that the server is not one of this script's jobs and its kill -9 is not reported. The
    # launcher execs the server's JVM, so the PID it starts with is the server's, which node<i>.pid records.
    # Every node gets the whole host list, so that a restarted node, node 0 included, finds a live one to rejoin through
    (if [ -n "${node_java[$1]}" ]; then export JAVA_HOME=${node_java[$1]} PATH=${node_java[$1]}/bin:$PATH; fi
        nohup "${node_home[$1]}/bin/voltdb" start -D "$cluster/node$1" -c "$nodes" -H "$(hosts internal)" \
        --client="$(client_port $1)" --admin="$(admin_port $1)" --internal="$(internal_port $1)" \
        --zookeeper="$(port $1 3)" --replication="$(port $1 4)" --metrics="$(port $1 5)" \
        >"$2" 2>&1 &
        echo $! >"$cluster/node$1.pid")
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

# --- XDCR peer ---------------------------------------------------------------------------------------------------

# a second cluster of the same shape, DR id 2, linked to the first by XDCR; its nodes take the slots from 20
if $xdcr; then
    sed -e 's/<dr id="1"/<dr id="2"/' -e "s|<connection source=\"[^\"]*\"|<connection source=\"$(replication_hosts)\"|" \
        "$cluster/deployment.xml" >"$cluster/deployment-xdcr.xml"
    for ((i = 0; i < nodes; i++)); do
        "$voltdb_home/bin/voltdb" init --force -D "$cluster/xdcr$i" -C "$cluster/deployment-xdcr.xml" \
            ${license_args[@]+"${license_args[@]}"} >"$cluster/init-xdcr$i.log" 2>&1 ||
            die "voltdb init failed for XDCR node $i; see $cluster/init-xdcr$i.log"
    done
    for ((i = 0; i < nodes; i++)); do
        x=$((xdcr_slot + i))
        (nohup "$voltdb_home/bin/voltdb" start -D "$cluster/xdcr$i" -c "$nodes" -H "$(xdcr_hosts internal)" \
            --client="$(client_port $x)" --admin="$(admin_port $x)" --internal="$(internal_port $x)" \
            --zookeeper="$(port $x 3)" --replication="$(port $x 4)" --metrics="$(port $x 5)" \
            >"$cluster/start-xdcr$i.log" 2>&1 &
            echo $! >"$cluster/xdcr$i.pid")
    done
    ready=0
    for _ in $(seq 1 300); do
        ready=0
        for ((i = 0; i < nodes; i++)); do
            grep -q "Server completed initialization" "$cluster/xdcr$i/voltdbroot/log/volt.log" 2>/dev/null &&
                ready=$((ready + 1))
        done
        [ "$ready" -eq "$nodes" ] && break
        sleep 1
    done
    [ "$ready" -eq "$nodes" ] || die "the XDCR peer did not start within 300 s ($ready of $nodes nodes ready)"
    log "XDCR peer ready: client ports $(xdcr_hosts client)"
    provider_args+=(--xdcr "$(xdcr_hosts client)")
fi

# --- mirror (DIFF) -----------------------------------------------------------------------------------------------

# one node of the other kit, k=0, the same sites per host, on the port slot after the last node's
if [ -n "$mirror_home" ]; then
    m=$nodes
    # the mirror never crashes: no command log or snapshots
    sed -e "s/kfactor=\"$kfactor\"/kfactor=\"0\"/" -e '/<commandlog /d' -e '/<snapshot /d' \
        -e '/<dr /d' -e '/<connection /d' -e '/<\/dr>/d' "$cluster/deployment.xml" >"$cluster/deployment-mirror.xml"
    mirror_cmd "$mirror_home/bin/voltdb" init --force -D "$cluster/mirror" -C "$cluster/deployment-mirror.xml" \
        ${mirror_license_args[@]+"${mirror_license_args[@]}"} >"$cluster/init-mirror.log" 2>&1 ||
        die "voltdb init failed for the mirror; see $cluster/init-mirror.log"
    (if [ -n "$mirror_java_home" ]; then export JAVA_HOME=$mirror_java_home PATH=$mirror_java_home/bin:$PATH; fi
        nohup "$mirror_home/bin/voltdb" start -D "$cluster/mirror" -c 1 -H "localhost:$(internal_port $m)" \
        --client="$(client_port $m)" --admin="$(admin_port $m)" --internal="$(internal_port $m)" \
        --zookeeper="$(port $m 3)" --replication="$(port $m 4)" --metrics="$(port $m 5)" \
        >"$cluster/start-mirror.log" 2>&1 &
        echo $! >"$cluster/mirror.pid")
    for _ in $(seq 1 300); do
        grep -q "Server completed initialization" "$cluster/mirror/voltdbroot/log/volt.log" 2>/dev/null && break
        # a mirror that exited (a JDK it refuses, say) will not start: stop waiting
        kill -0 "$(cat "$cluster/mirror.pid")" 2>/dev/null || break
        sleep 1
    done
    grep -q "Server completed initialization" "$cluster/mirror/voltdbroot/log/volt.log" 2>/dev/null ||
        die "the mirror did not start; see $cluster/start-mirror.log: $(tail -1 "$cluster/start-mirror.log" 2>/dev/null)"
    log "Mirror ready: $(grep -h -m1 "^Build:" "$cluster/init-mirror.log" 2>/dev/null), client port $(client_port $m)"
    provider_args+=(--mirror "localhost:$(client_port $m)")
fi

# --- SQLancer ----------------------------------------------------------------------------------------------------

sqlancer_args=(--num-threads "$threads" --num-tries "$threads" --timeout-seconds "$duration" --num-queries "$queries"
    --random-seed "$seed" --host localhost --port "$(client_port 0)" --username "" --password ""
    --database-prefix db --num-statement-kind-retries 20)
# A failed INSERT is retried with new values, up to SQLancer's default of 1000 times; once a table's key has run out of
# values (a BOOLEAN or TINYINT primary key, say), each of a database's inserts spent 1000 constraint violations, and
# with --num-statement-kind-retries 20 they spend 20
$use_reducer && sqlancer_args+=(--use-reducer)
# --- node failures -----------------------------------------------------------------------------------------------

# Every kill_interval seconds: kill -9 a random node, node 0 included (SQLancer is connected to every node), wait
# until its client port closes, restart it and wait until it has rejoined. Each event is a line of chaos.log; a rejoin that
# does not complete within 600 s ends the loop and fails the run. The loop stops when $results/chaos.stop exists,
# after the node it is handling has rejoined, so the run ends with every node up.
# rejoin_node I N: restarts node I after kill N and waits until it has rejoined, up to 600 s. A restart that exits
# because a port is taken ("Address already in use", by another process's outgoing connection) is tried again, up
# to 5 times, 10 s apart. The FATAL lines such an attempt writes to volt.log are listed in chaos.excluded and not
# counted against the run.
rejoin_node() {
    local log=$cluster/restart$1-$2.log attempt deadline=$((SECONDS + 600)) before
    local vlog=$cluster/node$1/voltdbroot/log/volt.log
    for attempt in 1 2 3 4 5; do
        before=$(wc -l <"$vlog" 2>/dev/null || echo 0)
        start_node "$1" "$log"
        while [ $SECONDS -lt $deadline ]; do
            grep -q "Server completed initialization" "$log" 2>/dev/null && return 0
            grep -q "unrecoverable error" "$log" 2>/dev/null && break
            sleep 1
        done
        grep -q "Address already in use" "$log" 2>/dev/null || return 1
        # the attempt is exiting (it prints "unrecoverable error" as it does); give its log a moment
        sleep 2
        echo "node$1 $((before + 1)) $(wc -l <"$vlog")" >>"$results/chaos.excluded"
        echo "$(date '+%H:%M:%S') rejoin $2: node$1 could not bind a port (attempt $attempt), retrying" >>"$results/chaos.log"
        mv "$log" "${log%.log}-bind$attempt.log"
        sleep 10
    done
    return 1
}

chaos_loop() {
    local n=0 i pid t0
    while true; do
        for _ in $(seq 1 "$kill_interval"); do
            [ -e "$results/chaos.stop" ] && return 0
            sleep 1
        done
        i=$((RANDOM % nodes))
        n=$((n + 1))
        # the PID start_node recorded; a pattern match on the command line is not reliable, as the server's command
        # line carries a long classpath before its voltdbroot directory
        pid=$(cat "$cluster/node$i.pid" 2>/dev/null)
        if [ -z "$pid" ] || ! is_server "$pid"; then
            echo "$(date '+%H:%M:%S') kill $n: node$i is not running" >>"$results/chaos.log"
            return 1
        fi
        kill -9 "$pid"
        t0=$(date +%s)
        echo "$(date '+%H:%M:%S') kill $n: node$i (pid $pid)" >>"$results/chaos.log"
        while port_in_use "$(client_port $i)"; do sleep 1; done
        # the survivors must notice the failure before the node comes back
        sleep 5
        if ! rejoin_node "$i" "$n"; then
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
    log "Killing and rejoining a random node every $kill_interval s (chaos.log)"
fi

# Every crash_interval seconds: kill -9 every node at once, wait until their client ports close, restart them all with
# the same command, which recovers the database from the last snapshot and the command log, and wait until every node
# has finished ("Server completed initialization"), up to 600 s. A restart that cannot bind a port is tried again, as in
# rejoin_node. Each event is a line of crash.log; a recovery that does not complete ends the loop and fails the run. The
# loop stops when $results/crash.stop exists, after the recovery it is waiting for, so the run ends with every node up.
crash_loop() {
    local n=0 i pid t0 up deadline log vlog pids attempts befores
    while true; do
        for _ in $(seq 1 "$crash_interval"); do
            [ -e "$results/crash.stop" ] && return 0
            sleep 1
        done
        n=$((n + 1))
        pids=()
        for ((i = 0; i < nodes; i++)); do
            pid=$(cat "$cluster/node$i.pid" 2>/dev/null)
            if [ -z "$pid" ] || ! is_server "$pid"; then
                echo "$(date '+%H:%M:%S') crash $n: node$i is not running" >>"$results/crash.log"
                return 1
            fi
            pids+=("$pid")
        done
        kill -9 "${pids[@]}"
        t0=$(date +%s)
        echo "$(date '+%H:%M:%S') crash $n: every node killed (pids ${pids[*]})" >>"$results/crash.log"
        for ((i = 0; i < nodes; i++)); do
            while port_in_use "$(client_port $i)"; do sleep 1; done
        done
        attempts=()
        befores=()
        for ((i = 0; i < nodes; i++)); do
            attempts[i]=1
            befores[i]=$(wc -l <"$cluster/node$i/voltdbroot/log/volt.log" 2>/dev/null || echo 0)
            start_node $i "$cluster/recover$i-$n.log"
        done
        deadline=$((SECONDS + 600))
        while true; do
            up=0
            for ((i = 0; i < nodes; i++)); do
                log=$cluster/recover$i-$n.log
                if grep -q "Server completed initialization" "$log" 2>/dev/null; then
                    up=$((up + 1))
                elif grep -q "Address already in use" "$log" 2>/dev/null && [ "${attempts[i]}" -lt 5 ]; then
                    # another process's outgoing connection took the port; the attempt's FATAL lines are not the run's
                    sleep 2
                    vlog=$cluster/node$i/voltdbroot/log/volt.log
                    echo "node$i $((befores[i] + 1)) $(wc -l <"$vlog")" >>"$results/chaos.excluded"
                    echo "$(date '+%H:%M:%S') recover $n: node$i could not bind a port (attempt ${attempts[i]}), retrying" >>"$results/crash.log"
                    mv "$log" "${log%.log}-bind${attempts[i]}.log"
                    attempts[i]=$((attempts[i] + 1))
                    befores[i]=$(wc -l <"$vlog" 2>/dev/null || echo 0)
                    start_node $i "$log"
                fi
            done
            [ "$up" -eq "$nodes" ] && break
            if [ $SECONDS -ge $deadline ]; then
                echo "$(date '+%H:%M:%S') recover $n: $up of $nodes nodes up after 600 s" >>"$results/crash.log"
                return 1
            fi
            sleep 1
        done
        echo "$(date '+%H:%M:%S') recover $n: every node up, $(($(date +%s) - t0)) s after the crash" >>"$results/crash.log"
    done
}

crash_pid=
if [ "$crash_interval" -gt 0 ]; then
    : >"$results/crash.log"
    crash_loop &
    crash_pid=$!
    log "Killing every node and recovering the cluster every $crash_interval s (crash.log)"
fi

# --upgrade-from: upgrade_at seconds into the run, upgrade the nodes one at a time, from the last to node 0: stop the
# node with "voltadmin stop" (which VoltDB refuses for a few seconds after a rejoin, and so is retried), wait until it
# has exited, start it on --voltdb, which rejoins it, and wait until it has, up to 600 s. Each event is a line of
# upgrade.log; a node that cannot be stopped or does not rejoin ends the loop and fails the run.
upgrade_loop() {
    local i j other info hid ip out pid t0 up
    for _ in $(seq 1 "$upgrade_at"); do
        [ -e "$results/upgrade.stop" ] && return 0
        sleep 1
    done
    echo "$(date '+%H:%M:%S') upgrade: from $(upgrade_cmd "$upgrade_from/bin/voltdb" --version 2>&1 | tail -1) to $("$voltdb_home/bin/voltdb" --version 2>&1 | tail -1)" >>"$results/upgrade.log"
    for ((i = nodes - 1; i >= 0; i--)); do
        t0=$(date +%s)
        other=$(((i + 1) % nodes))
        # voltadmin stop names the node by the address VoltDB reports for it, which need not be localhost
        info=$(echo "exec @SystemInformation OVERVIEW;" | "$voltdb_home/bin/sqlcmd" --port="$(client_port $other)" 2>/dev/null)
        hid=$(echo "$info" | awk -v p="$(internal_port $i)" '$2 == "INTERNALPORT" && $3 == p { print $1 }')
        ip=$(echo "$info" | awk -v h="$hid" '$1 == h && $2 == "IPADDRESS" { print $3 }')
        if [ -z "$ip" ]; then
            echo "$(date '+%H:%M:%S') upgrade: node$i not found in @SystemInformation" >>"$results/upgrade.log"
            return 1
        fi
        pid=$(cat "$cluster/node$i.pid" 2>/dev/null)
        # done once the node's server has exited; a refusal leaves it running, and is retried
        for j in $(seq 1 20); do
            out=$("$voltdb_home/bin/voltadmin" stop -H "localhost:$(admin_port $other)" "$ip:$(internal_port $i)" 2>&1 |
                grep -v '^ *$' | tail -1)
            for _ in $(seq 1 30); do
                kill -0 "$pid" 2>/dev/null || break
                sleep 1
            done
            kill -0 "$pid" 2>/dev/null || break
            echo "$(date '+%H:%M:%S') upgrade: stopping node$i refused (attempt $j): $out" >>"$results/upgrade.log"
            sleep 10
        done
        if kill -0 "$pid" 2>/dev/null; then
            echo "$(date '+%H:%M:%S') upgrade: node$i did not stop" >>"$results/upgrade.log"
            return 1
        fi
        echo "$(date '+%H:%M:%S') upgrade: node$i stopped" >>"$results/upgrade.log"
        node_home[i]=$voltdb_home
        node_java[i]=
        start_node $i "$cluster/upgrade$i.log"
        up=false
        for _ in $(seq 1 600); do
            grep -q "Server completed initialization" "$cluster/upgrade$i.log" 2>/dev/null && { up=true; break; }
            # kill -0, not is_server: the launcher runs as a script for a moment before it execs the server
            kill -0 "$(cat "$cluster/node$i.pid")" 2>/dev/null || break
            sleep 1
        done
        if ! $up; then
            echo "$(date '+%H:%M:%S') upgrade: node$i did not rejoin on the new kit" >>"$results/upgrade.log"
            return 1
        fi
        echo "$(date '+%H:%M:%S') upgrade: node$i upgraded, $(($(date +%s) - t0)) s" >>"$results/upgrade.log"
    done
    echo "$(date '+%H:%M:%S') upgrade: done" >>"$results/upgrade.log"
}

upgrade_pid=
if [ -n "$upgrade_from" ]; then
    : >"$results/upgrade.log"
    upgrade_loop &
    upgrade_pid=$!
    log "Upgrading the cluster in service, one node at a time, $upgrade_at s into the run (upgrade.log)"
fi

# Every resize_interval seconds, by turns: grow the cluster by k+1 nodes, then shrink it by the same nodes. A grow
# starts them with "voltdb start --add" in their own directories ($cluster/add<N>-<j>, kept for their logs) and waits
# until they have joined and until "voltadmin resize --test" would remove them: VoltDB publishes the new topology some
# seconds after the join (40 s in a trial), and a shrink planned before then would remove the original nodes, which it
# refuses as more than half the cluster. A shrink runs "voltadmin resize", which removes the partition group with
# the highest partition ids, those the last grow added, and waits until their servers exit. Each step has 600 s; one
# that fails or times out ends the loop and fails the run. Each event is a line of resize.log. The loop stops when
# $results/resize.stop exists, after its step, so the run ends at a settled size.
grows=0
shrinks=0
resize_hosts() {
    "$voltdb_home/bin/voltadmin" resize --test -H "localhost:$(admin_port 0)" 2>&1 | tail -1
}
# start_added N: starts the k+1 nodes of grow N, each initialized afresh in $cluster/add<N>-<j>
start_added() {
    local n=$1 j slot dir
    for ((j = 0; j < added; j++)); do
        slot=$((added_slot + j))
        dir=$cluster/add$n-$j
        "$voltdb_home/bin/voltdb" init --force -D "$dir" -C "$cluster/deployment.xml" \
            ${license_args[@]+"${license_args[@]}"} >"$dir.init.log" 2>&1 || return 1
        (nohup "$voltdb_home/bin/voltdb" start --add -D "$dir" -H "$(hosts internal)" \
            --client="$(client_port $slot)" --admin="$(admin_port $slot)" --internal="$(internal_port $slot)" \
            --zookeeper="$(port $slot 3)" --replication="$(port $slot 4)" --metrics="$(port $slot 5)" \
            >"$dir.start.log" 2>&1 &
            echo $! >"$dir.pid")
    done
}
# grow N: adds k+1 nodes and waits until the cluster would remove them again. VoltDB rejects a join while a catalog
# update is in progress, and SQLancer's DDL makes one likely: the joining nodes then exit with a FATAL line. Such an
# attempt is set aside (its directories renamed add<N>-<j>-rejected<A>, whose FATAL lines are not counted) and tried
# again 5 s later, up to 10 times.
grow() {
    local n=$1 j attempt deadline=$((SECONDS + 600)) ready out rejected originals
    for attempt in 1 2 3 4 5 6 7 8 9 10; do
        start_added $n || return 1
        rejected=false
        while [ $SECONDS -lt $deadline ]; do
            ready=0
            for ((j = 0; j < added; j++)); do
                grep -q "Server completed initialization" "$cluster/add$n-$j.start.log" 2>/dev/null && ready=$((ready + 1))
                grep -q "Can't do elastic join while catalog update is in progress" "$cluster/add$n-$j.start.log" 2>/dev/null &&
                    rejected=true
            done
            [ "$ready" -eq "$added" ] || $rejected && break
            # kill -0, not is_server: the PID is the launcher's until it execs the server's JVM
            for ((j = 0; j < added; j++)); do
                kill -0 "$(cat "$cluster/add$n-$j.pid")" 2>/dev/null && continue
                # exited: a rejected join (its log may have been read just before the line was written), or a failure
                sleep 1
                grep -q "Can't do elastic join while catalog update is in progress" "$cluster/add$n-$j.start.log" || return 1
                rejected=true
            done
            $rejected && break
            sleep 1
        done
        $rejected || break
        echo "$(date '+%H:%M:%S') grow $n: join rejected while a catalog update was in progress (attempt $attempt), retrying" >>"$results/resize.log"
        for ((j = 0; j < added; j++)); do
            kill -9 "$(cat "$cluster/add$n-$j.pid")" 2>/dev/null
        done
        sleep 5
        for ((j = 0; j < added; j++)); do
            for f in "" .init.log .start.log .pid; do
                mv "$cluster/add$n-$j$f" "$cluster/add$n-$j-rejected$attempt$f" 2>/dev/null
            done
        done
        [ $SECONDS -lt $deadline ] || return 1
    done
    $rejected && return 1
    [ "$ready" -eq "$added" ] || return 1
    # the original nodes are hosts 0 to nodes-1: done once the planned removal names none of them (paste, not
    # seq -s, as BSD seq ends the list with the separator, which makes an empty alternative)
    originals=$(seq 0 $((nodes - 1)) | paste -sd '|' -)
    while [ $SECONDS -lt $deadline ]; do
        out=$(resize_hosts)
        if echo "$out" | grep -q "Host Ids: \[" && ! echo "$out" | grep -qE "Host Ids: \[(.*[ ,\[])?($originals)[],]"; then
            echo "$(date '+%H:%M:%S') grow $n: planned removal: ${out#INFO: }" >>"$results/resize.log"
            return 0
        fi
        sleep 5
    done
    return 1
}
# ENG-30622: a table dropped while an elastic remove streams its index snapshot hangs the remove for good, so DDL is
# held (the provider's --ddl-hold) from a few seconds before the shrink, letting a catalog update in flight finish,
# until it is done
shrink() {
    local rc=0
    touch "$results/ddl.hold"
    sleep 3
    shrink_steps "$1" || rc=1
    rm -f "$results/ddl.hold"
    return $rc
}
shrink_steps() {
    local n=$1 j alive deadline=$((SECONDS + 600)) out
    out=$("$voltdb_home/bin/voltadmin" resize -H "localhost:$(admin_port 0)" 2>&1 | tail -1)
    echo "$(date '+%H:%M:%S') shrink $n: $out" >>"$results/resize.log"
    echo "$out" | grep -q "^INFO" || return 1
    while [ $SECONDS -lt $deadline ]; do
        alive=0
        for ((j = 0; j < added; j++)); do
            is_server "$(cat "$cluster/add$n-$j.pid")" && alive=$((alive + 1))
        done
        [ "$alive" -eq 0 ] && return 0
        sleep 1
    done
    return 1
}
resize_loop() {
    local n=0 t0
    while true; do
        for _ in $(seq 1 "$resize_interval"); do
            [ -e "$results/resize.stop" ] && return 0
            sleep 1
        done
        n=$((n + 1))
        t0=$SECONDS
        echo "$(date '+%H:%M:%S') grow $n: adding $added node(s)" >>"$results/resize.log"
        if ! grow $n; then
            echo "$(date '+%H:%M:%S') grow $n: FAILED after $((SECONDS - t0)) s" >>"$results/resize.log"
            return 1
        fi
        echo "$(date '+%H:%M:%S') grow $n: done, $((SECONDS - t0)) s" >>"$results/resize.log"
        for _ in $(seq 1 "$resize_interval"); do
            [ -e "$results/resize.stop" ] && return 0
            sleep 1
        done
        t0=$SECONDS
        if ! shrink $n; then
            echo "$(date '+%H:%M:%S') shrink $n: FAILED after $((SECONDS - t0)) s" >>"$results/resize.log"
            return 1
        fi
        echo "$(date '+%H:%M:%S') shrink $n: done, $((SECONDS - t0)) s" >>"$results/resize.log"
    done
}
resize_pid=
if [ "$resize_interval" -gt 0 ]; then
    : >"$results/resize.log"
    resize_loop &
    resize_pid=$!
    log "Growing and shrinking the cluster by $added node(s) by turns, every $resize_interval s (resize.log)"
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
upgrade_failed=false
upgraded=0
if [ -n "$upgrade_pid" ]; then
    touch "$results/upgrade.stop"
    wait "$upgrade_pid" || upgrade_failed=true
    upgraded=$(grep -c " upgrade: node[0-9]* upgraded" "$results/upgrade.log")
    # an upgrade that never started (a run shorter than --upgrade-at) fails too: the run did not do what it says
    grep -q " upgrade: done" "$results/upgrade.log" || upgrade_failed=true
    log "Upgrade: $upgraded of $nodes nodes upgraded$($upgrade_failed && echo ", FAILED")"
fi
crash_failed=false
crashes=0
recoveries=0
if [ -n "$crash_pid" ]; then
    touch "$results/crash.stop"
    wait "$crash_pid" || crash_failed=true
    crashes=$(grep -c " crash [0-9]*: every node killed" "$results/crash.log")
    recoveries=$(grep -c " recover [0-9]*: every node up" "$results/crash.log")
    log "Crashes: $crashes, recoveries: $recoveries$($crash_failed && echo ", the last one FAILED")"
fi
resize_failed=false
if [ -n "$resize_pid" ]; then
    touch "$results/resize.stop"
    wait "$resize_pid" || resize_failed=true
    grows=$(grep -c " grow [0-9]*: done" "$results/resize.log")
    shrinks=$(grep -c " shrink [0-9]*: done" "$results/resize.log")
    log "Resizes: $grows grows, $shrinks shrinks$($resize_failed && echo ", the last step FAILED")"
fi

# --- collect -----------------------------------------------------------------------------------------------------

down=()
for ((i = 0; i < nodes; i++)); do
    port_in_use "$(client_port $i)" || down+=("node$i")
    $xdcr && { port_in_use "$(client_port $((xdcr_slot + i)))" || down+=("xdcr$i"); }
done
stop_cluster
for ((i = 0; i < nodes; i++)); do
    mkdir -p "$results/servers/node$i"
    cp "$cluster/start$i.log" "$cluster/init$i.log" "$cluster"/restart$i-*.log "$cluster"/recover$i-*.log "$cluster/upgrade$i.log" "$results/servers/node$i/" 2>/dev/null
    cp -R "$cluster/node$i/voltdbroot/log/." "$results/servers/node$i/" 2>/dev/null
done
for d in "$cluster"/add*-*; do
    [ -d "$d" ] || continue
    a=$(basename "$d")
    mkdir -p "$results/servers/$a"
    cp "$d.init.log" "$d.start.log" "$results/servers/$a/" 2>/dev/null
    cp -R "$d/voltdbroot/log/." "$results/servers/$a/" 2>/dev/null
done
if $xdcr; then
    for ((i = 0; i < nodes; i++)); do
        mkdir -p "$results/servers/xdcr$i"
        cp "$cluster/start-xdcr$i.log" "$cluster/init-xdcr$i.log" "$results/servers/xdcr$i/" 2>/dev/null
        cp -R "$cluster/xdcr$i/voltdbroot/log/." "$results/servers/xdcr$i/" 2>/dev/null
    done
fi
if [ -n "$mirror_home" ]; then
    mkdir -p "$results/servers/mirror"
    cp "$cluster/start-mirror.log" "$cluster/init-mirror.log" "$results/servers/mirror/" 2>/dev/null
    cp -R "$cluster/mirror/voltdbroot/log/." "$results/servers/mirror/" 2>/dev/null
fi
cp "$cluster/deployment.xml" "$results/"
# XDCR's conflict log: each database writes through one cluster only, so XDCR has nothing to resolve, and any
# conflict it logged fails the run
conflicts=0
if $xdcr; then
    mkdir -p "$results/xdcr_conflicts"
    for f in $(find "$cluster" -path "*xdcr_conflicts*" -type f 2>/dev/null); do
        cp "$f" "$results/xdcr_conflicts/$(echo "${f#"$cluster"/}" | tr / _)"
        conflicts=$((conflicts + $(wc -l <"$f")))
    done
fi
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
# FATAL lines in the server logs, but for those of restarts that could not bind a port (chaos.excluded)
fatal=0
for ((i = 0; i < nodes; i++)); do
    n=$(awk -v node="node$i" -v excluded="$results/chaos.excluded" '
        BEGIN { while ((getline line < excluded) > 0) { split(line, f, " "); if (f[1] == node) { lo[++k] = f[2]; hi[k] = f[3] } } }
        / FATAL / { for (j = 1; j <= k; j++) if (FNR >= lo[j] && FNR <= hi[j]) next; c++ }
        END { print c + 0 }' "$results/servers/node$i/volt.log" 2>/dev/null)
    fatal=$((fatal + ${n:-0}))
done
for f in "$results"/servers/xdcr*/volt.log; do
    [ -e "$f" ] || continue
    fatal=$((fatal + $(grep -c " FATAL " "$f")))
done
for f in "$results"/servers/add*-*/volt.log; do
    [ -e "$f" ] || continue
    case $f in *-rejected*) continue ;; esac
    fatal=$((fatal + $(grep -c " FATAL " "$f")))
done

# --- summary -----------------------------------------------------------------------------------------------------

result=PASS
[ "$sqlancer_exit" -eq 0 ] && [ ${#failures[@]} -eq 0 ] && [ ${#down[@]} -eq 0 ] && [ "$fatal" -eq 0 ] &&
    ! $chaos_failed && ! $resize_failed && ! $crash_failed && ! $upgrade_failed && [ "$conflicts" -eq 0 ] || result=FAIL
{
    echo "RESULT: $result"
    echo "run: $run_id"
    echo "progress: $(grep "Executed" "$results/sqlancer/out.log" | tail -1)"
    echo "sqlancer exit code: $sqlancer_exit"
    echo "failed databases: ${#failures[@]}"
    echo "nodes down at the end: ${down[*]:-none}"
    echo "FATAL lines in server logs: $fatal"
    # ENG-30602: failures that did not recur when rerun at once (VoltDBTransientCheck), counted, not failed
    echo "transient results skipped (ENG-30602): $(grep -c "^ENG-30602 transient:" "$results/sqlancer/out.log" 2>/dev/null || true)"
    [ "$kill_interval" -gt 0 ] && echo "node failures: $kills kills, $rejoins rejoins$($chaos_failed && echo ", a rejoin FAILED (chaos.log)")"
    [ "$crash_interval" -gt 0 ] && echo "crashes: $crashes crashes, $recoveries recoveries$($crash_failed && echo ", a recovery FAILED (crash.log)")"
    [ -n "$upgrade_from" ] && echo "upgrade: $upgraded of $nodes nodes upgraded$($upgrade_failed && echo ", FAILED (upgrade.log)")"
    $xdcr && echo "XDCR conflicts logged: $conflicts$([ "$conflicts" -eq 0 ] || echo " (xdcr_conflicts/)")"
    $xdcr && echo "databases XDCR compared after convergence: $(grep -c "^XDCR converged:" "$results/sqlancer/out.log" 2>/dev/null || true)"
    [ "$crash_interval" -gt 0 ] && echo "databases DURABLE compared across a crash: $(grep -c "^DURABLE across a crash:" "$results/sqlancer/out.log" 2>/dev/null || true)"
    [ "$resize_interval" -gt 0 ] && echo "resizes: $grows grows, $shrinks shrinks$($resize_failed && echo ", a step FAILED (resize.log)")"
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
