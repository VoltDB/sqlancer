import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.voltdb.VoltTable;
import org.voltdb.client.Client2;
import org.voltdb.client.Client2Config;
import org.voltdb.client.Client2Impl;
import org.voltdb.client.ClientFactory;

// args: host:port
// Fills R with the rows 1-5 and streams a snapshot of it named s10; then replaces them with the rows 6-8 and streams a
// snapshot named s1. The second stream must hold R's rows 6-8. Exit 0 if it holds others (the bug), 1 if not.
public class PrefixNonceRepro {
    static List<Long> stream(Client2 client, String nonce) throws Exception {
        List<Long> keys = Collections.synchronizedList(new ArrayList<>());
        ((Client2Impl) client).streamSnapshotSync(null, nonce, "R", null, metadata -> {
        }, (table, partition, chunk, buffer) -> {
            VoltTable t = VoltTable.fromBuffer(buffer, true);
            while (t.advanceRow()) {
                keys.add(t.getLong(0));
            }
        });
        // ENG-30720: a stream can complete before its last chunks are handed over
        Thread.sleep(2000);
        List<Long> sorted = new ArrayList<>(keys);
        Collections.sort(sorted);
        return sorted;
    }

    public static void main(String[] args) throws Exception {
        Client2 client = ClientFactory.createClient(new Client2Config());
        client.connectSync(args[0]);
        client.callProcedureSync("@AdHoc", "CREATE TABLE R (k INTEGER NOT NULL, PRIMARY KEY (k));");
        for (int k = 1; k <= 5; k++) {
            client.callProcedureSync("R.insert", k);
        }
        System.out.println("R holds 1-5; the stream s10 of R: " + stream(client, "s10"));
        client.callProcedureSync("@AdHoc", "DELETE FROM R");
        for (int k = 6; k <= 8; k++) {
            client.callProcedureSync("R.insert", k);
        }
        List<Long> second = stream(client, "s1");
        System.out.println("R holds 6-8; the stream s1 of R: " + second);
        client.close();
        if (!second.equals(List.of(6L, 7L, 8L))) {
            System.out.println("the stream s1 holds rows of the snapshot s10 (the bug)");
            System.exit(0);
        }
        System.out.println("the stream s1 holds R's rows");
        System.exit(1);
    }
}
