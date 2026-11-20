-- ticket: ENG-30628
-- summary: Planner IllegalStateException on a LEFT JOIN whose WHERE compares a scalar subquery with the partitioned outer table's primary key
-- bug: contains: java.lang.IllegalStateException
-- fixed: contains: (0 rows)
CREATE TABLE T0 (c0 INTEGER, c1 VARBINARY(4), c2 TINYINT NOT NULL, c3 FLOAT NOT NULL, PRIMARY KEY (c2));
CREATE TABLE T2 (c0 TINYINT NOT NULL, PRIMARY KEY (c0));
PARTITION TABLE T2 ON COLUMN c0;
-- check
SELECT T0.c3 FROM T2 LEFT JOIN T0 ON T0.c1 IS NOT NULL WHERE (SELECT MIN(S0.c0) FROM T0 AS S0) = T2.c0;
