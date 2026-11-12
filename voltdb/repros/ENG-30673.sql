-- ticket: ENG-30673
-- summary: After an INSERT fails in a join view's maintenance (DATEADD out of range), the row stays visible to the view, and the next DDL crashes the server ("getStats() called with ... an invalid locator")
-- bug: crash
-- fixed: contains: (1 rows)
CREATE TABLE t0 (a BIGINT);
CREATE TABLE t1 (k INTEGER NOT NULL, d DATE, PRIMARY KEY (k));
CREATE VIEW v (d, n) AS SELECT t1.d, COUNT(*) FROM t0 JOIN t1 ON DATEADD(QUARTER, t0.a, t1.d) IS NOT NULL GROUP BY t1.d;
INSERT INTO t1 VALUES (1, '2000-01-01');
INSERT INTO t0 VALUES (788427637);
UPDATE t1 SET d = d;
-- check
CREATE TABLE z (a INTEGER);
SELECT COUNT(*) FROM t1;
