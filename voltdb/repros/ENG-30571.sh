#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30571
-- summary: JDBC: a PreparedStatement counts a ? inside a string literal as a parameter ("Incorrect number of parameters passed")
HEADER
# Starts its own one-node server on PORT_BASE+0..5, runs the Java check below, and exits 0 if the bug reproduces,
# 1 if it is fixed, 2 on error.
: "${VOLTDB_HOME:?}" "${PORT_BASE:?}" "${WORKDIR:?}"
java_bin=${JAVA_HOME:+$JAVA_HOME/bin/}
cp="$VOLTDB_HOME/lib/*:$VOLTDB_HOME/voltdb/*"
p() { echo $((PORT_BASE + $1)); }
mkdir -p "$WORKDIR/src"
cat >"$WORKDIR/src/Repro.java" <<'JAVA'
import java.sql.*;
import org.voltdb.*;
import org.voltdb.client.*;

public class Repro {
    static Client client(String port) throws Exception {
        Client c = ClientFactory.createClient(new ClientConfig());
        c.createConnection("localhost", Integer.parseInt(port));
        return c;
    }

    static VoltTable adhoc(Client c, String sql, Object... params) throws Exception {
        Object[] args = new Object[params.length + 1];
        args[0] = sql;
        System.arraycopy(params, 0, args, 1, params.length);
        return c.callProcedure("@AdHoc", args).getResults()[0];
    }

    public static void main(String[] a) throws Exception {
        Connection c = DriverManager.getConnection("jdbc:voltdb://localhost:" + a[0]);
        c.createStatement().execute("CREATE TABLE pq (k INTEGER, v VARCHAR(10))");
        try {
            PreparedStatement ps = c.prepareStatement("INSERT INTO pq VALUES (?, 'what?')");
            ps.setInt(1, 1);
            System.out.println("inserted " + ps.executeUpdate());
            System.exit(11);
        } catch (SQLException e) {
            System.out.println(e.getMessage());
            System.exit(e.getMessage().contains("Incorrect number of parameters passed") ? 10 : 2);
        }
    }
}
JAVA
"${java_bin}javac" -cp "$cp" -d "$WORKDIR/src" "$WORKDIR/src/Repro.java" || exit 2
printf '<?xml version="1.0"?>\n<deployment>\n    <cluster sitesperhost="4" kfactor="0"/>\n    <httpd enabled="false"/>\n</deployment>\n' \
    >"$WORKDIR/deployment.xml"
"$VOLTDB_HOME/bin/voltdb" init --force ${VOLTDB_LICENSE:+--license "$VOLTDB_LICENSE"} -D "$WORKDIR/node" -C "$WORKDIR/deployment.xml" >"$WORKDIR/init.log" 2>&1 || exit 2
nohup "$VOLTDB_HOME/bin/voltdb" start -D "$WORKDIR/node" -H "localhost:$(p 2)" --client="$(p 0)" --admin="$(p 1)" \
    --internal="$(p 2)" --zookeeper="$(p 3)" --replication="$(p 4)" --metrics="$(p 5)" >"$WORKDIR/start.log" 2>&1 &
pid=$!
stop() {
    "$VOLTDB_HOME/bin/voltadmin" shutdown --force -H "localhost:$(p 1)" >/dev/null 2>&1 &
    local s=$!
    for _ in $(seq 1 30); do kill -0 $s 2>/dev/null || break; sleep 1; done
    kill -9 $s $pid 2>/dev/null
    wait $pid 2>/dev/null
}
trap stop EXIT
for _ in $(seq 1 180); do
    grep -q "Server completed initialization" "$WORKDIR/start.log" 2>/dev/null && break
    kill -0 $pid 2>/dev/null || { cat "$WORKDIR/start.log"; exit 2; }
    sleep 1
done
grep -q "Server completed initialization" "$WORKDIR/start.log" || { echo "server did not start"; exit 2; }
"${java_bin}java" -cp "$cp:$WORKDIR/src" Repro "$(p 0)"
# Repro exits 10 for the bug and 11 for fixed (a plain 1 would be an uncaught exception)
case $? in 10) exit 0 ;; 11) exit 1 ;; *) exit 2 ;; esac
