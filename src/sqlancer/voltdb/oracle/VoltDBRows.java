package sqlancer.voltdb.oracle;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import sqlancer.IgnoreMeException;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.common.query.SQLancerResultSet;
import sqlancer.voltdb.VoltDBProvider.VoltDBGlobalState;

// Reading the rows of a query, for the oracles that compare them
final class VoltDBRows {

    private VoltDBRows() {
    }

    // The rows of a query, each row its values. An expected error throws IgnoreMeException, an unexpected one
    // AssertionError.
    static List<List<Object>> values(VoltDBGlobalState state, String sql, ExpectedErrors errors, int columns)
            throws SQLException {
        SQLQueryAdapter q = new SQLQueryAdapter(sql, errors);
        List<List<Object>> rows = new ArrayList<>();
        try (SQLancerResultSet rs = q.executeAndGet(state)) {
            if (rs == null) {
                throw new IgnoreMeException();
            }
            while (rs.next()) {
                List<Object> row = new ArrayList<>();
                for (int i = 1; i <= columns; i++) {
                    row.add(rs.getObject(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    // The rows of a query, each its values joined with " | ": a NULL as null, a FLOAT exactly (with -0.0 as 0.0, which
    // equals it) and a VARBINARY in hexadecimal
    static List<String> strings(VoltDBGlobalState state, String sql, ExpectedErrors errors, int columns)
            throws SQLException {
        List<String> rows = new ArrayList<>();
        for (List<Object> row : values(state, sql, errors, columns)) {
            rows.add(format(row));
        }
        return rows;
    }

    static String format(List<Object> row) {
        List<String> values = new ArrayList<>();
        for (Object value : row) {
            values.add(format(value));
        }
        return String.join(" | ", values);
    }

    static String format(Object value) {
        if (value instanceof Double) {
            return Double.toString((Double) value + 0.0);
        }
        if (value instanceof byte[]) {
            StringBuilder sb = new StringBuilder("x'");
            for (byte b : (byte[]) value) {
                sb.append(String.format("%02X", b));
            }
            return sb.append('\'').toString();
        }
        return String.valueOf(value);
    }

    // the rows of a that b lacks, counting duplicates
    static List<String> difference(List<String> a, List<String> b) {
        List<String> rest = new ArrayList<>(a);
        for (String row : b) {
            rest.remove(row);
        }
        return rest;
    }
}
