import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.voltdb.client.Client2;
import org.voltdb.client.Client2Config;
import org.voltdb.client.Client2Impl;
import org.voltdb.client.ClientFactory;

// args: host:port[,host:port...] tables [control]
// Client A streams a snapshot of T0 and stays connected; once its stream's leftover request has expired (ENG-30724:
// 65 s), client B streams a snapshot of TABLES one-row tables, which takes about 20 s, and 3 s into B's stream A closes
// its connection. B's stream must still complete, and then a stream of client C (B still connected) must too. Exit 0
// if either does not (the bug: A's disconnect removes B's participant nodes, so B's stream state is never removed), 1
// if both do. With "control", A stays connected.
public class DisconnectRepro {
    static Client2 connect(String server) throws Exception {
        Client2 c = ClientFactory.createClient(new Client2Config());
        for (String s : server.split(",")) {
            c.connectSync(s);
        }
        return c;
    }

    public static void main(String[] args) throws Exception {
        int tables = Integer.parseInt(args[1]);
        boolean control = args.length > 2;
        Client2 a = connect(args[0]);
        StringBuilder ddl = new StringBuilder();
        for (int i = 0; i < tables; i++) {
            ddl.append("CREATE TABLE T").append(i).append(" (k INTEGER NOT NULL, PRIMARY KEY (k)); PARTITION TABLE T")
                    .append(i).append(" ON COLUMN k; ");
        }
        a.callProcedureSync("@AdHoc", ddl.toString());
        for (int i = 0; i < tables; i++) {
            a.callProcedureSync("T" + i + ".insert", i);
        }
        ((Client2Impl) a).streamSnapshotSync(null, "streama", "T0", null, m -> {
        }, (t, p, n, b) -> {
        });
        System.out.println("client A streamed T0; waiting 65 s for its leftover request to expire");
        Thread.sleep(65_000);
        Client2 b = connect(args[0]);
        AtomicLong chunks = new AtomicLong();
        long start = System.currentTimeMillis();
        CompletableFuture<Void> f = ((Client2Impl) b).streamSnapshotAsync(null, "streamb", null, null, m -> {
        }, (t, p, n, buf) -> chunks.incrementAndGet());
        Thread.sleep(3000);
        if (control) {
            System.out.println("client A stays connected (control)");
        } else {
            a.close();
            System.out.println("client A closed its connection 3 s into client B's stream (" + chunks + " chunks so far)");
        }
        boolean broken = true;
        try {
            f.get(60, TimeUnit.SECONDS);
            System.out.println("client B's stream completed in " + (System.currentTimeMillis() - start) + " ms with "
                    + chunks + " chunks");
            broken = false;
        } catch (TimeoutException e) {
            System.out.println("client B's stream did not end in 60 s; " + chunks + " chunks arrived (the bug)");
        } catch (Exception e) {
            System.out.println("client B's stream failed after " + (System.currentTimeMillis() - start) + " ms with "
                    + chunks + " chunks: " + e.getMessage() + " (the bug)");
        }
        Client2 c = connect(args[0]);
        try {
            ((Client2Impl) c).streamSnapshotAsync(null, "streamc", "T0", null, m -> {
            }, (t, p, n, buf) -> {
            }).get(30, TimeUnit.SECONDS);
            System.out.println("client C's stream then completed");
        } catch (Exception e) {
            System.out.println("client C's stream then failed: " + e.getMessage() + " (the bug)");
            broken = true;
        }
        System.exit(broken ? 0 : 1);
    }
}
