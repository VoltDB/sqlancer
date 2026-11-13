-- ticket: ENG-30664
-- summary: A unique index duplicating the primary key of a materialized view's join source crashes the server on the next insert ("Could not find Index with name ...")
-- sites: 8
-- platforms: Darwin
-- bug: crash
-- fixed: contains: (1 rows)
CREATE TABLE t0 (a BIGINT NOT NULL, PRIMARY KEY (a));
CREATE TABLE t2 (b DECIMAL, k SMALLINT NOT NULL);
PARTITION TABLE t2 ON COLUMN k;
CREATE VIEW v (n) AS SELECT COUNT(*) FROM t0 JOIN t2 ON t0.a = t2.k;
INSERT INTO t0 VALUES (1);
CREATE INDEX i1 ON t0 (a);
CREATE UNIQUE INDEX i2 ON t0 (a);
-- check
INSERT INTO t0 VALUES (2);
INSERT INTO t2 VALUES (NULL, 2);
INSERT INTO t0 VALUES (3);
SELECT n FROM v;
