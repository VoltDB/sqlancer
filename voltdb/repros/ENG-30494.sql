-- ticket: ENG-30494
-- summary: Ad hoc BOOLEAN literal mis-parameterized: after a CASE with bo = TRUE is planned, WHERE b = TRUE AND s = 'x' fails with a VARCHAR/BOOLEAN cast error
-- bug: contains: cannot be cast for comparison to type BOOLEAN
-- fixed: SELECT CASE WHEN k = 1 THEN 1 WHEN bo = TRUE THEN 2 END FROM q;
-- fixed: C1
-- fixed: 1
-- fixed: (1 rows)
-- fixed: SELECT COUNT(*) FROM pq WHERE b = TRUE AND s = 'x';
-- fixed: C1
-- fixed: 1
-- fixed: (1 rows)
CREATE TABLE q (k INTEGER NOT NULL, bo BOOLEAN, PRIMARY KEY (k));
INSERT INTO q VALUES (1, TRUE);
CREATE TABLE pq (b BOOLEAN, s VARCHAR(4), i INTEGER);
INSERT INTO pq VALUES (TRUE, 'x', 1);
INSERT INTO pq VALUES (FALSE, 'y', 2);
-- check
SELECT CASE WHEN k = 1 THEN 1 WHEN bo = TRUE THEN 2 END FROM q;
SELECT COUNT(*) FROM pq WHERE b = TRUE AND s = 'x';
