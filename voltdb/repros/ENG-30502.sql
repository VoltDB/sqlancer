-- ticket: ENG-30502
-- summary: Unary minus on a NULL INTEGER is not NULL (the negated sentinel), so WHERE -i < 0 matches a NULL row
-- bug: contains: (1 rows)
-- fixed: contains: (0 rows)
CREATE TABLE nb (id INTEGER, i INTEGER);
INSERT INTO nb (id) VALUES (1);
-- check
SELECT id FROM nb WHERE -i < 0;
