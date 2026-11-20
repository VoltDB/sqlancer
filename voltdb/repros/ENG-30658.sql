-- ticket: ENG-30658
-- summary: FORMAT_TIMESTAMP(TO_TIMESTAMP(unit, n)) or FORMAT_TIMESTAMP(FROM_UNIXTIME(n)) fails on a NULL n: "Type BIGINT can't be cast as TIMESTAMP or DATE"
-- bug: contains: can't be cast as TIMESTAMP
-- fixed: contains: NULL
CREATE TABLE tn (b BIGINT);
INSERT INTO tn VALUES (NULL);
-- check
SELECT FORMAT_TIMESTAMP(FROM_UNIXTIME(b), 'UTC') FROM tn;
