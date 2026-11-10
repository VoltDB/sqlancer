-- ticket: ENG-30508
-- summary: Planner NumberFormatException for a join with a constant DECIMAL IN filter (1.5 IN (2)), instead of the usual rejection
-- bug: contains: NUMERIC constant value type must match
-- fixed: contains: Join with filters that do not depend on joined tables is not supported
CREATE TABLE qi (a INTEGER);
-- check
SELECT COUNT(*) FROM qi, qi AS q2 WHERE q2.a = 1 AND 1.5 IN (2);
