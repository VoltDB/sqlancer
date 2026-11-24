#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30627
-- summary: Ad hoc DDL from a user without ADMIN is compiled before the permission check, and the error discloses another procedure's SQL
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR. One node with security on; as a user with role SQLREAD,
# DROP TABLE T, which a procedure reads. Exit 0 if the error quotes the procedure (the bug), 1 if it is a permission
# error (fixed), 2 otherwise.
V=$VOLTDB_HOME
C=$WORKDIR/eng30627
B=$PORT_BASE
rm -rf "$C"; mkdir -p "$C"
cat >"$C/d.xml" <<X
<?xml version="1.0"?>
<deployment><cluster sitesperhost="4" kfactor="0"/><httpd enabled="false"/>
<security enabled="true" provider="hash"/>
<users>
  <user name="admin" password="admin" roles="administrator"/>
  <user name="reader" password="reader" roles="r_read"/>
</users></deployment>
X
"$V/bin/voltdb" init --force ${VOLTDB_LICENSE:+--license "$VOLTDB_LICENSE"} -D "$C/n" -C "$C/d.xml" >"$C/init.log" 2>&1 || exit 2
(nohup "$V/bin/voltdb" start -D "$C/n" -H "localhost:$((B + 2))" --client=$B --admin=$((B + 1)) --internal=$((B + 2)) \
    --zookeeper=$((B + 3)) --replication=$((B + 4)) --metrics=$((B + 5)) >"$C/start.log" 2>&1 & echo $! >"$C/pid")
trap '"$V/bin/voltadmin" shutdown --force -H "localhost:$((B + 1))" -u admin -p admin >/dev/null 2>&1; sleep 2; kill -9 $(cat "$C/pid") 2>/dev/null' EXIT
for _ in $(seq 1 120); do grep -q "Server completed initialization" "$C/start.log" && break; sleep 1; done
echo "CREATE ROLE r_read WITH SQLREAD; CREATE TABLE T (a INTEGER); CREATE PROCEDURE SECRET_P AS SELECT COUNT(*) FROM T WHERE a = 42;" |
    "$V/bin/sqlcmd" --port=$B --user=admin --password=admin >"$C/setup.log" 2>&1
out=$(echo "DROP TABLE T;" | "$V/bin/sqlcmd" --port=$B --user=reader --password=reader 2>&1)
echo "$out" | grep -v "^ *at "
if echo "$out" | grep -q "SECRET_P"; then exit 0; fi
if echo "$out" | grep -q "does not have admin permission"; then exit 1; fi
exit 2
