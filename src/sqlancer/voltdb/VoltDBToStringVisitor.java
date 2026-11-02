package sqlancer.voltdb;

import sqlancer.common.ast.newast.NewFunctionNode;
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

    // ENG-30506: in a parenthesized AND/OR list, an operand that starts with a function call after an IN predicate
    // crashes VoltDB's parser, e.g. (a IN (1) OR ABS(a) > 5); BETWEEN and IN print their left operand without
    // parentheses, so a function call is always printed in parentheses
    @Override
    public void visit(NewFunctionNode<VoltDBExpression, ?> funcCall) {
        sb.append("(");
        super.visit(funcCall);
        sb.append(")");
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
