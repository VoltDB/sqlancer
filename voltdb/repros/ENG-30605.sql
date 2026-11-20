-- ticket: ENG-30605
-- summary: String literals at the same position of a set operation are padded with spaces to the longest one ('a' UNION ALL 'abc' gives 'a  ')
-- bug: contains: (0 rows)
-- fixed: contains: (2 rows)
CREATE TABLE SR (k INTEGER NOT NULL, s VARCHAR(10), PRIMARY KEY (k));
INSERT INTO SR VALUES (1, 'x');
INSERT INTO SR VALUES (2, 'y');
-- check
SELECT x FROM (SELECT 'a' AS x FROM SR UNION ALL SELECT 'abc' FROM SR) AS u WHERE x = 'a';
