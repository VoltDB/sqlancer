-- ticket: ENG-30534
-- summary: MIN(x'') and MIN('') in a GROUP BY join whose ON condition contains x'' fail with a VARCHAR/VARBINARY cast error
-- bug: contains: can't be cast as VARBINARY
-- fixed: contains: (1 rows)
CREATE TABLE ja (k INTEGER);
CREATE TABLE jb (k INTEGER);
INSERT INTO ja VALUES (1);
INSERT INTO jb VALUES (1);
-- check
SELECT MIN(x''), MIN('') FROM ja JOIN jb ON (x'' IS NOT NULL) GROUP BY ja.k;
