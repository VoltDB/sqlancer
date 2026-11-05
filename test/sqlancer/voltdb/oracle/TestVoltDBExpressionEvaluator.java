package sqlancer.voltdb.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import sqlancer.voltdb.ast.VoltDBBetweenOperation;
import sqlancer.voltdb.ast.VoltDBBinaryOperation;
import sqlancer.voltdb.ast.VoltDBBinaryOperation.VoltDBBinaryOperator;
import sqlancer.voltdb.ast.VoltDBConstant;
import sqlancer.voltdb.ast.VoltDBExpression;
import sqlancer.voltdb.ast.VoltDBFunction;
import sqlancer.voltdb.ast.VoltDBFunction.VoltDBFunctionName;
import sqlancer.voltdb.ast.VoltDBInOperation;
import sqlancer.voltdb.ast.VoltDBUnaryPostfixOperation;
import sqlancer.voltdb.ast.VoltDBUnaryPostfixOperation.VoltDBUnaryPostfixOperator;
import sqlancer.voltdb.ast.VoltDBUnaryPrefixOperation;
import sqlancer.voltdb.ast.VoltDBUnaryPrefixOperation.VoltDBUnaryPrefixOperator;
import sqlancer.voltdb.oracle.VoltDBExpressionEvaluator.Binary;
import sqlancer.voltdb.oracle.VoltDBExpressionEvaluator.Unpredictable;

// The expected values are VoltDB's, as observed on 16.1 with column operands (constants can fold differently)
public class TestVoltDBExpressionEvaluator {

    private static final VoltDBExpressionEvaluator EVALUATOR = new VoltDBExpressionEvaluator(Map.of(), false);
    private static final VoltDBExpression NULL_INT = c("CAST(NULL AS INTEGER)");
    private static final VoltDBExpression NULL_BOOL = c("CAST(NULL AS BOOLEAN)");

    private static VoltDBExpression c(String text) {
        return new VoltDBConstant(text);
    }

    private static VoltDBExpression op(VoltDBExpression l, VoltDBBinaryOperator op, VoltDBExpression r) {
        return new VoltDBBinaryOperation(l, r, op);
    }

    private static Object eval(VoltDBExpression e) {
        return EVALUATOR.evaluate(e);
    }

    private static BigDecimal dec(String s) {
        return new BigDecimal(s).setScale(VoltDBExpressionEvaluator.DECIMAL_SCALE);
    }

    @Test
    public void testConstants() {
        assertEquals(true, eval(c("(1 = 1)")));
        assertEquals(false, eval(c("(1 = 0)")));
        assertEquals(-5L, eval(c("(-5)")));
        assertEquals(2.5, eval(c("2.5E0")));
        assertEquals(dec("1.5"), eval(c("1.5")));
        assertEquals("it's", eval(c("'it''s'")));
        // a lone surrogate reaches VoltDB as '?', as the driver encodes SQL text in UTF-8
        assertEquals("a?", eval(c("'a\uD800'")));
        assertEquals(new Binary(new byte[] { 0x0A, (byte) 0xFF }), eval(c("x'0AFF'")));
        assertEquals(new Binary(new byte[0]), eval(c("x''")));
        assertEquals(LocalDateTime.of(2020, 1, 2, 3, 4, 5, 123_456_000),
                eval(c("CAST('2020-01-02 03:04:05.123456' AS TIMESTAMP)")));
        assertEquals(LocalDateTime.of(2020, 1, 2, 0, 0), eval(c("CAST('2020-01-02' AS DATE)")));
        assertNull(eval(NULL_INT));
    }

    @Test
    public void testIntegerArithmetic() {
        assertEquals(-3L, eval(op(c("(-7)"), VoltDBBinaryOperator.DIVIDE, c("2"))));
        assertEquals(300L, eval(op(c("100"), VoltDBBinaryOperator.MULTIPLY, c("3"))));
        assertNull(eval(op(NULL_INT, VoltDBBinaryOperator.ADD, c("1"))));
        // overflow and division by zero are errors
        assertThrows(Unpredictable.class, () -> eval(op(c("9223372036854775807"), VoltDBBinaryOperator.ADD, c("1"))));
        assertThrows(Unpredictable.class, () -> eval(op(c("100"), VoltDBBinaryOperator.DIVIDE, c("0"))));
        assertThrows(Unpredictable.class, () -> eval(op(NULL_INT, VoltDBBinaryOperator.DIVIDE, c("0"))));
        // Long.MIN_VALUE is VoltDB's BIGINT NULL
        assertThrows(Unpredictable.class,
                () -> eval(op(c("(-9223372036854775807)"), VoltDBBinaryOperator.SUBTRACT, c("1"))));
    }

    @Test
    public void testFoldedIntegerSums() {
        VoltDBExpression sum = op(c("2147483647"), VoltDBBinaryOperator.ADD, c("1"));
        assertEquals(2147483648L, eval(sum));
        // ENG-30547: unpredictable while the bug is avoided
        VoltDBExpressionEvaluator avoiding = new VoltDBExpressionEvaluator(Map.of(), true);
        assertThrows(Unpredictable.class, () -> avoiding.evaluate(sum));
        assertEquals(2147483647L, avoiding.evaluate(op(c("2147483646"), VoltDBBinaryOperator.ADD, c("1"))));
        assertEquals(4294967296L, avoiding.evaluate(op(c("65536"), VoltDBBinaryOperator.MULTIPLY, c("65536"))));
    }

    @Test
    public void testFoldedStringComparisons() {
        VoltDBExpression padded = op(c("'a'"), VoltDBBinaryOperator.EQUALS, c("'a '"));
        assertEquals(false, eval(padded));
        // ENG-30549: unpredictable while the bug is avoided, where trailing spaces decide
        VoltDBExpressionEvaluator avoiding = new VoltDBExpressionEvaluator(Map.of(), true);
        assertThrows(Unpredictable.class, () -> avoiding.evaluate(padded));
        assertThrows(Unpredictable.class,
                () -> avoiding.evaluate(op(c("'a'"), VoltDBBinaryOperator.SMALLER, c("'a\t'"))));
        assertEquals(true, avoiding.evaluate(op(c("'a'"), VoltDBBinaryOperator.SMALLER, c("'ab'"))));
        assertThrows(Unpredictable.class,
                () -> avoiding.evaluate(new VoltDBInOperation(c("''"), List.of(c("' '")), false)));
    }

    @Test
    public void testDecimalArithmetic() {
        // multiplication and division truncate toward zero at scale 12
        assertEquals(dec("0.500000000007"), eval(op(c("1.000000000015"), VoltDBBinaryOperator.MULTIPLY, c("0.5"))));
        assertEquals(dec("0.000000000012"), eval(op(c("0.000000000025"), VoltDBBinaryOperator.MULTIPLY, c("0.5"))));
        assertEquals(dec("-0.500000000002"), eval(op(c("(-1.000000000005)"), VoltDBBinaryOperator.MULTIPLY, c("0.5"))));
        assertEquals(dec("0.666666666666"), eval(op(c("2.0"), VoltDBBinaryOperator.DIVIDE, c("3.0"))));
        assertEquals(dec("-0.666666666666"), eval(op(c("(-2.0)"), VoltDBBinaryOperator.DIVIDE, c("3.0"))));
        assertEquals(dec("0.142857142859"), eval(op(c("1.000000000015"), VoltDBBinaryOperator.DIVIDE, c("7"))));
        assertEquals(dec("6.000000000001"), eval(op(c("1.000000000001"), VoltDBBinaryOperator.ADD, c("5"))));
        assertThrows(Unpredictable.class,
                () -> eval(op(c("99999999999999999999999999.0"), VoltDBBinaryOperator.ADD, c("1"))));
        assertThrows(Unpredictable.class, () -> eval(op(c("1.5"), VoltDBBinaryOperator.DIVIDE, c("0"))));
        // the EE's DECIMAL-to-double conversion is not modelled
        assertThrows(Unpredictable.class, () -> eval(op(c("1.5"), VoltDBBinaryOperator.ADD, c("2.5E0"))));
    }

    @Test
    public void testFloatArithmetic() {
        assertEquals(7.5, eval(op(c("2.5E0"), VoltDBBinaryOperator.ADD, c("5"))));
        assertThrows(Unpredictable.class, () -> eval(op(c("2.5E0"), VoltDBBinaryOperator.DIVIDE, c("0.0E0"))));
        assertThrows(Unpredictable.class,
                () -> eval(op(c("2.5E0"), VoltDBBinaryOperator.MULTIPLY, c("1.7976931348623157E308"))));
        // -Double.MAX_VALUE is VoltDB's FLOAT NULL
        assertThrows(Unpredictable.class,
                () -> eval(op(c("0"), VoltDBBinaryOperator.SUBTRACT, c("1.7976931348623157E308"))));
        assertEquals(2.5, eval(new VoltDBFunction(List.of(c("(-2.5E0)")), VoltDBFunctionName.ABS)));
        assertEquals(dec("1.5"), eval(new VoltDBFunction(List.of(c("(-1.5)")), VoltDBFunctionName.ABS)));
    }

    @Test
    public void testThreeValuedLogic() {
        assertEquals(false, eval(op(NULL_BOOL, VoltDBBinaryOperator.AND, c("(1 = 0)"))));
        assertNull(eval(op(NULL_BOOL, VoltDBBinaryOperator.AND, c("(1 = 1)"))));
        assertEquals(true, eval(op(NULL_BOOL, VoltDBBinaryOperator.OR, c("(1 = 1)"))));
        assertNull(eval(op(NULL_BOOL, VoltDBBinaryOperator.OR, c("(1 = 0)"))));
        assertNull(eval(new VoltDBUnaryPrefixOperation(NULL_BOOL, VoltDBUnaryPrefixOperator.NOT)));
        assertEquals(true, eval(new VoltDBUnaryPostfixOperation(NULL_INT, VoltDBUnaryPostfixOperator.IS_NULL)));
        assertEquals(false, eval(new VoltDBUnaryPostfixOperation(NULL_INT, VoltDBUnaryPostfixOperator.IS_NOT_NULL)));
        assertNull(eval(op(NULL_INT, VoltDBBinaryOperator.EQUALS, c("1"))));
        // VoltDB evaluates both operands, so an error on either side is an error even when the other decides
        assertThrows(Unpredictable.class, () -> eval(op(c("(1 = 0)"), VoltDBBinaryOperator.AND,
                op(op(c("1"), VoltDBBinaryOperator.DIVIDE, c("0")), VoltDBBinaryOperator.EQUALS, c("1")))));
    }

    @Test
    public void testComparisons() {
        assertEquals(true, eval(op(c("5"), VoltDBBinaryOperator.EQUALS, c("5.0"))));
        assertEquals(true, eval(op(c("0.0E0"), VoltDBBinaryOperator.EQUALS, c("(-0.0E0)"))));
        assertEquals(true, eval(op(c("'a'"), VoltDBBinaryOperator.SMALLER, c("'é'"))));
        assertEquals(true, eval(op(c("x'0A'"), VoltDBBinaryOperator.SMALLER, c("x'FF'"))));
        // a DATE is midnight of its day, whichever side it is on (ENG-30545 is VoltDB truncating the TIMESTAMP)
        VoltDBExpression date = c("CAST('2020-01-02' AS DATE)");
        VoltDBExpression noon = c("CAST('2020-01-02 12:00:00.000000' AS TIMESTAMP)");
        assertEquals(true, eval(op(date, VoltDBBinaryOperator.SMALLER, noon)));
        assertEquals(false, eval(op(date, VoltDBBinaryOperator.EQUALS, noon)));
        assertThrows(Unpredictable.class, () -> eval(op(c("(1 = 1)"), VoltDBBinaryOperator.SMALLER, c("(1 = 0)"))));
        assertThrows(Unpredictable.class, () -> eval(op(c("1.5"), VoltDBBinaryOperator.SMALLER, c("2.5E0"))));
        assertThrows(Unpredictable.class,
                () -> eval(op(c("9007199254740993"), VoltDBBinaryOperator.EQUALS, c("9.007199254740992E15"))));
    }

    @Test
    public void testBetweenAndIn() {
        assertEquals(true, eval(new VoltDBBetweenOperation(c("5"), c("1"), c("9"), true)));
        assertEquals(false, eval(new VoltDBBetweenOperation(c("5"), c("1"), c("9"), false)));
        assertEquals(false, eval(new VoltDBBetweenOperation(c("5"), c("9"), c("1"), true)));
        assertNull(eval(new VoltDBBetweenOperation(c("5"), NULL_INT, c("9"), true)));
        assertEquals(false, eval(new VoltDBBetweenOperation(c("5"), NULL_INT, c("4"), true)));
        assertEquals(true, eval(new VoltDBInOperation(c("5"), List.of(c("1"), c("5")), false)));
        assertEquals(true, eval(new VoltDBInOperation(c("5"), List.of(c("1"), c("2")), true)));
        // a NULL in the list makes a non-match NULL (ENG-30503 is VoltDB ignoring it)
        assertNull(eval(new VoltDBInOperation(c("5"), List.of(c("1"), NULL_INT), false)));
        assertNull(eval(new VoltDBInOperation(c("5"), List.of(c("1"), NULL_INT), true)));
        assertEquals(true, eval(new VoltDBInOperation(c("5"), List.of(NULL_INT, c("5")), false)));
        assertNull(eval(new VoltDBInOperation(NULL_INT, List.of(c("5")), false)));
    }

    @Test
    public void testLikeAndConcat() {
        assertTrue(VoltDBExpressionEvaluator.likePattern("a%").matcher("aBc").matches());
        assertFalse(VoltDBExpressionEvaluator.likePattern("A%").matcher("aBc").matches());
        assertTrue(VoltDBExpressionEvaluator.likePattern("_").matcher("é").matches());
        assertTrue(VoltDBExpressionEvaluator.likePattern("%").matcher("").matches());
        assertFalse(VoltDBExpressionEvaluator.likePattern("%_%").matcher("").matches());
        assertFalse(VoltDBExpressionEvaluator.likePattern("a\\%b").matcher("a%b").matches());
        assertTrue(VoltDBExpressionEvaluator.likePattern("a.*").matcher("a.*").matches());
        assertTrue(VoltDBExpressionEvaluator.likePattern("a%").matcher("a\nb").matches());
        assertEquals(false, eval(op(c("'abc'"), VoltDBBinaryOperator.NOT_LIKE, c("'%c'"))));
        assertEquals("abc", eval(op(c("'ab'"), VoltDBBinaryOperator.CONCAT, c("'c'"))));
        assertNull(eval(op(c("'ab'"), VoltDBBinaryOperator.CONCAT, c("CAST(NULL AS VARCHAR)"))));
    }

    @Test
    public void testSame() {
        assertTrue(VoltDBExpressionEvaluator.same(null, null));
        assertFalse(VoltDBExpressionEvaluator.same(null, 0L));
        assertTrue(VoltDBExpressionEvaluator.same(dec("1"), 1L));
        assertTrue(VoltDBExpressionEvaluator.same(0.0, -0.0));
        assertTrue(VoltDBExpressionEvaluator.same(VoltDBExpressionEvaluator.fromJdbc((byte) 5), 5L));
        assertTrue(VoltDBExpressionEvaluator.same(VoltDBExpressionEvaluator.fromJdbc(new byte[] { 1 }),
                new Binary(new byte[] { 1 })));
    }

}
