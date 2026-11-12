package sqlancer.voltdb.oracle;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.voltdb.VoltTable;
import org.voltdb.VoltType;
import org.voltdb.types.TimestampType;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.Reproducer;
import sqlancer.common.oracle.TestOracle;
import sqlancer.voltdb.VoltDBConnection;
import sqlancer.voltdb.VoltDBProvider.VoltDBGlobalState;

/**
 * Bulk operations (milestone 35), which milestone 30's coverage run found unexercised: the system procedures
 * {@code @LoadMultipartitionTable}, {@code @LoadSinglepartitionTable} and {@code @SwapTables}, and the engine's swap
 * executor. Each check uses two twin tables of its own, {@code XL<P|R>A_<db>} and {@code XL<P|R>B_<db>} for LOAD and
 * {@code XW...} for SWAP, partitioned on k or replicated, with a primary key (k, id), a unique index (k, v) and an
 * ordinary index (f, t).
 * <p>
 * LOAD: both twins get the same base rows. A batch of rows is loaded into A by the system procedure, in insert or
 * upsert mode, and applied to B as single INSERT or UPSERT statements. If every statement succeeded, A must hold B's
 * rows and the procedure must report the batch's size; if one broke a constraint, the load must fail as a whole and
 * leave A as it was. A partitioned batch has one k, as VoltBulkLoader sends it.
 * <p>
 * SWAP: A and B get different rows; after {@code @SwapTables(A, B)} each must hold the other's, the procedure must
 * report how many rows moved, and lookups through each index must find each row.
 * <p>
 * After either, {@code @ValidatePartitioning} must find no row in the wrong partition, in any table.
 * <p>
 * Changes go through prepared statements and calls, which the mirror (DIFF) does not repeat; the DDL goes through an
 * ordinary statement, as the other oracles' does, so that it waits while a node is being killed (--ddl-hold). Each
 * check starts by emptying the twins, so one that fails other than on a constraint (a node failure's) is only skipped.
 */
public class VoltDBBulkOracle implements TestOracle<VoltDBGlobalState> {

    public enum Mode {
        LOAD, SWAP
    }

    private static final String COLUMNS = " (k INTEGER NOT NULL, id SMALLINT NOT NULL, v VARCHAR(12), b VARBINARY(6),"
            + " f FLOAT, d DECIMAL, t TIMESTAMP, PRIMARY KEY (k, id))";
    private static final String SELECT = "SELECT k, id, v, b, f, d, t FROM ";
    private static final AtomicLong LOADS = new AtomicLong();
    private static final AtomicLong LOADS_REJECTED = new AtomicLong();
    private static final AtomicLong SWAPS = new AtomicLong();
    private static final AtomicLong VALIDATIONS = new AtomicLong();
    // the twins created so far (they are never dropped): their DDL goes to the cluster once, as each catalog change is
    // slow on a cluster and waits while a node is killed
    private static final Set<String> CREATED = ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime()
                .addShutdownHook(new Thread(() -> System.out.println(
                        "LOAD checks: " + LOADS + " (" + LOADS_REJECTED + " batches rejected as a whole); SWAP checks: "
                                + SWAPS + "; partitioning validated: " + VALIDATIONS)));
    }

    private final VoltDBGlobalState state;
    private final Mode mode;
    private String lastQuery;

    public VoltDBBulkOracle(VoltDBGlobalState state, Mode mode) {
        this.state = state;
        this.mode = mode;
    }

    @Override
    public void check() throws Exception {
        boolean partitioned = Randomly.getBoolean();
        String prefix = (mode == Mode.LOAD ? "XL" : "XW") + (partitioned ? "P" : "R");
        String db = state.getDatabaseName().toUpperCase();
        String a = prefix + "A_" + db;
        String b = prefix + "B_" + db;
        create(a, partitioned);
        create(b, partitioned);
        write("DELETE FROM " + a);
        write("DELETE FROM " + b);
        if (mode == Mode.LOAD) {
            load(a, b, partitioned);
        } else {
            swap(a, b);
        }
        validate();
    }

    private void load(String a, String b, boolean partitioned) throws SQLException {
        int batchK = (int) Randomly.getNotCachedInteger(0, 50);
        // the base rows go into both twins alike; one that breaks a constraint does so in both
        for (int i = (int) Randomly.getNotCachedInteger(0, 8); i > 0; i--) {
            Object[] row = row(partitioned ? Randomly.getBoolean() ? batchK : (int) Randomly.getNotCachedInteger(0, 50)
                    : (int) Randomly.getNotCachedInteger(0, 3));
            boolean inA = tryWrite("INSERT INTO " + a + " VALUES " + literal(row));
            boolean inB = tryWrite("INSERT INTO " + b + " VALUES " + literal(row));
            if (inA != inB) {
                throw new AssertionError("the same INSERT " + literal(row) + " into the twin tables " + a + " and " + b
                        + " (the same schema, the same rows) "
                        + (inA ? "succeeded in " + a + " only" : "succeeded in " + b + " only"));
            }
        }
        List<String> before = rows(a);
        if (!before.equals(rows(b))) {
            throw new AssertionError("after the same INSERTs, the twin tables " + a + " and " + b + " differ; only in "
                    + a + ": " + VoltDBRows.difference(before, rows(b)) + "; only in " + b + ": "
                    + VoltDBRows.difference(rows(b), before));
        }
        boolean upsert = Randomly.getBoolean();
        List<Object[]> batch = new ArrayList<>();
        for (int i = (int) Randomly.getNotCachedInteger(1, 12); i > 0; i--) {
            batch.add(row(partitioned ? batchK : (int) Randomly.getNotCachedInteger(0, 3)));
        }
        boolean statementFailed = false;
        for (Object[] row : batch) {
            if (!tryWrite((upsert ? "UPSERT" : "INSERT") + " INTO " + b + " VALUES " + literal(row))) {
                statementFailed = true;
            }
        }
        VoltTable table = new VoltTable(new VoltTable.ColumnInfo("K", VoltType.INTEGER),
                new VoltTable.ColumnInfo("ID", VoltType.SMALLINT), new VoltTable.ColumnInfo("V", VoltType.STRING),
                new VoltTable.ColumnInfo("B", VoltType.VARBINARY), new VoltTable.ColumnInfo("F", VoltType.FLOAT),
                new VoltTable.ColumnInfo("D", VoltType.DECIMAL), new VoltTable.ColumnInfo("T", VoltType.TIMESTAMP));
        for (Object[] row : batch) {
            table.addRow(row);
        }
        String procedure = partitioned ? "@LoadSinglepartitionTable" : "@LoadMultipartitionTable";
        lastQuery = procedure + "('" + a + "', " + (upsert ? "upsert" : "insert") + ", " + batch.size() + " rows: "
                + literals(batch) + ")";
        Long loaded = null;
        String rejected = null;
        try (CallableStatement call = connection()
                .prepareCall("{call " + procedure + "(" + (partitioned ? "?, " : "") + "?, ?, ?)}")) {
            int p = 1;
            if (partitioned) {
                call.setObject(p++, VoltType.valueToBytes(batchK));
            }
            call.setString(p++, a);
            call.setByte(p++, (byte) (upsert ? 1 : 0));
            call.setObject(p, table);
            try (ResultSet rs = call.executeQuery()) {
                loaded = rs.next() ? rs.getLong(1) : null;
            }
        } catch (SQLException e) {
            if (!String.valueOf(e.getMessage()).contains("CONSTRAINT VIOLATION")) {
                throw new IgnoreMeException();
            }
            rejected = e.getMessage().split("\n")[0];
        }
        LOADS.incrementAndGet();
        List<String> after = rows(a);
        if (statementFailed) {
            LOADS_REJECTED.incrementAndGet();
            if (rejected == null || !after.equals(before)) {
                throw new AssertionError(lastQuery + ": a row of the batch breaks a constraint (its "
                        + (upsert ? "UPSERT" : "INSERT") + " into the twin " + b + " failed), so the load should fail "
                        + "and leave " + a + " as it was; the load "
                        + (rejected == null ? "reported " + loaded : "failed (" + rejected + ")") + "; rows it added: "
                        + VoltDBRows.difference(after, before) + "; rows it removed: "
                        + VoltDBRows.difference(before, after));
            }
            return;
        }
        List<String> expected = rows(b);
        if (rejected != null || !after.equals(expected) || loaded == null || loaded != batch.size()) {
            throw new AssertionError(lastQuery + ": the same rows as single " + (upsert ? "UPSERT" : "INSERT")
                    + " statements into the twin " + b + " all succeeded; the load "
                    + (rejected != null ? "failed: " + rejected : "reported " + loaded + " rows of " + batch.size())
                    + "; only in " + a + ": " + VoltDBRows.difference(after, expected) + "; only in " + b + ": "
                    + VoltDBRows.difference(expected, after));
        }
    }

    private void swap(String a, String b) throws SQLException {
        for (String t : new String[] { a, b }) {
            for (int i = (int) Randomly.getNotCachedInteger(0, 10); i > 0; i--) {
                tryWrite("INSERT INTO " + t + " VALUES " + literal(row((int) Randomly.getNotCachedInteger(0, 50))));
            }
        }
        List<String> rowsA = rows(a);
        List<String> rowsB = rows(b);
        for (int round = 0; round < (Randomly.getBoolean() ? 2 : 1); round++) {
            lastQuery = "@SwapTables('" + a + "', '" + b + "')";
            long moved;
            try (CallableStatement call = connection().prepareCall("{call @SwapTables(?, ?)}")) {
                call.setString(1, a);
                call.setString(2, b);
                try (ResultSet rs = call.executeQuery()) {
                    moved = rs.next() ? rs.getLong(1) : -1;
                }
            } catch (SQLException | IgnoreMeException e) {
                throw new IgnoreMeException();
            }
            SWAPS.incrementAndGet();
            List<String> nowA = rows(a);
            List<String> nowB = rows(b);
            if (!nowA.equals(rowsB) || !nowB.equals(rowsA) || moved != rowsA.size() + rowsB.size()) {
                throw new AssertionError(lastQuery + " should leave each table with the other's rows and report "
                        + (rowsA.size() + rowsB.size()) + " rows moved; it reported " + moved + "; " + a
                        + " now has, beyond " + b + "'s old rows: " + VoltDBRows.difference(nowA, rowsB)
                        + ", and lacks: " + VoltDBRows.difference(rowsB, nowA) + "; " + b + " now has, beyond " + a
                        + "'s old rows: " + VoltDBRows.difference(nowB, rowsA) + ", and lacks: "
                        + VoltDBRows.difference(rowsA, nowB));
            }
            lookups(a);
            lookups(b);
            List<String> swapped = rowsA;
            rowsA = rowsB;
            rowsB = swapped;
        }
    }

    // each row, looked up through the primary key's index and, where v is not NULL, through the unique index on (k, v)
    private void lookups(String table) throws SQLException {
        List<List<Object>> all = values(SELECT + table + " ORDER BY k, id");
        for (List<Object> row : all) {
            List<String> expected = List.of(VoltDBRows.format(row));
            String byKey = SELECT + table + " WHERE k = " + row.get(0) + " AND id = " + row.get(1);
            List<String> found = strings(byKey);
            if (!found.equals(expected)) {
                throw new AssertionError(byKey + " after @SwapTables should return " + expected
                        + ", the row a scan of the table finds; it returned " + found);
            }
            if (row.get(2) != null) {
                String byValue = SELECT + table + " WHERE k = " + row.get(0) + " AND v = '" + row.get(2) + "'";
                found = strings(byValue);
                if (!found.equals(expected)) {
                    throw new AssertionError(byValue + " after @SwapTables should return " + expected
                            + " through the unique index on (k, v); it returned " + found);
                }
            }
        }
        String counted = "SELECT COUNT(*) FROM " + table + " WHERE f >= -1.0E300";
        long withF = all.stream().filter(r -> r.get(4) != null).count();
        List<List<Object>> count = values(counted);
        if (((Number) count.get(0).get(0)).longValue() != withF) {
            throw new AssertionError(counted + " (through the index on (f, t)) after @SwapTables should count the "
                    + withF + " rows with a FLOAT; it counted " + count.get(0).get(0));
        }
    }

    // every table's rows in the partition its key hashes to
    private void validate() throws SQLException {
        lastQuery = "@ValidatePartitioning(NULL)";
        List<String> wrong = new ArrayList<>();
        try (CallableStatement call = connection().prepareCall("{call @ValidatePartitioning(?)}")) {
            // NULL, the current hash configuration; a byte array it cannot parse crashes the server (ENG-30674)
            call.setNull(1, Types.VARBINARY);
            try (ResultSet rs = call.executeQuery()) {
                while (rs.next()) {
                    if (rs.getLong("MISPARTITIONED_ROWS") != 0) {
                        wrong.add(rs.getString("TABLE") + " in partition " + rs.getLong("PARTITION_ID") + ": "
                                + rs.getLong("MISPARTITIONED_ROWS"));
                    }
                }
            }
        } catch (SQLException e) {
            // a node failure, a resize or a schema change in progress
            throw new IgnoreMeException();
        }
        VALIDATIONS.incrementAndGet();
        if (!wrong.isEmpty()) {
            throw new AssertionError("@ValidatePartitioning found rows in the wrong partition: " + wrong);
        }
    }

    private Object[] row(int k) {
        // a batch shares its k when partitioned, so v and id come from ranges wide enough that most batches load
        String v = Randomly.getBooleanWithRatherLowProbability() ? null
                : Randomly.getBooleanWithRatherLowProbability() ? "" : "s" + Randomly.getNotCachedInteger(0, 60);
        byte[] bytes = Randomly.fromOptions(null, new byte[0], new byte[] { 1 }, new byte[] { (byte) 0xAB, 0x0C });
        Double f = Randomly.fromOptions(null, -1.5, 0.0, 2.5, 1.0E20);
        BigDecimal d = Randomly.fromOptions(null, BigDecimal.ZERO, new BigDecimal("-12.345"),
                new BigDecimal("99999.5"));
        TimestampType t = Randomly.fromOptions(null, new TimestampType(0L), new TimestampType(1_577_836_800_000_000L),
                new TimestampType(1L));
        return new Object[] { k, (short) Randomly.getNotCachedInteger(0, 60), v, bytes, f,
                d == null ? null : d.setScale(12), t };
    }

    private static String literal(Object... row) {
        StringBuilder sb = new StringBuilder("(").append(row[0]).append(", ").append(row[1]).append(", ");
        sb.append(row[2] == null ? "NULL" : "'" + row[2] + "'").append(", ");
        if (row[3] == null) {
            sb.append("NULL");
        } else {
            sb.append("x'");
            for (byte x : (byte[]) row[3]) {
                sb.append(String.format("%02X", x));
            }
            sb.append("'");
        }
        String f = String.valueOf(row[4]);
        sb.append(", ").append(row[4] == null ? "NULL" : f.contains("E") ? f : f + "E0");
        sb.append(", ").append(row[5] == null ? "NULL" : ((BigDecimal) row[5]).toPlainString());
        sb.append(", ")
                .append(row[6] == null ? "NULL" : "TO_TIMESTAMP(MICROS, " + ((TimestampType) row[6]).getTime() + ")");
        return sb.append(")").toString();
    }

    private static String literals(List<Object[]> rows) {
        List<String> l = new ArrayList<>();
        for (Object[] row : rows) {
            l.add(literal(row));
        }
        return String.join(", ", l);
    }

    private VoltDBConnection connection() {
        return (VoltDBConnection) state.getConnection();
    }

    // DDL that may already have been done for this database name earlier in the run
    private void create(String table, boolean partitioned) {
        if (CREATED.contains(table)) {
            return;
        }
        String sql = "CREATE TABLE " + table + COLUMNS
                + (partitioned ? "; PARTITION TABLE " + table + " ON COLUMN k" : "") + "; CREATE UNIQUE INDEX " + table
                + "_KV ON " + table + " (k, v); CREATE INDEX " + table + "_FT ON " + table + " (f, t)";
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

    private List<List<Object>> values(String sql) throws SQLException {
        lastQuery = sql;
        List<List<Object>> rows = new ArrayList<>();
        // a query, which the mirror does not repeat
        try (Statement st = connection().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= n; i++) {
                    row.add(rs.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private List<String> strings(String sql) throws SQLException {
        List<String> rows = new ArrayList<>();
        for (List<Object> row : values(sql)) {
            rows.add(VoltDBRows.format(row));
        }
        return rows;
    }

    private List<String> rows(String table) throws SQLException {
        return strings(SELECT + table + " ORDER BY k, id");
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
