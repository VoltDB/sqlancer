#!/bin/bash
#
# Runs the minimal reproduction of every VoltDB bug the provider found (voltdb/repros/) against a VoltDB kit, each on
# a fresh server, and reports which still reproduce, which are fixed and which changed. See README "Bug
# reproductions" (src/sqlancer/voltdb/README.md).
#
# Exit code: 0 if every reproduction gave its bug's result or the fixed one, 1 if one changed or could not run, 2 for
# a bad setup.

usage() {
    cat <<EOF
Usage: $0 --voltdb DIR [options]

  --voltdb DIR       the VoltDB kit (bin/voltdb, bin/sqlcmd)
  --license FILE     license file for voltdb init (default: none, the kit's own)
  --port-base N      first port; a reproduction's node i uses N + 10*i + 0..5 (default 31000)
  --results DIR      where the run's directory is created (default ./repro-results)
  --only LIST        comma-separated tickets to run (default: every file in voltdb/repros)
  -h, --help         this help
EOF
}

die() {
    echo "run-repros: $*" >&2
    exit 2
}

repo_dir=$(cd "$(dirname "$0")/.." && pwd)
repros_dir=$repo_dir/voltdb/repros
voltdb_home=
license=
port_base=31000
results_root=./repro-results
only=
while [ $# -gt 0 ]; do
    case $1 in
    --voltdb) voltdb_home=$2; shift 2 ;;
    --license) license=$2; shift 2 ;;
    --port-base) port_base=$2; shift 2 ;;
    --results) results_root=$2; shift 2 ;;
    --only) only=$2; shift 2 ;;
    -h | --help) usage; exit 0 ;;
    *) usage >&2; die "unknown option $1" ;;
    esac
done
[ -n "$voltdb_home" ] || { usage >&2; die "--voltdb is required"; }
[ -x "$voltdb_home/bin/voltdb" ] || die "no bin/voltdb in $voltdb_home"
license_args=()
[ -z "$license" ] || license_args=(--license "$license")
# the kit's bin first on the PATH, as sqlcmd otherwise prints a note that its scripts are not there
export PATH=$voltdb_home/bin:$PATH

run_id=repros-$(date -u +%Y%m%dT%H%M%SZ)
results=$results_root/$run_id
mkdir -p "$results" || die "cannot create $results"
results=$(cd "$results" && pwd)
work=$results/work

port() { echo $((port_base + 10 * $1 + $2)); }
port_in_use() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

# header VALUE of a reproduction file: header FILE NAME prints every "-- NAME: value" line's value, one per line
header() {
    sed -n "s/^-- $2: //p" "$1"
}

# start_cluster NODES KFACTOR SITES: a fresh cluster in $work, every node with the whole host list (-H), so that no
# node can join a cluster outside this run
start_cluster() {
    local n=$1 k=$2 sites=$3 i hosts=
    rm -rf "$work"
    mkdir -p "$work"
    printf '<?xml version="1.0"?>\n<deployment>\n    <cluster sitesperhost="%s" kfactor="%s"/>\n    <httpd enabled="false"/>\n</deployment>\n' \
        "$sites" "$k" >"$work/deployment.xml"
    for ((i = 0; i < n; i++)); do
        for j in 0 1 2 3 4 5; do
            port_in_use "$(port $i $j)" && { echo "port $(port $i $j) is in use"; return 1; }
        done
        hosts+="${hosts:+,}localhost:$(port $i 2)"
    done
    for ((i = 0; i < n; i++)); do
        "$voltdb_home/bin/voltdb" init --force -D "$work/node$i" -C "$work/deployment.xml" \
            ${license_args[@]+"${license_args[@]}"} >"$work/init$i.log" 2>&1 || { echo "voltdb init failed"; return 1; }
        (nohup "$voltdb_home/bin/voltdb" start -D "$work/node$i" -c "$n" -H "$hosts" \
            --client="$(port $i 0)" --admin="$(port $i 1)" --internal="$(port $i 2)" \
            --zookeeper="$(port $i 3)" --replication="$(port $i 4)" --metrics="$(port $i 5)" \
            >"$work/start$i.log" 2>&1 &
            echo $! >"$work/node$i.pid")
    done
    for _ in $(seq 1 180); do
        local up=0
        for ((i = 0; i < n; i++)); do
            grep -q "Server completed initialization" "$work/start$i.log" 2>/dev/null && up=$((up + 1))
        done
        [ "$up" -eq "$n" ] && return 0
        sleep 1
    done
    echo "the cluster did not start within 180 s"
    return 1
}

# whether every node of the cluster is still running
cluster_alive() {
    local i
    for ((i = 0; i < $1; i++)); do
        kill -0 "$(cat "$work/node$i.pid")" 2>/dev/null || return 1
    done
}

stop_cluster() {
    local i
    "$voltdb_home/bin/voltadmin" shutdown --force -H "localhost:$(port 0 1)" >/dev/null 2>&1 &
    local s=$!
    for _ in $(seq 1 30); do kill -0 $s 2>/dev/null || break; sleep 1; done
    kill -9 $s 2>/dev/null
    for ((i = 0; i < $1; i++)); do
        kill -9 "$(cat "$work/node$i.pid" 2>/dev/null)" 2>/dev/null
    done
    sleep 1
}

# sqlcmd's output, without its timings and column padding: "(Returned 3 rows in 0.01s)" becomes "(3 rows)", the
# dashes under the column names go, and runs of spaces become one
normalize() {
    sed -E -e 's/\(Returned ([0-9]+) rows? in [0-9.]+s\)/(\1 rows)/' -e '/^-+( +-+)*$/d' -e 's/[[:space:]]+/ /g' \
        -e 's/^ //' -e 's/ $//' | grep -v '^$'
}

# matches EXPECTED OUTPUT: whether the normalized OUTPUT is EXPECTED: "contains: TEXT" (a line holds TEXT), "crash"
# (handled by the caller) or the exact lines
matches() {
    local expected=$1 output=$2
    case $expected in
    contains:*) grep -qF -- "$(echo "${expected#contains: }" | head -1)" <<<"$output" ;;
    *) [ "$expected" = "$output" ] ;;
    esac
}

run_sql() {
    local f=$1 log=$2 nodes kfactor sites bug fixed setup check output alive
    nodes=$(header "$f" nodes); nodes=${nodes:-1}
    kfactor=$(header "$f" kfactor); kfactor=${kfactor:-0}
    sites=$(header "$f" sites); sites=${sites:-4}
    bug=$(header "$f" bug)
    fixed=$(header "$f" fixed)
    [ -n "$bug" ] && [ -n "$fixed" ] || { echo "ERROR: no '-- bug:' or '-- fixed:' header" >"$log"; verdict=ERROR; return; }
    grep -q '^-- check$' "$f" || { echo "ERROR: no '-- check' line" >"$log"; verdict=ERROR; return; }
    setup=$(sed -n '/^-- check$/q;/^--/d;p' "$f")
    check=$(sed -n '/^-- check$/,$p' "$f" | sed '/^--/d')
    if ! start_cluster "$nodes" "$kfactor" "$sites" >"$log" 2>&1; then
        verdict=ERROR
        stop_cluster "$nodes"
        return
    fi
    {
        echo "== setup"
        echo "$setup" | "$voltdb_home/bin/sqlcmd" --port="$(port 0 0)" --stop-on-error=false 2>&1
    } >>"$log"
    output=$(echo "$check" | "$voltdb_home/bin/sqlcmd" --port="$(port 0 0)" --stop-on-error=false 2>&1 |
        grep -v "^Warning: voltdb database\|^  But this script\|^    /\|^The 'voltdb' database scripts\|^For ease of use" |
        normalize)
    # a server that crashed can take minutes to exit (writing its crash files) after the connection is lost: on the
    # lab's Linux agents ENG-30595's server was still up 20 s after sqlcmd lost it (lab builds #42 and #43)
    # A crash reproduction is watched for 10 s even when sqlcmd got every answer: a server can answer a statement and
    # die in the fragments that follow it (ENG-30664 reported FIXED in lab build #49)
    alive=true
    local i
    for i in $(seq 1 300); do
        sleep 1
        cluster_alive "$nodes" || { alive=false; break; }
        [ "$bug" = crash ] && [ "$i" -lt 10 ] && continue
        echo "$output" | grep -q "was lost before a response was received" || break
    done
    # on the lab's agents a crashed server can still be writing its crash files (a core dump) after the waits above
    # (ENG-30595, lab build #46; ENG-30664, lab builds #49 and #50, whose server answered the INSERT and then died):
    # a crash in the server's output is a crash, after a lost connection or for a crash reproduction
    if $alive && { [ "$bug" = crash ] || echo "$output" | grep -q "was lost before a response was received"; } &&
        grep -qE "SIGSEGV|SIGBUS|SIGABRT|SIGILL|A fatal error has been detected|libc\+\+abi|FATAL" "$work"/start*.log \
            "$work"/node*/voltdbroot/log/volt.log 2>/dev/null; then
        alive=false
        echo "== a server crashed but had not exited" >>"$log"
    fi
    {
        echo "== check (normalized)"
        echo "$output"
        echo "== servers alive: $alive"
    } >>"$log"
    if ! $alive; then
        grep -h -m1 "libc++abi\|FATAL" "$work"/start*.log >>"$log" 2>/dev/null
        if [ "$bug" = crash ]; then verdict=BROKEN; else verdict=CHANGED; fi
    elif [ "$bug" != crash ] && matches "$bug" "$output"; then
        verdict=BROKEN
    elif matches "$fixed" "$output"; then
        verdict=FIXED
    else
        verdict=CHANGED
    fi
    stop_cluster "$nodes"
}

# a script reproduction: exit 0 while the bug reproduces, 1 once it is fixed, anything else an error
run_sh() {
    local f=$1 log=$2 rc
    mkdir -p "$work"
    VOLTDB_HOME=$voltdb_home VOLTDB_LICENSE=$license PORT_BASE=$port_base WORKDIR=$work timeout_secs=1800 bash "$f" >"$log" 2>&1
    rc=$?
    case $rc in
    0) verdict=BROKEN ;;
    1) verdict=FIXED ;;
    *) verdict=ERROR ;;
    esac
    settle_ports
    rm -rf "$work"
}

# after a script reproduction: kill what it left running from its work directory, and wait until its ports (node
# slots 0 to 29, which take in an XDCR peer's) are free, so the next reproduction can start (lab build #55's ENG-30679
# found port 31000 still taken in the second ENG-30678 exited)
settle_ports() {
    local slot j busy
    pkill -9 -f "$work/" 2>/dev/null
    for _ in $(seq 1 60); do
        busy=
        for slot in $(seq 0 29); do
            for j in 0 1 2 3 4 5; do
                (exec 3<>"/dev/tcp/127.0.0.1/$(port "$slot" "$j")") 2>/dev/null && { busy=$(port "$slot" "$j"); break 2; }
            done
        done
        [ -z "$busy" ] && return
        sleep 1
    done
    echo "port $busy is still in use 60 s after a reproduction" >&2
}

# a reproduction with a "-- manual: reason" header does not reproduce every time, and one with a "-- platforms: LIST"
# header (uname -s values) reproduces only there; either runs only when --only names it
os=$(uname -s)
files=()
manual=()
for f in "$repros_dir"/ENG-*.sql "$repros_dir"/ENG-*.sh; do
    [ -e "$f" ] || continue
    t=$(basename "${f%.*}")
    if [ -n "$only" ]; then
        echo ",$only," | grep -q ",$t," || continue
    elif [ -n "$(header "$f" manual)" ]; then
        manual+=("$t: $(header "$f" manual | head -1)")
        continue
    elif [ -n "$(header "$f" platforms)" ] && ! echo " $(header "$f" platforms | head -1) " | grep -q " $os "; then
        manual+=("$t: reproduces only on $(header "$f" platforms | head -1), not on $os")
        continue
    fi
    files+=("$f")
done
[ ${#files[@]} -gt 0 ] || die "no reproductions in $repros_dir"

version=$("$voltdb_home/bin/voltdb" --version 2>&1 | tail -1)
echo "VoltDB: $version; ${#files[@]} reproductions; results in $results"
broken=0 fixed_n=0 changed=0 errors=0
: >"$results/table.txt"
SECONDS=0
for f in "${files[@]}"; do
    t=$(basename "${f%.*}")
    verdict=
    case $f in
    *.sql) run_sql "$f" "$results/$t.log" ;;
    *.sh) run_sh "$f" "$results/$t.log" ;;
    esac
    case $verdict in
    BROKEN) broken=$((broken + 1)) ;;
    FIXED) fixed_n=$((fixed_n + 1)) ;;
    CHANGED) changed=$((changed + 1)) ;;
    *) errors=$((errors + 1)) ;;
    esac
    line=$(printf '%-9s %-12s %s' "$verdict" "$t" "$(header "$f" summary | head -1 | cut -c1-150)")
    echo "$line"
    echo "$line" >>"$results/table.txt"
done
result=PASS
[ "$changed" -eq 0 ] && [ "$errors" -eq 0 ] || result=FAIL
{
    echo "RESULT: $result"
    echo "VoltDB: $version"
    echo "reproductions: ${#files[@]}: $broken still broken, $fixed_n fixed, $changed changed, $errors errors"
    echo "time: $SECONDS s"
    for m in ${manual[@]+"${manual[@]}"}; do
        echo "not run: $m"
    done
    echo
    cat "$results/table.txt"
} >"$results/summary.txt"
echo
head -4 "$results/summary.txt"
[ "$result" = PASS ]
