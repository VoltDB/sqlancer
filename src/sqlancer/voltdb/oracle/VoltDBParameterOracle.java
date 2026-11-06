package sqlancer.voltdb.oracle;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.Reproducer;
import sqlancer.common.oracle.TestOracle;
import sqlancer.common.oracle.TestOracleUtils;
import sqlancer.common.oracle.UnexpectedErrorReproducer;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.voltdb.VoltDBProvider.VoltDBGlobalState;
import sqlancer.voltdb.VoltDBSchema.VoltDBDataType;
import sqlancer.voltdb.VoltDBToStringVisitor;
import sqlancer.voltdb.ast.VoltDBAggregate;
import sqlancer.voltdb.ast.VoltDBAlias;
import sqlancer.voltdb.ast.VoltDBBetweenOperation;
import sqlancer.voltdb.ast.VoltDBBinaryOperation;
import sqlancer.voltdb.ast.VoltDBColumnReference;
import sqlancer.voltdb.ast.VoltDBConstant;
import sqlancer.voltdb.ast.VoltDBExpression;
import sqlancer.voltdb.ast.VoltDBFunction;
import sqlancer.voltdb.ast.VoltDBInOperation;
import sqlancer.voltdb.ast.VoltDBJoin;
import sqlancer.voltdb.ast.VoltDBSelect;
import sqlancer.voltdb.ast.VoltDBUnaryPostfixOperation;
import sqlancer.voltdb.ast.VoltDBUnaryPrefixOperation;
import sqlancer.voltdb.gen.VoltDBExpressionGenerator;
import sqlancer.voltdb.gen.VoltDBExpressionGenerator.Kind;
import sqlancer.voltdb.oracle.VoltDBExpressionEvaluator.Binary;
import sqlancer.voltdb.oracle.VoltDBExpressionEvaluator.Unpredictable;

/**
 * Literals against parameters. A query must return the same rows when some of its constants are JDBC parameters instead
 * of literals. VoltDB turns an ad hoc statement's literals into parameters itself, or plans the statement with them
 * inline when it cannot, so the two queries reach the planner and the executor by different paths, with values typed
 * differently.
 *
 * <p>
 * The query is a SELECT of columns and expressions with a WHERE clause, or a GROUP BY query with aggregates and maybe
 * WHERE and HAVING clauses. Each constant becomes a parameter with probability 3/4, written as:
 * <ul>
 * <li>{@code ?} when the other operand of its comparison, arithmetic, LIKE, BETWEEN or IN is a column whose type holds
 * the value. VoltDB gives the parameter the column's type, and a value that does not fit is an error.</li>
 * <li>{@code CAST(? AS T)} otherwise, where T is the literal's own type: VoltDB cannot type a bare parameter that has
 * no column beside it ("data type cast needed for parameter or null literal"), and types one inside ABS as FLOAT.</li>
 * </ul>
 * BOOLEAN constants (ENG-30494) and typed NULLs stay literals. So, unless --avoid-known-bugs is false, do the operands
 * of an expression of constants alone: the planner folds such an expression, differently from how the executor computes
 * it (ENG-30547, ENG-30548), and the parameterized query would be computed at run time.
 *
 * <p>
 * Rows are compared whole, as multisets, as TLP compares them. A failure hands SQLancer a reproducer, so
 * {@code --use-reducer} can reduce it.
 */
public class VoltDBParameterOracle implements TestOracle<VoltDBGlobalState> {

    private final VoltDBGlobalState state;
    private final VoltDBExpressionGenerator gen;
    private final ExpectedErrors errors;
    private final boolean avoidKnownBugs;
    private String lastQuery;
    private Reproducer<VoltDBGlobalState> reproducer;

    public VoltDBParameterOracle(VoltDBGlobalState state, ExpectedErrors errors) {
        this.state = state;
        this.gen = new VoltDBExpressionGenerator(state);
        this.errors = errors;
        this.avoidKnownBugs = state.getDbmsSpecificOptions().avoidKnownBugs;
    }

    @Override
    public void check() throws Exception {
        reproducer = null;
        boolean grouped = Randomly.getBoolean();
        // ENG-30531 (see VoltDBTLPOracle): a parameter can change the plan, and so whether a FULL JOIN of a GROUP BY
        // query returns the NULL group twice
        gen.setNoFullJoins(grouped);
        gen.setTablesAndColumns(TestOracleUtils.getRandomTableNonEmptyTables(state.getSchema()));
        VoltDBSelect select = gen.generateSelect();
        select.setFromList(gen.getTableRefs());
        select.setJoinClauses(gen.getRandomJoinClauses());
        if (grouped) {
            List<VoltDBExpression> grouping = gen.generateGroupingColumns();
            List<VoltDBExpression> fetched = new ArrayList<>(Randomly.subset(grouping));
            if (fetched.isEmpty() || Randomly.getBoolean()) {
                fetched.addAll(gen.generateAggregates());
            }
            select.setFetchColumns(gen.aliased(fetched));
            select.setGroupByExpressions(grouping);
            if (Randomly.getBoolean()) {
                select.setWhereClause(gen.generateBooleanExpression());
            }
            if (Randomly.getBoolean()) {
                select.setHavingClause(gen.generateHavingPredicate(grouping));
            }
        } else {
            List<VoltDBExpression> fetched = new ArrayList<>(gen.generateFetchColumns());
            for (int i = Randomly.smallNumber(); i > 0; i--) {
                fetched.add(
                        new VoltDBAlias(gen.generateExpressionInDomain(Randomly.fromOptions(Kind.values())), "e" + i));
            }
            select.setFetchColumns(fetched);
            select.setWhereClause(gen.generateBooleanExpression());
            if (Randomly.getBooleanWithSmallProbability()) {
                select.setOrderByClauses(gen.generateOrderBys());
            }
        }

        Map<VoltDBConstant, Parameter> parameters = new IdentityHashMap<>();
        new Chooser(parameters).visit(select);
        if (parameters.isEmpty()) {
            throw new IgnoreMeException();
        }
        ParameterizingVisitor visitor = new ParameterizingVisitor(parameters);
        visitor.visit(select);
        if (avoidKnownBugs && visitor.get().chars().filter(c -> c == '?').count() > visitor.values.size()) {
            // ENG-30571: a string literal left in the query holds a ?, in a part the Chooser does not visit
            throw new IgnoreMeException();
        }
        Queries queries = new Queries(select.asString(), visitor.get(), visitor.values, visitor.types,
                select.getFetchColumns().size());
        lastQuery = queries.parameterized;

        Result result;
        try {
            result = queries.run(state);
        } catch (AssertionError unexpectedError) {
            reproducer = new UnexpectedErrorReproducer<>(queries::run,
                    TestOracleUtils.getUnexpectedErrorMessage(unexpectedError), queries.describe());
            throw unexpectedError;
        }
        if (result.differs()) {
            reproducer = new MismatchReproducer(queries, result);
            throw new AssertionError("literals against parameters mismatch: " + result.inline.size() + " rows vs "
                    + result.parameterized.size());
        }
    }

    // A constant written as a parameter: its value, its SQL type, and the text that stands for it
    private static final class Parameter {
        final Object value;
        final String type;
        final String text;

        Parameter(Object value, String type, boolean bare) {
            this.value = value;
            this.type = type;
            this.text = bare ? "?" : "CAST(? AS " + type + ")";
        }
    }

    // Decides which constants of a query become parameters, and how each is written
    private final class Chooser {
        private final Map<VoltDBConstant, Parameter> parameters;
        private boolean inHaving;
        // whether the constants being visited must stay literals
        private boolean literalsOnly;

        Chooser(Map<VoltDBConstant, Parameter> parameters) {
            this.parameters = parameters;
        }

        void visit(VoltDBSelect select) {
            select.getFetchColumns().forEach(e -> visit(e, null, null));
            for (VoltDBJoin join : select.getJoinClauses()) {
                visit(join.getOnCondition(), null, null);
            }
            if (select.getWhereClause() != null) {
                visit(select.getWhereClause(), null, null);
            }
            if (select.getHavingClause() != null) {
                inHaving = true;
                visit(select.getHavingClause(), null, null);
                inHaving = false;
            }
        }

        // expr is an operand of parent, beside the operand sibling (null when it has none of interest)
        private void visit(VoltDBExpression expr, VoltDBExpression parent, VoltDBExpression sibling) {
            if (avoidKnownBugs && !(expr instanceof VoltDBConstant) && VoltDBExpressionEvaluator.isConstant(expr)
                    && (parent == null || !VoltDBExpressionEvaluator.isConstant(parent))) {
                // ENG-30551: a statement with parameters keeps its other constants inline, and an expression of
                // constants that folds to a subnormal FLOAT makes its plan fail to load, and can crash the node later
                try {
                    new VoltDBExpressionEvaluator(Map.of(), true).evaluate(expr);
                } catch (Unpredictable e) {
                    throw new IgnoreMeException();
                }
            }
            if (expr instanceof VoltDBConstant) {
                choose((VoltDBConstant) expr, parent, sibling);
            } else if (expr instanceof VoltDBBinaryOperation) {
                VoltDBBinaryOperation op = (VoltDBBinaryOperation) expr;
                visit(op.getLeft(), op, op.getRight());
                visit(op.getRight(), op, op.getLeft());
            } else if (expr instanceof VoltDBBetweenOperation) {
                VoltDBBetweenOperation op = (VoltDBBetweenOperation) expr;
                visit(op.getLeft(), op, null);
                visit(op.getMiddle(), op, op.getLeft());
                visit(op.getRight(), op, op.getLeft());
            } else if (expr instanceof VoltDBInOperation) {
                VoltDBInOperation op = (VoltDBInOperation) expr;
                visit(op.getLeft(), op, null);
                op.getRight().forEach(e -> visit(e, op, op.getLeft()));
            } else if (expr instanceof VoltDBUnaryPrefixOperation) {
                visit(((VoltDBUnaryPrefixOperation) expr).getExpr(), expr, null);
            } else if (expr instanceof VoltDBUnaryPostfixOperation) {
                visit(((VoltDBUnaryPostfixOperation) expr).getExpr(), expr, null);
            } else if (expr instanceof VoltDBAlias) {
                visit(((VoltDBAlias) expr).getExpr(), parent, sibling);
            } else if (expr instanceof VoltDBFunction) {
                // ABS: a bare parameter in it is typed FLOAT, so it is cast
                ((VoltDBFunction) expr).getArgs().forEach(arg -> visit(arg, expr, null));
            } else if (expr instanceof VoltDBAggregate) {
                // ENG-30572: in HAVING, two aggregates that differ only in their parameters take the first one's
                // value, so unless --avoid-known-bugs is false, constants in HAVING's aggregates stay literals
                boolean outer = literalsOnly;
                literalsOnly = outer || inHaving && avoidKnownBugs;
                ((VoltDBAggregate) expr).getArgs().forEach(arg -> visit(arg, expr, null));
                literalsOnly = outer;
            }
        }

        private void choose(VoltDBConstant constant, VoltDBExpression parent, VoltDBExpression sibling) {
            String text = constant.toString();
            if (text.startsWith("CAST(NULL ") || text.equals("(1 = 1)") || text.equals("(1 = 0)") || text.equals("TRUE")
                    || text.equals("FALSE")) {
                return;
            }
            Object value = VoltDBExpressionEvaluator.parseConstant(text);
            // In a statement with parameters, VoltDB reads a DECIMAL literal as a FLOAT (ENG-30560) and takes an
            // aggregate of a VARBINARY literal for one of a VARCHAR literal with the same text (ENG-30534); and the
            // JDBC driver counts a ? inside a string literal as a parameter (ENG-30571). Unless --avoid-known-bugs is
            // false, such constants are always parameters, and a query that must keep one as a literal is skipped
            // (see also check).
            boolean mustBeParameter = avoidKnownBugs && (value instanceof BigDecimal || value instanceof Binary
                    || text.startsWith("'") && text.contains("?"));
            if (literalsOnly || avoidKnownBugs && parent != null && VoltDBExpressionEvaluator.isConstant(parent)) {
                if (mustBeParameter) {
                    throw new IgnoreMeException();
                }
                return;
            }
            if (!mustBeParameter && Randomly.getBoolean() && Randomly.getBoolean()) {
                return;
            }
            boolean bare = sibling instanceof VoltDBColumnReference
                    && fits(value, ((VoltDBColumnReference) sibling).getColumn().getType().getType());
            parameters.put(constant, new Parameter(value, literalType(value), bare));
        }
    }

    // the type VoltDB gives the literal for the value
    static String literalType(Object value) {
        if (value instanceof Long) {
            long v = (Long) value;
            return v >= -Integer.MAX_VALUE && v <= Integer.MAX_VALUE ? "INTEGER" : "BIGINT";
        }
        if (value instanceof BigDecimal) {
            return "DECIMAL";
        }
        if (value instanceof Double) {
            return "FLOAT";
        }
        if (value instanceof String) {
            return "VARCHAR";
        }
        if (value instanceof Binary) {
            return "VARBINARY";
        }
        if (value instanceof LocalDateTime) {
            return "TIMESTAMP";
        }
        throw new AssertionError(value);
    }

    // whether a parameter of a column's type holds the value; VoltDB's integer types reserve their minimum for NULL
    static boolean fits(Object value, VoltDBDataType type) {
        if (value instanceof Long) {
            long v = (Long) value;
            switch (type) {
            case TINYINT:
                return Math.abs(v) <= Byte.MAX_VALUE;
            case SMALLINT:
                return Math.abs(v) <= Short.MAX_VALUE;
            case INTEGER:
                return Math.abs(v) <= Integer.MAX_VALUE;
            case BIGINT:
                return v != Long.MIN_VALUE;
            default:
                return false;
            }
        }
        return value instanceof BigDecimal && type == VoltDBDataType.DECIMAL
                || value instanceof Double && type == VoltDBDataType.FLOAT
                || value instanceof String && type == VoltDBDataType.VARCHAR
                || value instanceof Binary && type == VoltDBDataType.VARBINARY
                || value instanceof LocalDateTime && type == VoltDBDataType.TIMESTAMP;
    }

    // Writes a query with the chosen constants as parameters, collecting their values in the order of the ?s
    private static final class ParameterizingVisitor extends VoltDBToStringVisitor {
        private final Map<VoltDBConstant, Parameter> parameters;
        final List<Object> values = new ArrayList<>();
        final List<String> types = new ArrayList<>();

        ParameterizingVisitor(Map<VoltDBConstant, Parameter> parameters) {
            this.parameters = parameters;
        }

        @Override
        public void visitSpecific(VoltDBExpression expr) {
            Parameter p = expr instanceof VoltDBConstant ? parameters.get(expr) : null;
            if (p == null) {
                super.visitSpecific(expr);
                return;
            }
            sb.append(p.text);
            values.add(p.value);
            types.add(p.type);
        }
    }

    // The query with literals and with parameters, and how to run them
    private final class Queries {
        final String inline;
        final String parameterized;
        final List<Object> values;
        final List<String> types;
        final int columns;

        Queries(String inline, String parameterized, List<Object> values, List<String> types, int columns) {
            this.inline = inline;
            this.parameterized = parameterized;
            this.values = values;
            this.types = types;
            this.columns = columns;
        }

        // An expected error in either query throws IgnoreMeException; an unexpected one throws AssertionError
        Result run(VoltDBGlobalState s) throws SQLException {
            return new Result(rows(s, inline, List.of()), rows(s, parameterized, values));
        }

        // each row as its values joined with " | ", as VoltDBTLPOracle writes them
        private List<String> rows(VoltDBGlobalState s, String sql, List<Object> params) throws SQLException {
            List<String> rows = new ArrayList<>();
            try (Statement st = params.isEmpty() ? s.getConnection().createStatement()
                    : s.getConnection().prepareStatement(sql)) {
                for (int i = 0; i < params.size(); i++) {
                    bind((PreparedStatement) st, i + 1, params.get(i));
                }
                try (ResultSet rs = params.isEmpty() ? st.executeQuery(sql) : ((PreparedStatement) st).executeQuery()) {
                    while (rs.next()) {
                        List<String> row = new ArrayList<>();
                        for (int i = 1; i <= columns; i++) {
                            Object value = rs.getObject(i);
                            row.add(value instanceof Double ? String.format("%.12g", (Double) value + 0.0)
                                    : String.valueOf(rs.getString(i)));
                        }
                        rows.add(String.join(" | ", row));
                    }
                }
            } catch (SQLException e) {
                if (errors.errorIsExpected(e.getMessage())) {
                    throw new IgnoreMeException();
                }
                throw new AssertionError(sql, e);
            }
            return rows;
        }

        // the queries as commented lines, each ending in a line separator
        String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("-- inline: ").append(inline).append(';').append(System.lineSeparator());
            sb.append("-- parameterized: ").append(parameterized).append(';').append(System.lineSeparator());
            sb.append("-- parameters:");
            for (int i = 0; i < values.size(); i++) {
                sb.append(' ').append(i + 1).append(" = ").append(VoltDBExpressionEvaluator.show(values.get(i)))
                        .append(" (").append(types.get(i)).append(')');
            }
            return sb.append(System.lineSeparator()).toString();
        }
    }

    // JDBC setters for the evaluator's value types. A TIMESTAMP is bound by its instant at UTC, as VoltDB stores a
    // TIMESTAMP literal, so that the JVM's time zone does not shift it.
    private static void bind(PreparedStatement st, int index, Object value) throws SQLException {
        if (value instanceof Long) {
            st.setLong(index, (Long) value);
        } else if (value instanceof BigDecimal) {
            st.setBigDecimal(index, (BigDecimal) value);
        } else if (value instanceof Double) {
            st.setDouble(index, (Double) value);
        } else if (value instanceof String) {
            st.setString(index, (String) value);
        } else if (value instanceof Binary) {
            st.setBytes(index, ((Binary) value).getBytes());
        } else if (value instanceof LocalDateTime) {
            LocalDateTime t = (LocalDateTime) value;
            Timestamp ts = new Timestamp(t.toEpochSecond(ZoneOffset.UTC) * 1000);
            ts.setNanos(t.getNano());
            st.setTimestamp(index, ts);
        } else {
            throw new AssertionError(value);
        }
    }

    // The rows of the two queries, each sorted
    private static final class Result {
        final List<String> inline;
        final List<String> parameterized;

        Result(List<String> inline, List<String> parameterized) {
            this.inline = new ArrayList<>(inline);
            this.parameterized = new ArrayList<>(parameterized);
            Collections.sort(this.inline);
            Collections.sort(this.parameterized);
        }

        boolean differs() {
            return !inline.equals(parameterized);
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

    // Re-runs both queries and reports the bug as still present while they still differ, with the rows of the last run
    // that did, as VoltDBTLPOracle's reproducer does
    private static final class MismatchReproducer implements Reproducer<VoltDBGlobalState> {
        private final Queries queries;
        private Result result;

        MismatchReproducer(Queries queries, Result result) {
            this.queries = queries;
            this.result = result;
        }

        @Override
        public boolean bugStillTriggers(VoltDBGlobalState globalState) {
            try {
                Result rerun = queries.run(globalState);
                if (rerun.differs()) {
                    result = rerun;
                    return true;
                }
                return false;
            } catch (AssertionError | SQLException | RuntimeException e) {
                // an error, expected or not, is an artifact of the reduction, such as a removed table
                return false;
            }
        }

        @Override
        public String getBugInformation() {
            return "-- On the database set up by the statements above, the rows of these queries mismatch:"
                    + System.lineSeparator() + queries.describe() + "-- inline rows: " + result.inline.size()
                    + ", parameterized rows: " + result.parameterized.size() + System.lineSeparator()
                    + "-- rows only inline: " + Result.difference(result.inline, result.parameterized)
                    + System.lineSeparator() + "-- rows only parameterized: "
                    + Result.difference(result.parameterized, result.inline) + System.lineSeparator();
        }
    }

    @Override
    public Reproducer<VoltDBGlobalState> getLastReproducer() {
        return reproducer;
    }

    @Override
    public String getLastQueryString() {
        return lastQuery;
    }

}
