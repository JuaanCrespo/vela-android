# Phase 3.a.2 — Market Dataset core domain

Baseline: `020786ded208cd6887dfc8b89e485bb52b3fe844`, verified against origin after fetch.
Implementation result: `PASS_3A2_MARKET_DATASET_CORE_DOMAIN_IMPLEMENTED`.
Independent final audit: `READY_3A2_TO_PUBLISH`.
Last validated: 2026-10-29. Historical implementation/revalidation results are
preserved below. Global lint retains its documented pre-existing failure.
Publication unit: the single commit containing this domain, its tests and this
report; its full hash and remote confirmation are recorded in the delivery report.

## Boundary and prior-domain audit

Package: `com.vela.android.lab.core.marketdataset.domain`, under
`android/app/src/main/kotlin/com/vela/android/lab/core/marketdataset/domain`.
This follows the existing small `core` namespace without putting dataset semantics
inside the operational market, UI, Alpaca, Paper reconciliation or Room packages.

The architecture/trust/hardening references are the four `phase-3a1*` documents.
`OneMinuteBar` uses floating-point OHLC and synthetic volume, `MarketTick` has
millisecond clocks, and `MarketDataProvenance` / `ExecutionReferencePrice` describe
execution authority. None is reused as dataset truth. `DecimalQuantity` provided a
useful normalization convention, not a dependency or a conversion path.
`core/Symbols.kt` performs URL decoding and crypto-pair inference: those behaviors
are inappropriate implicit durable-identity transformations and are not reused.
Existing domain validation is exception-based; this package refines it with stable
`DatasetValidationException.error` codes and never puts input text into messages.

No pre-existing production file is changed. In particular, price trust hardening,
Release demo gates, display labels, resets and Room stay untouched. The earlier
`DEVICE_VALIDATION_DEFERRED_SAFE_UI_PATH_UNAVAILABLE` remains deferred, not PASS.

## Production files and actual dependency graph

| File | Responsibility | External imports |
|---|---|---|
| `MarketDatasetValues.kt` | Errors, version/namespace IDs, fingerprint, framing, defensive lists | `java.security.MessageDigest`, `java.util.Collections` |
| `ExactMarketDecimal.kt` | Exact canonical numeric value and raw lexical evidence | `java.math.BigDecimal` |
| `MarketIdentity.kt` | Provider, feed, provenance, instrument, conservative candidate prerequisite | `java.util.Locale` |
| `MarketTime.kt` | UTC seconds/nanos, local sequence, interval, range, availability | `java.time.Instant` |
| `RawMarketEvent.kt` | Typed raw payloads, envelope, content/native/receipt identities, references | None |
| `CanonicalMarketBar.kt` | Immutable OHLC revision and raw lineage | None |
| `DatasetSnapshotSpec.kt` | Immutable source/instrument/version/time/high-water cut specification | None |

All other dependencies are intra-package or Kotlin/JDK primitives. No Android,
Compose, Room, SQLite, filesystem, HTTP, coroutine, UI, execution, Risk Core or
position-reconciliation dependency. No external production consumer is wired.

## Source and instrument identity

`MarketSourceIdentity = provider + feed + sourceClass`. Provider/feed are separate
validated value types containing stable uppercase protocol codes, not display
labels. Named codes cover ALPACA/LOCAL/UNKNOWN and IEX/TEST/DEMO/UNKNOWN. New codes
can be represented without adding an integration or making a new source trusted.
There is no SIP implementation or named SIP support promise.

Source classes: REAL_PROVIDER, SYNTHETIC, DEMO, LEGACY_UNKNOWN, UNKNOWN.
Asset classes: EQUITY, SYNTHETIC, UNKNOWN. Crypto normalization in the old app does
not establish a crypto market adapter; no CRYPTO/FX support is claimed here.

Instrument identity includes assetClass, provider, feed, canonicalSymbol and exact
providerSymbol. All five participate in equality and its content-derived identity.
IEX/SPY and TEST/SPY can never share a series. Provider aliases are not silently
merged; a future registry may assign a surrogate separately from this logical key.

Symbols must be nonblank, unpadded, free of ISO control characters and valid UTF-16.
Punctuation, internal spaces, Unicode and pair notation remain representable.
Constructor text is preserved. Optional `CanonicalMarketSymbol.uppercase` explicitly
uses Locale.ROOT; it does not trim, decode URLs or guess asset classes. Full
Unicode case folding may conflate canonical spellings; exact providerSymbol remains
in identity and therefore prevents implicit provider-alias merging.

## Exact numeric contract

`ExactMarketDecimal` accepts `[+-]?[0-9]+(?:\.[0-9]+)?`, 1–512 characters.
No exponent syntax, separators, spaces, NaN, Infinity, implicit rounding or binary
floating-point constructor. Oversized values are rejected, never rounded.
Canonical output is plain decimal, with redundant trailing zeros removed and zero
normalized to `0`. `1.0 == 1.00` and their hash codes agree. Values differing at
`0.100000000000000001` versus `0.100000000000000002` remain distinct.

`RawMarketDecimal` keeps the original accepted lexeme and its exact value. Thus
`+001.00` and `1.0` differ as raw evidence but agree numerically. Raw fingerprints
include the original lexeme. Canonical bar numeric fingerprints use normalized
values. This preserves provider precision without treating scale as numeric truth.

The generic decimal allows negatives. Field boundaries enforce prices/VWAP > 0,
sizes/volume >= 0 and tradeCount >= 0. Missing volume, sizes, tradeCount and VWAP
are null, never synthetic zero. There is no arithmetic/feature engine or computed
VWAP policy in this phase; supplied values require future canonicalization evidence.

## Nanosecond time and causal order

`MarketTimestamp` uses `epochSecond: Long` plus `nanoAdjustment: Int` in
0..999,999,999, within Instant's structural range. It never packs all time into a
Long nanosecond scalar and therefore avoids the architecture sketch's 2262 limit.
This is the lossless equivalent explicitly allowed by the 3.a.2 request.
Equality, lexicographic ordering and canonical components preserve a 1 ns difference.
Addition checks both Long overflow and representable bounds, returning a stable
domain error. Historical/future dates are structurally valid; no current clock is read.

- eventTime: provider-defined event instant, explicitly supplied.
- receivedAt: local receipt observation, explicitly supplied.
- availableAt: first legal downstream availability, explicitly supplied; must not
  precede receipt. No implicit fallback to eventTime or receipt time.

eventTime can be after receivedAt: device/provider skew is not clamped or hidden.
Clock quality/regression classification remains deferred. Canonical bars cannot
claim availability or receipt earlier than their referenced evidence.
Original timestamp lexeme extraction and provider bar-start interpretation are
adapter concerns for 3.a.4; no existing parser is changed or provider rule assumed.

`IngestPosition` = session identifier + nonnegative sequence, with zero valid.
It is not provider order, a trade ID, time or a database row ID. No generator or
collector-session lifecycle exists. Session IDs must be unique in their future
dataset namespace; this domain does not allocate them.
No implicit global event ordering comparator is introduced. Event order, receipt
sequence and visibility are intentionally separate.

`DatasetId` and `IngestSessionId` identify explicit namespaces; `ProviderEventId`
is optional native evidence identity, not a locally invented provider ID.
DatasetSchemaVersion, ParserVersion, CanonicalizationVersion and QualityPolicyVersion
are distinct positive integer value types. They are not Android app versions or
operational Room versions. A version describes a contract, not successful execution
of a parser, canonicalizer or quality engine (none is implemented here).

## Raw evidence and canonical fingerprinting

Payload variants: Quote, Trade, Bar (BAR or BAR_UPDATE) and DigestOnly
(STATUS/CONTROL/UNKNOWN). They represent possible evidence shapes, not claims
that current Alpaca parsing/subscriptions support every shape. Typed decimals and
optional fields avoid `Map<String, Any>`. DigestOnly holds no arbitrary text body.
Quotes may be locked/crossed: interpretation is deferred to quality policy.

Envelope fields explicitly bind source/instrument, event time, receipt and
availability times, session sequence, parser/schema versions, typed payload and
optional provider event ID. No credential, auth-header, account or token field exists.
An adapter must provide only allowlisted, non-secret IDs/fields; structural validation
cannot prove arbitrary caller-supplied strings free of sensitive content.

Fingerprint algorithm: SHA-256 over canonical UTF-8. Framing V1 emits each field as
`utf8ByteLength:value`; null is `-1:`. The record tag is the first framed field.
Nested records are framed strings. Fixed field order, explicit nulls and framing
avoid delimiter, empty/null and Unicode ambiguities. Unpaired UTF-16 surrogates are
rejected before UTF-8 conversion. No object hash, clock, randomness or locale enters
serialization. Enum protocol names and record tags are frozen V1 vocabulary; future
changes require explicit schema/parser/canonicalization version decisions.
V1 must never be repurposed or old identities silently recomputed under a changed
encoding. A new encoding requires a new protocol tag/version and explicit reader
dispatch; only the fixed V1 encoding is implemented here.
Length framing removes structural concatenation ambiguity; it does not make
SHA-256 mathematically collision-free. Fingerprints are content/dedupe identities,
not signatures, authentication, or proof of trustworthy provenance.

- Content fingerprint: source/instrument/type/eventTime, parser/schema versions,
  optional native ID and full supported typed payload, preserving raw decimal text.
  Receipt/availability/session sequence are excluded so repeated receipts remain
  recognizable. Envelope serialization includes those local fields separately.
- PROVIDER_ID: native ID scoped by instrument/source/event type. A changed payload
  under the same native identity has a different content fingerprint; references
  carry both so conflicts/corrections are not hidden.
- DETERMINISTIC_FINGERPRINT: content fallback when native ID is absent.
- LOCAL_INGEST_ID: separate receipt identity from session + sequence, never replacing
  content identity and never used to assert provider truth.

Receipt identity is local to the ingest namespace. Cross-dataset storage keys must
also carry DatasetId; identical session/sequence values in separate datasets do
not establish globally unique receipts. Namespace allocation and persistence are
deferred, as are native-provider ID uniqueness rules.

No dedupe pipeline is implemented. Identical native-ID-free evidence can represent
duplicates or indistinguishable legitimate observations. Later ingestion must retain
receipt counts/quality evidence, not silently collapse by symbol+time. Unknown
provider fields are not parsed or discarded here: future adapters must extend the
versioned payload vocabulary or retain safe fingerprints without promoting unknown
messages to numeric truth.

## Canonical bars, revision and immutable lineage

Fixed-second BarInterval supports 1 minute and any positive future fixed duration.
Boundaries are UTC epoch-aligned, including negative epoch seconds. Contract is
`[start,end)`, end > start and exact interval duration. A 86400-second interval is
not a claim about local exchange sessions or calendar days. No resampling is done.

OHLC are strictly positive; high >= open/close/low and low <= open/close. Flat bars
are valid. No price heuristics or synthetic-volume fallback exists.
Canonical values also carry receivedAt, availableAt, volume/count/VWAP optionals,
schema/canonicalization versions, finality, revision and explicit raw references.

Logical bar identity includes instrument/source/interval/start. Revision starts at
zero as required by the concrete 3.a.2 regression example (superseding the older
architecture sketch's start-at-one suggestion). Revision zero has no parent; later
revisions require a parent fingerprint. REVISED requires revision > 0. PROVISIONAL,
FINAL and REVISED are caller-supplied states, never inferred from the wall clock.
Revision identity is logical key + revision number + schema/canonicalization versions.
The content fingerprint additionally binds all values, times, finality and lineage.
Conflicting content at the same revision key remains detectable; neither value
destructively replaces the other. Parent existence and revision-chain validation
belong to future append transactions/canonicalization, not these value constructors.

BarEvidence defensively copies, sorts and wraps references and distinct quality
flags in unmodifiable lists. Empty/duplicate lineage is rejected. References must
match the bar source/instrument. Minimal flags LATE/PARTIAL/UNKNOWN are descriptive,
not a gap engine, quality score or eligibility bit.
References retain raw parser/schema versions explicitly; a bar cannot hide inputs
from outside the snapshot's version scope behind its canonicalization version.

## DatasetSnapshotSpec and no-lookahead

The immutable spec identifies dataset ID, dataset schema, parser,
canonicalization and quality-policy versions, exact source/instrument scopes,
half-open event-time range, inclusive availability cutoff and per-session high-water.
Scope collections are defensively copied and canonically sorted; conflicting
high-water entries for the same session are rejected, never resolved by iteration.
The spec fingerprint identifies the selection contract, NOT a selected-row digest.
There is no query, materialization, storage reader or snapshot-row content hash yet.

Availability is necessary: `availableAt <= cutoff`. Equality at cutoff is included;
one nanosecond later is excluded even when eventTime is earlier. A 10:00 bar known
at 10:01 can be visible at 10:03; its 10:05 correction cannot. Every referenced raw
position must fit the corresponding session high-water. Canonical bars must fit
entirely inside the event range and match version scope.

The converse case is structurally possible: a provider may claim eventTime after
the availability cutoff while the information was already received/available.
The pure cut checks event range and availability independently; it includes such
raw evidence if the explicit event range contains it and all other prerequisites
pass. It does not silently clamp provider time or treat cut membership as proof
of clock quality. To exclude timestamps after T, a caller can explicitly bound
the event range at T (its end remains exclusive). Clock-skew quality and training
eligibility remain future policy; no automatically usable future market truth is
inferred from a provider timestamp. A permanent regression fixes this exact behavior.

Membership requires the conservative source prerequisite: currently only
REAL_PROVIDER / ALPACA / IEX / EQUITY. UNKNOWN, LEGACY_UNKNOWN, SYNTHETIC and DEMO
are excluded even if explicitly named in scope; labeling TEST as REAL_PROVIDER
does not promote it. Raw evidence can still represent quarantined sources without
making them eligible for a dataset cut. Every raw reference must match the cut's
parser and schema versions, including mixed-version lineage.
Source candidacy and cut membership are prerequisites, not training permission.
Training remains unavailable until the future versioned quality/finality gates,
snapshot integrity and ingestion proofs exist. No mutable eligibility flag exists.
No future feed/provider or unknown asset gains a positive default.

## Equality, immutability and allocation

Decimals use normalized BigDecimal equality/hash. Raw decimals use original lexical
equality. Other value objects use all constructor fields. BarEvidence and snapshot
scopes use immutable value lists, not caller object identity. Data-class `copy`
reruns invariants and recomputes derived fingerprints. Equality never serializes
JSON or computes a new fingerprint. SHA-256 and framed UTF-8 strings allocate when
identities are constructed; nested framing allocates short strings/byte arrays for
lengths. Scope membership is linear in scope/lineage size. These are explicit
high-volume concerns for later measurement, not a performance claim. No reflection
or mutable global cache is used in production.
Scope construction also checks instrument/source compatibility by scanning sources
for each instrument (O(instruments * sources)); scope/reference sorting is O(n log n).
These are validation/allocation costs, not JSON/reflection-based identity generation.

## Tests and validation

Seven suites cover decimals, source/instrument identity, timestamps, raw evidence,
bars/revisions, snapshot cuts, and static isolation. Parameterized malformed-input
tests and fixed-seed (302) / bounded-loop properties cover precision, ordering,
normalization, OHLC boundaries and repeatability without a new framework.
Isolation tests positively assert that the new package exists and contains seven
production files before checking imports, absence of legacy import paths, runtime
wiring, nondeterminism and operational Room v9.

Initial fresh verification on 2026-10-05 (JUnit XML counts, not Gradle task counts):

| Run | Exit | Suites | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|---|---|
| Full Debug, `--rerun-tasks` | 0 | 123 | 2275 | 0 | 0 | 0 |
| Full Release, `--rerun-tasks`, after Debug | 0 | 123 | 2275 | 0 | 0 | 0 |
| Targeted Market Dataset Debug | 0 | 7 | 98 | 0 | 0 | 0 |
| Targeted Market Dataset Release | 0 | 7 | 98 | 0 | 0 | 0 |

The full count is the 2177-test baseline plus 98 new tests, in each variant.
Targeted tasks actually executed; only their unchanged compilation prerequisites
were up to date. The earlier initial targeted development run is not used as
final evidence; the table covers the final parser-lineage/source-scope hardening.

| New suite | Tests per variant |
|---|---|
| ExactMarketDecimalTest | 25 |
| MarketIdentityTest | 20 |
| MarketTimeTest | 9 |
| RawMarketEventTest | 12 |
| CanonicalMarketBarTest | 14 |
| DatasetSnapshotSpecTest | 14 |
| MarketDatasetIsolationTest | 4 |

Required targeted outcomes: EXACT_DECIMAL_PRECISION=PASS, NANOSECOND_TIME=PASS,
SOURCE_IDENTITY_ISOLATION=PASS, INSTRUMENT_IDENTITY=PASS,
RAW_FINGERPRINT_DETERMINISM=PASS, BAR_INVARIANTS=PASS, REVISION_IDENTITY=PASS,
AVAILABLE_AT_NO_LOOKAHEAD=PASS, UNKNOWN_FAILS_CONSERVATIVELY=PASS,
LEGACY_NOT_TRAINING_AUTHORITY=PASS. Additional regressions cover immutable
collections, mixed parser/schema lineage and explicit scopes that try to promote
quarantined source classes or a TEST feed mislabeled REAL_PROVIDER.

Safety scan: `android/scripts/safety-scan.ps1`, exit 0,
`allowed_phase2v_submit=11 suspicious=0 forbidden=0`.

| Lint invocation | Exit | Errors | Warnings | Information | Findings in 3.a.2 files | Added vs prior report |
|---|---|---|---|---|---|---|
| `:app:lintDebug` | 1 | 1 | 55 | 1 | 0 | 0 |
| `:app:lintRelease` | 1 | 1 | 42 | 1 | 0 | 0 |

Global lint is NOT green. Both fresh invocations fail on the same pre-existing
`NewApi` at `core/Symbols.kt:40`: URLDecoder's Charset overload requires API 33 or
desugaring, while minSdk is 29. That file is untouched. The issue sets match the
archived pre-validation reports by ID, severity, message and source locations;
their counts also match the published 3.a.1-D documentation. No new findings in
production or test files, no new suppression, no lint baseline/configuration
change. The earlier informational `AutoboxingStateCreation` remains outside this
phase. No out-of-scope lint repair was performed.

Validation uses the existing project toolchain entirely from C:
`C:\Android\Android Studio 2026.1.2\jbr`, SDK `C:\Android\Sdk` and Gradle home
`C:\Android\gradle-home`. The repository remains `G:\vela-android`.
Every Gradle invocation uses `--offline --no-daemon --max-workers=2` and
`-Pkotlin.compiler.execution.strategy=in-process`. Full suites run in order:
`:app:testDebugUnitTest --rerun-tasks`, then
`:app:testReleaseUnitTest --rerun-tasks`. Final targeted runs use
`--tests 'com.vela.android.lab.core.marketdataset.domain.*'` for each variant.
Full-suite XML reports are archived before targeted runs overwrite Gradle output.
Logs and archived XML are local audit artifacts under
`C:\Android\audits\phase-3a2-20261005`; they are not source changes or staged files.

## Revalidation on 2026-10-29

The resubmitted attachment is byte-identical to the original 3.a.2 request.
`git fetch origin` completed; HEAD and origin/main both remain
`020786ded208cd6887dfc8b89e485bb52b3fe844`. The existing implementation was reviewed
and retained. SHA-256 checks of all seven production and eight test files before
and after validation confirm no source/test edits during this revalidation.
Only this report was updated in the repository during this run.

| Run | Exit | Suites | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|---|---|
| Full Debug, `--rerun-tasks` | 0 | 123 | 2275 | 0 | 0 | 0 |
| Full Release, `--rerun-tasks`, after Debug | 0 | 123 | 2275 | 0 | 0 | 0 |
| Targeted Market Dataset Debug | 0 | 7 | 98 | 0 | 0 | 0 |
| Targeted Market Dataset Release | 0 | 7 | 98 | 0 | 0 | 0 |

Both full suites and both targeted test tasks executed. Targeted counts and
required category outcomes remain as documented above. Full XML results were
archived before targeted executions replaced Gradle's working reports.
Safety scan was executed again: exit 0, 11 allowed / 0 suspicious / 0 forbidden.

Both `lintDebug` and `lintRelease` were invoked separately and exited 1 on the
same baseline `NewApi` at `core/Symbols.kt:40`. Gradle reused unchanged lint
analysis/report tasks marked UP-TO-DATE; no forced fresh lint analysis is claimed.
The reports retain 1 error / 55 warnings / 1 information item for Debug and
1 error / 42 warnings / 1 information item for Release. Comparison against the
previous archived reports finds zero added issues and zero findings in the new
domain/test files. New suppressions=0. Global lint remains NOT green.

Static dependency, determinism, legacy-import, Room v9, scope and whitespace checks
were repeated successfully. All three showcase SHA-256 hashes still match the
initial precheck. Staging is empty; runtime, stage, commit and push actions remain
zero. No later phase was implemented.

Current logs, XML archives, prior lint reports for comparison and the source/test
hash manifest are under `C:\Android\audits\phase-3a2-revalidation-20261029`.
The original evidence under `C:\Android\audits\phase-3a2-20261005` is preserved.
Result remains `PASS_3A2_MARKET_DATASET_CORE_DOMAIN_IMPLEMENTED`.

## Independent final audit — 2026-10-29

Audited the actual production types, constructors, equality/hash implementations,
collection copies, canonical field framing and inclusion predicates independently
of the earlier XML/report claims. The seven production files required no semantic
change. Four audit regressions were added in three existing test files:

| Audit gap | Closure |
|---|---|
| Only the primitive SHA-256 `abc` vector was fixed | A complete raw-event literal canonical string and fixed expected SHA-256, calculated independently with PowerShell/.NET rather than production serialization |
| Existing snapshot-order regression had only one instrument | Two sources, three instruments and three session high-waters are permuted; spec equality, hash, canonical text and fingerprint all remain equal |
| Provider eventTime after cutoff was not explicitly specified | Regression and policy text above prove independent event-range/availability bounds, without inferring clock quality or training authority |
| Bar version changes lacked a direct identity regression | Schema/canonicalization changes preserve logical bar identity and change both revision and content fingerprints |

Complete event golden vector: ALPACA / IEX / REAL_PROVIDER / EQUITY / SPY,
event UTC components `(1700000000, 123456789)`, parser 3, schema 2, native ID
`provider-42`, QUOTE bid `0.100000000000000001`, ask `0.100000000000000002`,
bid size `12.5000`, ask size `0`. Expected content SHA-256:
`6bf0f962f0b39d47a99da1e5f7fd6bdac9c142e0b4ec604b83caed05e5bda6cc`.
The test fixes receipt/availability/session data too; as documented, those local
fields do not participate in the provider-content fingerprint.

Fresh sequential audit verification, after all four regressions were added:

| Run | Exit | Suites | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|---|---|
| Full Debug, `--rerun-tasks` | 0 | 123 | 2279 | 0 | 0 | 0 |
| Full Release, `--rerun-tasks`, after Debug | 0 | 123 | 2279 | 0 | 0 | 0 |
| Targeted Debug | 0 | 7 | 102 | 0 | 0 | 0 |
| Targeted Release | 0 | 7 | 102 | 0 | 0 | 0 |

Final suite counts per variant: ExactMarketDecimalTest=25, MarketIdentityTest=20,
MarketTimeTest=9, RawMarketEventTest=13, CanonicalMarketBarTest=15,
DatasetSnapshotSpecTest=16, MarketDatasetIsolationTest=4.
2279 = 2177 baseline tests + 102 domain tests. Full XML was archived before the
targeted tasks executed; prior XML was not used as fresh audit evidence.

All required targeted outcomes are PASS in Debug and Release:
EXACT_DECIMAL_PRECISION, DECIMAL_EQUALS_HASH_CONTRACT, NANOSECOND_TIME,
SOURCE_IDENTITY_ISOLATION, INSTRUMENT_IDENTITY, RAW_FINGERPRINT_DETERMINISM,
FINGERPRINT_SERIALIZATION_UNAMBIGUOUS, BAR_INVARIANTS, REVISION_IDENTITY,
AVAILABLE_AT_NO_LOOKAHEAD, REVISION_CUTOFF_NO_LOOKAHEAD,
UNKNOWN_FAILS_CONSERVATIVELY, LEGACY_NOT_TRAINING_AUTHORITY,
SNAPSHOT_ORDER_DETERMINISM.

Safety scan executed: exit 0, allowed_phase2v_submit=11, suspicious=0, forbidden=0.

| Separate lint invocation | Exit | Errors | Warnings | Information | 3.a.2 findings | Added findings |
|---|---|---|---|---|---|---|
| `:app:lintDebug` | 1 | 1 | 42 | 1 | 0 | 0 |
| `:app:lintRelease` | 1 | 1 | 29 | 1 | 0 | 0 |

Global lint is NOT green: the sole error is still NewApi at `core/Symbols.kt:40`.
Both invocations reanalyzed the changed unit-test sources and regenerated their
reports; unchanged main-source analysis was UP-TO-DATE. Each current report omits
13 prior dependency-upgrade notices (one AndroidGradlePluginVersion and twelve
GradleDependency findings in untouched `gradle/libs.versions.toml`). Per-issue
comparison confirms zero added findings. The offline invocations did not edit
dependencies, the version catalog, lint configuration or suppressions. The lower
warning counts are recorded as report differences, not claimed source fixes.

Equality/hash audit: canonical BigDecimal values are normalized before storage,
including a single zero representation; raw lexeme equality remains separate.
Other leaf/value types use generated structural equality/hash. BarEvidence and
DatasetSnapshotScope explicitly compare/hash their frozen value lists. Snapshot
copies therefore retain structural equality across input orders. No authoritative
value can be externally mutated after fingerprint construction.

Static audit: no wall clock, randomness, default locale/timezone, float authority,
filesystem, Android/Room/network/UI/execution dependency or external production
consumer. Legacy import paths=0. All version types are explicit positive value
objects. No default branch promotes unknown sources. Room=9; schema changes=0;
new Entity/DAO=0; Migration9To10=NO; Market DB=NOT YET. Previous-phase production
files, execution gates, demo gates, reset/display behavior and network contracts
are unchanged. Runtime/device/emulator/app/IEX/TEST/broker GET/POST actions=0.

Scope: A=7 production files, B=8 test files, C=1 report, D=0 support, E=0 unrelated.
The three user-owned showcase files remain outside the publication artifact and
match the precheck hashes. Source/test hashes match the fresh tested inputs.
Whitespace and `git diff --check` pass. No blocker remains:
`READY_3A2_TO_PUBLISH`.

Evidence directory: `C:\Android\audits\phase-3a2-final-audit-20261029`.
The publication workflow explicitly stages only those 16 paths, reviews the
cached diff, makes one commit, fetches origin to check the race against the
required baseline, pushes normally, then fetches/verifies HEAD=origin/main.
The final commit hash and push verification are delivery evidence, not a
self-referential hash embedded into this commit's own contents.

## Static audit and pre-publication diff classification

- MARKET_DATASET_DEPENDS_ON_ANDROID=NO; ROOM=NO; NETWORK=NO; EXECUTION=NO; UI=NO.
  The actual five JDK imports are listed in the production-file table above.
  Static scans across all Kotlin/Java source variants find zero external consumers
  of `core.marketdataset`, corroborating the permanent isolation tests.
- LEGACY_MARKET_IMPORT_PATHS=0. No `market_bars_1m` adapter, legacy row copying,
  provenance relabeling or trusted-price conversion exists in the new domain.
- DETERMINISM_AUDIT=PASS: no current clock, random ID, default locale/timezone,
  Float/Double authority or object identity in production canonicalization.
  Only tests use fixed-seed Random and temporarily change JVM defaults in finally-
  restored blocks. No production reflection, JSON or IO dependency.
- Room remains 9; schema changes=0; Migration9To10=NO; separate market DB=NOT YET.
  NETWORK_CONTRACT_CHANGED=NO. Existing execution, demo and legacy UI behavior is
  untouched. Runtime=0; emulator/app/installed DB/IEX/TEST/broker GET/POST actions=0.
- `git diff --check` passes. Because the new files are intentionally untracked,
  a separate UTF-8 read also checks all 16 task files for trailing whitespace and
  a final newline: zero problems.

| Class | New/changed task paths | Exact scope |
|---|---|---|
| A: production | 7 new | Seven domain files in the production table |
| B: tests | 8 new | Seven named test suites plus `DatasetTestFixtures.kt`, under the same domain package in `src/test/kotlin` |
| C: documentation | 1 new | This document |
| D: build/test support | 0 | No dependency or Gradle configuration change |
| E: unrelated introduced/modified | 0 | No unrelated edit |

Before publication: tracked modifications=0 and staging empty; no stage, commit
or push had been performed. HEAD and origin/main both equaled the required
baseline `020786ded208cd6887dfc8b89e485bb52b3fe844`.
The three allowed, pre-existing untracked showcase files are not part of the task
diff. SHA-256 comparison with the precheck confirms all three intact:

| Preserved file in `ui/showcase/` | SHA-256 |
|---|---|
| VelaShowcaseComponents.kt | A36707DB0FAF4E98FCC704DDCF917D5966E37E691AA4D2EA5C510AB5BA13B4FD |
| VelaShowcaseFixtures.kt | 825D97E44019A0D5E13569C01F5307E5E807BCFD17141D3DEADEB265DFB449E6 |
| VelaShowcaseScreen.kt | 67102DED38C760E05B1436945FE789CF406276D931CAC1CF0CD9A05D3DA3070F |

## Deferred work and explicit 3.a.3 handoff

- 3.a.3: separate market database, SQLite schema/indexes, append transactions,
  persisted high-water and revision-parent checks, retention and physical partitions.
  Do not import or relabel any `market_bars_1m` row. Preserve/quarantine it.
- 3.a.4: provider documentation verification, Alpaca raw parsers, exact timestamp
  and numeric lexemes, IEX ingestion, quote/trade/bar adapters, full payload-version
  extensions, canonicalization, dedupe, late-event application and revision creation.
- 3.a.5: collector sessions, connection events, clock quality, gap classification,
  dataset quality reports and actual training eligibility gates.
- 3.a.6+: query API, offline replay, ReplayClock, Parquet/export and runtime validation.

No persistence/DAO/schema/migration, WebSocket/REST/subscription change, ingestion,
replay, features, labels, ML, services, broker GET/POST or device access in 3.a.2.
Room=9; separate dataset database=NOT YET. After this audited publication, the next
phase is 3.a.3 — Separate Market Database. It is not started in this change.
