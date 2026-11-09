-- ticket: ENG-30584
-- summary: Deleting a group's last non-NULL value leaves 0 instead of NULL in a materialized view's SUM
-- bug: contains: (0 rows)
-- fixed: contains: (1 rows)
CREATE TABLE sn (g INTEGER, x BIGINT);
CREATE VIEW snv (g, c, s) AS SELECT g, COUNT(*), SUM(x) FROM sn GROUP BY g;
INSERT INTO sn VALUES (1, NULL);
INSERT INTO sn VALUES (1, 5);
DELETE FROM sn WHERE x = 5;
-- check
SELECT g FROM snv WHERE s IS NULL;
