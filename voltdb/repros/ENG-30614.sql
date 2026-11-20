-- ticket: ENG-30614
-- summary: An outer join whose ON condition folds to FALSE returns no rows, not the preserved rows null-extended
-- bug: contains: (0 rows)
-- fixed: contains: (1 rows)
CREATE TABLE A (c0 TINYINT NOT NULL, c4 TIMESTAMP);
CREATE TABLE B (c1 SMALLINT NOT NULL);
INSERT INTO A VALUES (33, '4808-01-13 02:26:25');
-- check
SELECT B.c1, A.c4 FROM A LEFT JOIN B ON 1 = 0;
