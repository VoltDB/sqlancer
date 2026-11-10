-- ticket: ENG-30503
-- summary: IN lists ignore NULL elements, so d NOT IN (NULL, 7) is TRUE instead of NULL and returns rows
-- bug: contains: (2 rows)
-- fixed: contains: (0 rows)
CREATE TABLE ni (id INTEGER, d INTEGER);
INSERT INTO ni VALUES (1, 5);
INSERT INTO ni VALUES (2, 1);
INSERT INTO ni VALUES (3, NULL);
-- check
SELECT id FROM ni WHERE d NOT IN (CAST(NULL AS INTEGER), 7) ORDER BY id;
