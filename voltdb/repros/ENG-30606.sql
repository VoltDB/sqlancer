-- ticket: ENG-30606
-- summary: CREATE PROCEDURE ... AS turns a newline inside a string literal into a space
-- bug: contains: space
-- fixed: contains: newline
CREATE TABLE NL (c0 INTEGER);
INSERT INTO NL VALUES (1);
CREATE PROCEDURE NLP AS SELECT CASE WHEN 'a
b' = 'a b' THEN 'space' ELSE 'newline' END AS kept FROM NL;
-- check
EXEC NLP;
