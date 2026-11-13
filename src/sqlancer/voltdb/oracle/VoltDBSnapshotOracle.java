package sqlancer.voltdb.oracle;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.Reproducer;
import sqlancer.common.oracle.TestOracle;
import sqlancer.voltdb.VoltDBConnection;
import sqlancer.voltdb.VoltDBOptions;
import sqlancer.voltdb.VoltDBProvider.VoltDBGlobalState;

/**
 * Snapshots (milestone 37), whose save and restore code milestone 30's coverage run found little exercised:
 * {@code @SnapshotSave} of one table, in the native and CSV formats, {@code @SnapshotRestore} of that table into the
 * running database, {@code @SnapshotScan} and {@code @SnapshotDelete}. Each check uses a table of its own,
 * {@code XN<P|R>_<db>}, shaped as LOAD's twins (see VoltDBBulkOracle): partitioned on k or replicated, a primary key
 * (k, id) and a unique index (k, v).
 * <p>
 * A check fills the table, saves a native snapshot of it alone (blocking) and changes it. Then either it empties the
 * table and restores it, which must bring back exactly the rows saved; or it restores into the changed table with a
 * duplicates path, which must add each saved row that collides with no row there (on the primary key or the unique
 * index) and write each one that does to the duplicates files, and change nothing else. VoltDB reports a restore that
 * found duplicates as failed, so the oracle checks the table and the files, not the status. A third of the checks also
 * save a CSV snapshot of the table, whose files must hold its rows. {@code @SnapshotScan} must list the snapshot, and
 * {@code @SnapshotDelete} removes the check's snapshots.
 * <p>
 * The snapshots go to each node's own snapshot directory ({@code pathType SNAP_AUTO}), under its voltdbroot:
 * run-sqlancer.sh's nodes all run on one machine, and in one shared directory they find each other's files and refuse
 * the nonce. --snapshot-path is the directory holding the voltdbroots, where the oracle finds the files. Every replica
 * of a partition writes the duplicates it finds, so they and the CSV files' rows are counted as distinct rows.
 */
public class VoltDBSnapshotOracle implements TestOracle<VoltDBGlobalState> {

    private static final Object LOCK = new Object();
    private static final AtomicLong NONCES = new AtomicLong();
    private static final AtomicLong RESTORES = new AtomicLong();
    private static final AtomicLong MERGES = new AtomicLong();
    private static final AtomicLong CSVS = new AtomicLong();
    // saves VoltDB queued while another snapshot ran, and took later
    private static final AtomicLong QUEUED = new AtomicLong();
    // why saves were given up, and how often
    private static final java.util.Map<String, Long> SKIPS = new ConcurrentHashMap<>();
    // the tables created so far (never dropped): their DDL goes to the cluster once
    private static final Set<String> CREATED = ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime()
                .addShutdownHook(new Thread(() -> System.out.println("SNAPSHOT restores checked: " + RESTORES
                        + " into an empty table, " + MERGES + " with duplicates; CSV snapshots checked: " + CSVS
                        + "; saves queued and taken later: " + QUEUED + "; saves given up: " + SKIPS)));
    }

    private final VoltDBGlobalState state;
    private String lastQuery;

    public VoltDBSnapshotOracle(VoltDBGlobalState state) {
        this.state = state;
    }

    // a row as read back: its text, and the values the constraints look at
    private static final class Row {
        final String text;
        final Object k;
        final Object id;
        final Object v;

        Row(List<Object> values) {
            this.text = VoltDBRows.format(values);
            this.k = values.get(0);
            this.id = values.get(1);
            this.v = values.get(2);
        }

        // the same primary key, or the same (k, v): VoltDB's unique indexes count NULLs as equal (an INSERT of a second
        // (1, NULL) breaks the index on (k, v) too)
        boolean collides(Row other) {
            return Objects.equals(k, other.k) && (Objects.equals(id, other.id) || Objects.equals(v, other.v));
        }
    }

    @Override
    public void check() throws Exception {
        // VoltDB takes one snapshot at a time: a save while another runs is queued, not done, so checks take turns
        synchronized (LOCK) {
            checkAlone();
        }
    }

    private void checkAlone() throws Exception {
        String dir = state.getDbmsSpecificOptions().snapshotPath;
        if (dir == null || dir.isBlank()) {
            throw new IgnoreMeException();
        }
        boolean partitioned = Randomly.getBoolean();
        String table = "XN" + (partitioned ? "P" : "R") + "_" + state.getDatabaseName().toUpperCase();
        create(table, partitioned);
        write("DELETE FROM " + table);
        int k = (int) Randomly.getNotCachedInteger(0, 5);
        for (int i = (int) Randomly.getNotCachedInteger(0, 15); i > 0; i--) {
            tryWrite("INSERT INTO " + table + " VALUES " + VoltDBBulkOracle.literal(VoltDBBulkOracle.row(key(k))));
        }
        List<Row> saved = rows(table);
        String nonce = "sqlancer_" + table + "_" + NONCES.incrementAndGet();
        List<String> nonces = new ArrayList<>();
        try {
            save(nonce, table, "native");
            nonces.add(nonce);
            scan(dir, nonce, table);
            if (Randomly.fromOptions(true, false, false)) {
                String csv = nonce + "_csv";
                save(csv, table, "csv");
                nonces.add(csv);
                checkCsv(dir, csv, table, saved.size());
            }
            // change the table: delete some rows, update some, insert some
            write("DELETE FROM " + table + " WHERE id < " + Randomly.getNotCachedInteger(0, 60));
            write("UPDATE " + table + " SET f = 7.5E0 WHERE id > " + Randomly.getNotCachedInteger(0, 60));
            for (int i = (int) Randomly.getNotCachedInteger(0, 8); i > 0; i--) {
                tryWrite("INSERT INTO " + table + " VALUES " + VoltDBBulkOracle.literal(VoltDBBulkOracle.row(key(k))));
            }
            if (Randomly.getBoolean()) {
                write("DELETE FROM " + table);
                restore(nonce, table, null);
                RESTORES.incrementAndGet();
                List<String> now = texts(rows(table));
                List<String> expected = texts(saved);
                if (!now.equals(expected)) {
                    throw new AssertionError(
                            lastQuery + " into the emptied table should bring back the rows the snapshot"
                                    + " saved; only in the table: " + VoltDBRows.difference(now, expected)
                                    + "; only in the snapshot: " + VoltDBRows.difference(expected, now));
                }
            } else {
                List<Row> current = rows(table);
                // each node writes the duplicates it finds into its own snapshot directory, as
                // <TABLE>-duplicates-<time>.csv; those of earlier checks go first
                for (Path f : files(dir, n -> n.startsWith(table + "-duplicates-"))) {
                    Files.deleteIfExists(f);
                }
                restore(nonce, table, "duplicates");
                MERGES.incrementAndGet();
                List<String> expected = texts(current);
                int collisions = 0;
                for (Row r : saved) {
                    if (current.stream().anyMatch(r::collides)) {
                        collisions++;
                    } else {
                        expected.add(r.text);
                    }
                }
                expected.sort(null);
                List<String> now = texts(rows(table));
                // every replica of a partition writes its duplicates, so the rows are counted once each; they are
                // written in
                // the background (the "Restore duplicate row handler") after the restore's answer: a replicated
                // table's, on a
                // cluster, up to a minute later
                // ENG-30678: after a node has rejoined, a restore's duplicates can go unwritten, the rows left out of
                // the
                // table but in no file; while avoiding known bugs and nodes fail or the cluster changes, the files are
                // not counted
                VoltDBOptions o = state.getDbmsSpecificOptions();
                boolean countFiles = !o.avoidKnownBugs || !(o.nodeFailures || o.resizes || o.crashes || o.upgrades);
                long written = 0;
                for (long until = System.currentTimeMillis() + (countFiles ? 70_000 : 0); System
                        .currentTimeMillis() < until;) {
                    written = distinctLines(files(dir, n -> n.startsWith(table + "-duplicates-")));
                    if (written >= collisions) {
                        break;
                    }
                    Thread.sleep(250);
                }
                if (!now.equals(expected) || countFiles && written != collisions) {
                    throw new AssertionError(
                            lastQuery + " into the changed table should add each saved row that collides "
                                    + "with none there and write the " + collisions
                                    + " that do to the duplicates files; they hold " + written
                                    + " rows; only in the table: " + VoltDBRows.difference(now, expected)
                                    + "; missing from it: " + VoltDBRows.difference(expected, now));
                }
            }
        } finally {
            for (String n : nonces) {
                delete(dir, n);
            }
        }
    }

    // ENG-30676: a restore while a node rejoins wedges the cluster (a site's queue blocked for good); while
    // avoiding known bugs, a restore waits while --ddl-hold's file exists, which run-sqlancer.sh creates from before
    // each kill until the node has rejoined
    private void awaitNoHold() {
        String hold = state.getDbmsSpecificOptions().ddlHold;
        if (hold == null || !state.getDbmsSpecificOptions().avoidKnownBugs) {
            return;
        }
        long deadline = System.currentTimeMillis() + 900_000;
        while (Files.exists(Paths.get(hold)) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IgnoreMeException();
            }
        }
    }

    // the k of a row: for a partitioned table one of two values, so that both a single partition and several hold rows
    private static int key(int k) {
        return Randomly.getBoolean() ? k : k + 1;
    }

    private void save(String nonce, String table, String format) {
        // SNAP_AUTO: each node's own snapshot directory, under its voltdbroot (all the nodes of run-sqlancer.sh's
        // cluster are on one machine, and in one directory they would find each other's files and refuse the nonce)
        String json = "{\"path\":\"\",\"pathType\":\"SNAP_AUTO\",\"nonce\":\"" + nonce + "\",\"block\":true,"
                + "\"format\":\"" + format + "\",\"tables\":[\"" + table + "\"]}";
        lastQuery = "@SnapshotSave('" + json + "')";
        long deadline = System.currentTimeMillis() + 90_000;
        String refused = null;
        while (System.currentTimeMillis() < deadline) {
            refused = null;
            try (CallableStatement call = connection().prepareCall("{call @SnapshotSave(?)}")) {
                call.setString(1, json);
                try (ResultSet rs = call.executeQuery()) {
                    while (rs.next()) {
                        String err = rs.getString("ERR_MSG");
                        if (!"SUCCESS".equals(rs.getString("RESULT")) || err != null && !err.isEmpty()) {
                            refused = rs.getString("RESULT") + " " + err;
                        }
                    }
                }
            } catch (SQLException e) {
                refused = String.valueOf(e.getMessage()).split("\n")[0];
            }
            if (refused == null) {
                return;
            }
            // while another snapshot runs (the truncation snapshot a restore asks for, the command log's), VoltDB
            // answers SNAPSHOT REQUEST QUEUED (or SNAPSHOT IN PROGRESS) and takes the request later, after
            // USER_SNAPSHOT_RETRY_INTERVAL seconds (run-sqlancer.sh sets 1); a request sent again then finds the nonce
            // taken
            if (refused.contains("QUEUED") || refused.contains("IN PROGRESS") || refused.contains("SAME NONCE")) {
                if (awaitComplete(nonce, deadline)) {
                    return;
                }
            } else {
                break;
            }
        }
        SKIPS.merge(refused == null ? "timed out" : refused.length() > 60 ? refused.substring(0, 60) : refused, 1L,
                Long::sum);
        throw new IgnoreMeException();
    }

    // whether @Statistics SNAPSHOTSTATUS comes to show the snapshot done on every host before the deadline
    private boolean awaitComplete(String nonce, long deadline) {
        while (System.currentTimeMillis() < deadline) {
            try (CallableStatement call = connection().prepareCall("{call @Statistics(?, ?)}")) {
                call.setString(1, "SNAPSHOTSTATUS");
                call.setInt(2, 0);
                try (ResultSet rs = call.executeQuery()) {
                    int rows = 0;
                    boolean done = true;
                    while (rs.next()) {
                        if (nonce.equals(rs.getString("NONCE"))) {
                            rows++;
                            done &= "SUCCESS".equals(rs.getString("RESULT"));
                        }
                    }
                    if (rows > 0 && done) {
                        QUEUED.incrementAndGet();
                        return true;
                    }
                }
            } catch (SQLException e) {
                return false;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void restore(String nonce, String table, String duplicatesPath) {
        awaitNoHold();
        if (!state.getDbmsSpecificOptions().avoidKnownBugs) {
            restoreNow(nonce, table, duplicatesPath);
            return;
        }
        // ENG-30677: a restore while DDL runs crashes the server on writes to a join view's replicated table; no DDL of
        // this SQLancer runs during the restore
        try {
            VoltDBConnection.withoutDdl(() -> {
                restoreNow(nonce, table, duplicatesPath);
                return null;
            });
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new IgnoreMeException();
        }
    }

    private void restoreNow(String nonce, String table, String duplicatesPath) {
        String json = "{\"path\":\"\",\"pathType\":\"SNAP_AUTO\",\"nonce\":\"" + nonce + "\",\"tables\":[\"" + table
                + "\"]" + (duplicatesPath == null ? "" : ",\"duplicatesPath\":\"" + duplicatesPath + "\"") + "}";
        lastQuery = "@SnapshotRestore('" + json + "')";
        try (CallableStatement call = connection().prepareCall("{call @SnapshotRestore(?)}")) {
            call.setString(1, json);
            try (ResultSet rs = call.executeQuery()) {
                int parts = 0;
                while (rs.next()) {
                    // each site's part; the table's contents decide
                    parts++;
                }
                lastQuery += " (" + parts + " results)";
            }
        } catch (SQLException e) {
            String m = String.valueOf(e.getMessage());
            // a restore that found duplicates reports that it failed; one that a node failure stopped is skipped
            if (duplicatesPath == null || !m.contains("Restore failed to complete")) {
                throw new IgnoreMeException();
            }
        }
    }

    // @SnapshotScan of a node's snapshot directory must list the snapshot, of the table alone
    private void scan(String dir, String nonce, String table) throws IOException {
        List<Path> mine = files(dir, n -> n.startsWith(nonce + "-") && n.endsWith(".vpt"));
        if (mine.isEmpty()) {
            throw new AssertionError("@SnapshotSave of " + table + " (nonce " + nonce
                    + ") reported success, but no node's" + " snapshot directory under " + dir + " holds its files");
        }
        String nodeDir = mine.get(0).getParent().toString();
        lastQuery = "@SnapshotScan('" + nodeDir + "')";
        boolean found = false;
        try (CallableStatement call = connection().prepareCall("{call @SnapshotScan(?)}")) {
            call.setString(1, nodeDir);
            try (ResultSet rs = call.executeQuery()) {
                while (rs.next()) {
                    if (nonce.equals(rs.getString("NONCE"))) {
                        found = true;
                        String required = rs.getString("TABLES_REQUIRED");
                        if (!table.equals(required)) {
                            throw new AssertionError(lastQuery + " lists the snapshot " + nonce + " of " + table
                                    + " with TABLES_REQUIRED " + required);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            throw new IgnoreMeException();
        }
        if (!found) {
            throw new AssertionError(lastQuery + " does not list the snapshot " + nonce + " just saved");
        }
    }

    // a CSV snapshot's files for the table must hold its rows: a partitioned table's in a file per host,
    // nonce-TABLE-host_N.csv, each partition's rows written once; a replicated table's in nonce-TABLE.csv
    private void checkCsv(String dir, String nonce, String table, int rows) throws IOException {
        CSVS.incrementAndGet();
        long written = distinctLines(files(dir, n -> n.equals(nonce + "-" + table + ".csv")
                || n.startsWith(nonce + "-" + table + "-host_") && n.endsWith(".csv")));
        if (written != rows) {
            throw new AssertionError("@SnapshotSave of " + table + " in CSV (nonce " + nonce + ") wrote " + written
                    + " distinct rows to its files; the table held " + rows);
        }
    }

    // the files in the nodes' snapshot directories (dir/<node>/voltdbroot/snapshots) whose names pass the test; a file
    // the server deletes meanwhile is left out
    private static List<Path> files(String dir, java.util.function.Predicate<String> name) throws IOException {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> nodes = Files.newDirectoryStream(Paths.get(dir))) {
            for (Path node : nodes) {
                Path snapshots = node.resolve("voltdbroot").resolve("snapshots");
                if (!Files.isDirectory(snapshots)) {
                    continue;
                }
                try (DirectoryStream<Path> files = Files.newDirectoryStream(snapshots)) {
                    for (Path f : files) {
                        if (name.test(f.getFileName().toString())) {
                            found.add(f);
                        }
                    }
                } catch (java.nio.file.NoSuchFileException e) {
                    // a node directory going away (a resize's)
                }
            }
        }
        return found;
    }

    // the distinct non-empty lines of the files
    private static long distinctLines(List<Path> files) throws IOException {
        Set<String> lines = new java.util.HashSet<>();
        for (Path f : files) {
            try (java.util.stream.Stream<String> l = Files.lines(f)) {
                l.filter(x -> !x.isEmpty()).forEach(lines::add);
            } catch (java.nio.file.NoSuchFileException e) {
                // deleted meanwhile
            }
        }
        return lines.size();
    }

    // @SnapshotDelete of the snapshot in each node's snapshot directory
    private void delete(String dir, String nonce) {
        Set<String> dirs = new java.util.TreeSet<>();
        try {
            for (Path f : files(dir, n -> n.startsWith(nonce + "-") || n.equals(nonce + ".jar"))) {
                dirs.add(f.getParent().toString());
            }
        } catch (IOException e) {
            return;
        }
        for (String d : dirs) {
            try (CallableStatement call = connection().prepareCall("{call @SnapshotDelete(?, ?)}")) {
                call.setString(1, d);
                call.setString(2, nonce);
                try (ResultSet rs = call.executeQuery()) {
                    int deleted = 0;
                    while (rs.next()) {
                        deleted++;
                    }
                    lastQuery = "@SnapshotDelete('" + d + "', '" + nonce + "'): " + deleted + " files";
                }
            } catch (SQLException | IgnoreMeException e) {
                // the next check uses another nonce
            }
        }
    }

    private VoltDBConnection connection() {
        return (VoltDBConnection) state.getConnection();
    }

    private void create(String table, boolean partitioned) {
        if (CREATED.contains(table)) {
            return;
        }
        String sql = "CREATE TABLE " + table + VoltDBBulkOracle.COLUMNS
                + (partitioned ? "; PARTITION TABLE " + table + " ON COLUMN k" : "") + "; CREATE UNIQUE INDEX " + table
                + "_KV ON " + table + " (k, v)";
        lastQuery = sql;
        try (Statement st = state.getConnection().createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            if (!String.valueOf(e.getMessage()).contains("already exists")) {
                throw new AssertionError(sql, e);
            }
        }
        CREATED.add(table);
    }

    // a change that must succeed: one that fails (a node failure's) skips the check; the next starts afresh
    private void write(String sql) {
        if (!tryWrite(sql)) {
            throw new IgnoreMeException();
        }
    }

    // a change that may break a constraint: false if it did
    private boolean tryWrite(String sql) {
        lastQuery = sql;
        try (PreparedStatement st = (PreparedStatement) connection().prepareStatement(sql)) {
            st.execute();
            return true;
        } catch (SQLException e) {
            if (String.valueOf(e.getMessage()).contains("CONSTRAINT VIOLATION")) {
                return false;
            }
            throw new IgnoreMeException();
        }
    }

    private List<Row> rows(String table) throws SQLException {
        String sql = VoltDBBulkOracle.SELECT + table + " ORDER BY k, id";
        lastQuery = sql;
        List<Row> rows = new ArrayList<>();
        // a query, which the mirror does not repeat
        try (Statement st = connection().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= n; i++) {
                    row.add(rs.getObject(i));
                }
                rows.add(new Row(row));
            }
        }
        return rows;
    }

    private static List<String> texts(List<Row> rows) {
        List<String> t = new ArrayList<>();
        for (Row r : rows) {
            t.add(r.text);
        }
        t.sort(null);
        return t;
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
