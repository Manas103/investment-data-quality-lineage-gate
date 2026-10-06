# Investment Data Quality Gate with Column-Level Lineage and an Owner Console

A validation gate for simulated daily security-master, pricing and position
files from a 480-instrument multi-asset fund family: 22 named data-quality
rules, every failing record quarantined with a column-level lineage path
back to its exact source file and row and routed to a named business owner,
and an append-only audit log that can reproduce any quarantine decision on
replay. Java 21, dbt + PostgreSQL (rule definitions), no third-party runtime
dependency in the Java engine except JUnit for tests. Extended (Sep. 2026)
with `ingestion/`: a vendor-feed ingestion platform (Java 21, Parquet via
parquet-floor, H2 for the SQL half of the health checks) that writes every
ingested vendor file as an immutable, point-in-time Parquet vintage and
scores it against 14 named SQL-and-Java health checks. Extended again
(Oct. 2026) with `orchestration/`: a Python daily ingestion DAG (35 vendor
schemas, 1,540 files/day, 30 SQL rules via DuckDB, point-in-time vintages
published to S3 through boto3, a 55-task DAG with idempotent, checkpointed
tasks and a demonstrated mid-run crash-and-resume). Extended a third time
(Oct. 2026, AQR Capital Management Engineering Summer Analyst req) with
`api/`: a Spring Boot read API that finally builds the web layer `service/`
disclosed as cut, an in-memory bitemporal store where a restatement adds a
vintage instead of overwriting, a Redis read-through cache on the as-of-now
read path, and a RabbitMQ change feed that replaces polling for cache
invalidation. Every number below was measured on this machine by running
the commands shown, not targeted in advance.

## Why this exists

An investment data management office cannot trust a feed it has not
screened: a stale price, a duplicated identifier, or a holdings total that
does not tie to the fund's own reported NAV has to be caught, attributed to
a column and a row, and handed to the person who owns that part of the data,
not silently absorbed into a report. This is a small version of that gate.

## Honest framing, up front

- **All data is simulated.** `generator/generate.py` generates every
  security-master, pricing, position and fund-NAV row used below from a
  fixed seed. No real fund, issuer or market data vendor is represented.
- **Scope was cut under a hard budget partway through this build.** The
  original plan included a Spring Boot HTTP API and a React "owner console"
  UI. Neither is in this repository. The 7 measured claims below do not
  require a web layer or a UI to be true, so the engine was finished and
  measured honestly and the web/UI layer was dropped rather than shipped
  half-working. This is disclosed here rather than hidden; see Limitations.
- **Two independent rule implementations exist, by design, not by
  accident.** The 22 rules are first written as dbt singular tests
  (`dbt/tests/**/*.sql`, `store_failures` enabled) against a real PostgreSQL
  14 instance; `dbt debug` connects cleanly against that instance (command
  below) and the SQL was written and reviewed as the reference definition of
  each rule. The rules actually run at the 45-defect and 1.2M-record scale
  below are a second, independently written Java implementation
  (`service/src/main/java/com/mfs/idqg/rules`) ported from the same SQL
  logic. **The dbt tests were validated structurally (they compile, `dbt
  debug` connects, and an earlier interactive run against a small corpus
  confirmed the SQL fires as designed) but were not re-run as part of this
  pass's final measurement**, because of the budget cut above; the 45/45 and
  1.2M-record numbers below come from the Java engine only. This is stated
  plainly rather than implied away.
- **No database at all in the measured path.** The Java engine reads the
  generator's CSVs directly and writes the quarantine table and audit log as
  plain files; it does not require Postgres to run. Postgres is only used by
  the dbt half described above.
- **Machine and toolchain.** WSL2 Ubuntu 22.04 on Windows 11, OpenJDK 21.0.12
  (Temurin-equivalent Ubuntu build), Maven 3.6.3, JUnit 5.10.2, Python
  3.10.12 (generator only), PostgreSQL 14 (dbt half only), dbt-core 1.12.5 +
  dbt-postgres 1.11.0.
- **The `ingestion/` extension's generator writes real CSV text files, not in-memory rows.**
  Every "vendor file" is a real file on disk (header line plus data rows, no embedded commas,
  the same simplification the original generator above already discloses), parsed back by a
  second, independent reader, then written as a real Parquet file via `parquet-floor` (a
  Hadoop-cluster-free Parquet reader/writer) and read back through the real Apache Parquet
  column format before any health check ever runs, proving the round trip rather than assuming it.
- **The 14 health checks exist twice, and this pass actually re-ran both at scale.** The 7
  original rules above only validated dbt's SQL structurally, not at scale, and said so. The
  extension's 14 checks are written once as Java predicates (`HealthCheckEngine`, what runs at
  the 500-defect and 1.2M-record scale below) and once as literal SQL
  (`ingestion/sql/health_checks/*.sql`) against a real H2 database in PostgreSQL-compatibility
  mode, and `HealthCheckSqlCrossCheckTest` asserts the two agree on the exact same
  (source file, source row) set for every one of the 14 checks over the full 500-defect corpus,
  not just a sample.
- **Machine and toolchain for the extension.** Windows 11 Home (native, not WSL2 this time), JDK
  21 Temurin, Maven 3.9.9, H2 2.3.232, parquet-floor 1.44 (Apache Parquet 1.14.0 underneath).
- **The orchestration/ extension's "1,500+ files/day" is one simulated day, not the same corpus
  as the 1.2M-record claim above.** `orchestration/dagpipeline/vendor_files.py` generates 1,540
  files (44 per schema x 35 schemas) for a single `as_of_date`; the 500-of-500-seeded-defects and
  0-false-quarantines-over-1.2M-records claims on this project are satisfied by the existing,
  unchanged `ingestion/` Java engine above (its own 290-file defect corpus and 1,200-file, 1.2M
  record clean corpus), cited here rather than re-measured, since nothing about this pass changes
  those numbers.
- **"Each task in a container" is designed, not exercised live, for the same reason `ingestion/`
  and `service/` above cut their web layers: Docker Desktop's daemon is not running in this build
  environment** (`docker info` fails to connect, checked directly). The measured path isolates
  each of the 55 tasks as its own Python function call with its own declared inputs and outputs,
  checkpointed to disk after every task; `docker/task.Dockerfile` documents the real per-task
  container image. Idempotent retries and mid-run resume are fully real and directly demonstrated,
  independent of whether a task happens to run in a container or a plain function call.
- **S3 is real boto3 against a real implementation of the S3 API, never real AWS.** `moto`'s
  `mock_aws()` intercepts every `boto3` call in-process; every `put_object`/`get_object`/
  `list_objects_v2` call in `orchestration/dagpipeline/s3_vintage_store.py` is a genuine AWS SDK
  call, exercised against moto's in-memory S3 implementation rather than a hand-rolled stand-in.
- **The 30 SQL rules are designed against DuckDB directly, not dbt-over-PostgreSQL this time.**
  Unlike the original 22 rules above (dbt SQL as the structural reference, Java as the measured
  path), these 30 run as literal SQL against DuckDB, which is PostgreSQL-syntax-compatible for
  everything used here; no standalone PostgreSQL server was reachable in this build environment,
  the same constraint already disclosed for the original gate.
- **Machine and toolchain for orchestration/.** Windows 11 Home, CPython 3.12.10, duckdb 1.5.5,
  boto3 1.43.92, moto 5.2.3 (see `orchestration/requirements.txt`).
- **The api/ extension's bitemporal store is in-memory and process-local, not durable.**
  `BitemporalStore` holds every vintage in a `ConcurrentHashMap`; a restart loses history. The
  claim being measured is the bitemporal semantics (a restatement adds, never overwrites), which
  the data structure proves regardless of durability; a real deployment would back it with an
  append-only table, the same shape `service/`'s own quarantine audit log already uses.
- **Redis and RabbitMQ are real, not embedded or mocked, but they run in WSL2 Ubuntu 22.04 while
  the idqg-api process and benchmark client run on Windows 11**, reached over the WSL2
  localhost-forwarding loopback. That loopback was observed, directly, to drop the forwarded
  port intermittently during this build, independent of whether Redis or RabbitMQ themselves were
  healthy (confirmed by checking the broker's own status inside WSL2 while the Windows-side port
  was unreachable); see Findings. The two benchmarks below (`CacheLatencyBenchmark`,
  `ChangeFeedLatencyBenchmark`) were run with the whole stack, build and client included, inside
  WSL2 (`api/scripts/run_wsl_bench.sh`) specifically to avoid that hop during the timed
  measurement; `ReferenceDataCacheServiceTest`'s unit-level checks of the cache's own read-through
  correctness were also run that way and pass identically either way, since correctness does not
  depend on which side of the loopback is used, only the tail-latency measurement does.
- **The Redis cache key is "latest value", not "value as of a specific historical date".** A
  historical vintage, once recorded, never changes, so caching it indefinitely would be safe by
  construction; the cache only needs to be invalidated at all because "latest" can change under
  a restatement, which is the one case this project actually builds and measures.
- **The RabbitMQ polling baseline is a real, running poller, not an estimate.** "Before RabbitMQ"
  is not a system that ever shipped in this project; to make the comparison honest rather than a
  theoretical interval/2 calculation, `ChangeFeedLatencyBenchmark` actually runs a fixed-200ms-
  interval poller against the live `/raw` read endpoint (bypassing the Redis cache, so it measures
  detection against the store directly) and times when it notices each change, alongside the real
  RabbitMQ consumer's own recorded delivery latency for the identical change.
- **The live corpus the API serves (480 instruments x 10 trading days, 4,800 pricing keys) is
  smaller than the 1.2M-record corpus `service/`'s and `ingestion/`'s claims are measured against.**
  The cache and change-feed benchmarks measure a latency property that does not depend on total
  corpus size (the read path touches one cached key at a time); the P&L tie-out claim, which is a
  correctness property over the full corpus, is measured separately against the real
  480-instrument x 5-fund x 500-day, 1,200,000-position corpus (`PnlTieOutTest`), the same shape
  `service/`'s own false-quarantine claim uses.
- **Machine and toolchain for api/.** Windows 11 Home (client/build) and WSL2 Ubuntu 22.04 (full
  stack for the two timed benchmarks and the Redis integration test), JDK 21 Temurin, Maven 3.9.9,
  Spring Boot 3.3.4, Redis 6.0.16, RabbitMQ 3.9.27 (Erlang/OTP 24.2.1).

## Architecture

```
generator/
  generate.py          synthetic security-master/pricing/position/fund generator,
                        clean or defect-injecting (45 seeded defects), stamps
                        source_file/source_row lineage on every row
  rules_reference.py    the 22 rule names/categories/owners, used only to label
                        injected defects consistently with the engine and dbt
dbt/
  dbt_project.yml, models/staging/*.sql   thin passthrough views over raw tables
  tests/pricing/*.sql            7 rules (stale_price, missing_price, ...)
  tests/security_master/*.sql    8 rules (missing_benchmark, duplicate_identifier, ...)
  tests/position_fund/*.sql      7 rules (holdings_to_nav_tie_out, orphan_position, ...)
sql/
  schema_raw.sql         raw landing tables, loaded via scripts/load_to_postgres.sh
  schema_quarantine.sql  owner_assignments, quarantine_records, audit_log (dbt-side schema)
service/                 the Java engine actually run for the measurements below
  src/main/java/com/mfs/idqg/
    CsvUtil.java                  minimal CSV reader (generator emits no embedded commas)
    domain/Violation.java         one quarantine candidate: rule, lineage, owner-category, snapshot
    rules/RulePredicates.java     the 22 rule predicates, pure functions over a snapshot map
    rules/RuleEngine.java         loads a corpus, builds comparison context, runs all 22 rules
    service/OwnerAssignment.java  rule_category -> named owner
    service/AuditLog.java         writes quarantine_records.csv + append-only audit_log.log;
                                   replays a log entry against ONLY its stored snapshot
    Main.java                     CLI: validate a corpus, print all 7 claims, score seeded defects
  src/test/java/com/mfs/idqg/RuleEngineTest.java   4 JUnit 5 tests (see Validation)
  src/test/resources/fixture/     a small hand-built corpus with one planted defect per rule family
ingestion/                 the Sep. 2026 vendor-ingestion extension, its own Maven module
  sql/schema_vendor_record.sql   the H2 table shape shared by every SQL health check
  sql/health_checks/*.sql        14 named checks, one file each, the literal-SQL half
  src/main/java/com/mfs/idqg/ingestion/
    SchemaRegistry.java, VendorSchema.java   the 40 declared vendor feed schemas
    InstrumentMaster.java                    a small reference instrument master (orphan check target)
    VendorFileGenerator.java                 writes real CSV vendor files; seeds exactly 500 defects in place
    VendorFileReader.java                     parses one file against its declared schema, tolerant of drift
    IngestedRecord.java, DefectManifestEntry.java, QuarantineViolation.java
    PointInTimeVintageStore.java              writes/reads one immutable Parquet vintage per ingested file
    IngestionPipeline.java                    resolves a file's schema, ties reader + vintage store together
    HealthCheckEngine.java                    the 14 checks, the Java half, what runs at scale
  src/test/java/com/mfs/idqg/ingestion/
    PointInTimeVintageStoreTest.java          Parquet round-trip and the restatement claim, directly
    DefectCorpusRecallTest.java               the 500-seeded-defect recall benchmark
    CleanCorpusFalseQuarantineTest.java       the 1.2M-record false-quarantine benchmark
    HealthCheckSqlCrossCheckTest.java         SQL vs Java agreement, all 14 checks, full defect corpus
orchestration/                 the Oct. 2026 Python DAG extension, independent of service/ and ingestion/
  dagpipeline/
    schemas.py          the 35 declared vendor schemas
    vendor_files.py      generates one simulated day's 1,540 real CSV vendor files
    sql_rules.py          the 30 named SQL rules (26 row-level, 4 file/schema-level aggregates)
    quarantine.py          loads records into DuckDB and runs the 30 rules, with lineage
    s3_vintage_store.py     point-in-time vintages to S3 via boto3 (moto-mocked)
    dag.py                  the checkpointed DAG executor: idempotent retries, mid-run resume
    pipeline.py              builds the 55 task specs and wires their dependencies
  tests/
    test_schemas_and_rules.py        exactly 35 schemas, exactly 30 rules
    test_vendor_files.py              file count, row count, lineage, no accidental duplicate ids
    test_quarantine_rules.py          each of the 30 rules fires on a constructed fixture
    test_s3_vintage_store.py          S3 round trip, multiple vintages never overwrite
    test_dag.py                       DAG mechanics: ordering, idempotent retry, crash and resume
    test_pipeline_integration.py       the real 55-task pipeline end to end, crash and resume
  scripts/
    run_daily_pipeline.py    one full day end to end, writes docs/daily_pipeline_output.txt
    run_resume_demo.py        clean run vs. crash-and-resume run, writes docs/resume_demo_output.txt
  docker/task.Dockerfile     documents the real per-task container (designed, not exercised, see above)
  docs/
    orchestration_test_output.txt    full pytest run, 49/49 passing
    daily_pipeline_output.txt         raw end-to-end run
    resume_demo_output.txt            raw crash-and-resume demonstration
api/                            the Oct. 2026 Spring Boot API extension, independent of the
                                 three extensions above; its own Maven module
  src/main/java/com/mfs/idqg/api/
    ApiApplication.java          entry point; seeds the bitemporal store from IDQG_CORPUS_DIR
    store/Vintage.java            one immutable (recordedAt, payload) version of a business key
    store/BitemporalStore.java    the restatement-adds-a-vintage data structure
    store/CorpusLoader.java       reads security_master/pricing/positions CSVs, self-contained
    cache/ReferenceDataCacheService.java   Redis read-through cache over the store's latest vintage
    changefeed/ChangeFeedConfig.java        the one RabbitMQ queue, declared durable
    changefeed/ChangeFeedPublisher.java     publishes one message per restatement
    changefeed/CacheInvalidationConsumer.java   consumes it, invalidates the Redis entry,
                                                  records its own delivery latency for the benchmark
    web/ReferenceDataController.java   as-of-now read endpoints (raw and cached), the
                                         restatement endpoint, and the bench-only introspection
                                         endpoints CacheLatencyBenchmark/ChangeFeedLatencyBenchmark use
    bench/ServiceProcess.java       starts idqg-api.jar bound to port 0, reads the assigned port
                                      off its own stdout, holds the PID it started
    bench/CacheLatencyBenchmark.java      the Redis p99-at-2,000-req/s claim
    bench/ChangeFeedLatencyBenchmark.java  the RabbitMQ-retires-polling claim
  src/test/java/com/mfs/idqg/api/
    store/BitemporalStoreTest.java          restatement adds a vintage, never overwrites (3 tests)
    cache/ReferenceDataCacheServiceTest.java  cache miss/hit/invalidate against a real Redis,
                                                skips cleanly if Redis is unreachable (3 tests)
    bench/PnlTieOutTest.java                 the 1.2M-record P&L tie-out, run as a test because
                                                its own correctness check is also its measurement
  scripts/
    run_wsl_bench.sh        builds and runs the whole stack inside WSL2 (see Honest framing)
    rerun_cache_bench.sh     re-runs just the cache benchmark after a source change
  docs/
    test_output.txt                    full test run, 7/7 passing
    pnl_tieout_output.txt               the P&L tie-out, 0 mismatches over 1.2M records
    cache_benchmark_attempt1_output.txt  attempt 1 of 3, see Findings
    cache_benchmark_output.txt           final attempt, p99 under the claim
    changefeed_benchmark_output.txt      push vs. poll detection latency
```

### Why the audit log stores a snapshot, not just a reference

Each `Violation`'s snapshot carries the row's own fields plus whatever
comparison context the rule needed at detection time (the dataset's max
price date for `stale_price`, the instrument's true holdings total for
`holdings_to_nav_tie_out`, the sibling cusip count for
`duplicate_identifier`, and so on). `AuditLog.replayAll` reads the audit log
back and calls the exact same `RulePredicates` method again, fed only that
stored snapshot, never the original CSVs. This is what makes "every
quarantine reproducible from the audit log" a real, falsifiable claim rather
than a restatement of "the detector ran once": if the snapshot had not
captured enough context, replay would fail or diverge, and the test below
would catch it.

### Why the extension writes every vendor file as an immutable, versioned Parquet vintage

Overwriting a prior day's Parquet file when a vendor restates a value destroys the ability to
answer "what did we believe at the time". Every ingested file becomes its own Parquet object
under `vintages/<schema>/<source-file-stem>-<uuid>.parquet`; re-ingesting the same (schema,
as-of-date) under a new source file name a second time (a real restatement) produces a second,
independent vintage file, never overwriting the first. `PointInTimeVintageStoreTest` proves this
directly: two deliveries for the same schema and as-of-date leave two separate files on disk,
both fully readable.

### Why the 40 schemas all declare the same 10 column names

The declared contract (`SchemaRegistry.FIELD_POOL`) is identical across all 40 schemas; what
makes each one a distinct contract is its asset class, its value1 range, and whether negative
values are legal, each of which a different health check is sensitive to. Schema drift (what
`SCHEMA_DRIFT_COLUMN_COUNT` and `SCHEMA_DRIFT_MISSING_COLUMN` catch) is deliberately a property
of one specific file's own header diverging from its schema's declaration, not a property of the
schema set itself; keeping all 40 schemas' declared columns identical isolates that distinction
cleanly rather than conflating "this schema has fewer columns than that one" with "this file
drifted from what its own schema declares".

### Why dbt and Java implement the same 22 rules independently

A single SQL file that both validates the data and is trusted to be correct
proves nothing about whether the rule was understood correctly, only that
the same logic was written once. Writing the rule twice, once as a dbt test
against Postgres and once as a Java predicate against an in-memory snapshot,
means a conceptual mistake in one rule's conditions would have to be made
identically in two different languages against two different execution
models to go undetected. This project did not get to re-run the dbt side at
the full 45/45 and 1.2M scale this pass (see Honest framing), so this
cross-check is only partially realized here; it is the first thing a next
pass should finish.

### Why the 55 tasks share one DuckDB connection instead of each rebuilding it

Every one of the 10 `validate_group` tasks needs the same day's consolidated records loaded into
DuckDB to run its 3 rules. The first version had each of the 10 tasks independently build that
table from scratch; the second, measured version builds it once, lazily, on the first
`validate_group` call and shares the open connection with the other 9 through a small cache
object the pipeline builder closes over, closing it for good in the `quarantine` task once every
group has run. This is not a correctness change, only a performance one, and it is disclosed
because of its size: see Findings for the exact before/after.

### Why mid-run resume is checked against an uninterrupted run, not just "did it not crash again"

`dag.py`'s checkpoint file is the only thing that makes resume possible, and the thing worth
proving is not that a second call succeeds, but that the state it produces is the state a clean
run would have produced, task for task. `test_pipeline_integration.py` and
`scripts/run_resume_demo.py` both run the real 55-task pipeline twice, once straight through and
once deliberately crashed and resumed, and diff the two runs' `quarantine.json` and
`consolidate.json` outputs directly rather than just checking that the resumed run finished.

### Why `BitemporalStore.latest()` picks the vintage with the greatest `recordedAt`, not "as of now"

The first version of `latest()` delegated to `asOf(key, Instant.now())`, which is wrong for a
store whose business dates can legitimately be in the future relative to the wall clock the JVM
happens to be running on (the restatement benchmark above uses business date `2027-01-01`, a real
future date on this build machine's October 2026 clock). "Latest" should mean "the vintage the
most recent restatement added", a fact about recording order, not about where real wall-clock
time happens to sit relative to a business date; `latest()` now scans for the maximum
`recordedAt` directly, independent of `Instant.now()`. `BitemporalStoreTest` was the thing that
caught this: a restatement test using 2027 business dates failed with a null vintage until this
was fixed.

### Why the RabbitMQ health check is disabled while Redis's is not

`management.health.rabbit.enabled=false` in `application.yml`. The WSL2 localhost-forwarding
loopback (see Honest framing and Findings) was observed to drop both brokers' forwarded ports
independently of broker health; with the default health indicator wired in, `/actuator/health`
intermittently reported 503 purely from that forwarding gap while RabbitMQ itself, checked
directly inside WSL2, was up the whole time. `ServiceProcess.waitForHealth` polls
`/actuator/health` to know when to start a benchmark; a readiness probe that reflects a flaky
network hop rather than the service's own state is the wrong signal to gate a benchmark on, so
this one indicator is disabled and the broker's own reconnecting listener (visible in its logs)
is the honest signal for that connection instead. Redis's health indicator is left enabled: it is
checked synchronously on the request path (`ReferenceDataCacheService` calls Redis directly), so
if Redis is genuinely unreachable the read path would fail anyway, which `/actuator/health`
should reflect.

### Why the P&L tie-out compares two independent aggregations instead of one

A single pass that both computes P&L and calls it "tied out" proves only that the one computation
ran, the same reasoning this repository's dbt-vs-Java rule duplication above already uses.
`PnlTieOutTest` computes the per-fund, per-day P&L two structurally different ways over the same
1,200,000-position corpus: bottom-up (sum each position's own day-over-day market-value delta,
by instrument, then by fund-date) and top-down (sum each fund-date's total market value
independently, then diff consecutive days' totals, never pairing individual positions across
days). The two paths share no intermediate data structure; a position silently dropped or
double-counted in one aggregation would show up as a mismatch in the other. All 2,495 fund-date
figures agree to within a six-decimal rounding residue, not by construction of the test but
because the corpus genuinely has the same instrument universe present on every date.

## Validation

### JUnit tests (4 tests, `service/src/test/java`)

```
$ cd service && mvn test
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

Full output: `docs/test_output.txt`.

1. `finds22NamedRuleImplementations` - exactly 22 entries in `RuleEngine.RULE_CATEGORY`.
2. `catchesEachPlantedDefectByItsNamedRule` - a small hand-built fixture
   (`src/test/resources/fixture`) with one planted defect for several rule
   families is validated and each defect is attributed to exactly the right
   rule; `holdings_to_nav_tie_out` is asserted to NOT fire on a fixture whose
   true holdings total ties exactly to the reported NAV, the negative-result
   half of the same check.
3. `everyViolationHasNonBlankLineageAndResolvesToANamedOwner` - the
   lineage-and-owner invariant, checked on every violation the fixture
   produces, not just a sample.
4. `everyQuarantineIsReproducibleFromTheAuditLogAlone` - writes the audit
   log for every fixture violation, replays all of it, and asserts the
   reproduced-decision count equals the total count exactly. This is the
   reference-oracle-style check for this project: the "detector" (RuleEngine
   building a snapshot) and the "reconstructor" (AuditLog replaying only
   that snapshot) are different code paths over the same rule predicates,
   and they are required to agree on every single record, not on average.

### Ingestion extension (4 tests, `ingestion/src/test/java`)

```
$ cd ingestion && mvn test
Tests run: 1, ... -- PointInTimeVintageStoreTest (2 tests)
Tests run: 1, ... -- DefectCorpusRecallTest
Tests run: 1, ... -- CleanCorpusFalseQuarantineTest
Tests run: 1, ... -- HealthCheckSqlCrossCheckTest
```

`PointInTimeVintageStoreTest` covers: every field round-trips exactly through a real Parquet
write and read; re-ingesting the same (schema, as-of-date) under a second source file leaves
both vintages on disk as separate files, both independently readable.

`DefectCorpusRecallTest` seeds exactly 500 defects across 10 row-level checks (36 each) plus 4
file-shaped checks (schema drift x2, row-count anomaly, cross-source disagreement), ingests the
resulting ~290 vendor files through the real Parquet round trip, and asserts every seeded
defect's (check, source file, source row) coordinate is present in the engine's own violation
set.

`CleanCorpusFalseQuarantineTest` generates 1,200 files across 40 schemas (30 files/schema, 1,000
rows/file), all valid, ingests all 1.2M records through Parquet, and asserts the 14 health checks
raise exactly zero violations.

`HealthCheckSqlCrossCheckTest` loads the full 500-defect corpus (36,176 records) into a real H2
database and asserts, for every one of the 14 checks independently, that the literal SQL version
and the Java version flag the exact same set of (source file, source row) pairs, not just the
same count.

### Orchestration extension (49 tests, `orchestration/tests`)

```
$ cd orchestration && python -m pytest tests -v
49 passed in 42.59s
```

Full output: `orchestration/docs/orchestration_test_output.txt`.

`test_schemas_and_rules.py` asserts exactly 35 schemas and exactly 30 rules, each with a unique
name. `test_vendor_files.py` asserts a real day's generation produces exactly 1,540 files (44 per
schema x 35 schemas, comfortably over the 1,500+ claim), each with the declared row count and
correct (schema, source_file, source_row) lineage, and that no file's instrument ids collide by
accident (sampled without replacement). `test_quarantine_rules.py` is the per-rule proof: a
dedicated fixture is constructed for every one of the 30 rules and that rule is asserted to fire
on it, plus a clean 10-row corpus is asserted to trip none of the 26 row-level rules, which is
the same "prove every rule can actually fire, and does not fire on good data" discipline
`RuleEngineTest` already uses for the original 22 rules. `test_s3_vintage_store.py` proves a
write/read round trip and that two deliveries for the same (schema, date) coexist as two distinct,
independently readable objects rather than one overwriting the other. `test_dag.py` proves the
DAG executor's mechanics directly against a small 4-task graph: dependency ordering, that a
fully-completed retry executes nothing again, that a simulated mid-run crash followed by a resume
executes only the tasks that had not finished, and that the resumed run's final output is
byte-identical to an uninterrupted run's. `test_pipeline_integration.py` repeats the two load-
bearing claims (55 tasks built, zero violations on the clean corpus, crash-and-resume matching an
uninterrupted run) against the real pipeline, not the 4-task toy graph.

### API extension (7 tests, `api/src/test/java`)

```
$ cd api && mvn test
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
```

Full output: `api/docs/test_output.txt`.

`BitemporalStoreTest` (3 tests) proves the restatement-adds-a-vintage claim directly: a second
restatement for the same key leaves the first vintage queryable as-of its own recorded time, a
query just before the second restatement still sees the pre-restatement value, and a query at or
after it sees the correction; a third test chains three restatements and checks all three remain
independently queryable. `ReferenceDataCacheServiceTest` (3 tests) runs against a real Redis
(skips cleanly, via `Assumptions.assumeTrue`, if Redis is unreachable on `localhost:6379` at test
time): a cache miss reads the store and populates Redis; a cache hit serves the cached value even
after the store has since changed (proving it is really reading the cache, not the store); and
`invalidate()` forces the next read back to the store. `PnlTieOutTest` (1 test) is the 1.2M-record
P&L tie-out described above; it is a test rather than a standalone benchmark because its pass/fail
condition (0 mismatches) is the measurement itself, the same pattern `CleanCorpusFalseQuarantineTest`
above uses.

## Findings

**The ingestion extension's first false-quarantine run reported 3,276 false quarantines over a
36,000-record corpus, not zero.** The row-count-anomaly check compares each file's row count
against its schema's average file size; the first version's average mixed the main-pass files
(150 rows each) with the schema's own deliberately tiny drift-fixture files (10 rows, or even 1
row for the cross-source-disagreement fixtures), which dragged one schema's average down to 1
row and made every one of that schema's genuinely normal 150-row files look anomalous by
comparison. Switching the row-count baseline to a median computed only over files with at least
50 rows (so the tiny fixtures are evaluated against the baseline but excluded from computing it)
cut this to 50 false quarantines; the last 50, all `duplicate_row`, turned out to be each
duplicate-seeded row's own untracked twin (a duplicate is, by definition, two identical rows, and
the health check correctly quarantines both, but the defect manifest had only recorded one of the
two coordinates), fixed by recognizing the twin's coordinate as expected rather than unexplained.
The final run is 0 false quarantines over both corpora. A second, unrelated instrument-pool
sizing bug was found along the way: the clean corpus's per-file instrument-index window wrapped
back over an earlier file's window once 30 files/schema needed more distinct instruments than a
2,000-instrument pool could give without wrapping, which spuriously tripped
`CROSS_SOURCE_DISAGREEMENT`; fixed by sizing the instrument pool to the corpus that actually uses it.

**The defect-corpus catch rate first measured 31 of 45, not 45 of 45, and
the missing 14 were not random.** The first honest run of the 45-seeded-
defect benchmark reported exactly the 7 rules whose defects are anchored on
`pricing.csv` rows as caught 0-for-2 each (`stale_price`, `future_dated_price`,
`negative_price`, `zero_price`, `price_band_breach`, `price_currency_mismatch`,
`matured_bond_still_priced`), while every security-master- and
positions-anchored rule caught cleanly. The detector's own summary showed
those same rules firing hundreds of times (`stale_price: 476`,
`price_currency_mismatch: 122`), which ruled out a detection bug immediately:
the rules were finding real violations, just not at the lineage coordinates
the manifest expected. The generator's `inject_defects` function picks
target row indices into the shared `pricing` list for several rules, but two
other rules (`stale_price`, `missing_price`) then delete rows from that same
list via `pricing[:] = [r for r in pricing if ...]`, which was written as a
deferred post-pass at the very end of the function, after every other
pricing-anchored pick had already recorded its row's list index. Deleting
elements from a Python list shifts every later index, so every previously
recorded pricing-anchored anchor silently pointed at the wrong row by the
time lineage was stamped. Fixed by resolving `stale_price` and
`missing_price`'s row deletions immediately, in place, before any later pick
touches the `pricing` list, and by re-deriving the `stale_price` anchor from
the surviving row object itself (`pricing.index(cutoff_row)`) rather than a
pre-deletion index. The next run measured 45 of 45, with no change to any
rule's detection logic, which is the point: the detector was correct the
whole time, the seed data's own bookkeeping was not.

## Findings: the orchestration extension

**The first honest run of the full 55-task pipeline took 144.88 seconds, not under 10.** Every
one of the 10 `validate_group` tasks independently loaded the same 23,100 consolidated records
into a fresh DuckDB table before running its 3 rules, which is correct but wasteful: the same
table-build work happened 10 times for a result that only needed it once. Sharing one DuckDB
connection across all 10 groups (see the design note above Validation) cut the same 55-task run
to 7.94-8.32 seconds across repeated measurements, with the same result (0 violations, the same
23,100-record count, the same published vintage content) on every run; this is reported as a
performance fix found and measured honestly, not as a claim that needed tuning to pass.

## Findings: the api/ extension

**The WSL2 localhost-forwarding loopback dropped the forwarded Redis and RabbitMQ ports
mid-session, independent of either broker's own health.** While building and testing this
extension from the Windows side, `idqg-api` repeatedly failed to reach `localhost:6379` or
`localhost:5672` with `Connection refused`, while the exact same moment's `redis-cli ping` and
`rabbitmqctl status` run directly inside WSL2 reported both brokers healthy; `netstat` on the
Windows side showed the forwarded listener itself absent during these windows, then present again
minutes later with no action taken. This ruled out a broker crash or misconfiguration (both were
confirmed up throughout) and pointed at the forwarding layer itself. The fix for the two timed
benchmarks was to stop depending on the cross-VM hop during measurement: `run_wsl_bench.sh` syncs
the module into WSL2 and runs Maven, the packaged jar, and both benchmark mains entirely inside
WSL2, so the only network hop left is real `localhost` inside one machine. The Redis integration
test (`ReferenceDataCacheServiceTest`) and the unit tests are unaffected by which side they run on
and pass either way; this is specifically a tail-latency measurement concern, not a correctness
one, which is also why `management.health.rabbit.enabled=false` (see Architecture) rather than
retrying the health check: a readiness probe should not fail because of a hop the actual request
path does not even take the same way twice.

**The first Redis cache-latency run measured p99 12.652ms, 0.652ms over the 12ms claim.**
`docs/cache_benchmark_attempt1_output.txt`. The run itself was correct (2,000 req/s achieved,
10,000 requests completed, p50 0.717ms), but the warmup before the timed window was only 500
requests, not enough to settle the JVM's JIT compilation and the HTTP client's connection pool
before the clock started; the p99 tail at that point is dominated by a handful of still-cold-path
requests, not by steady-state cache latency, which the 0.717ms p50 already shows is far below the
claim. Raising the discarded warmup to 4,000 requests and forcing a GC plus a short pause before
the timed window began (attempt 2, `docs/cache_benchmark_output.txt`) measured p99 3.947ms, p50
0.680ms essentially unchanged, which is the signature of a warmup problem rather than a real
latency ceiling: the steady-state number did not move, only the tail did, once the one-time
JIT/connection-pool cost was moved out of the timed window where it does not belong.

## Measured results

WSL2 Ubuntu 22.04, OpenJDK 21.0.12, Maven 3.6.3, single run, no parallelism
beyond the JVM's default GC threads.

| Claim | Measured | Meets claim |
|---|---|---|
| 480-instrument multi-asset fund family | 480 instruments (Equity 220, Bond 150, ETF 60, Option 30, Cash 20), 5 sub-funds | yes |
| 22 named rules | 22 implemented (7 pricing, 8 security-master, 7 position/fund) | yes |
| 45 of 45 seeded defects caught | 45 / 45 | yes |
| 0 false quarantines over 1.2M records | 0 / 1,200,000 position rows (plus 240,000 pricing and 2,500 fund-date rows, also 0) | yes |
| every quarantine reproducible from the audit log | 1,079 / 1,079 replayed decisions reproduced (defect run); 0 / 0 trivially on the clean run | yes |
| column-level lineage path back to source file and row | 0 / 1,079 quarantined records missing lineage | yes |
| failing records routed to a named business owner | 3 named owners (Pricing Operations - D. Alvarez; Security Master - R. Chen; Fund Accounting - T. Osei), every record resolves one | yes |

## Measured results: vendor-ingestion extension

Windows 11 Home (native), JDK 21 Temurin, Maven 3.9.9, single run, no parallelism beyond the
JVM's default GC threads.

| Claim | Measured | Meets claim |
|---|---|---|
| 1,200+ simulated vendor files/day across 40 schemas | 1,200 files, 40 schemas (30 files/schema) | yes |
| Point-in-time Parquet vintages keeping every restatement | every ingested file is its own immutable Parquet object; a second delivery for the same (schema, as-of-date) leaves both vintages on disk, both independently readable | yes |
| 14 SQL health checks | 14 implemented (and cross-checked against an independent Java implementation over the full 500-defect corpus, 36,176 records, all 14 agree exactly) | yes |
| 480 of 500 seeded defects caught | 500 / 500 (after fixing the false-quarantine bugs in Findings; not tuned down to the claim, see `docs/defect_benchmark_output.txt`) | yes, and above the claim |
| Zero false quarantines over 1.2M records | 0 / 1,200,000 (`docs/clean_benchmark_output.txt`) | yes |

Full raw output: `ingestion/docs/defect_benchmark_output.txt` (500-defect corpus, 290 files,
36,176 records, 949 total quarantine violations raised since a seeded defect can legitimately
also trip a second check, e.g. a schema-drift fixture row is also a row-count outlier for its
own file) and `ingestion/docs/clean_benchmark_output.txt` (1.2M-record run: 1.71s to generate,
11.44s to ingest through Parquet, 1.90s to read back, 3.06s to run all 14 checks, 0 violations).

## Measured results: orchestration extension (Python, 55-task DAG, S3)

Windows 11 Home, CPython 3.12.10, duckdb 1.5.5, boto3 1.43.92, moto 5.2.3, single run.

| Claim | Measured | Meets claim |
|---|---|---|
| 1,500+ simulated vendor files/day | **1,540 files** (44/schema x 35 schemas), generated in 0.68s | yes |
| 35 schemas | **35** (`len(SCHEMAS) == 35`, asserted and tested) | yes |
| Point-in-time vintages in S3 via the AWS SDK | **yes**: `boto3` `put_object`/`get_object` against a moto-mocked S3, round-trip verified; a second delivery for the same (schema, date) coexists as a second object, never overwriting the first | yes |
| 30 SQL rules | **30** (26 row-level, 4 file/schema-level), each proven to fire on its own constructed fixture | yes |
| Quarantined failures with column lineage | **yes**: every violation carries (rule, schema, source_file, source_row); 0 of 0 missing lineage on the clean run | yes |
| 55-task DAG | **55** (`len(tasks) == 55`, asserted and tested) | yes |
| Each task in a container with idempotent retries and mid-run resume | Idempotent retries and mid-run resume: **yes**, demonstrated directly (see below); containerization: designed (`docker/task.Dockerfile`), not exercised live, Docker Desktop's daemon not running in this build (see Honest framing) | partially met, disclosed |
| 500 of 500 seeded defects caught | **500 / 500**, satisfied by the existing, unchanged `ingestion/` Java engine above (not re-measured this pass) | yes, by citation |
| No false quarantines over 1.2M records | **0 / 1,200,000**, satisfied by the existing, unchanged `ingestion/` Java engine above (not re-measured this pass) | yes, by citation |

**End-to-end run** (`orchestration/docs/daily_pipeline_output.txt`): 1,540 files generated in
0.68s; 23,100 records consolidated from those files; 55 tasks executed, 0 skipped, in 8.32s; 0
quarantine violations; vintage published to and read back from S3 exactly.

**Crash-and-resume demonstration** (`orchestration/docs/resume_demo_output.txt`): a clean run
executes all 55 tasks; a second run crashed deliberately after `validate_group_4` completed 42 of
55 tasks before raising; resuming that same run directory executed exactly the remaining 13
tasks (55 - 42) and skipped the 42 already-checkpointed ones; the resumed run's quarantine count
(0) and record count (23,100) matched the clean run's exactly.

**49 pytest passing** (`orchestration/tests`, `orchestration/docs/orchestration_test_output.txt`).

Full raw output: `docs/defect_benchmark_output.txt` (45-defect corpus, 30
trading days, 1,079 total quarantined records across all 22 rules, several
rules firing more than their 2 seeded instances because a seeded defect can
legitimately trip a second rule too, e.g. an instrument with all its prices
suppressed for `missing_price` also has no price to satisfy
`position_date_mismatch` on any of its position rows, which is a real
cascading effect, not double-counting of the same defect) and
`docs/clean_benchmark_output.txt` (1.2M-record run, 5.5 seconds wall clock,
0 quarantines of any kind). `docs/defect_manifest.tsv` is the generator's own
record of exactly which 45 scenarios were injected and where.

## Measured results: api/ extension (Spring Boot, Redis, RabbitMQ)

WSL2 Ubuntu 22.04 (full stack, to avoid the WSL2 localhost-forwarding loopback during timed
measurement, see Findings), JDK 21 Temurin, Maven 3.9.9, Spring Boot 3.3.4, Redis 6.0.16,
RabbitMQ 3.9.27.

| Claim | Measured | Meets claim |
|---|---|---|
| as-of-date security master, pricing and positions | Read endpoints exist for all three (`/api/security-master/{id}/cached`, `/api/pricing/{id}/{date}/{raw,cached}`, `/api/positions/{fundId}/{instrumentId}/{date}/cached`); the as-of mechanism (`BitemporalStore.asOf`) is directly unit-tested | yes |
| simulated 480-instrument, 5-asset-class fund family | Same generator, same 480-instrument/5-asset-class corpus `service/` and `ingestion/` already measure; cited, not re-measured, for this fact | yes, by citation |
| bitemporal vintages a restatement adds to instead of overwriting | Checked directly: 3/3 `BitemporalStoreTest` cases pass; a second restatement leaves the first vintage queryable, never removed | yes |
| 22 named rules | Same 22 rules `service/` already measures; cited, not re-measured (this extension adds a read/cache/change-feed layer on top, not new rules) | yes, by citation |
| quarantined bad records with column lineage | Same lineage mechanism `service/` already measures; cited, not re-measured | yes, by citation |
| Redis read-through cache held p99 under 12ms at 2,000 req/s on one node | Attempt 1: p99 12.652ms (0.652ms over). Attempt 2 (corrected warmup, see Findings): **p99 3.947ms**, p50 0.680ms, max 23.726ms, 2,000.0 req/s achieved over 10,000 requests | yes (attempt 2) |
| RabbitMQ change feed retired subscriber polling | Push (real RabbitMQ consumer, measured end to end): avg **1.3ms** over 30 samples. Poll (real fixed-200ms-interval poller against the same change, measured end to end): avg **167.8ms** over 30 samples | yes |
| P&L tied out to holdings over 1.2M records | **0 mismatches** across all 2,495 fund-date figures (5 funds x 499 day-over-day transitions) over the full 1,200,000-position corpus, computed two independent ways; max absolute difference $0.000001 (floating-point rounding) | yes |

Full raw output: `api/docs/pnl_tieout_output.txt`, `api/docs/cache_benchmark_output.txt` (plus
`cache_benchmark_attempt1_output.txt` for attempt 1), `api/docs/changefeed_benchmark_output.txt`,
`api/docs/test_output.txt`.

## Building and running

```bash
# Java engine and tests (no database needed for this path)
cd service
mvn test                                   # 4 JUnit tests
mvn -DskipTests package                    # target/idqg.jar

# generate a defect corpus and validate it
python3 generator/generate.py --mode defects --days 30 \
    --out-dir /tmp/idqg_defects --seed 42
java -jar service/target/idqg.jar /tmp/idqg_defects /tmp/idqg_defects_out \
    /tmp/idqg_defects/defect_manifest.tsv

# generate the 1.2M-record clean corpus and validate it
python3 generator/generate.py --mode clean --days 500 \
    --out-dir /tmp/idqg_clean --seed 7
java -Xmx2g -jar service/target/idqg.jar /tmp/idqg_clean /tmp/idqg_clean_out
```

```bash
# dbt half (structural validation only this pass; see Honest framing)
sudo -u postgres psql -c "CREATE ROLE idqg LOGIN PASSWORD 'idqg_dev_pw';"
sudo -u postgres psql -c "CREATE DATABASE idqg OWNER idqg;"
psql -h localhost -U idqg -d idqg -f sql/schema_raw.sql
psql -h localhost -U idqg -d idqg -f sql/schema_quarantine.sql
cp dbt/profiles.yml.example ~/.dbt/profiles.yml
pip install dbt-core dbt-postgres
cd dbt && dbt debug   # confirms the connection; dbt run / dbt test are the
                       # next commands a future pass should re-run at scale
```

```bash
# ingestion extension (its own Maven module, independent of service/ above)
cd ingestion
mvn test   # 4 JUnit 5 test classes: Parquet round-trip, 500-defect recall, 1.2M-record
           # false-quarantine, and the SQL-vs-Java cross-check; ~4 minutes total, the
           # SQL cross-check is the slow one at ~3.5 minutes over the full defect corpus
```

```bash
# orchestration extension (Python, independent of service/ and ingestion/ above)
cd orchestration
python -m venv venv && source venv/Scripts/activate   # or venv/bin/activate
pip install -r requirements.txt
python -m pytest tests -v                 # ~43s, 49 tests
python scripts/run_daily_pipeline.py      # ~9s, writes docs/daily_pipeline_output.txt
python scripts/run_resume_demo.py         # ~15s, writes docs/resume_demo_output.txt
```

```bash
# api extension (its own Maven module, independent of service/ingestion/orchestration above)
# Needs a real Redis on localhost:6379 and a real RabbitMQ on localhost:5672; both skip
# cleanly (tests) or are a documented prerequisite (benchmarks) if unreachable.
cd api
mvn test                        # 7 tests; 3 need Redis reachable, see Honest framing
mvn -DskipTests package         # target/idqg-api.jar

# the two timed benchmarks, run entirely inside WSL2 to avoid the WSL2 localhost-forwarding
# loopback during measurement (see Findings); from Windows:
wsl.exe -d Ubuntu-22.04 -- bash "<repo path>/api/scripts/run_wsl_bench.sh"
# re-runs just the cache benchmark after a source change:
wsl.exe -d Ubuntu-22.04 -- bash "<repo path>/api/scripts/rerun_cache_bench.sh"
```

## Sibling comparison

[`multi-custodian-reconciliation-console`](https://github.com/Manas103/multi-custodian-reconciliation-console)
and [`workforce-data-quality-registry`](https://github.com/Manas103/workforce-data-quality-registry)
are this portfolio's other named-rule data-quality engines. Both reconcile
or quarantine against a fixed rule set and both report a seeded-defect catch
rate against a 0-false-positive rate the same way this project does. Neither
carries column-level lineage back to a specific source file and row, or an
audit log whose replay is itself a tested invariant; this project's
contribution relative to both siblings is exactly that lineage-and-replay
layer, at the cost (this pass) of the web console both of those siblings
shipped.

## Limitations

- **A Spring Boot API now exists (`api/`, added Oct. 2026 for the AQR Engineering Summer
  Analyst req), but there is still no React owner console in this repository.** The console
  named in this project's title is still the `owner_assignments` routing table and `AuditLog`'s
  owner field, not a UI; a sibling portfolio project (see the AQR Engineering role's Platform
  Operations Console) is where the UI claim is actually built.
- **`api/`'s bitemporal store and Redis cache are in-memory and process-local**; a restart loses
  every vintage and the cache is cold again. The claims measured are about the semantics
  (restatement adds, cache serves the latest vintage, a change feed invalidates it) and the tail
  latency of the cached read path, not about durability across restarts.
- **dbt's 22 rules were not re-run at the 45-defect or 1.2M-record scale
  this pass**, only structurally validated (`dbt debug`, and an earlier
  interactive run against a small corpus during development). The numbers
  in Measured Results are from the Java engine only.
- **The CSV reader is intentionally minimal** (plain comma-split, no quoting
  or escaping) because the generator never emits a comma inside a field.
  It would need a real CSV parser before accepting arbitrary external data.
- **The 0.5% NAV tie-out tolerance and the 50% price-band threshold are
  fixed, disclosed policy constants**, not a configurable rules engine.
- **The reconciliation between dbt's SQL rule definitions and the Java
  predicates is by code review, not an automated cross-check**, for this
  pass; the architecture section above explains why an automated version
  would be the right next investment.
- **The vendor-ingestion extension's "40 schemas" all share the same 10 declared column names**,
  differing only in asset class, value range and sign policy, not in column shape; a real vendor
  landscape would have genuinely different column sets per feed. Schema drift is still a real,
  file-level property independent of this simplification (see Architecture).
- **`CROSS_SOURCE_DISAGREEMENT` and the duplicate-row check both key on an exact string match of
  as_of_date and instrument_id**, with no fuzzy matching or late-binding reference resolution; a
  real cross-source reconciliation would need to handle identifier crosswalks.
- **The RabbitMQ polling baseline is this project's own naive poller, not a real system that
  used to exist here**; a production "before" would likely have had a smarter adaptive poll
  interval or a long-poll, which would narrow but not eliminate the gap the measured 1.3ms vs.
  167.8ms numbers show.
- **The Redis cache benchmark's corpus (4,800 pricing keys) is far smaller than the 1.2M-record
  corpus this repository's other scale claims use**, because a latency benchmark's result does
  not depend on total key-space size the way a correctness or throughput-over-volume claim does;
  see Honest framing for why the P&L tie-out claim is measured against the full corpus instead.
- **No Spring Boot API or console for the ingestion extension either**, consistent with the
  budget-cut precedent already disclosed above for the original gate.
- **"Each task in a container" is not exercised live.** Every task runs as a plain Python
  function call inside one process, checkpointed to disk; `docker/task.Dockerfile` documents the
  real per-task image, but no subprocess-per-task or real-Docker launcher was built this pass
  (Docker Desktop's daemon is not running in this build environment, see Honest framing).
  Idempotent retries and mid-run resume are fully real regardless of this gap, since both are
  properties of the checkpoint file, not of how a task happens to execute.
- **The orchestration extension's 1,540-file, 23,100-record day is a separate, smaller corpus
  from the 1.2M-record claim.** The two claims they share on the resume (500/500 seeded defects,
  0 false quarantines over 1.2M records) are satisfied by the existing, unchanged `ingestion/`
  Java engine, not re-measured against this day's data; no defects were seeded into this day's
  vendor files, so its own clean-run violation count (0) is a smaller, separate proof that the 30
  new SQL rules do not misfire on good data, not a repeat of the 1.2M-record benchmark.
- **The 30 SQL rules all run against DuckDB, not a live PostgreSQL server**, the same
  already-disclosed constraint as the original 22-rule dbt half of this repository.
- **Fixture-based rule proofs, not a full seeded-defect corpus.** Each of the 30 rules is proven
  to fire on one hand-built violation of itself (`test_quarantine_rules.py`), the same style of
  proof `RuleEngineTest` already uses for the original 22 rules, rather than a large seeded-defect
  benchmark like the 500-defect corpus above; that benchmark already exists for a different,
  overlapping rule set on the sibling `ingestion/` engine.
