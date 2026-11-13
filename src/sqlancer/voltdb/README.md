# VoltDB provider

A SQLancer provider for [VoltDB](https://www.voltactivedata.com/), developed for ENG-30396. It generates random
VoltDB schemas, both replicated and partitioned tables, fills them with single-row inserts, upserts, updates and deletes, and runs SQLancer's oracles
against them through the VoltDB JDBC driver.

**Status: milestone 41.** The provider creates schemas and data and runs these logic-bug oracles: NoREC (the default,
milestone 2), TLP WHERE (milestone 3), TLP DISTINCT, TLP HAVING and TLP AGGREGATE (milestone 5), PQS (milestone 6), and
PARAMS, literals against JDBC parameters (milestone 8). Since milestone 9 each database's rows are also updated,
upserted and deleted before the oracles run. Since milestone 10 each database also has materialized views, which the
VIEWS oracle checks. Since milestone 11 expressions also use CASE, COALESCE, MOD, FLOOR, CEILING, CHAR_LENGTH,
SUBSTRING, UPPER, LOWER and TRIM, and the LIMIT oracle checks LIMIT and OFFSET. Since milestone 12 the run script
can kill and rejoin nodes while SQLancer runs (`--kill-interval`), which the nightly job's 3-node run does. Since
milestone 13 the nightly job also runs a third cluster shape: 3 nodes with k=2 and 2 sites per host. Since
milestone 14 expressions also hold subqueries (EXISTS, IN, ANY, ALL and scalar subqueries), the SUBQUERY oracle checks
IN, ANY and ALL against EXISTS, and the WINDOW oracle checks window functions; see
[Subqueries and window functions](#subqueries-and-window-functions). Since milestone 15 node failures include node 0:
SQLancer connects to every node. Since milestone 16 the SETOPS oracle checks UNION, INTERSECT and EXCEPT, and the
DERIVED oracle checks derived tables and WITH, recursive WITH included; see
[Set operations, derived tables and WITH](#set-operations-derived-tables-and-with). Since milestone 17 the PROCEDURE
oracle checks ad hoc queries against the same queries as stored procedures, single-partition ones included; see
[The PROCEDURE oracle](#the-procedure-oracle). Since milestone 18 the SCHEMA oracle changes a table's schema in the
middle of the run and checks that queries return the same rows from new plans, cached plans and plans made after the
change; see [The SCHEMA oracle](#the-schema-oracle). Since milestone 19 expressions also use FLOAT math, date and time
functions and JSON functions, and the GEO oracle checks GEOGRAPHY_POINT and GEOGRAPHY; see
[Date, JSON, FLOAT math and geography](#date-json-float-math-and-geography). Since milestone 20 the DIFF oracle
compares this VoltDB with another version, normally the last release, on the same data; see
[The DIFF oracle](#the-diff-oracle). Since milestone 21 the run script can also grow and shrink the cluster
elastically while SQLancer runs (`--resize-interval`); see [Elastic resizes](#elastic-resizes). Since milestone 22 it
can also crash the whole cluster and recover it from its command log (`--crash-interval`), and the DURABLE oracle
checks that no acknowledged change was lost; see [Whole-cluster crashes](#whole-cluster-crashes). Since milestone 23
it can run two clusters linked by XDCR (`--xdcr`), and the XDCR oracle checks that they converge; see [XDCR](#xdcr).
Since milestone 24 it can start the cluster on the last release and upgrade it in service, one node at a time, while
SQLancer runs (`--upgrade-from`); see [In-service upgrades](#in-service-upgrades). Since milestone 25,
`voltdb/run-repros.sh` reruns the minimal reproduction of every bug the provider found, and the nightly job reports
which are fixed; see [Bug reproductions](#bug-reproductions). Since milestone 26 the PLANS oracle checks that the
same query planned two ways returns the same rows; see [The PLANS oracle](#the-plans-oracle). Since milestone 28 the
run script can enable security (`--security`), and the PERMS oracle checks what users may and may not do; see
[The PERMS oracle](#the-perms-oracle). Since milestone 29 the STREAMS oracle checks export streams and their views, and
`--export` checks that every row a stream got was exported; see [The STREAMS oracle](#the-streams-oracle). Since
milestone 30 `voltdb/run-coverage.sh` measures the Java and C++ code the runs exercise; see
[Code coverage](#code-coverage). Since milestone 31 expressions also use the string, bitwise, number, time and mask
functions that coverage found unexercised; see [Functions in expressions](#functions-in-expressions). Since milestone 32
some materialized views read an inner join of two or three tables; see [The VIEWS oracle](#the-views-oracle). Since
milestone 33 `--export` also exports through HTTP and loopback targets; see [The STREAMS oracle](#the-streams-oracle).
Since milestone 34 the TTL oracle checks rows VoltDB removes by itself, time to live and migration; see
[The TTL oracle](#the-ttl-oracle). Since milestone 35 the LOAD and SWAP oracles check bulk loads and
`@SwapTables`, and `@ValidatePartitioning` after each; see [The LOAD and SWAP oracles](#the-load-and-swap-oracles).
Since milestone 36 the CONFIG oracle changes the configuration while the database runs; see
[The CONFIG oracle](#the-config-oracle). Since milestone 37 the SNAPSHOT oracle saves and restores a table by
snapshot; see [The SNAPSHOT oracle](#the-snapshot-oracle). Since milestone 38 the XCONFLICT oracle makes XDCR
conflicts on purpose and checks that every divergence they leave is reported; see
[The XCONFLICT oracle](#the-xconflict-oracle). Since milestone 39 the TASK oracle runs scheduled tasks and checks that
they run as scheduled and stop when disabled; see [The TASK oracle](#the-task-oracle). Since milestone 40 `--export`
also exports through a JDBC target into a sink cluster; see [The JDBC target](#the-jdbc-target-milestone-40). Since
milestone 41 the IMPORT oracle sends rows through the pull socket importer; see [The IMPORT oracle](#the-import-oracle).
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
| `--kill-interval SECS` | 0 (never) | Every SECS seconds, kill a random node, node 0 included, with `kill -9`, restart it and wait until it has rejoined (see [Node failures](#node-failures)). Needs k-safety 1 or more. |
| `--resize-interval SECS` | 0 (never) | Every SECS seconds, add k+1 nodes with `voltdb start --add`, or remove them again with `voltadmin resize`, by turns (see [Elastic resizes](#elastic-resizes)). Does not mix with `--kill-interval`. |
| `--crash-interval SECS` | 0 (never) | Every SECS seconds, kill every node at once with `kill -9` and restart them all, which recovers the database from its snapshots and synchronous command log (see [Whole-cluster crashes](#whole-cluster-crashes)). Does not mix with `--kill-interval` or `--resize-interval`. |
| `--xdcr` | off | Also start a second cluster of the same shape, linked to the first by XDCR (see [XDCR](#xdcr)). Does not mix with `--kill-interval`, `--resize-interval` or `--crash-interval`. |
| `--upgrade-from DIR` | none | Start the cluster on this older kit, normally the last release, and upgrade it in service to `--voltdb`, one node at a time (see [In-service upgrades](#in-service-upgrades)). Needs k-safety 1 or more. Does not mix with `--kill-interval`, `--resize-interval`, `--crash-interval` or `--xdcr`. |
| `--upgrade-java-home DIR` | the environment's | JAVA_HOME for the older kit's nodes (16.0.0 needs Java 17 or 21). |
| `--upgrade-at SECS` | a third of `--duration` | When the upgrade starts. |
| `--security` | off | Enable security: SQLancer connects as the administrator `sqlancer`, and the PERMS oracle runs statements as the users `reader` (role `sqlancer_read`, SQLREAD) and `writer` (role `sqlancer_write`, SQL); see [The PERMS oracle](#the-perms-oracle). Does not mix with the node failure, resize, crash, XDCR and upgrade options. |
| `--export` | off | Add a file export target, `sqlancer` (CSV, into the cluster directory), for the STREAMS oracle's streams, and check at the end that every row they got was exported (see [The STREAMS oracle](#the-streams-oracle)). |
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
| `servers/node<i>/` | Each node's `volt.log` and its `voltdb init` and `voltdb start` output. A log over 256 MB keeps its first and last 64 MB and the FATAL lines between, and fails the run: a server logging without end would fill the agent's disk (ENG-30629). |
| `sqlancer/` | SQLancer's output (`out.log`) and its `logs/voltdb/` directory (see [Reading the results](#reading-the-results)). If SQLancer had not exited 15 minutes after its duration, `jstack.txt` is its thread dump from just before the script killed it. |
| `chaos.log` | With `--kill-interval`: each kill and rejoin, with its time. |
| `crash.log` | With `--crash-interval`: each crash and recovery, with its time and how long the recovery took. `servers/node<i>/recover<i>-<n>.log` is node i's output from recovery n. |
| `servers/xdcr<i>/` | With `--xdcr`: the logs of the second cluster's node i, like `servers/node<i>/`. |
| `xdcr_conflicts/` | With `--xdcr`: every conflict XDCR logged (there should be none). |
| `upgrade.log` | With `--upgrade-from`: each node's stop and rejoin on the new kit, with its time. `servers/node<i>/upgrade<i>.log` is node i's output on the new kit. |
| `resize.log` | With `--resize-interval`: each grow and shrink, with its time, the planned removal and how long it took. |
| `servers/add<n>-<j>/` | With `--resize-interval`: the logs of node j added by grow n, like `servers/node<i>/`. A join VoltDB refused is kept as `add<n>-<j>-rejected<a>`. |
| `reproductions/` | The log of each database whose thread failed: the statements that set it up, then the failing queries. With `--use-reducer`, these are reduced, and `<db>-reduce.log` shows the steps. |

A run FAILs if any of these happens:
- SQLancer exits non-zero, or any thread fails.
- SQLancer has not exited 15 minutes after its duration (`SQLANCER_HANG_GRACE` seconds): a thread stuck in a call keeps it from stopping, and the run would wait for good. The script saves its thread dump and kills it.
- A node is down at the end.
- A server log has a FATAL line.
- With `--kill-interval`, a killed node does not rejoin within 600 seconds.
- With `--resize-interval`, a grow or a shrink does not complete within 600 seconds.
- With `--crash-interval`, the cluster does not recover within 600 seconds of a crash.
- With `--xdcr`, either cluster has a node down at the end, or XDCR logged a conflict.
- With `--upgrade-from`, a node cannot be stopped or does not rejoin on the new kit within 600 seconds, or the upgrade
  has not finished when SQLancer stops.

While `--avoid-known-bugs` is true (the default), a failed check whose queries no longer fail when its reproducer
reruns them at once is not a failure. It is printed as `ENG-30602 transient: ...` in `sqlancer/out.log`, and the
summary counts it as "transient results skipped (ENG-30602)". ENG-30602 is a VoltDB bug in which a query, now and
then, gets another statement's result while other connections change the schema. A failure that recurs is a failure,
and so is one from an oracle without a reproducer (SCHEMA, GEO).

The script passes SQLancer `--num-statement-kind-retries 20`: a failed INSERT is retried with new values, up to
SQLancer's default of 1,000 times, so once a table's key had run out of values (a BOOLEAN or TINYINT primary key, say)
each of a database's inserts spent 1,000 constraint violations, and SQLancer's "successful statements" fell below 70%.

The cluster's data is deleted after the logs are copied, unless `--keep-cluster` is given. Several runs can share a
machine if each has its own `--port-base`.

## Node failures

With `--kill-interval SECS`, the script kills a node every SECS seconds while SQLancer runs, and the oracles keep
checking query results through the failures:

1. It picks a node at random, node 0 included. With more than one node, the script passes the provider
   `--servers` with every node's client port, and the JDBC URL lists them all with `topologychangeaware=true`: the
   driver connects to each node and sends every call through a live connection, so SQLancer keeps working while any one
   node is down, and reconnects to a node once it has rejoined. Every node also starts with the whole host list
   (`voltdb start -H`), so a restarted node 0 rejoins through another node. Until milestone 15, SQLancer connected to
   node 0 alone, and node 0 was never killed.
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

From 3 seconds before each kill until the node logs "Node rejoin completed", the script holds DDL (the provider's
`--ddl-hold`, as around a shrink): a node killed while a catalog update is in flight can wedge the MPI (ENG-30612) or
crash a surviving node (ENG-30663), and in lab build #49, DDL while a node rejoined was followed by both survivors
crashing in the engine (SIGSEGV in `PersistentTable::index`), noted on ENG-30663. With `-- --avoid-known-bugs false`
the provider ignores the hold.

With `--kill-interval`, the script passes `--node-failures true` to the provider, which makes `VoltDBConnection`
handle the errors a node failure causes:

| Error | What it means | What the provider does |
| --- | --- | --- |
| `Transaction dropped due to change in mastership. It is possible the transaction was committed` | The statement was in flight when a partition's leader moved; it may have committed. | Skips the statement, check or database (IgnoreMeException), since it cannot know. |
| `Transaction being restarted due to fault recovery or shutdown`, `Transaction Interrupted`, `transaction completion is in progress` | The same for a transaction the failure aborted or caught completing. | The same. |
| `OPS request hit sixty second timeout before all responses were received` | A system request, such as the JDBC metadata calls that read the schema, waited on the dead node. | The same. |
| `The errors for @VerifyCatalogAndWriteJar from host N: Host N failed, connection lost` | A DDL statement's catalog update was waiting on the dead node. | The same. |
| `object name already exists` | A DDL statement that a failure interrupted committed after all, so a later CREATE of the same name collides with a table or index the provider never saw. | Skips, as above. Without `--node-failures` this fails the run, as it would be a provider bug. |
| `Catalog has been updated. The planner is outdated.`, `... was no longer live`, `Ad Hoc Planner task queue is full` | The statement was planned while a catalog update, frequent around a rejoin, replaced the planner, or the planner was busy. VoltDB rejects it before running it. | Retries it, with or without `--node-failures`, like a concurrent schema change. |
| `@UpdateCore in progress` | Another connection's catalog update is still running. Its blocker can be gone when the node that held it failed, so a DDL statement gets as far as staging and is refused there, before it changes anything. Found by the lab once node 0 was killed too (milestone 15). | Retries the statement, like a concurrent schema change. |
| `Connection to database host (localhost) was lost before a response was received` | The statement was in flight on the connection to the node that was killed; it may have committed. The driver sends the next calls through the other connections. | Skips, as for a change in mastership. |
| `Can't do catalog update(@AdHoc) while ...`: a node rejoin, leader promotion or transaction repair, or a catalog update transaction, is in progress | VoltDB's catalog update blocker (`VoltZK`) refuses DDL until the failure is handled and the rejoin is done. | Retries the statement, like a concurrent schema change, for up to `--retry-seconds`. VoltDB rejects it before running it. |

Without `--node-failures`, these errors fail the run as before. The OPS timeout makes each failure cost up to a minute
of the threads that read the schema at the time, so a run with failures checks fewer queries.

## Elastic resizes

With `--resize-interval SECS`, the script grows the cluster by k+1 nodes and shrinks it back by turns, a step every
SECS seconds, while SQLancer runs and the oracles keep checking query results:

1. **Grow.** It starts k+1 new nodes with `voltdb start --add`, each with the whole host list, on the ports after the
   original nodes' (slots `--nodes` to `--nodes`+k). VoltDB refuses a join while a catalog update is in progress
   ("Can't do elastic join while catalog update is in progress"), which SQLancer's DDL makes common, and the joiner
   then exits: the script kills the attempt's other nodes, keeps their logs as `add<n>-<j>-rejected<a>`, and tries
   again, up to 10 times. A grow is done once every new node has started ("Server completed initialization") and the
   cluster's topology has settled, about 40 seconds after the join: `voltadmin resize --test` then plans to remove
   only the new nodes.
2. **Shrink.** `voltadmin resize` removes the partition group added last, which is the new nodes (the planner
   removes the highest partitions). A shrink is done once their servers have exited.

Host ids keep growing across grows, and VoltDB refuses to remove more than half the cluster, which is why the script
adds k+1 nodes: one partition group. When SQLancer stops the script lets the current step finish, and the summary
counts grows and shrinks. A step that does not finish within 600 seconds fails the run, and FATAL lines in an added
node's log fail it too (not in a rejected join's, where the FATAL is the refusal).

With `--resize-interval`, the script passes the provider `--resizes true`: a shrink drops the calls in flight on the
hosts it removes, which the driver is connected to (`--servers`, `topologychangeaware`), so the provider expects the
same errors as for a killed node (see [Node failures](#node-failures)); the oracles' workarounds for ENG-30612, which
is about killed nodes, do not apply. It also passes `--ddl-hold <run>/ddl.hold`: the script creates that file 3
seconds before each shrink and deletes it once the shrink is done, and while it exists the connection holds every
DDL statement (CREATE, DROP, ALTER, PARTITION), for up to 15 minutes. Without it, a table that SQLancer drops while
the shrink streams its index snapshot hangs the shrink for good (ENG-30622). With `-- --avoid-known-bugs false` the
provider ignores the file.

With `--resizes`, the provider also makes no ASSUMEUNIQUE constraint or index (a unique key on a partitioned table
that leaves out the partitioning column). VoltDB checks ASSUMEUNIQUE within a partition only, so two partitions can
hold rows with the same key, and a resize that moves one of them next to the other fails ("Found duplicates while
rebalancing data across partitions") and retries for good: lab build #40 hung a shrink this way for 10 minutes, until
SQLancer dropped the table. VoltDB documents this; it is not a bug.

An elastic join, like a rejoin, makes system requests time out for a minute (ENG-30229): `@SystemInformation` and
`@SystemCatalog`, which the JDBC driver's `getMetaData()` and `DatabaseMetaData` calls send, fail with "OPS request hit
sixty second timeout" or "Too many pending stat requests". The connection retries them for up to 150 seconds, with or
without `--resizes`.

## Whole-cluster crashes

With `--crash-interval SECS`, every SECS seconds the script kills every node at once with `kill -9`, as a power loss
would, while SQLancer runs:

1. The deployment has a synchronous command log (`<commandlog enabled="true" synchronous="true"/>`): VoltDB
   acknowledges a transaction only once it is on disk, so no acknowledged change may be lost. Automatic snapshots
   every 2 minutes (`<snapshot frequency="2m" retain="2"/>`) give recovery a starting point before it replays the log.
   The mirror's deployment has neither, as the mirror never crashes.
2. Once every node's client port has closed, the script restarts them all with the same `voltdb start` command,
   which recovers: each node loads the last snapshot and replays the command log after it. A recovery is done once
   every node prints "Server completed initialization"; one that takes over 600 seconds fails the run. A restart that
   cannot bind its port is tried again, as for node failures.
3. Each crash and recovery is a line of `crash.log`. When SQLancer stops, the script lets the current recovery
   finish, so the run ends with every node up, and the summary counts crashes and recoveries. A local recovery of 3
   nodes takes about 15 seconds.

The script passes the provider `--crashes true`. A crash fails every call in flight, with the errors of a killed node
(see [Node failures](#node-failures)), and the provider handles them the same way. A database whose change was in
flight may or may not have it after the recovery, so it is marked diverged and DIFF and DURABLE skip it; a query in
flight changes nothing, so its database is not. New connections are retried for up to 11 minutes while the cluster
is down. The JDBC driver reconnects the open ones (`topologychangeaware`) once the nodes are back.

### The DURABLE oracle

With `--mirror`, every change also runs on a cluster that never crashes, so after a recovery every table must hold as
many rows here as on the mirror. DURABLE counts each of the database's tables on both clusters (`SELECT COUNT(*)`) and
fails on a difference, or on a table that is missing here and present on the mirror. The first time it compares a
database after a crash interrupted one of its calls, it prints `DURABLE across a crash: <database>` to
`sqlancer/out.log`, and the summary counts these lines as "databases DURABLE compared across a crash": the databases
shown to have kept every row. DIFF compares the rows themselves, but only on tables that are not empty, so it cannot
see a table that a recovery left empty. Without `--mirror`, DURABLE skips every check. A failure has no reproducer,
as it depends on the crash.

A control run showed DURABLE catches a loss: with the command log turned off, a crash every 90 seconds and no
snapshot yet, recovery lost the tables created since start, and DURABLE failed 3 of 4 databases within 5 minutes
("object not found: DB0_T0 here, 24 on the mirror"). An earlier control with DIFF alone passed: recovery had left
the tables empty or missing, which DIFF skips.

## XDCR

With `--xdcr`, the script starts a second cluster of the same shape (nodes, k-safety, sites per host) on the port
slots from 20, and links the two by XDCR, active-active DR: DR id 1 here and 2 there, each with the other's
replication ports as its source. The mirror, if any, gets no DR.

The script passes the provider `--xdcr` with the second cluster's client ports, and each database then works like this:

1. SQLancer connects to one of the two clusters, chosen at random per database, and writes and reads there only. The
   connection to the other cluster is its peer. Both directions of replication are exercised across a run, and no
   table is written on both sides, so XDCR has no conflicts to resolve.
2. XDCR replicates rows, not the schema, so every schema change (CREATE, DROP, ALTER, PARTITION and DR TABLE) that
   succeeds runs on the peer too. A change the peer refuses marks the database split, and XDCR skips it.
3. Before any rows go in, every table becomes a DR table (`DR TABLE t`) on both sides. Tables without a unique index
   replicate updates and deletes by matching the whole row, which VoltDB warns is slow but allows.
4. A database that reuses an earlier one's name may write through the other cluster, and drops the old tables on its
   own first. Rows the old database wrote just before, still on their way, would then reach a table that cluster no
   longer has, and pause that DR stream for good (ENG-30630). So, before those drops, the provider waits up to 60 s
   until each cluster's DR producer has had acknowledged everything it had queued (`@Statistics DRPRODUCERPARTITION`:
   LASTACKDRID reaches the LASTQUEUEDDRID of the start of the wait). LASTQUEUEDDRID counts only flushed DR buffers,
   and VoltDB flushes them every second by default, so the wait starts 1.5 s in; read at once, it missed the last
   rows and the stream paused (lab build #46). A wait that runs out says so on standard error. With
   `--avoid-known-bugs false` it does not wait.

### The XDCR oracle

XDCR reads every table and materialized view of the database on both clusters and compares their rows as multisets.
Replication is asynchronous, so a difference is retried every second for up to 60 seconds; one that persists fails
with the rows found on one side only. A view's rows on the peer come from rows that XDCR applied, so this also checks
view maintenance on that path. The database's thread is its only writer and is in the check, so nothing changes the
rows while they are read. The first comparison of each database prints `XDCR converged: <database>`, and the summary
counts these as "databases XDCR compared after convergence".

The script also collects XDCR's conflict log (`xdcr_conflicts/` in each node's root) into the results, and a conflict
fails the run, as the provider never writes a table on both sides. A failure has no reproducer, as it depends on
replication.

A control run showed XDCR catches a broken link: with the second cluster's DR source pointed at a closed port, every
database written on the first cluster failed within 4 minutes ("DB0_T0: 19 rows here, 0 on the other cluster").

## In-service upgrades

With `--upgrade-from DIR`, the script initializes and starts every node with the older kit in DIR, on
`--upgrade-java-home` if given, and SQLancer runs against it. At `--upgrade-at` seconds it upgrades the cluster in
service, from the last node to node 0:

1. It stops the node with `voltadmin stop`, naming it by the address VoltDB reports for it (`@SystemInformation`
   IPADDRESS and INTERNALPORT), which need not be localhost. VoltDB refuses to stop a node for some seconds after
   another one has rejoined ("You may shut down the node with the voltadmin stop --force command"); a refusal is retried
   every 10 seconds, up to 20 times.
2. Once the node's server has exited, it starts it with the same `voltdb start` command on `--voltdb`, which rejoins it
   to the cluster, and waits for "Server completed initialization", up to 600 seconds.

Each step is a line of `upgrade.log`, and the summary counts the nodes upgraded. Locally, 3 nodes went from 16.0.0 to
16.1.0-SNAPSHOT in under 2 minutes, 10 to 54 seconds each. The oracles check query results throughout: on the old
version, on the mixed cluster while nodes of both versions run fragments of the same queries, and on the new version.
With `--mirror-voltdb` on the same older kit, DIFF compares the upgraded cluster with the release it came from.

The script passes the provider `--upgrades true`: a stopped node drops the calls in flight on it, which the provider
expects as for a killed node (see [Node failures](#node-failures)). While `--avoid-known-bugs` is true, tables have no
DECIMAL columns, as the 16.0.0 nodes abort on grouping, DISTINCT or a set operation over a NULL DECIMAL (ENG-30255,
fixed after 16.0.0), which the first trials on 16.0.0 hit within two minutes. DDL during the upgrade can be refused with "Can
not update catalog while catalog update is in progress" before it runs; the connection retries it.

## Bug reproductions

`voltdb/repros/` holds the minimal reproduction of each VoltDB bug the provider found, one file per ticket, and
`voltdb/run-repros.sh --voltdb DIR` runs them all against a kit, each on a fresh server, and says which still
reproduce:

```
voltdb/run-repros.sh --voltdb ~/voltdb [--port-base 31000] [--results DIR] [--only ENG-30614,ENG-30611]
```

| Verdict | Meaning |
| --- | --- |
| `BROKEN` | The bug still reproduces: the check gave the bug's result. |
| `FIXED` | The check gave the right result: the workaround in the provider can go, and the ticket can be checked. |
| `CHANGED` | Neither: the result changed in another way, or the server crashed where the bug is not a crash. |
| `ERROR` | The reproduction could not run (the server did not start, a script failed). |

The run fails on CHANGED or ERROR; FIXED does not fail it. `<results>/repros-<time>/summary.txt` has the counts and a
line per ticket, and `<ticket>.log` the setup's output and the check's.

A `.sql` reproduction has header lines, setup statements, a line `-- check`, and the statements whose output is
compared:

```
-- ticket: ENG-30614
-- summary: An outer join whose ON condition folds to FALSE returns no rows, not the preserved rows null-extended
-- bug: contains: (0 rows)
-- fixed: contains: (1 rows)
CREATE TABLE A (c0 TINYINT NOT NULL, c4 TIMESTAMP);
CREATE TABLE B (c1 SMALLINT NOT NULL);
INSERT INTO A VALUES (33, '4808-01-13 02:26:25');
-- check
SELECT B.c1, A.c4 FROM A LEFT JOIN B ON 1 = 0;
```

`-- nodes:`, `-- kfactor:` and `-- sites:` give the cluster (1 node, k=0, 4 sites per host by default). An expectation
is `contains: TEXT` (a line of the check's output holds TEXT), `crash` (the server exits; for the bug only), or the
exact output, one header line per line. sqlcmd's output is normalized first: "(Returned 1 rows in 0.01s)" becomes
"(1 rows)", the dashes under the column names go, and runs of spaces become one. A bug that sqlcmd cannot show (the
JDBC driver's, a resize hang) is a `.sh` script instead, run with `VOLTDB_HOME`, `PORT_BASE` and `WORKDIR` set; it
starts its own servers (always with `-H`), and exits 0 while the bug reproduces, 1 once it is fixed, anything else on
an error. A `-- manual: reason` header marks a reproduction that does not reproduce every time, and a `-- platforms:
LIST` header (`uname -s` values) one that reproduces on those systems only: the runner skips either, and lists it in
the summary, unless `--only` names it. A crash counts once the server exits, which it may take minutes to do after
sqlcmd loses the connection; the runner waits up to 300 s.

Not every bug has a reproduction: ENG-30602 (a query now and then gets another statement's result) and ENG-30608 (a
SIGILL in a recursive common table, beside other threads' DDL) never reproduced on demand, and ENG-30612 (an MPI
deadlock after a node dies mid-@UpdateCore) depends on when the kill lands. ENG-30622's script reproduces in a few
cycles in some runs only, so it is marked `-- manual:` and runs only when `--only` names it. ENG-30551 (a subnormal
FLOAT constant in a plan) reproduces on macOS (arm64) only; the lab's x86_64 Linux agents plan the constant, so it is
marked `-- platforms: Darwin`.

## Code coverage

`voltdb/run-coverage.sh` measures how much of VoltDB the provider's runs exercise. It makes the nightly job's runs
(security, kill, export, resize, xdcr, crash, then the reproductions) against a VoltDB build whose execution engine
was built with clang's source-based coverage, with the JaCoCo agent on every server JVM, and reports line and branch
coverage per Java package, per engine directory and file, and per area (planner, executors, DR, export, rejoin and
so on).

Build the engine with coverage in a clean checkout of VoltDB (macOS; Linux with clang works the same way):

```
export CFLAGS="-fprofile-instr-generate -fcoverage-mapping" CXXFLAGS="$CFLAGS" LDFLAGS=-fprofile-instr-generate
export MAVEN_OPTS=-Dmaven.gitcommitid.skip=true   # in a git worktree, where the plugin cannot read HEAD
ant -Djmemcheck=NO_MEMCHECK
```

VoltDB's own `--coverage` build option adds the gcov flags to the link only, so it measures nothing. Then:

```
voltdb/run-coverage.sh --voltdb ~/voltdb-cov --license ~/license.xml --results cov-sqlancer --duration 1200
voltdb/run-coverage.sh --voltdb ~/voltdb-cov --results cov-sqlcoverage --runs sqlcoverage
voltdb/run-coverage.sh --voltdb ~/voltdb-cov --results cov-sqlancer --report-only --compare-with cov-sqlcoverage
```

The second command measures VoltDB's own randomized SQL suite (`ant sqlcoverage`, against HSQL) the same way, and the
third compares the two: `cov-sqlancer/report/compare.txt` lists the lines each covers that the other does not.
sqlcoverage's server always uses VoltDB's default ports (21212, 21211, 3021), so the script refuses to run it while
anything is listening on them: a VoltDB server already running there would be asked to take it into its cluster.

- **Nodes killed with kill -9 keep their counts.** The engine's profiles are written continuously (`%c` in
  `LLVM_PROFILE_FILE`), and each JVM's JaCoCo agent connects to a collector (`coverage/CoverageServer.java`) that
  asks it for its counts every 10 s; a killed node loses at most its last 10 s of Java counts.
- **What is not counted.** The 16.0.0 mirror (DIFF) and the in-service upgrade, whose older kit is not the build
  under test, are left out. The Java totals include the client classes that the harness's own `voltadmin` and
  `sqlcmd` JVMs run; the areas leave those packages out.
- **Versions.** JaCoCo 0.8.12 (agent, core and report) and ASM 9.7, from `~/.m2` by default (`--jacoco-agent`,
  `--jacoco-lib`). VoltDB's own `third_party/java/jars/jacocoagent.jar` is 0.7.9, too old for Java 17 class files.

## The nightly lab job

`matrix-systemtest-apprunner-sqlancer` on jenkins-st runs this script against master's kit as part of the nightly
system test (`matrix-systemtest/master`, on the nights the rotation in `internal`'s
`jenkins-lab/systemtest/trigger-job-for-systemtest` gives master). Its pipeline is `internal`'s
`jenkins-lab/systemtest/apprunner/sqlancer/Jenkinsfile`; the script it calls is `pro`'s
`tests/apptests/jenkins-scripts/run-jenkins-sqlancer-test.sh`.

The job fetches this repository at the commit in its `SQLANCER_REF` parameter. That commit is pinned in the Jenkinsfile,
so a change here reaches the nightly run only when the Jenkinsfile is changed to name it. It then builds SQLancer with
the kit's runtime JDK and makes seven runs, one after the other, every node on the agent:

| Run (triagedb name) | Cluster | Oracles | Time |
| --- | --- | --- | --- |
| `sqlancer-3n-k1-kill300` | 3 nodes, k=1, 8 sites per host; a node is killed and rejoined every 5 minutes | all thirty (NoREC, TLP WHERE, DISTINCT, HAVING, AGGREGATE, PQS, PARAMS, VIEWS, LIMIT, SUBQUERY, WINDOW, SETOPS, DERIVED, PROCEDURE, SCHEMA, GEO, DIFF, DURABLE, XDCR, PLANS, PERMS, STREAMS, TTL, LOAD, SWAP, CONFIG, SNAPSHOT, XCONFLICT, TASK, IMPORT; DURABLE checks across crashes in the crash run, XDCR only in the XDCR run) | 45 minutes |
| `sqlancer-1n-k0-security` | 1 node, 8 sites per host, security on (see [The PERMS oracle](#the-perms-oracle)); `sqlancer-1n-k0` until milestone 28 | the same | 45 minutes |
| `sqlancer-3n-k2-export-s2` | 3 nodes, k=2, 2 sites per host: every partition on every node, and 2 partitions in all; the streams export (see [The STREAMS oracle](#the-streams-oracle)); `sqlancer-3n-k2-s2` until milestone 29 | the same | 45 minutes |
| `sqlancer-3n-k1-resize120` | 3 nodes, k=1, 8 sites per host; every 2 minutes, 2 nodes are added, or removed again (see [Elastic resizes](#elastic-resizes)) | the same | 45 minutes |
| `sqlancer-3n-k1-xdcr-s4` | Two clusters of 3 nodes, k=1, 4 sites per host, linked by XDCR (see [XDCR](#xdcr)) | the same | 45 minutes |
| `sqlancer-3n-k1-upgrade` | 3 nodes, k=1, 8 sites per host, started on 16.0.0 and upgraded in service to the kit a third of the way in (see [In-service upgrades](#in-service-upgrades)) | the same | 45 minutes |
| `sqlancer-3n-k1-crash600` | 3 nodes, k=1, 8 sites per host, a synchronous command log; every 10 minutes every node is killed and the cluster recovered (see [Whole-cluster crashes](#whole-cluster-crashes)) | the same | 45 minutes |

The shapes differ in what the planner and the execution engine do. With one node and 8 sites, a multi-partition query
gathers 8 partitions' results in one process. With 3 nodes, k=1, the partitions' leaders are spread over the nodes and
each has one replica, and node failures move them; elastic resizes add a partition group and move data to it and
back; crashes replay the command log. With k=2 and 2 sites per host, every node holds every partition, so
there are only 2 partitions and every write is replicated twice. The whole job takes about 5 hours 50 minutes.

Before the runs, the job reruns the bug reproductions against the kit, as the triagedb row `sqlancer-repros` (see
[Bug reproductions](#bug-reproductions)): about 10 minutes on 1 node. It fails when a reproduction changes or cannot
run; the bugs that no longer reproduce are listed in the build log ("no longer reproduces: ..."). `SQLANCER_REPROS=no`
skips it.

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

The job's parameters change a manual run: `CLUSTERS` (runs as `nodes:kfactor[:chaos[:sites]]`, where chaos is the
seconds between node failures, `r` and the seconds between elastic resizes, `c` and the seconds between crashes, or
`x` for an XDCR pair, `u` for an in-service upgrade from `MIRROR_VERSION`, `sec` for security, or `exp` for export, and
sites the sites per host, default `3:1:300 1:0:sec 3:2:exp:2 3:1:r120 3:1:x:4 3:1:u 3:1:c600`), `DURATION` (seconds
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
| `--oracle` | `NOREC` (the default), `TLP_WHERE`, `TLP_DISTINCT`, `TLP_HAVING`, `TLP_AGGREGATE`, `PQS`, `PARAMS`, `VIEWS`, `LIMIT`, `SUBQUERY`, `WINDOW`, `SETOPS`, `DERIVED`, `PROCEDURE`, `SCHEMA`, `GEO`, `DIFF` or `SMOKE`; several are comma-separated. |
| `--max-num-tables` | Tables per database, 1 to this (default 3). |
| `--max-num-indexes` | CREATE INDEX statements per database, 0 to this (default 4). |
| `--max-num-updates` | UPDATE statements per database, 0 to this (default 5). |
| `--max-num-deletes` | DELETE statements per database, 0 to this (default 2). |
| `--max-num-views` | CREATE VIEW statements per database, 0 to this (default 2). |
| `--retry-seconds` | How long a statement rejected because of another thread's schema change is retried (default 120). |
| `--avoid-known-bugs` | `true` (the default) keeps the generator away from the VoltDB bugs listed below, so a failure is something new. `false` generates them too. |
| `--servers` | Comma-separated `host:port` client addresses of every node. SQLancer connects to all of them (instead of `--host` and `--port`), so it keeps working while any one node is down. `run-sqlancer.sh` passes it for more than one node. |

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
When the predicate holds a subquery, the unoptimized query is the inner SELECT alone, and the oracle adds up its rows'
values: VoltDB rejects a SUM of an expression with a subquery, even through the derived table ("SQL Aggregate function
calls with subquery expression arguments are not allowed").

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

## The PROCEDURE oracle

PROCEDURE (`oracle/VoltDBParameterOracle` with `procedure` true) checks ad hoc execution against stored procedures. It
writes queries as PARAMS does, one with literals and one with some of them parameters, and runs the second as a DDL
procedure, `CREATE PROCEDURE p AS q`, called through `{call p(?, ...)}` with the literals' values. VoltDB plans a
procedure's statement when the procedure is created, not when it runs; gives its parameters the types the statement
implies; and converts the call's values to them. An ad hoc statement does none of these.

When a query reads a partitioned table, half its procedures are single-partition. The query gets `AND t.c = ?` on the
partitioning column of the table that every other partitioned table in it joins on that column, with a value read from
the table, and the procedure is `CREATE PROCEDURE p PARTITION ON TABLE t COLUMN c PARAMETER n AS q`. It runs on the one
partition that holds the value, through the single-partition planner and executor. The query with literals gets
`t.c = value` and runs on every partition.

VoltDB applies one catalog update (a DDL statement) at a time across the whole cluster. Each one blocks every partition
and empties the plan caches. With a CREATE and a DROP per check, about two updates a second cut every oracle's rate to
a third in a 1-node trial. So each check writes up to eight queries and keeps them. Once 24 are kept, about once per
database, the oracle creates their procedures in one DDL batch, compares each, and drops them in another.

A batch fails as a whole when VoltDB cannot plan one of its statements. A parameter can cause that where its literal
folds: `CAST(? AS VARBINARY) IS NOT NULL` in a join's WHERE clause is "Join with filters that do not depend on joined
tables". So each query runs ad hoc with its parameters as it is written, which is a PARAMS check, and one that fails is
not kept. If the batch still fails, its procedures are created one by one. Procedure names
are the database prefix, `P` and a number unique within the run. A procedure that a node failure kept from being
dropped is dropped when its database's name is next used.

A failure reports the procedure, the call and the rows only one side returned. The reproducer creates the procedure,
calls it and drops it again, so `--use-reducer` can reduce it.

## The SCHEMA oracle

SCHEMA (`oracle/VoltDBSchemaChangeOracle`) checks schema changes made in the middle of a run, and VoltDB's plan cache.
It writes up to eight queries over one table, each a SELECT of columns or expressions with a WHERE clause half the
time. It runs each query twice: the first run plans it, and the second runs the cached plan. Then it changes the table
and runs the queries again; the catalog changed, so they need new plans. It undoes the change and runs them a fourth
time. All four runs must return the same rows.

The change is one of:

- `CREATE INDEX` on one to three of the table's columns, undone by `DROP INDEX`. It can change every plan that reads
  the table.
- `ALTER TABLE t ADD COLUMN x type [DEFAULT d]`, undone by `DROP COLUMN`. It rewrites every row, so every row's new
  value must be d, or NULL without a DEFAULT: `SELECT COUNT(*) FROM t WHERE x = d` must count every row. The types
  include a VARCHAR(100) whose default VoltDB stores outside the tuple.
- `ALTER TABLE t ALTER COLUMN c VARCHAR(n)` with a larger n, on a VARCHAR column outside the primary key and the
  partitioning column. It isn't undone. It can move the column's values out of
  the tuple when n crosses 63 bytes.

Each catalog update blocks every partition, so the oracle changes the schema once per database, on its first check,
and skips its later checks. Tables with a materialized view are left out: VoltDB refuses most changes to them. If VoltDB
refuses a change, the check is skipped: this oracle checks query results, not which changes VoltDB allows.

A failure has no reproducer: reducing the database's statements would lose the order of the queries and the change.
The failure message holds the change, the undo and the query.

While nodes fail (`--node-failures`), SCHEMA and PROCEDURE do nothing unless `--avoid-known-bugs` is false:
ENG-30612, an MPI deadlock when a node dies during an @UpdateCore. SCHEMA's slow ALTER TABLE and PROCEDURE's frequent
DDL batches make such a kill likely. The wedge happened twice in the lab, the second time with SCHEMA already off.

## Date, JSON, FLOAT math and geography

### Functions in expressions

Since milestone 19 the expression generator, and so every oracle that uses it, also writes:

- **FLOAT math:** `SQRT`, `EXP`, `LN`, `LOG10`, `POWER(x, n)` with n from -3 to 3, `SIN` and `COS`. Their results are
  FLOAT, so they appear only where FLOAT operands may (a comparison draws its operands from FLOAT or from the exact
  types, ENG-30513). A NaN or infinite result is "Invalid result value", expected.
- **Parts of a DATE or TIMESTAMP:** `YEAR`, `MONTH`, `DAY`, `HOUR`, `MINUTE`, `DAYOFWEEK`, `DAYOFYEAR`, `QUARTER`,
  `WEEK` and `WEEKDAY`, integers. `HOUR` and `MINUTE` take a TIMESTAMP: VoltDB refuses them on a DATE ("incompatible
  data type in conversion"). `SINCE_EPOCH(unit, t)`, a BIGINT, takes a TIMESTAMP too.
- **Temporal expressions:** `DATEADD(unit, n, t)` and `TRUNCATE(unit, t)`, of t's type, and
  `TO_TIMESTAMP(unit, n)`, a TIMESTAMP. A result or input outside TIMESTAMP's years 1583 to 9999 is an expected error.
  The units are keywords (`ast/VoltDBKeyword`), which PARAMS never makes parameters.
- **JSON:** `FIELD(doc, name)`, `ARRAY_ELEMENT(FIELD(doc, 'b'), i)` and `SET_FIELD(doc, name, value)`, VARCHAR, and
  `ARRAY_LENGTH(FIELD(doc, 'b'))`, an integer. The document is one of a few JSON documents, or now and then a string
  expression, which is mostly not JSON: "Invalid JSON" is expected.

PQS's evaluator does not model these functions: a PQS query that holds one is skipped.

Since milestone 31 it also writes the functions that milestone 30's coverage run found the engine never ran
(`expressions/stringfunctions.h` was 13% covered, `bitwisefunctions.h` 0%, `datefunctions.h` 38%):

- **Strings:** `LEFT(s, n)`, `RIGHT(s, n)`, `REPEAT(s, n)`, `SPACE(n)`, `REPLACE(s, t, r)` with t a constant that is
  not empty, `CONCAT(a, b [, c])`, `OVERLAY(s PLACING t FROM start [FOR length])`, `CHAR(code point)`, `HEX(n)` and
  `BIN(n)`. Counts and positions are small constants of 0 or more: a negative one is an error.
- **Integers from strings:** `OCTET_LENGTH(s)`, `POSITION(t IN s)` and `REGEXP_POSITION(s, pattern [, 'i' | 'c'])`,
  the pattern a constant (a generated one would mostly not be a valid regular expression).
- **Bitwise:** `BITAND`, `BITOR`, `BITXOR`, `BITNOT`, `BIT_SHIFT_LEFT(n, k)` and `BIT_SHIFT_RIGHT(n, k)`, BIGINT in
  and out. VoltDB rejects any other integer type ("incompatible data type in conversion"), so an operand that is not
  a BIGINT column is written `CAST(x AS BIGINT)`. A result of BIGINT's minimum, VoltDB's NULL, is an expected error;
  k runs past 63, where the result is 0.
- **Numbers:** `SIGN(x)`, an integer; `ROUND(x, places)`, DECIMAL; `STR(x [, length [, decimals]])` and
  `FORMAT_CURRENCY(x, places)`, VARCHAR. As documented, ROUND and STR take FLOAT or DECIMAL and FORMAT_CURRENCY
  DECIMAL only, so x is a FLOAT expression or `CAST(... AS DECIMAL)`: VoltDB accepts an integer when it plans the
  statement, then fails when it runs it ("Type INTEGER can't be cast as DECIMAL").
- **Time:** `DATEDIFF(unit, t1, t2)`, a BIGINT; `TIME_WINDOW(unit, n, t [, START | END])` and
  `FROM_UNIXTIME(seconds)`, TIMESTAMPs; `FORMAT_TIMESTAMP(t, zone)`, VARCHAR.
- **Masks:** `MASK_TEXT(s, mask, keep)`, and `MASK_INNER`, `MASK_OUTER` and `MASK_PARTIAL(s, left, right, mask)`.

PQS's evaluator models LEFT, RIGHT, REPEAT, REPLACE, SPACE, CONCAT, HEX, BIN, OCTET_LENGTH, POSITION, SIGN and the
bitwise functions, as the engine computes them; a PQS query with any of the others is skipped.

The bugs below were found in milestone 31.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30655 | A procedure created AS a query with `TIME_WINDOW(..., END)` succeeds and runs, but every later DDL statement fails with "unexpected token: END", its own DROP PROCEDURE included, and still after a restart; only `voltdb init --force` gets DDL back. START, no option, and END in a view or an index do not do it. Critical. | `TIME_WINDOW`'s option is START only. |
| ENG-30656 | ROUND, STR and FORMAT_CURRENCY fail on a DECIMAL whose integer part is 2^63 or more, which DECIMAL holds: "Type DECIMAL with value ... can't be cast as BIGINT because the value is out of range" (the engine narrows the whole part to an int64). Major. | None needed: the error is expected for other reasons ("out of range for the destination type"), and the reproduction tracks the bug. |
| ENG-30658 | TO_TIMESTAMP and FROM_UNIXTIME of a NULL return a NULL typed BIGINT, which FORMAT_TIMESTAMP rejects ("Type BIGINT can't be cast as TIMESTAMP or DATE"), so `FORMAT_TIMESTAMP(FROM_UNIXTIME(n), zone)` fails on any row where n is NULL. Major. | FORMAT_TIMESTAMP's argument is a TIMESTAMP column or constant. |

### The GEO oracle

GEO (`oracle/VoltDBGeographyOracle`) checks GEOGRAPHY_POINT and GEOGRAPHY against geography computed in Java. On one
database in four (its first check there) it creates a table `<prefix>GEO (id, p GEOGRAPHY_POINT, g GEOGRAPHY(1024))`, replicated or partitioned
on id, with twelve rows: a random point and a small box, written counter-clockwise (VoltDB rejects a clockwise ring as
"not normalized"). It checks:

- `LATITUDE(p)` and `LONGITUDE(p)` return the inserted coordinates within 1e-9 degrees, also after
  `POINTFROMTEXT(ASTEXT(p))`. VoltDB keeps a point as a unit vector, so they don't come back exactly.
- `DISTANCE(a.p, b.p)` for every pair is the great-circle distance on a sphere of radius 6,371,008.8 m, within a
  relative 1e-9. `WHERE DISTANCE(a.p, b.p) < r` returns the pairs closer than r. On a replicated table only, as the
  query joins the table with itself.
- `CONTAINS(b.g, a.p)` for every point and box, or each row's point and its own box on a partitioned table, is the
  containment computed in Java. A box's edges are great-circle arcs, not lines of latitude, so the points are drawn
  away from every box's border by a fifth of its size. The check runs again after `CREATE INDEX` on g, which VoltDB
  uses for CONTAINS.

The table is dropped at the end, and the schema reader ignores it, so no other oracle reads its columns. A failure has
no reproducer; its message holds the statements and the query.

GEO runs on one database in four and skips the rest. Its four catalog updates (CREATE TABLE, CREATE INDEX and two
DROPs) block the whole cluster: run on every database, as in milestone 19, they cost the other oracles about 40% of
their queries (4,481 against 7,724 in 150 seconds on 1 node, 4 threads).

## The DIFF oracle

DIFF (`oracle/VoltDBDifferentialOracle`) is differential testing against another VoltDB version. Give the provider
`--mirror host:port[,host:port...]`, the client addresses of a second cluster, normally the last release. Every
statement that changes the database (DDL, INSERT, UPDATE, UPSERT, DELETE, the oracles' own CREATE PROCEDURE and ALTER
TABLE) then also runs on the mirror (`VoltDBConnection`), so the two clusters hold the same rows. DIFF runs the same
generated query on both and compares the rows as multisets. The query is a SELECT of columns and expressions with a
WHERE clause, or a GROUP BY query with aggregates and maybe WHERE and HAVING clauses, over the FROM clauses and joins
the other oracles use.

- FLOATs compare to 12 significant digits, as a SUM of FLOATs may add them in another order on each cluster. Exact
  numbers compare by value.
- A query with an expected error on either cluster is skipped.
- A change that succeeds on one cluster and fails on the other marks the database diverged, and DIFF skips its later
  checks. So does a statement a node failure interrupted, as it may have committed on one side only.
- A difference is a change between the versions: a bug fixed, a bug introduced, or a deliberate change in behavior.
  `--avoid-known-bugs` avoids the bugs filed against the newer version, which the older one may share.
- While `--avoid-known-bugs` is true, DIFF doesn't group by a DECIMAL column. ENG-30255, fixed after 16.0.0: a
  GROUP BY on a DECIMAL that is NULL aborts a 16.0.0 server (`NValue::hashCombine` throws in the hash aggregate).
  DIFF found it in its first trial against 16.0.0.

`run-sqlancer.sh --mirror-voltdb DIR [--mirror-license FILE] [--mirror-java-home DIR]` starts one node of the kit
in DIR beside the cluster, on the port slot after the last node's, k=0 with the same sites per host. It passes
`--mirror` to the provider, records the mirror's version in the manifest, and saves its server log under
`servers/mirror`. Without `--mirror`, DIFF skips every check.

`--mirror-java-home DIR` gives the mirror its own JAVA_HOME. An older release may refuse the newer JDK the cluster
runs on: 16.0.0 needs Java 17 or 21 and exits at once on Java 25 ("Unsupported openjdk version"), so the lab job
passes `/opt/oracle_java21`. A mirror that exits stops the run at once, with the last line of its start log.

## The PLANS oracle

Differential query plans (DQP, after Ba and Rigger, SIGMOD 2024): the same query, planned two ways, must return the same
rows. PLANS generates a query, with columns and a WHERE clause over the FROM clauses and joins the other oracles use,
and changes how VoltDB can plan it without changing what it means:

| Change | What it does to the plan |
| --- | --- |
| index off | A numeric column the WHERE clause reads becomes `(c + 0)` there. VoltDB can use it neither for an index scan nor to send the query to one partition, so an index scan becomes a sequential scan, or a single-partition plan a multi-partition one. |
| join order | The FROM list of an inner join, reversed (the selected columns stay in their order). |
| index on | At most once per database: `CREATE INDEX` on a column the WHERE clause reads, the query run again, the index dropped. |

PLANS asks `@Explain` for both plans and compares their nodes (EXPLAIN's lines that start with a capital letter: the
scans, joins, sends and receives, without their predicates). When the plans have the same nodes, it skips the check
without running the queries; otherwise it compares the two queries' rows as multisets, FLOATs to 12 significant
digits, and a failure shows both queries and both plans. Only numeric columns are wrapped: the identities of the other
types run into known bugs (`v || ''` is NULL when v is '', ENG-30546). At exit, PLANS prints the number of checks that
compared different plans, of each kind, and of those it skipped as the same plan; the summary has the line.

Most checks keep their plan: wrapping a column changes nothing when no index covers it and the query reads every
partition anyway. In 5-minute trials of PLANS alone, 1 node compared 116 index-off, 98 join-order and 5 index-on pairs
of plans (4,154 skipped), and 3 nodes 95, 53 and 3 (2,920 skipped).

CERT-style checks (a more restrictive query must not get a larger estimated row count) do not apply: VoltDB's plans
carry no row estimates in EXPLAIN or EXPLAINJSON, and its planner estimates from fixed table sizes, not from the data.

## The TTL oracle

Milestone 34. Milestone 30's coverage run found the system procedures behind rows that VoltDB removes by itself
(`LowImpactDeleteNT`, `MigrateRowsNT`) unexercised; clients cannot call them, so TTL drives them through SQL:

- **Time to live.** A table `XT_DB0 (k, ts, v) USING TTL 2 SECONDS ON COLUMN ts`, with a random `BATCH_SIZE` and
  `MAX_FREQUENCY` and an index on `ts`. Each check inserts 1 to 30 rows, each with a `v` unique in the run and a `ts`
  an hour ago (expired) or a day ahead (live), and waits up to 60 s until every expired row is gone; then every live
  row must still be there.
- **Time to live with migration.** With `--ttl-migrate-target` (`--export` adds the loopback target `sqlancer_lbt`,
  procedure `XS_LBT.insert`), half the TTL checks use a table `XTM_DB0 MIGRATE TO TARGET sqlancer_lbt (d, k, ts, v)`
  with the same TTL: its expired rows are migrated, not deleted, through `MigrateRowsNT`. They must leave the table and
  arrive in `XS_LBT`, and no live row may. Its index on `ts` is `WHERE NOT MIGRATING`: a TTL table that migrates finds
  its expired rows only through such an index, and with an ordinary one never migrates them (`TTLManager` logs a
  warning saying so).
- **Migration.** A table `XM_DB0 (d, k, v, s, f) MIGRATE TO TARGET ...` with a shadow table `XM_DB0_SH`. Each check
  inserts rows into both, then runs `MIGRATE FROM XM_DB0 WHERE k < x AND NOT MIGRATING`.
  - With `--loopback-target` (`--export`), the table migrates to that target, whose procedure inserts into `XS_LB`:
    once the export has caught up, the table's rows and XS_LB's rows tagged `XM_DB0` must together be the shadow
    table's, with none in both.
  - Without it, the target does not exist and the rows stay: the table must hold the shadow's rows, and exactly those
    migrated so far must be `MIGRATING`.

A statement that fails (a node failure's, as with STREAMS) leaves the table out of step, and that name's TTL or
migration checks stop for the run. At exit TTL prints how many checks of each kind it made.

In a 5-minute trial on 1 node (beside NoREC and TLP WHERE) TTL made 892 TTL checks and 880 migration checks; in one on
3 nodes with k=2 and `--export` (beside STREAMS and NoREC), 110 and 105, the migrations through the loopback target. With
the migrating TTL tables, a 5-minute trial on 3 nodes with k=2 and `--export` (beside NoREC) made 294 TTL checks and
307 migration checks.

Measured with `run-coverage.sh`'s export run (20 minutes, master bf8602e3, every oracle), against milestone 30's seven
runs together: `TTLManager` 107 of 132 lines (from 25), `LowImpactDeleteNT` 77 of 108 (from 2), `MigrateRowsNT` 72 of
109 (from 2) and `MigrateRowsBase` 76 of 108 (from 4).

## The LOAD and SWAP oracles

Milestone 35. Milestone 30's coverage run found the system procedures for bulk operations unexercised:
`@LoadMultipartitionTable` (3 of 104 lines), `@LoadSinglepartitionTable` (2 of 76), `@SwapTables` (1 of 18, and the
engine's swap executor and plan node 0 of 112) and `@ValidatePartitioning` (3 of 62). Each check uses two twin tables
of its own, partitioned on `k` or replicated at random:
`XLPA_DB0`/`XLPB_DB0` (LOAD, partitioned), `XLRA_DB0`/`XLRB_DB0` (LOAD, replicated), and `XW...` for SWAP, each
`(k INTEGER, id SMALLINT, v VARCHAR(12), b VARBINARY(6), f FLOAT, d DECIMAL, t TIMESTAMP, PRIMARY KEY (k, id))`
with a unique index on `(k, v)` and an ordinary one on `(f, t)`.

- **LOAD.** Both twins are emptied and get the same 0 to 8 base rows. A batch of 1 to 12 rows is then loaded into A,
  in insert or upsert mode, by `@LoadMultipartitionTable` (replicated) or `@LoadSinglepartitionTable` (partitioned,
  every row of the batch with the same `k`, as `VoltBulkLoader` sends it), and applied to B as single `INSERT` or
  `UPSERT` statements. If every statement succeeded, A must hold B's rows and the procedure must report the batch's
  size. If one broke a constraint (the primary key or the unique index), the load must fail as a whole and leave A as
  it was. About two batches in five break one.
- **SWAP.** A and B get 0 to 10 rows each, then `@SwapTables(A, B)`, once or twice: each table must hold the other's
  rows, the procedure must report how many rows moved, and each row must be found through the primary key's index, the
  unique index, and, by count, the index on `(f, t)`.
- **Partitioning.** After each check, `@ValidatePartitioning` with NULL (the current hash configuration) must find no
  row of any table in the wrong partition: with node kills or resizes that checks the whole database, not only the
  twins. A byte array it cannot parse crashes the server (ENG-30674 below), so the oracle never passes one.

The twins' rows change through prepared statements and calls, which DIFF's mirror does not repeat, so the mirror never
compares them. Their DDL goes through an ordinary statement, which waits while a node is killed (`--ddl-hold`), and
only once per run, as each catalog change is slow on a cluster. Each check starts by emptying the twins, so one that
fails other than on a constraint (a node failure's) is only skipped. At exit the oracle prints how many checks it made.
In a 90-second trial on 1 node: 643 loads (263 batches rejected as a whole), 986 swaps and 1,286 validations. In a
5-minute trial on 3 nodes with k=1 and a node killed every 60 s (beside NoREC, TLP WHERE and VIEWS): 663 loads (282
rejected), 989 swaps and 1,323 validations, across 4 kills and rejoins.

Measured with `run-coverage.sh`'s kill run (15 minutes, master bf8602e3, every oracle), against milestone 30's seven
runs together: `@SwapTables` 13 of 18 lines (from 1), the engine's swap executor 56 of 60 and plan node 27 of 52
(from 0), `@LoadMultipartitionTable` 88 of 104 (from 3), `@LoadSinglepartitionTable` 50 of 76 (from 2) and
`@ValidatePartitioning` 59 of 62 (from 3).

## The CONFIG oracle

Milestone 36. Milestone 30's coverage run found the procedures that change the configuration while the database runs
unexercised: `@SetYamlConfiguration` (10 of 404 lines), `@GetYamlConfiguration` (2 of 155), `YamlUtils` (0 of 169)
and `@UpdateDeployment` (3 of 256). Each CONFIG check:

- reads the system settings (`@GetYamlConfiguration('deployment.systemsettings')`);
- changes one of them with `@SetYamlConfiguration`, either by an assignment
  (`deployment.systemsettings.snapshot.priority=6`) or by a YAML document, which `@UpdateDeployment` applies;
- reads them again: that setting must have its new value and every other its old one. A value equal to the current one
  gives `@UpdateDeployment`'s status 2, no change, and nothing may change.

About one check in ten instead sends a change VoltDB must refuse (a value that is not a number, a setting that cannot
change while the database runs, one that does not exist, a YAML document that is not a deployment, text that is not
YAML), and nothing may change.

The settings are ones that cannot change other oracles' results: snapshot priority, the flush intervals, the resource
monitor's frequency, compaction, elastic resize throughput and duration, procedure logging, the clock skew interval,
and query timeouts of 10 s or more. One check runs at a time across SQLancer's threads, so the settings read back are
the check's own. SQLancer connects to every node, so the reads come from all of them, those that rejoined after a
kill included. In a 90-second trial on 1 node (beside NoREC) CONFIG checked 750 changes and 77 refusals; in a 5-minute
trial on 3 nodes with k=1 and a node killed every 60 s (beside LOAD, NoREC and TLP WHERE), 616 and 53 across 4 kills
and rejoins.

Measured with `run-coverage.sh`'s kill run (15 minutes, master bf8602e3, every oracle), against milestone 30's seven
runs together: `@SetYamlConfiguration` 176 of 404 lines (from 10), `@GetYamlConfiguration` 73 of 155 (from 2),
`YamlUtils` 86 of 169 (from 0) and `@UpdateDeployment` 168 of 256 (from 3).

## The SNAPSHOT oracle

Milestone 37. Milestone 30's coverage run found the snapshot code little exercised beyond what recovery uses:
`SnapshotRestore` 802 of 1,518 lines, `SnapshotUtil` 439 of 852, `TableSaveFile` 268 of 518, and none of
`SavedTableConverter`, `CSVSnapshotWritePlan` or `SnapshotSizeEstimator`. Each SNAPSHOT check uses a table of its own,
`XNP_DB0` (partitioned) or `XNR_DB0` (replicated), shaped as LOAD's twins:

1. It empties the table, fills it, and saves a native snapshot of it alone with a blocking `@SnapshotSave`.
   `@SnapshotScan` of a node's snapshot directory must list the snapshot, of that table.
2. A third of the checks also save a CSV snapshot of it, whose files must hold the table's rows, each once.
3. It changes the table (deletes, updates, inserts), then restores the table alone with `@SnapshotRestore`, either:
   - into the emptied table: the table must hold exactly the rows saved; or
   - into the table as it is, with a duplicates path: each saved row that collides with none there (on the primary key
     or the unique index on `(k, v)`, where VoltDB counts NULLs as equal) must be added, each that does must go to the
     duplicates files, and nothing else may change. VoltDB reports such a restore as failed, so the table and the
     files decide.
4. `@SnapshotDelete` removes the check's snapshots.

How the oracle fits VoltDB's snapshot handling:

- Snapshots go to each node's own snapshot directory (`pathType` `SNAP_AUTO`): `run-sqlancer.sh`'s nodes all run on one
  machine, and in a shared directory they find each other's files and refuse the nonce. `--snapshot-path`, which
  `run-sqlancer.sh` sets to its cluster directory, is where the oracle finds the nodes' files.
- Every replica of a partition writes its duplicates, and a partitioned table's CSV rows are spread over the hosts, so
  the oracle counts distinct rows. A replicated table's duplicates reach the files up to a minute after the restore
  answers, and the oracle waits for them.
- VoltDB takes one snapshot at a time: a save while another runs (the truncation snapshot that each restore asks for,
  with the command log on) is answered SNAPSHOT REQUEST QUEUED and taken later, after `USER_SNAPSHOT_RETRY_INTERVAL`
  seconds, 30 by default. `run-sqlancer.sh` sets it to 1 on the servers (`SQLANCER_USER_SNAPSHOT_RETRY` overrides it;
  empty keeps VoltDB's default), and the oracle waits for a queued save in `@Statistics SNAPSHOTSTATUS`. Checks take
  turns across SQLancer's threads.
- A restore while a node rejoins wedges the cluster (ENG-30676 below), so while `--avoid-known-bugs` is true a restore
  waits while `--ddl-hold`'s file exists, from before each kill until the node has rejoined.
- After a node has rejoined, a restore's duplicates can go unwritten (ENG-30678 below), so while `--avoid-known-bugs` is
  true and nodes fail, resize, crash or upgrade, the duplicates files are not counted; the table still is.
- A restore while DDL runs makes writes to a join view's replicated table crash the server (ENG-30677 below), so while
  `--avoid-known-bugs` is true no DDL of SQLancer runs during a restore: every connection's DDL holds a lock shared
  (`VoltDBConnection`), and a restore holds it exclusive.

## The XCONFLICT oracle

Milestone 38. The XDCR oracle never makes a conflict: each database writes through one cluster only, and any conflict
XDCR logs fails the run. So milestone 30's coverage run found XDCR's conflict handling (`DRConflictManagerImpl`, 7 of
190 lines) and much of the replication of multi-partition writes (`DRConsumerMpCoordinatorImpl`, 255 of 949)
unexercised. In an XDCR run (`--xdcr`), each XCONFLICT check:

1. uses a DR table of its own, `XCP_DB0` (partitioned on `k`) or `XCR_DB0` (replicated), `(k, v, n)`, created on
   both clusters; if the clusters do not hold the same rows (a divergence an earlier check left), it empties the table
   through both first;
2. writes the same few keys through both clusters at once, alternating, without waiting for replication: inserts,
   upserts and updates by key, updates by value (multi-partition on the partitioned table), and deletes by key and by
   value;
3. waits up to 60 s for the clusters to agree. VoltDB resolves most conflicts so that they do. Where they still differ
   on a key, XDCR's conflict log (its `xdcr_conflicts` export files under each node's voltdbroot, which the oracle
   finds through `--cluster-dir`) must have a row for that table and key with `DIVERGENCE` `D`. While
   `--avoid-known-bugs` is true, a key left different with no such row is counted (ENG-30679 below), not failed.

`run-sqlancer.sh` does not count conflicts on these tables against the run. At exit the oracle prints how many checks
converged and how many differing keys were reported as diverged. In a 5-minute trial on two 3-node clusters (k=1,
4 sites per host), beside XDCR and NoREC: 719 checks, 716 converged.

## The TASK oracle

Milestone 39. Milestone 30's coverage run found scheduled tasks (`CREATE TASK`) little exercised: the scheduler
(`TaskManager`), its statistics (`TaskStatsSource`) and the CRON schedule. Each TASK check creates a task of its own,
`XKT_DB0_<n>`, whose procedure inserts a row tagged with the task's name into `XK_DB0` (partitioned on `k`):

- on the database (`XKP_DB0`, a multi-partition insert), or on every partition (`RUN ON PARTITIONS`, through the
  partitioned `XKQ_DB0`, so that each partition's scheduler calls it);
- on a schedule of `EVERY` or `DELAY` 200, 500 or 1000 milliseconds, or `CRON * * * * * *` (every second);
- `ON ERROR LOG` or `IGNORE`.

It lets the task run for 3 s and disables it (`ALTER TASK ... DISABLE`), then checks that:

1. it ran: at least one row, or two on every partition, and no more than the schedule allows in the time it really ran,
   from `CREATE TASK` until the `ALTER` returned (`ALTER TASK` is DDL, which waits while a node is killed);
2. after the `ALTER`, at most one more row per scheduler (one in flight), and then none for a few seconds;
3. `@Statistics TASK` shows it `DISABLED`.

A third of the checks instead run a task whose procedure fails from its second run on (a duplicate primary key in the
replicated `XKS_DB0`) with `ON ERROR STOP`: it must stop at the first failure, with at most one row, and show `ERROR`
in `@Statistics TASK`. Every task is dropped at the end of its check. A failure lists the times of the task's rows.

While `--avoid-known-bugs` is true and nodes fail, resize, crash or upgrade, a task may run twice as often as its
schedule allows, and two more rows per scheduler may follow `ALTER TASK ... DISABLE` (ENG-30680 below).

In two 5-minute trials on 3 nodes (k=1, 4 sites per host) with a node killed and rejoined every 45 s: 173 checks, 61 of
them of a task stopped by an error.

## The IMPORT oracle

Milestone 41. Milestone 30's coverage run found importers never run: the importer framework (`ImportManager`) and the
distribution of their resources among the nodes (`ChannelDistributer`, 751 lines). With IMPORT among the oracles,
run-sqlancer.sh configures VoltDB's pull socket importer (`pullsocketimporter.jar`, CSV) to pull lines from four ports
on this machine (port slot 18), each a resource the nodes share out among themselves and again when one fails, into
`XI.insert`; the provider gets `--import-ports`. The XDCR peer, the mirror and the JDBC sink import nothing. SQLancer
listens on those ports, and each IMPORT check:

1. creates `XI (k, v)` (partitioned on `k`) if the catalog lacks it;
2. sends 1 to 20 lines, each with a key unique in the run, through one port, to the node that has connected to it;
3. waits up to 60 s until `XI` holds those keys with their values (through both clusters of an XDCR run, as a database
   may work through either and only the first imports);
4. checks that `XI` holds no more rows than the run has sent.

The importer delivers at most once: a line in flight when a node fails is lost. So where nodes fail, resize, crash or
are upgraded, lines that never arrive are counted, not failed; elsewhere each must arrive, and a line put into `XI`
altered fails the check anywhere. At exit IMPORT prints its checks, the lines sent and the lines lost.

In 2- to 5-minute trials on 3 nodes (k=1, 4 sites per host) beside NoREC: 40,308 lines with no failures; 20,106 with a
node killed every 45 s; 30,566 with 2 nodes added or removed every minute; 6,020 with every node killed and the cluster
recovered every 90 s; 22,344 on one node with security; and 16,781 on two 2-node clusters linked by XDCR: none was lost
or altered.

## The PERMS oracle

With `--security`, the deployment enables security with three users, `sqlancer` (administrator, whom SQLancer and
voltadmin use), `reader` and `writer`, and the provider creates their roles when it first connects:
`CREATE ROLE sqlancer_read WITH SQLREAD` and `CREATE ROLE sqlancer_write WITH SQL` (on the mirror too). PERMS then
runs statements as reader or writer:

| Statement | reader | writer | Checked |
| --- | --- | --- | --- |
| A query (columns and a WHERE clause over the usual FROM clauses and joins) | allowed | allowed | The same rows as the administrator gets. |
| A change (the provider's INSERT, UPDATE and DELETE generators) | refused | allowed | Refused: a permission error, and every table still holds as many rows. Allowed: writer's change matches no row (`UPDATE t SET c = c WHERE 1 = 0`, `DELETE FROM t WHERE 1 = 0`), as a real one would leave the mirror behind. |
| DDL | refused | refused | A permission error, and no row count changes. |
| A procedure created `ALLOW sqlancer_read` (created and dropped around the call) | allowed | refused | SQL does not include ALLPROC. |

The database's thread is its only writer, so a row count cannot change for another reason. VoltDB checks for a catalog
update in progress before it checks a user's permission, so DDL can be refused with "another one is in progress"
first; PERMS retries that until the permission error comes. At exit, PERMS prints how many allowed and refused
statements it checked; the summary has the line. Without `--security`, it skips every check. In a 5-minute trial on
1 node it checked 307 allowed and 283 refused statements.

## The STREAMS oracle

A stream cannot be read, so STREAMS puts each row into a stream and into a shadow table with the same columns, and
checks the stream's materialized view against the same GROUP BY over the shadow table. Each database gets, at its
first STREAMS check:

```sql
CREATE STREAM XS_DB0_ST PARTITION ON COLUMN k [EXPORT TO TARGET sqlancer] (k INTEGER NOT NULL, v BIGINT, s VARCHAR(16), f FLOAT, i BIGINT NOT NULL);
CREATE TABLE XS_DB0_SH (...the same columns...); PARTITION TABLE XS_DB0_SH ON COLUMN k;
CREATE VIEW XS_DB0_SV (k, s, n, sv, mn, mx) AS SELECT k, s, COUNT(*), SUM(v), MIN(f), MAX(v) FROM XS_DB0_ST GROUP BY k, s;
```

(VoltDB requires a view of a partitioned stream to group by the partitioning column.) Each check inserts 1 to 20 rows,
with NULLs now and then, into the stream and the shadow table, and compares the view with the GROUP BY. The names start
with `XS_`, outside the database's own names, so no generator or other oracle touches them, and they stay for the run:
a later database of the same name goes on with them, and no stream is dropped with rows still to export.

With `--export`, the deployment adds a file export target, and the provider gets `--export-target sqlancer`, so the
streams export to it. Its `outdir` is relative, so each node writes into its own `voltdbroot/sqlancer_export`, as on
separate hosts: in one shared directory two nodes exporting the same stream in the same second name the same file, and
the second loops on it for good (ENG-30629). When SQLancer stops, the script runs `@Quiesce`, shuts the cluster down gracefully, which closes
the export files (the file target writes some rows only when it rolls or closes a file, minutes after `@Quiesce`
returns while many catalog updates run), and counts the distinct rows in them. That must equal the rows STREAMS
inserted, which it prints at exit. Export is at least once: an identical line (the same transaction id and sequence
number) counts once, and the summary reports how many were exported twice. A missing row fails the run.

In a 5-minute trial on 3 nodes with k=2 and 2 sites per host, beside VIEWS, NoREC, TLP WHERE, DIFF and SCHEMA, STREAMS
compared 1,548 views and all 15,695 rows were exported. That trial used one shared `outdir` and got by; lab build #43
did not, as below.

### Other export targets (milestone 33)

Milestone 30's coverage run found export the least covered area (15.9% of its lines), as only the file target ever ran.
So `--export` adds two more targets:

- **HTTP** (`sqlancer_http`, `type="http"`, endpoint `http://localhost:<port-base + 7>/%t`). The script starts
  `voltdb/http-receiver.py` there, which appends each request (one row, its columns as form fields) to
  `cluster/http-export.log`, copied into the results' `export/`. The provider gets `--export-target
  sqlancer,sqlancer_http`, and a database's stream exports to one of the two by the database's number: db0 and db2 to
  the file target, db1 and db3 to HTTP. HTTP sends the columns only, so each row carries a column `i`, numbered in the
  run, and the count at the end adds the distinct `i` the receiver got to the file target's distinct lines.
- **Loopback** (`sqlancer_lb`, the custom `LoopbackExportClient`), whose procedure is `XS_LB.insert`. With
  `--loopback-target`, STREAMS also creates the table `XS_LB (d, k, v, s, f)` and, per database, a stream `XS_DB0_LS`
  of the same columns that exports to it, and puts each row into it with the database's prefix in `d`. After each
  check's inserts it waits, up to 60 s, until `XS_LB` holds for that prefix the shadow table's distinct rows: the rows
  come back into the database through the export.

In a 6-minute trial on 3 nodes with k=2 and 2 sites per host, beside NoREC, TLP WHERE and VIEWS, STREAMS inserted 3,400
rows: all were exported, 1,529 of them through HTTP, and 337 loopback checks found every row in `XS_LB`. A STREAMS check
now waits for the export, so the run makes fewer queries.

The nightly job's k=2 export run also kills a node every 300 s (pro's run shape `3:2:exp300:2`), and so does
`run-coverage.sh`'s export run. Measured with it (20 minutes, master bf8602e3), export and streams went from 15.9% of
its lines (milestone 30's seven runs together) to 25.8% from the export run alone, 1,138 of 4,418; its branches from
7.7% to 16.9%. The HTTP target's classes went from 0 to 166 lines and the loopback target's from 0 to 150. The export
gap sessions (`E3ServerGapSession`, `E3ClientGapSession`, 237 lines) still never run: with k=2 every replica has every
row, and a rejoined node never had to fill a gap.

### The JDBC target (milestone 40)

Milestone 33's coverage run still found the JDBC export client (`JDBCExportClient`, 623 lines) never run. So
`--export` adds a third target for the streams, **JDBC** (`sqlancer_jdbc`, `type="jdbc"`), into VoltDB itself: the
script starts a sink, one node of the kit under test (k=0, 2 sites per host, on port slot 19, never killed), and the
target's `jdbcurl` is `jdbc:voltdb://localhost:<its client port>`, with `ignoregenerations` and `skipinternals`, so
that each stream's rows go into a sink table of the stream's name and columns. The provider gets `--export-target
sqlancer,sqlancer_http,sqlancer_jdbc`, so db0 and db3 export to the file target, db1 to HTTP and db2 to JDBC. After
`@Quiesce`, once the sink's counts have not changed for 10 s, each `XS_DB<n>_ST` table's rows and distinct `I` go into
the results' `export/jdbc.csv`, and the count at the end adds the distinct `I` to the file and HTTP targets' rows.

Into VoltDB the target writes rows with `UPSERT`, and the tables it creates have no primary key, so VoltDB refuses
every row; its `CREATE TABLE` also gives a FLOAT column the type `DOUBLE PRECISION`, which VoltDB refuses (ENG-30681
below). So while `--avoid-known-bugs` is true (the provider's default; the script reads `-- --avoid-known-bugs false`),
the script creates the sink's tables beforehand, with `I` as their primary key, and sets `createtable` to false.

Milestone 40 also fixes four things that kept the HTTP and JDBC targets nearly idle in runs that kill nodes, or that
miscounted there:

- With `--node-failures`, the connection reports "object name already exists" as a node failure, so STREAMS' `CREATE`
  of an object made earlier (`XS_LB`, made by another database first) ended the check, and the database name's loopback
  stream was never made: from then on every check of db1, db2 and db3 failed its first loopback insert and left its
  stream out of step. In lab runs with kills, HTTP got 1 row. STREAMS now creates only what the catalog lacks, and
  takes a stream's name only once all of its objects exist.
- A stream out of step (an insert a node failure interrupted) was skipped for the rest of the run. Its database name
  now goes on with a stream of the next generation, `XS_DB2G1_ST` and so on, up to 8; the old one is kept, as dropping
  it may lose rows still to export. The sink has a table for each generation.
- An insert into a stream that failed, or that a node failure interrupted, may still have committed and been exported.
  STREAMS counts these, and those still running when SQLancer exits, as interrupted, and the count at the end allows
  between the inserted rows and those plus the interrupted ones.
- The file target's rows were counted as distinct lines. After a failover the new partition leader exports some rows
  again with its own site id, so the line differs: the rows are now counted by their distinct `I`, like the HTTP and
  JDBC targets'.

In a 5-minute trial on 3 nodes with k=2 and 2 sites per host, beside NoREC, STREAMS inserted 2,653 rows; all were
exported, 729 through HTTP and 656 through JDBC. In an 8-minute one with a node killed every 2 minutes, beside NoREC,
TLP WHERE, VIEWS and TTL, 3,221 rows, 883 through HTTP and 828 through JDBC (before these fixes, 1 and 1).

The bugs below were found in milestone 29.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30630 | XDCR: when one cluster has dropped and recreated a DR table (here replicated, where it was partitioned) and the other then writes rows into its old one, the first pauses the DR stream on the mismatched schema ("Received binary log with schema that doesn't match local catalog. Pausing this DR stream"), and once the other has recreated the table too and the catalogs match, the stream stays paused for good: nothing more replicates in that direction, not even a new table's rows, while DRROLE says ACTIVE. Also on 16.0.0. Found by lab build #43's XDCR run, which stalled after 157 databases. Critical. | A database waits for XDCR to apply what is queued before it drops its name's leftovers (step 4 of [XDCR](#xdcr)). |
| ENG-30629 | When the file export target's output file name is taken, it logs "Output file for next period already exists ... ExportToFileClient will stop to prevent data loss" but does not stop: it retries the block at once, for good, with a stack trace each time (about 15,000 a second), and the block's rows are never exported. Two nodes on one host sharing an `outdir` take each other's names. Also on 16.0.0. Found by lab build #43, whose three nodes wrote 48 to 68 GB of `volt.log` each in 45 minutes, until the disk was full, and exported 26,955 of 28,240 rows. Critical. | Each node exports into its own voltdbroot; server logs over 256 MB are cut and fail the run. |

## The VIEWS oracle

VIEWS (`oracle/VoltDBViewOracle`) checks VoltDB's materialized views, which it keeps up to date as their tables
change rather than computing them again.

**The views.** Each database creates zero to `--max-num-views` views right after its tables, before any rows:
- VoltDB can't create some views on a table that already holds data ("cannot always be applied if the table already
  contains data").
- A view made first is kept up to date through every insert, upsert, update and delete that builds the database.

A view (`gen/VoltDBViewGenerator`) covers one table, or since milestone 32, a third of the time, an inner join of two
or three tables:
- It groups by zero to two non-BOOLEAN columns.
- It always has `COUNT(*)`, plus up to three more of `COUNT`, `SUM`, `MIN` and `MAX` of the table's columns. MIN and
  MAX are never of BOOLEAN columns (ENG-30493).
- While `--avoid-known-bugs` is true, SUM is only of NOT NULL TINYINT, SMALLINT and INTEGER columns (ENG-30584 and
  ENG-30588 below). VoltDB sums those as BIGINT, which can't overflow.
- While `--avoid-known-bugs` is false, half the views of one table have a random WHERE clause. While it is true, none
  do (ENG-30583 below).

A view over a join is kept up to date by different code from a view of one table: VoltDB runs the view's query for
each changed row (`MaterializedViewHandler` in the engine), where a view of one table has triggers on the table.
Milestone 30's coverage run found that code never ran. Such a view:
- Joins distinct tables (VoltDB's views have no self-join) with `JOIN ... ON` (inner joins only; VoltDB rejects outer
  joins in a view).
- Joins at most one partitioned table to replicated ones, or partitioned tables on their partitioning columns and
  nothing else: VoltDB keeps a view of partitioned tables only when every joined row is in one partition.
- Joins a replicated table on a comparison of two columns of the same kind (=, <, <=, >, >= or <>; random rows rarely
  have equal values, and most comparisons let many pairs of rows through), a random predicate over the tables
  joined so far, or both; while `--avoid-known-bugs` is true, on the comparison alone (ENG-30673 below).
- Has a random WHERE clause a third of the time while `--avoid-known-bugs` is false. ENG-30583 is about views of one
  table, and constants, LIKE and BETWEEN in a join view's conditions leave later DDL working; but a random predicate
  can fail on a row (DATEADD out of range, say), and a row whose join-view maintenance fails is not entirely rolled
  back: the next DDL crashes the server (ENG-30673 below; lab build #51's export run, where the 16.0.0 mirror died of
  it too). So while `--avoid-known-bugs` is true, a join view's ON and WHERE clauses hold only column comparisons.
- While `--avoid-known-bugs` is true, groups by columns with distinct names: the view is right, but its query run as a
  SELECT merges groups (ENG-30529 below).
- While `--avoid-known-bugs` is true and DIFF's mirror or an in-service upgrade runs 16.0.0, groups by no DECIMAL
  column: a join view grouped by a NULL DECIMAL aborts a 16.0.0 server (ENG-30255, fixed after 16.0.0; lab build #48's
  mirror died of it). Views of one table do not.
- While `--avoid-known-bugs` is true, a comparison of two columns of different types in the join is = or <>: with an
  ordered comparison, the view's maintenance can read a narrower integer column through its index with a key below
  its range, which matches every row (ENG-30511), and the server crashes (ENG-30665 below).
- While `--avoid-known-bugs` is true, a VARBINARY column in the join is compared only with one of the same size: the
  view's maintenance scans the other table's index with the condition's values, and a value longer than a VARBINARY(n)
  column fails the change ("Type VARCHAR can't be cast as VARBINARY", ENG-12656 below; lab build #49's upgrade run).
- While `--avoid-known-bugs` is true and nodes fail, rejoin, resize, crash or upgrade, groups by at least one column:
  after a rejoin, a join view without GROUP BY holds its one row twice on the rejoined node (ENG-30667 below).
- While `--avoid-known-bugs` is true, an index on a table a view reads is never UNIQUE or ASSUMEUNIQUE: a unique index
  that duplicates the primary key of a join view's source table crashes the server on the next insert (ENG-30664
  below).

While `--avoid-known-bugs` is true, the other oracles' queries never read a table that has a view: VoltDB's view
rewriter fails to plan some of their GROUP BY and aggregate queries on such a table (ENG-30582 below).

The view's defining query is kept with the database's state.

**The check.** It picks a view. Half the time it first runs one more INSERT, UPDATE or DELETE on the view's table (on
one of them, for a view over a join), so the check follows a change made right before it. It then compares three results, as multisets, as TLP compares them:
1. the view's rows, `SELECT * FROM v`;
2. the defining query with `ORDER BY 1`, which VoltDB's view rewriter (`MVQueryRewriter`) leaves alone, so it is
   computed from the tables;
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

## Subqueries and window functions

### Subqueries in expressions

Since milestone 14 the expressions of NoREC, TLP WHERE, DISTINCT, HAVING (its WHERE clause) and AGGREGATE, SUBQUERY
and WINDOW (its WHERE clause) may hold subqueries:

- `EXISTS (SELECT 1 FROM t AS s [WHERE q])`, as a boolean;
- `x [NOT] IN (SELECT e FROM t AS s [WHERE q])` and `x op ANY|ALL (SELECT e ...)`, with x and e of one kind and, as
  for comparisons, one numeric or temporal domain;
- `(SELECT MIN(e) | MAX(e) | COUNT(*) FROM t AS s [WHERE q])`, a scalar subquery, in place of a value.

The WHERE condition q references the outer query's columns (a correlated subquery), except in an IN, ANY or ALL
subquery, and so does the output e, while `--avoid-known-bugs` is false (ENG-30597 and ENG-30601 below); EXISTS
subqueries are correlated either way. A
subquery can hold another, two deep (EXISTS only while `--avoid-known-bugs` is true: ENG-30600). Each reads one table under its own alias (`S0`, `S1`, ...), which may be the outer
query's table too. PQS, PARAMS, VIEWS and LIMIT generate no subqueries, and a generator with subqueries off makes the
same random choices as before milestone 14.

VoltDB limits where subqueries go, and the generator follows (these are rules, not bugs):

- A subquery reads replicated tables only ("Subquery expressions are only supported for single partition procedures
  and AdHoc queries referencing only replicated tables"). The outer query may read partitioned tables.
- No subquery in a HAVING clause ("SQL HAVING clauses with subquery expressions are not allowed"), in an aggregate's
  argument ("SQL Aggregate function calls with subquery expression arguments are not allowed"), or in a window
  function's argument or ORDER BY.
- While `--avoid-known-bugs` is true, a subquery does not read a table with a materialized view (ENG-30582).

### The SUBQUERY oracle

SUBQUERY (`oracle/VoltDBSubqueryOracle`) checks that a query with an IN, ANY or ALL subquery in its WHERE clause
returns the rows of the same query with the subquery written with EXISTS. With c the comparison of x and e (`x = e` for
IN):

| Subquery | Written with EXISTS |
| --- | --- |
| `x IN (SELECT e FROM t AS s WHERE q)`, `x op ANY (...)` | `EXISTS (SELECT 1 FROM t AS s WHERE c AND q)` |
| `x NOT IN (...)` (that is, `x <> ALL`), `x op ALL (...)` | `NOT EXISTS (SELECT 1 FROM t AS s WHERE (c IS NULL OR NOT c) AND q)` |

(c comes before q because of ENG-30601 below.) The two hold, are TRUE, for the same rows, whatever is NULL and whether the subquery is empty; where either is FALSE or
NULL makes no difference to a WHERE clause. The predicate is the whole WHERE clause, or ANDed with a random predicate.
VoltDB plans an IN subquery by turning it into EXISTS itself, moving x into the subquery, so this checks that rewrite.
The query reads the FROM clauses and joins of TLP WHERE, and outputs a random subset of their columns.

### The WINDOW oracle

WINDOW (`oracle/VoltDBWindowOracle`) checks one windowed function against the rows of its own query. The query
outputs the window's PARTITION BY and ORDER BY columns and the function's argument, then the window function:

```sql
SELECT p..., o..., [arg,] f([arg]) OVER ([PARTITION BY p...] [ORDER BY o [DESC]...]) FROM ... [WHERE q]
```

The oracle groups the rows by p, orders each group by o, NULLs first (last with DESC), and works out each row's value
in Java: RANK is 1 plus the rows before it, DENSE_RANK 1 plus the distinct o values before it, and ROW_NUMBER is one
of its peers' numbers (the rows with its o values), which together must be consecutive. COUNT(*), COUNT, SUM, MIN and
MAX cover the rows up to the current row and its peers, or the whole group without an ORDER BY. A FLOAT SUM may differ
in its last digits, as it adds in another order. The query must also return the rows of the same query without the
window function.

- f is RANK, DENSE_RANK, ROW_NUMBER, COUNT, SUM, MIN or MAX: VoltDB supports no other window function, and no frame
  clause.
- p is up to 2 columns of any type but BOOLEAN; o is up to 3 integer or TIMESTAMP columns, which is all VoltDB orders
  a window by ("Windowed function call expressions can have only integer or TIMESTAMP value types in the ORDER BY
  expression of their window"). RANK and DENSE_RANK need an ORDER BY.
- arg is an expression: numeric for SUM, of any kind but BOOLEAN for MIN and MAX (ENG-30493), of any kind for COUNT.
- VoltDB allows one window function per SELECT list, and none with GROUP BY or DISTINCT.

Values are compared as SQL orders them: numbers by value, strings by code point (the order of their UTF-8 bytes),
VARBINARY by unsigned bytes, and TIMESTAMPs in time. A failure reports the first row whose value is wrong, or the rows
that differ from the query without the window function.

## Set operations, derived tables and WITH

### The SETOPS oracle

SETOPS (`oracle/VoltDBSetOperationOracle`) checks UNION, INTERSECT and EXCEPT, each with and without ALL, against the
same operation done in Java on its operands' rows. Each operand is `SELECT c1, ... FROM t [WHERE p]` over one table,
and the query is `A op B` or `(A op B) op C`, sometimes inside `SELECT * FROM (...) AS u`. Rows are compared as
multisets in which NULLs are equal and exact numbers compare by value; UNION, INTERSECT and EXCEPT keep one copy of each
row, UNION ALL adds the counts, INTERSECT ALL keeps the smaller and EXCEPT ALL the difference.

VoltDB's rules, which the oracle follows:

- The columns at each position must have one type: INTEGER with BIGINT, or TINYINT with SMALLINT, is "Incompatible
  data types in UNION" (for INTERSECT and EXCEPT too); an integer literal is BIGINT. VARCHAR and VARBINARY of different
  sizes combine. An operand without a column of a position's type outputs a constant of it, such as
  `CAST(2 AS TINYINT)`.
- At most one operand reads a partitioned table: "Statements are too complex in set operation using multiple
  partitioned tables".

### The DERIVED oracle

DERIVED (`oracle/VoltDBDerivedTableOracle`) has three modes:

- `WRAP`: a query `SELECT f FROM ... WHERE p` returns the rows of `SELECT * FROM (that query) AS d`.
- `PUSH`: it returns the rows of `SELECT d.f' FROM (SELECT t.c AS A0, ... FROM ...) AS d WHERE p'`, which selects every
  FROM column under an alias and reads them outside, so that VoltDB pushes p back into the derived table; or the same as
  `WITH D AS (...) SELECT ...` when the query reads replicated tables only, as VoltDB rejects a common table over a
  partitioned table in a multi-partition query.
- `RECURSIVE`: `WITH RECURSIVE RC (V) AS (SELECT t.c FROM t WHERE p UNION ALL SELECT t.c FROM t, RC WHERE t.c = RC.V +
  1) SELECT V FROM RC`, over an integer column of a replicated table, returns the closure the oracle computes in Java:
  the start values, and from each value every row whose value is one more. A closure of more than 5,000 rows is not
  checked. Since milestone 17 this mode runs only with `--avoid-known-bugs false` (ENG-30608, a node crash).

The JDBC driver runs only statements that start with SELECT as queries (ENG-30603 below), so the connection sends a
query that starts with WITH or a parenthesis through `{call @AdHoc(?)}`.

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
| ENG-30497 | With an outer join, a boolean expression over the inner table, such as `(t.c IS NULL) IS NOT NULL` or `(t.c IS NULL) = TRUE`, turns the join into an inner join: wrong rows. In milestone 7 the nightly lab job found it again through a later join's ON condition: `(t0.b OR t2.b) IS NOT NULL` with `t2` null-extended by an earlier join. In milestone 18 (lab build #35) NoREC found it through a correlated scalar subquery: `1 <> (SELECT COUNT(*) FROM r WHERE t.c > 0)` is TRUE for the null-extended row, as the COUNT is 0. | With an outer join, boolean operands of comparisons, IN and IS NULL are columns or constants, in WHERE clauses and in every explicit join's ON condition, and a scalar subquery is a MIN or MAX, not a COUNT. |
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
| ENG-30529 | A multi-partition GROUP BY or DISTINCT groups by only one of two columns with the same name, such as `t0.c0` and `t1.c0`, merging groups. Found while checking the SQL these oracles generate; TLP HAVING found the form where the grouped columns are not selected. Wrong rows. | Grouping and DISTINCT columns have distinct names, and every output column of such a query has its own alias (`AS a0`, `AS a1`, ...). Since milestone 32 the VIEWS oracle found it again through a view over a join, whose own rows were right; such a view groups by columns with distinct names. |
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
| ENG-30549 | The planner folds a comparison of two string constants ignoring trailing spaces (`'a' = 'a '` is TRUE), while the EE compares exactly. Found by PQS. Wrong result. | PQS does not check a folded string comparison that trailing spaces decide. The left operand of a string IN, ANY or ALL subquery reads a column (milestone 15: SUBQUERY found it, as the EXISTS form of `' ' <> ALL (SELECT '' ...)` folds and the subquery form does not). |

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

The bugs below were found in milestone 14, by subqueries and the SUBQUERY oracle.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30594 | A subquery in the output of an IN or `= ANY` subquery, such as `1 IN (SELECT (SELECT MAX(a) FROM t AS s1) FROM t AS s0)`, crashes the planner: NullPointerException, "this.m_subqueryNode" is null. ALL and EXISTS work. | The output of an IN, ANY or ALL subquery holds no subquery. |
| ENG-30595 | `CHAR_LENGTH((SELECT MAX(s) ...)) IN (SELECT ...)` kills the server (SIGSEGV in `StringRef::getObject`): the string function on the left of an IN or `= ANY` subquery gets an INTEGER instead of the scalar subquery's value. UPPER of it fails with "Type INTEGER can't be cast as VARCHAR". Found by SUBQUERY as a node crash. Critical. | The left operand of an IN, ANY or ALL subquery holds no subquery. |
| ENG-30596 | A bare scalar subquery on the left of an IN list fails in the EE: "Invalid ExpressionType 'COMPARE_IN' called for VectorComparisonExpression". `(SELECT ...) + 0 IN (...)` works. | A numeric one is written `(s + 0)`; a string one is not generated there. |
| ENG-30597 | With GROUP BY and an aggregate, an ALL or IN subquery whose output is a column of the outer query fails: `<> ALL` in the EE ("invalid column type 'INVALID'", or a garbage type such as 'UNKNOWN[-47]'), IN in the planner (IndexOutOfBoundsException in `rewriteInSubqueryAsExists`). Found by TLP DISTINCT. | The output of an IN, ANY or ALL subquery reads only the subquery's own columns. |
| ENG-16927 (comment) | An old bug, open since 2019: any subquery in the string argument of SUBSTRING or TRIM crashes the SQL parser (NullPointerException in `QueryExpression.getColumns`). Other functions of a subquery work. Found by TLP AGGREGATE. | SUBSTRING's string argument has no subquery (and TRIM is already written `LTRIM(RTRIM(x))`). |
| ENG-30497 (comment) | The milestone 2 bug, through subqueries: NOT IN, `<> ALL` and a correlated NOT EXISTS over an outer join's inner table turn the join into an inner join, though each can be TRUE for the null-extended row. `oa LEFT JOIN ob ... WHERE NOT EXISTS (SELECT 1 FROM ob AS s WHERE s.x = ob.x)` loses the unmatched rows. Found by TLP WHERE. Wrong result. | No subquery predicates with an outer join. |

The bugs below were found by milestone 14's oracles in milestone 15's lab runs. ENG-30602 is not reproducible on demand and has no workaround: a multi-partition SELECT once failed with "Column count mismatch. Expecting 3, but 1 given" and a one-column `modified_tuples` table, as if it had received a DML statement's fragment result (once in 175,000 checks). If it recurs, the nightly run fails on it.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30599 | A window's `ORDER BY ... DESC` is applied ascending when an equality on its PARTITION BY column makes the planner use an index on (partition column, order column): `RANK() OVER (PARTITION BY a ORDER BY b DESC) ... WHERE a = 1` ranks the smallest b first. The plan is a forward range scan with no sort; `a >= 1` gets a reverse scan and is right. Found by WINDOW. Wrong result. | A window with a PARTITION BY orders ascending. |
| ENG-30601 | `WHERE t.k < c AND t.k = u.j`, with k indexed (unique or not), drops the upper bound when the join equality becomes the unique index lookup (the plan's `uniquely match` has no filter); `t.k = u.j AND t.k < c` keeps it. Found by SUBQUERY, as the EXISTS form of an IN subquery on a primary key. Wrong result. | In the SUBQUERY oracle's EXISTS form, the comparison comes before the subquery's WHERE condition, and the WHERE condition of an IN, ANY or ALL subquery is not correlated (a non-unique index and an equality in that condition did it too: comment on the ticket). Other queries can still meet it. |
| ENG-30600 | An IN or `= ANY` subquery inside another subquery, whose left operand is a column of the outermost query, holds for every row: `A WHERE EXISTS (SELECT 1 FROM B AS S1 WHERE A.k IN (SELECT S2.k FROM B AS S2))` returns all of A, NULLs included. NOT IN and the same written with EXISTS are right. Found by SUBQUERY. Wrong result. | A subquery inside a subquery is EXISTS. |

The bugs below were found in milestone 16.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30604 | A set operation inside a derived table, with an operand pinned to one partition (`WHERE k = 1` on the partitioning column), returns the replicated operands' rows once per site: `SELECT * FROM (SELECT k FROM SP WHERE k = 1 UNION ALL SELECT k FROM SR) AS u` has 17 rows instead of 3 on 8 sites, and UNION 16 instead of 2. Found by SETOPS. Wrong result. | A set operation that reads a partitioned table is not wrapped in a derived table. |
| ENG-30605 | String literals at one position of a set operation are padded to the longest: `'a' UNION ALL 'abc'` gives `'a  '`, and `''` against `'a'` gives `' '`. A VARCHAR column on either side is right. Found by SETOPS. Wrong result. | String and VARBINARY constants in set operations have one character or byte. |
| ENG-30607 | A set operation inside a derived table, `SELECT * FROM (A UNION B) AS u`, fails with "The size 17 of the value '...' exceeds the size of the VARCHAR(16) column" when A's column is VARCHAR(16) or wider and a value from B is longer; the bare set operation, and A of VARCHAR(15) or narrower, are right. Found by SETOPS in lab build #28. Wrong error. | A set operation with a VARCHAR or VARBINARY output is not wrapped in a derived table. |
| ENG-30603 (JDBC) | The driver takes a statement for a query only if it starts with SELECT: `executeQuery` rejects `WITH ... SELECT` and `(SELECT ...) EXCEPT ...` ("Cannot submit statement in current context"), and `execute` returns their first value as an update count. Filed under ENG-30559 (JDBC). | The connection sends such queries through `{call @AdHoc(?)}`. |

The bugs below were found in milestone 17.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30606 | `CREATE PROCEDURE p AS ...` turns a newline inside a string literal into a space, so `UPPER('<newline>')` returns a space from the procedure and a newline ad hoc; a tab is kept. ENG-4997 (fixed in V3.5) was the same for `voltdb compile`. Found by PROCEDURE. Wrong result. | In the PROCEDURE oracle a string constant with a line break is always a parameter, and a query that keeps one in its text is skipped. |
| ENG-30602 (seen again) | A WINDOW query on 1 node read a one-column result ("Column index out of range: '2' (Column count: 1)") while the PROCEDURE oracle's catalog updates ran beside it; the query was not logged, which it now is. | None. |
| ENG-30610 | A join with a constant ON condition (`ON 1 = 1`) is not auto-parameterized, and with `WHERE p.c = x'..'` on a VARBINARY partitioning column it runs single-partition on the wrong partition: `SELECT VB.c0 FROM VB JOIN R ON 1 = 1 WHERE VB.c0 = x'03'` returns no row (with `ON TRUE`, or a parameter, it is right). Found by PROCEDURE's PARAMS check on 3 nodes. Wrong result. | No table is partitioned on a VARBINARY column. |
| ENG-30608 | Intermittent node crash (SIGILL): a site thread recursed without end through `SeqScanExecutor::p_execute`, `ExecutorContext::getCommonTable` and `executeExecutors` while the DERIVED oracle's `WITH RECURSIVE` query ran, beside other threads' catalog updates: twice in three 20-minute 1-node trials with PROCEDURE, never in the lab without it, and not reproducible on demand. | DERIVED's RECURSIVE mode is off. |
| ENG-30609 | `SELECT COUNT(*) FROM rc`, rc a recursive common table, fails to plan: "TableCountPlanNode cannot be cast to SeqScanPlanNode". Found reducing ENG-30608. | DERIVED selects the common table's column, not `COUNT(*)`. |

The bugs below were found in milestone 18.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30612 | A node killed while an ad hoc DDL's @UpdateCore is in flight: a surviving host reclaims the dead host's catalog-update blocker before the interrupted @UpdateCore is resolved on the leaders, the next @UpdateCore queues behind it on five leaders, and the MPI deadlocks; every later DDL is "in progress on host 2" and the node's rejoin times out after 600 s. Found by the nightly run with node failures (lab builds #32 and #33). Critical. | SCHEMA and PROCEDURE do nothing while nodes fail. |
| ENG-30614 | An outer join whose ON condition is, or folds to, FALSE returns no rows at all, not the preserved rows null-extended: `A LEFT JOIN B ON 1 = 0` returns nothing (the plan's `filter by false` is applied to the join's output). Also on 16.0.0. Found by PARAMS on 1 node (lab build #34), where the literal form of the ON condition folded to FALSE and the parameterized one did not. Wrong result. | No AND, OR or NOT in an ON condition has a column-free operand, so nothing in it folds to a constant. |
| ENG-30602 (seen again) | Three times in lab builds #31 and #32, 1 node: a SUBQUERY EXISTS query returned one row holding 0, another returned no rows where 4 were right, and a two-column query read a one-column result. Rerunning the same statements returned the right rows each time. The rate rises with the schema changes of PROCEDURE and SCHEMA. | A failure that does not recur when rerun at once is counted, not failed (`VoltDBTransientCheck`). |

The bugs below were found in milestone 19.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30510 (comment) | The RIGHT JOIN bug through a FULL JOIN: a WHERE clause that rejects the right table's NULLs turns `a FULL JOIN b ON c >= b.k` into a RIGHT JOIN and then a LEFT JOIN that uses the ON condition as b's index range, dropping b's row. Found by TLP WHERE. Wrong result. | A FULL JOIN's ON condition reads the left table only. |
| ENG-30611 | `DATEADD(YEAR, n, d)` on a DATE February 29 into a non-leap year: with a DATE literal the server aborts (uncaught `boost::gregorian::bad_day_of_month`), with a column it is "Invalid date 'February 29' as '2025' is not a leap year"; a TIMESTAMP and MONTH 12 give February 28. Found by PARAMS, crash found probing it. Critical. | DATEADD has no YEAR unit. |

The bugs below were found in milestone 21.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30622 | An elastic remove hangs for good at "Building index for data migration" when a table is dropped while it streams its index snapshot: the hosts being removed log "No table streamer of Type TABLE_STREAM_ELASTIC_INDEX" and "Snapshot of table ... failed to complete", and `voltadmin resize --status` stays at "Planning data migration". Also on 16.0.0. Found by a local `--resize-interval` trial, where SQLancer's cleanup of an old database dropped its tables during a shrink; a script reproduces it in a few cycles. Critical. | DDL waits during each shrink (`--ddl-hold`). |
| ENG-30229 (existing) | System requests time out for 60 seconds during an elastic join, as during a rejoin, and the JDBC metadata calls with them. | The connection retries them for up to 150 seconds. |

The bugs below were found in milestone 26.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30626 | `MOD(x, c + 1)` inside LN, SQRT or EXP fails with "unsupported non-integral or non-decimal type for SQL MOD function": the literal, made a parameter, seems to take FLOAT from the outer function. `MOD(20, c)`, `MOD(20, c + c)` and `ABS(MOD(20, c + 0))` are right. Also on 16.0.0. Found by PLANS, which writes `(c + 0)`. | PLANS leaves a WHERE clause with MOD as it is. |

The bugs below were found in milestone 29's lab run of the milestones before it.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30628 | A LEFT JOIN of a partitioned table P whose WHERE clause compares a scalar subquery with P's primary key (its partitioning column) fails to plan: "SQL error while compiling query: java.lang.IllegalStateException" (`SubPlanAssembler.buildIndexAccessPlanForTable`). The inner join, and the WHERE clause on P alone, plan. Also on 16.0.0. Found by TLP AGGREGATE in lab build #41 (the upgrade run, after the upgrade). | No scalar subqueries in a query with an outer join. |

The bugs below were found in lab build #53 and in milestone 41.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30682 | A node that joins elastically while `@SetYamlConfiguration` changes are made misses some of them for good: afterwards it reports, through `@GetYamlConfiguration`, older values of some system settings than the rest of the cluster (in the reproduction, `elastic.throughput` 1 and `flushinterval.minimum` 2000 where the others have 2 and 500). 16.0.0 too. Found by CONFIG in lab build #53's resize run; `repros/ENG-30682.sh` adds 2 nodes to 3 while toggling three settings and compares each node's. Critical. | While `--avoid-known-bugs` is true, CONFIG checks nothing in runs that resize |
| ENG-30683 | During an in-service upgrade from 16.0.0, `@SetYamlConfiguration` through a node already upgraded to 16.1 fails with UNEXPECTED_FAILURE, "The UpdateDeployment system procedure requires exactly two parameters, the deployment as a string, and an operation mode, 'full' or 'sectional'"; through the 16.0.0 nodes it succeeds. Found by CONFIG in lab build #53's upgrade run; `repros/ENG-30683.sh` (manual: it needs a 16.0.0 kit as `OLD_VOLTDB_HOME`) upgrades one of 3 nodes and calls it through each. Major. | While `--avoid-known-bugs` is true, CONFIG checks nothing in runs that upgrade |

The bug below was found in milestone 40.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30681 | JDBC export to VoltDB with `createtable` (its default) delivers no rows: the tables it creates have no primary key, but it writes rows with `UPSERT` ("Unsupported UPSERT table without primary key"), and for a FLOAT column its `CREATE TABLE` uses `DOUBLE PRECISION` ("type not found or user lacks privilege: DOUBLE"). The export retries each block for good, and a graceful shutdown stops with the cluster paused. 16.0.0 too. Found by the JDBC target's first trial; `repros/ENG-30681.sh` exports a stream with a FLOAT column and one without from one 1-node cluster to another and finds neither row. Hang, Blocker for V16.1.0. | While `--avoid-known-bugs` is true, the script creates the sink's tables beforehand, with a primary key, and sets `createtable` to false |

The bug below was found in milestone 39.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30680 | A task on the database (`DELAY 20 MILLISECONDS`, a multi-partition procedure, `ON ERROR LOG`) runs in two scheduling chains, about twice as often as its schedule allows, after the node of the multi-partition initiator's leader is killed while one of its calls is in flight; it lasts until the task is disabled or restarted. `@Statistics TASK` counts only one chain, and its `PROCEDURE_*` times go to about ±2.4e18. 16.0.0 too. Found by TASK in a 3-node trial with kills; `repros/ENG-30680.sh` kills the MP leader's node once and finds half the gaps between the task's rows under 20 ms. Extra writes, Blocker for V16.1.0. | While `--avoid-known-bugs` is true and nodes fail, resize, crash or upgrade, TASK allows twice the runs |

The bug below was found in milestone 38.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30679 | XDCR silently diverges when a DELETE of a key on one cluster races a write of it (insert, update, upsert) on the other: e.g. `A: INSERT (0, 'a0', 3)`, `B: UPSERT (0, 'b1', 2)`, `B: DELETE k = 0` (twice), sent without waiting for replication, leave cluster A without the row and cluster B with A's, and every conflict in the log says consistent (`C`). Other forms leave each cluster with the other's value. 16.0.0 too. Found by XCONFLICT; `repros/ENG-30679.sh` starts an XDCR pair and replays the sequence with `repros/xdcr/XdcrRepro.java`. Wrong result, Blocker for V16.1.0. | While `--avoid-known-bugs` is true, XCONFLICT counts a key left different with no divergence logged rather than failing (until lab build #53 its checks deleted nothing, but in the lab, on x86, inserts racing upserts diverge silently too) |

The bugs below were found in milestone 37.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30678 | After a node has been killed and has rejoined (node 0 in the reproduction), `@SnapshotRestore` with a duplicates path leaves the duplicate rows out of the table, as it should, but writes none of them to the duplicates files, and no host logs finding them. 16.0.0 too. Found by SNAPSHOT in a 3-node trial with kills; `repros/ENG-30678.sh` restores a replicated table into itself between kills and finds the files empty after the first host has rejoined. Major. | While `--avoid-known-bugs` is true and nodes fail, resize, crash or upgrade, SNAPSHOT checks the restored table but does not count the duplicates files |
| ENG-30677 | Writes to the replicated table of a view over a join of a replicated and a (empty) partitioned table crash the server (SIGSEGV in `ReplicatedMaterializedViewHandler`'s maintenance scan, on a stale table) while a `@SnapshotRestore` of another table and DDL on others run at once; either alone does not. 16.0.0 too. Found by SNAPSHOT in a 3-node trial (all three nodes at once, 13 s in); `repros/ENG-30677.sh` (a stress script) crashes it in seconds to minutes. Server crash, Blocker for V16.1.0. | While `--avoid-known-bugs` is true, no DDL of SQLancer runs during a restore: DDL holds a lock shared, a restore exclusive |
| ENG-30676 | `@SnapshotRestore` of a table while a node rejoins wedges the cluster for good: a site waits in `SnapshotRestore.executePlanFragment` (`SiteMailbox.recvBlocking`) for table data that never comes, the MP site waits for it, nothing else runs on those sites, and the rejoin never completes. 16.0.0 too. Found by SNAPSHOT in a 3-node trial with node kills; `repros/ENG-30676.sh` (two clients restoring a table while a node is killed and rejoined) wedges the cluster at the first kill. Hang, Blocker for V16.1.0. | While `--avoid-known-bugs` is true, a restore waits while `--ddl-hold`'s file exists (from before each kill until the node has rejoined) |

Milestone 37 also met the open ENG-15392: a `@SnapshotRestore` parameter that is not JSON makes the server
close the client's connection, and JSON without `path` fails with an internal `JSONException` and its stack trace.
The oracle always sends complete JSON.

The bug below was found in milestone 35.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30674 | `@ValidatePartitioning` passes a non-NULL hash configuration from the client straight to the engine, which parses it without checking: `exec @ValidatePartitioning 'AB';` (or an empty byte array through JDBC) crashes the server with SIGSEGV in `ElasticHashinator::newInstance`. 16.0.0 too. Found while adding the LOAD and SWAP oracles' validation. `repros/ENG-30674.sql`. | The oracles pass NULL, which means the current configuration |

The bugs below were found in milestone 33.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30673 | A view over a join whose ON clause can fail on a row (`ON DATEADD(QUARTER, t0.a, t1.d) IS NOT NULL`): an INSERT of a row out of DATEADD's range fails, as it should, but the row stays visible to the view's maintenance (a later UPDATE of the other table fails on it), and the next DDL, whatever it is, aborts the server while rebuilding the tables ("getStats() called with ... an invalid locator"). 16.0.0 too. Lab build #51's export run: all three nodes and the mirror died at one catalog update. `repros/ENG-30673.sql`. | While `--avoid-known-bugs` is true, a join view's ON and WHERE clauses hold only column comparisons |
| ENG-30667 | After a node rejoins, a view without GROUP BY over a join of replicated tables (`SELECT COUNT(*), MIN(b.d) FROM b JOIN a ON ...`) returns its one row twice on about one read in three, through any node: the rejoined node's copy holds the row twice. A view without GROUP BY of one table stays right. Also on 16.0.0. Found by VIEWS in a local trial with export and a node killed every 60 s; a script reproduces it at every run (`voltdb/repros/ENG-30667.sh`). Wrong result. | While nodes fail, rejoin, resize, crash or upgrade, a join view groups by at least one column. |

The bugs below were found in milestone 32's lab runs (builds #48 and #49).

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30664 | A materialized view over a join of T with a partitioned table; T, holding a row, gets a UNIQUE index on its primary key's column: the next INSERT into T kills the server with "Could not find Index with name I among {VOLTDB_AUTOGEN_IDX_PK_T_A}" (`persistenttable.cpp`), as if the engine kept one of the two identical indexes while the view's maintenance plan names the other. A non-unique index, an empty T, no view, a view of one table or two replicated tables do not crash. Also on 16.0.0. Found by the security run, whose server died 12 minutes in. Server crash. | An index on a table a view reads is not unique. |
| ENG-30665 | A view over a join of a partitioned table T0 and a replicated T1 on `T0.tinyint_col < T1.int_col`, with an index on the TINYINT column created after the view: when a T1 value is below TINYINT's range, the view's maintenance reads T0 through the index with that key, which matches every row (ENG-30511), and the next change kills the server: "MaterializedViewHandler for table V went looking for a tuple in the view and expected to find it but didn't". Also on 16.0.0. Found by the resize run (lab build #49), where an UPDATE of the replicated table killed two nodes at once and the cluster with them. Server crash. | Columns of different types in a join view's condition are compared with = or <> only. |
| ENG-30255 (existing, fixed after 16.0.0) | A view over a join grouped by a DECIMAL column that is NULL aborts a 16.0.0 server ("Type DECIMAL with value -1701...728 can't be cast as BIGINT", the NULL DECIMAL); master is fixed. The mirror of the kill run died of it 75 s in. | With a 16.0.0 mirror or upgrade, join views do not group by DECIMAL. |

The bug below was found in milestone 31's lab run (build #46).

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30663 | A node killed while catalog updates are in flight, as in ENG-30612, but here a *surviving* node dies and, at k=1, the cluster is lost: in the lab the MPI restarted the interrupted @UpdateCore while the next DDL's update was prepared on a survivor, whose sites then failed with "Unexpected: @UpdateCore's prepared catalog is null during non-replay case"; locally a survivor aborted in the EE with "getStats() called with selector 0, and an invalid locator 455 that does not correspond to a table". A script (`-- manual:`, a race) reproduced it in 1 of 3 local runs. Critical. | DDL waits from 3 s before each kill until the survivors have handled the failure (`--ddl-hold`). |

The bugs below were found in milestone 28.

| Ticket | Issue | Workaround |
| --- | --- | --- |
| ENG-30627 | Ad hoc DDL from a user without ADMIN is compiled before the permission check: `DROP TABLE T` by a reader, with a procedure reading T, fails with "[SECRET_P]: Failed to plan for statement ... SELECT COUNT(*) FROM T WHERE a = 42 ... object not found: T", disclosing the procedure and its SQL; DDL that compiles gets the permission error. While another catalog update runs, the refusal is "another one is in progress". Also on 16.0.0. Found by PERMS. Major. | PERMS's refused DDL is `CREATE TABLE` of a new table, which always compiles; "in progress" is retried. |

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
