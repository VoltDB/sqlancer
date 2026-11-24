import java.util.concurrent.atomic.AtomicLong;

import org.voltdb.VoltTable;
import org.voltdb.client.Client2;
import org.voltdb.client.Client2Config;
import org.voltdb.client.Client2Impl;
import org.voltdb.client.ClientFactory;

// args: host:port[,host:port...] rows streams
// Fills T with rows rows, then streams a snapshot of it streams times through Client2Impl.streamSnapshotSync. Counts
// the rows the chunk consumer (which spends 2 ms on each chunk) has been given when the call returns (the stream complete), and again 2 s later.
// Exit 0 if any stream returned before its consumer had every row (the bug), 1 if none did.
public class EarlyCompletionRepro {
    public static void main(String[] args) throws Exception {
        int rows = Integer.parseInt(args[1]);
        int streams = Integer.parseInt(args[2]);
        Client2 client = ClientFactory.createClient(new Client2Config());
        for (String server : args[0].split(",")) {
            client.connectSync(server);
        }
        client.callProcedureSync("@AdHoc", "CREATE TABLE T (k INTEGER NOT NULL, v VARCHAR(40), PRIMARY KEY (k));"
                + " PARTITION TABLE T ON COLUMN k;");
        for (int k = 0; k < rows; k++) {
            client.callProcedureAsync("T.insert", k, "row " + k + " of the streamed table");
        }
        client.drain();
        long inTable = client.callProcedureSync("@AdHoc", "SELECT COUNT(*) FROM T").getResults()[0].asScalarLong();
        System.out.println("rows in T: " + inTable);
        int early = 0;
        for (int s = 1; s <= streams; s++) {
            AtomicLong received = new AtomicLong();
            ((Client2Impl) client).streamSnapshotSync(null, "early" + s, "T", null, metadata -> {
            }, (table, partition, chunk, buffer) -> {
                // a consumer that takes a moment over each chunk, as one writing it to a file does
                received.addAndGet(VoltTable.fromBuffer(buffer, true).getRowCount());
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            long atCompletion = received.get();
            Thread.sleep(2000);
            long later = received.get();
            System.out.println("stream " + s + ": rows given to the consumer when the stream completed: " + atCompletion
                    + "; 2 s later: " + later);
            if (atCompletion < inTable) {
                early++;
            }
        }
        client.close();
        if (early > 0) {
            System.out.println(early + " of " + streams
                    + " streams completed before their consumer had every row of T (the bug)");
            System.exit(0);
        }
        System.out.println("every stream completed with every row of T given to its consumer");
        System.exit(1);
    }
}
