-- ticket: ENG-30626
-- summary: MOD(x, c + 1) inside LN, SQRT or EXP fails with "unsupported non-integral or non-decimal type for SQL MOD function"
-- bug: contains: unsupported non-integral or non-decimal type for SQL MOD function
-- fixed: contains: 0.6931471805599453
CREATE TABLE M (c0 INTEGER);
INSERT INTO M VALUES (3);
-- check
SELECT LN(MOD(20, c0 + 0)) FROM M;
