package sqlancer.voltdb;

import sqlancer.common.ast.newast.NewToStringVisitor;
import sqlancer.voltdb.ast.VoltDBConstant;
import sqlancer.voltdb.ast.VoltDBExpression;
import sqlancer.voltdb.ast.VoltDBJoin;
import sqlancer.voltdb.ast.VoltDBSelect;

public class VoltDBToStringVisitor extends NewToStringVisitor<VoltDBExpression> {

    @Override
    public void visitSpecific(VoltDBExpression expr) {
        if (expr instanceof VoltDBConstant) {
            sb.append(expr.toString());
        } else if (expr instanceof VoltDBSelect) {
            visit((VoltDBSelect) expr);
        } else if (expr instanceof VoltDBJoin) {
            visit((VoltDBJoin) expr);
        } else {
            throw new AssertionError(expr.getClass());
        }
    }

    private void visit(VoltDBJoin join) {
        sb.append(' ').append(join.getJoinType()).append(" JOIN ");
        visit((VoltDBExpression) join.getTable());
        sb.append(" ON ");
        visit(join.getOnCondition());
    }

    private void visit(VoltDBSelect select) {
        sb.append("SELECT ");
        visit(select.getFetchColumns());
        sb.append(" FROM ");
        // the joins attach to the last table of the FROM list, which is the one their ON conditions reference
        visit(select.getFromList());
        for (VoltDBExpression join : select.getJoinList()) {
            visit(join);
        }
        if (select.getWhereClause() != null) {
            sb.append(" WHERE ");
            visit(select.getWhereClause());
        }
        if (!select.getOrderByClauses().isEmpty()) {
            sb.append(" ORDER BY ");
            visit(select.getOrderByClauses());
        }
    }

    public static String asString(VoltDBExpression expr) {
        VoltDBToStringVisitor visitor = new VoltDBToStringVisitor();
        visitor.visit(expr);
        return visitor.get();
    }

}
