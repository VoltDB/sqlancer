package sqlancer.voltdb.gen;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.voltdb.VoltDBErrors;
import sqlancer.voltdb.VoltDBProvider.VoltDBGlobalState;
import sqlancer.voltdb.VoltDBSchema.VoltDBColumn;
import sqlancer.voltdb.VoltDBSchema.VoltDBTable;

/** Single-row INSERT. VoltDB has no multi-row VALUES, and every NOT NULL column is always given a value. */
public final class VoltDBInsertGenerator {

    private VoltDBInsertGenerator() {
    }

    public static SQLQueryAdapter getQuery(VoltDBGlobalState globalState) {
        VoltDBTable table = globalState.getSchema().getRandomTable();
        List<VoltDBColumn> columns = new ArrayList<>();
        for (VoltDBColumn c : table.getColumns()) {
            if (!c.isNullable() || Randomly.getBoolean()) {
                columns.add(c);
            }
        }
        if (columns.isEmpty()) {
            columns.add(Randomly.fromList(table.getColumns()));
        }
        Randomly r = globalState.getRandomly();
        String sql = "INSERT INTO " + table.getName() + " ("
                + columns.stream().map(VoltDBColumn::getName).collect(Collectors.joining(", ")) + ") VALUES ("
                + columns.stream().map(c -> VoltDBConstantGenerator.generate(r, c.getType(), c.isNullable()))
                        .collect(Collectors.joining(", "))
                + ")";
        ExpectedErrors errors = ExpectedErrors.newErrors().build();
        VoltDBErrors.addInsertErrors(errors);
        return new SQLQueryAdapter(sql, errors);
    }

}
