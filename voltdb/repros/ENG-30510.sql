-- ticket: ENG-30510
-- summary: RIGHT JOIN uses an ON condition on the preserved table as its index range, dropping rows that should be null-extended
-- bug: contains: (0 rows)
-- fixed: contains: (1 rows)
CREATE TABLE ro_a (x INTEGER);
INSERT INTO ro_a VALUES (1);
CREATE TABLE y2 (k INTEGER NOT NULL, a INTEGER, b INTEGER, PRIMARY KEY (b, k));
CREATE INDEX y2_i ON y2 (a);
INSERT INTO y2 (k, a, b) VALUES (1, 9, 7);
INSERT INTO y2 (k) VALUES (2);
-- check
SELECT y2.k FROM ro_a RIGHT JOIN y2 ON y2.a <= 5 WHERE y2.b IS NULL;
