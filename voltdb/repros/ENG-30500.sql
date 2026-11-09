-- ticket: ENG-30500
-- summary: (d > 3) IN (CAST(NULL AS BOOLEAN), FALSE, TRUE) drops the TRUE row once IN (CAST(NULL AS BOOLEAN), TRUE) has been planned
-- bug: SELECT id FROM bi WHERE (d > 3) IN (CAST(NULL AS BOOLEAN), TRUE) ORDER BY id;
-- bug: ID
-- bug: 1
-- bug: (1 rows)
-- bug: SELECT id FROM bi WHERE (d > 3) IN (CAST(NULL AS BOOLEAN), FALSE, TRUE) ORDER BY id;
-- bug: ID
-- bug: 2
-- bug: (1 rows)
-- fixed: contains: (2 rows)
CREATE TABLE bi (id INTEGER, d INTEGER, b BOOLEAN);
INSERT INTO bi VALUES (1, 5, TRUE);
INSERT INTO bi VALUES (2, 1, FALSE);
INSERT INTO bi VALUES (3, NULL, NULL);
-- check
SELECT id FROM bi WHERE (d > 3) IN (CAST(NULL AS BOOLEAN), TRUE) ORDER BY id;
SELECT id FROM bi WHERE (d > 3) IN (CAST(NULL AS BOOLEAN), FALSE, TRUE) ORDER BY id;
