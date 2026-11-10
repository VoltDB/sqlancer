-- ticket: ENG-30589
-- summary: UPDATE of a VARCHAR column to a CASE fails to compile ("Asking for fixed size for non-fixed or unknown type:varchar")
-- bug: contains: Asking for fixed size for non-fixed or unknown type
-- fixed: contains: (1 rows)
CREATE TABLE q (k INTEGER NOT NULL, s VARCHAR(10), PRIMARY KEY (k));
INSERT INTO q VALUES (1, 'ab');
-- check
UPDATE q SET s = CASE WHEN k = 1 THEN 'x' ELSE 'y' END;
