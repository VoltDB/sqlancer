-- ticket: ENG-30611
-- summary: DATEADD(YEAR, n, DATE '2024-02-29') into a non-leap year aborts the server (uncaught boost bad_day_of_month)
-- bug: crash
-- fixed: contains: 2025-02-28
CREATE TABLE DT (d DATE, t TIMESTAMP);
INSERT INTO DT VALUES ('2024-02-29', '2024-02-29 10:00:00');
-- check
SELECT DATEADD(YEAR, 1, CAST('2024-02-29' AS DATE)) FROM DT;
