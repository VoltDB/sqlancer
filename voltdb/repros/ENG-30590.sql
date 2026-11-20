-- ticket: ENG-30590
-- summary: A CASE with two WHENs and a DECIMAL literal result fails to compile ("Unable to determine cast type for 'VoltType.NUMERIC'")
-- bug: contains: Unable to determine cast type
-- fixed: contains: 1.000000000000
CREATE TABLE q (k INTEGER NOT NULL, PRIMARY KEY (k));
INSERT INTO q VALUES (1);
-- check
SELECT CASE WHEN k = 1 THEN 1 WHEN k = 2 THEN 2.5 END FROM q;
