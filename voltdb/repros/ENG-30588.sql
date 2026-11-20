-- ticket: ENG-30588
-- summary: An UPDATE of an indexed key that fails in view SUM maintenance leaves the row out of the primary-key index (a later UPDATE of it crashes the server)
-- bug: contains: (0 rows)
-- fixed: contains: (1 rows)
CREATE TABLE T (k INTEGER NOT NULL, d DECIMAL NOT NULL, PRIMARY KEY (k));
CREATE VIEW V (n, s) AS SELECT COUNT(*), SUM(d) FROM T;
INSERT INTO T VALUES (1, -99999999999999999999999999.999999999999);
INSERT INTO T VALUES (2, 5);
INSERT INTO T VALUES (3, -3);
UPDATE T SET k = 20 WHERE k = 2;
-- check
SELECT k FROM T WHERE k = 2;
