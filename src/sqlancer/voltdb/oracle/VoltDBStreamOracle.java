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
 * <p>
 * With {@code --loopback-target} (a loopback export target whose procedure is {@code XS_LB.insert}), each row also goes
 * into a second stream, {@code _LS}, tagged with the database's prefix, which exports it back into the table
 * {@code XS_LB} through that procedure; once the export has caught up, XS_LB must hold, for the tag, the shadow table's
 * rows. Export delivers at least once, so the two are compared as sets of distinct rows.
 */
public class VoltDBStreamOracle implements TestOracle<VoltDBGlobalState> {

    private static final AtomicLong INSERTED = new AtomicLong();
    private static final AtomicLong CHECKED = new AtomicLong();
    private static final AtomicLong LOOPBACK_CHECKED = new AtomicLong();
    // how long a loopback check waits for the export to deliver the rows
    private static final long LOOPBACK_MILLIS = 60_000;
    // i numbers the rows of the run, so that an exported row can be told from an equal one: the HTTP target sends the
    // columns only (run-sqlancer.sh counts its distinct i), where the file target's lines carry a transaction id
    private static final String COLUMNS = " (k INTEGER NOT NULL, v BIGINT, s VARCHAR(16), f FLOAT, i BIGINT NOT NULL)";
    private static final AtomicLong ROW_IDS = new AtomicLong();
    private static final String TAGGED_COLUMNS = " (d VARCHAR(24) NOT NULL, k INTEGER NOT NULL, v BIGINT, s VARCHAR(16), f FLOAT)";
    private static final String VIEW_QUERY = "SELECT k, s, COUNT(*), SUM(v), MIN(f), MAX(v) FROM %s GROUP BY k, s";

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println("STREAMS inserted rows: " + INSERTED
                + "; views compared: " + CHECKED + "; loopback exports compared: " + LOOPBACK_CHECKED)));
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
        String target = state.getDbmsSpecificOptions().exportTarget;
        String loopback = state.getDbmsSpecificOptions().loopbackTarget;
        boolean looping = loopback != null && !loopback.isBlank();
        boolean exporting = target != null && !target.isBlank() || looping;
        if (prefix == null) {
            prefix = "XS_" + state.getDatabaseName().toUpperCase();
            // kept from an earlier database of the same name in this run, or made now
            create(target != null && !target.isBlank() ? " EXPORT TO TARGET " + target(target) : "");
            if (looping) {
                ddl("CREATE TABLE XS_LB" + TAGGED_COLUMNS + "; PARTITION TABLE XS_LB ON COLUMN k");
                ddl("CREATE STREAM " + prefix + "_LS PARTITION ON COLUMN k EXPORT TO TARGET " + loopback
                        + TAGGED_COLUMNS);
            }
        }
        if (OUT_OF_STEP.contains(prefix)) {
            // an exported stream is kept for the run, as dropping it may lose rows still to export; one that exports
            // nowhere starts afresh
            if (exporting) {
                throw new IgnoreMeException();
            }
            ddl("DROP VIEW " + prefix + "_SV IF EXISTS");
            ddl("DROP STREAM " + prefix + "_ST IF EXISTS");
            ddl("DROP TABLE " + prefix + "_SH IF EXISTS");
            create("");
            OUT_OF_STEP.remove(prefix);
        }
        for (int i = (int) Randomly.getNotCachedInteger(1, 20); i > 0; i--) {
            String columns = "(" + Randomly.getNotCachedInteger(0, 50) + ", "
                    + (Randomly.getBooleanWithRatherLowProbability() ? "NULL"
                            : Randomly.getNotCachedInteger(-1000, 1000))
                    + ", "
                    + (Randomly.getBooleanWithRatherLowProbability() ? "NULL"
                            : "'" + Randomly.fromOptions("a", "b", "c", "", "dd") + "'")
                    + ", " + (Randomly.getBooleanWithRatherLowProbability() ? "NULL"
                            : Randomly.getNotCachedInteger(-100, 100) + ".5E0");
            String values = columns + ", " + ROW_IDS.incrementAndGet() + ")";
            // an insert a node failure interrupted (IgnoreMeException with --node-failures, --crashes and the like) may
            // or may not have committed, so the stream and its shadow can differ from then on (lab build #44)
            boolean inStream;
            try {
                inStream = insert("INSERT INTO " + prefix + "_ST VALUES " + values);
            } catch (IgnoreMeException e) {
                OUT_OF_STEP.add(prefix);
                throw e;
            }
            if (!inStream) {
                continue;
            }
            INSERTED.incrementAndGet();
            if (looping) {
                boolean inLoopback;
                try {
                    inLoopback = insert(
                            "INSERT INTO " + prefix + "_LS VALUES ('" + prefix + "', " + columns.substring(1) + ")");
                } catch (IgnoreMeException e) {
                    inLoopback = false;
                }
                if (!inLoopback) {
                    OUT_OF_STEP.add(prefix);
                    throw new IgnoreMeException();
                }
            }
            boolean inShadow;
            try {
                inShadow = insert("INSERT INTO " + prefix + "_SH VALUES " + values);
            } catch (IgnoreMeException e) {
                inShadow = false;
            }
            if (!inShadow) {
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
        if (looping) {
            checkLoopback();
        }
    }

    // --export-target names one target or several, comma-separated: a database's stream exports to one of them by
    // the database's number, so that each target gets streams of its own
    private String target(String targets) {
        String[] all = targets.split(",");
        String digits = state.getDatabaseName().replaceAll("[^0-9]", "");
        int n = digits.isEmpty() ? 0 : Integer.parseInt(digits);
        return all[n % all.length].trim();
    }

    // XS_LB must come to hold, for this prefix, the shadow table's distinct rows
    private void checkLoopback() throws SQLException {
        List<String> expected = rows("SELECT DISTINCT k, v, s, f FROM " + prefix + "_SH");
        String query = "SELECT DISTINCT k, v, s, f FROM XS_LB WHERE d = '" + prefix + "'";
        long deadline = System.currentTimeMillis() + LOOPBACK_MILLIS;
        List<String> looped;
        while (true) {
            looped = rows(query);
            if (looped.equals(expected) || System.currentTimeMillis() >= deadline) {
                break;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IgnoreMeException();
            }
        }
        LOOPBACK_CHECKED.incrementAndGet();
        if (!looped.equals(expected)) {
            throw new AssertionError("the loopback export of the stream " + prefix + "_LS did not bring XS_LB the rows"
                    + " inserted into it within " + LOOPBACK_MILLIS / 1000 + " s; only XS_LB: "
                    + VoltDBRows.difference(looped, expected) + "; only the rows: "
                    + VoltDBRows.difference(expected, looped));
        }
    }

    private void create(String export) {
        String columns = COLUMNS;
        ddl("CREATE STREAM " + prefix + "_ST PARTITION ON COLUMN k" + export + columns);
        ddl("CREATE TABLE " + prefix + "_SH" + columns + "; PARTITION TABLE " + prefix + "_SH ON COLUMN k");
        // a view of a partitioned stream groups by the partitioning column
        ddl("CREATE VIEW " + prefix + "_SV (k, s, n, sv, mn, mx) AS " + String.format(VIEW_QUERY, prefix + "_ST"));
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
