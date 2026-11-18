package sqlancer.voltdb.oracle;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
import sqlancer.voltdb.VoltDBOptions;
import sqlancer.voltdb.VoltDBProvider.VoltDBGlobalState;

/**
 * The metrics system (milestone 52), which milestone 51's coverage found never enabled: {@code org.voltdb.metrics} (the
 * recorders, the collector, the Prometheus exporter) and {@code @Metrics}. run-sqlancer.sh enables it
 * ({@code <metrics enabled="true" interval="1s"/>}) and passes every node's metrics port as --metrics-servers.
 * <p>
 * Each check uses objects of its own: a partitioned table {@code XMP_<db>}, a replicated table {@code XMR_<db>}, and
 * four procedures on them: {@code XMI_<db>} inserts into XMP (single-partition write), {@code XMG_<db>} reads a row of
 * XMP (single-partition read), {@code XMS_<db>} counts XMP's rows (multi-partition read) and {@code XMU_<db>} inserts
 * into XMR (multi-partition write). A check reads every node's {@code /metrics}, calls each procedure a few times (some
 * inserts break the primary key and fail), waits two collection intervals and reads them again. Then:
 * <ul>
 * <li>{@code voltdb_procedure_invoked_total}, summed over the cluster, must have grown by the calls made, failed ones
 * included: a single-partition write by the calls times k+1 (every replica runs it), the others by the calls (a
 * single-partition read runs on the partition's leader, a multi-partition procedure on the MP coordinator);</li>
 * <li>for each of the procedures, {@code @Statistics PROCEDURE}'s invocations must equal the metric's;</li>
 * <li>{@code voltdb_table_tuple_total} of XMP, summed, must be its rows times k+1, and each site's for XMR must be
 * XMR's rows; {@code voltdb_index_entry_count_total} of their primary key indexes the same;</li>
 * <li>{@code @Metrics} (the host the connection reaches) must report the same tuple counts for that host;</li>
 * <li>some checks drop and create XMS again: until it is called, no node may report it, and then its count
 * restarts.</li>
 * </ul>
 * A catalog change starts every procedure's counts afresh, so a check holds off DDL (VoltDBConnection.withoutDdl) and
 * the configuration and class changes of CONFIG, LOGIN and JAVAPROC. Counts also start afresh on a node that rejoins,
 * so in runs where nodes fail or the cluster changes, METRICS does nothing; nor in XDCR runs, where the peer's changes
 * arrive as more invocations.
 */
public class VoltDBMetricsOracle implements TestOracle<VoltDBGlobalState> {

    private static final AtomicLong CHECKS = new AtomicLong();
    private static final AtomicLong CALLS = new AtomicLong();
    private static final AtomicLong SERIES = new AtomicLong();
    private static final AtomicLong RECREATED = new AtomicLong();
    private static final AtomicLong SYSPROC = new AtomicLong();
    // the longest the metrics took to agree after a check's calls, in ms
    private static final AtomicLong LAG = new AtomicLong();
    private static final Map<String, Long> SKIPS = new ConcurrentHashMap<>();
    private static final Set<String> CREATED = ConcurrentHashMap.newKeySet();
    private static final Map<String, AtomicLong> KEYS = new ConcurrentHashMap<>();
    // a metric line: name{labels} value [timestamp]
    private static final Pattern LINE = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)\\{(.*)\\}\\s+(\\S+)(\\s+\\S+)?$");
    private static final Pattern LABEL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    static {
        Runtime.getRuntime()
                .addShutdownHook(new Thread(() -> System.out.println("METRICS checks: " + CHECKS
                        + "; procedure calls counted: " + CALLS + "; series compared: " + SERIES
                        + "; procedures dropped and created again: " + RECREATED + "; @Metrics compared: " + SYSPROC
                        + "; longest wait for the metrics to agree: " + LAG + " ms; checks given up: " + SKIPS)));
    }

    private final VoltDBGlobalState state;
    private String lastQuery;

    public VoltDBMetricsOracle(VoltDBGlobalState state) {
        this.state = state;
    }

    // one series: its labels and value
    private static final class Sample {
        final Map<String, String> labels;
        final double value;
        final String timestamp;

        Sample(Map<String, String> labels, double value, String timestamp) {
            this.labels = labels;
            this.value = value;
            this.timestamp = timestamp;
        }

        @Override
        public String toString() {
            return "host " + labels.get("host_id") + " site " + labels.get("site_id") + " partition "
                    + labels.get("partition_id") + ": " + (long) value + " at " + timestamp;
        }
    }

    // what a check's calls should leave: each procedure's calls and its expected growth, the tables' rows
    private static final class Expectation {
        final Map<String, Long> growth = new TreeMap<>();
        final Map<String, Integer> calls = new TreeMap<>();
        List<Sample> before;
        String recreated;
        int k;
        String p;
        String r;
        long rowsP;
        long rowsR;
    }

    @Override
    public void check() throws Exception {
        VoltDBOptions o = state.getDbmsSpecificOptions();
        if (o.metricsServers == null || o.metricsServers.isBlank()) {
            throw new IgnoreMeException();
        }
        if (o.nodeFailures || o.resizes || o.crashes || o.upgrades
                || ((VoltDBConnection) state.getConnection()).getPeer() != null) {
            throw new IgnoreMeException();
        }
        synchronized (VoltDBConfigOracle.LOCK) {
            VoltDBConnection.withoutDdl(() -> {
                checkAlone();
                return null;
            });
        }
    }

    private void checkAlone() throws Exception {
        String db = state.getDatabaseName().toUpperCase(Locale.ROOT);
        String p = "XMP_" + db;
        String r = "XMR_" + db;
        String insert = "XMI_" + db;
        String get = "XMG_" + db;
        String count = "XMS_" + db;
        String replicated = "XMU_" + db;
        if (CREATED.add(db)) {
            ddl("CREATE TABLE " + p + " (k INTEGER NOT NULL, v VARCHAR(16), PRIMARY KEY (k)); PARTITION TABLE " + p
                    + " ON COLUMN k; CREATE TABLE " + r + " (k INTEGER NOT NULL, PRIMARY KEY (k)); CREATE PROCEDURE "
                    + insert + " PARTITION ON TABLE " + p + " COLUMN k AS INSERT INTO " + p
                    + " VALUES (?, ?); CREATE PROCEDURE " + get + " PARTITION ON TABLE " + p
                    + " COLUMN k AS SELECT v FROM " + p + " WHERE k = ?; CREATE PROCEDURE " + count
                    + " AS SELECT COUNT(*) FROM " + p + "; CREATE PROCEDURE " + replicated + " AS INSERT INTO " + r
                    + " VALUES (?)");
        }
        boolean recreate = Randomly.getBooleanWithRatherLowProbability();
        if (recreate) {
            ddl("DROP PROCEDURE " + count);
            ddl("CREATE PROCEDURE " + count + " AS SELECT COUNT(*) FROM " + p);
        }
        int k = kfactor();
        // the counts as they stand once the last change has been collected: two scrapes alike, 1.2 s apart
        List<Sample> before = settled(p, r, insert, get, count, replicated);
        if (recreate) {
            for (Sample s : select(before, "voltdb_procedure_invoked_total", "procedure_name", count)) {
                throw new AssertionError(
                        "a node still reports " + count + ", dropped and created again but not yet called: " + s);
            }
        }
        AtomicLong keys = KEYS.computeIfAbsent(db, x -> new AtomicLong());
        int inserts = (int) Randomly.getNotCachedInteger(1, 12);
        int gets = (int) Randomly.getNotCachedInteger(0, 6);
        int counts = (int) Randomly.getNotCachedInteger(recreate ? 1 : 0, 4);
        int replicatedInserts = (int) Randomly.getNotCachedInteger(0, 5);
        for (int i = 0; i < inserts; i++) {
            // one in five breaks the primary key, and fails; it still counts as a call
            long key = keys.get() > 0 && Randomly.getBooleanWithRatherLowProbability()
                    ? Randomly.getNotCachedInteger(1, (int) keys.get() + 1) : keys.incrementAndGet();
            call(insert, key, "v" + key);
        }
        for (int i = 0; i < gets; i++) {
            call(get, Randomly.getNotCachedInteger(1, (int) keys.get() + 2));
        }
        for (int i = 0; i < counts; i++) {
            call(count);
        }
        for (int i = 0; i < replicatedInserts; i++) {
            long key = Randomly.getBooleanWithRatherLowProbability() ? 1 : keys.incrementAndGet();
            call(replicated, key);
        }
        CALLS.addAndGet(inserts + gets + counts + replicatedInserts);
        Map<String, Long> expected = new TreeMap<>();
        expected.put(insert, (long) inserts * (k + 1));
        expected.put(get, (long) gets);
        expected.put(count, (long) counts);
        expected.put(replicated, (long) replicatedInserts);
        Expectation x = new Expectation();
        x.growth.putAll(expected);
        x.calls.put(insert, inserts);
        x.calls.put(get, gets);
        x.calls.put(count, counts);
        x.calls.put(replicated, replicatedInserts);
        x.before = before;
        x.recreated = recreate ? count : null;
        x.k = k;
        x.p = p;
        x.r = r;
        x.rowsP = scalar("SELECT COUNT(*) FROM " + p);
        x.rowsR = scalar("SELECT COUNT(*) FROM " + r);
        // the metrics are collected every second, each site's in turn, and reach /metrics some seconds later: they
        // must come to agree within 30 s
        long start = System.currentTimeMillis();
        String why;
        List<Sample> after;
        do {
            Thread.sleep(1000);
            after = scrape();
            why = differences(x, after);
        } while (why != null && System.currentTimeMillis() - start < 30_000);
        if (why != null) {
            throw new AssertionError(why + " (still so 30 s after the calls)");
        }
        LAG.accumulateAndGet(System.currentTimeMillis() - start, Math::max);
        for (String metric : new String[] { "voltdb_table_tuple_total", "voltdb_index_entry_count_total" }) {
            SERIES.addAndGet(
                    select(after, metric, "table_name", p).size() + select(after, metric, "table_name", r).size());
        }
        compareStatistics(after, expected.keySet());
        compareSysproc(p);
        if (recreate) {
            RECREATED.incrementAndGet();
        }
        CHECKS.incrementAndGet();
    }

    // the scrape once the objects' series stop changing: two alike, 1.2 s apart (up to 20 s)
    private List<Sample> settled(String... names) throws InterruptedException {
        List<Sample> last = scrape();
        for (long until = System.currentTimeMillis() + 20_000; System.currentTimeMillis() < until;) {
            Thread.sleep(1200);
            List<Sample> now = scrape();
            if (values(last, names).equals(values(now, names))) {
                return now;
            }
            last = now;
        }
        skip("the metrics did not settle in 20 s");
        return last;
    }

    // the values of the series that name one of NAMES, by series
    private static Map<String, Double> values(List<Sample> samples, String... names) {
        Map<String, Double> v = new TreeMap<>();
        for (Sample s : samples) {
            for (String n : names) {
                if (n.equals(s.labels.get("procedure_name")) || n.equals(s.labels.get("table_name"))) {
                    v.put(new TreeMap<>(s.labels).toString(), s.value);
                }
            }
        }
        return v;
    }

    // what differs from the expected counts, or null
    private static String differences(Expectation x, List<Sample> after) {
        for (Map.Entry<String, Long> e : x.growth.entrySet()) {
            // a procedure dropped and created again starts at 0
            double was = e.getKey().equals(x.recreated) ? 0
                    : sum(select(x.before, "voltdb_procedure_invoked_total", "procedure_name", e.getKey()));
            List<Sample> now = select(after, "voltdb_procedure_invoked_total", "procedure_name", e.getKey());
            if (sum(now) - was != e.getValue()) {
                return "voltdb_procedure_invoked_total of " + e.getKey()
                        + ", summed over every node's /metrics, went from " + (long) was + " to " + (long) sum(now)
                        + " over " + x.calls.get(e.getKey()) + " calls; it should have grown by " + e.getValue()
                        + " (k=" + x.k + "); by site: " + now;
            }
        }
        for (String metric : new String[] { "voltdb_table_tuple_total", "voltdb_index_entry_count_total" }) {
            List<Sample> ps = select(after, metric, "table_name", x.p);
            if (sum(ps) != x.rowsP * (x.k + 1)) {
                return metric + " of " + x.p + ", summed over every node's /metrics, is " + (long) sum(ps)
                        + "; the table has " + x.rowsP + " rows, which with k=" + x.k + " should make "
                        + x.rowsP * (x.k + 1) + "; by site: " + ps;
            }
            List<Sample> rs = select(after, metric, "table_name", x.r);
            if (rs.isEmpty()) {
                return "no node's /metrics reports " + metric + " of " + x.r;
            }
            for (Sample s : rs) {
                if (s.value != x.rowsR) {
                    return metric + " of the replicated table " + x.r + " is " + s + "; the table has " + x.rowsR
                            + " rows, which every site holds";
                }
            }
        }
        return null;
    }

    // @Statistics PROCEDURE's invocations of each procedure, summed, must equal voltdb_procedure_invoked_total's
    private void compareStatistics(List<Sample> after, Set<String> procs) {
        Map<String, Long> stats = new HashMap<>();
        lastQuery = "@Statistics('PROCEDURE', 0)";
        try (CallableStatement call = connection().prepareCall("{call @Statistics(?, ?)}")) {
            call.setString(1, "PROCEDURE");
            call.setInt(2, 0);
            try (ResultSet rs = call.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("PROCEDURE");
                    // the name is the procedure's, possibly with a statement after it
                    if (name != null && procs.contains(name)) {
                        stats.merge(name, rs.getLong("INVOCATIONS"), Long::sum);
                    }
                }
            }
        } catch (SQLException e) {
            skip("@Statistics: " + e.getMessage());
        }
        for (String proc : procs) {
            long metric = (long) sum(select(after, "voltdb_procedure_invoked_total", "procedure_name", proc));
            long stat = stats.getOrDefault(proc, 0L);
            if (metric != stat) {
                throw new AssertionError(
                        "voltdb_procedure_invoked_total of " + proc + ", summed over every node's /metrics, is "
                                + metric + "; @Statistics PROCEDURE's invocations of it sum to " + stat);
            }
        }
    }

    // @Metrics answers for the host the connection reaches: its TABLE_TUPLE rows of TABLE must be that host's
    // voltdb_table_tuple_total series
    private void compareSysproc(String table) throws InterruptedException {
        // the gauges are stable by now (no writes since); @Metrics and /metrics may read different collections, so a
        // difference must last 10 s
        String why = null;
        for (long until = System.currentTimeMillis() + 10_000; System.currentTimeMillis() < until;) {
            why = sysprocDifference(scrape(), table);
            if (why == null) {
                SYSPROC.incrementAndGet();
                return;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError(why);
    }

    private String sysprocDifference(List<Sample> after, String table) {
        Map<String, Long> fromSysproc = new TreeMap<>();
        String host = null;
        lastQuery = "@Metrics()";
        try (CallableStatement call = connection().prepareCall("{call @Metrics}")) {
            boolean more = call.execute();
            while (more) {
                try (ResultSet rs = call.getResultSet()) {
                    java.sql.ResultSetMetaData md = rs.getMetaData();
                    boolean tables = false;
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        tables |= "TABLE_NAME".equals(md.getColumnName(i));
                    }
                    while (tables && rs.next()) {
                        if ("TABLE_TUPLE".equals(rs.getString("NAME")) && table.equals(rs.getString("TABLE_NAME"))) {
                            host = rs.getString("HOST_ID");
                            fromSysproc.put(rs.getString("SITE_ID") + "/" + rs.getString("PARTITION_ID"),
                                    rs.getLong("VALUE"));
                        }
                    }
                }
                more = call.getMoreResults();
            }
        } catch (SQLException e) {
            skip("@Metrics: " + e.getMessage());
        }
        if (host == null) {
            return null;
        }
        Map<String, Long> fromHttp = new TreeMap<>();
        for (Sample s : select(after, "voltdb_table_tuple_total", "table_name", table)) {
            if (host.equals(s.labels.get("host_id"))) {
                fromHttp.put(s.labels.get("site_id") + "/" + s.labels.get("partition_id"), (long) s.value);
            }
        }
        if (!fromSysproc.equals(fromHttp)) {
            return "@Metrics reports TABLE_TUPLE of " + table + " on host " + host + " by site/partition as "
                    + fromSysproc + "; that host's /metrics reports " + fromHttp;
        }
        return null;
    }

    // every node's /metrics, parsed
    private List<Sample> scrape() {
        List<Sample> all = new ArrayList<>();
        for (String server : state.getDbmsSpecificOptions().metricsServers.split(",")) {
            String url = "http://" + server.trim() + "/metrics";
            lastQuery = "GET " + url;
            String body;
            try {
                HttpResponse<String> resp = HTTP.send(
                        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    throw new AssertionError("GET " + url + " answered " + resp.statusCode());
                }
                body = resp.body();
            } catch (java.io.IOException e) {
                skip("GET /metrics: " + e.getMessage());
                return all;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IgnoreMeException();
            }
            for (String line : body.split("\n")) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                Matcher m = LINE.matcher(line.trim());
                if (!m.matches()) {
                    continue;
                }
                Map<String, String> labels = new HashMap<>();
                labels.put("__name__", m.group(1));
                Matcher l = LABEL.matcher(m.group(2));
                while (l.find()) {
                    labels.put(l.group(1), l.group(2));
                }
                double value;
                try {
                    value = Double.parseDouble(m.group(3));
                } catch (NumberFormatException e) {
                    throw new AssertionError("GET " + url + " returned a value that is not a number: " + line, e);
                }
                all.add(new Sample(labels, value, m.group(4) == null ? "-" : m.group(4).trim()));
            }
        }
        return all;
    }

    private static List<Sample> select(List<Sample> samples, String metric, String label, String value) {
        List<Sample> out = new ArrayList<>();
        for (Sample s : samples) {
            if (metric.equals(s.labels.get("__name__")) && value.equals(s.labels.get(label))) {
                out.add(s);
            }
        }
        return out;
    }

    private static double sum(List<Sample> samples) {
        double total = 0;
        for (Sample s : samples) {
            total += s.value;
        }
        return total;
    }

    // a procedure call; a primary key violation is one of the calls that fail on purpose
    private void call(String proc, Object... params) {
        // no parentheses for no parameters: the driver takes "{call P()}" for an empty query
        StringBuilder sql = new StringBuilder("{call ").append(proc);
        for (int i = 0; i < params.length; i++) {
            sql.append(i == 0 ? "(?" : ", ?");
        }
        sql.append(params.length == 0 ? "}" : ")}");
        lastQuery = proc + java.util.Arrays.toString(params);
        try (CallableStatement call = connection().prepareCall(sql.toString())) {
            for (int i = 0; i < params.length; i++) {
                call.setObject(i + 1, params[i]);
            }
            call.execute();
        } catch (SQLException e) {
            if (!String.valueOf(e.getMessage()).contains("CONSTRAINT VIOLATION")) {
                skip(proc + ": " + e.getMessage());
            }
        }
    }

    private long scalar(String sql) throws SQLException {
        lastQuery = sql;
        try (Statement st = connection().createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // the cluster's k-safety, from @SystemInformation DEPLOYMENT
    private int kfactor() {
        lastQuery = "@SystemInformation('DEPLOYMENT')";
        try (CallableStatement call = connection().prepareCall("{call @SystemInformation(?)}")) {
            call.setString(1, "DEPLOYMENT");
            try (ResultSet rs = call.executeQuery()) {
                while (rs.next()) {
                    if ("kfactor".equalsIgnoreCase(rs.getString(1))) {
                        return Integer.parseInt(rs.getString(2).trim());
                    }
                }
            }
        } catch (SQLException | NumberFormatException e) {
            skip("@SystemInformation: " + e.getMessage());
        }
        skip("@SystemInformation DEPLOYMENT has no kfactor");
        return 0;
    }

    private void ddl(String sql) {
        lastQuery = sql;
        try (Statement st = state.getConnection().createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            if (!String.valueOf(e.getMessage()).contains("already exists")) {
                CREATED.remove(state.getDatabaseName().toUpperCase(Locale.ROOT));
                skip("DDL: " + e.getMessage());
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
