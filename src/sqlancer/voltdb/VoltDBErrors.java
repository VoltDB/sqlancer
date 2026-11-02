package sqlancer.voltdb;

import java.sql.SQLException;

import sqlancer.common.query.ExpectedErrors;

/**
 * Errors VoltDB raises for statements that are invalid by design of the generator. Each entry names the reason the
 * generator cannot avoid it, so that no whole error class is suppressed without one.
 */
public final class VoltDBErrors {

    /**
     * Rejections caused by a schema change on another thread, which {@link VoltDBConnection} retries so they are never
     * reported. VoltDB runs one catalog update (DDL statement) at a time and rejects a concurrent one; and it aborts an
     * ad hoc statement that was planned before a catalog update committed. Both are rejected before they run.
     */
    private static final String[] RETRYABLE = { "while another one is in progress",
            "was planned against a catalog version which does not match" };

    private VoltDBErrors() {
    }

    public static boolean isRetryable(SQLException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        for (String r : RETRYABLE) {
            if (message.contains(r)) {
                return true;
            }
        }
        return false;
    }

    public static void addExpressionErrors(ExpectedErrors errors) {
        // VoltDB does not support a join whose WHERE clause folds to a filter on none of the joined tables; a
        // predicate that references a column can still fold to a constant, e.g. (FALSE AND t.c) = FALSE
        errors.add("Join with filters that do not depend on joined tables is not supported");
        // run-time arithmetic errors: random operands divide by zero and overflow, and the two NoREC queries may
        // evaluate an expression on different rows, so only one of them may hit the error
        errors.add("Attempted to divide"); // integer or DECIMAL division by zero
        errors.add("Invalid result value"); // FLOAT result is infinite or NaN
        errors.add("will overflow BigInt storage"); // integer +, -, * out of range
        errors.add("causing overflow/underflow"); // DECIMAL out of range
        errors.add("out of range for the destination type"); // a huge DECIMAL or FLOAT result cast to an integer
    }

    public static void addInsertErrors(ExpectedErrors errors) {
        // a generated key can repeat an existing primary key or unique value
        errors.add("CONSTRAINT VIOLATION");
    }

    public static void addIndexErrors(ExpectedErrors errors) {
        // VoltDB cannot add a UNIQUE index (an ASSUMEUNIQUE one it can) to a table that already holds rows
        errors.add("is not empty");
    }

}
