-- ticket: ENG-30610
-- summary: A join with a constant ON condition and an equality on a VARBINARY partitioning column is routed to the wrong partition (literal not parameterized)
-- sites: 8
-- bug: contains: (0 rows)
-- fixed: contains: (1 rows)
CREATE TABLE VB (c0 VARBINARY(16) NOT NULL, n INTEGER);
PARTITION TABLE VB ON COLUMN c0;
CREATE TABLE R (c0 INTEGER NOT NULL);
INSERT INTO R VALUES (5);
INSERT INTO VB VALUES (x'01', 1);
INSERT INTO VB VALUES (x'02', 1);
INSERT INTO VB VALUES (x'03', 1);
INSERT INTO VB VALUES (x'04', 1);
INSERT INTO VB VALUES (x'05', 1);
INSERT INTO VB VALUES (x'06', 1);
INSERT INTO VB VALUES (x'07', 1);
INSERT INTO VB VALUES (x'08', 1);
INSERT INTO VB VALUES (x'09', 1);
INSERT INTO VB VALUES (x'0A', 1);
INSERT INTO VB VALUES (x'30FF', 1);
-- check
SELECT VB.c0 FROM VB INNER JOIN R ON 1 = 1 WHERE VB.c0 = x'03';
