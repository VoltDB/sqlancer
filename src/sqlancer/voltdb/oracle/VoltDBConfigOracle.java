package sqlancer.voltdb.oracle;

import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.Reproducer;
import sqlancer.common.oracle.TestOracle;
import sqlancer.voltdb.VoltDBConnection;
import sqlancer.voltdb.VoltDBProvider.VoltDBGlobalState;

/**
 * Configuration changed online (milestone 36), which milestone 30's coverage run found unexercised:
 * {@code @SetYamlConfiguration}, {@code @GetYamlConfiguration} and the code that applies a deployment change.
 * <p>
 * Each check changes one system setting, with assignments ({@code deployment.systemsettings.x.y=v}) or a YAML document,
 * then reads the system settings back: the setting must have its new value, and every other setting its old one. Now
 * and then it also sends a change VoltDB must refuse (a value that is not a number, a setting that cannot change while
 * the database runs, a setting that does not exist), and the settings must not change.
 * <p>
 * The settings are ones that cannot change other oracles' results: snapshot priority, flush intervals, the resource
 * monitor's frequency, compaction, elastic resize throughput, procedure logging, the clock skew interval, and query
 * timeouts of 10 s or more. One check runs at a time, so that the settings read back are this check's.
 */
public class VoltDBConfigOracle implements TestOracle<VoltDBGlobalState> {

    // a setting under deployment.systemsettings and the values it may take
    private static final Map<String, Object[]> SETTINGS = new LinkedHashMap<>();
    private static final Object LOCK = new Object();
    private static final AtomicLong CHANGES = new AtomicLong();
    private static final AtomicLong REFUSALS = new AtomicLong();

    static {
        SETTINGS.put("query.timeout", new Object[] { 10000, 30000, 60000, 120000 });
        SETTINGS.put("snapshot.priority", new Object[] { 0, 1, 6, 10 });
        SETTINGS.put("flushinterval.minimum", new Object[] { 500, 1000, 2000 });
        SETTINGS.put("flushinterval.export.interval", new Object[] { 1000, 4000, 10000 });
        SETTINGS.put("resourcemonitor.frequency", new Object[] { 5, 30, 60, 300 });
        SETTINGS.put("compaction.interval", new Object[] { 10, 60, 600 });
        SETTINGS.put("compaction.maxcount", new Object[] { 1, 5, 20 });
        SETTINGS.put("elastic.throughput", new Object[] { 1, 2, 10 });
        SETTINGS.put("elastic.duration", new Object[] { 10, 50, 100 });
        SETTINGS.put("procedure.loginfo", new Object[] { 1000, 10000, 100000 });
        SETTINGS.put("clockskew.interval", new Object[] { 10, 60, 600 });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out
                .println("CONFIG changes checked: " + CHANGES + "; refused changes checked: " + REFUSALS)));
    }

    private final VoltDBGlobalState state;
    private String lastQuery;

    public VoltDBConfigOracle(VoltDBGlobalState state) {
        this.state = state;
    }

    @Override
    public void check() throws Exception {
        synchronized (LOCK) {
            String before = settings();
            if (Randomly.getBooleanWithRatherLowProbability()) {
                refused(before);
                return;
            }
            String key = Randomly.fromList(new ArrayList<>(SETTINGS.keySet()));
            Object value = Randomly.fromOptions(SETTINGS.get(key));
            String path = "deployment.systemsettings." + key;
            String expected = withValue(before, key, value);
            // @UpdateDeployment's status 2 (UPDATE_NOCHANGE): the value was already set
            boolean same = expected.equals(before);
            String status;
            if (Randomly.getBoolean()) {
                status = set("", path + "=" + value);
            } else {
                status = set(yaml(key, value), "");
            }
            if (status != null && !(same && "status 2".equals(status))) {
                throw new AssertionError(lastQuery + " should change " + path + " to " + value + ", a value it takes "
                        + "while the database runs; it failed: " + status);
            }
            CHANGES.incrementAndGet();
            String after = settings();
            if (!after.equals(expected)) {
                throw new AssertionError(lastQuery + " should change " + path + " to " + value + " and nothing else;"
                        + " the system settings were:\n" + before + "\nand are now:\n" + after);
            }
        }
    }

    // a change VoltDB must refuse, after which the settings must be as they were
    private void refused(String before) throws SQLException {
        String key = Randomly.fromList(new ArrayList<>(SETTINGS.keySet()));
        String[] change = Randomly.fromOptions(new String[] { "", "deployment.systemsettings." + key + "=abc" },
                new String[] { "", "deployment.cluster.sitesperhost=3" },
                new String[] { "", "deployment.systemsettings.nosuch.setting=1" },
                new String[] { "deployment: [1, 2]", "" }, new String[] { "not yaml: [", "" });
        String status = set(change[0], change[1]);
        REFUSALS.incrementAndGet();
        String after = settings();
        if (status == null || !after.equals(before)) {
            throw new AssertionError(lastQuery + " should be refused and leave the configuration as it was; it "
                    + (status == null ? "succeeded" : "failed (" + status + ")") + (after.equals(before) ? ""
                            : ", and the system settings were:\n" + before + "\nand are now:\n" + after));
        }
    }

    private static String yaml(String key, Object value) {
        StringBuilder sb = new StringBuilder("deployment:\n  systemsettings:\n");
        String indent = "    ";
        String[] parts = key.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            sb.append(indent).append(parts[i]).append(':');
            if (i == parts.length - 1) {
                sb.append(' ').append(value);
            }
            sb.append('\n');
            indent += "  ";
        }
        return sb.toString();
    }

    // the settings' YAML with one value replaced: the line of the key's last part, under its parents
    private static String withValue(String yaml, String key, Object value) {
        String[] parts = key.split("\\.");
        List<String> lines = new ArrayList<>(List.of(yaml.split("\n", -1)));
        int depth = 0;
        int from = 0;
        for (int i = 0; i < parts.length; i++) {
            boolean found = false;
            for (int l = from; l < lines.size(); l++) {
                String line = lines.get(l);
                String trimmed = line.trim();
                int indent = line.length() - line.stripLeading().length();
                if (trimmed.startsWith(parts[i] + ":") && indent >= depth) {
                    if (i == parts.length - 1) {
                        lines.set(l, line.substring(0, indent) + parts[i] + ": " + value);
                    }
                    depth = indent + 1;
                    from = l + 1;
                    found = true;
                    break;
                }
            }
            if (!found) {
                // a setting the configuration did not show before: what VoltDB adds for it is not predicted
                throw new IgnoreMeException();
            }
        }
        return String.join("\n", lines);
    }

    private String settings() throws SQLException {
        lastQuery = "@GetYamlConfiguration('deployment.systemsettings')";
        try (CallableStatement call = connection().prepareCall("{call @GetYamlConfiguration(?)}")) {
            call.setString(1, "deployment.systemsettings");
            try (ResultSet rs = call.executeQuery()) {
                if (!rs.next() || rs.getLong("STATUS") != 1) {
                    throw new AssertionError(lastQuery + " failed");
                }
                return rs.getString("DATA");
            }
        } catch (SQLException e) {
            // a node failure, a resize or a catalog change in progress
            throw new IgnoreMeException();
        }
    }

    // null if the change was applied, else why not
    private String set(String yaml, String assignments) {
        lastQuery = "@SetYamlConfiguration(" + quote(yaml) + ", " + quote(assignments) + ")";
        try (CallableStatement call = connection().prepareCall("{call @SetYamlConfiguration(?, ?)}")) {
            call.setString(1, yaml);
            call.setString(2, assignments);
            try (ResultSet rs = call.executeQuery()) {
                long status = rs.next() ? rs.getLong(1) : -1;
                return status == 0 ? null : "status " + status;
            }
        } catch (SQLException e) {
            String m = String.valueOf(e.getMessage());
            // a change that a node failure, a resize or another catalog change interrupted: not the change's fault
            if (m.contains("Connection") || m.contains("in progress") || m.contains("rejoin") || m.contains("elastic")
                    || m.contains("Server is paused") || m.contains("Unable to execute")) {
                throw new IgnoreMeException();
            }
            return m.split("\n")[0];
        }
    }

    private static String quote(String s) {
        return "'" + s.replace("\n", "\\n") + "'";
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
