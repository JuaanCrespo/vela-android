# Phase 2.x.4 — Paper history integrity closure

## Baseline and scope

Audit started 2026-09-15 at `2aef3220b089c8bc7f3adff3a36e7c263b3e115b`.
`git fetch origin` succeeded; HEAD and origin/main matched. The tracked worktree
and staging area were empty before the integrity audit started.

The three pre-existing migration changes (README, Check-EmulatorClock.ps1,
Verify-SafeApk.ps1) were preserved in
`C:\Desarrollo\phase2x4-infrastructure-backup-20260915` and temporarily isolated.
The ignored SDK settings continue to use C:\Android; this audit does not require
moving the repository, re-enabling manual submit, or using the old G toolchain.
The original phase prompt prohibited commits. The user's later, explicit
instruction authorizes two separate local commits: the integrity closure (A)
and, only if justified by audit, Android toolchain paths (B). That instruction
supersedes the no-commit requirement; push, merge, pull and rebase remain outside
scope. Validation was completed on 2026-09-17.

## Gap reevaluation before implementation

| Gap from 2.x.1 | At baseline | Evidence / missing proof |
| --- | --- | --- |
| A. Temporal ordering | PARTIAL | DAO uses durable ids and repository tests latest/range rollback; isolated lifecycle equal-time/rollback proof missing. |
| B. Lifecycle contradictions | PARTIAL | `validateLifecycle` detects terminal regressions, terminal field changes and decreasing fills; existing combined test can mask individual missing branches. |
| C. Duplicate lifecycle payload | CLOSED | Existing `partial regression and repeated payload are explicit without erasing observations` compares different observedAt, equal fingerprints and both retained rows; viewer timeline test retains both. |
| D. Real SQL query ordering | PARTIAL | Repository fake mirrors the DAO SQL; no host execution of the actual queries on the exported Room v7 schema. |
| E. Projection consistency | PARTIAL | `matchesProjection` compares all requested fields, but baseline test changes them together. |
| F. Legacy missing metadata | CLOSED | Canonical legacy test yields only LEGACY_SUBMIT_METADATA_UNKNOWN; viewer renders nullable fields. Strengthen the existing viewer test with the warning integrity state. |
| G. Canonical reconstruction | PARTIAL | Complete aggregate reconstruction is tested; replacement repository/DAO from independent durable records needs explicit coverage. |
| H. History read-only | CLOSED | Narrow reader, query-only DAO, reflection/source contracts and viewer-only actions. Add no redundant control or endpoint; reconstruction test also checks all source collections remain unchanged. |

Only test coverage and test-only infrastructure were added for the closure. Production code,
Room entities/schema/migrations, UI, status/submit endpoints, guards and tokens
remain frozen. A PARTIALLY_FILLED -> NEW observation is already an explicit
warning diagnostic; this satisfies the requested diagnostic alternative without
changing legacy semantics or classifying every broker anomaly as corruption.

The earlier 2.x.3 runtime findings supplied by the user are historical evidence,
not newly rerun evidence. Phase 2.x.4 uses no emulator, install, broker GET/POST,
IEX, preflight, arm, token, confirmation or reset action.

## Implemented closure and exact evidence

Evidence abbreviations below refer to these source files. Line numbers identify
the final worktree validated for this report:

- R: [PaperOrderHistoryRepositoryTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/paper/history/PaperOrderHistoryRepositoryTest.kt).
- S: [PaperOrderHistorySqlContractTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/paper/history/PaperOrderHistorySqlContractTest.kt).
- V: [PaperOrderHistoryViewModelTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/ui/history/PaperOrderHistoryViewModelTest.kt).
- VC: [PaperOrderHistoryViewerContractTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/ui/history/PaperOrderHistoryViewerContractTest.kt).
- P: [PaperOrderHistoryRepository.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/history/PaperOrderHistoryRepository.kt).
- D: [PaperOrderHistoryDao.kt](../android/app/src/main/kotlin/com/vela/android/lab/db/room/dao/PaperOrderHistoryDao.kt).
- T: [PaperOrderStatusTrackerRepositoryTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/paper/status/PaperOrderStatusTrackerRepositoryTest.kt).
- A: [PaperOrderSubmitAuditRepositoryTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/paper/submit/PaperOrderSubmitAuditRepositoryTest.kt).

| Historical gap | Current | Exact closure evidence |
| --- | --- | --- |
| A. Temporal ordering | CLOSED | R:96 existing list/range cases; R:354 independent lifecycle equal-time/rollback cases; S:29 executes actual latest-N, audit and lifecycle SQL with disagreeing insertion/time/id order. |
| B. Lifecycle contradictions | CLOSED | R:260 independently tests FILLED, CANCELED, EXPIRED and REJECTED to NEW; R:285 isolates terminal quantity, price, filledAt and status changes; R:308 isolates decreasing nonterminal quantity. R:179 already covers PARTIALLY_FILLED to NEW as an explicit warning diagnostic. |
| C. Duplicate lifecycle payload | CLOSED | R:179 uses identical remote payload at observedAt 4000/5000, checks equal fingerprints, repeated flag and both retained observations. P:`payloadFingerprint` excludes local time/id; V:215 and viewer `forEachIndexed` preserve the full sequence. S:75 also retains both persisted rows. No redundant duplicate unit test added. |
| D. Real SQL query ordering | CLOSED | S:29,44,63,75 execute all 15 actual DAO queries against real host SQLite, using the exported v7 table/index SQL, not a handwritten substitute query. |
| E. Projection consistency | CLOSED | R:327 isolates status, terminal, quantity, average price and filledAt. Each produces only PROJECTION_MISMATCH; lifecycle remains authoritative and both projection/evidence remain unchanged. |
| F. Legacy missing metadata | CLOSED | R:55 already proves null initial metadata yields only LEGACY_SUBMIT_METADATA_UNKNOWN, not corruption. V:230 now explicitly retains VALID_WITH_WARNINGS and that exact diagnostic; VC:14 covers displayed legacy fields. |
| G. Canonical reconstruction | CLOSED | R:373 copies independent durable entities, clears the original DAO, recreates the repository and compares the entire canonical aggregate. R:21 verifies identity, links, semantics, metadata, lifecycle, fill, reset, integrity and signed delta; S:75 closes/reopens an actual persisted synthetic database. |
| H. History read-only | CLOSED | R:240, V:276 and VC:37/52 retain narrow read-only contracts. R:373 exercises every repository read without changing source collections. S:75 exercises every DAO SELECT under SQLite query_only, asserts unchanged rows and unchanged closed-file SHA-256. |

The SQL evidence is **host SQLite execution of the actual Room query/schema
contract**, not Android Room instrumentation or a new device runtime run. Room
generated sources compile in both variants. Synthetic databases live only in
JUnit temporary directories; no application database is opened. A deliberately
attempted DELETE is rejected by query_only on that synthetic fixture only.

Ordering is domain-specific: audit/lifecycle rows use their own id ASC; latest-N
uses submit-result id DESC with documented fallbacks; range membership uses
local result time with an exclusive upper bound, but output uses result id ASC;
symbol/side/identity use matching audit ids and terminal/filled use matching
lifecycle ids. These are not a fictitious global broker event sequence. The
viewer preserves repository order, including repeated observations; no
Int.MAX_VALUE fetch or repair path was introduced.

## Files and scope

Commit A, `test: close Paper history integrity gaps`, contains exactly:

- New `PaperOrderHistorySqlContractTest.kt`: 6 test invocations.
- Modified `PaperOrderHistoryRepositoryTest.kt`: 17 additional invocations,
  including parameterized independent contradiction/projection cases.
- Modified `PaperOrderHistoryViewModelTest.kt`: strengthens one existing legacy
  case without increasing its test count.
- Modified `android/app/build.gradle.kts`: only
  `testImplementation("org.xerial:sqlite-jdbc:3.41.2.2")` and explanatory comments.
  The version matches the existing cached Room compiler driver. It is host-test
  scope, not an application runtime dependency.
- New `docs/phase-2x4-integrity-closure.md`: this report.

Production code changed: **NO**. Integrity implementation tests-only: **YES**
(plus its test dependency and report). Room version: **7**. DB layer/schema diff:
**0**. `android/app/src/main` and `android/app/schemas` are unchanged from the
baseline, including entities, migrations, viewer UI, network clients and every
execution guard. No Room v8, Migration7To8, new UI, ML implementation or position
reconciliation was added. The real device database was not accessed or hashed
again in this phase; its unchanged runtime hash is historical 2.x.3 evidence.

## Validation completed on 2026-09-17

The final run used the SDK and Java on C, offline dependencies, no build cache
and forced task execution. From `G:\vela-android\android`, with the persisted
Android/Java/Gradle environment loaded into the process:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease `
  --offline --console=plain --no-daemon --no-build-cache --rerun-tasks --continue --max-workers=2 `
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8' `
  '-Pkotlin.compiler.execution.strategy=in-process'
```

| Check | Result | Evidence |
| --- | --- | --- |
| Debug unit tests | PASS: 1738 tests, 93 suites, 0 failures/errors/skipped | `android/app/build/test-results/testDebugUnitTest/TEST-*.xml` |
| Release unit tests | PASS: 1738 tests, 93 suites, 0 failures/errors/skipped | `android/app/build/test-results/testReleaseUnitTest/TEST-*.xml` |
| Added coverage | 23 invocations above the 1715-test baseline | 17 repository + 6 SQL cases; existing cases retained |
| Safety scan | PASS: 11 allowed submit-boundary hits / 0 suspicious / 0 forbidden | `android/scripts/safety-scan.ps1` |
| Verifier synthetic evidence suite | PASS: all 14 internal cases, in both variant unit runs | `SafeApkProvenanceScriptTest`, invoking `Verify-SafeApk.Tests.ps1` |
| Lint Debug | KNOWN_PREEXISTING_LINT_FAILURE: NewApi, Symbols.kt:40 | `android/app/build/reports/lint-results-debug.xml` |
| Lint Release | KNOWN_PREEXISTING_LINT_FAILURE: NewApi, Symbols.kt:40 | `android/app/build/reports/lint-results-release.xml` |
| Whitespace check | PASS | `git diff --check` |
| Production/schema freeze | PASS, diff=0 | `git diff --exit-code -- android/app/src/main android/app/schemas` |
| Manual submit compile gate | false in both generated BuildConfig variants | `android/app/build/generated/source/buildConfig/{debug,release}/com/vela/android/lab/BuildConfig.java` |
| JDBC isolation | PASS: no sqlite-jdbc in release runtime dependencies | Offline `:app:dependencyInsight --dependency org.xerial:sqlite-jdbc --configuration releaseRuntimeClasspath` completed successfully with no matching dependencies |

The combined Gradle invocation completed 69 tasks and returned exit code 1
**because of the two known lint task failures**, not test failures. Each lint
report has exactly one error, 13 warnings and one informational item. The error
is the unchanged Symbols.kt:40 NewApi finding allowed by the phase prompt. One
non-failing `UseTomlInstead` warning points at the new test-only dependency;
the other warning locations are unchanged code/existing dependency declarations.
No lint suppression, lint baseline or production fix was introduced to hide
these findings. Generated test/lint reports remain local build artifacts, not
committed source files.

## Final closure matrix

"BEFORE 2.x" describes the inherited pre-canonical-history capability, not the
clean 2.x.4 baseline (which already includes 2.x.2/2.x.3). CLOSED means the
requested order-history contract is closed; it does not claim Phase 3 or
position reconciliation functionality.

| AREA | BEFORE 2.x | CURRENT | EVIDENCE | REMAINING GAP |
| --- | --- | --- | --- | --- |
| identity persistence | Durable submit/reconciliation identities; no unified canonical view | CLOSED | A:14; R:21,77,219; S:63 exact identity queries | None in 2.x scope |
| submit evidence | Append-only submit audit; historical optional metadata absent | CLOSED | A:14,75,102,128; R:21,55 | Historical nulls remain explicit unknowns, not invented data |
| lifecycle persistence | Durable status observations existed | CLOSED | T:257,307,529; R:260,285,308; S:75 | None in 2.x scope |
| fill persistence | Fill evidence stored in lifecycle/projection | CLOSED | R:21,285,327; T:307 | None in 2.x scope |
| temporal ordering | Ordering proof incomplete across local read paths | CLOSED | D actual SQL; R:96,354; S:29,44; V:45 | No cross-domain global sequence claimed |
| duplicate observations | Explicit repeated-payload proof was an audit gap | CLOSED | R:179; P:`payloadFingerprint`; V:215; S:75 | None; every observation remains visible |
| projection consistency | Independent per-field proof incomplete | CLOSED | P:`matchesProjection`; R:327 | None; detection only, no repair |
| legacy semantics | Legacy initial broker metadata missing | CLOSED | R:55; V:230; VC:14 | Unknown legacy values intentionally remain null |
| query determinism | Canonical query/filter contract not yet fully proven | CLOSED | S:29,44,63,75,114; R:77,96; V:45,59,169 | Host SQL proof, not a new Android instrumentation run |
| canonical reconstruction | Evidence spread across durable domains | CLOSED | R:21,373; P:`buildRecord` | None in 2.x scope |
| process-death recovery | Durable tracker recovery existed | CLOSED | T:257,307,529; R:373; S:75; V:265; historical 2.x.3 restart | Local reopen/recreation proof, no new device process kill |
| reset preservation | Manual reset acknowledgement already preserved history | CLOSED | T:379; R:21,373; S:75 unchanged snapshots | No reset action executed in this phase |
| history viewer | No canonical read-only viewer before 2.x | CLOSED | V:184,196,215,230; VC:14,37,52; historical 2.x.3 runtime | None; no UI expansion |
| read-only guarantee | No full viewer-to-canonical contract before 2.x | CLOSED | R:240,373; S:75,114; V:276; VC:37,52 | Real DB runtime hash retained as historical evidence |
| execution boundary | Frozen safe defaults and manual-only boundary | CLOSED | Production diff=0; safety 11/0/0; generated compile flags false; guard tests | None; no new execution authority |
| ML order ground truth | Persisted evidence, no canonical integrity-qualified layer | CLOSED / READY | R:21,55,77,327,373; canonical status/diagnostics and signed delta | Market-state/features/outcomes are Phase 3, not this closure |

## Separate infrastructure audit: commit B

Commit B is justified: ignored `android/local.properties` resolves the SDK to
`C:\Android\Sdk`, while the checked-in helper defaults still referenced G.
User environment values also resolve ANDROID_HOME/ANDROID_SDK_ROOT to that C
SDK, ANDROID_AVD_HOME to `C:\Android\avd`, GRADLE_USER_HOME to
`C:\Android\gradle-home`, and JAVA_HOME to
`C:\Android\Android Studio 2026.1.2\jbr`. The C ADB and Java binaries exist;
the Java processes used for validation execute from that C JBR.

`chore: update Android SDK tooling paths` is limited to:

- `android/scripts/Check-EmulatorClock.ps1`: ADB default and matching help text,
  G to C. The script was not run against an emulator.
- `android/scripts/Verify-SafeApk.ps1`: approved SDK and Java roots, G to C.
  Approved Git paths, certificate, compile flag, SDK/package checks and all
  fail-closed verification logic remain unchanged. Its synthetic suite passes.
- `README.md`: the workspace-layout paragraph documents repository on G and
  Android tools/data on C. This belongs to B, not integrity commit A.

The three restored migration files match their preserved backup hashes. No
credentials, ignored settings or unrelated migration files belong to either
commit. Both helper scripts also pass PowerShell syntax parsing. This audit
does not claim a fresh emulator boot, APK installation or
full APK provenance verification. The full verifier's HEAD/origin equality
gate is not weakened to accommodate local commits, and no push is performed.

## Verdict and boundary declaration

```text
PASS_PHASE_2X4_INTEGRITY_CLOSURE_IMPLEMENTED
PHASE_2X_READY_FOR_FINAL_PUBLICATION
PHASE_2X_INTEGRITY_READY

TEMPORAL_ORDERING=CLOSED
LIFECYCLE_INTEGRITY=CLOSED
DUPLICATE_OBSERVATION_SEMANTICS=CLOSED
PROJECTION_CONSISTENCY=CLOSED
LEGACY_SEMANTICS=CLOSED
QUERY_DETERMINISM=CLOSED
CANONICAL_RECONSTRUCTION=CLOSED
HISTORY_READ_ONLY=CLOSED
ML_GROUND_TRUTH_ORDER_LAYER=READY
```

Execution boundary diff=0; `PaperTradingExecutionGuard.canExecuteOrders=false`;
release `MANUAL_PAPER_SUBMIT_COMPILED=false`; REAL locked=true, LIVE=false,
Auto Paper=false under the unchanged safe defaults. Phase 2.x.4 runtime GET=0,
POST=0, IEX=NO: no application runtime, broker request, emulator, install,
preflight, arm, token, confirmation or reset was invoked for this closure.
Expected signed position delta remains available; no positions query, portfolio
comparison, netting or corrective order was added. Those belong to Phase 2.y.

There is no remaining integrity gap within the requested scope. Phase 2.x is
ready for final publication; this is not a claim that publication/push occurred.
The authorized handoff is two scoped local commits, then empty staging and
clean worktree. Commit hashes and the post-commit clean-state check are supplied
in the final delivery rather than embedded self-referentially in commit A.
