package sqlancer.voltdb.oracle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.oracle.TestOracleUtils;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLancerResultSet;
import sqlancer.voltdb.VoltDBProvider.VoltDBGlobalState;
import sqlancer.voltdb.ast.VoltDBExpression;
import sqlancer.voltdb.ast.VoltDBSelect;
import sqlancer.voltdb.gen.VoltDBExpressionGenerator;

/**
 * Ternary Logic Partitioning of the WHERE clause. A query without a WHERE clause must return the same rows as the three
 * queries that filter with a random predicate p, with NOT p, and with p IS NULL together, since every row makes p
 * exactly one of TRUE, FALSE or NULL.
 *
 * <p>
 * It differs from the shared {@code TLPWhereOracle} in two ways that VoltDB needs or allows:
 * <ul>
 * <li>The three partitions are combined with UNION ALL only when the query reads replicated tables alone. VoltDB
 * rejects a set operation over partitioned tables ("Statements are too complex in set"), so for those the three are run
 * one after another.</li>
 * <li>Rows are compared whole, as multisets, on every fetched column rather than on the first one.</li>
 * </ul>
 */
public class VoltDBTLPWhereOracle implements TestOracle<VoltDBGlobalState> {

    private final VoltDBGlobalState state;
    private final VoltDBExpressionGenerator gen;
    private final ExpectedErrors errors;
    private String lastQuery;

    public VoltDBTLPWhereOracle(VoltDBGlobalState state, ExpectedErrors errors) {
        this.state = state;
        this.gen = new VoltDBExpressionGenerator(state);
        this.errors = errors;
    }

    @Override
    public void check() throws Exception {
        gen.setTablesAndColumns(TestOracleUtils.getRandomTableNonEmptyTables(state.getSchema()));
        VoltDBSelect select = gen.generateSelect();
        List<VoltDBExpression> fetchColumns = gen.generateFetchColumns();
        select.setFetchColumns(fetchColumns);
        select.setFromList(gen.getTableRefs());
        select.setJoinClauses(gen.getRandomJoinClauses());
        String originalQuery = select.asString();
        lastQuery = originalQuery;
        List<String> original = rows(originalQuery, fetchColumns.size());

        VoltDBExpression predicate = gen.generateBooleanExpression();
        // ORDER BY changes the plan of each partition but not its rows
        if (Randomly.getBooleanWithSmallProbability()) {
            select.setOrderByClauses(gen.generateOrderBys());
        }
        List<String> partitionQueries = new ArrayList<>();
        for (VoltDBExpression where : List.of(predicate, gen.negatePredicate(predicate), gen.isNull(predicate))) {
            select.setWhereClause(where);
            partitionQueries.add(select.asString());
        }
        List<String> combined = new ArrayList<>();
        String combinedQuery;
        if (gen.isReplicatedOnly() && select.getOrderByClauses().isEmpty()) {
            combinedQuery = String.join(" UNION ALL ", partitionQueries);
            combined.addAll(rows(combinedQuery, fetchColumns.size()));
        } else {
            combinedQuery = String.join("; ", partitionQueries);
            for (String q : partitionQueries) {
                combined.addAll(rows(q, fetchColumns.size()));
            }
        }
        lastQuery = combinedQuery;

        Collections.sort(original);
        Collections.sort(combined);
        if (!original.equals(combined)) {
            List<String> missing = difference(original, combined);
            List<String> extra = difference(combined, original);
            // one line each: the log escapes line breaks inside an entry
            for (String line : List.of(
                    "-- On the database set up by the statements above, the rows of these queries mismatch:",
                    "-- original (" + original.size() + " rows): " + originalQuery + ";",
                    "-- partitions (" + combined.size() + " rows): " + combinedQuery + ";",
                    "-- rows only in the original: " + missing, "-- rows only in the partitions: " + extra)) {
                state.getState().getLocalState().log(line);
            }
            throw new AssertionError("TLP WHERE mismatch: " + original.size() + " rows vs " + combined.size());
        }
    }

    // the rows of a that b lacks, counting duplicates
    private static List<String> difference(List<String> a, List<String> b) {
        List<String> rest = new ArrayList<>(a);
        for (String s : b) {
            rest.remove(s);
        }
        return rest;
    }

    // each row as its values joined with " | ", a NULL as NULL
    private List<String> rows(String sql, int columns) throws Exception {
        SQLQueryAdapter q = new SQLQueryAdapter(sql, errors);
        List<String> rows = new ArrayList<>();
        try (SQLancerResultSet rs = q.executeAndGet(state)) {
            if (rs == null) {
                throw new IgnoreMeException();
            }
            while (rs.next()) {
                List<String> values = new ArrayList<>();
                for (int i = 1; i <= columns; i++) {
                    values.add(String.valueOf(rs.getString(i)));
                }
                rows.add(String.join(" | ", values));
            }
        }
        return rows;
    }

    @Override
    public String getLastQueryString() {
        return lastQuery;
    }

}
