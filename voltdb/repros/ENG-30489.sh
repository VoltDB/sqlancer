#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30489
-- summary: JDBC: closing a Connection twice closes the client shared by every other open connection to the same URL ("Client is shut down")
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR. One node; connection B stays open while connection A (same URL)
# is opened and closed twice, 3 times (the pool's user count starts one high, so a single double close is not
# enough), then B runs a query. Exit 0 if B's query fails (the bug), 1 if it succeeds.
V=$VOLTDB_HOME
C=$WORKDIR/eng30489
B=$PORT_BASE
J=${JAVA_HOME:+$JAVA_HOME/bin/}java
rm -rf $C; mkdir -p $C
cat > $C/d.xml <<X
<?xml version="1.0"?>
<deployment><cluster sitesperhost="4" kfactor="0"/><httpd enabled="false"/></deployment>
X
stop() {
  $V/bin/voltadmin shutdown --force -H localhost:$((B + 1)) > /dev/null 2>&1 &
  local s=$!; for _ in $(seq 1 30); do kill -0 $s 2>/dev/null || break; sleep 1; done
  kill -9 $s $(cat $C/n.pid 2>/dev/null) 2>/dev/null
}
$V/bin/voltdb init --force -D $C/n -C $C/d.xml > $C/init.log 2>&1 || { echo "init failed"; exit 2; }
(nohup $V/bin/voltdb start -D $C/n -H localhost:$((B + 2)) --client=$((B + 0)) --admin=$((B + 1)) --internal=$((B + 2)) \
  --zookeeper=$((B + 3)) --replication=$((B + 4)) --metrics=$((B + 5)) > $C/start.log 2>&1 & echo $! > $C/n.pid)
up=0; for _ in $(seq 1 180); do grep -q "Server completed initialization" $C/start.log 2>/dev/null && { up=1; break; }; sleep 1; done
[ $up -eq 1 ] || { echo "server did not start"; stop; exit 2; }
cat > $C/DoubleClose.java <<'X'
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

public class DoubleClose {
    public static void main(String[] args) throws Exception {
        String url = args[0];
        Class.forName("org.voltdb.jdbc.Driver");
        Connection b = DriverManager.getConnection(url);
        for (int i = 0; i < 3; i++) {
            Connection a = DriverManager.getConnection(url);
            a.close();
            a.close();
        }
        try (ResultSet rs = b.getMetaData().getTables(null, null, null, null)) {
            while (rs.next()) {
            }
            System.out.println("B's query succeeded");
            System.exit(1);
        } catch (Exception e) {
            System.out.println("B failed: " + e.getMessage());
            System.exit(0);
        }
    }
}
X
$J -cp "$V/lib/*:$V/voltdb/*" $C/DoubleClose.java jdbc:voltdb://localhost:$B
rc=$?
stop
exit $rc
