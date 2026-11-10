-- ticket: ENG-30607
-- summary: Derived table over a set operation fails "exceeds the size of the VARCHAR(n) column" when the first operand is VARCHAR(16+) and a later value is longer
-- bug: contains: exceeds the size
-- fixed: contains: (1 rows)
CREATE TABLE A (c VARCHAR(16));
CREATE TABLE B (c VARCHAR(20));
INSERT INTO B VALUES ('xxxxxxxxxxxxxxxxx');
-- check
SELECT * FROM (SELECT c FROM A UNION ALL SELECT c FROM B) AS u;
