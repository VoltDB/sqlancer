package sqlancer.voltdb.oracle;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.Reproducer;
import sqlancer.common.oracle.TestOracle;
import sqlancer.voltdb.VoltDBConnection;
import sqlancer.voltdb.VoltDBProvider.VoltDBGlobalState;

/**
 * XDCR conflicts (milestone 38), which the XDCR oracle never makes: each database writes through one cluster only, so
 * milestone 30's coverage run found XDCR's conflict handling ({@code DRConflictManagerImpl}) and much of the multi-
 * partition replication ({@code DRConsumerMpCoordinatorImpl}) unexercised.
 * <p>
 * Each check uses a DR table of its own, {@code XC<P|R>_<db>} (partitioned on k, or replicated), on both clusters of an
 * XDCR run, and writes the same few keys through both at once: inserts, upserts, updates and deletes by key, and
 * updates and deletes by value, which on the partitioned table are multi-partition. Then it waits up to 60 s for the
 * clusters to agree. VoltDB resolves most conflicts so that they do; where they still differ on a key, its conflict log
 * (the {@code xdcr_conflicts} export files, under --cluster-dir) must report that key of that table as diverged
 * ({@code DIVERGENCE} D). run-sqlancer.sh does not count these tables' conflicts as failures.
 */
public class VoltDBXdcrConflictOracle implements TestOracle<VoltDBGlobalState> {

    private static final long CONVERGE_MILLIS = 60_000;
    private static final AtomicLong CHECKS = new AtomicLong();
    private static final AtomicLong CONVERGED = new AtomicLong();
    private static final AtomicLong DIVERGED = new AtomicLong();
    private static final Set<String> CREATED = ConcurrentHashMap.newKeySet();
    // a conflict log row: "ROW_TYPE","ACTION","TYPE","ON_PK","DECISION","CLUSTER","TIMESTAMP","DIVERGENCE","TABLE",
    // "CURRENT_CLUSTER","CURRENT_TIMESTAMP","{tuple as JSON}"
    private static final Pattern LOG_ROW = Pattern
            .compile("^\"[A-Z]+\",\"[A-Z]\",\"[A-Z]+\",\"\\d\",\"[A-Z]\",\"\\d+\",\"\\d+\",\"([CD])\",\"([^\"]+)\",.*"
                    + "\"\"K\"\":\"\"(-?\\d+)\"\".*");

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("XCONFLICT checks: " + CHECKS
                + " (converged: " + CONVERGED + "; differing keys each reported as diverged: " + DIVERGED + ")")));
    }

    private final VoltDBGlobalState state;
    private String lastQuery;

    public VoltDBXdcrConflictOracle(VoltDBGlobalState state) {
        this.state = state;
    }

    @Override
    public void check() throws Exception {
        VoltDBConnection here = (VoltDBConnection) state.getConnection();
        VoltDBConnection there = here.getPeer();
        if (there == null || here.isPeerSplit()) {
            throw new IgnoreMeException();
        }
        boolean partitioned = Randomly.getBoolean();
        String table = "XC" + (partitioned ? "P" : "R") + "_" + state.getDatabaseName().toUpperCase();
        create(table, partitioned);
        settle(here, there, table);
        // the same writes, with different values, through both clusters, interleaved
        int keys = (int) Randomly.getNotCachedInteger(1, 6);
        // the rows before the check, and its writes, for the report
        String before = rows(here, table) + " / " + rows(there, table);
        List<String> writes = new ArrayList<>();
        // ENG-30679: a DELETE of a key on one cluster racing a write of it (insert, update, upsert) on the other leaves
        // the clusters different with every conflict logged as consistent; while avoiding known bugs, a check deletes
        // nothing
        boolean upserts = true;
        boolean deletes = !state.getDbmsSpecificOptions().avoidKnownBugs;
        for (int round = (int) Randomly.getNotCachedInteger(1, 6); round > 0; round--) {
            for (int side = 0; side < 2; side++) {
                VoltDBConnection c = side == 0 ? here : there;
                String tag = (side == 0 ? "a" : "b") + Randomly.getNotCachedInteger(0, 9);
                int k = (int) Randomly.getNotCachedInteger(0, keys);
                String sql;
                int op = (int) Randomly.getNotCachedInteger(0, 6);
                if (op == 1 && !upserts) {
                    op = 0;
                } else if ((op == 3 || op == 5) && !deletes) {
                    op = 2;
                }
                switch (op) {
                case 0:
                    sql = "INSERT INTO " + table + " VALUES (" + k + ", '" + tag + "', "
                            + Randomly.getNotCachedInteger(0, 9) + ")";
                    break;
                case 1:
                    sql = "UPSERT INTO " + table + " VALUES (" + k + ", '" + tag + "', "
                            + Randomly.getNotCachedInteger(0, 9) + ")";
                    break;
                case 2:
                    sql = "UPDATE " + table + " SET v = '" + tag + "' WHERE k = " + k;
                    break;
                case 3:
                    sql = "DELETE FROM " + table + " WHERE k = " + k;
                    break;
                case 4:
                    // by value: on the partitioned table a multi-partition write
                    sql = "UPDATE " + table + " SET n = n + 1, v = '" + tag + "' WHERE n < "
                            + Randomly.getNotCachedInteger(0, 9);
                    break;
                default:
                    sql = "DELETE FROM " + table + " WHERE n = " + Randomly.getNotCachedInteger(0, 9);
                    break;
                }
                writes.add((side == 0 ? "A: " : "B: ") + sql);
                write(c, sql);
            }
        }
        CHECKS.incrementAndGet();
        long deadline = System.currentTimeMillis() + CONVERGE_MILLIS;
        Map<Integer, String> a;
        Map<Integer, String> b;
        while (true) {
            a = rows(here, table);
            b = rows(there, table);
            if (here.isPeerSplit()) {
                throw new IgnoreMeException();
            }
            if (a.equals(b) || System.currentTimeMillis() >= deadline) {
                break;
            }
            Thread.sleep(1000);
        }
        if (a.equals(b)) {
            CONVERGED.incrementAndGet();
            return;
        }
        List<Integer> differing = new ArrayList<>();
        for (int k = 0; k <= keys; k++) {
            if (!String.valueOf(a.get(k)).equals(String.valueOf(b.get(k)))) {
                differing.add(k);
            }
        }
        // the conflict log is exported to files, which the export flushes now and then
        List<Integer> unreported = new ArrayList<>(differing);
        for (long until = System.currentTimeMillis() + 15_000; System.currentTimeMillis() < until;) {
            Set<Integer> diverged = divergedKeys(table);
            unreported.removeIf(diverged::contains);
            if (unreported.isEmpty()) {
                break;
            }
            Thread.sleep(1000);
        }
        if (!unreported.isEmpty()) {
            throw new AssertionError("the XDCR clusters still differ on " + table + " after " + CONVERGE_MILLIS / 1000
                    + " s, on keys " + differing + ", and the conflict log reports no divergence (D) for " + unreported
                    + "; here (A): " + a + "; on the other cluster (B): " + b + "; before the check (A / B): " + before
                    + "; the check's writes, in order: " + writes);
        }
        DIVERGED.addAndGet(differing.size());
    }

    // brings the clusters to the same rows before a check: a divergence an earlier check left (one it reported, or that
    // the run let pass) would otherwise count against this one; empties the table through both if they differ
    private void settle(VoltDBConnection here, VoltDBConnection there, String table) throws Exception {
        if (rows(here, table).equals(rows(there, table))) {
            return;
        }
        write(here, "DELETE FROM " + table);
        write(there, "DELETE FROM " + table);
        for (long until = System.currentTimeMillis() + CONVERGE_MILLIS; System.currentTimeMillis() < until;) {
            if (rows(here, table).isEmpty() && rows(there, table).isEmpty()) {
                return;
            }
            Thread.sleep(500);
        }
        throw new IgnoreMeException();
    }

    // the keys of the table that a conflict log row marks diverged
    private Set<Integer> divergedKeys(String table) throws IOException {
        Set<Integer> keys = ConcurrentHashMap.newKeySet();
        String dir = state.getDbmsSpecificOptions().clusterDir;
        if (dir == null || dir.isBlank()) {
            return keys;
        }
        try (DirectoryStream<Path> nodes = Files.newDirectoryStream(Paths.get(dir))) {
            for (Path node : nodes) {
                Path logs = node.resolve("voltdbroot").resolve("xdcr_conflicts");
                if (!Files.isDirectory(logs)) {
                    continue;
                }
                try (DirectoryStream<Path> files = Files.newDirectoryStream(logs, "*.csv")) {
                    for (Path f : files) {
                        try (java.util.stream.Stream<String> lines = Files.lines(f)) {
                            lines.forEach(line -> {
                                Matcher m = LOG_ROW.matcher(line);
                                if (m.matches() && "D".equals(m.group(1)) && table.equals(m.group(2))) {
                                    keys.add(Integer.parseInt(m.group(3)));
                                }
                            });
                        } catch (java.nio.file.NoSuchFileException e) {
                            // rolled over meanwhile
                        }
                    }
                }
            }
        }
        return keys;
    }

    // DDL that may already have been done for this database name earlier in the run; the connection runs it on both
    // clusters
    private void create(String table, boolean partitioned) {
        if (CREATED.contains(table)) {
            return;
        }
        for (String sql : new String[] {
                "CREATE TABLE " + table + " (k INTEGER NOT NULL, v VARCHAR(8), n INTEGER NOT NULL, PRIMARY KEY (k))"
                        + (partitioned ? "; PARTITION TABLE " + table + " ON COLUMN k" : ""),
                "DR TABLE " + table }) {
            lastQuery = sql;
            try (Statement st = state.getConnection().createStatement()) {
                st.execute(sql);
            } catch (SQLException e) {
                String m = String.valueOf(e.getMessage());
                if (!m.contains("already exists") && !m.contains("already DR")) {
                    throw new AssertionError(sql, e);
                }
            }
        }
        CREATED.add(table);
    }

    // a write through one cluster; one that a constraint refuses is part of the test, one that a node failure stops
    // skips the check
    private void write(VoltDBConnection c, String sql) {
        lastQuery = sql;
        try (PreparedStatement st = (PreparedStatement) c.prepareStatement(sql)) {
            st.execute();
        } catch (SQLException e) {
            if (!String.valueOf(e.getMessage()).contains("CONSTRAINT VIOLATION")) {
                throw new IgnoreMeException();
            }
        }
    }

    // the table's rows by key, each its values joined with " | "
    private Map<Integer, String> rows(VoltDBConnection c, String table) throws SQLException {
        String sql = "SELECT k, v, n FROM " + table;
        lastQuery = sql;
        Map<Integer, String> rows = new TreeMap<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rows.put(rs.getInt(1), rs.getString(2) + " | " + rs.getInt(3));
            }
        }
        return rows;
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
