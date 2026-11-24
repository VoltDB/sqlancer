package sqlancer.voltdb.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class TestVoltDBConstantGenerator {

    @Test
    public void testQuoteDoublesQuotes() {
        assertEquals("'it''s'", VoltDBConstantGenerator.quote("it's"));
        assertEquals("''", VoltDBConstantGenerator.quote(""));
    }

    // ENG-30562: no backslash right before a quote, which VoltDB reads as escaping it
    @Test
    public void testQuoteAvoidsBackslashBeforeQuote() {
        assertEquals("'a/'", VoltDBConstantGenerator.quote("a\\"));
        assertEquals("'/'", VoltDBConstantGenerator.quote("\\"));
        assertEquals("'a/''b'", VoltDBConstantGenerator.quote("a\\'b"));
        assertEquals("'a\\b'", VoltDBConstantGenerator.quote("a\\b"));
        assertEquals("'\\N'", VoltDBConstantGenerator.quote("\\N"));
    }
}
