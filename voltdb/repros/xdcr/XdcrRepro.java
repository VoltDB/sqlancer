import java.nio.file.*;
import java.sql.*;
import java.util.*;

// XdcrRepro PORT_A PORT_B CLUSTER_DIR ITERATIONS [partitioned]: replays one statement sequence (SEQ, or the lines
// "A: sql" / "B: sql" of $SEQ_FILE, %s standing for the table), alternating between the two XDCR
// clusters, on a fresh DR table each iteration; after it settles, reports iterations where the clusters differ and
// the conflict log has no D (diverged) row for the table.
public class XdcrRepro {
    // ENG-30679: the shortest sequence found that diverges the clusters with every conflict logged as consistent
    static final String[][] SEQ = {
        {"A", "INSERT INTO %s VALUES (0, 'a0', 3)"},
        {"B", "UPSERT INTO %s VALUES (0, 'b1', 2)"},
        {"B", "DELETE FROM %s WHERE k = 0"},
        {"B", "DELETE FROM %s WHERE k = 0"},
    };

    public static void main(String[] a) throws Exception {
        Class.forName("org.voltdb.jdbc.Driver");
        int n = Integer.parseInt(a[3]);
        String[][] seq = SEQ;
        if (System.getenv("SEQ_FILE") != null) {
            List<String[]> l = new ArrayList<>();
            for (String line : Files.readAllLines(Paths.get(System.getenv("SEQ_FILE")))) {
                if (!line.isBlank()) l.add(new String[] {line.substring(0, 1), line.substring(3)});
            }
            seq = l.toArray(new String[0][]);
        }
        boolean replicated = a.length < 5 || !a[4].equals("partitioned");
        try (Connection ca = DriverManager.getConnection("jdbc:voltdb://localhost:" + a[0]);
             Connection cb = DriverManager.getConnection("jdbc:voltdb://localhost:" + a[1])) {
            int silent = 0, differ = 0;
            for (int i = 0; i < n; i++) {
                String t = "XR" + System.getenv().getOrDefault("TAG", "") + i;
                for (Connection c : new Connection[] {ca, cb}) {
                    try (Statement s = c.createStatement()) {
                        s.execute("CREATE TABLE " + t + " (k INTEGER NOT NULL, v VARCHAR(8), n INTEGER NOT NULL, PRIMARY KEY (k))"
                                + (replicated ? "" : "; PARTITION TABLE " + t + " ON COLUMN k"));
                        s.execute("DR TABLE " + t);
                    }
                }
                Thread.sleep(2000);
                for (String[] st : seq) {
                    if (st[0].equals("S")) {
                        // "S: millis": wait, as for replication
                        Thread.sleep(Long.parseLong(st[1].trim()));
                        continue;
                    }
                    Connection c = st[0].equals("A") ? ca : cb;
                    try (Statement s = c.createStatement()) {
                        s.execute(String.format(st[1], t));
                    } catch (SQLException e) {
                        // a constraint violation is part of the test
                    }
                }
                Thread.sleep(15000);
                String ra = rows(ca, t), rb = rows(cb, t);
                if (!ra.equals(rb)) {
                    differ++;
                    boolean d = divergenceLogged(a[2], t);
                    if (!d) silent++;
                    System.out.println("iteration " + i + ": A " + ra + " B " + rb + (d ? " (D logged)" : " (no D logged)"));
                }
            }
            System.out.println(n + " iterations, " + differ + " left the clusters different, " + silent
                    + " of them with no divergence logged");
            // exit 0 for the bug (a divergence the conflict log does not report), 1 for none
            System.exit(silent > 0 ? 0 : 1);
        }
    }

    static String rows(Connection c, String t) throws SQLException {
        StringBuilder sb = new StringBuilder("{");
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT k, v, n FROM " + t + " ORDER BY k")) {
            while (rs.next()) sb.append(rs.getInt(1)).append('=').append(rs.getString(2)).append('|').append(rs.getInt(3)).append(' ');
        }
        return sb.append('}').toString();
    }

    static boolean divergenceLogged(String clusterDir, String table) throws Exception {
        Thread.sleep(5000);
        try (DirectoryStream<Path> nodes = Files.newDirectoryStream(Paths.get(clusterDir))) {
            for (Path node : nodes) {
                Path logs = node.resolve("voltdbroot").resolve("xdcr_conflicts");
                if (!Files.isDirectory(logs)) continue;
                try (DirectoryStream<Path> fs = Files.newDirectoryStream(logs, "*.csv")) {
                    for (Path f : fs) {
                        for (String line : Files.readAllLines(f)) {
                            if (line.contains("\"D\",\"" + table + "\"")) return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
