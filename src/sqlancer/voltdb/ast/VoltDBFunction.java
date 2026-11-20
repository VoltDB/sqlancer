package sqlancer.voltdb.ast;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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
        COALESCE,
        // FLOAT math (milestone 19): FLOAT results
        SQRT, EXP, LN, LOG10, POWER, SIN, COS,
        // parts of a DATE or TIMESTAMP (milestone 19): integer results
        YEAR, MONTH, DAY, HOUR, MINUTE, DAYOFWEEK, DAYOFYEAR, QUARTER, WEEK, WEEKDAY,
        // SINCE_EPOCH(unit, TIMESTAMP): BIGINT; TO_TIMESTAMP(unit, integer): TIMESTAMP; DATEADD(unit, n, t) and
        // TRUNCATE(unit, t): t's type (milestone 19)
        SINCE_EPOCH, TO_TIMESTAMP, DATEADD, TRUNCATE,
        // JSON in VARCHAR (milestone 19): FIELD(doc, name), ARRAY_ELEMENT(array, i) and SET_FIELD(doc, name, value)
        // return VARCHAR, ARRAY_LENGTH(array) an integer
        FIELD, ARRAY_ELEMENT, SET_FIELD, ARRAY_LENGTH,
        // milestone 31, strings: VARCHAR results
        LEFT, RIGHT, REPEAT, REPLACE, SPACE, CONCAT, HEX, BIN, CHAR, OVERLAY, STR, FORMAT_CURRENCY, FORMAT_TIMESTAMP,
        MASK_TEXT, MASK_INNER, MASK_OUTER, MASK_PARTIAL,
        // milestone 31, integer results: OCTET_LENGTH, POSITION and REGEXP_POSITION read strings, the bitwise
        // functions take and return BIGINT, SIGN any number, DATEDIFF(unit, t1, t2) two TIMESTAMPs
        OCTET_LENGTH, POSITION, REGEXP_POSITION, BITAND, BITOR, BITXOR, BITNOT, BIT_SHIFT_LEFT, BIT_SHIFT_RIGHT, SIGN,
        DATEDIFF,
        // milestone 31: ROUND(x, n) is DECIMAL; TIME_WINDOW(unit, n, t [, START | END]) and FROM_UNIXTIME(seconds)
        // are TIMESTAMPs
        ROUND, TIME_WINDOW, FROM_UNIXTIME,
        // CAST(x AS BIGINT), for the bitwise functions' operands: they reject any other integer type; CAST(x AS
        // DECIMAL),
        // for ROUND, STR and FORMAT_CURRENCY, which reject integers
        CAST_BIGINT, CAST_DECIMAL;

        private static final Set<VoltDBFunctionName> MODELLED = EnumSet.of(ABS, FLOOR, CEILING, MOD, CHAR_LENGTH,
                SUBSTRING, UPPER, LOWER, TRIM, LTRIM, RTRIM, COALESCE, LEFT, RIGHT, REPEAT, REPLACE, SPACE, CONCAT, HEX,
                BIN, OCTET_LENGTH, POSITION, BITAND, BITOR, BITXOR, BITNOT, BIT_SHIFT_LEFT, BIT_SHIFT_RIGHT, SIGN,
                CAST_BIGINT);

        // whether the PQS evaluator models the function: not the milestone 19 functions, nor the milestone 31 ones
        // whose results depend on formatting, regular expressions, masks or time
        public boolean isModelled() {
            return MODELLED.contains(this);
        }
    }

    public VoltDBFunction(List<VoltDBExpression> args, VoltDBFunctionName func) {
        super(args, func);
    }

}
