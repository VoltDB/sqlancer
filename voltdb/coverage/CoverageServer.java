import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.runtime.RemoteControlReader;
import org.jacoco.core.runtime.RemoteControlWriter;

/**
 * Collects JaCoCo execution data from every JVM started with the agent option output=tcpclient, while they run.
 * A JVM killed with kill -9 never writes its data at exit, so every INTERVAL seconds this asks each connected JVM
 * for a dump and rewrites EXEC_FILE with everything collected so far: a killed JVM loses at most the last interval.
 *
 * <p>
 * Usage: java CoverageServer PORT EXEC_FILE [INTERVAL_SECONDS (default 10)]. It runs until it is killed (SIGTERM),
 * when it writes EXEC_FILE once more.
 */
public final class CoverageServer {

    private final ExecutionDataStore data = new ExecutionDataStore();
    private final SessionInfoStore sessions = new SessionInfoStore();
    private final List<RemoteControlWriter> clients = new CopyOnWriteArrayList<>();
    private final File execFile;

    private CoverageServer(File execFile) {
        this.execFile = execFile;
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("Usage: CoverageServer PORT EXEC_FILE [INTERVAL_SECONDS]");
            System.exit(2);
        }
        int port = Integer.parseInt(args[0]);
        long interval = args.length > 2 ? Long.parseLong(args[2]) : 10;
        CoverageServer server = new CoverageServer(new File(args[1]));
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        timer.scheduleWithFixedDelay(server::dumpAll, interval, interval, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(server::write));
        try (ServerSocket listener = new ServerSocket(port, 50, InetAddress.getLoopbackAddress())) {
            System.out.println("CoverageServer listening on " + port);
            while (true) {
                Socket socket = listener.accept();
                Thread t = new Thread(() -> server.serve(socket));
                t.setDaemon(true);
                t.start();
            }
        }
    }

    private void serve(Socket socket) {
        RemoteControlWriter writer = null;
        try (Socket s = socket) {
            writer = new RemoteControlWriter(s.getOutputStream());
            RemoteControlReader reader = new RemoteControlReader(s.getInputStream());
            reader.setSessionInfoVisitor(info -> {
                synchronized (this) {
                    sessions.visitSessionInfo(info);
                }
            });
            reader.setExecutionDataVisitor(d -> {
                synchronized (this) {
                    data.put(d);
                }
            });
            clients.add(writer);
            // reads dumps until the JVM goes away; its final dump (at a normal exit) arrives here too
            while (reader.read()) {
            }
        } catch (IOException e) {
            // the JVM exited or was killed
        } finally {
            if (writer != null) {
                clients.remove(writer);
            }
        }
    }

    private void dumpAll() {
        for (RemoteControlWriter w : clients) {
            try {
                synchronized (w) {
                    w.visitDumpCommand(true, false);
                }
            } catch (IOException e) {
                clients.remove(w);
            }
        }
        write();
    }

    private synchronized void write() {
        File tmp = new File(execFile.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            ExecutionDataWriter writer = new ExecutionDataWriter(out);
            sessions.accept(writer);
            data.accept(writer);
        } catch (IOException e) {
            System.err.println("CoverageServer: cannot write " + tmp + ": " + e);
            return;
        }
        try {
            Files.move(tmp.toPath(), execFile.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("CoverageServer: cannot replace " + execFile + ": " + e);
        }
    }
}
