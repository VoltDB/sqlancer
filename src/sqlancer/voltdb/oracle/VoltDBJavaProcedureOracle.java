package sqlancer.voltdb.oracle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.voltdb.client.ClientResponse;
import org.voltdb.client.ProcCallException;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.Reproducer;
import sqlancer.common.oracle.TestOracle;
import sqlancer.voltdb.VoltDBConnection;
import sqlancer.voltdb.VoltDBErrors;
import sqlancer.voltdb.VoltDBOptions;
import sqlancer.voltdb.VoltDBProvider.VoltDBGlobalState;

/**
 * Java stored procedures (milestone 50). Every procedure the other oracles make is one SQL statement in DDL, so
 * milestone 49's coverage found the rest of VoltDB's procedure support unexercised: compound procedures
 * ({@code CompoundProcedureRunner}, none of it), {@code @UpdateClasses} and its checks, the procedure compiler's class
 * paths, and the multi-batch, abort and expectation paths of {@code ProcedureRunnerImpl}.
 * <p>
 * Each check writes the Java source of four classes over {@code XJ_<db>} (partitioned on k), compiles them in
 * SQLancer's JVM against the kit's jars (--procedure-classpath), loads them with {@code @UpdateClasses} and creates the
 * procedures {@code FROM CLASS}:
 * <ul>
 * <li>single-partition (JPS): inserts n rows for its key in batches, reads them back, and returns its class's version,
 * the count and the sum; or, by its mode, then aborts ({@code VoltAbortException}), throws, fails an expectation
 * ({@code EXPECT_EMPTY}) or violates the primary key;</li>
 * <li>multi-partition (JPM): the same over several keys, so across partitions;</li>
 * <li>compound (JPC, {@code CREATE COMPOUND PROCEDURE}): calls JPS for several keys in its first stage, and in its
 * second completes (with a number or a table), aborts, throws, never completes, queues calls it completes before
 * sending, or sets the response's app status; or calls itself (refused), queues more calls than a stage may, or sets up
 * no stages;</li>
 * </ul>
 * and a non-transactional procedure class (JPN), which VoltDB creates procedures from only in its own tests. Every key
 * a call writes is new, so the rows a call leaves are known: all of them when it succeeds, none when it fails. A
 * compound procedure is not one transaction: the rows of each JPS call it made that succeeded stay, whatever it
 * returns. When a node failure interrupts a call (with --node-failures and the like) the call may or may not have
 * committed, but it must have committed whole: all its rows, on every partition, or none.
 * <p>
 * Then one of: the classes replaced by a new version, which the next call must run; the class of a procedure in use
 * removed, which must be refused; a class whose static initializer throws loaded, which must be refused; a procedure
 * created from a class of the wrong kind (CREATE PROCEDURE from JPN or JPC, CREATE COMPOUND PROCEDURE from JPS), or
 * with a partition parameter it does not have, which must be refused. In the end the procedures are dropped and the
 * classes removed, and {@code @SystemCatalog CLASSES} must no longer list them.
 * <p>
 * The classes are also loaded into the mirror (--mirror) and the XDCR peer (--xdcr), where the DDL is repeated; the
 * calls and their rows are on this cluster only, and XJ tables are no database's.
 */
public class VoltDBJavaProcedureOracle implements TestOracle<VoltDBGlobalState> {

    private static final String PACKAGE = "sqlancer_jp";
    private static final AtomicLong IDS = new AtomicLong();
    private static final AtomicLong CHECKS = new AtomicLong();
    private static final AtomicLong CALLS = new AtomicLong();
    private static final AtomicLong SP_CALLS = new AtomicLong();
    private static final AtomicLong MP_CALLS = new AtomicLong();
    private static final AtomicLong COMPOUND_CALLS = new AtomicLong();
    private static final AtomicLong FAILURES = new AtomicLong();
    private static final AtomicLong INTERRUPTED = new AtomicLong();
    private static final AtomicLong REPLACED = new AtomicLong();
    private static final AtomicLong REFUSED = new AtomicLong();
    private static final Set<String> CREATED = ConcurrentHashMap.newKeySet();
    // compiling is CPU-heavy: one thread at a time
    private static final Object COMPILE = new Object();

    static {
        Runtime.getRuntime()
                .addShutdownHook(new Thread(() -> System.out.println("JAVAPROC checks: " + CHECKS + "; calls checked: "
                        + CALLS + " (single-partition " + SP_CALLS + ", multi-partition " + MP_CALLS + ", compound "
                        + COMPOUND_CALLS + "); failures checked: " + FAILURES
                        + "; calls interrupted by a node failure, checked whole: " + INTERRUPTED
                        + "; classes replaced: " + REPLACED + "; class changes refused: " + REFUSED)));
    }

    // a JPS or JPM call's mode, and the status it must end with
    private enum Mode {
        OK(ClientResponse.SUCCESS), ABORT(ClientResponse.USER_ABORT), THROW(ClientResponse.UNEXPECTED_FAILURE),
        EXPECTATION(ClientResponse.USER_ABORT), CONSTRAINT(ClientResponse.GRACEFUL_FAILURE);

        final byte status;

        Mode(byte status) {
            this.status = status;
        }
    }

    // a JPC call's mode
    private enum Compound {
        // completes with the sum of the JPS calls' counts, or aborts if one failed
        SUM, TABLE, THROW_ABORT, THROW, NO_STAGES, NOT_COMPLETED, NESTED, TOO_MANY, DROPPED, APP_STATUS
    }

    private enum FollowUp {
        NONE, REPLACE, REMOVE_IN_USE, BAD_CLASS, BAD_BASE, BAD_PARTITION
    }

    private final VoltDBGlobalState state;
    private String lastQuery;
    // the next key of this check
    private long nextKey;
    private boolean failures;

    public VoltDBJavaProcedureOracle(VoltDBGlobalState state) {
        this.state = state;
    }

    @Override
    public void check() throws Exception {
        VoltDBOptions o = state.getDbmsSpecificOptions();
        if (o.procedureClasspath == null || o.procedureClasspath.isBlank()
                || ToolProvider.getSystemJavaCompiler() == null) {
            throw new IgnoreMeException();
        }
        failures = o.nodeFailures || o.crashes || o.resizes || o.upgrades;
        String db = state.getDatabaseName().toUpperCase();
        String table = "XJ_" + db;
        create(table);
        long id = IDS.incrementAndGet();
        String suffix = db + "_" + id;
        Names n = new Names(suffix);
        nextKey = id * 1000;
        long version = 1 + Randomly.getNotCachedInteger(0, 1000);
        int batch = (int) Randomly.getNotCachedInteger(1, 6);
        updateClasses(jar(compile(sources(table, n, version, batch))), null, true);
        List<String> procedures = new ArrayList<>();
        try {
            ddl("CREATE PROCEDURE PARTITION ON TABLE " + table + " COLUMN k FROM CLASS " + PACKAGE + "." + n.sp);
            procedures.add(n.sp);
            ddl("CREATE PROCEDURE FROM CLASS " + PACKAGE + "." + n.mp);
            procedures.add(n.mp);
            ddl("CREATE COMPOUND PROCEDURE FROM CLASS " + PACKAGE + "." + n.cp);
            procedures.add(n.cp);
            int calls = 2 + (int) Randomly.getNotCachedInteger(0, 4);
            for (int i = 0; i < calls; i++) {
                randomCall(table, n, version);
            }
            switch (Randomly.fromOptions(FollowUp.values())) {
            case REPLACE:
                long next = version + 1 + Randomly.getNotCachedInteger(0, 1000);
                updateClasses(jar(compile(sources(table, n, next, batch))), null, true);
                version = next;
                REPLACED.incrementAndGet();
                singlePartition(table, n, version, Mode.OK);
                break;
            case REMOVE_IN_USE:
                String why = updateClasses(null, PACKAGE + "." + n.sp, false);
                mustBeRefused(why, "removing the class of " + n.sp + ", a procedure in use");
                singlePartition(table, n, version, Mode.OK);
                break;
            case BAD_CLASS:
                Map<String, String> bad = new LinkedHashMap<>();
                bad.put(n.bad,
                        "package " + PACKAGE + ";\npublic class " + n.bad + " extends org.voltdb.VoltProcedure {\n"
                                + "    static { if (true) { throw new IllegalStateException(\"JAVAPROC: no\"); } }\n"
                                + "    public long run() { return 1; }\n}\n");
                why = updateClasses(jar(compile(bad)), null, false);
                mustBeRefused(why, "loading " + n.bad + ", whose static initializer throws");
                if (!failures && listed(n).contains(n.bad.toUpperCase())) {
                    throw new AssertionError("@SystemCatalog CLASSES lists " + n.bad + ", whose loading was refused");
                }
                singlePartition(table, n, version, Mode.OK);
                break;
            case BAD_BASE:
                String[] wrong = Randomly.fromOptions(
                        new String[] { "CREATE PROCEDURE", n.nt,
                                "a non-transactional procedure class (VoltDB's own " + "only)" },
                        new String[] { "CREATE PROCEDURE", n.cp, "a compound procedure class" },
                        new String[] { "CREATE COMPOUND PROCEDURE", n.sp, "a plain procedure class" });
                why = tryDdl(wrong[0] + " " + n.sp + "X FROM CLASS " + PACKAGE + "." + wrong[1]);
                mustBeRefused(why, wrong[0] + " from " + wrong[2]);
                break;
            case BAD_PARTITION:
                why = tryDdl("CREATE PROCEDURE " + n.sp + "X PARTITION ON TABLE " + table
                        + " COLUMN k PARAMETER 7 FROM " + "CLASS " + PACKAGE + "." + n.sp);
                mustBeRefused(why, "a procedure partitioned on a parameter its class does not have");
                break;
            default:
                break;
            }
            CHECKS.incrementAndGet();
        } finally {
            for (int i = procedures.size() - 1; i >= 0; i--) {
                tryDdl("DROP PROCEDURE " + procedures.get(i) + " IF EXISTS");
            }
            updateClasses(null, n.all(), false);
        }
        if (!failures) {
            Set<String> left = listed(n);
            if (!left.isEmpty()) {
                throw new AssertionError("@SystemCatalog CLASSES still lists " + left + " after their procedures were "
                        + "dropped and @UpdateClasses removed them");
            }
        }
    }

    private void randomCall(String table, Names n, long version) throws SQLException {
        switch ((int) Randomly.getNotCachedInteger(0, 3)) {
        case 0:
            singlePartition(table, n, version, mode());
            break;
        case 1:
            multiPartition(table, n, version, mode());
            break;
        default:
            compound(table, n, Randomly.fromOptions(Compound.values()));
            break;
        }
    }

    // a call's mode: half of them succeed
    private static Mode mode() {
        return Randomly.getBoolean() ? Mode.OK : Randomly.fromOptions(Mode.values());
    }

    // JPS(k, rows, mode)
    private void singlePartition(String table, Names n, long version, Mode mode) throws SQLException {
        long k = nextKey++;
        int rows = 1 + (int) Randomly.getNotCachedInteger(0, 20);
        Outcome r = call(n.sp, k, rows, mode.ordinal());
        SP_CALLS.incrementAndGet();
        verify("JPS " + n.sp + "(" + k + ", " + rows + ", " + mode + ")", table, k, 1, rows, mode, r, version);
    }

    // JPM(k0, keys, rows, mode): rows for each of keys keys from k0
    private void multiPartition(String table, Names n, long version, Mode mode) throws SQLException {
        long k0 = nextKey;
        int keys = 2 + (int) Randomly.getNotCachedInteger(0, 6);
        nextKey += keys;
        int rows = 1 + (int) Randomly.getNotCachedInteger(0, 10);
        Outcome r = call(n.mp, k0, keys, rows, mode.ordinal());
        MP_CALLS.incrementAndGet();
        verify("JPM " + n.mp + "(" + k0 + ", " + keys + ", " + rows + ", " + mode + ")", table, k0, keys, rows, mode, r,
                version);
    }

    private void verify(String what, String table, long k0, int keys, int rows, Mode mode, Outcome r, long version)
            throws SQLException {
        long[] found = rows(table, k0, k0 + keys - 1);
        long all = (long) keys * rows;
        long sum = 0;
        for (long k = k0; k < k0 + keys; k++) {
            for (int i = 0; i < rows; i++) {
                sum += k * 1000 + i;
            }
        }
        if (r.interrupted) {
            // a node failure: committed whole or not at all
            if (found[0] != 0 && (found[0] != all || found[1] != sum)) {
                throw new AssertionError(what + ", interrupted by a node failure (" + r.message + "), committed in "
                        + "part: " + found[0] + " of its " + all + " rows (sum " + found[1] + " of " + sum + ")");
            }
            INTERRUPTED.incrementAndGet();
            throw new IgnoreMeException();
        }
        CALLS.incrementAndGet();
        if (mode == Mode.OK) {
            if (r.status != ClientResponse.SUCCESS) {
                throw new AssertionError(what + " should succeed; it failed: " + r.message);
            }
            long[] row = r.row;
            if (row == null || row[0] != version || row[1] != all || row[2] != sum) {
                throw new AssertionError(what + " should return version " + version + ", count " + all + " and sum "
                        + sum + "; it returned " + (row == null ? "no row" : row[0] + ", " + row[1] + ", " + row[2]));
            }
            if (found[0] != all || found[1] != sum) {
                throw new AssertionError(what + " succeeded, but its keys hold " + found[0] + " rows (sum " + found[1]
                        + "), not " + all + " (sum " + sum + ")");
            }
            return;
        }
        FAILURES.incrementAndGet();
        if (r.status != mode.status) {
            throw new AssertionError(what + " should fail with status " + mode.status + "; it ended with status "
                    + r.status + ": " + r.message);
        }
        if (found[0] != 0) {
            throw new AssertionError(what + " failed (" + r.message + "), but " + found[0] + " of its rows are there; "
                    + "its transaction should have rolled back whole");
        }
    }

    // JPC(k0, calls, subMode, mode)
    private void compound(String table, Names n, Compound mode) throws SQLException {
        long k0 = nextKey;
        int calls = 1 + (int) Randomly.getNotCachedInteger(0, 4);
        nextKey += 10;
        // the last JPS call fails, by its own mode, in a third of the calls
        Mode last = Randomly.fromOptions(Mode.OK, Mode.OK,
                Randomly.fromOptions(Mode.ABORT, Mode.THROW, Mode.CONSTRAINT));
        String what = "JPC " + n.cp + "(" + k0 + ", " + calls + ", " + last + ", " + mode + ")";
        Outcome r = call(n.cp, k0, calls, last.ordinal(), mode.ordinal());
        COMPOUND_CALLS.incrementAndGet();
        boolean made = mode != Compound.NO_STAGES && mode != Compound.NESTED && mode != Compound.TOO_MANY;
        // the rows each JPS call leaves: j + 1 rows for key k0 + j, none for a failed call or one never made
        for (int j = 0; j < calls; j++) {
            long k = k0 + j;
            long expected = made && (j < calls - 1 || last == Mode.OK) ? j + 1 : 0;
            long found = rows(table, k, k)[0];
            boolean ok = found == expected || r.interrupted && found == 0 || failures && found == 0 && made;
            if (!ok) {
                throw new AssertionError(what + ": key " + k + " holds " + found + " rows, not " + expected
                        + " (the rows of the JPS call " + j + (r.interrupted ? "; interrupted: " + r.message : "")
                        + "; the compound procedure ended with status " + r.status + ": " + r.message + ")");
            }
        }
        // DROPPED: a call queued in the stage that completes is never sent
        long dropped = rows(table, k0 + 9, k0 + 9)[0];
        if (dropped != 0) {
            throw new AssertionError(what + ": the JPS call queued in the stage that completed was made: key "
                    + (k0 + 9) + " holds " + dropped + " rows");
        }
        if (r.interrupted) {
            INTERRUPTED.incrementAndGet();
            throw new IgnoreMeException();
        }
        CALLS.incrementAndGet();
        byte status;
        switch (mode) {
        case SUM:
            status = last == Mode.OK ? ClientResponse.SUCCESS : ClientResponse.COMPOUND_PROC_USER_ABORT;
            break;
        case TABLE:
        case DROPPED:
        case APP_STATUS:
            status = ClientResponse.SUCCESS;
            break;
        case THROW_ABORT:
            status = ClientResponse.COMPOUND_PROC_USER_ABORT;
            break;
        default:
            status = ClientResponse.UNEXPECTED_FAILURE;
            break;
        }
        if (r.status != status) {
            // a JPS call a node failure failed makes SUM abort
            if (failures && mode == Compound.SUM && r.status == ClientResponse.COMPOUND_PROC_USER_ABORT
                    && VoltDBErrors.isNodeFailure(new SQLException(r.message))) {
                throw new IgnoreMeException();
            }
            throw new AssertionError(what + " should end with status " + status + "; it ended with status " + r.status
                    + ": " + r.message);
        }
        if (status != ClientResponse.SUCCESS) {
            FAILURES.incrementAndGet();
        }
        if (mode == Compound.SUM && status == ClientResponse.SUCCESS) {
            long sum = (long) calls * (calls + 1) / 2;
            if (r.row == null || r.row[0] != sum) {
                throw new AssertionError(what + " should return " + sum + ", the JPS calls' counts; it returned "
                        + (r.row == null ? "no row" : r.row[0]));
            }
        }
    }

    // a procedure call's outcome: SUCCESS and the first row's BIGINT columns, or the status and message it failed
    // with, or interrupted by a node failure
    private static final class Outcome {
        byte status;
        String message;
        long[] row;
        boolean interrupted;
    }

    private Outcome call(String procedure, Object... args) throws SQLException {
        StringBuilder sql = new StringBuilder("{call ").append(procedure).append("(");
        for (int i = 0; i < args.length; i++) {
            sql.append(i == 0 ? "?" : ", ?");
        }
        sql.append(")}");
        lastQuery = procedure + java.util.Arrays.toString(args);
        Outcome r = new Outcome();
        try (CallableStatement call = connection().prepareCall(sql.toString())) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Long) {
                    call.setLong(i + 1, (Long) args[i]);
                } else {
                    call.setInt(i + 1, (Integer) args[i]);
                }
            }
            try (ResultSet rs = call.executeQuery()) {
                r.status = ClientResponse.SUCCESS;
                if (rs.next()) {
                    int columns = rs.getMetaData().getColumnCount();
                    r.row = new long[columns];
                    for (int c = 0; c < columns; c++) {
                        r.row[c] = rs.getLong(c + 1);
                    }
                }
            }
        } catch (IgnoreMeException e) {
            r.interrupted = true;
            r.message = "a node failure";
        } catch (SQLException e) {
            ClientResponse response = response(e);
            if (response == null) {
                throw new AssertionError(lastQuery + " failed without a response: " + e.getMessage(), e);
            }
            r.status = response.getStatus();
            r.message = String.valueOf(response.getStatusString()).split("\n")[0];
            if (failures && (r.status == ClientResponse.CONNECTION_LOST || r.status == ClientResponse.SERVER_UNAVAILABLE
                    || r.status == ClientResponse.RESPONSE_UNKNOWN || r.status == ClientResponse.CONNECTION_TIMEOUT
                    || VoltDBErrors.isNodeFailure(e))) {
                r.interrupted = true;
            }
        }
        return r;
    }

    private static ClientResponse response(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof ProcCallException) {
                return ((ProcCallException) t).getClientResponse();
            }
        }
        return null;
    }

    // {count, sum of v} of XJ's rows with k in [from, to]
    private long[] rows(String table, long from, long to) throws SQLException {
        String sql = "SELECT COUNT(*), COALESCE(SUM(v), 0) FROM " + table + " WHERE k BETWEEN " + from + " AND " + to;
        lastQuery = sql;
        try (Statement st = connection().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return new long[] { rs.getLong(1), rs.getLong(2) };
        }
    }

    // the classes of this check that @SystemCatalog CLASSES lists, upper case
    private Set<String> listed(Names n) throws SQLException {
        lastQuery = "@SystemCatalog('CLASSES')";
        Set<String> left = new java.util.TreeSet<>();
        try (CallableStatement call = connection().prepareCall("{call @SystemCatalog(?)}")) {
            call.setString(1, "CLASSES");
            try (ResultSet rs = call.executeQuery()) {
                while (rs.next()) {
                    String c = rs.getString("CLASS_NAME");
                    String simple = c.substring(c.lastIndexOf('.') + 1).toUpperCase();
                    if (c.startsWith(PACKAGE + ".") && n.contains(simple)) {
                        left.add(simple);
                    }
                }
            }
        }
        return left;
    }

    private void mustBeRefused(String why, String what) {
        if (why == null) {
            throw new AssertionError(what + " should be refused; it succeeded");
        }
        REFUSED.incrementAndGet();
    }

    // @UpdateClasses(jar, deletes) on this cluster, the mirror and the XDCR peer; returns null, or why this cluster
    // refused it (then the others are not asked); with mustSucceed, a refusal fails the check
    private String updateClasses(byte[] jar, String deletes, boolean mustSucceed) throws SQLException {
        lastQuery = "@UpdateClasses(" + (jar == null ? "null" : "a jar of " + jar.length + " bytes") + ", " + deletes
                + ")";
        String why = updateClasses(connection(), jar, deletes);
        if (why != null) {
            if (mustSucceed) {
                throw new AssertionError(lastQuery + " should succeed; it failed: " + why);
            }
            return why;
        }
        for (VoltDBConnection other : new VoltDBConnection[] { connection().getMirror(), connection().getPeer() }) {
            if (other != null) {
                try {
                    updateClasses(other, jar, deletes);
                } catch (SQLException | IgnoreMeException e) {
                    // the CREATE PROCEDURE repeated there fails, which marks the database diverged or split
                }
            }
        }
        return null;
    }

    private static String updateClasses(VoltDBConnection c, byte[] jar, String deletes) throws SQLException {
        try (CallableStatement call = c.prepareCall("{call @UpdateClasses(?, ?)}")) {
            if (jar == null) {
                call.setNull(1, Types.VARBINARY);
            } else {
                call.setBytes(1, jar);
            }
            if (deletes == null) {
                call.setNull(2, Types.VARCHAR);
            } else {
                call.setString(2, deletes);
            }
            call.executeQuery().close();
            return null;
        } catch (SQLException e) {
            if (response(e) == null) {
                throw e;
            }
            return String.valueOf(e.getMessage()).split("\n")[0];
        }
    }

    private VoltDBConnection connection() {
        return (VoltDBConnection) state.getConnection();
    }

    private void create(String table) {
        if (CREATED.contains(table)) {
            return;
        }
        ddl("CREATE TABLE " + table + " (k BIGINT NOT NULL, i INTEGER NOT NULL, v BIGINT NOT NULL, r BIGINT, "
                + "PRIMARY KEY (k, i)); PARTITION TABLE " + table + " ON COLUMN k");
        CREATED.add(table);
    }

    private void ddl(String sql) {
        String why = tryDdl(sql);
        if (why != null && !why.contains("already exists")) {
            throw new AssertionError(sql + " should succeed; it failed: " + why);
        }
    }

    // DDL; returns null, or why it failed
    private String tryDdl(String sql) {
        lastQuery = sql;
        try (Statement st = connection().createStatement()) {
            st.execute(sql);
            return null;
        } catch (SQLException e) {
            return String.valueOf(e.getMessage()).split("\n")[0];
        }
    }

    private static final class Names {
        final String sp;
        final String mp;
        final String cp;
        final String nt;
        final String bad;

        Names(String suffix) {
            sp = "JPS_" + suffix;
            mp = "JPM_" + suffix;
            cp = "JPC_" + suffix;
            nt = "JPN_" + suffix;
            bad = "JPB_" + suffix;
        }

        boolean contains(String upper) {
            for (String s : new String[] { sp, mp, cp, nt, bad }) {
                if (s.toUpperCase().equals(upper)) {
                    return true;
                }
            }
            return false;
        }

        // @UpdateClasses' deletes: every class of the check
        String all() {
            return PACKAGE + "." + sp + "," + PACKAGE + "." + mp + "," + PACKAGE + "." + cp + "," + PACKAGE + "." + nt
                    + "," + PACKAGE + "." + bad;
        }
    }

    // the four classes' sources, by simple name
    private static Map<String, String> sources(String table, Names n, long version, int batch) {
        Map<String, String> s = new LinkedHashMap<>();
        String header = "package " + PACKAGE
                + ";\n\nimport org.voltdb.*;\nimport org.voltdb.client.ClientResponse;\n\n";
        String result = "        VoltTable t = new VoltTable(new VoltTable.ColumnInfo(\"V\", VoltType.BIGINT),\n"
                + "                new VoltTable.ColumnInfo(\"N\", VoltType.BIGINT), new VoltTable.ColumnInfo(\"S\", VoltType.BIGINT),\n"
                + "                new VoltTable.ColumnInfo(\"U\", VoltType.BIGINT));\n"
                + "        t.addRow(V, count.getLong(0), count.getLong(1), getUniqueId());\n"
                + "        return new VoltTable[] { t };\n";
        // modes: 0 OK, 1 ABORT, 2 THROW, 3 EXPECTATION, 4 CONSTRAINT
        String modes = "        switch (mode) {\n"
                + "        case 1: throw new VoltAbortException(\"JAVAPROC abort \" + k);\n"
                + "        case 2: throw new IllegalStateException(\"JAVAPROC failure \" + k);\n"
                + "        case 3: voltQueueSQL(sel, EXPECT_EMPTY, k, k); voltExecuteSQL(true); break;\n"
                + "        case 4: voltQueueSQL(ins, k, 0, 0L, 0L); voltExecuteSQL(true); break;\n"
                + "        default: break;\n        }\n";
        s.put(n.sp, header + "public class " + n.sp + " extends VoltProcedure {\n" + "    public static final long V = "
                + version + "L;\n" + "    public final SQLStmt ins = new SQLStmt(\"INSERT INTO " + table
                + " (k, i, v, r) VALUES (?, ?, ?, ?)\");\n"
                + "    public final SQLStmt sel = new SQLStmt(\"SELECT COUNT(*), SUM(v) FROM " + table
                + " WHERE k BETWEEN ? AND ?\");\n\n" + "    public VoltTable[] run(long k, int n, int mode) {\n"
                + "        java.util.Random random = getSeededRandomNumberGenerator();\n"
                + "        for (int i = 0; i < n; i++) {\n"
                + "            voltQueueSQL(ins, EXPECT_SCALAR_MATCH(1), k, i, k * 1000 + i, random.nextLong());\n"
                + "            if ((i + 1) % " + batch + " == 0) {\n                voltExecuteSQL();\n            }\n"
                + "        }\n" + "        voltQueueSQL(sel, EXPECT_ONE_ROW, k, k);\n"
                + "        VoltTable[] results = voltExecuteSQL(mode == 0);\n"
                + "        VoltTable count = results[results.length - 1];\n" + "        count.advanceRow();\n" + modes
                + "        setAppStatusCode((byte) (k % 100));\n" + "        setAppStatusString(\"V\" + V);\n" + result
                + "    }\n}\n");
        s.put(n.mp, header + "public class " + n.mp + " extends VoltProcedure {\n" + "    public static final long V = "
                + version + "L;\n" + "    public final SQLStmt ins = new SQLStmt(\"INSERT INTO " + table
                + " (k, i, v, r) VALUES (?, ?, ?, ?)\");\n"
                + "    public final SQLStmt sel = new SQLStmt(\"SELECT COUNT(*), SUM(v) FROM " + table
                + " WHERE k BETWEEN ? AND ?\");\n\n"
                + "    public VoltTable[] run(long k, int keys, int n, int mode) {\n"
                + "        for (int j = 0; j < keys; j++) {\n" + "            for (int i = 0; i < n; i++) {\n"
                + "                voltQueueSQL(ins, EXPECT_SCALAR_MATCH(1), k + j, i, (k + j) * 1000 + i, (long) (j * n + i));\n"
                + "            }\n" + "            if ((j + 1) % " + batch
                + " == 0) {\n                voltExecuteSQL();\n            }\n" + "        }\n"
                + "        voltQueueSQL(sel, EXPECT_ONE_ROW, k, k + keys - 1);\n"
                + "        VoltTable[] results = voltExecuteSQL(mode == 0);\n"
                + "        VoltTable count = results[results.length - 1];\n" + "        count.advanceRow();\n" + modes
                + result + "    }\n}\n");
        // modes: Compound's order
        s.put(n.cp, header + "public class " + n.cp + " extends VoltCompoundProcedure {\n"
                + "    public static final long V = " + version + "L;\n"
                + "    private long k;\n    private int calls;\n    private int last;\n    private int mode;\n\n"
                + "    public long run(long k, int calls, int last, int mode) {\n"
                + "        this.k = k;\n        this.calls = calls;\n        this.last = last;\n        this.mode = mode;\n"
                + "        if (mode != 4) {\n" + "            newStageList(this::first).then(this::second).build();\n"
                + "        }\n" + "        return 0L;\n" + "    }\n\n"
                + "    private void first(ClientResponse[] none) {\n"
                + "        if (mode == 6) {\n            queueProcedureCall(\"" + n.cp + "\", k, 1, 0, 0);\n        }\n"
                + "        int c = mode == 7 ? 1000 : calls;\n" + "        for (int j = 0; j < c; j++) {\n"
                + "            queueProcedureCall(\"" + n.sp + "\", k + j, j + 1, j == calls - 1 ? last : 0);\n"
                + "        }\n" + "    }\n\n" + "    private void second(ClientResponse[] responses) {\n"
                + "        long sum = 0;\n"
                + "        VoltTable t = new VoltTable(new VoltTable.ColumnInfo(\"N\", VoltType.BIGINT),\n"
                + "                new VoltTable.ColumnInfo(\"STATUS\", VoltType.TINYINT));\n"
                + "        for (ClientResponse r : responses) {\n"
                + "            if (r.getStatus() != ClientResponse.SUCCESS) {\n" + "                if (mode == 0) {\n"
                + "                    abortProcedure(\"JAVAPROC call failed: \" + r.getStatusString());\n"
                + "                    return;\n" + "                }\n"
                + "                t.addRow(-1L, r.getStatus());\n" + "                continue;\n" + "            }\n"
                + "            VoltTable c = r.getResults()[0];\n" + "            c.advanceRow();\n"
                + "            sum += c.getLong(1);\n" + "            t.addRow(c.getLong(1), r.getStatus());\n"
                + "        }\n" + "        switch (mode) {\n" + "        case 0: completeProcedure(sum); return;\n"
                + "        case 1: completeProcedure(t); return;\n"
                + "        case 2: throw new CompoundProcAbortException(\"JAVAPROC abort \" + k);\n"
                + "        case 3: throw new IllegalStateException(\"JAVAPROC failure \" + k);\n"
                + "        case 5: return;\n" + "        case 8:\n" + "            queueProcedureCall(\"" + n.sp
                + "\", k + 9, 1, 0);\n" + "            completeProcedure(sum);\n" + "            return;\n"
                + "        case 9:\n" + "            setAppStatusCode((byte) 7);\n"
                + "            setAppStatusString(\"V\" + V);\n"
                + "            completeProcedure(new VoltTable[] { t });\n" + "            return;\n"
                + "        default: completeProcedure(sum);\n" + "        }\n" + "    }\n}\n");
        s.put(n.nt,
                header + "public class " + n.nt + " extends VoltNonTransactionalProcedure {\n"
                        + "    public static final long V = " + version + "L;\n\n"
                        + "    public java.util.concurrent.CompletableFuture<ClientResponse> run(long k, int n) {\n"
                        + "        return callProcedure(\"" + n.sp + "\", k, n, 0);\n" + "    }\n}\n");
        return s;
    }

    // compiles the sources against --procedure-classpath; returns each class's bytes by its file's path in a jar
    private Map<String, byte[]> compile(Map<String, String> sources) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<JavaFileObject> units = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            units.add(new SimpleJavaFileObject(URI.create("string:///" + PACKAGE + "/" + e.getKey() + ".java"),
                    JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return e.getValue();
                }
            });
        }
        synchronized (COMPILE) {
            StandardJavaFileManager standard = compiler.getStandardFileManager(diagnostics, null,
                    StandardCharsets.UTF_8);
            JavaFileManager files = new ForwardingJavaFileManager<JavaFileManager>(standard) {
                @Override
                public JavaFileObject getJavaFileForOutput(Location location, String className,
                        JavaFileObject.Kind kind, FileObject sibling) {
                    return new SimpleJavaFileObject(URI.create("bytes:///" + className.replace('.', '/') + ".class"),
                            kind) {
                        @Override
                        public OutputStream openOutputStream() {
                            return new ByteArrayOutputStream() {
                                @Override
                                public void close() throws IOException {
                                    super.close();
                                    out.put(className.replace('.', '/') + ".class", toByteArray());
                                }
                            };
                        }
                    };
                }
            };
            List<String> options = List.of("-classpath", state.getDbmsSpecificOptions().procedureClasspath.trim(),
                    "--release", "17", "-proc:none", "-nowarn", "-g");
            Boolean ok = compiler.getTask(null, files, diagnostics, options, null, units).call();
            if (!Boolean.TRUE.equals(ok)) {
                throw new AssertionError(
                        "JAVAPROC's procedure classes do not compile: " + diagnostics.getDiagnostics());
            }
        }
        return out;
    }

    private static byte[] jar(Map<String, byte[]> classes) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                jar.putNextEntry(new JarEntry(e.getKey()));
                jar.write(e.getValue());
                jar.closeEntry();
            }
        }
        return bytes.toByteArray();
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
