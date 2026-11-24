-- ticket: ENG-30512
-- summary: INDEX COUNT over an empty index range (i BETWEEN 9 AND 1) returns a negative count
-- bug: SELECT COUNT(*) AS n FROM bi WHERE i BETWEEN 9 AND 1;
-- bug: N
-- bug: -1
-- bug: (1 rows)
-- fixed: SELECT COUNT(*) AS n FROM bi WHERE i BETWEEN 9 AND 1;
-- fixed: N
-- fixed: 0
-- fixed: (1 rows)
CREATE TABLE bi (i INTEGER);
CREATE INDEX bi_i ON bi (i);
INSERT INTO bi VALUES (5);
-- check
SELECT COUNT(*) AS n FROM bi WHERE i BETWEEN 9 AND 1;
