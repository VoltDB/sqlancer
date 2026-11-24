-- ticket: ENG-30561
-- summary: CAST of a TIMESTAMP constant to DATE folds to a wrong value, so WHERE d = CAST(CAST('...' AS TIMESTAMP) AS DATE) matches no rows
-- bug: contains: (0 rows)
-- fixed: contains: (1 rows)
CREATE TABLE R (i INTEGER, d DATE);
INSERT INTO R VALUES (1, '2000-01-01');
-- check
SELECT i FROM R WHERE d = CAST(CAST('2000-01-01 00:00:00' AS TIMESTAMP) AS DATE);
