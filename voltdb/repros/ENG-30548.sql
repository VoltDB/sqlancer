-- ticket: ENG-30548
-- summary: DECIMAL constant folding differs from run-time DECIMAL arithmetic: folded 1 / 3.0 is 0.3333
-- bug: contains: 0.333300000000
-- fixed: contains: 0.333333333333
CREATE TABLE p6d (a DECIMAL, b DECIMAL, k INTEGER);
INSERT INTO p6d VALUES (1.000000000015, 0.5, 1);
-- check
SELECT 1 / 3.0 FROM p6d;
