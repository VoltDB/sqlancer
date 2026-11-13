package sqlancer.voltdb.oracle;

import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.Reproducer;
import sqlancer.common.oracle.TestOracle;
import sqlancer.voltdb.VoltDBConnection;
import sqlancer.voltdb.VoltDBOptions;
import sqlancer.voltdb.VoltDBProvider.VoltDBGlobalState;
import sqlancer.voltdb.VoltDBSchema.VoltDBTable;

/**
 * Statistics (milestone 43), about half of whose code milestone 41's coverage run found unexercised
 * ({@code SystemInformation}, {@code StatsAgent}, the statistics sources). Each STATS check:
 * <ul>
 * <li>calls one of {@code @Statistics}' selectors, {@code @SystemInformation} or {@code @SystemCatalog}, which must
 * succeed;</li>
 * <li>once {@code @Statistics TABLE} has stopped changing (it is refreshed every second or two), checks it against the
 * database's tables: each table's tuple count, summed over its partitions, or on every site for a replicated table,
 * must be its {@code COUNT(*)}; every replica of a partition must have the same count;</li>
 * <li>and {@code @Statistics INDEX}: on each site, a primary key's index must have as many entries as the table has
 * tuples there.</li>
 * </ul>
 * The database's tables are written by this thread only, so they do not change during a check.
 */
public class VoltDBStatsOracle implements TestOracle<VoltDBGlobalState> {

    private static final String[] SELECTORS = { "MEMORY", "PROCEDURE", "PROCEDUREDETAIL", "PROCEDUREPROFILE",
            "PROCEDUREINPUT", "PROCEDUREOUTPUT", "INITIATOR", "IOSTATS", "LIVECLIENTS", "LATENCY", "PLANNER",
            "COMPACTION", "CPU", "GC", "IDLETIME", "QUEUE", "STARVATION", "COMMANDLOG", "SNAPSHOTSTATUS",
            "SNAPSHOTSUMMARY", "TTL", "TASK", "IMPORT", "EXPORT", "PARTITIONCOUNT", "TOPO", "REBALANCE", "DRROLE",
            "DRPRODUCER", "DRCONSUMER", "CLOCKSKEW", "LIMITS", "TABLE", "INDEX" };
    private static final String[] INFORMATION = { "OVERVIEW", "DEPLOYMENT", "LICENSE" };
    private static final String[] CATALOG = { "TABLES", "COLUMNS", "INDEXINFO", "PRIMARYKEYS", "PROCEDURES",
            "PROCEDURECOLUMNS", "CLASSES" };
    private static final AtomicLong CALLS = new AtomicLong();
    private static final AtomicLong TABLES = new AtomicLong();
    private static final AtomicLong INDEXES = new AtomicLong();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("STATS calls checked: " + CALLS
                + "; tables' tuple counts checked: " + TABLES + "; primary key indexes checked: " + INDEXES)));
    }

    private final VoltDBGlobalState state;
    private String lastQuery;

    public VoltDBStatsOracle(VoltDBGlobalState state) {
        this.state = state;
    }

    @Override
    public void check() throws Exception {
        call();
        VoltDBOptions o = state.getDbmsSpecificOptions();
        boolean chaos = o.nodeFailures || o.resizes || o.crashes || o.upgrades;
        String prefix = state.getDatabaseName().toUpperCase() + "_";
        Map<String, Long> counts = new TreeMap<>();
        for (VoltDBTable table : state.getSchema().getDatabaseTables()) {
            String name = table.getName().toUpperCase();
            if (!name.startsWith(prefix)) {
                continue;
            }
            // ENG-30667: after a rejoin a view over a join of replicated tables can hold its row twice on the
            // rejoined node; while avoiding known bugs, views are left out where nodes fail
            if (table.isView() && chaos && o.avoidKnownBugs) {
                continue;
            }
            counts.put(name, count("SELECT COUNT(*) FROM " + name));
        }
        if (counts.isEmpty()) {
            throw new IgnoreMeException();
        }
        // where nodes fail, a site can be rejoining, and its tables still filling: try again a few times
        List<String> problems = null;
        for (int attempt = 0; attempt < (chaos ? 4 : 1); attempt++) {
            if (attempt > 0) {
                Thread.sleep(5000);
            }
            problems = compare(counts, settled());
            if (problems.isEmpty()) {
                break;
            }
        }
        if (!problems.isEmpty()) {
            if (chaos) {
                // a node may be failing right now; a problem a failure explains is not one
                throw new IgnoreMeException();
            }
            throw new AssertionError(
                    "@Statistics TABLE/INDEX disagree with the tables: " + String.join("; ", problems));
        }
        TABLES.addAndGet(counts.size());
    }

    // one selector of @Statistics, @SystemInformation or @SystemCatalog, which must succeed
    private void call() throws SQLException {
        String proc;
        Object[] args;
        switch ((int) Randomly.getNotCachedInteger(0, 4)) {
        case 0:
            proc = "@SystemInformation";
            args = new Object[] { Randomly.fromOptions(INFORMATION) };
            break;
        case 1:
            proc = "@SystemCatalog";
            args = new Object[] { Randomly.fromOptions(CATALOG) };
            break;
        default:
            proc = "@Statistics";
            args = new Object[] { Randomly.fromOptions(SELECTORS), Randomly.getNotCachedInteger(0, 2) };
            break;
        }
        StringBuilder sql = new StringBuilder("{call ").append(proc).append('(');
        for (int i = 0; i < args.length; i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        lastQuery = proc + Arrays.toString(args);
        try (CallableStatement call = connection().prepareCall(sql.append(")}").toString())) {
            for (int i = 0; i < args.length; i++) {
                call.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = call.executeQuery()) {
                while (rs.next()) {
                    rs.getObject(1);
                }
            }
        } catch (SQLException e) {
            throw new AssertionError(lastQuery + " failed", e);
        }
        CALLS.incrementAndGet();
    }

    // @Statistics TABLE and INDEX, read until TABLE's tuple counts are the same twice in a row (at most 15 reads)
    private Snapshot settled() throws Exception {
        Snapshot last = read();
        for (int i = 0; i < 15; i++) {
            Thread.sleep(1000);
            Snapshot now = read();
            if (now.tuples.equals(last.tuples)) {
                return now;
            }
            last = now;
        }
        return last;
    }

    private Snapshot read() throws SQLException {
        Snapshot s = new Snapshot();
        String prefix = state.getDatabaseName().toUpperCase() + "_";
        try (CallableStatement call = statistics("TABLE"); ResultSet rs = call.executeQuery()) {
            while (rs.next()) {
                String table = rs.getString("TABLE_NAME");
                if (table.startsWith(prefix)) {
                    s.tuples.put(table + " " + rs.getLong("HOST_ID") + ":" + rs.getLong("SITE_ID") + " p"
                            + rs.getLong("PARTITION_ID"), rs.getLong("TUPLE_COUNT"));
                }
            }
        }
        try (CallableStatement call = statistics("INDEX"); ResultSet rs = call.executeQuery()) {
            while (rs.next()) {
                String table = rs.getString("TABLE_NAME");
                String index = rs.getString("INDEX_NAME");
                if (table.startsWith(prefix) && index.startsWith("VOLTDB_AUTOGEN_IDX_PK_")) {
                    s.pkEntries.put(table + " " + rs.getLong("HOST_ID") + ":" + rs.getLong("SITE_ID") + " p"
                            + rs.getLong("PARTITION_ID"), rs.getLong("ENTRY_COUNT"));
                }
            }
        }
        return s;
    }

    // the differences between the statistics and the tables' counts
    private List<String> compare(Map<String, Long> counts, Snapshot s) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Long> table : counts.entrySet()) {
            String name = table.getKey();
            long count = table.getValue();
            // the table's tuple count on each site, and the counts of each partition's replicas
            List<Long> sites = new ArrayList<>();
            Map<String, Set<Long>> partitions = new TreeMap<>();
            for (Map.Entry<String, Long> e : s.tuples.entrySet()) {
                String key = e.getKey();
                if (key.startsWith(name + " ")) {
                    sites.add(e.getValue());
                    partitions.computeIfAbsent(key.substring(key.lastIndexOf(' ') + 1), p -> new TreeSet<>())
                            .add(e.getValue());
                }
            }
            if (sites.isEmpty()) {
                // a table @Statistics does not list yet
                continue;
            }
            for (Map.Entry<String, Set<Long>> p : partitions.entrySet()) {
                if (p.getValue().size() > 1) {
                    problems.add(name + "'s replicas of partition " + p.getKey() + " hold " + p.getValue() + " tuples");
                }
            }
            long sum = 0;
            for (Set<Long> p : partitions.values()) {
                sum += p.iterator().next();
            }
            boolean everySite = sites.stream().allMatch(n -> n == count);
            if (sum != count && !everySite) {
                problems.add(name + ": COUNT(*) " + count + ", @Statistics TABLE's tuples " + sum
                        + " summed over its partitions, by site " + sites);
            }
            // a primary key's index holds one entry per tuple on each site
            for (Map.Entry<String, Long> e : s.pkEntries.entrySet()) {
                if (e.getKey().startsWith(name + " ")) {
                    Long tuples = s.tuples.get(e.getKey());
                    if (tuples != null && !tuples.equals(e.getValue())) {
                        problems.add(name + " at " + e.getKey().substring(name.length() + 1) + ": " + tuples
                                + " tuples, but its primary key's index has " + e.getValue() + " entries");
                    }
                    INDEXES.incrementAndGet();
                }
            }
        }
        return problems;
    }

    private CallableStatement statistics(String selector) throws SQLException {
        lastQuery = "@Statistics('" + selector + "', 0)";
        CallableStatement call = connection().prepareCall("{call @Statistics(?, ?)}");
        call.setString(1, selector);
        call.setInt(2, 0);
        return call;
    }

    private long count(String sql) throws SQLException {
        lastQuery = sql;
        try (Statement st = connection().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            // a table the provider's schema still lists but that is gone
            throw new IgnoreMeException();
        }
    }

    private VoltDBConnection connection() {
        return (VoltDBConnection) state.getConnection();
    }

    private static final class Snapshot {
        private final Map<String, Long> tuples = new HashMap<>();
        private final Map<String, Long> pkEntries = new HashMap<>();
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
