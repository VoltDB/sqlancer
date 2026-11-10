-- ticket: ENG-30495
-- summary: Planner NPE when the whole WHERE clause folds to a constant NULL (WHERE NOT (CAST(NULL AS INTEGER) = 1))
-- bug: contains: unexpected system error
-- fixed: contains: (0 rows)
CREATE TABLE lk (id INTEGER, s VARCHAR(8));
INSERT INTO lk VALUES (1, 'S');
-- check
SELECT id FROM lk WHERE NOT (CAST(NULL AS INTEGER) = 1);
