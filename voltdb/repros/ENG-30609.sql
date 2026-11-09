-- ticket: ENG-30609
-- summary: SELECT COUNT(*) FROM a recursive common table fails to plan: TableCountPlanNode cannot be cast to SeqScanPlanNode
-- bug: contains: TableCountPlanNode
-- fixed: contains: (1 rows)
CREATE TABLE RCT (c0 INTEGER);
INSERT INTO RCT VALUES (1);
INSERT INTO RCT VALUES (2);
-- check
WITH RECURSIVE RC (V) AS (SELECT c0 FROM RCT WHERE c0 = 1 UNION ALL SELECT RCT.c0 FROM RCT, RC WHERE RCT.c0 = RC.V + 1) SELECT COUNT(*) FROM RC;
