-- ticket: ENG-30599
-- summary: A window's ORDER BY ... DESC is applied ascending when an equality on the PARTITION BY column picks a forward index scan
-- bug: contains: 1 10 1
-- fixed: contains: 1 30 1
CREATE TABLE WR (a INTEGER NOT NULL, b INTEGER NOT NULL);
CREATE INDEX WR_I ON WR (a, b);
INSERT INTO WR VALUES (1, 10);
INSERT INTO WR VALUES (1, 20);
INSERT INTO WR VALUES (1, 30);
INSERT INTO WR VALUES (2, 40);
-- check
SELECT a, b, RANK() OVER (PARTITION BY a ORDER BY b DESC) FROM WR WHERE a = 1;
