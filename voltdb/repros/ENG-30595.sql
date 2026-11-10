-- ticket: ENG-30595
-- summary: CHAR_LENGTH of a scalar subquery as the left side of an IN subquery crashes the server (SIGSEGV in StringRef::getObject)
-- bug: crash
-- fixed: contains: (2 rows)
CREATE TABLE sq (k INTEGER NOT NULL, a INTEGER, s VARCHAR(8), PRIMARY KEY (k));
INSERT INTO sq VALUES (1, 1, 'x');
INSERT INTO sq VALUES (2, 2, NULL);
-- check
SELECT k FROM sq WHERE CHAR_LENGTH((SELECT MAX(s0.s) FROM sq AS s0)) IN (SELECT s1.a FROM sq AS s1);
