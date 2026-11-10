-- ticket: ENG-30493
-- summary: The EE orders BOOLEAN TRUE < FALSE, so WHERE b < FALSE returns the TRUE row
-- bug: contains: (1 rows)
-- fixed: contains: (0 rows)
CREATE TABLE bq (id INTEGER, b BOOLEAN);
INSERT INTO bq VALUES (1, TRUE);
INSERT INTO bq VALUES (2, FALSE);
INSERT INTO bq VALUES (3, NULL);
-- check
SELECT id FROM bq WHERE b < FALSE ORDER BY id;
