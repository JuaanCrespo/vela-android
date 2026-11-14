# Phase 3.a.3-R1 — Independent Android runtime evidence review

Review date: 2026-11-14, America/Buenos_Aires.
Runtime date: 2026-11-13, America/Buenos_Aires.

## Verdict and review scope

Runtime verdict independently supported: **PASS_3A3R1_ANDROID_SQLITE_RUNTIME_VALIDATED**.

Evidence review: **PASS**. Publication gate: **READY_3A3R1_TO_CLOSE**.

Exactly **13 executed / 13 passed / 0 failed / 0 skipped / 0 automatic retries**,
in one authorized full-class invocation. This audit closes the R1 evidence gap
for the specific Android runtime and operations below. It is not a new execution.
The publication commit hash and actual remote equality must be supplied in the
post-push handoff; a self-containing commit hash is not embedded here.

Reviewed repository: `G:\vela-android`, branch `main`.
Required source/artifact commit:
`34ced1d34941560ba9b9771d97a33ea1809e3125`.
At review entry, local HEAD and local origin/main both equal that commit,
tracked files are unchanged and the index is empty. The prior FIX2 publication
record ties the approved artifacts and recorded source hashes to that same commit.
All 15 entries in the runtime source/showcase manifest still match their SHA-256.

This review ran no ADB command, emulator/app launch, Android instrumentation,
Gradle test/build, broker request, database open, migration or UI implementation.
Local APK hash/signature verification and local APK DEX decoding were repeated
read-only. Only this evidence report is authorized for the publication commit.

Primary evidence directory:

`C:\Android\audits\phase-3a3-r1-full-runtime-20261113`

Primary narrative: `full13-report.md`. It was not accepted as proof by itself.

## Raw results and exact test identities

Class:
`com.vela.android.lab.core.marketdataset.runtime.MarketDatasetAndroidRuntimeIntegrityTest`.

Runner:
`com.vela.android.lab.test/com.vela.android.lab.core.marketdataset.runtime.MarketDatasetR1Runner`.

The saved exact arguments select that class, its dedicated runner and the
owner-approved authorization gate only. No authorization token is reproduced here.
The controller has a single Process.Start site and refuses an existing invocation
marker; its final record reports one invocation, no retries and no failure status.
The instrumentation process is launched separately from the Device logging helper,
so the absence of an instrumentation command in device-operations.log is expected:
the argument file, start/end markers and raw JUnit stream record that invocation.

Independently reparsed instrumentation-stdout.log, rather than trusting summary
booleans: 13 start events (status 1), 13 success events (status 0), unique current
indices 1–13, one exact class and numtests=13 throughout. All 13 method identities
are distinct and match both the baseline Kotlin source and the actual approved
APK's decoded 13 JUnit annotations/methods. Diagnostic status 2 events are not tests.
There are no negative per-test status codes, skips, crashes or failure trailer.
The stream ends with `OK (13 tests)` and instrumentation result code -1;
stderr is whitespace-only and the saved native client exit code is zero.

The raw execution order was H, C, M, I, L, J, G, A, B, D, E, F, K.
The A–M presentation below is not a claim of alphabetical execution.

| Case | Exact method, within the class above | Actual result | Assertions established by this passing method |
| --- | --- | --- | --- |
| A | `a_creation_hasV1MetadataAndExclusiveDataset` | PASS, once | V1 metadata, exact dataset namespace, 7 user tables, 7 explicit indexes, 16 triggers, pinned schema fingerprint; repository integrity valid. |
| B | `b_configuration_isObservedOnTheActualFactoryConnection` | PASS, once | DELETE/FULL/FK/recursive readback on the factory handle; distinct contender gets SQL_BUSY under a held writer; no partial write/high-water change; only a later explicit append succeeds once. |
| C | `c_exactDecimalsAndOriginalRawLexemesRoundTrip` | PASS, once | Different exact canonical decimals survive; raw bid spellings 0.100000000000000001, 1.0 and 1.00 remain distinct; raw/canonical decimal columns read as TEXT. |
| D | `d_adjacentNanosecondsSurviveANewAndroidConnection` | PASS, once | Two receipts survive a new connection with event nanos 1/2 and available nanos 3/4, plus full receipt equality. |
| E | `e_explicitSessionsContiguousSequenceAndDurableHighWater` | PASS, once | Unregistered session and sequence gaps rejected; explicit registration and contiguous receipts advance high-water to 1, retained after reopen. |
| F | `f_idempotentRetryAndChangedContentConflict` | PASS, once | Identical batch retry returns idempotent counts; changed receipt content raises RECEIPT_CONFLICT; original receipt and high-water retained. |
| G | `g_directHistoricalUpdateAndDeleteAreRejectedByAndroidTriggers` | PASS, once | Direct UPDATE and DELETE on raw_receipts and canonical_revisions raise APPEND_ONLY; unchanged evidence and integrity checked after each attempt. |
| H | `h_failureAfterSuccessfulSqlRollsBackTheWholeBatch` | PASS, once | total_changes increases before a revision-parent failure, yet session/high-water/raw/revisions/lineage/flags all roll back to zero. |
| I | `i_revisionParentsAndCommittedRawLineageRemainExact` | PASS, once | Two exact revisions, previous-parent fingerprint and committed raw references retained; incorrect parent rejected; two lineage links and no FK violations. |
| J | `j_reopened1001OriginalIsVisibleBeforeThe1005Correction` | PASS, once | After reopen, 10:01 original visible at 10:03 while 10:05 correction is not; synthetic source is separately excluded from snapshot eligibility. |
| K | `k_closeAndNewFactoryInstanceReconstructAllDurableEvidence` | PASS, once | Closed original handle and different factory/store/handle reopen reconstruct exact receipts, event/receipt identities, fingerprints, revisions and high-water. |
| L | `l_engineForeignKeysAndFullRepositoryIntegrityAgree` | PASS, once | Actual SQLite integrity_check=ok, foreign_key_check empty and full repository verification valid. |
| M | `m_unsupportedOwnedSchemaIsNotRecreatedOrMigrated` | PASS, once | Owned V2 file rejected with UNSUPPORTED_SCHEMA; V2 marker/user_version preserved and no V1 metadata created. |

The domain claims in this matrix come from successful assertions in the
hash-verified test APK/source, not invented value dumps. The raw diagnostic blocks
print runtime/configuration/count/path observations; they do not print every
decimal, metadata field or domain object. In particular, G is direct UPDATE/DELETE
coverage of two historical tables, not Android negative-SQL coverage of every
operation on every table. J validates the stated availability predicates and
synthetic exclusion, not a live provider pipeline or materialized real snapshot.
K is an orderly close/reopen experiment, not a device power-cut experiment.

All 13 test-results.json rows match raw method identity, result, SQLite version,
receipt/revision counts, high-water and path. All 13 runtime-evidence.json blocks
match the raw blocks field by field, with 13 unique method/path pairs. These JSON
files and validated-full13-result.json are derived from the same invocation;
they are not independent repeat runs or separate raw measurements.

## Actual Android SQLite findings

Device identity is recorded repeatedly around execution and in the final read-only
checks: AVD **VELA_Runtime_API34**, serial **emulator-5554**, **Android 14 / API 34**.
The recorded emulator executable and SDK are under `C:\Android\Sdk`.
Cold boot followed the Windows restart; saved arguments include no snapshot load/save
and no wipe/clock change.

| Invariant | Actual evidence and scope |
| --- | --- |
| SQLite engine | 3.39.2 in all 13 raw evidence blocks; also in the earlier successful smoke |
| Schema | V1/user_version=1 in all 12 production-factory-owned cases |
| Inventory | A asserts exactly 7 user tables, 7 explicit indexes and 16 triggers; successful smoke independently asserts the same inventory |
| Fingerprint | `a539bc2a020922419c27ae782d8c4bd43f02af4c642b1930dd8c59bc0bea6e47`; A's metadata assertion and smoke's printed diagnostic agree |
| Journal / synchronous | delete / 2 (FULL) in all 13 recorded blocks |
| Foreign keys / recursive triggers | 1 / 1 in all 12 factory-owned cases; B explicitly asserts them |
| Engine / FK integrity | integrity_check=ok and zero foreign_key_check rows in all 13 blocks; L asserts engine and repository agreement |
| Busy policy | PLATFORM_MANAGED; B records 2500 ms diagnostically and proves SQL_BUSY behavior, not a fixed 5000 ms contract |
| Reopen evidence | D, E, J and K record reopened_verified=true, backed by their actual assertions |

M intentionally creates an incompatible **test-owned V2** database outside the VELA
factory. Its marker is preserved and VELA refuses to open it. That control file
records FK=0 and recursive_triggers=0; those values are not attributed to a
production factory connection. Requiring V1 or FK/recursive=1 for M would misstate
the purpose of the test. Its engine/FK integrity diagnostics still succeed.

Timing clarification: JUnit reports **7.493 seconds**. The saved instrumentation
wall-clock measurement is **8.5880421 seconds**. Controller startedUtc/endedUtc span
**15.9850845 seconds** including isolation and restoration. The primary narrative
incorrectly calls approximately 8.59 seconds the complete controller time; that
is a documentation-only timing attribution error, corrected here without altering
or discarding the original evidence. Test outcomes and safety records agree.

## Approved artifact and source identity

Both original local APKs still exist. This audit recomputed their SHA-256 and
sizes, then independently reran local apksigner verification: both verify with
APK Signature Scheme v2 and one Android Debug signer. This is an integrity identity,
not a production-release signing certification.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| com.vela.android.lab debug APK | 28810501 | `6ECA69AA49C8C006AE07370004A730F3A2CD62F1F89E6875C60CD794E3F28CC3` |
| com.vela.android.lab.test APK | 1024659 | `DBB155AD0E2A7E9371B4A05344E0D019150C16C21430C096697B7AD5501880B2` |

Signer certificate SHA-256, both:
`C0106E6DF46127F68C312818124AB628637EFD348E43B175DD4605E97C697ADC`.

The full test class was decoded again from the verified APK. Its normalized DEX
text equals the saved full13-dex.txt, contains exactly 13 JUnit annotations and
the published A–M identities, and retains schema fingerprint, SQL_BUSY and
StrictMode network-death code. Saved manifest/runner DEX identifies the exact
instrumentation target and a plain android.app.Application, not VelaLabApplication.
Saved BuildConfig DEX has manual Paper submit compiled false and empty Alpaca
credential fields. Readiness/default-mode DEX retains READ_ONLY and disabled
Paper/LIVE/Auto guards. No credentials or runtime authorization tokens are
embedded in this report.

Operational Room remains **version 9**, as declared in VelaDatabase.kt.
Its code/schema/migrations and all production/test code are unchanged by this audit.

## Safety evidence: observations versus inference

| Area | Evidence and conclusion |
| --- | --- |
| Installed artifacts / preserved data | package-state-before/after JSON are identical, including both approved installed APK hashes, app UID 10192 / test UID 10193 and data-directory inodes 311854 / 344220. No reinstall was needed in the final 13-test run. Inode equality is identity evidence, not a full database-content checksum. |
| Offline containment | Saved IPv4 and IPv6 OUTPUT observations twice show the temporary chain as the first rule. Each chain allows loopback and only established root-UID ADB replies, then rejects other egress regardless of interface. The app UID does not qualify for the ADB exception. |
| Effective isolation | Reserved-address IPv4/IPv6 probes fail and increment the rejection counters. No Alpaca endpoint probe appears in controller commands. Final reject counters are IPv4=7 and IPv6=20 packets, including system/probe traffic; they are not VELA request counts. |
| Storage authority | Baseline sandbox checks target package/UID, canonical private cache containment, new directory/owner marker, no symlink/fallback, exact DB name and applicationContext wrapper. It derives forbidden normal database paths without opening them. |
| Unique temporary storage | 13 raw paths are distinct children of /data/data/com.vela.android.lab/cache/vela-3a3r1-*/vela_market_dataset.db. Source setup and successful results establish ownership/wrapper checks. |
| Close and cleanup | Successful @After closes stores, then deletes only validated owned files and directory. D/K explicitly assert closed old handles; the smoke explicitly records close_verified/cleanup_verified. Every one of the 13 final host absence checks returns exit 0. Before/final cache lists contain only the same preexisting lock-file name. No broad cache removal occurred. |
| Existing databases | Test/sandbox/factory code is confined to the owned cache paths; saved host command inventory contains no /databases access or database-opening tool. No normal Market DB or operational Room open is on the selected path. No assertion of a full filesystem-access trace is made. |
| App/network boundaries | Plain Application avoids the production application graph; selected methods use synthetic fixtures, a StrictMode guard on the test thread and SQLite-only code. No collector, broker, stream or execution entry point is called by that reviewed path. |
| Zero operations | Broker GET/POST=0, streams=0, orders=0, Paper/REAL/auto-trading=0, ML/collector activation=0 are supported by the reviewed exact APK/source path plus isolation controls, not merely by absent logcat messages. No HTTP/socket capture proves a separate request-attempt count; firewall counters are not used as such proof. |
| Restoration | IPv4/IPv6 filter tables equal before/after after counter normalization; temporary chains are gone. Radios are unchanged and saved default connectivity is VALIDATED. adbd returns to UID 2000. |
| Final package state | Force-stop precedes network restoration; both recorded pidof outputs are empty. Final read-only checks again find both packages absent. This describes the archived run's final state, not the live device state on review day. |
| Forbidden work | Full-run command/controller records show no uninstall, pm clear, wipe, downgrade, snapshot restore or clock manipulation. Current review performs no device/broker access. |

The final successful run has no separately archived logcat or JUnit XML file.
The complete per-method instrumentation status stream is the raw JUnit evidence;
the archived relevant/failure logcat files belong to the earlier failed experiments.
No missing final logcat is silently replaced by an invented capture, and absence
of logcat messages is never the basis for a zero-network conclusion.

## Historical failures, smoke and reader incident

These older failures remain failures; they do not enter the 13/13 success count.

| Stage | Independently reviewed actual evidence | Meaning |
| --- | --- | --- |
| Original storage-parent run, 2026-10-29 local | Host stdout contains the first setup failure and is partial. failure-logcat records six setup starts and six failure notices, five finished events and the sixth interrupted; seven never started, zero passed/SQLite bodies. | NoSuchFileException under the instrumentation-package cache. Exact missing-ancestor/DAC/SELinux mechanism was not proven. It is not a V1-schema failure. |
| Target-cache preflight, 2026-10-30 | Exact targetCacheOwnershipWrapperPathAndExclusiveCleanup method: one start, one success, OK (1 test). | Storage isolation only, not SQLite runtime validation. |
| First SQLite run, 2026-10-31 | H fails during adapter initialization, C starts and is interrupted; eleven unstarted, zero passed. | The old execSQL busy_timeout setter encounters a row-returning PRAGMA exception before schema/domain assertions. |
| Separately authorized repeat, 2026-10-31 | H/C fail with the same adapter exception; M starts and is interrupted; ten unstarted, zero passed. | Separate owner-authorized experiment, not an automatic retry or passing run. |
| FIX2 smoke, 2026-11-13 | Exact openV1SchemaAndRequiredConfiguration method: one start, one success, OK (1 test), complete smoke diagnostics and closed/cleaned sandbox. | Smoke validated on the approved FIX2 APKs; not the full suite. |
| Final full class, 2026-11-13 | Thirteen distinct successes, complete OK (13 tests) trailer and 13 evidence blocks. | The final R1 Android SQLite result reviewed here. |

The persisted **PowerShell reader incident belongs to the successful smoke**.
Invoke-OneSmoke.ps1 waits for process exit and completed stdout/stderr reads,
saves the closed output and end marker, verifies the one successful JUnit result,
then calls String.Split with an ambiguous overload. Integer 2 is interpreted as
StringSplitOptions and throws MethodException. Thus controller-result.json truthfully
retains testPassed=false and the host failure; it does not negate the already
recorded Android success.

Verify-SavedSmokeEvidence.ps1 subsequently uses PowerShell's split operator,
parses the same complete saved log and performs read-only metadata/cleanup checks.
Its instrumentation command string is compared as data, never executed; its
actual DeviceRead calls do not run tests. verified-smoke-result.json discloses the
incident, preserves the original controller error and records one invocation,
zero retries. No code repair or test rerun was used to recover that result.
The final full-class controller separately reports controllerFailure=null and
zero restoration errors. There is no archived full-suite reader exception to
mislabel as a SQLite failure. The interrupted historical failure logs are partial
by design; the final smoke and final full-suite logs are complete.

## Evidence inventory and SHA-256

These are hashes recomputed from the actual local files during this review.
They bind this report to those bytes, but are not a trusted timestamp or proof
against historical coordinated tampering. Raw logs, package metadata, network
addresses and authorization content remain local; only filenames, sizes, hashes
and non-sensitive findings are published.

All 70 files under the primary full-runtime directory were inventoried:

| File relative to the primary evidence directory | Bytes | SHA-256 |
| --- | ---: | --- |
| `adbd-final-identity.txt` | 280 | `DA0C47B34B9C97AC3DB55BBFB5A94B5CFD446905EC1D4FCB1FFF67B33862B0E4` |
| `app-buildconfig-dex.txt` | 1165 | `F48A0D7905AE7D5CB81746E91E1E65B49C88ADFE06BF62D83449E06B04E4876C` |
| `app-manifest.xml` | 3509 | `F959F182B925C19D79B09A905023C470147D3CAC61798275A8354110E74953F8` |
| `app-signature.txt` | 975 | `9795F594BCB19BD7AABA8C0A5BB54A64D250C8B81511A07EBDCE918470407042` |
| `app-state-dex.txt` | 15810 | `D5F027E3AA6A777A30843134DEB1982CCF0758CEC55152EB5A070834F63C10FD` |
| `authorization-dex.txt` | 8890 | `D2C776789092A27D26BFF7702261A3D4D0AFE98410D4EB5568474586D3857640` |
| `cache-after-run-before-host-cleanup.txt` | 20 | `83D541E4BFB9BE3130B9BDF2CBE73278D1E57524370F7B901A8E7CB400CC96A9` |
| `cache-before-names.txt` | 20 | `83D541E4BFB9BE3130B9BDF2CBE73278D1E57524370F7B901A8E7CB400CC96A9` |
| `cache-final-names.txt` | 20 | `83D541E4BFB9BE3130B9BDF2CBE73278D1E57524370F7B901A8E7CB400CC96A9` |
| `cache-runtime-before-names.txt` | 20 | `83D541E4BFB9BE3130B9BDF2CBE73278D1E57524370F7B901A8E7CB400CC96A9` |
| `com.vela.android.lab.test-before-package.txt` | 3888 | `94D653B1340D6714A49FB76E0DEF4CE15F72682A36B3A78A29AD8352A2D65737` |
| `com.vela.android.lab.test-final-process.txt` | 5 | `F01A374E9C81E3DB89B3A42940C4D6A5447684986A1296E42BF13F196EED6295` |
| `com.vela.android.lab-before-package.txt` | 5666 | `CA330EA590078E540839D92CBABA4AD74641DD130AEC3B59FAD25EF0184AF60C` |
| `com.vela.android.lab-final-process.txt` | 5 | `F01A374E9C81E3DB89B3A42940C4D6A5447684986A1296E42BF13F196EED6295` |
| `controller-result.json` | 744 | `AA0D543FC281EC85BBB7B82B4AC6F244FEB0B940B6CF47AB9A8AFD9B33A42C14` |
| `device-operations.log` | 187566 | `D954D575F03112EC7973F557BDEC99F14911FF240D848F05B41BB78C59B2EA91` |
| `emulator-stderr.log` | 0 | `E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855` |
| `emulator-stdout.log` | 13766 | `C043E99B8EBCF6895F44C311445E454039B5760305EAC1B888B1629CB065440C` |
| `exact-instrumentation-arguments.txt` | 299 | `EB11C42C1A62D1E675A4AD29A159AA0AAF747EDD675965BF7338807CC048BCD2` |
| `expected-test-methods.txt` | 686 | `F6B9ADF9E6D08E279F6DFC86561551AAEF81FF173FBF0D38E0DCAEACE8EDD886` |
| `final-readonly-checks.log` | 3292 | `B1273699AC389E150258B2F8DC198650E674FADD0B3CC2D9150C15C99D647258` |
| `final-verification.json` | 411 | `A483B8C93EBF428B48DE9F903355AD29B0AB4FCF1D84C91630B01A5636F1E6A4` |
| `full13-dex.txt` | 292295 | `12796DCAECF6AE87E750A5F34B58C1C5C8D553F79A78F0D2760D27636AB4BFDD` |
| `full13-report.md` | 10687 | `91D01C85878B1BA59D49E1EE84765FE075F7796E9EA2A273FC2C6E67A5739288` |
| `git-after.txt` | 371 | `6136D449EDED93F2514CAC7BE5D2DB699CF729A3D291B2A3DA3EB34A492C2DFA` |
| `git-before.txt` | 371 | `6136D449EDED93F2514CAC7BE5D2DB699CF729A3D291B2A3DA3EB34A492C2DFA` |
| `git-final.txt` | 371 | `6136D449EDED93F2514CAC7BE5D2DB699CF729A3D291B2A3DA3EB34A492C2DFA` |
| `host-emulator-processes.txt` | 650 | `DFCC02125598439C556E1428758E6EBEABC5A468EE5A1D8A7C5885D6B0A76BC2` |
| `installed-instrumentation.txt` | 146 | `3EDA5DEA8A2DDAAC7A501955FB93E6B6AC8543BDE67996F471FBB23EF1B9235E` |
| `instrumentation-elapsed-seconds.txt` | 14 | `D270CB1013D84BE6955A16BC51DC58091C51A6F6012CDC8F2DACF4472D8C7B53` |
| `instrumentation-exit-code.txt` | 6 | `9585FBECE5C402088F2D6A2DEF7E60D1C7FFECC24B16D32D5823A064B008935B` |
| `instrumentation-stderr.log` | 5 | `F01A374E9C81E3DB89B3A42940C4D6A5447684986A1296E42BF13F196EED6295` |
| `instrumentation-stdout.log` | 20276 | `947160ED295ED2784E40A8B524D3C37D11D30A6E62CF7629B807CB4675CD1F84` |
| `invocation-ended.txt` | 38 | `57500AE83044CB436CC3D7B840E3F8D19F95AC7947F1607E9888A665D8A17F5F` |
| `invocation-started.txt` | 38 | `B9829082B8FE331EA1A92F6A502AFB9AE1663312512F2DA3755D68470072031F` |
| `Invoke-Full13-Once.ps1` | 19758 | `F8E5B72507F6212C7498826E98339D119E59DBDD5D717B3C4C32578E21B181E9` |
| `isolated-ip6tables-rules.txt` | 238 | `669BF8EA66393421B1F9630E5C74C6CEADC7D2F09FCA3BD0CC5AAFB2C22811B5` |
| `isolated-iptables-rules.txt` | 237 | `54D412611188497F1695B91201D74BE38049D387D15693DDC84690145753E72A` |
| `isolation-ip6tables-counters-after.txt` | 509 | `78AFB3392C05C86FB483EB0C0EAE869235C6C8B0007A62B1338B0E608B483B33` |
| `isolation-iptables-counters-after.txt` | 508 | `AEF4E2F8A9350742B08172ABCA3DFDE50BF59C37A2323EE8872FF2E4C693C786` |
| `isolation-ipv4-counters-before.txt` | 508 | `FEE519F477EAAAFB65C19A2A18E0D927CF04AF2E3FCA0CC7B88067246EE70829` |
| `isolation-ipv4-reserved-probe.txt` | 190 | `7BFAB914D62F02184F92FF03CE8AEE65DF8E0DDF5FAA170B58148B9F6DE479C0` |
| `isolation-ipv6-counters-before.txt` | 509 | `34A9A9BFFEFD62158AADB47318030F99DE5B6036FABFC624E280F1543F933601` |
| `isolation-ipv6-reserved-probe.txt` | 243 | `005BA01695B9E96D151BE4715949D1B5709797BD6A6D2ED1841851F7135CA2DC` |
| `network-after-ipv4.txt` | 5636 | `98C366DED5EEB6A09930A651606A00785D20A83A15153BE2776EFA1536C10447` |
| `network-after-ipv6.txt` | 5135 | `32CA3F025CB6DBDFE11429738169C73116D89AE4FBBA2AD7470482FA0B7F3EA7` |
| `network-before-addresses.txt` | 3247 | `6F5445320133E89B5F2205A19AFD0417794B52895CBD861242A374FDED58F85E` |
| `network-before-connectivity.txt` | 55872 | `D8893B8DEBBB449745A411E2D92AB13A5AC79C3F18E92543C74735EA0AE69EFB` |
| `network-before-ipv4.txt` | 5650 | `3AF4944FBE6D04BA5F9E1DD05ACFC39155D5B03087F7D6C8779F0F84034620A1` |
| `network-before-ipv6.txt` | 5147 | `3B0D9B7517B3CE1237460A29D36AAA95B155DA8034821198C47FAE20D0AD45CC` |
| `network-before-routes-v4.txt` | 1230 | `B2F33A12809BF2CFC50311FDDE6E0A697BDF3B9DF20D8A5584C88BCE6BFD51AD` |
| `network-before-routes-v6.txt` | 1753 | `BC6079C092C5C36BAD16623AF456C11B62D09CCC75987F8E46DC1AC01BD76A0B` |
| `network-restored-connectivity.txt` | 55872 | `D8893B8DEBBB449745A411E2D92AB13A5AC79C3F18E92543C74735EA0AE69EFB` |
| `offline-ui-state-dex.txt` | 45747 | `1771CEC71401C698A7ED5F41659181C384CADD0903099B6E1B87F2E4AF9FCE53` |
| `owner-authorization.txt` | 13068 | `E3A3D71F30EF14D34813EC36A6CF5B2946CBDF54F297569EB8FEF6F61DEC3CAC` |
| `package-state-after.json` | 443 | `5B6B5582AC96993A0714E20B79C2CD783D07F3949CB4A404BD639D9BF74A25E9` |
| `package-state-before.json` | 443 | `5B6B5582AC96993A0714E20B79C2CD783D07F3949CB4A404BD639D9BF74A25E9` |
| `paper-readiness-dex.txt` | 53678 | `49EB2AFF0F991A7B07EA8762002B5CCF6D8D38AA1BE0ADE56F69D409936935EA` |
| `Precheck-Full13.ps1` | 13883 | `8E52E9D43AB316E84B55D73CB3EF5CFC2693A548E2DD4F3DBB46D2D2C9305620` |
| `precheck-result.json` | 4823 | `DD6A86F907CFBB4635D0C866A90E116F725749E8B6C170B98425EB82AEDAA4B2` |
| `radio-after.txt` | 49 | `979FB2C6CA7FA503BD105C7CB7F20F1D07B7915CA4E7052DAE4C69853E403181` |
| `radio-before.txt` | 49 | `979FB2C6CA7FA503BD105C7CB7F20F1D07B7915CA4E7052DAE4C69853E403181` |
| `runner-dex.txt` | 8813 | `2BD3E04EE0A4FF9324391AEE70BC51E6A5E9D3EE0A6A1DCC2A588D4D03E534C2` |
| `runtime-evidence.json` | 10770 | `5F3A8DAD13EC55BC979A93992A73A878A5840D44B65C8CC8FAF2F4A7B02DF66C` |
| `source-hashes-before.json` | 3301 | `B10CF562E9361708B9A7ECA373AA435E6128A944B16DDCA837B359DAB5202B12` |
| `test-manifest.xml` | 2577 | `BB0C94C7F890B0E8807B41E11CECA745F49165C5492984359762C5147DF75D66` |
| `test-results.json` | 7099 | `0E69F5A49E4919C65DE5223A1A27C7E7BAFB58699877A303A0ED0190593DAEB5` |
| `test-signature.txt` | 975 | `9795F594BCB19BD7AABA8C0A5BB54A64D250C8B81511A07EBDCE918470407042` |
| `validated-full13-result.json` | 18676 | `EA65F6AC71AE7789389E12998E74FEFEEFDCD0C70B7BA6B9E81A88430A0C91F6` |
| `Verify-Full13-Evidence.ps1` | 17904 | `C6AF7ED0D41996F5105A0E4C3E250D7C62E74C3981A1D13A2CD389E01C7A1869` |

Historical supporting files, relative to `C:\Android\audits`:

| Evidence file | SHA-256 |
| --- | --- |
| `phase-3a3-r1-runtime-20261029/instrumentation-stdout.log` | `7C1B20CBC881E79F976A53D59F5182447EAFCE2BD23CEEF9B5A4ACF0E2023692` |
| `phase-3a3-r1-runtime-20261029/failure-logcat.txt` | `3034CF31C7420B155B432B8D956D5F8A39FFF422F7EE34DE6E7AA097E72DE9B2` |
| `phase-3a3-r1-storage-preflight-20261030/instrumentation-stdout.log` | `621A061E86F90E3EB4BD473219EAD2EDCFCDC34DE0801928476C411FDB12C47C` |
| `phase-3a3-r1-sqlite-runtime-20261031/instrumentation-stdout.log` | `5D6C3CEF1B7D8D4ED31297297A2A518F7EE8F909A8CCDC5975C98E3C7204EF88` |
| `phase-3a3-r1-sqlite-runtime-20261031/relevant-logcat.txt` | `C4AE4D11F97B8EC07D83137CA747F00067B1520438F7630FB7349E69565F1FC6` |
| `phase-3a3-r1-sqlite-runtime-repeat-20261031/instrumentation-stdout.log` | `A8EC90F0A7DAA14A7E51F35EC1A75BB2F21403A043655BD9E2A2863D4564A82A` |
| `phase-3a3-r1-sqlite-runtime-repeat-20261031/relevant-logcat.txt` | `821DF941791DE563DED732F499C03099F23D9FC57861B7DACD5815F31144CAC3` |
| `phase-3a3-r1-fix2-smoke-20261113/instrumentation-stdout.log` | `68128A7506ECC1BF783AB6823CBF93B6E54BBF39548243CD332E54512F540B24` |
| `phase-3a3-r1-fix2-smoke-20261113/failure.txt` | `5BD92E6B824A5106E4D5D2375F9AD04507308A99BDDADF05E3DB3B1A838578B2` |
| `phase-3a3-r1-fix2-smoke-20261113/controller-result.json` | `D3F90F7B2215D7DCA61B63851482B8D20232D2BA007657F045882C89449B8203` |
| `phase-3a3-r1-fix2-smoke-20261113/Invoke-OneSmoke.ps1` | `01348A04579043EBA6A5F8A393FE678684B575B1E26ED25ECD577EDE587AEC86` |
| `phase-3a3-r1-fix2-smoke-20261113/Verify-SavedSmokeEvidence.ps1` | `1DB7ADC27AA81AA593CA1ADE67E2BF7D638A01CD8C5BE0911E3602C691630CFE` |
| `phase-3a3-r1-fix2-smoke-20261113/verified-smoke-result.json` | `C8561F0C6A534307CBB2BB8E1F0F88A52463B66E9E88CA8632548A849B727936` |
| `phase-3a3-r1-fix2-publication-20261031/publication-result.json` | `E6FF1A68D2554FC16DB49388ADF7FF7198CC1DAD0A268EE90944FD4868390D95` |

Preserved untracked user showcase files, under
`android/app/src/main/kotlin/com/vela/android/lab/ui/showcase/`:

| File | SHA-256 |
| --- | --- |
| `VelaShowcaseComponents.kt` | `A36707DB0FAF4E98FCC704DDCF917D5966E37E691AA4D2EA5C510AB5BA13B4FD` |
| `VelaShowcaseFixtures.kt` | `825D97E44019A0D5E13569C01F5307E5E807BCFD17141D3DEADEB265DFB449E6` |
| `VelaShowcaseScreen.kt` | `67102DED38C760E05B1436945FE789CF406276D931CAC1CF0CD9A05D3DA3070F` |

## Validation limits and next phase

Validated: the exact instrumented R1 operations above on the recorded Android 14 /
API 34 emulator with Android SQLite 3.39.2 and the approved APKs/source commit.

Not certified by this evidence:

- Android API 29–33 runtime behavior, other Android versions, all OEM devices or physical devices.
- Physical power-loss, storage-device failure or uncontrolled process-death durability.
- Future schema migrations, schema redesign, WAL or arbitrary future connection/pool behavior.
- Live Alpaca ingestion, real market-data quality, collection scheduling or production-scale performance.
- Feature generation, ML training/evaluation, profitable predictions, Paper automation or real trading.
- A universal fixed Android busy_timeout value, production-release signing or global lint cleanliness.

No production code, test assertion, SQLite schema or operational Room migration is
modified. Three user-owned showcases remain untracked and outside the publication.
"Clean" at handoff must mean **tracked tree clean and index empty, with exactly
those three expected untracked user files**, not a literally empty git status.

Next separately scoped phase: **3.a.4 — Raw Market Ingestion and Canonicalization**.
It is not implemented or automatically authorized by this review. No new Android
test execution, broker access or trading change is part of the closeout.

Permanent owner direction: reserve approximately **5% of effort in each future
implementation iteration** for one small, tested UX/UI improvement. This is an
effort guideline, not a measured project-completion percentage.

First planned increment, **UX-01 — VELA Status Overview**, is for beginners:
clear Spanish, truthful data/application status, simple current-mode indicator,
a safe next action and collapsed advanced information. It must not invent
readiness, confidence, predictions or profitability, and must not enable automatic
REAL trading. **Plan only: no UI code is changed in this audit.**

## Publication gate

All independent evidence checks above pass: **READY_3A3R1_TO_CLOSE**.
Only this report may be staged for one documentation-only commit:

`docs: close Android market SQLite R1 validation`

Before normal push, fetch origin and require origin/main still equals the exact
source baseline above. No merge, rebase or force push. Verify HEAD=origin/main
after publication, along with unchanged source/Room/showcase hashes and empty
staging. The actual outcome and commit are recorded in the final handoff.
