-- ticket: ENG-30656
-- summary: ROUND, STR and FORMAT_CURRENCY fail on a DECIMAL of 2^63 or more: "Type DECIMAL with value ... can't be cast as BIGINT"
-- bug: contains: can't be cast as BIGINT
-- fixed: contains: 9223372036854775808.00
CREATE TABLE z (k INTEGER NOT NULL, d DECIMAL, PRIMARY KEY (k));
INSERT INTO z VALUES (2, 9223372036854775808);
-- check
SELECT ROUND(d, 2) FROM z WHERE k = 2;
