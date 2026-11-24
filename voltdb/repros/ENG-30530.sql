-- ticket: ENG-30530
-- summary: DISTINCT/GROUP BY on the partitioning column of an outer join's null-extended table returns one NULL group per partition
-- sites: 8
-- bug: contains: (3 rows)
-- fixed: contains: (1 rows)
CREATE TABLE fa (k INTEGER NOT NULL, v INTEGER);
PARTITION TABLE fa ON COLUMN k;
CREATE TABLE fb (k INTEGER NOT NULL, w INTEGER);
PARTITION TABLE fb ON COLUMN k;
INSERT INTO fa VALUES (1, NULL);
INSERT INTO fa VALUES (2, NULL);
INSERT INTO fa VALUES (3, NULL);
INSERT INTO fa VALUES (4, NULL);
INSERT INTO fa VALUES (5, NULL);
INSERT INTO fa VALUES (6, NULL);
-- check
SELECT DISTINCT fb.k FROM fa LEFT JOIN fb ON fa.k = fb.k;
