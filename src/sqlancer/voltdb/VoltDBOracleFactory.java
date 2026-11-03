package sqlancer.voltdb;

import sqlancer.OracleFactory;
import sqlancer.common.oracle.NoRECOracle;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.voltdb.VoltDBProvider.VoltDBGlobalState;
import sqlancer.voltdb.gen.VoltDBExpressionGenerator;
import sqlancer.voltdb.oracle.VoltDBSmokeOracle;
import sqlancer.voltdb.oracle.VoltDBTLPWhereOracle;

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
    },

    /**
     * Compares the row count of a query that filters with a random predicate in its WHERE clause with the count of an
     * equivalent query that evaluates the predicate per row in its projection, which the planner cannot optimize.
     */
    NOREC {
        @Override
        public TestOracle<VoltDBGlobalState> create(VoltDBGlobalState globalState) {
            ExpectedErrors errors = ExpectedErrors.newErrors().build();
            VoltDBErrors.addExpressionErrors(errors);
            return new NoRECOracle<>(globalState, new VoltDBExpressionGenerator(globalState), errors);
        }
    },

    /**
     * Checks that a query returns the same rows as the union of the three queries that add WHERE p, WHERE NOT p and
     * WHERE p IS NULL for a random predicate p (Ternary Logic Partitioning).
     */
    TLP_WHERE {
        @Override
        public TestOracle<VoltDBGlobalState> create(VoltDBGlobalState globalState) {
            ExpectedErrors errors = ExpectedErrors.newErrors().build();
            VoltDBErrors.addExpressionErrors(errors);
            return new VoltDBTLPWhereOracle(globalState, errors);
        }
    }

}
