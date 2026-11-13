package sqlancer.voltdb.oracle;

import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
 * Scheduled tasks (milestone 39), which milestone 30's coverage run found little exercised ({@code TaskManager},
 * {@code TaskStatsSource}, the CRON schedule). Each check creates a task of its own that runs a procedure inserting a
 * row tagged with the task's name into {@code XK_<db>} (partitioned on k): on the database, or on every partition (RUN
 * ON PARTITIONS: each partition's scheduler calls a partitioned procedure); lets it run, disables it, and checks:
 * <ul>
 * <li>it ran: at least one row (two for a task on every partition), and no more than its schedule allows;</li>
 * <li>once {@code ALTER TASK ... DISABLE} has returned, at most one more row per scheduler (one in flight), and none
 * after that;</li>
 * <li>{@code @Statistics TASK} shows it DISABLED.</li>
 * </ul>
 * A third of the checks instead run a task whose procedure fails (a duplicate primary key) with {@code ON ERROR STOP}:
 * it must stop after the first failure, at most one row in all, and show ERROR in {@code @Statistics TASK}. The task is
 * dropped at the end.
 * <p>
 * Timing on a loaded machine varies, so the bounds are loose: a task that does not run at all, runs on after being
 * disabled or stopped, or runs far more often than scheduled, fails the check.
 */
public class VoltDBTaskOracle implements TestOracle<VoltDBGlobalState> {

    private static final AtomicLong IDS = new AtomicLong();
    private static final AtomicLong CHECKS = new AtomicLong();
    private static final AtomicLong STOPS = new AtomicLong();
    private static final Set<String> CREATED = ConcurrentHashMap.newKeySet();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> System.out.println("TASK checks: " + CHECKS + "; tasks stopped by an error checked: " + STOPS)));
    }

    private final VoltDBGlobalState state;
    private String lastQuery;

    public VoltDBTaskOracle(VoltDBGlobalState state) {
        this.state = state;
    }

    @Override
    public void check() throws Exception {
        String db = state.getDatabaseName().toUpperCase();
        String table = "XK_" + db;
        create(table, db);
        String task = "XKT_" + db + "_" + IDS.incrementAndGet();
        if (Randomly.fromOptions(true, false, false)) {
            stopOnError(db, task);
            return;
        }
        boolean partitions = Randomly.getBoolean();
        long millis;
        String schedule;
        switch ((int) Randomly.getNotCachedInteger(0, 3)) {
        case 0:
            millis = Randomly.fromOptions(200L, 500L, 1000L);
            schedule = "EVERY " + millis + " MILLISECONDS";
            break;
        case 1:
            millis = Randomly.fromOptions(200L, 500L, 1000L);
            schedule = "DELAY " + millis + " MILLISECONDS";
            break;
        default:
            millis = 1000;
            schedule = "CRON * * * * * *";
            break;
        }
        // on every partition, the partitioned procedure: each partition's scheduler calls it (with the same k, so
        // the rows all go to that k's partition)
        String call = partitions ? "XKQ_" + db + " WITH (" + Randomly.getNotCachedInteger(0, 1000) + ", '" + task + "')"
                : "XKP_" + db + " WITH ('" + task + "')";
        // ON ERROR comes before RUN ON
        ddl("CREATE TASK " + task + " ON SCHEDULE " + schedule + " PROCEDURE " + call + " ON ERROR "
                + Randomly.fromOptions("LOG", "IGNORE") + (partitions ? " RUN ON PARTITIONS" : ""));
        long created = System.currentTimeMillis();
        try {
            Thread.sleep(3000);
            ddl("ALTER TASK " + task + " DISABLE");
            // the time it really ran: ALTER TASK is DDL, which waits while a node is killed (--ddl-hold)
            long runMillis = System.currentTimeMillis() - created;
            long ran = count(table, task);
            int schedulers = partitions ? partitions() : 1;
            // ENG-30680: after a leader's node fails while the task's procedure is in flight, the task runs in two
            // chains; while avoiding known bugs, where nodes fail, it may run twice as often (ALTER TASK DISABLE still
            // stops both)
            VoltDBOptions o = state.getDbmsSpecificOptions();
            int chains = o.avoidKnownBugs && (o.nodeFailures || o.resizes || o.crashes || o.upgrades) ? 2 : 1;
            long most = chains * schedulers * (runMillis / millis + 3);
            if (ran < Math.min(schedulers, 2) || ran > most) {
                throw new AssertionError("task " + task + " (" + schedule + (partitions ? ", RUN ON PARTITIONS" : "")
                        + ") inserted " + ran + " rows in " + runMillis + " ms (at " + times(table, task)
                        + "); expected " + Math.min(schedulers, 2) + " to " + most);
            }
            Thread.sleep(Math.max(1500, 2 * millis));
            long after = count(table, task);
            Thread.sleep(Math.max(1500, 2 * millis));
            long later = count(table, task);
            if (after > ran + chains * schedulers || later != after) {
                throw new AssertionError("task " + task + " (" + schedule + ") went on after ALTER TASK DISABLE: " + ran
                        + " rows when disabled, " + after + " and then " + later + " later");
            }
            String status = status(task);
            if (status != null && !"DISABLED".equals(status)) {
                throw new AssertionError(
                        "@Statistics TASK shows task " + task + " as " + status + " after ALTER TASK " + "DISABLE");
            }
            CHECKS.incrementAndGet();
        } finally {
            dropQuietly(task);
        }
    }

    // a task whose procedure fails every time it runs (it inserts the same primary key again), ON ERROR STOP
    private void stopOnError(String db, String task) throws Exception {
        ddl("CREATE TASK " + task + " ON SCHEDULE EVERY 200 MILLISECONDS PROCEDURE XKU_" + db + " WITH ("
                + IDS.incrementAndGet() + ", '" + task + "') ON ERROR STOP");
        try {
            Thread.sleep(3000);
            long rows = count("XKS_" + db, task);
            Thread.sleep(1500);
            long later = count("XKS_" + db, task);
            String status = status(task);
            if (rows > 1 || later != rows || status != null && !"ERROR".equals(status)) {
                throw new AssertionError("task " + task + " ON ERROR STOP, whose procedure fails from its second run "
                        + "on, should stop: " + rows + " rows after 3 s, " + later + " after 4.5 s, @Statistics TASK "
                        + "state " + status);
            }
            STOPS.incrementAndGet();
        } finally {
            dropQuietly(task);
        }
    }

    // the table and its procedures, once per database name in the run
    private void create(String table, String db) {
        if (CREATED.contains(table)) {
            return;
        }
        ddl("CREATE TABLE " + table + " (k BIGINT NOT NULL, t VARCHAR(40) NOT NULL, ts TIMESTAMP); PARTITION TABLE "
                + table + " ON COLUMN k");
        // a row on the database (a multi-partition procedure), k from the time
        ddl("CREATE PROCEDURE XKP_" + db + " AS INSERT INTO " + table + " VALUES (SINCE_EPOCH(MICROS, NOW), ?, NOW)");
        // a row through k's partition
        ddl("CREATE PROCEDURE XKQ_" + db + " PARTITION ON TABLE " + table + " COLUMN k AS INSERT INTO " + table
                + " VALUES (?, ?, NOW)");
        // a replicated table with a primary key, and a procedure whose second run with the same k fails on it
        ddl("CREATE TABLE XKS_" + db + " (k BIGINT NOT NULL, t VARCHAR(40) NOT NULL, PRIMARY KEY (k))");
        ddl("CREATE PROCEDURE XKU_" + db + " AS INSERT INTO XKS_" + db + " VALUES (?, ?)");
        CREATED.add(table);
    }

    private int partitions() throws SQLException {
        lastQuery = "@GetPartitionKeys('INTEGER')";
        int n = 0;
        try (CallableStatement call = connection().prepareCall("{call @GetPartitionKeys(?)}")) {
            call.setString(1, "INTEGER");
            try (ResultSet rs = call.executeQuery()) {
                while (rs.next()) {
                    n++;
                }
            }
        }
        return n;
    }

    // the task's state in @Statistics TASK (one row per scheduler; the first), or null if it is not shown
    private String status(String task) throws SQLException {
        lastQuery = "@Statistics('TASK', 0)";
        try (CallableStatement call = connection().prepareCall("{call @Statistics(?, ?)}")) {
            call.setString(1, "TASK");
            call.setInt(2, 0);
            try (ResultSet rs = call.executeQuery()) {
                while (rs.next()) {
                    if (task.equalsIgnoreCase(rs.getString("TASK_NAME"))) {
                        return rs.getString("STATE");
                    }
                }
            }
        }
        return null;
    }

    // the times of the task's rows, for a report
    private String times(String table, String task) throws SQLException {
        String sql = "SELECT ts FROM " + table + " WHERE t = '" + task + "' ORDER BY ts";
        StringBuilder sb = new StringBuilder();
        try (Statement st = connection().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String t = String.valueOf(rs.getTimestamp(1));
                sb.append(sb.length() == 0 ? "" : ", ").append(t.length() > 11 ? t.substring(11) : t);
            }
        }
        return sb.toString();
    }

    private long count(String table, String task) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE t = '" + task + "'";
        lastQuery = sql;
        try (Statement st = connection().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IgnoreMeException();
        }
    }

    private VoltDBConnection connection() {
        return (VoltDBConnection) state.getConnection();
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

    private void dropQuietly(String task) {
        try (Statement st = state.getConnection().createStatement()) {
            st.execute("DROP TASK " + task + " IF EXISTS");
        } catch (SQLException | IgnoreMeException e) {
            // the next check uses another name
        }
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
