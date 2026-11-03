package sqlancer.voltdb;

import sqlancer.OracleFactory;
import sqlancer.common.oracle.NoRECOracle;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.voltdb.VoltDBProvider.VoltDBGlobalState;
import sqlancer.voltdb.gen.VoltDBExpressionGenerator;
import sqlancer.voltdb.oracle.VoltDBSmokeOracle;
import sqlancer.voltdb.oracle.VoltDBTLPAggregateOracle;
import sqlancer.voltdb.oracle.VoltDBTLPOracle;

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
            return new NoRECOracle<>(globalState, new VoltDBExpressionGenerator(globalState), expressionErrors());
        }
    },

    /**
     * Checks that a query returns the same rows as the union of the three queries that add WHERE p, WHERE NOT p and
     * WHERE p IS NULL for a random predicate p (Ternary Logic Partitioning).
     */
    TLP_WHERE {
        @Override
        public TestOracle<VoltDBGlobalState> create(VoltDBGlobalState globalState) {
            return new VoltDBTLPOracle(globalState, expressionErrors(), VoltDBTLPOracle.Mode.WHERE);
        }
    },

    /**
     * Checks that a SELECT DISTINCT, or a GROUP BY of all its columns, returns the rows of the three queries that add
     * WHERE p, WHERE NOT p and WHERE p IS NULL, without duplicates.
     */
    TLP_DISTINCT {
        @Override
        public TestOracle<VoltDBGlobalState> create(VoltDBGlobalState globalState) {
            return new VoltDBTLPOracle(globalState, expressionErrors(), VoltDBTLPOracle.Mode.DISTINCT);
        }
    },

    /**
     * Checks that a GROUP BY query returns the same rows as the three queries that add HAVING p, HAVING NOT p and
     * HAVING p IS NULL, for a random predicate p over the grouping columns and aggregates.
     */
    TLP_HAVING {
        @Override
        public TestOracle<VoltDBGlobalState> create(VoltDBGlobalState globalState) {
            return new VoltDBTLPOracle(globalState, expressionErrors(), VoltDBTLPOracle.Mode.HAVING);
        }
    },

    /**
     * Checks that MIN, MAX, SUM and COUNT over all rows equal the same aggregates over the three partitions WHERE p,
     * WHERE NOT p and WHERE p IS NULL, combined.
     */
    TLP_AGGREGATE {
        @Override
        public TestOracle<VoltDBGlobalState> create(VoltDBGlobalState globalState) {
            return new VoltDBTLPAggregateOracle(globalState, expressionErrors());
        }
    };

    private static ExpectedErrors expressionErrors() {
        ExpectedErrors errors = ExpectedErrors.newErrors().build();
        VoltDBErrors.addExpressionErrors(errors);
        return errors;
    }

}
