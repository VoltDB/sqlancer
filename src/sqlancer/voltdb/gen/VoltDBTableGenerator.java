package sqlancer.voltdb.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.voltdb.VoltDBProvider.VoltDBGlobalState;
import sqlancer.voltdb.VoltDBSchema.VoltDBCompositeDataType;
import sqlancer.voltdb.VoltDBSchema.VoltDBDataType;

/**
 * CREATE TABLE, followed by PARTITION TABLE for about half the tables. Both go to VoltDB as one batch, so a table is
 * never seen replicated before it is partitioned.
 *
 * <p>
 * VoltDB rules the generator follows: the partitioning column is NOT NULL and of an integer, VARCHAR or VARBINARY type,
 * and a PRIMARY KEY or UNIQUE constraint on a partitioned table includes the partitioning column (otherwise the
 * constraint is ASSUMEUNIQUE, which VoltDB enforces only within a partition).
 */
public final class VoltDBTableGenerator {

    private VoltDBTableGenerator() {
    }

    private static final class Column {
        private final String name;
        private final VoltDBCompositeDataType type;
        private boolean notNull;

        Column(String name, VoltDBCompositeDataType type, boolean notNull) {
            this.name = name;
            this.type = type;
            this.notNull = notNull;
        }
    }

    public static SQLQueryAdapter getQuery(VoltDBGlobalState globalState) {
        String table = globalState.getFreeTableName();
        List<Column> columns = new ArrayList<>();
        int nrColumns = (int) Randomly.getNotCachedInteger(1, 7);
        for (int i = 0; i < nrColumns; i++) {
            columns.add(new Column("c" + i, VoltDBCompositeDataType.getRandom(VoltDBDataType.getRandom()),
                    Randomly.getBooleanWithRatherLowProbability()));
        }

        Column partitionColumn = null;
        if (Randomly.getBoolean()) {
            int i = (int) Randomly.getNotCachedInteger(0, columns.size());
            if (!columns.get(i).type.getType().isPartitionable()) {
                columns.set(i, new Column(columns.get(i).name,
                        VoltDBCompositeDataType.getRandom(VoltDBDataType.getRandomPartitionable()), true));
            }
            partitionColumn = columns.get(i);
            partitionColumn.notNull = true;
        }

        StringBuilder sb = new StringBuilder("CREATE TABLE ").append(table).append(" (");
        sb.append(columns.stream().map(c -> c.name + " " + c.type + (c.notNull ? " NOT NULL" : ""))
                .collect(Collectors.joining(", ")));
        if (Randomly.getBooleanWithRatherLowProbability() || Randomly.getBoolean()) {
            appendKey(sb, columns, partitionColumn, true);
        }
        if (Randomly.getBooleanWithRatherLowProbability()) {
            appendKey(sb, columns, partitionColumn, false);
        }
        sb.append(");");
        if (partitionColumn != null) {
            sb.append(" PARTITION TABLE ").append(table).append(" ON COLUMN ").append(partitionColumn.name).append(';');
        }
        return new SQLQueryAdapter(sb.toString(), ExpectedErrors.newErrors().build(), true);
    }

    // Appends PRIMARY KEY (primary) or a UNIQUE / ASSUMEUNIQUE constraint over a random column subset.
    private static void appendKey(StringBuilder sb, List<Column> columns, Column partitionColumn, boolean primary) {
        List<Column> key = new ArrayList<>(Randomly.nonEmptySubset(columns));
        boolean hasPartitionColumn = partitionColumn == null || key.contains(partitionColumn);
        if (primary && !hasPartitionColumn) {
            key.add(partitionColumn);
            hasPartitionColumn = true;
        }
        sb.append(", ");
        if (primary) {
            sb.append("PRIMARY KEY");
        } else {
            sb.append(hasPartitionColumn ? "UNIQUE" : "ASSUMEUNIQUE");
        }
        sb.append(" (").append(key.stream().map(c -> c.name).collect(Collectors.joining(", "))).append(')');
    }

}
