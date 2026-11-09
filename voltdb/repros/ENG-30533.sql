-- ticket: ENG-30533
-- summary: MIN(a) WHERE a = const on a multi-column index (a, b) skips rows whose b is NULL
-- bug: SELECT MIN(ia.a) AS m FROM ia WHERE ia.a = 5;
-- bug: M
-- bug: NULL
-- bug: (1 rows)
-- fixed: SELECT MIN(ia.a) AS m FROM ia WHERE ia.a = 5;
-- fixed: M
-- fixed: 5
-- fixed: (1 rows)
CREATE TABLE ia (a INTEGER, b INTEGER);
CREATE INDEX ia_i ON ia (a, b);
INSERT INTO ia VALUES (5, NULL);
-- check
SELECT MIN(ia.a) AS m FROM ia WHERE ia.a = 5;
