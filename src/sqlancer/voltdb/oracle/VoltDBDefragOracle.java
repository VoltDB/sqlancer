package sqlancer.voltdb.oracle;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.Reproducer;
import sqlancer.common.oracle.TestOracle;
import sqlancer.voltdb.VoltDBConnection;
import sqlancer.voltdb.VoltDBProvider.VoltDBGlobalState;

/**
 * Table compaction (milestone 53): {@code @Defrag} (voltadmin defrag), which moves a table's tuples out of its emptiest
 * blocks into others and frees those blocks, and which nothing ran before
 * (ClientInterface.compact, @DefragSP, @DefragMP, PersistentTable::compact).
 * <p>
 * Each check uses a table of its own, {@code XDW_<db>}: partitioned on k, every row on one k (so in one partition), and
 * rows of about 2.5 KB, kept in line (40 {@code VARCHAR(63 BYTES)} columns), so that a few hundred rows fill a 2 MB
 * block. It has a primary key (k, id), an index on a number (n), an index on a string stored out of line (s) and a
 * unique index (k, c1). The oracle keeps the rows it should hold. A check tops the table up to some thousands of rows,
 * deletes most of them (every row whose id leaves a chosen remainder) and changes n and s of others, so that the blocks
 * are mostly empty; calls {@code @Defrag} on the table, a full compaction or one of 1 to 3 blocks; and then the table
 * must hold exactly the rows expected, read in full, through the index on n, through the index on s and by the unique
 * index. Then more changes go through the indexes (updates by n, deletes by (k, c1), inserts), and the table is read
 * again the same ways: an index whose entries still point at a tuple's old place would return the wrong rows. Sometimes
 * a check compacts every table instead (the other oracles' tables, in use).
 * <p>
 * {@code @Defrag} refuses while a snapshot, an elastic operation or a rejoin runs; the check is then skipped. A
 * statement that a node failure fails skips it too, and the next starts afresh.
 */
public class VoltDBDefragOracle implements TestOracle<VoltDBGlobalState> {

    private static final int COLUMNS = 40;
    private static final AtomicLong CHECKS = new AtomicLong();
    private static final AtomicLong RELOCATED = new AtomicLong();
    private static final AtomicLong DEFRAGS = new AtomicLong();
    private static final AtomicLong WHOLE = new AtomicLong();
    private static final AtomicLong READS = new AtomicLong();
    // batches a concurrent schema change rejected, after which the model was read back
    private static final AtomicLong REFILLS = new AtomicLong();
    private static final Map<String, Long> SKIPS = new ConcurrentHashMap<>();
    // the rows each database's table should hold, by id
    private static final Map<String, TreeMap<Integer, Row>> MODELS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> IDS = new ConcurrentHashMap<>();
    // the connection of the database each model belongs to: a later database of the same name starts afresh, as with
    // --xdcr it may write through the other cluster, whose table (no DR table) holds other rows (milestone 55)
    private static final Map<String, Object> OWNERS = new ConcurrentHashMap<>();

    static {
        Runtime.getRuntime()
                .addShutdownHook(new Thread(() -> System.out.println("DEFRAG checks: " + CHECKS + "; compactions: "
                        + DEFRAGS + " (of every table " + WHOLE + "); tuples relocated: " + RELOCATED
                        + "; reads compared: " + READS + "; batches refilled after a schema change: " + REFILLS
                        + "; checks given up: " + SKIPS)));
    }

    private final VoltDBGlobalState state;
    private String lastQuery;

    public VoltDBDefragOracle(VoltDBGlobalState state) {
        this.state = state;
    }

    // a row: id, n, s and the in-line columns
    private static final class Row {
        final int id;
        long n;
        String s;
        final String[] c;

        Row(int id, long n, String s, String... c) {
            this.id = id;
            this.n = n;
            this.s = s;
            this.c = c.clone();
        }

        String text() {
            return id + "|" + n + "|" + s + "|" + String.join("|", c);
        }
    }

    @Override
    public void check() throws Exception {
        String db = state.getDatabaseName().toUpperCase(Locale.ROOT);
        String t = "XDW_" + db;
        // every row on one k, so in one partition (whose blocks fill)
        int k = Math.floorMod(db.hashCode(), 1000);
        TreeMap<Integer, Row> model = MODELS.get(db);
        if (model == null || OWNERS.get(db) != connection()) {
            create(t);
            model = new TreeMap<>();
            MODELS.put(db, model);
            OWNERS.put(db, connection());
        }
        try {
            fill(t, k, model, (int) Randomly.getNotCachedInteger(1500, 3000));
            // most rows go: those whose id leaves a remainder other than r
            int m = (int) Randomly.getNotCachedInteger(2, 5);
            int r = (int) Randomly.getNotCachedInteger(0, m);
            write("DELETE FROM " + t + " WHERE MOD(id, " + m + ") <> " + r, model, x -> x.id % m != r, null);
            // and some change their index keys
            int m2 = (int) Randomly.getNotCachedInteger(3, 9);
            write("UPDATE " + t + " SET n = n + 1000000, s = 'u' || s WHERE MOD(id, " + m2 + ") = 1", model, null,
                    x -> x.id % m2 == 1);
            boolean whole = Randomly.getBooleanWithRatherLowProbability();
            long maxCount = Randomly.fromOptions(-1L, 1L, 2L, 3L);
            defrag(whole ? "" : t, maxCount);
            verify(t, k, model, "after @Defrag('" + (whole ? "" : t) + "', " + maxCount + ")");
            // changes through the indexes, after the tuples have moved
            if (!model.isEmpty()) {
                Row some = Randomly.fromList(new ArrayList<>(model.values()));
                long n = some.n;
                write("UPDATE " + t + " SET s = 'v' || s WHERE n = " + n, model, null, x -> x.n == n);
                Row other = Randomly.fromList(new ArrayList<>(model.values()));
                String c1 = other.c[0];
                write("DELETE FROM " + t + " WHERE k = " + k + " AND c1 = '" + c1 + "'", model, x -> x.c[0].equals(c1),
                        null);
            }
            fill(t, k, model, model.size() + (int) Randomly.getNotCachedInteger(1, 50));
            verify(t, k, model, "after changes that followed @Defrag");
            CHECKS.incrementAndGet();
        } catch (IgnoreMeException e) {
            // the table may now differ from the model (a statement whose outcome is unknown): read it back
            resync(t, db);
            throw e;
        }
    }

    // adds rows until the table has COUNT
    // adds rows until the table has COUNT, in batches of 50. A batch that a concurrent schema change rejects ("planned
    // against a catalog version", which every other database's DDL causes) may have inserted some of its rows: the
    // model is then read back from the table, and filling goes on (up to 20 times)
    private void fill(String t, int k, java.util.SortedMap<Integer, Row> model, int count) {
        AtomicLong ids = IDS.computeIfAbsent(t, x -> new AtomicLong());
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(t).append(" VALUES (?, ?, ?, ?");
        for (int i = 0; i < COLUMNS; i++) {
            sql.append(", ?");
        }
        sql.append(")");
        int rejected = 0;
        while (model.size() < count) {
            lastQuery = sql.toString();
            List<Row> batch = new ArrayList<>();
            try (PreparedStatement st = (PreparedStatement) connection().prepareStatement(sql.toString())) {
                while (model.size() + batch.size() < count && batch.size() < 50) {
                    int id = (int) ids.incrementAndGet();
                    String[] c = new String[COLUMNS];
                    for (int i = 0; i < COLUMNS; i++) {
                        // 50 bytes or so, in line; c1 unique within k
                        c[i] = i == 0 ? "u" + id : "c" + i + "-" + id + "-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
                    }
                    Row row = new Row(id, Randomly.getNotCachedInteger(0, 100), "s" + id % 97 + "-" + "z".repeat(300),
                            c);
                    st.setInt(1, k);
                    st.setInt(2, id);
                    st.setLong(3, row.n);
                    st.setString(4, row.s);
                    for (int i = 0; i < COLUMNS; i++) {
                        st.setString(5 + i, c[i]);
                    }
                    st.addBatch();
                    batch.add(row);
                }
                flush(st, batch.size());
                for (Row row : batch) {
                    model.put(row.id, row);
                }
            } catch (SQLException e) {
                if (++rejected > 20 || !String.valueOf(e.getMessage()).contains("catalog version")
                        && !String.valueOf(e.getMessage()).contains("a batch of")) {
                    skip("insert: " + e.getMessage());
                }
                REFILLS.incrementAndGet();
                reload(t, model);
            }
        }
    }

    // the model read back from the table
    private void reload(String t, java.util.SortedMap<Integer, Row> model) {
        lastQuery = "SELECT * FROM " + t + " ORDER BY id";
        java.util.SortedMap<Integer, Row> now = new TreeMap<>();
        try (Statement st = connection().createStatement(); ResultSet rs = st.executeQuery(lastQuery)) {
            while (rs.next()) {
                String[] c = new String[COLUMNS];
                for (int i = 0; i < COLUMNS; i++) {
                    c[i] = rs.getString(5 + i);
                }
                now.put(rs.getInt(2), new Row(rs.getInt(2), rs.getLong(3), rs.getString(4), c));
            }
        } catch (SQLException e) {
            skip("SELECT: " + e.getMessage());
        }
        model.clear();
        model.putAll(now);
    }

    // runs a batch of PENDING inserts, each of which must have inserted its row. VoltDBConnection retries a batch that
    // a concurrent schema change rejected, but the driver has cleared the batch by then: the retry inserts nothing and
    // reports no rows, so the counts are what tell
    private static void flush(PreparedStatement st, int pending) throws SQLException {
        if (pending == 0) {
            return;
        }
        int[] counts = st.executeBatch();
        boolean all = counts.length == pending;
        for (int n : counts) {
            all &= n == 1;
        }
        if (!all) {
            throw new SQLException("a batch of " + pending + " inserts reported " + counts.length + " results");
        }
    }

    // a statement whose effect on the model is: remove the rows GONE matches, and, for UPDATE, apply the change the
    // statement makes to the rows CHANGED matches
    private void write(String sql, java.util.SortedMap<Integer, Row> model, Predicate<Row> gone,
            Predicate<Row> changed) {
        lastQuery = sql;
        try (Statement st = connection().createStatement()) {
            st.executeUpdate(sql);
        } catch (SQLException e) {
            skip(sql.split(" ")[0] + ": " + e.getMessage());
        }
        if (gone != null) {
            model.values().removeIf(gone);
        }
        if (changed != null) {
            for (Row row : model.values()) {
                if (changed.test(row)) {
                    if (sql.contains("SET n = n + 1000000")) {
                        row.n += 1_000_000;
                        row.s = "u" + row.s;
                    } else if (sql.contains("SET s = 'v' || s")) {
                        row.s = "v" + row.s;
                    }
                }
            }
        }
    }

    // @Defrag(TABLES, MAXCOUNT): every row SUCCESS, or the check is skipped when VoltDB refuses for now
    private void defrag(String tables, long maxCount) {
        lastQuery = "@Defrag('" + tables + "', " + maxCount + ")";
        try (CallableStatement call = connection().prepareCall("{call @Defrag(?, ?)}")) {
            call.setString(1, tables);
            call.setLong(2, maxCount);
            try (ResultSet rs = call.executeQuery()) {
                while (rs.next()) {
                    long status = rs.getLong("STATUS");
                    String message = rs.getString("MESSAGE");
                    if (status != 1) {
                        if (message != null && message.contains("in progress")
                                || message != null && message.contains("is progress")) {
                            skip("@Defrag: " + message);
                        }
                        // a table dropped meanwhile by another database's thread
                        if (!tables.isEmpty() || message == null || !message.contains("not found")) {
                            throw new AssertionError(lastQuery + " failed on " + rs.getString("TABLE") + " partition "
                                    + rs.getLong("PARTITION_ID") + ": status " + status + ", " + message);
                        }
                    }
                    RELOCATED.addAndGet(rs.getLong("RELOCATED_TUPLE_COUNT"));
                }
            }
        } catch (SQLException e) {
            skip("@Defrag: " + e.getMessage());
        }
        DEFRAGS.incrementAndGet();
        if (tables.isEmpty()) {
            WHOLE.incrementAndGet();
        }
    }

    // the table read in full, through the index on n, through the index on s and by the unique index (k, c1), must be
    // the model's rows
    private void verify(String t, int k, java.util.SortedMap<Integer, Row> model, String when) throws SQLException {
        List<String> expected = new ArrayList<>();
        for (Row row : model.values()) {
            expected.add(row.text());
        }
        compare(when, "SELECT * FROM " + t + " ORDER BY id", expected);
        if (model.isEmpty()) {
            return;
        }
        List<Row> rows = new ArrayList<>(model.values());
        long lo = Randomly.fromList(rows).n;
        long hi = lo + Randomly.getNotCachedInteger(0, 30);
        List<String> byN = new ArrayList<>();
        for (Row row : rows) {
            if (row.n >= lo && row.n <= hi) {
                byN.add(row.text());
            }
        }
        compare(when, "SELECT * FROM " + t + " WHERE n >= " + lo + " AND n <= " + hi + " ORDER BY id", byN);
        String s = Randomly.fromList(rows).s;
        List<String> byS = new ArrayList<>();
        for (Row row : rows) {
            if (row.s.equals(s)) {
                byS.add(row.text());
            }
        }
        compare(when, "SELECT * FROM " + t + " WHERE s = '" + s + "' ORDER BY id", byS);
        Row one = Randomly.fromList(rows);
        compare(when, "SELECT * FROM " + t + " WHERE k = " + k + " AND c1 = '" + one.c[0] + "'", List.of(one.text()));
    }

    private void compare(String when, String sql, List<String> expected) throws SQLException {
        lastQuery = sql;
        List<String> got = read(sql);
        READS.incrementAndGet();
        if (!got.equals(expected)) {
            throw new AssertionError(sql + " " + when + " returns " + got.size() + " rows, " + expected.size()
                    + " expected; only returned: " + head(VoltDBRows.difference(got, expected)) + "; only expected: "
                    + head(VoltDBRows.difference(expected, got)));
        }
    }

    private static String head(List<String> rows) {
        List<String> out = new ArrayList<>();
        for (String r : rows.subList(0, Math.min(3, rows.size()))) {
            out.add(r.length() > 60 ? r.substring(0, 60) + "..." : r);
        }
        return out + (rows.size() > 3 ? " and " + (rows.size() - 3) + " more" : "");
    }

    private List<String> read(String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement st = connection().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int cols = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                // id, n, s, c1..cN (k, the first column, is the same in every row)
                StringBuilder sb = new StringBuilder().append(rs.getInt(2)).append('|').append(rs.getLong(3))
                        .append('|').append(rs.getString(4));
                for (int i = 5; i <= cols; i++) {
                    sb.append('|').append(Objects.toString(rs.getString(i)));
                }
                rows.add(sb.toString());
            }
        } catch (SQLException e) {
            skip("SELECT: " + e.getMessage());
        }
        return rows;
    }

    // after a statement whose outcome is unknown, the model is rebuilt from the table
    private void resync(String t, String db) {
        TreeMap<Integer, Row> model = new TreeMap<>();
        try (Statement st = connection().createStatement();
                ResultSet rs = st.executeQuery("SELECT * FROM " + t + " ORDER BY id")) {
            while (rs.next()) {
                String[] c = new String[COLUMNS];
                for (int i = 0; i < COLUMNS; i++) {
                    c[i] = rs.getString(5 + i);
                }
                model.put(rs.getInt(2), new Row(rs.getInt(2), rs.getLong(3), rs.getString(4), c));
            }
            MODELS.put(db, model);
        } catch (SQLException | RuntimeException e) {
            // the next check reads it again; until then, start over
            MODELS.remove(db);
        }
    }

    private void create(String t) {
        StringBuilder sql = new StringBuilder("CREATE TABLE ").append(t)
                .append(" (k INTEGER NOT NULL, id INTEGER NOT NULL, n BIGINT NOT NULL, s VARCHAR(400) NOT NULL");
        for (int i = 1; i <= COLUMNS; i++) {
            sql.append(", c").append(i).append(" VARCHAR(63 BYTES) NOT NULL");
        }
        sql.append(", PRIMARY KEY (k, id)); PARTITION TABLE ").append(t).append(" ON COLUMN k; CREATE INDEX ").append(t)
                .append("_N ON ").append(t).append(" (n); CREATE INDEX ").append(t).append("_S ON ").append(t)
                .append(" (s); CREATE UNIQUE INDEX ").append(t).append("_C ON ").append(t).append(" (k, c1)");
        lastQuery = sql.toString();
        try (Statement st = state.getConnection().createStatement()) {
            st.execute(sql.toString());
        } catch (SQLException e) {
            if (!String.valueOf(e.getMessage()).contains("already exists")) {
                skip("CREATE: " + e.getMessage());
            }
            // left over from an earlier database of this name: empty it
            try (Statement st = state.getConnection().createStatement()) {
                st.executeUpdate("DELETE FROM " + t);
            } catch (SQLException e2) {
                skip("DELETE: " + e2.getMessage());
            }
        }
    }

    private void skip(String why) {
        String w = String.valueOf(why).split("\n")[0];
        SKIPS.merge(w.length() > 70 ? w.substring(0, 70) : w, 1L, Long::sum);
        throw new IgnoreMeException();
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
