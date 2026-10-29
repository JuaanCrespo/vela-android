# Phase 3.a.3 — Separate Market Database

Source baseline: `ae5dc2dbb664767f6bd8f1fe2d94e12961f0102a`, verified against
origin/main after fetch. Validation date: 2026-10-29.
Implementation checkpoint: `PASS_3A3_SEPARATE_MARKET_DATABASE_IMPLEMENTED`.
Final independent audit/publication results are recorded below; the initial
implementation evidence is retained as history. No Android runtime was performed.
Independent final-audit gate: `READY_3A3_TO_PUBLISH`.

## Architecture and explicit lifecycle

Package: `com.vela.android.lab.core.marketdataset.storage`.

| Production file | Responsibility |
|---|---|
| AndroidMarketDatasetDatabase.kt | Explicit private-directory Android SQLite factory and owned connection adapter |
| MarketSql.kt | Narrow exact-text/NULL SQL binding interface, typed storage errors, batch/result/session/integrity API |
| MarketDatasetSchema.kt | Complete executable V1 DDL, schema fingerprint and exact sqlite_master verification |
| MarketRowCodec.kt | Typed field-to-column mapping and validating, fingerprint-checked rehydration |
| MarketDatasetStore.kt | Atomic append, immutable receipt/revision checks, minimal readers and read-only integrity diagnostics |

The Android factory opens only `context.applicationContext.getDatabasePath(
"vela_market_dataset.db")`. It never accepts an arbitrary database path or shared
handle. The operational database name remains `vela-lab.db`, Room version 9.
There is no ATTACH, cross-database transaction or operational DAO access.
No application/dashboard/Paper/reconciliation code calls the factory. Loading the
factory object performs no database IO; opening requires an explicit call.

The existing project primarily uses Room and its SQLite migrations. For this
separate database, direct SQLiteDatabase avoids implicit helper upgrades and gives
explicit ownership of schema validation and corruption handling. No new runtime
or test dependency was added. Host tests use existing sqlite-jdbc 3.41.2.2 through
a test-only implementation of MarketSqlConnection. Production DDL, codecs,
transactions, validation and repository methods are shared with those tests.

Android uses OpenParams with DELETE journal, FULL sync and a corruption handler
that throws instead of deleting evidence. Its journal/synchronous builder setters
are available since API 28, below this project's minimum API 29.
Reference: [official Android OpenParams.Builder API](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase.OpenParams.Builder).
Android device lifecycle/corruption handling is **NOT YET VALIDATED at runtime**.
JDBC success is not presented as proof of Android connection-pool/device behavior.

## V1 schema and version semantics

Market database `PRAGMA user_version=1` is independent of operational Room 9,
DatasetSchemaVersion, ParserVersion, CanonicalizationVersion, QualityPolicyVersion
and application version. A physical database is bound to one explicit DatasetId
through singleton metadata; opening it for another dataset fails WRONG_DATASET.
Receipt identities are therefore namespaced by that database's dataset identity.

The complete executable DDL, including every column, integer bound, FK, index and
trigger, is [MarketDatasetSchema.kt](../android/app/src/main/kotlin/com/vela/android/lab/core/marketdataset/storage/MarketDatasetSchema.kt).
It is generated as `MarketDatasetSchema.statements` and executed unchanged by
both backends. The exact expanded SQL was exported from the compiled production
object to `C:\Android\audits\phase-3a3-20261029\market-dataset-v1.sql`.
V1 DDL SHA-256: `a539bc2a020922419c27ae782d8c4bd43f02af4c642b1930dd8c59bc0bea6e47`.

| Table | Durable key and constraints | Purpose |
|---|---|---|
| market_metadata | singleton INTEGER PK, CHECK=1 | Format `VELA_MARKET_DATASET_V1`, DB version, DatasetId, exact DDL fingerprint |
| ingest_sessions | session_id PK; UNIQUE(session_id,provider,feed,source_class) | Minimal immutable explicit session/source namespace |
| session_high_water | session_id PK/FK to session; sequence nullable, integer >=0 | NULL means registered session with no committed receipts |
| raw_receipts | PK(session_id,sequence); composite source/session FK | Complete typed raw evidence and derived identities |
| canonical_revisions | revision_id PK; content_fp UNIQUE; UNIQUE(logical_id,dataset_schema_version,canonical_version,revision_number); parent-content FK | Immutable canonical revisions with complete timing/values/finality |
| bar_lineage | PK(revision_id,session_id,sequence); bar FK and raw receipt composite FK | Checked raw fingerprint/parser/schema lineage |
| bar_quality_flags | PK(revision_id,flag); bar FK | Immutable descriptive quality flags |

Core identity/FK DDL (full column declarations/checks are in the executable source):

```sql
-- raw_receipts
PRIMARY KEY(session_id,sequence)
FOREIGN KEY(session_id,provider,feed,source_class)
  REFERENCES ingest_sessions(session_id,provider,feed,source_class)
-- canonical_revisions
UNIQUE(logical_id,dataset_schema_version,canonical_version,revision_number)
content_fp TEXT NOT NULL UNIQUE
previous_content_fp TEXT REFERENCES canonical_revisions(content_fp)
CHECK((revision_number=0 AND previous_content_fp IS NULL)
   OR (revision_number>0 AND previous_content_fp IS NOT NULL))
-- bar_lineage
PRIMARY KEY(revision_id,session_id,sequence)
FOREIGN KEY(session_id,sequence) REFERENCES raw_receipts(session_id,sequence)
```

Opening an empty version-zero database creates schema/metadata/user_version in one
transaction. Nonempty version zero, incompatible sqlite_master objects, missing
indexes/triggers, wrong format/fingerprint or an unsupported older/newer version
fail closed. Internal-object filtering uses `NOT GLOB 'sqlite_*'`, where the
underscore is literal; user tables such as sqliteXmarker are never mistaken for
SQLite internals. Every public read/append checks schema identity and required
connection settings. There is no destructive fallback, DROP/recreate, migration,
automatic corruption repair or schema replacement. Future V2 needs an explicit,
tested migration; changing V1 DDL without that migration makes existing files fail
closed rather than silently changing their contract.

## Exact columns, source identity and rehydration

Raw rows contain provider/feed/sourceClass, assetClass, canonicalSymbol,
providerSymbol and instrument fingerprint. Provider/feed appear once per row
because the published domain requires instrument/source compatibility; neither
identity loses a component. All comparisons use SQLite's default binary text
semantics, never ticker-only or locale-based normalization.

Each event carries event type/native ID, event identity kind/fingerprint, content
fingerprint, receipt fingerprint, session/sequence, parser/dataset versions and
separate event/received/available seconds and nanos. Typed payload columns cover:

- Quote: bid/ask and nullable sizes.
- Trade: price and size.
- Bar/BAR_UPDATE: OHLC and nullable volume/count/VWAP.
- STATUS/CONTROL/UNKNOWN: safe payload fingerprint.

All decimal bindings/storage are TEXT, with original raw lexemes retained.
Canonical numerics use normalized ExactMarketDecimal text. There is no Double,
Float, REAL, rounding, scientific-format conversion or JSON payload authority.
Missing values stay NULL. Integer fields use INTEGER affinity plus typeof and
range checks, rejecting overflow-to-REAL or fractional values. Schema/parser/
canonicalization versions are checked within positive Int range before conversion.
Timestamps retain Instant's full structural second range and nanos 0..999999999;
negative epochs and adjacent nanos are covered by permanent round-trip tests.

Readers reconstruct the published immutable domain objects through validating
constructors, recompute every derived identity/fingerprint, and compare the entire
encoded row to stored columns. This also rejects altered unused payload columns,
noncanonical canonical text, mismatched identity metadata and corrupt lexemes.
Invalid normal readback reports DATA_INTEGRITY_ERROR, without normalization/repair.

## Append, retries and provider contradictions

`append(MarketAppendBatch)` defensively snapshots the supplied lists and executes
session registrations, raw receipts, revisions, lineage and flags in one explicit
transaction. Batch entries are processed in caller order; no hidden event sorting,
session generator, clock or sequence allocator exists.

Sessions are registered explicitly with one immutable source identity. Repeating
the same registration is idempotent; changing it is SESSION_CONFLICT. First receipt
sequence must be zero; subsequent new receipts must be exactly previous+1.
Long.MAX_VALUE boundaries cannot overflow into a new sequence. No rowid is used as
protocol truth.

The raw durable key is (session,sequence), not content/native identity. Identical
evidence at different positions remains two traceable receipts with the same
content fingerprint and different receipt identity. Retrying the same position
requires equality of the complete immutable envelope, including local timing;
it is a no-op if equal and RECEIPT_CONFLICT otherwise. There is no unique raw
content fingerprint constraint and no implicit deduplication or volume calculation.

Current conservative provider-ID policy: a different content fingerprint under the
same scoped PROVIDER_ID is PROVIDER_ID_CONFLICT and rejects/rolls back the entire
incoming batch. Existing evidence remains unchanged; the incoming contradiction
is explicitly identified, not silently selected, overwritten or acknowledged as
stored. A caller must retain/quarantine the rejected input externally if needed;
this phase does not add a correction parser or a conflict-retention pipeline.
Provider update/correction interpretation is deferred to 3.a.4.

## High-water and SQL append-only protection

High-water is NULL for a registered empty session, otherwise the last committed
contiguous sequence. It describes committed evidence, never an attempted/queued
position. The raw BEFORE INSERT trigger rejects gaps; AFTER INSERT advances
high-water in the same SQLite transaction. The high-water UPDATE guard permits
only the next existing receipt. A failed later batch statement rolls back the
raw row, trigger effects and every other row in the batch.

Representative exact trigger DDL:

```sql
CREATE TRIGGER raw_high_water AFTER INSERT ON raw_receipts
BEGIN UPDATE session_high_water SET sequence=NEW.sequence
WHERE session_id=NEW.session_id; END;
```

There are 16 triggers: 13 UPDATE/DELETE append-only guards, contiguous raw and
high-water guards, and the transactional high-water advance. recursive_triggers
is ON and verified, so destructive replacement cannot evade DELETE guards.
Production performs INSERT only for evidence; no INSERT OR REPLACE/destructive
UPSERT, pruning, cleanup or delete API exists. Updates are limited to checked
session high-water. A direct destructive-SQL rejection test covers both UPDATE/
DELETE and an attempted canonical replacement.

Before any append/retry handling, each affected session's existing ledger and
receipt prefix are checked against count/min/max. This check runs once per session
in the batch, not once per receipt, and includes sessions referenced by canonical
lineage. Missing ledgers or corrupted counters reject the whole batch without
repair; even a same-receipt retry cannot acknowledge an invalid prefix. Count scans
cost O(existing receipts in affected sessions) per batch and favor integrity over
unmeasured throughput; batching/scaling must be measured before large collection.

## Canonical revisions and lineage

Logical bar identity retains instrument/source/interval/start. Revision identity
is exactly the published domain fingerprint, including schema/canonicalization
versions. The database enforces the matching composite unique key; revision 0
and subsequent revisions coexist. Exact same-revision retries are no-ops; a
changed value at the same revision identity is REVISION_CONFLICT.

For number N>0, the predecessor must be number N-1 with the same logical identity,
dataset schema and canonicalization version. previousRevisionFingerprint must
equal that predecessor's **contentFingerprint**, not revisionIdentity. Revisions
cannot move receipt/availability earlier than their predecessor. Revision 0 has
no parent. A new processing-version chain starts at its own revision zero.

Every supplied raw reference is compared to the complete reconstructed committed
receipt reference (including source, instrument, content, parser/schema, identity
and local times). FK/session-position links reject nonexistent rows, while explicit
metadata checks reject contradictory references. Bars inserted earlier in the
same transaction can serve as parents; raw receipts in that transaction can serve
as lineage. The transaction is the unit that becomes durable.

Historical availableAt is never overwritten. After reopening, a 10:01 original
and a 10:05 revision remain separate; the existing pure DatasetSnapshotSpec at
10:03 includes only the original. Minimal read methods are readReceipt,
readRevision (exact identity, never latest) and readHighWater. There is no training
query, materialized DatasetSnapshot, selected-row digest or eligibility grant.

## Index DDL and intended uses

```sql
CREATE INDEX raw_content ON raw_receipts(content_fp);
CREATE INDEX raw_event_identity ON raw_receipts(event_identity_kind,event_identity_fp);
CREATE INDEX raw_series_time ON raw_receipts(provider,feed,source_class,asset_class,canonical_symbol,provider_symbol,event_sec,event_nano);
CREATE INDEX raw_availability ON raw_receipts(available_sec,available_nano);
CREATE INDEX bar_availability ON canonical_revisions(available_sec,available_nano);
CREATE INDEX bar_series_time ON canonical_revisions(provider,feed,source_class,asset_class,canonical_symbol,provider_symbol,interval_seconds,start_sec,start_nano);
CREATE INDEX lineage_receipt ON bar_lineage(session_id,sequence);
```

PK/unique indexes already cover raw session/sequence, revision identity, logical
series/version/revision, parent content and bar-to-lineage access. Secondary
indexes support duplicate/native conflict analysis, future exact source/instrument
time ranges, availability cuts and reverse lineage. They do not select latest
revision, erase history or implement replay/snapshot materialization.

## Integrity, journaling and concurrency

verifyIntegrity is an explicit consistent transaction with diagnostic PRAGMA/
SELECT statements only. It checks exact schema/format/version, foreign-key and
recursive-trigger settings, PRAGMA integrity_check, PRAGMA foreign_key_check,
validated session identity/source fields even for empty sessions, contiguous
counts/min/max versus high-water, every raw identity/content, native
identity contradictions, every canonical reconstruction, parent chain and lineage.
The sorted immutable report gives deterministic reason codes. It performs no
repair; total_changes is unchanged in tests. readHighWater independently validates
its range/count consistency before returning a committed boundary.

Journal mode is DELETE, synchronous FULL, busy timeout 5000 ms, foreign keys ON
on every supported connection. Android configures FKs with the database lifecycle
API; the host adapter explicitly enables/verifies the same invariants. Both use
IMMEDIATE writer reservation (Android beginTransactionNonExclusive), with owned
connection serialization and all-or-nothing commit/rollback. Read operations use
the same consistent transaction protocol; integrity SQL is read-only but reserves
the writer lock. Two independent host connections are tested with a latch barrier,
not sleeps: either one insert plus an identical retry, or one insert plus an
explicit conflicting rejection. No overwrite or lost high-water is acknowledged.

Busy/locked errors are reported as the distinct retryable `SQL_BUSY` code, not
RECEIPT_CONFLICT or generic SQL_FAILURE. Android maps SQLiteDatabaseLockedException
and SQLiteTableLockedException; the JDBC test adapter maps primary SQLite codes
5/6, including extended-code variants. A failed active transaction is rolled back
before that error propagates. There is no automatic blind write retry. A caller
may later retry the complete immutable batch; integrity diagnostics reporting
SQL_BUSY mean the scan was unavailable, not that corruption was established.
Reference: [Android lock exception semantics](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabaseLockedException).

DELETE journal avoids a persistent WAL sidecar and WAL checkpoint policy in V1.
Committed close/reopen and loss of an uncommitted writer connection are exercised.
This is not a device process-kill, power-loss or storage-device-failure certification.
No journal/WAL files are manually deleted or checkpointed. Future backup must use a
consistent SQLite backup or coordinated quiescent closed-store copy; copying an
active file is not a backup contract. A future WAL mode would require consistent
handling of its sidecars/checkpoints. No backup, retention or pruning job is built.

## Initial implementation validation and scope

Fresh verification used the existing Java/SDK/Gradle installation under C:\Android,
with `--offline --no-daemon --max-workers=2` and in-process Kotlin compilation.
Full Debug ran before full Release, both with `--rerun-tasks`; their XML was
archived before the targeted runs overwrote Gradle's working reports.

| Run | Exit | Suites | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|---|---|
| Full Debug, fresh | 0 | 126 | 2317 | 0 | 0 | 0 |
| Full Release, fresh after Debug | 0 | 126 | 2317 | 0 | 0 | 0 |
| Targeted storage Debug | 0 | 3 | 38 | 0 | 0 | 0 |
| Targeted storage Release | 0 | 3 | 38 | 0 | 0 | 0 |

2317 = 2279 published baseline + 38 new tests. Storage suite counts per variant:
MarketDatasetStoreTest=21, MarketStorageLifecycleTest=15,
MarketStorageIsolationTest=2. Fixed test inputs/latch coordination are used; there
are no sleep-based concurrency assertions and no new test framework.

All required categories PASS in both variants: DB_CREATION, DB_SCHEMA_VERSION,
EXACT_DECIMAL_ROUNDTRIP, NANOSECOND_ROUNDTRIP, SOURCE_IDENTITY_ISOLATION,
RAW_APPEND_ONLY, IDEMPOTENT_RETRY, CONFLICT_REJECTION, HIGH_WATER_ATOMICITY,
CANONICAL_REVISION_APPEND_ONLY, REVISION_PARENT_VALIDATION, LINEAGE_FOREIGN_KEYS,
TRANSACTION_ROLLBACK, CRASH_REOPEN, CONCURRENT_WRITE_INTEGRITY,
NO_LOOKAHEAD_PERSISTENCE, UNSUPPORTED_SCHEMA_FAIL_CLOSED, LEGACY_IMPORT_ZERO.

Safety scan: exit 0, allowed_phase2v_submit=11, suspicious=0, forbidden=0.
| Separate lint run | Exit | Errors | Warnings | Information | New 3.a.3 findings | Added findings |
|---|---|---|---|---|---|---|
| lintDebug | 1 | 1 | 14 | 1 | 0 | 0 |
| lintRelease | 1 | 1 | 14 | 1 | 0 | 0 |

Global lint is NOT green. The only error remains the pre-existing NewApi at
`core/Symbols.kt:40`; this file is unchanged. Main-source and test lint analyses
ran for the new storage code. No suppressions, baseline/configuration edits or
dependency updates were added. Compared to the archived earlier reports, 28 Debug
and 15 Release upgrade notices disappeared (AndroidGradlePluginVersion and
GradleDependency only); the untouched version catalog and offline report
differences are not claimed as source fixes. No new finding was added anywhere.

Static outcomes: MARKET_DB_DEPENDS_ON_PAPER=NO,
MARKET_DB_DEPENDS_ON_EXECUTION=NO, MARKET_DB_DEPENDS_ON_OPERATIONAL_ROOM=NO,
MARKET_DB_DEPENDS_ON_LEGACY_MARKET_REPOSITORY=NO,
MARKET_DB_HAS_NETWORK_REQUESTS=NO, MARKET_DB_HAS_COLLECTOR_WIRING=NO.
The seven published domain production files are unchanged and remain independent
of storage/Android. No operational Room schema or migration changed. Exact
source/test hashes were recorded after the successful fresh/targeted runs.

| Scope class | Paths | Contents |
|---|---|---|
| A storage production | 5 new | Five files listed in the architecture table |
| B storage tests | 4 new | Three suites and MarketStorageFixtures.kt |
| B existing test support | 1 modified | Narrow consumer-boundary update in MarketDatasetIsolationTest.kt |
| C documentation | 1 new | This report, including authoritative DDL reference/export |
| D build support | 0 | Existing sqlite-jdbc dependency reused |
| E unrelated edits | 0 | Three pre-existing showcase files excluded and preserved |

At the implementation checkpoint, total task paths=11. `git diff --check` and explicit trailing-whitespace/final-
newline checks on new files pass. Source baseline remains the required published
commit; staging was empty. Commit=NO, push=NO at that checkpoint. No later phase was implemented.
Evidence/logs/XML: `C:\Android\audits\phase-3a3-20261029`.

The initial three suites use temporary file-backed SQLite: MarketDatasetStoreTest,
MarketStorageLifecycleTest and MarketStorageIsolationTest, plus one fixture file.
The only pre-existing file changed is MarketDatasetIsolationTest: its old
no-external-consumer guard now permits the dedicated storage package to consume
the domain. It continues to reject every other production consumer, keeps all
seven pure-domain dependency checks, and adds no domain-to-storage dependency.

Legacy import=0. No legacy market repository/entity/DAO is referenced. No Windows
vela.db or G:\vela access occurred. Operational Room remains version 9 with no
schema/migration change; Paper history, execution authority, network contracts,
Release demo gates and operational reset/display paths remain unchanged. The
three unrelated user-owned showcase files are preserved.

Limitations: Android device runtime NOT YET VALIDATED; no automatic opening or
collection, provider parsing, Alpaca/IEX/TEST/broker access, scheduling/service,
quality engine, training, ML, features, labels, exports or replay. Integrity
verification currently materializes query rows and performs per-bar lineage/
parent reads; it is an explicit potentially expensive diagnostic, not a background
high-volume performance claim. Future collection/query scale needs measurement
and likely bounded streaming readers.

## Independent final audit — 2026-10-29

The actual schema, every storage method, Android initialization and host adapter
were reviewed independently of the original report and XML. Four defects were
closed before publication, without changing the published 3.a.2 domain or V1 DDL:

| Finding | Reproduction and correction |
|---|---|
| Empty-session identity/source fields were not included in integrity validation | Four corrupt-but-FK-valid empty ledgers were incorrectly reported valid. The full scan now reconstructs every session ID/source through domain validation and reports SESSION_IDENTITY. Registration/append also validate existing ledger identities. |
| SQLite lock contention was collapsed into generic SQL_FAILURE | A held writer lock with timeout zero produced SQL_FAILURE. Android/JDBC now report SQL_BUSY separately; an explicit later whole-batch retry succeeds without duplicate evidence. |
| `NOT LIKE 'sqlite_%'` treated underscore as a wildcard | A nonempty version-zero file or V1 file with sqliteXmarker could evade object checks. Exact-prefix GLOB filtering now rejects both without adding/dropping evidence or changing user_version. |
| Append/retry trusted a preexisting high-water without checking its committed prefix | A counter corrupted to 9 could accept sequence 10 despite missing earlier receipts, and identical retries could acknowledge a missing/corrupt ledger. Append now validates ledger existence and count/min/max consistency once per affected session before processing retries or inserts; a failure rolls back any session registration in that batch. |

The first two findings were reproduced before their fixes: five failing tests are
archived in `defects-before-fix-results` and `defects-before-fix.log`. All regressions
pass after correction. Three additional failing high-water cases were reproduced
and archived in `high-water-before-fix-results` / `high-water-before-fix.log` before
their correction. Additional focused regressions verify direct destructive
SQL on every table, schema/metadata mismatch variants, complete nano-time reopen,
canonical stored-fingerprint tampering, valid revision 2 and processing-version
chain isolation. An unsupported older user_version=-1 is rejected too.

### Independent schema inventory

An external host harness executed the compiled production `statements` in a new
disposable SQLite file, then enumerated table_info, index_list/index_info,
foreign_key_list, sqlite_master triggers, user_version and engine integrity.
The temporary database was closed/deleted; no existing database was inspected.
Full inventory: `C:\Android\audits\phase-3a3-final-audit-20261029\actual-schema-inventory.txt`.

| Table | Columns | INTEGER | TEXT | Primary key |
|---|---|---|---|---|
| market_metadata | 5 | 2 | 3 | singleton |
| ingest_sessions | 4 | 0 | 4 | session_id |
| session_high_water | 2 | 1 | 1 | session_id |
| raw_receipts | 37 | 10 | 27 | session_id, sequence |
| canonical_revisions | 31 | 13 | 18 | revision_id |
| bar_lineage | 6 | 3 | 3 | revision_id, session_id, sequence |
| bar_quality_flags | 2 | 0 | 2 | revision_id, flag |

There are 87 columns: 29 INTEGER and 58 TEXT. The independent regression pins all
87 names/types, primary keys, the seven explicit index column orders, parent FK,
lineage FK destinations and the reviewed DDL fingerprint. The schema contains
seven explicit indexes plus nine SQLite-created PK/UNIQUE autoindexes (16 total).
The nine autoindexes enforce session PK/source tuple, high-water PK, receipt PK,
bar revision PK/content uniqueness/logical-version-revision tuple, lineage PK
and quality-flag PK. Six FK definitions contain ten column mappings. All use
NO ACTION; none cascades an evidence mutation. Engine integrity=ok, FK violations=0,
user_version=1. DDL hash remains the V1 hash shown above.

| Trigger name(s) | Enforcement |
|---|---|
| market_metadata_update_blocked / market_metadata_delete_blocked | Immutable namespace/version/format metadata |
| ingest_sessions_update_blocked / ingest_sessions_delete_blocked | Immutable session/source association |
| session_high_water_delete_blocked | Retain committed ledger |
| raw_receipts_update_blocked / raw_receipts_delete_blocked | Immutable received evidence |
| canonical_revisions_update_blocked / canonical_revisions_delete_blocked | Immutable revision history |
| bar_lineage_update_blocked / bar_lineage_delete_blocked | Immutable source lineage |
| bar_quality_flags_update_blocked / bar_quality_flags_delete_blocked | Immutable descriptive flags |
| raw_sequence_guard | BEFORE INSERT requires the next sequence in that session |
| raw_high_water | AFTER INSERT advances that session's high-water |
| high_water_guard | BEFORE UPDATE permits only next committed receipt, no regression/jump |

The 16 triggers do not rely on the order of sibling triggers with identical
timing/event: the receipt BEFORE check precedes insertion, its AFTER advance sees
the inserted row, and the high-water BEFORE UPDATE guard validates that advance.
RAISE(ABORT) backs out the rejected statement; the repository/backend then rolls
back the entire batch when an exception escapes. recursive_triggers is enabled
and checked on supported connections so replacement deletes invoke DELETE guards.
The seven-table negative-SQL matrix covers UPDATE, DELETE, INSERT OR REPLACE and
UPSERT, checking actual constraint/trigger code 19 rather than accepting a syntax
error as proof. [SQLite conflict/trigger semantics](https://www.sqlite.org/lang_conflict.html)
explain why recursive triggers and outer rollback are necessary.

UPSERT appears only in these host negative tests, not production. All production
DDL/queries use longstanding SQLite tables/indexes/FKs/triggers, integer checks,
GLOB and parameters; no STRICT, RETURNING, modern UPSERT or newer SQL feature is
required on API 29. Android lock exception types are available below API 29.
Android compilation/static review is not a substitute for device validation:
`ANDROID_SQLITE_RUNTIME_VALIDATED=NO`.

The verifier is full rather than sampled: it visits every session and raw receipt,
every canonical revision (including its immediate parent), every referenced raw
lineage and quality flag. Engine/FK checks cover orphan rows and structural
violations; all revision chains are checked because every revision is visited.
No repair is performed and total_changes remains unchanged. DatasetSnapshot
specFingerprint still describes a logical specification only; no materialization,
selected-row digest or training permission is introduced.

### Fresh final-source validation

After the last high-water correction, full Debug was rerun with `--rerun-tasks`,
then full Release with `--rerun-tasks`. Only after both passed were the targeted
storage suites run in each variant. These are the publication-source results,
not the earlier implementation or intermediate audit runs:

| Run | Exit | Suites | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|---|---|
| Full Debug, fresh final source | 0 | 127 | 2347 | 0 | 0 | 0 |
| Full Release, fresh after Debug | 0 | 127 | 2347 | 0 | 0 | 0 |
| Targeted storage Debug | 0 | 4 | 68 | 0 | 0 | 0 |
| Targeted storage Release | 0 | 4 | 68 | 0 | 0 | 0 |

2347 = 2279 published-baseline tests + 68 storage tests. Per variant the storage
suites contain MarketDatasetStoreTest=21, MarketStorageLifecycleTest=16,
MarketStorageIsolationTest=2 and MarketStorageAuditRegressionTest=29. The fifth
test file is MarketStorageFixtures.kt. The audit adds 30 meaningful cases beyond
the initial implementation's 38: the 29 audit cases and one older-version case.

All required targeted categories pass in both variants:

```text
DB_CREATION=PASS
SCHEMA_V1=PASS
EXACT_DECIMAL_ROUNDTRIP=PASS
NANOSECOND_ROUNDTRIP=PASS
SOURCE_IDENTITY=PASS
APPEND_ONLY=PASS
NO_DESTRUCTIVE_REPLACE=PASS
IDEMPOTENT_RETRY=PASS
CONTENT_CONFLICT_REJECTION=PASS
HIGH_WATER_ATOMICITY=PASS
ROLLBACK=PASS
CRASH_REOPEN=PASS
CONCURRENT_WRITERS=PASS
REVISION_PARENT=PASS
REVISION_APPEND_ONLY=PASS
LINEAGE_INTEGRITY=PASS
FK_ENFORCEMENT=PASS
FINGERPRINT_TAMPER_DETECTION=PASS
NO_LOOKAHEAD_PERSISTENCE=PASS
SCHEMA_MISMATCH_FAIL_CLOSED=PASS
LEGACY_IMPORT_ZERO=PASS
```

The final XML archives are full-debug-final-results, full-release-final-results,
targeted-debug-final-results and targeted-release-final-results. Corresponding
logs are full-debug-final.log, full-release-final.log and
targeted-final-after-hwm.log. tested-source-final-manifest.json pins SHA-256 for
all eleven production/test paths; they were rechecked after the builds. No source
change occurred after these final runs. Evidence directory:
`C:\Android\audits\phase-3a3-final-audit-20261029`.

### Final publication scope

| Required class | Paths | Contents |
|---|---|---|
| A Market DB production | 5 new | Five storage files listed above |
| B Market DB tests | 5 new | Four storage suites and one JDBC fixture |
| C isolation tests | 1 modified | Narrow storage-consumer allowance in MarketDatasetIsolationTest.kt |
| D documentation | 1 new | This report |
| E justified build support | 0 | Existing dependency/tooling reused |
| F unrelated | 0 | Three user-owned showcase files excluded |

Total publication paths=12 (11 new, 1 modified). The three untracked showcase
files match their recorded hashes and remain outside the publication unit.
Whitespace/final-newline checks pass for all twelve paths; `git diff --check`
passes. The seven domain production files, operational Room/schema/migrations,
network/submit boundaries, price-trust/demo isolation and Paper guards are
unchanged. No new lint suppressions, dependencies or configuration changes exist.

### Final lint, static boundaries and readiness

Lint Debug and Release were run separately on the final tested source. Both main
and unit-test analyses executed. The final XML was compared by issue ID, severity,
message and complete file/line/column locations against the archived reports:

| Separate final lint run | Exit | Errors | Warnings | Information | Added anywhere | 3.a.3 findings |
|---|---|---|---|---|---|---|
| lintDebug | 1 | 1 | 14 | 1 | 0 | 0 |
| lintRelease | 1 | 1 | 14 | 1 | 0 | 0 |

Global lint is NOT green: the sole error remains unchanged NewApi at
`core/Symbols.kt:40`. No new finding or suppression was introduced. Evidence:
lint-debug-final.log/xml and lint-release-final.log/xml. The repeated safety scan
exited 0: allowed_phase2v_submit=11, suspicious=0, forbidden=0.

```text
OPERATIONAL_ROOM_VERSION=9
Migration9To10=NO
OPERATIONAL_ROOM_SCHEMA_CHANGED=NO
NETWORK_CONTRACT_CHANGED=NO
SUBMIT_BOUNDARY_CHANGED=NO
LEGACY_IMPORT_PATHS=0
MARKET_DB_EXECUTION_AUTHORITY_PATHS=0
AUTO_MARKET_COLLECTION=NO
ANDROID_SQLITE_RUNTIME_VALIDATED=NO
HOST_SQLITE_JDBC_INTEGRATION_VALIDATED=YES
```

All mandatory publication checks are satisfied, with the explicitly permitted
pre-existing lint error disclosed: `READY_3A3_TO_PUBLISH`. Only the twelve audited
paths may be staged. The publication unit is one commit containing this report,
with message `feat: add isolated market dataset SQLite storage`; no amend or
extra documentation commit is permitted. Immediately before normal push,
origin/main must still equal the source baseline above; otherwise publication
stops without merge, rebase or force.

The self-containing commit hash cannot be embedded in this one-commit report.
The post-push full hash, HEAD/origin equality, clean tracked tree, empty staging
and intact excluded showcase hashes are recorded in the final handoff and the
external audit directory's publication-result.json, after actual verification.

After audited publication, the next phase is **3.a.3-R1 — Separate Market DB Runtime
Integrity Validation**. It is not started here. Runtime/emulator/device/app/IEX/
TEST/broker GET/POST/installed DB actions remain zero; 3.a.4 is not started.
