import org.voltdb.VoltTable;
import org.voltdb.client.Client2;
import org.voltdb.client.Client2Config;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;

// args: host:port truststore
// Calls @ReloadSslContext(0) over TLS and prints what it answers: its STATUS (0 reloaded, 1 check failed, 2 reload
// failed) and message, or the exception if no answer comes.
public class ReloadRepro {
    public static void main(String[] args) throws Exception {
        Client2 client = ClientFactory.createClient(new Client2Config().trustStore(args[1], "sqlancer").enableSSL());
        client.connectSync(args[0]);
        try {
            ClientResponse r = client.callProcedureSync("@ReloadSslContext", 0);
            VoltTable t = r.getResults()[0];
            while (t.advanceRow()) {
                System.out.println("@ReloadSslContext(0) answered STATUS " + t.getLong(0) + " " + t.getString(1));
            }
        } catch (Exception e) {
            System.out.println("@ReloadSslContext(0) got no answer: " + e.getMessage());
        }
        client.close();
    }
}
