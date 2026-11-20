-- ticket: ENG-30529
-- summary: Multi-partition DISTINCT/GROUP BY merges groups when two grouped columns of different tables have the same name
-- sites: 8
-- bug: contains: (1 rows)
-- fixed: contains: (2 rows)
CREATE TABLE xa (k INTEGER NOT NULL, v INTEGER);
PARTITION TABLE xa ON COLUMN k;
CREATE TABLE xb (k INTEGER NOT NULL, v INTEGER);
PARTITION TABLE xb ON COLUMN k;
INSERT INTO xa VALUES (1, 10);
INSERT INTO xa VALUES (2, 20);
INSERT INTO xb VALUES (1, 7);
INSERT INTO xb VALUES (2, 7);
-- check
SELECT DISTINCT xa.v, xb.v FROM xa JOIN xb ON xa.k = xb.k;
