-- ticket: ENG-30509
-- summary: Range scan on a multi-column index truncates a search key longer than VARCHAR(1): c >= '-2' matches '-'
-- bug: contains: (1 rows)
-- fixed: contains: (0 rows)
CREATE TABLE lr (c VARCHAR(1) NOT NULL, d VARCHAR(100));
CREATE INDEX lr_i ON lr (c, d);
INSERT INTO lr VALUES ('-', 'a');
-- check
SELECT c FROM lr WHERE c >= '-2';
