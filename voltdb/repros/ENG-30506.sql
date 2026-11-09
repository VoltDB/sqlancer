-- ticket: ENG-30506
-- summary: Parser NPE (colTypes is null) on (a IN (1) OR -5 > a): parenthesized list with an operand after IN starting with a minus
-- bug: contains: unexpected system error
-- fixed: contains: (1 rows)
CREATE TABLE qi (a INTEGER);
-- check
SELECT COUNT(*) FROM qi WHERE (a IN (1) OR -5 > a);
