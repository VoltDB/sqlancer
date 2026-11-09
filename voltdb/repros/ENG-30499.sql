-- ticket: ENG-30499
-- summary: Planner ClassCastException folding DATE constant < TIMESTAMP constant
-- bug: contains: unexpected system error
-- fixed: contains: (1 rows)
CREATE TABLE dq (id INTEGER, d DATE, ts TIMESTAMP);
INSERT INTO dq VALUES (1, '2020-01-02', '2020-01-02 03:00:00');
-- check
SELECT id FROM dq WHERE CAST('2020-01-01' AS DATE) < CAST('2021-01-01 00:00:00' AS TIMESTAMP);
