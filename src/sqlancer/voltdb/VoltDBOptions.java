package sqlancer.voltdb;

import java.util.List;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import sqlancer.DBMSSpecificOptions;

@Parameters(separators = "=", commandDescription = "VoltDB (default host: " + VoltDBOptions.DEFAULT_HOST
        + ", default port: " + VoltDBOptions.DEFAULT_PORT + ")")
public class VoltDBOptions implements DBMSSpecificOptions<VoltDBOracleFactory> {

    public static final String DEFAULT_HOST = "localhost";
    public static final int DEFAULT_PORT = 21212;

    @Parameter(names = "--oracle", description = "The test oracles to use")
    public List<VoltDBOracleFactory> oracles = List.of(VoltDBOracleFactory.NOREC);

    @Parameter(names = "--max-num-tables", description = "The maximum number of tables each database creates")
    public int maxNumTables = 3;

    @Parameter(names = "--max-num-indexes", description = "The maximum number of CREATE INDEX statements each database issues")
    public int maxNumIndexes = 4;

    @Parameter(names = "--max-num-updates", description = "The maximum number of UPDATE statements each database issues")
    public int maxNumUpdates = 5;

    @Parameter(names = "--max-num-deletes", description = "The maximum number of DELETE statements each database issues")
    public int maxNumDeletes = 2;

    @Parameter(names = "--max-num-views", description = "The maximum number of CREATE VIEW statements each database issues")
    public int maxNumViews = 2;

    @Parameter(names = "--retry-seconds", description = "How long a statement VoltDB rejects because of a concurrent schema change is retried")
    public int retrySeconds = 120;

    @Parameter(names = "--avoid-known-bugs", description = "Do not generate SQL that hits a known, unfixed VoltDB bug (see the README)", arity = 1)
    public boolean avoidKnownBugs = true;

    @Parameter(names = "--node-failures", description = "Nodes are killed and rejoined during the run (run-sqlancer.sh --kill-interval): expect and retry the errors that causes", arity = 1)
    public boolean nodeFailures;

    @Parameter(names = "--servers", description = "Comma-separated host:port client addresses of every node; SQLancer connects to all of them, so it keeps working while any one node is down (used instead of --host and --port)")
    public String servers;

    @Override
    public List<VoltDBOracleFactory> getTestOracleFactory() {
        return oracles;
    }

}
