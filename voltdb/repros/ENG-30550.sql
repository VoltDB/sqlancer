-- ticket: ENG-30550
-- summary: ABS of a BIGINT multiplication is typed DECIMAL, so integer division inside it keeps a fraction (ABS((b * 1) / 2) is 3.5 for b = 7)
-- bug: contains: (0 rows)
-- fixed: contains: (1 rows)
CREATE TABLE p6m (b BIGINT);
INSERT INTO p6m VALUES (7);
-- check
SELECT b FROM p6m WHERE ABS((b * 1) / 2) = 3;
