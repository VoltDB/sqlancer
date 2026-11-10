-- ticket: ENG-30514
-- summary: A NULL in a primary key column; LEFT JOIN ... WHERE pk IS NULL drops the ON condition (planner assumes pk NOT NULL)
-- bug: contains: (1 rows)
-- fixed: contains: (0 rows)
CREATE TABLE pkr (a INTEGER);
CREATE TABLE pkn (b INTEGER, PRIMARY KEY (b));
INSERT INTO pkr VALUES (5);
INSERT INTO pkn VALUES (1);
INSERT INTO pkn VALUES (NULL);
-- check
SELECT pkr.a FROM pkr LEFT JOIN pkn ON pkr.a > pkn.b WHERE pkn.b IS NULL;
