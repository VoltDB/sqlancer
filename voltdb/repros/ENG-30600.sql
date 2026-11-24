-- ticket: ENG-30600
-- summary: An IN subquery nested in another subquery, whose left operand is a column of the outermost query, matches every row
-- bug: contains: (3 rows)
-- fixed: contains: (1 rows)
CREATE TABLE A (k INTEGER, t TIMESTAMP);
CREATE TABLE B (k INTEGER, t TIMESTAMP);
INSERT INTO A VALUES (1, '2020-01-01 00:00:00');
INSERT INTO A VALUES (2, NULL);
INSERT INTO A VALUES (NULL, '2021-01-01 00:00:00');
INSERT INTO B VALUES (1, '2020-01-01 00:00:00');
INSERT INTO B VALUES (5, '2021-01-01 00:00:00');
-- check
SELECT A.k FROM A WHERE EXISTS (SELECT 1 FROM B AS S1 WHERE A.k IN (SELECT S2.k FROM B AS S2)) ORDER BY 1;
