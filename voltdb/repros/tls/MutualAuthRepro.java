import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

import org.voltdb.client.Client2;
import org.voltdb.client.Client2Config;
import org.voltdb.client.ClientFactory;

// args: host:port truststore keystore
// Against a node that requires client certificates (<ssl clientauthrequired="true">), connects three ways and prints
// what each did: Client2 with the keystore (trustStoreWithMutualAuth), the control; the JDBC driver with ssl=true and
// the truststore, its only TLS properties; and the JDBC driver again with javax.net.ssl.keyStore set, which the
// driver's TLS setup could fall back to. Prints "client2: ok" and "jdbc: <ok or the error>" lines.
public class MutualAuthRepro {
    public static void main(String[] args) throws Exception {
        String server = args[0];
        String trust = args[1];
        String key = args[2];
        try {
            Client2 client = ClientFactory.createClient(
                    new Client2Config().trustStoreWithMutualAuth(trust, "sqlancer", key, "sqlancer").enableSSL());
            client.connectSync(server);
            client.callProcedureSync("@Ping");
            client.close();
            System.out.println("client2: ok");
        } catch (Exception e) {
            System.out.println("client2: " + e);
        }
        System.out.println("jdbc: " + jdbc(server, trust));
        System.setProperty("javax.net.ssl.keyStore", key);
        System.setProperty("javax.net.ssl.keyStorePassword", "sqlancer");
        System.out.println("jdbc with javax.net.ssl.keyStore: " + jdbc(server, trust));
    }

    private static String jdbc(String server, String trust) {
        Properties p = new Properties();
        p.setProperty("ssl", "true");
        p.setProperty("truststore", trust);
        p.setProperty("truststorepassword", "sqlancer");
        try (Connection c = DriverManager.getConnection("jdbc:voltdb://" + server, p);
                CallableStatement call = c.prepareCall("{call @Ping}")) {
            call.execute();
            return "ok";
        } catch (Exception e) {
            return String.valueOf(e.getMessage()).split("\n")[0];
        }
    }
}
