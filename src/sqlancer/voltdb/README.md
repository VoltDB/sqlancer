# VoltDB provider

A SQLancer provider for [VoltDB](https://www.voltactivedata.com/), developed for ENG-30396. It generates random
VoltDB schemas, both replicated and partitioned tables, fills them with single-row inserts, and runs SQLancer's oracles
against them through the VoltDB JDBC driver.

**Status: milestone 2.** The provider creates schemas and data and runs the NoREC logic-bug oracle (the default).
`SMOKE`, from milestone 1, only reads every table and checks that the queries succeed. TLP WHERE comes next
(milestone 3).

## Versions

| Component | Version |
| --- | --- |
| SQLancer | upstream `main` at 1d797ee (2026-10-21), forked as `VoltDB/sqlancer`. The last release, v2.0.0, is from 2022. |
| VoltDB JDBC driver | `org.voltdb:voltdbclient:16.0.0` from Maven Central (`pom.xml`) |
| JDK | 11 or later to build and run; tested with 17 |
| VoltDB server | tested with 16.1.0-SNAPSHOT |

The driver talks to older and newer servers, so the server under test need not match the driver version.

## Build

```bash
mvn package -DskipTests
```

The jar is `target/sqlancer-*.jar`, with its dependencies in `target/lib`.

## Start a VoltDB server

The provider needs a server with security disabled. Several sites per host exercise partitioned execution on one
machine:

```xml
<!-- deployment.xml -->
<deployment>
    <cluster sitesperhost="8" kfactor="0"/>
</deployment>
```

```bash
voltdb init --force --dir=/tmp/sqlancer-volt --config=deployment.xml
voltdb start --background --dir=/tmp/sqlancer-volt
```

The run removes only the tables it created, so a dedicated server is not required. Even so, keep it off a server that
holds data you care about: DDL from SQLancer serializes with all other DDL on the cluster.

## Run

```bash
java -jar target/sqlancer-*.jar \
    --num-threads 4 --num-tries 4 --timeout-seconds 1800 --num-queries 100 \
    --random-seed 20261102 --username "" --password "" \
    --host localhost --port 21212 --database-prefix db \
    voltdb --oracle NOREC
```

SQLancer's options come before `voltdb`, the provider's after it.

| Option | Meaning |
| --- | --- |
| `--num-threads`, `--num-tries` | Threads that run at once, and how many run in total. Use the same number for both. Every thread is one SQLancer "database". VoltDB runs one DDL statement at a time, so more than about 4 threads mostly wait. |
| `--timeout-seconds` | How long the run lasts. |
| `--num-queries` | Oracle checks per database before a thread drops it and generates a new one. 100 suits NoREC; with `SMOKE`, keep it small so schemas keep changing. |
| `--random-seed` | Thread *n* uses seed + *n*. A seed and the same server state give the same statements, but thread timing is not deterministic. |
| `--username`, `--password` | Empty for a server without security (SQLancer's default is `sqlancer`). |
| `--host`, `--port` | A server's client port. Defaults: localhost, 21212. |
| `--database-prefix` | Prefix of each thread's table names. Default `database`; `db` keeps the logs short. |
| `--oracle` | `NOREC` (the default) or `SMOKE`. |
| `--max-num-tables` | Tables per database, 1 to this (default 3). |
| `--max-num-indexes` | CREATE INDEX statements per database, 0 to this (default 4). |
| `--retry-seconds` | How long a statement rejected because of another thread's schema change is retried (default 120). |
| `--avoid-known-bugs` | `true` (the default) keeps the generator away from the VoltDB bugs listed below, so a failure is something new. `false` generates them too. |

## Reading the results

The run prints a progress line every 5 seconds:

```
[2026/11/02 10:01:27] Executed 475 queries (4 queries/s; 0.80/s dbs, successful statements: 94%). Threads shut down: 0.
```

- *queries* are oracle checks, *dbs* are databases (schemas) generated.
- *successful statements* counts every statement. The rest failed with an expected error (see below), which is normal.
- *Threads shut down* above 0 before the timeout means a thread hit an unexpected error: a suspected bug, or a gap in
  the provider.

Logs are in `logs/voltdb/` under the directory you run from:

| File | Contents |
| --- | --- |
| `<db>-cur.log` | The statements of the thread's current database, with times. It is overwritten for each new database. |
| `<db>.log` | Written only when the thread fails: the error and stack trace, then every statement of that database in order, which is the reproduction. |

The exit code is 0 when no thread failed. To reproduce a failure, start from an empty server and run the statements in
`<db>.log` in order (with `sqlcmd`, for example).

A NoREC failure ends with the two queries, `-- optimized:` and `-- unoptimized:`, and either the two counts that differ
("the row counts of the following queries mismatch") or the unexpected error.

## The NoREC oracle

NoREC ([Rigger and Su, 2020](https://doi.org/10.1145/3368089.3409704)) runs one random predicate two ways and compares
the counts:

```sql
-- optimized: the predicate is a WHERE clause, which the planner can push into index scans, joins and partitions
SELECT COUNT(*) FROM t0, t1 LEFT JOIN t2 ON ... WHERE p;
-- unoptimized: the predicate is evaluated on every row, so the planner cannot use it
SELECT COALESCE(SUM(c), 0) FROM (SELECT CASE WHEN p THEN 1 ELSE 0 END AS c FROM t0, t1 LEFT JOIN t2 ON ...) AS res;
```

Half the time the optimized query selects the rows instead of counting them, and the oracle counts the result set.

The VoltDB expression generator (`gen/VoltDBExpressionGenerator`) builds predicates from columns and constants with
AND, OR, NOT, the comparisons, BETWEEN, IN, LIKE, IS [NOT] NULL, `+ - * /`, `||` and ABS. To stay inside what VoltDB
supports:

- **Joins follow partitioning.** Partitioned tables are joined to the first partitioned table (the anchor) on their
  partitioning columns, so every query runs in one partition or as a correct multi-partition join. The ON condition is
  the equality alone: VoltDB plans the join only when it can see that equality, and an added condition that folds to
  FALSE, such as `pc = pc AND (1 = 0)`, takes it away ("This query is not plannable"). A partitioned table whose
  partitioning column cannot be compared with the anchor's is left out. Replicated tables are in the FROM list or,
  sometimes, joined explicitly (INNER, LEFT, RIGHT or FULL; RIGHT only when `--avoid-known-bugs` is false) with a random
  ON condition.
- **Typed NULLs.** A NULL constant is `CAST(NULL AS type)`; VoltDB rejects a bare NULL whose type it cannot infer.
- **Syntax.** Negative constants are in parentheses (`- -5` is a syntax error), `MOD` is never written as `%`, and an
  IN predicate is never the operand of a comparison or another IN (`x = (y IN (1))` is a syntax error).
- **Every WHERE clause references a column.** VoltDB does not support a join filter that depends on none of the joined
  tables.

## How the provider maps SQLancer onto VoltDB

- **One schema, many databases.** A VoltDB cluster has a single schema, so each SQLancer database is the set of tables
  and indexes named with its prefix (`DB0_T0`, `DB0_I3`). Creating a database drops that prefix's leftover tables.
  Tables are found through JDBC metadata, and the partitioning column comes from `getTables`' `REMARKS` JSON.
- **Schema changes collide across threads.** VoltDB runs one catalog update (DDL statement) at a time and rejects a
  concurrent one. It also aborts an ad hoc statement that was planned before another thread's DDL committed ("was
  planned against a catalog version which does not match"). Both are rejected before they run, so `VoltDBConnection`
  retries them for up to `--retry-seconds` and they are never reported.
- **Partitioning.** About half the tables are partitioned, sent as one batch: `CREATE TABLE ...; PARTITION TABLE ... ON
  COLUMN ...;`. The partitioning column is NOT NULL and of an integer, VARCHAR or VARBINARY type. A PRIMARY KEY always
  includes it. A UNIQUE constraint or index that does not include it is ASSUMEUNIQUE, which VoltDB checks only within
  a partition.
- **Types.** TINYINT, SMALLINT, INTEGER, BIGINT, FLOAT, DECIMAL, VARCHAR, VARBINARY, TIMESTAMP, DATE and BOOLEAN.
  VARCHAR and VARBINARY sizes fall on both sides of 64 bytes, where VoltDB moves values out of the row. GEOGRAPHY types
  are not generated yet.
- **Inserts** are single-row (VoltDB has no multi-row VALUES), and always set every NOT NULL column.

### Intentional VoltDB differences

These are VoltDB rules, not bugs. The generator stays inside them rather than expecting their errors:

- Each integer type's minimum value is its NULL, so TINYINT runs from -127 to 127, and so on. FLOAT's NULL is
  -1.7976931348623157E308. Neither is generated: see ENG-22880 below.
- FLOAT has no infinities. DECIMAL is fixed at precision 38, scale 12 (26 digits before the point).
- DATE and TIMESTAMP accept years 1583 to 9999.

### Expected errors

Each is listed in `VoltDBErrors` with its reason. The run fails on any other error.

| Statement | Error | Why it cannot be avoided |
| --- | --- | --- |
| INSERT | `CONSTRAINT VIOLATION` | Random values repeat existing keys. |
| CREATE INDEX | `is not empty` | VoltDB cannot add a UNIQUE index to a table that has rows (an ASSUMEUNIQUE index it can). |
| SELECT | `Join with filters that do not depend on joined tables is not supported` | A predicate that references a column can still fold to a constant, such as `(FALSE AND t.c) = FALSE`. |
| SELECT | `Attempted to divide` | Integer or DECIMAL division by zero. |
| SELECT | `Invalid result value` | A FLOAT result is infinite or NaN. |
| SELECT | `will overflow BigInt storage`, `causing overflow/underflow`, `out of range for the destination type` | Integer, DECIMAL or cast overflow. |
| SELECT | `Numeric conversion error to type DECIMAL` | DECIMAL overflow in an expression of constants, which the planner folds. The message quotes a truncated value. |

The two NoREC queries can evaluate an expression on different rows (the optimized query may skip rows through an index
or a join), so only one of them may hit a run-time error. The oracle treats an expected error in either query as no
result.

VoltDB also drops a CREATE INDEX that duplicates an existing index, logging "Dropping index ... because it duplicates
index ..." at WARN. The statement succeeds, so the provider ignores it.

### VoltDB issues the provider works around

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30489 | Closing a JDBC connection twice closes the client every other connection to the same URL shares, failing their in-flight calls. SQLancer closes each connection twice. | `VoltDBConnection.close()` is idempotent. |
| ENG-4643 | JDBC `getString` on a NULL returns the type's NULL sentinel (INTEGER, TINYINT, FLOAT) or throws (DECIMAL, BOOLEAN, DATE, TIMESTAMP). | Not needed: NoREC reads only counts. TLP compares values and will need one. |
| ENG-12656 | An index scan for `<`, `<=`, `>`, `>=` or BETWEEN on a VARBINARY(n) column, and for `=` when the column leads a multi-column unique index, fails ("Type VARCHAR can't be cast as VARBINARY") when the value, a constant or another table's column, is longer than n bytes. Found again by NoREC. | While `--avoid-known-bugs` is true, VARBINARY values are never compared with `<`, `>`, `<=`, `>=` or BETWEEN, a VARBINARY comparison has a constant on one side, and VARBINARY constants are at most one byte. |
| ENG-22880 | A BIGINT literal at the type's minimum is stored as NULL instead of rejected. A FLOAT at -1.7976931348623157E308 and an INTEGER expression equal to INTEGER's minimum do the same. | Never generated. |

The bugs below were found by NoREC in milestone 2. While `--avoid-known-bugs` is true (the default) the generator
avoids each one as described; set it to false to check whether a fix works.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30493 | BOOLEAN values order TRUE < FALSE where the EE compares them: `<`, `>`, BETWEEN and ORDER BY give wrong rows. | BOOLEAN values are only compared with `=` and `<>`. |
| ENG-30494 | BOOLEAN literals are passed to the EE as parameters of the wrong value or type: cast errors and wrong rows. | Boolean constants are written `(1 = 1)` and `(1 = 0)`. |
| ENG-30495 | A WHERE clause that folds to a constant NULL crashes the planner (NPE). | No NULL constants; NULLs come from the data. |
| ENG-30496 | LIKE inside CASE WHEN crashes the planner when its left operand is a constant or its pattern has no wildcard or is just `%`. | LIKE's left operand is a string column; the pattern has a wildcard and a literal character. |
| ENG-30497 | With an outer join, a boolean expression over the inner table, such as `(t.c IS NULL) IS NOT NULL` or `(t.c IS NULL) = TRUE`, turns the join into an inner join: wrong rows. | With an outer join, boolean operands of comparisons, IN and IS NULL are columns or constants. |
| ENG-30499 | Comparing a DATE constant with a TIMESTAMP constant crashes the planner (ClassCastException). | Temporal constants are `CAST('...' AS TIMESTAMP)`. |
| ENG-30500 | A boolean expression `IN (NULL, ...)` gives wrong rows. | IN list elements are constants that are not NULL. |
| ENG-30502 | Unary minus on a NULL integer or FLOAT gives a non-NULL value. | `0 - x` instead of `-x`. |
| ENG-30503 | IN lists ignore NULL elements: `x NOT IN (NULL, ...)` is TRUE instead of NULL. | As ENG-30500. |
| ENG-30504 | `NOT (b OR c)` over BOOLEAN values fails in the planner (cast error) when it is an operand: `NOT (NOT (b OR c))`, `(NOT (b OR c)) IS NOT NULL`. | NOT is never applied directly to OR. |
| ENG-30505 | `x IN ('...')` on a unique VARCHAR(n) or VARBINARY(n) column fails when a value is longer than n. | String and binary constants are at most one character or byte, as for ENG-30509 and ENG-12656. |
| ENG-30506 | In a parenthesized AND/OR list, an operand after an IN predicate that starts with a function call or a negative literal crashes the parser (NPE). | Function calls are always printed in parentheses, whatever `--avoid-known-bugs` is. |
| ENG-30507 | With an outer join, a WHERE IN predicate on an indexed column is applied as part of the join instead of after it, so it does not remove the rows it should: wrong rows. | No IN predicates when the query has an outer join. |
| ENG-30508 | A join filter of constants only, which VoltDB rejects ("Join with filters that do not depend on joined tables"), fails with an internal NumberFormatException instead when it is an IN with a DECIMAL constant on the left. | The internal error is an expected error, like the rejection it replaces. |
| ENG-30509 | A range scan on a multi-column index compares a VARCHAR(n) column with only the first n characters of a longer value, a constant or another table's column: `c >= '-2'` matches `'-'`, and so does `c LIKE '-2%'`. Wrong rows. | Strings are never compared with `<`, `>`, `<=`, `>=` or BETWEEN; string constants and a LIKE pattern's literal prefix are at most one character (the smallest VARCHAR). |
| ENG-30510 | After turning a RIGHT JOIN into a LEFT JOIN, VoltDB can use an ON condition on the preserved table as that table's index range, dropping rows that should be null-extended. Wrong rows. | No RIGHT JOIN (a LEFT JOIN with the tables swapped is the same). |

The published `voltdbclient` jar does not register its JDBC driver with `java.sql.Driver`, so the provider loads
`org.voltdb.jdbc.Driver` itself.

## Tests

`test/sqlancer/dbms/TestVoltDB.java` runs the provider for `TestConfig.SECONDS` against a server on localhost:21212. It
is skipped unless `VOLTDB_AVAILABLE=true`:

```bash
VOLTDB_AVAILABLE=true mvn -Djacoco.skip=true -Dtest=TestVoltDB test
```

`mvn verify -DskipTests` runs the formatter, checkstyle, PMD and SpotBugs checks that every provider passes.
