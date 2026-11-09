-- ticket: ENG-30515
-- summary: INDEX COUNT with an upper bound above the column type's range misses rows holding the type's maximum
-- bug: SELECT COUNT(*) AS n FROM ti WHERE c1 < 32767;
-- bug: N
-- bug: 2
-- bug: (1 rows)
-- fixed: SELECT COUNT(*) AS n FROM ti WHERE c1 < 32767;
-- fixed: N
-- fixed: 3
-- fixed: (1 rows)
CREATE TABLE ti (c1 TINYINT);
CREATE INDEX ti_i ON ti (c1);
INSERT INTO ti VALUES (127);
INSERT INTO ti VALUES (5);
INSERT INTO ti VALUES (-127);
INSERT INTO ti VALUES (NULL);
-- check
SELECT COUNT(*) AS n FROM ti WHERE c1 < 32767;
