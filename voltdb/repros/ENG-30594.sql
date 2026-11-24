-- ticket: ENG-30594
-- summary: Planner NPE ("this.m_subqueryNode" is null) on an IN subquery whose output column holds a scalar subquery
-- bug: contains: m_subqueryNode
-- fixed: contains: (0 rows)
CREATE TABLE sq (k INTEGER NOT NULL, a INTEGER, PRIMARY KEY (k));
INSERT INTO sq VALUES (1, 1);
INSERT INTO sq VALUES (2, 2);
-- check
SELECT k FROM sq WHERE 1 IN (SELECT (SELECT MAX(s1.a) FROM sq AS s1) FROM sq AS s0);
