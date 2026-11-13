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
    // inserts into a stream that a node failure interrupted, or that failed: each may have committed, and been
    // exported, or not
    private static final AtomicLong INTERRUPTED = new AtomicLong();
    // inserts into a stream not yet counted either way: at exit, SQLancer's threads may still be running one, which may
    // commit and be exported after INSERTED is printed (an export one row over in a milestone 40 trial)
    private static final AtomicLong IN_FLIGHT = new AtomicLong();
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
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // in flight first: an insert that completes between the reads is then counted twice, never not at all
            long inFlight = IN_FLIGHT.get();
            long inserted = INSERTED.get();
            System.out.println(
                    "STREAMS inserted rows: " + inserted + "; interrupted inserts: " + (INTERRUPTED.get() + inFlight)
                            + "; views compared: " + CHECKED + "; loopback exports compared: " + LOOPBACK_CHECKED);
        }));
    }

    // the streams whose rows went into the stream but not into the shadow table, after which the two may differ;
    // static, as a database name, and so its stream, is used again by later databases of the run
    private static final java.util.Set<String> OUT_OF_STEP = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // an exported stream out of step is kept, and the database name's checks go on with a stream of a new generation,
    // XS_DB0G1_ST and so on, up to MAX_GENERATION; so that, in a run that kills nodes, each export target still gets
    // rows after its stream's first interrupted insert (milestone 40: with kills, the HTTP and JDBC targets got 1 row
    // each)
    static final int MAX_GENERATION = 8;
    private static final java.util.Map<String, Integer> GENERATION = new java.util.concurrent.ConcurrentHashMap<>();

    private final VoltDBGlobalState state;
    private String prefix;
    private String lastQuery;
    private String lastError;

    public VoltDBStreamOracle(VoltDBGlobalState state) {
        this.state = state;
    }

    @Override
    public void check() throws Exception {
        String target = state.getDbmsSpecificOptions().exportTarget;
        String loopback = state.getDbmsSpecificOptions().loopbackTarget;
        boolean looping = loopback != null && !loopback.isBlank();
        boolean exporting = target != null && !target.isBlank() || looping;
        String base = "XS_" + state.getDatabaseName().toUpperCase();
        if (exporting && OUT_OF_STEP.contains(generationPrefix(base))) {
            // an exported stream is kept for the run, as dropping it may lose rows still to export: the next
            // generation's stream takes over
            int next = GENERATION.getOrDefault(base, 0) + 1;
            if (next > MAX_GENERATION) {
                throw new IgnoreMeException();
            }
            GENERATION.put(base, next);
            prefix = null;
        }
        if (prefix == null || !prefix.equals(generationPrefix(base))) {
            // kept from an earlier database of the same name in this run, or made now; the prefix is taken only once
            // all of it exists, so that DDL a node failure interrupted (IgnoreMeException) is done again by the next
            // check (before milestone 40 the loopback stream could be left missing, and every later check of the
            // name failed)
            String next = generationPrefix(base);
            prefix = next;
            try {
                create(target != null && !target.isBlank() ? " EXPORT TO TARGET " + target(target) : "");
                if (looping) {
                    ddlIfMissing("XS_LB",
                            "CREATE TABLE XS_LB" + TAGGED_COLUMNS + "; PARTITION TABLE XS_LB ON COLUMN k");
                    ddlIfMissing(next + "_LS", "CREATE STREAM " + next + "_LS PARTITION ON COLUMN k EXPORT TO TARGET "
                            + loopback + TAGGED_COLUMNS);
                }
            } catch (IgnoreMeException e) {
                prefix = null;
                throw e;
            }
        }
        if (OUT_OF_STEP.contains(prefix)) {
            // one that exports nowhere starts afresh
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
            IN_FLIGHT.incrementAndGet();
            try {
                inStream = insert("INSERT INTO " + prefix + "_ST VALUES " + values);
            } catch (IgnoreMeException e) {
                INTERRUPTED.incrementAndGet();
                IN_FLIGHT.decrementAndGet();
                OUT_OF_STEP.add(prefix);
                throw e;
            }
            if (inStream) {
                INSERTED.incrementAndGet();
            } else {
                INTERRUPTED.incrementAndGet();
            }
            IN_FLIGHT.decrementAndGet();
            if (!inStream) {
                // a stream has no constraints, so an insert into it fails only when something else does (a node
                // failure the connection did not report as one, say): it may have committed, and been exported
                System.err.println(
                        "STREAMS: an insert into " + prefix + "_ST failed, and may have committed: " + lastError);
                continue;
            }
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

    // the database name's current stream generation: XS_DB0, then XS_DB0G1 and so on
    private static String generationPrefix(String base) {
        int g = GENERATION.getOrDefault(base, 0);
        return g == 0 ? base : base + "G" + g;
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
        ddlIfMissing(prefix + "_ST", "CREATE STREAM " + prefix + "_ST PARTITION ON COLUMN k" + export + columns);
        ddlIfMissing(prefix + "_SH",
                "CREATE TABLE " + prefix + "_SH" + columns + "; PARTITION TABLE " + prefix + "_SH ON COLUMN k");
        // a view of a partitioned stream groups by the partitioning column
        ddlIfMissing(prefix + "_SV",
                "CREATE VIEW " + prefix + "_SV (k, s, n, sv, mn, mx) AS " + String.format(VIEW_QUERY, prefix + "_ST"));
    }

    // DDL that creates the object NAME, unless the catalog has it already: made for an earlier database of the same
    // name, or by another thread (XS_LB). With --node-failures the connection reports "object name already exists"
    // as a node failure (IgnoreMeException), so creating an existing object would end every check (before milestone
    // 40 the loopback stream of db1 and later was never made in a run with kills, and their streams got no rows)
    private void ddlIfMissing(String name, String sql) {
        lastQuery = sql;
        try (ResultSet rs = ((sqlancer.voltdb.VoltDBConnection) state.getConnection()).getMetaData().getTables(null,
                null, name, null)) {
            if (rs.next()) {
                return;
            }
        } catch (SQLException e) {
            throw new IgnoreMeException();
        }
        ddl(sql);
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
            lastError = e.getMessage();
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
