package sqlancer.voltdb.oracle;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.voltdb.VoltTable;
import org.voltdb.VoltType;
import org.voltdb.client.Client2;
import org.voltdb.client.Client2Config;
import org.voltdb.client.Client2Impl;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.Reproducer;
import sqlancer.common.oracle.TestOracle;
import sqlancer.voltdb.VoltDBConnection;
import sqlancer.voltdb.VoltDBOptions;
import sqlancer.voltdb.VoltDBProvider.VoltDBGlobalState;

/**
 * Streaming snapshots (milestone 51), whose code (org.voltdb.streamingsnapshots, the client's Client2StreamingSnapshot)
 * the coverage runs had not reached: {@code Client2Impl.streamSnapshotAsync} asks the cluster to take a snapshot and
 * stream it to the client, each host sending its partitions' chunks of each table.
 * <p>
 * Each check uses two tables of its own, {@code XQP_<db>} (partitioned on k) and {@code XQR_<db>} (replicated), shaped
 * as LOAD's twins (see VoltDBBulkOracle). It fills them, reads them back, and streams a snapshot through a client of
 * its own connected to every node, asking for both tables, one, or every table but some (skipTables). The stream must
 * end; the rows of each table asked for must be the table's rows, no more and no fewer; no other table may send rows;
 * the catalog metadata must list the tables. Some checks then stream again under the same name, which must be refused
 * ("already exists"), or start a second stream from another client at once, which must be refused as already in
 * progress unless the first has ended; neither may change the other's rows.
 * <p>
 * A stream takes a snapshot to every node's snapshot directory; --snapshot-path is the directory holding the
 * voltdbroots, where the check finds the files to delete with {@code @SnapshotDelete}. Without it the oracle does
 * nothing. Streams and SNAPSHOT's saves take turns.
 * <p>
 * Known bugs avoided with --avoid-known-bugs: ENG-30720 (the stream completes before its last chunks are handed over,
 * which may then be dropped: the check waits up to 10 s for them, and streams again, up to three times in all, if rows
 * are still missing), ENG-30722 (a stream also sends the files of each snapshot whose name begins with its own: names
 * of a fixed width, deleted at once), ENG-30723 (a stream whose table lists match no table never ends: without the
 * option, some checks ask for a table that does not exist and expect a refusal) and ENG-30725 (a client that
 * disconnects after its stream ended breaks the stream in progress: the checks share two clients that stay connected).
 * ENG-30724 (a client keeps a request outstanding for 60 s after a stream, and close() waits for it) is worked around
 * in either case: clients are closed in the background.
 */
public class VoltDBStreamingSnapshotOracle implements TestOracle<VoltDBGlobalState> {

    private static final AtomicLong NONCES = new AtomicLong();
    private static final AtomicLong STREAMS = new AtomicLong();
    private static final AtomicLong ROWS = new AtomicLong();
    private static final AtomicLong CHUNKS = new AtomicLong();
    private static final AtomicLong WHOLE = new AtomicLong();
    private static final AtomicLong SKIPPING = new AtomicLong();
    private static final AtomicLong SAME_NAME = new AtomicLong();
    private static final AtomicLong CONCURRENT = new AtomicLong();
    private static final AtomicLong LATE = new AtomicLong();
    // streams short of rows after the wait (ENG-30720), streamed again
    private static final AtomicLong RETRIED = new AtomicLong();
    private static final AtomicLong UNKNOWN_TABLE = new AtomicLong();
    // streams given up, and why
    private static final Map<String, Long> SKIPS = new ConcurrentHashMap<>();
    private static final Set<String> CREATED = ConcurrentHashMap.newKeySet();
    // without --avoid-known-bugs, the snapshots kept for later streams to find (ENG-30722); deleted ten checks on
    private static final Deque<String> KEPT = new ArrayDeque<>();

    // ENG-30724: after a stream, a client keeps a request outstanding for 60 s, and close() waits for it; clients are
    // closed in the background
    private static final java.util.concurrent.ExecutorService CLOSER = java.util.concurrent.Executors
            .newCachedThreadPool(r -> {
                Thread t = new Thread(r, "STREAMSNAP close");
                t.setDaemon(true);
                return t;
            });

    // ENG-30725: a client that disconnects after its stream has ended deletes the participant nodes of the stream in
    // progress, whose state then blocks every later stream; while avoiding known bugs, the checks share two clients
    // that stay connected (replaced only after a failure), and without, each check connects clients of its own and
    // closes them
    private static final Client2[] SHARED = new Client2[2];

    static {
        Runtime.getRuntime()
                .addShutdownHook(new Thread(() -> System.out.println("STREAMSNAP streams checked: " + STREAMS + " ("
                        + ROWS + " rows in " + CHUNKS + " chunks; of every table " + WHOLE + ", with skipTables "
                        + SKIPPING + "); refusals checked: the same name " + SAME_NAME + ", a concurrent stream "
                        + CONCURRENT + ", an unknown table " + UNKNOWN_TABLE
                        + "; streams that handed over chunks after completing (ENG-30720): " + LATE
                        + "; streams short of rows and streamed again (ENG-30720): " + RETRIED + "; streams given up: "
                        + SKIPS)));
    }

    private final VoltDBGlobalState state;
    private String lastQuery;

    public VoltDBStreamingSnapshotOracle(VoltDBGlobalState state) {
        this.state = state;
    }

    // what one stream delivered
    private static final class Received {
        final Map<String, List<String>> rows = new ConcurrentHashMap<>();
        final AtomicLong chunks = new AtomicLong();
        final List<String> catalogTables = Collections.synchronizedList(new ArrayList<>());
        volatile boolean metadata;

        long rowCount() {
            long n = 0;
            for (List<String> l : rows.values()) {
                n += l.size();
            }
            return n;
        }
    }

    // a stream short of rows while avoiding known bugs (ENG-30720)
    private static final class ShortStream extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ShortStream(String message) {
            super(message);
        }
    }

    @Override
    public void check() throws Exception {
        String dir = state.getDbmsSpecificOptions().snapshotPath;
        if (dir == null || dir.isBlank()) {
            throw new IgnoreMeException();
        }
        // in an XDCR run the writes go through either cluster, and reach the other later
        if (((VoltDBConnection) state.getConnection()).getPeer() != null) {
            throw new IgnoreMeException();
        }
        // one snapshot at a time (a save waiting for a stream, or a stream for a save, is queued; SNAPSHOT counts on
        // its saves being taken at once)
        synchronized (VoltDBSnapshotOracle.LOCK) {
            checkAlone(dir);
        }
    }

    private void checkAlone(String dir) throws Exception {
        String db = state.getDatabaseName().toUpperCase(Locale.ROOT);
        String p = "XQP_" + db;
        String r = "XQR_" + db;
        create(p, true);
        create(r, false);
        fill(p);
        fill(r);
        Map<String, List<String>> expected = new TreeMap<>();
        VoltDBOptions o = state.getDbmsSpecificOptions();
        try (Closing c = client(0)) {
            Client2 client = c.client;
            expected.put(p, rows(client, p));
            expected.put(r, rows(client, r));
            // what to ask for: both tables, one of them, or every table of the cluster but some
            String tables;
            String skip = null;
            Set<String> wanted = new java.util.TreeSet<>();
            switch ((int) Randomly.getNotCachedInteger(0, 5)) {
            case 0:
                tables = p;
                wanted.add(p);
                break;
            case 1:
                tables = r;
                wanted.add(r);
                break;
            case 2:
                // every table, but ours: with skipTables naming one of ours
                tables = null;
                skip = Randomly.getBoolean() ? p : r;
                wanted.add(skip.equals(p) ? r : p);
                SKIPPING.incrementAndGet();
                WHOLE.incrementAndGet();
                break;
            case 3:
                // both, and one skipped
                tables = p + "," + r;
                skip = Randomly.getBoolean() ? p : r;
                wanted.add(skip.equals(p) ? r : p);
                SKIPPING.incrementAndGet();
                break;
            default:
                tables = Randomly.getBoolean() ? p + "," + r : r.toLowerCase(Locale.ROOT) + "," + p;
                wanted.add(p);
                wanted.add(r);
                break;
            }
            String nonce = nonce();
            try {
                // ENG-30720: a stream's last chunks can be dropped for good once it has completed; while avoiding
                // known bugs, a stream still short of rows after the wait is streamed again, up to three times in all
                for (int attempt = 1;; attempt++) {
                    Received got = new Received();
                    String failed = stream(client, nonce, tables, skip, got, null);
                    if (failed != null) {
                        skipOrFail("a stream of " + describe(tables, skip), failed);
                    }
                    STREAMS.incrementAndGet();
                    try {
                        compare(got, expected, wanted, tables, skip, nonce);
                        break;
                    } catch (ShortStream e) {
                        if (attempt == 3) {
                            throw new AssertionError(e.getMessage() + " (the third stream in a row short of rows)");
                        }
                        RETRIED.incrementAndGet();
                        forget(dir, nonce);
                        nonce = nonce();
                    }
                }
                if (Randomly.getBooleanWithRatherLowProbability()) {
                    sameName(nonce, tables, skip);
                }
                if (Randomly.getBooleanWithRatherLowProbability()) {
                    concurrent(dir, expected, wanted, tables, skip);
                }
                if (!o.avoidKnownBugs && Randomly.getBooleanWithRatherLowProbability()) {
                    unknownTable(db);
                }
                c.keep();
            } finally {
                forget(dir, nonce);
            }
        }
    }

    // the check of what one stream delivered
    private void compare(Received got, Map<String, List<String>> expected, Set<String> wanted, String tables,
            String skip, String nonce) throws InterruptedException {
        VoltDBOptions o = state.getDbmsSpecificOptions();
        long wantedRows = 0;
        for (String t : wanted) {
            wantedRows += expected.get(t).size();
        }
        // ENG-30720: a stream completes before its last chunks are handed over; while avoiding known bugs, they are
        // waited for (10 s); without, the rows must be there when the stream completes
        long atCompletion = got.rowCount();
        if (o.avoidKnownBugs) {
            for (long until = System.currentTimeMillis() + 10_000; System.currentTimeMillis() < until
                    && rowsOf(got, wanted) < wantedRows;) {
                Thread.sleep(100);
            }
            // and for chunks that should not come at all
            Thread.sleep(200);
            if (got.rowCount() > atCompletion) {
                LATE.incrementAndGet();
            }
        }
        String what = "the streaming snapshot " + nonce + " of " + describe(tables, skip);
        if (o.avoidKnownBugs && rowsOf(got, wanted) < wantedRows) {
            throw new ShortStream(what + " holds " + rowsOf(got, wanted) + " of the " + wantedRows
                    + " rows of the tables asked for, 10 s after it completed");
        }
        lastQuery = what;
        if (!got.metadata) {
            throw new AssertionError(what + " completed without its catalog metadata");
        }
        for (String t : wanted) {
            if (!got.catalogTables.contains(t)) {
                throw new AssertionError(
                        what + ": its catalog metadata does not list " + t + "; it lists " + got.catalogTables);
            }
            List<String> now = new ArrayList<>(got.rows.getOrDefault(t, List.of()));
            Collections.sort(now);
            List<String> exp = expected.get(t);
            if (!now.equals(exp)) {
                throw new AssertionError(what + " should hold the " + exp.size() + " rows of " + t + "; it holds "
                        + now.size()
                        + (got.rowCount() > atCompletion
                                ? " (" + atCompletion + " rows of every table handed over when it completed)" : "")
                        + "; only in the stream: " + VoltDBRows.difference(now, exp) + "; only in the table: "
                        + VoltDBRows.difference(exp, now));
            }
        }
        // no other table of ours, and with a table list, no table outside it
        for (String t : got.rows.keySet()) {
            boolean ours = expected.containsKey(t);
            if (ours && !wanted.contains(t) || !ours && tables != null) {
                throw new AssertionError(
                        what + " streamed " + got.rows.get(t).size() + " rows of " + t + ", which it did not ask for");
            }
        }
        ROWS.addAndGet(wantedRows);
        CHUNKS.addAndGet(got.chunks.get());
    }

    private static long rowsOf(Received got, Set<String> tables) {
        long n = 0;
        for (String t : tables) {
            n += got.rows.getOrDefault(t, List.of()).size();
        }
        return n;
    }

    // a second stream under a name already taken must be refused
    private void sameName(String nonce, String tables, String skip) throws Exception {
        try (Closing c = client(1)) {
            Client2 client = c.client;
            Received got = new Received();
            String failed = stream(client, nonce, tables, skip, got, null);
            lastQuery = "a second streaming snapshot named " + nonce + " of " + describe(tables, skip);
            if (failed == null) {
                throw new AssertionError(
                        lastQuery + " should be refused (the name is taken); it streamed " + got.rowCount() + " rows");
            }
            if (!failed.contains("already exists")) {
                skipOrFail(lastQuery, failed);
            }
            SAME_NAME.incrementAndGet();
            c.keep();
        }
    }

    // two streams at once, from two clients: each must stream the tables' rows or be refused as one already in
    // progress (the second refused unless the first ended first)
    private void concurrent(String dir, Map<String, List<String>> expected, Set<String> wanted, String tables,
            String skip) throws Exception {
        String first = nonce();
        String second = nonce();
        Received a = new Received();
        Received b = new Received();
        try (Closing c1 = client(0); Closing c2 = client(1)) {
            Client2 one = c1.client;
            Client2 two = c2.client;
            CompletableFuture<String> fa = CompletableFuture
                    .supplyAsync(() -> stream(one, first, tables, skip, a, null));
            CompletableFuture<String> fb = CompletableFuture
                    .supplyAsync(() -> stream(two, second, tables, skip, b, null));
            String ra = fa.get(300, TimeUnit.SECONDS);
            String rb = fb.get(300, TimeUnit.SECONDS);
            lastQuery = "two streaming snapshots at once, " + first + " and " + second + ", of "
                    + describe(tables, skip);
            if (ra != null && rb != null) {
                if (!inProgress(ra) && !inProgress(rb)) {
                    skipOrFail(lastQuery, ra + "; " + rb);
                }
                throw new AssertionError(lastQuery + ": both were refused: " + ra + "; " + rb);
            }
            for (String why : new String[] { ra, rb }) {
                if (why != null && !inProgress(why)) {
                    skipOrFail(lastQuery, why);
                }
            }
            try {
                if (ra == null) {
                    compare(a, expected, wanted, tables, skip, first);
                }
                if (rb == null) {
                    compare(b, expected, wanted, tables, skip, second);
                }
            } catch (ShortStream e) {
                RETRIED.incrementAndGet();
                throw new IgnoreMeException();
            }
            CONCURRENT.incrementAndGet();
            c1.keep();
            c2.keep();
        } finally {
            forget(dir, first);
            forget(dir, second);
        }
    }

    private static boolean inProgress(String why) {
        return why.contains("already in progress") || why.contains("was already created");
    }

    // ENG-30723: a stream of a table that does not exist must be refused (it never ends)
    private void unknownTable(String db) throws Exception {
        try (Closing c = client(-1)) {
            Client2 client = c.client;
            String table = "XQ_NO_SUCH_" + db;
            Received got = new Received();
            String failed = stream(client, nonce(), table, null, got, 60);
            lastQuery = "a streaming snapshot of " + table + ", which does not exist";
            if (failed != null && failed.startsWith("no answer")) {
                throw new AssertionError(lastQuery + " should be refused; it neither ended nor failed in 60 s");
            }
            UNKNOWN_TABLE.incrementAndGet();
        }
    }

    // null if the stream completed, else why not; TIMEOUT seconds (300 if null) for it to end
    private String stream(Client2 client, String nonce, String tables, String skip, Received got, Integer timeout) {
        CompletableFuture<Void> f;
        try {
            f = ((Client2Impl) client).streamSnapshotAsync(null, nonce, tables, skip, metadata -> {
                got.metadata = true;
                got.catalogTables.addAll(catalogTables(metadata));
            }, (table, partition, chunk, buffer) -> take(got, table, buffer));
        } catch (RuntimeException e) {
            return String.valueOf(e.getMessage());
        }
        // a stream a node failure broke may never end: while nodes fail, it is given up after 120 s
        VoltDBOptions o = state.getDbmsSpecificOptions();
        int limit = timeout != null ? timeout : o.nodeFailures || o.resizes || o.crashes || o.upgrades ? 120 : 300;
        try {
            f.get(limit, TimeUnit.SECONDS);
            return null;
        } catch (TimeoutException e) {
            return "no answer in " + limit + " s";
        } catch (ExecutionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            return String.valueOf(c.getMessage()).replace('\n', ' ');
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IgnoreMeException();
        }
    }

    private static void take(Received got, String table, ByteBuffer buffer) {
        got.chunks.incrementAndGet();
        VoltTable t = VoltTable.fromBuffer(buffer, true);
        List<String> rows = got.rows.computeIfAbsent(table, x -> Collections.synchronizedList(new ArrayList<>()));
        while (t.advanceRow()) {
            rows.add(row(t));
        }
    }

    // the TABLES part of the catalog metadata: the TABLE_NAME of each row of the table its DATA holds
    private static List<String> catalogTables(VoltTable metadata) {
        List<String> names = new ArrayList<>();
        metadata.resetRowPosition();
        while (metadata.advanceRow()) {
            if ("TABLES".equalsIgnoreCase(metadata.getString("metadataType"))) {
                byte[] data = metadata.getVarbinary("data");
                if (data != null && data.length > 0) {
                    VoltTable t = VoltTable.fromBuffer(ByteBuffer.wrap(data), true);
                    while (t.advanceRow()) {
                        names.add(t.getString("TABLE_NAME"));
                    }
                }
            }
        }
        metadata.resetRowPosition();
        return names;
    }

    // a row as text, from a VoltTable: the table's rows, read with the same client, compare to the stream's
    private static String row(VoltTable t) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < t.getColumnCount(); i++) {
            VoltType type = t.getColumnType(i);
            Object v = t.get(i, type);
            if (t.wasNull()) {
                v = null;
            } else if (v instanceof BigDecimal) {
                v = ((BigDecimal) v).toPlainString();
            }
            values.add(VoltDBRows.format(v));
        }
        return String.join(" | ", values);
    }

    private List<String> rows(Client2 client, String table) throws Exception {
        String sql = VoltDBBulkOracle.SELECT + table;
        lastQuery = sql;
        ClientResponse resp;
        try {
            resp = client.callProcedureSync("@AdHoc", sql);
        } catch (Exception e) {
            throw new IgnoreMeException();
        }
        List<String> rows = new ArrayList<>();
        VoltTable t = resp.getResults()[0];
        while (t.advanceRow()) {
            rows.add(row(t));
        }
        Collections.sort(rows);
        return rows;
    }

    // why a stream was not checked: a node failure's or another snapshot's fault is skipped; anything else, while the
    // cluster runs undisturbed, is a failure
    private void skipOrFail(String what, String why) {
        VoltDBOptions o = state.getDbmsSpecificOptions();
        boolean disturbed = o.nodeFailures || o.resizes || o.crashes || o.upgrades;
        String w = why.length() > 70 ? why.substring(0, 70) : why;
        if (disturbed || why.contains("SNAPSHOT IN PROGRESS") || why.contains("already in progress")
                || why.contains("was already created")) {
            SKIPS.merge(w, 1L, Long::sum);
            throw new IgnoreMeException();
        }
        throw new AssertionError(what + " failed: " + why);
    }

    private static String describe(String tables, String skip) {
        return (tables == null ? "every table" : tables) + (skip == null ? "" : " but " + skip);
    }

    // a name no other snapshot's begins with (ENG-30722): fixed width, and an end letter; without --avoid-known-bugs,
    // the plain counter, so that s1's name begins s10's
    private String nonce() {
        long n = NONCES.incrementAndGet();
        return state.getDbmsSpecificOptions().avoidKnownBugs ? String.format("sqlstream%09dx", n) : "sqlstream" + n;
    }

    // delete the stream's snapshot: at once while avoiding known bugs, else ten checks later
    private void forget(String dir, String nonce) {
        List<String> gone = new ArrayList<>();
        if (state.getDbmsSpecificOptions().avoidKnownBugs) {
            gone.add(nonce);
        } else {
            synchronized (KEPT) {
                KEPT.add(nonce);
                while (KEPT.size() > 10) {
                    gone.add(KEPT.poll());
                }
            }
        }
        for (String n : gone) {
            VoltDBSnapshotOracle.deleteSnapshot(connection(), dir, n);
        }
    }

    private final class Closing implements AutoCloseable {
        final Client2 client;
        final int slot;
        boolean ok;

        Closing(Client2 client, int slot) {
            this.client = client;
            this.slot = slot;
        }

        // the client worked: a shared one is kept
        void keep() {
            ok = true;
        }

        @Override
        public void close() {
            if (slot >= 0) {
                synchronized (SHARED) {
                    if (ok) {
                        return;
                    }
                    if (SHARED[slot] == client) {
                        SHARED[slot] = null;
                    }
                }
            }
            CLOSER.execute(() -> {
                try {
                    client.close();
                } catch (Exception e) {
                    // closed anyway
                }
            });
        }
    }

    // the client of SLOT (0 or 1), shared while avoiding known bugs; one of its own for -1, or without the option
    private Closing client(int slot) {
        int s = state.getDbmsSpecificOptions().avoidKnownBugs ? slot : -1;
        if (s >= 0) {
            synchronized (SHARED) {
                if (SHARED[s] == null) {
                    SHARED[s] = connect();
                }
                return new Closing(SHARED[s], s);
            }
        }
        return new Closing(connect(), -1);
    }

    // a client connected to every node (a stream needs a connection to each host leading a partition)
    private Client2 connect() {
        Client2Config config = new Client2Config();
        String user = state.getOptions().getUserName();
        if (state.getDbmsSpecificOptions().security && user != null && !user.isEmpty()) {
            config.username(user).password(state.getOptions().getPassword());
        }
        String truststore = state.getDbmsSpecificOptions().sslTruststore;
        if (truststore != null && !truststore.isBlank()) {
            config.trustStore(truststore.trim(), "sqlancer").enableSSL();
        }
        Client2 client = ClientFactory.createClient(config);
        String servers = state.getDbmsSpecificOptions().servers;
        List<String> all = new ArrayList<>();
        if (servers == null || servers.isBlank()) {
            all.add(state.getOptions().getHost() + ":" + state.getOptions().getPort());
        } else {
            for (String s : servers.split(",")) {
                all.add(s.trim());
            }
        }
        int connected = 0;
        for (String s : all) {
            try {
                client.connectSync(s);
                connected++;
            } catch (Exception e) {
                // a node down; the stream fails if it leads a partition
            }
        }
        if (connected == 0) {
            CLOSER.execute(client::close);
            throw new IgnoreMeException();
        }
        return client;
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
                throw new IgnoreMeException();
            }
        }
        CREATED.add(table);
    }

    // empty the table and fill it: usually a few rows, sometimes none, sometimes a few hundred (several chunks)
    private void fill(String table) {
        write("DELETE FROM " + table);
        int n = Randomly.getBooleanWithRatherLowProbability() ? 0 : Randomly.getBooleanWithRatherLowProbability()
                ? (int) Randomly.getNotCachedInteger(100, 400) : (int) Randomly.getNotCachedInteger(1, 30);
        for (int i = 0; i < n; i++) {
            tryWrite("INSERT INTO " + table + " VALUES "
                    + VoltDBBulkOracle.literal(VoltDBBulkOracle.row((int) Randomly.getNotCachedInteger(0, 40))));
        }
    }

    private void write(String sql) {
        if (!tryWrite(sql)) {
            throw new IgnoreMeException();
        }
    }

    // false if the statement broke a constraint
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
