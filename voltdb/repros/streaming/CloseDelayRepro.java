import org.voltdb.client.Client2;
import org.voltdb.client.Client2Config;
import org.voltdb.client.Client2Impl;
import org.voltdb.client.ClientFactory;

// args: host:port
// Streams a snapshot of a one-row table R, then reports the client's outstanding requests and how long close() takes;
// a second client, which streams nothing, is closed the same way. Exit 0 if the first close takes 30 s or more (the
// bug), 1 if not.
public class CloseDelayRepro {
    public static void main(String[] args) throws Exception {
        Client2 plain = ClientFactory.createClient(new Client2Config());
        plain.connectSync(args[0]);
        plain.callProcedureSync("@AdHoc", "CREATE TABLE R (k INTEGER NOT NULL, PRIMARY KEY (k));");
        plain.callProcedureSync("R.insert", 1);
        long t = System.currentTimeMillis();
        plain.close();
        System.out.println("a client that streamed nothing: close() took " + (System.currentTimeMillis() - t) + " ms");
        Client2 client = ClientFactory.createClient(new Client2Config());
        client.connectSync(args[0]);
        ((Client2Impl) client).streamSnapshotSync(null, "closedelay", "R", null, metadata -> {
        }, (table, partition, chunk, buffer) -> {
        });
        System.out.println("after a stream that completed: outstanding requests " + client.outstandingTxnCount());
        t = System.currentTimeMillis();
        client.close();
        long took = System.currentTimeMillis() - t;
        System.out.println("its close() took " + took + " ms");
        System.exit(took >= 30_000 ? 0 : 1);
    }
}
