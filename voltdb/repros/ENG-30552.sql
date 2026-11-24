-- ticket: ENG-30552
-- summary: The string literal '\N' in ad hoc SQL becomes NULL (INSERT stores NULL)
-- bug: contains: (1 rows)
-- fixed: contains: (0 rows)
CREATE TABLE bs (v VARCHAR(10), w VARCHAR(10));
INSERT INTO bs VALUES ('x', '\N');
-- check
SELECT v FROM bs WHERE w IS NULL;
