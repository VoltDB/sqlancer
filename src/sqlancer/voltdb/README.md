# VoltDB provider

A SQLancer provider for [VoltDB](https://www.voltactivedata.com/), developed for ENG-30396. It generates random
VoltDB schemas, both replicated and partitioned tables, fills them with single-row inserts, and runs SQLancer's oracles
against them through the VoltDB JDBC driver.

**Status: milestone 1.** The provider creates schemas and data. Its only oracle, `SMOKE`, reads every table and checks
nothing but that the queries succeed. The logic-bug oracles come next: NoREC (milestone 2), then TLP WHERE
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
    --num-threads 4 --num-tries 4 --timeout-seconds 1800 --num-queries 5 \
    --random-seed 20261102 --username "" --password "" \
    --host localhost --port 21212 --database-prefix db \
    voltdb --oracle SMOKE
```

SQLancer's options come before `voltdb`, the provider's after it.

| Option | Meaning |
| --- | --- |
| `--num-threads`, `--num-tries` | Threads that run at once, and how many run in total. Use the same number for both. Every thread is one SQLancer "database". VoltDB runs one DDL statement at a time, so more than about 4 threads mostly wait. |
| `--timeout-seconds` | How long the run lasts. |
| `--num-queries` | Oracle checks per database before a thread drops it and generates a new one. With `SMOKE`, keep it small so schemas keep changing. |
| `--random-seed` | Thread *n* uses seed + *n*. A seed and the same server state give the same statements, but thread timing is not deterministic. |
| `--username`, `--password` | Empty for a server without security (SQLancer's default is `sqlancer`). |
| `--host`, `--port` | A server's client port. Defaults: localhost, 21212. |
| `--database-prefix` | Prefix of each thread's table names. Default `database`; `db` keeps the logs short. |
| `--oracle` | `SMOKE` (the default, and the only oracle so far). |
| `--max-num-tables` | Tables per database, 1 to this (default 3). |
| `--max-num-indexes` | CREATE INDEX statements per database, 0 to this (default 4). |
| `--retry-seconds` | How long a statement rejected because of another thread's schema change is retried (default 120). |

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

VoltDB also drops a CREATE INDEX that duplicates an existing index, logging "Dropping index ... because it duplicates
index ..." at WARN. The statement succeeds, so the provider ignores it.

### VoltDB issues the provider works around

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30489 | Closing a JDBC connection twice closes the client every other connection to the same URL shares, failing their in-flight calls. SQLancer closes each connection twice. | `VoltDBConnection.close()` is idempotent. |
| ENG-4643 | JDBC `getString` on a NULL returns the type's NULL sentinel (INTEGER, TINYINT, FLOAT) or throws (DECIMAL, BOOLEAN, DATE, TIMESTAMP). SQLancer's oracles compare results with `getString`. | Not needed yet; the NoREC and TLP oracles will need one. |
| ENG-22880 | A BIGINT literal at the type's minimum is stored as NULL instead of rejected. A FLOAT at -1.7976931348623157E308 and an INTEGER expression equal to INTEGER's minimum do the same. | Never generated. |

The published `voltdbclient` jar does not register its JDBC driver with `java.sql.Driver`, so the provider loads
`org.voltdb.jdbc.Driver` itself.

## Tests

`test/sqlancer/dbms/TestVoltDB.java` runs the provider for `TestConfig.SECONDS` against a server on localhost:21212. It
is skipped unless `VOLTDB_AVAILABLE=true`:

```bash
VOLTDB_AVAILABLE=true mvn -Djacoco.skip=true -Dtest=TestVoltDB test
```

`mvn verify -DskipTests` runs the formatter, checkstyle, PMD and SpotBugs checks that every provider passes.
