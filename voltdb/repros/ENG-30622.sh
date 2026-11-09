#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30622
-- manual: reproduces in some runs only (3 of 6 local runs); run it with --only ENG-30622, more than once
-- summary: Elastic remove hangs for good at "Building index for data migration" when a table is dropped during its index snapshot
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR. 3 nodes, k=1, then up to 8 cycles of: create 40 tables with rows, add
# 2 nodes, voltadmin resize while the 40 tables are dropped one per catalog update. Exit 0 if a shrink hangs (the bug),
# 1 if all 8 finish. The hang needs a DROP inside the index snapshot window, so a pass is likely, not certain, to mean
# fixed: it reproduced in 3 of 6 local runs.
V=$VOLTDB_HOME
C=$WORKDIR/eng30622
B=$PORT_BASE
port() { echo $((B + 10 * $1 + $2)); }
rm -rf $C; mkdir -p $C
cat > $C/d.xml <<X
<?xml version="1.0"?>
<deployment><cluster sitesperhost="4" kfactor="1"/><httpd enabled="false"/></deployment>
X
H="localhost:$(port 0 2),localhost:$(port 1 2),localhost:$(port 2 2)"
start() { # i extra-args
  local i=$1; shift
  $V/bin/voltdb init --force -D $C/n$i -C $C/d.xml > $C/init$i.log 2>&1
  (nohup $V/bin/voltdb start "$@" -D $C/n$i -H $H --client=$(port $i 0) --admin=$(port $i 1) --internal=$(port $i 2) --zookeeper=$(port $i 3) --replication=$(port $i 4) --metrics=$(port $i 5) > $C/start$i.log 2>&1 & echo $! > $C/n$i.pid)
}
waitup() { for _ in $(seq 1 300); do grep -q "Server completed initialization" $C/start$1.log 2>/dev/null && return 0; sleep 1; done; return 1; }
for i in 0 1 2; do start $i -c 3; done
for i in 0 1 2; do waitup $i || { echo "node $i did not start"; exit 2; }; done
SQL="$V/bin/sqlcmd --port=$(port 0 0)"
{ for t in 0 1 2 3; do echo "CREATE TABLE T$t (id INTEGER NOT NULL, v VARCHAR(30)); PARTITION TABLE T$t ON COLUMN id;"; done
  for t in 0 1 2 3; do for r in $(seq 1 500); do echo "INSERT INTO T$t VALUES ($r, 'row $r');"; done; done; } | $SQL > /dev/null 2>&1
echo "rows: $(echo 'SELECT COUNT(*) FROM T0;' | $SQL 2>/dev/null | grep -E '^ *[0-9]+ *$')"
hung=0
for cycle in 1 2 3 4 5 6 7 8; do
  { for d in $(seq 1 40); do echo "CREATE TABLE D$d (id INTEGER NOT NULL, v VARCHAR(30)); PARTITION TABLE D$d ON COLUMN id;"; done
    for d in $(seq 1 40); do for r in $(seq 1 20); do echo "INSERT INTO D$d VALUES ($r, 'row $r');"; done; done; } | $SQL > /dev/null 2>&1
  # retry joins rejected by a catalog update in progress
  for att in 1 2 3 4 5 6 7 8 9 10; do
    rm -rf $C/n3 $C/n4; start 3 --add; start 4 --add
    ok=1; for i in 3 4; do waitup $i || ok=0; done
    [ $ok -eq 1 ] && break
    kill -9 $(cat $C/n3.pid) $(cat $C/n4.pid) 2>/dev/null; sleep 5
  done
  [ $ok -eq 1 ] || { echo "cycle $cycle: grow failed"; for f in $C/n*.pid; do kill -9 $(cat $f) 2>/dev/null; done; exit 2; }
  for _ in $(seq 1 60); do o=$($V/bin/voltadmin resize --test -H localhost:$(port 0 1) 2>&1 | tail -1); echo "$o" | grep -qE "Host Ids: \[[0-9]+, [0-9]+\]" && ! echo "$o" | grep -qE "\[(0|1|2)[],]" && break; sleep 5; done
  T0=$(date +%s)
  # two loops, odd and even tables, so that catalog updates come about twice a second
  ( for d in $(seq 1 2 40); do echo "DROP TABLE D$d;" | $SQL > /dev/null 2>&1; done ) &
  dropper=$!
  ( for d in $(seq 2 2 40); do echo "DROP TABLE D$d;" | $SQL > /dev/null 2>&1; done ) &
  dropper2=$!
  r=$($V/bin/voltadmin resize -H localhost:$(port 0 1) 2>&1 | tail -1)
  for _ in $(seq 1 400); do a=0; for i in 3 4; do kill -0 $(cat $C/n$i.pid) 2>/dev/null && a=$((a+1)); done; [ $a -eq 0 ] && break; sleep 1; done
  echo "cycle $cycle: $r -> after $(( $(date +%s) - T0 )) s: $a removed server(s) still running; $($V/bin/voltadmin resize --status -H localhost:$(port 0 1) 2>&1 | tail -1)"
  [ $a -eq 0 ] || { hung=1; break; }
  wait $dropper $dropper2
done
touch $C/stop; wait
grep -h "No table streamer\|failed to complete" $C/n*/voltdbroot/log/volt.log | cut -c1-200 | head -5
grep -h "Elastic remove starting step\|Building index" $C/n0/voltdbroot/log/volt.log | cut -c1-140 | tail -4
echo "rows: $(echo 'SELECT COUNT(*) FROM T0;' | $SQL 2>/dev/null | grep -E '^ *[0-9]+ *$')"
$V/bin/voltadmin shutdown --force -H localhost:$(port 0 1) > /dev/null 2>&1 &
sleep 10; for f in $C/n*.pid; do kill -9 $(cat $f) 2>/dev/null; done
[ $hung -eq 1 ] && { echo "HUNG: the bug reproduces"; exit 0; }
echo "every shrink finished"
exit 1
