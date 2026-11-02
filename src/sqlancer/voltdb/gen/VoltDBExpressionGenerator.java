package sqlancer.voltdb.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.gen.NoRECGenerator;
import sqlancer.common.gen.TypedExpressionGenerator;
import sqlancer.common.schema.AbstractTables;
import sqlancer.voltdb.VoltDBProvider.VoltDBGlobalState;
import sqlancer.voltdb.VoltDBSchema.VoltDBColumn;
import sqlancer.voltdb.VoltDBSchema.VoltDBCompositeDataType;
import sqlancer.voltdb.VoltDBSchema.VoltDBDataType;
import sqlancer.voltdb.VoltDBSchema.VoltDBTable;
import sqlancer.voltdb.VoltDBToStringVisitor;
import sqlancer.voltdb.ast.VoltDBBetweenOperation;
import sqlancer.voltdb.ast.VoltDBBinaryOperation;
import sqlancer.voltdb.ast.VoltDBBinaryOperation.VoltDBBinaryOperator;
import sqlancer.voltdb.ast.VoltDBColumnReference;
import sqlancer.voltdb.ast.VoltDBConstant;
import sqlancer.voltdb.ast.VoltDBExpression;
import sqlancer.voltdb.ast.VoltDBFunction;
import sqlancer.voltdb.ast.VoltDBFunction.VoltDBFunctionName;
import sqlancer.voltdb.ast.VoltDBInOperation;
import sqlancer.voltdb.ast.VoltDBJoin;
import sqlancer.voltdb.ast.VoltDBSelect;
import sqlancer.voltdb.ast.VoltDBTableReference;
import sqlancer.voltdb.ast.VoltDBUnaryPostfixOperation;
import sqlancer.voltdb.ast.VoltDBUnaryPostfixOperation.VoltDBUnaryPostfixOperator;
import sqlancer.voltdb.ast.VoltDBUnaryPrefixOperation;
import sqlancer.voltdb.ast.VoltDBUnaryPrefixOperation.VoltDBUnaryPrefixOperator;

/**
 * Typed expressions, and the queries the NoREC oracle compares.
 *
 * <p>
 * Every operand is generated for one {@link Kind}, so that VoltDB never rejects an expression for mixing types
 * ("incompatible data types in combination"). NULL literals carry a type (CAST(NULL AS ...)), as VoltDB requires.
 *
 * <p>
 * VoltDB plans a query over several partitioned tables only when they are joined on their partitioning columns. The
 * FROM clause therefore holds at most one partitioned table without a join condition; every other partitioned table is
 * joined to it on the partitioning columns, and one whose partitioning column cannot be compared with it is left out.
 * The join is in the FROM clause, so both NoREC queries have it.
 */
public final class VoltDBExpressionGenerator
        extends TypedExpressionGenerator<VoltDBExpression, VoltDBColumn, VoltDBExpressionGenerator.Kind>
        implements NoRECGenerator<VoltDBSelect, VoltDBJoin, VoltDBExpression, VoltDBTable, VoltDBColumn> {

    // The type classes VoltDB compares and combines freely within, and not across.
    public enum Kind {
        BOOLEAN, NUMERIC, STRING, VARBINARY, TEMPORAL;

        public static Kind of(VoltDBDataType type) {
            switch (type) {
            case BOOLEAN:
                return BOOLEAN;
            case TINYINT:
            case SMALLINT:
            case INTEGER:
            case BIGINT:
            case FLOAT:
            case DECIMAL:
                return NUMERIC;
            case VARCHAR:
                return STRING;
            case VARBINARY:
                return VARBINARY;
            case TIMESTAMP:
            case DATE:
                return TEMPORAL;
            default:
                throw new AssertionError(type);
            }
        }
    }

    private enum BooleanExpression {
        COMPARISON, AND_OR, NOT, IS_NULL, BETWEEN, IN, LIKE
    }

    private enum NumericExpression {
        UNARY_MINUS, ARITHMETIC, ABS
    }

    private final VoltDBGlobalState state;
    private final boolean avoidKnownBugs;

    // the FROM list, comma-separated; the joins attach to its last table
    private List<VoltDBTable> fromTables = new ArrayList<>();
    private List<VoltDBJoin> joins = new ArrayList<>();
    private boolean hasOuterJoin;
    // how many comparison or IN operands enclose the expression being generated
    private int comparandNesting;

    public VoltDBExpressionGenerator(VoltDBGlobalState state) {
        this.state = state;
        this.avoidKnownBugs = state.getDbmsSpecificOptions().avoidKnownBugs;
    }

    @Override
    protected VoltDBExpression generateExpression(Kind kind, int depth) {
        if (depth >= state.getOptions().getMaxExpressionDepth() || Randomly.getBooleanWithSmallProbability()) {
            return generateLeafNode(kind);
        }
        switch (kind) {
        case BOOLEAN:
            return generateBooleanExpression(depth);
        case NUMERIC:
            return generateNumericExpression(depth);
        case STRING:
            if (Randomly.getBoolean()) {
                return new VoltDBBinaryOperation(generateExpression(Kind.STRING, depth + 1),
                        generateExpression(Kind.STRING, depth + 1), VoltDBBinaryOperator.CONCAT);
            }
            return generateLeafNode(kind);
        default:
            return generateLeafNode(kind);
        }
    }

    private VoltDBExpression generateBooleanExpression(int depth) {
        switch (Randomly.fromOptions(BooleanExpression.values())) {
        case COMPARISON:
            Kind kind = getRandomType();
            VoltDBBinaryOperator op = Randomly.fromOptions(VoltDBBinaryOperator.EQUALS, VoltDBBinaryOperator.NOT_EQUALS,
                    VoltDBBinaryOperator.SMALLER, VoltDBBinaryOperator.SMALLER_EQUALS, VoltDBBinaryOperator.GREATER,
                    VoltDBBinaryOperator.GREATER_EQUALS);
            if (op.isOrdering() && !canOrder(kind)) {
                op = Randomly.fromOptions(VoltDBBinaryOperator.EQUALS, VoltDBBinaryOperator.NOT_EQUALS);
            }
            return new VoltDBBinaryOperation(generateComparand(kind, depth), generateComparand(kind, depth), op);
        case AND_OR:
            return new VoltDBBinaryOperation(generateExpression(Kind.BOOLEAN, depth + 1),
                    generateExpression(Kind.BOOLEAN, depth + 1),
                    Randomly.fromOptions(VoltDBBinaryOperator.AND, VoltDBBinaryOperator.OR));
        case NOT:
            VoltDBExpression negated = generateExpression(Kind.BOOLEAN, depth + 1);
            if (avoidKnownBugs && negated instanceof VoltDBUnaryPrefixOperation
                    && ((VoltDBUnaryPrefixOperation) negated).getOperatorRepresentation().equals("NOT")) {
                // ENG-30504: the planner fails on NOT (NOT (b OR c)) over BOOLEAN columns
                return negated;
            }
            return new VoltDBUnaryPrefixOperation(negated, VoltDBUnaryPrefixOperator.NOT);
        case IS_NULL:
            // ENG-30497: with an outer join, (x IS NULL) IS NOT NULL and the like over the inner table turn the join
            // into an inner join, so the operand is a column or a constant unless --avoid-known-bugs is false
            Kind nullKind = getRandomType();
            return new VoltDBUnaryPostfixOperation(
                    hasOuterJoin && avoidKnownBugs ? generateLeafNode(nullKind)
                            : generateExpression(nullKind, depth + 1),
                    Randomly.fromOptions(VoltDBUnaryPostfixOperator.values()));
        case BETWEEN:
            Kind betweenKind;
            do {
                betweenKind = getRandomType();
            } while (!canOrder(betweenKind));
            return new VoltDBBetweenOperation(generateExpression(betweenKind, depth + 1),
                    generateExpression(betweenKind, depth + 1), generateExpression(betweenKind, depth + 1),
                    Randomly.getBoolean());
        case IN:
            if (comparandNesting > 0) {
                // VoltDB's parser rejects an IN predicate inside an operand of a comparison or IN, such as
                // x = (y IN (1)): "unexpected token: IN"
                return new VoltDBUnaryPostfixOperation(generateExpression(getRandomType(), depth + 1),
                        Randomly.fromOptions(VoltDBUnaryPostfixOperator.values()));
            }
            Kind inKind = getRandomType();
            List<VoltDBExpression> list = new ArrayList<>();
            int n = 1 + Randomly.smallNumber();
            for (int i = 0; i < n; i++) {
                list.add(generateInListElement(inKind, depth));
            }
            return new VoltDBInOperation(generateComparand(inKind, depth), list, Randomly.getBoolean());
        case LIKE:
            return generateLike(depth);
        default:
            throw new AssertionError();
        }
    }

    // An operand of a comparison or IN. ENG-30497: VoltDB turns an outer join into an inner join when the WHERE clause
    // compares a boolean expression over the inner table, such as (t.c IS NULL) = TRUE, with something, although the
    // comparison need not reject NULLs. With an outer join in the FROM clause, BOOLEAN operands are therefore only
    // columns and constants, unless --avoid-known-bugs is false.
    private VoltDBExpression generateComparand(Kind kind, int depth) {
        if (kind == Kind.BOOLEAN && hasOuterJoin && avoidKnownBugs) {
            return generateLeafNode(kind);
        }
        comparandNesting++;
        try {
            return generateExpression(kind, depth + 1);
        } finally {
            comparandNesting--;
        }
    }

    // ENG-30503: VoltDB ignores NULL elements of an IN list, so x NOT IN (NULL, ...) is TRUE instead of NULL
    // (ENG-30500 is another wrong result with a NULL in the list). Unless --avoid-known-bugs is false, IN list
    // elements are constants that are not NULL.
    private VoltDBExpression generateInListElement(Kind kind, int depth) {
        if (!avoidKnownBugs) {
            return generateComparand(kind, depth);
        }
        return generateConstant(kind, false);
    }

    // ENG-30493: VoltDB orders BOOLEAN values TRUE < FALSE where it evaluates them, so <, >, BETWEEN and ORDER BY on
    // BOOLEAN values give wrong results; they are left out unless --avoid-known-bugs is false.
    private boolean canOrder(Kind kind) {
        return kind != Kind.BOOLEAN || !avoidKnownBugs;
    }

    private VoltDBExpression generateNumericExpression(int depth) {
        switch (Randomly.fromOptions(NumericExpression.values())) {
        case UNARY_MINUS:
            if (avoidKnownBugs) {
                // ENG-30502: unary minus on a NULL integer or FLOAT gives a non-NULL value; 0 - x does not
                return new VoltDBBinaryOperation(new VoltDBConstant("0"), generateExpression(Kind.NUMERIC, depth + 1),
                        VoltDBBinaryOperator.SUBTRACT);
            }
            return new VoltDBUnaryPrefixOperation(generateExpression(Kind.NUMERIC, depth + 1),
                    VoltDBUnaryPrefixOperator.MINUS);
        case ARITHMETIC:
            return new VoltDBBinaryOperation(generateExpression(Kind.NUMERIC, depth + 1),
                    generateExpression(Kind.NUMERIC, depth + 1), Randomly.fromOptions(VoltDBBinaryOperator.ADD,
                            VoltDBBinaryOperator.SUBTRACT, VoltDBBinaryOperator.MULTIPLY, VoltDBBinaryOperator.DIVIDE));
        case ABS:
            return new VoltDBFunction(List.of(generateExpression(Kind.NUMERIC, depth + 1)), VoltDBFunctionName.ABS);
        default:
            throw new AssertionError();
        }
    }

    // ENG-30496: inside CASE WHEN, which is where NoREC's unoptimized query puts the predicate, VoltDB's planner
    // crashes on a LIKE with a constant left operand, or with a pattern that has no wildcard or is just '%'. Unless
    // --avoid-known-bugs is false, the left operand is a string column and the pattern has a wildcard and a literal
    // character.
    private VoltDBExpression generateLike(int depth) {
        VoltDBBinaryOperator op = Randomly.fromOptions(VoltDBBinaryOperator.LIKE, VoltDBBinaryOperator.NOT_LIKE);
        if (!avoidKnownBugs) {
            return new VoltDBBinaryOperation(generateExpression(Kind.STRING, depth + 1),
                    Randomly.getBoolean() ? generateLikePattern() : generateExpression(Kind.STRING, depth + 1), op);
        }
        if (!canGenerateColumnOfType(Kind.STRING)) {
            return new VoltDBUnaryPostfixOperation(generateExpression(getRandomType(), depth + 1),
                    Randomly.fromOptions(VoltDBUnaryPostfixOperator.values()));
        }
        return new VoltDBBinaryOperation(generateColumn(Kind.STRING), generateLikePattern(), op);
    }

    // A LIKE pattern: a random string with wildcards mixed in, keeping at least one wildcard and one other character.
    private VoltDBExpression generateLikePattern() {
        String s = state.getRandomly().getString().replace("%", "").replace("_", "");
        if (s.isEmpty()) {
            s = state.getRandomly().getAlphabeticChar();
        }
        StringBuilder sb = new StringBuilder();
        boolean wildcard = false;
        for (int i = 0; i < s.length(); i++) {
            if (Randomly.getBooleanWithRatherLowProbability()) {
                sb.append(Randomly.fromOptions("%", "_"));
                wildcard = true;
            }
            sb.append(s.charAt(i));
        }
        if (!wildcard || Randomly.getBoolean()) {
            sb.append(Randomly.fromOptions("%", "_"));
        }
        return new VoltDBConstant("'" + sb.toString().replace("'", "''") + "'");
    }

    @Override
    public VoltDBExpression generateConstant(Kind kind) {
        return generateConstant(kind, true);
    }

    private VoltDBExpression generateConstant(Kind kind, boolean mayBeNull) {
        VoltDBDataType type = randomDataType(kind);
        if (kind == Kind.TEMPORAL && avoidKnownBugs) {
            // ENG-30499: the planner crashes folding a comparison of a DATE constant with a TIMESTAMP constant
            type = VoltDBDataType.TIMESTAMP;
        }
        // ENG-30495: a WHERE clause that folds to a constant NULL crashes the planner, and only NULL literals can make
        // it fold to NULL (ENG-30500 and ENG-30503 are wrong results with NULLs in IN lists). Unless
        // --avoid-known-bugs is false, queries get their NULLs from the data only.
        if (mayBeNull && !avoidKnownBugs && Randomly.getBooleanWithRatherLowProbability()) {
            // VoltDB needs a typed NULL: a bare NULL is "data type cast needed for parameter or null literal"
            return new VoltDBConstant("CAST(NULL AS " + type + ")");
        }
        if (kind == Kind.BOOLEAN && avoidKnownBugs) {
            // ENG-30494: VoltDB passes BOOLEAN literals to the EE as parameters of the wrong value or type, giving
            // cast errors and wrong rows; a comparison of integers is a boolean constant without a BOOLEAN literal
            return new VoltDBConstant(Randomly.getBoolean() ? "(1 = 1)" : "(1 = 0)");
        }
        String literal = VoltDBConstantGenerator.generate(state.getRandomly(), VoltDBCompositeDataType.getRandom(type));
        if (kind == Kind.TEMPORAL) {
            // a string literal compares with TIMESTAMP and DATE values, but not inside an IN list
            return new VoltDBConstant("CAST(" + literal + " AS " + type + ")");
        }
        if (literal.startsWith("-")) {
            // VoltDB's parser rejects "- -5", which the unary minus would otherwise produce
            return new VoltDBConstant("(" + literal + ")");
        }
        return new VoltDBConstant(literal);
    }

    private static VoltDBDataType randomDataType(Kind kind) {
        switch (kind) {
        case BOOLEAN:
            return VoltDBDataType.BOOLEAN;
        case NUMERIC:
            return Randomly.fromOptions(VoltDBDataType.TINYINT, VoltDBDataType.SMALLINT, VoltDBDataType.INTEGER,
                    VoltDBDataType.BIGINT, VoltDBDataType.FLOAT, VoltDBDataType.DECIMAL);
        case STRING:
            return VoltDBDataType.VARCHAR;
        case VARBINARY:
            return VoltDBDataType.VARBINARY;
        case TEMPORAL:
            return Randomly.fromOptions(VoltDBDataType.TIMESTAMP, VoltDBDataType.DATE);
        default:
            throw new AssertionError(kind);
        }
    }

    @Override
    protected VoltDBExpression generateColumn(Kind kind) {
        return new VoltDBColumnReference(Randomly.fromList(
                columns.stream().filter(c -> Kind.of(c.getType().getType()) == kind).collect(Collectors.toList())));
    }

    @Override
    protected Kind getRandomType() {
        return Randomly.fromOptions(Kind.values());
    }

    @Override
    protected boolean canGenerateColumnOfType(Kind kind) {
        return columns.stream().anyMatch(c -> Kind.of(c.getType().getType()) == kind);
    }

    @Override
    public VoltDBExpressionGenerator setTablesAndColumns(AbstractTables<VoltDBTable, VoltDBColumn> tables) {
        fromTables = new ArrayList<>();
        joins = new ArrayList<>();
        hasOuterJoin = false;
        List<VoltDBTable> partitionJoined = new ArrayList<>();
        VoltDBTable anchor = null;
        for (VoltDBTable t : tables.getTables()) {
            if (!t.isPartitioned()) {
                fromTables.add(t);
            } else if (anchor == null) {
                anchor = t;
            } else if (partitionKind(t) == partitionKind(anchor)) {
                partitionJoined.add(t);
            }
            // a partitioned table whose partitioning column cannot be compared with the anchor's is left out
        }
        if (anchor == null) {
            anchor = fromTables.remove(fromTables.size() - 1);
        }
        List<VoltDBTable> explicitlyJoined = new ArrayList<>();
        for (VoltDBTable t : new ArrayList<>(fromTables)) {
            if (Randomly.getBooleanWithRatherLowProbability()) {
                fromTables.remove(t);
                explicitlyJoined.add(t);
            }
        }
        fromTables.add(anchor);

        List<VoltDBColumn> all = new ArrayList<>();
        fromTables.forEach(t -> all.addAll(t.getColumns()));
        for (VoltDBTable t : partitionJoined) {
            joins.add(new VoltDBJoin(new VoltDBTableReference(t), VoltDBJoin.JoinType.getRandom(),
                    partitionJoinCondition(anchor, t)));
            all.addAll(t.getColumns());
        }
        for (VoltDBTable t : explicitlyJoined) {
            joins.add(new VoltDBJoin(new VoltDBTableReference(t), VoltDBJoin.JoinType.getRandom(),
                    joinCondition(anchor, t)));
            all.addAll(t.getColumns());
        }
        this.columns = all;
        hasOuterJoin = joins.stream().anyMatch(j -> j.getJoinType() != VoltDBJoin.JoinType.INNER);
        return this;
    }

    private static Kind partitionKind(VoltDBTable t) {
        return Kind.of(t.getColumns().stream().filter(c -> c.getName().equals(t.getPartitionColumn())).findFirst()
                .orElseThrow(() -> new AssertionError(t.getName())).getType().getType());
    }

    // anchor.pc = t.pc, sometimes AND a random condition over the two tables
    private VoltDBExpression partitionJoinCondition(VoltDBTable anchor, VoltDBTable t) {
        VoltDBExpression equal = new VoltDBBinaryOperation(new VoltDBColumnReference(partitionColumn(anchor)),
                new VoltDBColumnReference(partitionColumn(t)), VoltDBBinaryOperator.EQUALS);
        if (Randomly.getBooleanWithRatherLowProbability()) {
            return new VoltDBBinaryOperation(equal, joinCondition(anchor, t), VoltDBBinaryOperator.AND);
        }
        return equal;
    }

    private static VoltDBColumn partitionColumn(VoltDBTable t) {
        return t.getColumns().stream().filter(c -> c.getName().equals(t.getPartitionColumn())).findFirst()
                .orElseThrow(() -> new AssertionError(t.getName()));
    }

    // a random condition over the columns of the two tables
    private VoltDBExpression joinCondition(VoltDBTable anchor, VoltDBTable t) {
        List<VoltDBColumn> joinColumns = new ArrayList<>(anchor.getColumns());
        joinColumns.addAll(t.getColumns());
        VoltDBExpressionGenerator gen = new VoltDBExpressionGenerator(state).setColumns(joinColumns);
        return gen.generatePredicateWithColumn();
    }

    @Override
    public VoltDBExpression generatePredicate() {
        return generateExpression(Kind.BOOLEAN);
    }

    @Override
    public VoltDBExpression negatePredicate(VoltDBExpression predicate) {
        return new VoltDBUnaryPrefixOperation(predicate, VoltDBUnaryPrefixOperator.NOT);
    }

    @Override
    public VoltDBExpression isNull(VoltDBExpression expr) {
        return new VoltDBUnaryPostfixOperation(expr, VoltDBUnaryPostfixOperator.IS_NULL);
    }

    // A WHERE predicate always references a column. VoltDB does not support a join filter that depends on none of the
    // joined tables, and its planner crashes on a WHERE clause that folds to a constant NULL (ENG-30495).
    @Override
    public VoltDBExpression generateBooleanExpression() {
        return generatePredicateWithColumn();
    }

    private VoltDBExpression generatePredicateWithColumn() {
        for (int i = 0; i < 100; i++) {
            VoltDBExpression predicate = generateExpression(Kind.BOOLEAN);
            String text = VoltDBToStringVisitor.asString(predicate);
            if (columns.stream().anyMatch(c -> text.contains(c.getFullQualifiedName()))) {
                return predicate;
            }
        }
        throw new IgnoreMeException();
    }

    @Override
    public VoltDBSelect generateSelect() {
        return new VoltDBSelect();
    }

    @Override
    public List<VoltDBJoin> getRandomJoinClauses() {
        return joins;
    }

    @Override
    public List<VoltDBExpression> getTableRefs() {
        return fromTables.stream().map(VoltDBTableReference::new).collect(Collectors.toList());
    }

    @Override
    public String generateOptimizedQueryString(VoltDBSelect select, VoltDBExpression whereCondition,
            boolean shouldUseAggregate) {
        if (shouldUseAggregate) {
            select.setFetchColumns(List.of(new VoltDBConstant("COUNT(*)")));
        } else {
            select.setFetchColumns(columns.stream().map(VoltDBColumnReference::new).collect(Collectors.toList()));
            if (Randomly.getBooleanWithSmallProbability()) {
                select.setOrderByClauses(generateOrderBys());
            }
        }
        select.setWhereClause(whereCondition);
        return select.asString();
    }

    @Override
    public List<VoltDBExpression> generateOrderBys() {
        List<VoltDBColumn> orderable = columns.stream().filter(c -> canOrder(Kind.of(c.getType().getType())))
                .collect(Collectors.toList());
        if (orderable.isEmpty()) {
            return List.of();
        }
        return Randomly.nonEmptySubset(orderable).stream().map(VoltDBColumnReference::new).collect(Collectors.toList());
    }

    @Override
    public String generateUnoptimizedQueryString(VoltDBSelect select, VoltDBExpression whereCondition) {
        // The predicate moves from WHERE into the projection, where the planner cannot use it to filter. CASE counts
        // a row only when the predicate is TRUE, as WHERE does. COALESCE turns the SUM of no rows (NULL) into 0.
        select.setFetchColumns(List.of(new VoltDBConstant(
                "CASE WHEN " + VoltDBToStringVisitor.asString(whereCondition) + " THEN 1 ELSE 0 END AS c")));
        select.setWhereClause(null);
        select.setOrderByClauses(List.of());
        return "SELECT COALESCE(SUM(c), 0) FROM (" + select.asString() + ") AS res";
    }

}
