-- ticket: ENG-30547
-- summary: The planner folds + of integer constants in 32 bits: 2135068309 + 1293734779 is -866164208
-- bug: contains: -866164208
-- fixed: contains: 3428803088
CREATE TABLE p6i (n INTEGER, b BIGINT);
INSERT INTO p6i VALUES (2147483647, 2147483648);
-- check
SELECT 2135068309 + 1293734779 FROM p6i;
