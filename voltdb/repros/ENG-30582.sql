-- ticket: ENG-30582
-- summary: With a materialized view on the table, a GROUP BY query with a duplicate aggregate fails to plan ("Duplicate key" in MVQueryRewriter)
-- bug: contains: Duplicate key
-- fixed: contains: (1 rows)
CREATE TABLE rw2 (g INTEGER, x INTEGER);
CREATE VIEW rw2v (g, c, s) AS SELECT g, COUNT(*), SUM(x) FROM rw2 GROUP BY g;
INSERT INTO rw2 VALUES (1, 5);
INSERT INTO rw2 VALUES (1, 7);
-- check
SELECT g, SUM(x) AS a, SUM(x) AS b FROM rw2 GROUP BY g;
