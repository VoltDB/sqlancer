-- ticket: ENG-30521
-- summary: In a join with inline literals, a DECIMAL expression of 2^63 or more compares wrongly with a non-integer DECIMAL literal
-- bug: contains: (2 rows)
-- fixed: contains: (1 rows)
CREATE TABLE da (d DECIMAL);
CREATE TABLE db (i INTEGER);
INSERT INTO da VALUES (10000000000000000000);
INSERT INTO da VALUES (5);
INSERT INTO db VALUES (1);
-- check
SELECT d FROM da, db WHERE (d + 0) <= 22.656 AND (1 = 1);
