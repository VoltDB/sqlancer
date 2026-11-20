-- ticket: ENG-30511
-- summary: Index nested-loop range scan on a SMALLINT index with a BIGINT key below SMALLINT's range matches every row
-- bug: contains: (3 rows)
-- fixed: contains: (1 rows)
CREATE TABLE ns (s SMALLINT NOT NULL, PRIMARY KEY (s));
CREATE TABLE nb (b BIGINT);
INSERT INTO ns VALUES (5);
INSERT INTO nb VALUES (-1113012595);
INSERT INTO nb VALUES (-40000);
INSERT INTO nb VALUES (-100);
INSERT INTO nb VALUES (100000);
-- check
SELECT nb.b, ns.s FROM nb, ns WHERE nb.b > ns.s;
