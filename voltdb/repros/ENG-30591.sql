-- ticket: ENG-30591
-- summary: SUBSTRING of a CASE whose condition has an IN list crashes the SQL parser (NPE in TableUtil.addAutoColumns)
-- bug: contains: unexpected system error was logged by the SQL parser
-- fixed: contains: (1 rows)
CREATE TABLE q (k INTEGER NOT NULL, PRIMARY KEY (k));
INSERT INTO q VALUES (1);
-- check
SELECT SUBSTRING(CASE WHEN k IN (1) THEN 'a' ELSE 'b' END, 1) FROM q;
