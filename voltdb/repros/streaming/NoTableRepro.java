import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.voltdb.client.Client2;
import org.voltdb.client.Client2Config;
import org.voltdb.client.Client2Impl;
import org.voltdb.client.ClientFactory;

// args: host:port tables skipTables ("-" for none)
// Streams a snapshot with these table lists, which name no table of the database to stream, and waits 60 s for the
// stream to succeed or fail; then tries another stream on the same client. Exit 0 if the first neither succeeds nor
// fails (the bug), 1 if it does either.
public class NoTableRepro {
    public static void main(String[] args) throws Exception {
        String tables = "-".equals(args[1]) ? null : args[1];
        String skip = "-".equals(args[2]) ? null : args[2];
        Client2 client = ClientFactory.createClient(new Client2Config());
        client.connectSync(args[0]);
        client.callProcedureSync("@AdHoc", "CREATE TABLE R (k INTEGER NOT NULL, PRIMARY KEY (k));");
        client.callProcedureSync("R.insert", 1);
        String what = "a stream with tables " + tables + " and skipTables " + skip;
        try {
            ((Client2Impl) client).streamSnapshotAsync(null, "notable", tables, skip, metadata -> {
            }, (table, partition, chunk, buffer) -> {
            }).get(60, TimeUnit.SECONDS);
            System.out.println(what + " succeeded");
        } catch (TimeoutException e) {
            System.out.println(what + " neither succeeded nor failed in 60 s (the bug)");
            try {
                ((Client2Impl) client).streamSnapshotAsync(null, "after", "R", null, metadata -> {
                }, (table, partition, chunk, buffer) -> {
                });
            } catch (IllegalStateException again) {
                System.out.println("another stream on the same client: " + again.getMessage());
            }
            System.exit(0);
        } catch (Exception e) {
            System.out.println(what + " failed: " + e.getMessage());
        }
        System.exit(1);
    }
}
