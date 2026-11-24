-- ticket: ENG-30497
-- summary: LEFT JOIN becomes an inner join when WHERE compares an IS NULL test on the inner table: WHERE (lb.c1 IS NULL) = TRUE
-- bug: contains: (0 rows)
-- fixed: contains: (1 rows)
CREATE TABLE la (c0 INTEGER NOT NULL);
CREATE TABLE lb (c1 INTEGER NOT NULL);
INSERT INTO la VALUES (1);
INSERT INTO la VALUES (2);
INSERT INTO lb VALUES (2);
-- check
SELECT la.c0 FROM la LEFT JOIN lb ON la.c0 = lb.c1 WHERE (lb.c1 IS NULL) = TRUE;
