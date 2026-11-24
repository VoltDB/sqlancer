-- ticket: ENG-30549
-- summary: The planner folds constant string comparisons ignoring trailing spaces ('a' = 'a ' is TRUE) while the EE compares VARCHAR exactly
-- bug: contains: (1 rows)
-- fixed: contains: (0 rows)
CREATE TABLE p6p (v VARCHAR(10), w VARCHAR(10));
INSERT INTO p6p VALUES ('a', 'a ');
-- check
SELECT v FROM p6p WHERE 'a' = 'a ';
