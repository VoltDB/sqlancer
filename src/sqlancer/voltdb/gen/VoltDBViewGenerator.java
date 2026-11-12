package sqlancer.voltdb.gen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.voltdb.VoltDBErrors;
import sqlancer.voltdb.VoltDBProvider.VoltDBGlobalState;
import sqlancer.voltdb.VoltDBSchema.VoltDBColumn;
import sqlancer.voltdb.VoltDBSchema.VoltDBDataType;
import sqlancer.voltdb.VoltDBSchema.VoltDBTable;
import sqlancer.voltdb.VoltDBToStringVisitor;
import sqlancer.voltdb.gen.VoltDBExpressionGenerator.Kind;

/**
 * A materialized view: rows grouped by zero to two columns, with COUNT(*) and up to three more of COUNT, SUM, MIN and
 * MAX. VoltDB keeps it up to date as its tables change. The view's defining query is recorded in the global state, for
 * the VIEWS oracle to compare the view with.
 *
 * <p>
 * A third of the views read an inner join of two or three tables; the rest read one table. VoltDB keeps the two kinds
 * up to date with different code: a view of one table by triggers on that table, a view over a join by a handler that
 * runs the view's query for each changed row. A view over a join has no self-join and no outer join (VoltDB supports
 * neither), joins at most one partitioned table to the others unless they are joined on their partitioning columns, and
 * its ON conditions compare two columns, are random predicates, or both. It has a random WHERE clause a third of the
 * time. A view of one table has a WHERE clause only when --avoid-known-bugs is false (ENG-30583, which views over joins
 * do not hit).
 */
public final class VoltDBViewGenerator {

    private VoltDBViewGenerator() {
    }

    public static SQLQueryAdapter getQuery(VoltDBGlobalState globalState) {
        if (globalState.getSchema().getDatabaseTables().size() > 1 && Randomly.fromOptions(true, false, false)) {
            List<VoltDBTable> tables = joinedTables(globalState);
            if (tables.size() > 1) {
                return joinView(globalState, tables);
            }
        }
        return tableView(globalState);
    }

    private static SQLQueryAdapter tableView(VoltDBGlobalState globalState) {
        VoltDBTable table = globalState.getSchema().getRandomTable();
        boolean avoidKnownBugs = globalState.getDbmsSpecificOptions().avoidKnownBugs;
        List<VoltDBColumn> grouping = grouping(table.getColumns());
        List<String> outputs = outputs(globalState, grouping, table.getColumns(), VoltDBColumn::getName);
        StringBuilder query = new StringBuilder("SELECT ").append(String.join(", ", outputs)).append(" FROM ")
                .append(table.getName());
        // ENG-30583: a view's WHERE clause can block later DDL: a FLOAT constant such as 12.5E0 blocks all of it, and a
        // predicate an index could serve blocks that index (LIKE, BETWEEN). Unless --avoid-known-bugs is false, views
        // of one table have no WHERE clause.
        if (!avoidKnownBugs && Randomly.getBoolean()) {
            VoltDBExpressionGenerator gen = new VoltDBExpressionGenerator(globalState).setColumns(table.getColumns());
            query.append(" WHERE ").append(VoltDBToStringVisitor.asString(gen.generateBooleanExpression()));
        }
        appendGroupBy(query, grouping, VoltDBColumn::getName);
        return create(globalState, List.of(table.getName()), outputs.size(), query.toString());
    }

    private static SQLQueryAdapter joinView(VoltDBGlobalState globalState, List<VoltDBTable> tables) {
        List<VoltDBColumn> all = new ArrayList<>();
        tables.forEach(t -> all.addAll(t.getColumns()));
        List<VoltDBColumn> grouping = grouping(all);
        if (globalState.getDbmsSpecificOptions().avoidKnownBugs) {
            // ENG-30529: a multi-partition GROUP BY groups by only one of two columns of the same name, such as t0.c0
            // and t1.c0, merging groups; the view is right, but the VIEWS oracle's run of its query is not, so unless
            // --avoid-known-bugs is false the grouping columns have distinct names
            List<String> names = new ArrayList<>();
            grouping = grouping.stream().filter(c -> !names.contains(c.getName()) && names.add(c.getName()))
                    .collect(Collectors.toList());
            // ENG-30255 (fixed after 16.0.0): a join view grouped by a DECIMAL that is NULL aborts a 16.0.0 server
            // (lab build #48's mirror); single-table views do not. So with a mirror (--mirror, normally 16.0.0) or
            // an in-service upgrade from it, a join view does not group by a DECIMAL column
            String mirror = globalState.getDbmsSpecificOptions().mirror;
            if (mirror != null && !mirror.isBlank() || globalState.getDbmsSpecificOptions().upgrades) {
                grouping = grouping.stream().filter(c -> c.getType().getType() != VoltDBDataType.DECIMAL)
                        .collect(Collectors.toList());
            }
        }
        List<String> outputs = outputs(globalState, grouping, all, VoltDBColumn::getFullQualifiedName);
        StringBuilder query = new StringBuilder("SELECT ").append(String.join(", ", outputs)).append(" FROM ")
                .append(tables.get(0).getName());
        List<VoltDBColumn> joined = new ArrayList<>(tables.get(0).getColumns());
        VoltDBTable partitioned = tables.get(0).isPartitioned() ? tables.get(0) : null;
        for (VoltDBTable t : tables.subList(1, tables.size())) {
            joined.addAll(t.getColumns());
            String on;
            if (t.isPartitioned() && partitioned != null) {
                // VoltDB keeps a view of partitioned tables only when their join puts every row in one partition, so
                // they are joined on their partitioning columns and nothing else
                on = partitionColumn(partitioned).getFullQualifiedName() + " = "
                        + partitionColumn(t).getFullQualifiedName();
            } else {
                on = joinCondition(globalState, t, joined);
                if (t.isPartitioned()) {
                    partitioned = t;
                }
            }
            query.append(" JOIN ").append(t.getName()).append(" ON ").append(on);
        }
        if (Randomly.fromOptions(true, false, false)) {
            VoltDBExpressionGenerator gen = new VoltDBExpressionGenerator(globalState).setColumns(all);
            query.append(" WHERE ").append(VoltDBToStringVisitor.asString(gen.generateBooleanExpression()));
        }
        appendGroupBy(query, grouping, VoltDBColumn::getFullQualifiedName);
        return create(globalState, tables.stream().map(VoltDBTable::getName).collect(Collectors.toList()),
                outputs.size(), query.toString());
    }

    // Two or three distinct tables (VoltDB's views have no self-join): any replicated tables, and at most one
    // partitioned table unless the others can be joined with it on their partitioning columns
    private static List<VoltDBTable> joinedTables(VoltDBGlobalState globalState) {
        List<VoltDBTable> candidates = new ArrayList<>(globalState.getSchema().getDatabaseTables());
        Collections.shuffle(candidates, new Random(globalState.getRandomly().getInteger()));
        int size = Randomly.fromOptions(2, 2, 3);
        boolean avoidKnownBugs = globalState.getDbmsSpecificOptions().avoidKnownBugs;
        List<VoltDBTable> tables = new ArrayList<>();
        VoltDBTable partitioned = null;
        for (VoltDBTable t : candidates) {
            if (tables.size() == size) {
                break;
            }
            if (!t.isPartitioned()) {
                tables.add(t);
            } else if (partitioned == null) {
                partitioned = t;
                tables.add(t);
            } else if (canPartitionJoin(partitioned, t, avoidKnownBugs)) {
                tables.add(t);
            }
        }
        return tables;
    }

    // A comparison of two columns of the same kind, one from the table being joined and one from those before it, a
    // random predicate over all their columns, or both. Random rows rarely have equal values, so most comparisons are
    // <, <=, >, >= or <>, which many pairs of rows pass.
    private static String joinCondition(VoltDBGlobalState globalState, VoltDBTable t, List<VoltDBColumn> joined) {
        List<VoltDBColumn[]> pairs = new ArrayList<>();
        for (VoltDBColumn a : t.getColumns()) {
            for (VoltDBColumn b : joined) {
                Kind kind = Kind.fromType(a.getType().getType());
                // VoltDB orders BOOLEAN values TRUE < FALSE (ENG-30493), so they are compared for equality only
                if (b.getTable() != t && kind == Kind.fromType(b.getType().getType())) {
                    pairs.add(new VoltDBColumn[] { b, a });
                }
            }
        }
        String comparison = null;
        if (!pairs.isEmpty()) {
            VoltDBColumn[] pair = Randomly.fromList(pairs);
            // ENG-30665: the view's maintenance reads a narrower integer column through its index with keys from a
            // wider one, and a key below the narrower type's range matches every row (ENG-30511), so the view loses
            // track of its rows and the server crashes; while avoiding known bugs, columns of different types are
            // compared with = and <> only
            boolean sameType = pair[0].getType().getType() == pair[1].getType().getType();
            String operator = Kind.fromType(pair[0].getType().getType()) == Kind.BOOLEAN ? "="
                    : !sameType && globalState.getDbmsSpecificOptions().avoidKnownBugs ? Randomly.fromOptions("=", "<>")
                            : Randomly.fromOptions("=", "<", "<=", ">", ">=", "<>");
            comparison = pair[0].getFullQualifiedName() + " " + operator + " " + pair[1].getFullQualifiedName();
            if (Randomly.getBoolean()) {
                return comparison;
            }
        }
        VoltDBExpressionGenerator gen = new VoltDBExpressionGenerator(globalState).setColumns(joined);
        String predicate = VoltDBToStringVisitor.asString(gen.generateBooleanExpression());
        return comparison == null || Randomly.getBoolean() ? predicate : comparison + " AND " + predicate;
    }

    private static List<VoltDBColumn> grouping(List<VoltDBColumn> columns) {
        // VoltDB does not GROUP BY a BOOLEAN expression
        List<VoltDBColumn> groupable = columns.stream()
                .filter(c -> Kind.fromType(c.getType().getType()) != Kind.BOOLEAN).collect(Collectors.toList());
        List<VoltDBColumn> grouping = Randomly.subset(groupable);
        return grouping.subList(0, Math.min(2, grouping.size()));
    }

    private static List<String> outputs(VoltDBGlobalState globalState, List<VoltDBColumn> grouping,
            List<VoltDBColumn> columns, Function<VoltDBColumn, String> name) {
        boolean avoidKnownBugs = globalState.getDbmsSpecificOptions().avoidKnownBugs;
        List<String> outputs = grouping.stream().map(name).collect(Collectors.toList());
        outputs.add("COUNT(*)");
        for (int i = Randomly.smallNumber(); i >= 0 && outputs.size() < grouping.size() + 4; i--) {
            VoltDBColumn c = Randomly.fromList(columns);
            Kind kind = Kind.fromType(c.getType().getType());
            List<String> functions = new ArrayList<>(List.of("COUNT"));
            // ENG-30584: deleting a group's last non-NULL value leaves 0 in the view's SUM instead of NULL, so unless
            // --avoid-known-bugs is false, a view sums NOT NULL columns only
            // ENG-30588: an UPDATE of an indexed column whose view SUM overflows fails, but leaves the row out of the
            // index, and the next UPDATE of the row crashes the node, so unless --avoid-known-bugs is false, a view
            // sums TINYINT, SMALLINT and INTEGER columns only, whose sums can't overflow
            if (kind == Kind.NUMERIC && !(avoidKnownBugs && (c.isNullable() || !sumCannotOverflow(c)))) {
                functions.add("SUM");
            }
            if (kind != Kind.BOOLEAN) {
                // ENG-30493: VoltDB orders BOOLEAN values TRUE < FALSE, so no MIN or MAX of them
                functions.add("MIN");
                functions.add("MAX");
            }
            String aggregate = Randomly.fromList(functions) + "(" + name.apply(c) + ")";
            // ENG-30582: VoltDB's view rewriter fails ("Duplicate key") on a matching query with the same aggregate
            // twice, and the view's own query matches it
            if (!(avoidKnownBugs && outputs.contains(aggregate))) {
                outputs.add(aggregate);
            }
        }
        return outputs;
    }

    private static void appendGroupBy(StringBuilder query, List<VoltDBColumn> grouping,
            Function<VoltDBColumn, String> name) {
        if (!grouping.isEmpty()) {
            query.append(" GROUP BY ").append(grouping.stream().map(name).collect(Collectors.joining(", ")));
        }
    }

    private static SQLQueryAdapter create(VoltDBGlobalState globalState, List<String> tables, int width, String query) {
        String name = globalState.getFreeViewName();
        List<String> columns = new ArrayList<>();
        for (int i = 0; i < width; i++) {
            columns.add("C" + i);
        }
        globalState.addView(name, tables, query);
        ExpectedErrors errors = ExpectedErrors.newErrors().build();
        VoltDBErrors.addViewErrors(errors);
        return new SQLQueryAdapter("CREATE VIEW " + name + " (" + String.join(", ", columns) + ") AS " + query, errors,
                true);
    }

    // VoltDB sums TINYINT, SMALLINT and INTEGER values as BIGINT, which no realistic number of rows can overflow
    private static boolean sumCannotOverflow(VoltDBColumn c) {
        VoltDBDataType type = c.getType().getType();
        return type == VoltDBDataType.TINYINT || type == VoltDBDataType.SMALLINT || type == VoltDBDataType.INTEGER;
    }

    private static VoltDBColumn partitionColumn(VoltDBTable t) {
        return t.getColumns().stream().filter(c -> c.getName().equals(t.getPartitionColumn())).findFirst()
                .orElseThrow(() -> new AssertionError(t.getName()));
    }

    // ENG-12656: an index lookup on a VARBINARY(n) column fails for a longer value, so unless --avoid-known-bugs is
    // false, VARBINARY partitioning columns are joined only when they have the same size
    private static boolean canPartitionJoin(VoltDBTable anchor, VoltDBTable t, boolean avoidKnownBugs) {
        VoltDBColumn a = partitionColumn(anchor);
        VoltDBColumn b = partitionColumn(t);
        Kind kind = Kind.fromType(a.getType().getType());
        if (kind != Kind.fromType(b.getType().getType())) {
            return false;
        }
        return kind != Kind.VARBINARY || !avoidKnownBugs || a.getType().getSize() == b.getType().getSize();
    }

}
