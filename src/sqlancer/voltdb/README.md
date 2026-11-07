# VoltDB provider

A SQLancer provider for [VoltDB](https://www.voltactivedata.com/), developed for ENG-30396. It generates random
VoltDB schemas, both replicated and partitioned tables, fills them with single-row inserts, upserts, updates and deletes, and runs SQLancer's oracles
against them through the VoltDB JDBC driver.

**Status: milestone 13.** The provider creates schemas and data and runs these logic-bug oracles: NoREC (the default,
milestone 2), TLP WHERE (milestone 3), TLP DISTINCT, TLP HAVING and TLP AGGREGATE (milestone 5), PQS (milestone 6), and
PARAMS, literals against JDBC parameters (milestone 8). Since milestone 9 each database's rows are also updated,
upserted and deleted before the oracles run. Since milestone 10 each database also has materialized views, which the
VIEWS oracle checks. Since milestone 11 expressions also use CASE, COALESCE, MOD, FLOOR, CEILING, CHAR_LENGTH,
SUBSTRING, UPPER, LOWER and TRIM, and the LIMIT oracle checks LIMIT and OFFSET. Since milestone 12 the run script
can kill and rejoin nodes while SQLancer runs (`--kill-interval`), which the nightly job's 3-node run does. Since
milestone 13 the nightly job also runs a third cluster shape: 3 nodes with k=2 and 2 sites per host.
`SMOKE`, from
milestone 1, only reads every table and checks that the queries succeed. `voltdb/run-sqlancer.sh` (milestone 4) starts a
cluster, runs SQLancer against it and saves the results. A nightly lab job (milestone 7) runs it against master's kit;
see [The nightly lab job](#the-nightly-lab-job).

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

## Run everything with one script

`voltdb/run-sqlancer.sh`, at the top of the repository, does a whole run:
1. It starts a VoltDB cluster on this machine from a kit or build.
2. It runs SQLancer against the cluster for a set time with a fixed seed.
3. It stops the cluster and saves everything to one directory.

It builds SQLancer first if `target/` has no jar.

```bash
# one node, 8 sites per host, NoREC and TLP WHERE for 30 minutes
voltdb/run-sqlancer.sh --voltdb ~/workspace/VoltDB/internal --seed 20261103

# three nodes with k-safety 1, one hour, reduce failures
voltdb/run-sqlancer.sh --voltdb /opt/voltdb --nodes 3 --duration 3600 --seed 7 --use-reducer
```

| Option | Default | Meaning |
| --- | --- | --- |
| `--voltdb DIR` | `$VOLTDB_HOME` | A VoltDB kit or build: the directory holding `bin/voltdb`. |
| `--license FILE` | none | The license `voltdb init` gets. An enterprise kit needs one; a build from source does not. |
| `--nodes N` | 1 | Nodes, all on this machine. |
| `--kfactor K` | 0 for one node, else 1 | K-safety. |
| `--sites N` | 8 | Sites per host. |
| `--oracle LIST` | `NOREC,TLP_WHERE` | Comma-separated oracles, for example `NOREC,TLP_WHERE,TLP_DISTINCT,TLP_HAVING,TLP_AGGREGATE`. With several, each thread takes turns among them. |
| `--duration SECS` | 1800 | How long SQLancer runs. |
| `--seed N` | the current time | The random seed. It is in the manifest either way, so any run can be repeated. |
| `--threads N` | 4 | SQLancer threads. |
| `--queries N` | 100 | Oracle checks per generated database. |
| `--port-base N` | 30000 | The cluster's first port. Node *i* uses the 6 ports from N + 10*i (client, admin, internal, zookeeper, replication, metrics). The script refuses to start if any is in use, and it never touches other servers. The default is below the ephemeral ports Linux and macOS give outgoing connections, one of which can take a killed node's port before it restarts. |
| `--results DIR` | `./sqlancer-results` | Where the run's directory is created. |
| `--use-reducer` | off | Reduce each failure before saving it (see [Reducing a failure](#reducing-a-failure)). |
| `--kill-interval SECS` | 0 (never) | Every SECS seconds, kill a node other than node 0 with `kill -9`, restart it and wait until it has rejoined (see [Node failures](#node-failures)). Needs k-safety 1 or more. |
| `--keep-cluster` | off | Leave the cluster running, with its data, after the run. |
| `-- ...` | | Everything after `--` goes to the provider, for example `-- --avoid-known-bugs false`. |

The exit code is 0 for PASS, 1 for FAIL, and 2 when the run could not be set up (bad options, a busy port, a cluster
that did not start). Each run writes `<results>/sqlancer-<UTC time>-seed<N>/`:

| Path | Contents |
| --- | --- |
| `summary.txt` | `RESULT: PASS` or `FAIL`, the progress line, and the oracle's report for each failed database. |
| `manifest.txt` | Run id, host, command line, SQLancer commit, VoltDB version, Java version, cluster shape and ports, oracles, duration, seed and threads. |
| `run.log` | The script's own steps. |
| `deployment.xml` | The cluster's configuration. |
| `servers/node<i>/` | Each node's `volt.log` and its `voltdb init` and `voltdb start` output. |
| `sqlancer/` | SQLancer's output (`out.log`) and its `logs/voltdb/` directory (see [Reading the results](#reading-the-results)). |
| `chaos.log` | With `--kill-interval`: each kill and rejoin, with its time. |
| `reproductions/` | The log of each database whose thread failed: the statements that set it up, then the failing queries. With `--use-reducer`, these are reduced, and `<db>-reduce.log` shows the steps. |

A run FAILs if any of these happens:
- SQLancer exits non-zero, or any thread fails.
- A node is down at the end.
- A server log has a FATAL line.
- With `--kill-interval`, a killed node does not rejoin within 600 seconds.

The cluster's data is deleted after the logs are copied, unless `--keep-cluster` is given. Several runs can share a
machine if each has its own `--port-base`.

## Node failures

With `--kill-interval SECS`, the script kills a node every SECS seconds while SQLancer runs, and the oracles keep
checking query results through the failures:

1. It picks a node other than node 0 at random. SQLancer connects to node 0 only, so its connections survive.
2. It kills the node's server with `kill -9`, waits until its client port closes, and waits 5 seconds more for the
   survivors to notice.
3. It restarts the node with the same `voltdb start` command, which rejoins it to the running cluster: it gets its
   partitions' data back from the replicas, while the cluster keeps working.
4. It waits until the rejoin completes ("Server completed initialization" in the restart's output), up to 600 seconds,
   and logs both events to `chaos.log`. A rejoin that does not complete fails the run. A restart that exits because a
   port is taken ("Address already in use") is tried again, up to 5 times: an outgoing connection elsewhere on the
   machine can take a port in the ephemeral range while the node is down, which is why `--port-base` defaults below
   it.

When SQLancer stops, the script lets the current rejoin finish, so the run ends with every node up, and the summary
counts the kills and rejoins. A local rejoin takes about 10 seconds.

With `--kill-interval`, the script passes `--node-failures true` to the provider, which makes `VoltDBConnection`
handle the errors a node failure causes:

| Error | What it means | What the provider does |
| --- | --- | --- |
| `Transaction dropped due to change in mastership. It is possible the transaction was committed` | The statement was in flight when a partition's leader moved; it may have committed. | Skips the statement, check or database (IgnoreMeException), since it cannot know. |
| `Transaction being restarted due to fault recovery or shutdown` | The same for a read, aborted by the failure. | The same. |
| `OPS request hit sixty second timeout before all responses were received` | A system request, such as the JDBC metadata calls that read the schema, waited on the dead node. | The same. |
| `The errors for @VerifyCatalogAndWriteJar from host N: Host N failed, connection lost` | A DDL statement's catalog update was waiting on the dead node. | The same. |
| `object name already exists` | A DDL statement that a failure interrupted committed after all, so a later CREATE of the same name collides with a table or index the provider never saw. | Skips, as above. Without `--node-failures` this fails the run, as it would be a provider bug. |
| `Can't do catalog update(@AdHoc) while node rejoin is in progress`, `... while catalog update transaction is in progress` | VoltDB rejects DDL until the rejoin is done, or until a catalog update the failure interrupted is cleaned up. | Retries the statement, like a concurrent schema change, for up to `--retry-seconds`. VoltDB rejects it before running it. |

Without `--node-failures`, these errors fail the run as before. The OPS timeout makes each failure cost up to a minute
of the threads that read the schema at the time, so a run with failures checks fewer queries.

## The nightly lab job

`matrix-systemtest-apprunner-sqlancer` on jenkins-st runs this script against master's kit as part of the nightly
system test (`matrix-systemtest/master`, on the nights the rotation in `internal`'s
`jenkins-lab/systemtest/trigger-job-for-systemtest` gives master). Its pipeline is `internal`'s
`jenkins-lab/systemtest/apprunner/sqlancer/Jenkinsfile`; the script it calls is `pro`'s
`tests/apptests/jenkins-scripts/run-jenkins-sqlancer-test.sh`.

The job fetches this repository at the commit in its `SQLANCER_REF` parameter. That commit is pinned in the Jenkinsfile,
so a change here reaches the nightly run only when the Jenkinsfile is changed to name it. It then builds SQLancer with
the kit's runtime JDK and makes three runs, one after the other, every node on the agent:

| Run (triagedb name) | Cluster | Oracles | Time |
| --- | --- | --- | --- |
| `sqlancer-3n-k1-kill300` | 3 nodes, k=1, 8 sites per host; a node is killed and rejoined every 5 minutes | all nine (NoREC, TLP WHERE, DISTINCT, HAVING, AGGREGATE, PQS, PARAMS, VIEWS, LIMIT) | 45 minutes |
| `sqlancer-1n-k0` | 1 node, 8 sites per host | the same | 45 minutes |
| `sqlancer-3n-k2-s2` | 3 nodes, k=2, 2 sites per host: every partition on every node, and 2 partitions in all | the same | 45 minutes |

The shapes differ in what the planner and the execution engine do. With one node and 8 sites, a multi-partition query
gathers 8 partitions' results in one process. With 3 nodes, k=1, the partitions' leaders are spread over the nodes and
each has one replica, and node failures move them. With k=2 and 2 sites per host, every node holds every partition, so
there are only 2 partitions and every write is replicated twice. The whole job takes about 2 hours 20 minutes.

Every run uses `--use-reducer`. The first run's seed is the UTC time as `yyyymmddHHMM`, and each next run's is one
more.
`--avoid-known-bugs` stays on, so a failure is a new bug, or a known one in a new form.

Each run is one row in the triagedb, with the seed in its run arguments. A failing row's reason is the oracle's account
of the first failed database (or what else failed the run), and the run's whole results directory is archived on the
build as `savedlogs/sqlancer/<name>-PASS.tar.gz` or `-FAIL.tar.gz`. A failing bundle is also copied to
`gs://cloudtest-storage/apprunner-failures/<branch>/<build tag>/`, like other apprunner suites' bundles.

To repeat a failure on your machine, take the seed and oracles from the bundle's `manifest.txt` and run the script with
them against the same build. The reproduction in `reproductions/` is usually quicker: run its statements on an empty
server.

The job's parameters change a manual run: `CLUSTERS` (runs as `nodes:kfactor[:kill[:sites]]`, where kill is the
seconds between node failures and sites the sites per host, default `3:1:300 1:0 3:2:0:2`), `DURATION` (seconds
per run, default 2700), `ORACLES`, `SEED`, `EXTRA_ARGS` (provider options) and `SQLANCER_REF`. A run of the job alone
needs a kit: give `releasedir`, for example `releases/one-offs/16.1.0-SNAPSHOT-master/<kit build>`.

## Start a VoltDB server by hand

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
| `--num-queries` | Oracle checks per database before a thread drops it and generates a new one. 100 suits NoREC and TLP WHERE; with `SMOKE`, keep it small so schemas keep changing. |
| `--random-seed` | Thread *n* uses seed + *n*. A seed and the same server state give the same statements, but thread timing is not deterministic. |
| `--username`, `--password` | Empty for a server without security (SQLancer's default is `sqlancer`). |
| `--host`, `--port` | A server's client port. Defaults: localhost, 21212. |
| `--database-prefix` | Prefix of each thread's table names. Default `database`; `db` keeps the logs short. |
| `--oracle` | `NOREC` (the default), `TLP_WHERE`, `TLP_DISTINCT`, `TLP_HAVING`, `TLP_AGGREGATE`, `PQS`, `PARAMS`, `VIEWS`, `LIMIT` or `SMOKE`; several are comma-separated. |
| `--max-num-tables` | Tables per database, 1 to this (default 3). |
| `--max-num-indexes` | CREATE INDEX statements per database, 0 to this (default 4). |
| `--max-num-updates` | UPDATE statements per database, 0 to this (default 5). |
| `--max-num-deletes` | DELETE statements per database, 0 to this (default 2). |
| `--max-num-views` | CREATE VIEW statements per database, 0 to this (default 2). |
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

A TLP WHERE, DISTINCT or HAVING failure ends with the original query, the partition queries, and the rows that only one
side returned ("the rows of these queries mismatch"). A TLP AGGREGATE failure ends with the queries, the original
query's aggregates, the ones combined from the partitions, and each partition's own ("the aggregates of these queries
mismatch").

### Reducing a failure

Add `--use-reducer` (a SQLancer option, before `voltdb`) to have SQLancer reduce each failure before reporting it. It
reruns the database's statements on an empty prefix, leaving out one at a time (or a chunk), and keeps a statement only
if the failure no longer happens without it. The reduced statements replace the originals in `<db>.log`, and
`logs/voltdb/reduce/<db>-reduce.log` records each step. Every oracle but SMOKE supports it, for wrong results and for
unexpected errors. The reducer removes statements but does not simplify the failing query itself, so check the
remaining query by hand: drop its parts while the failure persists, then retry it on fresh tables without SQLancer's
naming before filing.

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
  ON condition. When partitioned tables are joined, those explicit joins are LEFT or FULL: in a chain of INNER JOINs,
  a replicated table's ON condition that folds to FALSE takes the partitioning columns' equality with it too. An outer
  join can lose it as well, with some WHERE clauses on the replicated table, so "This query is not plannable" is an
  expected error (see below).
- **Typed NULLs.** A NULL constant is `CAST(NULL AS type)`; VoltDB rejects a bare NULL whose type it cannot infer.
- **Syntax.** Negative constants are in parentheses (`- -5` is a syntax error), `MOD` is never written as `%`, and an
  IN predicate is never the operand of a comparison or another IN (`x = (y IN (1))` is a syntax error).
- **Every WHERE clause references a column.** VoltDB does not support a join filter that depends on none of the joined
  tables.

## The TLP WHERE oracle

Ternary Logic Partitioning ([Rigger and Su, 2020](https://doi.org/10.1145/3428279)) relies on every row making a
predicate exactly one of TRUE, FALSE or NULL. A query without a WHERE clause must therefore return the same rows as
the three queries that add `WHERE p`, `WHERE NOT p` and `WHERE p IS NULL`:

```sql
-- original
SELECT t1.c2, t0.c0 FROM t0, t1 LEFT JOIN t2 ON ...;
-- partitions
SELECT t1.c2, t0.c0 FROM t0, t1 LEFT JOIN t2 ON ... WHERE p;
SELECT t1.c2, t0.c0 FROM t0, t1 LEFT JOIN t2 ON ... WHERE NOT p;
SELECT t1.c2, t0.c0 FROM t0, t1 LEFT JOIN t2 ON ... WHERE NOT (p IS NOT NULL);
```

Unlike NoREC, it compares values, not just counts, so it also catches a query that returns the right number of
wrong rows. The predicate `p`, the FROM clause and the joins come from the same generator as NoREC's, with the same
rules and known-bug workarounds. The fetched columns are a random subset of the FROM clause's columns, and sometimes
the partitions have an ORDER BY.

`oracle/VoltDBTLPOracle` differs from SQLancer's shared `TLPWhereOracle` in two ways:

- **UNION ALL only over replicated tables.** VoltDB does not run a set operation over partitioned tables ("Statements
  are too complex in set"), so when the query reads one, the three partitions run as separate queries. Over replicated
  tables alone they run as one `UNION ALL` query, which exercises VoltDB's set operations too.
- **Whole rows.** The two sides are compared as multisets of whole rows. The shared oracle compares the set of values
  in the first column only.

An expected error in any of the queries skips the check, as for NoREC.

## The TLP DISTINCT, HAVING and AGGREGATE oracles

These apply the same partitioning to other parts of a query, as in the TLP paper. All three use the FROM clauses, joins
and predicates of TLP WHERE.

**TLP DISTINCT** (`oracle/VoltDBTLPOracle`, mode `DISTINCT`) checks duplicate elimination. The original query is a
`SELECT DISTINCT` of some columns or, half the time, the same columns with `GROUP BY` and no aggregate. Each partition
adds `WHERE p`, `WHERE NOT p` or `WHERE p IS NULL`. A row can come from more than one partition, so the partitions'
rows, without duplicates, must equal the original's, which must have none. Over replicated tables, the partitions run
as one `UNION` query. FLOAT values are compared exactly: two that differ only in their last digits, such as
-32767.000000000004 and -32766.999999999996, are two rows.

**TLP HAVING** (mode `HAVING`) checks groups. The original query groups by some columns, selects some of them and 1
to 3 aggregates, and sometimes has a WHERE clause. Each partition adds `HAVING p`, `HAVING NOT p` or `HAVING p IS
NULL`, where `p` is a predicate over the grouping columns and aggregates (MIN, MAX, SUM and COUNT of expressions over
the FROM clause's columns). Every group makes `p` exactly one of TRUE, FALSE or NULL, so the partitions' rows must equal
the original's. VoltDB requires an aggregate in a HAVING clause, so `p` always has one. FLOAT values in the rows are
compared to 12 significant digits, since a FLOAT SUM adds the same values in an order that can differ between queries.

**TLP AGGREGATE** (`oracle/VoltDBTLPAggregateOracle`) checks aggregation. The original query computes 1 to 3 of MIN,
MAX, SUM, COUNT(x) and COUNT(*) over all rows, with no GROUP BY. Each partition computes the same with `WHERE p`,
`WHERE NOT p` or `WHERE p IS NULL`. Combined, the partitions must give the original's values: the least of the MINs,
the greatest of the MAXes, and the sum of the SUMs and of the COUNTs.

- **Combined in Java.** The shared oracles combine the partitions in SQL, as an outer aggregate over a `UNION ALL`,
  which VoltDB rejects over partitioned tables. This oracle combines them in Java for every query.
- **Exact where VoltDB is.** Integer and DECIMAL SUMs are added exactly. FLOAT SUMs may differ by a relative 1e-9, as
  they add the same values in a different order. VARCHAR values are ordered by their UTF-8 bytes, VARBINARY by their
  bytes.
- **Left out.** AVG cannot be combined from the partitions' AVGs. MIN and MAX of BOOLEAN values are left out because of
  ENG-30493.

## The PQS oracle

Pivoted Query Synthesis (`oracle/VoltDBPivotedQuerySynthesisOracle`) checks a query against values computed outside
VoltDB, which the other oracles never do: they compare VoltDB with itself, so a comparison or function that is wrong
the same way everywhere passes them.

1. **Pivot row.** It reads every row of the query's tables and picks one row of each, the pivot row. Partitioned
   tables are joined on their partitioning columns, so their rows of the pivot row share a partitioning value.
2. **Rectified predicates.** It generates a WHERE predicate, and an ON condition for each joined replicated table,
   evaluates each on the pivot row in Java, and rewrites it to be TRUE there: `p`, `NOT p` or `p IS NULL`.
3. **Expressions.** It adds up to three random expressions (numbers, strings, booleans, ...) to the SELECT list, with
   their values on the pivot row.
4. **Check.** The query fetches every column, so the pivot row must be among its rows, and the expressions must have
   the computed values on it.

The join types are TLP WHERE's: INNER, LEFT and FULL joins all return the pivot row, as their ON conditions are TRUE for
it. SQLancer's tables are small, so the rows are read and checked in Java; a query whose tables could produce more than
50,000 rows is skipped.

**The evaluator** (`oracle/VoltDBExpressionEvaluator`) follows VoltDB's execution engine, as checked against it:

- Integer arithmetic is BIGINT and truncates division toward zero. DECIMAL keeps scale 12 and truncates multiplication
  and division toward zero. FLOAT is IEEE double arithmetic. An integer with a DECIMAL is a DECIMAL, with a FLOAT a
  FLOAT.
- Comparisons, AND, OR, NOT, BETWEEN, IN and LIKE follow SQL's three-valued logic. VARCHAR compares exactly, by UTF-8
  bytes; LIKE is case-sensitive, `_` is one character and nothing escapes `%` or `_`. A DATE compares with a TIMESTAMP
  as midnight.
- A string literal goes through UTF-8 first, as the driver sends it: a character UTF-8 cannot encode, such as a lone
  surrogate in SQLancer's random strings, reaches VoltDB as `?`.
- CASE and COALESCE take the widest type of their results: integers and DECIMAL give DECIMAL, and any FLOAT gives
  FLOAT. FLOOR, CEILING and ABS keep their argument's type; MOD (of integers) and CHAR_LENGTH are integers, and MOD
  takes the dividend's sign.
- Strings are counted in characters. `SUBSTRING(s, start, length)` is positions start to start + length - 1, counting
  from 1, so a start of 0 or less shortens it; it is empty, never NULL, past the end. TRIM, LTRIM and RTRIM remove
  spaces only.

What it cannot predict, it does not check: an expression is left out of the SELECT list, and a predicate skips the
check. That covers errors (VoltDB evaluates every operand, so an error anywhere fails the query), results equal to an
integer or FLOAT NULL sentinel, a DECIMAL mixed or compared with a FLOAT, and ordered comparisons of BOOLEAN values
(ENG-30493). An error in a CASE or COALESCE branch that is not taken counts too: VoltDB skips the branch at run time,
but fails on it when the planner folds it. So does UPPER or LOWER of a string beyond ASCII, which VoltDB leaves as it
is (`UPPER('é')` is `'é'`). Its unit tests are `test/sqlancer/voltdb/oracle/TestVoltDBExpressionEvaluator.java`.

A failure reports the query, the pivot row, and the expected and actual values. Its reducer checks that every table
still holds its row of the pivot row before re-running the query.

## The PARAMS oracle

PARAMS (`oracle/VoltDBParameterOracle`) runs each query twice: once with its constants as literals, and once with some
of them as JDBC parameters of a `PreparedStatement`. Both must return the same rows. VoltDB turns an ad hoc statement's
literals into parameters itself, or plans the statement with them inline when it cannot, so the two queries reach the
planner and the executor by different paths, with values typed differently. Most of the bugs found in milestones 6
and 7 (ENG-30551, ENG-30552, ENG-30560, ENG-30562) were in that literal handling.

The query is a SELECT of columns and up to a few expressions with a WHERE clause, or a GROUP BY query with aggregates
and maybe WHERE and HAVING clauses, over the same joins as TLP WHERE (GROUP BY queries make no FULL JOINs, for
ENG-30531, since a parameter can change the plan). Each constant becomes a parameter with probability 3/4, written as:

- `?` when the other operand of its comparison, arithmetic, LIKE, BETWEEN or IN is a column whose type holds the value.
  VoltDB gives such a parameter the column's type and rejects a value that does not fit ("out of range for the target
  parameter type"), which is documented behavior, so a value is bare only where it fits.
- `CAST(? AS T)` otherwise, where T is the literal's own type (INTEGER or BIGINT by size, DECIMAL, FLOAT, VARCHAR,
  VARBINARY, TIMESTAMP). VoltDB cannot type a bare parameter with no column beside it ("data type cast needed for
  parameter or null literal"), and types one inside ABS as FLOAT where the literal would be an integer.

Values are bound with the matching setter (`setLong`, `setBigDecimal`, `setDouble`, `setString`, `setBytes`,
`setTimestamp`). A TIMESTAMP is bound as its instant at UTC, which is how VoltDB reads a TIMESTAMP literal, so the JVM's
time zone does not shift it.

These stay literals: BOOLEAN constants (ENG-30494) and typed NULLs; and, while `--avoid-known-bugs` is true, the
operands of an expression of constants alone, which the planner folds differently from how the executor computes
(ENG-30547, ENG-30548), and constants in a HAVING clause's aggregates (ENG-30572). A statement with parameters is not
auto-parameterized, so its remaining literals reach the planner as literals, which three known bugs make unsafe. While
`--avoid-known-bugs` is true, these constants are therefore always parameters, and a query that would have to keep one as a
literal is skipped:
- DECIMAL constants: the planner reads them as FLOAT (ENG-30560).
- VARBINARY constants: an aggregate of one is taken for an aggregate of a VARCHAR literal with the same text (ENG-30534).
- Strings holding `?`: the JDBC driver counts it as a parameter (ENG-30571).

A query is also skipped when one of its expressions of constants cannot be predicted by the PQS evaluator, such as one
that folds to a subnormal FLOAT, whose plan then fails to load (ENG-30551) and which can crash the node later.

An expected error in either query skips the check; an unexpected one fails it. Rows are compared as TLP compares them.
A failure reports both queries and the parameters' values and types, and `--use-reducer` works.

## The VIEWS oracle

VIEWS (`oracle/VoltDBViewOracle`) checks VoltDB's materialized views, which it keeps up to date as their tables
change rather than computing them again.

**The views.** Each database creates zero to `--max-num-views` views right after its tables, before any rows:
- VoltDB can't create some views on a table that already holds data ("cannot always be applied if the table already
  contains data").
- A view made first is kept up to date through every insert, upsert, update and delete that builds the database.

A view (`gen/VoltDBViewGenerator`) covers one table:
- It groups by zero to two non-BOOLEAN columns.
- It always has `COUNT(*)`, plus up to three more of `COUNT`, `SUM`, `MIN` and `MAX` of the table's columns. MIN and
  MAX are never of BOOLEAN columns (ENG-30493).
- While `--avoid-known-bugs` is true, SUM is only of NOT NULL TINYINT, SMALLINT and INTEGER columns (ENG-30584 and
  ENG-30588 below). VoltDB sums those as BIGINT, which can't overflow.
- While `--avoid-known-bugs` is false, half the views have a random WHERE clause. While it is true, none do (ENG-30583
  below).

While `--avoid-known-bugs` is true, the other oracles' queries never read a table that has a view: VoltDB's view
rewriter fails to plan some of their GROUP BY and aggregate queries on such a table (ENG-30582 below).

The view's defining query is kept with the database's state.

**The check.** It picks a view. Half the time it first runs one more INSERT, UPDATE or DELETE on the view's table, so
the check follows a change made right before it. It then compares three results, as multisets, as TLP compares them:
1. the view's rows, `SELECT * FROM v`;
2. the defining query with `ORDER BY 1`, which VoltDB's view rewriter (`MVQueryRewriter`) leaves alone, so it is
   computed from the table;
3. the defining query as written, which the rewriter may answer from the view itself.

The view and the query as written must both match the query computed from the table. VoltDB combines a view's
per-partition rows when it is read, so a view of a partitioned table also has one row per group. A failure reports
the three queries and the rows that differ, and `--use-reducer` works. A view whose CREATE VIEW failed with an expected
error is skipped.

## The LIMIT oracle

LIMIT (`oracle/VoltDBLimitOracle`) checks LIMIT and OFFSET. A query ordered by every one of its output columns has one
order, up to rows that are the same in every column, so `LIMIT n OFFSET m` must return exactly rows m + 1 to m + n of
the query without them, in the same order.

- The query reads the FROM clauses and joins of TLP WHERE, has a WHERE clause half the time, and is DISTINCT about one
  time in four. While `--avoid-known-bugs` is true it makes no FULL JOINs (ENG-30531 below).
- It outputs columns, or one to three expressions that read a column, of any kind but BOOLEAN, which VoltDB orders
  wrongly (ENG-30493). VoltDB rejects ORDER BY of a constant.
- ORDER BY names every output by position, each ascending or descending. LIMIT is 0 to 12, and OFFSET 0 to 12.
- Rows are compared in order, FLOAT values exactly with -0.0 as 0.0: the two are equal, so they tie, and either may
  come first.

VoltDB pushes LIMIT and OFFSET down to the partitions of a multi-partition query and merges their sorted results;
that, and ORDER BY on expressions and DISTINCT, is what this checks. A failure reports both queries, the full query's
rows, and the expected and actual slices.

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
- **Inserts** are single-row (VoltDB has no multi-row VALUES), and always set every NOT NULL column. About one in
  ten, into a table with a primary key, is an UPSERT instead (VoltDB supports UPSERT only there).
- **Expressions** (milestone 11). Besides comparisons, AND, OR, NOT, IS NULL, BETWEEN, IN, LIKE, arithmetic, ABS and
  `||`, the generator writes:
  - numeric: `FLOOR`, `CEILING`, `MOD` of integers, `CHAR_LENGTH` of a string, `CASE` and `COALESCE`;
  - string: `SUBSTRING(s, start [, length])` with constant positions, `UPPER`, `LOWER`, `TRIM`, `LTRIM`, `RTRIM`,
    `CASE` and `COALESCE`.

  A CASE is searched, `CASE WHEN p THEN x [ELSE y] END`. While `--avoid-known-bugs` is true it has one WHEN and an
  ELSE (ENG-30590 below), and there is no CASE in a query with an outer join (ENG-30497 below). There is no
  multiplication or division inside ABS, FLOOR, CEILING, MOD, CASE or COALESCE while it is true (ENG-30550 below).
- **Updates and deletes** (milestone 9) run among the inserts and CREATE INDEX statements that build a database, so the
  oracles check data that rows were updated, replaced and deleted to reach, through indexes built before and after.
  - `UPDATE t SET c = v [, ...] [WHERE p]` sets one to three columns. Each gets a constant of its type or an expression
    of its kind over the table's columns.
  - The partitioning column is never set: VoltDB does not support updating it.
  - `DELETE FROM t [WHERE p]` almost always has a WHERE clause, so a database keeps rows for the oracles.
  - WHERE clauses come from the same generator as the queries' and avoid the same bugs.

### Intentional VoltDB differences

These are VoltDB rules, not bugs. The generator stays inside them rather than expecting their errors:

- Each integer type's minimum value is its NULL, so TINYINT runs from -127 to 127, and so on. FLOAT's NULL is
  -1.7976931348623157E308. Neither is generated: see ENG-22880 below.
- FLOAT has no infinities. DECIMAL is fixed at precision 38, scale 12 (26 digits before the point).
- DATE and TIMESTAMP accept years 1583 to 9999.
- VoltDB does not GROUP BY a BOOLEAN expression, nor take DISTINCT of one ("A GROUP BY clause does not allow a BOOLEAN
  expression"). Grouping columns and DISTINCT columns are never BOOLEAN.
- A HAVING clause must have an aggregate ("does not support HAVING clause without aggregation").
- An aggregate query whose WHERE clause folds to FALSE returns no row instead of one with COUNT 0 and NULL for the
  others: the planner adds LIMIT 0 (ENG-19957, closed as won't fix). TLP AGGREGATE treats a partition with no row as
  that row. The original query has no WHERE clause, so it must return a row.

### Expected errors

Each is listed in `VoltDBErrors` with its reason. The run fails on any other error.

| Statement | Error | Why it cannot be avoided |
| --- | --- | --- |
| INSERT | `CONSTRAINT VIOLATION` | Random values repeat existing keys. |
| UPDATE | `CONSTRAINT VIOLATION` | A random new value repeats a primary key or unique value, or is NULL in a NOT NULL column. VoltDB checks constraints row by row, so even `SET k = k + 1` on a unique `k` fails once one row's new value meets the next row's old one. |
| UPDATE | `is too wide`, `exceeds the size of the`, `overflows/underflows`, `has an invalid format for a constant <integer type> value` | A new value does not fit its column: too long for a VARCHAR or VARBINARY, out of an integer type's range, or a constant with a fraction set into an integer column. |
| CREATE VIEW | `with a LIMIT or OFFSET clause is not supported` | A view's WHERE clause that folds to FALSE makes the planner add LIMIT 0 (ENG-19957, closed as won't fix), which a view rejects. |
| INSERT, UPSERT | the SELECT errors below | Keeping a view up to date evaluates its WHERE clause on the new row, which can overflow or divide by zero. |
| CREATE INDEX | `is not empty` | VoltDB cannot add a UNIQUE index to a table that has rows (an ASSUMEUNIQUE index it can). |
| SELECT | `Join with filters that do not depend on joined tables is not supported` | A predicate that references a column can still fold to a constant, such as `(FALSE AND t.c) = FALSE`. |
| SELECT | `This query is not plannable` | VoltDB plans a join of partitioned tables only when it can see the equality of their partitioning columns. The generator joins them on that equality alone, but an outer join of a replicated table whose ON condition folds to FALSE, with a WHERE clause on that table, can take the equality away. The nightly lab job found three such shapes in milestone 7; generating around each one cost coverage and did not end. |
| SELECT | `Attempted to divide`, `division by zero` | Division or MOD by zero, at run time or in constants the planner folds. |
| SELECT (LIMIT) | `ORDER BY cannot contain constant values` | An output that is constant in a way the oracle does not see, such as a CASE whose condition is `('a') IS NULL`. |
| SELECT | `Invalid result value` | A FLOAT result is infinite or NaN. |
| SELECT | `will overflow BigInt storage`, `causing overflow/underflow`, `out of range for the destination type` | Integer, DECIMAL or cast overflow. |
| SELECT | `Numeric conversion error to type DECIMAL` | DECIMAL overflow in an expression of constants, which the planner folds. The message quotes a truncated value. |
| SELECT | `has more than 38 digits of precision` | The same, when the folded value has more digits than DECIMAL holds. |
| SELECT | `Character I is neither a decimal digit number` | FLOAT overflow to infinity in an expression of constants, which the parser folds (ENG-30522: an internal error instead of "Invalid result value"). |

The two NoREC queries can evaluate an expression on different rows (the optimized query may skip rows through an index
or a join), so only one of them may hit a run-time error. The oracle treats an expected error in either query as no
result.

VoltDB also drops a CREATE INDEX that duplicates an existing index, logging "Dropping index ... because it duplicates
index ..." at WARN. The statement succeeds, so the provider ignores it.

### VoltDB issues the provider works around

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30489 | Closing a JDBC connection twice closes the client every other connection to the same URL shares, failing their in-flight calls. SQLancer closes each connection twice. | `VoltDBConnection.close()` is idempotent. |
| ENG-4643 | JDBC `getString` on a NULL returns the type's NULL sentinel (INTEGER, TINYINT, FLOAT) or throws (DECIMAL, BOOLEAN, DATE, TIMESTAMP). | The connection wraps every result set so that `getString` checks `wasNull()` and returns null for a NULL. |
| ENG-12656 | An index scan for `<`, `<=`, `>`, `>=` or BETWEEN on a VARBINARY(n) column, and for `=` when the column leads a multi-column unique index, fails ("Type VARCHAR can't be cast as VARBINARY") when the value, a constant or another table's column, is longer than n bytes. Found again by NoREC. | While `--avoid-known-bugs` is true, VARBINARY values are never compared with `<`, `>`, `<=`, `>=` or BETWEEN, a VARBINARY comparison has a constant on one side, VARBINARY constants are at most one byte, and VARBINARY partitioning columns are joined only when their sizes match. |
| ENG-22880 | A BIGINT literal at the type's minimum is stored as NULL instead of rejected. A FLOAT at -1.7976931348623157E308 and an INTEGER expression equal to INTEGER's minimum do the same. | Never generated. |

The bugs below were found by NoREC in milestone 2. While `--avoid-known-bugs` is true (the default) the generator
avoids each one as described; set it to false to check whether a fix works.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30493 | BOOLEAN values order TRUE < FALSE where the EE compares them: `<`, `>`, BETWEEN and ORDER BY give wrong rows. | BOOLEAN values are only compared with `=` and `<>`. |
| ENG-30494 | BOOLEAN literals are passed to the EE as parameters of the wrong value or type: cast errors and wrong rows. | Boolean constants are written `(1 = 1)` and `(1 = 0)`. |
| ENG-30495 | A WHERE clause that folds to a constant NULL crashes the planner (NPE). | No NULL constants; NULLs come from the data. |
| ENG-30496 | LIKE inside CASE WHEN crashes the planner when its left operand is a constant or its pattern has no wildcard or is just `%`. | LIKE's left operand is a string column; the pattern has a wildcard and a literal character. |
| ENG-30497 | With an outer join, a boolean expression over the inner table, such as `(t.c IS NULL) IS NOT NULL` or `(t.c IS NULL) = TRUE`, turns the join into an inner join: wrong rows. In milestone 7 the nightly lab job found it again through a later join's ON condition: `(t0.b OR t2.b) IS NOT NULL` with `t2` null-extended by an earlier join. | With an outer join, boolean operands of comparisons, IN and IS NULL are columns or constants, in WHERE clauses and in every explicit join's ON condition. |
| ENG-30499 | Comparing a DATE constant with a TIMESTAMP constant crashes the planner (ClassCastException). | Temporal constants are `CAST('...' AS TIMESTAMP)`. |
| ENG-30500 | A boolean expression `IN (NULL, ...)` gives wrong rows. | IN list elements are constants that are not NULL. |
| ENG-30502 | Unary minus on a NULL integer or FLOAT gives a non-NULL value. | `0 - x` instead of `-x`. |
| ENG-30503 | IN lists ignore NULL elements: `x NOT IN (NULL, ...)` is TRUE instead of NULL. | As ENG-30500. |
| ENG-30504 | `NOT (b OR c)` over BOOLEAN values fails in the planner (cast error) when it is an operand: `NOT (NOT (b OR c))`, `(NOT (b OR c)) IS NOT NULL`. The OR need not be over BOOLEAN columns. | NOT is never applied directly to OR, `NOT (NOT q)` is written `q`, and the operand of IS [NOT] NULL never starts with NOT (`(NOT q) IS NULL` is `q IS NULL`): the OR can also hide behind a constant the join planner folds away, as in `NOT (NOT ((1 = 1) AND (x OR y)))`. |
| ENG-30505 | `x IN ('...')` on a unique VARCHAR(n) or VARBINARY(n) column fails when a value is longer than n. | String and binary constants are at most one character or byte, as for ENG-30509 and ENG-12656. |
| ENG-30506 | In a parenthesized AND/OR list, an operand after an IN predicate that starts with a function call or a negative literal crashes the parser (NPE). | Function calls are always printed in parentheses, whatever `--avoid-known-bugs` is. |
| ENG-30507 | With an outer join, a WHERE IN predicate on an indexed column is applied as part of the join instead of after it, so it does not remove the rows it should: wrong rows. | No IN predicates when the query has an outer join. |
| ENG-30508 | A join filter of constants only, which VoltDB rejects ("Join with filters that do not depend on joined tables"), fails with an internal NumberFormatException instead when it is an IN with a DECIMAL constant on the left. | The internal error is an expected error, like the rejection it replaces. |
| ENG-30509 | A range scan on a multi-column index compares a VARCHAR(n) column with only the first n characters of a longer value, a constant or another table's column: `c >= '-2'` matches `'-'`, and so does `c LIKE '-2%'`. Wrong rows. | Strings are never compared with `<`, `>`, `<=`, `>=` or BETWEEN; string constants and a LIKE pattern's literal prefix are at most one character (the smallest VARCHAR). |
| ENG-30510 | After turning a RIGHT JOIN into a LEFT JOIN, VoltDB can use an ON condition on the preserved table as that table's index range, dropping rows that should be null-extended. Wrong rows. | No RIGHT JOIN (a LEFT JOIN with the tables swapped is the same). |
| ENG-30511 | An index nested-loop join's range scan on a narrower integer column, with a key from a wider column that is below the narrower type's range, matches every row: `b > s` is true for BIGINT `b = -40000` and SMALLINT `s = 5`. Wrong rows. | Ordered comparisons of numbers have a constant on one side, and BETWEEN on numbers has constant bounds. |
| ENG-30512 | COUNT(*) over an empty index range, such as `x BETWEEN 9 AND 1`, is negative (INDEX COUNT). | BETWEEN bounds are constants in ascending order. |
| ENG-30513 | Comparing a FLOAT with a DECIMAL of magnitude 2^63 or more gives wrong results; the DECIMAL appears to wrap through a 64-bit integer. Such DECIMALs come from the data and from arithmetic. | A numeric comparison, BETWEEN or IN uses only FLOAT operands or only exact ones (integers and DECIMAL). |
| ENG-30514 | A primary key column that is not declared NOT NULL accepts NULL, but the planner assumes it is never NULL: `LEFT JOIN t ON ... WHERE t.pk IS NULL` drops the ON condition. Wrong rows. | Primary key columns are NOT NULL. |
| ENG-30515 | INDEX COUNT clamps a bound above the column type's range to the type's maximum and misses rows holding it: TINYINT `c < 32767` does not count 127. | Constants in ordered numeric comparisons and BETWEEN lie between -126 and 126. |
| ENG-30516 | `WHERE c IS NULL AND c = 5` on an indexed column drops the IS NULL when `c = 5` becomes the index lookup. Wrong rows. | IS NULL is written `NOT (x IS NOT NULL)`, in the predicate and in TLP's third partition. |

The bugs below were found by TLP WHERE in milestone 3.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30521 | In a join whose statement keeps its literals inline (a constant conjunct such as `AND (1 = 1)` stops parameterization), a DECIMAL expression of 2^63 or more compares wrongly with a DECIMAL literal: `(d + 0) <= 22.656` matches `d = 1E19`. Wrong rows. | Comparisons, BETWEEN and IN over exact types have integer literals within BIGINT's range: no DECIMAL constants, and range bounds without a fraction. |
| ENG-30522 | A constant FLOAT expression that overflows to infinity fails in the parser with an internal NumberFormatException. | An expected error. |

ENG-30504 was also found again in new forms; see its row above.

The bugs below were found while building the TLP DISTINCT, HAVING and AGGREGATE oracles in milestone 5.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30529 | A multi-partition GROUP BY or DISTINCT groups by only one of two columns with the same name, such as `t0.c0` and `t1.c0`, merging groups. Found while checking the SQL these oracles generate; TLP HAVING found the form where the grouped columns are not selected. Wrong rows. | Grouping and DISTINCT columns have distinct names, and every output column of such a query has its own alias (`AS a0`, `AS a1`, ...). |
| ENG-30530 | GROUP BY or DISTINCT on the partitioning column of a table an outer join null-extends returns one NULL group per partition, because the planner leaves out the coordinator's aggregation. Found by TLP DISTINCT. Wrong rows. | With an outer join, partitioning columns are not grouped by. |
| ENG-30531 | GROUP BY or DISTINCT on an indexed column, inlined into a FULL JOIN as a serial aggregation, returns the NULL group twice: the null-extended rows come after the index-ordered ones. Related to the closed ENG-13603. Found by TLP DISTINCT in the 30-minute acceptance run. Wrong rows. | TLP DISTINCT and TLP HAVING make no FULL JOINs. Where partitioned tables are joined, they also keep replicated tables in the FROM list rather than joining them, since a LEFT JOIN there can be "not plannable" (see the join rule above). |
| ENG-30533 | MIN or MAX of a column without GROUP BY, with a WHERE clause on that column as the leading column of a multi-column index, skips rows whose next index column is NULL: with an index on `(a, b)` and the row `(5, NULL)`, `MIN(a) WHERE a = 5` is NULL. Found by TLP AGGREGATE in the 30-minute acceptance run. Wrong result. | TLP AGGREGATE writes a numeric column `c` as `(c + 0)` and a string column as `(c \|\| '')`, which the shortcut does not apply to, and leaves out MIN and MAX of VARBINARY and temporal values. |
| ENG-30534 | Aggregates of `x''` and of `''` in a GROUP BY join whose ON condition holds `x''` fail with "Type VARCHAR can't be cast as VARBINARY" (or the reverse), as if the two were one expression. Found by TLP HAVING in the 30-minute acceptance run. | VARBINARY constants inside an aggregate are never empty. |
| ENG-30504 | In a HAVING clause, `NOT (a OR b)` fails with the BOOLEAN cast error even as the whole clause (commented on the ticket). Found by TLP HAVING. | TLP HAVING's `NOT p` partition pushes the NOT down: `NOT (a OR b)` becomes `(NOT a) AND (NOT b)`. |

The bugs below were found while building the PQS oracle in milestone 6. Most are values VoltDB gets wrong the same way
in every query, which only an oracle that computes values outside VoltDB sees; ENG-30551 crashes a node.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30545 | Comparing a DATE with a TIMESTAMP converts the right operand to the left one's type, so with the DATE on the left the TIMESTAMP is truncated to its date: `dt = ts` is TRUE and `ts = dt` FALSE for `2020-01-02` and `2020-01-02 12:00`. Found while checking the evaluator's semantics. Wrong rows. | A temporal comparison, BETWEEN or IN uses only DATE operands or only TIMESTAMP ones; since ENG-30560, only TIMESTAMP ones. |
| ENG-30546 | A concatenation whose result is empty is NULL at run time: `v \|\| v` and `CONCAT(v, v)` with `v = ''`. The planner folds `'' \|\| ''` to `''`. Found by PQS in its first trial. Wrong result. | A concatenation's right operand is a constant of one character. |
| ENG-30547 | The planner folds `+` and `-` of integer constants in 32 bits: `2147483647 + 1` is NULL and `2135068309 + 1293734779` is -866164208, where the EE computes in BIGINT. In a WHERE clause too. A folded multiplication with an operand beyond INTEGER's range is typed DECIMAL, so `7 / (2 * 3000000000)` keeps a fraction (comment on the ticket). Found by PQS. Wrong result. | PQS does not check a value that depends on folded `+`, `-` or `*` of integer constants with an operand or result outside INTEGER's range. |
| ENG-30548 | DECIMAL constant folding differs from run-time DECIMAL arithmetic: `1 / 3.0` folds to 0.3333, and folded multiplication rounds where the EE truncates. Found while checking the evaluator's semantics. Wrong result. | None needed: no DECIMAL literals are generated while ENG-30521 is avoided. |
| ENG-30551 | **Node crash.** A FLOAT constant in the subnormal range (such as `1.0E-309`, written or folded from `0.28E0 / 1.79E308`) in a statement that is not parameterized makes the plan fail to load ("JSON parse failure"); running it again after any DDL crashes the node with SIGSEGV in `ExecutorContext::cleanupAllExecutors`. Found by PQS in the 30-minute 3-node acceptance run. Critical. | No subnormal FLOAT literals are generated, and PQS never sends an expression of constants that folds to one. |
| ENG-30552 | The string literal `'\N'` in ad hoc SQL becomes NULL, apparently because a parameter string `\N` is read as the NULL token: INSERT stores NULL, `SELECT '\N'` and the folded `'\' \|\| 'N'` return NULL. Found by PQS in the 30-minute 3-node acceptance run. Wrong result, lost data. | The constant generator never writes `'\N'`, and PQS never sends an expression of constants that is `\N`. |
| ENG-30550 | ABS of a BIGINT multiplication is typed DECIMAL, so a division inside or after it keeps a fraction: `ABS((b * 1) / 2)` is 3.5 for `b = 7`, while `(b * 1) / 2` is 3. More generally ABS pushes a DECIMAL or FLOAT argument's type into an integer division inside it: `ABS((7 / i) - d)` divides 7 by 2 as 3.5 (comment on the ticket). Found by PQS in 5- and 10-minute trials. Wrong result. | No multiplication or division inside ABS. |
| ENG-30549 | The planner folds a comparison of two string constants ignoring trailing spaces (`'a' = 'a '` is TRUE), while the EE compares exactly. Found by PQS. Wrong result. | PQS does not check a folded string comparison that trailing spaces decide. |

The bugs below were found by the nightly lab job's first runs in milestone 7, or while reducing what they found.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30560 | A DATE constant (`CAST('...' AS DATE)`, or a string compared with a DATE column) anywhere in a statement makes its DECIMAL literals FLOAT: `SELECT 1.5, CAST('2000-01-01' AS DATE)` returns 1.5 as a FLOAT. Folded DECIMAL constants (`0.1 + 0.2`, `CAST(1.5 AS DECIMAL)`, `3000000000 * 2`) then fail to compile: "Numeric conversion error to type DECIMAL for input string". Found by TLP HAVING on 3 nodes, where `SUM(ABS(605228403.497119))` was DECIMAL in the original query and FLOAT in the partitions, whose HAVING clauses held DATE casts. Wrong result. | No DATE constants: temporal comparisons, BETWEEN and IN use TIMESTAMP operands only. DATE columns still appear in SELECT lists and aggregates. |
| ENG-30561 | `CAST(<TIMESTAMP constant> AS DATE)` folds to an invalid date: in a SELECT list it fails with "Invalid value for MonthOfYear", and `WHERE d = CAST(CAST('2000-01-01 00:00:00' AS TIMESTAMP) AS DATE)` matches nothing. Found by hand while reducing ENG-30560. Wrong rows. | None needed: the generator never casts a constant twice. |
| ENG-30562 | VoltDB reads a backslash before a quote as escaping it, so after a string literal ending in a backslash (`'a\'`) it strips `/*...*/` and `--` from the statement's later string literals: `INSERT ... VALUES ('a\', 'c/*d*/e')` stores `c e`, and an unclosed `/*` or `--` gives "malformed bit string". Found by TLP HAVING on 1 node, as an unexpected error. Lost data. | No string literal has a backslash at its end or before a quote (`VoltDBConstantGenerator.quote` writes a slash instead), whatever `--avoid-known-bugs` says, as for ENG-30552. |

The bugs below were found by the PARAMS oracle in milestone 8, in its first two 3-minute trials.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30571 | The JDBC driver counts every `?` in a prepared statement's text as a parameter, including one inside a string literal (`(query + ";").split("\\?")` in `JDBC4Statement`), so any prepared statement with a literal such as `'?'` or `'what?'` fails: "Incorrect number of parameters passed". Found by PARAMS: all four failed databases of its first trial. | A string holding `?` is always a parameter. |
| ENG-30560 (comment) | The milestone 7 bug, wider than filed: any statement VoltDB does not auto-parameterize reads a DECIMAL literal as FLOAT and fails to plan `CAST(1.5 AS DECIMAL)` or `0.1 + 0.2`. That is every prepared statement with parameters and every stored procedure, with or without parameters; a DATE constant was only one way to stop auto-parameterization. Found by PARAMS: all seven failed databases of its two 10-minute trials. | DECIMAL constants are always parameters. |
| ENG-30534 (comment) | The milestone 5 bug, wider than filed: in a GROUP BY statement that is not auto-parameterized, an aggregate of a VARCHAR literal and one of a VARBINARY literal with the same text are one expression. `MAX('00')` returns the byte 0x00 beside `MAX(x'00')`, and in the other order the query fails with "VARCHAR can't be cast as VARBINARY". No join or empty string is needed. Found by PARAMS on 1 node and on 3 nodes. Wrong result. | VARBINARY constants are always parameters. |
| ENG-30551 (seen again) | The milestone 6 node crash: an expression of FLOAT constants that folds to a subnormal value, such as `0.63E0 / 0.70E0 / (0 - 1.8E308)`, makes the plan fail to load ("JSON parse failure"). Every PARAMS query is a statement VoltDB does not auto-parameterize, which is where this bites. Found by PARAMS on 1 node and on 3 nodes. | PARAMS skips a query with an expression of constants the PQS evaluator cannot predict. |
| ENG-30572 | In HAVING, two aggregates that differ only in their parameters take the first one's value: `HAVING MAX(k + ?) < MAX(k + ?)` with 1 and 2 returns no groups. In the SELECT list, and with literals, they are kept apart. Found by PARAMS: all four failed databases of its second trial. Wrong rows. | Constants in HAVING's aggregates stay literals. |

The bugs below were found by the UPDATE generator in milestone 9, in its first trials.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30494 (comment) | The milestone 2 bug, in UPDATE: a BOOLEAN literal in SET takes another assignment's or the WHERE clause's parameter. `UPDATE t SET b1 = FALSE, b2 = TRUE WHERE k = 1` fails with "BOOLEAN can't be cast as BIGINT", and a second UPDATE of the same shape fails once the first's plan is cached. | A BOOLEAN column is set to `(1 = 1)` or `(1 = 0)`. |
| ENG-30561 (comment) | The milestone 7 bug, in assignment: a TIMESTAMP constant set into a DATE column, by UPDATE or INSERT, fails to plan with "has an invalid format for a constant date value", quoting the timestamp's microseconds. A TIMESTAMP column converts correctly. | A DATE column is set to a date string. |

The bugs below were found by the VIEWS oracle in milestone 10, in its first trials.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30582 | Once a table has a materialized view, VoltDB's view rewriter fails on GROUP BY queries whose WHERE clause and GROUP BY columns match the view's: the same aggregate twice gives "Duplicate key" (a `Collectors.toMap` without a merge function in `MVQueryRewriter.getViewColumnMaps`), and an aggregate of an expression, such as `SUM(x + 0)`, gives a ClassCastException. These queries worked before the view existed, and the other oracles generate them. | A view never has the same aggregate twice, and the other oracles' queries never read a table that has a view. |
| ENG-30583 | A view whose WHERE clause has a FLOAT constant of 10 or more with a fraction, such as `c0 > 12.5E0`, blocks every later DDL in the database: "May not dynamically modify field 'predicate' of schema object 'MaterializedViewInfo{...}'". Dropping the view recovers. Likewise a predicate in a view's WHERE clause that an index could serve, such as a LIKE or a TIMESTAMP BETWEEN on a column, makes an index on that column fail (comments on the ticket). Found as failed CREATE INDEX statements. | Views have no WHERE clause. |
| ENG-30584 | Deleting a group's last non-NULL value leaves 0 instead of NULL in a view's SUM. A query the rewriter answers from the view returns the 0 too. Wrong result. | A view sums NOT NULL columns only. |
| ENG-30588 | An UPDATE of an indexed column fails when it overflows a view's SUM (DECIMAL or BIGINT), as it should, but the row is left out of the index on that column: lookups by the key miss it, and the next UPDATE of the row crashes the node with "Failed to remove tuple from index (during update)". A failed DELETE, or a failed UPDATE of other columns, leaves the index intact. Found by the lab's 45-minute run on 1 node, as a crash. | A view sums TINYINT, SMALLINT and INTEGER columns only. |

The bugs below were found in milestone 11, as the generator learned functions and CASE.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30550 (comment) | The milestone 6 bug, wider than ABS: a BIGINT multiplication inside CASE, COALESCE, FLOOR, CEILING or MOD is typed DECIMAL, so a division after the function keeps a fraction (`COALESCE(b * 1, 0) / 2` is 1.5 for b = 3) and MOD rejects it ("incompatible data type in operation"). Wrong result. | No multiplication or division inside those functions. |
| ENG-30589 | Setting a VARCHAR or VARBINARY column to a CASE or COALESCE, by UPDATE, INSERT or UPSERT, fails to compile: "Asking for fixed size for non-fixed or unknown type:varchar". Inside another function, such as `UPPER(CASE ...)`, it works. | An UPDATE never sets a VARCHAR column to a CASE or COALESCE. |
| ENG-30590 | A CASE with a DECIMAL literal result keeps the generic NUMERIC type. With two or more WHENs it fails to compile: "Unable to determine cast type for 'VoltType.NUMERIC' and 'VoltType.NUMERIC' types", or a "not boolean" error when a condition is a BOOLEAN column. With one WHEN and no ELSE it fails in the EE: "unsupported type 'NUMERIC'" (comment on the ticket). One WHEN with an ELSE works. | A CASE has one WHEN and an ELSE. |
| ENG-30497 (comment) | The milestone 2 bug, through CASE: a CASE over an outer join's inner table in WHERE turns the join into an inner join, though the CASE can be non-NULL for the null-extended row. `WHERE (CASE WHEN jb.n > 0 THEN 'a' ELSE 'b' END) = 'b'` drops the row a LEFT JOIN null-extends. COALESCE is handled correctly. Found by TLP WHERE. Wrong result. | With an outer join, the generator writes COALESCE instead of CASE. |
| ENG-30591 | SUBSTRING of a CASE whose condition has an IN list crashes the SQL parser (NullPointerException in `TableUtil.addAutoColumns`). Other functions of the same CASE work. Found by PARAMS. | SUBSTRING's string argument has no IN predicate. |
| ENG-30592 | A parameter inside `TRIM(x)` is counted twice: "Incorrect number of parameters passed: expected 2, passed 1". `TRIM(BOTH ' ' FROM x)`, LTRIM and RTRIM take it once. Found by PARAMS: all eight failed databases of its first 10-minute trials. | `TRIM(x)` is written `LTRIM(RTRIM(x))`. |
| ENG-30531 (comment) | The milestone 5 bug, in ORDER BY: VoltDB trusts an index's order through a FULL JOIN, whose null-extended rows come last, so `ORDER BY` of partitioned tables comes out unsorted (each partition skips its sort, and the coordinator merges the partitions as if they were sorted). LIMIT and OFFSET then pick the wrong rows. Found by LIMIT in milestone 12's trials. Wrong result. | LIMIT's queries make no FULL JOINs. |
| ENG-30494 (comment) | The milestone 2 bug, in CASE: `CASE WHEN k = 1 THEN 1 WHEN bo = TRUE THEN 2 END` is 0 for k = 1. Wrong result. | The generator writes no BOOLEAN literals (already). |

The published `voltdbclient` jar does not register its JDBC driver with `java.sql.Driver`, so the provider loads
`org.voltdb.jdbc.Driver` itself.

## Tests

`test/sqlancer/voltdb/oracle/TestVoltDBExpressionEvaluator.java` checks the PQS evaluator against VoltDB's semantics
as observed, without a server:

```bash
mvn -Djacoco.skip=true -Dtest=TestVoltDBExpressionEvaluator test
```

`test/sqlancer/voltdb/oracle/TestVoltDBParameterOracle.java` checks how PARAMS types its parameters:

```bash
mvn -Djacoco.skip=true -Dtest=TestVoltDBParameterOracle test
```

`test/sqlancer/voltdb/gen/TestVoltDBConstantGenerator.java` checks how string literals are written (ENG-30562):

```bash
mvn -Djacoco.skip=true -Dtest=TestVoltDBConstantGenerator test
```

`test/sqlancer/dbms/TestVoltDB.java` runs the provider for `TestConfig.SECONDS` against a server on localhost:21212. It
is skipped unless `VOLTDB_AVAILABLE=true`:

```bash
VOLTDB_AVAILABLE=true mvn -Djacoco.skip=true -Dtest=TestVoltDB test
```

`mvn verify -DskipTests` runs the formatter, checkstyle, PMD and SpotBugs checks that every provider passes.
