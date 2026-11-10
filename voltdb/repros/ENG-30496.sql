-- ticket: ENG-30496
-- summary: Planner NPE on LIKE without a wildcard inside CASE WHEN (CASE WHEN s LIKE 'S' THEN 1 ELSE 0 END)
-- bug: contains: unexpected system error
-- fixed: contains: (3 rows)
CREATE TABLE lk (id INTEGER, s VARCHAR(8));
INSERT INTO lk VALUES (1, 'S');
INSERT INTO lk VALUES (2, 'ab');
INSERT INTO lk VALUES (3, NULL);
-- check
SELECT CASE WHEN s LIKE 'S' THEN 1 ELSE 0 END FROM lk;
