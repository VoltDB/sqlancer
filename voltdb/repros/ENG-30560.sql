-- ticket: ENG-30560
-- summary: DECIMAL literals are FLOAT in statements that are not auto-parameterized; 0.1 + 0.2 with a DATE constant fails to plan
-- bug: contains: Numeric conversion error to type DECIMAL
-- fixed: contains: 0.300000000000
CREATE TABLE R (i INTEGER, d DATE);
INSERT INTO R VALUES (1, '2000-01-01');
-- check
SELECT 0.1 + 0.2 AS a FROM R WHERE d = '2000-01-01';
