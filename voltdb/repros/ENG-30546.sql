-- ticket: ENG-30546
-- summary: Concatenating empty strings at run time (v || v with v = '') returns NULL instead of ''
-- bug: SELECT COUNT(*) AS n FROM p6c WHERE v || v IS NULL;
-- bug: N
-- bug: 1
-- bug: (1 rows)
-- fixed: SELECT COUNT(*) AS n FROM p6c WHERE v || v IS NULL;
-- fixed: N
-- fixed: 0
-- fixed: (1 rows)
CREATE TABLE p6c (v VARCHAR(10), w VARCHAR(10));
INSERT INTO p6c VALUES ('', 'a');
-- check
SELECT COUNT(*) AS n FROM p6c WHERE v || v IS NULL;
