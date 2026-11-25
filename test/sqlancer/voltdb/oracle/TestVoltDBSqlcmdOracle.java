package sqlancer.voltdb.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

// How SQLCMD reads sqlcmd's csv (--output-format=csv --output-skip-metadata), as sqlcmd writes it
public class TestVoltDBSqlcmdOracle {

    @Test
    public void testQuotedFields() {
        assertEquals(List.of(List.of("\"1\"", "\"a,b \"q\" 's'\"", "\"\\N\"")),
                VoltDBSqlcmdOracle.parseCsvForTest("\"1\",\"a,b \"\"q\"\" 's'\",\"\\N\"\n"));
    }

    // a value with a line break stays one field of one record
    @Test
    public void testLineBreakInField() {
        assertEquals(List.of(List.of("\"6\"", "\"line1\nline2\""), List.of("\"7\"", "\"x\"")),
                VoltDBSqlcmdOracle.parseCsvForTest("\"6\",\"line1\nline2\"\n\"7\",\"x\"\n"));
    }

    // ENG-30895: a BOOLEAN is an empty field, unquoted; an empty string is quoted
    @Test
    public void testEmptyFields() {
        assertEquals(List.of(List.of("\"1\"", "", "\"\"")), VoltDBSqlcmdOracle.parseCsvForTest("\"1\",,\"\"\n"));
    }

    // a query whose only column is a BOOLEAN prints an empty line per row; no rows print nothing
    @Test
    public void testEmptyLines() {
        assertEquals(List.of(List.of(""), List.of("")), VoltDBSqlcmdOracle.parseCsvForTest("\n\n"));
        assertEquals(List.of(), VoltDBSqlcmdOracle.parseCsvForTest(""));
    }

    @Test
    public void testCrLf() {
        assertEquals(List.of(List.of("\"1\"", "\"2\""), List.of("\"3\"", "\"4\"")),
                VoltDBSqlcmdOracle.parseCsvForTest("\"1\",\"2\"\r\n\"3\",\"4\"\r\n"));
    }
}
