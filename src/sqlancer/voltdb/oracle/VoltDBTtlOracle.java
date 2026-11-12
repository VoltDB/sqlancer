package sqlancer.voltdb.oracle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.Reproducer;
import sqlancer.common.oracle.TestOracle;
import sqlancer.voltdb.VoltDBProvider.VoltDBGlobalState;

/**
 * Rows that VoltDB removes by itself: time to live and migration (milestone 34). Milestone 30's coverage run found the
 * system procedures behind them (LowImpactDeleteNT, MigrateRowsNT) unexercised.
 * <p>
 * TTL: a table {@code XT_<database>} with {@code USING TTL 2 SECONDS ON COLUMN ts}. Each check inserts rows whose ts is
 * an hour ago (expired) or a day ahead (live), each with a v unique in the run, and waits up to 60 s until every
 * expired row is gone while every live one is still there.
 * <p>
 * MIGRATE: a table {@code XM_<database>} that migrates to {@code --loopback-target}, if any. Each check inserts rows
 * into it and into a shadow table, then runs {@code MIGRATE FROM XM_... WHERE k < x AND NOT MIGRATING}. With the
 * loopback target, the migrated rows go, through the export, into XS_LB (tagged with the table's name), and once none
 * is migrating any more, the table's rows and those in XS_LB must together be the shadow table's, with none in both.
 * Without it, the rows stay, marked as migrating: the table must hold the shadow's rows, and exactly those migrated so
 * far must be MIGRATING.
 */
public class VoltDBTtlOracle implements TestOracle<VoltDBGlobalState> {

    private static final AtomicLong IDS = new AtomicLong();
    private static final AtomicLong TTL_CHECKED = new AtomicLong();
    private static final AtomicLong MIGRATE_CHECKED = new AtomicLong();
    private static final long WAIT_MILLIS = 60_000;
    // the names whose tables a failed statement may have left out of step with what the oracle knows; static, as a
    // later database of the same name goes on with them
    private static final Set<String> OUT_OF_STEP = ConcurrentHashMap.newKeySet();
    // without a loopback target, the v of each row migrated so far, by table
    private static final ConcurrentHashMap<String, Set<Long>> MIGRATED = new ConcurrentHashMap<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> System.out.println("TTL checks: " + TTL_CHECKED + "; MIGRATE checks: " + MIGRATE_CHECKED)));
    }

    private final VoltDBGlobalState state;
    private String lastQuery;

    public VoltDBTtlOracle(VoltDBGlobalState state) {
        this.state = state;
    }

    @Override
    public void check() throws Exception {
        String db = state.getDatabaseName().toUpperCase();
        if (Randomly.getBoolean()) {
            String target = state.getDbmsSpecificOptions().ttlMigrateTarget;
            boolean migrating = target != null && !target.isBlank() && Randomly.getBoolean();
            ttl(migrating ? "XTM_" + db : "XT_" + db, migrating ? target : null);
        } else {
            migrate("XM_" + db);
        }
    }

    // with migrateTarget (--ttl-migrate-target), the table migrates its expired rows to that loopback target, whose
    // procedure inserts them into XS_LBT, instead of deleting them
    private void ttl(String table, String migrateTarget) throws SQLException {
        if (OUT_OF_STEP.contains(table)) {
            throw new IgnoreMeException();
        }
        boolean migrating = migrateTarget != null;
        String columns = " (d VARCHAR(24) NOT NULL, k INTEGER NOT NULL, ts TIMESTAMP NOT NULL, v BIGINT NOT NULL)";
        if (migrating) {
            ddl("CREATE TABLE XS_LBT" + columns + "; PARTITION TABLE XS_LBT ON COLUMN k");
        }
        ddl("CREATE TABLE " + table + (migrating ? " MIGRATE TO TARGET " + migrateTarget : "") + columns
                + " USING TTL 2 SECONDS ON COLUMN ts BATCH_SIZE " + Randomly.fromOptions(1, 10, 1000)
                + " MAX_FREQUENCY " + Randomly.fromOptions(1, 10, 100) + "; PARTITION TABLE " + table + " ON COLUMN k");
        // a TTL table that migrates finds its expired rows through an index WHERE NOT MIGRATING; with an ordinary one
        // it never migrates them
        ddl("CREATE INDEX " + table + "_TS ON " + table + " (ts)" + (migrating ? " WHERE NOT MIGRATING" : ""));
        List<Long> expired = new ArrayList<>();
        List<Long> live = new ArrayList<>();
        for (int i = (int) Randomly.getNotCachedInteger(1, 30); i > 0; i--) {
            long v = IDS.incrementAndGet();
            boolean expires = Randomly.getBoolean();
            String ts = expires ? "DATEADD(HOUR, -1, NOW)" : "DATEADD(DAY, 1, NOW)";
            write(table, "INSERT INTO " + table + " VALUES ('" + table + "', " + Randomly.getNotCachedInteger(0, 50)
                    + ", " + ts + ", " + v + ")");
            (expires ? expired : live).add(v);
        }
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        Set<Long> left;
        while (true) {
            left = ids("SELECT v FROM " + table + " WHERE v >= " + min(expired, live));
            boolean expiredGone = expired.stream().noneMatch(left::contains);
            if (expiredGone || System.currentTimeMillis() >= deadline) {
                break;
            }
            sleep();
        }
        Set<Long> remaining = left;
        TTL_CHECKED.incrementAndGet();
        if (migrating) {
            // the expired rows must have arrived in XS_LBT, and no live one
            Set<Long> moved;
            while (true) {
                moved = ids("SELECT v FROM XS_LBT WHERE d = '" + table + "' AND v >= " + min(expired, live));
                if (moved.containsAll(expired) || System.currentTimeMillis() >= deadline) {
                    break;
                }
                sleep();
            }
            List<Long> missing = new ArrayList<>();
            for (long v : expired) {
                if (!moved.contains(v)) {
                    missing.add(v);
                }
            }
            List<Long> early = new ArrayList<>();
            for (long v : live) {
                if (moved.contains(v)) {
                    early.add(v);
                }
            }
            if (!missing.isEmpty() || !early.isEmpty()) {
                throw new AssertionError("TTL table " + table + " MIGRATE TO TARGET " + migrateTarget
                        + ": expired rows " + "that never reached XS_LBT within " + WAIT_MILLIS / 1000 + " s, by v: "
                        + missing + "; live rows that did: " + early);
            }
        }
        List<Long> notExpired = new ArrayList<>();
        expired.stream().filter(remaining::contains).forEach(notExpired::add);
        List<Long> lost = new ArrayList<>();
        live.stream().filter(v -> !remaining.contains(v)).forEach(lost::add);
        if (!notExpired.isEmpty() || !lost.isEmpty()) {
            throw new AssertionError(
                    "TTL table " + table + " (USING TTL 2 SECONDS): rows an hour old still there after "
                            + WAIT_MILLIS / 1000 + " s, by v: " + notExpired + "; rows a day ahead gone: " + lost);
        }
    }

    private void migrate(String table) throws SQLException {
        if (OUT_OF_STEP.contains(table)) {
            throw new IgnoreMeException();
        }
        String loopback = state.getDbmsSpecificOptions().loopbackTarget;
        boolean looping = loopback != null && !loopback.isBlank();
        // the loopback target's procedure, XS_LB.insert, takes these columns; v is unique in the run
        String columns = " (d VARCHAR(24) NOT NULL, k INTEGER NOT NULL, v BIGINT NOT NULL, s VARCHAR(16), f FLOAT)";
        if (looping) {
            // as STREAMS creates it (VoltDBStreamOracle.TAGGED_COLUMNS), whichever of the two comes first
            ddl("CREATE TABLE XS_LB (d VARCHAR(24) NOT NULL, k INTEGER NOT NULL, v BIGINT, s VARCHAR(16), f FLOAT);"
                    + " PARTITION TABLE XS_LB ON COLUMN k");
        }
        ddl("CREATE TABLE " + table + " MIGRATE TO TARGET " + (looping ? loopback : "sqlancer_none") + columns
                + "; PARTITION TABLE " + table + " ON COLUMN k");
        ddl("CREATE INDEX " + table + "_K ON " + table + " (k) WHERE NOT MIGRATING");
        ddl("CREATE TABLE " + table + "_SH" + columns + "; PARTITION TABLE " + table + "_SH ON COLUMN k");
        for (int i = (int) Randomly.getNotCachedInteger(1, 20); i > 0; i--) {
            String values = "('" + table + "', " + Randomly.getNotCachedInteger(0, 50) + ", " + IDS.incrementAndGet()
                    + ", "
                    + (Randomly.getBooleanWithRatherLowProbability() ? "NULL"
                            : "'" + Randomly.fromOptions("a", "b", "") + "'")
                    + ", " + (Randomly.getBooleanWithRatherLowProbability() ? "NULL"
                            : Randomly.getNotCachedInteger(-100, 100) + ".5E0")
                    + ")";
            write(table, "INSERT INTO " + table + " VALUES " + values);
            write(table, "INSERT INTO " + table + "_SH VALUES " + values);
        }
        long below = Randomly.getNotCachedInteger(0, 50);
        Set<Long> migrating = ids("SELECT v FROM " + table + " WHERE k < " + below + " AND NOT MIGRATING");
        write(table, "MIGRATE FROM " + table + " WHERE k < " + below + " AND NOT MIGRATING");
        Set<Long> all = ids("SELECT v FROM " + table + "_SH");
        MIGRATE_CHECKED.incrementAndGet();
        if (!looping) {
            Set<Long> migrated = MIGRATED.computeIfAbsent(table, t -> ConcurrentHashMap.newKeySet());
            migrated.addAll(migrating);
            Set<Long> here = ids("SELECT v FROM " + table);
            Set<Long> marked = ids("SELECT v FROM " + table + " WHERE MIGRATING");
            if (!here.equals(all) || !marked.equals(migrated)) {
                throw new AssertionError("MIGRATE to a target that does not exist: " + table + " should hold every row "
                        + "inserted and mark as MIGRATING those migrated; only in the table: " + minus(here, all)
                        + ", missing: " + minus(all, here) + "; MIGRATING but not migrated: " + minus(marked, migrated)
                        + ", migrated but not MIGRATING: " + minus(migrated, marked));
            }
            return;
        }
        // once the export has caught up, nothing is migrating any more
        long deadline = System.currentTimeMillis() + WAIT_MILLIS;
        Set<Long> here;
        Set<Long> moved;
        while (true) {
            here = ids("SELECT v FROM " + table);
            moved = ids("SELECT v FROM XS_LB WHERE d = '" + table + "'");
            Set<Long> both = new HashSet<>(here);
            both.addAll(moved);
            if (both.equals(all) && minus(here, moved).equals(here) || System.currentTimeMillis() >= deadline) {
                break;
            }
            sleep();
        }
        Set<Long> both = new HashSet<>(here);
        both.addAll(moved);
        Set<Long> twice = new HashSet<>(here);
        twice.retainAll(moved);
        if (!both.equals(all) || !twice.isEmpty()) {
            throw new AssertionError(
                    "MIGRATE through the loopback target: " + table + " and XS_LB should together hold "
                            + "every row inserted, each in one of them, within " + WAIT_MILLIS / 1000 + " s; missing: "
                            + minus(all, both) + "; in both: " + twice + "; never inserted: " + minus(both, all));
        }
    }

    private static long min(List<Long> a, List<Long> b) {
        long m = Long.MAX_VALUE;
        for (long v : a) {
            m = Math.min(m, v);
        }
        for (long v : b) {
            m = Math.min(m, v);
        }
        return m;
    }

    private static Set<Long> minus(Set<Long> a, Set<Long> b) {
        Set<Long> d = new HashSet<>(a);
        d.removeAll(b);
        return d;
    }

    private static void sleep() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IgnoreMeException();
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

    // a change whose failure (a node failure's, with --node-failures and the like) leaves the table's contents unknown
    private void write(String table, String sql) {
        lastQuery = sql;
        try (Statement st = state.getConnection().createStatement()) {
            st.execute(sql);
        } catch (SQLException | IgnoreMeException e) {
            OUT_OF_STEP.add(table);
            throw new IgnoreMeException();
        }
    }

    private Set<Long> ids(String sql) throws SQLException {
        lastQuery = sql;
        Set<Long> ids = new HashSet<>();
        try (Statement st = state.getConnection().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        return ids;
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
