-- ticket: ENG-30551
-- summary: A plan with a subnormal FLOAT constant fails with "JSON parse failure"; re-running it after any DDL crashes the node (SIGSEGV in cleanupAllExecutors)
-- bug: crash
-- fixed: contains: (1 rows)
CREATE TABLE r (c0 INTEGER, c2 DATE);
INSERT INTO r VALUES (1, '2020-01-01');
SELECT c0, 1.0E-309 FROM r WHERE c2 > CAST('2000-01-01' AS DATE);
CREATE TABLE x1 (a INTEGER);
-- check
SELECT c0, 1.0E-309 FROM r WHERE c2 > CAST('2000-01-01' AS DATE);
