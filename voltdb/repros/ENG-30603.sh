#!/bin/bash
# Reproduction for a JDBC driver bug, run by voltdb/run-repros.sh with VOLTDB_HOME, PORT_BASE and WORKDIR set.
# Exit 0 if the bug reproduces, 1 if it is fixed, anything else on error.
: <<'HEADER'
-- ticket: ENG-30603
-- summary: JDBC executeQuery rejects a query that does not start with SELECT (WITH ...): "Cannot submit statement in current context"
HEADER

: "${VOLTDB_HOME:?}" "${PORT_BASE:?}" "${WORKDIR:?}"
java_bin=${JAVA_HOME:+$JAVA_HOME/bin/}java
javac_bin=${JAVA_HOME:+$JAVA_HOME/bin/}javac
p() { echo $((PORT_BASE + $1)); }
mkdir -p "$WORKDIR"
pidfile=$WORKDIR/server.pid

cleanup() {
    "$VOLTDB_HOME/bin/voltadmin" shutdown --force -H "localhost:$(p 1)" >/dev/null 2>&1 &
    local s=$!
    for _ in $(seq 1 30); do kill -0 $s 2>/dev/null || break; sleep 1; done
    kill -9 $s 2>/dev/null
    [ -f "$pidfile" ] && kill -9 "$(cat "$pidfile")" 2>/dev/null
}
trap cleanup EXIT

for i in 0 1 2 3 4 5; do
    (exec 3<>"/dev/tcp/127.0.0.1/$(p $i)") 2>/dev/null && { echo "port $(p $i) is in use"; exit 2; }
done
printf '<?xml version="1.0"?>\n<deployment>\n    <cluster sitesperhost="4" kfactor="0"/>\n    <httpd enabled="false"/>\n</deployment>\n' \
    >"$WORKDIR/deployment.xml"
"$VOLTDB_HOME/bin/voltdb" init --force ${VOLTDB_LICENSE:+--license "$VOLTDB_LICENSE"} -D "$WORKDIR/node0" -C "$WORKDIR/deployment.xml" >"$WORKDIR/init.log" 2>&1 ||
    { echo "voltdb init failed"; exit 2; }
(nohup "$VOLTDB_HOME/bin/voltdb" start -D "$WORKDIR/node0" -c 1 -H "localhost:$(p 2)" \
    --client="$(p 0)" --admin="$(p 1)" --internal="$(p 2)" --zookeeper="$(p 3)" --replication="$(p 4)" \
    --metrics="$(p 5)" >"$WORKDIR/start.log" 2>&1 &
    echo $! >"$pidfile")
up=false
for _ in $(seq 1 180); do
    grep -q "Server completed initialization" "$WORKDIR/start.log" 2>/dev/null && { up=true; break; }
    sleep 1
done
$up || { echo "the server did not start within 180 s"; exit 2; }

"$VOLTDB_HOME/bin/sqlcmd" --port="$(p 0)" <<'EOF' || { echo "setup failed"; exit 2; }
CREATE TABLE R (k INTEGER NOT NULL, a INTEGER, PRIMARY KEY (k));
INSERT INTO R VALUES (1, 1);
INSERT INTO R VALUES (2, 2);
INSERT INTO R VALUES (3, 3);
EOF

mkdir -p "$WORKDIR/java"
cat >"$WORKDIR/java/Repro.java" <<'EOF'
import java.sql.*;

public class Repro {
    public static void main(String[] args) throws Exception {
        Class.forName("org.voltdb.jdbc.Driver");
        try (Connection c = DriverManager.getConnection("jdbc:voltdb://localhost:" + args[0]);
             Statement s = c.createStatement()) {
            int rows = 0;
            try (ResultSet rs = s.executeQuery("WITH w AS (SELECT a FROM R) SELECT a FROM w")) {
                while (rs.next()) rows++;
            } catch (SQLException e) {
                System.out.println("executeQuery failed: " + e.getMessage());
                if (e.getMessage() != null && e.getMessage().contains("Cannot submit statement in current context")) {
                    System.out.println("BUG: the WITH query is not treated as a query");
                    System.exit(0);
                }
                System.exit(3);
            }
            System.out.println("executeQuery returned " + rows + " rows");
            System.exit(rows == 3 ? 1 : 3);
        }
    }
}
EOF
cp="$VOLTDB_HOME/lib/*:$VOLTDB_HOME/voltdb/*"
"$javac_bin" -cp "$cp" -d "$WORKDIR/java" "$WORKDIR/java/Repro.java" || { echo "javac failed"; exit 2; }
"$java_bin" -cp "$WORKDIR/java:$cp" Repro "$(p 0)"
rc=$?
echo "Repro exit code: $rc"
exit $rc
