-- ticket: ENG-30545
-- summary: DATE = TIMESTAMP truncates the TIMESTAMP when the DATE is the left operand, so dt = ts and ts = dt differ
-- bug: SELECT COUNT(*) AS n FROM p6e WHERE dt = ts;
-- bug: N
-- bug: 1
-- bug: (1 rows)
-- fixed: SELECT COUNT(*) AS n FROM p6e WHERE dt = ts;
-- fixed: N
-- fixed: 0
-- fixed: (1 rows)
CREATE TABLE p6e (dt DATE, ts TIMESTAMP);
INSERT INTO p6e VALUES ('2020-01-02', '2020-01-02 12:00:00');
-- check
SELECT COUNT(*) AS n FROM p6e WHERE dt = ts;
