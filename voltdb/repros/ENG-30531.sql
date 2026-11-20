-- ticket: ENG-30531
-- summary: Index order trusted through a FULL JOIN whose null-extended rows come last: DISTINCT returns the NULL group twice
-- bug: contains: (3 rows)
-- fixed: contains: (2 rows)
CREATE TABLE sb (w INTEGER);
CREATE INDEX sb_w ON sb (w);
CREATE TABLE sc (k INTEGER NOT NULL, v INTEGER);
INSERT INTO sb VALUES (NULL);
INSERT INTO sb VALUES (5);
INSERT INTO sc VALUES (1, 1);
-- check
SELECT DISTINCT sb.w FROM sc FULL JOIN sb ON sb.w = 99;
