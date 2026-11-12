-- ticket: ENG-30665
-- summary: A view over a join, maintained through an index on a narrower integer column with a key below its range, crashes the server ("went looking for a tuple in the view and expected to find it but didn't")
-- sites: 8
-- bug: crash
-- fixed: contains: (2 rows)
CREATE TABLE t0 (c3 TINYINT, c4 BIGINT NOT NULL);
PARTITION TABLE t0 ON COLUMN c4;
CREATE TABLE t1 (c0 BIGINT NOT NULL, c2 INTEGER NOT NULL);
CREATE VIEW v (c3, n) AS SELECT t0.c3, COUNT(*) FROM t0 JOIN t1 ON t0.c3 < t1.c2 GROUP BY t0.c3;
INSERT INTO t0 VALUES (127, 1);
INSERT INTO t1 VALUES (1, -1000);
INSERT INTO t1 VALUES (2, 500);
CREATE INDEX i ON t0 (c3);
-- check
UPDATE t1 SET c2 = 1;
SELECT COUNT(*) FROM v;
