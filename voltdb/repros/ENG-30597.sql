-- ticket: ENG-30597
-- summary: GROUP BY query with an IN subquery outputting only an outer column fails to plan (IndexOutOfBoundsException in rewriteInSubqueryAsExists)
-- bug: contains: Index 0 out of bounds
-- fixed: contains: (2 rows)
CREATE TABLE rq (k INTEGER NOT NULL, a INTEGER, PRIMARY KEY (k));
CREATE TABLE rr (x INTEGER);
INSERT INTO rq VALUES (1, 1);
INSERT INTO rq VALUES (2, 7);
INSERT INTO rr VALUES (1);
INSERT INTO rr VALUES (5);
-- check
SELECT rq.a, COUNT(*) FROM rq WHERE rq.a IN (SELECT rq.a FROM rr AS s0) GROUP BY rq.a;
