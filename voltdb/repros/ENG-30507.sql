-- ticket: ENG-30507
-- summary: RIGHT JOIN with WHERE IN on an indexed column of the preserved table applies the IN as a join filter, returning a row that fails it
-- bug: contains: (1 rows)
-- fixed: contains: (0 rows)
CREATE TABLE rj_r (a INTEGER);
CREATE TABLE rj_t (a INTEGER, b INTEGER);
CREATE INDEX rj_t_b ON rj_t (b);
INSERT INTO rj_t VALUES (5, 1);
-- check
SELECT rj_t.b FROM rj_r RIGHT JOIN rj_t ON rj_r.a = rj_t.a WHERE rj_t.b IN (2);
