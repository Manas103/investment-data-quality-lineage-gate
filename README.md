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
scores it against 14 named SQL-and-Java health checks. Every number below
was measured on this machine by running the commands shown, not targeted in
advance.

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

- **No Spring Boot API and no React owner console in this repository.**
  Both were in the original design; neither is required by the 7 measured
  claims above, and both were cut under a hard budget rather than shipped
  partially working. The "owner console" named in this project's title is
  the `owner_assignments` routing table and `AuditLog`'s owner field, not a
  UI, in this pass.
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
- **No Spring Boot API or console for the ingestion extension either**, consistent with the
  budget-cut precedent already disclosed above for the original gate.
