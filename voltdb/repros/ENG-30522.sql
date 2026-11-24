-- ticket: ENG-30522
-- summary: A constant FLOAT expression overflowing to infinity fails in the parser with NumberFormatException "Character I ..."
-- bug: contains: Character I is neither a decimal digit
-- fixed: contains: Invalid result value
CREATE TABLE fx (f FLOAT);
INSERT INTO fx VALUES (1);
-- check
SELECT 1.7976931348623157E308 * 10E0 FROM fx;
