package sqlancer.voltdb.ast;

import java.util.List;

import sqlancer.common.ast.newast.NewFunctionNode;

public class VoltDBFunction extends NewFunctionNode<VoltDBExpression, VoltDBFunction.VoltDBFunctionName>
        implements VoltDBExpression {

    // Functions the generator calls; the name is printed as written.
    public enum VoltDBFunctionName {
        // numeric
        ABS, FLOOR, CEILING, MOD, CHAR_LENGTH,
        // string
        SUBSTRING, UPPER, LOWER, TRIM, LTRIM, RTRIM,
        // any type
        COALESCE
    }

    public VoltDBFunction(List<VoltDBExpression> args, VoltDBFunctionName func) {
        super(args, func);
    }

}
