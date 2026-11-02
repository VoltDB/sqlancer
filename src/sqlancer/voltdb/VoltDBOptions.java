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

    @Parameter(names = "--retry-seconds", description = "How long a statement VoltDB rejects because of a concurrent schema change is retried")
    public int retrySeconds = 120;

    @Parameter(names = "--avoid-known-bugs", description = "Do not generate SQL that hits a known, unfixed VoltDB bug (see the README)", arity = 1)
    public boolean avoidKnownBugs = true;

    @Override
    public List<VoltDBOracleFactory> getTestOracleFactory() {
        return oracles;
    }

}
