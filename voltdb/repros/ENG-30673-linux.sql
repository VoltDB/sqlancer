-- ticket: ENG-30673
-- summary: After an INSERT and an UPDATE fail in a join view's maintenance (DATEADD out of range), the table's row is gone on x86 Linux (on macOS the next DDL crashes the server instead, ENG-30673.sql)
-- platforms: Linux
-- bug: contains: (0 rows)
-- fixed: contains: (1 rows)
CREATE TABLE t0 (a BIGINT);
CREATE TABLE t1 (k INTEGER NOT NULL, d DATE, PRIMARY KEY (k));
CREATE VIEW v (d, n) AS SELECT t1.d, COUNT(*) FROM t0 JOIN t1 ON DATEADD(QUARTER, t0.a, t1.d) IS NOT NULL GROUP BY t1.d;
INSERT INTO t1 VALUES (1, '2000-01-01');
INSERT INTO t0 VALUES (788427637);
UPDATE t1 SET d = d;
-- check
CREATE TABLE z (a INTEGER);
SELECT k FROM t1;
