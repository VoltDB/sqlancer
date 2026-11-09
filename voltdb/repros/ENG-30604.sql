-- ticket: ENG-30604
-- summary: A set operation in a derived table with one operand pinned to a partition returns the replicated operand's rows once per partition
-- sites: 8
-- bug: contains: (17 rows)
-- fixed: contains: (3 rows)
CREATE TABLE SR (k INTEGER NOT NULL, s VARCHAR(10), PRIMARY KEY (k));
CREATE TABLE SP (k INTEGER NOT NULL, s VARCHAR(10), PRIMARY KEY (k));
PARTITION TABLE SP ON COLUMN k;
INSERT INTO SR VALUES (1, 'x');
INSERT INTO SR VALUES (2, 'y');
INSERT INTO SP VALUES (1, 'x');
INSERT INTO SP VALUES (5, 'z');
-- check
SELECT * FROM (SELECT k FROM SP WHERE k = 1 UNION ALL SELECT k FROM SR) AS u;
