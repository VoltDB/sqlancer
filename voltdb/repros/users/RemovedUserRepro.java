import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

import org.voltdb.client.Client;
import org.voltdb.client.ClientConfig;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.ProcCallException;

/**
 * RemovedUserRepro SERVER JAR ROUNDS [control]: as the administrator sqlancer/sqlancer, one thread loads the classes of JAR with
 * @UpdateClasses and removes them, again and again; the main thread adds a user with @SetYamlConfiguration, removes it,
 * checks that @GetYamlConfiguration no longer lists it, and logs in as it, which must be refused. Prints BUG and exits
 * 0 at the first removed user that logs in; exits 1 after ROUNDS users without one. With control, no @UpdateClasses.
 */
public class RemovedUserRepro {
    public static void main(String[] args) throws Exception {
        String server = args[0];
        byte[] jar = Files.readAllBytes(Paths.get(args[1]));
        int rounds = Integer.parseInt(args[2]);
        Client admin = ClientFactory.createClient(new ClientConfig("sqlancer", "sqlancer"));
        admin.createConnection(server);
        AtomicBoolean stop = new AtomicBoolean();
        Thread classes = new Thread(() -> {
            Client c = ClientFactory.createClient(new ClientConfig("sqlancer", "sqlancer"));
            try {
                c.createConnection(server);
                while (!stop.get()) {
                    try {
                        c.callProcedure("@UpdateClasses", jar, null);
                        c.callProcedure("@UpdateClasses", null, "repro.*");
                    } catch (ProcCallException e) {
                        // another catalog update in progress: try again
                    }
                }
                c.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        boolean control = args.length > 3 && args[3].equals("control");
        if (!control) {
            classes.start();
        }
        int updates = 0;
        try {
            for (int i = 1; i <= rounds; i++) {
                String user = "removed" + i;
                String password = "pw" + i;
                yaml(admin, ".users[" + user + "].password=" + password + "\n.users[" + user + "].roles=user");
                yaml(admin, ".users[" + user + "]=null");
                String users = admin.callProcedure("@GetYamlConfiguration", "deployment.users").getResults()[0]
                        .fetchRow(0).getString("DATA");
                if (users.contains(user)) {
                    System.out.println("round " + i + ": @GetYamlConfiguration still lists " + user);
                    continue;
                }
                Client c = ClientFactory.createClient(new ClientConfig(user, password));
                try {
                    c.createConnection(server);
                    ClientResponse r = c.callProcedure("@Ping");
                    System.out.println("BUG: round " + i + ": " + user + ", removed and no longer listed by "
                            + "@GetYamlConfiguration, logged in and ran @Ping (status " + r.getStatus() + ")");
                    stop.set(true);
                    if (!control) {
                        classes.join();
                    }
                    System.exit(0);
                } catch (Exception e) {
                    if (!String.valueOf(e.getMessage()).contains("Authentication rejected")) {
                        System.out.println("round " + i + ": login failed otherwise: " + e.getMessage());
                    }
                } finally {
                    c.close();
                }
                updates++;
            }
        } finally {
            stop.set(true);
            if (!control) {
                classes.join();
            }
        }
        System.out.println("no removed user logged in, " + updates + " users");
        System.exit(1);
    }

    // @SetYamlConfiguration('', assignments), retried while another catalog update is in progress
    private static void yaml(Client admin, String assignments) throws Exception {
        for (int attempt = 0;; attempt++) {
            try {
                admin.callProcedure("@SetYamlConfiguration", "", assignments);
                return;
            } catch (ProcCallException e) {
                if (attempt > 200 || !String.valueOf(e.getMessage()).contains("in progress")) {
                    throw e;
                }
                Thread.sleep(20);
            }
        }
    }
}
