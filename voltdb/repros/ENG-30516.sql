-- ticket: ENG-30516
-- summary: WHERE c IS NULL AND c = <constant> on an indexed column drops the IS NULL conjunct
-- bug: SELECT COUNT(*) AS n FROM ei WHERE c0 IS NULL AND c0 = 5;
-- bug: N
-- bug: 1
-- bug: (1 rows)
-- fixed: SELECT COUNT(*) AS n FROM ei WHERE c0 IS NULL AND c0 = 5;
-- fixed: N
-- fixed: 0
-- fixed: (1 rows)
CREATE TABLE ei (c0 INTEGER NOT NULL, PRIMARY KEY (c0));
INSERT INTO ei VALUES (5);
-- check
SELECT COUNT(*) AS n FROM ei WHERE c0 IS NULL AND c0 = 5;
