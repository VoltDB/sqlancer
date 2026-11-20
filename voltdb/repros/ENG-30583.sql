-- ticket: ENG-30583
-- summary: A materialized view whose WHERE has a FLOAT constant like 12.5E0 blocks every later DDL ("May not dynamically modify field 'predicate'")
-- bug: contains: May not dynamically modify field 'predicate'
-- fixed: contains: Command succeeded
CREATE TABLE pq1 (c0 FLOAT, c1 INTEGER);
CREATE VIEW pq1v (c, s) AS SELECT COUNT(*), SUM(c1) FROM pq1 WHERE c0 > 12.5E0;
-- check
CREATE TABLE pq2 (a INTEGER);
