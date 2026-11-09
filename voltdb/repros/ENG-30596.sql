-- ticket: ENG-30596
-- summary: A bare scalar subquery on the left of an IN list fails in the EE: Invalid ExpressionType 'COMPARE_IN' for VectorComparisonExpression
-- bug: contains: COMPARE_IN
-- fixed: contains: (2 rows)
CREATE TABLE sq (k INTEGER NOT NULL, a INTEGER, PRIMARY KEY (k));
INSERT INTO sq VALUES (1, 1);
INSERT INTO sq VALUES (2, 2);
-- check
SELECT k FROM sq WHERE (SELECT MAX(s0.a) FROM sq AS s0) IN (2, 3);
