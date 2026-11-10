-- ticket: ENG-30513
-- summary: Comparing FLOAT with a DECIMAL of magnitude 2^63 or more gives wrong results (DECIMAL wraps through 64 bits)
-- bug: SELECT COUNT(*) AS n FROM fd WHERE f <= 10000000000000000000.0;
-- bug: N
-- bug: 0
-- bug: (1 rows)
-- fixed: SELECT COUNT(*) AS n FROM fd WHERE f <= 10000000000000000000.0;
-- fixed: N
-- fixed: 1
-- fixed: (1 rows)
CREATE TABLE fd (f FLOAT, d DECIMAL);
INSERT INTO fd VALUES (-51.8E0, 5);
-- check
SELECT COUNT(*) AS n FROM fd WHERE f <= 10000000000000000000.0;
