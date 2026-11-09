package sqlancer.voltdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.Reproducer;
import sqlancer.common.oracle.TestOracle;
import sqlancer.voltdb.VoltDBProvider.VoltDBGlobalState;

/**
 * Streams, their materialized views and export (run-sqlancer.sh --export). A stream cannot be read, so each of the
 * database's rows goes into a stream and into a shadow table with the same columns; the stream's materialized view must
 * then hold what the same GROUP BY over the shadow table returns. The objects are named {@code XS_<database>_ST} (the
 * stream, partitioned on k, exported to the target {@code --export-target} names, if any), {@code _SH} (the shadow
 * table) and {@code _SV} (the view), outside the database's own names, so that no other oracle or generator touches
 * them; they are made at the first check and kept for the run, as a stream dropped with rows not yet exported may lose
 * them. At exit STREAMS prints how many rows it inserted into the streams, which run-sqlancer.sh compares with the rows
 * the export target wrote.
 */
public class VoltDBStreamOracle implements TestOracle<VoltDBGlobalState> {

    private static final AtomicLong INSERTED = new AtomicLong();
    private static final AtomicLong CHECKED = new AtomicLong();
    private static final String VIEW_QUERY = "SELECT k, s, COUNT(*), SUM(v), MIN(f), MAX(v) FROM %s GROUP BY k, s";

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> System.out.println("STREAMS inserted rows: " + INSERTED + "; views compared: " + CHECKED)));
    }

    // the streams whose rows went into the stream but not into the shadow table, after which the two may differ;
    // static, as a database name, and so its stream, is used again by later databases of the run
    private static final java.util.Set<String> OUT_OF_STEP = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final VoltDBGlobalState state;
    private String prefix;
    private String lastQuery;

    public VoltDBStreamOracle(VoltDBGlobalState state) {
        this.state = state;
    }

    @Override
    public void check() throws Exception {
        if (prefix == null) {
            prefix = "XS_" + state.getDatabaseName().toUpperCase();
            String target = state.getDbmsSpecificOptions().exportTarget;
            String export = target == null || target.isBlank() ? "" : " EXPORT TO TARGET " + target;
            String columns = " (k INTEGER NOT NULL, v BIGINT, s VARCHAR(16), f FLOAT)";
            // kept from an earlier database of the same name in this run, or made now
            ddl("CREATE STREAM " + prefix + "_ST PARTITION ON COLUMN k" + export + columns);
            ddl("CREATE TABLE " + prefix + "_SH" + columns + "; PARTITION TABLE " + prefix + "_SH ON COLUMN k");
            // a view of a partitioned stream groups by the partitioning column
            ddl("CREATE VIEW " + prefix + "_SV (k, s, n, sv, mn, mx) AS " + String.format(VIEW_QUERY, prefix + "_ST"));
        }
        if (OUT_OF_STEP.contains(prefix)) {
            throw new IgnoreMeException();
        }
        for (int i = (int) Randomly.getNotCachedInteger(1, 20); i > 0; i--) {
            String values = "(" + Randomly.getNotCachedInteger(0, 50) + ", "
                    + (Randomly.getBooleanWithRatherLowProbability() ? "NULL"
                            : Randomly.getNotCachedInteger(-1000, 1000))
                    + ", "
                    + (Randomly.getBooleanWithRatherLowProbability() ? "NULL"
                            : "'" + Randomly.fromOptions("a", "b", "c", "", "dd") + "'")
                    + ", " + (Randomly.getBooleanWithRatherLowProbability() ? "NULL"
                            : Randomly.getNotCachedInteger(-100, 100) + ".5E0")
                    + ")";
            if (!insert("INSERT INTO " + prefix + "_ST VALUES " + values)) {
                continue;
            }
            INSERTED.incrementAndGet();
            if (!insert("INSERT INTO " + prefix + "_SH VALUES " + values)) {
                OUT_OF_STEP.add(prefix);
                throw new IgnoreMeException();
            }
        }
        List<String> view = rows("SELECT k, s, n, sv, mn, mx FROM " + prefix + "_SV");
        List<String> expected = rows(String.format(VIEW_QUERY, prefix + "_SH"));
        CHECKED.incrementAndGet();
        if (!view.equals(expected)) {
            throw new AssertionError("the view " + prefix + "_SV of the stream " + prefix
                    + "_ST differs from the same GROUP BY over the rows inserted into it; only the view: "
                    + VoltDBRows.difference(view, expected) + "; only the rows: "
                    + VoltDBRows.difference(expected, view));
        }
    }

    // DDL that may already have been done for this database name earlier in the run
    private void ddl(String sql) {
        lastQuery = sql;
        try (Statement st = state.getConnection().createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            if (!String.valueOf(e.getMessage()).contains("already exists")) {
                throw new AssertionError(sql, e);
            }
        }
    }

    private boolean insert(String sql) {
        lastQuery = sql;
        try (Statement st = state.getConnection().createStatement()) {
            st.execute(sql);
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private List<String> rows(String sql) throws SQLException {
        lastQuery = sql;
        List<String> rows = new ArrayList<>();
        try (Statement st = state.getConnection().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= columns; i++) {
                    Object v = rs.getObject(i);
                    row.add(v instanceof Double ? String.format("%.12g", (Double) v) : String.valueOf(v));
                }
                rows.add(String.join(" | ", row));
            }
        }
        Collections.sort(rows);
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
