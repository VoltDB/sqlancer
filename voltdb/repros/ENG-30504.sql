-- ticket: ENG-30504
-- summary: Planner fails on WHERE NOT (NOT (b OR c)) over BOOLEAN columns: Unable to determine cast type
-- bug: contains: Unable to determine cast type
-- fixed: contains: (1 rows)
CREATE TABLE bb (b BOOLEAN, c BOOLEAN);
INSERT INTO bb VALUES (TRUE, FALSE);
INSERT INTO bb VALUES (FALSE, FALSE);
-- check
SELECT b FROM bb WHERE NOT (NOT (b OR c));
