package sqlancer.voltdb;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ThreadLocalRandom;

import sqlancer.SQLConnection;

/**
 * The provider's JDBC connection. Besides exposing metadata, which is how the provider reads a database's tables, it
 * works around two things that would otherwise fail runs with several threads:
 *
 * <ul>
 * <li>Statements VoltDB rejects because of a schema change on another thread are retried (see
 * {@link VoltDBErrors#isRetryable}). VoltDB rejects them before running them, so the retry is safe.</li>
 * <li>close() is idempotent. SQLancer closes each connection twice, and the VoltDB driver treats every close() as one
 * user leaving the client that all connections to the same URL share, so a second close() shuts that client down under
 * the other threads (ENG-30489).</li>
 * </ul>
 */
public class VoltDBConnection extends SQLConnection {

    private final Connection connection;
    private final long retryMillis;
    private boolean closed;

    public VoltDBConnection(Connection connection, int retrySeconds) {
        super(connection);
        this.connection = connection;
        this.retryMillis = retrySeconds * 1000L;
    }

    @Override
    public Statement createStatement() throws SQLException {
        return retrying(connection.createStatement(), Statement.class);
    }

    @Override
    public Statement prepareStatement(String sql) throws SQLException {
        return retrying(connection.prepareStatement(sql), PreparedStatement.class);
    }

    @Override
    public synchronized void close() throws SQLException {
        if (!closed) {
            closed = true;
            super.close();
        }
    }

    public DatabaseMetaData getMetaData() throws SQLException {
        return connection.getMetaData();
    }

    // wraps a statement so that its execute* methods retry while VoltDB rejects them as retryable
    private <T extends Statement> T retrying(T statement, Class<T> type) {
        return type
                .cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
                    long deadline = System.currentTimeMillis() + retryMillis;
                    while (true) {
                        try {
                            return method.invoke(statement, args);
                        } catch (InvocationTargetException e) {
                            Throwable cause = e.getCause();
                            if (method.getName().startsWith("execute") && cause instanceof SQLException
                                    && VoltDBErrors.isRetryable((SQLException) cause)
                                    && System.currentTimeMillis() < deadline) {
                                Thread.sleep(ThreadLocalRandom.current().nextLong(20, 100));
                                continue;
                            }
                            throw cause;
                        }
                    }
                }));
    }

}
