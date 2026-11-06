package sqlancer.voltdb.oracle;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Map;
import java.util.regex.Pattern;

import sqlancer.voltdb.ast.VoltDBBetweenOperation;
import sqlancer.voltdb.ast.VoltDBBinaryOperation;
import sqlancer.voltdb.ast.VoltDBBinaryOperation.VoltDBBinaryOperator;
import sqlancer.voltdb.ast.VoltDBColumnReference;
import sqlancer.voltdb.ast.VoltDBConstant;
import sqlancer.voltdb.ast.VoltDBExpression;
import sqlancer.voltdb.ast.VoltDBFunction;
import sqlancer.voltdb.ast.VoltDBInOperation;
import sqlancer.voltdb.ast.VoltDBUnaryPostfixOperation;
import sqlancer.voltdb.ast.VoltDBUnaryPostfixOperation.VoltDBUnaryPostfixOperator;
import sqlancer.voltdb.ast.VoltDBUnaryPrefixOperation;
import sqlancer.voltdb.ast.VoltDBUnaryPrefixOperation.VoltDBUnaryPrefixOperator;

/**
 * Evaluates the generator's expressions in Java, on one row, as VoltDB's execution engine does. The PQS oracle uses it
 * to know each predicate's value on the pivot row.
 *
 * <p>
 * Values are null, Boolean, Long (every integer type), BigDecimal (DECIMAL, at scale 12), Double (FLOAT), String,
 * {@link Binary} (VARBINARY) and LocalDateTime (TIMESTAMP, and DATE at midnight), as {@link #fromJdbc} converts them.
 *
 * <p>
 * The semantics, checked against VoltDB:
 * <ul>
 * <li>Integer arithmetic is BIGINT; division truncates toward zero. Overflow, and division by zero, are errors.</li>
 * <li>DECIMAL arithmetic keeps scale 12. Multiplication and division truncate toward zero. A result beyond 38 digits is
 * an error.</li>
 * <li>FLOAT arithmetic is IEEE double arithmetic; an infinite or NaN result is an error.</li>
 * <li>An integer combined with a DECIMAL is a DECIMAL, and with a FLOAT a FLOAT.</li>
 * <li>Comparisons, AND, OR, NOT, BETWEEN, IN and LIKE follow SQL's three-valued logic. LIKE is case-sensitive, '%'
 * matches any characters and '_' one character, and nothing escapes them.</li>
 * <li>A DATE compares with a TIMESTAMP as midnight of that day (ENG-30545 is VoltDB doing otherwise).</li>
 * </ul>
 *
 * <p>
 * Where the value cannot be known in advance, evaluation throws {@link Unpredictable}, and the oracle skips the check:
 * <ul>
 * <li>An error, since VoltDB evaluates every operand (it does not short-circuit AND or OR) and so fails the query.</li>
 * <li>An integer result of Long.MIN_VALUE, or a FLOAT result of -Double.MAX_VALUE, which are VoltDB's NULLs for those
 * types.</li>
 * <li>A DECIMAL combined or compared with a FLOAT, which converts the DECIMAL to a double the way VoltDB's EE does, and
 * an integer of magnitude 2^53 or more compared with a FLOAT.</li>
 * <li>Ordered comparisons of BOOLEAN values, which VoltDB gets wrong (ENG-30493).</li>
 * </ul>
 */
public final class VoltDBExpressionEvaluator {

    static final int DECIMAL_SCALE = 12;
    // DECIMAL(38, 12): the largest value is 26 nines, then 12 after the point
    private static final BigDecimal MAX_DECIMAL = new BigDecimal("99999999999999999999999999.999999999999");
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS");
    private static final long MAX_EXACT_DOUBLE = 1L << 53;

    // the row's values, by column full name (table.column)
    private final Map<String, Object> row;
    private final boolean avoidKnownBugs;

    // A value VoltDB would not give back for sure: an error, a NULL sentinel, or semantics the evaluator does not
    // model. Without a stack trace, as it is common and only skips a check.
    public static final class Unpredictable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Unpredictable(String reason) {
            super(reason, null, false, false);
        }
    }

    // A VARBINARY value, compared by content
    public static final class Binary {
        private final byte[] bytes;

        public Binary(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Binary && Arrays.equals(bytes, ((Binary) o).bytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        byte[] getBytes() {
            return bytes.clone();
        }

        int compareTo(Binary other) {
            return Arrays.compareUnsigned(bytes, other.bytes);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("x'");
            for (byte b : bytes) {
                sb.append(String.format("%02X", b));
            }
            return sb.append('\'').toString();
        }
    }

    public VoltDBExpressionEvaluator(Map<String, Object> row, boolean avoidKnownBugs) {
        this.row = row;
        this.avoidKnownBugs = avoidKnownBugs;
    }

    // a value read with getObject, in the evaluator's representation
    public static Object fromJdbc(Object value) {
        if (value == null || value instanceof Boolean || value instanceof String || value instanceof Double) {
            return value;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).setScale(DECIMAL_SCALE);
        }
        if (value instanceof byte[]) {
            return new Binary((byte[]) value);
        }
        if (value instanceof Timestamp) {
            return LocalDateTime.ofInstant(((Timestamp) value).toInstant(), ZoneOffset.UTC);
        }
        if (value instanceof java.sql.Date) {
            // the driver makes a DATE in the JVM's time zone, which toLocalDate undoes
            return ((java.sql.Date) value).toLocalDate().atStartOfDay();
        }
        throw new AssertionError(value.getClass());
    }

    // whether two values are the same, as VoltDB would compare them for equality, with NULL the same as NULL
    public static boolean same(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Double && b instanceof Double) {
            return (double) a == (double) b; // 0.0 and -0.0 are equal
        }
        if (a instanceof Number && b instanceof Number) {
            return decimal(a).compareTo(decimal(b)) == 0;
        }
        return a.equals(b);
    }

    // a value as SQL text, for reports
    public static String show(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof String) {
            return "'" + ((String) value).replace("'", "''") + "'";
        }
        if (value instanceof LocalDateTime) {
            return "'" + ((LocalDateTime) value).format(TIMESTAMP_FORMAT) + "'";
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toPlainString();
        }
        return value.toString();
    }

    public Object evaluate(VoltDBExpression expr) {
        Object value = evaluateNode(expr);
        if (avoidKnownBugs && value instanceof Double && (Double) value != 0
                && Math.abs((Double) value) < Double.MIN_NORMAL && isConstant(expr)) {
            // ENG-30551: a subnormal FLOAT constant, as written or as folded by the planner, makes the plan fail to
            // load ("JSON parse failure") when the statement is not parameterized, and re-running it after any DDL
            // crashes the node, so a query must not hold one
            throw new Unpredictable("ENG-30551: subnormal FLOAT constant");
        }
        if (avoidKnownBugs && "\\N".equals(value) && isConstant(expr)) {
            // ENG-30552: a string constant '\N', as written or as folded by the planner, reaches the EE as NULL
            throw new Unpredictable("ENG-30552: string constant \\N");
        }
        return value;
    }

    private Object evaluateNode(VoltDBExpression expr) {
        if (expr instanceof VoltDBConstant) {
            return parseConstant(expr.toString());
        }
        if (expr instanceof VoltDBColumnReference) {
            String name = ((VoltDBColumnReference) expr).getColumn().getFullQualifiedName();
            if (!row.containsKey(name)) {
                throw new AssertionError(name);
            }
            return row.get(name);
        }
        if (expr instanceof VoltDBBinaryOperation) {
            VoltDBBinaryOperation op = (VoltDBBinaryOperation) expr;
            VoltDBBinaryOperator operator = binaryOperator(op.getOperatorRepresentation());
            // every operand is evaluated, for its errors
            Object left = evaluate(op.getLeft());
            Object right = evaluate(op.getRight());
            if (operator.isOrdering() || operator == VoltDBBinaryOperator.EQUALS
                    || operator == VoltDBBinaryOperator.NOT_EQUALS) {
                checkFoldedStrings(op.getLeft(), left, op.getRight(), right);
            }
            Object value = binary(operator, left, right);
            if (avoidKnownBugs
                    && (operator == VoltDBBinaryOperator.ADD || operator == VoltDBBinaryOperator.SUBTRACT
                            || operator == VoltDBBinaryOperator.MULTIPLY)
                    && value instanceof Long && (beyondInteger(value) || beyondInteger(left) || beyondInteger(right))
                    && isConstant(expr)) {
                // ENG-30547: the planner folds + and - of integer constants in 32 bits, wrapping around or giving NULL,
                // and types a multiplication with a BIGINT operand as DECIMAL, so a division by it keeps a fraction
                throw new Unpredictable("ENG-30547: folded integer arithmetic beyond INTEGER's range");
            }
            return value;
        }
        if (expr instanceof VoltDBUnaryPrefixOperation) {
            VoltDBUnaryPrefixOperation op = (VoltDBUnaryPrefixOperation) expr;
            Object v = evaluate(op.getExpr());
            if (op.getOperatorRepresentation().equals(VoltDBUnaryPrefixOperator.NOT.getTextRepresentation())) {
                return not(v);
            }
            return negate(v);
        }
        if (expr instanceof VoltDBUnaryPostfixOperation) {
            VoltDBUnaryPostfixOperation op = (VoltDBUnaryPostfixOperation) expr;
            boolean isNull = evaluate(op.getExpr()) == null;
            return op.getOperatorRepresentation().equals(VoltDBUnaryPostfixOperator.IS_NULL.getTextRepresentation())
                    ? isNull : !isNull;
        }
        if (expr instanceof VoltDBBetweenOperation) {
            VoltDBBetweenOperation op = (VoltDBBetweenOperation) expr;
            Object v = evaluate(op.getLeft());
            Object low = evaluate(op.getMiddle());
            Object high = evaluate(op.getRight());
            checkFoldedStrings(op.getLeft(), v, op.getMiddle(), low);
            checkFoldedStrings(op.getLeft(), v, op.getRight(), high);
            Object between = and(compare(VoltDBBinaryOperator.GREATER_EQUALS, v, low),
                    compare(VoltDBBinaryOperator.SMALLER_EQUALS, v, high));
            return op.isTrue() ? between : not(between); // isTrue is BETWEEN, otherwise NOT BETWEEN
        }
        if (expr instanceof VoltDBInOperation) {
            VoltDBInOperation op = (VoltDBInOperation) expr;
            Object v = evaluate(op.getLeft());
            Object[] list = op.getRight().stream().map(this::evaluate).toArray();
            for (int i = 0; i < list.length; i++) {
                checkFoldedStrings(op.getLeft(), v, op.getRight().get(i), list[i]);
            }
            Object in = inList(v, list);
            return op.isNegated() ? not(in) : in;
        }
        if (expr instanceof VoltDBFunction) {
            VoltDBFunction f = (VoltDBFunction) expr;
            switch (f.getFunc()) {
            case ABS:
                return abs(evaluate(f.getArgs().get(0)));
            default:
                throw new AssertionError(f.getFunc());
            }
        }
        throw new AssertionError(expr.getClass());
    }

    // ENG-30549: the planner folds a comparison of two string constants ignoring trailing spaces (PAD SPACE), while the
    // EE compares exactly, so while the bug is avoided such a comparison is unpredictable where the two differ
    private void checkFoldedStrings(VoltDBExpression leftExpr, Object left, VoltDBExpression rightExpr, Object right) {
        if (avoidKnownBugs && left instanceof String && right instanceof String && isConstant(leftExpr)
                && isConstant(rightExpr)
                && Integer.signum(order(left, right)) != Integer.signum(padSpaceOrder((String) left, (String) right))) {
            throw new Unpredictable("ENG-30549: folded string comparison with trailing spaces");
        }
    }

    private static int padSpaceOrder(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        byte[] px = Arrays.copyOf(x, Math.max(x.length, y.length));
        byte[] py = Arrays.copyOf(y, px.length);
        Arrays.fill(px, x.length, px.length, (byte) ' ');
        Arrays.fill(py, y.length, py.length, (byte) ' ');
        return Arrays.compareUnsigned(px, py);
    }

    private static boolean beyondInteger(Object v) {
        return v instanceof Long && ((Long) v < -Integer.MAX_VALUE || (Long) v > Integer.MAX_VALUE);
    }

    // whether the expression has no column, so that the planner folds it
    static boolean isConstant(VoltDBExpression expr) {
        if (expr instanceof VoltDBConstant) {
            return true;
        }
        if (expr instanceof VoltDBBinaryOperation) {
            return isConstant(((VoltDBBinaryOperation) expr).getLeft())
                    && isConstant(((VoltDBBinaryOperation) expr).getRight());
        }
        if (expr instanceof VoltDBUnaryPrefixOperation) {
            return isConstant(((VoltDBUnaryPrefixOperation) expr).getExpr());
        }
        if (expr instanceof VoltDBFunction) {
            return ((VoltDBFunction) expr).getArgs().stream().allMatch(VoltDBExpressionEvaluator::isConstant);
        }
        return false;
    }

    private static VoltDBBinaryOperator binaryOperator(String text) {
        for (VoltDBBinaryOperator op : VoltDBBinaryOperator.values()) {
            if (op.getTextRepresentation().equals(text)) {
                return op;
            }
        }
        throw new AssertionError(text);
    }

    private static Object binary(VoltDBBinaryOperator op, Object left, Object right) {
        switch (op) {
        case AND:
            return and(left, right);
        case OR:
            return not(and(not(left), not(right)));
        case EQUALS:
        case NOT_EQUALS:
        case SMALLER:
        case SMALLER_EQUALS:
        case GREATER:
        case GREATER_EQUALS:
            return compare(op, left, right);
        case LIKE:
            return like(left, right);
        case NOT_LIKE:
            return not(like(left, right));
        case ADD:
        case SUBTRACT:
        case MULTIPLY:
        case DIVIDE:
            return arithmetic(op, left, right);
        case CONCAT:
            return left == null || right == null ? null : (String) left + right;
        default:
            throw new AssertionError(op);
        }
    }

    static Object not(Object v) {
        return v == null ? null : !(Boolean) v;
    }

    static Object and(Object a, Object b) {
        if (Boolean.FALSE.equals(a) || Boolean.FALSE.equals(b)) {
            return false;
        }
        return a == null || b == null ? null : true;
    }

    private static Object inList(Object v, Object... list) {
        if (v == null) {
            return null;
        }
        boolean sawNull = false;
        for (Object e : list) {
            Object eq = compare(VoltDBBinaryOperator.EQUALS, v, e);
            if (eq == null) {
                sawNull = true;
            } else if ((Boolean) eq) {
                return true;
            }
        }
        return sawNull ? null : false;
    }

    static Object compare(VoltDBBinaryOperator op, Object left, Object right) {
        if (left == null || right == null) {
            return null;
        }
        if (left instanceof Boolean && op != VoltDBBinaryOperator.EQUALS && op != VoltDBBinaryOperator.NOT_EQUALS) {
            throw new Unpredictable("ENG-30493: ordered comparison of BOOLEAN values");
        }
        int c = order(left, right);
        switch (op) {
        case EQUALS:
            return c == 0;
        case NOT_EQUALS:
            return c != 0;
        case SMALLER:
            return c < 0;
        case SMALLER_EQUALS:
            return c <= 0;
        case GREATER:
            return c > 0;
        case GREATER_EQUALS:
            return c >= 0;
        default:
            throw new AssertionError(op);
        }
    }

    // compares two non-NULL values of comparable types; for doubles, 0.0 and -0.0 are equal
    private static int order(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            if (a instanceof Double || b instanceof Double) {
                double x = toDouble(a);
                double y = toDouble(b);
                return x < y ? -1 : x > y ? 1 : 0;
            }
            return decimal(a).compareTo(decimal(b));
        }
        if (a instanceof String && b instanceof String) {
            return Arrays.compareUnsigned(((String) a).getBytes(StandardCharsets.UTF_8),
                    ((String) b).getBytes(StandardCharsets.UTF_8));
        }
        if (a instanceof Binary && b instanceof Binary) {
            return ((Binary) a).compareTo((Binary) b);
        }
        if (a instanceof LocalDateTime && b instanceof LocalDateTime) {
            return ((LocalDateTime) a).compareTo((LocalDateTime) b);
        }
        if (a instanceof Boolean && b instanceof Boolean) {
            return a.equals(b) ? 0 : 1;
        }
        throw new AssertionError(a.getClass() + " vs " + b.getClass());
    }

    // a FLOAT operand: an integer converts exactly below 2^53; a DECIMAL's conversion is not modelled
    private static double toDouble(Object v) {
        if (v instanceof Double) {
            return (Double) v;
        }
        if (v instanceof Long) {
            long l = (Long) v;
            if (l >= MAX_EXACT_DOUBLE || l <= -MAX_EXACT_DOUBLE) {
                throw new Unpredictable("an integer of 2^53 or more as a FLOAT");
            }
            return l;
        }
        throw new Unpredictable("a DECIMAL as a FLOAT");
    }

    private static BigDecimal decimal(Object v) {
        return v instanceof BigDecimal ? (BigDecimal) v : BigDecimal.valueOf((Long) v);
    }

    private static Object arithmetic(VoltDBBinaryOperator op, Object left, Object right) {
        if (op == VoltDBBinaryOperator.DIVIDE && isZero(right)) {
            throw new Unpredictable("division by zero");
        }
        if (left == null || right == null) {
            return null;
        }
        if (left instanceof Double || right instanceof Double) {
            double x = toDouble(left);
            double y = toDouble(right);
            double r;
            switch (op) {
            case ADD:
                r = x + y;
                break;
            case SUBTRACT:
                r = x - y;
                break;
            case MULTIPLY:
                r = x * y;
                break;
            case DIVIDE:
                r = x / y;
                break;
            default:
                throw new AssertionError(op);
            }
            return checkedDouble(r);
        }
        if (left instanceof BigDecimal || right instanceof BigDecimal) {
            BigDecimal x = decimal(left);
            BigDecimal y = decimal(right);
            BigDecimal r;
            switch (op) {
            case ADD:
                r = x.add(y);
                break;
            case SUBTRACT:
                r = x.subtract(y);
                break;
            case MULTIPLY:
                r = x.multiply(y).setScale(DECIMAL_SCALE, RoundingMode.DOWN);
                break;
            case DIVIDE:
                r = x.divide(y, DECIMAL_SCALE, RoundingMode.DOWN);
                break;
            default:
                throw new AssertionError(op);
            }
            return checkedDecimal(r);
        }
        long x = (Long) left;
        long y = (Long) right;
        long r;
        try {
            switch (op) {
            case ADD:
                r = Math.addExact(x, y);
                break;
            case SUBTRACT:
                r = Math.subtractExact(x, y);
                break;
            case MULTIPLY:
                r = Math.multiplyExact(x, y);
                break;
            case DIVIDE:
                r = x / y;
                break;
            default:
                throw new AssertionError(op);
            }
        } catch (ArithmeticException e) {
            throw new Unpredictable("BIGINT overflow");
        }
        return checkedLong(r);
    }

    private static boolean isZero(Object v) {
        if (v instanceof Double) {
            return (Double) v == 0;
        }
        return v instanceof Number && decimal(v).signum() == 0;
    }

    private static Object negate(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Double) {
            return checkedDouble(-(Double) v);
        }
        if (v instanceof BigDecimal) {
            return ((BigDecimal) v).negate();
        }
        return checkedLong(-(Long) v);
    }

    private static Object abs(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Double) {
            return Math.abs((Double) v);
        }
        if (v instanceof BigDecimal) {
            return ((BigDecimal) v).abs();
        }
        return Math.abs((Long) v);
    }

    private static Object checkedLong(long r) {
        if (r == Long.MIN_VALUE) {
            throw new Unpredictable("BIGINT result is VoltDB's NULL");
        }
        return r;
    }

    private static Object checkedDouble(double r) {
        if (Double.isInfinite(r) || Double.isNaN(r)) {
            throw new Unpredictable("FLOAT result is not finite");
        }
        if (r == -Double.MAX_VALUE) {
            throw new Unpredictable("FLOAT result is VoltDB's NULL");
        }
        return r;
    }

    private static Object checkedDecimal(BigDecimal r) {
        if (r.abs().compareTo(MAX_DECIMAL) > 0) {
            throw new Unpredictable("DECIMAL overflow");
        }
        return r.setScale(DECIMAL_SCALE);
    }

    private static Object like(Object value, Object pattern) {
        if (value == null || pattern == null) {
            return null;
        }
        return likePattern((String) pattern).matcher((String) value).matches();
    }

    // '%' is any characters and '_' one character; everything else, backslash included, is itself
    static Pattern likePattern(String pattern) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        pattern.codePoints().forEach(cp -> {
            if (cp == '%' || cp == '_') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(cp == '%' ? ".*" : ".");
            } else {
                literal.appendCodePoint(cp);
            }
        });
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    // A constant as the generator writes it (see VoltDBExpressionGenerator#generateConstant)
    static Object parseConstant(String text) {
        if (text.equals("(1 = 1)") || text.equals("TRUE")) {
            return true;
        }
        if (text.equals("(1 = 0)") || text.equals("FALSE")) {
            return false;
        }
        if (text.startsWith("CAST(NULL AS ")) {
            return null;
        }
        if (text.startsWith("CAST('")) {
            String literal = text.substring("CAST('".length(), text.indexOf('\'', "CAST('".length()));
            if (text.endsWith(" AS DATE)")) {
                return LocalDate.parse(literal).atStartOfDay();
            }
            if (text.endsWith(" AS TIMESTAMP)")) {
                return LocalDateTime.parse(literal, TIMESTAMP_FORMAT);
            }
            throw new AssertionError(text);
        }
        if (text.startsWith("x'")) {
            String hex = text.substring(2, text.length() - 1);
            byte[] bytes = new byte[hex.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
            }
            return new Binary(bytes);
        }
        if (text.startsWith("'")) {
            // the driver sends SQL text as UTF-8, which turns a character it cannot encode, such as a lone surrogate
            // that SQLancer's random strings can hold, into '?'
            String str = text.substring(1, text.length() - 1).replace("''", "'");
            return new String(str.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        }
        String number = text.startsWith("(") ? text.substring(1, text.length() - 1) : text;
        if (number.contains("E") || number.contains("e")) {
            return Double.parseDouble(number);
        }
        if (number.contains(".")) {
            return new BigDecimal(number).setScale(DECIMAL_SCALE);
        }
        try {
            return Long.parseLong(number);
        } catch (NumberFormatException e) {
            throw new AssertionError(text, e);
        }
    }

}
