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
        // VoltDB plans a join of partitioned tables only when it can see the equality of their partitioning columns.
        // The generator joins them on that equality alone, but an outer join of a replicated table whose ON condition
        // folds to FALSE, with a WHERE clause on that table, can take the equality away; the shapes that do are many
        errors.add("This query is not plannable");
        // ENG-30508: the same rejected filter, when it is an IN with a DECIMAL constant on the left, fails with an
        // internal error instead
        errors.add("NUMERIC constant value type must match a FLOAT, DECIMAL, or integral column, not null");
        // run-time arithmetic errors: random operands divide by zero and overflow, and the two NoREC queries may
        // evaluate an expression on different rows, so only one of them may hit the error
        errors.add("Attempted to divide"); // integer or DECIMAL division by zero
        errors.add("data exception: division by zero"); // the same, in constants the planner folds
        errors.add("Invalid result value"); // FLOAT result is infinite or NaN
        errors.add("will overflow BigInt storage"); // integer +, -, * out of range
        errors.add("causing overflow/underflow"); // DECIMAL out of range
        errors.add("out of range for the destination type"); // a huge DECIMAL or FLOAT result cast to an integer
        // DECIMAL out of range in an expression of constants, which the planner folds; the message quotes a
        // truncated value, e.g. "30145.999" for -99999999999999999999999999.999999999999 + -30146
        errors.add("Numeric conversion error to type DECIMAL");
        // the same where the folded value has more digits than DECIMAL's precision allows, e.g. a 26-digit DECIMAL
        // constant times a 9-digit integer
        errors.add("digits of precision");
        // ENG-30522: FLOAT overflow to infinity in an expression of constants, which the parser folds, fails with an
        // internal NumberFormatException instead of "Invalid result value"
        errors.add("Character I is neither a decimal digit number");
    }

    public static void addInsertErrors(ExpectedErrors errors) {
        // a generated key can repeat an existing primary key or unique value
        errors.add("CONSTRAINT VIOLATION");
    }

    public static void addUpdateErrors(ExpectedErrors errors) {
        // a random new value can repeat a primary key or unique value, or be NULL in a NOT NULL column; VoltDB checks
        // constraints row by row, so even SET k = k + 1 on a unique k fails once one row's new value meets the next
        errors.add("CONSTRAINT VIOLATION");
        // a value does not fit the column: a constant at planning time, or a computed value when it is stored
        errors.add("is too wide");
        errors.add("exceeds the size of the");
        errors.add("overflows/underflows");
        // a constant with a fraction, such as (0 - 0.42E0), set into an integer column is rejected when planned; not
        // the same message for a DATE column, which is ENG-30561
        for (String type : new String[] { "tinyint", "smallint", "integer", "bigint" }) {
            errors.add("has an invalid format for a constant " + type + " value");
        }
    }

    public static void addIndexErrors(ExpectedErrors errors) {
        // VoltDB cannot add a UNIQUE index (an ASSUMEUNIQUE one it can) to a table that already holds rows
        errors.add("is not empty");
    }

}
