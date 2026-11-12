-- ticket: ENG-30655
-- summary: A procedure created AS a query with TIME_WINDOW(..., END) blocks every later DDL, its own DROP included ("unexpected token: END")
-- bug: contains: unexpected token: END
-- fixed: contains: Command succeeded
CREATE TABLE t (k INTEGER NOT NULL, ts TIMESTAMP, PRIMARY KEY (k));
CREATE PROCEDURE p AS SELECT TIME_WINDOW(HOUR, 2, ts, END) FROM t WHERE k = ?;
-- check
CREATE TABLE u (a INTEGER);
