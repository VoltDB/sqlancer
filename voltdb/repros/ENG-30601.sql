-- ticket: ENG-30601
-- summary: WHERE t.k < c AND t.k = other.col drops the range predicate when the join equality becomes an index lookup
-- bug: contains: (1 rows)
-- fixed: contains: (0 rows)
CREATE TABLE F1 (k INTEGER NOT NULL, PRIMARY KEY (k));
CREATE TABLE F2 (j INTEGER NOT NULL);
INSERT INTO F1 VALUES (50);
INSERT INTO F2 VALUES (50);
-- check
SELECT F1.k FROM F2, F1 WHERE F1.k < 10 AND F1.k = F2.j;
