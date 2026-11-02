package sqlancer.voltdb;

import sqlancer.OracleFactory;
import sqlancer.common.oracle.TestOracle;
import sqlancer.voltdb.VoltDBProvider.VoltDBGlobalState;
import sqlancer.voltdb.oracle.VoltDBSmokeOracle;

public enum VoltDBOracleFactory implements OracleFactory<VoltDBGlobalState> {

    /**
     * Reads every table of the database and checks only that the queries succeed. It exercises schema and data
     * generation without checking results.
     */
    SMOKE {
        @Override
        public TestOracle<VoltDBGlobalState> create(VoltDBGlobalState globalState) {
            return new VoltDBSmokeOracle(globalState);
        }
    }

}
