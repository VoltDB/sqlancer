#!/usr/bin/env bash
#
# Measures the code SQLancer's VoltDB provider exercises: runs run-sqlancer.sh in the nightly job's run shapes
# against a VoltDB build whose server JVMs carry the JaCoCo agent and whose execution engine was built with clang's
# source-based coverage, then reports line and branch coverage of the Java and C++ code, per package and directory.
#
# Usage: voltdb/run-coverage.sh --voltdb <instrumented build> --results <dir> [options]
# Run with --help for the options and for how to build the engine. Exit code: 0 every run passed, 1 a run failed
# (the coverage report is still written), 2 the runs could not be set up.

set -u

usage() {
    cat <<'EOF'
Usage: voltdb/run-coverage.sh --voltdb <instrumented build> --results <dir> [options]

  --voltdb DIR       a VoltDB build (bin/voltdb) whose execution engine was built with clang's source-based
                     coverage: in a clean checkout, CFLAGS/CXXFLAGS="-fprofile-instr-generate -fcoverage-mapping"
                     and LDFLAGS=-fprofile-instr-generate in the environment of "ant -Djmemcheck=NO_MEMCHECK".
                     Without it, only the Java side is measured.
  --license FILE     license for voltdb init (default: none)
  --results DIR      where to put the runs and the report (created; must not exist)
  --duration SECS    how long each run's SQLancer runs (default 1200)
  --runs LIST        comma-separated runs to make, in order (default: security, kill, export, resize, xdcr,
                     crash, repros). Also sqlcoverage: "ant sqlcoverage" in --voltdb (VoltDB's own
                     randomized SQL suite, against HSQL), for a baseline to compare with (coverage/compare.py)
  --coverage-port N  port of the JaCoCo collector (default 36300)
  --compare-with DIR the results of another run-coverage.sh (normally --runs sqlcoverage) to compare with: also
                     reports the two combined, and coverage/compare.py prints what each covers that the other
                     does not (<results>/report/compare.txt)
  --report-only      make no runs: report on the data already in --results (which must then exist)
  --jacoco-agent JAR the JaCoCo agent (default: org.jacoco.agent 0.8.12's runtime jar in ~/.m2)
  --jacoco-lib DIR   directory holding org.jacoco.core and org.jacoco.report 0.8.12 and asm, asm-commons and
                     asm-tree 9.7, for the report (default: those jars in ~/.m2)
  --seed N           seed of the first run; run i gets N + i (default: the current time)
  -h, --help         this help

Each SQLancer run is one run-sqlancer.sh invocation with every server-side oracle (no mirror, so no DIFF, and no
in-service upgrade: neither the mirror's nor the older kit's code is the build under test). The runs share one
JaCoCo collector and one directory of LLVM raw profiles. The LLVM profiles are written continuously (%c), so a
node killed with kill -9 keeps the counts it had. Every server JVM's JaCoCo agent connects to the collector
(coverage/CoverageServer.java), which asks each for its counts every 10 s, so a killed node loses at most its last
10 s of Java counts.

The report is <results>/report: java/ (HTML, classes.csv, packages.tsv), ee/ (files.tsv, dirs.tsv, and
llvm-cov's report.txt), and summary.txt, the totals and the areas ENG-30396 asks about.
EOF
}

die() {
    echo "run-coverage: $*" >&2
    exit 2
}

script_dir=$(cd "$(dirname "$0")" && pwd)
m2=${M2_REPO:-$HOME/.m2/repository}
voltdb_home=
license=
results=
duration=1200
runs=security,kill,export,resize,xdcr,crash,repros
jacoco_agent=$m2/org/jacoco/org.jacoco.agent/0.8.12/org.jacoco.agent-0.8.12-runtime.jar
jacoco_lib=
seed=$(date +%s)
coverage_port=36300
compare_with=
report_only=false

while [ $# -gt 0 ]; do
    case $1 in
    --voltdb) voltdb_home=$2; shift 2 ;;
    --license) license=$2; shift 2 ;;
    --results) results=$2; shift 2 ;;
    --duration) duration=$2; shift 2 ;;
    --runs) runs=$2; shift 2 ;;
    --jacoco-agent) jacoco_agent=$2; shift 2 ;;
    --jacoco-lib) jacoco_lib=$2; shift 2 ;;
    --seed) seed=$2; shift 2 ;;
    --coverage-port) coverage_port=$2; shift 2 ;;
    --compare-with) compare_with=$(cd "$2" && pwd) || die "no directory $2"; shift 2 ;;
    --report-only) report_only=true; shift ;;
    -h | --help) usage; exit 0 ;;
    *) die "unknown option $1 (see --help)" ;;
    esac
done

[ -n "$voltdb_home" ] && [ -x "$voltdb_home/bin/voltdb" ] || die "--voltdb must be a VoltDB build holding bin/voltdb"
voltdb_home=$(cd "$voltdb_home" && pwd)
[ -n "$results" ] || die "--results is required"
if $report_only; then
    [ -d "$results/jacoco" ] || die "$results holds no coverage data"
else
    [ -e "$results" ] && die "$results already exists"
fi
[ -f "$jacoco_agent" ] || die "no JaCoCo agent at $jacoco_agent (see --jacoco-agent)"
command -v java >/dev/null || die "no java on PATH"
mkdir -p "$results" || die "cannot create $results"
results=$(cd "$results" && pwd)

if [ -n "$jacoco_lib" ]; then
    report_cp=$(ls "$jacoco_lib"/org.jacoco.core-0.8.12.jar "$jacoco_lib"/org.jacoco.report-0.8.12.jar \
        "$jacoco_lib"/asm-9.7.jar "$jacoco_lib"/asm-commons-9.7.jar "$jacoco_lib"/asm-tree-9.7.jar | paste -sd: -)
else
    report_cp=$m2/org/jacoco/org.jacoco.core/0.8.12/org.jacoco.core-0.8.12.jar:$m2/org/jacoco/org.jacoco.report/0.8.12/org.jacoco.report-0.8.12.jar:$m2/org/ow2/asm/asm/9.7/asm-9.7.jar:$m2/org/ow2/asm/asm-commons/9.7/asm-commons-9.7.jar:$m2/org/ow2/asm/asm-tree/9.7/asm-tree-9.7.jar
fi

# the engine library and the LLVM tools (xcrun on macOS)
# (a build's obj/release/nativelibs, or a kit's voltdb/; macOS names it .jnilib)
ee_lib=$(find "$voltdb_home/obj" "$voltdb_home/voltdb" "$voltdb_home/volt-ee/target/compiled/nativelibs" \
    \( -name 'libvoltdb*.jnilib' -o -name 'libvoltdb*.dylib' -o -name 'libvoltdb*.so' \) 2>/dev/null | head -1)
llvm() {
    if command -v "llvm-$1" >/dev/null; then "llvm-$1" "${@:2}"; else xcrun "llvm-$1" "${@:2}"; fi
}
ee_measured=false
if [ -n "$ee_lib" ] && llvm profdata --help >/dev/null 2>&1 &&
    nm "$ee_lib" 2>/dev/null | grep -q __llvm_profile_runtime; then
    ee_measured=true
fi

log() {
    echo "[$(date +%H:%M:%S)] $*" | tee -a "$results/coverage.log"
}

all_oracles=NOREC,TLP_WHERE,TLP_DISTINCT,TLP_HAVING,TLP_AGGREGATE,PQS,PARAMS,VIEWS,LIMIT,SUBQUERY,WINDOW,SETOPS
all_oracles=$all_oracles,DERIVED,PROCEDURE,SCHEMA,GEO,DURABLE,XDCR,PLANS,PERMS,STREAMS
license_args=()
[ -n "$license" ] && license_args=(--license "$license")

mkdir -p "$results/classes"
javac -nowarn -d "$results/classes" -cp "$report_cp" "$script_dir/coverage/CoverageReport.java" \
    "$script_dir/coverage/CoverageServer.java" || die "cannot compile the coverage tools"

failed=()
if ! $report_only; then
    {
        echo "voltdb:        $voltdb_home ($(git -C "$voltdb_home" log -1 --format='%h %cd' 2>/dev/null))"
        echo "sqlancer:      $(git -C "$script_dir/.." log -1 --format=%h 2>/dev/null)"
        echo "engine:        ${ee_lib:-none} (measured: $ee_measured)"
        echo "jacoco agent:  $jacoco_agent"
        echo "java:          $(java -version 2>&1 | head -1)"
        echo "runs:          $runs, $duration s each, first seed $seed"
    } >"$results/manifest.txt"
    cat "$results/manifest.txt"
    $ee_measured || log "The engine is not built with source-based coverage: measuring the Java side only"

    mkdir -p "$results/jacoco" "$results/profraw" "$results/runs"
    java -cp "$results/classes:$report_cp" CoverageServer "$coverage_port" "$results/jacoco/server.exec" \
        >"$results/jacoco/collector.log" 2>&1 &
    collector=$!
    trap 'kill $collector 2>/dev/null' EXIT
    for _ in $(seq 1 30); do
        grep -q listening "$results/jacoco/collector.log" && break
        kill -0 $collector 2>/dev/null ||
            die "the JaCoCo collector did not start: $(cat "$results/jacoco/collector.log")"
        sleep 1
    done
    agent="-javaagent:$jacoco_agent=output=tcpclient,address=127.0.0.1,port=$coverage_port"
    agent="$agent,includes=org.voltdb.*:org.voltcore.*:org.hsqldb_voltpatches.*"
    # %c: continuous mode, the counters are mapped onto the file and survive kill -9; %p: one file per process
    export LLVM_PROFILE_FILE="$results/profraw/%c%p.profraw"

    # sqlcoverage's server (org.voltdb.sqlgenerator.SimpleServer) always uses VoltDB's default ports, client 21212,
    # admin 21211 and internal 3021: a VoltDB already on this machine would be asked to take it into its cluster
    if [[ ",$runs," == *,sqlcoverage,* ]]; then
        for p in 21212 21211 3021; do
            if (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null; then
                die "port $p is in use: sqlcoverage's server needs VoltDB's default ports 21212, 21211 and 3021 free"
            fi
        done
    fi
    i=0
    IFS=, read -ra run_list <<<"$runs"
    for run in "${run_list[@]}"; do
        s=$((seed + i))
        i=$((i + 1))
        case $run in
        security) args=(--nodes 1 --kfactor 0 --sites 8 --security) ;;
        kill) args=(--nodes 3 --kfactor 1 --sites 8 --kill-interval 300) ;;
        export) args=(--nodes 3 --kfactor 2 --sites 2 --export) ;;
        resize) args=(--nodes 3 --kfactor 1 --sites 8 --resize-interval 120) ;;
        xdcr) args=(--nodes 3 --kfactor 1 --sites 4 --xdcr) ;;
        crash) args=(--nodes 3 --kfactor 1 --sites 8 --crash-interval 600) ;;
        repros | sqlcoverage) args=() ;;
        *) die "unknown run $run (see --runs)" ;;
        esac
        log "Run $i: $run"
        # (pro.build.done: sqlcoverage would otherwise rebuild VoltDB first, and run its unit tests under the agent)
        # the server JVMs get the agent through VOLTDB_OPTS, which the voltdb launcher adds; sqlcoverage's server
        # is a plain java command, so it gets it through JAVA_TOOL_OPTIONS (as does ant's own JVM, which loads no
        # VoltDB class)
        if [ "$run" = repros ]; then
            VOLTDB_OPTS="${VOLTDB_OPTS:-} $agent" "$script_dir/run-repros.sh" --voltdb "$voltdb_home" \
                ${license_args[@]+"${license_args[@]}"} --results "$results/runs/$run" >"$results/runs/$run.log" 2>&1
        elif [ "$run" = sqlcoverage ]; then
            (cd "$voltdb_home" && JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} $agent" ant -Djmemcheck=NO_MEMCHECK \
                -Dpro.build.done=true -Dsql_coverage_seed="$s" -Dsqlcoverage.failonerror=false sqlcoverage) >"$results/runs/$run.log" 2>&1
        else
            VOLTDB_OPTS="${VOLTDB_OPTS:-} $agent" "$script_dir/run-sqlancer.sh" --voltdb "$voltdb_home" \
                ${license_args[@]+"${license_args[@]}"} --results "$results/runs/$run" "${args[@]}" \
                --oracle "$all_oracles" --duration "$duration" --seed "$s" >"$results/runs/$run.log" 2>&1
        fi
        rc=$?
        log "Run $i: $run: $(grep -m1 -e '^RESULT' -e 'BUILD [A-Z]*' "$results/runs/$run.log" || echo "exit $rc")"
        [ $rc -eq 0 ] || failed+=("$run")
    done
    unset LLVM_PROFILE_FILE
    # the last dump, at the collector's exit
    kill $collector 2>/dev/null
    wait $collector 2>/dev/null
    trap - EXIT
    if $ee_measured; then
        llvm profdata merge -sparse -failure-mode=all "$results"/profraw/*.profraw -o "$results/ee.profdata" \
            2>>"$results/coverage.log" || log "llvm-profdata merge failed; see coverage.log"
    fi
fi

# report_on DIR EXEC[,EXEC...] PROFDATA: the Java and C++ reports and the areas' summary in DIR
report_on() {
    mkdir -p "$1/java" "$1/ee"
    java -cp "$results/classes:$report_cp" CoverageReport "$1/java" "$2" "$jars" "$src" | tee "$1/summary.txt"
    if $ee_measured && [ -f "$3" ]; then
        local ignore='third_party|3pty|/usr/|Toolchains|/tests/'
        llvm cov report "$ee_lib" -instr-profile "$3" -ignore-filename-regex="$ignore" \
            >"$1/ee/report.txt" 2>>"$results/coverage.log"
        llvm cov export "$ee_lib" -instr-profile "$3" -ignore-filename-regex="$ignore" -summary-only \
            >"$1/ee/summary.json" 2>>"$results/coverage.log"
        python3 "$script_dir/coverage/ee_summary.py" "$1/ee/summary.json" "$1/ee" | tee -a "$1/summary.txt"
    fi
    python3 "$script_dir/coverage/areas.py" "$1" | tee -a "$1/summary.txt"
}

log "Writing the report"
jars=$(ls "$voltdb_home"/voltdb/voltdb-*.jar | grep -v -e client -e sources -e javadoc | paste -sd, -)
src=$(find "$voltdb_home" -path '*/src/main/java' -type d -not -path '*/target/*' 2>/dev/null | paste -sd, -)
report_on "$results/report" "$results/jacoco/server.exec" "$results/ee.profdata"

if [ -n "$compare_with" ]; then
    log "Comparing with $compare_with"
    [ -f "$compare_with/report/java/classes.csv" ] || die "$compare_with has no report"
    if $ee_measured && [ -f "$compare_with/ee.profdata" ]; then
        llvm profdata merge -sparse "$results/ee.profdata" "$compare_with/ee.profdata" \
            -o "$results/combined.profdata" 2>>"$results/coverage.log"
    fi
    report_on "$results/report-combined" "$results/jacoco/server.exec,$compare_with/jacoco/server.exec" \
        "$results/combined.profdata" >/dev/null
    python3 "$script_dir/coverage/compare.py" "$results/report" "$compare_with/report" "$results/report-combined" |
        tee "$results/report/compare.txt"
fi

if [ ${#failed[@]} -gt 0 ]; then
    log "Failed runs: ${failed[*]} (coverage is still reported)"
    exit 1
fi
log "Done"
exit 0
