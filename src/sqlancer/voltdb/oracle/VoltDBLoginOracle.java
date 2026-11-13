package sqlancer.voltdb.oracle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.voltdb.client.Client;
import org.voltdb.client.ClientConfig;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;
import org.voltdb.client.ProcCallException;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.Reproducer;
import sqlancer.common.oracle.TestOracle;
import sqlancer.voltdb.VoltDBConnection;
import sqlancer.voltdb.VoltDBOptions;
import sqlancer.voltdb.VoltDBProvider.VoltDBGlobalState;

/**
 * Logins and the users changed while the database runs (milestone 46), with --security. Milestone 44's coverage run
 * found the refusals never run: {@code AuthFailedBecause}, the failed-login log lines, user expiry dates, and users
 * changed through {@code @SetYamlConfiguration}.
 * <p>
 * Each check adds a user of its own (a YAML document with the password in plain text or masked, as its SHA-256 digest,
 * or assignments), which {@code @GetYamlConfiguration} must list with the digest of its password. The user must log in
 * with its password and be refused with any other, and a user that does not exist must be refused. Then one change: a
 * new password (the old one is refused, the new one logs in), an expiry date (in the past the user is refused, in the
 * future it logs in), a new role, or a change VoltDB must refuse (an expiry date that is not a date, the only
 * administrator made a plain user), after which the user must log in as before. Finally the user is removed and must be
 * refused. Each login is a new connection of the native client, closed at once.
 * <p>
 * Changes to the deployment take turns with CONFIG's.
 */
public class VoltDBLoginOracle implements TestOracle<VoltDBGlobalState> {

    private static final AtomicLong NEXT_USER = new AtomicLong();
    private static final AtomicLong CHECKS = new AtomicLong();
    private static final AtomicLong ACCEPTED = new AtomicLong();
    private static final AtomicLong REFUSED = new AtomicLong();
    private static final AtomicLong CHANGES_REFUSED = new AtomicLong();
    private static final String PASSWORD_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-+!@*";
    private static final String[] ROLES = { "user", "sqlancer_read", "sqlancer_write" };

    static {
        Runtime.getRuntime()
                .addShutdownHook(new Thread(() -> System.out.println("LOGIN checks: " + CHECKS + "; logins accepted: "
                        + ACCEPTED + "; logins refused: " + REFUSED + "; user changes refused: " + CHANGES_REFUSED)));
    }

    private final VoltDBGlobalState state;
    private String lastQuery;

    public VoltDBLoginOracle(VoltDBGlobalState state) {
        this.state = state;
    }

    @Override
    public void check() throws Exception {
        VoltDBOptions o = state.getDbmsSpecificOptions();
        if (!o.security) {
            throw new IgnoreMeException();
        }
        synchronized (VoltDBConfigOracle.LOCK) {
            String user = "login" + NEXT_USER.incrementAndGet();
            String password = password();
            String role = Randomly.fromOptions(ROLES);
            add(user, password, role, o.avoidKnownBugs);
            try {
                listed(user, password, role, null);
                mustLogIn(user, password);
                mustBeRefused(user, otherThan(password), "a wrong password");
                mustBeRefused(user + "x", password, "a user that does not exist");
                switch (Randomly.fromOptions(Change.values())) {
                case PASSWORD:
                    String newPassword = otherThan(password);
                    set("", ".users[" + user + "].password=" + newPassword);
                    listed(user, newPassword, role, null);
                    mustBeRefused(user, password, "its old password");
                    mustLogIn(user, newPassword);
                    password = newPassword;
                    break;
                case EXPIRED:
                    String past = LocalDate.now().minusDays(2 + (int) Randomly.getNotCachedInteger(0, 400)).toString();
                    set("", ".users[" + user + "].expires=" + past);
                    listed(user, password, role, past);
                    mustBeRefused(user, password, "an expiry date in the past, " + past);
                    String future = LocalDate.now().plusDays(2 + (int) Randomly.getNotCachedInteger(0, 400)).toString();
                    set("", ".users[" + user + "].expires=" + future);
                    mustLogIn(user, password);
                    break;
                case EXPIRES:
                    String later = LocalDate.now().plusDays(2 + (int) Randomly.getNotCachedInteger(0, 4000)).toString();
                    set("deployment:\n  users:\n  - name: " + user + "\n    expires: " + later + "\n", "");
                    listed(user, password, role, later);
                    mustLogIn(user, password);
                    break;
                case ROLE:
                    String newRole;
                    do {
                        // the same role would be no change (status 2)
                        newRole = Randomly.fromOptions(ROLES);
                    } while (newRole.equals(role));
                    set("", ".users[" + user + "].roles=" + newRole);
                    listed(user, password, newRole, null);
                    role = newRole;
                    mustLogIn(user, password);
                    break;
                case REFUSED:
                    String[] change = Randomly.fromOptions(
                            new String[] { "", ".users[" + user + "].expires=" + LocalDate.now().getYear() + "-13-45" },
                            new String[] { "", ".users[" + user + "].expires=tomorrow" },
                            new String[] { "", ".users[" + state.getOptions().getUserName() + "].roles=user" },
                            new String[] { "", ".users[" + user + "]=1" });
                    String status = trySet(change[0], change[1]);
                    if (status == null) {
                        throw new AssertionError(lastQuery + " should be refused; it succeeded");
                    }
                    CHANGES_REFUSED.incrementAndGet();
                    listed(user, password, role, null);
                    mustLogIn(user, password);
                    break;
                default:
                    throw new AssertionError();
                }
            } finally {
                set("", ".users[" + user + "]=null");
            }
            if (users().containsKey(user)) {
                throw new AssertionError(
                        lastQuery + " should remove the user " + user + "; @GetYamlConfiguration " + "still lists it");
            }
            mustBeRefused(user, password, "a user that was removed");
            CHECKS.incrementAndGet();
        }
    }

    private enum Change {
        PASSWORD, EXPIRED, EXPIRES, ROLE, REFUSED
    }

    // a YAML document with the password in plain text or masked, or assignments
    private void add(String user, String password, String role, boolean avoidKnownBugs) throws SQLException {
        switch (Randomly.fromOptions(0, 1, 2)) {
        case 0:
            set("deployment:\n  users:\n  - name: " + user + "\n    password: \"" + password + "\"\n    roles: " + role
                    + "\n", "");
            break;
        case 1:
            String digest = sha256(password);
            if (!avoidKnownBugs && Randomly.getBoolean()) {
                // a masked password in lowercase: a user that never logs in (MASKED_CASE_BUG)
                digest = digest.toLowerCase(Locale.ROOT);
            }
            set("deployment:\n  users:\n  - name: " + user + "\n    password: \"" + digest
                    + "\"\n    plaintext: false\n    roles: " + role + "\n", "");
            break;
        default:
            set("", ".users[" + user + "].password=" + password + "\n.users[" + user + "].roles=" + role);
            break;
        }
    }

    private static String password() {
        int length = (int) Randomly.getNotCachedInteger(1, 25);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(PASSWORD_CHARS.charAt((int) Randomly.getNotCachedInteger(0, PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }

    private static String otherThan(String password) {
        while (true) {
            String other = Randomly.getBoolean() ? password()
                    : password + PASSWORD_CHARS.charAt((int) Randomly.getNotCachedInteger(0, PASSWORD_CHARS.length()));
            if (!other.equals(password)) {
                return other;
            }
        }
    }

    // the SHA-256 digest in uppercase hex, as VoltDB lists a masked password
    static String sha256(String password) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private void listed(String user, String password, String role, String expires) throws SQLException {
        Map<String, Map<String, String>> users = users();
        Map<String, String> u = users.get(user);
        if (u == null) {
            throw new AssertionError(
                    lastQuery + " does not list the user " + user + " just added; it lists " + users.keySet());
        }
        String expected = sha256(password);
        if (!expected.equals(u.get("password")) || !"false".equals(u.get("plaintext")) || !role.equals(u.get("roles"))
                || expires != null && !expires.equals(u.get("expires"))) {
            throw new AssertionError(lastQuery + " lists the user " + user + " as " + u + "; expected the password "
                    + expected + " (masked, plaintext false), the role " + role
                    + (expires == null ? "" : " and the expiry date " + expires));
        }
    }

    // the users @GetYamlConfiguration lists, by name, each with its fields
    private Map<String, Map<String, String>> users() throws SQLException {
        lastQuery = "@GetYamlConfiguration('deployment.users')";
        String yaml;
        try (CallableStatement call = connection().prepareCall("{call @GetYamlConfiguration(?)}")) {
            call.setString(1, "deployment.users");
            try (ResultSet rs = call.executeQuery()) {
                if (!rs.next() || rs.getLong("STATUS") != 1) {
                    throw new AssertionError(lastQuery + " failed");
                }
                yaml = rs.getString("DATA");
            }
        }
        Map<String, Map<String, String>> users = new LinkedHashMap<>();
        Map<String, String> current = null;
        for (String line : yaml.split("\n")) {
            String t = line.trim();
            if (t.startsWith("- ")) {
                current = new LinkedHashMap<>();
                t = t.substring(2).trim();
            }
            int colon = t.indexOf(':');
            if (current == null || colon < 0) {
                continue;
            }
            String value = t.substring(colon + 1).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            current.put(t.substring(0, colon).trim(), value);
            if ("name".equals(t.substring(0, colon).trim())) {
                users.put(value, current);
            }
        }
        return users;
    }

    private void set(String yaml, String assignments) throws SQLException {
        String status = trySet(yaml, assignments);
        if (status != null) {
            throw new AssertionError(lastQuery + " should succeed; it failed: " + status);
        }
    }

    // null if the change was applied, else why not
    private String trySet(String yaml, String assignments) throws SQLException {
        lastQuery = "@SetYamlConfiguration('" + yaml.replace("\n", "\\n") + "', '" + assignments.replace("\n", "\\n")
                + "')";
        try (CallableStatement call = connection().prepareCall("{call @SetYamlConfiguration(?, ?)}")) {
            call.setString(1, yaml);
            call.setString(2, assignments);
            try (ResultSet rs = call.executeQuery()) {
                long status = rs.next() ? rs.getLong(1) : -1;
                return status == 0 ? null : "status " + status;
            }
        } catch (SQLException e) {
            String m = String.valueOf(e.getMessage());
            if (m.contains("in progress")) {
                // another catalog change, from DDL: not this change's fault
                throw new IgnoreMeException();
            }
            return m.split("\n")[0];
        }
    }

    private void mustLogIn(String user, String password) throws Exception {
        lastQuery = "a login as " + user + " with the password " + password;
        Client c = ClientFactory.createClient(config(user, password));
        try {
            c.createConnection(server());
            ClientResponse r = c.callProcedure("@Ping");
            if (r.getStatus() != ClientResponse.SUCCESS) {
                throw new AssertionError(lastQuery + ": @Ping failed: " + r.getStatusString());
            }
        } catch (IOException | ProcCallException e) {
            throw new AssertionError(lastQuery + " should succeed; it failed: " + e.getMessage(), e);
        } finally {
            c.close();
        }
        ACCEPTED.incrementAndGet();
    }

    // the native client, not JDBC: the JDBC driver reports every failed connection as "Unable to connect to the
    // cluster", where the client says why ("Authentication rejected")
    private void mustBeRefused(String user, String password, String why) throws Exception {
        lastQuery = "a login as " + user + " with the password " + password + " (" + why + ")";
        Client c = ClientFactory.createClient(config(user, password));
        try {
            c.createConnection(server());
        } catch (IOException e) {
            String m = String.valueOf(e.getMessage());
            if (!m.contains("Authentication rejected")) {
                throw new AssertionError(
                        lastQuery + " should be refused for its credentials; it failed otherwise: " + m, e);
            }
            REFUSED.incrementAndGet();
            return;
        } finally {
            c.close();
        }
        throw new AssertionError(lastQuery + " should be refused; it logged in");
    }

    private ClientConfig config(String user, String password) {
        ClientConfig config = new ClientConfig(user, password);
        String truststore = state.getDbmsSpecificOptions().sslTruststore;
        if (truststore != null && !truststore.isBlank()) {
            config.setTrustStore(truststore.trim(), "sqlancer");
            config.enableSSL();
        }
        return config;
    }

    // the first server SQLancer connects to
    private String server() {
        String servers = state.getDbmsSpecificOptions().servers;
        return servers == null || servers.isBlank() ? state.getOptions().getHost() + ":" + state.getOptions().getPort()
                : servers.trim().split(",")[0].trim();
    }

    private VoltDBConnection connection() {
        return (VoltDBConnection) state.getConnection();
    }

    @Override
    public Reproducer<VoltDBGlobalState> getLastReproducer() {
        return null;
    }

    @Override
    public String getLastQueryString() {
        return lastQuery;
    }

}
