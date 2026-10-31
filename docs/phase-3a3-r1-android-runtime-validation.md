# Phase 3.a.3-R1-FIX2 — Independent offline audit and publication

Date: 2026-10-31 (America/Buenos_Aires).
Required parent baseline: `f60469370757e93ac7b13e84997921103ac0848b`.

Offline publication gate: **READY_3A3R1_FIX2_TO_PUBLISH**.

**ANDROID_SQLITE_RUNTIME_VALIDATED=NO.**
Prepared/compiled instrumentation is not runtime validation. This audit performed
no emulator launch, device command, installation, instrumentation or app launch.
The commit containing this report is intended to be the single FIX2/R1 publication;
its full hash and actual remote equality are recorded after push in
`C:\Android\audits\phase-3a3-r1-fix2-publication-20261031\publication-result.json`.
No self-referential commit hash is embedded in the source document.

## Historical evidence — outcomes not rewritten

| Stage before this audit | Actual outcome | What it establishes |
| --- | --- | --- |
| Original storage-parent experiment, 2026-10-29 | Six setup starts; five failed finishes and one interrupted; seven not started; zero SQLite bodies | Instrumentation-package cache parent was not established; failure was before SQLite. |
| Corrected target-cache storage preflight, 2026-10-30 | Exactly 1 executed / 1 passed / 0 failed / 0 skipped | Target-app ownership, wrapper containment and exclusive cleanup only. |
| First authorized SQLite class invocation, 2026-10-31 | 1 failed / 1 interrupted / 11 not started / 0 passed | Production factory rejected the busy_timeout setter before schema initialization. |
| Separately owner-authorized repeat | 2 failed / 1 interrupted / 10 not started / 0 passed | Same adapter failure reproduced; not an automatic retry. |

Storage-parent evidence remains in phase-3a3-r1-runtime-20261029 and
phase-3a3-r1-fix-preparation-20261029. The exact DAC/SELinux/missing-ancestor
mechanism was not proved; the confirmed error was choosing the other package's
cache and assuming a usable parent. The validated fix uses targetContext only.

The passing storage preflight remains in
`C:\Android\audits\phase-3a3-r1-storage-preflight-20261030`.
SQLite failures remain in phase-3a3-r1-sqlite-runtime-20261031 and
phase-3a3-r1-sqlite-runtime-repeat-20261031. Host stop is not instantaneous JUnit
fail-fast; later starts are recorded, not hidden. No previous failure is relabeled
PASS, and opening the normal UI did not validate the unwired Market Dataset factory.

## Observed root cause versus API contract and inference

Observed: the published adapter at AndroidMarketDatasetDatabase.kt:28 executed
`db.execSQL("PRAGMA busy_timeout=5000")`. Android raised SQLiteException through
nativeExecuteForChangedRowCount: `Queries can be performed using SQLiteDatabase query or rawQuery methods only.`
The first method h failed in factory initialization, before its rollback assertions
or V1 DDL. The schema is not blamed for that failure.

Contract: execSQL is not the API for row-returning statements; the Android native
changed-row path rejects SQLITE_ROW. execPerConnectionSQL is the pool-wide API
available from API 30, not an unguarded replacement for this minSdk 29 application.
[Android API](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase#execSQL(java.lang.String)),
[per-connection API](https://developer.android.com/reference/android/database/sqlite/SQLiteDatabase#execPerConnectionSQL(java.lang.String,%20java.lang.Object%5B%5D)),
[AOSP native path](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/core/jni/android_database_SQLiteConnection.cpp).

FIX2's sole production semantic change removes that explicit setter. Two comments
state platform ownership and preserved lock classification. No rawQuery setter
fallback, new API-level branch, sleep, application retry loop, swallowing catch,
migration or unrelated refactoring was introduced. Android's timeout policy is
**PLATFORM_MANAGED; exact duration NOT GUARANTEED BY VELA**. AOSP's native default
is an implementation detail, not an asserted device measurement or timing SLA.
JDBC's explicitly configured 5000 ms policy and its regressions remain unchanged.

### Exception-flow audit

| Path | Actual adapter propagation |
| --- | --- |
| open / connection configuration | Entire open entry is in reportBusy; configuration failure closes and rethrows. Store initialization preserves typed MarketStorageException. |
| transaction begin | beginTransactionNonExclusive is inside reportBusy; a begin failure cannot return an append result. |
| execute | execSQL exceptions propagate to the enclosing transaction's reportBusy. |
| query / cursor consumption | rawQuery and iteration failures propagate through the same transaction wrapper; cursors use close-safe use blocks. |
| commit / endTransaction | endTransaction is in finally inside reportBusy; a thrown commit/end failure prevents the success result from returning. |

Both SQLiteDatabaseLockedException and SQLiteTableLockedException still become
MarketStorageError.SQL_BUSY. MarketDatasetStore.access passes that typed failure
through unchanged instead of turning it into receipt/provider evidence conflict.
Other failures are not treated as successful transactions. No new exception
handling was added to production.

### Connection settings and lifecycle limits

| Requirement | Configuration and audit finding |
| --- | --- |
| journal_mode=DELETE | OpenParams sets DELETE; no WAL enable flag/call; factory readback requires delete. |
| synchronous=FULL | OpenParams sets FULL; factory readback requires 2. |
| foreign_keys=ON | Public setForeignKeyConstraintsEnabled updates framework pool configuration before transactions; schema verification checks the value. |
| recursive_triggers=ON | Existing connection-local setter runs before schema handoff; schema verification checks it inside store transactions. It is not claimed to configure arbitrary future pool connections. |

Reviewed AOSP API 29/API 34 non-WAL pools have maximum size one, so the configured
primary connection is the transaction/schema path under those implementations.
This is source-level evidence, not proof on every OEM/future runtime. The framework
persists journal/synchronous/FK configuration; arbitrary PRAGMAs are different.
Internal connection recreation (for example debug idle closing) may reset
recursive_triggers; the adapter does not opt into idle closing or WAL, and append
verifies the setting before mutations. No second definite runtime defect was
established and no storage redesign was made.
[AOSP API 29 pool](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-10.0.0_r1/core/java/android/database/sqlite/SQLiteConnectionPool.java),
[API 34 pool](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/core/java/android/database/sqlite/SQLiteConnectionPool.java),
[connection configuration](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-14.0.0_r1/core/java/android/database/sqlite/SQLiteConnection.java).

Schema V1 is unchanged: **7 tables, 7 explicit indexes, 16 triggers**, fingerprint
`a539bc2a020922419c27ae782d8c4bd43f02af4c642b1930dd8c59bc0bea6e47`.
Raw/receipt identities, decimal codec, nanosecond representation, high-water,
revision chains, lineage, append-only guards, transactions and corruption handling
are unchanged. Operational Room remains v9 with no migration.

## Owner-approved assertion resolution — no unresolved Android 5000 ms contract

Only b_configuration_isObservedOnTheActualFactoryConnection changed in the
thirteen-case class. Its method name and @Test annotation remain. Shared setup,
helpers and the other twelve methods are unchanged after newline normalization.

Removed assertion:
`assertEquals("5000", scalar(db, "PRAGMA busy_timeout"))`.

All four original safety-setting assertions (DELETE, FULL=2, FK=1, recursive=1)
remain. The old duration assertion is replaced, not merely deleted, by:

1. Successful opens through two distinct production factory handles to the same owned sandbox DB.
2. A held writer transaction on the owner handle, and an append on the contender
   required to throw exactly MarketStorageError.SQL_BUSY.
3. After lock release: raw/canonical rows unchanged, high-water still 0, attempted
   receipt absent, and original raw/canonical evidence identical.
4. An explicit subsequent append required to succeed exactly once, advance
   high-water to 1, produce exactly two receipts/one revision, and preserve originals.
5. Integrity assertions before and after the explicit retry; timeout value recorded
   diagnostically with policy PLATFORM_MANAGED, without a hardcoded duration.

The behavioral assertions are **prepared and compiled, not executed on Android**.
No internal application retry loop exists by production audit; the prepared test
also verifies the failed call did not silently acknowledge/retain its write.
No @Ignore, @Disabled, conditional skip, assumption escape, generic-success catch
or deletion of contention coverage was introduced. JDBC fixtures and real host
contention/rollback/idempotency/provider-conflict/high-water tests are unchanged.

A sixth focused host source-contract test guards this owner-approved replacement.
It is static coverage, not a substitute for the Android contention experiment.
Counts remain exactly thirteen Android cases, one SQLite smoke and one storage preflight.

## Sandbox, smoke and exact disjoint selections

The validated target-only sandbox is unchanged: canonical private cache, fresh
vela-3a3r1 directory, exclusive UUID marker, package/UID checks, no symlinks or
fallback, exact database filename only, and applicationContext retaining the
wrapper. Cleanup validates the complete allowed file set and deletes only owned
files/empty directory after close; no recursive or cache-parent deletion.

The unchanged smoke opens through the actual production factory, checks schema
V1 and 7/7/16 objects/fingerprint, required PRAGMAs on the factory handle inside a
transaction, integrity, real handle close and owned cleanup. It never opens Room
or normal installed Market DB paths and never starts broker/stream/collector code.

Registered runner:
`com.vela.android.lab.test/com.vela.android.lab.core.marketdataset.runtime.MarketDatasetR1Runner`.

| Future stage, NOT executed here | Exact class argument | vela3a3r1Authorization |
| --- | --- | --- |
| Exactly ONE SQLite smoke method | com.vela.android.lab.core.marketdataset.runtime.MarketDatasetR1SqliteSmokeTest#openV1SchemaAndRequiredConfiguration | SQLITE_SMOKE_EXPLICITLY_AUTHORIZED |
| Separate full thirteen-case validation | com.vela.android.lab.core.marketdataset.runtime.MarketDatasetAndroidRuntimeIntegrityTest | SQLITE_13_EXPLICITLY_AUTHORIZED |

Runner validation occurs before super.onCreate and accepts only the exact selector/token
pair and exactly those two argument keys. Smoke class-only, any other method,
combined filters, cross-tokens, package/annotation/test-file/sharding selectors,
unrelated Room/Paper tests and discovery are rejected. The smoke does not inherit
or delegate to the thirteen-case class. Packaged annotation counts are 1/13/1.
The plain Application replacement avoids the normal VELA graph.
The previously passing storage preflight need not be rerun.

## Fresh offline verification in this audit

Disposable detached worktree:
`C:\Android\audits\phase-3a3-r1-fix2-publication-20261031\clean-worktree`.

Production is f604693 plus the audited one-file adapter change only. Baseline
source is intact elsewhere; only approved R1/test files were copied with matching
hashes. No showcase, credentials or unsafe build flags. Sanitized local.properties
contains only sdk.dir=C:\Android\Sdk and MANUAL_PAPER_SUBMIT_COMPILED=false.
All Gradle commands used --offline and the audited test-only init script.

Debug ran first with :app:testDebugUnitTest --rerun-tasks. After completion,
Release ran separately with :app:testReleaseUnitTest --rerun-tasks. Then Debug app
and AndroidTest APKs were assembled, targeted storage ran, and lintDebug/lintRelease
ran as two separate commands. No connectedAndroidTest or other device task ran.

| Check | Actual result |
| --- | --- |
| Fresh Debug | 128 suites / **2353 tests**, exit=0, failures=0, errors=0, skipped=0 |
| Fresh Release, after Debug | 128 suites / **2353 tests**, exit=0, failures=0, errors=0, skipped=0 |
| Targeted storage | 5 suites / **74 tests**, exit=0, failures=0, errors=0, skipped=0 |
| Compiled pure Kotlin authorization gate | **51 checks**: 3 accepted + 48 rejected, no skips/Android/SQLite execution |
| Main and clean-tree safety scans | Each **11 allowed / 0 suspicious / 0 forbidden** |
| lintDebug | exit=1: existing Symbols.kt:40 NewApi, 13 warnings, 1 information |
| lintRelease | exit=1: same existing NewApi, 13 warnings, 1 information |
| Lint comparison | **0 new findings, 0 new suppressions**; global lint is NOT green |
| Tracked diff and legitimate untracked whitespace/conflict checks | PASS |

The count increase is 2352 preparation tests plus one new static regression.
No test was removed or skipped. Full Debug/Release XML was archived before the
targeted run. The actual JDBC writer-contention test still reports SQL_BUSY,
preserves ledger/evidence on failure and permits only an explicit later retry.

## Audited source scope and new compiled-only APK provenance

Exactly twelve publication paths; unrelated changes=0:

| Category | File |
| --- | --- |
| A — sole production correction | AndroidMarketDatasetDatabase.kt |
| B — focused host regressions | AndroidMarketDatasetAdapterContractTest.kt |
| C — thirteen prepared SQLite cases | MarketDatasetAndroidRuntimeIntegrityTest.kt |
| C — strict selector gate | MarketDatasetR1Authorization.kt |
| C — dedicated plain-Application runner | MarketDatasetR1Runner.kt |
| C — validated ownership sandbox | MarketDatasetR1Sandbox.kt |
| C — one-method SQLite smoke | MarketDatasetR1SqliteSmokeTest.kt |
| C — canonical cache-parent guard | MarketDatasetR1StoragePaths.kt |
| C — storage-only preflight | MarketDatasetR1StoragePreflightTest.kt |
| D — this report/history | docs/phase-3a3-r1-android-runtime-validation.md |
| E — test-only runner manifest | android/app/src/androidTest/AndroidManifest.xml |
| E — sanitized test build configuration | android/app/src/androidTest/market-dataset-r1.init.gradle |

The three user-owned showcase files are preserved by SHA-256 and excluded from
both clean builds and staging. No schema/Room/domain/network/submit-boundary change,
legacy import, execution authority, automatic collection, ML or 3.a.4 is included.
No stage-all, amend, merge, rebase or force push is permitted.

| Current artifact | Package | Bytes | Fresh SHA-256 |
| --- | --- | ---: | --- |
| app-debug.apk | com.vela.android.lab | 28810501 | 6ECA69AA49C8C006AE07370004A730F3A2CD62F1F89E6875C60CD794E3F28CC3 |
| app-debug-androidTest.apk | com.vela.android.lab.test | 1024659 | DBB155AD0E2A7E9371B4A05344E0D019150C16C21430C096697B7AD5501880B2 |

Both certificate SHA-256 values verify as:
`C0106E6DF46127F68C312818124AB628637EFD348E43B175DD4605E97C697ADC`.

The app hash was recomputed and is unchanged because production/configuration
match the audited FIX2 preparation. AndroidTest is NEW after the assertion change;
the older AC683EC2..., 5F43EE07... and 4B9925E8... test hashes are historical only.
Generated/DEX BuildConfig has manual submit=false and both Alpaca keys empty.
Manifest has exactly the intended runner and target, minSdk 29 / targetSdk 34.
Adapter DEX has no busy_timeout setter; the full-test DEX has PLATFORM_MANAGED
and SQL_BUSY, with no hardcoded "5000" string.

| Current test-only source (runtime/ = kotlin/com/vela/android/lab/core/marketdataset/runtime/) | SHA-256 |
| --- | --- |
| AndroidManifest.xml | 94A04873807A0C99A3D2AB49DC589E6C2CF5D73A5CB0845D7B70FB0E6E7DD2CD |
| market-dataset-r1.init.gradle | 83A54F5A3BCA715567FD53D9C3D58B7BC9094E31BCADF949603CDCDD8C8AA9E6 |
| runtime/MarketDatasetAndroidRuntimeIntegrityTest.kt | 3F55A4CF9953CEF9CE9E2917EBBE2D44A8ED5D68353B276B81BC33C497EFCF89 |
| runtime/MarketDatasetR1Authorization.kt | 36261B33F3A884EE27263F40270221C40D9AB7A1A2BDCC407D084B72DA38D013 |
| runtime/MarketDatasetR1Runner.kt | DC1B603E325FDD2FA4D6EA67064999B9E82AE9D7A4B489F686A6C2C49FF29ECF |
| runtime/MarketDatasetR1Sandbox.kt | DCA703BA7BDBD3F5B9591105C97077F02EAF937D77CDBBE5ACE5464F51C4BF05 |
| runtime/MarketDatasetR1SqliteSmokeTest.kt | AE69FCD6F50B0ABDFFAD15680429A27CA4B9AC8C41B3C56C297CD6939EE6247C |
| runtime/MarketDatasetR1StoragePaths.kt | B1EAFF00D502EAE6A3DCDDB521E16E9762FA8E97164F0AB2EA78F54C51F65DB2 |
| runtime/MarketDatasetR1StoragePreflightTest.kt | 4C0100420AF5BEDEE341547EB95132F05EB0FCBEA62903ADEBF429464E784699 |

Production adapter hash:
`189E326C9CE3C69B56DF834CA61BE0639B1D6FC3F54E6B1714F70AD179C42BB8`.
Host contract regression hash:
`44A0FDB55CB2FD52C9BD95E2377BAA9976DAD34F1C1AF3AA44159944CA94C60C`.

Evidence directory: `C:\Android\audits\phase-3a3-r1-fix2-publication-20261031`.
It contains before-audit snapshots, assertion-change-audit.json, source-hashes.json,
full/targeted XML, step results with timestamps, safety/lint comparisons, APK
manifests/signatures/DEX, artifact-provenance.json and publication-result.json.
The pre-audit FIX2 report remains archived; the earlier R1 history follows below.

## Publication procedure and remaining runtime gate

After READY only these twelve paths may be staged. The complete cached diff and
cached path set must be inspected before the single coherent commit. Immediately
before normal push, fetch origin and require origin/main still equals f604693...;
otherwise stop without merge/rebase/force. After push, fetch and require
HEAD=origin/main=the new commit, tracked clean and staging empty. Actual result
and full commit hash are in the external publication result, not assumed here.

This entire audit/publication performed **zero new Android runtime execution**:
no emulator/device interaction, install, instrumentation, app launch, broker GET/POST,
IEX/TEST stream, clock/snapshot change or installed database access. No G:\vela
or Windows vela.db access. Existing runtime failures and storage-preflight PASS remain.

NEXT: request a separate human authorization for exactly ONE offline SQLite smoke
with these exact new artifacts and preserved installed data. Stop after that
future result; the full thirteen require a further separate authorization.
**NO RUNTIME, NO THIRTEEN-TEST EXECUTION, NO 3.a.4 IN THIS PUBLICATION TASK.**

<details>
<summary>Superseded R1 preparation and earlier historical evidence — not current FIX2 instructions</summary>

# Phase 3.a.3-R1 — Sandbox FIX and historical evidence

Current phase: **3.a.3-R1-FIX — PREPARATION ONLY / NO RUNTIME**.
Gate: `READY_3A3R1_STORAGE_PREFLIGHT_AUTHORIZATION`.
Baseline: `f60469370757e93ac7b13e84997921103ac0848b`.
Date: 2026-10-29. **ANDROID_SQLITE_RUNTIME_VALIDATED=NO**.

This FIX supersedes the old preparation instructions below. The failed runtime
is preserved as failure evidence; no runtime PASS is claimed. No emulator/device
command, install, instrumentation, app launch, broker/stream call, installed-data
access, clock/snapshot/network change, uninstall or data clear occurred in this
correction. Existing device state was not inspected or changed.

## Confirmed failure and limits of the root-cause assessment

The old sandbox used instrumentation.context.cacheDir, required package
com.vela.android.lab.test, and passed it directly to Files.createTempDirectory
without establishing an existing/writable parent or safely creating it.

The first reported runtime case failed in @Before with NoSuchFileException at
MarketDatasetR1Sandbox.kt:25, before wrapper construction or SQLite opening:

`/data/data/com.vela.android.lab.test/cache/vela-3a3r1-3941895075865943481`

Independent ContextImpl logs say ensuring
/data/user/0/com.vela.android.lab.test/cache failed with ENOENT. Thus a usable
cache parent was not established; this is corroborated by source and two log
sources, not asserted solely from the exception.

Saved package metadata confirms appId=10192/dataDir=/data/user/0/com.vela.android.lab
for the target, versus appId=10193/dataDir=/data/user/0/com.vela.android.lab.test
for the instrumentation package. These are different ownership namespaces.
The [Instrumentation API](https://developer.android.com/reference/android/app/Instrumentation)
explicitly distinguishes getContext from getTargetContext.

Confirmed defect: choosing the other package's private cache and assuming it was
usable. Inference: the ownership mismatch is a likely accessibility factor.
The failed process's UID, directory owner/mode and SELinux decision were NOT
captured, so the precise DAC/SELinux/missing-ancestor mechanism is not claimed
proved. No fresh device investigation was performed.

The original wrapper returned itself from applicationContext, but the failed
constructor never reached that wrapper. It was not an applicationContext escape
to the normal database path.

Historical execution remains one invocation, six failed setups (five finish
events plus one interrupted), seven not started, zero passing/validated SQLite
tests, no retry. Host interval: 1.3562877 seconds. Original evidence is unchanged:
`C:\Android\audits\phase-3a3-r1-runtime-20261029`.

Before repair, both old APKs, five original support sources and the original
report were copied to:
`C:\Android\audits\phase-3a3-r1-fix-preparation-20261029\historical-r1-before-fix`.
The old AndroidTest hash 4B9925E8D06754834DD368ADD685CAA93E710595A4DD16697D36CA7AFDA2F549
and old authorization token are historical, NOT valid verification/approval for
the rebuilt test APK.

## Corrected ownership, parent and cleanup

MarketDatasetR1Sandbox now takes ONLY
InstrumentationRegistry.getInstrumentation().targetContext. It verifies:

- target package and ApplicationInfo package exactly com.vela.android.lab;
- target ApplicationInfo.uid equals the actual Process.myUid;
- canonical target dataDir equals ApplicationInfo.dataDir;
- the cache candidate is exactly the canonical target dataDir/cache;
- no cache symlink or canonical alias into databases/external storage;
- the existing app data root is a directory; arbitrary data roots are never created;
- the cache exists or only that single cache directory can safely be created;
- its final directory/type/containment/no-symlink/writable checks pass.

MarketDatasetR1StoragePaths is a pure test-only helper. It handles concurrent
cache creation by revalidating rather than replacing anything. It neither scans
nor alters existing cache contents. Actual fresh child creation and marker writing
will test Android usability in the future preflight; host writability is not
substituted for Android proof. Failure to establish the parent/child reports
BLOCKED_3A3R1_CACHE_PARENT_UNAVAILABLE. Package/UID/data mismatch fails isolation.

The only database path is:

`<verified-target-private-cache>/vela-3a3r1-<fresh-id>/vela_market_dataset.db`

The wrapper is based on targetContext and still overrides BOTH applicationContext
(returning itself) and getDatabasePath (exact filename only). Traversal, absolute
names, vela-lab.db and any other filename are rejected before opening. Protected
Market/Room paths are derived from target dataDir/databases without calling
normal getDatabasePath or accessing those files. No external, test-package,
normal-database or arbitrary /data/data fallback exists.

Exclusive marker, canonical parent, fixed prefix and no-symlink checks remain.
Cleanup validates the entire deletion set before deleting only the owned DB,
its own journal/WAL/SHM sidecars and marker. No recursive deletion, parent-cache
deletion, permission grant or unrelated-cache cleanup. Unknown entries abort;
failed connection close prevents deletion. The preflight creates only its marker,
checks the DB does not exist, closes the sandbox and verifies its directory is
removed while the cache parent remains.

## Mandatory independent checkpoints

One dedicated registered runner:
`com.vela.android.lab.test/com.vela.android.lab.core.marketdataset.runtime.MarketDatasetR1Runner`.

| Checkpoint | Exact class filter | vela3a3r1Authorization | Prepared / newly executed |
|---|---|---|---|
| A: storage ONLY | com.vela.android.lab.core.marketdataset.runtime.MarketDatasetR1StoragePreflightTest | STORAGE_PREFLIGHT_EXPLICITLY_AUTHORIZED | 1 / 0 |
| B: SQLite, later separate approval | com.vela.android.lab.core.marketdataset.runtime.MarketDatasetAndroidRuntimeIntegrityTest | SQLITE_13_EXPLICITLY_AUTHORIZED | 13 / 0 |

The runner requires exactly class + authorization keys, checks the two disjoint
pairs BEFORE super.onCreate, and rejects cross-pairs, old token
OFFLINE_RUNTIME_EXPLICITLY_AUTHORIZED, class lists, method selectors, wildcards,
unrelated/Room classes, whitespace variants, missing/null values, extra package,
annotation/test-file filters and sharding. Tokens guard accidental selection;
they never replace actual human consent.

Stage A has ONE compiled @Test. It checks context/package/UID identity, cache
parent, writable/fresh sandbox, wrapper retention, exact path, forbidden names,
absence of a DB and owner-checked cleanup. Its compiled APK DEX has no SQLite,
MarketDatasetStore or SQLite-test-class reference. No suite/delegation to B exists.
The instrumentation Context is consulted for package/UID identity only, NEVER its
cache or private files. Runtime evidence will report actual UIDs, paths, wrapper
and cleanup, not host results.

A success does not persist/issue authorization for B, invoke B or launch another
test process. After A succeeds OR fails: STOP and report. B needs a new explicit
human checkpoint. Host force-stop is not synchronous JUnit fail-fast, as the
historical six-setup cascade demonstrated; do not rediscover cache availability
with the 13-case selection.

All THIRTEEN existing SQLite test bodies/assertions are unchanged after newline
normalization. Only @Before's stage authorization names and target-only sandbox
constructor changed. Their A-M matrix is retained in the historical appendix.
They remain prepared, with zero newly executed in this FIX.

## Compile-only verification and fresh APK provenance

The approved detached worktree was reused:
`C:\Android\audits\phase-3a3-r1-preparation-20261029\clean-worktree`.
Its HEAD and all tracked files still match the published baseline. Production
tree identities remain root=03343f74c59d11974449e644c546b4d2a16faef6 and
android/app/src/main=96b2d35b5d459d9a7ce8c5740426563e10ca6a50.

Eight audited test/support paths were copied with matching SHA-256. The three
original Kotlin harness files changed; three were added: storage preflight,
pure authorization gate and pure storage-path guard. Test manifest/init script
are unchanged. This report is updated: nine R1 paths total, unrelated changes=0.
All five production storage files, Room v9, domain, network, execution, startup
and UI remain unchanged. No production defect was repaired.

Build only in that clean worktree, without showcase/credentials. Its sanitized
local.properties still contains only sdk.dir=C:\Android\Sdk and the false manual
Paper flag; the existing test-only init script enforces that and the dedicated
runner. Existing JDK/Gradle/SDK settings under C:\Android were used.

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --init-script app/src/androidTest/market-dataset-r1.init.gradle --offline --no-daemon --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process'
```

Compile exit=0. Compiled @Test counts are exactly preflight=1 and SQLite=13;
none was executed on Android in this correction. No fresh full host suite is
claimed; the prior 2347-test-per-variant suite is established historical evidence.

| Freshly recomputed artifact | Package / variant | Bytes | SHA-256 |
|---|---|---|---|
| app-debug.apk | com.vela.android.lab / debug | 28810501 | 2FBA2102947187E4F3FB56D0F4494EC809B2DFBD5673265A512432A41A4B1EC9 |
| app-debug-androidTest.apk | com.vela.android.lab.test / debugAndroidTest | 1071047 | 5F43EE07CCEEC531BFEBF0E67E4FCABB11F7841C1CD6902D42073934D0E1AF75 |

Both signatures verify with the required certificate SHA-256:
`C0106E6DF46127F68C312818124AB628637EFD348E43B175DD4605E97C697ADC`.

The app hash was freshly recomputed and happens to be identical: production and
safe configuration are unchanged. The test APK hash is NEW. Do not verify new
artifacts using old hashes/provenance. App versionCode=1/versionName=0.1.0-phase1;
test manifest has no versionName (default versionCode=0). Only the dedicated runner
is registered, target=com.vela.android.lab; minimum SDK=29, target SDK=34.

Generated BuildConfig and current app DEX confirm
MANUAL_PAPER_SUBMIT_COMPILED=false and both Alpaca credential fields empty.
No REAL/LIVE/Auto Paper capability is introduced. Plain Application replaces the
production graph; no Activity, service, collector or trading UI is started.
The baseline APK's INTERNET permission remains: future runtime MUST be offline.

Current source hashes, relative to android/app/src/androidTest:

| File | SHA-256 |
|---|---|
| `AndroidManifest.xml` | `94A04873807A0C99A3D2AB49DC589E6C2CF5D73A5CB0845D7B70FB0E6E7DD2CD` |
| `market-dataset-r1.init.gradle` | `83A54F5A3BCA715567FD53D9C3D58B7BC9094E31BCADF949603CDCDD8C8AA9E6` |
| `kotlin/com/vela/android/lab/core/marketdataset/runtime/MarketDatasetAndroidRuntimeIntegrityTest.kt` | `7C5AE0699EAD72BBE4AC86203D414BA8B2B89DBEFC84DB6A3D0E1FDCFF0786CD` |
| `kotlin/com/vela/android/lab/core/marketdataset/runtime/MarketDatasetR1Authorization.kt` | `C4930003EFA9ADD130DA1AB0CE47FE664D40A47642D6676D542665D72D3FB7FF` |
| `kotlin/com/vela/android/lab/core/marketdataset/runtime/MarketDatasetR1Runner.kt` | `DC1B603E325FDD2FA4D6EA67064999B9E82AE9D7A4B489F686A6C2C49FF29ECF` |
| `kotlin/com/vela/android/lab/core/marketdataset/runtime/MarketDatasetR1Sandbox.kt` | `DCA703BA7BDBD3F5B9591105C97077F02EAF937D77CDBBE5ACE5464F51C4BF05` |
| `kotlin/com/vela/android/lab/core/marketdataset/runtime/MarketDatasetR1StoragePaths.kt` | `B1EAFF00D502EAE6A3DCDDB521E16E9762FA8E97164F0AB2EA78F54C51F65DB2` |
| `kotlin/com/vela/android/lab/core/marketdataset/runtime/MarketDatasetR1StoragePreflightTest.kt` | `4C0100420AF5BEDEE341547EB95132F05EB0FCBEA62903ADEBF429464E784699` |

29 checks passed on the ACTUAL compiled PURE host helpers: 22 authorization cases
(2 accepted pairs, 20 rejected selections), and 7 storage cases covering existing
cache preservation, absent-cache creation, wrong parent, database-directory
substitution, regular-file cache, missing data root and symbolic-link alias.
No checks were skipped. Disposable host fixtures were cleaned only via their
known files/empty directories, not recursive traversal. Android/SQLite were not
loaded by these checks. Android UID/SELinux/ownership usability remains unvalidated.

Safety scan=11/0/0. lintDebug exit=1: 1 pre-existing NewApi error at Symbols.kt:40,
13 warnings, 1 information. New findings=0, phase findings=0, suppressions=0.
Global lint is NOT green. Compile has no new phase warning. Format/diff checks pass.

FIX evidence is under:
`C:\Android\audits\phase-3a3-r1-fix-preparation-20261029`.
See compile-only.log, host-checks.log, R1FixHostChecks.java, APK manifests/DEX,
signatures, annotation listings, lint-debug.log/xml, safety-scan.log and
fix-preparation-provenance.json. Historical runtime evidence was not overwritten.

## Stop and request ONE storage preflight authorization

Suggested explicit human checkpoint:

> Autorizo solo MarketDatasetR1StoragePreflightTest en VELA_Runtime_API34,
> offline, con los APKs/hashes de este FIX y preservando los datos instalados.
> No abras SQLite y detenete al terminar esa única prueba. No autorizo las
> 13 pruebas SQLite.

Before that future A run, reverify new hashes/certificate, exact device/serial/API,
compatible nondestructive installation, safe flags and effective IPv4/IPv6/
Ethernet isolation. No snapshots, clock changes, permission-grant shortcut,
uninstall or data clear. The current FIX performed NONE of those device actions.

The only next eligible selection, after new human consent, is:

```text
class=com.vela.android.lab.core.marketdataset.runtime.MarketDatasetR1StoragePreflightTest
vela3a3r1Authorization=STORAGE_PREFLIGHT_EXPLICITLY_AUTHORIZED
runner=com.vela.android.lab.test/com.vela.android.lab.core.marketdataset.runtime.MarketDatasetR1Runner
```

Even if A passes: STOP. B remains separately unauthorized. No stage/commit/push;
baseline and all showcase hashes preserved. No G:\vela or Windows vela.db access.
**NO RUNTIME IN THIS FIX. NO 13-TEST RUN. NO 3.a.4.**

## Historical initial preparation appendix — SUPERSEDED

The following text is retained verbatim as the original R1-P preparation record.
Its path policy, legacy token, APK hashes and runnable 13-test instructions are
historical and MUST NOT be followed for the repaired harness.

<details>
<summary>Original R1-P preparation (historical; not current authorization)</summary>


# Phase 3.a.3-R1-P — Android SQLite runtime validation preparation

Status: **PREPARATION ONLY**.
Gate: `READY_3A3R1_FOR_HUMAN_RUNTIME_AUTHORIZATION`.
Date: 2026-10-29. Repository: `G:\vela-android`.
Published target: `f60469370757e93ac7b13e84997921103ac0848b`.

**No runtime PASS is claimed.** No emulator/device access, install, app launch,
instrumentation execution, broker request, stream, snapshot restore or clock
change was performed. Operational databases, Windows vela.db and G:\vela were
not accessed. 3.a.4 has not started. Staging/commit/push=0.

## Baseline, scope and clean build

At entry, fetched HEAD=origin/main=the target commit, branch main, tracked clean,
staging empty. The three user-owned showcase files remain untracked and match
their recorded SHA-256 values:

| Showcase file | SHA-256 |
|---|---|
| VelaShowcaseComponents.kt | A36707DB0FAF4E98FCC704DDCF917D5966E37E691AA4D2EA5C510AB5BA13B4FD |
| VelaShowcaseFixtures.kt | 825D97E44019A0D5E13569C01F5307E5E807BCFD17141D3DEADEB265DFB449E6 |
| VelaShowcaseScreen.kt | 67102DED38C760E05B1436945FE789CF406276D931CAC1CF0CD9A05D3DA3070F |

Only six preparation paths are added: three Kotlin androidTest files, one test
manifest, one test-only Gradle init script and this document. Production files,
dependencies, application startup, operational Room v9 and all published Market
Dataset storage/domain sources are unchanged.

The sensitive APKs were NOT built in the main worktree. A new detached worktree
was created at the exact target commit:

`C:\Android\audits\phase-3a3-r1-preparation-20261029\clean-worktree`

Published tracked source tree: `03343f74c59d11974449e644c546b4d2a16faef6`.
Published `android/app/src/main` subtree:
`96b2d35b5d459d9a7ce8c5740426563e10ca6a50`.
All tracked files in the detached worktree still match that commit. Only the
five explicitly audited test/support files below were copied there, with matching
source/copy hashes. No showcase sources or definitions are present in its app APK.

The developer's normal local.properties was not copied. The newly created build
file contains ONLY sdk.dir pointing to C:\Android\Sdk and
MANUAL_PAPER_SUBMIT_COMPILED=false. The init script rejects extra keys (including
credentials), any other SDK, any value other than literal false, and a showcase
directory. It changes only the instrumentation runner through finalizeDsl; it
does not edit build.gradle or production source.

Toolchain: existing C:\Android SDK, JDK
`C:\Android\Android Studio 2026.1.2\jbr`, Gradle home
`C:\Android\gradle-home`, offline/no-daemon/max-workers=2 and in-process Kotlin.

## Harness and context isolation

Source directory:
`android/app/src/androidTest/kotlin/com/vela/android/lab/core/marketdataset/runtime/`.

- MarketDatasetAndroidRuntimeIntegrityTest.kt: 13 separate AndroidJUnit4 cases.
- MarketDatasetR1Sandbox.kt: exclusive per-test storage capability and cleanup.
- MarketDatasetR1Runner.kt: restricted AndroidJUnitRunner subclass, plain Application.
- androidTest/AndroidManifest.xml: the dedicated instrumentation declaration.
- androidTest/market-dataset-r1.init.gradle: required test-only build configuration.

Every case uses the published
`AndroidMarketDatasetDatabase.open(context, datasetId)`, MarketDatasetStore,
production codec, transactions and V1 DDL. No JDBC, host fixtures, duplicated
production schema, Room fixture, Activity or trading UI is used.

The sandbox is allocated by Files.createTempDirectory under the instrumentation
package's own private cache directory. The context package must be exactly
com.vela.android.lab.test; its canonical cache must be inside that package's
private data directory. The conceptual database path is:

`<instrumentation-private-cache>/vela-3a3r1-<fresh-suffix>/vela_market_dataset.db`

A ContextWrapper overrides BOTH getApplicationContext() and getDatabasePath(name).
getApplicationContext returns that wrapper itself. getDatabasePath accepts ONLY
the published Market DB filename and returns the sandbox-owned file; it never
delegates an unsupported name. The test verifies wrapper identity, resolved
filename, ownership and nonexistence of that new file BEFORE calling the factory.
This matters because the published factory first calls context.applicationContext.
Overriding getDatabasePath alone would allow that lookup to discard the wrapper.
See [ContextWrapper API](https://developer.android.com/reference/android/content/ContextWrapper).

Forbidden normal Market/Room paths are derived from targetContext.dataDir and
the standard databases directory, without calling the normal getDatabasePath
(which can create that parent directory). Neither normal file is opened,
inspected, queried or deleted. Inequality/outside-location checks fail closed,
with no production-path fallback.

Each sandbox has an exclusive ownership marker containing its nonce. Cleanup
validates the canonical parent, fixed prefix, marker contents, absence of symbolic
links and the ENTIRE deletion set before removing anything. Only the known
database, its own journal/WAL/SHM sidecars and ownership marker are accepted.
Unknown files/directories abort cleanup and preserve the directory. No recursive
delete, caller-supplied path, pm clear, uninstall or app-data deletion exists.
All factory handles close before cleanup; a close failure prevents deletion.
Successful cases emit integrity/evidence before teardown.

Synthetic payload prices/times are fixed. Fresh nonces namespace only test
DatasetId, session ID and directory ownership. Provider=R1_SYNTHETIC, feed=TEST,
sourceClass=SYNTHETIC and assetClass=SYNTHETIC: these are NOT Alpaca observations
and never satisfy DatasetSourcePolicy.isCandidate. No credentials or real
market data are copied. Synthetic evidence never enters Room, normal Market DB,
Paper execution or an eligible training snapshot.

## Prepared test matrix — 13, not executed

| Case / method prefix | Prepared assertion |
|---|---|
| A a_creation | New exclusive file, user_version=1, seven tables/seven explicit indexes/16 triggers, complete metadata and exact test DatasetId |
| B b_configuration | DELETE, FULL=2, FKs=1, recursive_triggers=1 and busy_timeout=5000 observed on the ACTUAL factory-owned handle |
| C c_exactDecimals | 0.100000000000000001 versus 0.100000000000000002 remain unequal; raw 1.0 and 1.00 stay distinct lexemes; TEXT storage and typed readback |
| D d_adjacentNanoseconds | Event/received/available components one nanosecond apart survive close and a new Android connection |
| E e_explicitSessions | Missing registration rejected, first sequence zero, gaps rejected, contiguous append, durable high-water=1 after reopen |
| F f_idempotentRetry | Same raw/canonical batch is an exact no-op retry; changed receipt content rejected without high-water advancement |
| G g_directHistorical | Direct UPDATE and DELETE on raw/canonical rows raise SQLiteConstraintException with APPEND_ONLY, not merely an arbitrary SQL failure |
| H h_failureAfterSuccessfulSql | Raw, canonical, lineage, flags and ledger SQL succeed before a deliberately invalid next parent; total_changes proves intermediate work, all batch rows roll back |
| I i_revisionParents | Revisions 0/1 coexist, parent is previous CONTENT fingerprint, raw references rehydrate exactly, wrong parent rejected |
| J j_reopened1001Original | Reopened 10:01 original is visible at 10:03 under MarketAvailability; 10:05 correction is not; synthetic snapshot eligibility separately remains false |
| K k_closeAndNewFactory | Actual SQLiteDatabase.isOpen becomes false, new store/handle opens the SAME owned file, all raw/revision values/identities/fingerprints/high-water reconstruct |
| L l_engineForeignKeys | Actual integrity_check returns exactly ok, foreign_key_check has zero rows, full repository verifier agrees |
| M m_unsupportedOwnedSchema | Own marker DB at user_version=2 rejects factory open with UNSUPPORTED_SCHEMA, preserves marker/version, creates no Market metadata |

The configuration/direct-SQL checks use narrowly scoped reflection into the
application-owned store/adapter fields sql and db. They inspect the exact handle
created by the production factory, never a separate connection whose settings
could give a false result. No Android hidden-framework API is reflected. This is
intentionally coupled to this frozen baseline and fails if its shape changes.

For J, using DatasetSnapshotSpec.includes alone would be vacuous with synthetic
sources: it excludes BOTH revisions. The prepared test instead asserts the
positive and negative pure MarketAvailability outcomes, then separately asserts
both synthetic bars are excluded by the full snapshot policy. It grants no
training permission and never mislabels a synthetic fixture as ALPACA/IEX.

## Exact selection and startup audit

Dedicated class filter:

`com.vela.android.lab.core.marketdataset.runtime.MarketDatasetAndroidRuntimeIntegrityTest`

Only registered instrumentation in the verified final test APK:

`com.vela.android.lab.test/com.vela.android.lab.core.marketdataset.runtime.MarketDatasetR1Runner`

The runner rejects any argument set other than exactly:
class=<full dedicated class> and
vela3a3r1Authorization=OFFLINE_RUNTIME_EXPLICITLY_AUTHORIZED.
No package filter, class list, method selector, sharding or historical Room/Paper
suite can be selected through this entry point. Missing authorization or a
different filter fails before delegation to AndroidJUnitRunner.onCreate.
The token is an accidental-execution guard, NOT a substitute for human consent.

The first packaging inspection exposed AGP rewriting the test manifest's runner
to the stock runner. That intermediate artifact was rejected without executing
anything. A remove marker also generated a warning and was removed. The final
solution uses the explicit init script's androidComponents.finalizeDsl override,
then verifies the DECODED APK has exactly one dedicated runner. Merely reading
the source manifest is insufficient. Building without this script is NOT an
approved artifact; redo provenance and manifest checks after any rebuild.

The runner's newApplication uses plain android.app.Application, not
VelaLabApplication. Thus the target process does not instantiate the production
DI graph. The test setup checks the runner type, application type, class filter,
authorization and safe BuildConfig again. No ActivityRule, ActivityScenario,
service start, UI, stream, broker client, token, position refresh or Room DAO is
invoked. A StrictMode network-death policy is installed on the test thread and
restored in finally; it is NOT claimed as a process-wide firewall.

The unchanged production Application was also reviewed: all database/repository/
broker/stream properties are lazy, with no overriding onCreate or init block
activating them. The compiled app manifest still contains its ordinary main
Activity, unstarted Room MultiInstanceInvalidationService and AndroidX startup
provider. Startup dependencies were inspected from the cached pinned libraries:
lifecycle-process 2.8.4 registers lifecycle tracking; emoji2 1.3.0 defers loading
until first resume (no Activity is launched); profileinstaller 1.3.1 schedules
Android optimization/profile work. None is a VELA broker/stream/Room-opening
initializer. Framework/profile/cache side effects are not claimed absent; they
are distinct from protected operational/user databases. The sandbox guarantees
apply to the harness's database IO and setup/teardown, not every OS optimization
write in a running Android process. Device-wide offline isolation remains a
mandatory future defense before execution.

The pinned runner 1.5.2 bytecode confirms its super.onCreate starts execution;
all guards precede that call. Its newApplication lifecycle and exact-class
selection are documented by [AndroidJUnitRunner API](https://developer.android.com/reference/androidx/test/runner/AndroidJUnitRunner)
and [AndroidX runner/filter guide](https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner).

## Compile-only verification and APK provenance

Approved compile command, executed ONLY in the detached worktree's android folder:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --init-script app/src/androidTest/market-dataset-r1.init.gradle --offline --no-daemon --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process'
```

Exit=0. Both APKs compiled. The class bytecode contains exactly 13 org.junit.Test
annotations. No instrumented test ran. Final phase compile warnings=0 and
suppressions=0; unchanged global Gradle deprecation notices remain. The initial
cold script configuration also reported the existing build.gradle kotlinOptions
deprecation, not a new phase-source warning.

| Artifact | Application ID | Variant | Bytes | APK SHA-256 |
|---|---|---|---|---|
| app-debug.apk | com.vela.android.lab | debug | 28810501 | 2FBA2102947187E4F3FB56D0F4494EC809B2DFBD5673265A512432A41A4B1EC9 |
| app-debug-androidTest.apk | com.vela.android.lab.test | debugAndroidTest | 1060351 | 4B9925E8D06754834DD368ADD685CAA93E710595A4DD16697D36CA7AFDA2F549 |

Both APKs verify with APK Signature Scheme v2, one Android Debug RSA-2048 signer.
Certificate SHA-256 for BOTH:
`c0106e6df46127f68c312818124ab628637efd348e43b175dd4605e97c697adc`.

Paths relative to detached worktree:
android/app/build/outputs/apk/debug/app-debug.apk and
android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk.

Generated BuildConfig AND the app APK's DEX independently confirm:
MANUAL_PAPER_SUBMIT_COMPILED=false, ALPACA_TEST_KEY_ID="" and
ALPACA_TEST_SECRET="". This preparation adds no REAL/LIVE/Auto Paper capability;
no production execution guard is changed and the harness starts no trading
graph/UI. The APK still contains the baseline app and INTERNET permission; it
must remain offline during future instrumentation. Safe build flags alone are
not a firewall or authorization to launch the app.

Audited test/support source hashes (relative to android/app/src/androidTest):

| File | SHA-256 |
|---|---|
| `AndroidManifest.xml` | `94A04873807A0C99A3D2AB49DC589E6C2CF5D73A5CB0845D7B70FB0E6E7DD2CD` |
| `market-dataset-r1.init.gradle` | `83A54F5A3BCA715567FD53D9C3D58B7BC9094E31BCADF949603CDCDD8C8AA9E6` |
| `kotlin/com/vela/android/lab/core/marketdataset/runtime/MarketDatasetR1Runner.kt` | `3140D8CD89D76B375290A56D8231C6BF4295DF949C803685E1F9F769AB49B132` |
| `kotlin/com/vela/android/lab/core/marketdataset/runtime/MarketDatasetR1Sandbox.kt` | `DDA89A63C9C1BF5DC124CE4E23C338F95EC08BA18FFC7E8253C9DEEDCA5D4C58` |
| `kotlin/com/vela/android/lab/core/marketdataset/runtime/MarketDatasetAndroidRuntimeIntegrityTest.kt` | `CBAF6633539AB59E150B3FA112375F31D9E44FF78FD971995AA42C3C45094BC2` |

Source/copy hashes match. app DEX definitions of the three showcase classes=0.
The test APK still packages historical androidTest classes from the unchanged
baseline, but they are NOT an execution selection. The guarded runner/class
filter is mandatory; do not use connectedAndroidTest/connectedDebugAndroidTest.

Safety scan: exit=0, allowed_phase2v_submit=11, suspicious=0, forbidden=0.
Final guarded lintDebug: exit=1, errors=1, warnings=13, information=1.
The sole error remains pre-existing core/Symbols.kt:40 NewApi. Normalized
file/line/column/message comparison against the published audit reports gives
added findings=0, R1-source findings=0, new suppressions=0. One showcase-only
warning is absent from the clean worktree; this is not a source/lint fix.
Global lint is NOT green. Whitespace/final-newline/diff checks pass.

The published full host baseline remains 127 suites / 2347 tests per variant;
it was NOT rerun in this preparation. No fresh full-suite result is claimed.
No host JDBC result is used as Android runtime evidence.

## Evidence ready for the later runtime phase

Each case emits R1_EVIDENCE_BEGIN/END status output containing actual SQLite
version, user_version, journal/synchronous/FK/recursive-trigger settings,
raw/revision counts, session high-water, integrity_check, foreign_key_check row
count, test-owned path and verified reopen status. Standard JUnit instrumentation
output provides each case result and final count. Evidence is emitted before
successful sandbox cleanup. The incompatible-schema case labels its handle
test-only-incompatible-schema, not production-factory-owned; its absent Market
tables are reported N/A.

| Runtime evidence | Current value |
|---|---|
| Executed instrumentation count / individual results | NOT VALIDATED |
| Actual Android SQLite version | NOT VALIDATED |
| Actual Android schema/configuration/integrity values | NOT VALIDATED |
| Android decimal/nanosecond/rollback/revision/reopen results | NOT VALIDATED |
| VELA_Runtime_API34 identity / current network state | NOT CHECKED |
| Installed package/signature compatibility | NOT CHECKED |

Compile-only evidence is local under:
`C:\Android\audits\phase-3a3-r1-preparation-20261029`.
Key artifacts: compile-guarded-final.log, app-buildconfig-dex.txt,
test-manifest.xml, guarded-runner-dex.txt, prepared-test-bytecode.txt,
app-signature.txt, test-signature.txt, lint-debug-guarded-final.log/xml,
safety-scan.log and preparation-provenance.json.
The detached worktree/APKs remain available for review; no worktree cleanup,
package removal or device action was performed.

## Future device procedure — PLAN ONLY, NOT EXECUTED

1. Obtain the explicit human authorization below. Recheck source hashes, both
   approved APK hashes, signature and single-runner manifest. Any rebuild needs
   new provenance; never use an intermediate artifact from an earlier log.
2. Identify one explicit serial and verify AVD name exactly VELA_Runtime_API34
   and SDK 34. VELA_Lite, another serial or an ambiguous identity is forbidden.
   No snapshot restore and no clock manipulation.
3. Stop target/test packages. Record original network state, then isolate the
   device fully offline. Verify isolation, including emulator virtual Ethernet/
   routing or UID-level egress as needed; do not assume disabling Wi-Fi alone is
   sufficient. If isolation cannot be demonstrated, STOP.
4. Inspect installed target/test package metadata and PUBLIC APK certificate
   without opening their databases/credentials. Require signature compatibility
   with the recorded certificate and a nondestructive same-package install
   path. No uninstall, pm clear, data reset or implicit downgrade. If replacing
   either package requires data deletion or an unsafe downgrade, report
   BLOCKED_3A3R1_INSTALL_PRESERVATION and stop.
5. Only after those checks, install the hash-pinned safe app and test APKs by
   compatible data-preserving replacement (-r; -t for the test APK). Do not
   launch the launcher Activity. Keep networking isolated.
6. Execute exactly the dedicated class through its dedicated runner, once.
   The following is a FUTURE command, not authorization and not executed here:

```powershell
& 'C:\Android\Sdk\platform-tools\adb.exe' -s $r1Serial shell am instrument -w -r -e class 'com.vela.android.lab.core.marketdataset.runtime.MarketDatasetAndroidRuntimeIntegrityTest' -e vela3a3r1Authorization 'OFFLINE_RUNTIME_EXPLICITLY_AUTHORIZED' 'com.vela.android.lab.test/com.vela.android.lab.core.marketdataset.runtime.MarketDatasetR1Runner'
```

7. Capture complete stdout/stderr, individual JUnit outcomes, exact executed
   count (expected 13, zero skips/failures/errors), every case's runtime evidence,
   and cleanup outcome. Do not substitute the prepared count for the actual
   result. A setup/cleanup failure may leave only an exclusively owned R1
   directory for later owner-checked investigation; never broadly delete caches.
8. Stop BOTH packages before restoring the recorded original network state.
   Preserve installed app data; leave packages installed and stopped. Do not
   reinstall a credentials-bearing developer APK or open operational databases
   as part of this phase.
9. Report actual Android runtime results only after execution. Success on API 34
   does not certify API 29, arbitrary devices, physical power-loss durability or
   Android concurrency. No 3.a.4 ingestion or trading is authorized by R1.

### Exact human checkpoint

Before ANY emulator start, device access, install or instrumentation, request an
explicit answer such as:

> Autorizo la ejecución instrumentada offline de la clase R1 en
> VELA_Runtime_API34, con los APKs y hashes de este reporte, preservando los datos
> instalados y deteniéndote si la firma, instalación, selección o aislamiento no
> son seguros. Sin broker, streams, cambios de reloj ni snapshots.

Preparation/readiness and the runner's token do not grant that authorization.
No runtime step above may begin in this preparation turn. If context/storage
isolation fails, use BLOCKED_3A3R1_TEST_STORAGE_ISOLATION; if runner selection fails,
BLOCKED_3A3R1_INSTRUMENTATION_FILTER_UNSAFE; if build flags/provenance fail,
BLOCKED_3A3R1_UNSAFE_APK_CONFIGURATION.

**STOP: no emulator, install, instrumentation execution, broker or 3.a.4.**

</details>

</details>
