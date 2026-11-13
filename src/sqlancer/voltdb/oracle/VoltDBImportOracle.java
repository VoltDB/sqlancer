package sqlancer.voltdb.oracle;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.Reproducer;
import sqlancer.common.oracle.TestOracle;
import sqlancer.voltdb.VoltDBConnection;
import sqlancer.voltdb.VoltDBOptions;
import sqlancer.voltdb.VoltDBProvider.VoltDBGlobalState;

/**
 * The pull socket importer (milestone 41), which milestone 30's coverage run found never run, with the importer
 * framework's channel distribution ({@code ChannelDistributer}, 751 lines) and {@code ImportManager}. run-sqlancer.sh
 * configures the importer ({@code pullsocketimporter.jar}, CSV) to pull from {@code --import-ports} ports on this
 * machine into {@code XI.insert}; each port is a resource, which the cluster's nodes share out among themselves (and
 * again when one fails). This JVM listens on those ports, and each IMPORT check:
 * <ul>
 * <li>sends a few CSV lines, each with a key unique in the run and a value, through one port, to whichever node has
 * connected to it;</li>
 * <li>waits up to 60 s until {@code XI} holds those keys, with their values;</li>
 * <li>checks that {@code XI} holds no more rows than the run has sent.</li>
 * </ul>
 * The importer delivers at most once: a line in flight when a node fails is lost. So where nodes fail, resize, crash or
 * are upgraded, lines that never arrive are counted, not failed; everywhere else each must arrive.
 */
public class VoltDBImportOracle implements TestOracle<VoltDBGlobalState> {

    private static final long ARRIVE_MILLIS = 60_000;
    private static final AtomicLong KEYS = new AtomicLong();
    private static final AtomicLong SENT = new AtomicLong();
    private static final AtomicLong CHECKS = new AtomicLong();
    private static final AtomicLong LOST = new AtomicLong();
    private static volatile Feeder[] feeders;

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.out.println(
                "IMPORT checks: " + CHECKS + "; lines sent: " + SENT + "; lines lost to node failures: " + LOST)));
    }

    private final VoltDBGlobalState state;
    private String lastQuery;

    public VoltDBImportOracle(VoltDBGlobalState state) {
        this.state = state;
    }

    @Override
    public void check() throws Exception {
        VoltDBOptions o = state.getDbmsSpecificOptions();
        if (o.importPorts == null || o.importPorts.isBlank()) {
            throw new IgnoreMeException();
        }
        Feeder[] all = feeders(o.importPorts);
        createTable();
        Feeder feeder = all[(int) Randomly.getNotCachedInteger(0, all.length)];
        if (!feeder.connected()) {
            // no node has taken this port yet (just started, or its node failed and the resource moves)
            throw new IgnoreMeException();
        }
        Map<Long, String> sent = new TreeMap<>();
        StringBuilder lines = new StringBuilder();
        for (int i = (int) Randomly.getNotCachedInteger(1, 20); i > 0; i--) {
            long k = KEYS.incrementAndGet();
            String v = Randomly.fromOptions("a", "b", "c", "dd", "") + Randomly.getNotCachedInteger(0, 1000);
            sent.put(k, v);
            lines.append(k).append(',').append(v).append('\n');
        }
        SENT.addAndGet(sent.size());
        feeder.send(lines.toString());
        String keys = sent.keySet().toString().replace('[', '(').replace(']', ')');
        Map<Long, String> arrived = new TreeMap<>();
        for (long until = System.currentTimeMillis() + ARRIVE_MILLIS; System.currentTimeMillis() < until;) {
            arrived = rows("SELECT k, v FROM XI WHERE k IN " + keys);
            if (arrived.size() == sent.size()) {
                break;
            }
            Thread.sleep(500);
        }
        for (Map.Entry<Long, String> e : arrived.entrySet()) {
            if (!e.getValue().equals(sent.get(e.getKey()))) {
                throw new AssertionError("the importer put the line " + e.getKey() + "," + sent.get(e.getKey())
                        + " into XI as " + e.getKey() + "," + e.getValue());
            }
        }
        if (arrived.size() < sent.size()) {
            if (!(o.nodeFailures || o.resizes || o.crashes || o.upgrades)) {
                List<Long> missing = new ArrayList<>(sent.keySet());
                missing.removeAll(arrived.keySet());
                throw new AssertionError("the pull socket importer took " + sent.size() + " lines from port "
                        + feeder.port + ", and after " + ARRIVE_MILLIS / 1000 + " s XI lacks the keys " + missing);
            }
            LOST.addAndGet(sent.size() - arrived.size());
        }
        long total = count("SELECT COUNT(*) FROM XI");
        if (total > SENT.get()) {
            throw new AssertionError("XI holds " + total + " rows, more than the " + SENT.get() + " lines sent");
        }
        CHECKS.incrementAndGet();
    }

    private static synchronized Feeder[] feeders(String ports) throws IOException {
        if (feeders == null) {
            String[] range = ports.trim().split("-");
            int first = Integer.parseInt(range[0]);
            int last = range.length > 1 ? Integer.parseInt(range[1]) : first;
            Feeder[] made = new Feeder[last - first + 1];
            for (int p = first; p <= last; p++) {
                made[p - first] = new Feeder(p);
            }
            feeders = made;
        }
        return feeders;
    }

    private void createTable() {
        String sql = "CREATE TABLE XI (k BIGINT NOT NULL, v VARCHAR(16), PRIMARY KEY (k)); PARTITION TABLE XI ON COLUMN k";
        lastQuery = sql;
        try (ResultSet rs = connection().getMetaData().getTables(null, null, "XI", null)) {
            if (rs.next()) {
                return;
            }
        } catch (SQLException e) {
            throw new IgnoreMeException();
        }
        try (Statement st = connection().createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            if (!String.valueOf(e.getMessage()).contains("already exists")) {
                throw new AssertionError(sql, e);
            }
        }
    }

    // XI's rows, read through both clusters of an XDCR run: only the first imports (the peer's deployment has no
    // importer), and a database may work through either; XI is not a DR table, so the other's XI stays empty
    private Map<Long, String> rows(String sql) throws SQLException {
        lastQuery = sql;
        Map<Long, String> rows = new TreeMap<>();
        for (VoltDBConnection c : clusters()) {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    rows.put(rs.getLong(1), rs.getString(2) == null ? "" : rs.getString(2));
                }
            }
        }
        return rows;
    }

    private long count(String sql) throws SQLException {
        lastQuery = sql;
        long n = 0;
        for (VoltDBConnection c : clusters()) {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                n += rs.getLong(1);
            }
        }
        return n;
    }

    private List<VoltDBConnection> clusters() {
        List<VoltDBConnection> all = new ArrayList<>();
        all.add(connection());
        if (connection().getPeer() != null) {
            all.add(connection().getPeer());
        }
        return all;
    }

    private VoltDBConnection connection() {
        return (VoltDBConnection) state.getConnection();
    }

    /**
     * One port the importer pulls from: accepts the importer's connections (a node's, and another's after the resource
     * moves) and writes queued lines to the latest; a line whose write fails is dropped, as the importer may or may not
     * have read it.
     */
    private static final class Feeder {
        private final int port;
        private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        // open for the run: the importer's nodes reconnect to it whenever the resource moves
        private final ServerSocket server;
        private volatile Socket current;

        Feeder(int port) throws IOException {
            this.port = port;
            this.server = new ServerSocket(port, 8, InetAddress.getLoopbackAddress());
            Thread acceptor = new Thread(() -> {
                while (true) {
                    try {
                        Socket s = server.accept();
                        Socket old = current;
                        current = s;
                        if (old != null) {
                            old.close();
                        }
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "import-accept-" + port);
            acceptor.setDaemon(true);
            acceptor.start();
            Thread writer = new Thread(() -> {
                while (true) {
                    String lines;
                    try {
                        lines = queue.take();
                    } catch (InterruptedException e) {
                        return;
                    }
                    Socket s = current;
                    if (s == null) {
                        continue;
                    }
                    try {
                        OutputStream out = s.getOutputStream();
                        out.write(lines.getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } catch (IOException e) {
                        // the importer's node failed: these lines may be lost
                    }
                }
            }, "import-write-" + port);
            writer.setDaemon(true);
            writer.start();
        }

        boolean connected() {
            Socket s = current;
            return s != null && !s.isClosed();
        }

        void send(String lines) {
            queue.add(lines);
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
