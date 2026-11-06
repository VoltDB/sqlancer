package sqlancer.voltdb.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import sqlancer.voltdb.VoltDBSchema.VoltDBDataType;
import sqlancer.voltdb.oracle.VoltDBExpressionEvaluator.Binary;

// How PARAMS writes a constant as a parameter: CAST(? AS <literal type>), or a bare ? where a column's type holds it
public class TestVoltDBParameterOracle {

    @Test
    public void testLiteralType() {
        assertEquals("INTEGER", VoltDBParameterOracle.literalType(5L));
        assertEquals("INTEGER", VoltDBParameterOracle.literalType((long) -Integer.MAX_VALUE));
        assertEquals("BIGINT", VoltDBParameterOracle.literalType(3000000000L));
        assertEquals("DECIMAL", VoltDBParameterOracle.literalType(new BigDecimal("1.5")));
        assertEquals("FLOAT", VoltDBParameterOracle.literalType(1.5));
        assertEquals("VARCHAR", VoltDBParameterOracle.literalType("a"));
        assertEquals("VARBINARY", VoltDBParameterOracle.literalType(new Binary(new byte[] { 1 })));
        assertEquals("TIMESTAMP", VoltDBParameterOracle.literalType(LocalDateTime.of(2000, 1, 1, 0, 0)));
    }

    // VoltDB's integer types reserve their minimum for NULL, and a parameter of a column's type must hold the value
    @Test
    public void testFits() {
        assertTrue(VoltDBParameterOracle.fits(127L, VoltDBDataType.TINYINT));
        assertTrue(VoltDBParameterOracle.fits(-127L, VoltDBDataType.TINYINT));
        assertFalse(VoltDBParameterOracle.fits(-128L, VoltDBDataType.TINYINT));
        assertFalse(VoltDBParameterOracle.fits(300L, VoltDBDataType.TINYINT));
        assertTrue(VoltDBParameterOracle.fits(300L, VoltDBDataType.SMALLINT));
        assertFalse(VoltDBParameterOracle.fits(3000000000L, VoltDBDataType.INTEGER));
        assertTrue(VoltDBParameterOracle.fits(3000000000L, VoltDBDataType.BIGINT));
        assertFalse(VoltDBParameterOracle.fits(5L, VoltDBDataType.DECIMAL));
        assertFalse(VoltDBParameterOracle.fits(5L, VoltDBDataType.FLOAT));
        assertFalse(VoltDBParameterOracle.fits(new BigDecimal("1.5"), VoltDBDataType.INTEGER));
        assertTrue(VoltDBParameterOracle.fits(new BigDecimal("1.5"), VoltDBDataType.DECIMAL));
        assertTrue(VoltDBParameterOracle.fits(1.5, VoltDBDataType.FLOAT));
        assertFalse(VoltDBParameterOracle.fits(1.5, VoltDBDataType.DECIMAL));
        assertTrue(VoltDBParameterOracle.fits("x", VoltDBDataType.VARCHAR));
        assertTrue(VoltDBParameterOracle.fits(new Binary(new byte[] { 1 }), VoltDBDataType.VARBINARY));
        assertTrue(VoltDBParameterOracle.fits(LocalDateTime.of(2000, 1, 1, 0, 0), VoltDBDataType.TIMESTAMP));
        assertFalse(VoltDBParameterOracle.fits(LocalDateTime.of(2000, 1, 1, 0, 0), VoltDBDataType.DATE));
    }
}
