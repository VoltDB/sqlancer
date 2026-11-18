package sqlancer.voltdb.oracle;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.Reproducer;
import sqlancer.common.oracle.TestOracle;
import sqlancer.voltdb.VoltDBConnection;
import sqlancer.voltdb.VoltDBProvider;
import sqlancer.voltdb.VoltDBProvider.VoltDBGlobalState;

/**
 * Reloading TLS certificates while the cluster runs (milestone 54): {@code @ReloadSslContext} (voltadmin ssl reload)
 * and SslSetup's reload path (computeStoreChecksum, updateConfiguration), which nothing ran before.
 * <p>
 * In a run with TLS (run-sqlancer.sh --ssl), the keystore and truststore are files in the cluster's tls directory,
 * beside the truststore --ssl-truststore names, and every node reads them. A TLSRELOAD check, one at a time:
 * <ol>
 * <li>calls {@code @ReloadSslContext(0)} with no store changed since the last load, which must answer STATUS 1 ("No
 * updated TLS/SSL store found");</li>
 * <li>rotates the certificate: makes a new key pair and self-signed certificate for localhost (keytool), adds the
 * certificate to the truststore (so that the nodes and SQLancer trust the old and the new), replaces the keystore with
 * the new one, and calls {@code @ReloadSslContext(0)}, which must answer STATUS 0. Then a TLS handshake with every
 * node's client port must present the new certificate, the connection SQLancer already has must still work, and a new
 * one must connect;</li>
 * <li>without --avoid-known-bugs, also replaces the keystore with bytes that are no keystore and reloads: the answer
 * must be STATUS 2 ("reload failed") and the nodes must go on presenting the certificate they had (ENG-30745: the node
 * crashes instead). The good keystore is then put back.</li>
 * </ol>
 * The truststore keeps the certificates of the last three rotations. Files are written whole and renamed into place, so
 * a connection opened meanwhile never reads half a store. Without --ssl-truststore TLSRELOAD does nothing.
 */
public class VoltDBTlsReloadOracle implements TestOracle<VoltDBGlobalState> {

    private static final Object LOCK = new Object();
    private static final AtomicLong CHECKS = new AtomicLong();
    private static final AtomicLong ROTATIONS = new AtomicLong();
    private static final AtomicLong HANDSHAKES = new AtomicLong();
    private static final AtomicLong REFUSED = new AtomicLong();
    private static final AtomicLong BAD_STORES = new AtomicLong();
    private static final Map<String, Long> SKIPS = new ConcurrentHashMap<>();
    // the truststore aliases this oracle added, oldest first
    private static final List<String> ALIASES = new ArrayList<>();
    private static final String PASSWORD = "sqlancer";

    static {
        Runtime.getRuntime().addShutdownHook(
                new Thread(() -> System.out.println("TLSRELOAD checks: " + CHECKS + "; certificates rotated: "
                        + ROTATIONS + "; handshakes checked: " + HANDSHAKES + "; reloads refused with no change: "
                        + REFUSED + "; bad keystores refused: " + BAD_STORES + "; checks given up: " + SKIPS)));
    }

    private final VoltDBGlobalState state;
    private String lastQuery;

    public VoltDBTlsReloadOracle(VoltDBGlobalState state) {
        this.state = state;
    }

    @Override
    public void check() throws Exception {
        String truststore = state.getDbmsSpecificOptions().sslTruststore;
        if (truststore == null || truststore.isBlank()) {
            throw new IgnoreMeException();
        }
        synchronized (LOCK) {
            checkAlone(Paths.get(truststore.trim()));
        }
    }

    private void checkAlone(Path truststore) throws Exception {
        Path dir = truststore.getParent();
        Path keystore = dir.resolve("keystore.jks");
        // 1. nothing changed: refused
        long[] status = reload();
        if (status[0] != 1) {
            throw new AssertionError(
                    "@ReloadSslContext(0) with no TLS/SSL store changed since the last load answered STATUS "
                            + status[0] + "; it should refuse (STATUS 1, \"No updated TLS/SSL store found\")");
        }
        REFUSED.incrementAndGet();
        // 2. a new certificate
        Path work = Files.createTempDirectory(dir, "rotate");
        try {
            String alias = "r" + System.currentTimeMillis();
            Path newStore = work.resolve("keystore.jks");
            Path cert = work.resolve("new.cer");
            keytool("-genkeypair", "-alias", "voltdb", "-keyalg", "RSA", "-keysize", "2048", "-validity", "30",
                    "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-keystore",
                    newStore.toString(), "-storetype", "JKS", "-storepass", PASSWORD, "-keypass", PASSWORD);
            keytool("-exportcert", "-alias", "voltdb", "-keystore", newStore.toString(), "-storepass", PASSWORD,
                    "-file", cert.toString());
            // the truststore first, whole and renamed into place: the nodes and SQLancer trust both certificates
            Path trustCopy = work.resolve("truststore.jks");
            Files.copy(truststore, trustCopy);
            keytool("-importcert", "-noprompt", "-alias", alias, "-file", cert.toString(), "-keystore",
                    trustCopy.toString(), "-storetype", "JKS", "-storepass", PASSWORD);
            synchronized (ALIASES) {
                ALIASES.add(alias);
                while (ALIASES.size() > 3) {
                    keytool("-delete", "-alias", ALIASES.remove(0), "-keystore", trustCopy.toString(), "-storepass",
                            PASSWORD);
                }
            }
            Files.move(trustCopy, truststore, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            String expected = fingerprint(certificate(newStore));
            // a modification time later than the last load's, at a granularity of a second
            Thread.sleep(1100);
            Path keyCopy = work.resolve("keystore.jks.next");
            Files.copy(newStore, keyCopy);
            Files.move(keyCopy, keystore, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            status = reload();
            if (status[0] != 0) {
                throw new AssertionError("@ReloadSslContext(0) after a new keystore replaced the old answered STATUS "
                        + status[0] + "; it should reload (STATUS 0)");
            }
            ROTATIONS.incrementAndGet();
            served(expected, "after the reload of a new certificate");
            stillWorks();
            // 3. a keystore that cannot be loaded (ENG-30745 crashes the node instead of answering STATUS 2)
            if (!state.getDbmsSpecificOptions().avoidKnownBugs && Randomly.getBooleanWithRatherLowProbability()) {
                Path good = work.resolve("good.jks");
                Files.copy(keystore, good);
                Thread.sleep(1100);
                Path bad = work.resolve("bad.jks");
                Files.write(bad, ("not a keystore " + System.nanoTime()).repeat(100).getBytes(StandardCharsets.UTF_8));
                Files.move(bad, keystore, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                try {
                    status = reload();
                } finally {
                    Files.move(good, keystore, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                }
                if (status[0] != 2) {
                    throw new AssertionError(
                            "@ReloadSslContext(0) with a keystore file that is no keystore answered STATUS " + status[0]
                                    + "; it should answer STATUS 2 (reload failed) and keep its TLS context");
                }
                served(expected, "after a reload of a bad keystore failed");
                BAD_STORES.incrementAndGet();
                // the good keystore, put back, is newer than the last load: loaded again, so that the next check's
                // first reload finds nothing changed
                Thread.sleep(1100);
                reload();
            }
            CHECKS.incrementAndGet();
        } finally {
            deleteTree(work);
        }
    }

    // @ReloadSslContext(0): its STATUS
    private long[] reload() {
        lastQuery = "@ReloadSslContext(0)";
        try (CallableStatement call = connection().prepareCall("{call @ReloadSslContext(?)}")) {
            call.setInt(1, 0);
            try (ResultSet rs = call.executeQuery()) {
                if (!rs.next()) {
                    throw new AssertionError(lastQuery + " returned no row");
                }
                lastQuery += " -> STATUS " + rs.getLong(1) + " " + rs.getString(2);
                return new long[] { rs.getLong(1) };
            }
        } catch (SQLException e) {
            String m = String.valueOf(e.getMessage());
            if (m.contains("Connection") || m.contains("connect")) {
                // a reload that took the node down (ENG-30745) loses the connection
                throw new AssertionError(lastQuery + " got no answer: " + m.split("\n")[0], e);
            }
            skip("@ReloadSslContext: " + m);
            return new long[] { -1 };
        }
    }

    // a TLS handshake with every node's client port must present the certificate EXPECTED
    private void served(String expected, String when) throws Exception {
        for (String server : servers()) {
            String[] hp = server.split(":");
            String got = handshake(hp[0], Integer.parseInt(hp[1]));
            HANDSHAKES.incrementAndGet();
            if (!expected.equals(got)) {
                throw new AssertionError("a TLS handshake with " + server + " " + when + " presents the certificate "
                        + got + "; the keystore's is " + expected);
            }
        }
    }

    // the connection SQLancer has, and a new one, must both run a query
    private void stillWorks() throws SQLException {
        lastQuery = "@Ping on the existing connection";
        try (CallableStatement call = connection().prepareCall("{call @Ping}")) {
            call.execute();
        } catch (SQLException e) {
            throw new AssertionError(
                    "the connection SQLancer opened before the certificate was rotated fails @Ping: " + e.getMessage(),
                    e);
        }
        String server = servers().get(0);
        lastQuery = "@Ping on a new connection to " + server;
        try (Connection c = DriverManager.getConnection("jdbc:voltdb://" + server,
                VoltDBProvider.connectionProperties(state.getDbmsSpecificOptions(), state.getOptions().getUserName(),
                        state.getOptions().getPassword(), true));
                CallableStatement call = c.prepareCall("{call @Ping}")) {
            call.execute();
        } catch (SQLException e) {
            throw new AssertionError("a new TLS connection after the certificate was rotated fails: " + e.getMessage(),
                    e);
        }
    }

    private List<String> servers() {
        String servers = state.getDbmsSpecificOptions().servers;
        List<String> all = new ArrayList<>();
        if (servers == null || servers.isBlank()) {
            all.add(state.getOptions().getHost() + ":" + state.getOptions().getPort());
        } else {
            for (String s : servers.split(",")) {
                all.add(s.trim());
            }
        }
        return all;
    }

    // the SHA-256 of the certificate HOST:PORT presents, trusting any
    private static String handshake(String host, int port) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] { new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        } }, null);
        try (SSLSocket socket = (SSLSocket) ctx.getSocketFactory().createSocket(host, port)) {
            socket.setSoTimeout(10_000);
            socket.startHandshake();
            return fingerprint(socket.getSession().getPeerCertificates()[0]);
        }
    }

    private static Certificate certificate(Path keystore) throws Exception {
        KeyStore ks = KeyStore.getInstance("JKS");
        try (InputStream in = Files.newInputStream(keystore)) {
            ks.load(in, PASSWORD.toCharArray());
        }
        return ks.getCertificate("voltdb");
    }

    private static String fingerprint(Certificate cert) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(cert.getEncoded())) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // keytool of the JRE SQLancer runs on
    private void keytool(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Paths.get(System.getProperty("java.home"), "bin", "keytool").toString());
        cmd.addAll(List.of(args));
        lastQuery = String.join(" ", cmd);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            skip("keytool " + args[0] + ": " + out.trim());
        }
    }

    private static void deleteTree(Path dir) {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // left behind in the run's tls directory
                }
            });
        } catch (IOException e) {
            // left behind
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
