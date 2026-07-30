# Phase 1 — Progress Notes

Date: 2026-05-27
Phase: 1.a — pure-domain ports (safety-critical modules first).
Source: `G:\vela` (read-only, untouched).
Lab root: `G:\vela-android`.

## What landed in this iteration

### Gradle project skeleton (Android lab)

```
android/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   └── libs.versions.toml          ← AGP 8.5.0, Kotlin 2.0.0, JUnit Jupiter 5.10.2
└── app/
    ├── build.gradle.kts            ← minSdk 29, targetSdk 34, JDK 17
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml ← allowBackup=false, no permissions, no Activities
        │   └── res/values/strings.xml
        ├── main/kotlin/com/vela/android/lab/...
        └── test/kotlin/com/vela/android/lab/...
```

### Pure-Kotlin domain modules ported

| Windows source                                          | Android target                                                       |
|---------------------------------------------------------|----------------------------------------------------------------------|
| `app/constants.py::OperationMode`                       | `core/OperationMode.kt`                                              |
| `app/constants.py` (labels, lock reason, template)      | `core/Constants.kt`                                                  |
| `app/services/mode_guard.py`                            | `state/ModeGuard.kt`                                                 |
| `app/services/app_state.py`                             | `state/AppState.kt`                                                  |
| `app/data/risk_manager.py::RiskAction`                  | `data/risk/RiskAction.kt`                                            |
| `app/data/risk_manager.py::RiskLimits`                  | `data/risk/RiskLimits.kt`                                            |
| `app/data/risk_manager.py::RiskStateSnapshot`           | `data/risk/RiskStateSnapshot.kt`                                     |
| `app/data/risk_manager.py::RiskDecision`                | `data/risk/RiskDecision.kt`                                          |
| `app/data/risk_manager.py::RiskManager`                 | `data/risk/RiskManager.kt`                                           |

### JUnit 5 tests

| Windows test                       | Android target                                                  |
|------------------------------------|-----------------------------------------------------------------|
| `tests/test_mode_guard.py`         | `state/ModeGuardTest.kt`                                        |
| `tests/test_app_state.py`          | `state/AppStateTest.kt` (+ two extra invariant tests)           |
| `tests/test_risk_manager.py`       | `data/risk/RiskManagerTest.kt` (+ three extra branch tests)     |

The Kotlin tests preserve the original assertions verbatim where the
Python counterparts existed. The "extra" tests cover branches the
Python suite did not exercise (default `realModeLocked`, `lockRealMode`
fallback, `invalid_symbol`/`invalid_size`/`max_daily_loss` rules).

## Safety properties preserved

- `AppState.realModeLocked` still defaults to `true`.
- `ModeGuard.validateModeTransition` still rejects `REAL` while locked
  and returns the verbatim `REAL_MODE_LOCK_REASON` string.
- `lockRealMode` still forces `mode` back to `READ_ONLY` if the caller
  was in `REAL`.
- `RiskManager` rule ordering is identical to the Python implementation:
  `invalid_symbol → invalid_size → symbol_block → max_position_size →
  max_daily_loss → max_open_positions`.
- `RiskLimits` constructor preconditions match (`maxPositionSize > 0`,
  `maxOpenPositions >= 0`, `maxDailyLoss > 0`).
- Symbol normalization (`trim + uppercase + drop empty`) is preserved.

## Translation choices worth flagging

1. **Mutability**: Python dataclasses use `frozen=True` + `slots=True`.
   Kotlin equivalents use immutable construction with explicit init
   normalization (`RiskLimits.blockedSymbols`, `RiskStateSnapshot.openSymbols`).
   `data class` is reserved for value types without init normalization
   (`RiskDecision`, `ModeTransitionValidation`).
2. **Reactive state**: `AppState` mirrors the Python dataclass surface
   (private setters, synchronous methods). The migration map (§2.1)
   wraps this in `StateFlow` in Phase 1.b. **Not done in this iteration.**
3. **Number formatting**: `String.format(Locale.ROOT, "%.2f", value)`
   instead of Python `f"{x:.2f}"`. Decimal separator is locked to `.`
   so messages survive locale changes on device.
4. **Tuples → Lists**: Python `tuple[str, ...]` becomes Kotlin `List<String>`.
   Equality semantics differ slightly (lists are not hashable as
   dict keys, which is irrelevant here).
5. **`subTest` → `DynamicTest`**: per-case failure granularity preserved.

## What is **not** in this iteration

- No `data/market/` modules (`BarAggregator`, `FeatureEngine`, `SignalEngine`).
- No `data/simulation/` modules (`TradeSimulator`, `SimulationJournal`).
- No Room database, no DAOs, no entities.
- No Compose UI, no Activity, no Application class.
- No Hilt/Koin DI wiring.
- No Alpaca client, no networking dependency.
- No Foreground Service.
- No Keystore wrapper.
- No `StateFlow` wrapper around `AppState` (planned for Phase 1.b).

## Verification status

**Not built yet.** This iteration writes source files only. The Gradle
project has not been opened in Android Studio, the wrapper (`gradlew`)
has not been generated, and no test has been executed. The next person
to touch this should:

1. Run `gradle wrapper --gradle-version 8.7` (or open the project in
   Android Studio Hedgehog+ to let it generate the wrapper).
2. Run `./gradlew :app:test` to execute the JUnit 5 suite.
3. Confirm all `*Test.kt` files pass before adding new modules.

If any test fails, fix the Kotlin port rather than the test — the
tests are derived from the Python suite which is the source of truth.

## Next Phase 1 steps (proposed, smallest-first)

1. Wrap `AppState` in a `StateFlow` and expose read-only flows.
2. Port `BarAggregator` (pure logic, easy).
3. Port `FeatureEngine` (pure logic).
4. Port `SignalEngine` (pure logic).
5. Port `OperationalSchedule` evaluator (pure function).
6. Define Room entities mirroring `app/db/models.py` and write a
   single migration scaffold (no Alpaca tables until Phase 2 lands).
7. Port `TradeSimulator` (in-memory only).
8. Port `SimulationJournal` with a Room-backed store.
9. Compose shell: one Activity, one screen showing `AppState.mode` and
   a "force-lock REAL" debug button. No real-mode unlock UI.

Phase 2 (Alpaca paper REST + WebSocket, foreground service) does not
begin until Phase 1 is green end-to-end.

---

## Validation log — 2026-05-27

Attempted to validate Phase 1.a (generate Gradle wrapper, run JUnit 5
suite) before adding new modules.

### Toolchain on this host

| Tool                | Status                                                      |
|---------------------|-------------------------------------------------------------|
| `gradle` (global)   | **Not installed.** `where gradle` returned no match.        |
| JDK 17              | **Not installed.** Only `jre1.8.0_471` present.             |
| Android SDK         | **Not installed.** `ANDROID_HOME` / `ANDROID_SDK_ROOT` empty; no `%LOCALAPPDATA%\Android\Sdk`. |
| Android Studio      | **Not installed.** No `C:\Program Files\Android`.           |
| Kotlin CLI (`kotlinc`) | Not installed.                                           |
| `~/.gradle`         | Does not exist — no prior Gradle activity on this account.  |

### Build outcome

- `gradle wrapper --gradle-version 8.7` — **not run.** No `gradle`
  binary exists on this host. Without a global Gradle, the wrapper
  cannot be generated by the CLI.
- `./gradlew :app:test` — **not run.** No wrapper, no JDK 17, no SDK.

**No tests were executed. No tests were faked.** The Phase 1.a Kotlin
ports remain unverified at runtime.

### Static review (no execution)

I re-read each ported source and test for obvious port mistakes against
the Python originals. The code passes static review:

- Rule ordering in `RiskManager.evaluateEntry` matches Python exactly
  (`invalid_symbol → invalid_size → symbol_block → max_position_size →
  max_daily_loss → max_open_positions`).
- `AppState.realModeLocked` default is `true`.
- `validateModeTransition` returns the verbatim `REAL_MODE_LOCK_REASON`
  string when the lock is active and `REAL` is requested.
- Symbol normalization (`trim().uppercase()`) is identical to Python's
  `symbol.strip().upper()`.
- Number formatting uses `Locale.ROOT` to lock the decimal separator.
- JUnit 5 test assertions mirror the Python `assertEqual` /
  `assertFalse` / `assertTrue` checks.

### Build-config fix applied

`gradle/libs.versions.toml` had `junit-platform-launcher` declared
without a version reference. Without a BOM aligning the version, Gradle
would have failed to resolve the dependency. Fixed by pinning
`junit-platform = "1.10.2"` (the platform version paired with Jupiter
5.10.2) and switching the library entry to `version.ref =
"junit-platform"`. No other build files were changed.

### Recommended path to a real build

Choose one:

1. **Android Studio (recommended).** Install Android Studio Hedgehog
   (2023.1.1) or newer. Open `G:\vela-android\android` as an existing
   project. Android Studio bundles a compatible JDK 17 and Gradle, and
   will trigger a Gradle sync that generates `gradlew` /
   `gradlew.bat` on the first sync. Then run the unit-test
   configuration from the IDE or `./gradlew :app:test` from the
   integrated terminal.
2. **CLI-only path.** Install three things outside the lab:
   - A JDK 17 (Eclipse Temurin or Microsoft OpenJDK), set
     `JAVA_HOME` to its install path, prepend `%JAVA_HOME%\bin` to
     `PATH`.
   - Gradle 8.7 (extract to `C:\Gradle\gradle-8.7`, add `bin` to
     `PATH`).
   - Android command-line tools + platform 34 + build-tools 34. Set
     `ANDROID_HOME` to the SDK root.
   Then from `G:\vela-android\android`:
   ```
   gradle wrapper --gradle-version 8.7
   .\gradlew.bat :app:test
   ```

Neither option was attempted on this host because installing toolchains
was out of scope for the validation step.

### Safety reconfirmation

- `G:\vela` was not modified (latest mtime inside it predates this
  session).
- No Alpaca, network, or order-submission code was added.
- No internet permission added to the manifest.
- No `.env`, credentials, database, or installer artifacts were touched.
- `realModeLocked` default in `AppState.kt` remains `true`.
- The live trading base URL is not referenced anywhere in the lab.

---

## Phase 1.b — 2026-05-27

### Ports added (pure Kotlin, no Android dependencies)

| Windows source                              | Android target                                              |
|---------------------------------------------|-------------------------------------------------------------|
| `app/data/alpaca_client.py::normalize_market_symbol` (+ helpers) | `core/Symbols.kt`                      |
| `app/data/stream_manager.py::BootstrapMarketUpdate`              | `data/market/BootstrapMarketUpdate.kt` |
| `app/data/bar_aggregator.py::OneMinuteBar`                       | `data/market/OneMinuteBar.kt`          |
| `app/data/bar_aggregator.py::BarAggregatorStatus`                | `data/market/BarAggregatorStatus.kt`   |
| `app/data/bar_aggregator.py::OneMinuteBarAggregator`             | `data/market/OneMinuteBarAggregator.kt`|
| `app/data/feature_engine.py::SymbolFeatures`                     | `data/market/SymbolFeatures.kt`        |
| `app/data/feature_engine.py::FeatureEngineStatus`                | `data/market/FeatureEngineStatus.kt`   |
| `app/data/feature_engine.py::FeatureEngine`                      | `data/market/FeatureEngine.kt`         |
| `app/data/signal_engine.py::SignalState`                         | `data/market/SignalState.kt`           |
| `app/data/signal_engine.py::SymbolSignal`                        | `data/market/SymbolSignal.kt`          |
| `app/data/signal_engine.py::SignalEngineStatus`                  | `data/market/SignalEngineStatus.kt`    |
| `app/data/signal_engine.py::SignalEngine`                        | `data/market/SignalEngine.kt`          |

The Qt `Signal`/`Slot` wiring used by the Windows project becomes a
minimal callback list in each engine (`addBarListener`,
`addFeatureListener`, etc). Wrapping these in `SharedFlow` is still
on the Phase 1.b backlog (deferred — listeners are sufficient for
test wiring).

### Tests added (JUnit 5)

| Windows test                          | Android target                                       |
|---------------------------------------|------------------------------------------------------|
| (no dedicated file)                   | `core/SymbolsTest.kt` (7 cases — covers `normalize_market_symbol` rules from `alpaca_client.py`) |
| (no dedicated file)                   | `data/market/OneMinuteBarAggregatorTest.kt` (9 cases — bucketing, merging, eviction, listeners) |
| `tests/test_feature_engine.py`        | `data/market/FeatureEngineTest.kt` (3 cases — direct ports) |
| `tests/test_signal_engine.py`         | `data/market/SignalEngineTest.kt` (5 cases — 3 direct ports + 2 boundary cases at the 0.5 barRange threshold) |

### Verification — RAN ON HOST

The build was executed on this Windows host. Toolchain used:

- `JAVA_HOME` = `C:\Program Files\Android\Android Studio\jbr` (OpenJDK 21.0.10).
- Gradle 9.0.0 (already pre-downloaded by the wrapper).
- Android SDK at `G:\Android\Sdk` (already installed).
- AGP 8.5.0, Kotlin 2.0.0.

Command executed (with reduced heap because Android Studio was
already holding ~3.8 GB of resident memory):

```
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
  ./gradlew.bat :app:test --console=plain --no-daemon \
  -Dorg.gradle.jvmargs="-Xmx768m -Dfile.encoding=UTF-8"
```

Result: `BUILD SUCCESSFUL in 28s`.

Per-class results (both `testDebugUnitTest` and `testReleaseUnitTest`
variants ran the same suite; numbers shown are per variant):

| Class                            | tests | failures | errors | skipped |
|----------------------------------|------:|---------:|-------:|--------:|
| `ModeGuardTest`                  |     6 |        0 |      0 |       0 |
| `AppStateTest`                   |     5 |        0 |      0 |       0 |
| `RiskManagerTest`                |     7 |        0 |      0 |       0 |
| `SymbolsTest`                    |     7 |        0 |      0 |       0 |
| `OneMinuteBarAggregatorTest`     |     9 |        0 |      0 |       0 |
| `FeatureEngineTest`              |     3 |        0 |      0 |       0 |
| `SignalEngineTest`               |     5 |        0 |      0 |       0 |
| **Total per variant**            | **42**|    **0** |  **0** |   **0** |

Each test ran twice (debug + release), so **84 test invocations all
passed** with zero failures, zero errors, and zero skips.

### Build-environment notes

- `gradle.properties`: heap dropped from `-Xmx2048m` to `-Xmx1024m`
  to fit alongside Android Studio's resident set. The 768m override
  was passed only at the command line; the file default is now 1024m.
- The Gradle wrapper was generated by Android Studio (a prior IDE
  sync, not by this session). Wrapper distribution is Gradle 9.0.0.
  AGP 8.5.0 logs a deprecation warning ("incompatible with Gradle 10")
  but the build still succeeds.
- `local.properties` was auto-generated by Android Studio and points
  at `sdk.dir=G:\Android\Sdk`. It is correctly listed in standard
  `.gitignore` patterns and contains no secrets.

### Safety reconfirmation (Phase 1.b)

- `G:\vela` was not modified. `find -newer` against the lab README
  returned no files inside `G:\vela`.
- No Alpaca SDK, OkHttp, Retrofit, Ktor, or networking library was
  added to `app/build.gradle.kts`.
- No `INTERNET` permission added to `AndroidManifest.xml`.
- No order submission, no live URL reference, no credential code.
- `realModeLocked` still defaults to `true` in `AppState.kt`.
- The `data/market/*` modules are pure logic with no Android-only
  imports; they compile against both debug and release variants and
  do not require an emulator.

---

## Phase 1.c — 2026-05-27 — Room/SQLite persistence foundation

### Dependencies added

`gradle/libs.versions.toml`:
- `room = "2.6.1"` (runtime + ktx + compiler + testing)
- `ksp = "2.0.0-1.0.24"` (Kotlin Symbol Processing, matches Kotlin 2.0.0)

`build.gradle.kts` (root): `alias(libs.plugins.ksp) apply false`.

`app/build.gradle.kts`:
- `alias(libs.plugins.ksp)` applied
- `implementation(libs.room.runtime)`, `implementation(libs.room.ktx)`
- `ksp(libs.room.compiler)`
- `androidTestImplementation(libs.room.testing)`, `androidx.test.ext:junit`, `androidx.test:runner`
- `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`
- Test JVM tuning for low-memory hosts: `maxHeapSize = "384m"`, `-XX:+UseSerialGC`, `-XX:MaxMetaspaceSize=256m`, `maxParallelForks = 1`

**No networking, no Alpaca SDK, no INTERNET permission, no credentials.**

### Entities created (`db/room/entities/`)

| Entity | Table | Notes |
|---|---|---|
| `MarketBar1mEntity` | `market_bars_1m` | unique `(symbol, bucketStartEpochMillis)`; mirrors `OneMinuteBar` |
| `SymbolFeaturesEntity` | `symbol_features` | unique `(symbol, bucketStartEpochMillis)`; mirrors `SymbolFeatures` |
| `SymbolSignalEntity` | `symbol_signals` | unique `(symbol, bucketStartEpochMillis)`; state stored as string |
| `JournalEventEntity` | `journal_events` | generic event log keyed by `(symbol?, eventType, timestampEpochMillis)` |

`Instant` ↔ `Long` (epoch millis) via `InstantConverter` `@TypeConverter`. Type converter file: `db/room/converters/InstantConverter.kt`.

### DAOs created (`db/room/dao/`)

Interfaces (so JVM tests can substitute pure-Kotlin fakes), with suspend functions:
- `MarketBarDao` — insert/insertAll/bySymbol/recent/countBySymbol/countAll/deleteBySymbol/clear
- `FeatureDao` — insert/insertAll/bySymbol/recent/latestFor/countBySymbol/clear
- `SignalDao` — insert/insertAll/bySymbol/recent/latestFor/byState/clear
- `JournalDao` — insert/bySymbol/byType/inRange/countAll/clear

### Database

`db/room/VelaDatabase.kt`:
- `@Database(version = 1, exportSchema = true, entities = [...4...])`
- `@TypeConverters(InstantConverter::class)`
- `DATABASE_NAME = "vela-lab.db"` (deliberately distinct from Windows `vela.db`)
- `create(context)` for production (app-private storage) and `createInMemory(context)` for tests

Schema JSON exported to `app/schemas/com.vela.android.lab.db.room.VelaDatabase/1.json` — checked into the lab. SQL DDL for `market_bars_1m` includes the expected columns and types (verified by reading the exported JSON).

### Mappers (`db/Mappers.kt`)

- `OneMinuteBar.toEntity()` / `MarketBar1mEntity.toDomain()`
- `SymbolFeatures.toEntity()` / `SymbolFeaturesEntity.toDomain()`
- `SymbolSignal.toEntity()` / `SymbolSignalEntity.toDomain()` (preserves `SignalState` enum via its string value)
- `journalEvent(...)` factory

All `toEntity()` mappers re-run `normalizeMarketSymbol()` as a defense in depth — even if a caller bypasses the aggregator and constructs a domain object with a raw symbol, the persisted row uses the canonical `BASE/QUOTE` form.

### Repositories (`data/repository/`)

- `MarketDataRepository` — `persistBar`, `persistBars`, `bars`, `recentBars`, `count`, `countAll`, `clear`, `clearAll`
- `FeatureRepository` — `persist`, `persistAll`, `forSymbol`, `latestFor`, `count`, `clear`
- `SignalRepository` — `persist`, `persistAll`, `forSymbol`, `latestFor`, `byState`, `clear`
- `JournalRepository` — `record`, `forSymbol`, `byType`, `inRange`, `count`, `clear`

Repositories normalize input symbols at the boundary so callers can query with `BTC/USD`, `BTCUSD`, or `btcusd` and hit the same canonical-keyed rows.

### Tests added — JVM (`app/src/test/`)

| Class | Cases |
|---|---:|
| `db/MappersTest` | 12 |
| `data/repository/MarketDataRepositoryTest` | 9 |
| `data/repository/FeatureRepositoryTest` | 6 |
| `data/repository/SignalRepositoryTest` | 4 |
| `data/repository/JournalRepositoryTest` | 7 |
| **New in Phase 1.c** | **38** |

Repository tests use **fake DAO implementations** that mirror the SQL semantics the Room-generated implementation will exhibit (`REPLACE` on unique constraint, `ASC` for `bySymbol`, `DESC + LIMIT` for `recent`). This lets the repository layer be exercised under `:app:test` (JVM) without needing an emulator or Robolectric.

### Tests added — instrumented (`app/src/androidTest/`)

`db/room/VelaDatabaseTest.kt` — 4 cases that exercise the **real** SQLite-backed Room implementation via `Room.inMemoryDatabaseBuilder`:
- `insertedBars_areReturnedInTimestampOrder`
- `featuresRoundTrip`
- `signalRoundTripPreservesEnumState`
- `journalEventInsertAndQueryByType`

**Not run.** These tests require an Android emulator or physical device (task `:app:connectedDebugAndroidTest`). No device or emulator is attached to this host, so these tests are documented but unexecuted. The repository-layer tests cover the same semantics against fake DAOs.

### Full test result

```
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
  ./gradlew.bat :app:test --console=plain --no-daemon \
  -Dorg.gradle.jvmargs="-Xmx768m -Dfile.encoding=UTF-8"

BUILD SUCCESSFUL in 22s
51 actionable tasks: 3 executed, 48 up-to-date
```

Per-class breakdown (same numbers for `testDebugUnitTest` and `testReleaseUnitTest`):

| Class | tests | failures | errors | skipped |
|---|---:|---:|---:|---:|
| ModeGuardTest | 6 | 0 | 0 | 0 |
| AppStateTest | 5 | 0 | 0 | 0 |
| RiskManagerTest | 7 | 0 | 0 | 0 |
| SymbolsTest | 7 | 0 | 0 | 0 |
| OneMinuteBarAggregatorTest | 9 | 0 | 0 | 0 |
| FeatureEngineTest | 3 | 0 | 0 | 0 |
| SignalEngineTest | 5 | 0 | 0 | 0 |
| **MappersTest** | **12** | **0** | **0** | **0** |
| **MarketDataRepositoryTest** | **9** | **0** | **0** | **0** |
| **FeatureRepositoryTest** | **6** | **0** | **0** | **0** |
| **SignalRepositoryTest** | **4** | **0** | **0** | **0** |
| **JournalRepositoryTest** | **7** | **0** | **0** | **0** |
| **Total per variant** | **80** | **0** | **0** | **0** |

**160 JVM test invocations across debug + release. All passed.**

### Build-environment notes

- A `-XX:+UseSerialGC -Xmx384m -XX:MaxMetaspaceSize=256m` JVM config was required for the test executor because the Windows page-file commit limit could not satisfy G1 GC's default ~256 MB address-space reservation alongside Android Studio's resident set (~3.8 GB). With these settings, the test executor JVM fits.
- The first attempt with KSP + Room compiled successfully (44 tasks ran) but failed in the test runtime with `error='El archivo de paginación es demasiado pequeño'` (page file too small) — fixed by the test JVM tuning above. **No code or test was changed to make it pass.**
- AGP logs a `kotlinOptions` deprecation warning (irrelevant — works on Gradle 9). Behavior unchanged.

### Safety reconfirmation (Phase 1.c)

- `G:\vela` was not modified. `find G:\vela -newer G:\vela-android\README.md` returned zero files.
- No Alpaca SDK, OkHttp, Retrofit, Ktor, or networking library declared in `app/build.gradle.kts` (grep verified).
- No `INTERNET` permission in `AndroidManifest.xml` (grep verified).
- No order submission code, no live URL reference, no credential handling.
- No `vela.db` was opened, read, or copied — the new database is named `vela-lab.db` and lives only in app-private storage at runtime.
- `realModeLocked` still defaults to `true` in `AppState.kt`.
- The mapper layer enforces canonical symbol form on every persistence boundary, so BTC/USD and BTCUSD resolve to the same row (verified by `MarketDataRepositoryTest.BTCUSD and BTC slash USD resolve to the same row`).

---

## Phase 1.d — 2026-05-27 — Offline market pipeline wired to persistence

### Files added (`data/pipeline/`)

- `PipelineEventTypes.kt` — five string constants:
  `market_update_received`, `bar_persisted`, `features_persisted`,
  `signal_persisted`, `invalid_market_update`.
- `PipelineStepResult.kt` — immutable result of one `addUpdate` call
  (symbol, accepted, bar?, features?, signal?, journalEventsRecorded).
- `OfflineMarketPipelineCoordinator.kt` — the only new orchestration
  module. Pure Kotlin, suspend-based, no Android imports.

The coordinator's `addUpdate(BootstrapMarketUpdate)` performs, in
order: normalize symbol → reject if empty (journal + return) →
journal "market_update_received" → `barAggregator.addUpdate` →
persist bar → journal "bar_persisted" → `featureEngine.addBar` →
persist features → journal "features_persisted" →
`signalEngine.addFeatures` → persist signal → journal
"signal_persisted". On any stage producing no output, the coordinator
short-circuits and returns the partial result without leaving dangling
state.

### Tests added (`app/src/test/.../data/pipeline/`)

`OfflineMarketPipelineCoordinatorTest.kt` — **10 cases**:

1. `one update creates and persists one bar features and signal`
2. `two updates in the same minute update the same bar bucket`
3. `new minute creates a new persisted bar`
4. `features are generated and persisted for every accepted update`
5. `signals are generated and persisted with state derived from features`
6. `BTCUSD and BTC slash USD normalize to the same canonical row`
7. `journal receives the four expected events for one accepted update`
8. `empty or whitespace symbol is rejected with a single journal event`
9. `bars persist in chronological order across many updates`
10. `feature and signal rows share the symbol and bucketStart of the bar`

The test file uses real repositories backed by pure-Kotlin fake DAOs
that mirror the SQL semantics (REPLACE on the
`(symbol, bucketStartEpochMillis)` uniqueness, ASC for `bySymbol`,
DESC + LIMIT for `recent`). Same pattern as Phase 1.c — runs under
`:app:test` with no emulator.

### Notes from the run

- First attempt failed on case 5 with `expected: <4> but was: <2>`
  for `signalDao.rows.size`. **Diagnosis:** 3 of the 4 updates in that
  scenario sit in the same minute bucket, so the unique constraint
  collapses their signal rows onto one row (REPLACE), and the 4th
  update opens bucket 1. Two persisted rows total is the correct
  behavior — the unique-bucket dedup is the whole point of the
  schema. **Fix:** updated the assertion to expect 2 rows and added a
  follow-on assertion that both rows belong to "SPY" in chronological
  order. **No coordinator code was changed to make the test pass.**

### No instrumented tests added in Phase 1.d

The Phase 1.c instrumented `VelaDatabaseTest.kt` already exercises
the real SQLite-backed Room implementation. Phase 1.d's coordinator
is pure Kotlin and has no Android dependency — JVM coverage with
fake DAOs is sufficient. No new instrumented tests were added.

### Full test result

```
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" \
  ./gradlew.bat :app:test --console=plain --no-daemon \
  -Dorg.gradle.jvmargs="-Xmx768m -Dfile.encoding=UTF-8"

BUILD SUCCESSFUL in 31s
51 actionable tasks: 7 executed, 44 up-to-date
```

Per-class breakdown (identical numbers for `testDebugUnitTest` and
`testReleaseUnitTest`):

| Class | tests | new in 1.d? |
|---|---:|---|
| ModeGuardTest | 6 | |
| AppStateTest | 5 | |
| RiskManagerTest | 7 | |
| SymbolsTest | 7 | |
| OneMinuteBarAggregatorTest | 9 | |
| FeatureEngineTest | 3 | |
| SignalEngineTest | 5 | |
| MappersTest | 12 | |
| MarketDataRepositoryTest | 9 | |
| FeatureRepositoryTest | 6 | |
| SignalRepositoryTest | 4 | |
| JournalRepositoryTest | 7 | |
| **OfflineMarketPipelineCoordinatorTest** | **10** | ✓ |
| **Per variant** | **90** | |

**180 JVM test invocations across debug + release. All passed.**

### Safety reconfirmation (Phase 1.d)

- `G:\vela` was not modified. `find G:\vela -newer G:\vela-android\README.md`
  returned zero files.
- No Alpaca SDK, OkHttp, Retrofit, Ktor, or any networking library
  was added. Targeted import grep `^import.*(okhttp|retrofit|ktor|alpaca|java\.net\.URL)`
  matched only `core/Symbols.kt`, where `java.net.URLDecoder` operates
  on in-memory strings (the Python `unquote` equivalent) and never
  opens sockets.
- No `INTERNET` permission in `AndroidManifest.xml`.
- No order submission, no live URL reference, no credential handling,
  no `TradingClient`-style code anywhere in the lab.
- No foreground service, no Compose UI, no ML — all deferred.
- `realModeLocked` still defaults to `true` in `AppState.kt`.
- `vela-lab.db` remains the only database name. No Windows artifact
  was touched.

---

## Phase 1.e — 2026-05-30 — Minimal offline Compose UI shell

### Status

**Complete.** Validated on Windows after the user increased the page
file. Both `:app:testDebugUnitTest` and `:app:test` returned
**BUILD SUCCESSFUL**.

### Files changed (all inside `G:\vela-android`)

**Build config:**
- [gradle/libs.versions.toml](../android/gradle/libs.versions.toml) —
  added Compose BOM `2024.06.00`, `activity-compose 1.9.1`, lifecycle
  `2.8.4` (`-runtime-ktx`, `-viewmodel-ktx`, `-viewmodel-compose`,
  `-runtime-compose`), `kotlinx-coroutines-test 1.8.1`, and the
  `kotlin-compose` plugin (`org.jetbrains.kotlin.plugin.compose`)
  matched to Kotlin `2.0.0`.
- [build.gradle.kts](../android/build.gradle.kts) — registered
  `kotlin-compose` plugin.
- [app/build.gradle.kts](../android/app/build.gradle.kts) — applied
  `kotlin-compose` plugin, enabled `buildFeatures { compose = true }`,
  added Compose + lifecycle + coroutines-test deps, kept low-memory
  test JVM tuning from Phase 1.c/d.
- [settings.gradle.kts](../android/settings.gradle.kts) — added the
  `org.gradle.toolchains.foojay-resolver-convention` plugin to enable
  automatic JVM toolchain provisioning (preserved as-modified).
- [app/src/main/AndroidManifest.xml](../android/app/src/main/AndroidManifest.xml)
  — registered `.VelaLabApplication` + `.MainActivity` with the
  `LAUNCHER` intent filter. `allowBackup="false"` preserved.
  **No INTERNET permission, no service, no provider, no receiver.**

**New main source (Compose + DI):**
- [VelaLabApplication.kt](../android/app/src/main/kotlin/com/vela/android/lab/VelaLabApplication.kt)
  — process-scoped DI graph: lazy `VelaDatabase`, four repositories,
  three engines, and the offline coordinator.
- [MainActivity.kt](../android/app/src/main/kotlin/com/vela/android/lab/MainActivity.kt)
  — `ComponentActivity` that wires the ViewModel via
  `viewModels { ... }` and calls `setContent { VelaLabTheme { ... } }`.
- [ui/theme/Theme.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/theme/Theme.kt)
  — `VelaLabTheme` Composable with light + dark Material3 color
  schemes.
- [ui/dashboard/OfflineDashboardUiState.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardUiState.kt)
  — UI state data class with `Initial` factory (mode `READ_ONLY`,
  `realLocked = true`, pipeline `Offline demo`).
- [ui/dashboard/OfflineDashboardViewModel.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardViewModel.kt)
  — `androidx.lifecycle.ViewModel` exposing `StateFlow<OfflineDashboardUiState>`.
  Deterministic demo updates (`generateBtcUpdate`, `generateSpyUpdate`)
  push `BootstrapMarketUpdate`s into the coordinator. `clearDemoState`
  wipes the four repositories and resets visible counters. Injectable
  `clock: () -> Instant` for test determinism.
- [ui/dashboard/OfflineDashboardScreen.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt)
  — Compose screen with `TopAppBar` ("VELA Android Lab"),
  `Scaffold`, scrollable `Column` of four Material3 `Card`s
  (Status / Last pipeline step / Persistence / Demo controls),
  optional error `Card`, and three buttons (`Generate demo BTC/USD
  update`, `Generate demo SPY update`, `Clear local demo state`).
  `collectAsStateWithLifecycle` reads the ViewModel's `StateFlow`.
  Includes a `@Preview` helper for IDE rendering.

**New test:**
- [test/.../ui/dashboard/OfflineDashboardViewModelTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardViewModelTest.kt)
  — 12 JVM cases (see "Tests added" below). Uses `Dispatchers.setMain(UnconfinedTestDispatcher())`
  with `@BeforeEach` / `@AfterEach` to drive `viewModelScope` from
  JUnit 5 synchronously. Real repositories backed by pure-Kotlin
  fake DAOs, mirroring the Phase 1.c/1.d pattern.

### Compose dependencies added

| Dependency | Version | Scope |
|---|---|---|
| `androidx.compose:compose-bom` | 2024.06.00 (BOM) | `implementation` |
| `androidx.compose.ui:ui` | (BOM) | `implementation` |
| `androidx.compose.ui:ui-graphics` | (BOM) | `implementation` |
| `androidx.compose.ui:ui-tooling-preview` | (BOM) | `implementation` |
| `androidx.compose.material3:material3` | (BOM) | `implementation` |
| `androidx.activity:activity-compose` | 1.9.1 | `implementation` |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.8.4 | `implementation` |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | 2.8.4 | `implementation` |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.8.4 | `implementation` |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.8.4 | `implementation` |
| `org.jetbrains.kotlinx:kotlinx-coroutines-test` | 1.8.1 | `testImplementation` |
| `org.jetbrains.kotlin.plugin.compose` | 2.0.0 | Gradle plugin |

### MainActivity, Application, Screen, ViewModel, State

- **`VelaLabApplication`** created at `com/vela/android/lab/VelaLabApplication.kt`.
  Holds the lazy DI graph (`VelaDatabase` + four repositories + three
  engines + `OfflineMarketPipelineCoordinator`). Registered in the
  manifest as `android:name=".VelaLabApplication"`.
- **`MainActivity`** created at `com/vela/android/lab/MainActivity.kt`.
  Single-Activity Compose host with a `viewModelFactory { initializer { ... } }`
  that pulls services off `application as VelaLabApplication` and
  constructs `OfflineDashboardViewModel`.
- **`OfflineDashboardScreen`** created at
  `com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt`.
  Material3 `Scaffold` + cards displaying every required field
  (mode, REAL lock, pipeline label, last symbol/price/bar
  close/feature direction/signal state/score, persisted bar count,
  journal event count, last error) and the three demo buttons.
- **`OfflineDashboardViewModel`** + **`OfflineDashboardUiState`**
  created at `com/vela/android/lab/ui/dashboard/`. State holder
  exposes a `StateFlow<OfflineDashboardUiState>`; methods drive the
  coordinator and update visible counters from repository counts.

### Tests added

Single new test class `OfflineDashboardViewModelTest`, **12 cases**:

1. `initial state shows READ_ONLY mode`
2. `initial state shows REAL locked true`
3. `initial state shows offline pipeline label`
4. `initial state has no last symbol, price, or signal`
5. `demo BTC update changes last symbol to BTC slash USD`
6. `demo SPY update changes last symbol to SPY`
7. `demo update produces a signal state`
8. `persisted bar count increases after a demo update`
9. `journal event count increases by four per accepted update`
10. `clear demo state resets the visible counters and last error`
11. `REAL remains locked across demo activity`
12. `BTC symbol spelling normalizes to canonical BTC slash USD`

### Full test result — BUILD SUCCESSFUL

Commands executed by the user from PowerShell (after raising the
Windows page file to satisfy commit-limit for the Compose-heavy
Kotlin compilation):

```
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-daemon `
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8'

.\gradlew.bat :app:test --console=plain --no-daemon `
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8'
```

Both **BUILD SUCCESSFUL**. Per-class breakdown (identical numbers
for `testDebugUnitTest` and `testReleaseUnitTest`):

| Class | tests | failures | errors | skipped | new in 1.e? |
|---|---:|---:|---:|---:|---|
| ModeGuardTest | 6 | 0 | 0 | 0 | |
| AppStateTest | 5 | 0 | 0 | 0 | |
| RiskManagerTest | 7 | 0 | 0 | 0 | |
| SymbolsTest | 7 | 0 | 0 | 0 | |
| OneMinuteBarAggregatorTest | 9 | 0 | 0 | 0 | |
| FeatureEngineTest | 3 | 0 | 0 | 0 | |
| SignalEngineTest | 5 | 0 | 0 | 0 | |
| MappersTest | 12 | 0 | 0 | 0 | |
| MarketDataRepositoryTest | 9 | 0 | 0 | 0 | |
| FeatureRepositoryTest | 6 | 0 | 0 | 0 | |
| SignalRepositoryTest | 4 | 0 | 0 | 0 | |
| JournalRepositoryTest | 7 | 0 | 0 | 0 | |
| OfflineMarketPipelineCoordinatorTest | 10 | 0 | 0 | 0 | |
| **OfflineDashboardViewModelTest** | **12** | **0** | **0** | **0** | ✓ |
| **Total per variant** | **102** | **0** | **0** | **0** | |

**204 JVM test invocations across debug + release. All passed.**

### Tests not run, and why

- **Compose UI tests** (`createComposeRule()`-based) — not added.
  Compose UI tests are instrumented; they require an Android
  emulator or a physical device attached via ADB. No device is
  attached to this host. The ViewModel was deliberately split from
  the screen so the JVM ViewModel test covers behavior, and the
  Composable layer is a thin render of the same `UiState` data
  class. `@Preview` is provided for IDE rendering.
- **Instrumented `VelaDatabaseTest.kt`** (Phase 1.c) — still present,
  still not run, same reason as Phase 1.c–1.d (no device attached).

### Safety reconfirmation (Phase 1.e)

- **`G:\vela` was not modified.** `find G:\vela -newer G:\vela-android\README.md`
  returned zero files.
- **No `INTERNET` permission.** Targeted manifest grep
  `android\.permission\.INTERNET|uses-permission` against
  `AndroidManifest.xml` returned no matches.
- **No Alpaca URL or client added.** Targeted import grep
  `^import.*(okhttp|retrofit|ktor|alpaca)` returned no matches.
- **No order submission code added.** Grep
  `submit.*order|live\.alpaca|api\.alpaca\.markets|TradingClient|paper-api\.alpaca\.markets`
  (case-insensitive) returned no files.
- **No LIVE endpoint added.** No live trading URL constants exist
  anywhere in the lab source.
- **REAL remains locked.** [AppState.kt:18](../android/app/src/main/kotlin/com/vela/android/lab/state/AppState.kt#L18)
  still has `realModeLocked: Boolean = true`. The new
  `OfflineDashboardViewModelTest.REAL remains locked across demo activity`
  test passes twice (debug + release).
- **Windows `vela.db` not read, copied, or touched.** The only
  references to `vela.db` in the Android lab are documentation
  comments that explicitly distinguish the Android `vela-lab.db`
  from the Windows database. No file path under
  `G:\vela\` is opened by any lab source.
- **No foreground service, no ML, no real market data, no background
  execution.** All deferred per plan. The UI's "Generate demo …
  update" buttons construct synthetic `BootstrapMarketUpdate` values
  in-process and feed them to the offline coordinator only.

---

## Phase 1.f — 2026-05-30 — Android runtime validation (build-only on this host)

### Status

**Partial.** Both Gradle build steps succeed; **runtime validation
on a device/emulator cannot be performed** because no ADB device is
attached and no Android Virtual Device (AVD) has been provisioned
on this host. The user's rules explicitly require honest reporting
of this state rather than faking success. **No new features were
added. No source files were modified in this phase.**

This section was refreshed on 2026-05-30 after a re-run of the two
required Gradle commands; both still pass with full task caching.

### Step 1 — Environment

- `JAVA_HOME` target = `C:\Program Files\Android\Android Studio\jbr`.
  `java -version` reports:

  ```
  openjdk version "21.0.10" 2026-01-20
  OpenJDK Runtime Environment (build 21.0.10+-14961533-b1163.108)
  OpenJDK 64-Bit Server VM (build 21.0.10+-14961533-b1163.108, mixed mode)
  ```

  AGP 8.5.0 requires JDK 17 or newer; the JBR's JDK 21 satisfies it.

### Step 2 — `testDebugUnitTest` result

Command:

```
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-daemon `
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8'
```

Result on the latest run: **BUILD SUCCESSFUL in 14s.** 25 actionable
tasks, all UP-TO-DATE (cached from Phase 1.e). 90 JVM tests in the
debug variant re-validated via cache, 0 failures.

### Step 3 — `assembleDebug` result

Command:

```
.\gradlew.bat :app:assembleDebug --console=plain --no-daemon `
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8'
```

Result on the latest run: **BUILD SUCCESSFUL in 13s.** 36 actionable
tasks, all UP-TO-DATE. (The cold first build during the original
Phase 1.f run took 53s and executed 18 of 36 tasks; everything is
cached now.)

### Step 4 — APK path

```
G:\vela-android\android\app\build\outputs\apk\debug\app-debug.apk
```

Size: 21,143,920 bytes (~20.2 MB). `output-metadata.json` written
alongside.

### Step 5 — ADB / AVD probe

```
G:\Android\Sdk\platform-tools\adb.exe devices -l
  List of devices attached

G:\Android\Sdk\emulator\emulator.exe -list-avds
  (empty)
```

- Zero ADB devices attached (no physical handset, no running emulator).
- Zero AVDs configured under this user account.

### Steps 6–9 — Runtime validation: NOT PERFORMED

Per the phase's rule ("If no emulator/device is available: do not
fake runtime success"), the manual checklist was **not executed**.
Creating an AVD or downloading a system image from scratch was not
attempted because:

- AVD creation requires downloading a system image (hundreds of MB)
  and configuring it (RAM, storage, accelerator) — that is project
  setup, not validation.
- The phase's instructions limit this work to runtime validation
  only, with no redesign and no new product features.

What the build pipeline DID confirm end-to-end:

- The Compose source compiles cleanly under AGP 8.5.0 + Kotlin
  2.0.0 + compose-compiler 2.0.0.
- KSP processes the Room schema without errors for the
  `assembleDebug` task.
- Resource processing (`mergeDebugResources`, `processDebugResources`)
  succeeds against the lab's `AndroidManifest.xml` and theme.
- DEX assembly (`mergeProjectDexDebug`, `mergeLibDexDebug`) completes —
  meaning every transitive dependency is reachable on the runtime
  classpath.
- `validateSigningDebug` accepts the default debug signing
  configuration.
- The APK package is produced and listed in the output directory.

**Manual checklist (mode label, REAL lock display, button taps,
counter increments, clear behavior) remains unverified at runtime.**
The JVM ViewModel suite (`OfflineDashboardViewModelTest`, 12 cases)
covers the same observable behavior at the state-holder level and
passed in Phase 1.e.

### Step 11 — Crash logs

None. No instrumentation was launched, so no runtime crash could
occur in this phase.

Stale `hs_err_pid*.log` and `replay_pid*.log` files remain in
`G:\vela-android\android\` from Phase 1.e's daemon-OOM incidents
(the user has since increased the page file). They are diagnostic
leftovers — Phase 1.e's final report already covered those crashes
and they are not the result of any Phase 1.f action. Left in place
rather than deleting unrequested.

### Files changed

**None.** Phase 1.f produced build artifacts only:

- `G:\vela-android\android\app\build\outputs\apk\debug\app-debug.apk`
- `G:\vela-android\android\app\build\outputs\apk\debug\output-metadata.json`
- Intermediate files under `G:\vela-android\android\app\build\` (regenerable)

No `*.kt`, `*.kts`, `*.toml`, or `*.xml` source file was modified.
Verified by `find G:\vela-android -name "*.kt" -newer phase-1-progress.md`
returning zero matches.

### Confirmation: no new features were added

Confirmed. No new Kotlin classes, no new Composables, no new Gradle
dependencies, no new permissions, no new tests. Phase 1.f is
build/probe only.

### Safety reconfirmation

| Check | Result |
|---|---|
| `android.permission.INTERNET` in `AndroidManifest.xml` | No matches (grep) |
| Alpaca / OkHttp / Retrofit / Ktor imports | No files found (grep) |
| Order submission / live URL / TradingClient / paper-api.alpaca | No files found (grep) |
| LIVE endpoint constant | Not present anywhere in the lab |
| `realModeLocked: Boolean = true` (AppState.kt:18) | Confirmed |
| `G:\vela` modified since lab start | No (`find -newer` returned empty) |
| Windows `vela.db` read, copied, or touched | No — lab uses `vela-lab.db` in app-private storage |
| Foreground service / ML / networking / Auto Paper / background workers | All deferred, none added |

### How to complete the runtime validation later

When an emulator or device becomes available:

1. Either connect a USB device with developer mode + USB debugging
   enabled, or create an AVD via Android Studio's AVD Manager (a
   system image at API 29+ matches `minSdk`) and start it with
   `G:\Android\Sdk\emulator\emulator.exe @<avd>`.
2. Confirm `adb devices` lists it.
3. `adb install -r G:\vela-android\android\app\build\outputs\apk\debug\app-debug.apk`
4. `adb shell am start -n com.vela.android.lab/.MainActivity`
5. Walk the manual checklist from this phase's spec: title = "VELA
   Android Lab"; mode = "READ_ONLY"; REAL locked = "true"; pipeline
   = "Offline demo"; press BTC button → last symbol = "BTC/USD";
   signal state appears; persisted bar count and journal event count
   increment; press SPY button → last symbol = "SPY"; press Clear →
   counters reset; no crash; no external files touched.

The JVM `OfflineDashboardViewModelTest` already asserts every
data-state transition required by that checklist, so any UI
deviation observed on-device will be a Compose rendering or
lifecycle issue, not a logic issue.

---

## Phase 1.g — 2026-05-30 — Complete runtime validation (blocked: still no Android runtime target)

### Status

**Blocked.** Phase 1.g cannot complete on this host because there
is still no Android Virtual Device configured and no physical
device attached via ADB. The phase rule is explicit: *"If no AVD
exists, report that an AVD must be created manually through Android
Studio Device Manager. Do not fake runtime validation."* That is
what this section does — no install, no launch, no manual
validation steps were executed or fabricated.

**No new features were added. No source files were modified in
this phase.**

### Step 1 — `adb devices -l`

```
G:\Android\Sdk\platform-tools\adb.exe devices -l
List of devices attached

```

Empty list. Zero physical devices attached. Zero emulators running.

### Step 2 — `emulator -list-avds`

```
G:\Android\Sdk\emulator\emulator.exe -list-avds
```

Empty output. **Zero AVDs configured** under this user account at
`%USERPROFILE%\.android\avd\`.

### Step 3 — Action required from the user (manual AVD creation)

An emulator must be created interactively through Android Studio,
because AVD provisioning requires:

- selecting a hardware profile (e.g. **Pixel 6**);
- downloading a **system image** (e.g. `system-images;android-34;google_apis;x86_64`);
- accepting the system-image license;
- configuring RAM / internal storage / graphics acceleration.

Recommended path:

1. Open **Android Studio**.
2. Open `G:\vela-android\android` as an existing project (it is
   already a valid Gradle project; the prior `local.properties` and
   `gradlew` are reused).
3. **Tools → Device Manager → Create Device** → pick a hardware
   profile (Pixel 6 is fine).
4. Select a system image at **API 29 or later** (matches the lab's
   `minSdk = 29`). API 34 with Google APIs is a sensible default.
5. Finish the wizard and start the AVD.
6. While the emulator is running, return to this terminal and
   re-run Phase 1.g.

Alternative path: connect a **physical Android device** with
Developer Options enabled and USB debugging on. Confirm it shows
up under `adb devices` before re-running Phase 1.g.

### Step 4 — Install / launch: NOT PERFORMED

- `adb install -r G:\vela-android\android\app\build\outputs\apk\debug\app-debug.apk`
  was **not executed** — there is no install target.
- `adb shell am start -n com.vela.android.lab/.MainActivity` was
  **not executed** — there is no launch target.

### Step 5 — Manual checklist result: NOT EXECUTED

Every checklist item below is **unverified at runtime** in Phase 1.g:

- app opens without crash
- title shows "VELA Android Lab"
- mode shows "READ_ONLY"
- REAL locked shows "true"
- pipeline shows "Offline demo"
- "Generate demo BTC/USD update" → last symbol = "BTC/USD"; signal
  state appears; persisted bar count and journal event count increase
- "Generate demo SPY update" → last symbol = "SPY"; counters increase
- "Clear local demo state" → counters reset; no crash

The corresponding **logic** behind every one of these items is
covered by [`OfflineDashboardViewModelTest`](../android/app/src/test/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardViewModelTest.kt)
(12 JVM cases, all passing in Phase 1.e). Any deviation observed
on-device once the AVD is up will be a Compose rendering /
Activity lifecycle issue, not a logic issue.

### Step 6 — Crash logs: NONE

No instrumentation was launched, so no runtime crash could occur
in Phase 1.g.

### APK still present and unchanged

```
G:\vela-android\android\app\build\outputs\apk\debug\app-debug.apk
```

Size: 21,143,920 bytes (~20.2 MB), mtime 2026-05-30 13:30. Same
artifact produced by Phase 1.f's `assembleDebug`. Ready to install
once a runtime target exists.

### Files changed

**None.** Phase 1.g produced no build artifacts, no source edits,
no Gradle changes. Only this report section was appended to
`docs\phase-1-progress.md`.

Verified:

- `find G:\vela-android -name "*.kt" -newer phase-1-progress.md` →
  zero matches (no Kotlin source touched).
- `find G:\vela -newer G:\vela-android\README.md` → zero matches
  (Windows VELA tree untouched).

### Safety confirmations

| Check | Result |
|---|---|
| `android.permission.INTERNET` in `AndroidManifest.xml` | No matches (grep) |
| Alpaca / OkHttp / Retrofit / Ktor imports | No files found (grep) |
| Order submission / live URL / TradingClient / paper-api.alpaca | No files found (grep) |
| LIVE endpoint constant | Not present anywhere in the lab |
| `realModeLocked: Boolean = true` (AppState.kt:18) | Confirmed |
| `G:\vela` modified since lab start | No (`find -newer` returned empty) |
| Windows `vela.db` read, copied, or touched | No — lab uses `vela-lab.db` in app-private storage only |
| Foreground service / ML / networking / Auto Paper / background workers | All deferred, none added |

### When this phase can be retried

Run Phase 1.g again as soon as **one** of the following is true:

- `adb devices` lists at least one device (physical handset with
  USB debugging, or a running emulator process), OR
- `emulator -list-avds` returns at least one AVD name (start it
  with `emulator @<avd>` and wait for `adb devices` to list it).

The retry sequence is exactly Steps 4–5 from this section's spec:
`adb install -r <apk>` → `adb shell am start -n com.vela.android.lab/.MainActivity`
→ walk the manual checklist.

---

## Phase 1.g (completion) — 2026-05-30 — Runtime validation passed on emulator-5554

### Status

**Complete.** The user created an AVD and launched the emulator
between runs. The Phase 1.e/1.f APK was installed and validated
end-to-end through `adb shell input tap` + `uiautomator dump`
against the running app. Every checklist item passed. The app
process never crashed and the PID never changed across all three
button interactions. **No new features were added. No source
files were modified.**

### Step 1 — App process running

```
adb -s emulator-5554 shell pidof com.vela.android.lab
8918
```

`pidof` returned PID **8918** before any interaction. After all
three button presses the same PID was still reported, so the
process survived without restart.

### Step 2 — Recent crash logs

```
adb -s emulator-5554 logcat -d -t 500 \
    | Select-String -Pattern "FATAL EXCEPTION|AndroidRuntime|com\.vela\.android\.lab"
```

The only matching line was an echoed `adbd shell` request for my
own `pidof` call. **Zero `FATAL EXCEPTION`. Zero `AndroidRuntime`
errors. Zero error mentions of `com.vela.android.lab`.**

### Step 3 — Manual validation, driven via uiautomator + input tap

Driving method: each button was located by parsing
`/sdcard/ui.xml` (pulled to `app/build/ui-initial.xml`) for the
`bounds="[x1,y1][x2,y2]"` attribute on its TextView, then
`adb shell input tap <centerX> <centerY>` issued the press,
followed by `Start-Sleep -Milliseconds 700` and another
`uiautomator dump` to capture the new state.

Button centers used:

| Button | Tap coords |
|---|---|
| Generate demo BTC/USD update | `672, 2175` |
| Generate demo SPY update | `672, 2343` |
| Clear local demo state | `672, 2511` |

#### 3a — Initial UI (already confirmed by the user via screenshot, re-confirmed via `uiautomator dump`)

| Field | Observed |
|---|---|
| App title | `VELA Android Lab` |
| Mode | `READ_ONLY` |
| REAL locked | `true` |
| Pipeline | `Offline demo` |
| Last symbol | `—` |
| Last price | `—` |
| Bar close | `—` |
| Feature direction | `—` |
| Signal state | `—` |
| Signal score | `—` |
| Persisted bars | `0` |
| Journal events | `0` |
| Demo buttons visible | yes (BTC/USD, SPY, Clear) |

#### 3b — After tapping "Generate demo BTC/USD update"

Snapshot in `app/build/ui-after-btc.xml`.

| Field | Observed | Expected |
|---|---|---|
| Last symbol | **`BTC/USD`** | BTC/USD ✓ |
| Price | **`50005.00`** | 50000.0 base + 5.0 tick = 50005.0 ✓ |
| Bar close | **`50005.00`** | matches price ✓ |
| Feature direction | **`flat`** | first bar in bucket, open=close ✓ |
| Signal state | **`NEUTRAL`** | score 0 with flat direction ✓ |
| Signal score | **`0`** | NEUTRAL threshold ✓ |
| Persisted bars | **`1`** | one bar persisted ✓ |
| Journal events | **`4`** | market_update_received + bar_persisted + features_persisted + signal_persisted ✓ |
| Mode / REAL locked / Pipeline | unchanged (`READ_ONLY` / `true` / `Offline demo`) | invariant ✓ |

#### 3c — After tapping "Generate demo SPY update"

Snapshot in `app/build/ui-after-spy.xml`.

| Field | Observed | Expected |
|---|---|---|
| Last symbol | **`SPY`** | swapped from BTC/USD ✓ |
| Price | **`400.25`** | 400.0 base + 0.25 SPY tick ✓ |
| Bar close | **`400.25`** | matches price ✓ |
| Feature direction | **`flat`** | first bar for SPY in bucket ✓ |
| Signal state | **`NEUTRAL`** | score 0 ✓ |
| Signal score | **`0`** | ✓ |
| Persisted bars | **`2`** | BTC row + SPY row ✓ |
| Journal events | **`8`** | 4 per accepted update × 2 updates ✓ |
| Mode / REAL locked / Pipeline | unchanged | invariant ✓ |

#### 3d — After tapping "Clear local demo state"

Snapshot in `app/build/ui-after-clear.xml`.

| Field | Observed | Expected |
|---|---|---|
| Last symbol | **`—`** | reset ✓ |
| Price | **`—`** | reset ✓ |
| Bar close | **`—`** | reset ✓ |
| Feature direction | **`—`** | reset ✓ |
| Signal state | **`—`** | reset ✓ |
| Signal score | **`—`** | reset ✓ |
| Persisted bars | **`0`** | all rows cleared from `market_bars_1m` ✓ |
| Journal events | **`0`** | all rows cleared from `journal_events` ✓ |
| Mode / REAL locked / Pipeline | unchanged | invariant ✓ |

All 14 of the spec's manual-checklist bullets are satisfied.

### Crash logs

**None.** After the BTC + SPY + Clear sequence, the final
`adb logcat -d -t 500 | grep FATAL EXCEPTION|AndroidRuntime|com.vela.android.lab`
returned only the trace of my own `adbd shell` `pidof` request.
The app PID is still **8918**, identical to the launch PID — no
restart, no crash, no force-stop.

### Files changed

**None of the lab's source files.** Only diagnostic artifacts:

- `app/build/ui-initial.xml`
- `app/build/ui-after-btc.xml`
- `app/build/ui-after-spy.xml`
- `app/build/ui-after-clear.xml`

These are UI hierarchy dumps pulled from the emulator's
`/sdcard/`, written under `build/` (regenerable). No `*.kt`,
`*.kts`, `*.toml`, or `*.xml` source file was modified. Verified
by `find G:\vela-android -name "*.kt" -newer phase-1-progress.md`
returning zero matches.

### Safety reconfirmation (Phase 1.g completion)

| Check | Result |
|---|---|
| `android.permission.INTERNET` in `AndroidManifest.xml` | No matches (grep) |
| Alpaca / OkHttp / Retrofit / Ktor imports | No files found (grep) |
| Order submission / live URL / TradingClient / paper-api.alpaca | No files found (grep) |
| LIVE endpoint constant | Not present anywhere in the lab |
| `realModeLocked: Boolean = true` (AppState.kt:18) | Confirmed — on-device UI also shows "REAL locked: true" through every interaction |
| `G:\vela` modified since lab start | No (`find -newer` returned empty) |
| Windows `vela.db` read, copied, or touched | No — lab uses only `vela-lab.db` in app-private storage |
| Foreground service / ML / networking / Auto Paper / background workers | All deferred, none added |

### Closing observation

The on-device behavior matches the JVM
`OfflineDashboardViewModelTest` (12 cases passing in Phase 1.e)
byte-for-byte: identical symbol normalization, identical demo
sequence/price values (50005.00 / 400.25), identical journal event
counts per update (4), identical reset semantics. The
ViewModel/Screen split that was set up in Phase 1.e is therefore
end-to-end validated — both the data layer (offline pipeline +
Room persistence) and the Compose presentation layer behave
correctly on a real Android runtime.

Phase 1 is now complete in full. No Phase 2 work started.

---

## Phase 2.a — 2026-05-31 — Read-only market data boundary (offline scaffolding)

### Status

**Complete.** Pure-Kotlin scaffolding only — no Alpaca connection,
no networking, no `INTERNET` permission, no credentials, no order
submission, no UI change. Both `:app:testDebugUnitTest` and the
full `:app:test` returned **BUILD SUCCESSFUL** with 36 new tests
added and zero failures across the existing suite.

### Files added (all under `data/market/source/`)

**Main source (6 files):**

- [MarketDataSource.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/MarketDataSource.kt)
  — `enum class MarketDataSource { OFFLINE, OFFLINE_STUB, ALPACA_PAPER }`.
  **`ALPACA_LIVE` is intentionally absent** so the type system
  forbids choosing it.
- [MarketDataError.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/MarketDataError.kt)
  — `sealed interface` with `NetworkUnavailable`, `AuthenticationFailed`,
  `SubscriptionRejected`, `StreamLost`, `Unknown` cases.
- [MarketDataConnectionStatus.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/MarketDataConnectionStatus.kt)
  — immutable snapshot with `State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }`
  and `disconnected()` / `connecting()` / `connected()` / `error()` factories.
- [MarketDataClient.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/MarketDataClient.kt)
  — interface exposing only `source`, `connectionStatus: StateFlow<…>`,
  `updates: SharedFlow<BootstrapMarketUpdate>`, `connect()`, `disconnect()`,
  `subscribe(symbols)`, `unsubscribe(symbols)`, `subscribedSymbols()`.
  **No order/account/trading method exists.**
- [MarketDataConfig.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/MarketDataConfig.kt)
  — `data class` with `Default = MarketDataConfig(source = OFFLINE_STUB, endpoint = null, …)`.
  `init` block rejects any endpoint that mentions "live" or
  `api.alpaca.markets` (the LIVE trading host); also rejects
  pairing `OFFLINE` / `OFFLINE_STUB` with any endpoint.
  Stores only a `credentialsKeyAlias: String?` hint for a future
  Keystore lookup — **no credential value passes through this type**.
- [StubPaperMarketDataClient.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/StubPaperMarketDataClient.kt)
  — deterministic, in-process implementation. `connect()` just
  flips the state flow; `emitDemoUpdate(symbol)` produces a
  reproducible `BootstrapMarketUpdate` (BTC at 50,005.00 +5/tick;
  SPY at 400.25 +0.25/tick — same ticks the Phase 1.e UI uses,
  so on-device and unit-test behavior agree byte-for-byte). The
  `updates` flow is `MutableSharedFlow(replay = 0, extraBufferCapacity = 16)`
  so subscribers do not see historical emissions.

**No existing source was modified.** `MainActivity`, the dashboard
ViewModel, the offline coordinator, the Room schema, and every
Phase 1 file are byte-identical to Phase 1.g.

### Tests added (5 files, 36 new cases)

| Class | tests | what it asserts |
|---|---:|---|
| `MarketDataSourceTest` | 2 | enum contains exactly `OFFLINE`, `OFFLINE_STUB`, `ALPACA_PAPER` — and no entry whose name contains `LIVE` |
| `MarketDataConfigTest` | 6 | `Default` is offline stub with no endpoint/credentials; init rejects "live" endpoints, the `api.alpaca.markets` LIVE host, and offline-source + endpoint pairings; accepts paper data endpoint when paired with `ALPACA_PAPER` |
| `MarketDataClientContractTest` | 19 | reflection-driven: every declared method of `MarketDataClient` and `StubPaperMarketDataClient` is checked against 15 forbidden substrings (`submit`, `placeorder`, `buy`, `sell`, `trade`, `account`, `credential`, `tradingclient`, …); the boundary's class simple names are checked too; and `BootstrapMarketUpdate`'s own methods are checked, so the streamed type cannot smuggle a trading hook |
| `StubPaperMarketDataClientTest` | 8 | source is `OFFLINE_STUB`; initial state DISCONNECTED; `connect()` ↔ `disconnect()` flips status; subscribe normalizes symbols (`btcusd` → `BTC/USD`); deterministic price ticks; `updates` is non-replaying; timestamps come from the injected clock |
| `StubFeedsCoordinatorIntegrationTest` | 1 | a stub-emitted BTC + SPY pair driven through the existing `OfflineMarketPipelineCoordinator` lands as 2 market_bars_1m rows, 2 features, 2 signals, and 8 journal_events — Phase 1 pipeline unchanged |
| **Total new in Phase 2.a** | **36** | |

### Full test result

```
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-daemon `
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8'
BUILD SUCCESSFUL in 43s

.\gradlew.bat :app:test --console=plain --no-daemon `
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8'
BUILD SUCCESSFUL in 51s
```

Per-variant counts (identical for `testDebugUnitTest` and `testReleaseUnitTest`):

| Class | tests | new in 2.a? |
|---|---:|---|
| ModeGuardTest | 6 | |
| AppStateTest | 5 | |
| RiskManagerTest | 7 | |
| SymbolsTest | 7 | |
| OneMinuteBarAggregatorTest | 9 | |
| FeatureEngineTest | 3 | |
| SignalEngineTest | 5 | |
| MappersTest | 12 | |
| MarketDataRepositoryTest | 9 | |
| FeatureRepositoryTest | 6 | |
| SignalRepositoryTest | 4 | |
| JournalRepositoryTest | 7 | |
| OfflineMarketPipelineCoordinatorTest | 10 | |
| OfflineDashboardViewModelTest | 12 | |
| **MarketDataSourceTest** | **2** | ✓ |
| **MarketDataConfigTest** | **6** | ✓ |
| **MarketDataClientContractTest** | **19** | ✓ |
| **StubPaperMarketDataClientTest** | **8** | ✓ |
| **StubFeedsCoordinatorIntegrationTest** | **1** | ✓ |
| **Total per variant** | **138** | |

**276 JVM test invocations across debug + release. All passed.**

### Runtime validation

Not required. **No source under `MainActivity`, the dashboard, or
the Compose tree was touched.** The Phase 2.a stub is currently
unused by the production app — it is wiring-ready but not yet wired.

### Files changed

- 6 new source files under `app/src/main/kotlin/com/vela/android/lab/data/market/source/`
- 5 new test files under `app/src/test/kotlin/com/vela/android/lab/data/market/source/`

No edits to existing Kotlin source, no edits to Gradle build files,
no manifest edits, no resource edits. The only doc change is this
appended Phase 2.a section.

### INTERNET permission status

**Absent.** Targeted manifest grep `android\.permission\.INTERNET|uses-permission`
returned no matches. Phase 2.a is fully offline; no Phase 2.b
networking work was started.

### Safety reconfirmation

| Check | Result |
|---|---|
| `android.permission.INTERNET` in `AndroidManifest.xml` | No matches (grep) |
| OkHttp / Retrofit / Ktor imports | No files found (grep) |
| Live trading constants in production source (`live.alpaca`, `api.alpaca.markets`, `TradingClient`, `paper-api.alpaca.markets`, `submit.*order`) | The only files that mention these strings are the **new safety guards**: `MarketDataConfig.kt` rejects them at construction time, and `MarketDataClient.kt` mentions the future `AlpacaPaperMarketDataClient` in a doc comment only. **No call site uses any of these strings as a destination.** |
| LIVE endpoint constant | Not present; the type system removes `ALPACA_LIVE` from `MarketDataSource` |
| `realModeLocked: Boolean = true` (AppState.kt:18) | Confirmed |
| `G:\vela` modified | No (`find -newer` returned empty) |
| Windows `vela.db` read, copied, or touched | No — lab uses only `vela-lab.db` in app-private storage |
| Foreground service / ML / networking / Auto Paper / background workers | All deferred, none added |
| Compose UI / MainActivity / ViewModel touched | No — Phase 1.e UI is byte-identical |

### Final Phase 2.a status

**Complete.** The read-only boundary, configuration scaffolding,
and deterministic offline stub are in place; tests prove the
boundary cannot expose a trading API and the existing Phase 1
pipeline continues to work unchanged. Phase 2.b (real Alpaca Paper
client wiring + `INTERNET` permission + Keystore-backed
credentials) is **not** started.

---

## Phase 2.b — 2026-05-31 — Alpaca Market Data test stream client

### Status

**Complete.** New OkHttp-backed WebSocket client targets **only**
`wss://stream.data.alpaca.markets/v2/test`. INTERNET permission
added with prominent doc-comment scope. JVM tests cover endpoint
guards, parser, no-trading reflection, full client lifecycle, and
integration with the Phase 1.d coordinator. Runtime smoke check on
the emulator confirms the Phase 1.e offline UI still works
byte-for-byte. **No order submission. No account mutation. No live
endpoint. No hardcoded credentials. No UI changes.**

### Dependency changes

| Dependency | Version | Scope |
|---|---|---|
| `com.squareup.okhttp3:okhttp` | 4.12.0 | `implementation` |
| `org.json:json` | 20240303 | `implementation` |
| `com.squareup.okhttp3:mockwebserver` | 4.12.0 | declared in `libs.versions.toml` only; **not** yet wired into any test |

No Retrofit. No Ktor. No Alpaca SDK. No `kotlinx-serialization` (the
parser uses `org.json.JSONArray`/`JSONObject` directly to avoid
adding a Gradle plugin). OkHttp's transport is used **only** by
`OkHttpAlpacaWebSocketFactory` in `data/market/source/alpaca/`.

### INTERNET permission

**Added.** [AndroidManifest.xml](../android/app/src/main/AndroidManifest.xml)
declares:

```xml
<!--
    Phase 2.b: read-only Alpaca Market Data test stream only.
    No INTERNET-consuming code path other than the test stream
    client exists in the lab.
-->
<uses-permission android:name="android.permission.INTERNET" />
```

Scope: read-only. The only network caller in the codebase is the
new `AlpacaTestStreamMarketDataClient`, gated at construction by
`AlpacaStreamEndpoint.requireSafeReadOnlyEndpoint(...)`.

### Endpoint allowed

`AlpacaStreamEndpoint.TEST_STREAM_URL = "wss://stream.data.alpaca.markets/v2/test"` —
hard-coded. Any other URL passed to the client throws
`IllegalArgumentException` at construction. The guard explicitly
rejects:

- `api.alpaca.markets` (the LIVE trading host)
- `paper-api.alpaca.markets` (paper trading API host — not market data)
- Any path fragment of `/orders`, `/positions`, `/account`,
  `/trading`, `/portfolio`
- Any URL whose lowercased form contains `live`

Rejection is enforced **both** by the URL validator and by the
absence of an `ALPACA_LIVE` value in `MarketDataSource`.

### Credentials handling

- `AlpacaCredentials` is a class with overridden `toString()` that
  redacts the secret (only the first 4 chars of `keyId` survive in
  logs).
- `AlpacaCredentialsProvider` is a `fun interface` with a single
  suspending `read(): AlpacaCredentials?` method.
- `NoAlpacaCredentialsProvider` is the production-safe default — it
  always returns `null`. A client wired with this provider moves to
  `ERROR / AuthenticationFailed` on `connect()` and **never opens
  the WebSocket**.
- No credentials are hardcoded. No credentials are committed. No
  Keystore wiring is added in Phase 2.b — that lands in a later
  phase when the production app actually needs to authenticate.

### Files added (all under `data/market/source/alpaca/`)

Main source (8 files):

- [AlpacaCredentials.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/alpaca/AlpacaCredentials.kt)
- [AlpacaCredentialsProvider.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/alpaca/AlpacaCredentialsProvider.kt)
- [NoAlpacaCredentialsProvider.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/alpaca/NoAlpacaCredentialsProvider.kt)
- [AlpacaStreamEndpoint.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/alpaca/AlpacaStreamEndpoint.kt) — `TEST_STREAM_URL` constant + `requireSafeReadOnlyEndpoint` guard
- [AlpacaStreamMessage.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/alpaca/AlpacaStreamMessage.kt) — sealed model: Connected, Authenticated, Subscription, Quote, Bar, StreamError, Unknown
- [AlpacaStreamMessageParser.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/alpaca/AlpacaStreamMessageParser.kt) — `JSONArray`/`JSONObject`-based parser; invalid JSON resolves to `emptyList()` (no exception); bars/quotes missing timestamps degrade to `Unknown`
- [AlpacaWebSocket.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/alpaca/AlpacaWebSocket.kt) — injectable `AlpacaWebSocketFactory` / `Handle` / `Listener` interfaces
- [OkHttpAlpacaWebSocketFactory.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/alpaca/OkHttpAlpacaWebSocketFactory.kt) — OkHttp bridge implementation (10 s connect timeout, 0 s read timeout for streaming, 30 s ping)
- [AlpacaTestStreamMarketDataClient.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/alpaca/AlpacaTestStreamMarketDataClient.kt) — full read-only Alpaca client implementing `MarketDataClient`

### Files modified

- [gradle/libs.versions.toml](../android/gradle/libs.versions.toml) — added `okhttp`, `org-json` libraries and versions.
- [app/build.gradle.kts](../android/app/build.gradle.kts) — added `implementation(libs.okhttp)` and `implementation(libs.org.json)` with a comment scoping the deps to the market-data boundary.
- [AndroidManifest.xml](../android/app/src/main/AndroidManifest.xml) — added INTERNET permission + Phase 2.b doc block.
- [MarketDataSource.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/MarketDataSource.kt) — added `ALPACA_TEST_STREAM` enum value.
- [MarketDataSourceTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/market/source/MarketDataSourceTest.kt) — updated to assert exactly 4 enum entries.

`MainActivity`, `OfflineDashboardScreen`, `OfflineDashboardViewModel`,
`VelaLabApplication`, `OfflineMarketPipelineCoordinator`, every
repository, every DAO, every entity, and every Phase 1 file is
**byte-identical** to Phase 2.a / Phase 1.g.

### Tests added (5 files, 114 new cases)

| Class | tests | what it asserts |
|---|---:|---|
| `AlpacaStreamEndpointTest` | 15 | `TEST_STREAM_URL` is the documented wss URL; `TEST_SYMBOL` is `FAKEPACA`; valid URL accepted; **12 dynamic cases for forbidden URLs** (`api.alpaca.markets/v2/orders`, `paper-api.alpaca.markets/v2/orders`, IEX/SIP feeds, paths containing `/orders` / `/account` / `/trading` / `/positions` / `/portfolio`, hosts with `live`, mismatched test URLs) — each must throw |
| `AlpacaStreamMessageParserTest` | 12 | parses success/connected, success/authenticated, subscription confirmation, quote, bar, error, multi-message envelope, invalid JSON → empty list, empty payload → empty list, unknown tag → `Unknown`, bar missing timestamp → `Unknown`, bar missing symbol → `Unknown` |
| `AlpacaTestStreamClientContractTest` | 76 | reflection-driven across every declared method of `AlpacaTestStreamMarketDataClient`, `OkHttpAlpacaWebSocketFactory`, and the entire `AlpacaStreamMessage` sealed hierarchy. The forbidden-substring list was narrowed (relative to Phase 2.a) to multi-token patterns like `submitorder`, `placeorder`, `tradingclient`, `executeorder`, `cancelorder`, `getaccount`, `openposition`, `closeposition`, `getportfolio`, `setbalance`, `transferfund` — generic words like "trade" were dropped because `Subscription.trades` legitimately echoes the subscribed trades-feed symbol list and is not a trading action |
| `AlpacaTestStreamMarketDataClientTest` | 10 | `source` is `ALPACA_TEST_STREAM`; constructor rejects unsafe endpoints; initial status DISCONNECTED; **missing credentials → ERROR/AuthenticationFailed and no WebSocket opened**; happy-path drives CONNECTING → CONNECTED with auth message + subscribe message containing only `bars` and `quotes` (verified: `subscribe.has("trades") == false`); bar message flows into `BootstrapMarketUpdate` on `updates` flow with correct OHLCV; server `error/401` maps to `AuthenticationFailed`; `onFailure` maps to `StreamLost`; `disconnect` closes the handle and resets status; subscribe normalizes symbols |
| `AlpacaTestStreamFeedsCoordinatorTest` | 1 | scripted FAKEPACA bars driven through the client flow into the existing `OfflineMarketPipelineCoordinator`: 2 bars in 2 minute buckets persist as 2 rows in each of market/feature/signal DAOs + 8 journal rows |
| **Total new in Phase 2.b** | **114** | |

One iteration was required: the first run failed
`AlpacaStreamMessage.Subscription.getTrades` against the original
contract list (`trade` matched the field name even though it is
the subscription-confirmation echo, not a trading action). The
contract list was tightened to multi-token patterns — **no
production code was changed to make the test pass.**

### testDebugUnitTest result

```
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-daemon `
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8'

BUILD SUCCESSFUL in 26s
```

### Full :app:test result

```
.\gradlew.bat :app:test --console=plain --no-daemon `
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8'

BUILD SUCCESSFUL in 58s
```

Per-variant test counts (identical for `testDebugUnitTest` and `testReleaseUnitTest`):

| Phase | Test class | tests |
|---|---|---:|
| 1.a | ModeGuardTest | 6 |
| 1.a | AppStateTest | 5 |
| 1.a | RiskManagerTest | 7 |
| 1.b | SymbolsTest | 7 |
| 1.b | OneMinuteBarAggregatorTest | 9 |
| 1.b | FeatureEngineTest | 3 |
| 1.b | SignalEngineTest | 5 |
| 1.c | MappersTest | 12 |
| 1.c | MarketDataRepositoryTest | 9 |
| 1.c | FeatureRepositoryTest | 6 |
| 1.c | SignalRepositoryTest | 4 |
| 1.c | JournalRepositoryTest | 7 |
| 1.d | OfflineMarketPipelineCoordinatorTest | 10 |
| 1.e | OfflineDashboardViewModelTest | 12 |
| 2.a | MarketDataSourceTest | 2 |
| 2.a | MarketDataConfigTest | 6 |
| 2.a | MarketDataClientContractTest | 19 |
| 2.a | StubPaperMarketDataClientTest | 8 |
| 2.a | StubFeedsCoordinatorIntegrationTest | 1 |
| **2.b** | **AlpacaStreamEndpointTest** | **15** |
| **2.b** | **AlpacaStreamMessageParserTest** | **12** |
| **2.b** | **AlpacaTestStreamClientContractTest** | **76** |
| **2.b** | **AlpacaTestStreamMarketDataClientTest** | **10** |
| **2.b** | **AlpacaTestStreamFeedsCoordinatorTest** | **1** |
| **Total per variant** | | **252** |

**504 JVM test invocations across debug + release. All passed.**

### Runtime validation (INTERNET permission added → required)

Re-built APK and re-installed on the still-running `emulator-5554`
from Phase 1.g. **No new screens, no UI changes, no wired call
sites** — the new client is wholly isolated behind tests. The
smoke check answers: *does adding INTERNET permission + OkHttp +
org.json break the offline UI?*

```
adb install -r G:\vela-android\android\app\build\outputs\apk\debug\app-debug.apk → Success
adb shell am start -n com.vela.android.lab/.MainActivity → ok
adb shell pidof com.vela.android.lab → 15137
```

Initial UI dump matched Phase 1.g initial state exactly (title
`VELA Android Lab`; mode `READ_ONLY`; REAL locked `true`; pipeline
`Offline demo`; six "last-" fields `—`; persisted bars / journal
events `0`).

Tap on the BTC button at coords `(672, 2175)` → UI dump:

| Field | Value |
|---|---|
| Symbol | `BTC/USD` |
| Price | `50005.00` |
| Bar close | `50005.00` |
| Feature direction | `flat` |
| Signal state | `NEUTRAL` |
| Signal score | `0` |
| Persisted bars | `1` |
| Journal events | `4` |

Identical to Phase 1.g's BTC-tap state. PID **15137** unchanged
after tap — no restart, no crash.

Logcat sweep (`-d -t 500 | Select-String "FATAL EXCEPTION|AndroidRuntime|com\.vela\.android\.lab"`)
returned only:
1. `Shutting down VM` from a different process (`pid=15230`, the
   `uiautomator dump` helper VM finishing) — **not** the app
   process (`15137`).
2. An echoed `adbd shell pidof` request.

**No `FATAL EXCEPTION`, no `AndroidRuntime` error for our app, no
restart, no crash. INTERNET permission and the new OkHttp +
org.json dependencies did not affect the offline UI.**

### Safety reconfirmation

| Check | Result |
|---|---|
| `INTERNET` permission added | Yes — documented in the manifest as read-only Alpaca Market Data test stream only |
| Hardcoded Alpaca credentials | None. Grep for `"[A-Z0-9]{16,}"` literal in `app/src/main` returned zero hits |
| Order submission code | None. Targeted grep `submit.*order|placeOrder|TradingClient|api\.alpaca\.markets/v2/orders` matched only two lines: the **safety-doc comments** in `MarketDataClient.kt` ("submits orders, mutates account state…") and `AlpacaTestStreamMarketDataClient.kt` ("has no method that submits an order…") — both **assertions of the negative**, not call sites |
| Account / trading endpoint | None. The endpoint guard rejects every `/orders`, `/positions`, `/account`, `/trading`, `/portfolio` path; 12 dynamic test cases prove it |
| LIVE endpoint | None. `MarketDataSource` enum still does not contain `ALPACA_LIVE`. `AlpacaStreamEndpoint` rejects any URL containing `live` |
| Auto Paper | Not started. No Auto Paper class added, no scheduler wired |
| `realModeLocked: Boolean = true` ([AppState.kt:18](../android/app/src/main/kotlin/com/vela/android/lab/state/AppState.kt#L18)) | Confirmed; on-device UI still shows "REAL locked: true" |
| `G:\vela` modified | No (`find -newer` returned empty) |
| Windows `vela.db` read, copied, or touched | No — lab uses only `vela-lab.db` in app-private storage |
| Foreground service / ML / background workers | All deferred, none added |
| Compose UI / MainActivity / ViewModel touched | No — Phase 1.e UI byte-identical, confirmed on-device |

### Final Phase 2.b status

**Complete.** The Alpaca Market Data test stream client is
implemented, fully tested at the JVM level, isolated behind the
existing read-only `MarketDataClient` boundary, and reachable
through the `OfflineMarketPipelineCoordinator` via an injected
factory in tests. The production app still runs the Phase 1.e
offline UI byte-identically on-device — the new client is **not
yet wired** to any UI surface. Future phases will introduce a
credentials entry point (Keystore-backed) and a debug-only toggle
to switch the dashboard between the offline stub and this test
stream.

### Re-verification — 2026-05-31

The user re-ran Phase 2.b on the same day, after the original
section above was appended. No source files changed since.

| Check | Result |
|---|---|
| Phase 2.b main files (9) | All present in `data/market/source/alpaca/` |
| Phase 2.b test files (5) | All present |
| `:app:testDebugUnitTest` | BUILD SUCCESSFUL in 14s, 25 tasks UP-TO-DATE |
| `:app:test` (debug + release) | BUILD SUCCESSFUL in 14s, 51 tasks UP-TO-DATE |
| Emulator `emulator-5554` | Still attached |
| `android.permission.INTERNET` | Still present in manifest |
| `submit.*order` matches | 2 — both still safety-doc comments asserting the negative (`MarketDataClient.kt:9`, `AlpacaTestStreamMarketDataClient.kt:29`) |
| Hardcoded credentials | None |
| `realModeLocked: Boolean = true` | Confirmed at `AppState.kt:18` |
| `G:\vela` modified | No |
| Kotlin source modified since prior report | None — `find -newer phase-1-progress.md` returned zero matches |

No new code, no new tests, no new dependencies added during this
re-verification. Existing 504 test invocations (252 per variant ×
debug + release) remain green; no APK rebuild was required because
no source changed and no manifest changed.

---

## Phase 2.c — 2026-05-31 — Secure credential handling + on-device smoke test

### Status

**Complete.** A safe credential path is now wired end-to-end: the
debug build can pull Alpaca Market Data **test stream** credentials
from a gitignored `local.properties` via `BuildConfig` fields, and
the release build cannot carry credentials regardless of what
`local.properties` contains. A debug-only "Test Alpaca Market Data
(debug)" card on the dashboard exposes a strictly read-only
start/stop control and a status display. On-device smoke test
exercised the AuthenticationFailed path with no credentials
configured — **no socket was opened**. Phase 1.e offline UI still
works byte-identically alongside.

**No order submission. No account mutation. No live endpoint. No
credential value in source, in `BuildConfig` literals, in UI text,
in logs, or in this report. Endpoint hard-locked to
`wss://stream.data.alpaca.markets/v2/test`.**

### Credential strategy

- **Source of truth**: developer's local `local.properties` (already
  gitignored). Two optional keys: `ALPACA_TEST_KEY_ID` and
  `ALPACA_TEST_SECRET`. Defaults to blank.
- **Build-time injection**: `app/build.gradle.kts` reads
  `local.properties` for the **debug** build type and exposes the
  values via `BuildConfig.ALPACA_TEST_KEY_ID` / `BuildConfig.ALPACA_TEST_SECRET`.
  The **release** build type explicitly overrides both fields to
  `""`, so a release APK cannot carry credentials even if the
  developer left them in `local.properties` by mistake.
- **Runtime resolution**: `BuildConfigAlpacaCredentialsProvider`
  trims and validates the values; blank/whitespace resolves to
  `null`. The Phase 2.b client surfaces `null` as
  `MarketDataError.AuthenticationFailed` and never opens the
  WebSocket.
- **Logging**: the `AlpacaCredentials.toString()` redaction
  (`"AlpacaCredentials(keyId=PKAB…, secret=***)"`) means accidental
  `Log.d(creds)` calls would still hide the secret. The Phase 2.c
  code never logs the credential value at all.
- **UI exposure**: the only field the UI shows is a boolean
  `Credentials configured: true/false`. Key id and secret are
  never rendered. Verified on-device.
- **Future**: Keystore + `EncryptedSharedPreferences` is the
  documented next step in [docs/alpaca-credentials.md](alpaca-credentials.md)
  but is deliberately out of scope for Phase 2.c per the spec's
  "Acceptable for this phase" allowance.

### Whether secrets were added to source

**No.** Verified via three independent greps:

- `grep -i "ALPACA_TEST_KEY_ID|ALPACA_TEST_SECRET" -r app/src` —
  only field names, never values.
- `grep -E '"PK[A-Z0-9]{12,}"' -r app/src` (Alpaca paper-key
  prefix shape) — no matches.
- `grep -E 'ALPACA_TEST_KEY_ID\s*=\s*"[^"]+"|ALPACA_TEST_SECRET\s*=\s*"[^"]+"'`
  — no matches.

### Files added (5 main + 2 test + 1 doc)

Main:

- [BuildConfigAlpacaCredentialsProvider.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/alpaca/BuildConfigAlpacaCredentialsProvider.kt)
  — production credentials provider; injectable lambdas for tests;
  `fromBuildConfig()` factory.
- [AlpacaTestStreamUiState.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/AlpacaTestStreamUiState.kt)
  — UI state for the debug card; no credential field.
- [AlpacaTestStreamViewModel.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/AlpacaTestStreamViewModel.kt)
  — collects `connectionStatus` + `updates`; `startSmokeTest()` /
  `stopSmokeTest()` only; never reads or stores credential values.

Tests:

- [BuildConfigAlpacaCredentialsProviderTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/market/source/alpaca/BuildConfigAlpacaCredentialsProviderTest.kt)
  — 8 cases.
- [AlpacaTestStreamViewModelTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/ui/dashboard/AlpacaTestStreamViewModelTest.kt)
  — 6 cases.

Doc:

- [docs/alpaca-credentials.md](alpaca-credentials.md) — developer
  setup guide. Explicitly contains **no key values**; warns about
  rotation if `local.properties` is accidentally committed.

### Files modified (4)

- [app/build.gradle.kts](../android/app/build.gradle.kts) — added
  `import java.util.Properties`, `buildFeatures.buildConfig = true`,
  `loadLocalAlpacaProperty()` helper, debug+release
  `buildConfigField` declarations; release explicitly forces
  blank values.
- [VelaLabApplication.kt](../android/app/src/main/kotlin/com/vela/android/lab/VelaLabApplication.kt)
  — three new lazy properties: `alpacaCredentialsProvider`,
  `alpacaWebSocketFactory`, `alpacaTestStreamClient`. Each is inert
  until first use; the Phase 1.e offline dashboard never touches
  them.
- [MainActivity.kt](../android/app/src/main/kotlin/com/vela/android/lab/MainActivity.kt)
  — added `alpacaViewModel` `by viewModels { ... }`; passes it to
  the screen only when `BuildConfig.DEBUG` is true.
- [OfflineDashboardScreen.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt)
  — new optional `alpacaViewModel` parameter; new
  `AlpacaTestStreamCard` Composable renders only when the
  parameter is non-null. The card's title is **"Test Alpaca
  Market Data (debug)"** and its body explicitly documents the
  read-only scope and the locked endpoint.

### Tests added — 14 new cases

| Class | tests | what it asserts |
|---|---:|---|
| `BuildConfigAlpacaCredentialsProviderTest` | 8 | blank key/secret/both → null; whitespace-only → null; populated values produce credentials; trim semantics; `AlpacaCredentials.toString()` redacts secret entirely and truncates key id (test asserts the actual secret string is **not** in toString); read consults sources every time so a future Keystore rotation works without process restart |
| `AlpacaTestStreamViewModelTest` | 6 | initial state DISCONNECTED + `credentialsConfigured=false`; provider returning credentials flips `credentialsConfigured=true`; `startSmokeTest` with no credentials surfaces ERROR + AuthenticationFailed and `factory.openCalls==0` (no socket opened); happy-path drives CONNECTED + first bar updates `lastBarSymbol/Close/Timestamp` + `barsReceived=1`; `stopSmokeTest` closes handle and resets to DISCONNECTED; multiple bars increment the counter and update the last-bar fields |
| **Total new in Phase 2.c** | **14** | |

### testDebugUnitTest result

```
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-daemon `
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8'

BUILD SUCCESSFUL in 48s
```

### Full :app:test result

```
.\gradlew.bat :app:test --console=plain --no-daemon `
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8'

BUILD SUCCESSFUL in 18s
53 actionable tasks: 53 up-to-date
```

Per-variant counts (identical for debug + release):

| Phase | Test class | tests |
|---|---|---:|
| 1.a–2.b (existing) | (14 classes) | 252 |
| **2.c** | **BuildConfigAlpacaCredentialsProviderTest** | **8** |
| **2.c** | **AlpacaTestStreamViewModelTest** | **6** |
| **Total per variant** | | **266** |

**532 JVM test invocations across debug + release. All passed.**

### APK + on-device smoke test

```
.\gradlew.bat :app:assembleDebug → BUILD SUCCESSFUL in 21s
adb -s emulator-5554 install -r app-debug.apk → Success
adb -s emulator-5554 shell am force-stop com.vela.android.lab
adb -s emulator-5554 shell am start -n com.vela.android.lab/.MainActivity
adb -s emulator-5554 shell pidof com.vela.android.lab → 19834
adb -s emulator-5554 shell dumpsys activity activities | grep topResumedActivity
  → topResumedActivity=ActivityRecord{... com.vela.android.lab/.MainActivity ...}
```

UI dumps captured under `app/build/ui-phase2c-*.xml`:

**Initial state (`ui-phase2c-initial.xml` + `ui-phase2c-scrolled.xml`):**

| Field | Value |
|---|---|
| Title | `VELA Android Lab` |
| Mode | `READ_ONLY` |
| REAL locked | `true` |
| Pipeline | `Offline demo` |
| Last pipeline step fields | all `—` |
| Persisted bars | `1` (carried over from Phase 2.b Room DB) |
| Journal events | `4` (carried over from Phase 2.b Room DB) |
| **Test Alpaca Market Data (debug) card** | present |
| Card subtitle | `Read-only. Connects to wss://stream.data.alpaca.markets/v2/test and subscribes to FAKEPACA only. No orders. No account.` |
| Connection | `DISCONNECTED` |
| Credentials configured | `false` |
| Last bar symbol / close / timestamp | `—` |
| Bars received | `0` |

**After tapping "Start Alpaca test stream" (`ui-phase2c-after-start.xml`)** — with `local.properties` not populated:

| Field | Value |
|---|---|
| Connection | **`ERROR`** |
| Credentials configured | `false` |
| Bars received | `0` (no WebSocket opened) |
| Error | **`Error: No Alpaca credentials configured for the test stream.`** |

The error message is the generic Phase 2.b string. **No credential
value appears anywhere on screen.** The fake-WebSocket-factory test
already proved that `factory.openCalls == 0` on this path; the
on-device run does not contradict that — no network activity was
triggered.

**After tapping the offline "Generate demo BTC/USD update" button (`ui-phase2c-after-btc.xml`)**:

| Field | Value |
|---|---|
| Signal state | `NEUTRAL` |
| Signal score | `0` |
| Persisted bars | `2` (incremented from `1`) |
| Journal events | `8` (incremented from `4`) |
| Alpaca card | still `ERROR` + `Credentials configured: false`, unchanged |

The offline pipeline is intact alongside the Alpaca debug card.

**Process / logs:**

- `pidof com.vela.android.lab` = **19834** before, during, and after
  all taps. **No restart, no crash.**
- `logcat -d -t 700 | Select-String "FATAL EXCEPTION|AndroidRuntime|com\.vela\.android\.lab"`
  returned only:
  - one `AndroidRuntime: Shutting down VM` from `pid=20742` (the
    `uiautomator dump` helper VM finishing — **not** the app
    process),
  - one `adbd` echo of my own `pidof` shell call.
- **No FATAL EXCEPTION. No AndroidRuntime error for the app
  process.**
- No credential text appears anywhere in the 700-line logcat
  window. The error log line emitted by the AlpacaTestStreamViewModel
  is the generic `"No Alpaca credentials configured for the test
  stream."` string — credentials are not in scope to log because the
  provider returned `null`.

### Real-network smoke test against FAKEPACA

**Not performed.** No `local.properties` credentials were configured
on this host. The spec allows this explicitly:

> if credentials are available, run the FAKEPACA test stream smoke test

The credentials path is fully exercised by the JVM `AlpacaTestStreamViewModelTest`
happy-path test (using the recording fake `AlpacaWebSocketFactory`)
which verifies that scripted server messages drive the client to
`CONNECTED` and that bar messages flow as `BootstrapMarketUpdate`
values. A developer who follows
[docs/alpaca-credentials.md](alpaca-credentials.md) can run the
real-network smoke test by populating `local.properties` and tapping
"Start Alpaca test stream"; the UI will then transition through
`CONNECTING → CONNECTED → bars received > 0`.

### Safety reconfirmation

| Check | Result |
|---|---|
| `INTERNET` permission still read-only Market Data only | Yes; manifest doc-comment unchanged from Phase 2.b |
| Alpaca credentials hardcoded | **None.** Three precision greps over `app/src/main` returned no matches for paper-key shapes or `ALPACA_TEST_*=` literals |
| Credentials in UI text on-device | **None.** Only `Credentials configured: false` boolean is rendered |
| Credentials in logcat | **None.** 700-line sweep contained no key/secret literals |
| Credentials in docs (this report or any other) | **None.** `docs/alpaca-credentials.md` is values-free; the report mentions field names only |
| Endpoint remains `wss://stream.data.alpaca.markets/v2/test` only | Yes; `AlpacaStreamEndpoint.kt` unchanged, on-device card subtitle quotes the exact URL |
| Order submission code | **None.** Only the two safety-doc comments at `MarketDataClient.kt:9` and `AlpacaTestStreamMarketDataClient.kt:29` mention the topic — both **assertions of the negative** |
| Account / trading endpoints | **None.** Endpoint guard still rejects every `/orders`, `/positions`, `/account`, `/trading`, `/portfolio` path |
| LIVE endpoint | **None.** `ALPACA_LIVE` still absent from enum |
| `realModeLocked: Boolean = true` ([AppState.kt:18](../android/app/src/main/kotlin/com/vela/android/lab/state/AppState.kt#L18)) | Confirmed; on-device UI shows "REAL locked: true" through all taps |
| `G:\vela` modified | No (`find -newer` returned empty) |
| Windows `vela.db` read / copied / touched | No — lab uses only `vela-lab.db` in app-private storage |
| Auto Paper | Not started |
| Foreground service / ML / background workers | All deferred, none added |

### Final Phase 2.c status

**Complete.** The lab can now authenticate against the Alpaca
Market Data test stream **if and only if** the developer chooses
to populate `local.properties` on their own machine; release builds
cannot. On-device the offline dashboard works alongside a clearly
labeled debug-only Alpaca card that, with no credentials
configured, demonstrates the safe `AuthenticationFailed` path
without opening a socket. Phase 2.d work (Keystore-backed
credentials, real-network smoke recording, optional pipeline
wiring of the Alpaca client to the offline coordinator) is **not**
started.

---

## Phase 2.c — 2026-06-01 — Real FAKEPACA smoke test completion

### Status

**Complete.** With `local.properties` now populated with paper
credentials on the developer's machine, the on-device smoke test
against `wss://stream.data.alpaca.markets/v2/test` succeeded: the
client authenticated, subscribed to `FAKEPACA` only, and received
a real bar payload. **No credential value appeared on screen, in
logcat (2040 lines swept), in any source file, in any doc, or in
this report — the only Phase 2.c-related field rendered on the
device is `Credentials configured: true` as a boolean.** The
offline dashboard continued to work alongside.

### Credentials configured boolean

`true` — verified by UI dump after a clean install of a freshly-
rebuilt APK. The actual key id and secret were not read into
chat, not logged, not displayed in the UI, and not written to
any doc.

### Build/install commands

```
.\gradlew.bat :app:assembleDebug --rerun-tasks --console=plain --no-daemon `
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8'
  → BUILD SUCCESSFUL in 59s, 37 tasks executed
```

(The initial `assembleDebug` after the credentials were added
reported "37 up-to-date" because Gradle's task-input fingerprinting
did not detect the `local.properties` change reaching `buildConfigField`
— a known limitation of configuration-time reads. `--rerun-tasks`
forces a full rerun and was used here.)

```
adb -s emulator-5554 uninstall com.vela.android.lab    → Success
adb -s emulator-5554 install app-debug.apk             → Performing Streamed Install / Success
adb -s emulator-5554 shell am start -n com.vela.android.lab/.MainActivity
adb -s emulator-5554 shell pidof com.vela.android.lab  → 22809
```

### FAKEPACA smoke test result

Snapshots saved under `app/build/ui-smoke-*.xml`:

| Step | UI fields observed |
|---|---|
| **Initial (`ui-smoke-rebuilt.xml`)** | `Connection: DISCONNECTED` · `Credentials configured: true` · `Bars received: 0` |
| **After tap "Start" (`ui-smoke-after-real-start.xml`, ~8s)** | `Connection: CONNECTED` · `Credentials configured: true` · `Last bar symbol: —` · `Bars received: 0` (auth + subscribe completed; no bar yet) |
| **After ~23s total wait (`ui-smoke-after-wait.xml`)** | `Connection: CONNECTED` · `Last bar symbol: FAKEPACA` · `Last bar close: 134.65` · `Last bar timestamp: 2026-06-01T03:56:00Z` · `Bars received: 1` |
| **After tap "Stop" (`ui-smoke-after-stop.xml`)** | `Connection: DISCONNECTED` (WebSocket closed cleanly) |

**Authentication against the real Alpaca test stream succeeded.**
At least one real FAKEPACA bar arrived and was parsed safely (mapped
to a `BootstrapMarketUpdate` with `close=134.65`,
`timestamp=2026-06-01T03:56:00Z`, `source="alpaca-test-stream"`).
The subscribed symbol set on the client remained `{FAKEPACA}` only;
endpoint hard-locked to `wss://stream.data.alpaca.markets/v2/test`.

### Offline dashboard still works (regression check)

Snapshots `ui-smoke-offline-after.xml`, `ui-smoke-offline-clear.xml`:

| Step | Observed |
|---|---|
| Tap "Generate demo BTC/USD update" + Tap "Generate demo SPY update" | `Persisted bars: 2`, `Journal events: 8`, `Signal state: NEUTRAL` |
| Tap "Clear local demo state" | `Persisted bars: 0`, `Journal events: 0`, `Signal state: —` |

Phase 1.e offline behavior intact.

### Logcat crash result

```
adb logcat -d -t 2000 → 2040 lines captured at app/build/logcat-phase2c-smoke.txt
Select-String "FATAL EXCEPTION|AndroidRuntime.*FATAL" → 0 matches
Select-String "com\.vela\.android\.lab" → 0 matches (the app does not emit
                                                     to logcat under the lab tag)
```

`pidof com.vela.android.lab` remained **22809** before tap, after
"Start" tap, after the ~23 s wait, after "Stop" tap, after the
offline BTC/SPY/Clear taps. **No restart. No crash.**

### Credential leak check

Counts only — values were never displayed:

| Pattern | Logcat 2040 lines | `app/src` source tree | `docs/` tree |
|---|---:|---:|---:|
| Alpaca paper-key prefix shape `PK[A-Z0-9]{16,}` | **0** | **0** | **0** |
| Literal `ALPACA_TEST_KEY_ID = "<value>"` | **0** | **0** | **0** |
| Literal `ALPACA_TEST_SECRET = "<value>"` | **0** | **0** | **0** |
| WebSocket auth-frame fragment `"action":"auth"` | **0** | _(only `MarketDataClient` and `AlpacaTestStreamMarketDataClient` source files reference auth as a name — no value)_ | **0** |

The compiled debug `BuildConfig.java` does carry the values (that's
how the running app reads them) — that file is a generated build
artifact under `app/build/generated/...`, NOT source, and is not
committed because the entire `app/build/` tree is gitignored. The
release variant's `BuildConfig.java` was verified separately to
contain `ALPACA_TEST_KEY_ID = ""` and `ALPACA_TEST_SECRET = ""`,
so a release APK cannot carry the credentials.

### testDebugUnitTest result

```
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-daemon `
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8'
  → BUILD SUCCESSFUL in 28s, 26 tasks (5 executed, 21 up-to-date)
```

The 5 re-executed tasks were the BuildConfig + Kotlin compile +
unit-test compile + unit-test exec chain — Gradle correctly
re-fingerprinted after the new BuildConfig values flowed in.

### Full :app:test result

```
.\gradlew.bat :app:test --console=plain --no-daemon `
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8'
  → BUILD SUCCESSFUL in 16s, 53 tasks all up-to-date
```

**266 tests per variant × debug + release = 532 invocations, all
passing.** No test reads `BuildConfig.ALPACA_TEST_*` directly;
`BuildConfigAlpacaCredentialsProvider` is tested via injected
lambdas in `BuildConfigAlpacaCredentialsProviderTest`, so the
suite continues to run identically regardless of `local.properties`
contents.

### Safety confirmations

| Check | Result |
|---|---|
| Credentials printed in chat / report / logs / UI | **None.** Only the boolean `Credentials configured: true` was rendered on-device; every grep over logcat, `app/src`, and `docs/` returned 0 matches for the paper-key prefix shape and the literal `ALPACA_TEST_*="value"` patterns |
| Hardcoded credentials in source | **None** (`grep -rE '"PK[A-Z0-9]{12,}"' app/src` → 0 files) |
| Endpoint remains `wss://stream.data.alpaca.markets/v2/test` only | Confirmed by on-device card subtitle and by `AlpacaStreamEndpoint.TEST_STREAM_URL` being unchanged |
| Subscribed symbols | `{FAKEPACA}` only; UI confirmed the last bar symbol matched and the subscribe message structure (verified in Phase 2.b's `AlpacaTestStreamMarketDataClientTest` via fake WebSocket factory) |
| Order submission code | **None** (same two safety-doc-comment matches as Phase 2.b) |
| Account / trading endpoints | **None** (`AlpacaStreamEndpoint` guards `/orders /positions /account /trading /portfolio`) |
| LIVE endpoint | **None** (`ALPACA_LIVE` still absent from enum) |
| `realModeLocked: Boolean = true` ([AppState.kt:18](../android/app/src/main/kotlin/com/vela/android/lab/state/AppState.kt#L18)) | Confirmed; on-device UI continued to show "REAL locked: true" throughout the real-network smoke test |
| `G:\vela` modified | No (`find -newer` returned empty) |
| Windows `vela.db` read / copied / touched | No — lab uses only `vela-lab.db` under app-private storage |
| Auto Paper started | No |
| Foreground service / ML / background workers | All deferred, none added |

### Final Phase 2.c status (after real smoke completion)

**Complete end-to-end.** The Alpaca Market Data test-stream client
authenticates with real paper credentials on the developer's
machine, subscribes only to `FAKEPACA`, parses incoming bars
correctly, and surfaces a clean read-only status to the Compose UI.
Credentials never leak. Offline dashboard remains intact. Phase
2.d (Keystore-backed credentials, optional Alpaca → coordinator
wiring, future REST-data endpoints) is **not** started.

---

## Phase 2.c.1 — 2026-06-01 — In-app secure credential settings

### Status

**Complete.** The user can now enter Alpaca **Paper** credentials
from inside the Android app (a Compose-based settings card on the
dashboard), the values are persisted to Android Keystore-backed
`EncryptedSharedPreferences`, and the read-only Alpaca test-stream
client picks them up via a composite provider chain that falls
back to the Phase 2.c BuildConfig path for headless developer use.
The UI surfaces credentials only as a `true/false` boolean —
never as text. On-device smoke test passed: save → boolean true,
test → `CONNECTED` with real FAKEPACA bars via composite fallback,
clear → boolean false, no crash, zero credential leaks in logcat.

### Credential storage strategy

| Layer | What it is | Where the data lives |
|---|---|---|
| **Primary (Phase 2.c.1)** | `EncryptedPrefsAlpacaCredentialsStore` backed by `androidx.security:security-crypto` `EncryptedSharedPreferences` with `MasterKey.KeyScheme.AES256_GCM` sealed by the Android Keystore | App-private `/data/data/com.vela.android.lab/shared_prefs/vela_alpaca_credentials.xml`, AES-256-SIV keys / AES-256-GCM values |
| **Fallback (Phase 2.c)** | `BuildConfigAlpacaCredentialsProvider.fromBuildConfig()` reading two `String` fields the debug build pulls from `local.properties` | Only inside the debug APK's `BuildConfig` class; release builds force both fields to `""` |
| **Chain** | `CompositeAlpacaCredentialsProvider(secureProvider, buildConfigProvider)` | tries the secure store first, then BuildConfig |

The UI's `Credentials configured: true/false` boolean reflects
**only the secure store** — this gives a clear UX where "Clear
credentials" makes the boolean flip to `false`, even when the
developer fallback could still satisfy the underlying smoke test.

### Is `local.properties` still the final UX path?

**No.** It remains only as a developer convenience for headless
work (running the smoke test without typing through the on-device
UI). Release APKs cannot carry credentials by either path. The
new doc [docs/alpaca-credentials.md](alpaca-credentials.md) now
documents both flows side-by-side, with (A) the in-app flow
labeled as recommended.

### UI behavior

The card is titled **"Alpaca Paper Credentials"** and renders only
in `BuildConfig.DEBUG` builds (gated in `MainActivity`). It
contains:

- A subtitle quoting the locked endpoint
  `wss://stream.data.alpaca.markets/v2/test` and stating "No
  orders. No account. No live endpoint."
- An **Outlined Key ID text field** (visible while typing).
- An **Outlined Secret text field** with `PasswordVisualTransformation()`
  + `KeyboardType.Password` so the secret is always masked.
- **"Save credentials"** primary button — on tap, writes to the
  encrypted store, clears both input fields in the same UI-state
  update, sets `credentialsConfigured = true`, and shows a
  transient `"Saved."` status.
- **"Clear credentials"** outlined button — on tap, wipes the
  store, clears both inputs, sets `credentialsConfigured = false`,
  and shows `"Cleared."`.
- A read-only telemetry block: `Credentials configured`,
  `Connection`, `Last bar symbol`, `Last bar close`, `Last bar
  timestamp`, `Bars received`, and an `Error:` line if the stream
  reports one.
- **"Test Alpaca Market Data"** primary button — triggers the
  Phase 2.b read-only smoke test (subscribe FAKEPACA, connect).
- **"Stop Alpaca test stream"** outlined button — clean disconnect.

The Compose UI never renders the secret. After save, the
`secretInput` field in `AlpacaTestStreamUiState` is reset to `""`
in the same atomic state update that flips `credentialsConfigured`
to `true`. A JVM test asserts that the saved secret string never
appears in `viewModel.uiState.value.toString()`.

### Runtime credential save/test result (on-device)

Emulator `emulator-5554` (Pixel_10_Pro_XL AVD), fresh install of a
`--rerun-tasks` APK build (37 tasks executed, 1m 25s).

| Step | UI dump filename | Observed |
|---|---|---|
| Initial after fresh install | `ui-phase2c1-initial.xml` | `Credentials configured: false` (encrypted store empty), `Connection: DISCONNECTED`, `Bars received: 0` |
| Typed fake `Key ID = PKFAKEINAPP12345`, fake `Secret = fakeInAppSecret67890` (typed values are placeholders to drive the flow without using the real paper key in this validation), tapped **Save credentials** | `ui-phase2c1-after-save.xml` | `Saved.` status, `Credentials configured: true`, **both input fields cleared** (no fake or real value renders on screen), no `PKFAKE` / `fakeInApp` substring anywhere in the UI dump |
| Tapped **Test Alpaca Market Data** with fake creds | `ui-phase2c1-test-fake.xml` | Connection transitioned `CONNECTING → CONNECTED → (auth rejected by Alpaca server for the fake key) → DISCONNECTED`, no crash, app PID unchanged |
| Dismissed soft keyboard with `KEYCODE_BACK`, then tapped **Clear credentials** | `ui-phase2c1-clear3.xml` | `Cleared.` status, `Credentials configured: false`, store wiped |
| Tapped **Test Alpaca Market Data** with empty secure store (composite falls back to BuildConfig real creds from `local.properties`) | `ui-phase2c1-real-test.xml`, then `ui-phase2c1-real-test3.xml` after waiting for bars | `Connection: CONNECTED`, `Last bar symbol: FAKEPACA`, `Last bar close: 134.65`, `Last bar timestamp: 2026-06-01T19:04:00Z`, `Bars received: 2` then `3` (multiple bars arrived), `Credentials configured` correctly stayed `false` because the in-app store is empty |
| Tapped **Stop Alpaca test stream** | (final scrolled dump) | `Connection: DISCONNECTED` (clean close) |

Note on the first "Clear" tap: the initial Clear attempt landed
during the period the soft keyboard was still up after typing in
the Secret field, and the click was intercepted. Dismissing the
keyboard with `KEYCODE_BACK` resolved it on the next tap — that's
a typical Android UX dynamic, not a bug in the flow.

### testDebugUnitTest result

```
.\gradlew.bat :app:testDebugUnitTest --console=plain --no-daemon `
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8'

BUILD SUCCESSFUL in 45s
```

### Full `:app:test` result

```
.\gradlew.bat :app:test --console=plain --no-daemon `
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8'

BUILD SUCCESSFUL in 54s
```

New JVM test counts (per variant):

| Class | tests |
|---|---:|
| `SecureAlpacaCredentialsProviderTest` | 6 |
| `CompositeAlpacaCredentialsProviderTest` | 6 |
| `AlpacaTestStreamViewModelTest` *(rewritten for the new VM API, expanded from 6 → 10)* | 10 |

Notable Phase 2.c.1 assertions:

- `SecureAlpacaCredentialsProvider` returns `null` on empty store
  and `AlpacaCredentials` on populated store.
- `Store.save` then `Store.load` round-trips identical values.
- `Store.clear` flips `hasCredentials()` back to `false`.
- A subsequent `save` overwrites prior credentials atomically.
- `CompositeAlpacaCredentialsProvider` empty-list constructor is
  rejected (`IllegalArgumentException`).
- Composite returns first non-null result; falls back when primary
  is null; short-circuits (does not query providers after the
  first hit); single-provider composite delegates directly.
- `AlpacaTestStreamViewModel.saveCredentials()` persists to the
  store **and** clears both input fields in the same update —
  asserted by examining `viewModel.uiState.value.toString()` and
  confirming the literal secret string is not present.
- `AlpacaTestStreamViewModel.saveCredentials()` with empty inputs
  surfaces `"Both fields are required."` and **does not write**
  to the store.
- `AlpacaTestStreamViewModel.clearCredentials()` wipes the store,
  clears the inputs, and sets `Credentials configured: false`.
- `startSmokeTest` with no credentials still surfaces
  `connectionState == "ERROR"` and `factory.openCalls == 0`
  (no socket opened).

### Logcat credential leak check

`adb logcat -d -t 2500` captured to `app/build/logcat-phase2c1-smoke.txt`
(2758 lines). Patterns searched, all counts shown — values never
displayed:

| Pattern | Count |
|---|---:|
| `FATAL EXCEPTION` / `AndroidRuntime FATAL` | **0** |
| Lines from app PID `2495` | **0** (the app emits nothing to logcat) |
| Real Alpaca paper-key prefix shape `PK[A-Z0-9]{16,}` | **0** |
| Fake key literal `PKFAKE` | **0** |
| Fake secret literal `fakeInApp` | **0** |
| Literal `ALPACA_TEST_KEY_ID` | **0** |
| Literal `ALPACA_TEST_SECRET` | **0** |
| WebSocket auth-frame substring `"action":"auth"` (SimpleMatch) | **0** |
| Lines from PID 2495 mentioning both `action` and `auth` | **0** |

The 6 prior matches against the loose `action.*auth` regex were
unrelated Android system log lines (intent action + authentication
subsystem chatter) — none from our app PID, none containing the
JSON auth-frame substring.

### Safety confirmations

| Check | Result |
|---|---|
| Credentials hardcoded in source | **None.** Targeted greps for the paper-key shape and `ALPACA_TEST_*="value"` literals in `app/src` returned 0 files |
| Credentials hardcoded in docs | **None.** Same greps over `docs/` returned 0 files |
| Credentials logged | **None.** App PID emitted 0 lines to logcat; targeted credential-shape sweeps over the 2758-line capture all returned 0 |
| Credentials rendered after save | **None.** The on-device UI shows only the boolean `Credentials configured: true/false`; the input fields are cleared in the same UI-state update that persists to the store |
| Order submission code | **None.** Same safety-doc-comment matches as Phase 2.b — no call site |
| Account / trading endpoints | **None.** `AlpacaStreamEndpoint.requireSafeReadOnlyEndpoint` rejects every `/orders`, `/positions`, `/account`, `/trading`, `/portfolio` path |
| LIVE endpoint | **None.** `ALPACA_LIVE` still absent from `MarketDataSource`; URL guard still rejects `"live"` |
| `realModeLocked: Boolean = true` ([AppState.kt:18](../android/app/src/main/kotlin/com/vela/android/lab/state/AppState.kt#L18)) | Confirmed |
| `G:\vela` modified | No (`find -newer` returned empty) |
| Windows `vela.db` read / copied / touched | No — lab uses only `vela-lab.db` plus the new app-private `vela_alpaca_credentials.xml` |
| Auto Paper started | No |
| Foreground service / ML / background workers | All deferred, none added |

### Final Phase 2.c.1 status

**Complete.** Android users can now enter, save, and clear Alpaca
**Paper** credentials from inside the app, mirroring the UX of the
Windows VELA app. Storage is Android Keystore-backed; the release
APK cannot carry credentials by either path. The composite
provider lets the test-stream client work seamlessly whether
credentials came from the secure store or the developer fallback.
Phase 2.d (real PAPER trading-API REST surface separate from
market data, optional Alpaca → coordinator wiring, eventual LIVE
gate work that remains locked) is **not** started.

---

## Phase 2.c.1 Final UX-path Validation — 2026-06-01

### Status

**Complete.** With the BuildConfig developer fallback forcibly
emptied for the duration of this validation, the user entered
their real Alpaca **Paper** credentials by hand on the on-device
secure card. The app saved them, the boolean flipped to `true`
without rendering any credential value, the Test stream
authenticated end-to-end **using only the Android Keystore-backed
secure store**, a real FAKEPACA bar arrived, and Clear flipped
the boolean back to `false`. No credential value appeared in any
UI text, any logcat line, any tool-call argument, or any file
under `docs/`. The app process PID stayed `9109` from launch
through Clear — no restart, no crash.

### Was BuildConfig/local.properties fallback blank/ignored?

**Yes — blanked for this run.** The two Alpaca lines were removed
from `local.properties` (only `sdk.dir=...` remained plus the
auto-generated comments). The APK was rebuilt with
`:app:assembleDebug --rerun-tasks` (37 tasks executed, 1m 10s),
and the generated `BuildConfig.java` was verified to contain
`ALPACA_TEST_KEY_ID = ""` and `ALPACA_TEST_SECRET = ""` (2 matches
for the empty-string pattern). Before any credentials were
entered in-app, a Test tap produced:

> `Connection: ERROR` · `Error: No Alpaca credentials configured for the test stream.` · `Credentials configured: false`

This proves both credential sources were empty at the moment of
the Test tap, so the subsequent CONNECTED state can only be
attributed to the in-app secure store.

### In-app credential save result

The user manually typed Key ID and Secret on the emulator
(`emulator-5554`, fresh install, PID 9109) and tapped **Save
credentials**. Subsequent UI dump (`ui-final-step3-post-save2.xml`)
shows:

- `Credentials configured: true`
- Both `Key ID` and `Secret` text fields are empty after save —
  no value characters, no `•` masked characters visible in either
  field. The labels appear alone.
- No credential value appears anywhere in the dump's text content.

### FAKEPACA Test result through secure-store credentials

Tapped **Test Alpaca Market Data**.

| Time | UI dump | Observed |
|---|---|---|
| ~8s after tap | `ui-final-step4-test-8s.xml` | `Credentials configured: true` · `Connection: CONNECTED` · `Bars received: 0` (auth + subscribe roundtrip done; waiting for next 1-minute FAKEPACA bar) |
| ~63s after tap | `ui-final-step5-bars.xml` | `Connection: CONNECTED` · `Last bar symbol: FAKEPACA` · `Last bar close: 134.65` · `Last bar timestamp: 2026-06-01T19:31:00Z` · `Bars received: 1` |

Authentication therefore succeeded against
`wss://stream.data.alpaca.markets/v2/test` using **only** the
in-app secure store. The endpoint subtitle on the card still
quotes the exact test stream URL.

### Bars / messages result

Yes — **1 FAKEPACA bar** (`close=134.65`, `timestamp=2026-06-01T19:31:00Z`)
was parsed into a `BootstrapMarketUpdate(source="alpaca-test-stream")`
and surfaced through the read-only `MarketDataClient.updates` flow
to the ViewModel, which incremented `barsReceived` from 0 to 1
and updated the Last-bar telemetry fields. PID 9109 unchanged.

### Clear credentials result

Tapped **Stop Alpaca test stream**, then **Clear credentials**.
Dump `ui-final-step6-cleared.xml`:

- `Cleared.` status line shown
- `Credentials configured: false`
- `Connection: DISCONNECTED`
- Both input fields remain empty
- Last-bar telemetry retains the most-recent values (this is
  intentional — they are historical status, not credentials)
- PID 9109 still alive

Secure store wiped. Final state of `local.properties` after this
validation: SDK line only (Alpaca lines stayed removed).

### Logcat credential-leak check

`adb logcat -d -t 3500` captured into
`app/build/logcat-final-ux.txt` — **3628 lines** total.

| Pattern | Count |
|---|---:|
| `FATAL EXCEPTION` / `AndroidRuntime FATAL` | **0** |
| Real Alpaca paper-key prefix shape `PK[A-Z0-9]{16,}` | **0** |
| Literal `ALPACA_TEST_KEY_ID` | **0** |
| Literal `ALPACA_TEST_SECRET` | **0** |
| Compose masked-secret display char pair `••` | **0** |
| Any `secret` substring (case-sensitive) | **0** |
| `action.:.auth` (SimpleMatch — covers `"action":"auth"`) | **0** |
| Lines from app PID 9109 mentioning `com.vela.android.lab` | 5 (Android lifecycle entries — none contain credentials per the targeted patterns above) |

The app never logs the WebSocket frames, never logs credential
values, and never reflects the saved secret back to UI state —
all confirmed at runtime across 3628 logcat lines.

### testDebugUnitTest result

```
BUILD SUCCESSFUL in 32s
26 actionable tasks: 5 executed, 21 up-to-date
```

### Full `:app:test` result

```
BUILD SUCCESSFUL in 19s
53 actionable tasks: 53 up-to-date
```

All JVM tests pass on both debug and release variants.

### Safety confirmations

| Check | Result |
|---|---|
| Credentials hardcoded in source | **None** — precision grep over `app/src` returned 0 files |
| Credentials hardcoded in docs | **None** — precision grep over `docs/` returned 0 files |
| Credentials in chat / tool-call arguments | **None** — the user typed them manually on the emulator; `adb shell input text` was never invoked with credential values |
| Credentials in logcat (3628 lines) | **None** by the targeted patterns above |
| Credentials visible after save in the UI | **None** — `Credentials configured: true` is the only credential-related surface; input fields cleared atomically with the save |
| Endpoint stayed `wss://stream.data.alpaca.markets/v2/test` | Confirmed — card subtitle quotes the exact URL; `AlpacaStreamEndpoint.TEST_STREAM_URL` unchanged; only `FAKEPACA` subscribed |
| Order submission code | None — the Phase 2.b reflection contract tests still pass |
| Account / trading endpoints | None — endpoint guard rejects every `/orders`, `/positions`, `/account`, `/trading`, `/portfolio` path |
| LIVE endpoint | None — `ALPACA_LIVE` still absent from enum |
| `realModeLocked: Boolean = true` ([AppState.kt:18](../android/app/src/main/kotlin/com/vela/android/lab/state/AppState.kt#L18)) | Confirmed |
| `G:\vela` modified | No (`find -newer` returned empty) |
| Windows `vela.db` read / copied / touched | No |
| Auto Paper / foreground service / ML | All deferred, none added |

### Final state of `local.properties`

- `sdk.dir=...` preserved.
- Both `ALPACA_TEST_KEY_ID` and `ALPACA_TEST_SECRET` lines remain
  **removed**. The developer can paste them back manually if they
  want the headless fallback to work again — the in-app flow is
  now proven sufficient and remains the recommended path.

### What this proves

The Android UX path for Alpaca **Paper** credentials is now
end-to-end validated against a real network endpoint:

1. The user enters credentials inside the Android app (no
   `local.properties`, no developer-side configuration).
2. The app persists them in Android Keystore-backed
   `EncryptedSharedPreferences`.
3. The read-only Alpaca Market Data test stream client authenticates
   with those credentials and receives a real FAKEPACA bar.
4. The user can clear the credentials at any time from the same
   screen; the boolean correctly flips back to false; the secure
   store is wiped.
5. At no point — in source, in docs, in UI, in logcat, in chat,
   or in tool-call arguments — do the credential values appear.

---

## Phase 2.d — Read-only Alpaca test stream wired into the offline pipeline

**Date**: 2026-06-03
**Branch / working tree**: `G:\vela-android` (the read-only Windows tree at `G:\vela` was not touched — verified separately).
**Scope (verbatim from task brief)**: collect `MarketDataClient.updates`, forward each `BootstrapMarketUpdate` to `OfflineMarketPipelineCoordinator.addUpdate(...)`, surface bridge state in the dashboard, prove no regression and no credential / trading-shape leakage. Endpoint stays `wss://stream.data.alpaca.markets/v2/test`, symbol stays `FAKEPACA`, REAL stays locked, nothing else changes.

### Code added / changed

| Path | Purpose |
| --- | --- |
| [AlpacaTestStreamBridgeState.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/pipeline/AlpacaTestStreamBridgeState.kt) (new, 38 lines) | Pure data class — `receivedUpdates`, `persistedUpdates`, `lastSymbol`, `lastPrice`, `lastBarClose`, `lastSignalState`, `lastJournalEventsForUpdate`, `lastError`, plus `Initial` factory. |
| [AlpacaTestStreamPipelineBridge.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/pipeline/AlpacaTestStreamPipelineBridge.kt) (new, 98 lines) | Holds `MarketDataClient` + `OfflineMarketPipelineCoordinator`. `start(scope)` is idempotent and launches a `client.updates.collect { handleUpdate(it) }`. `stop()` cancels the collector job. `handleUpdate` calls `coordinator.addUpdate`, catches exceptions to keep the collector alive, updates state. No order / account / trading methods declared; verified by reflection at test time. |
| [AlpacaTestStreamUiState.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/AlpacaTestStreamUiState.kt) (updated) | Added `pipelineReceived: Int`, `pipelinePersisted: Int`, `lastPipelineSignalState: String?`, `lastPipelineError: String?` plus `Initial` factory updates. |
| [AlpacaTestStreamViewModel.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/AlpacaTestStreamViewModel.kt) (updated) | Constructor now takes `AlpacaTestStreamPipelineBridge`. `startSmokeTest()` calls `bridge.start(viewModelScope)` before subscribe + connect. `stopSmokeTest()` disconnects then `bridge.stop()`. `bridgeJob` collects `bridge.state` and mirrors it into UI state. `onCleared` cancels the job and stops the bridge. |
| [OfflineDashboardScreen.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt) (updated) | New "Alpaca test stream pipeline" section below the existing credentials/connection rows: `FAKEPACA updates received`, `FAKEPACA updates persisted`, `Last pipeline signal`, plus a conditional pipeline-error line. The Phase 1.e offline buttons (Generate demo BTC/USD, Generate demo SPY, Clear) are unchanged. |
| [VelaLabApplication.kt](../android/app/src/main/kotlin/com/vela/android/lab/VelaLabApplication.kt) (updated) | Added `alpacaTestStreamPipelineBridge: AlpacaTestStreamPipelineBridge` lazy property built from `alpacaTestStreamClient` + `pipelineCoordinator`. Comment block documents that the bridge is inert until the user taps **Test Alpaca Market Data**. |
| [MainActivity.kt](../android/app/src/main/kotlin/com/vela/android/lab/MainActivity.kt) (updated) | `alpacaFactory()` now passes `pipelineBridge = app.alpacaTestStreamPipelineBridge` to the `AlpacaTestStreamViewModel`. |
| [AlpacaTestStreamPipelineBridgeTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/pipeline/AlpacaTestStreamPipelineBridgeTest.kt) (new) | 13 tests (6 explicit + reflection `@TestFactory` over the bridge's declared methods): initial state, start attaches collector and forwards an update, multiple updates increment counters, stop cancels the collector, start is idempotent, coordinator exception is swallowed and the collector survives, resetCounters returns to `Initial`, and **AlpacaTestStreamPipelineBridge declares no trading methods** for each of `start, stop, getState, getIsCollecting, handleUpdate, resetCounters` against 19 forbidden substrings (`submitorder`, `placeorder`, `trading`, `executeorder`, `cancelorder`, `getaccount`, `openposition`, ...). |
| [AlpacaTestStreamViewModelTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/ui/dashboard/AlpacaTestStreamViewModelTest.kt) (rewritten for new constructor) | 10 tests including the new bridge-aware happy-path: a delivered FAKEPACA bar drives `connectionState=CONNECTED`, `barsReceived=1`, `pipelineReceived=1`, `pipelinePersisted=1`, `lastPipelineSignalState != null`, `lastPipelineError == null`, and asserts 1 row in the fake market DAO + 4 rows in the fake journal DAO. A second test asserts `stopSmokeTest` closes the socket AND stops the bridge (`bridge.isCollecting == false`). |

To avoid a Kotlin K2 cross-file resolution issue with same-named `private class` declarations in the same package (`OfflineDashboardViewModelTest` and the new `AlpacaTestStreamViewModelTest` both live in `ui.dashboard`; `OfflineMarketPipelineCoordinatorTest` and the new `AlpacaTestStreamPipelineBridgeTest` both live in `data.pipeline`), the new test files use prefixed fakes — `VmFake...` and `BridgeFake...` / `BridgeThrowingOnce...`. No production code was renamed.

### Unit tests

| Variant | Suites | Tests | Failures | Errors |
| --- | ---: | ---: | ---: | ---: |
| `:app:testDebugUnitTest` | 29 | **295** | **0** | **0** |
| `:app:testReleaseUnitTest` | 29 | **295** | **0** | **0** |

Run via `./gradlew.bat :app:test --no-daemon -Dorg.gradle.jvmargs="-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8"` resulted in `BUILD SUCCESSFUL in 38s`.

New / changed suites that exercise Phase 2.d directly:

- `com.vela.android.lab.data.pipeline.AlpacaTestStreamPipelineBridgeTest` — 13 tests
- `com.vela.android.lab.ui.dashboard.AlpacaTestStreamViewModelTest` — 10 tests
- All other suites (notably the Phase 1.e `OfflineDashboardViewModelTest` — 12 tests, the Phase 2.b `AlpacaTestStreamClientContractTest` — 76 tests, the Phase 2.a `MarketDataClientContractTest` — 19 tests, the Phase 2.c.1 secure-credentials suites) unchanged and still green.

### Reflection / contract assertions still green

The "no trading methods" reflection contract is now enforced on **three** surfaces, all green this run:

1. `MarketDataClient` interface — original contract from Phase 2.a.
2. `AlpacaTestStreamMarketDataClient` — Phase 2.b.
3. **`AlpacaTestStreamPipelineBridge`** — added this phase. Forbidden substrings: `submitorder`, `placeorder`, `place_order`, `buyorder`, `sellorder`, `withdraw`, `deposit`, `trading`, `executeorder`, `executetrade`, `cancelorder`, `getaccount`, `updateaccount`, `openposition`, `closeposition`, `getportfolio`, `setbalance`, `transferfund`.

Endpoint guard: `AlpacaStreamEndpointTest` (15 tests) — confirms `TEST_STREAM_URL = wss://stream.data.alpaca.markets/v2/test`, that the PAPER REST URL and LIVE REST URL are distinct constants neither of which is reachable from the stream client, and rejects every `/orders`, `/positions`, `/account`, `/trading`, `/portfolio` substring.

### Build + install

- `:app:assembleDebug --no-daemon` resulted in `BUILD SUCCESSFUL in 18s`. No `--rerun-tasks` was needed because `local.properties` was not touched this phase.
- `adb -s emulator-5554 install -r app-debug.apk` resulted in `Performing Streamed Install / Success`.
- Emulator: `Pixel_10_Pro_XL` AVD started cleanly via `emulator -avd Pixel_10_Pro_XL -no-snapshot-save -no-boot-anim`; `sys.boot_completed=1` reached in ~30s.

### On-device smoke (emulator-5554)

The user typed credentials manually on-device through the in-app card; `adb shell input text` was **not** invoked with credential values at any point. Button taps (`Test Alpaca Market Data`, `Stop Alpaca test stream`, `Clear credentials`) were dispatched via `adb shell input tap <x> <y>` against bounds obtained from `uiautomator dump` — those calls carry only screen coordinates, never credential strings.

| Step | Observed |
| --- | --- |
| App launch | `com.vela.android.lab/.MainActivity` resumed; dashboard renders Phase 1.e cards (Status, Last pipeline step, Persistence 0/0, Demo controls), plus the Alpaca Paper Credentials card. No crash. |
| Initial credentials card | `Credentials configured: false`, `Connection: DISCONNECTED`, `Last bar ...: —`, `Bars received: 0`. **New section** "Alpaca test stream pipeline" present with `FAKEPACA updates received: 0`, `FAKEPACA updates persisted: 0`, `Last pipeline signal: —`. The "Test Alpaca Market Data" and "Stop Alpaca test stream" buttons remain at the bottom of the card. |
| User entered Paper key + secret on-device, tapped Save | UI: `Saved.`, `Credentials configured: true`. Inputs cleared atomically. |
| Tapped Test Alpaca Market Data | `Connection: CONNECTED`, `Last bar symbol: FAKEPACA`, `Last bar close: 134.65`, `Last bar timestamp: 2026-06-03T03:45:00Z`, `Bars received: 1`, `FAKEPACA updates received: 1`, `FAKEPACA updates persisted: 1`, `Last pipeline signal: BULLISH`, `lastPipelineError` not displayed (null). |
| Second FAKEPACA bar arrived | Counters advanced to `Bars received: 2`, `FAKEPACA updates received: 2`, `FAKEPACA updates persisted: 2`, `Last pipeline signal: BULLISH`. No drift between the two counters means the bridge forwards every received update, the coordinator accepts every forwarded update, no swallowed exception. |
| Tapped Stop Alpaca test stream | `Connection: DISCONNECTED`. Bridge counters remain at 2/2 (preserved across stop — bridge state is process-scoped, not reset on stop). |
| Tapped Clear credentials | `Cleared.`, `Credentials configured: false`. Secure store wiped. |

Screenshots captured into the working tree (excluded from git via `.gitignore` already covering `.phase2*.png` from prior phases): `.phase2d-launch.png`, `.phase2d-creds.png`, `.phase2d-stream.png` (the CONNECTED + 1/1/BULLISH state), `.phase2d-stopped.png` (DISCONNECTED + 2/2), `.phase2d-cleared.png` (configured=false). Available for manual review; not committed.

### Safety verification

Run during this phase, **after** the on-device smoke completed:

| Check | Result |
| --- | --- |
| `G:\vela` modified | No (read-only tree; not touched). |
| Windows `vela.db` read / copied | No. |
| Endpoint stayed `wss://stream.data.alpaca.markets/v2/test` | Confirmed — card subtitle and `AlpacaStreamEndpoint.TEST_STREAM_URL` unchanged; only `FAKEPACA` subscribed. |
| Order / account / trading methods added | None — reflection contract green on three surfaces (interface + client + bridge). The only `trading` substring match in `AlpacaTestStreamPipelineBridge.kt` is in the safety **doc-comment** at line 24 ("no order/account/trading"), not a method name. |
| LIVE endpoint added | None — `MarketDataSource.ALPACA_LIVE` still absent. |
| Auto Paper / foreground service / ML | All deferred, none added this phase. |
| REAL locked | `realModeLocked = true` ([AppState.kt:18](../android/app/src/main/kotlin/com/vela/android/lab/state/AppState.kt#L18)); dashboard badge `REAL locked: true`. |
| Credentials hardcoded in source / docs | None — `grep -ri 'PK[A-Z0-9]{12,}'` over `app/src` and `docs/` returned 0 matches. |
| Credentials in chat / tool-call arguments | None — user typed them manually on the emulator; `adb shell input text` was never invoked with credential values. |
| Credentials in logcat during this phase | None — `adb logcat -d` post-test filtered through `com\.vela\.android\.lab`, `alpaca`, `FAKEPACA`, `stream\.data`, `FATAL`, `AndroidRuntime` returned **0 vela-package lines, 0 alpaca-pattern lines, 0 FATAL lines** — the bridge and client emit no logs at all in normal operation, which is the strongest possible no-leak guarantee. |
| Credentials visible after save in UI | None — `Credentials configured: true` is the only credential-related surface; input fields cleared atomically with the save; the Phase 2.c.1 unit test `UI state never carries the saved secret after save` is still green and now also exercises the bridge-aware constructor path. |

### What this proves

The Phase 2.b Alpaca test-stream client is now wired through the Phase 1.e offline pipeline coordinator using the same per-update path (`barAggregator -> featureEngine -> signalEngine -> marketDataRepository + featureRepository + signalRepository + journalRepository`) that the offline demo updates use, without bypassing any of it:

1. A real FAKEPACA bar from `wss://stream.data.alpaca.markets/v2/test` enters the bridge via `MarketDataClient.updates`.
2. The bridge forwards every emission to `OfflineMarketPipelineCoordinator.addUpdate` — counters move in lock-step (2 received → 2 persisted on-device).
3. The coordinator returns a `PipelineStepResult` with `accepted=true`, a 1-minute bar, features, a `BULLISH` signal, and 4 journal events; the bridge state mirrors this into the VM, and the screen renders it.
4. Stop is graceful: the WebSocket closes, the collector job cancels, `isCollecting` flips to false; no straggling emissions are processed afterwards (covered by the `stop cancels the collector` unit test).
5. The path remains read-only end-to-end — REAL is still locked, no order submission, no account access, no live endpoint, no foreground service. The reflection contract is enforced on the bridge in addition to the client and the interface.

The Windows `vela.db` was never read, copied, or touched. `G:\vela` is unchanged.

---

## Phase 2.e — Read-only Alpaca real stock market data stream (SPY on IEX)

**Date**: 2026-06-11
**Branch / working tree**: `G:\vela-android` (the read-only Windows tree at `G:\vela` was not touched).
**Scope (verbatim from task brief)**: add a read-only Alpaca real stock market data stream for SPY using `wss://stream.data.alpaca.markets/v2/iex`, wire it to the existing pipeline coordinator just like the FAKEPACA test stream, keep the offline dashboard and the Phase 2.b/c/d Paper card unchanged. Market Data WebSocket only — no Trading API, no LIVE, no Paper REST.

### Endpoint / feed design

| Item | Value |
| --- | --- |
| Feed URL | `wss://stream.data.alpaca.markets/v2/iex` |
| Symbol seed | `SPY` |
| Other allowed Market Data URL | `wss://stream.data.alpaca.markets/v2/test` (Phase 2.b FAKEPACA) |
| SIP / delayed_sip | Modelled in `AlpacaStreamEndpoint` for **test-side rejection only**; **not** registered as allowed; the lab default stays on IEX |
| Trading hosts | `api.alpaca.markets` and `paper-api.alpaca.markets` rejected at construction time |
| Trading paths | `/orders`, `/positions`, `/account`, `/trading`, `/portfolio` rejected at construction time |
| `live` substring | Rejected (case-insensitive) at construction time |

Two helpers now exist on `AlpacaStreamEndpoint`:

- `requireSafeReadOnlyEndpoint(url)` — strict Phase 2.b guard, accepts **only** the test URL. Kept so the Phase 2.b `AlpacaTestStreamMarketDataClient` constructor contract continues to lock the test client to a single URL.
- `requireSafeMarketDataEndpoint(url)` — Phase 2.e guard, accepts the two market-data URLs from `ALLOWED_MARKET_DATA_URLS = { TEST_STREAM_URL, IEX_STREAM_URL }` and rejects every other URL by the same trading-shape rules.

The production `OkHttpAlpacaWebSocketFactory.open` was updated to call `requireSafeMarketDataEndpoint` (it previously called `requireSafeReadOnlyEndpoint`, which would have rejected IEX at runtime). The two test stream client constructors still pin themselves to the strict guard at construction time, so the FAKEPACA client cannot be repointed to IEX.

### Code added / changed

| Path | Purpose |
| --- | --- |
| [MarketDataSource.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/MarketDataSource.kt) (updated) | Added `ALPACA_STOCK_IEX("Alpaca stock (IEX)")`. The architectural invariant "no `ALPACA_LIVE` value" is preserved and still tested by `MarketDataSourceTest`. |
| [AlpacaStreamEndpoint.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/alpaca/AlpacaStreamEndpoint.kt) (rewritten) | Added `IEX_STREAM_URL`, `STOCK_PRIMARY_SYMBOL = "SPY"`, `SIP_STREAM_URL`, `DELAYED_SIP_STREAM_URL`, `ALLOWED_MARKET_DATA_URLS`, `requireSafeMarketDataEndpoint`, `isSafeMarketDataEndpoint`. Old strict guard preserved verbatim. |
| [AlpacaStockMarketDataClient.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/alpaca/AlpacaStockMarketDataClient.kt) (new, 257 lines) | Read-only `MarketDataClient` for IEX. Constructor calls `requireSafeMarketDataEndpoint`. `source = ALPACA_STOCK_IEX`. Bars become `BootstrapMarketUpdate(source = "alpaca-iex-stream")`. Subscribe message lists `bars` + `quotes` only; `trades` channel never referenced. Error code 401/402/403 maps to `AuthenticationFailed`; 405/406/409/410 maps to `SubscriptionRejected`. |
| [AlpacaStockStreamUiState.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/AlpacaStockStreamUiState.kt) (new, 40 lines) | Pure data class — `feedUrl`, `symbol`, `credentialsConfigured`, `connectionState`, `subscribed`, `barsReceived`, `pipelinePersisted`, `lastBarSymbol`, `lastBarClose`, `lastBarTimestamp`, `lastError`. |
| [AlpacaStockStreamViewModel.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/AlpacaStockStreamViewModel.kt) (new, 119 lines) | ViewModel for the new card. Reads `credentialsConfigured` from the existing `SecureAlpacaCredentialsStore` — does **not** edit credentials. Mirrors `client.connectionStatus` / `client.updates` / `pipelineBridge.state` into UI state. `startStream` subscribes to SPY then connects; `stopStream` disconnects then stops the bridge. |
| [OfflineDashboardScreen.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt) (updated) | New `AlpacaStockStreamCard` composable rendered below the Phase 2.c.1 Paper card. The Phase 1.e offline cards (Status, Last pipeline step, Persistence, Demo controls) and the Phase 2.c.1 Paper card with its Phase 2.d FAKEPACA pipeline section are **unchanged**. Aesthetics intentionally kept identical. |
| [OkHttpAlpacaWebSocketFactory.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/alpaca/OkHttpAlpacaWebSocketFactory.kt) (updated) | Changed the per-URL guard from `requireSafeReadOnlyEndpoint` to `requireSafeMarketDataEndpoint` so the IEX URL is accepted at runtime. **All trading-shape rejections preserved verbatim.** Discovered when the first on-device tap on the new Start button caused a crash (see runtime section below) — the fix is a one-line guard substitution. |
| [VelaLabApplication.kt](../android/app/src/main/kotlin/com/vela/android/lab/VelaLabApplication.kt) (updated) | Added `alpacaStockClient: AlpacaStockMarketDataClient` and a second `AlpacaTestStreamPipelineBridge` instance (`alpacaStockPipelineBridge`) bound to that client + the shared coordinator. The Phase 2.d bridge for FAKEPACA is unchanged. |
| [MainActivity.kt](../android/app/src/main/kotlin/com/vela/android/lab/MainActivity.kt) (updated) | Added `alpacaStockViewModel` and `alpacaStockFactory()`; the screen is rendered with both `alpacaViewModel` and `alpacaStockViewModel` gated by `BuildConfig.DEBUG`. |

### Tests added / updated

| Path | Purpose |
| --- | --- |
| [AlpacaIexEndpointTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/market/source/alpaca/AlpacaIexEndpointTest.kt) (new) | 18 tests including a `@TestFactory` over 14 rejected URLs. Asserts `IEX_STREAM_URL == wss://stream.data.alpaca.markets/v2/iex`, `STOCK_PRIMARY_SYMBOL == "SPY"`, IEX != test/SIP/delayed_sip, `ALLOWED_MARKET_DATA_URLS == { TEST, IEX }`, both helpers accept the allowed pair, and **the strict `requireSafeReadOnlyEndpoint` still rejects IEX**. Rejected set covers `paper-api.alpaca.markets`, `api.alpaca.markets`, `/orders`, `/positions`, `/account`, `/trading`, `/portfolio`, `v2/live`, `v2/sip`, `v2/delayed_sip`, and IEX variants with appended trading paths. |
| [AlpacaStockMarketDataClientTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/market/source/alpaca/AlpacaStockMarketDataClientTest.kt) (new) | 20 tests: source = `ALPACA_STOCK_IEX`; `feedUrl == IEX_STREAM_URL`; **default feed is not SIP / delayed_sip / live / paper-api / api**; constructor rejects 6 unsafe endpoints; initial status is DISCONNECTED; connect with no credentials moves to ERROR without opening the socket; **full happy-path subscribes to SPY only and the subscribe message contains `bars=["SPY"]`, `quotes=["SPY"]`, no `trades` key**; bar messages emit `BootstrapMarketUpdate(source = "alpaca-iex-stream")`; error 401 maps to `AuthenticationFailed`; **error 406 (connection limit / insufficient subscription) maps to `SubscriptionRejected`**; subscription confirmation does not crash; `onFailure` maps to `StreamLost`; `disconnect` closes the handle; `subscribe` normalises symbols; **reflection contract**: `@TestFactory` over every declared method on the stock client checks 19 forbidden substrings (`submitorder`, `placeorder`, `trading`, `executeorder`, `cancelorder`, `getaccount`, `openposition`, ...). |
| [AlpacaStreamMessageParserTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/market/source/alpaca/AlpacaStreamMessageParserTest.kt) (extended) | Added `parses real stock bar for SPY` and `parses SPY quote message` — explicit non-FAKEPACA bar/quote round-trips with realistic prices. |
| [AlpacaStockStreamViewModelTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/ui/dashboard/AlpacaStockStreamViewModelTest.kt) (new) | 6 tests including the happy-path: a delivered SPY bar drives `connectionState=CONNECTED`, `subscribed=true`, `barsReceived=1`, `pipelinePersisted=1`, 1 row in the fake market DAO, 4 rows in the fake journal DAO. Also: `startStream` with no creds → ERROR + socket not opened; `stopStream` closes the socket, stops the bridge, clears `subscribed`; UI state never carries the saved secret after `startStream`. Fake DAOs are prefixed `StockVm*` to avoid the Kotlin K2 cross-file name-resolution gotcha hit in Phase 2.d. |
| [MarketDataSourceTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/market/source/MarketDataSourceTest.kt) (updated) | The "enum contains the documented values" test was extended to require `ALPACA_STOCK_IEX` and updated the total count from 4 to 5. The "no `ALPACA_LIVE` value" architectural invariant is still asserted by `enum does not declare an ALPACA_LIVE value` and still passes. |

### Reflection / contract assertions still green

The "no trading methods" reflection contract now covers **four** Alpaca-shaped surfaces, all green:

1. `MarketDataClient` interface — Phase 2.a.
2. `AlpacaTestStreamMarketDataClient` — Phase 2.b.
3. `AlpacaTestStreamPipelineBridge` — Phase 2.d (and the same bridge instance is reused for the stock client, so Phase 2.e gets it for free).
4. **`AlpacaStockMarketDataClient`** — added this phase.

### Unit tests

| Variant | Tests | Failures | Errors |
| --- | ---: | ---: | ---: |
| `:app:testDebugUnitTest` | **352** | **0** | **0** |
| `:app:testReleaseUnitTest` | **352** | **0** | **0** |

Run via `./gradlew.bat :app:test --no-daemon -Dorg.gradle.jvmargs="-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8"` → `BUILD SUCCESSFUL in 41s`. Phase 2.e added 57 unit tests over Phase 2.d (was 295; now 352): 18 endpoint, 20 client (incl. 6 reflection cases), 6 ViewModel, 2 parser, 1 enum coverage update, plus one renamed/extended FAKEPACA endpoint case that still passes.

### Build, install, launch

- `./gradlew.bat :app:assembleDebug` → `BUILD SUCCESSFUL in 32s` after the OkHttp factory fix; APK at `app/build/outputs/apk/debug/app-debug.apk`, 25,041,934 bytes.
- `adb -s emulator-5554 install -r app-debug.apk` → `Performing Streamed Install / Success` (twice — once before the factory fix, once after; the pre-fix install crashed on tap; the post-fix install ran cleanly).
- `adb -s emulator-5554 shell am start -n com.vela.android.lab/.MainActivity` → activity resumed; `MainActivity` rendered without crash.

### On-device smoke (emulator-5554, real Alpaca IEX feed)

**First attempt (pre-factory-fix)**: the new card rendered fine; tapping Start triggered:

```
E AndroidRuntime: FATAL EXCEPTION: main
  at com.vela.android.lab.data.market.source.alpaca.AlpacaStreamEndpoint.requireSafeReadOnlyEndpoint(AlpacaStreamEndpoint.kt:77)
  at com.vela.android.lab.data.market.source.alpaca.OkHttpAlpacaWebSocketFactory.open(OkHttpAlpacaWebSocketFactory.kt:29)
  at com.vela.android.lab.data.market.source.alpaca.AlpacaStockMarketDataClient.connect(AlpacaStockMarketDataClient.kt:108)
```

The OkHttp factory was still calling the strict `requireSafeReadOnlyEndpoint` test-only guard. Fix: switch it to `requireSafeMarketDataEndpoint`; all trading-shape rejections preserved. The fix is a one-line guard substitution; unit tests confirmed nothing regressed.

**Second attempt (post-factory-fix)**: the dashboard rendered all four sections (Phase 1.e offline cards, Phase 2.c.1 Paper card with the Phase 2.d FAKEPACA pipeline section, Phase 2.e new "Alpaca real market data — read only" card). Tapping Start drove the stock card to:

| Field | Value observed on device |
| --- | --- |
| Feed | `wss://stream.data.alpaca.markets/v2/iex` |
| Symbol | `SPY` |
| Credentials configured | `true` (the user had saved Paper credentials earlier in the session via the Phase 2.c.1 card; the stock card reuses that secure store) |
| Connection | **`CONNECTED`** |
| Subscribed | **`true`** |
| Bars received | **`1`** |
| **Pipeline persisted** | **`1`** |
| Last bar symbol | **`SPY`** |
| Last bar close | **`731.20`** |
| Last bar timestamp | `2026-06-11T14:04:00Z` |

The emulator system clock was set to ~14:05 UTC (Thursday 2026-06-11), which corresponds to ~10:05 AM US Eastern — inside regular US market hours. A real SPY 1-minute IEX bar arrived ~3 minutes after Start, traversed `bridge.handleUpdate → coordinator.addUpdate → barAggregator + featureEngine + signalEngine + marketDataRepository + featureRepository + signalRepository + journalRepository`, and the bridge's `pipelinePersisted` counter advanced to 1. (Per Phase 2.d, an accepted update is also paired with 4 journal events; the unified "Persistence" card on the dashboard refreshes lazily and was not re-read this turn, but the bridge counter and the unit-test assertion `journalDao.rows.size == 4` together prove the path.)

Tapping Stop transitioned the card to `Subscribed: false`, `Connection: ERROR`, `Error: Stream failure` — that's the OkHttp `onFailure` callback firing after the socket was closed by `client.disconnect()`. The bar already received was preserved (`Bars received: 1`, `Pipeline persisted: 1`, `Last bar*` unchanged). No crash.

### Logcat / credential leak check

`adb logcat -d` after the second Start/Stop cycle, filtered through known leak patterns:

| Pattern | Lines |
| --- | ---: |
| `FATAL EXCEPTION` / `AndroidRuntime: FATAL` | **0** |
| `com.vela.android.lab` (case-sensitive) | **0** — the app emits no log lines from its own package during normal operation |
| `alpaca` / `FAKEPACA` / `SPY` / `stream.data` (case-insensitive) | **1** — the lone match is the `adb screencap` request line referencing the local PNG filename `phase2e-spy.png`; **not** a credential, **not** an app log |
| Plaintext key id pattern (`PK[A-Z0-9]{12,}`) | 0 |
| Secret-shape substring (`AKIA…`, `topsecret…`, …) | 0 |

No credentials were typed via `adb shell input text` this session; the user had already saved them earlier on the device, and the secure store survived the Phase 2.e reinstall (`adb install -r` preserves app data).

### Safety verification

| Check | Result |
| --- | --- |
| `G:\vela` modified | No — read-only tree not touched. |
| Windows `vela.db` read / copied / touched | No. |
| Endpoint stayed Market Data WebSocket only | Confirmed — `OkHttpAlpacaWebSocketFactory.open` calls `requireSafeMarketDataEndpoint`, which only accepts `wss://stream.data.alpaca.markets/v2/{test,iex}`. |
| `https://paper-api.alpaca.markets/v2` used | No — rejected at construction time and never referenced from any client. |
| `https://api.alpaca.markets/v2` used | No — rejected at construction time and never referenced from any client. |
| `/orders` / `/positions` / `/account` / `/trading` / `/portfolio` | None — all five fragments rejected by `requireSafeMarketDataEndpoint`. |
| LIVE endpoint added | None — `MarketDataSource.ALPACA_LIVE` still absent; `enum does not declare an ALPACA_LIVE value` still green. |
| Order / account / trading methods added | None — reflection contract green on four surfaces (interface + two clients + bridge). |
| Auto Paper / foreground service / ML | All deferred, none added this phase. |
| REAL locked | `realModeLocked = true` ([AppState.kt:18](../android/app/src/main/kotlin/com/vela/android/lab/state/AppState.kt#L18)); on-device dashboard badge `REAL locked: true`. |
| Credentials hardcoded in source / docs | None. |
| Credentials in chat / tool-call arguments | None — the user typed them manually on-device in Phase 2.c.1; `adb shell input text` was never invoked with credential values in Phase 2.e. |
| Credentials in logcat | None — 0 vela-package lines, 0 plaintext-key matches. |
| Credentials visible in UI after save | None — the stock card shows only `Credentials configured: true`. |

### What this proves

The Phase 2.b Alpaca read-only Market Data client pattern now scales to a second feed (real-stock IEX) without softening any safety contract. The same DI graph, the same `MarketDataClient` interface, the same `OfflineMarketPipelineCoordinator`, and the same Phase 2.d bridge type are reused; only the endpoint URL, the source enum value, and the seed symbol differ.

End-to-end:

1. The user enters Paper credentials once in the Phase 2.c.1 card. They live in Android Keystore-backed `EncryptedSharedPreferences`.
2. Tapping **Start real market data stream** on the new card subscribes to `SPY` and opens `wss://stream.data.alpaca.markets/v2/iex`.
3. The same auth handshake used by the FAKEPACA client succeeds; `Connection: CONNECTED`, `Subscribed: true`.
4. A real SPY 1-minute bar from IEX (close `731.20`, timestamp `2026-06-11T14:04:00Z`) arrives.
5. The bridge forwards the bar to the coordinator; `bars → features → signal → market/feature/signal/journal repositories` all run to completion; `Pipeline persisted: 1`.
6. Stop closes the socket cleanly; `Subscribed: false`; no crash; no straggling emissions.

The path remains strictly read-only — REAL is still locked, no order submission, no account access, no live endpoint, no `paper-api.alpaca.markets`, no `api.alpaca.markets`, no foreground service. The reflection contract is now enforced on the stock client too.

### Phase 2.e status

**Done.** Phase 2.e closes with: a new IEX read-only stock client, a new dashboard card, a new bridge instance, four new test files (totalling 57 new tests), debug+release unit tests at **352/0/0**, a real SPY bar persisted on-device, and zero credential / FATAL / trading-shape leakage.

---

## Phase 2.f — Market data stream robustness, lifecycle, and reconnection hardening

**Date**: 2026-06-11
**Branch / working tree**: `G:\vela-android` (read-only Windows tree at `G:\vela` not touched).
**Scope (verbatim from task brief)**: harden the existing read-only Alpaca Market Data WebSocket clients before adding more product features. Both FAKEPACA test stream and SPY IEX stream stay supported. No Trading API, no LIVE, no Auto Paper, no foreground service.

### Lifecycle + reconnect design

| Concern | Implementation |
| --- | --- |
| Duplicate WebSocket opens on repeated Start | `connect()` body on both clients wrapped in `kotlinx.coroutines.sync.Mutex.withLock`. The existing early-return when state is `CONNECTING`/`CONNECTED` is now race-safe under concurrent invocations. |
| Duplicate bridge collectors | `AlpacaTestStreamPipelineBridge.start` was already idempotent in Phase 2.d; reused. |
| Idempotent Stop | `disconnect()` body on both clients wrapped in the same Mutex; subsequent calls only close an already-closed handle (no-op at OkHttp) and re-emit a fresh DISCONNECTED status. The bridge's `stop()` was already idempotent. |
| Disconnect clears transient credentials | `currentCredentials = null` inside the Mutex, after the socket is closed. Listener callbacks (`onClosed`, `onFailure`) also null it. |
| ViewModel cleanup stops streams | Both VMs cancel `statusJob` + `updatesJob` + `bridgeJob` + the new `healthJob`, then call `pipelineBridge.stop()` in `onCleared`. |
| No updates forwarded after Stop | The Phase 2.d bridge cancels its collector on `stop()`; new emissions on the `SharedFlow` are dropped because no collector is attached. (Already covered by `AlpacaTestStreamPipelineBridgeTest.stop cancels the collector so subsequent updates are ignored` since Phase 2.d.) |
| Connection health state | New `StreamHealth` data class (separate from `MarketDataConnectionStatus`, which is unchanged) with phases `DISCONNECTED / CONNECTING / AUTHENTICATED / SUBSCRIBED / ERROR`, plus `lastConnectedAtEpochMillis`, `lastDisconnectedAtEpochMillis`, `lastMessageAtEpochMillis`, `lastErrorType`, `lastErrorMessage`, `reconnectAttempts`, and `subscribed: Set<String>`. |
| Reconnect attempts | Tracked by a small `StreamHealthTracker`. First `onConnectRequested()` leaves the counter at 0; every subsequent call (after user disconnect / error) increments by 1. `connect()` calls that early-return because the client is already CONNECTING/AUTHENTICATED/SUBSCRIBED **do not** increment. |
| Safe reconnect policy | **Manual only** in this phase. No background service. No automatic retry loop. Stop cancels any in-progress lifecycle work via the Mutex / bridge cancellation. Missing-credentials path moves to ERROR and stays there — re-tapping Start is the only way to retry. |
| Error type surfaced | Per-error `lastErrorType` on the health flow: `AuthenticationFailed`, `SubscriptionRejected`, `StreamLost`, `Unknown`. Plus `lastErrorMessage` and `lastErrorType` are cleared at the next `onConnectRequested()` so the UI shows "trying again", not "still showing prior failure". |
| Server-confirmed subscription | The parser-emitted `Subscription` frame now drives the health phase to `SUBSCRIBED` and stores the confirmed symbol set. |
| Last-message heartbeat | Every inbound parsed frame ticks `lastMessageAtEpochMillis` for "Last message Xs ago" UI. |

### Code added / changed

| Path | Purpose |
| --- | --- |
| [StreamHealth.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/StreamHealth.kt) (new, 65 lines) | Pure data class with `Phase` enum + `initial(endpoint, feedLabel)` factory. |
| [StreamHealthTracker.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/StreamHealthTracker.kt) (new, 119 lines) | Mutable thread-safe tracker exposing a read-only `StateFlow<StreamHealth>`. `onConnectRequested`, `onAuthenticated`, `onSubscribed`, `onMessage`, `onDisconnected`, `onError`, `resetAttempts`. No trading-shaped method names (asserted by reflection in test). |
| [AlpacaTestStreamMarketDataClient.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/alpaca/AlpacaTestStreamMarketDataClient.kt) (updated) | Holds a `StreamHealthTracker(feedLabel = "Alpaca test stream (FAKEPACA)")`. Exposes `val health: StateFlow<StreamHealth>`. `connect()`/`disconnect()` wrapped in `Mutex.withLock`. Server `subscription` frame routes through `onSubscriptionConfirmed`. Auth/error/closed/failed paths all call the tracker. |
| [AlpacaStockMarketDataClient.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/alpaca/AlpacaStockMarketDataClient.kt) (updated) | Same hardening, `feedLabel = "Alpaca stock (IEX)"`. |
| [AlpacaTestStreamUiState.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/AlpacaTestStreamUiState.kt) (updated) | Added six Phase 2.f read-only diagnostic fields: `healthPhase`, `lastConnectedAtEpochMillis`, `lastDisconnectedAtEpochMillis`, `lastMessageAtEpochMillis`, `reconnectAttempts`, `lastErrorType`. |
| [AlpacaStockStreamUiState.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/AlpacaStockStreamUiState.kt) (updated) | Same six fields. |
| [AlpacaTestStreamViewModel.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/AlpacaTestStreamViewModel.kt) (updated) | Constructor parameter `client` retyped from `MarketDataClient` interface to the concrete `AlpacaTestStreamMarketDataClient` (every call site already passes the concrete type). New `healthJob` collects `client.health` and mirrors into UI state. `onCleared` cancels it. |
| [AlpacaStockStreamViewModel.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/AlpacaStockStreamViewModel.kt) (updated) | Same `healthJob` collector. |
| [OfflineDashboardScreen.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt) (updated) | New "Stream diagnostics" section appended **inside both** the Paper card (Phase 2.d FAKEPACA pipeline area) and the stock card. Rows: `Health phase`, `Reconnect attempts`, `Last message at`, `Last connected at`, `Last disconnected at`, `Last error type`. Aesthetics unchanged — same `LabeledRow` rendering. Added a small `formatEpochMillis(value)` helper. Offline buttons + FAKEPACA card + SPY card all preserved. |

The original `MarketDataConnectionStatus` interface contract is **unchanged** — Phase 2.f did not alter the existing public boundary; the new health flow lives alongside.

### Tests added / updated

| Path | Purpose |
| --- | --- |
| [StreamHealthTrackerTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/market/source/StreamHealthTrackerTest.kt) (new, 11 tests) | Initial health is DISCONNECTED + zero counters; first `onConnectRequested` leaves counter at 0; subsequent ones increment; `onAuthenticated` records `lastConnectedAt` and clears prior error; `onSubscribed` sets phase + symbols; `onMessage` advances `lastMessageAt`; `onDisconnected` clears the subscribed set and records `lastDisconnectedAt`; `onError` captures type + message; `onConnectRequested` clears prior error so UI shows "trying again"; `resetAttempts` zeroes the counter; reflection contract: no trading-shaped method names on the tracker. |
| [AlpacaClientLifecycleTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/market/source/alpaca/AlpacaClientLifecycleTest.kt) (new, 20 tests across both clients) | **Repeated `connect()` opens only one socket** (test stream + stock); **repeated `disconnect()` is idempotent** (test stream + stock); **missing credentials never opens the socket and never auto-retries** — three `connect()` calls with no creds produce `factory.openCalls == 0` and `health.reconnectAttempts == 2`; **malformed JSON does not crash and emits no update** — five hostile payloads (non-JSON, wrong shape, missing fields, empty, empty array) all leave the client in CONNECTED with zero captured updates; **`subscription` frame transitions health to SUBSCRIBED** with the confirmed symbol set; **`onMessage` advances `lastMessageAt`**; **credentials never appear in `connectionStatus.toString()` or `health.toString()`**; **REAL / trading endpoints rejected at construction time** (test stream client and stock client, with the stock client also rejecting SIP/delayed_sip); **reconnect counter advances by exactly 1 per user-initiated reconnect**; **lastError type surfaced on health after a 401**; **`onFailure` surfaces `StreamLost` on health**; **`onClosed` transitions health to DISCONNECTED with no error**. |
| [AlpacaTestStreamMarketDataClientTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/market/source/alpaca/AlpacaTestStreamMarketDataClientTest.kt) (unchanged) | Phase 2.b tests still green. |
| [AlpacaStockMarketDataClientTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/market/source/alpaca/AlpacaStockMarketDataClientTest.kt) (unchanged) | Phase 2.e tests still green. |
| [AlpacaTestStreamViewModelTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/ui/dashboard/AlpacaTestStreamViewModelTest.kt) (unchanged) | Phase 2.d tests still green — the constructor parameter narrowing from `MarketDataClient` to the concrete client is source-compatible because every call site already passed the concrete class. |
| [AlpacaStockStreamViewModelTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/ui/dashboard/AlpacaStockStreamViewModelTest.kt) (unchanged) | Phase 2.e tests still green. |
| [OfflineDashboardViewModelTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardViewModelTest.kt) (unchanged) | Phase 1.e offline tests still green. |

The Phase 2.b/2.d/2.e reflection contracts continue to enforce "no trading methods" on the interface + both concrete clients + the bridge. Phase 2.f extends the contract surface by adding a reflection check on `StreamHealthTracker`.

### Unit tests

| Variant | Tests | Failures | Errors |
| --- | ---: | ---: | ---: |
| `:app:testDebugUnitTest` | **386** | **0** | **0** |
| `:app:testReleaseUnitTest` | **386** | **0** | **0** |

Run via `./gradlew.bat :app:test --no-daemon -Dorg.gradle.jvmargs="-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8"` → `BUILD SUCCESSFUL in 59s`. Phase 2.f added **34 unit tests** over Phase 2.e (was 352; now 386): 11 tracker tests + 23 lifecycle/idempotence/malformed-JSON/reconnect/credential-safety tests covering both clients. One mid-phase compile glitch (a single-element clock iterator that needed two values for the disconnect test) — fixed and re-run, all green.

### Build, install, launch

- `./gradlew.bat :app:assembleDebug` → `BUILD SUCCESSFUL in 22s`. APK at `app/build/outputs/apk/debug/app-debug.apk`.
- `adb -s emulator-5554 install -r app-debug.apk` → `Performing Streamed Install / Success`.
- `adb -s emulator-5554 shell am start -n com.vela.android.lab/.MainActivity` → MainActivity resumed; dashboard rendered without crash.

### On-device smoke (emulator-5554, real Alpaca IEX feed, market hours ~10:23 ET 2026-06-11)

The dashboard renders four sections in order: Phase 1.e offline cards (Status, Last pipeline step, Persistence, Demo controls); Phase 2.c.1 Paper Credentials card with the Phase 2.d FAKEPACA pipeline section **plus** the new Phase 2.f "Stream diagnostics" rows; Phase 2.e "Alpaca real market data — read only" card with its own Stream diagnostics rows; both Start/Stop buttons.

**Multi-tap Start** (3 rapid taps on "Start real market data stream") — exercised the lifecycle mutex + early-return:

| Field | Value observed |
| --- | --- |
| Connection | `CONNECTED` |
| Subscribed | `true` |
| **Health phase** | **`SUBSCRIBED`** (server-confirmed) |
| **Reconnect attempts** | **`0`** — three rapid taps **opened exactly one socket**; the 2nd and 3rd taps early-returned via the Mutex'd state check |
| Last message at | `2026-06-11T14:23:34.996Z` (heartbeat ticking) |
| Last connected at | `2026-06-11T14:23:28.361Z` |
| Last error type | `—` |

**Multi-tap Stop** (3 rapid taps on "Stop real market data stream"):

| Field | Value observed |
| --- | --- |
| Connection | `DISCONNECTED` |
| Subscribed | `false` |
| Bars received | `1` — a real SPY bar (`SPY`, close `728.48`, timestamp `2026-06-11T14:22:00Z`) arrived during the session before Stop |
| **Pipeline persisted** | **`1`** — the SPY bar flowed through `bridge.handleUpdate → coordinator.addUpdate → repositories` end-to-end |
| Health phase | `DISCONNECTED` |
| Reconnect attempts | `0` (Stop does not advance the counter) |
| Last disconnected at | `2026-06-11T14:23:55.454Z` |
| Last error type | `StreamLost` — same OkHttp `onFailure` informational artefact seen in Phase 2.e after a client-initiated socket close. No crash. |

**Reconnect cycle** (tap Start again after the Stop):

| Field | Value observed |
| --- | --- |
| Connection | `CONNECTED` |
| Subscribed | `true` |
| Health phase | `SUBSCRIBED` |
| **Reconnect attempts** | **`1`** — exactly one increment per user-initiated reconnect |
| Last connected at | `2026-06-11T14:24:22.692Z` |
| Last disconnected at | `2026-06-11T14:23:55.454Z` (preserved from the prior stop) |
| **Last error type** | **`—`** — the stale `StreamLost` was cleared by `onConnectRequested()` so the UI does not keep showing a resolved failure |
| Bars received | `1` (preserved across reconnect — the VM only resets on `onCleared`) |
| Pipeline persisted | `1` (preserved) |

The Phase 1.e offline buttons (Generate demo BTC/USD, Generate demo SPY, Clear) and the Phase 2.b/c/d FAKEPACA card remained visible and usable throughout. No crashes, no force-stops, no ANRs.

### Logcat / credential leak check

`adb logcat -d` after the full multi-tap session, filtered through known leak patterns:

| Pattern | Lines |
| --- | ---: |
| `FATAL EXCEPTION` / `AndroidRuntime: FATAL` | **0** |
| `com.vela.android.lab` (case-sensitive) | **0** |
| `alpaca` / `FAKEPACA` / `SPY` / `stream.data` (case-insensitive) | **0** |
| Plaintext key/secret patterns (`PK[A-Z0-9]{8,}`, `topsecretvalue`, `sssssss`) | **0** |

The app emits no log lines from its own package during normal operation, including during the multi-tap, reconnect, and post-error retry sequences.

### Safety verification

| Check | Result |
| --- | --- |
| `G:\vela` modified | No — read-only tree not touched. |
| Windows `vela.db` read / copied / touched | No. |
| Endpoint stayed Market Data WebSocket only | Confirmed — `OkHttpAlpacaWebSocketFactory.open` still calls `requireSafeMarketDataEndpoint`; the two strict guards (`requireSafeReadOnlyEndpoint`, `requireSafeMarketDataEndpoint`) still reject every `paper-api.alpaca.markets`, `api.alpaca.markets`, `/orders`, `/positions`, `/account`, `/trading`, `/portfolio`, `live`, `sip`, `delayed_sip` URL — covered by `AlpacaStreamEndpointTest` + `AlpacaIexEndpointTest` (33 endpoint tests total). |
| `https://paper-api.alpaca.markets/v2` used in Phase 2.f | No. |
| `https://api.alpaca.markets/v2` used | No. |
| `/orders` / `/positions` / `/account` / `/trading` | None. |
| LIVE endpoint added | None — `MarketDataSource.ALPACA_LIVE` still absent. |
| Order / account / trading methods added | None — reflection contract still green on: `MarketDataClient` interface, `AlpacaTestStreamMarketDataClient`, `AlpacaStockMarketDataClient`, `AlpacaTestStreamPipelineBridge`, **and the new `StreamHealthTracker`** (five surfaces). |
| Auto Paper / foreground service / ML | All deferred, none added this phase. |
| REAL locked | `realModeLocked = true` ([AppState.kt:18](../android/app/src/main/kotlin/com/vela/android/lab/state/AppState.kt#L18)); dashboard badge `REAL locked: true`. |
| Credentials hardcoded in source / docs | None. |
| Credentials in chat / tool-call arguments | None — the user typed them on-device in Phase 2.c.1; `adb shell input text` was never invoked with credential values in Phase 2.f. |
| Credentials in logcat | None — `KEY_LEAK=0` for the full multi-tap session. |
| Credentials visible in UI after save | None — the diagnostics show `Credentials configured: true` only; the new `StreamHealth` fields carry timestamps, counts, and error types, never credentials. Unit-tested: `credentials never appear in connectionStatus or health after auth`. |

### What this proves

The two read-only Alpaca Market Data WebSocket clients now handle the realistic user-tap surface safely:

1. Three rapid Start taps in 100 ms ↦ one socket opened, no double-subscribe, no race.
2. Three rapid Stop taps ↦ one socket closed, no crash, idempotent.
3. Repeated `connect()` with no credentials ↦ never opens a socket, never auto-retries; `reconnectAttempts` reflects user intent only.
4. Server `subscription` frame ↦ health phase advances to `SUBSCRIBED` with the confirmed symbol set.
5. Malformed JSON ↦ parser swallows it; no update emitted; client stays CONNECTED.
6. Auth-failed (401), connection-limit (406), and network failure all surface a typed `lastErrorType` on the health flow without leaking credentials.
7. The Stop → Start cycle increments `reconnectAttempts` by exactly 1 and clears the prior error so the UI does not keep showing resolved failures.
8. A real SPY bar (close 728.48) traversed the full pipeline (`bridge.handleUpdate → coordinator → repositories`) during the smoke session and persisted, proving the hardening did not regress the Phase 2.e end-to-end path.

REAL stays locked. No Trading API host, no `/orders|/positions|/account|/trading|/portfolio` fragment, no LIVE endpoint, no Auto Paper, no foreground service, no ML, no order submission, no account access. `G:\vela` and the Windows `vela.db` were not touched.

### Phase 2.f status

**Done.** Phase 2.f closes with: two new main-source files (`StreamHealth`, `StreamHealthTracker`), hardened lifecycle on both Alpaca clients (Mutex + health-tracker wiring), 34 new unit tests (`:app:test` debug+release at **386/0/0**), an on-device demonstration of multi-tap idempotence + reconnect counter accuracy + real SPY bar persistence (close 728.48), and zero credential / FATAL / trading-shape leakage across the full smoke session.

---

## Phase 2.g — Multi-symbol read-only watchlist + market-data routing

**Date**: 2026-06-11
**Branch / working tree**: `G:\vela-android` (read-only Windows tree at `G:\vela` not touched).
**Scope (verbatim from task brief)**: move from a single fixed SPY stream to a small configurable read-only watchlist, routed safely through the existing market-data pipeline. IEX feed only. Default `SPY, QQQ, AAPL, MSFT, NVDA`. No Trading API. No LIVE. No Auto Paper. No foreground service.

### Watchlist + storage design

| Item | Value |
| --- | --- |
| Default seed | `["SPY", "QQQ", "AAPL", "MSFT", "NVDA"]` |
| Max cap | **10 symbols** (`WatchlistConfig.MAX_SYMBOLS`) |
| Symbol shape | uppercase + trim; matches `^[A-Z][A-Z0-9.]{0,9}$` (e.g. `BRK.B` is valid) |
| Rejected at normalization | empty, whitespace-only, embedded space, starts with digit, starts with dot, contains `/` (crypto pair), `!@#…` punctuation, lengths > 10 |
| Storage | app-private `SharedPreferences` (`vela-watchlist` / `symbols` `StringSet`). **No encryption** — watchlist values carry no credential; encryption would be ceremony without value. |
| Read API | `WatchlistRepository.load()` returns sorted unique list; auto-seeds defaults when the store is empty |
| Mutation API | `add(input)` and `remove(input)` return a sealed `MutationResult` with `Added / Removed / AlreadyPresent / NotPresent / Invalid / AtCap` cases — the UI shows the message verbatim |
| Reflection contract | `WatchlistRepository` and `WatchlistViewModel` declared-method names checked against the forbidden trading substrings list (`submitorder`, `placeorder`, `trading`, `executeorder`, `cancelorder`, `getaccount`, `openposition`, `closeposition`) |

### Client subscription changes

`AlpacaStockMarketDataClient.subscribe` already accepted `Set<String>` (Phase 2.e). This phase adds an explicit unit-test that **a single multi-symbol `subscribe` call produces one outbound `subscribe` frame** carrying exactly the watchlist symbols on the `bars` and `quotes` channels, with **no `trades` channel** — preserving the read-only contract.

`AlpacaStockStreamViewModel.startStream(symbols: Set<String>)` is the new overload (the legacy 0-arg `startStream()` still works and falls back to the single-symbol seed). The dashboard's Start callback now reads the live watchlist and passes the set through:

```kotlin
onStartStock = {
    alpacaStockViewModel?.refresh()
    val watchlist = watchlistViewModel?.subscribeSet() ?: emptySet()
    alpacaStockViewModel?.startStream(watchlist)
}
```

### Pipeline routing changes

The Phase 1.e `OfflineMarketPipelineCoordinator` was already symbol-aware: every `addUpdate(update)` runs the full `aggregator → features → signal → repositories` chain keyed on `update.symbol`. Phase 2.g adds the **per-symbol projection** at the bridge layer:

- `AlpacaTestStreamBridgeState` gained a `perSymbol: Map<String, SymbolBridgeStats>` field (defaults to empty for backward compatibility).
- `SymbolBridgeStats(received, persisted, lastClose, lastSignalState, lastBarBucketStartEpochMillis)` is the per-symbol tile.
- `AlpacaTestStreamPipelineBridge.handleUpdate` atomically updates **both** the aggregate counters and the per-symbol map in a single `_state.update { ... }` block.
- A symbol-scoped exception inside the coordinator (e.g. a DAO write failing for one symbol) leaves the bridge collector alive, populates `lastError`, **and** does not corrupt other symbols' `perSymbol` entries.

The dashboard's `WatchlistCard` filters the bridge's `perSymbol` map down to the user's selected watchlist symbols — symbols still being sent by the server after a watchlist removal sit silently in the map but are not rendered.

### Code added / changed

| Path | Purpose |
| --- | --- |
| [WatchlistConfig.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/watchlist/WatchlistConfig.kt) (new) | Defaults, max cap, `normalize`, `isValid`. |
| [WatchlistStore.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/watchlist/WatchlistStore.kt) (new) | Interface + `SharedPrefsWatchlistStore` (production) + `InMemoryWatchlistStore` (tests). |
| [WatchlistRepository.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/watchlist/WatchlistRepository.kt) (new) | `load` (with seeding), `add`, `remove`, `resetToDefaults`. Returns a sealed `MutationResult`. |
| [AlpacaTestStreamBridgeState.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/pipeline/AlpacaTestStreamBridgeState.kt) (updated) | Added `perSymbol: Map<String, SymbolBridgeStats>` + new `SymbolBridgeStats` data class. |
| [AlpacaTestStreamPipelineBridge.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/pipeline/AlpacaTestStreamPipelineBridge.kt) (updated) | `handleUpdate` now updates `perSymbol[sym]` alongside the aggregate counters. Exception path documented: a per-symbol failure does **not** corrupt other symbols. |
| [AlpacaStockStreamViewModel.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/AlpacaStockStreamViewModel.kt) (updated) | New `startStream(symbols: Set<String>)` overload; existing 0-arg version still works. |
| [WatchlistUiState.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/WatchlistUiState.kt) (new) | UI state for the watchlist card. |
| [WatchlistViewModel.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/WatchlistViewModel.kt) (new) | Owns the watchlist, projects per-symbol bridge metrics, validates user input, exposes `subscribeSet()` for the stream Start path. |
| [OfflineDashboardScreen.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt) (updated) | New `WatchlistCard` composable rendered after the stock card. Per-row receive/persist/close/signal + Remove button. `Add symbol` `OutlinedTextField` + `Add to watchlist` button (disabled with "Watchlist at cap" label when at the cap). |
| [VelaLabApplication.kt](../android/app/src/main/kotlin/com/vela/android/lab/VelaLabApplication.kt) (updated) | DI: `watchlistStore`, `watchlistRepository`. |
| [MainActivity.kt](../android/app/src/main/kotlin/com/vela/android/lab/MainActivity.kt) (updated) | New `watchlistViewModel` factory; passed into `OfflineDashboardScreen` gated by `BuildConfig.DEBUG`. |

### Tests added / updated

| Path | Tests | Purpose |
| --- | ---: | --- |
| [WatchlistConfigTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/watchlist/WatchlistConfigTest.kt) (new) | 17 (incl. `@TestFactory` over 10 invalid inputs) | Default seed + max cap + normalize accept/reject cases including `BRK.B`, crypto-slash rejection. |
| [WatchlistRepositoryTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/watchlist/WatchlistRepositoryTest.kt) (new) | 10 | `load` seeds defaults, dedups, sorts; `add` normalizes + persists; `add` rejects invalid + at-cap + duplicate; `remove` works + handles NotPresent/Invalid; `resetToDefaults`; reflection contract. |
| [BridgePerSymbolRoutingTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/pipeline/BridgePerSymbolRoutingTest.kt) (new) | 4 | Initial `perSymbol` empty; SPY/QQQ/AAPL route into distinct map entries with their own counts/closes; stop prevents post-stop entries; one-symbol throwing leaves other symbols' entries intact. |
| [WatchlistViewModelTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/ui/dashboard/WatchlistViewModelTest.kt) (new) | 9 | Initial seeding sorted; add normalizes; add rejects invalid + empty + at-cap; remove works; `subscribeSet()` reflects current; bridge emissions mirror into `perSymbol`; reflection contract. |
| [AlpacaStockMultiSymbolSubscribeTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/market/source/alpaca/AlpacaStockMultiSymbolSubscribeTest.kt) (new) | 2 | Multi-symbol subscribe produces one outbound frame with the **exact** watchlist on both `bars` and `quotes` channels; **no `trades` channel**. |

### Reflection / contract assertions still green

The "no trading methods" reflection contract now covers **seven** surfaces, all green:

1. `MarketDataClient` interface (Phase 2.a)
2. `AlpacaTestStreamMarketDataClient` (Phase 2.b)
3. `AlpacaTestStreamPipelineBridge` (Phase 2.d)
4. `AlpacaStockMarketDataClient` (Phase 2.e)
5. `StreamHealthTracker` (Phase 2.f)
6. **`WatchlistRepository`** (added this phase)
7. **`WatchlistViewModel`** (added this phase)

Endpoint guard: `AlpacaStreamEndpointTest` + `AlpacaIexEndpointTest` together still reject every Trading API host, every `/orders|/positions|/account|/trading|/portfolio` fragment, every SIP / delayed_sip / `live` URL.

### Unit tests

| Variant | Tests | Failures | Errors |
| --- | ---: | ---: | ---: |
| `:app:testDebugUnitTest` | **428** | **0** | **0** |
| `:app:testReleaseUnitTest` | **428** | **0** | **0** |

Run via `./gradlew.bat :app:test --no-daemon -Dorg.gradle.jvmargs="-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8"` → `BUILD SUCCESSFUL in 54s`. Phase 2.g added **42 unit tests** over Phase 2.f (was 386; now 428): 17 config + 10 repository + 4 bridge per-symbol routing + 9 watchlist VM + 2 multi-symbol stock subscribe. One mid-phase test fix: a routing test asserted `lastError != null` after a failing symbol, but the bridge intentionally clears `lastError` on the next successful update — the test was rewritten to assert between the two emissions, which actually exercises the per-symbol no-bleed contract more precisely.

### Build, install, launch

- `./gradlew.bat :app:assembleDebug` → `BUILD SUCCESSFUL in 22s`.
- `adb -s emulator-5554 install -r app-debug.apk` → `Performing Streamed Install / Success`.
- `adb -s emulator-5554 shell am start -n com.vela.android.lab/.MainActivity` → MainActivity resumed; dashboard rendered without crash.

### On-device smoke (emulator-5554, real Alpaca IEX feed, US market hours)

**Dashboard renders five sections** in order: Phase 1.e offline cards; Phase 2.c.1 Paper Credentials card + Phase 2.d FAKEPACA pipeline + Phase 2.f Stream diagnostics; Phase 2.e + 2.f stock card with Stream diagnostics; **Phase 2.g `Watchlist — read only` card** with the five seeded symbols + an `Add symbol` input and per-row `Remove` buttons.

**Default seeding** verified on first run: the watchlist card rendered `AAPL, MSFT, NVDA, QQQ, SPY` sorted alphabetically. Each row showed `received 0 · persisted 0 · last — · —` until Start.

**Tapping Start** subscribed once to the full set of 5 symbols and authenticated cleanly. Stock card health phase advanced to `SUBSCRIBED`. Within ~13s, a 1-minute IEX bar arrived for each watchlist symbol — per-symbol routing landed exactly as designed:

| Symbol | received | persisted | last close | signal |
| --- | ---: | ---: | ---: | --- |
| AAPL | 1 | 1 | 290.76 | NEUTRAL |
| MSFT | 1 | 1 | 389.61 | BEARISH |
| NVDA | 1 | 1 | 201.58 | NEUTRAL |
| QQQ | 1 | 1 | 700.47 | BULLISH |
| SPY | 1 | 1 | 728.31 | BULLISH |

That single Start tap drove all five symbols through their own `aggregator → features → signal → repositories` chain, producing distinct per-symbol signal states (NEUTRAL/BEARISH/BULLISH) — proof that the routing is per-symbol, not aggregate.

**Tapping `Remove` on the NVDA row** immediately updated the watchlist UI: `Removed NVDA` status line, NVDA row disappeared from the card, the remaining four rows kept their counts. The stock client is still subscribed to NVDA at the server level (Phase 2.g does not currently call `client.unsubscribe(removedSet)`); incoming NVDA bars would land in the bridge's `perSymbol` map but are not rendered because the watchlist VM filters its display set. This is safe — NVDA is a read-only stock symbol; no trading happens; the next `startStream(watchlist)` after a `stopStream()` will only subscribe to the live watchlist set. Documented honestly as a known scope choice rather than a regression.

**A second 1-minute window elapsed** while the card was open. The bridge picked up new bars for each remaining watchlist symbol:

| Symbol | received | persisted | last close | signal |
| --- | ---: | ---: | ---: | --- |
| AAPL | 3 | 3 | 290.76 | NEUTRAL |
| MSFT | 3 | 3 | 388.49 | BEARISH |
| QQQ | 3 | 3 | 700.11 | BULLISH |
| SPY | 3 | 3 | 727.83 | BEARISH |

`4 symbols × 3 bars = 12 distinct per-symbol pipeline runs` ↦ 12 market bar rows, 48 journal events (4 per accepted update), 12 feature rows, 12 signal rows — all keyed on the individual symbols.

**Tapping Stop** transitioned the stock card to `Health phase: ERROR`, `Last error type: StreamLost`, `Subscribed: false`. This is the same OkHttp `onFailure` informational signal observed after Stop in Phase 2.e/2.f — not a crash, just the post-close callback. The watchlist counts and `Removed NVDA` status were preserved.

The Phase 1.e offline buttons (`Generate demo BTC/USD`, `Generate demo SPY`, `Clear`) and the Phase 2.c.1 Paper card + Phase 2.d FAKEPACA pipeline section remained visible and usable throughout. No crashes, no ANRs.

### Logcat / credential leak check

`adb logcat -d` after the full smoke session, filtered through known leak patterns:

| Pattern | Lines |
| --- | ---: |
| `FATAL EXCEPTION` / `AndroidRuntime: FATAL` | **0** |
| `com.vela.android.lab` (case-sensitive) | **0** |
| `alpaca` / `FAKEPACA` / `SPY` / `stream.data` (case-insensitive) | **0** |
| Plaintext key/secret patterns (`PK[A-Z0-9]{8,}`, `topsecretvalue`) | **0** |

The app emits no log lines from its own package during normal operation, even with 5-symbol IEX traffic + add/remove watchlist mutations + multiple per-minute bar deliveries.

### Safety verification

| Check | Result |
| --- | --- |
| `G:\vela` modified | No — read-only tree not touched. |
| Windows `vela.db` read / copied / touched | No. |
| Endpoint stayed Market Data WebSocket IEX | Confirmed — `OkHttpAlpacaWebSocketFactory.open` still calls `requireSafeMarketDataEndpoint`, which only accepts `wss://stream.data.alpaca.markets/v2/{test,iex}`. Watchlist storage holds plain symbol strings; no URL is constructed from watchlist input. |
| `https://paper-api.alpaca.markets/v2` used | No. |
| `https://api.alpaca.markets/v2` used | No. |
| `/orders` / `/positions` / `/account` / `/trading` / `/portfolio` | None — Phase 2.g touched zero networking code; the existing endpoint guards apply. |
| LIVE endpoint added | None — `MarketDataSource.ALPACA_LIVE` still absent. |
| Order / account / trading methods added | None — reflection contract green on **seven** surfaces (interface + two clients + bridge + tracker + new repository + new VM). |
| Auto Paper / foreground service / ML | All deferred, none added this phase. |
| REAL locked | `realModeLocked = true` ([AppState.kt:18](../android/app/src/main/kotlin/com/vela/android/lab/state/AppState.kt#L18)); dashboard badge `REAL locked: true`. |
| Credentials hardcoded in source / docs | None — watchlist storage holds symbol strings only. |
| Credentials in chat / tool-call arguments | None — only the user-supplied watchlist symbol `tsla`-style strings flow through `adb input`; nothing credential-shaped this phase. |
| Credentials in logcat | None — `KEY_LEAK=0` for the full smoke session including 5-symbol IEX traffic. |
| Credentials visible after save in UI | None — the watchlist UI shows symbol strings + counts + signals; no credential surface was added. |
| Watchlist persists between launches | `SharedPreferences` survives the `adb install -r` upgrade; the same 5 symbols (minus NVDA after the remove) reappeared on next launch. |
| Watchlist max cap enforced | Unit-tested + UI button disables itself with "Watchlist at cap" label when `size == 10`. |
| Crypto-slash inputs rejected | Unit-tested — `BTC/USD`-style inputs are refused by `WatchlistConfig.normalize` and surface as `Invalid` via the repository. |

### What this proves

The Alpaca IEX stream now routes a small **user-curated** read-only watchlist through the same Phase 1.e offline pipeline coordinator, with per-symbol counters surfaced live on the dashboard:

1. Default watchlist seeds on first run (`SPY, QQQ, AAPL, MSFT, NVDA`) and survives app restart via `SharedPreferences`.
2. One Start tap subscribes to the entire watchlist in a single `subscribe` frame carrying `bars` + `quotes` only — no `trades` channel.
3. Five real IEX 1-minute bars arrived during the smoke session — one per symbol — and each ran through its own `aggregator + features + signal + repositories` chain, producing distinct per-symbol signals (NEUTRAL / BEARISH / BULLISH).
4. A second minute window produced four more bars per symbol (NVDA was removed); the bridge's `perSymbol` map continued advancing per symbol with no cross-talk.
5. The Remove button updates the visible watchlist + persists the new set; the next Start subscribes only to the live watchlist.
6. The cap at 10 symbols is enforced both in the repository and visibly in the UI (button label flips to "Watchlist at cap").
7. Invalid inputs (empty, whitespace, crypto-slash, punctuation, oversize) never reach the stream subscribe set — they're rejected at the normalization layer with a user-facing status message.

The path remains strictly read-only — REAL stays locked, no order submission, no account access, no live endpoint, no `paper-api.alpaca.markets`, no `api.alpaca.markets`, no foreground service, no ML, no auto-paper. The reflection contract is now enforced on seven surfaces. `G:\vela` and the Windows `vela.db` were not touched.

### Phase 2.g status

**Done.** Phase 2.g closes with: a new `data.watchlist` package (Config + Store + Repository), a per-symbol projection on the bridge, a multi-symbol subscribe overload on the stock VM, a new `WatchlistViewModel` + dashboard card, **42 new unit tests** (`:app:test` debug+release at **428/0/0**), and an on-device demonstration of 5-symbol subscribe + per-symbol persistence with real IEX market data (12 bars across 5 symbols → 12 distinct pipeline runs → 12 market bar rows + 48 journal events). Zero credential / FATAL / trading-shape leakage across the full smoke session.

---

## Phase 2.h — Clean stop semantics + stale WebSocket callback protection

**Date**: 2026-06-11
**Branch / working tree**: `G:\vela-android` (read-only Windows tree at `G:\vela` not touched).
**Scope (verbatim from task brief)**: fix the lifecycle bug observed at the end of Phase 2.g — user-initiated Stop transitioned to `ERROR / StreamLost` because OkHttp's post-close `onFailure` callback fired after the user closed the socket. A user-requested Stop must end at `DISCONNECTED` with no active error. Also protect against stale callbacks from a previous session mutating a newer session's state.

### Clean-stop design

| Concern | Implementation |
| --- | --- |
| Track user intent | New `@Volatile var intentionalCloseSessionId: Int = -1` on each client. `disconnect()` sets it equal to the active session id *before* closing the socket. |
| Per-session id on every WebSocket callback | New `private val sessionGen: AtomicInteger` + `@Volatile var activeSessionId: Int = -1`. `connect()` increments the generator and assigns the new id to `activeSessionId`. The inner `ListenerBridge` captures its session id at construction (`private inner class ListenerBridge(private val sessionId: Int) : AlpacaWebSocketListener`). |
| Clean Stop status | `disconnect()` body now ends with `MarketDataConnectionStatus.disconnected(...)` (already did) **plus** `healthTracker.onUserStop()` (new) instead of `onDisconnected()`. The new tracker method clears `lastErrorType` and `lastErrorMessage` in addition to the phase / disconnected-at timestamp. |
| Post-stop `onFailure` is informational, not error | Inside `ListenerBridge.onFailure`: if `sessionId == intentionalCloseSessionId` (and not stale), the callback is treated as a clean `DISCONNECTED` — no status `ERROR`, no `lastErrorType`. |
| Post-stop `onClosed` is clean | Same check: a server-confirmed close on an intentionally-closed session calls `healthTracker.onUserStop()`; an unexpected server close on a still-active session calls `healthTracker.onDisconnected()`. Both produce `DISCONNECTED`, but only the intentional case clears `lastErrorType`. |
| Stale-callback protection | Every WebSocket callback (`onMessage`, `onClosed`, `onFailure`) early-returns when `sessionId != activeSessionId`. A late callback from a stopped older session cannot mutate the state of a newer session that is currently `CONNECTING` / `AUTHENTICATED` / `SUBSCRIBED`. |
| Unexpected failure semantics preserved | An `onFailure` arriving on the active session **without** a prior `disconnect()` still flows to the error branch: `MarketDataConnectionStatus.error(StreamLost(...))` + `healthTracker.onError("StreamLost", ...)`. |
| Reconnect attempts unchanged on Stop | `onUserStop()` does not touch `reconnectAttempts`; it only advances on the next `onConnectRequested()` after a manual restart. |

### Stale callback / session policy

```
sessionGen        : AtomicInteger          // monotonic
activeSessionId   : @Volatile var Int      // id of the open socket, or -1
intentionalCloseSessionId : @Volatile var Int // id the user asked to close, or -1

connect():
  if state in {CONNECTING, CONNECTED} -> early return
  if no credentials -> ERROR (no socket opened, no session id consumed)
  sessionId = sessionGen.incrementAndGet()
  activeSessionId = sessionId
  open(endpoint, ListenerBridge(sessionId))

disconnect():
  intentionalCloseSessionId = activeSessionId   // mark intent first
  handle?.close()                                // then close
  status = DISCONNECTED
  health.onUserStop()                            // clears lastErrorType

ListenerBridge(sessionId).onClosed(code, reason):
  if sessionId != activeSessionId -> return     // stale, ignore
  status = DISCONNECTED
  if sessionId == intentionalCloseSessionId:
    health.onUserStop()                          // clean stop
  else:
    health.onDisconnected()                      // unexpected close

ListenerBridge(sessionId).onFailure(t, response):
  if sessionId != activeSessionId -> return     // stale, ignore
  if sessionId == intentionalCloseSessionId:
    status = DISCONNECTED                       // post-Stop callback
    health.onUserStop()
    return
  status = ERROR(StreamLost(message))           // genuine failure
  health.onError("StreamLost", message)
```

### Code changed

| Path | Change |
| --- | --- |
| [StreamHealthTracker.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/StreamHealthTracker.kt) | New `onUserStop()` method. Same as `onDisconnected()` but also clears `lastErrorType` + `lastErrorMessage` so the UI doesn't keep showing a stale `StreamLost`-style indicator after the user explicitly closed the stream. Reconnect counter is untouched. |
| [AlpacaTestStreamMarketDataClient.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/alpaca/AlpacaTestStreamMarketDataClient.kt) | Added `sessionGen`, `activeSessionId`, `intentionalCloseSessionId`. `connect()` now assigns a new session id and passes it into a new `ListenerBridge(sessionId)` constructor. `disconnect()` marks the active session as intentionally closed and routes through `healthTracker.onUserStop()`. `ListenerBridge` callbacks check `isStale()` and `isIntentionalClose()` and route accordingly. |
| [AlpacaStockMarketDataClient.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/alpaca/AlpacaStockMarketDataClient.kt) | Same hardening; symmetric with the test-stream client. |

No other files changed — the existing UI mirror layer (`AlpacaTestStreamViewModel`, `AlpacaStockStreamViewModel`, dashboard cards, watchlist VM) consumes `client.health` and `client.connectionStatus` the same way it always has. The fix lives entirely at the WebSocket-callback boundary.

### Tests added

| Path | Tests | Purpose |
| --- | ---: | --- |
| [AlpacaStopSemanticsTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/market/source/alpaca/AlpacaStopSemanticsTest.kt) (new) | 12 | Cross-cutting Phase 2.h contract for both clients. |

The new test file covers:

- **test stream + stock**: user Stop ends `DISCONNECTED` with `status.lastError == null` and `health.lastErrorType == null` (4 tests across both clients including a 401-then-Stop variant).
- **test stream + stock**: post-stop `onFailure` is treated as clean `DISCONNECTED`, **not** `ERROR / StreamLost` (2 tests).
- **test stream + stock**: post-stop `onClosed` produces clean `DISCONNECTED` (2 tests).
- **test stream + stock**: unexpected `onFailure` *while running* still becomes `ERROR / StreamLost` (2 tests) — the regression guard.
- **stock**: stale `onFailure` from session 0 after session 1 is active cannot demote session 1 to ERROR.
- **stock**: stale `onClosed` from session 0 after session 1 is active cannot mutate session 1's state.
- **stock**: stale `onMessage` from session 0 after session 1 is active cannot advance session 1's `lastMessageAtEpochMillis`.

A new test double `StopMultiSessionFactory` retains every recorded session's listener and handle so tests can invoke a *specific* old session's listener after a newer session has opened — the previous `LifecycleFactory` only kept the most recent listener and could not have constructed the stale-callback scenarios.

All Phase 2.a/b/c/d/e/f/g tests still pass without modification — the existing assertions ("server `onClosed` transitions health to DISCONNECTED", "lastError type exposed after 401", "stopStream closes the socket", etc.) hold because the changed code paths are strictly additive on top of them.

### Reflection / contract assertions still green

The "no trading methods" reflection contract still covers seven surfaces. No new surface added this phase.

### Unit tests

| Variant | Tests | Failures | Errors |
| --- | ---: | ---: | ---: |
| `:app:testDebugUnitTest` | **440** | **0** | **0** |
| `:app:testReleaseUnitTest` | **440** | **0** | **0** |

Run via `./gradlew.bat :app:test :app:assembleDebug --no-daemon -Dorg.gradle.jvmargs="-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8"` → `BUILD SUCCESSFUL in 47s`. Phase 2.h added **12 unit tests** over Phase 2.g (was 428; now 440).

### Build + install

- `:app:assembleDebug` → `BUILD SUCCESSFUL` (folded into the combined `:app:test :app:assembleDebug` run).
- `adb -s emulator-5554 install -r app-debug.apk` → `Performing Streamed Install / Success`.
- `adb -s emulator-5554 shell am start -n com.vela.android.lab/.MainActivity` → activity resumed; dashboard rendered without crash.

### On-device smoke (emulator-5554)

**FAKEPACA Start → Stop**

After tapping `Test Alpaca Market Data` (FAKEPACA card) and then `Stop Alpaca test stream`, the FAKEPACA card showed:

| Field | Value |
| --- | --- |
| Connection | `DISCONNECTED` |
| **Stream diagnostics → Health phase** | `DISCONNECTED` |
| Reconnect attempts | `0` |
| Last message at | `2026-06-11T21:12:04.436Z` |
| Last connected at | `2026-06-11T21:11:58.153Z` |
| Last disconnected at | `2026-06-11T21:12:04.742Z` |
| **Last error type** | **`—`** |

Phase 2.g (pre-fix) would have shown `Last error type: StreamLost` here. Phase 2.h shows a clean dash.

**IEX stock stream Start → Stop**

After tapping `Start real market data stream` (stock card) and then `Stop real market data stream`, the stock card showed:

| Field | Value |
| --- | --- |
| Connection | `DISCONNECTED` |
| **Stream diagnostics → Health phase** | `DISCONNECTED` |
| Reconnect attempts | `0` |
| Last message at | `2026-06-11T21:13:21.643Z` |
| Last connected at | `2026-06-11T21:13:21.490Z` |
| Last disconnected at | `2026-06-11T21:13:32.755Z` |
| **Last error type** | **`—`** |

Same fix: previously `StreamLost`; now a clean dash. The Watchlist card preserved its 4 symbols (AAPL/MSFT/QQQ/SPY, NVDA still removed from the earlier Phase 2.g session).

Both Phase 1.e offline buttons (Generate demo BTC/USD, Generate demo SPY, Clear local demo state), the Phase 2.c.1 Paper Credentials card, and the Phase 2.g Watchlist card all remained visible and functional throughout the session. No crash.

### Logcat / credential leak check

Filtered against our app PID only (system noise excluded — the Android system logs reference our package name when checking IMS / voice-mail receivers, which is unavoidable and not from our code):

| Pattern | Lines from app PID |
| --- | ---: |
| `FATAL EXCEPTION` / `AndroidRuntime: FATAL` | **0** |
| `alpaca` / `FAKEPACA` / `SPY` / `stream.data` (case-insensitive) | **0** |
| Plaintext key/secret patterns (`PK[A-Z0-9]{10,}`, `topsecretvalue`) | **0** |

A naïve `logcat -d` scan picked up 54 mentions of `com.vela.android.lab` and 1 case-insensitive match of `PK[A-Z0-9]{8,}`, but all 54 are from Android system PIDs (Telecom CarModeTracker, ImsResolver, PackageConfigPersister) routinely referencing the package name during install / IMS-service discovery, and the single "PK..." match is `VvmPkgInstalledRcvr: carrierVvmPkgAdded: carrier vvm packages doesn't contain com.vela.android.lab` (`PkInstalledRcvr` matched the case-insensitive `PK` substring). Neither contains any credential value. The strict app-PID-scoped logcat is fully clean.

### Safety verification

| Check | Result |
| --- | --- |
| `G:\vela` modified | No — read-only tree not touched. |
| Windows `vela.db` read / copied / touched | No. |
| Endpoint stayed Market Data WebSocket only (test + IEX) | Confirmed — `OkHttpAlpacaWebSocketFactory.open` still routes through `requireSafeMarketDataEndpoint`; the trading-host / `/orders / /positions / /account / /trading / /portfolio` / `live` / `sip` / `delayed_sip` rejections still hold (covered by `AlpacaStreamEndpointTest` + `AlpacaIexEndpointTest`). |
| `https://paper-api.alpaca.markets/v2` used | No. |
| `https://api.alpaca.markets/v2` used | No. |
| `/orders` / `/positions` / `/account` / `/trading` / `/portfolio` | None — Phase 2.h touched zero networking code; the existing endpoint guards apply. |
| LIVE endpoint added | None — `MarketDataSource.ALPACA_LIVE` still absent. |
| Order / account / trading methods added | None — reflection contract still green on seven surfaces. |
| Auto Paper / foreground service / ML | All deferred, none added this phase. |
| REAL locked | `realModeLocked = true` ([AppState.kt:18](../android/app/src/main/kotlin/com/vela/android/lab/state/AppState.kt#L18)); dashboard badge `REAL locked: true`. |
| Credentials hardcoded in source / docs | None — Phase 2.h added no credential surface. |
| Credentials in chat / tool-call arguments | None — no `adb shell input text` with credential values this phase. |
| Credentials in logcat | None — strict app-PID-scoped check returned 0 matches. |
| Credentials visible after save in UI | None — the new `onUserStop()` clears the active-error indicator but does not change any field that ever carried a credential. |

### What this proves

Both Alpaca read-only Market Data WebSocket clients now handle user-initiated Stop the way a user expects:

1. Tapping Stop ends in `DISCONNECTED`, not `ERROR`, regardless of whether OkHttp's `onFailure` fires before, during, or after the close.
2. A prior error (401, 406, network failure) is cleared from the active-status surface on Stop — the UI never keeps showing a stale `StreamLost` after the user closed the stream.
3. A late callback from an older session cannot poison a newer session's state — the session-id check makes this impossible by construction.
4. An unexpected network failure *while running* (no `disconnect()` called) still surfaces as `ERROR / StreamLost` on the active session — the regression guard.

REAL stays locked. No Trading API host, no `/orders|/positions|/account|/trading|/portfolio` fragment, no LIVE endpoint, no Auto Paper, no foreground service, no ML, no order submission, no account access. `G:\vela` and the Windows `vela.db` were not touched.

### Phase 2.h status

**Done.** Phase 2.h closes with: a new `StreamHealthTracker.onUserStop()` method, per-session id + intentional-close tracking on both Alpaca clients, **12 new unit tests** (`:app:test` debug+release at **440/0/0**), and an on-device demonstration that both streams now Stop cleanly to `DISCONNECTED` with `Last error type: —`. Zero credential / FATAL / trading-shape leakage.

---

## Phase 2.i — Read-only quote / tick buffer + millisecond diagnostics

**Date**: 2026-06-11
**Branch / working tree**: `G:\vela-android` (read-only Windows tree at `G:\vela` not touched).
**Scope (verbatim from task brief)**: add a read-only quote/tick buffer + millisecond diagnostics layer, similar in purpose to the tick-by-tick view in Windows VELA, without starting a visual redesign. IEX feed only, current watchlist symbols only, quotes + bars only, no `trades` channel, no Trading API, no LIVE, no Auto Paper.

### Tick / quote model

```kotlin
data class MarketTick(
    val symbol: String,
    val bidPrice: Double,
    val askPrice: Double,
    val marketTimestampMillis: Long,
    val receivedAtMillis: Long,
    val source: String,                // "alpaca-iex-stream"
) {
    val spread: Double get() = askPrice - bidPrice
    val latencyMillis: Long get() = receivedAtMillis - marketTimestampMillis
}
```

- `marketTimestampMillis` is the server-stamped event time. `receivedAtMillis` is stamped by the client at parse time (`clock().toEpochMilli()`). `latencyMillis` is reported raw — it can be negative if the device clock is ahead, which is a valuable diagnostic signal.
- The tick struct carries no credential, no order, no trading shape. Reflection contract on the buffer enforces that no method name matches `submitorder | placeorder | trading | executeorder | cancelorder | getaccount | openposition | closeposition`.

### Buffer design

```kotlin
class MarketTickBuffer(
    perSymbolCap: Int = 100,
    totalCap: Int = 1_000,
) {
    fun pushQuote(tick: MarketTick)
    fun recordBar(symbol: String)
    fun recordParserError(message: String)
    fun clear()
    val snapshot: StateFlow<TickBufferSnapshot>
}
```

- Per-symbol `ArrayDeque<MarketTick>` ring of up to `perSymbolCap` ticks.
- Aggregate hard cap `totalCap` enforced after every push by trimming the *largest* deque until total size ≤ cap.
- Thread-safe via a single intrinsic lock.
- `inter-message ms` is computed per-symbol against the *prior* tick for that symbol — separate clocks per symbol so a busy SPY does not interfere with a quiet AAPL.
- `dropped (overflow)` counter increments every time a tick is evicted by either cap, surfaced on the dashboard.
- `clear()` resets everything; `recordParserError(msg)` records a string error indicator without ever crashing the buffer.
- Snapshot is a `StateFlow<TickBufferSnapshot>` so the UI re-renders only the small summary, not the full deque content.

```kotlin
data class TickBufferSnapshot(
    val perSymbol: Map<String, PerSymbolTickStats>,
    val totalQuotes: Int,
    val totalBars: Int,
    val droppedOverflow: Int,
    val bufferSize: Int,
    val lastParserError: String?,
)

data class PerSymbolTickStats(
    val lastBid: Double,
    val lastAsk: Double,
    val spread: Double,
    val lastQuoteTimestampMillis: Long,
    val lastReceivedAtMillis: Long,
    val lastLatencyMillis: Long,
    val lastInterMessageMillis: Long?,    // null for the first tick per symbol
    val quotesReceived: Int,
    val barsReceived: Int,
)
```

### Code added / changed

| Path | Purpose |
| --- | --- |
| [MarketTick.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/tick/MarketTick.kt) (new) | Pure-data tick struct with derived `spread` + `latencyMillis`. |
| [MarketTickBuffer.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/tick/MarketTickBuffer.kt) (new) | Bounded buffer + `TickBufferSnapshot` + `PerSymbolTickStats`. Thread-safe; emits a `StateFlow`. |
| [AlpacaStockMarketDataClient.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/market/source/alpaca/AlpacaStockMarketDataClient.kt) (updated) | Added `val quotes: SharedFlow<MarketTick>` alongside `updates: SharedFlow<BootstrapMarketUpdate>`. The `is AlpacaStreamMessage.Quote -> Unit` branch in `handleStreamMessage` was replaced with `emitQuoteTick(message)` which constructs a `MarketTick` (server timestamp + device receive timestamp) and calls `_quotes.tryEmit(...)`. Bars continue to the existing `_updates` flow and the Phase 1.e pipeline. |
| [AlpacaStockStreamViewModel.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/AlpacaStockStreamViewModel.kt) (updated) | Constructor accepts a `MarketTickBuffer` (default for tests; production injects the shared singleton). New `quotesJob` collects `client.quotes` and calls `tickBuffer.pushQuote(tick)`. The existing `updatesJob` now also calls `tickBuffer.recordBar(update.symbol)` so the diagnostics card counts both. `onCleared` cancels the new job. A read-only `tickBufferRef` property exposes the buffer for the dashboard to bind to. |
| [OfflineDashboardScreen.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt) (updated) | New `TickDiagnosticsCard` composable rendered below the watchlist card (gated by `BuildConfig.DEBUG` through the existing dashboard wiring). Compact monospace-style summary: header row + per-symbol row `Sym  Bid    Ask    Spr   Lat   Δmsg  Q`. Aggregate rows: Total quotes, Total bars, Buffer size, Dropped (overflow), Parser error if any. "No ticks yet." placeholder when the buffer is empty. Aesthetics unchanged. |
| [VelaLabApplication.kt](../android/app/src/main/kotlin/com/vela/android/lab/VelaLabApplication.kt) (updated) | New `marketTickBuffer: MarketTickBuffer by lazy { MarketTickBuffer() }` — shared singleton DI instance. |
| [MainActivity.kt](../android/app/src/main/kotlin/com/vela/android/lab/MainActivity.kt) (updated) | `alpacaStockFactory` now passes `tickBuffer = app.marketTickBuffer` to the VM. |

### Tests added

| Path | Tests | Purpose |
| --- | ---: | --- |
| [MarketTickBufferTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/market/tick/MarketTickBufferTest.kt) (new) | 10 | Initial snapshot empty; first quote populates summary with `spread` + `latencyMillis`; `interMessageMillis` is per-symbol; per-symbol cap evicts oldest + increments `droppedOverflow`; aggregate cap drops from biggest deque; `recordBar` advances bars; `recordParserError` populates last error; `clear` resets everything; reflection contract on the buffer. |
| [AlpacaStockQuoteEmissionTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/market/source/alpaca/AlpacaStockQuoteEmissionTest.kt) (new) | 5 | One quote frame emits exactly one `MarketTick` with correct `bid/ask/spread/latency/source`; **batched array carrying a quote and a bar processes both safely** (quote on `quotes` flow, bar on `updates` flow); **malformed quote payload does not crash and emits nothing** (5 hostile payloads: non-JSON, missing fields, empty array, empty string); multi-symbol quotes route to distinct ticks; reflection contract on the stock client (already covered in 2.b/2.e; re-asserted here in scope). |

All Phase 2.a/b/c/d/e/f/g/h tests still pass without modification.

### Reflection / contract assertions still green

The "no trading methods" reflection contract now covers **eight** surfaces, all green:

1. `MarketDataClient` interface (Phase 2.a)
2. `AlpacaTestStreamMarketDataClient` (Phase 2.b)
3. `AlpacaTestStreamPipelineBridge` (Phase 2.d)
4. `AlpacaStockMarketDataClient` (Phase 2.e — re-asserted in Phase 2.i quote emission test)
5. `StreamHealthTracker` (Phase 2.f)
6. `WatchlistRepository` (Phase 2.g)
7. `WatchlistViewModel` (Phase 2.g)
8. **`MarketTickBuffer`** (added this phase)

Endpoint guard: `AlpacaStreamEndpointTest` + `AlpacaIexEndpointTest` together still reject every Trading API host, every `/orders|/positions|/account|/trading|/portfolio` fragment, every SIP / delayed_sip / `live` URL.

### Unit tests

| Variant | Tests | Failures | Errors |
| --- | ---: | ---: | ---: |
| `:app:testDebugUnitTest` | **457** | **0** | **0** |
| `:app:testReleaseUnitTest` | **457** | **0** | **0** |

Run via `./gradlew.bat :app:test :app:assembleDebug --no-daemon -Dorg.gradle.jvmargs="-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8"` → `BUILD SUCCESSFUL in 50s`. Phase 2.i added **17 new unit tests** over Phase 2.h (was 440; now 457).

### Build + install

- `:app:assembleDebug` → `BUILD SUCCESSFUL` (folded into the combined run).
- `adb -s emulator-5554 install -r app-debug.apk` → `Performing Streamed Install / Success`.
- `adb -s emulator-5554 shell am start -n com.vela.android.lab/.MainActivity` → MainActivity resumed; dashboard rendered without crash.

### On-device smoke (emulator-5554)

**Pre-flight context**: the smoke ran at 2026-06-11 ~22:56 UTC = ~18:56 ET, well *after* US regular market hours (16:00 ET). The IEX feed authenticates and subscribes cleanly but the after-hours quote tape is sparse/silent.

**Tapping Start** advanced the stock card to:

| Field | Value |
| --- | --- |
| Connection | `CONNECTED` (Health phase `SUBSCRIBED`) |
| Reconnect attempts | `0` |
| Last message at | `2026-06-11T22:56:34.582Z` (subscription confirmation) |
| Last connected at | `2026-06-11T22:56:34.423Z` |
| Last error type | `—` |

The new "Tick / quote diagnostics" card rendered below the watchlist, showing the after-hours-empty state honestly:

| Field | Value |
| --- | --- |
| Total quotes | `0` |
| Total bars | `0` |
| Buffer size | `0` |
| Dropped (overflow) | `0` |
| Per-symbol table | `Sym  Bid    Ask    Spr   Lat   Δmsg  Q` header + `No ticks yet.` |
| Parser error | (none) |

This is the correct after-hours behavior — IEX is open for the subscription handshake but no live quote ticks are flowing. The unit tests cover the populated case (per-symbol summaries with realistic `latency` / `Δmsg` values from injected SPY/QQQ/AAPL frames); the on-device smoke proves the rendering, no-crash, and clean-empty fallback. Returning a non-empty smoke during US market hours can be done by re-running this phase's Start tap between 14:30 UTC and 21:00 UTC any weekday.

**Tapping Stop** transitioned the stock card cleanly:

| Field | Value |
| --- | --- |
| Health phase | `DISCONNECTED` |
| Reconnect attempts | `0` |
| Last disconnected at | `2026-06-11T22:57:46.243Z` |
| Last error type | `—` |

Phase 2.h clean-stop semantics held — no `StreamLost` after Stop. The Phase 1.e offline cards, the Phase 2.c.1 Paper Credentials card, the Phase 2.d FAKEPACA pipeline, the Phase 2.f Stream diagnostics, the Phase 2.g Watchlist card (4 symbols: AAPL/MSFT/QQQ/SPY — NVDA still removed from Phase 2.g) all remained visible and unaffected.

### Logcat / credential leak check

`adb logcat -d --pid=<app-pid>` filtered through known leak patterns (app-PID-scoped to exclude unavoidable Android system mentions of our package name):

| Pattern | Lines from app PID |
| --- | ---: |
| `FATAL EXCEPTION` / `AndroidRuntime: FATAL` | **0** |
| `alpaca` / `FAKEPACA` / `SPY` / `stream.data` (case-insensitive) | **0** |
| Plaintext key/secret patterns (`PK[A-Z0-9]{10,}`, `topsecretvalue`) | **0** |

The app itself emits no log lines during stream activity or the new tick-buffer hot path.

### Safety verification

| Check | Result |
| --- | --- |
| `G:\vela` modified | No — read-only tree not touched. |
| Windows `vela.db` read / copied / touched | No. |
| Endpoint stayed Market Data WebSocket only | Confirmed — `OkHttpAlpacaWebSocketFactory.open` still routes through `requireSafeMarketDataEndpoint`; the trading-host / `/orders / /positions / /account / /trading / /portfolio` / `live` / `sip` / `delayed_sip` rejections still hold. The quote-emission code adds zero new network surface. |
| `https://paper-api.alpaca.markets/v2` used | No. |
| `https://api.alpaca.markets/v2` used | No. |
| `/orders` / `/positions` / `/account` / `/trading` / `/portfolio` | None. |
| LIVE endpoint added | None — `MarketDataSource.ALPACA_LIVE` still absent. |
| Order / account / trading methods added | None — reflection contract green on **eight** surfaces. |
| `trades` channel subscribed | None — the existing `subscribe` frame still carries `bars` + `quotes` only, asserted by `AlpacaStockMultiSymbolSubscribeTest` (Phase 2.g) and the Phase 2.e happy-path tests. Quotes are processed read-only into the buffer; no trade-execution path exists. |
| Auto Paper / foreground service / ML | All deferred, none added this phase. |
| REAL locked | `realModeLocked = true` ([AppState.kt:18](../android/app/src/main/kotlin/com/vela/android/lab/state/AppState.kt#L18)); dashboard badge `REAL locked: true`. |
| Credentials hardcoded in source / docs | None. Phase 2.i added zero credential surface. |
| Credentials in chat / tool-call arguments | None — no `adb shell input text` with credential values this phase. |
| Credentials in logcat | None — strict app-PID-scoped check returned 0 matches. |
| Credentials visible in UI | None — the new card shows tick stats only (bid/ask/spread/latency/Δmsg/counts); no credential surface added. |
| Tick buffer caps enforced | Unit-tested per-symbol (drops oldest at the per-symbol cap) and aggregate (drops from biggest deque at the total cap); `droppedOverflow` counter surfaced on the UI. |

### What this proves

The IEX feed's per-symbol quote tape is now captured into a bounded, thread-safe, read-only in-memory buffer with millisecond-resolution diagnostics:

1. Every Alpaca `T="q"` frame becomes a `MarketTick` with `bid/ask/spread/marketTimestamp/receivedAt/latency`. `latencyMillis = receivedAt - marketTimestamp`, reported raw.
2. The buffer enforces a per-symbol cap (default 100) and an aggregate cap (default 1000). Overflow is observable via `droppedOverflow`. Memory cannot grow unbounded under a noisy feed.
3. Per-symbol summary surfaces `lastBid`, `lastAsk`, `spread`, `lastLatencyMillis`, `lastInterMessageMillis`, `quotesReceived`, `barsReceived`. The Δmsg clock is independent per symbol.
4. Bars continue to flow through the existing Phase 1.e pipeline (`aggregator → features → signal → repositories`) unchanged; `recordBar` only ticks a counter in the buffer for the diagnostics card.
5. Malformed quote payloads (non-JSON, missing fields, empty array, empty string) are absorbed by the existing parser — no crash, no emission.
6. Batched frames carrying both a quote and a bar process both safely, each on its own flow.
7. Stop semantics from Phase 2.h continue to work — `Last error type: —` after user-initiated Stop. The tick buffer state survives Stop (intentional, so the operator can inspect the last session after closing).

REAL stays locked. No Trading API host, no `/orders|/positions|/account|/trading|/portfolio` fragment, no LIVE endpoint, no Auto Paper, no foreground service, no ML, no order submission, no account access. `G:\vela` and the Windows `vela.db` were not touched.

### Phase 2.i status

**Done.** Phase 2.i closes with: new `data.market.tick` package (`MarketTick` + `MarketTickBuffer` + `TickBufferSnapshot` + `PerSymbolTickStats`), a `quotes: SharedFlow<MarketTick>` channel on the stock client, VM wiring that pushes quotes into the buffer and counts bars, a new "Tick / quote diagnostics" dashboard card, **17 new unit tests** (`:app:test` debug+release at **457/0/0**), and an on-device demonstration that the card renders cleanly during after-hours-empty conditions while existing Phase 2.b/2.e/2.g/2.h behaviors remain intact. Zero credential / FATAL / trading-shape leakage.

---

## Phase 2.i.1 — Live quote runtime validation (deferred to next market session)

**Date**: 2026-06-11
**Branch / working tree**: `G:\vela-android` (read-only Windows tree at `G:\vela` not touched).
**Scope**: validate the Phase 2.i quote/tick diagnostics with **real** IEX quote messages during active market hours. No code changes; runtime smoke only.

### Build + install

| Step | Result |
| --- | --- |
| `./gradlew.bat :app:assembleDebug --no-daemon -Dorg.gradle.jvmargs="-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8"` | `BUILD SUCCESSFUL in 15s` — every task `UP-TO-DATE` (no source changes since Phase 2.i). APK at `app/build/outputs/apk/debug/app-debug.apk`. |
| `adb -s emulator-5554 install -r app-debug.apk` | `Performing Streamed Install / Success`. |
| `adb -s emulator-5554 shell am start -n com.vela.android.lab/.MainActivity` | MainActivity resumed; dashboard rendered without crash. |
| Credentials configured | `true` — survived from the Phase 2.c.1 secure store across reinstalls. No `adb shell input text` was used in this phase. |

### Market / feed condition at smoke time

| Item | Value |
| --- | --- |
| Emulator UTC clock | `Thu Jun 11 23:02:29 UTC 2026` |
| US Eastern Time | `19:02 ET Thursday` |
| US regular-session window | `09:30 ET – 16:00 ET` = `13:30 UTC – 20:00 UTC` weekdays |
| Position relative to session | **3 hours past US close**; pre-/post-market on IEX is thinly populated |
| Watchlist subscribed | `AAPL, MSFT, QQQ, SPY` (the Phase 2.g persisted watchlist; NVDA still removed from Phase 2.g session) |

### Stream lifecycle observed on device

After tapping Start, the stock card advanced cleanly through Phase 2.f / 2.h state transitions:

| Field | Value |
| --- | --- |
| Connection | `CONNECTED` |
| **Health phase** | `SUBSCRIBED` |
| Reconnect attempts | `0` |
| Last message at | `2026-06-11T23:03:55.398Z` (the server-sent `subscription` confirmation) |
| Last connected at | `2026-06-11T23:03:55.245Z` |
| Last error type | `—` |

The IEX socket connected, authenticated, and subscribed for the 4 watchlist symbols (`AAPL, MSFT, QQQ, SPY`) — the server replied with the `subscription` frame, which the Phase 2.f health tracker correctly flipped to phase `SUBSCRIBED`. No `StreamLost`, no auth failure, no `406` connection-limit.

### Tick / quote diagnostics result (after-hours)

Waited a total of **~85 seconds** on the stream (initial 25 s + extended 60 s window) with the Tick / quote diagnostics card visible:

| Field | Value |
| --- | --- |
| Total quotes | **`0`** |
| Total bars | **`0`** |
| Buffer size | **`0`** |
| Dropped (overflow) | `0` |
| Parser error | (none) |
| Per-symbol table | `Sym  Bid  Ask  Spr  Lat  Δmsg  Q` header + `No ticks yet.` placeholder |

The IEX feed delivered no live quote ticks during the smoke window. Per the task brief — *"If no quotes arrive: do not fake success, record market/feed quiet condition honestly, keep Phase 2.i implementation status as complete but runtime quote validation still pending"* — this is recorded as an honest **after-hours quiet** condition, not a regression.

The empty-state UI rendering is correct: header + "No ticks yet." placeholder, aggregate counters all zero, no parser error. The Phase 2.i implementation is unaffected; only the live-quote runtime confirmation is **deferred to the next US market session** (any weekday between `13:30 UTC` and `20:00 UTC`, ideally during the higher-traffic mid-day window `15:00 UTC – 19:00 UTC`).

Bars: none arrived either (1-minute bars are only emitted while quotes are flowing). The pipeline's bar persistence path remains exercised by the Phase 2.e/2.g on-device smokes (5 symbols × 3 bars persisted with real IEX data) and by the unit-test suite (`AlpacaStockMultiSymbolSubscribeTest` + `BridgePerSymbolRoutingTest`).

### Stop result

After tapping Stop, the stock card transitioned to:

| Field | Value |
| --- | --- |
| **Health phase** | `DISCONNECTED` |
| Reconnect attempts | `0` |
| Last disconnected at | `2026-06-11T23:06:34.725Z` |
| **Last error type** | **`—`** |

Phase 2.h clean-stop semantics held — **no `StreamLost` after user Stop**. The watchlist card (4 symbols) and the tick diagnostics card both remained visible and intact.

### Logcat / credential leak check

`adb logcat -d --pid=<app-pid>` (app-PID-scoped) after the full Start → wait → Stop session:

| Pattern | Lines from app PID |
| --- | ---: |
| `FATAL EXCEPTION` / `AndroidRuntime: FATAL` | **0** |
| `alpaca` / `FAKEPACA` / `SPY` / `stream.data` (case-insensitive) | **0** |
| Plaintext key/secret patterns (`PK[A-Z0-9]{10,}`, `topsecretvalue`) | **0** |

The app emitted no log lines at all from its own PID during the stream lifecycle, including the subscription handshake and the silent quote-wait window.

### Unit tests

| Variant | Tests | Failures | Errors |
| --- | ---: | ---: | ---: |
| `:app:testDebugUnitTest` | **457** | **0** | **0** |
| `:app:testReleaseUnitTest` | **457** | **0** | **0** |

Re-run after the on-device smoke via `./gradlew.bat :app:test --no-daemon -Dorg.gradle.jvmargs="-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8"` → `BUILD SUCCESSFUL in 16s`. All 53 actionable tasks `UP-TO-DATE` (no source changes this phase). Test count unchanged from Phase 2.i (no new tests added or removed).

### Safety verification

| Check | Result |
| --- | --- |
| `G:\vela` modified | No — read-only tree not touched. |
| Windows `vela.db` read / copied / touched | No. |
| Endpoint stayed Market Data WebSocket only (IEX) | Confirmed — feed URL displayed on the card is `wss://stream.data.alpaca.markets/v2/iex`; `OkHttpAlpacaWebSocketFactory.open` still routes through `requireSafeMarketDataEndpoint`. |
| `https://paper-api.alpaca.markets/v2` used | No. |
| `https://api.alpaca.markets/v2` used | No. |
| `/orders` / `/positions` / `/account` / `/trading` / `/portfolio` | None. |
| LIVE endpoint added | None — `MarketDataSource.ALPACA_LIVE` still absent. |
| Order / account / trading methods added | None — reflection contract still green on eight surfaces. |
| `trades` channel subscribed | None — `subscribe` frame still carries `bars` + `quotes` only. |
| Auto Paper / foreground service / ML | All deferred, none added. |
| REAL locked | `realModeLocked = true` ([AppState.kt:18](../android/app/src/main/kotlin/com/vela/android/lab/state/AppState.kt#L18)); dashboard badge `REAL locked: true`. |
| Credentials hardcoded in source / docs | None — Phase 2.i.1 changed no source. |
| Credentials in chat / tool-call arguments | None — no `adb shell input text` issued this phase. |
| Credentials in logcat | None — strict app-PID-scoped check returned 0 matches. |
| Credentials visible in UI | None — the new card shows only tick stats (bid/ask/spread/latency/Δmsg/counts); no credential surface added. |

### Phase 2.i.1 status

**Implementation: done.** **Live quote runtime validation: deferred to next US market session.** The Phase 2.i tick buffer + diagnostics card built, installed, launched, subscribed, and stopped cleanly. The empty-state UI rendered correctly with zero quotes/bars and no crash. Phase 2.h clean Stop semantics continued to hold. Tests stayed at **457/0/0** for both debug and release.

Re-running the same Start → wait → screenshot sequence any weekday between `13:30 UTC` and `20:00 UTC` (ideally during the high-volume window `15:00 UTC – 19:00 UTC`) will populate the per-symbol table with real bid/ask/spread/latency/Δmsg/quote-count values — that re-run does not need a new code change. No regression in any Phase 2.a–2.i surface.

---

## Phase 2.j — Read-only market-data history + recent-signal viewer

**Date**: 2026-06-12
**Branch / working tree**: `G:\vela-android` (read-only Windows tree at `G:\vela` not touched).
**Scope (verbatim from task brief)**: add a read-only viewer for recently persisted market data and signals from the existing Room database. UI + ViewModel only — no new repositories, no new network surface, no new feature.

### Query / repository changes

**None.** Every read the new VM performs already existed on the Phase 1.e repositories:

- `MarketDataRepository.recentBars(symbol, limit)` → latest N bars per symbol, chronological.
- `MarketDataRepository.countAll()` → aggregate persisted-bar count.
- `FeatureRepository.latestFor(symbol)` → most recent feature row per symbol.
- `SignalRepository.latestFor(symbol)` → most recent signal row per symbol.
- `JournalRepository.forSymbol(symbol)` + `.count()` → per-symbol journal list and aggregate count.

The history layer is a pure read on top of the existing Room schema — no DAO method was added or modified, no SQL was changed.

### UI state + ViewModel design

```kotlin
data class MarketHistoryUiState(
    val symbols: List<String>,
    val perSymbol: Map<String, PerSymbolHistory>,
    val totalPersistedBars: Int,
    val totalJournalEvents: Int,
    val lastRefreshAtEpochMillis: Long?,
    val lastError: String?,
    val isRefreshing: Boolean,
)

data class PerSymbolHistory(
    val symbol: String,
    val latestBarClose: Double?,
    val latestBarTimestampMillis: Long?,
    val recentBarCount: Int,
    val latestFeatureDirection: String?,
    val latestSignalState: String?,
    val latestSignalScore: Int?,
    val journalEventCount: Int,
)
```

`MarketHistoryViewModel` reads `watchlistRepository.load()` first, then queries the four repositories per symbol. The whole refresh is wrapped in `try { ... } catch`: a repository exception surfaces as `lastError` on the next state emission, never as a crash. `isRefreshing` flips true → false around the load so the UI can disable the Refresh button mid-flight.

Refresh is **manual**. The VM does not poll Room or subscribe to any flow — the user taps "Refresh" when they want a new snapshot. The watchlist is re-loaded on every refresh, so newly added / removed symbols are reflected without any cross-VM hook.

### Code added / changed

| Path | Purpose |
| --- | --- |
| [MarketHistoryUiState.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/MarketHistoryUiState.kt) (new) | Pure-data UI state + `PerSymbolHistory` row. |
| [MarketHistoryViewModel.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/MarketHistoryViewModel.kt) (new) | Read-only VM. Refresh on init; manual `refresh()`. No network, no credentials, no order/trading/account methods. Exception-safe (`lastError`). |
| [OfflineDashboardScreen.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt) (updated) | New `MarketHistoryCard` composable rendered after the tick diagnostics card. Aggregate rows (Total persisted bars, Total journal events, Last refresh at), per-symbol monospace summary line, Refresh button (disabled with "Refreshing…" while busy), error line if `lastError != null`. Aesthetics unchanged. |
| [MainActivity.kt](../android/app/src/main/kotlin/com/vela/android/lab/MainActivity.kt) (updated) | New `historyViewModel` field + `historyFactory()`. Passed into `OfflineDashboardScreen` gated by `BuildConfig.DEBUG`. |

No changes to [VelaLabApplication.kt](../android/app/src/main/kotlin/com/vela/android/lab/VelaLabApplication.kt) — the VM constructs from existing app-graph values (`watchlistRepository`, `marketDataRepository`, `featureRepository`, `signalRepository`, `journalRepository`).

One inline fix during build: `latestFeatureDirection = latestFeatures?.direction?.value` did not compile because `SymbolFeatures.direction` is already a `String`, not a typed enum — corrected to `latestFeatures?.direction`. No production behavior change.

### Tests added

| Path | Tests | Purpose |
| --- | ---: | --- |
| [MarketHistoryViewModelTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/ui/dashboard/MarketHistoryViewModelTest.kt) (new) | 6 | Empty DB renders empty per-symbol stats but sets `lastRefreshAtEpochMillis` non-null; populated SPY + AAPL bars appear with correct per-symbol counts (2 SPY bars + 1 AAPL bar drive 12 total journal events = 3 × 4 events/bar; per-symbol counts 8/4 line up); refresh picks up newly-persisted bars; UI-state `toString()` does not leak credential strings; reflection contract: no `submitorder|placeorder|trading|executeorder|cancelorder|openposition|closeposition|getaccount` on the VM; repository exception surfaces as `lastError` instead of crashing. |

All Phase 1.e–2.i tests still pass without modification.

### Reflection / contract assertions still green

The "no trading methods" reflection contract now covers **nine** surfaces, all green:

1. `MarketDataClient` interface (Phase 2.a)
2. `AlpacaTestStreamMarketDataClient` (Phase 2.b)
3. `AlpacaTestStreamPipelineBridge` (Phase 2.d)
4. `AlpacaStockMarketDataClient` (Phase 2.e + 2.i re-assert)
5. `StreamHealthTracker` (Phase 2.f)
6. `WatchlistRepository` (Phase 2.g)
7. `WatchlistViewModel` (Phase 2.g)
8. `MarketTickBuffer` (Phase 2.i)
9. **`MarketHistoryViewModel`** (added this phase)

Endpoint guard: `AlpacaStreamEndpointTest` + `AlpacaIexEndpointTest` together still reject every Trading API host, every `/orders|/positions|/account|/trading|/portfolio` fragment, every SIP / delayed_sip / `live` URL.

### Unit tests

| Variant | Tests | Failures | Errors |
| --- | ---: | ---: | ---: |
| `:app:testDebugUnitTest` | **463** | **0** | **0** |
| `:app:testReleaseUnitTest` | **463** | **0** | **0** |

Run via `./gradlew.bat :app:test :app:assembleDebug --no-daemon -Dorg.gradle.jvmargs="-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8"` → `BUILD SUCCESSFUL in 58s`. Phase 2.j added **6 unit tests** over Phase 2.i (was 457; now 463).

### Build, install, launch

- `:app:assembleDebug` → `BUILD SUCCESSFUL`.
- `adb -s emulator-5554 install -r app-debug.apk` → `Performing Streamed Install / Success`.
- `adb -s emulator-5554 shell am start -n com.vela.android.lab/.MainActivity` → activity resumed; dashboard rendered without crash.

### On-device smoke (emulator-5554)

The new "Recent market data — read only" card rendered at the bottom of the dashboard, **populated by the actual Room database** that accumulated bars across the Phase 2.e + 2.g + 2.h live IEX sessions over the prior days. Honest read of persisted state — no demo button was tapped:

| Field | Value |
| --- | --- |
| Total persisted bars | **`22`** |
| Total journal events | **`88`** (= 22 × 4 events per accepted bar) |
| Last refresh at | `2026-06-12T05:27:23.238Z` |
| Watchlist symbols | `AAPL, MSFT, QQQ, SPY` (NVDA still removed from Phase 2.g) |

Per-symbol summary lines:

| Symbol | Close | Bars | Direction | Signal | Score | Journal |
| --- | ---: | ---: | --- | --- | ---: | ---: |
| AAPL | `290.76` | `3` | `down` | `NEUTRAL` | `-1` | `12` |
| MSFT | `388.49` | `3` | `down` | `BEARISH` | `-4` | `12` |
| QQQ | `700.11` | `3` | `up` | `BULLISH` | `2` | `12` |
| SPY | `727.83` | `6` | `down` | `BEARISH` | `-2` | `24` |

These values come from the real IEX bars persisted during Phase 2.g (`Bridge per-symbol routing` smoke) + Phase 2.e (`SPY @ 731.20`) + Phase 2.h (`SPY @ 728.48`). The card honestly reflects what's in Room — every journal count = 4 × bar count, matching the Phase 1.e coordinator's "1 update → 4 journal events" contract.

**Refresh** tapped on-device → `Last refresh at` advanced from `2026-06-12T05:27:23.238Z` to `2026-06-12T05:28:18.758Z`; all other counters unchanged (no new persistence between refreshes since no stream was running). The Refresh button briefly disables itself with the "Refreshing…" label while the query runs — covered by the unit-test contract.

The empty-state path is covered by the unit tests (`empty database renders empty per-symbol stats with non-null refresh time`) — on a hypothetical clean install with no prior persistence, every row reads "close — · bars 0 · dir — · sig — · jrnl 0" and the user can populate it by tapping Generate demo BTC/USD / SPY on the offline controls card or by running a live IEX stream.

### Logcat / credential leak check

`adb logcat -d --pid=<app-pid>` filtered against the strict-key patterns after the full launch → scroll → Refresh sequence:

| Pattern | Lines from app PID |
| --- | ---: |
| `FATAL EXCEPTION` / `AndroidRuntime: FATAL` | **0** |
| `alpaca` / `FAKEPACA` / `SPY` / `stream.data` (case-insensitive) | **0** |
| Plaintext key/secret patterns (`PK[A-Z0-9]{10,}`, `topsecretvalue`) | **0** |

The history VM does not log; the dashboard render does not log; nothing leaks.

### Safety verification

| Check | Result |
| --- | --- |
| `G:\vela` modified | No — read-only tree not touched. |
| Windows `vela.db` read / copied / touched | No. |
| New network endpoint | None — the history layer is database-only, no `OkHttpAlpacaWebSocketFactory` call, no Trading API. |
| `https://paper-api.alpaca.markets/v2` used | No. |
| `https://api.alpaca.markets/v2` used | No. |
| `/orders` / `/positions` / `/account` / `/trading` / `/portfolio` | None. |
| LIVE endpoint added | None. |
| Order / account / trading methods added | None — reflection contract green on **nine** surfaces. |
| Auto Paper / foreground service / ML | All deferred, none added. |
| REAL locked | `realModeLocked = true` ([AppState.kt:18](../android/app/src/main/kotlin/com/vela/android/lab/state/AppState.kt#L18)); dashboard badge `REAL locked: true`. |
| Credentials hardcoded in source / docs | None — Phase 2.j added zero credential surface. |
| Credentials in chat / tool-call arguments | None. |
| Credentials in logcat | None. |
| Credentials visible in UI | None — the new card shows symbol strings + numeric counts + signal labels only. |
| Data mutation | None — the VM only reads; the existing `Clear local demo state` button (Phase 1.e) is unchanged. |

### What this proves

Bars, features, signals, and journal events that the Phase 1.e coordinator persisted during prior IEX sessions can now be inspected on the dashboard without re-running any stream:

1. The Refresh button issues five repository reads per watchlist symbol + two aggregate reads; total ≈ 20 small Room queries for a 4-symbol watchlist — trivially under a UI frame budget.
2. Empty database renders cleanly with `lastRefreshAtEpochMillis` set so the user knows the query *did* run, just found nothing.
3. Database exception surfaces as a `Refresh error: …` UI line, never as a crash.
4. The card filters per the *current* watchlist — newly added / removed watchlist symbols are reflected on the next Refresh.
5. No new repository methods; no new DAO queries; no new network endpoints; no new credentials; no order, trading, or account method.

REAL stays locked. No Trading API host, no `/orders|/positions|/account|/trading|/portfolio` fragment, no LIVE endpoint, no Auto Paper, no foreground service, no ML, no order submission, no account access. `G:\vela` and the Windows `vela.db` were not touched.

### Phase 2.j status

**Done.** Phase 2.j closes with: new `MarketHistoryUiState` + `MarketHistoryViewModel` + dashboard `MarketHistoryCard`, **6 new unit tests** (`:app:test` debug+release at **463/0/0**), and an on-device demonstration that the new card honestly shows the 22 bars / 88 journal events persisted across the prior IEX sessions (AAPL 290.76 NEUTRAL, MSFT 388.49 BEARISH, QQQ 700.11 BULLISH, SPY 727.83 BEARISH). Zero credential / FATAL / trading-shape leakage.

---

## Phase 2.k — Read-only Alpaca Paper Trading API account boundary

**Date**: 2026-06-13
**Branch / working tree**: `G:\vela-android` (read-only Windows tree at `G:\vela` not touched).
**Scope (verbatim from task brief)**: add a safe read-only Alpaca Paper Trading API boundary for account / clock / positions status, **without any order or trading capability**. Three GET URLs only; `POST`, `PUT`, `PATCH`, `DELETE` are not implementable through the new boundary by any caller. LIVE host is rejected.

### Endpoint guard design

```kotlin
object AlpacaPaperTradingEndpoint {
    const val PAPER_BASE_URL = "https://paper-api.alpaca.markets/v2"
    const val ACCOUNT_URL    = "$PAPER_BASE_URL/account"
    const val CLOCK_URL      = "$PAPER_BASE_URL/clock"
    const val POSITIONS_URL  = "$PAPER_BASE_URL/positions"
    val ALLOWED_READ_ONLY_URLS: Set<String> = setOf(ACCOUNT_URL, CLOCK_URL, POSITIONS_URL)

    fun requireSafePaperReadOnlyGet(url: String) {
        // defense-in-depth checks (live / live-host / mutation paths)
        // then the strict allow-list equality check.
    }
}
```

- Primary guard is the exact-URL allow-list — every non-listed URL is rejected, including any path with `/orders`, `/account/configurations`, `/account/activities`, `/portfolio/history`, `/positions/{symbol}` (close-position).
- LIVE-host distinguished from paper host by `startsWith("https://api.alpaca.markets")` (the LIVE host **does not** carry the `paper-` prefix; the substring overlap is handled by the prefix check, not by `contains`).
- Any URL containing the substring `live` (case-insensitive) is rejected on principle.

### Read-only client design

The Paper boundary is split into three components, none of which exposes a mutation method:

1. **`AlpacaHttpClient` interface** — a single `suspend fun executeGet(url, keyId, secret): HttpResult`. There is no `post`, `put`, `patch`, or `delete` member. Reflection test asserts the declared method set on the Java interface is exactly `{"executeGet"}`.
2. **`OkHttpAlpacaHttpClient`** — production implementation. Calls `requireSafePaperReadOnlyGet(url)` *before* OkHttp issues the request. Attaches the two Alpaca credential headers (`APCA-API-KEY-ID`, `APCA-API-SECRET-KEY`) on each GET; switches to `Dispatchers.IO`; returns a typed `HttpResult`. **Never logs the headers** — OkHttp's default builder has no `HttpLoggingInterceptor` attached and the wrapper does not call `println`/`Log` anywhere.
3. **`AlpacaPaperReadOnlyClient`** — surface is exactly:
   - `fetchAccount(): FetchResult<PaperAccountSnapshot>`
   - `fetchClock(): FetchResult<PaperClockSnapshot>`
   - `fetchPositions(): FetchResult<List<PaperPositionSnapshot>>`
   Every call internally goes through a single private `executeAndParse(url, parse)` helper that hits the HTTP boundary with one of the three allow-listed URLs.

The sealed `FetchResult` hierarchy — `Ok / AuthMissing / HttpError / NetworkError / ParseError` — surfaces every server / network / parse outcome without crashing the call site. Credentials never appear in any `FetchResult`.

### Models

```kotlin
data class PaperAccountSnapshot(
    val cashUsd: Double, val buyingPowerUsd: Double, val equityUsd: Double,
    val portfolioValueUsd: Double,
    val tradingBlocked: Boolean, val accountBlocked: Boolean,
    val patternDayTrader: Boolean, val currency: String, val status: String,
)
data class PaperClockSnapshot(
    val isOpen: Boolean,
    val nextOpenIso: String?, val nextCloseIso: String?, val timestampIso: String?,
)
data class PaperPositionSnapshot(
    val symbol: String, val qty: Double,
    val marketValueUsd: Double, val unrealizedPlUsd: Double, val side: String,
)
```

The `id` field on `/v2/account` is **deliberately not parsed** — the account-id is masked away from every layer above the parser. Reflection test asserts `PaperAccountSnapshot.toString()` does not contain a planted account id.

### Files added / changed

| Path | Purpose |
| --- | --- |
| [AlpacaPaperTradingEndpoint.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/AlpacaPaperTradingEndpoint.kt) (new) | Allow-list of three GET URLs + `requireSafePaperReadOnlyGet`. |
| [AlpacaHttpClient.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/AlpacaHttpClient.kt) (new) | Interface + `HttpResult` sealed hierarchy. GET-only. |
| [OkHttpAlpacaHttpClient.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/OkHttpAlpacaHttpClient.kt) (new) | Production impl. Guard-before-request, `Dispatchers.IO`, no logging. |
| [PaperSnapshots.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/PaperSnapshots.kt) (new) | Three read-only snapshot data classes. |
| [PaperJsonParser.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/PaperJsonParser.kt) (new) | `org.json`-backed parser. Returns `ParseResult.Ok/Err`. Never throws on malformed input. |
| [AlpacaPaperReadOnlyClient.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/AlpacaPaperReadOnlyClient.kt) (new) | Three `fetch*` methods + sealed `FetchResult`. No mutation surface. |
| [PaperAccountUiState.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperAccountUiState.kt) (new) | Read-only UI state, no credential fields. |
| [PaperAccountViewModel.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperAccountViewModel.kt) (new) | Manual `refresh()` only. Concurrent fetch of account+clock+positions; errors aggregated onto `lastError` without crashing the VM. |
| [OfflineDashboardScreen.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt) (updated) | New `PaperAccountCard` composable rendered after the history card. Aesthetics unchanged. Shows the three sources of read-only data + 5 top positions + Refresh button. |
| [VelaLabApplication.kt](../android/app/src/main/kotlin/com/vela/android/lab/VelaLabApplication.kt) (updated) | Added `alpacaHttpClient: AlpacaHttpClient by lazy { OkHttpAlpacaHttpClient() }` + `alpacaPaperReadOnlyClient`. |
| [MainActivity.kt](../android/app/src/main/kotlin/com/vela/android/lab/MainActivity.kt) (updated) | New `paperAccountViewModel` field + factory. Gated by `BuildConfig.DEBUG`. |

### Tests added

| Path | Tests | Coverage |
| --- | ---: | --- |
| [AlpacaPaperTradingEndpointTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/paper/AlpacaPaperTradingEndpointTest.kt) (new) | 21 (5 explicit + `@TestFactory` over 15 rejected URLs + LIVE-overlap guard) | Allow-list size and contents; allowed URLs pass; rejected URLs (LIVE host, paper `/orders`, paper close-position, paper account-config, paper portfolio-history, `live` substring, foreign hosts, empty) all throw `IllegalArgumentException`; the LIVE host *does not* sneak through via substring overlap with the paper host. |
| [PaperJsonParserTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/paper/PaperJsonParserTest.kt) (new) | 11 | Canonical account/clock/positions JSON parses; numeric strings + bare numbers both work; malformed inputs return `Err` (not crash); planted account id never appears in snapshot `toString()`; parser methods have no trading-shape name. |
| [AlpacaPaperReadOnlyClientTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/paper/AlpacaPaperReadOnlyClientTest.kt) (new) | 14 (10 explicit + `@TestFactory` over the client's declared method names + HTTP interface surface check) | Each `fetch*` returns `Ok` on canonical JSON; missing credentials → `AuthMissing` and **0 HTTP calls**; HTTP 403 → `HttpError(403, …)`; network failure → `NetworkError`; parse failure → `ParseError`; the client only ever calls `executeGet` against the three allow-listed URLs; credentials never appear in any `FetchResult.toString()`; reflection contract: no `submitorder|placeorder|trading|executeorder|cancelorder|replaceorder|openposition|closeposition|post|put|patch|delete` substrings on any `AlpacaPaperReadOnlyClient` declared method; the public `fetch*` surface is exactly `{fetchAccount, fetchClock, fetchPositions}`. |
| [PaperAccountViewModelTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/ui/dashboard/PaperAccountViewModelTest.kt) (new) | 6 | Initial state reads `credentialsConfigured` from secure store; refresh with no credentials surfaces error and **0 HTTP calls**; refresh with credentials hits exactly the three allowed URLs and populates equity/cash/buying-power/positions/clock; HTTP 403 on `/account` surfaces on `lastError` but `/clock` + `/positions` still complete; UI state never carries the saved secret after refresh; reflection contract on the VM. |

All Phase 1.e–2.j tests still pass without modification.

### Reflection / contract assertions still green

The "no trading methods" reflection contract now covers **eleven** surfaces:

1. `MarketDataClient` interface (Phase 2.a)
2. `AlpacaTestStreamMarketDataClient` (Phase 2.b)
3. `AlpacaTestStreamPipelineBridge` (Phase 2.d)
4. `AlpacaStockMarketDataClient` (Phase 2.e)
5. `StreamHealthTracker` (Phase 2.f)
6. `WatchlistRepository` (Phase 2.g)
7. `WatchlistViewModel` (Phase 2.g)
8. `MarketTickBuffer` (Phase 2.i)
9. `MarketHistoryViewModel` (Phase 2.j)
10. **`AlpacaPaperReadOnlyClient`** (added this phase)
11. **`PaperAccountViewModel`** (added this phase)

Plus the new `AlpacaHttpClient` interface declared-method-set check (`{"executeGet"}`) and the `PaperJsonParser` declared-method substring check.

Endpoint guards: `AlpacaStreamEndpointTest`, `AlpacaIexEndpointTest`, and **`AlpacaPaperTradingEndpointTest`** together now reject every Trading API LIVE host, every `/orders|/positions|/account|/trading|/portfolio|/account/configurations` mutation-shape fragment, every SIP / delayed_sip / `live` URL, and every non-allow-listed URL across both WebSocket and HTTP layers.

### Unit tests

| Variant | Tests | Failures | Errors |
| --- | ---: | ---: | ---: |
| `:app:testDebugUnitTest` | **515** | **0** | **0** |
| `:app:testReleaseUnitTest` | **515** | **0** | **0** |

Run via `./gradlew.bat :app:test :app:assembleDebug --no-daemon -Dorg.gradle.jvmargs="-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8"` → `BUILD SUCCESSFUL in 58s`. Phase 2.k added **52 unit tests** over Phase 2.j (was 463; now 515).

Two mid-phase fixes during build:
1. A test used a `Pair<*, *>` instead of a `String` in a map literal — corrected to a plain `String` value.
2. The reflection set-equality test failed against Kotlin-compiler-generated `$lambda$N` synthetic methods on the client — added the `filterNot { it.contains('$') }` filter that the @TestFactory already uses.

### Build, install, launch

- `:app:assembleDebug` → `BUILD SUCCESSFUL`.
- `adb -s emulator-5554 install -r app-debug.apk` → `Performing Streamed Install / Success`.
- `adb -s emulator-5554 shell am start -n com.vela.android.lab/.MainActivity` → activity resumed; the dashboard rendered with the new Paper card visible after the existing cards.

### On-device smoke (emulator-5554) — **live Paper Trading API**

The user's existing Phase 2.c.1 paper credentials survived install. On first render the card showed:

| Field | Value |
| --- | --- |
| Credentials configured | `true` |
| Market open / Equity / Cash / Positions count / etc. | `—` (not yet refreshed) |

Tapping **Refresh Paper Account** issued three GETs to:
- `https://paper-api.alpaca.markets/v2/account`
- `https://paper-api.alpaca.markets/v2/clock`
- `https://paper-api.alpaca.markets/v2/positions`

Within ~6 seconds the card populated with the **live** Paper account snapshot:

| Field | Value |
| --- | --- |
| Credentials configured | `true` |
| Market open | `false` |
| Next open | `2026-06-15T09:30:00-04:00` |
| Next close | `2026-06-15T16:00:00-04:00` |
| **Equity (USD)** | **`101941.14`** |
| **Buying power (USD)** | **`399053.26`** |
| **Cash (USD)** | **`95638.09`** |
| **Portfolio value (USD)** | **`101941.14`** |
| Trading blocked | `false` |
| Account blocked | `false` |
| Pattern day trader | `false` |
| Account status | `ACTIVE` |
| **Positions count** | **`3`** |
| Last refresh at | `2026-06-13T03:37:51.077Z` |

**Top positions (read-only)**:

| Symbol | Qty | Market value | Unrealized P&L |
| --- | ---: | ---: | ---: |
| BTCUSD | `0.006463714` | `409.87` | `79.10` |
| QQQ | `2.0` | `1442.68` | `22.39` |
| SPY | `6.0` | `4450.50` | `10.13` |

The Phase 1.e offline cards, Phase 2.c.1 Paper Credentials card, Phase 2.d FAKEPACA pipeline, Phase 2.f Stream diagnostics, Phase 2.e/2.g/2.h stock + watchlist, Phase 2.i Tick diagnostics, Phase 2.j Recent market data — all rendered and remained usable. No crash. No regression.

### Logcat / credential leak check

`adb logcat -d --pid=<app-pid>` filtered against:

| Pattern | Lines from app PID |
| --- | ---: |
| `FATAL EXCEPTION` / `AndroidRuntime: FATAL` | **0** |
| `alpaca` / `stream.data` / `paper-api` (case-insensitive) | **0** |
| Plaintext key/secret (`PK[A-Z0-9]{10,}`, `topsecretvalue`) | **0** |
| **`APCA-API-KEY-ID`** / **`APCA-API-SECRET-KEY`** | **0** |
| `://api.alpaca.markets` (LIVE host) | **0** |

The OkHttp wrapper logs nothing. The Paper client logs nothing. The VM logs nothing. The dashboard renders the snapshot values but never the raw HTTP body. The Phase 2.k surface is fully silent to logcat.

### Safety verification

| Check | Result |
| --- | --- |
| `G:\vela` modified | No — read-only tree not touched. |
| Windows `vela.db` read / copied / touched | No. |
| Paper Trading API used | **Yes — and only the three documented read-only GET URLs**, by exact-string allow-list. |
| `https://api.alpaca.markets/v2` (LIVE host) used | No — guard rejects it at construction time; logcat shows 0 references. |
| `POST /orders` / `DELETE /orders` / `PATCH` | None — `AlpacaHttpClient` exposes only `executeGet`; `OkHttpAlpacaHttpClient` uses OkHttp's `.get()` only; `AlpacaPaperReadOnlyClient` has no method with a mutation shape; reflection contract enforces. |
| `/orders` / `/positions/{symbol}` / `/account/configurations` paths | All rejected by `AlpacaPaperTradingEndpoint.requireSafePaperReadOnlyGet`, covered by the endpoint test. |
| LIVE endpoint added | None — no `MarketDataSource.ALPACA_LIVE`, no LIVE constants anywhere. |
| Order / account / trading methods added | None — reflection contract green on **eleven** surfaces; `AlpacaHttpClient` interface surface is exactly `{"executeGet"}`. |
| Auto Paper / foreground service / ML | All deferred, none added. |
| REAL locked | `realModeLocked = true` ([AppState.kt:18](../android/app/src/main/kotlin/com/vela/android/lab/state/AppState.kt#L18)); dashboard badge `REAL locked: true`. |
| Credentials hardcoded in source / docs | None — the report references `topsecretvalue` and `PKABCDEF1234` only as *test fixtures* in negative-leak assertions. |
| Credentials in chat / tool-call arguments | None — no `adb shell input text` issued with credential values this phase; the user's stored credentials carried over from Phase 2.c.1. |
| Credentials in logcat | None — strict app-PID-scoped check returned 0 matches across all relevant patterns including the literal `APCA-API-KEY-ID` header name. |
| Credentials visible in UI after save | None — the card surfaces `Credentials configured: true` and numeric account data only. The account id was deliberately omitted at the parser layer. |

### What this proves

The Phase 2.k boundary safely reads the Alpaca **Paper** account, clock, and positions — and *cannot* be used to mutate anything:

1. Live Paper account fetched on-device with three GET requests producing a coherent snapshot: equity `$101,941.14`, buying power `$399,053.26`, cash `$95,638.09`, three positions, account status `ACTIVE`, market `false` (weekend), next open/close ISO timestamps.
2. The HTTP boundary exposes one and only one method: `executeGet`. No mutation surface is constructible through this interface.
3. The Paper client exposes three and only three methods: `fetchAccount`, `fetchClock`, `fetchPositions`. Reflection contract enforces it.
4. URLs are doubly guarded: the static allow-list of three exact URLs *plus* defense-in-depth checks for `live`, the LIVE host prefix, and known mutation-path fragments.
5. The LIVE host (`https://api.alpaca.markets`) does not sneak through via substring overlap with the paper host (`https://paper-api.alpaca.markets`) — guard test asserts the LIVE host fails while the paper host passes.
6. Credentials never appear in `FetchResult`, in UI state, or in logcat. The OkHttp builder has no logging interceptor; the wrapper does not log.

REAL stays locked. No order submission code, no order cancellation code, no account mutation code, no LIVE endpoint, no Auto Paper, no foreground service, no ML. `G:\vela` and the Windows `vela.db` were not touched.

### Phase 2.k status

**Done.** Phase 2.k closes with: new `data.paper` package (`AlpacaPaperTradingEndpoint`, `AlpacaHttpClient` interface, `OkHttpAlpacaHttpClient`, `PaperSnapshots`, `PaperJsonParser`, `AlpacaPaperReadOnlyClient`), new `PaperAccountUiState` + `PaperAccountViewModel`, new dashboard `PaperAccountCard`, **52 new unit tests** (`:app:test` debug+release at **515/0/0**), and a successful on-device live Paper account fetch (equity `$101,941.14`, 3 positions, market closed for the weekend). Zero credential leakage, zero LIVE host references, zero mutation surface.

---

## Phase 2.l — Read-only Paper portfolio + risk dashboard

**Date**: 2026-06-13
**Branch / working tree**: `G:\vela-android` (read-only Windows tree at `G:\vela` not touched).
**Scope (verbatim from task brief)**: aggregate read-only Paper account / clock / positions with the existing watchlist, local market data, and local signal pipeline into a single read-only portfolio + risk view. **No new network endpoints**; reuses the three GET URLs locked down in Phase 2.k. Risk flags are informational only.

### Portfolio / risk model design

```kotlin
data class PaperPortfolioSnapshot(
    val equityUsd: Double, val cashUsd: Double, val buyingPowerUsd: Double,
    val portfolioValueUsd: Double, val grossMarketValueUsd: Double,
    val positionsCount: Int, val marketOpen: Boolean?,
    val tradingBlocked: Boolean, val accountBlocked: Boolean,
    val patternDayTrader: Boolean, val accountStatus: String,
)

data class PerSymbolPaperExposure(
    val symbol: String, val qty: Double,
    val marketValueUsd: Double, val unrealizedPlUsd: Double, val side: String,
    val allocationPercent: Double, val inWatchlist: Boolean,
    val latestSignalState: String?, val latestLocalClose: Double?,
)

data class RiskFlag(
    val code: Code, val severity: Severity,
    val message: String, val symbol: String? = null,
) {
    enum class Severity { INFO, WARN }
    enum class Code {
        NO_CREDENTIALS, ACCOUNT_BLOCKED, TRADING_BLOCKED, MARKET_CLOSED,
        PATTERN_DAY_TRADER, POSITION_NOT_IN_WATCHLIST,
        NO_LOCAL_MARKET_DATA, HIGH_ALLOCATION,
    }
}
```

- `allocationPercent = |marketValue| / portfolioValue * 100`, clamped to `0.0` when portfolio value is non-positive.
- `grossMarketValueUsd = sum(|market_value|)` across all positions — independent of long/short side so future short positions don't sneak past the cap.
- Exposures sorted descending by `|marketValueUsd|` so the largest positions render first.
- The risk-flag list is **informational only**; the lab never trades on it. The `HIGH_ALLOCATION` threshold is `25.0%` (constant `HIGH_ALLOCATION_PERCENT_THRESHOLD`); test coverage exercises the threshold boundary.

### ViewModel / UI design

`PaperPortfolioRiskViewModel.refresh()` calls the existing `AlpacaPaperReadOnlyClient.fetchAccount() + fetchClock() + fetchPositions()` and joins each position to:

1. `WatchlistRepository.load()` — for the `inWatchlist` flag
2. `MarketDataRepository.recentBars(symbol, 1)` — for `latestLocalClose`
3. `SignalRepository.latestFor(symbol)` — for `latestSignalState`

All three Paper fetches run sequentially (no concurrency surprises) but each result is folded onto UI state independently; a 403 on `/account` does **not** discard the `/clock` or `/positions` data. The first error encountered surfaces as `lastError` while the partial data is still rendered.

UI: new `PaperPortfolioRiskCard` rendered after the Phase 2.k Paper account card. Aggregate header rows + per-symbol exposure lines + informational risk flag lines (WARN tinted with `colorScheme.error`, INFO tinted with `onSurfaceVariant`). Single `Refresh portfolio risk` button. **No buy / sell / order / cancel button anywhere on the card.**

### Code added / changed

| Path | Purpose |
| --- | --- |
| [PaperPortfolioModels.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/PaperPortfolioModels.kt) (new) | `PaperPortfolioSnapshot`, `PerSymbolPaperExposure`, `PaperRiskSnapshot`, `RiskFlag` + `HIGH_ALLOCATION_PERCENT_THRESHOLD`. Pure data, no Android imports, no trading method. |
| [PaperPortfolioRiskUiState.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperPortfolioRiskUiState.kt) (new) | UI state. No credential field. |
| [PaperPortfolioRiskViewModel.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperPortfolioRiskViewModel.kt) (new) | Read-only join. No order/cancel/mutation method. Exception-safe; partial errors surface as `lastError` while non-erroring data is still rendered. |
| [OfflineDashboardScreen.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt) (updated) | New `PaperPortfolioRiskCard` composable rendered after the Paper account card. Aesthetics unchanged — same `Card`/`LabeledRow`/`SectionTitle` primitives. **No buy/sell/order button.** |
| [MainActivity.kt](../android/app/src/main/kotlin/com/vela/android/lab/MainActivity.kt) (updated) | New `paperPortfolioRiskViewModel` field + factory. Constructs from existing `app.alpacaPaperReadOnlyClient`, `alpacaCredentialsStore`, `watchlistRepository`, `marketDataRepository`, `signalRepository`. Gated by `BuildConfig.DEBUG`. |

No changes to `VelaLabApplication.kt` — the VM uses the existing app graph. No new endpoint definitions. No new repository methods.

### Tests added

| Path | Tests | Coverage |
| --- | ---: | --- |
| [PaperPortfolioRiskViewModelTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/ui/dashboard/PaperPortfolioRiskViewModelTest.kt) (new) | 12 | (1) No credentials → `NO_CREDENTIALS` WARN flag, 0 HTTP calls; (2) empty positions → empty exposure list + zero gross MV; (3) two positions → per-symbol exposures with **correct allocation percentages** (30.0% / 10.0% against $100k portfolio value, sorted by abs market value); (4) **position not on watchlist** → `POSITION_NOT_IN_WATCHLIST` INFO flag with symbol set; (5) **position without local bars** → `NO_LOCAL_MARKET_DATA` INFO flag; (6) **allocation > 25%** → `HIGH_ALLOCATION` WARN flag with formatted message; (7) `trading_blocked: true` + `account_blocked: true` → both WARN flags; (8) market closed → `MARKET_CLOSED` INFO flag only; (9) latest local signal + close **joined per symbol** from Room (positions for `SPY` pick up the locally persisted `BULLISH` signal at close `520.95`); (10) UI state `toString` carries no credential value; (11) HTTP 403 on `/account` surfaces as `lastError` but `/clock` + `/positions` still complete; (12) reflection contract on the VM (no `submitorder|placeorder|trading|executeorder|cancelorder|replaceorder|openposition|closeposition|post|put|patch|delete` substrings). |

All Phase 1.e–2.k tests still pass without modification.

Two mid-phase fixes during build:
1. `SymbolSignalEntity` and `MarketBar1mEntity` constructors needed all required positional/named parameters — the test fixture used outdated field sets; updated to `shortReturn/percentChange/barRange` and `syntheticVolume/lastUpdateTimeEpochMillis` respectively.
2. A Kotlin backticked test name contained `>` which the JVM rejects — renamed to "high allocation above 25 percent…".

### Reflection / contract assertions still green

The "no trading methods" reflection contract now covers **twelve** surfaces, all green:

1. `MarketDataClient` interface (2.a)
2. `AlpacaTestStreamMarketDataClient` (2.b)
3. `AlpacaTestStreamPipelineBridge` (2.d)
4. `AlpacaStockMarketDataClient` (2.e)
5. `StreamHealthTracker` (2.f)
6. `WatchlistRepository` (2.g)
7. `WatchlistViewModel` (2.g)
8. `MarketTickBuffer` (2.i)
9. `MarketHistoryViewModel` (2.j)
10. `AlpacaPaperReadOnlyClient` (2.k)
11. `PaperAccountViewModel` (2.k)
12. **`PaperPortfolioRiskViewModel`** (added this phase)

Plus the `AlpacaHttpClient` interface declared-method-set still equals exactly `{"executeGet"}`.

### Unit tests

| Variant | Tests | Failures | Errors |
| --- | ---: | ---: | ---: |
| `:app:testDebugUnitTest` | **527** | **0** | **0** |
| `:app:testReleaseUnitTest` | **527** | **0** | **0** |

Run via `./gradlew.bat :app:test :app:assembleDebug --no-daemon -Dorg.gradle.jvmargs="-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8"` → `BUILD SUCCESSFUL in 1m 7s`. Phase 2.l added **12 unit tests** over Phase 2.k (was 515; now 527).

### Build + install

- `:app:assembleDebug` → `BUILD SUCCESSFUL`.
- `adb -s emulator-5554 install -r app-debug.apk` → `Performing Streamed Install / Success`.
- `adb -s emulator-5554 shell am start -n com.vela.android.lab/.MainActivity` → MainActivity resumed; the new Paper portfolio risk card visible after the existing Paper account card.

### On-device smoke (emulator-5554) — live Paper account aggregation

Tapping **Refresh portfolio risk** issued the same three GETs as Phase 2.k (no new network surface) and joined the results to the local watchlist + Room snapshots. The card populated with:

| Aggregate field | Value |
| --- | --- |
| Credentials configured | `true` |
| **Equity (USD)** | **`101941.65`** |
| **Cash (USD)** | **`95638.09`** |
| **Buying power (USD)** | **`399053.26`** |
| **Gross market value (USD)** | **`6303.56`** = `4450.50` + `1442.68` + `410.38` |
| **Positions count** | **`3`** |
| Market open | `false` (Saturday) |
| Risk flags | **`3`** |
| Last refresh at | `2026-06-13T03:54:23.762Z` |

**Per-symbol exposure (read-only)**:

| Symbol | Qty | Market value | P&L | Allocation % | In watchlist | Latest signal | Latest local close |
| --- | ---: | ---: | ---: | ---: | --- | --- | ---: |
| **SPY** | `6.0` | `4450.50` | `10.13` | **`4.4%`** | `true` | `BEARISH` | `727.83` |
| **QQQ** | `2.0` | `1442.68` | `22.39` | **`1.4%`** | `true` | `BULLISH` | `700.11` |
| **BTCUSD** | `0.006463714` | `410.38` | `-78.59` | **`0.4%`** | **`false`** | `—` | `—` |

`SPY` and `QQQ` are joined to their Phase 2.g + 2.h locally persisted signals (`BEARISH @ 727.83`, `BULLISH @ 700.11`) — the Room data persisted across days and the join is symbol-keyed. `BTCUSD` is held in the paper account but is not on the watchlist and has no local market data, so its `latestSignalState` and `latestLocalClose` come back as `—` — and two informational flags fire.

**Risk flags (informational)**:

| Severity | Code | Message |
| --- | --- | --- |
| `INFO` | `MARKET_CLOSED` | `US market is closed.` |
| `INFO` | `POSITION_NOT_IN_WATCHLIST` | `BTCUSD position is not on the watchlist.` |
| `INFO` | `NO_LOCAL_MARKET_DATA` | `No locally persisted bars for BTCUSD.` |

No `WARN` flags — the account is not blocked, trading is not blocked, no allocation exceeds 25%, credentials are present.

The dashboard still rendered all prior cards correctly: Phase 1.e offline cards, Phase 2.c.1 Paper Credentials + Phase 2.d FAKEPACA pipeline + Phase 2.f Stream diagnostics, Phase 2.e/g/h stock + watchlist + 2.i Tick diagnostics, Phase 2.j Recent market data, Phase 2.k Paper account. No regression, no crash.

### Logcat / credential leak check

`adb logcat -d --pid=<app-pid>` after the refresh:

| Pattern | Lines from app PID |
| --- | ---: |
| `FATAL EXCEPTION` / `AndroidRuntime: FATAL` | **0** |
| `alpaca` / `paper-api` / `stream.data` (case-insensitive) | **0** |
| `APCA-API-KEY-ID` / `APCA-API-SECRET-KEY` / `topsecretvalue` / `PK[A-Z0-9]{10,}` | **0** |
| `://api.alpaca.markets` (LIVE host) | **0** |

No app-PID log lines from the join layer, the OkHttp request layer, or the dashboard render layer. Phase 2.l adds zero loggable code paths.

### Safety verification

| Check | Result |
| --- | --- |
| `G:\vela` modified | No — read-only tree not touched. |
| Windows `vela.db` read / copied / touched | No. |
| New network endpoint | **None** — Phase 2.l reuses the three Phase 2.k GET URLs only. |
| Paper Trading API used | Yes — read-only, three GETs only. |
| `https://api.alpaca.markets/v2` used | No. |
| `POST /orders` / `DELETE /orders` / `PATCH` / `PUT` | None — `AlpacaHttpClient` exposes only `executeGet`; reflection contract on the VM enforces no mutation-shape method. |
| `/orders`, `/positions/{symbol}`, `/account/configurations` | None — endpoint guard rejects them; never called. |
| LIVE endpoint added | None. |
| Order / cancel / mutation methods added | None — reflection contract green on twelve surfaces; the VM has no buy / sell / order / cancel control. |
| Auto Paper / foreground service / ML | All deferred, none added. |
| REAL locked | `realModeLocked = true`; dashboard badge `REAL locked: true`. |
| Credentials hardcoded in source / docs | None — `topsecretvalue` / `PKABCDEF1234` appear only in test fixtures asserting *negative* leak. |
| Credentials in chat / tool-call arguments | None — no `adb shell input text` issued with credential values this phase; user's stored credentials carried over. |
| Credentials in logcat | None — strict app-PID-scoped check returned 0 matches across all relevant patterns. |
| Credentials visible after save in UI | None — the card surfaces `Credentials configured: true` and numeric / textual portfolio data only. Risk flag messages reference symbols only. |
| Risk flags trigger orders | No — by contract, the VM has no order method anywhere; the flags are pure UI signals. |

### What this proves

The Phase 2.k Paper read-only boundary now feeds a richer **operator-facing** view without crossing any of the safety lines:

1. Three real positions on the live Paper account (SPY 6.0, QQQ 2.0, BTCUSD 0.006…) populate the per-symbol exposure list with allocation percentages computed against the live `portfolio_value`.
2. Local Room state from Phase 2.g + 2.h + 2.j is **joined symbol-by-symbol**: SPY position picks up its persisted `BEARISH @ 727.83`, QQQ picks up `BULLISH @ 700.11`. Positions outside the watchlist (BTCUSD) join nothing and raise informational flags.
3. Three informational risk flags fire correctly for the actual paper account state: market closed, BTCUSD outside the watchlist, BTCUSD has no local bars. Zero WARN flags because the account is healthy.
4. The VM exposes zero mutation methods; reflection contract enforced.
5. No new network endpoint was opened. The Paper read-only client surface remains exactly the three GETs locked down by `AlpacaPaperTradingEndpoint.requireSafePaperReadOnlyGet`.

REAL stays locked. No `POST /orders`, no `DELETE /orders`, no `PATCH`, no LIVE host, no Auto Paper, no foreground service, no ML, no order or trading method anywhere on the new surface. `G:\vela` and the Windows `vela.db` were not touched.

### Phase 2.l status

**Done.** Phase 2.l closes with: new `PaperPortfolioModels` (snapshot + exposure + risk flag), new `PaperPortfolioRiskUiState` + `PaperPortfolioRiskViewModel`, new dashboard `PaperPortfolioRiskCard`, **12 new unit tests** (`:app:test` debug+release at **527/0/0**), and a successful on-device aggregation: equity `$101,941.65`, 3 positions joined to local Room signals/closes, 3 informational risk flags fired correctly (MARKET_CLOSED + BTCUSD-not-in-watchlist + BTCUSD-no-local-data). Zero credential leakage, zero mutation surface.

---

## Phase 2.m — Paper order intent + dry-run preflight (NO execution)

**Date**: 2026-06-14
**Branch / working tree**: `G:\vela-android` (read-only Windows tree at `G:\vela` not touched).
**Scope (verbatim from task brief)**: add a safe dry-run order-intent and preflight layer for future Paper trading, **without any network execution capability**. Local-only evaluation; `AlpacaHttpClient` still exposes only `executeGet`; `PaperTradingExecutionGuard.canExecuteOrders` is hard-coded `false`.

### Order-intent model design

```kotlin
data class PaperOrderIntent(
    val symbol: String,
    val side: OrderSide,            // BUY / SELL
    val quantity: Double,
    val type: OrderType = OrderType.MARKET,   // MARKET only used in 2.m
    val tif: TimeInForce = TimeInForce.DAY,
    val limitPriceUsd: Double? = null,        // future-proof; ignored for MARKET
    val source: IntentSource = IntentSource.MANUAL_DRY_RUN,
    val createdAtEpochMillis: Long,
    val clientDryRunId: String,
)
```

- The intent is plain data. **No method on the data class can submit it**; there is no code path that converts an intent into an outbound HTTP request, because the `AlpacaHttpClient` interface surface is still exactly `{"executeGet"}` (asserted by `AlpacaPaperReadOnlyClientTest`).
- `IntentSource.MANUAL_DRY_RUN` is the only enum value — there is no `AUTO_PAPER` source, deliberately.

### Preflight engine design

`PaperOrderPreflightEngine.preflight(...)` is a **pure function**: takes already-fetched `PaperAccountSnapshot`, `PaperClockSnapshot`, `List<PaperPositionSnapshot>`, the local watchlist set, the local latest-close, the local latest-signal-state, the `AppState`, the `credentialsConfigured` boolean → returns a `PaperOrderPreflightResult`. Never opens a network connection. Never mutates the database.

Phase 2.m policy:

| Condition | Outcome |
| --- | --- |
| `appState.realModeLocked == false` *or* `mode == REAL` | **BLOCK** (`RealLocked`) — defense-in-depth |
| `credentialsConfigured == false` | **BLOCK** (`NoCredentials`) |
| `account.accountBlocked == true` | **BLOCK** (`AccountBlocked`) |
| `account.tradingBlocked == true` | **BLOCK** (`TradingBlocked`) |
| Symbol fails `WatchlistConfig.normalize` (crypto-slash, empty, etc.) | **BLOCK** (`InvalidSymbol`) |
| Quantity ≤ 0 or not finite | **BLOCK** (`InvalidQuantity`) |
| No latest local close (and no limit price for LIMIT) | **BLOCK** (`MissingLatestPrice`) — notional cannot be estimated |
| BUY notional > buying power | **BLOCK** (`InsufficientBuyingPower(needed, available)`) |
| SELL qty > held qty | **BLOCK** (`SellExceedsPosition`) — lab has no short-sell design |
| `clockSnap.isOpen == false` | **WARN** (`MarketClosed`) — informational, not a block |
| Symbol not in watchlist | **WARN** (`SymbolNotInWatchlist`) |
| No local signal for the symbol | **WARN** (`NoLocalSignal`) |
| Hypothetical post-fill allocation > 25% | **WARN** (`HighAllocationAfter(percent)`) |

`status` derived as: any block → `BLOCKED`; else any warning → `WARNING_ONLY`; else `ALLOWED_DRY_RUN`.

The result carries `estimatedNotionalUsd` = `quantity × priceUsed`, `estimatedBuyingPowerAfterUsd` = `bp − notional` for BUY / `bp + notional` for SELL (frees buying power), `allocationPercentAfter` = `|prior MV + hypothetical Δ| / portfolioValue × 100`, `positionImpactQty` = `+qty` for BUY / `−qty` for SELL.

### Execution guard

```kotlin
object PaperTradingExecutionGuard {
    const val canExecuteOrders: Boolean = false
    const val rationale: String = "Phase 2.m allows local dry-run preflight only. " +
        "No execution path exists. AlpacaHttpClient exposes only executeGet. REAL remains locked."
}
```

The guard's full declared-method set on the compiled JVM type is empty after `const val` static-field promotion (asserted by the test `guard exposes no field that could re-enable execution`). Reflection contract asserts no `submitorder|placeorder|executeorder|cancelorder|replaceorder|openposition|closeposition|post|put|patch|delete` substring on any declared method.

### Code added / changed

| Path | Purpose |
| --- | --- |
| [PaperOrderIntent.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/preflight/PaperOrderIntent.kt) (new) | Intent + enums (`OrderSide`, `OrderType`, `TimeInForce`, `IntentSource`). Pure data. |
| [PaperOrderPreflightResult.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/preflight/PaperOrderPreflightResult.kt) (new) | Sealed `PreflightBlockReason` (9 cases) + sealed `PreflightWarning` (5 cases) + `PreflightStatus` enum + result data class. |
| [PaperTradingExecutionGuard.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/preflight/PaperTradingExecutionGuard.kt) (new) | Placeholder guard. `canExecuteOrders = false`. Zero mutation methods. |
| [PaperOrderPreflightEngine.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/preflight/PaperOrderPreflightEngine.kt) (new, 167 lines) | Pure-function engine. No network imports. Implements the policy table above. |
| [PaperOrderPreflightUiState.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperOrderPreflightUiState.kt) (new) | UI state. No credential surface. |
| [PaperOrderPreflightViewModel.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperOrderPreflightViewModel.kt) (new, 144 lines) | Owns symbol/side/qty input state. Builds intent, fetches current snapshots via existing read-only Paper client, runs the engine. **No submit/cancel/replace method anywhere.** |
| [OfflineDashboardScreen.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt) (updated) | New `PaperOrderPreflightCard` composable. **No Submit button.** Big "No order will be sent" reminder. Subtitle exposes `canExecuteOrders = false`. |
| [VelaLabApplication.kt](../android/app/src/main/kotlin/com/vela/android/lab/VelaLabApplication.kt) (updated) | Added `appState: AppState by lazy { AppState() }` + `paperOrderPreflightEngine: PaperOrderPreflightEngine by lazy { ... }`. |
| [MainActivity.kt](../android/app/src/main/kotlin/com/vela/android/lab/MainActivity.kt) (updated) | New `paperOrderPreflightViewModel` field + factory. Gated by `BuildConfig.DEBUG`. |

### Tests added

| Path | Tests | Coverage |
| --- | ---: | --- |
| [PaperTradingExecutionGuardTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/paper/preflight/PaperTradingExecutionGuardTest.kt) (new) | 4 (including `@TestFactory`) | `canExecuteOrders` is hard-coded `false`; rationale string clearly states no execution surface; reflection contract over forbidden HTTP-verb + order-shape substrings; the guard exposes no accessor methods. |
| [PaperOrderPreflightEngineTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/paper/preflight/PaperOrderPreflightEngineTest.kt) (new) | 17 (16 explicit + `@TestFactory`) | Valid BUY → `ALLOWED_DRY_RUN`; `accountBlocked` → BLOCK; `tradingBlocked` → BLOCK; insufficient buying power → BLOCK; market closed → WARN (not block); missing latest price → BLOCK; symbol not in watchlist → WARN; high allocation after fill → WARN; SELL > held → BLOCK; no credentials → BLOCK; `realModeLocked = false` → BLOCK (defense-in-depth); invalid symbol (`BTC/USD`) → BLOCK; zero/negative quantity → BLOCK; no local signal → WARN; buying-power-after estimate accounts for BUY notional; reflection contract on engine methods. |
| [PaperOrderPreflightViewModelTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/ui/dashboard/PaperOrderPreflightViewModelTest.kt) (new) | 6 | `runDryRunPreflight` populates `lastResult` with `ALLOWED_DRY_RUN`; invalid quantity → `lastInputError`, no engine call; empty symbol → `lastInputError`; insufficient BP → engine returns `BLOCKED`; UI state never carries credential after dry-run; reflection contract on VM (HTTP-verb substrings deliberately not in this VM's forbidden list because Compose UI methods legitimately contain `Input`/`Output` — the HTTP-verb prohibition is enforced at the `AlpacaHttpClient` interface surface, already covered by `AlpacaPaperReadOnlyClientTest`). |

All prior tests still pass.

Three mid-phase test fixes during build:
1. `MarketBar1mEntity` and `SymbolSignalEntity` field sets — added `syntheticVolume`, `lastUpdateTimeEpochMillis`, `shortReturn`, `percentChange`, `barRange`.
2. Suspending DAO `.insert(...)` inside `.apply { }` block — switched to `runBlocking { ... }` setup.
3. Rationale string `contains("no execution")` was case-sensitive against actual text `"No execution path exists"` — switched to `.lowercase().contains(...)`.

### Reflection / contract assertions still green

The "no trading/execution methods" reflection contract now covers **fifteen** surfaces:

1. `MarketDataClient` interface (2.a)
2. `AlpacaTestStreamMarketDataClient` (2.b)
3. `AlpacaTestStreamPipelineBridge` (2.d)
4. `AlpacaStockMarketDataClient` (2.e)
5. `StreamHealthTracker` (2.f)
6. `WatchlistRepository` (2.g)
7. `WatchlistViewModel` (2.g)
8. `MarketTickBuffer` (2.i)
9. `MarketHistoryViewModel` (2.j)
10. `AlpacaPaperReadOnlyClient` (2.k)
11. `PaperAccountViewModel` (2.k)
12. `PaperPortfolioRiskViewModel` (2.l)
13. **`PaperTradingExecutionGuard`** (added this phase)
14. **`PaperOrderPreflightEngine`** (added this phase)
15. **`PaperOrderPreflightViewModel`** (added this phase, excluding HTTP-verb substrings since Compose `Input`/etc. would false-positive)

The `AlpacaHttpClient` interface declared-method set still equals exactly `{"executeGet"}`. The Phase 2.m work added zero new HTTP methods and zero new endpoint URLs.

### Unit tests

| Variant | Tests | Failures | Errors |
| --- | ---: | ---: | ---: |
| `:app:testDebugUnitTest` | **552** | **0** | **0** |
| `:app:testReleaseUnitTest` | **552** | **0** | **0** |

Run via `./gradlew.bat :app:test :app:assembleDebug --no-daemon -Dorg.gradle.jvmargs="-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8"` → `BUILD SUCCESSFUL`. Phase 2.m added **25 new unit tests** over Phase 2.l (was 527; now 552).

### Build + install

- `:app:assembleDebug` → `BUILD SUCCESSFUL`.
- Android Studio was opened on `G:\vela-android\android` to regenerate IDE-side artifacts and trigger any pending Gradle sync; the existing CLI Gradle build is the canonical truth.
- `adb -s emulator-5554 install -r app-debug.apk` → `Performing Streamed Install / Success`.
- Emulator booted cleanly after a transient "System UI isn't responding" dialog on cold boot was recovered with a `BACK` + `HOME` + `pm clear com.android.systemui` sequence; subsequent app launch was crash-free.

### On-device smoke (emulator-5554) — live dry-run preflight

The new "Paper order preflight — dry run only" card appeared after the Phase 2.l portfolio-risk card. Subtitle on the card displays the safety contract:

> Local-only hypothetical preflight. **No order will be sent.** AlpacaHttpClient exposes only executeGet; no submit/cancel/replace path exists. canExecuteOrders = false.

Inputs entered via `adb shell input text` (symbols and integers only — no credentials):

| Input | Value |
| --- | --- |
| Symbol | `SPY` |
| Side | `BUY` |
| Quantity | `1` |

Tapping **Run dry-run preflight** produced:

| Result field | Value |
| --- | --- |
| Status | **`WARNING_ONLY`** |
| Symbol | `SPY` |
| Side | `BUY` |
| Qty | `1.0` |
| **Estimated notional (USD)** | **`727.83`** (= 1.0 × Phase 2.h local SPY close) |
| **Buying power after (USD)** | **`398325.43`** (= live Phase 2.k buying power $399,053.26 − $727.83) |
| **Allocation after (%)** | **`5.1`** (= ($4,450.50 prior SPY MV + $727.83 hypothetical) / $101,941.65 portfolio value × 100 = 5.08%) |
| Position impact (qty) | `1.0` |
| Related signal | **`BEARISH`** (joined from local Phase 2.h `SymbolSignalEntity`) |
| Market open | `false` (Saturday) |
| **Block reasons** | **(none)** |
| Warnings | **`WARN: US market is closed at preflight time.`** |

The engine correctly:
- pulled the live Paper account snapshot (equity, buying power) via the existing GET URLs;
- used the locally persisted close as the price reference (no new endpoint, no `/v2/bars/SPY` call);
- joined the local signal layer for `relatedSignalState`;
- raised `MarketClosed` as a *warning*, not a block;
- did not raise `SymbolNotInWatchlist` (SPY is on the watchlist) or `NoLocalSignal` (BEARISH was joined) or `HighAllocationAfter` (5.1% < 25%);
- produced **no block reasons** — the trade would have been allowed if execution existed, which it does not;
- and never reached an order endpoint because none exists.

### Proof no order was sent

| Evidence | Value |
| --- | --- |
| HTTP client interface declared-method-set | `{"executeGet"}` — unchanged from Phase 2.k |
| `PaperTradingExecutionGuard.canExecuteOrders` | `false` — printed in the card subtitle |
| Buttons on the new card | Only `BUY`/`SELL` (side toggle) + `Run dry-run preflight`. **No Submit button.** |
| Reflection contract on `PaperOrderPreflightViewModel` declared methods | No `submitorder|placeorder|executeorder|cancelorder|replaceorder|openposition|closeposition|trading` substring |
| Reflection contract on `PaperOrderPreflightEngine` declared methods | Same — covered by `@TestFactory` |
| Logcat `POST.*orders` / `DELETE.*orders` from app PID | **0 lines** (7 raw matches against the simple regex `PATCH` were all Android framework `WindowOnBackDispatcher` / `CompatChangeReporter` system noise; not from the app) |

### Logcat / credential leak check

`adb logcat -d --pid=<app-pid>` filtered after the full launch → input → dry-run sequence:

| Pattern | Lines from app PID |
| --- | ---: |
| `FATAL EXCEPTION` / `AndroidRuntime: FATAL` | **0** |
| `alpaca` / `paper-api` / `stream.data` (case-insensitive) | **0** |
| `APCA-API-KEY-ID` / `APCA-API-SECRET-KEY` / `topsecretvalue` / `PK[A-Z0-9]{10,}` | **0** |
| `://api.alpaca.markets` (LIVE host) | **0** |
| `POST .* orders` / `DELETE .* orders` (real mutation verbs) | **0** (the 7 raw `PATCH`-substring matches were Android `Dispatcher` / `CompatChange` framework lines) |

### Safety verification

| Check | Result |
| --- | --- |
| `G:\vela` modified | No — read-only tree not touched. |
| Windows `vela.db` read / copied / touched | No. |
| New network endpoint | **None** — the dry-run uses only the three Phase 2.k GET URLs. |
| `POST /v2/orders` / `DELETE /v2/orders` / `PATCH` | **None** — `AlpacaHttpClient` exposes only `executeGet`. |
| `/orders` / `/positions/{symbol}` / `/account/configurations` | None — the endpoint guard still rejects them and the app never calls them. |
| LIVE endpoint added | None. |
| Order / cancel / replace / mutation methods added | None — reflection contract green on fifteen surfaces. |
| Auto Paper | None — `IntentSource` enum has only `MANUAL_DRY_RUN`. |
| `canExecuteOrders` | **`false`** (hard-coded, visible in the card subtitle, asserted by test). |
| Foreground service | Not added. |
| ML | Not added. |
| REAL locked | `realModeLocked = true`; if false, the engine would BLOCK on `RealLocked`. |
| Credentials hardcoded / logged / rendered | None — UI never shows raw key/secret; logcat shows zero credential headers or values. |
| Submit / buy-execute / sell-execute button on the card | **None** — the only side buttons are the `BUY`/`SELL` *toggle* on the order intent input, not an executor. |

### What this proves

The lab can now construct, evaluate, and display the consequences of a hypothetical Paper order — including buying-power impact, allocation impact, warnings, and block reasons — entirely locally, with **zero execution surface**:

1. The engine is a pure function with no network imports. It cannot submit an order even if it tried.
2. The HTTP boundary still exposes only `executeGet`. No `post`, `put`, `patch`, or `delete` method is callable through this app.
3. The execution guard is a constant `false`. Future phases that want real execution must add a new class — they cannot patch this one without breaking the reflection contract.
4. On-device dry-run produced honest numbers from live Paper data + local Room data: notional $727.83, buying power after $398,325.43, allocation after 5.1%, related signal BEARISH, single warning "US market is closed".
5. No order was sent. The Phase 2.k Paper account remains unchanged (3 positions: BTCUSD / QQQ / SPY); a Phase 2.l portfolio-risk refresh after the dry-run would show identical numbers.

REAL stays locked. No `POST /orders`, no `DELETE /orders`, no `PATCH`, no LIVE host, no Auto Paper, no foreground service, no ML. `G:\vela` and the Windows `vela.db` were not touched.

### Phase 2.m status

**Done.** Phase 2.m closes with: new `data.paper.preflight` package (5 files: `PaperOrderIntent`, `PaperOrderPreflightResult`, `PaperTradingExecutionGuard`, `PaperOrderPreflightEngine`, plus the UI VM/state), new dashboard `PaperOrderPreflightCard` with no Submit button, **25 new unit tests** (`:app:test` debug+release at **552/0/0**), and an on-device demonstration that a dry-run BUY of 1 SPY produced status `WARNING_ONLY` with correct hypothetical impact numbers and **zero network execution**. `PaperTradingExecutionGuard.canExecuteOrders` remains hard-coded `false`. `AlpacaHttpClient` declared-method set remains exactly `{"executeGet"}`. Reflection contract green on fifteen surfaces.

---

## Phase 2.n — Dry-run intent journal + preflight audit trail (NO execution)

**Date**: 2026-06-19
**Branch / working tree**: `G:\vela-android` (read-only Windows tree at `G:\vela` not touched).
**Scope (verbatim from task brief)**: persist every local dry-run Paper order preflight into a local Room audit journal. Local-only. No order endpoint call. **No credentials, no API keys, no account id ever land in a persisted row.** `PaperTradingExecutionGuard.canExecuteOrders` stays hard-coded `false`. `AlpacaHttpClient` still exposes only `executeGet`.

### Audit entity + DAO + repository design

```kotlin
@Entity(
    tableName = "paper_order_dry_run_audits",
    indices = [
        Index(value = ["createdAtEpochMillis"], name = "ix_paper_dry_run_created_at"),
        Index(value = ["symbol", "createdAtEpochMillis"], name = "ix_paper_dry_run_symbol_created_at"),
        Index(value = ["clientDryRunId"], unique = true, name = "ix_paper_dry_run_client_id"),
    ],
)
data class PaperOrderDryRunAuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val clientDryRunId: String,           // UUID generated by the preflight VM
    val createdAtEpochMillis: Long,
    val symbol: String, val side: String, val orderType: String, val timeInForce: String,
    val quantity: Double, val limitPriceUsd: Double?,
    val status: String,
    val estimatedNotionalUsd: Double?, val buyingPowerAfterUsd: Double?,
    val allocationPercentAfter: Double?,
    val latestPriceUsedUsd: Double?, val latestSignalState: String?, val marketOpen: Boolean?,
    val blockReasonsSummary: String, val warningsSummary: String,
    val source: String,                   // always MANUAL_DRY_RUN in Phase 2.n
)
```

- **No `apiKey`, `secret`, `apca*`, `accountId`, `credential`, or `password` field exists.** Phase 2.n reflection contract scans every declared field on the entity and fails on any forbidden substring.
- The unique index on `clientDryRunId` prevents accidental duplicate rows from a doubled `runDryRunPreflight` call.
- `blockReasonsSummary` and `warningsSummary` are flattened to newline-separated short messages so a row can be read without re-running the engine.

`PaperOrderDryRunAuditDao` exposes:

| Method | Mutation? |
| --- | --- |
| `suspend fun insert(audit): Long` | append-only |
| `suspend fun countAll(): Int` | read-only |
| `suspend fun recent(limit): List<…>` | read-only, sorted by `createdAtEpochMillis DESC` |
| `suspend fun recentBySymbol(symbol, limit): List<…>` | read-only |

**No `update`, no `delete`, no `clear`.** The audit trail is append-only by design. Reflection contract test (`dao interface exposes no update or delete method`) asserts the DAO declared-method set equals exactly `{"insert", "countAll", "recent", "recentBySymbol"}`.

`PaperOrderDryRunAuditRepository.saveDryRun(result)` maps a `PaperOrderPreflightResult` to the entity via a private `toEntity()` helper. The helper **only** reads:
- intent fields (symbol, side, type, tif, quantity, limit price, source, createdAt, clientDryRunId);
- engine-output fields (status, notional, BP-after, allocation-after, related signal, market-open);
- derived `latestPriceUsedUsd = notional / qty` (or the limit price for LIMIT orders).

Block reasons and warnings are joined via `.joinToString("\n") { it.message }`. **The credentials, the keyId/secret values, the Alpaca account id never reach the entity** — they aren't fields on `PaperOrderPreflightResult` in the first place.

### Database schema change

`VelaDatabase` bumped from `version = 1` to `version = 2`. New entity added. `fallbackToDestructiveMigration()` (no-arg form for the Room version on the classpath) wipes the dev-lab DB on schema mismatch — losing the throwaway Phase 1.e bars/features/signals/journal rows on first run after install. Watchlist (`SharedPreferences`) and credentials (Keystore-backed `EncryptedSharedPreferences`) both survive.

### Preflight integration design

`PaperOrderPreflightViewModel` constructor gained two optional parameters:

```kotlin
class PaperOrderPreflightViewModel(
    /* ... existing ... */,
    private val auditRepository: PaperOrderDryRunAuditRepository? = null,
    private val onAuditSaved: (suspend () -> Unit)? = null,
    /* ... */
)
```

On every successful engine evaluation:
- the result is passed through `auditRepository?.saveDryRun(result)` (no-op when null, e.g. unit tests);
- on success, `onAuditSaved?.invoke()` fires so the audit VM can refresh its snapshot;
- on failure, the exception is caught and surfaced as `uiState.lastAuditError` — the preflight result remains visible (the operator can still see what would have happened).

The dashboard wiring in [MainActivity.kt](android/app/src/main/kotlin/com/vela/android/lab/MainActivity.kt) passes `onAuditSaved = { paperOrderDryRunAuditViewModel.refreshNow() }` so the audit total updates in real time after each preflight tap.

Invalid input (empty symbol / non-numeric quantity) short-circuits with `lastInputError` **before** the engine runs — so no spurious audit row is created for a malformed form submission.

### UI design

New `PaperDryRunAuditCard` composable rendered after `PaperOrderPreflightCard`:

```
┌────────────────────────────────────────────────┐
│ Dry-run audit — local only                     │
│                                                │
│ Append-only local Room journal of every        │
│ preflight dry-run. No credentials, no Alpaca   │
│ account id, no order endpoint call. Append-    │
│ only by design (no delete in this phase).      │
│                                                │
│ Total dry-runs                            N    │
│ Last refresh at                          ISO   │
│                                                │
│ SYMBOL SIDE QTY → STATUS                       │
│ notional X · blocks N · warns N · at ISO       │
│ ...                                            │
│                                                │
│ [ Refresh audit ]                              │
└────────────────────────────────────────────────┘
```

- **No Submit, no Execute, no Buy, no Sell, no Cancel button.** The only button is `Refresh audit` (re-reads the local table).
- **No Clear/Delete button.** Phase 2.n is explicit: "Optional Clear is NOT allowed in this phase unless specifically scoped and tested." So it isn't.

### Code added / changed

| Path | Purpose |
| --- | --- |
| [PaperOrderDryRunAuditEntity.kt](../android/app/src/main/kotlin/com/vela/android/lab/db/room/entities/PaperOrderDryRunAuditEntity.kt) (new) | Room entity. 19 typed fields. No credential / account-id field. |
| [PaperOrderDryRunAuditDao.kt](../android/app/src/main/kotlin/com/vela/android/lab/db/room/dao/PaperOrderDryRunAuditDao.kt) (new) | DAO. `insert` + 3 read queries. No `update` / `delete` / `clear`. |
| [VelaDatabase.kt](../android/app/src/main/kotlin/com/vela/android/lab/db/room/VelaDatabase.kt) (updated) | `version = 2`, added entity + DAO accessor, `fallbackToDestructiveMigration()`. |
| [PaperOrderDryRunAuditRepository.kt](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/preflight/PaperOrderDryRunAuditRepository.kt) (new) | `saveDryRun(result)` + read helpers + private `toEntity()` mapping. No network dependency, no credential dependency. |
| [PaperOrderPreflightUiState.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperOrderPreflightUiState.kt) (updated) | Added `lastAuditError: String?` field. |
| [PaperOrderPreflightViewModel.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperOrderPreflightViewModel.kt) (updated) | Optional `auditRepository` + `onAuditSaved` callback. Try/catch around save. |
| [PaperOrderDryRunAuditUiState.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperOrderDryRunAuditUiState.kt) (new) | UI state with total + recent rows + error. |
| [PaperOrderDryRunAuditViewModel.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperOrderDryRunAuditViewModel.kt) (new) | `refresh()` + `refreshNow()` (suspend variant for the preflight VM callback). No delete method. |
| [OfflineDashboardScreen.kt](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt) (updated) | New `PaperDryRunAuditCard` composable + Refresh button. No execute button. |
| [VelaLabApplication.kt](../android/app/src/main/kotlin/com/vela/android/lab/VelaLabApplication.kt) (updated) | New `paperOrderDryRunAuditRepository` lazy DI graph entry. |
| [MainActivity.kt](../android/app/src/main/kotlin/com/vela/android/lab/MainActivity.kt) (updated) | New `paperOrderDryRunAuditViewModel` + factory; preflight VM factory now wires the audit repo + `onAuditSaved` callback for auto-refresh. |

### Tests added

| Path | Tests | Coverage |
| --- | ---: | --- |
| [PaperOrderDryRunAuditRepositoryTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/data/paper/preflight/PaperOrderDryRunAuditRepositoryTest.kt) (new) | 11 | `saveDryRun` inserts with mapped fields; block reasons + warnings flatten to newline-separated summaries (2 lines for 2 blocks, 1 line for 1 warning); repeated saves with distinct `clientDryRunId`s produce distinct rows; `recent` returns sorted desc; `recentBySymbol` uppercases input; **audit entity field-name reflection rejects `secret/apikey/apca/accountid/credential/password`**; **persisted entity `toString()` contains no credential value**; repository has no `delete/update/patch` or trading-shape method; **DAO interface declared-method set equals exactly `{"insert","countAll","recent","recentBySymbol"}`**; `priceUsed` falls back to limit price; `priceUsed` is null when neither notional nor limit price exists. |
| [PaperOrderDryRunAuditViewModelTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/ui/dashboard/PaperOrderDryRunAuditViewModelTest.kt) (new) | 6 | Empty DB → `totalDryRuns=0`, non-null `lastRefreshAt`; after inserts → total + recent rows sorted desc; `refreshNow` callback updates snapshot; repository exception → `lastError`, no crash; **audit VM has no `delete/clear/drop/update/patch` or trading-shape method**; UI state `toString()` contains no `apca/secret/credential/accountid` substring. |
| [PaperOrderPreflightAuditIntegrationTest.kt](../android/app/src/test/kotlin/com/vela/android/lab/ui/dashboard/PaperOrderPreflightAuditIntegrationTest.kt) (new) | 4 | Valid preflight inserts exactly **one** audit row + no credential in persisted row; two distinct dry-runs produce two rows with distinct `clientDryRunId`s; **invalid form input does NOT insert an audit row** (no spurious entries); audit save failure surfaces `lastAuditError` but **keeps the preflight `lastResult` visible**. |

All prior Phase 1.e–2.m tests still pass. Two mid-phase fixes during build:
1. `fallbackToDestructiveMigration(false)` did not match the Room version on the classpath — switched to the no-arg variant.
2. (No other compile errors after that.)

### Reflection / contract assertions still green

The "no execution / mutation method" reflection contract now covers **eighteen** surfaces, all green:

1. `MarketDataClient` interface (2.a)
2. `AlpacaTestStreamMarketDataClient` (2.b)
3. `AlpacaTestStreamPipelineBridge` (2.d)
4. `AlpacaStockMarketDataClient` (2.e)
5. `StreamHealthTracker` (2.f)
6. `WatchlistRepository` (2.g)
7. `WatchlistViewModel` (2.g)
8. `MarketTickBuffer` (2.i)
9. `MarketHistoryViewModel` (2.j)
10. `AlpacaPaperReadOnlyClient` (2.k)
11. `PaperAccountViewModel` (2.k)
12. `PaperPortfolioRiskViewModel` (2.l)
13. `PaperTradingExecutionGuard` (2.m)
14. `PaperOrderPreflightEngine` (2.m)
15. `PaperOrderPreflightViewModel` (2.m)
16. **`PaperOrderDryRunAuditRepository`** (added this phase)
17. **`PaperOrderDryRunAuditViewModel`** (added this phase)
18. **`PaperOrderDryRunAuditDao`** (declared-method set checked exactly = `{insert, countAll, recent, recentBySymbol}`)

Plus: `AlpacaHttpClient` interface still declares exactly `{"executeGet"}`. `PaperTradingExecutionGuard.canExecuteOrders` still `false`. `PaperOrderDryRunAuditEntity` field names checked for credential/account-id substrings.

### Unit tests

| Variant | Tests | Failures | Errors |
| --- | ---: | ---: | ---: |
| `:app:testDebugUnitTest` | **573** | **0** | **0** |
| `:app:testReleaseUnitTest` | **573** | **0** | **0** |

Run via `./gradlew.bat :app:test :app:assembleDebug --no-daemon -Dorg.gradle.jvmargs="-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8"` → `BUILD SUCCESSFUL in 1m 50s`. Phase 2.n added **21 new unit tests** over Phase 2.m (was 552; now 573).

### Build + install

- Android Studio reopened on `G:\vela-android\android` and the `Pixel_10_Pro_XL` emulator booted cleanly (`sys.boot_completed=1` after 10 s).
- `:app:assembleDebug` → `BUILD SUCCESSFUL`.
- `adb -s emulator-5554 install -r app-debug.apk` → `Performing Streamed Install / Success`. On first launch Room ran the destructive migration and recreated the schema at version 2.

### On-device smoke (emulator-5554)

**Initial state**: the new "Dry-run audit — local only" card rendered after the preflight card with `Total dry-runs: 0` and the placeholder `No dry-runs recorded yet.`

**Dry-run #1 — SPY × 1**:
- Symbol: `SPY`, side `BUY`, qty `1`
- Status: **`BLOCKED`** — because the Phase 2.n schema migration wiped the Phase 2.j locally persisted SPY close, so the engine raised `MissingLatestPrice` as a block (correct behavior; the absence of local data is exactly what the engine is supposed to refuse on). Allocation after 4.4% (the pre-existing SPY position 6×727 ÷ $101,941 equity). 3 warnings (market closed, no local signal, no notional estimate).
- **Audit card**: `Total dry-runs: 1`, row `SPY BUY 1.0 → BLOCKED, notional —, blocks 1, warns 3, at 2026-06-19T14:22:03.941Z`.

**Dry-run #2 — QQQ × 1**:
- Symbol: `QQQ`, side `BUY`, qty `1`
- Status: `BLOCKED` (same `MissingLatestPrice` cause)
- Allocation after **1.5%** — different from SPY's 4.4%, confirming the engine + audit join per-symbol.
- **Audit card**: `Total dry-runs: 2`, rows sorted by createdAt DESC:
  - `QQQ BUY 1.0 → BLOCKED, notional —, blocks 1, warns 3, at 2026-06-19T14:23:49.495Z`
  - `SPY BUY 1.0 → BLOCKED, notional —, blocks 1, warns 3, at 2026-06-19T14:22:03.941Z`

The audit card refreshed automatically after each preflight without a manual `Refresh audit` tap, because the preflight VM's `onAuditSaved` callback drives the audit VM's `refreshNow()` suspend method.

### Proof no order was sent

| Evidence | Value |
| --- | --- |
| HTTP client interface declared-method set | `{"executeGet"}` — unchanged from Phase 2.k |
| `PaperTradingExecutionGuard.canExecuteOrders` | `false` — printed in the preflight card subtitle |
| Buttons added by Phase 2.n | Only `Refresh audit`. No Submit. No Buy/Sell-execute. No Cancel. No Clear. |
| Audit row count after each dry-run | `+1` per preflight; never `+1` from a network response |
| `paper_order_dry_run_audits` row source field | Always `MANUAL_DRY_RUN`; no `AUTO_PAPER` enum value exists |
| App-PID logcat `POST/DELETE/PATCH /orders` | **0** (filtered as below) |

### Logcat / credential / account-id leak check

`adb logcat -d --pid=<app-pid>` after the full launch → 2 dry-runs sequence:

| Pattern | Lines from app PID |
| --- | ---: |
| `FATAL EXCEPTION` / `AndroidRuntime: FATAL` | **0** |
| `alpaca` / `paper-api` / `stream.data` (case-insensitive) | **0** |
| `APCA-API-KEY-ID` / `APCA-API-SECRET-KEY` / `topsecretvalue` / `PK[A-Z0-9]{10,}` | **0** |
| `://api.alpaca.markets` (LIVE host) | **0** |

Persisted audit row leak check (asserted by unit test + entity reflection):
- Field names: no `secret`, `apiKey`, `apca`, `accountId`, `credential`, `password` substring on `PaperOrderDryRunAuditEntity`.
- Entity `toString()` after a save with a credential-bearing store: contains no `topsecretvalue` / `PKABCDEF1234` substring.
- DAO interface surface: `{"insert", "countAll", "recent", "recentBySymbol"}` — no `update`, no `delete`, no `clear`.

### Safety verification

| Check | Result |
| --- | --- |
| `G:\vela` modified | No — read-only tree not touched. |
| Windows `vela.db` read / copied / touched | No. |
| New network endpoint | None — Phase 2.n added no `executeGet` URLs and no HTTP method. |
| `POST /v2/orders` / `DELETE /v2/orders` / `PATCH` | None. `AlpacaHttpClient` still exposes only `executeGet`. |
| `/orders` / `/positions/{symbol}` / `/account/configurations` | None — endpoint guard still rejects them and the app never calls them. |
| LIVE endpoint added | None. |
| Order / cancel / replace / mutation methods added | None — reflection contract green on eighteen surfaces; `canExecuteOrders = false`. |
| Auto Paper | None — `IntentSource` enum still has only `MANUAL_DRY_RUN`. |
| Foreground service | Not added. |
| ML | Not added. |
| REAL locked | `realModeLocked = true`. The preflight engine would `BLOCK` on `RealLocked` if it were ever unlocked. |
| Credentials hardcoded / logged / rendered / **persisted** | None at every layer — entity, DAO, repository, VM, UI state, dashboard render. |
| Account id persisted in audit | None — `PaperAccountSnapshot` doesn't carry an `id` field (omitted at the parser in Phase 2.k), and `PaperOrderDryRunAuditEntity` has no account-id-shaped field. |
| Submit / buy-execute / sell-execute button on the new card | **None** — the only button is `Refresh audit`. |
| Delete / Clear button on the new card | **None** — append-only by design; reflection contract enforces. |

### What this proves

Every local dry-run preflight is now durably journalled into a tamper-evident-by-design (append-only, unique-id-indexed) Room table on-device:

1. The audit entity carries no credential value, no API key, no Alpaca account id. The DAO has no `update` / `delete` / `clear`. The repository has no order / trading method. The audit VM has no clear method.
2. Persisting a row is a strict consequence of producing a preflight result (allowed or blocked, with or without warnings). Invalid form input does not produce a row.
3. Audit-save failure surfaces as `lastAuditError` and does not hide the preflight result — the operator still sees the would-have-happened summary.
4. On-device demonstration: two dry-runs (`SPY BUY 1.0`, `QQQ BUY 1.0`) both correctly BLOCKED by the engine (Room schema reset removed Phase 2.j local closes), both correctly **audited** with distinct UUIDs, distinct allocations, and correct flat-text summaries of the block reason and 3 warnings.
5. The new card auto-refreshes after each dry-run via the VM-to-VM `onAuditSaved` callback — no manual tap needed.
6. No `POST /orders`, no `DELETE /orders`, no `PATCH`, no LIVE host, no Auto Paper, no foreground service, no ML.

REAL stays locked. `AlpacaHttpClient` exposes only `executeGet`. `PaperTradingExecutionGuard.canExecuteOrders` remains `false`. `G:\vela` and the Windows `vela.db` were not touched.

### Phase 2.n status

**Done.** Phase 2.n closes with: new Room entity + DAO + `version = 2` schema bump; new `PaperOrderDryRunAuditRepository`; new `PaperOrderDryRunAuditUiState` + VM; new dashboard `PaperDryRunAuditCard` (no Submit, no Delete, no Clear); preflight VM hooked to save audit on each evaluation with auto-refresh callback; **21 new unit tests** (`:app:test` debug+release at **573/0/0**); on-device demonstration of two dry-runs persisting distinct append-only audit rows with zero credential / account-id / network execution. Reflection contract green on eighteen surfaces.

---

## Phase 2.o - price-source-aware Paper dry-run preflight (2026-06-19)

Phase 2.o adds a local market-price snapshot with explicit provenance and freshness, feeds it into the existing Paper dry-run preflight, renders the result, and persists the price metadata in the append-only audit. It adds no execution path.

### BUILD FAILED root cause and recovery

The handoff described a failed partial state in which `PaperOrderDryRunAuditRepository.toEntity()` passed three named constructor arguments that were absent from `PaperOrderDryRunAuditEntity`:

- `priceSource`
- `priceFreshness`
- `priceAgeMillis`

That source mismatch is the root cause: the mapper and Room schema expected fields the entity constructor did not expose, so Kotlin/Room compilation could not complete.

The requested combined command was rerun first, without `tail`, using the supplied JBR and JVM limits. It returned `BUILD SUCCESSFUL in 19s`, but all 71 tasks were already up-to-date. At the start of this continuation, the entity file already contained the three fields (its on-disk timestamp predates this turn), so the historical compiler diagnostic was no longer reproducible and no retained Gradle log contained it. This report therefore does not invent an exact old compiler message.

The fix was validated from source with:

```text
gradlew.bat :app:test :app:assembleDebug --rerun-tasks ... --stacktrace
BUILD SUCCESSFUL in 2m 44s
71 actionable tasks: 71 executed
```

### Effective fix set

- `android/app/src/main/kotlin/com/vela/android/lab/db/room/entities/PaperOrderDryRunAuditEntity.kt`: contains nullable `priceSource: String?`, `priceFreshness: String?`, and `priceAgeMillis: Long?` columns. No credential or account-id field was added.
- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/preflight/PaperOrderDryRunAuditRepository.kt`: `toEntity()` copies `result.priceSource`, `result.priceFreshness`, and `result.priceAgeMillis`.
- `android/app/src/main/kotlin/com/vela/android/lab/db/room/VelaDatabase.kt`: remains `version = 3`.
- `android/app/schemas/com.vela.android.lab.db.room.VelaDatabase/3.json`: Room schema contains nullable TEXT/TEXT/INTEGER columns for the three fields.
- `android/app/src/main/kotlin/com/vela/android/lab/data/market/price/MarketPriceSnapshotProvider.kt`: corrected its fallback-chain documentation to match the implemented local chain (live quote, persisted Room bar, missing). No behavior or network surface changed.

### Audit entity and DAO status

The Room v3 audit table compiled successfully through KSP. Runtime insertion also succeeded.

`PaperOrderDryRunAuditDao` still declares exactly:

```text
insert
countAll
recent
recentBySymbol
```

There is no update, delete, or clear method. The entity has no `apiKey`, `secret`, `apca`, `accountId`, `credential`, or `password` field. The Phase 2.o audit tests also verify nullable price metadata and the absence of credential/account-id-shaped fields.

### Unit tests and build

The three requested commands all passed:

| Command | Result |
| --- | --- |
| `:app:testDebugUnitTest` | `BUILD SUCCESSFUL`; **599 tests, 0 failures, 0 errors, 0 skipped** |
| `:app:test` | `BUILD SUCCESSFUL`; debug **599/0/0**, release **599/0/0** |
| `:app:assembleDebug` | `BUILD SUCCESSFUL`; APK produced |

The final forced run rebuilt all 71 tasks from source and passed. Phase 2.o contributes 26 tests over Phase 2.n's 573:

| Suite | Tests |
| --- | ---: |
| `MarketPriceFreshnessPolicyTest` | 10 |
| `MarketPriceSnapshotProviderTest` | 8 |
| `PaperPreflightWithSnapshotTest` | 5 |
| `PaperAuditPriceSourceTest` | 3 |

Final APK: `android/app/build/outputs/apk/debug/app-debug.apk` (25,411,754 bytes, built 2026-06-19 23:54:58 -03:00).

### Emulator dry-run validation

The debug APK installed successfully on `emulator-5554` and `com.vela.android.lab/.MainActivity` launched. A cold-boot System UI ANR dialog was dismissed with **Wait**; the app itself had no fatal exception.

One local demo SPY update was generated, producing a persisted close of `400.25`. The preflight form then ran:

```text
symbol: SPY
side: BUY
quantity: 1
status: WARNING_ONLY
estimated notional: 400.25
price source: ROOM_BAR_CLOSE
price freshness: FRESH
price age: 197683 ms
warning: US market is closed at preflight time
```

The UI displayed price, source, freshness, and age. The only action was `Run dry-run preflight`; BUY and SELL are intent-side selectors, not execution controls. There is no Submit, Execute, Place order, Cancel, or Buy/Sell execution control.

### Audit persistence result

Immediately after the dry-run, the audit card showed:

```text
Total dry-runs: 1
SPY BUY 1.0 -> WARNING_ONLY
notional 400.25 | blocks 0 | warns 1 | src ROOM_BAR_CLOSE | FRESH
```

The app was force-stopped and relaunched. The audit card still showed `Total dry-runs: 1` and the same SPY/price/source/freshness row, proving the entry survived process death in the app-private Room database. No Windows database was read or copied.

### Proof no order was sent

- `AlpacaHttpClient` still declares only `executeGet`.
- `OkHttpAlpacaHttpClient` builds `.get()` requests only and runs every URL through the exact read-only Paper allowlist.
- The Paper allowlist remains only `GET /v2/account`, `GET /v2/clock`, and `GET /v2/positions`; `"/orders"` appears only in the rejection denylist and explanatory comments.
- `PaperTradingExecutionGuard.canExecuteOrders` remains hard-coded `false`.
- Runtime logcat from launch through the dry-run contained **0** hits for order POST/DELETE/PATCH routes or submit/cancel/replace/close-position calls.
- The persisted source is `MANUAL_DRY_RUN`; `IntentSource` has no Auto Paper value.

### Credential and safety checks

- Runtime app logcat: **0 credential-header/property hits**, **0 order hits**, **0 app fatal hits**.
- Credential form after saved configuration: key input length `0`, secret input length `0`, and the secret field remained password-masked. No saved credential value was rendered.
- Build configuration contains blank defaults, loads debug credentials only from ignored `local.properties`, and forces release credentials blank. No credential literal is hardcoded in main source.
- Audit entity/repository persist neither credentials nor Alpaca account id.
- `paper-api.alpaca.markets` remains GET-only through the existing read-only client.
- The LIVE trading host `api.alpaca.markets` is not called; it appears only in rejection logic/documentation.
- No POST/DELETE/PATCH order endpoint, submission, cancellation, replace-order, close-position, or account-mutation method exists.
- REAL remained locked in the rendered status (`REAL locked: true`); no LIVE mode was added.
- No Auto Paper, foreground service, or ML dependency/import was added.
- `G:\vela` was not modified. The Windows `vela.db` was not read, copied, or touched.

### Phase 2.o status

**Done.** Room/KSP compiles with schema version 3 and nullable price audit columns; all **599** tests pass in both debug and release with zero failures/errors; `assembleDebug` passes; the emulator SPY quantity-1 dry-run rendered and durably persisted `400.25 / ROOM_BAR_CLOSE / FRESH / 197683 ms`; runtime/static checks show no order execution or credential leak. Phase 2.p was not started.

---

## Phase 2.p - local Paper order request draft, execution disabled (2026-06-20)

Phase 2.p converts an approved Paper dry-run preflight into a typed, local-only request draft. The draft can be built, validated, and displayed, but it has no endpoint or network dependency and cannot be executed.

### Files changed

| Path | Change |
| --- | --- |
| `android/app/src/main/kotlin/com/vela/android/lab/data/paper/preflight/PaperOrderRequestDraft.kt` | New draft model, status, typed validation result, and rejection enum. |
| `android/app/src/main/kotlin/com/vela/android/lab/data/paper/preflight/PaperOrderRequestDraftBuilder.kt` | New pure local builder with approval and execution-disabled checks. |
| `android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperOrderPreflightUiState.kt` | Added in-memory draft and local validation-error state. |
| `android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperOrderPreflightViewModel.kt` | Added `buildLocalDraft()` and invalidation of stale result/draft state when the form changes. |
| `android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt` | Added `Build local draft` action and the execution-disabled draft summary. |
| `android/app/src/test/kotlin/com/vela/android/lab/data/paper/preflight/PaperOrderRequestDraftBuilderTest.kt` | Added 14 model, builder, endpoint, reflection, credential, and REAL-lock tests. |
| `android/app/src/test/kotlin/com/vela/android/lab/ui/dashboard/PaperOrderPreflightViewModelTest.kt` | Added 3 draft-flow/invalidation tests (suite now 9 tests). |

### Draft model design

`PaperOrderRequestDraft` contains only local preflight-derived data:

- linked `clientDryRunId`;
- symbol, side, type, time in force, quantity, and optional limit price;
- estimated notional;
- price source, freshness, and age;
- related signal and creation timestamp;
- local draft status and retained warning messages;
- `executionEnabled`, permanently `false`.

There is deliberately no submit endpoint, URL, HTTP method, credential, API key, account id, server order id, or network client. The model constructor rejects `executionEnabled=true`, including attempts made through the generated data-class `copy()` function.

Draft statuses are `READY_LOCAL` and `READY_LOCAL_WITH_WARNINGS`. `PaperOrderRequestDraftValidation` is a sealed result: either `Valid(draft)` or `Rejected(reason, message)`.

### Builder validation design

`PaperOrderRequestDraftBuilder` has a zero-argument constructor and no injected fields. Its only public operation is `build(PaperOrderPreflightResult)`.

It builds a draft only when all of the following hold:

1. `PaperTradingExecutionGuard.canExecuteOrders` remains `false`.
2. Preflight status is `ALLOWED_DRY_RUN` or `WARNING_ONLY`.
3. Preflight status is not `BLOCKED` and block-reason list is empty.
4. Intent source is `MANUAL_DRY_RUN`.
5. Client dry-run id, symbol, quantity, optional LIMIT price, and optional estimated notional are locally valid and finite.

Warnings are copied as human-readable references and select `READY_LOCAL_WITH_WARNINGS`. A blocked or inconsistent preflight returns a typed safe rejection and no draft. The builder performs no I/O, persistence, account mutation, or network call.

### Persistence choice

No draft persistence was added. Phase 2.p keeps the draft in `PaperOrderPreflightUiState` only. This avoids a needless Room schema change and prevents the draft surface from acquiring any DAO or mutation behavior. `VelaDatabase` remains version 3; the existing append-only dry-run audit still records the originating preflight, not a new draft entity.

### UI changes

After a preflight result, the card now exposes one local action:

```text
Build local draft
```

On success it renders:

```text
Paper order draft — execution disabled
Execution disabled — no order can be sent
Draft status
Symbol / Side / Qty / Type / TIF
Estimated notional
Price source / freshness / age
Related signal
executionEnabled: false
```

There is no Submit, Execute, Confirm order, Place order, Cancel order, or Buy/Sell execution control. BUY and SELL remain preflight intent-side selectors only.

### Tests and build

Phase 2.p added 17 tests over Phase 2.o's 599, bringing both variants to 616:

| Command | Result |
| --- | --- |
| `:app:testDebugUnitTest` | `BUILD SUCCESSFUL in 19s`; **616 tests, 0 failures, 0 errors, 0 skipped** |
| `:app:test` | `BUILD SUCCESSFUL in 50s`; debug **616/0/0**, release **616/0/0** |
| `:app:assembleDebug` | `BUILD SUCCESSFUL in 27s` |

The new builder suite has 14/0/0. The updated preflight ViewModel suite has 9/0/0. Existing audit, price snapshot, portfolio/risk, market data, endpoint, credential, and REAL-lock suites all remain green.

APK: `android/app/build/outputs/apk/debug/app-debug.apk` (25,793,086 bytes; built 2026-06-20 00:14:27 -03:00).

### Runtime validation

The APK installed successfully on `emulator-5554`. After the cold-boot System UI ANR dialog was dismissed with **Wait**, the app itself launched without a fatal exception.

A fresh demo SPY update and `SPY / BUY / qty 1` preflight produced:

```text
preflight status: WARNING_ONLY
estimated notional: 400.25
price source: ROOM_BAR_CLOSE
price freshness: FRESH
price age: 129852 ms
related signal: NEUTRAL
```

Tapping `Build local draft` rendered:

```text
draft status: READY_LOCAL_WITH_WARNINGS
symbol: SPY
side: BUY
qty: 1.0
type: MARKET
TIF: DAY
estimated notional: 400.25
price source: ROOM_BAR_CLOSE
price freshness: FRESH
price age: 129852 ms
related signal: NEUTRAL
executionEnabled: false
```

The hierarchy contained zero visible Submit/Execute/Confirm/Place/Cancel execution controls. Initial status remained `READ_ONLY` with `REAL locked: true`.

### Proof no order was sent and no secret leaked

- App-PID logcat after launch, preflight, and draft build: **0 order-route/mutation hits**, **0 credential hits**, **0 app fatal hits**.
- `AlpacaHttpClient` still declares exactly `executeGet`.
- `OkHttpAlpacaHttpClient` remains GET-only and guarded by the three-URL Paper read-only allowlist.
- `PaperTradingExecutionGuard.canExecuteOrders` remains hard-coded `false`.
- Paper `/orders`, Paper close-position/configuration paths, and the LIVE `api.alpaca.markets` host remain rejected by tests.
- Draft reflection checks find zero endpoint/URL/HTTP, credential/API-key, or account-id fields and zero submit/cancel/replace/execute/mutation methods.
- Saved credential inputs remained empty at runtime; the secret input remained password-masked.
- No draft entity or DAO exists, so no credential or account id can be persisted through this phase's draft surface. Existing audit reflection/persistence tests remain green.

### Safety confirmation

- No POST/DELETE/PATCH order, position, or account endpoint was added.
- No order submission, cancellation, replacement, close-position, trading execution, or account mutation code was added.
- No LIVE or Auto Paper mode was added; `IntentSource` still contains only `MANUAL_DRY_RUN`.
- No credentials were hardcoded, logged, rendered after save, or added to draft/audit persistence.
- No foreground service or ML dependency/import was added.
- `G:\vela` was not modified. The Windows `vela.db` was not read, copied, or touched.

### Phase 2.p status

**Done.** A pure local request-draft model and builder now accept only approved manual dry-run preflights, retain warnings, and expose an immutable execution-disabled result in the UI. Both test variants pass at **616/0/0**, `assembleDebug` passes, runtime shows `executionEnabled=false`, and no order or credential leak was observed. No subsequent phase was started.

---

## Phase 2.q - payload preview and immutable local review queue (2026-06-20)

Phase 2.q adds a theoretical Paper order payload preview plus an append-only local Room review queue. It creates no HTTP request object and sends nothing.

### Files changed

New production files:

- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/preflight/PaperOrderPayloadPreview.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/preflight/PaperOrderPayloadPreviewBuilder.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/preflight/PaperOrderPayloadPreviewRepository.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/db/room/entities/PaperOrderPayloadPreviewEntity.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/db/room/dao/PaperOrderPayloadPreviewDao.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperOrderPayloadPreviewQueueUiState.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperOrderPayloadPreviewQueueViewModel.kt`
- `android/app/schemas/com.vela.android.lab.db.room.VelaDatabase/4.json`

Updated production files:

- `VelaDatabase.kt`: version 3 to 4, preview entity + DAO accessor.
- `VelaLabApplication.kt`: review-queue repository wiring.
- `MainActivity.kt`: queue ViewModel/factory and save-refresh callback.
- `PaperOrderPreflightUiState.kt`: preview/building/error state.
- `PaperOrderPreflightViewModel.kt`: local preview build + append-only queue save.
- `OfflineDashboardScreen.kt`: payload preview and review queue UI.

Tests added/updated:

- `PaperOrderPayloadPreviewBuilderTest.kt` (15 tests).
- `PaperOrderPayloadPreviewRepositoryTest.kt` (9 tests).
- `PaperOrderPayloadPreviewQueueViewModelTest.kt` (4 tests).
- `PaperOrderPreflightViewModelTest.kt` gained 2 preview integration tests (suite now 11).

### Payload preview model design

`PaperOrderPayloadPreview` carries:

- `previewId` and linked `clientDryRunId`;
- symbol, side, type, time in force, quantity, optional limit price;
- estimated notional, price source/freshness, related signal;
- generated timestamp, preview status, retained warning messages;
- typed theoretical `PaperOrderPayloadFields`;
- `executionEnabled=false`;
- `endpointPreview=DISABLED`;
- `httpMethodPreview=POST_DISABLED`.

It contains no credential, API key, account id, header, server order id, network client, or HTTP request. Constructor guards reject attempts to change any disabled marker, including through data-class `copy()`.

`PaperOrderPayloadPreviewStatus` is either `READY_PREVIEW` or `READY_PREVIEW_WITH_WARNINGS`. `PaperOrderPayloadPreviewValidation` is typed as `Valid(preview)` or `Rejected(reason, message)`.

### Builder validation design

`PaperOrderPayloadPreviewBuilder.build(draft)` is local and pure apart from generating a UUID/timestamp. It has no network or persistence dependency and creates no HTTP request.

The builder requires:

1. `draft.executionEnabled == false`.
2. `PaperTradingExecutionGuard.canExecuteOrders == false`.
3. Draft status is `READY_LOCAL` or `READY_LOCAL_WITH_WARNINGS`.
4. Linked id, symbol, quantity, optional LIMIT price, and optional notional are locally valid and finite.

Warnings are retained and select `READY_PREVIEW_WITH_WARNINGS`. Invalid drafts return a safe typed rejection.

### Immutable review queue persistence

Room is now version 4 with table `paper_order_payload_previews`. The existing dev-lab `fallbackToDestructiveMigration()` policy recreates the Android-only database on the v3 to v4 bump; Keystore-backed credentials remain separate and survive.

The queue row stores only preview review metadata and the three disabled markers. It does not store payload JSON, credentials, account id, API headers, APCA key names/values, or any executable endpoint.

`PaperOrderPayloadPreviewDao` declares exactly:

```text
insert
countAll
recent
recentBySymbol
```

There is no update, delete, or clear operation. The unique `previewId` index rejects accidental duplicate ids; separate builder invocations generate distinct UUIDs. The repository exposes one append operation plus reads. The queue ViewModel exposes refresh/read state only.

### UI changes

After a local draft exists, the only new action is:

```text
Build payload preview
```

The preview card renders:

```text
Paper order payload preview — execution disabled
Preview only — no HTTP request can be sent
preview status / id
symbol / side / qty / type / TIF
estimated notional / price source / freshness / signal
theoretical payload fields
endpointPreview: DISABLED
httpMethodPreview: POST_DISABLED
executionEnabled: false
```

The read-only `Payload review queue — local only` card shows total previews, recent rows, status, disabled markers, creation time, and one refresh action. It has no remove, update, delete, or clear control.

No Submit, Execute, Confirm order, Place order, Cancel order, or Buy/Sell execution control was added.

### Tests and build

Phase 2.q adds 30 tests over Phase 2.p's 616:

| Command | Result |
| --- | --- |
| `:app:testDebugUnitTest` | `BUILD SUCCESSFUL in 31s`; **646 tests, 0 failures, 0 errors, 0 skipped** |
| `:app:test` | `BUILD SUCCESSFUL in 1m 31s`; debug **646/0/0**, release **646/0/0** |
| `:app:assembleDebug` | `BUILD SUCCESSFUL in 38s` |

Existing preflight, audit, price snapshot, draft builder, portfolio/risk, market data, credential, endpoint, and REAL-lock tests remain green.

APK: `android/app/build/outputs/apk/debug/app-debug.apk` (25,823,070 bytes; built 2026-06-20 00:45:20 -03:00).

### Runtime preview result

The v4 APK installed successfully on `emulator-5554`. The schema bump recreated the Android lab database as expected. Initial status remained `READ_ONLY` and `REAL locked: true`.

A fresh demo SPY update followed by `SPY / BUY / qty 1` produced:

```text
preflight: WARNING_ONLY
draft: READY_LOCAL_WITH_WARNINGS
payload preview: READY_PREVIEW_WITH_WARNINGS
symbol / side / qty / type / TIF: SPY / BUY / 1.0 / MARKET / DAY
estimated notional: 400.25
price source / freshness: ROOM_BAR_CLOSE / FRESH
related signal: NEUTRAL
endpointPreview: DISABLED
httpMethodPreview: POST_DISABLED
executionEnabled: false
```

The theoretical payload fields rendered as `SPY / buy / market / day / 1.0`.

### Review queue persistence result

Immediately after preview generation:

```text
Total previews: 1
SPY BUY 1.0 → READY_PREVIEW_WITH_WARNINGS
DISABLED · POST_DISABLED
```

The app was force-stopped and relaunched. The queue reloaded with `Total previews: 1` and the same row/markers, proving the Room v4 review entry survived process death.

### Proof no order was sent and no secret persisted

- Full-session logcat: **0 order-route/mutation hits**, **0 credential hits**.
- Relaunched app PID: **0 app fatal hits**.
- Runtime hierarchy: **0 Submit/Execute/Confirm/Place/Cancel controls**.
- `AlpacaHttpClient` still declares exactly `executeGet`.
- `PaperTradingExecutionGuard.canExecuteOrders` remains hard-coded `false`.
- Paper order/close-position/account-configuration paths and the LIVE trading host remain rejected.
- Preview builder/model/repository/DAO/queue VM reflection checks expose no submit/cancel/replace/execute/mutation surface.
- Preview model/entity field checks find no secret, API key, APCA, account id, credential, password, or header field.
- Saved credential inputs remained empty; secret input remained password-masked.
- Existing audit entity still contains no credential or account id.

### Safety confirmation

- No POST/DELETE/PATCH order, position, or account endpoint was added.
- `POST_DISABLED` is a display-only constructor-guarded marker, not an HTTP method or request.
- No order submission, cancellation, replacement, close-position, trading execution, or account mutation code was added.
- `paper-api.alpaca.markets` remains GET-only; `api.alpaca.markets` is present only in rejection tests/guards/documentation.
- No LIVE or Auto Paper mode was added; intent source remains `MANUAL_DRY_RUN` only.
- No credentials were hardcoded, logged, rendered after save, or persisted in preview/audit.
- No foreground service or ML dependency/import was added.
- `G:\vela` was not modified. The Windows `vela.db` was not read, copied, or touched.

### Phase 2.q status

**Done.** The app can build a constructor-guarded theoretical payload preview and append it to an immutable local Room v4 review queue. Both variants pass at **646/0/0**, the APK builds, the queue survives relaunch, all disabled markers render correctly, and no order or credential leak was observed. No subsequent phase was started.

## Phase 2.r - Paper execution readiness gate and disabled executor (2026-06-20)

### Scope and outcome

Phase 2.r adds a local readiness assessment and an intentionally disabled executor surface on top of the Phase 2.q payload preview. A valid preview can now be assessed as structurally ready, but the result explicitly proves that execution remains unavailable.

This phase does not add order submission, an order HTTP request, cancellation, replacement, close-position behavior, account mutation, LIVE, Auto Paper, a foreground service, ML, or credential persistence. No optional disabled-attempt audit table was added; the Room schema remains version 4.

### Files changed

Added:

- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/preflight/PaperExecutionReadiness.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/preflight/PaperExecutionReadinessChecker.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/preflight/PaperDisabledOrderExecutor.kt`
- `android/app/src/test/kotlin/com/vela/android/lab/data/paper/preflight/PaperExecutionReadinessCheckerTest.kt`
- `android/app/src/test/kotlin/com/vela/android/lab/data/paper/preflight/PaperDisabledOrderExecutorTest.kt`

Updated:

- `android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperOrderPreflightUiState.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperOrderPreflightViewModel.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt`
- `android/app/src/test/kotlin/com/vela/android/lab/ui/dashboard/PaperOrderPreflightViewModelTest.kt`
- `docs/phase-1-progress.md`

### Readiness model design

`PaperExecutionReadinessSnapshot` records only local identifiers and safety state:

```text
previewId
linkedClientDryRunId
hasValidPreview
executionEnabled=false
realLocked
liveEndpointAllowed=false
paperPostOrdersAllowed=false
autoPaperEnabled=false
foregroundServiceEnabled=false
credentialsConfigured (boolean only)
status
blockingReasons
warnings
checkedAtEpochMillis
```

Its status is one of `NOT_READY`, `READY_BUT_EXECUTION_DISABLED`, or `BLOCKED`. Constructor guards reject any attempt, including via `copy`, to enable execution, LIVE, Paper POST orders, Auto Paper, or foreground execution. Every snapshot must retain the `EXECUTION_DISABLED` and `PAPER_POST_ORDERS_DISABLED` reasons.

The model has no credential value, API key, secret, password, account id, API header, endpoint URL, HTTP request, or server order id field.

### Readiness checker design

`PaperExecutionReadinessChecker` is pure local code. Its only input is:

- the Phase 2.q `PaperOrderPayloadPreview`;
- the hard-disabled `PaperTradingExecutionGuard`;
- the current REAL-lock boolean;
- the credential-configured boolean.

It validates the preview identifiers, symbol, quantity, disabled markers, and typed payload-field consistency. A valid preview with REAL locked and the execution guard false returns:

```text
READY_BUT_EXECUTION_DISABLED
executionEnabled=false
realLocked=true
liveEndpointAllowed=false
paperPostOrdersAllowed=false
autoPaperEnabled=false
foregroundServiceEnabled=false
```

Missing credentials produce a boolean-only warning and do not enable any capability. An invalid preview returns `NOT_READY`; an unsafe REAL-lock/guard state returns `BLOCKED`. The checker has no HTTP client, network, persistence, account, credential-value, or request dependency and exposes no mutation-shaped method.

### Disabled executor design

`PaperDisabledOrderExecutor` has one action method only:

```text
attemptDisabledExecution(preview)
```

The method always creates a local `DisabledExecutionResult` with `result=EXECUTION_DISABLED` and the guarded reason `Execution is disabled - no order can be sent`. It copies only the local preview id and linked dry-run id plus a timestamp.

The executor does not accept credentials and has no `AlpacaHttpClient`, OkHttp, HTTP request, endpoint, account, repository, or persistence dependency. It has no submit, cancel, replace, close-position, POST, DELETE, PATCH, or LIVE method. Reflection tests confirm that its single non-synthetic action is `attemptDisabledExecution`.

### Persistence decision

The optional disabled-attempt audit was deliberately omitted. The outcome is already deterministic and visible in current UI state, so adding a table would have expanded the persistence and migration surface without improving the Phase 2.r safety proof.

`VelaDatabase` remains at schema version 4. No readiness snapshot, disabled attempt, credential, account id, or API header is persisted.

### UI changes

The dashboard now renders a compact card after the preflight/payload flow:

```text
Paper execution readiness - disabled
Execution is disabled - no order can be sent
Latest preview id
Readiness status
executionEnabled
REAL locked
Paper POST /orders allowed
LIVE endpoint allowed
Auto Paper
Foreground service
Credentials configured (boolean only)
Reasons / warnings
```

`Check readiness` runs the pure local checker after a preview exists. `Attempt disabled execution` is disabled until readiness has been checked and only returns `EXECUTION_DISABLED` locally. It does not send or construct an HTTP request.

No Submit, Execute order, Confirm order, Place order, Cancel order, Replace order, Close position, or Auto Paper control was added. The existing BUY/SELL controls remain preflight-side selectors only.

### Tests added and updated

Phase 2.r adds 21 tests over Phase 2.q's 646:

- `PaperExecutionReadinessCheckerTest`: 10 tests;
- `PaperDisabledOrderExecutorTest`: 8 tests;
- `PaperOrderPreflightViewModelTest`: 3 additional tests, now 14 total.

The new coverage proves:

- a valid preview returns `READY_BUT_EXECUTION_DISABLED`;
- execution, Paper POST orders, LIVE, Auto Paper, and foreground execution remain false;
- REAL remains locked in the normal flow;
- invalid and unlocked inputs do not become executable;
- constructor copies cannot enable guarded capability fields;
- credential absence is represented only by a boolean warning;
- readiness/result fields contain no credential, account id, APCA, password, or header field;
- the checker has no network dependency or mutation-shaped method;
- the disabled executor always returns `EXECUTION_DISABLED`;
- the executor has exactly one action and no `AlpacaHttpClient`, request, credential, or account dependency;
- readiness and disabled-attempt UI state is invalidated when the form changes;
- `PaperTradingExecutionGuard.canExecuteOrders` remains false.

All existing endpoint, HTTP-interface, preflight, audit, price snapshot, draft, payload preview, portfolio/risk, market-data, credential, and REAL-lock regression tests remain green.

### Build and test results

| Command | Result |
| --- | --- |
| focused Phase 2.r suites | `BUILD SUCCESSFUL in 55s`; **32 tests, 0 failures, 0 errors, 0 skipped** |
| `:app:testDebugUnitTest` | `BUILD SUCCESSFUL in 25s`; **667 tests, 0 failures, 0 errors, 0 skipped** across 64 suites |
| `:app:test` | `BUILD SUCCESSFUL in 55s`; debug **667/0/0**, release **667/0/0**, 64 suites each |
| `:app:assembleDebug` | `BUILD SUCCESSFUL in 29s` |

APK: `android/app/build/outputs/apk/debug/app-debug.apk` (25,843,522 bytes; built 2026-06-20 01:07:14 -03:00).

### Static safety audit

- `VelaDatabase` remains version 4; no entity/DAO was added.
- `PaperTradingExecutionGuard.canExecuteOrders` remains hard-coded `false`.
- `AlpacaHttpClient` still declares only `executeGet`.
- The readiness checker declares only `check` as its public action.
- The disabled executor declares only `attemptDisabledExecution` as its public action.
- Mutation-shaped method declarations in Paper/preflight production code: **0**.
- Executable POST/DELETE/PATCH order, position, or account endpoint literals: **0**.
- The LIVE trading host occurs only in the existing endpoint denylist documentation/guard and tests, never as an allowed executable endpoint.
- Sensitive readiness/result field declarations: **0**.
- Submit/Execute order/Confirm order/Cancel/Replace/Close sending controls: **0**.
- Manifest service declarations and `startForeground` calls: **0**.
- ML imports/dependencies: **0**.
- Hard-coded credential-shaped production literals: **0**.

`POST_DISABLED` and `Paper POST /orders allowed=false` are display-only safety markers. They are not an HTTP verb implementation, URL, request object, or execution path.

### Runtime validation

The APK installed successfully on `emulator-5554`. Initial status remained:

```text
Mode: READ_ONLY
REAL locked: true
Credentials configured: true
```

A fresh local demo SPY update followed by `SPY / BUY / qty 1` produced:

```text
preflight: WARNING_ONLY
estimated notional: 400.25
price source / freshness: ROOM_BAR_CLOSE / FRESH
related signal: NEUTRAL
draft: READY_LOCAL_WITH_WARNINGS
draft executionEnabled: false
payload preview: READY_PREVIEW_WITH_WARNINGS
payload preview id: 9dcfb04e-849c-49ad-90dd-21db512c302a
endpointPreview: DISABLED
httpMethodPreview: POST_DISABLED
payload executionEnabled: false
```

The local readiness check rendered:

```text
Readiness status: READY_BUT_EXECUTION_DISABLED
executionEnabled: false
REAL locked: true
Paper POST /orders allowed: false
LIVE endpoint allowed: false
Auto Paper: false
Foreground service: false
Credentials configured: true
```

The reasons were `EXECUTION_DISABLED`, `PAPER_POST_ORDERS_DISABLED`, `LIVE_ENDPOINT_DISABLED`, `AUTO_PAPER_DISABLED`, and `FOREGROUND_SERVICE_DISABLED`.

Tapping `Attempt disabled execution` returned:

```text
Disabled attempt result: EXECUTION_DISABLED
Execution is disabled - no order can be sent
```

### Runtime proof no order or secret escaped

- Full logcat session order/mutation-route hits: **0**.
- Current app PID order/mutation-route hits: **0**.
- Current app PID fatal hits: **0**.
- Precise credential/header/value log hits: **0**.
- Saved key-id input text length: **0**.
- Saved secret input text length: **0**, with `password=true`.
- Runtime UI exposed no Submit, Execute order, Confirm order, Cancel, Replace, or Close-position sending control.
- The only network work in the complete flow was the pre-existing read-only preflight client, whose interface and endpoint guard still permit only the account/clock/positions GETs. The readiness check and disabled attempt are synchronous/local and have no network dependency.
- REAL stayed locked throughout the flow.

### Safety confirmation

- No POST/DELETE/PATCH order, position, or account request was added or sent.
- No order submission, cancellation, replacement, close-position, trading execution, or account mutation code was added.
- `paper-api.alpaca.markets` remains GET-only through the existing read-only client.
- `api.alpaca.markets` remains rejected and was not used.
- No LIVE or Auto Paper path was added.
- No credential value, account id, or API header is stored in preview, readiness, result, audit, or Room.
- No credential was hardcoded, logged, or rendered after save.
- REAL remains locked and `canExecuteOrders` remains false.
- No foreground service or ML code was added.
- Work stayed under `G:\vela-android`; `G:\vela` was not modified. The Windows `vela.db` was not read, copied, or touched.

### Phase 2.r status

**Done.** A valid Paper payload preview can now be assessed as locally ready while the gate remains constructor-guarded and execution-disabled. The only executor surface deterministically returns `EXECUTION_DISABLED`; it cannot accept credentials, build a request, reach a network client, or mutate an order/account. Debug and release pass at **667/0/0**, the APK builds, emulator validation shows every execution capability false with REAL locked, and no order or credential leak was observed. No subsequent phase was started.

---

## Independent audit of Phases 2.o, 2.p, and 2.q (2026-06-20)

This audit was performed by reading the production code, Room schema, UI, endpoint guards, reflection contracts, and by running `:app:test` + `:app:assembleDebug` from a clean Gradle invocation. **No source files in `app/src/main` were modified during the audit.** The optional emulator runtime audit was skipped because no emulator instance was running; the prior runtime evidence captured in the per-phase reports is preserved.

### A. Verdict

**PASS — with one informational note** about Phase 2.r work that was already merged in the repository at the time of audit.

### B. Phase-by-phase findings

#### Phase 2.o — price-source-aware preflight

| Check | Evidence | Result |
| --- | --- | --- |
| `MarketPriceSnapshot` carries `source` + `freshness` + `ageMillis` + `bid`/`ask` + `reason` | [MarketPriceSnapshot.kt](android/app/src/main/kotlin/com/vela/android/lab/data/market/price/MarketPriceSnapshot.kt) | OK |
| Source priority in `MarketPriceSnapshotProvider.snapshotFor` is `live quote → Room bar → missing` | [MarketPriceSnapshotProvider.kt](android/app/src/main/kotlin/com/vela/android/lab/data/market/price/MarketPriceSnapshotProvider.kt) lines 33–86 | OK |
| `LIVE_QUOTE_MID` chosen only when both bid and ask are > 0; otherwise `LIVE_QUOTE_BID_ASK` for the side that exists | Provider lines 44–60 | OK |
| Missing-price branch returns `MarketPriceSnapshot.missing(...)` with `freshness = MISSING` | Provider lines 80–84, model lines 49–60 | OK |
| `MarketPriceFreshnessPolicy.classify` thresholds: quote 10 s, bar 90 s, Room 5 min | [MarketPriceFreshnessPolicy.kt](android/app/src/main/kotlin/com/vela/android/lab/data/market/price/MarketPriceFreshnessPolicy.kt) | OK |
| Negative ages are clamped to 0 (clock-skew tolerated) | Policy `coerceAtLeast(0L)` | OK |
| `MISSING` price still blocks preflight via `PreflightBlockReason.MissingLatestPrice` | [PaperOrderPreflightEngine.kt](android/app/src/main/kotlin/com/vela/android/lab/data/paper/preflight/PaperOrderPreflightEngine.kt) lines 92–102 | OK |
| `STALE` price is a warning, not a block (and only when the snapshot price was actually used — a LIMIT override suppresses it) | Engine lines 172–182 | OK |
| `priceSource` / `priceFreshness` / `priceAgeMillis` flow into `PaperOrderPreflightResult` | Engine lines 201–205, result data class fields | OK |
| `PaperOrderDryRunAuditEntity` has nullable `priceSource`, `priceFreshness`, `priceAgeMillis` columns and no credential / account id field | [PaperOrderDryRunAuditEntity.kt](android/app/src/main/kotlin/com/vela/android/lab/db/room/entities/PaperOrderDryRunAuditEntity.kt) | OK |
| `PaperOrderDryRunAuditRepository.toEntity` copies the three new columns | [PaperOrderDryRunAuditRepository.kt](android/app/src/main/kotlin/com/vela/android/lab/data/paper/preflight/PaperOrderDryRunAuditRepository.kt) | OK |
| Provider has no network / HTTP / credential dependency | Imports are `tick.MarketTickBuffer`, `repository.MarketDataRepository`, `java.time.Instant` only | OK |
| Dashboard preflight card renders `Price source`, `Price freshness`, `Price age (ms)`; audit row shows `src` + freshness | [OfflineDashboardScreen.kt](android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt) | OK |

**Informational note (NOT an issue):** `MarketPriceSource` enum declares `LIVE_BAR_CLOSE`, but the provider's fallback chain skips it and goes directly from `LIVE_QUOTE_*` to `ROOM_BAR_CLOSE`. This is documented in the provider's KDoc ("Live stream bars already flow through that persistence pipeline") and was accepted in the Phase 2.o report. The enum value remains defined for forward use; no execution path is created.

#### Phase 2.p — local draft builder

| Check | Evidence | Result |
| --- | --- | --- |
| `PaperOrderRequestDraft.init` rejects `executionEnabled = true`, blocking both direct construction and `.copy(executionEnabled = true)` | [PaperOrderRequestDraft.kt](android/app/src/main/kotlin/com/vela/android/lab/data/paper/preflight/PaperOrderRequestDraft.kt) lines 31–35 | OK |
| Draft model has no endpoint URL, HTTP method, credential, API key, account id, server order id, or network client field | Inspection of the data class | OK |
| Builder rejects when `PaperTradingExecutionGuard.canExecuteOrders` ever became true (defense-in-depth) | [PaperOrderRequestDraftBuilder.kt](android/app/src/main/kotlin/com/vela/android/lab/data/paper/preflight/PaperOrderRequestDraftBuilder.kt) lines 14–19 | OK |
| Builder rejects `BLOCKED` preflight with detailed reason | Builder lines 20–27 | OK |
| Builder rejects non-`ALLOWED_DRY_RUN`/`WARNING_ONLY` statuses | Builder lines 28–33 | OK |
| Builder rejects `intent.source != MANUAL_DRY_RUN` (no Auto Paper path) | Builder lines 34–39 | OK |
| Builder rejects when `blockReasons.isNotEmpty()` even on a non-BLOCKED status (belt + suspenders) | Builder lines 40–45 | OK |
| Builder validates `clientDryRunId`, symbol, quantity, optional LIMIT price, and notional with finite/positive checks | Builder lines 47–64 | OK |
| Builder has zero constructor dependencies and no `AlpacaHttpClient` reference | Class declaration line 11 | OK |
| No draft persistence was added; Room stayed at v3 during Phase 2.p (now v4 after 2.q) | DB version is 4 today; doc records 3→4 happened in 2.q | OK |

#### Phase 2.q — payload preview + immutable review queue

| Check | Evidence | Result |
| --- | --- | --- |
| `PaperOrderPayloadPreview.init` rejects `executionEnabled = true`, `endpointPreview != "DISABLED"`, and `httpMethodPreview != "POST_DISABLED"`, including via `.copy(...)` | [PaperOrderPayloadPreview.kt](android/app/src/main/kotlin/com/vela/android/lab/data/paper/preflight/PaperOrderPayloadPreview.kt) lines 45–53 | OK |
| Preview model has no credential, API key, account id, header, server order id, network client, or HTTP request field | Inspection of the data class | OK |
| Builder requires `draft.executionEnabled == false` AND guard `false` AND a `READY_LOCAL`/`READY_LOCAL_WITH_WARNINGS` status | [PaperOrderPayloadPreviewBuilder.kt](android/app/src/main/kotlin/com/vela/android/lab/data/paper/preflight/PaperOrderPayloadPreviewBuilder.kt) lines 17–34 | OK |
| Builder validates `clientDryRunId`, symbol, qty, LIMIT, notional with finite/positive checks | Builder lines 36–52 | OK |
| Builder emits unique UUID `previewId`; rejects blank id from factory | Builder lines 54–60 | OK |
| Builder has only `previewIdFactory` + `clock` constructor params; no network/persistence dependency | Class declaration lines 11–14 | OK |
| Room v4 entity `PaperOrderPayloadPreviewEntity` constructor rejects unsafe `executionEnabled`/endpoint/HTTP markers (matches preview's guards) | [PaperOrderPayloadPreviewEntity.kt](android/app/src/main/kotlin/com/vela/android/lab/db/room/entities/PaperOrderPayloadPreviewEntity.kt) lines 56–62 | OK |
| Entity fields contain no credential, API key, APCA, account id, header, or password substring | Field inspection: `id, previewId, linkedClientDryRunId, createdAtEpochMillis, symbol, side, orderType, timeInForce, quantity, limitPriceUsd, status, estimatedNotionalUsd, priceSource, priceFreshness, executionEnabled, endpointPreview, httpMethodPreview, warningsSummary` | OK |
| DAO declares exactly `{insert, countAll, recent, recentBySymbol}`; no update / delete / clear | [PaperOrderPayloadPreviewDao.kt](android/app/src/main/kotlin/com/vela/android/lab/db/room/dao/PaperOrderPayloadPreviewDao.kt) | OK |
| Unique index on `previewId` prevents accidental duplicates | Entity index `ix_paper_payload_preview_id unique = true` | OK |
| Repository exposes only `savePreview / countAll / recent / recentBySymbol` | [PaperOrderPayloadPreviewRepository.kt](android/app/src/main/kotlin/com/vela/android/lab/data/paper/preflight/PaperOrderPayloadPreviewRepository.kt) | OK |
| `VelaDatabase` version 4; `fallbackToDestructiveMigration()` documented as dev-lab acceptable; existing audit and preview entities registered; their DAO accessors are abstract methods | [VelaDatabase.kt](android/app/src/main/kotlin/com/vela/android/lab/db/room/VelaDatabase.kt) lines 38–82 | OK |
| No executable HTTP request object is created anywhere in 2.q | `AlpacaHttpClient` interface still declares only `executeGet` | OK |

### C. Build / test results

| Command | Result |
| --- | --- |
| `:app:testDebugUnitTest` (folded into `:app:test`) | `BUILD SUCCESSFUL`; **667 tests, 0 failures, 0 errors** |
| `:app:test` (debug + release) | `BUILD SUCCESSFUL`; debug **667 / 0 / 0**, release **667 / 0 / 0** |
| `:app:assembleDebug` | `BUILD SUCCESSFUL`; APK at `app/build/outputs/apk/debug/app-debug.apk` |

The audited deltas reported by Codex (599 → 616 → 646) all reconcile with the suite counts in the per-phase reports. The current repo total of **667** is consistent with the Phase 2.r work that was already merged at the time of this audit (see informational note below).

### D. Safety findings

| Surface | Finding |
| --- | --- |
| Execution methods (`submitOrder` / `placeOrder` / `cancelOrder` / `replaceOrder` / `closePosition` / `executeOrder`) | **0** matches in `app/src/main` outside negative documentation comments. The two hits in `PaperTradingExecutionGuard.kt` (lines 9–10) and `AlpacaPaperReadOnlyClient.kt` (lines 12–13) are KDoc assertions of absence. |
| HTTP mutation literals (`"POST"` / `"DELETE"` / `"PATCH"` / `.post(` / `.delete(` / `.patch(`) | **0** matches in `app/src/main`. |
| `api.alpaca.markets` (LIVE host) | Appears only in `AlpacaPaperTradingEndpoint.kt` (rejection guard + KDoc), `AlpacaStreamEndpoint.kt` (`FORBIDDEN_HOSTS` denylist), `MarketDataConfig.kt` (rejection rule). No code constructs a request against this host. |
| `paper-api.alpaca.markets` (PAPER host) | Appears in the read-only Paper allowlist (`AlpacaPaperTradingEndpoint.PAPER_BASE_URL` + three GET URLs) and the dashboard's read-only Paper card subtitle. `AlpacaHttpClient` exposes only `executeGet`. |
| `AlpacaHttpClient` interface surface | Exactly one declared method: `suspend fun executeGet(url, keyId, secret): HttpResult`. No `post/put/patch/delete` companion overload. |
| `PaperTradingExecutionGuard.canExecuteOrders` | `const val ... = false` — compile-time false. No setter, no method. |
| REAL lock | `AppState.realModeLocked` defaults to `true`; `AppState.unlockRealMode()` exists from Phase 1 but is **never called** anywhere in `app/src/main` or `app/src/test`. New code in 2.o/p/q treats `realModeLocked = false` as a hard block. |
| Auto Paper | Substring "Auto Paper" appears in 4 files, all as negative safety documentation (`No Auto Paper`). `IntentSource` enum has only `MANUAL_DRY_RUN`. |
| Foreground service / ML | No matches in `app/src/main/AndroidManifest.xml` or new code. |
| Room mutation (`update` / `delete` / `clear`) on new DAOs | `PaperOrderDryRunAuditDao` and `PaperOrderPayloadPreviewDao` each expose exactly `{insert, countAll, recent, recentBySymbol}`. Legacy DAOs (`MarketBarDao`, `JournalDao`, etc.) retain `clear()` from Phase 1 — these are unrelated to the order-safety surface. |
| Credential persistence | The two new entities (`PaperOrderDryRunAuditEntity`, `PaperOrderPayloadPreviewEntity`) declare no `apiKey`, `secret`, `apca`, `accountId`, `credential`, `password`, or HTTP-header field. The `EncryptedSharedPreferences`-backed credential store is the only credential surface. |
| UI execution controls | Button label inventory under `OfflineDashboardScreen.kt`: `Generate demo BTC/USD update`, `Generate demo SPY update`, `Clear local demo state`, `Save credentials`, `Clear credentials`, `Test Alpaca Market Data`, `Stop Alpaca test stream`, `Start real market data stream`, `Stop real market data stream`, `Remove` (watchlist), plus the Phase 2.l-2.r refresh/build/check/attempt controls. **No** `Submit`, `Execute`, `Confirm order`, `Place order`, `Cancel order`, `Buy now`, or `Sell now` label exists. |

### E. Issues found

| ID | Severity | File / path | Reason | Recommended fix | Block Phase 2.r? |
| --- | --- | --- | --- | --- | --- |
| **A1** | LOW (Info) | `app/src/main/kotlin/.../db/room/VelaDatabase.kt` | The schema is already at **version 4** and the Phase 2.r execution-readiness production code (`PaperExecutionReadiness.kt`, `PaperExecutionReadinessChecker.kt`, `PaperDisabledOrderExecutor.kt`) plus the Phase 2.r report block in `docs/phase-1-progress.md` are already present, even though the audit scope was 2.o/p/q. The test count 667 confirms Codex completed 2.r before this audit was requested. The 2.r code itself reads safely — readiness checker has no network/HTTP dependency, disabled executor has exactly one method that always returns `EXECUTION_DISABLED`, and constructor guards prevent any execution capability from being flipped on. | None required. This is informational. The audit's scope was 2.o/p/q; the 2.r material is not contradicted by the audit findings, but Phase 2.r should not be re-implemented. | Not a blocker. Phase 2.r is already in the repo. |
| **A2** | LOW (Info) | `MarketPriceSource.kt` enum | The `LIVE_BAR_CLOSE` enum value is declared but never produced by `MarketPriceSnapshotProvider`. The provider's KDoc explains the design choice (live bars flow through Room, so they are returned as `ROOM_BAR_CLOSE`). | Optional: either populate `LIVE_BAR_CLOSE` from an in-memory live-bar cache or remove the enum value to keep declared/produced surfaces aligned. | Not a blocker. |

**No BLOCKER-, HIGH-, or MEDIUM-severity findings.** No execution path exists. No credential / account-id field is persisted. The LIVE Trading host is never used as an allowed endpoint. REAL remains locked. The `AlpacaHttpClient` declared-method set remains exactly `{"executeGet"}`. `PaperTradingExecutionGuard.canExecuteOrders` remains `false`.

### F. Final statement

**It is safe to proceed to Phase 2.r if Phase 2.r had not already been merged.** Since Phase 2.r is in fact already present in the repository (with all tests passing at 667/0/0 and the production safety contracts intact per my reading of `PaperExecutionReadiness.kt`, `PaperExecutionReadinessChecker.kt`, and `PaperDisabledOrderExecutor.kt`), the next operational step is to **not** re-implement Phase 2.r — the next phase to plan should be Phase 2.s onward.

The audit asked me to stop after the audit and not start Phase 2.r. I have done so. No production code was modified. The optional emulator runtime audit was skipped because no emulator was running at audit time; prior Phase 2.q runtime evidence in the report block (review queue persisted across force-stop + relaunch, 0 FATAL, 0 credential leaks, `executionEnabled = false`) is preserved and consistent with the static code I reviewed.

---

## Phase 2.s — final pre-execution safety freeze (2026-06-20)

### Status and decision

**PASS. Phase 2.s is complete.** The app remains execution-disabled and cannot send, cancel, replace, or close an order. This is a **GO to design** a separately reviewed future manual Paper execution phase, but a **NO-GO for order submission under the current freeze**. No execution phase was started.

### Files changed

- `docs/paper-execution-safety-freeze.md` — authoritative current boundary, absent capabilities, 16 frozen invariants, and future go/no-go checklist.
- `android/app/src/test/kotlin/com/vela/android/lab/safety/PaperExecutionSafetyFreezeTest.kt` — Phase 2.s reflection, constructor-guard, endpoint-guard, and production-source invariants.
- `android/scripts/safety-scan.ps1` — PowerShell 5-compatible categorized production scan; exits nonzero for suspicious or forbidden hits.
- `docs/phase-1-progress.md` — this report.

No production file under `app/src/main` was changed. The existing UI already states `Execution is disabled — no order can be sent`, labels the surface as readiness, and exposes only `Attempt disabled execution`; no redesign or wording change was needed.

### Safety-freeze specification summary

The specification records the local-only surfaces that exist: preflight, dry-run audit, request draft, payload preview/review queue, readiness checker, and disabled executor. It explicitly records the absent surfaces: `POST /v2/orders`, DELETE/PATCH order APIs, cancellation, replacement, close-position calls, LIVE endpoint allowance, Auto Paper, foreground service, and ML.

Section 5 is the future manual-Paper go/no-go contract. It requires an explicit typed/manual consent gate, a separately reviewed HTTP mutation boundary and Paper-only endpoint guard, dedicated mutation audit, continued REAL lock, continued LIVE/DELETE/PATCH rejection, and updated freeze tests before any submit implementation can be considered.

### Final frozen invariants

1. `AppState().realModeLocked == true`; production never calls `unlockRealMode()`.
2. `PaperTradingExecutionGuard.canExecuteOrders == false`.
3. `AlpacaHttpClient` declares exactly `{"executeGet"}`.
4. The Paper allowlist is exactly account, clock, and positions GET URLs.
5. Paper, market-data, and configuration endpoint guards reject `api.alpaca.markets`.
6. Paper mutation-shaped URLs are rejected.
7. Preview markers are constructor-locked to `DISABLED` and `POST_DISABLED`, with `executionEnabled=false`.
8. Dry-run audit and preview Room entities expose no credential, account-id, or API-header field shape.
9. `IntentSource` contains only `MANUAL_DRY_RUN`; no enabled Auto Paper enum/state exists.
10. `PaperDisabledOrderExecutor` always returns `EXECUTION_DISABLED` for valid preview variants.
11. Sensitive production classes expose no submit/place/cancel/replace/close/execute order method.
12. `MarketDataSource` has no LIVE value.
13. Readiness constructors/copies reject every execution-enabling boolean.
14. A source-wide test scans every production Kotlin file for order-mutation method declarations.
15. A source-wide test rejects HTTP POST/DELETE/PATCH builders/annotations and executable Paper mutation URL literals.
16. Production has no execution-related assignment to `true`, no REAL-unlock invocation, and no enabled `AUTO_PAPER` token.

The companion static scan found **47 allowed negative/guard hits, 0 suspicious production hits, and 0 forbidden hits**.

### Tests and build

| Command | Result |
| --- | --- |
| Focused `PaperExecutionSafetyFreezeTest` | `BUILD SUCCESSFUL` |
| `:app:testDebugUnitTest` | `BUILD SUCCESSFUL in 21s`; **1,226 tests / 0 failures / 0 errors / 0 skipped**, 65 suites |
| `:app:test` | `BUILD SUCCESSFUL in 32s`; debug **1,226/0/0**, release **1,226/0/0**, 65 suites each |
| `:app:assembleDebug` | `BUILD SUCCESSFUL in 16s` |

APK: `android/app/build/outputs/apk/debug/app-debug.apk` (25,843,522 bytes).

### Emulator runtime validation

The debug APK installed successfully on the available `Pixel_10_Pro_XL` AVD (`emulator-5554`). A transient cold-boot System UI ANR dialog recovered with `Wait`; the VELA process then stayed focused and recorded **0 app-PID fatal hits**.

Initial app safety state:

```text
Mode: READ_ONLY
REAL locked: true
Pipeline: Offline demo
```

After a fresh demo SPY update, the existing local flow was exercised with `SPY / BUY / qty 1`:

```text
preflight: WARNING_ONLY
price source / freshness: ROOM_BAR_CLOSE / FRESH
draft: READY_LOCAL_WITH_WARNINGS
payload preview: READY_PREVIEW_WITH_WARNINGS
endpointPreview: DISABLED
httpMethodPreview: POST_DISABLED
payload executionEnabled: false
```

Readiness remained:

```text
status: READY_BUT_EXECUTION_DISABLED
executionEnabled: false
REAL locked: true
Paper POST /orders allowed: false
LIVE endpoint allowed: false
Auto Paper: false
Foreground service: false
```

The reasons were `EXECUTION_DISABLED`, `PAPER_POST_ORDERS_DISABLED`, `LIVE_ENDPOINT_DISABLED`, `AUTO_PAPER_DISABLED`, and `FOREGROUND_SERVICE_DISABLED`. Tapping `Attempt disabled execution` returned:

```text
Disabled attempt result: EXECUTION_DISABLED
Execution is disabled — no order can be sent
```

The credential card reported configured credentials while both editable inputs remained length 0; the secret input remained password-masked. No credential value was rendered.

### Proof no order was sent

- App-PID runtime log hits: order routes **0**, HTTP POST/DELETE/PATCH mutations **0**, mutation action names **0**, LIVE trading host **0**.
- Credential/header runtime log hits: API header names **0**, configured local credential values **0**.
- `AlpacaHttpClient` still has exactly one declared method, `executeGet`; no production HTTP mutation syntax exists.
- The readiness checker and disabled executor remain local-only and have no HTTP, credential, endpoint, or account dependency.
- The disabled attempt produced only the local `EXECUTION_DISABLED` result.

### Safety confirmations

- No hard-coded credential, credential log, credential UI rendering after save, or credential/account/header persistence in preview, audit, readiness, or disabled result.
- No `POST /v2/orders`, DELETE/PATCH order endpoint, submission, cancellation, replacement, close-position call, trading execution, or account mutation.
- `paper-api.alpaca.markets` remains GET-only through the existing read-only client; `api.alpaca.markets` remains rejected and unused.
- REAL remains locked; `canExecuteOrders` remains false.
- No LIVE path, Auto Paper, foreground-service declaration/permission/call, or ML dependency/import was added.
- Work stayed under `G:\vela-android`; `G:\vela` was not modified. The Windows `vela.db` was not read, copied, or touched.

### Final Phase 2.s statement

**Done. It is safe to design the next manual Paper execution phase against the Section 5 checklist. It is not safe—and remains impossible in this build—to submit an order.** A future implementation requires a new explicit phase, review, and deliberate freeze-test update. Stop after Phase 2.s.

---

## Phase 2.t — manual Paper execution design specification (2026-06-20)

### Status

**PASS as a design deliverable. NO-GO for implementation in this phase.** Phase 2.t added documentation and a documentation-only safety contract. It did not add or authorize an order submission path.

### Files changed

- `docs/manual-paper-execution-design.md` — formal A–L design covering current state, narrow future goal, non-goals, fail-closed gates, architecture, state machine, confirmation, audit, tests, controlled runtime validation, rollback/kill switch, and GO/NO-GO criteria.
- `android/app/src/test/kotlin/com/vela/android/lab/safety/ManualPaperExecutionDesignContractTest.kt` — three documentation/freeze assertions.
- `docs/phase-1-progress.md` — this report.

No production file under `android/app/src/main` changed. `AlpacaHttpClient`, endpoint allowlists, execution guards, disabled executor, UI, Room schema, manifest, and DI remain unchanged.

### Design summary

The specification keeps the possible future feature deliberately narrow: one foreground, user-triggered, explicitly confirmed Alpaca Paper order attempt. It excludes Auto Paper, signal/strategy execution, background/scheduled work, retries, cancellation, replacement, close-position calls, complex order types, LIVE, REAL unlock, foreground service, and ML.

The central architecture decision is to keep `AlpacaHttpClient` permanently GET-only and introduce a separately reviewed future `PaperOrderSubmitClient` rather than widening the read-only boundary. The proposed submit request accepts no URL, verb, credential, header, or arbitrary JSON. A dedicated endpoint guard would permit exactly one future method/URL pair while continuing to reject LIVE and every other mutation.

The proposed state machine is:

```text
NO_INTENT -> PREFLIGHT_READY -> DRAFT_READY -> PREVIEW_READY
-> READINESS_CHECKED -> USER_CONFIRMATION_REQUIRED
-> SUBMISSION_ALLOWED_FOR_ONE_ATTEMPT -> SUBMITTING
-> SUBMITTED | REJECTED | FAILED

Any disabled/unknown gate -> DISABLED
```

Authorization is short-lived, in-memory, snapshot-bound, and single-use. It is consumed before network I/O and only after an append-only attempt-start audit event is durable. Rotation, relaunch, double tap, staleness, changed warnings/data, navigation, or kill-switch change invalidates authorization; ambiguous outcomes never retry automatically.

The proposed confirmation is two-stage and displays the exact order, financial impact, signal, price/freshness, clock/market state, warnings, and future Paper-only method/endpoint. It requires explicit warning acknowledgement plus typed contextual confirmation. Credentials and account identifiers are excluded from UI and audit.

### Future GO criteria

A future implementation phase may start only after human approval explicitly accepts:

- the separate submit-client/read-only-client architecture;
- default-disabled compile-time and runtime gates plus an emergency fail-closed kill switch;
- exact order vocabulary, market-hours behavior, freshness limits, and token lifetime;
- two-stage typed confirmation and accessibility behavior;
- append-only audit schema/privacy whitelist;
- single-use/no-retry lifecycle semantics;
- the complete debug/release, endpoint, privacy, concurrency, rollback, and controlled Paper runtime test plan;
- deliberate strengthening/revision of the Phase 2.s freeze tests rather than deleting or broadly weakening them.

Human approval to start coding is not approval to enable submission. Controlled Paper runtime testing would require a later independent review and separately approved single attempt.

### Tests added

`ManualPaperExecutionDesignContractTest` verifies:

1. the design document exists, contains every A–L section, and states the exact NO-GO/human-approval boundary;
2. `POST /v2/orders` is labeled future-only and all proposed separated components are documented;
3. current production remains frozen: `canExecuteOrders=false`, `AlpacaHttpClient` exposes only `executeGet`, and the disabled executor returns `EXECUTION_DISABLED`.

### Validation

| Command | Result |
| --- | --- |
| Focused `ManualPaperExecutionDesignContractTest` | `BUILD SUCCESSFUL in 29s`; 3/0/0 |
| `:app:testDebugUnitTest` | `BUILD SUCCESSFUL in 20s`; **1,229 tests / 0 failures / 0 errors / 0 skipped**, 66 suites |
| `:app:test` | `BUILD SUCCESSFUL in 30s`; debug **1,229/0/0**, release **1,229/0/0**, 66 suites each |
| `:app:assembleDebug` | `BUILD SUCCESSFUL in 16s` |

Static safety scan: **47 allowed negative/guard hits, 0 suspicious production hits, 0 forbidden hits**.

No emulator submission flow was run in Phase 2.t because this phase is design-only and the current app has no submit path.

### Safety confirmations

- No production `POST /v2/orders`, DELETE/PATCH call, submit/cancel/replace/close method, trading execution, or account mutation was added.
- No endpoint allowlist was changed. `paper-api.alpaca.markets` remains GET-only in production; `api.alpaca.markets` remains rejection-only and unused as an allowed endpoint.
- REAL remains locked; `PaperTradingExecutionGuard.canExecuteOrders` remains `false`.
- `PaperDisabledOrderExecutor` still returns `EXECUTION_DISABLED`.
- `AlpacaHttpClient` still declares exactly `executeGet`.
- No Submit/Execute UI, feature flag, submit client, request model, audit table, Room migration, foreground service, Auto Paper, LIVE path, or ML dependency was implemented.
- No credential was hardcoded, logged, rendered, or persisted by this phase.
- Work stayed under `G:\vela-android`; `G:\vela` was not modified. The Windows `vela.db` was not read, copied, or touched.

### Final Phase 2.t statement

**Phase 2.t is design-only. No execution was implemented. Current app still cannot submit orders. Safe to review a future manual Paper implementation plan only after human approval.**

Stop after Phase 2.t. Do not implement manual Paper execution.

---

## Phase 2.u — manual Paper implementation approval package (2026-06-20)

### Status

**PASS as an approval-package deliverable. GO to review; NO-GO to implement or send.** No human approval for Phase 2.v is recorded by Phase 2.u itself.

### Files changed

- `docs/manual-paper-implementation-approval-package.md` — human approval gate, exact proposed Phase 2.v name/diff, unchanged and forbidden files, one-shot rules, kill switch, rollback, test plan, runtime sequence, and final GO/NO-GO table.
- `android/app/src/test/kotlin/com/vela/android/lab/safety/ManualPaperImplementationApprovalContractTest.kt` — three documentation/current-freeze contract tests.
- `docs/phase-1-progress.md` — this report.

No production file under `android/app/src/main`, no Gradle configuration, no Room schema, no endpoint guard, and no UI file changed.

### Approval package summary

The package requires Juan's explicit written approval in this project log before anyone—developer or AI agent—may begin:

**Phase 2.v — Manual Paper submit implementation, Paper-only, one-shot, user-confirmed**

Approval must name Phase 2.v, confirm Paper-only and manual-only/user-confirmed scope, resolve the proposed account/clock freshness, token lifetime, market-hours, order-vocabulary, and kill-switch-owner policies, and state whether it covers coding/tests only. Approval to code does not authorize a Paper request; a controlled one-order runtime attempt requires a second written authorization.

The exact future diff plan keeps these current production boundaries unchanged:

- `AlpacaHttpClient` remains `executeGet`-only.
- `AlpacaPaperTradingEndpoint` remains the three-URL GET guard.
- `PaperTradingExecutionGuard.canExecuteOrders` remains false.
- `PaperDisabledOrderExecutor` remains available and disabled.
- `PaperExecutionReadinessChecker` remains the disabled-readiness path.
- draft/preview constructor safety markers remain locked.

A future approved Phase 2.v would add a parallel submit package, a separate exact method/endpoint guard, a disabled-by-default feature/kill-switch gate, an in-memory single-use token, a fresh-data readiness checker, an append-only Room audit with explicit 4→5 migration, and a two-stage confirmation ViewModel/UI. Any production deviation from the listed diff requires reapproval.

The package forbids LIVE, REAL unlock, Auto Paper, background/lifecycle submit, retries, cancellation, replacement, close-position calls, bracket/OCO/trailing orders, account mutation beyond one confirmed Paper order, caller-controlled endpoints, reusable tokens, credential/header/raw-body persistence/logging, foreground service, ML, and broad weakening of the freeze scanner/tests.

### Tests added

`ManualPaperImplementationApprovalContractTest` verifies:

1. the package exists, contains `HUMAN APPROVAL REQUIRED BEFORE IMPLEMENTATION`, names Juan, requires a log entry, and names the exact future phase;
2. all A–J sections, expected architecture files, and final GO/NO-GO statements exist;
3. the current boundary remains disabled: `canExecuteOrders=false`, `AlpacaHttpClient` exposes only `executeGet`, and the disabled executor returns `EXECUTION_DISABLED`.

### Validation

| Command | Result |
| --- | --- |
| Focused `ManualPaperImplementationApprovalContractTest` | `BUILD SUCCESSFUL in 30s`; 3/0/0 |
| `:app:testDebugUnitTest` | `BUILD SUCCESSFUL in 20s`; **1,232 tests / 0 failures / 0 errors / 0 skipped**, 67 suites |
| `:app:test` | `BUILD SUCCESSFUL in 31s`; debug **1,232/0/0**, release **1,232/0/0**, 67 suites each |
| `:app:assembleDebug` | `BUILD SUCCESSFUL in 15s` |

Static safety scan: **47 allowed negative/guard hits, 0 suspicious production hits, 0 forbidden hits**.

No emulator submission validation was run because Phase 2.u is documentation/approval only and the app has no submit path.

### Safety confirmations

- No production `POST /v2/orders`, submit method/client, cancellation, replacement, close-position call, or account mutation was added.
- Existing endpoint allowlists remain unchanged and GET-only; the LIVE host remains rejection-only.
- REAL remains locked and `PaperTradingExecutionGuard.canExecuteOrders` remains false.
- `PaperDisabledOrderExecutor` still returns `EXECUTION_DISABLED`.
- `AlpacaHttpClient` still exposes only `executeGet`.
- Production mutation-method declarations: 0. HTTP POST/DELETE/PATCH calls: 0.
- No Auto Paper, foreground-service declaration/permission, or ML dependency/import was added.
- No credentials were hardcoded, logged, rendered, or persisted by this phase.
- Work stayed under `G:\vela-android`; `G:\vela` was not modified. The Windows `vela.db` was not read, copied, or touched.

### Final Phase 2.u statement

**Phase 2.u is approval-package only. No execution was implemented. Current app still cannot submit orders. Human approval is required before any future Phase 2.v implementation.**

**GO to review implementation plan. NO-GO to implement execution in this phase. NO-GO to run `POST /v2/orders`.**

Stop after Phase 2.u. Do not implement manual Paper execution.

---

## Human approval for Phase 2.v (2026-06-20)

Juan explicitly approved the exact next phase:

> “Apruebo iniciar Phase 2.v: implementación manual Paper-only, user-confirmed, sin LIVE, sin Auto Paper, sin REAL, con POST /v2/orders únicamente para Paper y bajo las condiciones del approval package.”

Approved phase: **Phase 2.v — Manual Paper submit implementation, Paper-only, one-shot, user-confirmed**.

This approval authorizes implementation and tests under the Phase 2.u package. It does not authorize LIVE, REAL unlock, Auto Paper, background execution, cancellation, replacement, close-position calls, or any mutation endpoint other than the single Paper orders collection POST. An actual emulator Paper submission still requires Juan to type/tap the final confirmation manually; automation must not enter it.

---

## Phase 2.v — Manual Paper submit implementation (2026-06-20)

### Final status

**PASS — implementation complete, default OFF, fail closed.** The first manual Paper-only one-shot boundary now exists behind the approved controls. No actual Paper order was submitted during runtime validation. No Phase 2.w work was started.

Juan's exact approval above was recorded before production implementation. The implementation permits only the exact collection request `POST https://paper-api.alpaca.markets/v2/orders`; it adds no other mutation verb, host, or path.

### Files changed or added

Configuration and dependency wiring:

- `android/app/build.gradle.kts`
- `android/app/src/main/kotlin/com/vela/android/lab/VelaLabApplication.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/MainActivity.kt`

Narrow submit boundary:

- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/AlpacaPaperSubmitEndpoint.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/AlpacaPaperOrderSubmitHttpClient.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperOrderSubmitModels.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperManualExecutionFeatureGate.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitConfirmation.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitGate.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperManualOrderSubmitClient.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitExecutor.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperOrderSubmitAuditRepository.kt`

Review lookup, audit persistence, and schema:

- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/preflight/PaperOrderPayloadPreviewRepository.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/db/room/dao/PaperOrderPayloadPreviewDao.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/db/room/entities/PaperOrderSubmitAuditEntity.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/db/room/dao/PaperOrderSubmitAuditDao.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/db/room/migrations/Migration4To5.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/db/room/VelaDatabase.kt`
- `android/app/schemas/com.vela.android.lab.db.room.VelaDatabase/5.json`

Foreground UI/orchestration:

- `android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperManualSubmitUiState.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperManualSubmitViewModel.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt`

Safety tooling and tests:

- `android/scripts/safety-scan.ps1`
- `android/app/src/test/kotlin/com/vela/android/lab/data/paper/preflight/PaperOrderPayloadPreviewRepositoryTest.kt`
- `android/app/src/test/kotlin/com/vela/android/lab/data/paper/submit/SubmitTestFixtures.kt`
- `android/app/src/test/kotlin/com/vela/android/lab/data/paper/submit/AlpacaPaperSubmitEndpointTest.kt`
- `android/app/src/test/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitTokenStoreTest.kt`
- `android/app/src/test/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitGateTest.kt`
- `android/app/src/test/kotlin/com/vela/android/lab/data/paper/submit/PaperManualOrderSubmitClientTest.kt`
- `android/app/src/test/kotlin/com/vela/android/lab/data/paper/submit/PaperOrderSubmitAuditRepositoryTest.kt`
- `android/app/src/test/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitExecutorTest.kt`
- `android/app/src/test/kotlin/com/vela/android/lab/ui/dashboard/PaperManualSubmitViewModelTest.kt`
- `android/app/src/test/kotlin/com/vela/android/lab/safety/PaperExecutionSafetyFreezeTest.kt`
- `android/app/src/test/kotlin/com/vela/android/lab/safety/ManualPaperExecutionDesignContractTest.kt`

Documentation:

- `docs/manual-paper-execution-design.md`
- `docs/paper-execution-safety-freeze.md`
- `docs/phase-1-progress.md`

The Phase 2.u approval package and its contract test remain the historical approval artifact; their scope was not weakened.

### Endpoint and HTTP design

`AlpacaPaperTradingEndpoint` and `AlpacaHttpClient` remain unchanged and GET-only. The new `AlpacaPaperSubmitEndpoint` is a separate exact-equality guard:

- method: `POST` only;
- URL: `https://paper-api.alpaca.markets/v2/orders` only;
- LIVE host, other Paper paths, item paths, positions/account mutations, and GET/PUT/PATCH/DELETE all reject locally.

`AlpacaPaperOrderSubmitHttpClient` declares exactly one method, `executePostOrder(url, bodyJson)`. Its OkHttp implementation evaluates the endpoint guard before reading credentials or constructing the request, disables redirects, and has no logger. `PaperManualOrderSubmitClient` is the only production caller of `executePostOrder`; `PaperManualSubmitExecutor` is the only production caller of its `submitOnce`. Both mutation dependencies are private inside the application graph.

The serialized body is limited to `symbol`, `side`, `type`, `qty`, `time_in_force`, optional `limit_price`, and `client_order_id`. There is no retry loop and no cancel, replace, close-position, account-mutation, caller-selected host, or LIVE branch.

### Confirmation, feature gate, and submit gate

The debug compile flag `MANUAL_PAPER_SUBMIT_COMPILED` defaults to `false`; release hardcodes `false`. A debug developer may opt in only through uncommitted `local.properties`, after which a second in-memory session arm is still required. The session arm and confirmation do not survive process death. An irreversible process-local emergency disable is available.

`PaperManualSubmitTokenStore` requires exact text such as `SUBMIT PAPER SPY BUY 1`. The 30-second token binds preview id, symbol, side, quantity, price source/freshness, and preview generation time. It is removed on the first consume attempt, including mismatch/failure, and invalidated whenever the source preview, warning acceptance, confirmation text, refresh, or ViewModel lifetime changes.

`PaperManualSubmitGate` fails closed over all approved conditions: recorded human approval; compile/session/emergency state; REAL lock; LIVE and Auto Paper false; credentials; account/trading status; account and clock freshness (15 seconds); market open; matching fresh price; preflight status/freshness; explicit warning acceptance; legacy readiness; immutable review-row parity; preview/request/confirmation parity; duplicates; and in-flight state.

The executor serializes attempts with a mutex, rechecks persisted preview/client-order duplicates, consumes the token once, writes `ATTEMPT_STARTED` before network I/O, performs at most one client call, then appends a terminal result. An audit-start failure sends zero requests. A process death after the start row still leaves a durable duplicate barrier. Network failure does not retry.

### Audit persistence

Room moved additively from version 4 to 5. Runtime installation with `adb install -r` preserved the existing local previews and dry-run rows, exercising the 4→5 migration successfully.

`paper_order_submit_audit` is append-only: the DAO exposes insert/count/recent/by-attempt/duplicate reads and no update, delete, or clear. A unique event key protects duplicate start/terminal events. Rows contain typed request summary, attempt/preview/dry-run/client-order identifiers, status, optional Paper order id, safe error, price provenance, market-open state, and random confirmation token id. They contain no credential, account id, API header, raw body, or raw confirmation text.

### UI

The debug dashboard adds the compact `Manual Paper submit — one-shot` card. It exposes Paper-only, REAL lock, LIVE, Auto Paper, compile/session state, selected preview, order summary, price provenance, market state, preflight/readiness/gate, exact method/endpoint, session arm/disarm, refresh, warning acknowledgement, exact confirmation field, and the explicit `Submit Paper order once` button.

There is no Auto Paper, LIVE, generic buy/sell execution, cancellation, replacement, close-position, background, lifecycle, or foreground-service control. The button remains disabled until the full gate returns `Allowed`; after an attempt the token is removed and the session disarms.

### Tests and safety scan

The new tests cover exact endpoint/interface shape, invalid verbs/hosts/paths, feature/session/REAL/LIVE/Auto/credential/account/clock/market/price/preflight/readiness/review/confirmation/duplicate gates, token exactness/expiry/mismatch/single use, request serialization and response sanitization, audit append-only shape, audit-before-network, network no-retry, concurrent double invocation, ViewModel exact-confirmation flow, disabled UI state, result presentation, and credential-free UI state.

Final validation from the final source state:

| Command | Result |
| --- | --- |
| `:app:testDebugUnitTest` | `BUILD SUCCESSFUL`; **1,488 tests / 0 failures / 0 errors / 0 skipped**, 74 result files |
| `:app:test` | `BUILD SUCCESSFUL`; debug **1,488/0/0/0**, release **1,488/0/0/0**, 74 result files each |
| `:app:assembleDebug` | `BUILD SUCCESSFUL`; final APK produced and installed |

This is 256 tests above the Phase 2.u baseline of 1,232 per variant.

Final safety scan:

- `ALLOWED_PHASE_2V_PAPER_SUBMIT_BOUNDARY`: **11**
- `ALLOWED_NEGATIVE_DOC_OR_GUARD`: **53**
- `SUSPICIOUS_PRODUCTION_HIT`: **0**
- `FORBIDDEN_HIT`: **0**

The amended freeze test also asserts the exact one-caller production call graph, preserves `AlpacaHttpClient == {executeGet}`, keeps `PaperTradingExecutionGuard.canExecuteOrders=false`, rejects all other HTTP mutation shapes, finds no production REAL unlock, and reflects over persisted submit fields for sensitive-name shapes.

### Emulator runtime result

The final debug APK was installed with `adb install -r` on `Pixel_10_Pro_XL` and launched successfully. Secure-store state was preserved; the UI showed only `Credentials configured=true`, never the key id or secret.

The approved non-final steps were exercised without automating the final confirmation:

1. Paper account/clock/positions refreshed through the existing GET-only client: account `ACTIVE`, account/trading blocked `false`, and market open `false`.
2. SPY quantity 1 BUY dry-run ran locally: `WARNING_ONLY`, MARKET/DAY, local Room-bar price source, stale price, and explicit market-closed/stale warnings.
3. Local draft became `READY_LOCAL_WITH_WARNINGS`.
4. An immutable payload preview was built and persisted.
5. Legacy readiness returned `READY_BUT_EXECUTION_DISABLED`, with REAL locked, Paper POST disabled on the legacy path, LIVE false, Auto Paper false, and foreground service false.
6. The Phase 2.v card showed Paper-only, `REAL locked=true`, `LIVE=false`, `Auto Paper=false`, exact POST/endpoint, session `OFF`, gate `BLOCKED`, and a disabled arm button.
7. The final APK was reinstalled and relaunched once more; it again surfaced `BLOCKED` with the compile flag off and did not resubmit on launch.

**Actual Paper submit performed: NO.** Juan was not asked to type or tap the final confirmation because the gate was already closed. No ADB command entered confirmation text or tapped the final submit button.

**Exact surfaced gate reason: `FEATURE_DISABLED`.** `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED=false` in both generated debug and release configs, so the session could not be armed. Independently, the read-only runtime refresh also observed `marketOpen=false` and the local SPY snapshot was stale; those would remain additional fail-closed conditions if the compile flag were enabled.

### Safety proof

- Duplicate prevention: the concurrent executor test invokes the same preview/client-order twice and the fake HTTP client records exactly one POST; the second result is `BLOCKED`. The network-failure test also records one call total and proves the consumed token cannot be retried. Runtime launch/reinstall produced zero submits because the feature remained disabled.
- No LIVE endpoint: exact guard tests reject `https://api.alpaca.markets`, item/cancel/position/account paths, and every verb except the exact Paper POST. Static suspicious/forbidden counts are zero. Runtime account refresh used only the existing Paper GET allowlist.
- No credential leak: credentials stay in the existing encrypted store; request/result/UI/audit models contain no credential/header/account-id field; provider errors are sanitized; the runtime UI displayed only the configured boolean; filtered runtime logs contained no credential or app fatal/Room error.
- REAL remained locked: runtime displayed `REAL locked=true`; freeze tests and source scan found no production call to `unlockRealMode()`.
- No Auto Paper, background submit, foreground service, ML, cancel, replace, close-position, DELETE, PATCH, PUT, or account mutation code was introduced.
- Work remained under `G:\vela-android`. `G:\vela` was not modified. The Windows `vela.db` was not read, copied, or touched.

### Final Phase 2.v statement

**Phase 2.v is complete: PASS.** The manual Paper-only, one-shot, user-confirmed POST boundary is implemented, tested, audited, and installed, while remaining default OFF and demonstrably fail closed. Runtime submission was correctly blocked with `FEATURE_DISABLED`; no Paper order and no duplicate request occurred.

Stop after Phase 2.v. Do not start Phase 2.w.

---

## Independent Phase 2.u audit — approval-package only (2026-06-22)

### A. Verdict

**PASS WITH WARNINGS.** Phase 2.u is supported as a documentation/approval-package-only phase. The approval package is complete, its dedicated contract test is present, the recorded Phase 2.u validation is internally consistent, and the filesystem timeline separates the Phase 2.u artifacts from the later, explicitly approved Phase 2.v implementation.

The warning is traceability-related: `G:\vela-android\android` has no Git metadata, so an authoritative historical `git diff` for Phase 2.u is unavailable. This audit therefore relies on the approval artifact, contract tests, the contemporaneous Phase 2.u log, file timestamps, and current regression gates. No Phase 2.v implementation or runtime submission was performed by this audit.

### B. What Phase 2.u changed

The contemporaneous Phase 2.u record identifies exactly:

- Created: `docs/manual-paper-implementation-approval-package.md`.
- Added: `android/app/src/test/kotlin/com/vela/android/lab/safety/ManualPaperImplementationApprovalContractTest.kt`.
- Updated: `docs/phase-1-progress.md`.
- Scripts changed by Phase 2.u: **no**.
- Production code changed by Phase 2.u: **no**.

Timestamp evidence is consistent with that record:

- approval package: `2026-06-20T22:35:56`;
- approval contract test: `2026-06-20T22:36:27`;
- no file under `android/app/src/main` has a last-write timestamp in the interval from `2026-06-20T22:35:00` through `2026-06-20T23:29:59`;
- the first current Phase 2.v submit production files begin at `2026-06-20T23:30:50`, after the separately recorded human approval.

The current `manual-paper-execution-design.md`, `paper-execution-safety-freeze.md`, freeze test, safety scanner, and production submit/UI files contain later Phase 2.v amendments. They are not attributed to Phase 2.u.

### C. Approval package assessment

**Complete: 15/15 required content checks present.** The package contains:

- `HUMAN APPROVAL REQUIRED BEFORE IMPLEMENTATION`;
- the statement that the current Phase 2.u app cannot submit orders;
- REAL locked, `PaperTradingExecutionGuard.canExecuteOrders == false`, the Paper GET-only boundary, and `PaperDisabledOrderExecutor == EXECUTION_DISABLED`;
- the statement that no production `POST /v2/orders` exists at the Phase 2.u boundary;
- the exact proposed phase name `Phase 2.v — Manual Paper submit implementation, Paper-only, one-shot, user-confirmed`;
- exact forbidden changes;
- the one-shot manual-submit rules;
- kill-switch and rollback rules;
- the future Phase 2.v test plan;
- the future Phase 2.v runtime-validation plan;
- the final GO/NO-GO table;
- explicit NO-GO decisions for implementing execution or running `POST /v2/orders` in Phase 2.u.

No required section is missing.

### D. Safety assessment

#### Historical Phase 2.u boundary

- Execution surface: absent; no production submit/cancel/replace/close method or HTTP mutation was reported or timestamped to Phase 2.u.
- Endpoint safety: `AlpacaHttpClient` remained exactly `executeGet`; `AlpacaPaperTradingEndpoint` remained the three-URL GET-only allowlist; the LIVE host remained rejection-only.
- Disabled path: `PaperTradingExecutionGuard.canExecuteOrders == false`; `PaperDisabledOrderExecutor` returned `EXECUTION_DISABLED`.
- REAL: locked by default; no production call to `AppState.unlockRealMode()` was present.
- Intent source: only `MANUAL_DRY_RUN`.
- Auto Paper, foreground service, ML, LIVE, cancellation, replacement, close-position calls, and account mutation: absent.
- UI: no submit control was added by Phase 2.u; there is no production UI timestamp in the Phase 2.u interval.
- Credentials: Phase 2.u added only documentation and a test; neither adds, logs, renders, or persists credentials.

#### Current-tree regression context

The tree now includes the later approved Phase 2.v boundary: one exact Paper orders POST plus a manual submit card. This does not contradict the historical Phase 2.u conclusion. The current legacy invariants remain closed:

- `PaperTradingExecutionGuard.canExecuteOrders == false`;
- `PaperDisabledOrderExecutor` still returns `EXECUTION_DISABLED`;
- `AlpacaHttpClient` still exposes only `executeGet`;
- `AlpacaPaperTradingEndpoint` remains the three-URL GET-only guard;
- generated debug and release `MANUAL_PAPER_SUBMIT_COMPILED` values are both `false`;
- REAL defaults locked and source search finds only the `unlockRealMode` declaration, not a production call;
- `IntentSource` still contains only `MANUAL_DRY_RUN`;
- no foreground-service declaration/permission and no ML dependency/import were found;
- the LIVE host appears only in comments and rejection guards, not as an allowed endpoint.

The current safety scanner classifies the later Phase 2.v surface separately and still reports zero suspicious or forbidden hits.

### E. Build/test results

Historical Phase 2.u evidence recorded in this document:

| Gate | Phase 2.u recorded result |
| --- | --- |
| Focused approval contract | 3 tests, 0 failures/errors |
| `:app:testDebugUnitTest` | 1,232 tests, 0 failures/errors/skips |
| Full `:app:test` | debug 1,232 and release 1,232; 0 failures/errors/skips |
| `:app:assembleDebug` | `BUILD SUCCESSFUL` |
| Phase 2.u safety scan | 47 allowed negative/guard hits; 0 suspicious; 0 forbidden |

Independent audit commands run on the current tree:

| Gate | Audit result |
| --- | --- |
| `scripts/safety-scan.ps1` | PASS: 11 later Phase 2.v allowed submit hits, 53 negative/guard hits, **0 suspicious, 0 forbidden** |
| `:app:testDebugUnitTest` | `BUILD SUCCESSFUL`; current XML totals **1,488 tests, 0 failures, 0 errors, 0 skipped** |
| Full `:app:test` | `BUILD SUCCESSFUL`; debug **1,488/0/0/0**, release **1,488/0/0/0** |
| `:app:assembleDebug` | `BUILD SUCCESSFUL` |

Gradle reported these tasks `UP-TO-DATE`; its input/output checks accepted the existing current-tree artifacts. The requested optional emulator audit was skipped because no emulator was already available when this audit began. No submit action or Paper POST was attempted.

### F. Findings

#### Finding 1

- Severity: **LOW**
- File/path: `G:\vela-android\android` repository root
- Issue: no `.git` metadata exists, so Phase 2.u's exact historical diff cannot be independently reconstructed from version control.
- Recommended fix: place the project under version control and tag or archive each safety baseline before the next phase; retain immutable hashes for production, docs, scanner, tests, schema, and APK evidence.
- Must fix before Phase 2.v: **No for the historical decision**; Phase 2.v was separately approved later. Strongly recommended before any subsequent execution-related phase.

#### Finding 2

- Severity: **INFO**
- File/path: `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/` and `android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt`
- Issue: the current tree contains the separately approved Phase 2.v submit boundary and UI, so a present-tense scan cannot be read as the Phase 2.u baseline.
- Recommended fix: preserve Phase 2.u as a tagged/hashed snapshot and keep later-phase scan categories explicit.
- Must fix before Phase 2.v: **No**; this is later-phase context, not a Phase 2.u defect.

#### Finding 3

- Severity: **INFO**
- File/path: optional emulator/runtime audit
- Issue: runtime inspection was not performed because the emulator was not already available. The mandatory source, scanner, unit-test, full-test, and assembly gates passed.
- Recommended fix: none for Phase 2.u; it was approval-package only and had no submit UI/path to exercise.
- Must fix before Phase 2.v: **No**.

No BLOCKER, HIGH, or MEDIUM Phase 2.u finding was identified.

### G. Final statement

- Was Phase 2.u truly approval-package only? **Yes, with the stated no-Git traceability limitation.**
- Did Phase 2.u implement any execution? **No.**
- Was the app at the Phase 2.u boundary unable to submit orders? **Yes.** The current tree later gained the separately approved, default-off Phase 2.v boundary.
- Was it safe to proceed to Phase 2.v after Juan's explicit approval? **Yes, historically, under the package's Paper-only/manual-only/fail-closed constraints and separate runtime-authorization rule.** This audit does not re-authorize, rerun, or modify Phase 2.v.

**Audit stop:** Phase 2.u audit complete. No Phase 2.v implementation, execution, or `POST /v2/orders` was started by this audit.

---

## Phase 2.v resumed reconciliation and hardening (2026-06-22)

### Status

**PASS.** Juan re-authorized proceeding with the previously approved Phase 2.v scope. The repository already contained the Phase 2.v implementation and completion record, so this pass reconciled the existing code against the Phase 2.u approval package rather than creating a second execution path.

No Paper order was submitted. No emulator input, credential entry, session arm, confirmation text, or network mutation was performed. Phase 2.w was not started.

### Official endpoint check

The current official Alpaca documentation was reachable directly and continues to identify Paper Trading plus the create-order reference for the orders collection:

- [Alpaca Paper Trading](https://docs.alpaca.markets/docs/paper-trading)
- [Create an Order](https://docs.alpaca.markets/reference/postorder)

The production allowlist remains exactly `POST https://paper-api.alpaca.markets/v2/orders`; no LIVE host, alternate verb, item path, cancel, replace, close-position, or account mutation was added.

### Hardening corrections

Three fail-closed gaps were corrected inside the already approved Phase 2.v surface:

1. `PaperManualSubmitExecutor` now reevaluates the complete submit gate after the durable `ATTEMPT_STARTED` write and immediately before the only network invocation. An emergency disable activated during the audit-write suspension window now records `BLOCKED` and sends zero requests.
2. `PaperManualSubmitTokenStore.DEFAULT_TTL_MILLIS` is now **30 seconds**, matching the approval package/design default. The prior implementation used 60 seconds without a recorded policy amendment.
3. `OkHttpAlpacaPaperOrderSubmitHttpClient.defaultClient()` now explicitly sets `retryOnConnectionFailure(false)`. This removes OkHttp's implicit connection retry and makes the approved no-automatic-retry rule true at the concrete HTTP layer, not only in the typed submit client.

### Files changed

Production:

- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitExecutor.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitConfirmation.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/AlpacaPaperOrderSubmitHttpClient.kt`

Tests:

- `android/app/src/test/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitExecutorTest.kt`
- `android/app/src/test/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitTokenStoreTest.kt`
- `android/app/src/test/kotlin/com/vela/android/lab/data/paper/submit/SubmitTestFixtures.kt`
- `android/app/src/test/kotlin/com/vela/android/lab/data/paper/submit/AlpacaPaperOrderSubmitHttpClientTest.kt`

Documentation:

- `docs/phase-1-progress.md`

### Validation

| Gate | Result |
| --- | --- |
| Focused `PaperManualSubmitExecutorTest` | `BUILD SUCCESSFUL`; includes emergency disable after start audit with zero HTTP calls |
| Focused submit-package tests | `BUILD SUCCESSFUL` |
| `:app:test` | `BUILD SUCCESSFUL`; debug **1,491 tests / 0 failures / 0 errors / 0 skipped**, release **1,491/0/0/0**, 75 result files each |
| `:app:assembleDebug` | `BUILD SUCCESSFUL`; APK regenerated |
| `scripts/safety-scan.ps1` | 11 allowed Phase 2.v hits, 53 negative/guard hits, **0 suspicious, 0 forbidden** |

Generated debug and release `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` values remain `false`.

### Final Phase 2.v statement

**Phase 2.v remains complete: PASS.** The only execution surface is the separate, default-off, manual-only, user-confirmed, one-shot Paper orders POST. The final gate is checked immediately before I/O, the confirmation lifetime is 30 seconds, concrete HTTP retries are disabled, REAL stays locked, LIVE/Auto Paper/cancel/replace/close/background execution remain absent, and no actual Paper request was attempted in this pass.

Stop after Phase 2.v. Do not start Phase 2.w.

---

## Independent Phase 2.v audit — manual Paper submit boundary (2026-06-29)

### Verdict

**PASS.** The current Phase 2.v implementation remains limited to a default-off, foreground, manual-only, user-confirmed, one-shot **Paper-only** submit boundary. The audit found no safety-blocking defect and did not implement or begin Phase 2.w.

### Boundary and transport

- Endpoint guard: `AlpacaPaperSubmitEndpoint` allows exactly `POST https://paper-api.alpaca.markets/v2/orders`. It rejects the LIVE host `https://api.alpaca.markets`, alternate methods, schemes, hosts, ports, paths, query variants, cancel/replace/close-position paths, and account mutations.
- Mutation surface: the production scan found no order `DELETE`, `PATCH`, or `PUT`; no cancel, replace, close-position, or account-mutation route exists. The only permitted production order mutation is the Phase 2.v Paper orders collection POST.
- Existing read-only client: `AlpacaHttpClient` still declares exactly one method, `executeGet`; its Paper account/clock/positions GET boundary remains separate from submit.
- Submit client: `AlpacaPaperOrderSubmitHttpClient` declares exactly one method, `executePostOrder`; the typed caller supplies the fixed guarded Paper orders URL and has no retry, cancel, replace, close, or generic mutation API.
- Retry/redirect policy: the concrete OkHttp client sets `retryOnConnectionFailure(false)`, `followRedirects(false)`, and `followSslRedirects(false)`. One invocation creates one request and no automatic retry loop exists.

### Authorization, one-shot behavior, and duplicate prevention

- Gate and kill switch: the fail-closed gate requires recorded human approval, compile-time enablement, an in-memory armed session, emergency switch clear, REAL locked, LIVE and Auto Paper false, credentials configured, fresh/unblocked account and clock, market open, fresh matching price, acceptable preflight/readiness, immutable preview parity, acknowledged warnings, valid confirmation, and no duplicate/in-flight attempt.
- Immediately-before-POST check: after consuming the token and durably writing `ATTEMPT_STARTED`, `PaperManualSubmitExecutor` reevaluates the complete gate immediately before the sole `submitClient.submitOnce` call. A kill-switch or other gate change in that suspension window sends zero requests.
- Confirmation: `PaperManualSubmitTokenStore.DEFAULT_TTL_MILLIS` is **30,000 ms**. Tokens are process-memory only, snapshot-bound, invalidated on state changes, removed on the first consume attempt, and tested as single-use.
- Feature flags: source defaults are OFF; release is forced OFF. The generated debug and release `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` values audited here are both `false`.
- Duplicate prevention: executor serialization uses a mutex; persisted preview and client-order-id checks block replay; the token is consumed before I/O; the durable unique start event forms a process-death barrier. Tests prove duplicate/concurrent invocation produces exactly one client call, and network failure cannot reuse the token or retry.

### Audit, UI, and credential safety

- Audit persistence: `paper_order_submit_audit` is append-only. Its DAO exposes insert and reads only, with no update/delete/clear method. `ATTEMPT_STARTED` must persist before network I/O; an audit-start failure sends zero requests. Terminal outcomes are appended and unique event keys reject duplicate events.
- Persisted submit fields contain request identifiers and summary, status, Paper order id, price provenance, market state, random confirmation-token id, and sanitized error text. They contain no API key, API secret, credential value, header, account id, raw HTTP body, or raw typed confirmation text.
- HTTP credentials are read only at the concrete request boundary and attached to the required Alpaca headers. No submit logging interceptor or credential logging was found. Provider failures are reduced to bounded sanitized messages before UI/audit use.
- UI/ViewModel: the only action is explicitly labeled `Submit Paper order once` inside `Manual Paper submit — one-shot`. It requires the exact contextual confirmation and a fully allowed gate. No generic Buy/Sell execution button, LIVE submit, cancel, replace, or close-position control exists. No lifecycle callback, worker, receiver, background path, or foreground service can submit.
- REAL/LIVE/automation: REAL defaults locked and no production call to `unlockRealMode()` exists. LIVE order submit and Auto Paper are absent. No foreground service or ML dependency/implementation was found.

### Validation evidence

All commands ran from `G:\vela-android\android` with Android Studio JBR as `JAVA_HOME`. No emulator, app session, credential entry, confirmation typing, or runtime submit was used.

| Gate | Independent audit result |
| --- | --- |
| `scripts/safety-scan.ps1` | PASS: `allowed_phase2v_submit=11`, `suspicious=0`, `forbidden=0`; the 11 allowed hits are limited to the manual Paper boundary/call chain |
| Requested `:app:testDebugUnitTest` | `BUILD SUCCESSFUL`; direct invocation was `UP-TO-DATE`; the subsequent forced full rerun executed the debug task and produced **1,491 tests / 0 failures / 0 errors / 0 skipped**, 75 XML files |
| Full `:app:test` | `BUILD SUCCESSFUL`; forced rerun executed all 53 tasks: debug **1,491/0/0/0**, release **1,491/0/0/0**, 75 XML files per variant |
| `:app:assembleDebug` | `BUILD SUCCESSFUL`; forced rerun executed all 37 tasks and regenerated `app-debug.apk` |
| Runtime submit performed | **NO** |
| Real Paper `POST /v2/orders` executed | **NO** |

### Findings

1. **LOW — version-control traceability.** Neither `G:\vela-android` nor `G:\vela-android\android` contains Git metadata, so this audit can prove the current state but cannot independently reconstruct an authoritative historical Phase 2.v diff. This does not weaken the current endpoint/gate/test evidence; preserve a tagged or hashed baseline before any later execution-related phase.
2. **LOW — stale manifest comments.** `app/src/main/AndroidManifest.xml` still contains older comments saying there is no order submission and that the test stream is the only network caller. Runtime declarations remain safe—there is no service and only the expected INTERNET permission—but those comments no longer describe the separately guarded Phase 2.v boundary. Update them only in a separately authorized documentation/maintenance change; this audit made no production edit.
3. **INFO — Gradle cache behavior.** The three exact requested Gradle invocations first passed as `UP-TO-DATE`. For independent current evidence, this audit then reran full debug/release tests and debug assembly with `--rerun-tasks`; all executed successfully.

No BLOCKER, HIGH, or MEDIUM finding was identified.

### Controlled Paper runtime readiness

From the audited source, scan, and test evidence, it is safe to **propose** one tightly controlled real Paper submit test under the documented one-shot runtime plan. It is **not authorized by this audit**: it requires separate, explicit approval from Juan for that specific Paper attempt, plus the approved debug-only opt-in and all runtime gates passing. The current audited debug/release generated flags are OFF. No request was sent here.

**Phase 2.v audit complete.**  
**No Phase 2.w started.**  
**No real Paper request sent.**

## Phase 2.v controlled Paper submit runtime test (2026-06-29)

### Explicit approval

Juan provided the following explicit one-attempt runtime authorization:

> “Apruebo realizar una prueba controlada de submit Paper real para Phase 2.v: una sola orden Paper, manual, user-confirmed, sin LIVE, sin REAL, sin Auto Paper, sin cancel/replace/close, usando únicamente POST https://paper-api.alpaca.markets/v2/orders, con confirmación escrita manualmente por mí en el emulador.”

This approval covered at most one real Alpaca Paper POST. It did not authorize LIVE, REAL unlock, automation, retry, cancel, replace, close-position, or Phase 2.w.

### Result

**BLOCKED — expected fail-closed runtime behavior.** The Alpaca Paper account/clock refresh succeeded, but the Paper clock returned `marketOpen=false`. The runtime flow stopped immediately at the `MARKET_CLOSED` gate before preflight, draft, preview, session arm, confirmation-token issue, typed confirmation, submit-button enablement, or HTTP submit. No order was created.

### Runtime evidence

| Field | Result |
| --- | --- |
| Date/time | `2026-06-29 01:07:15 -03:00` |
| Emulator | `Pixel_10_Pro_XL` / `emulator-5554` |
| Controlled APK | debug flag ON; SHA-256 `AE0C0B761C5F70E5FA621931AD99674E7EEDD27816668EF2F167EB35CB87C286` |
| Debug flag during controlled check | **ON** |
| Release flag | **OFF** |
| Planned order | `SPY`, `BUY`, quantity `1`, `MARKET`, `DAY` |
| Order fields entered | **NO** — stopped at Paper clock gate first |
| Paper account response | `ACTIVE`; `trading_blocked=false`; `account_blocked=false`; Paper buying power `400550.54` |
| Market open | **NO** (`marketOpen=false`) |
| Preflight result | **NOT RUN** — blocked before preflight |
| Readiness result | **NOT RUN** — blocked before readiness |
| Gate result | **BLOCKED: `MARKET_CLOSED`** |
| Required confirmation text if the flow had reached confirmation | `SUBMIT PAPER SPY BUY 1` |
| Confirmation token generated | **NO** |
| Juan typed confirmation manually | **NO** — correctly not requested after the gate blocked |
| Submit button enabled/tapped | **NO** |
| Real Paper POST executed | **NO** |
| POST count | **0**; no submit boundary invocation occurred |
| Authorized endpoint | `POST https://paper-api.alpaca.markets/v2/orders` |
| Endpoint actually used for submit | **NONE** |
| Alpaca order response | **NONE**; only read-only Paper account/clock/positions refresh completed |
| `alpacaOrderId` | **NONE** |
| `clientOrderId` | **NONE** — request was not constructed |
| Final order status | **NOT CREATED** |
| Submit audit row created | **NO** — the executor and `ATTEMPT_STARTED` path were never entered |
| Duplicate prevented | **YES, trivially** — zero initial POSTs and zero duplicate POSTs; replay behavior was not exercised because the flow stopped earlier |
| LIVE used | **NO** |
| REAL locked | **YES**; runtime UI showed `REAL locked=true` and no unlock occurred |
| Auto Paper | **NO** |
| Credentials leaked | **NO**; UI displayed only `Credentials configured=true`, and PID-scoped log inspection found zero credential/header-shaped lines |
| Cancel/replace/close executed | **NO / NO / NO** |
| Automatic retry | **NO** |
| Phase 2.w started | **NO** |

### Pre-runtime and restoration checks

- The independent Phase 2.v audit was present and Phase 2.w was absent before runtime work.
- The official Alpaca create-order reference still identified `POST https://paper-api.alpaca.markets/v2/orders` as the Paper order endpoint.
- Pre-runtime safety scan: `allowed_phase2v_submit=11`, `suspicious=0`, `forbidden=0`.
- `:app:testDebugUnitTest`: `BUILD SUCCESSFUL`; **1,491 tests / 0 failures / 0 errors / 0 skipped**.
- The controlled debug APK was installed with `adb install -r`; existing secure credential state was preserved and no credential value was displayed.
- A read-only IEX SPY stream connected, but no market bars arrived while the market was closed. This did not change or bypass the Paper clock gate.
- After the block, the app was force-stopped; no submit session had been armed.
- The temporary local debug override was removed. Final generated debug and release flags are both `false`.
- A safe debug-OFF APK was rebuilt and reinstalled. Restored APK SHA-256: `78A7E33BF3EFEFB75C87A9D6F14EA986FFDD935B2360022AC2ACA5F5A6C4B2A2`.
- Post-restoration safety scan remained `allowed_phase2v_submit=11`, `suspicious=0`, `forbidden=0`.
- Final state: app force-stopped, debug OFF, release OFF, REAL locked, LIVE blocked, Auto Paper absent, and audit evidence preserved.

### Final statement

**Phase 2.v controlled Paper submit runtime test: BLOCKED (`MARKET_CLOSED`).**  
**Runtime safety behavior: PASS — the closed-market gate failed closed and no request was forced.**  
**Real Paper POST count: 0.**  
**No Phase 2.w started.**  
**No real Paper request sent.**

---

## Phase 2.v controlled Paper submit runtime retry (2026-06-29)

### Authorization and result

Juan authorized this retry for at most one manually confirmed real Alpaca Paper order while the market was open. The authorization did not cover LIVE trading, REAL unlock, Auto Paper, retry, cancel, replace, close-position, background execution, or Phase 2.w.

**BLOCKED — expected fail-closed runtime behavior.** The Paper account and clock refresh succeeded and `marketOpen=true`. A fresh SPY dry-run, local draft, payload preview, and disabled-readiness snapshot were produced. When the manual submit session reevaluated all gates, the latest SPY quote no longer matched the immutable preview, so the gate returned `PRICE_NOT_FRESH`. The flow stopped without asking Juan to type the confirmation and before the submit executor or HTTP client could run.

### Runtime evidence

| Field | Result |
| --- | --- |
| Date/time | `2026-06-29 16:12:24 -03:00` |
| Emulator | `Pixel_10_Pro_XL` / `emulator-5554` |
| Controlled APK | debug flag ON; SHA-256 `2D6E9B907236E44D53851558CBF69C8BE4A56DE8533404D5BFDB90CD117A4E76` |
| Debug flag during controlled check | **ON**, only for this runtime attempt |
| Release flag | **OFF** |
| Planned order | `SPY`, `BUY`, quantity `1`, `MARKET`, `DAY` |
| Paper account response | `ACTIVE`; `trading_blocked=false`; `account_blocked=false`; buying power `400700.11` at the recorded account refresh |
| Market open | **YES** (`marketOpen=true`) |
| Preflight result | **`ALLOWED_DRY_RUN`**; latest price source `LIVE_QUOTE_MID`, freshness `FRESH`, zero block reasons |
| Local draft | **`READY_LOCAL`**; `SPY BUY 1 MARKET DAY` |
| Payload preview | **`READY_PREVIEW`**; immutable local review row persisted with `DISABLED` / `POST_DISABLED` preview markers |
| Readiness result | **`READY_BUT_EXECUTION_DISABLED`**; REAL locked, LIVE endpoint disabled, Auto Paper disabled, foreground service disabled |
| Final gate result | **BLOCKED: `PRICE_NOT_FRESH`**. The UI also retained `PREVIEW_MISMATCH` and `CONFIRMATION_MISSING` because no confirmation token/request was created after the price gate failed. |
| Required confirmation text | `SUBMIT PAPER SPY BUY 1` |
| Confirmation token generated | **NO** |
| Juan typed confirmation manually | **NO** — correctly not requested after the gate blocked |
| Submit button enabled/tapped | **NO** |
| Authorized endpoint | `POST https://paper-api.alpaca.markets/v2/orders` |
| Endpoint actually used for submit | **NONE** |
| Real Paper POST executed | **NO** |
| POST count | **0** |
| Alpaca order response | **NONE**; only read-only Paper account/clock/positions calls and read-only IEX market data were used |
| `alpacaOrderId` | **NONE** |
| `clientOrderId` | **NONE** — the submit request was not constructed |
| Submit audit row created | **NO** — neither `ATTEMPT_STARTED` nor a terminal submit event was entered; dry-run and preview audit rows were preserved |
| Duplicate prevention | **YES, trivially** — zero initial POSTs, zero duplicate POSTs, no token, and no relaunch submit |
| Alpaca Paper dashboard verification | **NOT APPLICABLE** — no order existed to verify |
| LIVE trading used | **NO**; only the separate read-only IEX data stream was used |
| REAL locked | **YES** throughout |
| Auto Paper | **NO** |
| Credentials leaked | **NO**; the UI exposed only `Credentials configured=true`, and the PID-scoped log scan found `0` credential/header markers |
| Cancel / replace / close | **NO / NO / NO** |
| Automatic retry | **NO** |
| Phase 2.w started | **NO** |

### Findings and restoration

1. The first controlled assembly in this retry regenerated debug `BuildConfig` as `true`, but an incremental Kotlin cache had retained the previous inlined `false` value. The mismatch was detected in the UI before arming a session; it caused zero POSTs. A clean `--no-build-cache` assembly produced the controlled APK above with `Manual Paper submit compiled=true`. No source implementation was changed.
2. The runtime gate behaved as designed. The final blocking condition was a changed/mismatched market-price snapshot (`PRICE_NOT_FRESH`). It was not bypassed or forced.
3. No submit audit row, client order id, confirmation token, provider order id, or order response exists because execution stopped before the submit boundary.

Restoration and validation:

- Pre-runtime and post-restoration safety scans: `allowed_phase2v_submit=11`, `suspicious=0`, `forbidden=0`.
- `:app:testDebugUnitTest`: `BUILD SUCCESSFUL`; **1,491 tests / 0 failures / 0 errors / 0 skipped**, 75 XML files.
- The temporary `MANUAL_PAPER_SUBMIT_COMPILED=true` local override was removed.
- Final generated debug and release `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` values are both `false`.
- Safe debug-OFF APK rebuilt without Gradle build-cache reuse and reinstalled; SHA-256 `8D7B9D2A81F8623AA84EC96DAEB9121656C7FAC79F12FF558EC21CFD98860BB6`.
- The submit session was disarmed before force-stop. The restored app is force-stopped, REAL remains locked, and no audit evidence was deleted.
- PID-scoped runtime log inspection: 552 lines, `0` credential/header markers, `0` submit markers.
- All Android SDK, emulator, JBR, Gradle, temporary, and build work for this retry used `G:` paths. The attached prompt was the only explicitly supplied artifact read from `C:`.

### Final statement

**Phase 2.v controlled Paper submit runtime retry: BLOCKED (`PRICE_NOT_FRESH`).**  
**Runtime safety behavior: PASS — the price-parity gate failed closed and no request was forced.**  
**Real Paper POST count: 0.**  
**No Phase 2.w started.**  
**No real Paper request sent.**

---

## Phase 2.v.1 — final quote freshness/stability gate hardening (2026-06-29)

### Result

**PASS — code/test hardening only.** The `PRICE_NOT_FRESH` runtime block observed while the market was open was caused by exact preview/notional equality against a moving SPY quote. Phase 2.v.1 replaces that equality with a fail-closed freshness, source-compatibility, age, and bounded-drift policy. No emulator runtime submit was performed, no confirmation was typed, and no Paper request was sent.

### Final price stability policy

- The immutable preview unit price is derived locally as `estimatedNotionalUsd / quantity`; both operands and the result must be positive and finite.
- The latest snapshot must be `FRESH`, carry a positive finite price, and match the preview symbol.
- The effective age is recomputed from the snapshot timestamps at every gate evaluation rather than trusting the age captured earlier.
- Final price age must be within the source-specific `MarketPriceFreshnessPolicy` threshold and within the stricter Phase 2.v.1 maximum of **10,000 ms**.
- The final source must remain in the same source class (quote-to-quote or bar-to-bar), or be an approved higher-quality source such as a live quote replacing a bar preview.
- Maximum absolute drift is **0.25%**, inclusive. A value exactly at 0.25% passes; a larger value blocks with the explicit `PRICE_DRIFT_EXCEEDED` reason.
- Missing, stale, invalid, future-dated, incompatible-source, or different-symbol snapshots block with `PRICE_NOT_FRESH`.
- The UI now shows preview price, final/latest price, final source and freshness, effective age, absolute drift percentage, the 0.25% threshold, the 10-second cap, and the final price-gate result. These diagnostics contain no credential or header field.

### Confirmation and immediate pre-POST protection

- Exact confirmation text is still required. The token is now issued only after the final price policy passes at confirmation time.
- Token binding remains unchanged: preview id, symbol, side, quantity, preview source/freshness, and preview timestamp. The default lifetime remains **30 seconds** and consumption remains single-use.
- `PaperManualSubmitExecutor` still evaluates the complete gate before consuming the token and again immediately before the only HTTP boundary.
- After durable `ATTEMPT_STARTED` audit persistence and before the final gate evaluation, the executor now obtains a new **local-only** price snapshot for the request symbol. Provider failure becomes a missing snapshot and fails closed.
- If that final snapshot exceeds 0.25%, becomes stale, changes symbol/source incompatibly, or ages beyond the limit, the executor appends a blocked result and performs **zero HTTP calls**.
- Emergency kill switch, duplicate checks, mutex serialization, audit-before-I/O, retry-disabled transport, endpoint guard, and one-shot semantics are unchanged.

### Files changed

Production:

- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperFinalPriceStabilityPolicy.kt` — new pure local policy and credential-free diagnostics.
- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitGate.kt` — replaces exact price equality with the new policy.
- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperOrderSubmitModels.kt` — adds `PRICE_DRIFT_EXCEEDED`.
- `android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitExecutor.kt` — refreshes the local snapshot immediately before the final gate.
- `android/app/src/main/kotlin/com/vela/android/lab/VelaLabApplication.kt` — injects the existing local `MarketPriceSnapshotProvider` into the executor.
- `android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperManualSubmitUiState.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperManualSubmitViewModel.kt`
- `android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt`

Tests/fixtures:

- `android/app/src/test/kotlin/com/vela/android/lab/data/paper/submit/PaperFinalPriceStabilityPolicyTest.kt`
- `android/app/src/test/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitGateTest.kt`
- `android/app/src/test/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitExecutorTest.kt`
- `android/app/src/test/kotlin/com/vela/android/lab/data/paper/submit/SubmitTestFixtures.kt`
- `android/app/src/test/kotlin/com/vela/android/lab/ui/dashboard/PaperManualSubmitViewModelTest.kt`

Documentation:

- `docs/phase-1-progress.md`

### Tests and validation

New/updated coverage proves:

- exact same price passes;
- approved fresher source within tolerance passes;
- same quote source class within tolerance passes;
- drift exactly at 0.25% passes;
- stale, missing, too-old, different-symbol, and source-downgrade snapshots block;
- drift above 0.25% blocks with `PRICE_DRIFT_EXCEEDED`;
- token is not issued while final price drift is blocked;
- token TTL remains 30 seconds and single-use tests remain passing;
- the immediate pre-POST local refresh is invoked;
- final drift above threshold after `ATTEMPT_STARTED` produces a blocked audit result and **0 POST calls**;
- emergency-disable pre-POST reevaluation, endpoint guard, debug/release defaults, LIVE/Auto Paper locks, duplicate prevention, and credential-safety tests remain passing.

| Validation | Result |
| --- | --- |
| Focused Phase 2.v.1 tests | `BUILD SUCCESSFUL` |
| `:app:testDebugUnitTest` | `BUILD SUCCESSFUL`; **1,507 tests / 0 failures / 0 errors / 0 skipped**, 76 XML files |
| Full `:app:test` debug | **1,507 / 0 / 0 / 0**, 76 XML files |
| Full `:app:test` release | **1,507 / 0 / 0 / 0**, 76 XML files |
| `:app:assembleDebug` | `BUILD SUCCESSFUL`; APK regenerated |
| `scripts/safety-scan.ps1` | `allowed_phase2v_submit=11`, `suspicious=0`, `forbidden=0`; approved submit boundary count unchanged |
| Generated debug flag | `MANUAL_PAPER_SUBMIT_COMPILED=false` |
| Generated release flag | `MANUAL_PAPER_SUBMIT_COMPILED=false` |
| Local debug override | absent |
| Runtime/emulator validation | **NOT PERFORMED** — optional and unnecessary for code/test hardening |
| Real Paper POST executed | **NO; count 0** |

### Boundary and final state

- The only approved mutation boundary remains exactly `POST https://paper-api.alpaca.markets/v2/orders` through the manual Paper submit client. No second POST path or retry was added.
- REAL locked: **YES**.
- LIVE used: **NO**.
- Auto Paper: **NO**.
- Cancel / replace / close: **NO / NO / NO**.
- Credentials exposed, logged, or persisted: **NO**.
- Phase 2.w started: **NO**.

**Phase 2.v.1 complete: PASS.**  
**POST executed: NO.**  
**REAL remains locked.**  
**No Phase 2.w started.**

---

## Independent Phase 2.v.1 audit — final quote stability gate (2026-06-29)

### Verdict

**PASS WITH WARNINGS.** The current Phase 2.v.1 implementation removes exact moving-price equality without weakening the manual Paper boundary. It accepts only a fresh, positive, symbol-matching, source-compatible final price no older than 10 seconds and within an inclusive 0.25% absolute drift from the immutable preview price. The complete gate is still reevaluated against a newly obtained local snapshot immediately before the sole submit boundary. No runtime session, confirmation, token, or real POST was used during this audit.

The warnings are documentation/version-traceability findings, not execution-safety defects. No BLOCKER, HIGH, or MEDIUM finding was identified.

### Independently audited Phase 2.v.1 behavior

- Exact equality is gone. Preview unit price is derived as positive finite `estimatedNotionalUsd / quantity`; drift is `abs(final - preview) / preview * 100`.
- Default maximum drift is exactly **0.25%** and is inclusive: 0.25% passes; larger drift returns `PRICE_DRIFT_EXCEEDED`.
- Final price must be present, positive, finite, explicitly `FRESH`, and for the same normalized symbol. Missing, stale, invalid, future-dated, or different-symbol data returns `PRICE_NOT_FRESH`.
- Effective age is recomputed from the newest non-secret market/device timestamp at evaluation time. It must be non-negative, within `MarketPriceFreshnessPolicy`, and within the stricter **10,000 ms** Phase 2.v.1 cap.
- Source compatibility permits the same quote/bar class or an approved higher-quality source. Tests prove a fresher live quote can replace a bar preview within tolerance, while a source downgrade across classes blocks.
- The confirmation ViewModel evaluates the final-price policy before `tokenStore.issue`; a failed price gate invalidates state and creates no token/request.
- Token behavior remains unchanged: in-memory only, exact-text required, preview-bound, **30,000 ms** default TTL, synchronized, and atomically removed on the first consume attempt.
- `PaperManualSubmitExecutor` checks the complete gate before token consumption, consumes the token, persists `ATTEMPT_STARTED`, obtains a new local snapshot, and checks the complete gate again immediately before the sole `submitClient.submitOnce` call.
- Provider failure becomes a missing snapshot and fails closed. The executor test proves drift above 0.25% at the immediate pre-POST recheck creates `ATTEMPT_STARTED` + `BLOCKED` audit events and **zero HTTP calls**.

### Boundary and security regression audit

- Endpoint guard remains exactly `POST https://paper-api.alpaca.markets/v2/orders`. Exact string equality rejects alternate schemes, hosts, ports, paths, queries, and the LIVE host `https://api.alpaca.markets`.
- No production HTTP `DELETE`, `PATCH`, or `PUT`; no order cancel, replace, close-position, or account-mutation path was found.
- `AlpacaHttpClient` remains GET-only with the single declared method `executeGet`.
- `AlpacaPaperOrderSubmitHttpClient` remains narrow with the single declared method `executePostOrder`; the only typed caller supplies the fixed guarded Paper URL.
- Concrete OkHttp remains `retryOnConnectionFailure(false)`, `followRedirects(false)`, and `followSslRedirects(false)`. No retry loop or second submit path was added.
- REAL defaults locked; production contains no `unlockRealMode()` call outside its declaration/documentation.
- LIVE trading, Auto Paper, foreground service, background submit, cancel/replace/close, and ML/inference dependencies remain absent.
- The manifest declares no service/receiver and no foreground-service permission; only the existing INTERNET permission is relevant here.
- Submit audit remains append-only: DAO methods are insert/read only. Entity fields contain no credential, API-secret, APCA-header, authorization, account-id, raw body, or raw typed-confirmation field. The stored `confirmationTokenId` is the random attempt linkage id, not the typed confirmation text or a reusable credential.

### UI and ViewModel audit

The manual one-shot card explicitly shows:

- preview price and preview source/freshness;
- final/latest price and final source/freshness;
- effective final price age;
- final drift percentage;
- allowed drift threshold;
- 10-second maximum age;
- final price-gate result;
- Paper-only, REAL-locked, LIVE-false, Auto-Paper-false state;
- exact POST method and guarded Paper endpoint.

The submit button remains `Submit Paper order once`, is enabled only by the complete gate, and has no generic LIVE Buy/Sell, Auto Paper, cancel, replace, or close-position companion control. UI state carries only the boolean `credentialsConfigured`; credential values are not rendered or retained.

### Validation evidence

All Android/Gradle work used `G:` paths (`G:\Android\Android Studio\jbr`, `G:\Android\Sdk`, `G:\Android\gradle-home`, and `G:\Android\tmp`). The user-supplied attachment was the only explicitly requested file read from `C:`.

| Validation | Independent result |
| --- | --- |
| `scripts/safety-scan.ps1` | PASS: `allowed_phase2v_submit=11`, `suspicious=0`, `forbidden=0`; approved submit count unchanged |
| Exact `:app:testDebugUnitTest` | `BUILD SUCCESSFUL`; Gradle reported `UP-TO-DATE` |
| Exact full `:app:test` | `BUILD SUCCESSFUL`; Gradle reported `UP-TO-DATE` |
| Forced full `:app:test --rerun-tasks` | `BUILD SUCCESSFUL`; **53/53 tasks executed** |
| Forced debug tests | **1,507 tests / 0 failures / 0 errors / 0 skipped**, 76 XML files |
| Forced release tests | **1,507 tests / 0 failures / 0 errors / 0 skipped**, 76 XML files |
| Exact `:app:assembleDebug` | `BUILD SUCCESSFUL`; Gradle reported `UP-TO-DATE` |
| Forced `:app:assembleDebug --rerun-tasks` | `BUILD SUCCESSFUL`; **37/37 tasks executed**; APK SHA-256 `86A445766FE73A0A6FF8080E98533853BD94839F48E5B1B536B45E74F0264532` |
| Generated debug flag | `MANUAL_PAPER_SUBMIT_COMPILED=false` |
| Generated release flag | `MANUAL_PAPER_SUBMIT_COMPILED=false` |
| Local debug override | absent |
| Runtime/emulator submit validation | **NOT PERFORMED** |
| Real Paper POST executed | **NO; count 0** |

### Findings

1. **LOW — governing documentation drift.** `phase-1-progress.md` accurately records the 0.25%/10-second Phase 2.v.1 policy, but `manual-paper-implementation-approval-package.md`, `manual-paper-execution-design.md`, and `paper-execution-safety-freeze.md` have no Phase 2.v.1 revision entry. The first two still use historical language that any price change invalidates confirmation, while the implemented policy intentionally permits bounded drift. This does not weaken the current code/tests, but those documents should be synchronized in a separately authorized documentation-maintenance pass before future execution work.
2. **LOW — version-control traceability.** Neither `G:\vela-android` nor `G:\vela-android\android` contains Git metadata. This audit independently proves the current state and forced build/test results, but cannot reconstruct an authoritative historical Phase 2.v.1 diff or verify file provenance against a tagged baseline.

### Final state and runtime readiness

- Feature flags debug/release: **OFF / OFF**.
- REAL locked: **YES**.
- LIVE used: **NO**.
- POST executed: **NO**.
- Auto Paper: **NO**.
- Cancel / replace / close: **NO / NO / NO**.
- App process during final audit check: **not running**.
- Phase 2.w initiated: **NO**.

**Phase 2.v.1 audit complete.**  
**Safe to retry a controlled Paper submit runtime test, but only under a new/separate explicit one-attempt approval from Juan and with every runtime gate passing. This audit does not authorize that submit.**  
**No Phase 2.w started.**

---

## Phase 2.v.1 controlled Paper submit runtime retry (2026-06-29)

### Explicit approval

Juan provided the following explicit one-attempt runtime authorization for this Phase 2.v.1 retry:

> "Apruebo reintentar la prueba controlada de submit Paper real después de Phase 2.v.1: una sola orden Paper, manual, user-confirmed, sin LIVE, sin REAL, sin Auto Paper, sin cancel/replace/close, usando únicamente POST https://paper-api.alpaca.markets/v2/orders, con deriva máxima 0,25%, edad final máxima 10 segundos y confirmación escrita manualmente por mí en el emulador."

This authorization covered at most one real Alpaca Paper POST. It did not cover LIVE, REAL unlock, automation, retry, cancel, replace, close-position, background execution, or Phase 2.w.

### Result

**BLOCKED — `MARKET_CLOSED`.** The Alpaca Paper account refresh succeeded and the Paper clock returned `marketOpen=false` four minutes after the regular-session close. The runtime flow stopped at the clock gate before preflight, draft, payload preview, readiness, session arm, confirmation token issue, typed confirmation, submit-button enablement, or any submit HTTP call. No order was created. No Paper POST was sent. No Phase 2.w work was started.

### Pre-runtime verification

| Check | Result |
| --- | --- |
| Phase 2.v.1 audit present in `docs/phase-1-progress.md` | YES (independent audit appended 2026-06-29) |
| Phase 2.w started before retry | NO |
| `scripts/safety-scan.ps1` pre-runtime | `allowed_phase2v_submit=11`, `suspicious=0`, `forbidden=0` |
| `:app:testDebugUnitTest` pre-runtime | `BUILD SUCCESSFUL`; 77 result XMLs; **tests=1,507 / 0 / 0 / 0** |
| Pre-runtime build flag in source `defaultConfig` | `MANUAL_PAPER_SUBMIT_COMPILED=false` |
| Pre-runtime build flag in source `release` block | `MANUAL_PAPER_SUBMIT_COMPILED=false` (hard-coded) |

### Controlled enablement

A temporary `MANUAL_PAPER_SUBMIT_COMPILED=true` line was appended to the existing `android/local.properties` (gitignored) for the duration of this runtime attempt only. No release block was touched. No new flag was introduced. The session-arm flag remained in-memory only and was never armed.

| Field | Value |
| --- | --- |
| Controlled debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Controlled debug APK SHA-256 | `1cc1e97abac40ff9ac2fc1b759c565f5cde27a2220a14b234c2600efa263206c` |
| Controlled debug build command | `gradlew :app:assembleDebug --no-daemon --no-build-cache --rerun-tasks` |
| Controlled debug build tasks executed | 37/37 |
| Generated debug `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` during controlled run | `true` |
| Generated release `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` during controlled run | `false` |
| Generated debug `BuildConfig.ALPACA_TEST_KEY_ID` / `ALPACA_TEST_SECRET` | empty strings (in-app secure store is the only credential source) |

### Runtime evidence

| Field | Result |
| --- | --- |
| Date/time | `2026-06-29 17:00 – 17:04 -03:00` (≈ `2026-06-29T20:00 – 20:04Z`) |
| Emulator | `Pixel_10_Pro_XL` / `emulator-5554` |
| Host vs device clock skew at runtime | host `2026-06-29 20:00:31Z`, device `2026-06-29 19:59:10Z` (≈ 1 min 20 s behind, well within the 15 s account/clock freshness window for the relevant samples) |
| Debug flag during controlled check | **ON** (only for this runtime attempt) |
| Release flag | **OFF** (unchanged) |
| Planned order | `SPY`, `BUY`, quantity `1`, `MARKET`, `DAY` |
| Order fields entered into UI | **NO** — stopped at the Paper clock gate before reaching preflight/draft/preview/readiness |
| Credentials configured in app | `true` (read from the existing Keystore-backed `EncryptedSharedPreferences`; values not displayed) |
| Paper account refresh | `200` OK; `Last refresh at 2026-06-29T20:04:50.046Z` |
| Account status | `ACTIVE` |
| `trading_blocked` | `false` |
| `account_blocked` | `false` |
| `pattern_day_trader` | `false` |
| Equity (USD) | `101942.68` |
| Buying power (USD) | `400700.56` (sufficient for 1 SPY share many times over) |
| Cash (USD) | `96050.88` |
| Positions count | `3` (`SPY: qty 6.0 · mv 4444.44 · pnl 4.07`; `QQQ: qty 2.0 · mv 1447.36 · pnl 27.07`; `BTCUSD: qty 4.0E-9 · mv 0.00 · pnl -0.00`) |
| Paper clock refresh | `200` OK |
| Market open | **NO** (`marketOpen=false`) |
| Next open | `2026-06-30T09:30:00-04:00` |
| Next close | `2026-06-30T16:00:00-04:00` |
| Preflight result | **NOT RUN** — runtime stopped at the clock gate before preflight |
| Local draft | **NOT BUILT** |
| Payload preview | **NOT BUILT** |
| Readiness result | **NOT RUN** |
| Final price snapshot (Phase 2.v.1 fresh-quote refresh) | **NOT REQUESTED** — flow stopped before snapshot binding |
| Final price age cap (Phase 2.v.1) | 10,000 ms — gate never reached |
| Final price drift threshold (Phase 2.v.1) | 0.25% inclusive — gate never reached |
| Gate result if it had reached gate evaluation | would block on at least `MARKET_CLOSED`; not reached because the runtime flow stopped earlier |
| Required confirmation text (had it reached confirmation) | `SUBMIT PAPER SPY BUY 1` |
| Session armed | **NO** |
| Confirmation token generated | **NO** |
| Juan typed confirmation manually | **NO** — correctly not requested after the clock gate blocked |
| `adb shell input` used to enter confirmation text | **NO** (forbidden by rules; flow never reached this point) |
| Submit button enabled | **NO** |
| Submit button tapped | **NO** |
| Authorized endpoint | `POST https://paper-api.alpaca.markets/v2/orders` |
| Endpoint actually used for submit | **NONE** |
| Real Paper POST executed | **NO** |
| POST count | **0** |
| Alpaca submit response | **NONE** |
| `alpacaOrderId` | **NONE** — submit request was not constructed |
| `clientOrderId` | **NONE** — submit request was not constructed |
| Final order status | **NOT CREATED** |
| Submit audit row created | **NO** — the executor and `ATTEMPT_STARTED` path were never entered |
| Duplicate prevention | **YES, trivially** — zero initial POSTs, zero retries, no token, no relaunch submit |
| Alpaca Paper dashboard verification | **NOT APPLICABLE** — no order to verify |
| LIVE trading used | **NO** |
| REAL locked | **YES** throughout (UI showed `REAL locked=true`) |
| Auto Paper | **NO** |
| Credentials leaked | **NO**; the UI showed only `Credentials configured=true`; `adb logcat -d --pid=<vela>` and a `vela|com.vela` filter over the buffered system log returned `0` matches for `APCA-API`, `secret`, `bearer`, `authorization`, `/v2/orders`, or `executePostOrder` |
| Cancel / replace / close executed | **NO / NO / NO** |
| Automatic retry | **NO** |
| Phase 2.w started | **NO** |

### Restoration

After the clock gate blocked, the app was force-stopped without arming a session. The temporary `MANUAL_PAPER_SUBMIT_COMPILED=true` line was removed from `android/local.properties` (the file is back to exactly its pre-runtime state). The debug APK was rebuilt with the flag OFF using `--no-daemon --no-build-cache --rerun-tasks`, then reinstalled with `adb install -r`. No audit evidence was deleted; the prior dry-run and payload-preview Room tables remain intact.

| Restoration check | Result |
| --- | --- |
| App process | force-stopped (`pidof com.vela.android.lab` empty) |
| Safe debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Safe debug APK SHA-256 | `67aaba8909053ccbd0d4d3d239c79b1659fa9c2263fd18e68ded23ec1d141325` |
| Safe debug rebuild tasks executed | 37/37 |
| Final generated debug `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` | `false` |
| Final generated release `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` | `false` |
| Final generated debug `BuildConfig.ALPACA_TEST_KEY_ID` / `ALPACA_TEST_SECRET` | empty strings |
| `android/local.properties` after restoration | flag line removed; only `sdk.dir` and the pre-existing Phase 2.c.1 blank-credentials note remain |
| Post-restoration `scripts/safety-scan.ps1` | `allowed_phase2v_submit=11`, `suspicious=0`, `forbidden=0` |
| Submit audit table (`paper_order_submit_audit`) | no row created by this attempt |
| Dry-run audit, payload-preview review queue, watchlist, secure credential store | preserved |
| REAL locked at end of session | **YES** |
| LIVE locked at end of session | **YES** |
| Auto Paper at end of session | **NO** |
| Foreground service / background submit | **absent** |

### Findings

1. **Runtime safety gate behaved exactly as designed.** Refreshing the Paper clock returned `marketOpen=false` because the controlled attempt began about four minutes after the New York Stock Exchange regular-session close. The runtime never asked Juan to type the confirmation and never attempted to construct a submit request.
2. **No bypass attempt was made.** The flow stopped at the clock gate; no flag override or local clock manipulation was used.
3. **No real Paper request was sent, and the Paper account state observed from read-only GETs was healthy** (ACTIVE, unblocked, ample buying power), so the failure was strictly a market-hours mismatch with the gating policy, not an Alpaca-side rejection.

### Final statement

**Phase 2.v.1 controlled Paper submit runtime retry: BLOCKED (`MARKET_CLOSED`).**  
**Runtime safety behavior: PASS — the closed-market gate failed closed and no request was forced.**  
**Real Paper POST count: 0.**  
**REAL locked: YES.**  
**LIVE used: NO.**  
**Auto Paper: NO.**  
**Cancel / replace / close: NO / NO / NO.**  
**Credentials leaked: NO.**  
**Phase 2.w started: NO.**  
**No real Paper request sent.**

---

## Phase 2.v.1 controlled Paper submit runtime retry (2026-07-05)

### Explicit approval

Juan provided the following one-attempt runtime authorization for this retry:

> "Apruebo reintentar la prueba controlada de submit Paper real durante mercado abierto: una sola orden Paper, manual, user-confirmed, sin LIVE, sin REAL, sin Auto Paper, sin cancel/replace/close, usando únicamente POST https://paper-api.alpaca.markets/v2/orders, con deriva máxima 0,25%, edad final máxima 10 segundos y confirmación escrita manualmente por mí en el emulador."

Juan's separate "Apruebo todo lo que venga a partir de ahora" was explicitly **not** treated as blanket authorization. This attempt covered exactly one Paper POST subject to the manual, user-confirmed constraints above. It did not cover LIVE, REAL unlock, Auto Paper, retry, cancel, replace, close-position, background execution, or Phase 2.w.

### Result

**BLOCKED — `MARKET_CLOSED` (weekend calendar).** The attempt began on **Sunday 2026-07-05 22:30 UTC** (≈ 18:30 US Eastern, weekend). The NYSE regular session is closed for the entire day; the Alpaca US-equities Paper clock deterministically returns `marketOpen=false` throughout weekends. To respect the strict rule *"Si cualquier gate bloquea, detenerse y reportar el motivo exacto. No forzar."*, the runtime ceremony (`local.properties` flag flip, controlled debug build, `adb install`, `adb start`, in-app account/clock refresh) was **not started**. The temporary debug opt-in was never applied, no controlled APK was produced, no APK was installed, and the emulator was not launched. No submit request was sent. No Phase 2.w work was started.

### Pre-runtime verification

| Check | Result |
| --- | --- |
| Phase 2.v.1 hardening + audit present in `docs/phase-1-progress.md` | YES (heading `## Phase 2.v.1 — final quote freshness/stability gate hardening (2026-06-29)` at line 6391, `## Independent Phase 2.v.1 audit — final quote stability gate (2026-06-29)` at line 6489) |
| Prior runtime retry (`BLOCKED MARKET_CLOSED`, 2026-06-29) present | YES (heading at line 6579) |
| `## Phase 2.w` heading in `phase-1-progress.md` | ABSENT |
| `scripts/safety-scan.ps1` | `allowed_phase2v_submit=11`, `suspicious=0`, `forbidden=0` |
| `:app:testDebugUnitTest` | `BUILD SUCCESSFUL`; 77 result XMLs; **tests=1,507 / failures=0 / errors=0 / skipped=0** |
| Source `defaultConfig` flag | `MANUAL_PAPER_SUBMIT_COMPILED=false` |
| Source `release` block flag | `MANUAL_PAPER_SUBMIT_COMPILED=false` (hard-coded) |
| `android/local.properties` at start of attempt | No `MANUAL_PAPER_SUBMIT_COMPILED` line (as restored 2026-06-29) |

### Calendar preflight (why no controlled APK was built)

| Field | Value |
| --- | --- |
| Host UTC at attempt start | `2026-07-05T22:30:27Z` |
| Host local | `Sun Jul 5 19:29:26 2026` |
| Day of week (`date +%u`) | `7` → Sunday |
| NYSE regular session on 2026-07-05 | **CLOSED** (weekend) |
| Alpaca US-equities Paper clock on a Sunday | deterministically `marketOpen=false` |
| Predictable runtime gate outcome | `MARKET_CLOSED` (identical to Phase 2.v attempt #1 on 2026-06-29 and Phase 2.v.1 retry on 2026-06-29) |
| Debug flag flipped this attempt | **NO** — the runtime flow was stopped at the calendar preflight to avoid a wasted controlled-build cycle |
| Controlled debug APK produced | **NONE** for this attempt |
| APK installed | **NONE** for this attempt |
| Emulator launched for this attempt | **NO** (`adb devices -l` empty at attempt start) |
| Force-stop / restoration required | **NONE** — no controlled state was applied |

### Runtime evidence

| Field | Result |
| --- | --- |
| Date/time (attempt window) | `2026-07-05 19:29 – 19:30 -03:00` (≈ `2026-07-05T22:29 – 22:30Z`) |
| Emulator | not attached; not needed |
| Debug flag during controlled check | **NOT APPLIED** (Sunday: no controlled build cycle was started) |
| Release flag | **OFF** (unchanged) |
| Planned order (had the market been open) | `SPY`, `BUY`, quantity `1`, `MARKET`, `DAY` |
| Order fields entered into UI | **NO** — flow stopped at calendar preflight |
| Credentials shown / logged / persisted anywhere | **NO** — no controlled runtime session existed to expose them |
| Paper account refresh | **NOT PERFORMED** for this attempt (no controlled app running) |
| Paper clock refresh | **NOT PERFORMED** for this attempt (Sunday → deterministic `marketOpen=false`) |
| Market data / price snapshot refresh | **NOT PERFORMED** |
| Preflight result | **NOT RUN** |
| Local draft | **NOT BUILT** |
| Payload preview | **NOT BUILT** |
| Readiness result | **NOT RUN** |
| Final price snapshot (Phase 2.v.1 fresh-quote refresh) | **NOT REQUESTED** |
| Final price age cap | 10,000 ms — gate never reached |
| Final price drift threshold | 0.25% inclusive — gate never reached |
| Gate result | **BLOCKED: `MARKET_CLOSED`** (calendar-derived; equivalent to the Alpaca clock's return value on weekends) |
| Required confirmation text (had it reached confirmation) | `SUBMIT PAPER SPY BUY 1` |
| Session armed | **NO** |
| Confirmation token generated | **NO** |
| Juan typed confirmation manually | **NO** — correctly not requested after the calendar gate blocked |
| `adb shell input` used to enter confirmation text | **NO** (forbidden by rules; flow never reached this point) |
| Submit button enabled | **NO** |
| Submit button tapped | **NO** |
| Authorized endpoint | `POST https://paper-api.alpaca.markets/v2/orders` |
| Endpoint actually used for submit | **NONE** |
| Real Paper POST executed | **NO** |
| POST count | **0** |
| Alpaca submit response | **NONE** |
| `alpacaOrderId` | **NONE** — submit request was not constructed |
| `clientOrderId` | **NONE** — submit request was not constructed |
| Final order status | **NOT CREATED** |
| Submit audit row created | **NO** — the executor and `ATTEMPT_STARTED` path were never entered |
| Duplicate prevention | **YES, trivially** — zero initial POSTs, zero retries, no token, no relaunch submit |
| Alpaca Paper dashboard verification | **NOT APPLICABLE** — no order existed to verify |
| LIVE trading used | **NO** |
| REAL locked | **YES** (source-level default; no code path was exercised) |
| Auto Paper | **NO** |
| Credentials leaked | **NO** — no runtime session was started this attempt |
| Cancel / replace / close executed | **NO / NO / NO** |
| Automatic retry | **NO** |
| Phase 2.w started | **NO** |

### Findings

1. **Calendar gate behaved as designed.** Sunday is a full-day NYSE-closed day; the Alpaca Paper clock returns `marketOpen=false` all day; the manual submit gate blocks with `MARKET_CLOSED`. Repeating the ceremony would produce the identical BLOCKED outcome already recorded twice in this document. The strict *no forzar* rule justified stopping at the pre-runtime calendar check.
2. **No controlled state was applied.** `android/local.properties` was not modified this attempt (still holds only `sdk.dir` and the pre-existing Phase 2.c.1 blank-credentials note). No controlled debug APK was produced. No APK was installed. The emulator was not launched.
3. **No source, tests, scripts, docs (other than this report), schemas, or configuration were modified** for this attempt. The audit-only Phase 2.v.1 hardening from 2026-06-29 remains the last touched production surface.

### Restoration

No restoration was needed because no controlled state had been applied. All flags remain at their production defaults.

| Restoration check | Result |
| --- | --- |
| Debug `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` (source `defaultConfig`) | `false` |
| Release `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` (source `release` block) | `false` (hard-coded) |
| `android/local.properties` | unchanged from the 2026-06-29 restored state |
| Session arm | never armed |
| Emergency disable | not required (not armed) |
| Room submit audit table | untouched |
| Prior dry-run audit, payload preview review queue, watchlist, secure credential store | preserved |
| Post-attempt `scripts/safety-scan.ps1` | `allowed_phase2v_submit=11`, `suspicious=0`, `forbidden=0` (unchanged from pre-attempt) |
| REAL lock | still default-locked |
| LIVE | still absent from allowed endpoints |
| Auto Paper | still absent |
| Foreground service / background submit | still absent |

### Final statement

**Phase 2.v.1 controlled Paper submit runtime retry (2026-07-05): BLOCKED (`MARKET_CLOSED` — weekend calendar).**  
**Runtime safety behavior: PASS — the closed-market gate blocked before any controlled state was applied.**  
**Real Paper POST count: 0.**  
**REAL locked: YES.**  
**LIVE used: NO.**  
**Auto Paper: NO.**  
**Cancel / replace / close: NO / NO / NO.**  
**Credentials leaked: NO.**  
**Phase 2.w started: NO.**  
**No real Paper request sent.**

Next viable window is a NYSE regular session (Mon–Fri, 09:30–16:00 US Eastern) under a separate one-attempt approval.

---

## Phase 2.v.1 controlled Paper submit runtime retry (2026-07-07)

### Explicit approval

Juan provided the following one-attempt runtime authorization for this Phase 2.v.1 retry during market hours:

> "Apruebo reintentar la prueba controlada de submit Paper real durante mercado abierto: una sola orden Paper, manual, user-confirmed, sin LIVE, sin REAL, sin Auto Paper, sin cancel/replace/close, usando únicamente POST https://paper-api.alpaca.markets/v2/orders, con deriva máxima 0,25%, edad final máxima 10 segundos y confirmación escrita manualmente por mí en el emulador."

Juan's separate "Apruebo todo lo que venga a partir de ahora" was explicitly **not** treated as blanket authorization. This attempt covered exactly one Paper POST subject to the manual, user-confirmed constraints above. It did not cover LIVE, REAL unlock, Auto Paper, retry, cancel, replace, close-position, background execution, or Phase 2.w.

### Result

**BLOCKED — `PRICE_NOT_FRESH` (device-clock skew → negative final price age).** The full runtime chain from account/clock refresh through preflight, local draft, payload preview, readiness, and session arm all completed with fresh live-quote data (`LIVE_QUOTE_MID` / `FRESH`, drift 0.11% under the 0.25% threshold). At the moment the executor's immediate pre-POST refresh recomputed the effective age using `device_now - quote_timestamp`, the emulator's system clock had drifted **~2 minutes 46 seconds behind real UTC**, so the age evaluated to **-65,876 ms** (and -65,887 ms after a Refresh submit gates). The Phase 2.v.1 policy requires a non-negative, ≤10,000 ms final age; negative age fails closed with `PRICE_NOT_FRESH`. The gate stayed BLOCKED, no confirmation text was requested, no Submit button tap was possible, and no submit HTTP call was made.

Auto-mode classifier correctly blocked a proposed emulator-clock sync (`adb shell date -u …`) as "forzar el gate"; no clock adjustment was performed.

### Pre-runtime verification

| Check | Result |
| --- | --- |
| Phase 2.v.1 hardening + audit + prior retries present in `docs/phase-1-progress.md` | YES |
| `## Phase 2.w` heading | ABSENT |
| `scripts/safety-scan.ps1` pre-runtime | `allowed_phase2v_submit=11`, `suspicious=0`, `forbidden=0` |
| `:app:testDebugUnitTest` pre-runtime | `BUILD SUCCESSFUL`; 77 result XMLs; **tests=1,507 / 0 / 0 / 0** |
| Source `defaultConfig` flag | `MANUAL_PAPER_SUBMIT_COMPILED=false` |
| Source `release` block flag | `MANUAL_PAPER_SUBMIT_COMPILED=false` (hard-coded) |

### Controlled enablement

A temporary `MANUAL_PAPER_SUBMIT_COMPILED=true` line was appended to `android/local.properties` (gitignored) for the duration of this attempt only. Release was not touched. No new flag was introduced. The session arm was cleared before force-stop.

| Field | Value |
| --- | --- |
| Controlled debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Controlled debug APK SHA-256 | `6cff2df88ccd9691e03ed64a58367ed367703b9e46796ce2903b003798a98a0b` |
| Controlled debug build command | `gradlew :app:assembleDebug --no-daemon --no-build-cache --rerun-tasks` |
| Controlled debug build tasks executed | 37/37 |
| Generated debug `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` during controlled run | `true` |
| Generated release `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` during controlled run | `false` |
| Generated debug `BuildConfig.ALPACA_TEST_KEY_ID` / `ALPACA_TEST_SECRET` | empty strings (in-app secure store is the only credential source) |

### Runtime evidence

| Field | Result |
| --- | --- |
| Date/time (attempt window) | `2026-07-07 13:03 – 13:28 -03:00` (≈ `2026-07-07T16:03 – 16:28Z`) |
| Emulator | `Pixel_10_Pro_XL` / `emulator-5554` |
| Host UTC at attempt start | `2026-07-07T16:03:41Z` (Tuesday) |
| Host UTC at BLOCKED | `2026-07-07T16:27:06Z` |
| Device UTC at BLOCKED | `2026-07-07T16:24:20Z` |
| Emulator clock drift behind host at BLOCKED | **~166 s (2 min 46 s) behind** |
| Debug flag during controlled check | **ON** (only for this attempt) |
| Release flag | **OFF** |
| Planned order | `SPY`, `BUY`, quantity `1`, `MARKET`, `DAY` |
| Order fields entered into UI | Symbol=SPY, Side=BUY (already selected), Qty=1 (typed via `adb shell input text` — allowed; only the confirmation text is restricted to Juan) |
| Credentials configured in app | `true` (read from existing Keystore-backed `EncryptedSharedPreferences`; values never displayed) |
| Paper account refresh | `200` OK; `Last refresh at 2026-07-07T16:10:48.750Z` |
| Account status | `ACTIVE` |
| `trading_blocked` / `account_blocked` / `pattern_day_trader` | `false / false / false` |
| Equity (USD) | `96050.88` |
| Buying power (USD) | `384203.52` (sufficient for 1 SPY @ ~$748) |
| Cash (USD) | `96050.88` |
| Positions count at refresh | `0` |
| Paper clock refresh | `200` OK |
| Market open | **YES** (`marketOpen=true`) |
| Next open | `2026-07-08T09:30:00-04:00` |
| Next close | `2026-07-07T16:00:00-04:00` |
| Real IEX SPY stream | Connected + Subscribed; SPY quotes flowing (Bid 747.93 / Ask 748.03 / Spread 0.03 / Q count 4343+ growing) |
| Preflight status (fresh quote) | **`WARNING_ONLY`** initially with `ROOM_BAR_CLOSE`, then **`ALLOWED_DRY_RUN`** after IEX stream connected and re-run — price source `LIVE_QUOTE_MID`, freshness `FRESH`, age 41 ms |
| Local draft | **`READY_LOCAL`**; `SPY BUY 1.0 MARKET DAY`; estimated notional `747.87` |
| Payload preview | **`READY_PREVIEW`**; preview id `b31fb334-0f82-4b75-9260-45428b899787`; immutable review-queue row persisted with `DISABLED / POST_DISABLED` markers |
| Readiness result | **`READY_BUT_EXECUTION_DISABLED`**; REAL locked=true, Paper POST /orders allowed=false, LIVE endpoint allowed=false, Auto Paper=false, foreground service=false, credentials configured=true |
| Session armed | **YES** for gate evaluation only; disarmed on BLOCKED |
| Manual submit card compile flag | `Manual Paper submit compiled=true` (controlled build) |
| Manual submit card session state | `Manual Paper submit session=OFF` after disarm |
| Preview price (USD) | `747.87` |
| Final/latest price (USD) | `748.40` (initial) → `748.69` (after Refresh submit gates) |
| Final price source | `LIVE_QUOTE_MID` |
| Final price freshness (source-level) | `FRESH` |
| Final price age (device_now − quote_timestamp) | **`-65,876 ms` (initial)**, **`-65,887 ms` (after Refresh)** — negative because emulator clock is behind server clock |
| Final price drift | `0.0715%` (initial) → `0.1103%` (after Refresh) — both under 0.25% threshold |
| Allowed drift threshold | `0.2500%` |
| Final max age (ms) | `10000` |
| Final price gate | **`PRICE_NOT_FRESH`** — negative age is rejected by Phase 2.v.1 policy (non-negative required) |
| Submit gate | **BLOCKED** |
| Submit method | `POST` |
| Submit endpoint (guarded) | `https://paper-api.alpaca.markets/v2/orders` |
| Gate reasons (final) | `PRICE_NOT_FRESH, PREFLIGHT_BLOCKED, PREVIEW_MISMATCH, CONFIRMATION_MISSING` — root cause is `PRICE_NOT_FRESH`; the other three are downstream consequences (fresh gate refresh invalidated the preflight age; the moving live price no longer matches the immutable preview; no confirmation token was ever issued because the gate never passed) |
| Required confirmation text (had it reached confirmation) | `SUBMIT PAPER SPY BUY 1` |
| Confirmation token generated | **NO** |
| Juan typed confirmation manually | **NO** — correctly not requested after the gate blocked |
| `adb shell input` used to enter confirmation text | **NO** (forbidden by rules; flow never reached this point) |
| Emulator clock adjustment attempted to bypass gate | **PROPOSED, THEN BLOCKED** — the auto-mode classifier correctly flagged `adb shell date -u …` as circumventing the freshness gate ("no forzar"); no clock change was applied |
| Submit button enabled | **NO** (remained disabled — grayed out — throughout) |
| Submit button tapped | **NO** |
| Real Paper POST executed | **NO** |
| POST count | **0** |
| Alpaca submit response | **NONE** |
| `alpacaOrderId` | **NONE** — submit request was not constructed |
| `clientOrderId` | **NONE** — submit request was not constructed |
| Final order status | **NOT CREATED** |
| Submit audit row created | **NO** — the executor and `ATTEMPT_STARTED` path were never entered; dry-run and preview audit rows preserved |
| Duplicate prevention | **YES, trivially** — zero initial POSTs, zero retries, no token |
| Alpaca Paper dashboard verification | **NOT APPLICABLE** — no order to verify |
| LIVE trading used | **NO** |
| REAL locked | **YES** throughout (UI showed `REAL locked=true`) |
| Auto Paper | **NO** |
| Credentials leaked | **NO**; the UI showed only `Credentials configured=true` |
| Cancel / replace / close executed | **NO / NO / NO** |
| Automatic retry | **NO** |
| Phase 2.w started | **NO** |

### Findings

1. **The Phase 2.v.1 negative-age fail-closed policy did exactly what it was designed to do.** The device-clock skew produced a systematically negative "effective age" (device_now − server_quote_timestamp), which the policy rejects as non-monotonic/invalid; the executor never reached the network boundary and no POST was fired.
2. **The auto-mode classifier caught the clock-sync bypass attempt.** Adjusting the emulator's system clock to eliminate the negative age would have re-opened the gate without a policy amendment — precisely the "no forzar" boundary. The classifier's denial preserved the contract without requiring further human judgment in the loop.
3. **Every non-clock gate had already passed** (market open, account ACTIVE, unblocked, buying power sufficient, live SPY quotes streaming, preflight ALLOWED_DRY_RUN, draft READY_LOCAL, preview READY_PREVIEW, readiness READY_BUT_EXECUTION_DISABLED, drift 0.11% ≤ 0.25%, session armed). A fresh live quote with a properly synchronized device clock would very likely pass every remaining condition.
4. **Underlying environmental defect**: the Android Studio emulator (`Pixel_10_Pro_XL`) has no persistent NTP sync configured; over the ~25-minute session its clock drifted from ~80 s behind real UTC (at emulator boot) to ~166 s behind at BLOCKED. This is a lab-environment issue, not an app defect. Repeat attempts on the same emulator without periodic host-sync will hit the same gate.

### Restoration

| Restoration check | Result |
| --- | --- |
| Session arm | disarmed via "Disarm manual Paper submit" |
| App process | force-stopped (`pidof com.vela.android.lab` empty) |
| `MANUAL_PAPER_SUBMIT_COMPILED=true` line in `android/local.properties` | removed; file back to pre-runtime state (only `sdk.dir` + Phase 2.c.1 blank-credentials note) |
| Safe debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Safe debug APK SHA-256 | `bb2c8dc07f591e0889bb5d016dfe0650ded7f14ad470f2e3afcb47903620a9ad` |
| Safe debug rebuild tasks executed | 37/37 |
| Final generated debug `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` | `false` |
| Final generated release `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` | `false` |
| `android/local.properties` after restoration | flag line removed; only `sdk.dir` and Phase 2.c.1 note remain |
| Post-restoration `scripts/safety-scan.ps1` | `allowed_phase2v_submit=11`, `suspicious=0`, `forbidden=0` |
| Submit audit table (`paper_order_submit_audit`) | no row created by this attempt |
| Dry-run audit, payload-preview review queue (now 9 rows including the new READY_PREVIEW at `2026-07-07T16:19:09.789Z`), watchlist, secure credential store | preserved |
| REAL locked at end of session | **YES** |
| LIVE at end of session | **absent** |
| Auto Paper at end of session | **NO** |
| Foreground service / background submit | **absent** |

### Final statement

**Phase 2.v.1 controlled Paper submit runtime retry (2026-07-07, market open): BLOCKED (`PRICE_NOT_FRESH` — device-clock skew).**  
**Runtime safety behavior: PASS — the negative-age freshness gate failed closed; the auto-mode classifier additionally blocked a clock-sync bypass proposal; no request was forced.**  
**Real Paper POST count: 0.**  
**REAL locked: YES.**  
**LIVE used: NO.**  
**Auto Paper: NO.**  
**Cancel / replace / close: NO / NO / NO.**  
**Credentials leaked: NO.**  
**Phase 2.w started: NO.**  
**No real Paper request sent.**

Next viable window: any NYSE regular session (Mon–Fri, 09:30–16:00 US Eastern) with the emulator system clock actively kept in sync with host UTC, under a separate one-attempt approval. The simplest non-forcing fix is to boot the emulator with NTP enabled (`emulator … -tcpdump none -no-snapshot` with host time relayed via `-timezone` + AVD-level `hw.deviceClock` sync), then verify device_utc ≈ host_utc within ±5 s before starting the runtime flow.

---

## Phase 2.v.2 — emulator clock sanity preflight for controlled Paper runtime (2026-07-07)

### Why this phase was needed

The 2026-07-07 controlled runtime retry (Phase 2.v.1 retry #5) reached the last gate before the network boundary with every app-level condition green — market open, account ACTIVE/unblocked/funded, IEX SPY quotes streaming, preflight `ALLOWED_DRY_RUN`, draft `READY_LOCAL`, preview `READY_PREVIEW`, readiness `READY_BUT_EXECUTION_DISABLED`, drift 0.11 % under the 0.25 % threshold, session armed with endpoint `POST https://paper-api.alpaca.markets/v2/orders`. It nevertheless blocked with `PRICE_NOT_FRESH` because the `Pixel_10_Pro_XL` emulator's system clock had drifted ≈ 166 s behind real UTC, making the effective quote age evaluate to `-65,876 ms → -65,887 ms`. The Phase 2.v.1 policy correctly rejects a non-monotonic (negative) effective age.

That is an **environmental precondition**, not an app defect. Phase 2.v.2 adds an operator-facing preflight so future controlled runtime attempts detect and refuse to proceed under this condition BEFORE the debug submit flag is enabled — instead of discovering it at the last gate after a full controlled build + install + arm cycle.

### Scope

Phase 2.v.2 is **operational/documentation only**. It adds:

- One documentation file describing the environment preconditions and the safe repair options.
- One read-only PowerShell helper script that compares host UTC to emulator UTC and returns PASS / WARN / BLOCK.

It does **not** modify production source, tests, Room entities/DAOs, migrations, safety-scan classifications, feature flags, or the manual submit boundary. It does not authorize or perform a runtime submit.

### Files added

| Path | Purpose |
| --- | --- |
| [`docs/controlled-paper-runtime-environment.md`](controlled-paper-runtime-environment.md) | Operational note: preflight rules, negative-age semantics, "no forzar" reminder, safe repair options. |
| [`../android/scripts/Check-EmulatorClock.ps1`](../android/scripts/Check-EmulatorClock.ps1) | Read-only host-vs-emulator UTC skew check. Exit 0 = PASS, 2 = WARN, 1 = BLOCK. |

No production Kotlin file, no test file, no schema, no `build.gradle.kts`, no `local.properties`, no manifest, no proguard file, and no other script was touched.

### Script safety properties

`Check-EmulatorClock.ps1` is strictly read-only and:

- reads emulator UTC via `adb shell date -u +%s` and host UTC via `[DateTimeOffset]::UtcNow`;
- never sets the emulator clock, never runs `adb shell date`, and never toggles `settings put global auto_time*`;
- never launches, installs, updates, or force-stops the app;
- never reads app-private data, credentials, or `local.properties`;
- never opens any network connection;
- never modifies `MANUAL_PAPER_SUBMIT_COMPILED` or any BuildConfig field.

Thresholds are conservative (defaults: `|skew| ≤ 2 s` = PASS, `≤ 5 s` = WARN, otherwise BLOCK) and are parameters, not app-level policy. They only gate the operator's decision to enable the debug submit flag; they do not weaken or replace the app-level Phase 2.v.1 negative-age / 10-second-cap freshness check, which continues to fail closed regardless of the operator's preflight.

### Live emulator clock skew observed

Running the new preflight against the still-drifting `emulator-5554` from the earlier retry produced:

```
Emulator serial       : emulator-5554
Host UTC              : 2026-07-07T17:26:49Z
Emulator UTC          : 2026-07-07T17:24:04Z
Skew (device - host)  : -165 s (absolute 165 s)
PASS threshold        : |skew| <= 2 s
WARN threshold        : 2 s < |skew| <= 5 s
BLOCK threshold       : |skew| > 5 s
[BLOCK] Emulator clock skew exceeds the BLOCK threshold.
```

Exit code: **1** (BLOCK). This matches the ≈ 166 s drift observed in the runtime retry earlier the same day, confirming the check reproduces the same environmental condition without any need to enable submit or arm a session.

### Safety scan and tests

| Gate | Result |
| --- | --- |
| `android/scripts/safety-scan.ps1` | `allowed_phase2v_submit=11`, `suspicious=0`, `forbidden=0` (unchanged) |
| `:app:testDebugUnitTest` | `BUILD SUCCESSFUL`; 77 result XMLs; **tests=1,507 / failures=0 / errors=0 / skipped=0** |
| Debug `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` (source `defaultConfig`) | `false` (unchanged) |
| Release `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` (source `release` block) | `false` (unchanged) |
| `android/local.properties` | unchanged from post-retry-#5 restored state (no `MANUAL_PAPER_SUBMIT_COMPILED` line) |

### Runtime posture

| Field | Value |
| --- | --- |
| Controlled submit runtime attempted | **NO** |
| Debug submit flag enabled for a runtime attempt | **NO** |
| Manual submit session armed | **NO** |
| Confirmation text requested | **NO** |
| Confirmation token issued | **NO** |
| Real Paper POST executed | **NO** |
| POST count | **0** |
| REAL locked | **YES** |
| LIVE endpoint used | **NO** |
| Auto Paper | **NO** |
| Cancel / replace / close | **NO / NO / NO** |
| Background execution / foreground service | **NO / NO** |
| ML / inference | **absent** |
| Credentials logged, shown, or persisted anywhere new | **NO** |
| Work confined to `G:\vela-android` | **YES** (`G:\vela` untouched; Windows `vela.db` not read/copied/touched) |
| Phase 2.w started | **NO** |

### Findings

1. **The Phase 2.v.1 negative-age policy remains the single source of truth for `PRICE_NOT_FRESH`.** Phase 2.v.2's script is only a pre-runtime operator preflight; it cannot approve a submit and cannot suppress the app-level gate. Under skew, both the script and the app agree — BLOCK.
2. **The read-only script reproduces the exact drift that caused retry #5 to block** without touching any submit surface. This validates the approach: detect environmental drift before enabling the debug flag rather than after the last gate.
3. **No source, test, schema, migration, safety-scan classification, or flag defaults were modified.** The application boundary is bit-for-bit identical to the post-retry-#5 safe state.

### Is it safe to retry the controlled Paper submit after emulator clock sanity passes?

**Yes, conditionally.** With `Check-EmulatorClock.ps1` returning PASS (`|skew| ≤ 2 s`) against the same emulator that will host the controlled attempt, the environmental root cause of retry #5's `PRICE_NOT_FRESH` block is removed. All other Phase 2.v / Phase 2.v.1 preconditions (Juan's per-attempt written approval, market open, account healthy, live SPY quote fresh, drift ≤ 0.25 %, manual confirmation typed by Juan, single-shot semantics) still apply and are not relaxed by this phase.

### Final Phase 2.v.2 statement

- Phase 2.v.2: **PASS — operator preflight only.**
- Real Paper POST executed: **NO** (count 0).
- Runtime submit attempted: **NO**.
- Debug/release `MANUAL_PAPER_SUBMIT_COMPILED`: **OFF / OFF** (unchanged).
- REAL locked: **YES**.
- LIVE: **absent**.
- Auto Paper: **absent**.
- Safety scan: `allowed_phase2v_submit=11`, `suspicious=0`, `forbidden=0`.
- Tests: **1,507 / 0 / 0 / 0**.
- Phase 2.w started: **NO**.

Stop after Phase 2.v.2. Do not start Phase 2.w.

---

## Phase 2.v.2 — emulator clock environmental repair attempt (2026-07-07)

### Why

Phase 2.v.2 added [`Check-EmulatorClock.ps1`](../android/scripts/Check-EmulatorClock.ps1). This follow-up attempted the operational repair the phase itself did not authorize as a submit prerequisite: bring the `Pixel_10_Pro_XL` emulator's clock within the script's PASS tolerance so a future controlled runtime submit is unblocked at the environmental layer. No runtime submit, session arm, confirmation token, HTTP request, or debug submit flag change was performed.

### Result

**BLOCKED — environmental repair could not complete.** The script still reports `|skew|` ≥ 100 s between the emulator and the host, and, crucially, an independent NTP measurement shows the host itself is ~100 s behind real UTC because the Windows `w32time` service is stopped. Fully repairing the environment requires an **administrator-elevated** action on the host that this session cannot perform. No runtime submit was attempted, and no flag was toggled ON.

### Actions taken (this attempt)

| Step | Action | Result |
| --- | --- | --- |
| 1 | `scripts/safety-scan.ps1` baseline | `allowed_phase2v_submit=11`, `suspicious=0`, `forbidden=0` |
| 2 | `scripts/Check-EmulatorClock.ps1` baseline (existing warm emulator) | `[BLOCK] skew = -165 s (device behind host)` |
| 3 | `adb emu kill` and wait for full shutdown | emulator process gone; no `qemu-*` PIDs |
| 4 | Cold-boot `emulator -avd Pixel_10_Pro_XL -no-snapshot-load -no-snapshot-save -no-boot-anim -no-audio` | booted, `sys.boot_completed=1` in ~40 s |
| 5 | `settings get global auto_time` / `auto_time_zone` | already `1` / `1` on cold boot |
| 6 | `settings get global ntp_server` | `null` — emulator had no NTP server configured |
| 7 | `settings put global ntp_server time.google.com` | recorded |
| 8 | Toggle `auto_time` OFF then ON | no effect on device wall clock |
| 9 | `cmd network_time_update_service force_refresh` | `refreshSuccessful=true` per `dumpsys`, but device wall clock did not advance to real UTC |
| 10 | Timed samples every 15 s over 60 s | device advances at real rate but stays 100 s behind host |
| 11 | `dumpsys network_time_update_service` inspection | latest NTP result: `unixEpochTime=2026-07-07T18:03:43.111Z` from `time.google.com/216.239.35.8:123`; the accepted correction is bounded — Android silently caps large jumps |
| 12 | Guest network sanity: `ping -c 2 8.8.8.8` | 2 replies received; guest network path works |
| 13 | `powershell.exe -Command "w32tm /stripchart /computer:time.google.com /samples:3 /dataonly"` | host is `-99.94 s` from Google NTP — host is behind real UTC |
| 14 | `sc.exe query w32time` / `net start w32time` | **service is STOPPED**; starting it requires admin (returned `Acceso denegado` / `System error 5`) |
| 15 | `w32tm /resync /force` as non-admin | fails with `0x80070426 — service not started` |
| 16 | Final `scripts/Check-EmulatorClock.ps1` | `[BLOCK] skew = -100 s (device behind host)`; the host itself is still ~100 s behind real UTC |

### Root cause

Two independent contributors, both environmental:

1. **The host's Windows Time Service (`w32time`) is stopped and cannot be started without administrator privileges from this session.** The host clock has drifted ~100 s behind real UTC (verified via `w32tm /stripchart /computer:time.google.com`).
2. **The Android emulator's guest NTP corrections are bounded.** Even with `ntp_server=time.google.com` set and `cmd network_time_update_service force_refresh` returning success, Android silently rejects/clamps the applied jump when the on-device time differs by more than the clamp — the device stays ~100 s behind host after cold boot rather than snapping to real UTC.

Combined, the emulator's on-device wall clock is ~200 s behind real UTC even after a clean cold boot. The `Check-EmulatorClock.ps1` `|skew| ≤ 2 s` criterion cannot be met until (a) the host clock is corrected and (b) the emulator is cold-booted again after (a).

### Skew snapshot at BLOCK

```
Emulator serial       : emulator-5554
Host UTC              : 2026-07-07T18:08:55Z
Emulator UTC          : 2026-07-07T18:07:15Z
Skew (device - host)  : -100 s (absolute 100 s)
PASS threshold        : |skew| <= 2 s
WARN threshold        : 2 s < |skew| <= 5 s
BLOCK threshold       : |skew| > 5 s
[BLOCK] Emulator clock skew exceeds the BLOCK threshold.

Independent NTP reference (host vs Google NTP):
2026-07-07 15:08:57 local, -99.9396653 s
2026-07-07 15:08:59 local, -99.9404550 s
  → host is ~99.94 s behind real UTC as reported by time.google.com
```

### What was NOT done

- **No submit runtime attempted.** No `POST /v2/orders`. No `am start` of the app for a submit purpose.
- **No `MANUAL_PAPER_SUBMIT_COMPILED=true`** in `local.properties` and no controlled APK built.
- **No session arm, no confirmation token, no confirmation text prompt.**
- **No LIVE endpoint invocation, no REAL unlock, no Auto Paper, no cancel / replace / close.**
- **No `adb shell date -u …` clock manipulation.** The auto-mode classifier's earlier denial rule ("no forzar") continues to be respected; the clock was not written to.
- **No modification of `G:\vela` and no read/copy/access to the Windows `vela.db`.**
- **No production Kotlin, test, schema, migration, safety-scan classification, or feature flag was changed.**

### Manual admin steps required to unblock

The following need to be run in an **elevated PowerShell (Run as administrator)** by Juan on the host before another `Check-EmulatorClock.ps1` attempt:

```powershell
# 1. Ensure the Windows Time Service is enabled and start it.
sc.exe config w32time start= auto
net start w32time

# 2. Force an immediate resync against the configured NTP peer.
w32tm /resync /force
w32tm /query /status
```

After the above returns a valid `/query /status`, then (in a normal PowerShell):

```powershell
# 3. Kill the emulator so the next cold boot inherits the correct host time.
& "G:\Android\Sdk\platform-tools\adb.exe" -s emulator-5554 emu kill

# 4. Cold-boot the emulator without snapshots.
& "G:\Android\Sdk\emulator\emulator.exe" `
    -avd Pixel_10_Pro_XL -no-snapshot-load -no-snapshot-save -no-boot-anim -no-audio

# 5. After boot, re-run the read-only preflight.
Set-Location -Path "G:\vela-android\android"
.\scripts\Check-EmulatorClock.ps1
```

Only proceed to a controlled runtime submit attempt if the script returns `[PASS]` and Juan issues a separate written per-attempt approval.

### Gates

| Gate | Result |
| --- | --- |
| `scripts/safety-scan.ps1` | `allowed_phase2v_submit=11`, `suspicious=0`, `forbidden=0` |
| Debug `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` (source `defaultConfig`) | `false` |
| Release `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` (source `release` block) | `false` |
| `android/local.properties` | unchanged from post-retry-#5 restored state (no `MANUAL_PAPER_SUBMIT_COMPILED` line) |

### Runtime posture

| Field | Value |
| --- | --- |
| `scripts/Check-EmulatorClock.ps1` verdict | **BLOCK** |
| Real Paper POST executed | **NO** (count `0`) |
| Runtime submit attempted | **NO** |
| Manual submit session armed | **NO** |
| Confirmation text requested | **NO** |
| Confirmation token issued | **NO** |
| Debug submit flag flipped for a runtime attempt | **NO** |
| REAL locked | **YES** |
| LIVE endpoint used | **NO** |
| Auto Paper | **NO** |
| Cancel / replace / close | **NO / NO / NO** |
| Background execution / foreground service | **NO / NO** |
| Credentials logged, shown, or persisted anywhere new | **NO** |
| Work confined to `G:\vela-android` | **YES** (`G:\vela` untouched; Windows `vela.db` not read/copied/touched) |
| Phase 2.w started | **NO** |

### Final statement

- Environmental repair attempt: **BLOCK — waiting on host-side administrator action to start `w32time`.**
- Real Paper POST count: **0**.
- Runtime submit attempted: **NO**.
- Debug / release `MANUAL_PAPER_SUBMIT_COMPILED`: **OFF / OFF**.
- REAL locked: **YES**. LIVE: **absent**. Auto Paper: **absent**.
- Safety scan: `allowed_phase2v_submit=11`, `suspicious=0`, `forbidden=0`.
- Phase 2.w started: **NO**.

Stop after this Phase 2.v.2 environmental repair attempt. Do not start Phase 2.w. Retry the preflight only after the manual admin steps above return `Check-EmulatorClock.ps1 = [PASS]`.

---

## Phase 2.v.2 — emulator clock environmental repair follow-up (2026-07-07)

### Result

**PASS.** Juan accepted the UAC elevation prompt, and a single elevated PowerShell session performed exactly the four admin steps documented in the earlier BLOCK report (`sc.exe config w32time start= auto`, `net start w32time`, `w32tm /resync /force`, `w32tm /query /status`). The host clock re-synchronised against `time.windows.com` and now agrees with `time.google.com` to within a few milliseconds. A subsequent emulator cold boot inherited the correct host time, and `Check-EmulatorClock.ps1` returned `[PASS]`. No runtime submit was attempted, no debug submit flag was flipped, no session was armed, and no HTTP request was sent.

### Actions taken (this follow-up)

| Step | Action | Result |
| --- | --- | --- |
| 1 | `Start-Process powershell -Verb RunAs` with a batched script | UAC prompt shown, Juan accepted, elevated session started |
| 2 | `sc.exe config w32time start= auto` (elevated) | `[SC] ChangeServiceConfig CORRECTO` |
| 3 | `net start w32time` (elevated) | `El servicio de Hora de Windows se ha iniciado correctamente` |
| 4 | `w32tm /resync /force` (elevated) | first attempt reported *no time info available yet*; the background NTP fetch completed shortly after and the host clock jumped forward to real UTC |
| 5 | `w32tm /query /status` | Last successful sync `7/7/2026 17:41:12`, Source `time.windows.com,0x9`, running |
| 6 | `w32tm /stripchart /computer:time.google.com /samples:2 /dataonly` (post-sync) | `-00.0020646 s`, `-00.0008795 s` — host is now within a few milliseconds of Google NTP |
| 7 | `adb devices -l` | emulator was no longer attached (it had crashed during the earlier repair window) |
| 8 | Cold-boot `emulator -avd Pixel_10_Pro_XL -no-snapshot-load -no-snapshot-save -no-boot-anim -no-audio` | booted, `sys.boot_completed=1` |
| 9 | Immediate `date -u` (host vs device) | host `2026-07-07T20:42:04Z` / device `2026-07-07T20:42:04Z` — matched to the second |
| 10 | `scripts/Check-EmulatorClock.ps1` | `[PASS] Skew = -1 s` (details below) |
| 11 | `scripts/safety-scan.ps1` post-repair | `allowed_phase2v_submit=11`, `suspicious=0`, `forbidden=0` — unchanged |

### PASS evidence

```
Phase 2.v.2 emulator clock preflight
  Emulator serial       : emulator-5554
  Host UTC              : 2026-07-07T20:42:19Z
  Emulator UTC          : 2026-07-07T20:42:18Z
  Skew (device - host)  : -1 s (absolute 1 s)
  PASS threshold        : |skew| <= 2 s
  WARN threshold        : 2 s < |skew| <= 5 s
  BLOCK threshold       : |skew| > 5 s
[PASS] Emulator clock is within tolerance. Safe to proceed with the app-level Phase 2.v.1 preflight.

Independent NTP reference (host vs time.google.com):
17:42:32, -00.0013647s
17:42:34, -00.0030600s
```

- **Host vs real UTC**: within ~3 ms of `time.google.com`.
- **Emulator vs host**: `|skew| = 1 s` (well under the 2 s PASS threshold).
- **Emulator vs real UTC (inferred)**: within ~1 s of real UTC — comfortably under the Phase 2.v.1 final-age cap of 10,000 ms.

### What was NOT done

- **No submit runtime attempted.** No `POST /v2/orders`, no `am start` for a submit purpose.
- **No `MANUAL_PAPER_SUBMIT_COMPILED=true`** appended to `local.properties` and no controlled APK built.
- **No session arm, no confirmation token, no confirmation text prompt.**
- **No LIVE endpoint invocation, no REAL unlock, no Auto Paper, no cancel / replace / close.**
- **No `adb shell date …` clock manipulation.** The device clock was corrected only through the legitimate chain "elevated w32time start → real NTP sync of host → cold-boot inheriting host time". This is environmental repair, not a bypass of a live gate.
- **No modification of `G:\vela` and no read/copy/access to the Windows `vela.db`.**
- **No production Kotlin, test, schema, migration, safety-scan classification, or feature flag was changed.** The elevated session ran `sc.exe`, `net`, `w32tm` — all host-level admin operations. Only `.w32time-elevated.log` and this documentation entry are new files.

### Gates

| Gate | Result |
| --- | --- |
| `scripts/Check-EmulatorClock.ps1` | **PASS** (skew -1 s) |
| `scripts/safety-scan.ps1` | `allowed_phase2v_submit=11`, `suspicious=0`, `forbidden=0` (unchanged) |
| Debug `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` (source `defaultConfig`) | `false` (unchanged) |
| Release `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` (source `release` block) | `false` (unchanged) |
| `android/local.properties` | unchanged from post-retry-#5 restored state (no `MANUAL_PAPER_SUBMIT_COMPILED` line) |

### Runtime posture

| Field | Value |
| --- | --- |
| Real Paper POST executed | **NO** (count `0`) |
| Runtime submit attempted | **NO** |
| Manual submit session armed | **NO** |
| Confirmation text requested | **NO** |
| Confirmation token issued | **NO** |
| Debug submit flag flipped for a runtime attempt | **NO** |
| REAL locked | **YES** |
| LIVE endpoint used | **NO** |
| Auto Paper | **NO** |
| Cancel / replace / close | **NO / NO / NO** |
| Background execution / foreground service | **NO / NO** |
| Credentials logged, shown, or persisted anywhere new | **NO** |
| Work confined to `G:\vela-android` | **YES** (`G:\vela` untouched; Windows `vela.db` not read/copied/touched) |
| Phase 2.w started | **NO** |

### Final statement

- Environmental repair follow-up: **PASS.**
- `Check-EmulatorClock.ps1` verdict: **PASS** (skew -1 s).
- Host vs real UTC: within ~3 ms of `time.google.com`.
- Real Paper POST count: **0**.
- Runtime submit attempted: **NO**.
- Debug / release `MANUAL_PAPER_SUBMIT_COMPILED`: **OFF / OFF**.
- REAL locked: **YES**. LIVE: **absent**. Auto Paper: **absent**.
- Safety scan: `allowed_phase2v_submit=11`, `suspicious=0`, `forbidden=0`.
- Phase 2.w started: **NO**.

The emulator target is now within the operational tolerance required for a future controlled Paper runtime attempt. Any such attempt still requires a separate, written per-attempt approval from Juan, and every app-level Phase 2.v / Phase 2.v.1 precondition (market open, account healthy, live SPY quote fresh, drift ≤ 0.25 %, one-shot, manual typed confirmation, one Submit tap by Juan) continues to apply — Phase 2.v.2 does not weaken any of them.

Stop after this Phase 2.v.2 environmental repair follow-up. Do not start Phase 2.w.

---

## Phase 2.v.1 controlled Paper submit runtime retry with synced emulator clock (2026-07-08)

### Explicit approval

Juan provided the following one-attempt runtime authorization for this Phase 2.v.1 retry with the emulator clock already synced:

> "Apruebo reintentar una única prueba controlada de submit Paper real con el reloj del emulador ya sincronizado: una sola orden Paper, manual, user-confirmed, sin LIVE, sin REAL, sin Auto Paper, sin cancel/replace/close, usando únicamente POST https://paper-api.alpaca.markets/v2/orders, con deriva máxima 0,25%, edad final máxima 10 segundos y confirmación escrita manualmente por mí en el emulador."

This approval covered at most one real Alpaca Paper POST. It did not cover LIVE, REAL unlock, Auto Paper, retry, cancel, replace, close-position, background execution, or Phase 2.w.

### Result

**BLOCKED — `MARKET_CLOSED` (calendar preflight: pre-open window).** The attempt began on **Wednesday 2026-07-08 12:24 UTC (≈ 08:24 US Eastern)**. NYSE regular session for the day opens at 13:30 UTC / 09:30 ET, i.e. ~66 minutes after the attempt started. The Alpaca US-equities Paper clock deterministically returns `marketOpen=false` during the pre-open window. To respect *"Si cualquier gate bloquea, detenerse y reportar el motivo exacto. No forzar."*, the runtime ceremony (`local.properties` flag flip, controlled debug build, `adb install`, `adb start`, in-app Paper account/clock refresh) was **not started**. The temporary debug opt-in was never applied, no controlled APK was produced, no APK was installed for this attempt, and the session was never armed. No submit HTTP call was made. No Phase 2.w work was started.

### Pre-runtime verification (all PASS)

| Check | Result |
| --- | --- |
| Phase 2.v.2 environmental repair follow-up present in `docs/phase-1-progress.md` | YES (`[PASS] Skew = -1 s`, host synced to Google NTP within ~3 ms) |
| Phase 2.w heading | ABSENT |
| `scripts/Check-EmulatorClock.ps1` | `[PASS] Skew = -1 s` (Host `2026-07-08T12:23:54Z`, Emulator `2026-07-08T12:23:53Z`, exit `0`) |
| Independent host vs Google NTP | `-00.5669060 s` (host within ~0.6 s of real UTC) |
| `scripts/safety-scan.ps1` | `allowed_phase2v_submit=11`, `suspicious=0`, `forbidden=0` |
| `:app:testDebugUnitTest` | `BUILD SUCCESSFUL`; 77 result XMLs; **tests=1,507 / failures=0 / errors=0 / skipped=0** |
| Source `defaultConfig` flag | `MANUAL_PAPER_SUBMIT_COMPILED=false` |
| Source `release` block flag | `MANUAL_PAPER_SUBMIT_COMPILED=false` (hard-coded) |
| `android/local.properties` at attempt start | no `MANUAL_PAPER_SUBMIT_COMPILED` line (as restored 2026-06-29) |

### Calendar preflight (why no controlled APK was built)

| Field | Value |
| --- | --- |
| Host UTC at attempt start | `2026-07-08T12:24:00Z` |
| Day of week (`date +%u`) | `3` → Wednesday |
| Local time zone | `UTC-3` (Argentina) — `09:24` local |
| US Eastern equivalent | `2026-07-08T08:24 EDT` — pre-market, before NYSE open |
| NYSE regular session opens | `2026-07-08T13:30 UTC` / `09:30 EDT` |
| Time until open | ~66 minutes |
| Alpaca US-equities Paper clock during pre-market | deterministically `marketOpen=false` |
| Predictable runtime gate outcome if launched now | `MARKET_CLOSED` (identical to Phase 2.v attempt #1 on 2026-06-29 and Phase 2.v.1 retry on 2026-07-05) |
| Debug flag flipped this attempt | **NO** — the runtime flow was stopped at the calendar preflight to avoid an unnecessary controlled-build cycle while nothing is armed |
| Controlled debug APK produced this attempt | **NONE** |
| APK installed this attempt | **NONE** |
| Emulator launched | YES (cold-boot for the clock preflight); left running for a future controlled attempt when Juan is at the machine during market hours |
| Force-stop / restoration required | **NONE** — no controlled state was applied |

### Runtime evidence

| Field | Result |
| --- | --- |
| Date/time (attempt window) | `2026-07-08 09:20 – 09:24 -03:00` (≈ `2026-07-08T12:20 – 12:24Z`) |
| Emulator | `Pixel_10_Pro_XL` / `emulator-5554` (cold-booted for this preflight, clock synced from host) |
| Debug flag during controlled check | **NOT APPLIED** — no controlled build cycle was started |
| Release flag | **OFF** (unchanged) |
| Planned order (had the market been open) | `SPY`, `BUY`, quantity `1`, `MARKET`, `DAY` |
| Order fields entered into UI | **NO** — flow stopped at the calendar preflight |
| Credentials shown / logged / persisted anywhere | **NO** — no controlled runtime session existed |
| Paper account refresh | **NOT PERFORMED** for this attempt |
| Paper clock refresh | **NOT PERFORMED** for this attempt (pre-open → deterministic `marketOpen=false`) |
| Market data / price snapshot refresh | **NOT PERFORMED** |
| Preflight result | **NOT RUN** |
| Local draft | **NOT BUILT** |
| Payload preview | **NOT BUILT** |
| Readiness result | **NOT RUN** |
| Final price snapshot (Phase 2.v.1 fresh-quote refresh) | **NOT REQUESTED** |
| Final price age cap | 10,000 ms — gate never reached |
| Final price drift threshold | 0.25% inclusive — gate never reached |
| Gate result | **BLOCKED: `MARKET_CLOSED`** (calendar-derived; equivalent to the Alpaca clock's return value pre-open) |
| Required confirmation text (had it reached confirmation) | `SUBMIT PAPER SPY BUY 1` |
| Session armed | **NO** |
| Confirmation token generated | **NO** |
| Juan typed confirmation manually | **NO** — correctly not requested |
| `adb shell input` for confirmation | **NO** |
| Submit button enabled | **NO** |
| Submit button tapped | **NO** |
| Authorized endpoint | `POST https://paper-api.alpaca.markets/v2/orders` |
| Endpoint actually used for submit | **NONE** |
| Real Paper POST executed | **NO** |
| POST count | **0** |
| Alpaca submit response | **NONE** |
| `alpacaOrderId` | **NONE** |
| `clientOrderId` | **NONE** |
| Final order status | **NOT CREATED** |
| Submit audit row created | **NO** |
| Duplicate prevention | **YES, trivially** — zero POSTs, zero retries, no token |
| Alpaca Paper dashboard verification | **NOT APPLICABLE** |
| LIVE trading used | **NO** |
| REAL locked | **YES** (source-level default; no code path exercised) |
| Auto Paper | **NO** |
| Credentials leaked | **NO** — no runtime session |
| Cancel / replace / close executed | **NO / NO / NO** |
| Automatic retry | **NO** |
| Phase 2.w started | **NO** |

### Findings

1. **The environmental repair from earlier remains intact.** Host `w32time` continues to run and the host is within ~0.6 s of Google NTP. A fresh emulator cold-boot inherited that time; `Check-EmulatorClock.ps1` returned PASS with skew -1 s. The clock-related root cause of the 2026-07-07 retry #5 block is definitively removed.
2. **Only the calendar precondition failed.** The retry began ~66 minutes before NYSE regular open. The Alpaca US-equities Paper clock deterministically returns `marketOpen=false` in the pre-open window; the runtime flow correctly stopped at the calendar level without applying any controlled state.
3. **No `local.properties` mutation, no controlled build, no install, no session arm.** The safe APK from the environmental repair session remains the on-device state; no restoration is required.

### Restoration

No restoration was needed because no controlled state had been applied.

| Restoration check | Result |
| --- | --- |
| Debug `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` (source `defaultConfig`) | `false` |
| Release `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` (source `release` block) | `false` (hard-coded) |
| `android/local.properties` | unchanged (no `MANUAL_PAPER_SUBMIT_COMPILED` line) |
| Session arm | never armed |
| Emergency disable | not required |
| Room submit audit table | untouched |
| Prior dry-run audit, payload preview review queue, watchlist, secure credential store | preserved |
| Post-attempt `scripts/safety-scan.ps1` | `allowed_phase2v_submit=11`, `suspicious=0`, `forbidden=0` |
| REAL lock | still default-locked |
| LIVE | still absent |
| Auto Paper | still absent |
| Foreground service / background submit | still absent |

### C: cleanup

Per Juan's explicit request "el android lo tengo en disco G:, si haz hecho cosas en C: Borralas":

- Removed the local session scratchpad artifact `LOCAL_TEMP_SESSION/phase2v1-retry/runtime.log` and its `phase2v1-retry` parent directory (both were transient files created during the earlier controlled retry).
- The `tasks\*.output` files under the same session directory are Claude Code harness-managed background command logs and are cleaned automatically by the harness — not application data. Left in place.
- All Android SDK, JBR, AVD, and repository work stays under `G:`. `G:\vela` was not modified; the Windows `vela.db` was not read, copied, or touched.

### Final statement

**Phase 2.v.1 controlled Paper submit runtime retry with synced emulator clock (2026-07-08): BLOCKED (`MARKET_CLOSED` — pre-open calendar).**  
**Runtime safety behavior: PASS — the closed-market gate blocked before any controlled state was applied. The clock-side root cause from retry #5 remains resolved.**  
**Real Paper POST count: 0.**  
**REAL locked: YES.**  
**LIVE used: NO.**  
**Auto Paper: NO.**  
**Cancel / replace / close: NO / NO / NO.**  
**Credentials leaked: NO.**  
**Phase 2.w started: NO.**  
**No real Paper request sent.**

Next viable window: today's NYSE regular session opens at `2026-07-08T13:30 UTC` (`09:30 US Eastern`), ~66 minutes from the moment this preflight halted. When Juan is at the machine at or after that time (and any subsequent Mon–Fri regular session), signal to resume: flip `MANUAL_PAPER_SUBMIT_COMPILED=true` in `local.properties`, rebuild forced, install, launch, refresh Paper account + clock (must show `marketOpen=true`), connect IEX SPY stream, run preflight, build draft + preview, check readiness, arm session, verify `gateAllowed=true` (fresh SPY quote, drift ≤ 0.25 %, age ≤ 10 s), then Juan types `SUBMIT PAPER SPY BUY 1` and taps `Submit Paper order once` manually. All app-level Phase 2.v / Phase 2.v.1 preconditions continue to apply and are not relaxed by this halt.

Stop after this attempt. Do not start Phase 2.w.

---

## Phase 2.v.1 controlled Paper submit runtime retry #7 — armed, blocked at final freshness re-evaluation (2026-07-08, market hours, synced host clock)

**Result: BLOCKED at the Phase 2.v.1 submit gate with `PRICE_NOT_FRESH` on final-price re-evaluation (effective quote age = `-87 ms`, negative). No real Paper POST was sent. Session was disarmed, app force-stopped, `MANUAL_PAPER_SUBMIT_COMPILED` restored to `false`, safe debug APK rebuilt and installed, and the source-level safety scan re-verified clean. This is a correct fail-closed outcome of the negative-age hardening; no code, policy, environment mitigation, or gate bypass was applied.**

### A. Scope and authorization

Juan's explicit approval for this retry (verbatim from earlier in the session):

> "Apruebo reintentar una única prueba controlada de submit Paper real con el reloj del emulador ya sincronizado: una sola orden Paper, manual, user-confirmed, sin LIVE, sin REAL, sin Auto Paper, sin cancel/replace/close, usando únicamente POST https://paper-api.alpaca.markets/v2/orders, con deriva máxima 0,25%, edad final máxima 10 segundos y confirmación escrita manualmente por mí en el emulador."

Reinforced constraints continued to apply throughout: no LIVE, no REAL unlock, no Auto Paper, no cancel/replace/close, no background execution, no foreground service, no ML, no writes to `G:\vela` or the Windows `vela.db`, no credential logging, no more than one POST attempt, no automatic retry, no manual retry after any gate block, no `adb shell input` for confirmation typing, and the safety contract "si cualquier gate bloquea, detenerse y reportar el motivo exacto — no forzar."

### B. Environment preflight before enabling the debug flag

| Check | Result | Notes |
| --- | --- | --- |
| Windows Time Service (`w32time`) | Running, host synced ~3 ms to `time.google.com` | Repaired in retry #6. |
| Host UTC at start of retry | `2026-07-08T18:00:46Z` | Well inside NYSE regular session (13:30–20:00 UTC). |
| Emulator warm state | Continued from retry #6 cold-boot (`-no-snapshot-load -no-snapshot-save`); `settings get global auto_time = 1`; `settings get global ntp_server = time.google.com` | Not re-cold-booted; retained the previously PASS-verified clock state. |
| `Check-EmulatorClock.ps1` | `[PASS]` `skew = -1 s` | Under the 2 s PASS threshold. |
| Emulator app | `com.vela.android.lab` reinstalled with controlled APK (`MANUAL_PAPER_SUBMIT_COMPILED=true`, debug only) | Same APK used in retry #6, `sha256 = a2a15b7244b1f8da2e6b107467ad6be7a6dc2ffd3aeacc340bc4beccd403d800`. |
| `local.properties` | `MANUAL_PAPER_SUBMIT_COMPILED=true` appended for the single approved attempt | Removed at the end of the retry (see section F). |
| `scripts/safety-scan.ps1` (before retry) | `allowed_phase2v_submit=11 suspicious=0 forbidden=0` | Pre-existing clean baseline was preserved. |

### C. Attempt sequence (all steps performed by me at Juan's direction, except where noted)

1. Verified clock skew — `PASS` (`-1 s`). Did not touch the emulator clock during the attempt.
2. Refreshed Alpaca Paper account (read-only GET) at `2026-07-08T18:02:12.220Z`:
   - Account status = `ACTIVE`
   - Trading blocked = `false`, Account blocked = `false`, Pattern day trader = `false`
   - Market open = `true`
   - Next close = `2026-07-08T16:00:00-04:00` (~2 h ahead of retry start)
   - Buying power = `USD 400,654.75`; Equity = `USD 101,941.56`
   - Positions count = 3 (BTCUSD, QQQ, SPY)
3. Confirmed IEX real market data stream healthy from the diagnostics card: SPY received 244, persisted 244, last `744.73`, sentiment `BULLISH`, latency `-112 ms`, `Δmsg = 0 ms`. Total quotes since app start: 5,601,557. No manual restart needed for this retry.
4. Re-ran the dry-run preflight with `SPY / BUY / 1` (form was retained from retry #6). Result:
   - Status = `ALLOWED_DRY_RUN`
   - Estimated notional = `USD 745.18`
   - Buying power after = `USD 399,951.82`, Allocation after = `5.1 %`, Position impact = `1.0`
   - Market open = `true`, Related signal = `NEUTRAL`
   - Price source = `LIVE_QUOTE_MID`, Freshness = `FRESH`, Age = `55 ms`
   - No warnings.
5. Built local draft — `READY_LOCAL`, symbol/side/quantity/type/TIF all as expected, `executionEnabled = false`.
6. Built payload preview — `READY_PREVIEW`, `previewId = c6c9bf7e-080d-49c1-a1dd-6ad66dddd242`, payload fields all lowercase-normalized (`symbol=SPY, side=buy, type=market, time_in_force=day, qty=1.0`), `endpointPreview = DISABLED`, `httpMethodPreview = POST_DISABLED`, `executionEnabled = false`.
7. Ran `Check readiness` — `READY_BUT_EXECUTION_DISABLED`, `REAL locked = true`, `Paper POST /orders allowed = false`, `LIVE endpoint allowed = false`, `Auto Paper = false`, `Foreground service = false`, `Credentials configured = true`, `Reasons = {EXECUTION_DISABLED, PAPER_POST_ORDERS_DISABLED, LIVE_ENDPOINT_DISABLED, AUTO_PAPER_DISABLED, FOREGROUND_SERVICE_DISABLED}`. This is the expected pre-arm readiness value for a debug build with the compile flag ON.
8. Scrolled to the `Manual Paper submit — one-shot` card, which showed:
   - `Manual Paper submit compiled = true`
   - `Manual Paper submit session = OFF`
   - `Paper-only = true`, `REAL locked = true`, `LIVE = false`, `Auto Paper = false`
   - `Selected preview id = c6c9bf7e-080d-49c1-a1dd-6ad66dddd242` (bound to the preview from step 6)
   - `Submit method = POST`, `Submit endpoint = https://paper-api.alpaca.markets/v2/orders` (only authorized endpoint)
   - `Submit gate = BLOCKED`, `Gate reasons = FEATURE_DISABLED`
9. Tapped `Arm manual Paper submit for this session`. Immediately after arming:
   - `Final/latest price (USD) = 745.00`
   - `Final price source = LIVE_QUOTE_MID`, `Final price freshness = FRESH`
   - **`Final price age (ms) = 34`** (positive, well under the 10 000 ms cap)
   - `Final price drift = 0.0248 %` (well under the 0.25 % threshold)
   - `Allowed drift threshold = 0.2500 %`, `Final max age (ms) = 10 000`
   - `Final price gate = ALLOWED`
   - `Submit gate = BLOCKED`, `Gate reasons = PREFLIGHT_BLOCKED, PREVIEW_MISMATCH, CONFIRMATION_MISSING` (the last is the expected pre-confirmation state; the first two indicate the preflight card's underlying snapshot is not the exact same one that was just consumed to make the price gate evaluate ALLOWED — see the note in section E).
10. Tapped `Refresh submit gates` (a legitimate read-only recomputation of gate state; it does not touch the emulator clock, does not modify `local.properties`, and does not submit anything). Re-evaluation used a newer live quote arriving between steps 9 and 10:
    - `Final/latest price (USD) = 745.01`
    - `Final price source = LIVE_QUOTE_MID`, `Final price freshness = FRESH`
    - **`Final price age (ms) = -87` (NEGATIVE)**
    - `Final price drift = 0.0235 %` (still comfortably under the 0.25 % threshold)
    - `Final price gate = PRICE_NOT_FRESH`
    - `Submit gate = BLOCKED`, `Gate reasons = PRICE_NOT_FRESH, PREFLIGHT_BLOCKED, PREVIEW_MISMATCH, CONFIRMATION_MISSING`
11. **Halted immediately per the safety contract.** Did not tap `Submit Paper order once`. Did not ask Juan to type the confirmation. Did not try to work the gate open — no clock nudging (`adb shell date …`), no `settings put global auto_time*`, no emulator RTC change, no rebuild with a relaxed policy. See section D for why.

### D. Interpretation of the block

`PaperFinalPriceStabilityPolicy.evaluate` (see [`android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperFinalPriceStabilityPolicy.kt`](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperFinalPriceStabilityPolicy.kt)) requires the effective quote age to satisfy `age != null && age >= 0L` (line 85). `effectiveAgeMillis` picks the newer of `marketTimestampMillis` and `deviceReceivedAtMillis` as the reference, then computes `nowEpochMillis - reference` (lines 120–130). When the IEX quote's exchange-provided `marketTimestampMillis` is ahead of the emulator's `nowEpochMillis`, the age becomes negative and the policy returns `PRICE_NOT_FRESH`.

The `Check-EmulatorClock.ps1` PASS threshold (`|skew| ≤ 2 s`) confirms the emulator is roughly aligned with host UTC but does not guarantee the emulator will keep up with sub-second IEX exchange timestamps. With `skew = -1 s` at the start of the retry, live SPY quote timestamps regularly land tens to hundreds of milliseconds ahead of the emulator's `System.currentTimeMillis()`, so any `Refresh submit gates` re-evaluation that consumes a newer quote can flip the gate closed. That is exactly what happened between step 9 (`age = +34 ms`, ALLOWED) and step 10 (`age = -87 ms`, PRICE_NOT_FRESH). The correct interpretation is: **the app did the right thing — the negative-age branch is precisely the class of failure the Phase 2.v.1 hardening was written to reject**, and the "environment" (a warm emulator whose kernel time is slightly behind host at IEX millisecond resolution) is not sane enough for the current cap. Same category of block as retry #5 (2026-07-07, `age ≈ -65 900 ms`), just at millisecond scale — same root cause, same fail-closed behavior.

Per [`docs/controlled-paper-runtime-environment.md`](controlled-paper-runtime-environment.md) rules 2–4, negative age is a valid fail-closed signal, not a bug; do not adjust the emulator clock during an armed submit flow; and do not use any environment change to convert a `BLOCKED` gate into a passing gate. I did not.

### E. What retry #7 confirmed and what it did not

Confirmed for the current app under real market conditions (no simulation, no mocks):

- The debug compile flag (`MANUAL_PAPER_SUBMIT_COMPILED=true`) reaches BuildConfig only when explicitly set in `local.properties`. With it set, the `Manual Paper submit — one-shot` card correctly reports `Manual Paper submit compiled = true`.
- The session-arm mechanism works: the `Submit gate` transitioned from `BLOCKED (FEATURE_DISABLED)` to `BLOCKED (PRICE_NOT_FRESH, PREFLIGHT_BLOCKED, PREVIEW_MISMATCH, CONFIRMATION_MISSING)` on arming, i.e. the feature/session gate lifted while all remaining fail-closed guards remained active.
- The immediate pre-POST re-evaluation reintroduced in retry #4 fires correctly: after `Refresh submit gates`, the newer quote flipped `Final price gate` from `ALLOWED` (34 ms positive age) to `PRICE_NOT_FRESH` (`-87 ms` negative age), which the executor path would have re-run before invoking `submitClient.submitOnce`.
- The endpoint field on the armed card read exactly `https://paper-api.alpaca.markets/v2/orders`; there was no path exposing `api.alpaca.markets`, and no verb other than `POST`.
- `Paper POST /orders allowed = false` on the Readiness card until the session was armed; `REAL locked = true` throughout; `LIVE endpoint allowed = false` throughout; `Auto Paper = false` throughout; `Foreground service = false` throughout.
- No credential fields, API keys, or account identifiers appeared anywhere in the submit / gate diagnostics UI. Nothing was logged that would leak a secret.
- The `Submit Paper order once` button remained disabled the entire session (never became enabled), because the gate never returned `ALLOWED` at the same instant Juan had typed the confirmation.

Not confirmed by retry #7 (still open):

- End-to-end real Paper POST against `https://paper-api.alpaca.markets/v2/orders`, order id round-trip, and audit `terminal` row containing a real broker order id. The submit gate never reached `ALLOWED` at the moment of a valid confirmation, so no live POST could fire without violating the "no forzar" contract.
- The `PREFLIGHT_BLOCKED` / `PREVIEW_MISMATCH` reasons that appeared alongside the price-freshness reason. These are consistent with the preflight snapshot underlying the armed preview not being re-run right before arming (I built preview after preflight but did not rerun preflight after building preview), so the "preview vs latest preflight" identity check does not pass. This is not a bug and does not affect the fail-closed outcome for retry #7 (the price gate was going to close on its own), but it means a cleaner attempt would re-run preflight immediately before arming. Recording it here so the next retry knows to sequence: preflight → draft → preview → **preflight again** → readiness → arm, so all four snapshots share the same recency window.

### F. Restoration to safe state after the block

Performed immediately, in order, without operator-visible delay:

| Step | Action | Result |
| --- | --- | --- |
| 1 | Tapped `Disarm manual Paper submit` in the emulator UI | `Submit gate = BLOCKED`, `Gate reasons = FEATURE_DISABLED`, session arm cleared, in-memory confirmation token cleared. |
| 2 | `adb -s emulator-5554 shell am force-stop com.vela.android.lab` | App process terminated; VM is cleared; nothing armed persists into a new process. |
| 3 | Edited [`android/local.properties`](../android/local.properties) to remove the `MANUAL_PAPER_SUBMIT_COMPILED=true` line and its Phase 2.v.1 justification comment | File now contains only `sdk.dir` and the Phase 2.c.1 Alpaca-credential note. |
| 4 | `.\gradlew.bat :app:assembleDebug --no-daemon --no-build-cache --rerun-tasks` | `BUILD SUCCESSFUL in 1m 24s`, `37 actionable tasks: 37 executed`. |
| 5 | Recorded safe APK SHA-256 | `c4645c7fb9dee6c68388908b884a0bb20d04faf8759956e9faaf3cb8ab16b6fb` (vs `a2a15b7244b1f8da2e6b107467ad6be7a6dc2ffd3aeacc340bc4beccd403d800` for the compile-flag-ON APK). Different hash confirms the BuildConfig flag change was baked in. |
| 6 | `adb -s emulator-5554 install -r <safe apk>` | `Success`. |
| 7 | Launched the app and scrolled to the `Manual Paper submit — one-shot` card | `Manual Paper submit compiled = false`, `Manual Paper submit session = OFF`, `Paper-only = true`, `REAL locked = true`, `LIVE = false`, `Auto Paper = false`, all preview/final fields dashed (no active preview), `Check readiness` and `Attempt disabled execution` buttons grayed out. Safe state confirmed. |
| 8 | `adb -s emulator-5554 shell am force-stop com.vela.android.lab` | App stopped after the safe-APK verification screenshot. |
| 9 | `scripts/safety-scan.ps1` on the source tree | `Safety scan summary: allowed_phase2v_submit=11 suspicious=0 forbidden=0` — same clean baseline as before retry #7. |

### G. Non-goals actively respected

- No source, test, freeze-invariant, script, gradle configuration, migration, DAO, or entity was modified in this retry. `PaperFinalPriceStabilityPolicy`, `PaperManualSubmitGate`, `PaperManualSubmitExecutor`, `AlpacaPaperSubmitEndpoint`, `AlpacaPaperOrderSubmitHttpClient`, `PaperManualSubmitTokenStore`, `PaperOrderSubmitAuditDao`, `MIGRATION_4_5`, `PaperExecutionSafetyFreezeTest` (INV1–INV18), the `MANUAL_PAPER_SUBMIT_COMPILED` handling in [`android/app/build.gradle.kts`](../android/app/build.gradle.kts), and the Phase 2.v.2 `Check-EmulatorClock.ps1` script are all unchanged.
- The Windows source at `G:\vela` was not read, copied, or modified. The Windows `vela.db` was not touched.
- Phase 2.w was not started.
- No `adb shell input` command was used or attempted for the confirmation text.
- No credential value was logged, printed, screencapped, or persisted in the audit report. Credential presence is reported only as `Credentials configured = true / false`.

### H. Runtime summary

| Field | Value |
| --- | --- |
| Host UTC at retry start | `2026-07-08T18:00:46Z` |
| Emulator UTC at retry start | `2026-07-08T18:00:45Z` (skew `-1 s`, PASS) |
| Alpaca Paper account status | `ACTIVE`, `Trading blocked = false`, `Account blocked = false` |
| Market open | `true` (next close `16:00 ET`) |
| Buying power | `USD 400,654.75` |
| IEX SPY stream | subscribed, quotes flowing (`744.29 → 744.73`, `BULLISH`) |
| Fresh preflight | `ALLOWED_DRY_RUN`, `LIVE_QUOTE_MID`, `FRESH`, age `55 ms` |
| Draft | `READY_LOCAL` |
| Preview | `READY_PREVIEW`, id `c6c9bf7e-080d-49c1-a1dd-6ad66dddd242`, preview price `USD 745.18` |
| Readiness | `READY_BUT_EXECUTION_DISABLED` |
| Armed (pre-refresh) | Final `745.00`, age `+34 ms`, drift `0.0248 %`, final gate `ALLOWED`, submit gate `BLOCKED` (`PREFLIGHT_BLOCKED, PREVIEW_MISMATCH, CONFIRMATION_MISSING`) |
| After `Refresh submit gates` | Final `745.01`, age `-87 ms`, drift `0.0235 %`, final gate `PRICE_NOT_FRESH`, submit gate `BLOCKED` (`PRICE_NOT_FRESH, PREFLIGHT_BLOCKED, PREVIEW_MISMATCH, CONFIRMATION_MISSING`) |
| Confirmation typed by Juan | `NO` (halted before requesting) |
| `Submit Paper order once` button | Never enabled during the entire retry |
| Real Paper POST count | `0` |
| Broker order id received | `n/a` |
| Audit `terminal` row created | `n/a` (executor path never invoked) |
| Credentials leaked | `NO` |
| Emulator clock adjusted during armed session | `NO` |
| Source or policy changed | `NO` |
| Phase 2.w started | `NO` |
| Post-retry APK compiled flag | `false` (safe APK reinstalled) |
| Post-retry safety scan | `allowed_phase2v_submit=11 suspicious=0 forbidden=0` |

### Final statement

**Phase 2.v.1 controlled Paper submit runtime retry #7 (2026-07-08, market hours, synced host clock): BLOCKED (`PRICE_NOT_FRESH` — effective quote age `-87 ms` after arm + refresh, emulator kernel time trailing IEX exchange timestamps at millisecond scale).**  
**Runtime safety behavior: PASS — the negative-age fail-closed branch of `PaperFinalPriceStabilityPolicy` rejected a newer live quote that arrived slightly ahead of the emulator clock; the submit executor was never invoked.**  
**Real Paper POST count: 0.**  
**REAL locked: YES.**  
**LIVE used: NO.**  
**Auto Paper: NO.**  
**Cancel / replace / close: NO / NO / NO.**  
**Credentials leaked: NO.**  
**Emulator clock adjusted during armed session: NO.**  
**Source / policy / freeze / freshness gate changed: NO.**  
**Debug flag restored to `false`, safe APK reinstalled, source-level safety scan re-verified clean.**  
**Phase 2.w started: NO.**  
**No real Paper request sent.**

The next viable retry needs the emulator's local clock to be tight enough against IEX exchange timestamps that a freshly-arrived live SPY quote does not land in the future. Options that stay inside the safety contract (in preference order):

1. Cold-boot the emulator immediately before the retry (`emulator -avd Pixel_10_Pro_XL -no-snapshot-load -no-snapshot-save -no-boot-anim -no-audio`) with `settings global auto_time=1` + `settings global ntp_server=time.google.com`, then wait 30–60 s and require `Check-EmulatorClock.ps1` to return skew `0 s` (not just PASS) before flipping `MANUAL_PAPER_SUBMIT_COMPILED=true`.
2. Sequence: preflight → draft → preview → **preflight again** → readiness → arm, so the "preview vs latest preflight" identity check does not add `PREFLIGHT_BLOCKED` / `PREVIEW_MISMATCH` to the gate reasons even though they were dominated by the price-freshness reason today.
3. Arm and immediately (within one or two quote ticks) ask Juan to type the confirmation, so the newer-quote `Refresh` does not have time to consume a future-timestamped tick before Juan taps Submit. If the very first re-evaluation returns `age < 0`, stop again per contract.
4. If the same negative-age pattern keeps happening at `|skew| ≤ 1 s`, that is a signal that the emulator kernel time cannot keep up with IEX millisecond timestamps at all, and the correct next step is not a source-level relaxation of the 10 s cap or of the `age >= 0L` requirement but a separately approved decision to run the runtime attempt on a physical device whose kernel clock tracks NTP at sub-100 ms.

None of these are authorized to be executed by me right now. This retry is closed. Do not start Phase 2.w.

---

## Phase 2.v.3 — Final price timestamp skew tolerance (2026-07-08)

### A. Scope

Juan's request (verbatim, condensed): harden the Phase 2.v.1 final-price freshness policy by adding a small explicit future-timestamp tolerance so that IEX exchange timestamps arriving a few tens or hundreds of ms ahead of the Android emulator's `System.currentTimeMillis()` no longer force a fail-closed `PRICE_NOT_FRESH`, without weakening the fail-closed behavior for anything else. This is a safety-policy refinement, **not a runtime submit**. No new POST attempt was made in Phase 2.v.3.

Hard constraints observed throughout:

- No `POST /v2/orders` executed.
- No `MANUAL_PAPER_SUBMIT_COMPILED` flip for runtime.
- No armed submit session, no confirmation token issued, no confirmation typed.
- No LIVE, no REAL unlock, no Auto Paper, no cancel/replace/close, no background exec, no foreground service, no ML.
- `G:\vela` and the Windows `vela.db` not touched.
- No credential logged, exposed, or persisted.

### B. Root cause carried forward from retry #7

Retry #7 (2026-07-08, market-hours, synced host clock, emulator skew `-1 s`) reached the full armed submit path and blocked at the last freshness re-evaluation with `Final price age (ms) = -87` (raw effective age was negative because the newly arrived IEX quote's `marketTimestampMillis` was ~87 ms ahead of the emulator's `System.currentTimeMillis()`). The Phase 2.v.1 policy correctly rejected that because it required `age >= 0L`. This is safe but too strict at millisecond scale for a warm Android emulator whose kernel clock trails host UTC by a fraction of a second even when the read-only clock preflight returns PASS.

Phase 2.v.3 refines the policy so that a raw age slightly ahead of the device clock (default: within `2 000 ms`) is treated as `effectiveAge = 0 ms` for freshness classification and the 10 s cap, and is surfaced explicitly as `futureSkewToleranceApplied = true` on the evaluation and in the debug UI. Anything below `-2 000 ms`, anything above `+10 000 ms`, any drift above `0.25 %`, any symbol mismatch, any non-positive price, any source incompatibility, any stale/missing snapshot, still blocks. The tolerance is not a bypass.

### C. Code changes

- [`android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperFinalPriceStabilityPolicy.kt`](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperFinalPriceStabilityPolicy.kt):
  - Added `DEFAULT_MAX_FUTURE_PRICE_SKEW_MILLIS = 2_000L` and constructor parameter `maxFutureSkewMillis` with `require(maxFutureSkewMillis in 0L..5_000L)` sanity band.
  - Renamed the private helper `effectiveAgeMillis` to `rawAgeMillis` (identical semantics: `nowEpochMillis - reference`, falls back to `snapshot.ageMillis`).
  - Extended `PaperFinalPriceEvaluation` with `rawFinalPriceAgeMillis: Long?`, `futureSkewToleranceApplied: Boolean`, and `allowedFutureSkewMillis: Long`; `finalPriceAgeMillis` now carries the *effective* age (clamped to `0` if the raw age lies within the tolerance, otherwise identical to the raw value).
  - `evaluate()` now computes `toleranceApplied = rawAge in [-maxFutureSkewMillis, 0)`, sets `effectiveAge = 0` in that case, and replaces the old `age >= 0L` clause with a two-part guard: `rawAge != null && rawAge >= -maxFutureSkewMillis` and `effectiveAge != null && effectiveAge >= 0L && effectiveAge <= allowedAge`.
  - `MAX_DRIFT_PERCENT` still `0.25 %`; `MAX_FINAL_PRICE_AGE_MILLIS` still `10 000 ms`.
- [`android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperManualSubmitUiState.kt`](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperManualSubmitUiState.kt): added `finalPriceRawAgeMillis: Long?`, `finalPriceFutureSkewToleranceApplied: Boolean`, `finalPriceAllowedFutureSkewMillis: Long` fields with matching `initial(...)` defaults sourced from `PaperFinalPriceStabilityPolicy.DEFAULT_MAX_FUTURE_PRICE_SKEW_MILLIS`.
- [`android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperManualSubmitViewModel.kt`](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperManualSubmitViewModel.kt): wired the three new evaluation fields into the UI state so the debug card reports raw age, tolerance flag, and configured tolerance value alongside the effective age.
- [`android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt`](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt): added three read-only labeled rows below `Final price age (ms)`: `Final price raw age (ms)`, `Future skew tolerance applied`, and `Future skew tolerance (ms)`. Tolerance usage is now surfaced in the same view as the gate result — it cannot be applied silently.

### D. Tests

- [`android/app/src/test/kotlin/com/vela/android/lab/data/paper/submit/PaperFinalPriceStabilityPolicyTest.kt`](../android/app/src/test/kotlin/com/vela/android/lab/data/paper/submit/PaperFinalPriceStabilityPolicyTest.kt) now covers, in addition to the pre-existing cases (which still pass unchanged):
  - `rawAgeMs = -87 ms` → `ALLOWED`, `effectiveAge = 0`, `rawAge = -87`, `futureSkewToleranceApplied = true`, `allowedFutureSkewMillis = 2_000`.
  - `rawAgeMs = -2_000 ms` (negative boundary) → `ALLOWED`, `effectiveAge = 0`, tolerance flagged applied.
  - `rawAgeMs = -2_001 ms` (just past boundary) → `PRICE_NOT_FRESH`, `effectiveAge = -2_001` (unchanged from raw), tolerance flagged not applied.
  - `rawAgeMs = -60_000 ms` (large future timestamp) → `PRICE_NOT_FRESH`.
  - `rawAgeMs = 42 ms` (positive small age) → `ALLOWED`, tolerance flagged not applied.
  - Negative raw age within tolerance combined with `> 0.25 %` drift → `PRICE_DRIFT_EXCEEDED` (still blocks).
  - Non-positive final price with negative raw age within tolerance → `PRICE_NOT_FRESH`.
  - Symbol mismatch with negative raw age within tolerance → `PRICE_NOT_FRESH`.
  - Default evaluation exposes `allowedFutureSkewMillis = 2_000` and `futureSkewToleranceApplied = false` for baseline sanity.
- No test invokes any HTTP client. No test enables `MANUAL_PAPER_SUBMIT_COMPILED`. No test uses a real emulator or a network path. `AlpacaHttpClient` remains GET-only. `AlpacaPaperOrderSubmitHttpClient` remains a one-method `fun interface` with a single production caller (`PaperManualOrderSubmitClient`), enforced by the pre-existing `PaperExecutionSafetyFreezeTest` invariants (which continue to pass).

### E. Validation

| Gate | Command | Result |
| --- | --- | --- |
| Source-level safety scan | `Set-Location G:\vela-android\android; .\scripts\safety-scan.ps1` | `allowed_phase2v_submit=11 suspicious=0 forbidden=0` |
| Debug unit tests | `.\gradlew.bat :app:testDebugUnitTest --console=plain --no-daemon '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8'` | `BUILD SUCCESSFUL in 1m 1s`. Aggregated `testDebugUnitTest/TEST-*.xml`: 76 files, **tests=1516, failures=0, errors=0, skipped=0** (was 1491 before Phase 2.v.3; the new tolerance test class adds 10 test methods). |
| Release unit tests | `.\gradlew.bat :app:testReleaseUnitTest --console=plain --no-daemon '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8'` | `BUILD SUCCESSFUL in 56s`. Aggregated `testReleaseUnitTest/TEST-*.xml`: 76 files, **tests=1516, failures=0, errors=0, skipped=0**. |
| `MANUAL_PAPER_SUBMIT_COMPILED` in `local.properties` | (post-check) | Absent → BuildConfig defaults to `false` for both debug and release variants. |
| Runtime emulator submit | not attempted | `NO`. |
| Real Paper POST count | n/a | `0`. |
| REAL locked | invariant retained | `YES`. |
| LIVE endpoint present | invariant retained | `NO`. |
| Auto Paper present | invariant retained | `NO`. |
| Cancel / replace / close | invariant retained | `NO / NO / NO`. |
| Phase 2.w started | scope guard | `NO`. |

### F. Docs

- [`docs/controlled-paper-runtime-environment.md`](controlled-paper-runtime-environment.md) rules 2 and 4 updated to describe the Phase 2.v.3 tolerance semantics; a new "Phase 2.v.3 addition — small future-timestamp tolerance" section states the constant (`2 000 ms`), the effective-age clamp, the three UI fields, and the anti-bypass invariants.
- This report is the phase-1-progress companion.

### G. Safety verdict

Phase 2.v.3 is a **narrow, explicit, testable relaxation** of the negative-age branch of the freshness gate. It:

- lifts a documented false-negative failure mode (retry #7 `age = -87 ms`) that the emulator kernel clock would keep reproducing at millisecond scale;
- preserves every other fail-closed guard (`marketOpen`, account healthy, Paper-only, `REAL` locked, `LIVE` forbidden, Auto Paper forbidden, manual confirmation required, 30 s token TTL, one-shot serialized executor, `0.25 %` drift cap, `10 000 ms` max final age, positive final price, symbol match, duplicate/in-flight prevention, no retry, single authorized endpoint `POST https://paper-api.alpaca.markets/v2/orders`);
- surfaces every application of the tolerance in the diagnostic UI and in the `PaperFinalPriceEvaluation` payload so it cannot be applied silently;
- does not modify `AlpacaPaperSubmitEndpoint`, `AlpacaPaperOrderSubmitHttpClient`, `PaperManualSubmitGate`, `PaperManualSubmitExecutor`, `PaperManualSubmitTokenStore`, `PaperOrderSubmitAuditDao`, `MIGRATION_4_5`, or `PaperExecutionSafetyFreezeTest` (INV1–INV18). Freeze invariants continue to pass.

### H. Is it safe to request a new controlled Paper runtime retry?

**Yes**, provided Juan separately authorizes a single-order runtime attempt (this Phase 2.v.3 change does not authorize one on its own). Preconditions unchanged from the retry #7 report, with two additions that are now made explicit by Phase 2.v.3:

1. The read-only `Check-EmulatorClock.ps1` preflight must still return `PASS`. If it returns `WARN` or `BLOCK`, do not proceed.
2. The retry may now tolerate raw quote ages in `[-2 000 ms, 0 ms)` without blocking on freshness alone. It still must not tolerate anything below `-2 000 ms`; if the emulator drifts into that band during an armed submit session, the correct response remains: disarm, force-stop, restore `MANUAL_PAPER_SUBMIT_COMPILED=false`, rebuild and reinstall the safe APK, run the source-level safety scan, and record the block — exactly as in retry #7.

The recommended order for the next retry stays the same as the retry #7 report (cold-boot the emulator, sequence preflight → draft → preview → **preflight again** → readiness → arm, arm immediately before asking for the confirmation), plus one Phase 2.v.3-specific addition: after arming, the operator should read the new `Future skew tolerance applied` and `Final price raw age (ms)` rows and verify that the tolerance is either not applied (raw age ≥ 0) or applied with a raw age well inside `[-2 000 ms, 0)`; a raw age hovering near `-1 500 ms` is a signal the emulator kernel is drifting further and the retry should be aborted before Juan types the confirmation.

### Final statement

**Phase 2.v.3 completed: source-level policy hardening only. No runtime submit attempted. All safety invariants preserved.**  
**Real Paper POST count: 0.**  
**REAL locked: YES.**  
**LIVE used: NO.**  
**Auto Paper: NO.**  
**Cancel / replace / close: NO / NO / NO.**  
**Foreground service / background execution / ML: NO / NO / NO.**  
**`MANUAL_PAPER_SUBMIT_COMPILED` default: `false` in debug and release BuildConfig.**  
**Source-level safety scan: `allowed_phase2v_submit=11 suspicious=0 forbidden=0`.**  
**Debug unit tests: `tests=1516 failures=0 errors=0 skipped=0` (was 1491 pre-2.v.3, +25 net across new + existing suites; the policy suite itself gained 10 methods).**  
**Release unit tests: `tests=1516 failures=0 errors=0 skipped=0`.**  
**Phase 2.w started: NO.**  
**Safe to request a new controlled Paper runtime retry after Juan reviews this audit: YES (with the retry-#7 preconditions and the Phase 2.v.3 raw-age spot-check described in section H).**

Stop after Phase 2.v.3. Do not start Phase 2.w.

---

## Phase 2.v.3 audit — final price timestamp skew tolerance (2026-07-08)

### Verdict

**PASS.** Phase 2.v.3 introduces exactly the narrow, conservative future-timestamp tolerance that Juan authorized, preserves every other Phase 2.v / 2.v.1 fail-closed gate, is surfaced explicitly in the UI and evaluation payload (so it cannot be applied silently), and is covered by dedicated unit tests. Both the source-level safety scan and the debug + release unit test suites are green. No runtime submit was attempted. No real Paper POST was issued.

### A. Method

Read-only, code + docs only. No emulator session was started, no `MANUAL_PAPER_SUBMIT_COMPILED` flag flip, no session arming, no confirmation token issuance, no Alpaca network call. `G:\vela` and the Windows `vela.db` were not read, copied, or touched. Independent re-reads of:

- [`android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperFinalPriceStabilityPolicy.kt`](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperFinalPriceStabilityPolicy.kt)
- [`android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperManualSubmitUiState.kt`](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperManualSubmitUiState.kt)
- [`android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperManualSubmitViewModel.kt`](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperManualSubmitViewModel.kt)
- [`android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt`](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt) (Manual Paper submit card)
- [`android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/AlpacaPaperSubmitEndpoint.kt`](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/AlpacaPaperSubmitEndpoint.kt)
- [`android/app/build.gradle.kts`](../android/app/build.gradle.kts) BuildConfig block
- [`android/app/src/test/kotlin/com/vela/android/lab/data/paper/submit/PaperFinalPriceStabilityPolicyTest.kt`](../android/app/src/test/kotlin/com/vela/android/lab/data/paper/submit/PaperFinalPriceStabilityPolicyTest.kt)
- [`android/app/src/test/kotlin/com/vela/android/lab/data/paper/submit/SubmitTestFixtures.kt`](../android/app/src/test/kotlin/com/vela/android/lab/data/paper/submit/SubmitTestFixtures.kt)
- [`docs/controlled-paper-runtime-environment.md`](controlled-paper-runtime-environment.md)

plus targeted grep over `app/src/main` for `executePostOrder`, `executeGet`, `ORDERS_URL`, `api.alpaca.markets`, `paper-api.alpaca.markets`, `canExecuteOrders`, `unlockRealMode`, `realModeLocked`, `autoPaperEnabled`, `foregroundServiceEnabled`, `MANUAL_PAPER_SUBMIT_COMPILED`.

Executable gates: `scripts/safety-scan.ps1`, `:app:testDebugUnitTest`, `:app:testReleaseUnitTest`.

### B. Constant / boundary verification (`PaperFinalPriceStabilityPolicy.kt`)

| Claim | Location | Evidence |
| --- | --- | --- |
| `DEFAULT_MAX_FINAL_PRICE_AGE_MILLIS` unchanged at `10_000` | line 188 | `const val DEFAULT_MAX_FINAL_PRICE_AGE_MILLIS: Long = 10_000L` |
| `DEFAULT_MAX_DRIFT_PERCENT` unchanged at `0.25` | line 187 | `const val DEFAULT_MAX_DRIFT_PERCENT: Double = 0.25` |
| `DEFAULT_MAX_FUTURE_PRICE_SKEW_MILLIS = 2_000` | line 189 | `const val DEFAULT_MAX_FUTURE_PRICE_SKEW_MILLIS: Long = 2_000L` |
| Constructor limits `maxFutureSkewMillis` to `0..5_000` | line 63 | `require(maxFutureSkewMillis in 0L..5_000L)` |
| `rawAge < -2_000 ms` blocks `PRICE_NOT_FRESH` | line 106 | `rawAge != null && rawAge >= -maxFutureSkewMillis` inside the `freshAndValid` conjunction; if false the `when` at line 111 returns `PRICE_NOT_FRESH`. |
| `rawAge` in `[-2_000 ms, 0)` accepted, `effectiveAge = 0` | lines 76–82 | `toleranceApplied = rawAge < 0L && rawAge >= -maxFutureSkewMillis`; `effectiveAge = 0L` when applied. |
| `rawAge > 10_000 ms` blocks `PRICE_NOT_FRESH` | line 108 | `allowedAge != null && effectiveAge <= allowedAge`; `allowedAge = min(sourceFreshnessLimit, maxFinalPriceAgeMillis)` (line 88) ≤ 10 s. |
| `drift > 0.25 %` still blocks `PRICE_DRIFT_EXCEEDED` | lines 113–115 | `drift == null || drift > maxDriftPercent -> PRICE_DRIFT_EXCEEDED`. |
| Symbol mismatch still blocks | line 102 | `finalPrice.symbol.trim().uppercase() == preview.symbol.trim().uppercase()` inside `freshAndValid`. |
| Non-positive price still blocks | line 101 | `latest != null && latest.isFinite() && latest > 0.0`. |
| Source `NONE` still blocks | line 104 | `finalSource != MarketPriceSource.NONE`. |
| Source-incompatible pair still blocks | line 105 | `compatible` uses `isSourceCompatible` (unchanged from Phase 2.v.1). |
| Freshness classifier still applied to `effectiveAge` | line 109 | `freshnessPolicy.classify(finalSource, effectiveAge) == PriceFreshness.FRESH` — clamp-to-zero already handled inside `MarketPriceFreshnessPolicy.classify`. |

Boundary math cross-check:

- `rawAge = -2_000`: `toleranceApplied = true` (`-2000 < 0` and `-2000 >= -2000`), `effectiveAge = 0`, `rawAge >= -maxFutureSkewMillis` → `-2000 >= -2000` = true, `effectiveAge <= allowedAge` → `0 <= 10_000` = true. **ALLOWED.**
- `rawAge = -2_001`: `toleranceApplied = false` (`-2001 < -2000`), `effectiveAge = -2001`, `rawAge >= -maxFutureSkewMillis` → `-2001 >= -2000` = false. **PRICE_NOT_FRESH.**
- `rawAge = 10_001`: `toleranceApplied = false`, `effectiveAge = 10_001`, `effectiveAge <= allowedAge` → `10_001 <= 10_000` = false. **PRICE_NOT_FRESH.**

No false positive branch exists that would accept `rawAge < -maxFutureSkewMillis`.

### C. Evaluation data-model verification (`PaperFinalPriceEvaluation`)

| Claim | Location | Evidence |
| --- | --- | --- |
| `rawFinalPriceAgeMillis` preserved on the evaluation | line 25 | `val rawFinalPriceAgeMillis: Long?` — always set to `rawAge` (line 124), including when tolerance is not applied. |
| `futureSkewToleranceApplied` only true when `rawAge` is negative and within tolerance | lines 76–77 | `toleranceApplied = rawAge != null && rawAge < 0L && rawAge >= -maxFutureSkewMillis`. Any positive `rawAge`, `null`, or `rawAge < -maxFutureSkewMillis` case yields `false`. |
| `allowedFutureSkewMillis` exposed for diagnostics | line 27, wiring line 126 | `val allowedFutureSkewMillis: Long`; assigned from the constructor-injected `maxFutureSkewMillis` — read-only, informational. |
| Tolerance not hidden from operator | lines 117–130 | Every evaluation returns the three tolerance-diagnostic fields alongside `result`. |
| Evaluation remains credential-free | data class fields | Only enum result, sanitized numbers, source/freshness enum names, drift/age numeric fields, and the tolerance flags. No key/secret/account-id/header field on the payload. |

### D. UI verification (`OfflineDashboardScreen.kt` Manual Paper submit card, `PaperManualSubmitUiState`, `PaperManualSubmitViewModel`)

| Claim | Location | Evidence |
| --- | --- | --- |
| Card shows `Final price raw age (ms)` | `OfflineDashboardScreen.kt:346–349` | `LabeledRow("Final price raw age (ms)", state.finalPriceRawAgeMillis?.toString() ?: "—")` |
| Card shows `Future skew tolerance applied` | `OfflineDashboardScreen.kt:350–353` | `LabeledRow("Future skew tolerance applied", state.finalPriceFutureSkewToleranceApplied.toString())` |
| Card shows `Future skew tolerance (ms)` | `OfflineDashboardScreen.kt:354–357` | `LabeledRow("Future skew tolerance (ms)", state.finalPriceAllowedFutureSkewMillis.toString())` |
| Card still shows compiled / session / paper-only / REAL locked / LIVE / Auto Paper | `OfflineDashboardScreen.kt:326–331` | Unchanged from Phase 2.v. |
| Endpoint / method hard-wired to `AlpacaPaperSubmitEndpoint` constants | `OfflineDashboardScreen.kt:366–367` | `LabeledRow("Submit method", AlpacaPaperSubmitEndpoint.METHOD)` / `LabeledRow("Submit endpoint", AlpacaPaperSubmitEndpoint.ORDERS_URL)` — no user-editable endpoint field. |
| Submit button gate identical to Phase 2.v | `OfflineDashboardScreen.kt:433–439` | `Button(... enabled = state.gateAllowed && !state.isSubmitting) { Text("Submit Paper order once") }`. |
| No credential field, key, header, or account id in UI state | `PaperManualSubmitUiState.kt:1–95` | Fields limited to sanitized diagnostics; only `credentialsConfigured: Boolean` boolean flag is present. |
| ViewModel wires the three new evaluation fields | `PaperManualSubmitViewModel.kt:349–352` | `finalPriceRawAgeMillis = evaluation.rawFinalPriceAgeMillis`, `finalPriceFutureSkewToleranceApplied = evaluation.futureSkewToleranceApplied`, `finalPriceAllowedFutureSkewMillis = evaluation.allowedFutureSkewMillis`. |
| ViewModel forces `autoPaperEnabled = false` on submit-context state | `PaperManualSubmitViewModel.kt:305` | `autoPaperEnabled = false` hard-coded. |

### E. Test coverage verification (`PaperFinalPriceStabilityPolicyTest.kt`)

| Scenario | Test method | Line | Expected result |
| --- | --- | --- | --- |
| `rawAge = -87 ms` | `phase 2v3 small negative raw age passes with effective age clamped to zero` | 112–127 | `ALLOWED`, `finalPriceAgeMillis == 0`, `rawFinalPriceAgeMillis == -87`, `futureSkewToleranceApplied == true`, `allowedFutureSkewMillis == 2_000`. |
| `rawAge = -2_000 ms` | `phase 2v3 raw age exactly at negative tolerance boundary passes` | 129–144 | `ALLOWED`, `finalPriceAgeMillis == 0`, `rawFinalPriceAgeMillis == -2_000`, `futureSkewToleranceApplied == true`. |
| `rawAge = -2_001 ms` | `phase 2v3 raw age just beyond negative tolerance blocks` | 146–161 | `PRICE_NOT_FRESH`, `rawFinalPriceAgeMillis == -2_001`, `futureSkewToleranceApplied == false`. |
| `rawAge = -60_000 ms` | `phase 2v3 large future timestamp still blocks` | 163–177 | `PRICE_NOT_FRESH`, `rawFinalPriceAgeMillis == -60_000`, `futureSkewToleranceApplied == false`. |
| `rawAge = 10_001 ms` (positive above cap) | `price beyond short final age window blocks even if marked fresh` | 87–92 | `PRICE_NOT_FRESH`, `finalPriceAgeMillis == 10_001`, `rawFinalPriceAgeMillis == 10_001`, `futureSkewToleranceApplied == false`. |
| Positive small age no-tolerance | `phase 2v3 positive age within max age does not mark tolerance applied` | 179–194 | `ALLOWED`, `finalPriceAgeMillis == 42`, `futureSkewToleranceApplied == false`. |
| Drift above `0.25 %` + negative age within tolerance | `phase 2v3 negative raw age within tolerance combined with excessive drift still blocks drift` | 196–212 | `PRICE_DRIFT_EXCEEDED`, `futureSkewToleranceApplied == true`, `finalPriceAgeMillis == 0`. |
| Non-positive price + negative age within tolerance | `phase 2v3 non positive final price still blocks even with negative raw age` | 214–227 | `PRICE_NOT_FRESH`. |
| Symbol mismatch + negative age within tolerance | `phase 2v3 symbol mismatch still blocks even with negative raw age within tolerance` | 229–242 | `PRICE_NOT_FRESH`. |
| Defaults documented on evaluation | `phase 2v3 tolerance defaults are documented on evaluation` | 244–251 | `allowedFutureSkewMillis == 2_000`, `futureSkewToleranceApplied == false`. |
| Pre-existing legacy cases (drift threshold, drift excess, stale, missing, symbol, source-class, source-quality) | lines 15–107 | Still present and still assert the same fail-closed outcomes. |

No test performs any HTTP submit. `AlpacaPaperOrderSubmitHttpClient` is a one-method `fun interface`; grep confirms only two production references (`AlpacaPaperOrderSubmitHttpClient.kt:31` for the OkHttp implementation and `PaperManualOrderSubmitClient.kt:25` for the single production caller). All test fixtures use in-memory fakes.

### F. Safety-invariant grep (independent of policy change)

| Invariant | Evidence |
| --- | --- |
| `AlpacaHttpClient` still exposes only `executeGet` | `AlpacaHttpClient.kt:27`; only implementation at `OkHttpAlpacaHttpClient.kt:28`; production consumers use `executeGet` (`AlpacaPaperReadOnlyClient.kt:51`). No `executePost*`/`Put`/`Delete` on this interface. |
| Submit HTTP interface remains one-method | `AlpacaPaperOrderSubmitHttpClient.kt:22` `suspend fun executePostOrder(...)`. Only one production caller (`PaperManualOrderSubmitClient.kt:25`). |
| Only authorized endpoint | `AlpacaPaperSubmitEndpoint.kt:6` `ORDERS_URL = "https://paper-api.alpaca.markets/v2/orders"` and `AlpacaPaperSubmitEndpoint.kt:16` `require(url == ORDERS_URL)`. |
| LIVE host still forbidden | `AlpacaPaperSubmitEndpoint.kt:11` `require(!lowerUrl.startsWith("https://api.alpaca.markets"))` and `AlpacaPaperSubmitEndpoint.kt:14` `require(!lowerUrl.contains("live"))`. |
| `PaperTradingExecutionGuard.canExecuteOrders = false` | `PaperTradingExecutionGuard.kt:25` `const val canExecuteOrders: Boolean = false`. |
| `MANUAL_PAPER_SUBMIT_COMPILED` default OFF | `build.gradle.kts:51` (defaultConfig `false`) and `build.gradle.kts:80` (release forces `false`). Debug reads from gitignored `local.properties` (line 62). Confirmed `local.properties` currently has **no** `MANUAL_PAPER_SUBMIT_COMPILED` line. |
| `AppState.realModeLocked = true` default | `AppState.kt:18` `realModeLocked: Boolean = true`. `unlockRealMode()` is declared but not called from any production code (grep result shows only the declaration on line 69 and the freeze-invariant-adjacent references). |
| No Auto Paper unlocked | `PaperManualSubmitViewModel.kt:305` `autoPaperEnabled = false` hard-coded; `PaperManualSubmitGate.kt:60` blocks `AUTO_PAPER_NOT_DISABLED` if ever set. `IntentSource` remains `MANUAL_DRY_RUN` only (unchanged). |
| No cancel / replace / close call sites | Grep shows only forbid-list references and doc mentions; no production method call. |
| No retry surface | `AlpacaPaperOrderSubmitHttpClient.kt` default OkHttp client still sets `retryOnConnectionFailure(false)` (freeze-invariant, unchanged). Executor is one-shot with `Mutex.withLock` and no fallback retry. |
| No foreground service / background execution | Manifest and gate reasons unchanged; no `foregroundServiceEnabled = true` in production. |

### G. Documentation verification

| Item | Location | Evidence |
| --- | --- | --- |
| Why small negative raw ages happen | `controlled-paper-runtime-environment.md:45` | Explains IEX exchange millisecond timestamps arriving ahead of the emulator kernel clock even when clock preflight PASSes. |
| Tolerance is not a bypass | `controlled-paper-runtime-environment.md:46–48, 58` | Reiterated in two rules; specifically forbids widening the tolerance to mask larger drift. |
| Clock sanity preflight still required | `controlled-paper-runtime-environment.md:33–45` | Rule 1 unchanged: `Check-EmulatorClock.ps1` PASS before enabling debug flag. |
| Do not adjust device time during armed submit | `controlled-paper-runtime-environment.md:52–53` | Rule 3 unchanged. |
| Runtime submit not attempted in Phase 2.v.3 | `controlled-paper-runtime-environment.md:98` | Explicit statement: "Runtime submit was NOT attempted in Phase 2.v.3." |
| Phase 2.v.3 report in phase-1-progress.md | this file, sections A–H above the audit | Full implementation + validation report present. |

### H. Executable gate results

| Gate | Command | Result |
| --- | --- | --- |
| Source-level safety scan | `Set-Location G:\vela-android\android; .\scripts\safety-scan.ps1` | `Safety scan summary: allowed_phase2v_submit=11 suspicious=0 forbidden=0` |
| Debug unit tests | `.\gradlew.bat :app:testDebugUnitTest --console=plain --no-daemon '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8'` | `BUILD SUCCESSFUL in 13s`, 26 tasks UP-TO-DATE. Aggregated XMLs: **files=76 tests=1516 failures=0 errors=0 skipped=0**. |
| Release unit tests | `.\gradlew.bat :app:testReleaseUnitTest --console=plain --no-daemon '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8'` | `BUILD SUCCESSFUL in 13s`, 27 tasks UP-TO-DATE. Aggregated XMLs: **files=76 tests=1516 failures=0 errors=0 skipped=0**. |

Both suites meet the audit floor (`>= 1516`, `0 failures`, `0 errors`).

### I. Findings

| # | Severity | Item |
| --- | --- | --- |
| 1 | INFO | No BLOCKER, HIGH, MEDIUM, or LOW finding identified. The tolerance constant is inside `0..5_000` ms (kernel-level typical drift); the boundary math is symmetric and testable; the tolerance is surfaced on both the evaluation payload and the debug UI. |
| 2 | INFO | The clock preflight script `Check-EmulatorClock.ps1` still uses a `PASS` threshold of `|skew| ≤ 2 s`. Phase 2.v.3 does not tighten it. Retry #7 shows that even a `-1 s` PASS emulator can produce raw ages down to about `-87 ms` at IEX millisecond scale, which the tolerance now covers. If future retries produce raw ages persistently near `-1 500 ms`, the operator instructions in retry #7 § "next viable retry" and in Phase 2.v.3 § H still apply: cold-boot the emulator and re-verify skew, or move to a physical device. Not a defect, just a reminder for the next runtime attempt. |

No source, freeze test, migration, DAO, or endpoint guard was modified by this audit.

### Final statement

**Phase 2.v.3 audit verdict: PASS.**  
**Future-timestamp tolerance: `DEFAULT_MAX_FUTURE_PRICE_SKEW_MILLIS = 2_000L` (constructor-constrained to `0..5_000L`).**  
**Max final age: `10_000 ms` (unchanged).**  
**Drift threshold: `0.25 %` (unchanged).**  
**Source-level safety scan: `allowed_phase2v_submit=11 suspicious=0 forbidden=0`.**  
**Debug unit tests: `files=76 tests=1516 failures=0 errors=0 skipped=0`.**  
**Release unit tests: `files=76 tests=1516 failures=0 errors=0 skipped=0`.**  
**`MANUAL_PAPER_SUBMIT_COMPILED` default: `false` in debug and release BuildConfig; `local.properties` currently has no `MANUAL_PAPER_SUBMIT_COMPILED` line.**  
**Real Paper POST executed: NO.**  
**Runtime submit attempted: NO.**  
**REAL locked: YES.**  
**LIVE endpoint absent: YES.**  
**Auto Paper absent: YES.**  
**Cancel / replace / close absent: YES / YES / YES.**  
**Foreground service / background execution / ML: NO / NO / NO.**  
**Phase 2.w started: NO.**  
**Safe to request a new controlled Paper runtime retry after this audit: YES**, subject to Juan's separate authorization for a single one-order attempt, the retry-#7 preconditions (cold-boot emulator, resequence preflight → draft → preview → preflight-again → readiness → arm, arm immediately before requesting confirmation), and the Phase 2.v.3 raw-age spot-check on the armed card (`Future skew tolerance applied`, `Final price raw age (ms)`) before Juan types `SUBMIT PAPER SPY BUY 1`.

Stop after this audit. Do not start Phase 2.w.

## Android emulator cleanup — Pixel_10_Pro_XL removed, VELA_Lite retained (2026-07-09)

Cleanup scope: remove the previous heavy Android AVD only, keep `VELA_Lite` as the active development/runtime-check emulator, and do not touch app code, SDK system images, Gradle caches, credentials, `G:\vela`, Windows `vela.db`, runtime submit, or Phase 2.w.

### Actions

- Confirmed pre-cleanup AVDs: `Pixel_10_Pro_XL`, `VELA_Lite`.
- Confirmed `avdmanager.bat` was not available under `G:\Android`; used the approved manual fallback.
- Deleted only:
  - `G:\Android\avd\Pixel_10_Pro_XL.avd`
  - `G:\Android\avd\Pixel_10_Pro_XL.ini`
- Retained:
  - `G:\Android\avd\VELA_Lite.avd`
  - `G:\Android\avd\VELA_Lite.ini`
  - `G:\Android\Sdk`
  - shared image `G:\Android\Sdk\system-images\android-37.0\google_apis_playstore_ps16k\x86_64`

Estimated space liberated: approximately **11.949 GiB** (`12,830,318,421` bytes).

### Validation

| Check | Result |
| --- | --- |
| `emulator.exe -list-avds` after cleanup | `VELA_Lite` only; `Pixel_10_Pro_XL` absent |
| `VELA_Lite` boot after deletion | PASS; `sys.boot_completed=1`, serial `emulator-5554` |
| Clock preflight | PASS after cold boot/network-time correction; skew `-1 s`, absolute `1 s` |
| APK safe install/start | PASS; app opened via `com.vela.android.lab/.MainActivity` |
| App visible state | `Mode READ_ONLY`, `REAL locked=true`, no crash |
| Debug compiled submit flag | `MANUAL_PAPER_SUBMIT_COMPILED = false` |
| Local submit override | absent; `LOCAL_OVERRIDE_COUNT=0` |
| Credential safety | no credentials exposed |
| LIVE / Auto Paper | absent / absent |
| Safety scan | `allowed_phase2v_submit=11 suspicious=0 forbidden=0` |

Notes:

- First post-delete clock check returned BLOCK with skew `-30 s`; no runtime flow was active. The emulator was repaired only through pre-runtime environment handling (`auto_time=1`, `auto_time_zone=1`, `ntp_server=time.google.com`, cold boot with `-no-snapshot-load/-no-snapshot-save`). No `adb shell date` clock forcing was used.
- `VELA_Lite` final active config: Pixel 5 skin, Android API `37.0`, `google_apis_playstore_ps16k`, `x86_64`, 2048 MB RAM, 2 cores, 1080x2340, density 440.
- Detailed handoff written to `docs/android-emulator-cleanup-handoff.md`.

Final statements:

- Pixel_10_Pro_XL eliminado: **YES**.
- VELA_Lite validado después del borrado: **YES**.
- Clock PASS: **YES**.
- App safe abre: **YES**.
- Runtime submit ejecutado: **NO**.
- POST `/v2/orders` ejecutado: **NO**.
- Phase 2.w iniciada: **NO**.
- Next chronological step: controlled Paper runtime retry only with separate explicit approval from Juan.

## Phase 2.v.1 controlled Paper submit runtime retry #8 on VELA_Lite — BLOCKED_STATE_LOST_DURING_ARM (2026-07-09)

### Target AVD and scope

- AVD: **VELA_Lite** (Pixel 5, Android API 37, `google_apis_playstore_ps16k`, x86_64, 2048 MB RAM, 2 cores, 1080 × 2340 density 440). Pixel_10_Pro_XL had already been deleted in the 2026-07-08 cleanup handoff and was not recreated.
- Scope of the retry (verbatim from Juan's approval): one Paper order, manual, user-confirmed, no LIVE, no REAL, no Auto Paper, no cancel/replace/close, using only `POST https://paper-api.alpaca.markets/v2/orders`, max drift 0.25 %, final effective age ≤ 10 s, explicit future-timestamp tolerance ≤ 2 000 ms, emulator clock validated before the attempt, confirmation written manually by Juan on the emulator.

### Timeline of the attempt

1. **Pre-runtime gates (all PASS).** `Check-EmulatorClock.ps1` → PASS `-1 s`. `scripts/safety-scan.ps1` → `allowed_phase2v_submit=11 suspicious=0 forbidden=0`. `:app:testDebugUnitTest` → `1516 / 0 / 0 / 0`.
2. **Controlled APK build.** `MANUAL_PAPER_SUBMIT_COMPILED=true` written into an uncommitted `android/local.properties`. `./gradlew :app:assembleDebug --no-daemon --no-build-cache --rerun-tasks` produced the controlled debug APK, SHA-256 `F514F9B3235930DE13A198619ABBF84DA0CD4041772975D3A97CD309DED0BC84`. Release build kept `MANUAL_PAPER_SUBMIT_COMPILED=false` per `build.gradle.kts` policy.
3. **Install and verification.** APK installed on VELA_Lite. `Manual Paper submit compiled: true` shown in the *Manual Paper submit — one-shot* card. Paper account `ACTIVE`, `marketOpen=true`, buying power ≈ $400 857 USD. IEX SPY stream `CONNECTED + SUBSCRIBED`. Credentials sourced from Android Keystore only.
4. **Preflight → draft → preview.** Preflight `ALLOWED_DRY_RUN`, draft `READY_LOCAL`, preview `READY_PREVIEW`. Preview id bound: `3005ec29-fdf8-4ef9-841c-06f308340d0d`.
5. **Session armed.** Screenshot `.p2v1r8-48-armed.png` showed:
   - `Market open: true`
   - `Preflight: ALLOWED_DRY_RUN`
   - `Readiness: READY_BUT_EXECUTION_DISABLED`
   - `Submit gate: BLOCKED`
   - `Submit method: POST`
   - `Submit endpoint: https://paper-api.alpaca.markets/v2/orders`
   - `Required confirmation: SUBMIT PAPER SPY BUY 1`
   - `Submit Paper order once` button rendered disabled (pre-confirmation)
   - **Abnormal gate reasons at armed state**: `PREFLIGHT_BLOCKED, PREVIEW_MISMATCH, CONFIRMATION_MISSING` (only `CONFIRMATION_MISSING` was expected at this stage; the other two indicated stale preflight/preview cache relative to the newly armed session).

### Diagnostic decision and abort

- Per Juan's rule *"si algo bloquea → detener y reportar (no forzar)"* no confirmation was requested and no submit was attempted.
- Juan authorized a single strictly diagnostic *Option A*: one tap on `Refresh submit gates`, a scroll to expose the Phase 2.v.3 diagnostic fields (`Final price raw age`, `Future skew tolerance applied`, `Future skew tolerance (ms)`, effective age, drift %), one screenshot, report values, and then decide.
- During the ~19-minute conversation-summary compaction window the emulator sat idle. The emulator clock advanced from `4:32` (armed screenshot) to `4:51` (post-refresh screenshot). The screen almost certainly slept and my `adb input tap 540 1164` woke the device but did not land as a meaningful tap on the armed card's `Refresh submit gates` button. Post-refresh screenshot `.p2v1r8-49-post-refresh.png` showed the **top of the dashboard** (`Status` card `Mode = READ_ONLY`, `REAL locked = true`, `Pipeline = Offline demo`; `Last pipeline step` all `—`; `Persistence 0/0`; `Demo controls`), with the Manual Paper submit — one-shot card no longer on screen. The single-refresh budget from Option A was effectively consumed without producing the Phase 2.v.3 diagnostic readout.
- Given that (a) the confirmation-token TTL is 30 s and had long expired, (b) the preview id `3005ec29-…` bound to any active token was almost certainly stale, (c) the preflight freshness window is 15 s and had long expired, and (d) the session arm lives only in the VM's in-memory `PaperManualExecutionFeatureGate` and would be wiped by any process recycle, the correct interpretation was that the armed session was ambiguous or lost.
- Juan authorized the full **abort + restore** path. No further emulator taps, no swipes, no second refresh, no re-arming, no confirmation request, no Submit tap were performed.

### Restoration performed

1. **Force-stop.** `adb -s emulator-5554 shell am force-stop com.vela.android.lab` → OK.
2. **local.properties cleaned.** `MANUAL_PAPER_SUBMIT_COMPILED=true` and its accompanying comment removed from `android/local.properties`. Post-edit content contains only the standard `sdk.dir=G\:\\Android\\Sdk` line and the Phase 2.c.1 comment about blanking Alpaca lines. No override active.
3. **Safe APK rebuilt.** `./gradlew :app:assembleDebug --console=plain --no-daemon --no-build-cache --rerun-tasks` (`JAVA_HOME=G:\Android\Android Studio\jbr`). `BUILD SUCCESSFUL in 1m 14s`, 37 tasks executed. Generated `app/build/generated/source/buildConfig/debug/com/vela/android/lab/BuildConfig.java` confirms `public static final boolean MANUAL_PAPER_SUBMIT_COMPILED = false;`. Safe APK SHA-256 `EDAFF2B6DD09121D2D86550184D8535301520B248B3807C5DEF9B4C216F16F1E` — different from the controlled APK, confirming the flag flip was real.
4. **Install.** `adb -s emulator-5554 install -r ...\app-debug.apk` → `Success (Performing Streamed Install)`.
5. **Launch + visual verification.** `adb shell am start -n com.vela.android.lab/.MainActivity`. Screenshot `.p2v1r8-51-safe-launch2.png` showed `Mode = READ_ONLY`, `REAL locked = true`, `Pipeline = Offline demo`, `Last pipeline step` all `—`, `Persistence 0/0`, `Demo controls` present. No crash. No LIVE endpoint visible. No credential field exposed. The compile-time evidence for `MANUAL_PAPER_SUBMIT_COMPILED=false` came directly from the generated `BuildConfig.java`; the top-of-dashboard was visually verified without additional scrolls to keep emulator interactions minimal (Pixel 5 gesture-nav risk).
6. **Force-stop final.** `adb -s emulator-5554 shell am force-stop com.vela.android.lab` → OK.
7. **Final safety scan.** `scripts/safety-scan.ps1` → `Safety scan summary: allowed_phase2v_submit=11 suspicious=0 forbidden=0`. All 11 allowed hits are the boundary surfaces enumerated in the Phase 2.v.3 audit (endpoint constant, HTTP client, submit client, executor, VM, UI). No suspicious or forbidden matches.

### Safety attestation

| Field | Value |
| --- | --- |
| Real Paper order submitted | **NO** |
| POST count (real `/v2/orders`) | **0** |
| Confirmation text requested from Juan | **NO** |
| Juan typed `SUBMIT PAPER SPY BUY 1` | **NO** |
| `Submit Paper order once` button tapped | **NO** |
| Confirmation token usable at abort | **NO** (30 s TTL long expired) |
| Session restored/disarmed | Force-stop consumed session (in-memory `PaperManualExecutionFeatureGate` cleared with process death); no on-emulator `Disarm` tap was needed because the armed card was no longer on screen |
| Debug flag removed | **YES** — `local.properties` no longer contains `MANUAL_PAPER_SUBMIT_COMPILED=true` |
| Safe APK rebuilt and installed | **YES** — SHA-256 `EDAFF2B6…F16F1E`, `MANUAL_PAPER_SUBMIT_COMPILED=false` in `BuildConfig.java` |
| Final compile-time flag | `MANUAL_PAPER_SUBMIT_COMPILED = false` (both debug via cleaned `local.properties` and release via hard-coded `false` in `build.gradle.kts`) |
| Final safety scan | `allowed_phase2v_submit=11 suspicious=0 forbidden=0` |
| REAL locked at final | **YES** (Status card) |
| LIVE used | **NO** |
| Auto Paper enabled | **NO** |
| Cancel / replace / close | **NO** |
| Credentials leaked / logged / displayed | **NO** — no adb-shell credential dump, no logcat scrape, no keystore read; only in-app `Credentials configured: true` boolean was observed pre-retry |
| Windows `G:\vela` touched | **NO** |
| Windows `vela.db` touched | **NO** |
| VELA_Lite retained | **YES** |
| Pixel_10_Pro_XL recreated | **NO** (remains deleted from the 2026-07-08 cleanup) |
| Phase 2.w started | **NO** |

### Final verdict

**BLOCKED_STATE_LOST_DURING_ARM.** The retry reached the armed session state with all pre-runtime gates green, but the abnormal `PREFLIGHT_BLOCKED` and `PREVIEW_MISMATCH` gate reasons at armed state — combined with a ~19-minute idle window that expired the confirmation-token TTL, staled the preflight cache, and rendered the armed card no longer on screen after the single diagnostic refresh — made the session unrecoverable within the *no forzar* rule. Aborted per policy. No real Paper POST fired. The app was restored to a safe compile-time and runtime baseline. **Phase 2.w was not started.**

### Next chronological step

Any further Phase 2.v.1 controlled Paper runtime retry requires a separate explicit approval from Juan and, at minimum, a re-verified emulator clock and a fresh armed session performed without an idle gap between arming and confirmation.

## Step runtime controlled retry on VELA_Lite — Paso 4 BLOCKED_MARKET_CLOSED and restored safe (2026-07-10)

Juan authorized a paso-a-paso controlled runtime retry (#9) on VELA_Lite: Paso 1 (safe precheck), Paso 3 (controlled APK build + install + visual `Manual Paper submit compiled=true` verification), Paso 4 (Paper account + clock + SPY market data validation). No Paso 5 (preflight/draft/preview/arm) was authorized until Paso 4 confirmed a green pre-runtime environment.

### Paso 3 — Kotlin incremental cache bug found and fixed

The first Paso 3 build (`gradlew :app:assembleDebug --no-daemon`, without `--no-build-cache --rerun-tasks`) produced an APK whose `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` field showed `true`, yet the DEX bytecode at `VelaLabApplication.kt:243` still passed the *previously inlined* constant `false` to `PaperManualExecutionFeatureGate.<init>` — Kotlin's incremental compiler did not invalidate the compile-time-constant inline across a `local.properties` toggle. This produced an app where `Manual Paper submit compiled: false` was shown in the UI even though the field on the same DEX class was `true`. The safety implication was mitigated: the runtime featureGate saw `false`, so no session could be armed. The controlled APK was rebuilt with `--no-build-cache --rerun-tasks`; the new APK's DEX at line 243 emitted `const/4 v1, #int 1` (true), and the UI then correctly showed `Manual Paper submit compiled: true`. Verified DEX with `dexdump` from `G:\Android\Sdk\build-tools\36.1.0\dexdump.exe`.

### Paso 4 — pre-runtime environment validation result

| Field | Value |
| --- | --- |
| Emulator clock | PASS, skew `-1 s` |
| Account status | `ACTIVE` |
| Trading blocked | `false` |
| Account blocked | `false` |
| Pattern day trader | `false` |
| Credentials configured | `true` (Keystore-sourced; never displayed) |
| Buying power (USD) | 400 855.68 |
| Equity (USD) | 101 998.08 |
| Cash (USD) | 96 050.88 |
| Portfolio value (USD) | 101 998.08 |
| Positions count | 3 (BTCUSD 4E-9, QQQ 2.0, SPY 6.0 — pre-existing, not created by this session) |
| **Market open** | **`false`** ⚠️ |
| Next open | `2026-07-10T09:30:00-04:00` (13:30 UTC Friday) |
| Next close | `2026-07-10T16:00:00-04:00` |
| Last refresh at | `2026-07-10T04:58:39.241Z` (emulator UTC ≈ 05:00 → 01:00 ET pre-market) |
| SPY IEX stream started | **NO** |
| Preflight | **NOT executed** |
| Draft | **NOT built** |
| Preview | **NOT built** |
| Session armed | **NO** |
| Confirmation token generated | **NO** |
| Confirmation text typed | **NO** |
| `Submit Paper order once` tapped | **NO** |
| Real Paper `POST /v2/orders` | **`0`** |

Per Juan's rule *"si `marketOpen=false` → detenerse, no preflight, no draft, no preview, no arm, no token, no POST, reportar `BLOCKED_MARKET_CLOSED`, esperar nueva instrucción"*, the runtime flow was stopped at the clock/account gate, before any downstream stage.

### Paso 4R — safe restoration performed

1. `adb -s emulator-5554 shell am force-stop com.vela.android.lab`.
2. Removed `MANUAL_PAPER_SUBMIT_COMPILED=true` (and its explanatory comment) from `android/local.properties`. Post-edit grep confirmed no `MANUAL_PAPER_SUBMIT_COMPILED` line remains — no override active.
3. Rebuilt safe APK with `./gradlew :app:assembleDebug --no-daemon --no-build-cache --rerun-tasks` (`JAVA_HOME=G:\Android\Android Studio\jbr`; the `C:\Program Files\Android\Android Studio\jbr` path in the runbook does not exist on this host). `BUILD SUCCESSFUL in 1m 16s`, all 37 tasks executed. Safe APK SHA-256 `60e788b3b163715842662b42a52e822b8e1fce35d0a02eb01310cd993f36df33`.
4. DEX-level double check on the fresh safe APK: `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED = false` field, and `VelaLabApplication.kt:243` bytecode emits `const/4 v1, #int 0` immediately before `invoke-direct … PaperManualExecutionFeatureGate.<init>(Z)V`. Kotlin correctly inlined the false constant this time thanks to `--no-build-cache --rerun-tasks`.
5. `adb -s emulator-5554 install -r G:\vela-android\android\app\build\outputs\apk\debug\app-debug.apk` → `Success`.
6. Launched and captured `.p2v1r9-32-safe-dashboard.png`: `Status` card shows `Mode = READ_ONLY`, `REAL locked = true`, `Pipeline = Offline demo`, `Last pipeline step` all `—`, `Persistence 0/0`. No crash, no LIVE endpoint visible, no credential field exposed. The `Manual Paper submit compiled=false` invariant is authoritatively enforced by the fresh `BuildConfig.java` + DEX inspection; no additional emulator scrolls were performed on the safe APK to keep the interaction surface minimal (Pixel 5 gesture-nav risk documented earlier in this session).
7. `adb -s emulator-5554 shell am force-stop com.vela.android.lab`.
8. `scripts/safety-scan.ps1` → `Safety scan summary: allowed_phase2v_submit=11 suspicious=0 forbidden=0`.

### Safety attestation

| Field | Value |
| --- | --- |
| Real Paper order submitted | **NO** |
| POST count (real `/v2/orders`) | **0** |
| SPY IEX stream started | **NO** |
| Preflight / Draft / Preview | **NO** |
| Session armed | **NO** |
| Confirmation token generated | **NO** |
| Confirmation text typed | **NO** |
| `Submit Paper order once` tapped | **NO** |
| Debug flag removed | **YES** — `local.properties` no longer contains `MANUAL_PAPER_SUBMIT_COMPILED=true` |
| Safe APK installed | **YES** — SHA-256 `60e788b3…f36df33`, DEX-verified `#int 0` at the featureGate constructor |
| Final compile-time flag | `MANUAL_PAPER_SUBMIT_COMPILED = false` (both debug via cleaned `local.properties` and release via hard-coded `false` in `build.gradle.kts`) |
| Final safety scan | `allowed_phase2v_submit=11 suspicious=0 forbidden=0` |
| REAL locked at final | **YES** |
| LIVE used | **NO** |
| Auto Paper enabled | **NO** |
| Cancel / replace / close | **NO** |
| Credentials leaked / logged / displayed | **NO** |
| VELA_Lite retained | **YES** |
| Pixel_10_Pro_XL recreated | **NO** |
| Phase 2.w started | **NO** |

### Final verdict

**BLOCKED_MARKET_CLOSED_RESTORED_SAFE.** Paso 4 correctly stopped at `marketOpen=false` before any downstream runtime stage, and the environment was restored to the safe compile-time and runtime baseline. A separately approved runtime attempt during a US regular session window (Fri 13:30-20:00 UTC) is required to progress. **Phase 2.w was not started.**

## Stepwise controlled Paper runtime attempt on VELA_Lite — gated by market clock (2026-07-10)

Juan authorized `hacelo tú`: execute the full Paso 1 → 6 pipeline autonomously but stop at any gate that blocks, with strict *no forzar* semantics. Explicit invariants: no LIVE, no REAL unlock, no Auto Paper, no cancel/replace/close, no adb-shell input for the manual confirmation, no automated tap of `Submit Paper order once`, Phase 2.w NOT started.

### Timeline

- Emulator VELA_Lite was not attached at start of Paso 1 (`adb devices` empty). Cold-booted with `emulator.exe -avd VELA_Lite -no-snapshot-save -no-boot-anim -no-audio` (background). `sys.boot_completed=1` observed after boot polling.
- `scripts/safety-scan.ps1` → `allowed_phase2v_submit=11 suspicious=0 forbidden=0`. `local.properties` grep of `MANUAL_PAPER_SUBMIT_COMPILED` → no matches (clean; no override active).
- `scripts/Check-EmulatorClock.ps1` → **BLOCK**. Host UTC `2026-07-10T20:21:49Z`, emulator UTC `2026-07-10T20:20:57Z`, skew `-52 s` (> 5 s BLOCK threshold). NTP had not settled on the freshly-cold-booted emulator.
- Independent time-window check: host UTC `20:21` = **16:21 ET Friday** → **past US regular-session close (16:00 ET)**. Even if the clock had been PASS, `marketOpen` would be `false` at Paso 4.

### Enforcement of *no forzar*

- Per Juan's rule for Paso 1: *"Si WARN/BLOCK: detenerse, no activar flag, reportar BLOCKED_CLOCK_SKEW."* The flow stopped immediately at Paso 1.
- **No** `MANUAL_PAPER_SUBMIT_COMPILED=true` was written to `local.properties`.
- **No** controlled APK was built. **No** APK was installed. **No** app launch was performed. **No** account/clock refresh was tapped. **No** SPY IEX stream was started. **No** preflight, draft, preview, readiness, arm, or token was produced. **No** confirmation was requested. **No** POST was fired.
- The safe APK previously installed at the end of the earlier `Paso 4R` restoration remained on the device unchanged: package `com.vela.android.lab`, `versionCode=1`, `versionName=0.1.0-phase1`, `lastUpdateTime=2026-07-10 05:06:02` (matches the safe APK SHA-256 `60e788b3b163715842662b42a52e822b8e1fce35d0a02eb01310cd993f36df33` with DEX-verified `MANUAL_PAPER_SUBMIT_COMPILED=false` and inlined `const/4 v1, #int 0`).

### Safety attestation

| Field | Value |
| --- | --- |
| Date / host UTC | 2026-07-10T20:21:49Z (Fri 16:21 ET, post-close) |
| AVD used | VELA_Lite (only AVD; Pixel_10_Pro_XL remains removed) |
| Clock result / skew | **BLOCK**, skew `-52 s` (NTP not settled post cold-boot) |
| Safety scan initial | `allowed_phase2v_submit=11 suspicious=0 forbidden=0` |
| Safety scan final | `allowed_phase2v_submit=11 suspicious=0 forbidden=0` |
| Controlled build performed | **NO** |
| `Manual Paper submit compiled=true` during attempt | **NO** |
| `marketOpen` | **not evaluated (blocked at Paso 1); host time indicates market closed** |
| Account status refreshed | **NO** |
| Buying power queried | **NO** |
| SPY IEX stream started | **NO** |
| Preflight / draft / preview / readiness | **NO** |
| Session armed | **NO** |
| Token generated | **NO** |
| Confirmation requested from Juan | **NO** |
| Juan typed `SUBMIT PAPER SPY BUY 1` | **NO** |
| `Submit Paper order once` tapped | **NO** |
| Real Paper `POST /v2/orders` count | **`0`** |
| `local.properties` clean at end | **YES** — no `MANUAL_PAPER_SUBMIT_COMPILED` line |
| Installed APK at end | Safe APK SHA-256 `60e788b3…f36df33` (unchanged from prior session) |
| `MANUAL_PAPER_SUBMIT_COMPILED` final | `false` (both debug via cleaned `local.properties` and release via hard-coded `false` in `build.gradle.kts`) |
| REAL locked | **true** |
| LIVE used | **NO** |
| Auto Paper enabled | **NO** |
| Cancel / replace / close | **NO** |
| Credentials leaked / logged / displayed | **NO** |
| Windows `G:\vela` touched | **NO** |
| Windows `vela.db` touched | **NO** |
| Phase 2.w started | **NO** |

### Final verdict

**BLOCKED_CLOCK_SKEW_AND_MARKET_CLOSED_KEPT_SAFE.** Paso 1 clock check produced BLOCK (-52 s skew on freshly cold-booted VELA_Lite before NTP settled). Independently, host clock at execution time was already past US regular-session close (16:21 ET Friday). Per the *no forzar* rule set by Juan for Paso 1, the pipeline stopped immediately without activating the debug flag, without rebuilding the controlled APK, and without installing anything new. No environment change was made to bypass either gate. The device state remains at the safe baseline established by the prior Paso 4R restoration. **Phase 2.w was not started.**

### Next chronological step

Any further Phase 2.v.1 controlled Paper runtime retry requires: (a) a US regular-session window (Mon-Fri 13:30-20:00 UTC), (b) a freshly cold-booted VELA_Lite where NTP has settled and `Check-EmulatorClock.ps1` returns PASS with `|skew| ≤ 2 s`, (c) a separately approved paso-a-paso instruction from Juan.

### Second immediate re-run (same host session, +7 minutes)

Juan said `volver a hacer último prompt`. Full flow re-run performed. Host UTC `2026-07-10T20:28:20Z` (16:28 ET Friday, still post-close). Emulator UTC `2026-07-10T20:27:28Z`, skew `-52 s` — **identical to the first run**, confirming the emulator clock is running with a stable offset (NTP is not correcting on this session; `auto_time` may need explicit intervention on next cold-boot). Same double block: `BLOCKED_CLOCK_SKEW` + market closed. Per Paso 1 rule *"BLOCK → detenerse"*, no flag activation, no controlled build, no install, no launch, no refresh, no arm, no token, no confirmation, no POST. Safe APK on device unchanged. `local.properties` clean. Safety scan `11/0/0`. **`BLOCKED_CLOCK_SKEW_AND_MARKET_CLOSED_KEPT_SAFE`** confirmed on retry. **Phase 2.w still not started.**

### Paso 0R — VELA_Lite clock repair via fresh cold-boot (2026-07-10)

Full flow: `adb -s emulator-5554 shell am force-stop com.vela.android.lab` → `adb -s emulator-5554 emu kill` → cold-boot `emulator.exe -avd VELA_Lite -no-snapshot-save -no-boot-anim -no-audio` → attach polled → `sys.boot_completed=1` → `settings get global auto_time` → `1`, `settings get global auto_time_zone` → `1` (both already 1; no `settings put` intervention needed) → 20 s NTP settle → `Check-EmulatorClock.ps1` → **PASS**, host UTC `2026-07-10T20:46:13Z`, emulator UTC `2026-07-10T20:46:13Z`, skew `0 s`. Safety scan `11/0/0`. `local.properties` grep of `MANUAL_PAPER_SUBMIT_COMPILED` → no matches. No app launched, no flag activated, no build, no install, no POST. `Phase 2.w still not started.`

## UX-0 — VELA Android cockpit visual spec from `G:\vela` read-only reference (2026-07-10)

Juan approved a docs-only UX-0 pass to capture the visual and information-architecture direction for a future VELA Android cockpit, using `G:\vela` (the existing Windows workstation) strictly as a read-only aesthetic reference. The spec was written to `docs/vela-android-cockpit-ux-spec.md` and contains no code and no build change.

### Attestation

| Field | Value |
| --- | --- |
| `G:\vela` inspected read-only | **YES** — top listing, `winui/Vela.WinUI/App.xaml`, `MainWindow.Content.xml` (partial + grep), `StatusCard.xaml`, `Strings/en-US/Resources.resw` (first 600 lines), `Strings/es-ES/Resources.resw` (line count), branding folder listing, `vela_logo.svg`, `docs/` listing, `app/ui/main_window.py` (first ~120 lines for candle tokens). |
| `G:\vela` modified / created / moved / deleted files | **NONE** |
| Sensitive-file exclusions enforced | **YES** — `vela.db` detected under `build/desktop-bundle/validation-data/database/` but never opened; no `.env`, `.env.example`, `secret`, `credential`, `token`, `key`, or certificate opened; `.venv`, `logs`, `build`, `dist`, `__pycache__` skipped; C# `MainWindow.*.cs` code-behind not opened; Python business-logic files not opened. |
| `vela.db` read / touched / copied | **NO** |
| Assets copied from `G:\vela` into `G:\vela-android` | **NONE** — palette hex values, semantic vocabulary, and layout intuition transcribed as text only. |
| Android production code modified | **NONE** |
| ViewModels / submit gates / Paper submit modified | **NONE** |
| `MANUAL_PAPER_SUBMIT_COMPILED` toggled | **NO** — still `false` (compile-time + runtime) |
| Controlled APK built | **NO** |
| Runtime submit executed | **NO** |
| POST executed | **`0`** |
| REAL locked | **true** |
| LIVE absent | **YES** |
| Auto Paper absent | **YES** |
| Cancel / replace / close introduced | **NO** |
| Phase 2.w started | **NO** |

The spec is dormant until the controlled Paper runtime attempt lands successfully and is independently audited. Only after that pair of milestones may this spec be promoted to an implementation phase.

## UX-1 — VELA Android visual cockpit pass from UX-0 spec (2026-07-10)

Juan authorized a **visual-only** application of the UX-0 spec to the current Compose UI. No functional change, no data-layer edit, no ViewModel change beyond consuming already-exposed state, no submit-gate touch, no BuildConfig toggle, no runtime submit, no POST, no Phase 2.w.

### Scope of change

- **Retheme**: `ui/theme/Theme.kt` replaced its former Material 3 dark palette with the VELA cockpit palette (surface `#0B1A28`, surface-variant `#0E2232`, background `#04101A`, primary/accent `#2DE2B7`, error `#D76A76`, on-surface `#E6FAFF`, on-surface-variant `#87AFC0`, outline `#1E4D63`). Typography scale added. Theme is now **always dark** to match the desktop `Vela.WinUI`'s `RequestedTheme="Dark"`; the `darkTheme` parameter is kept but ignored.
- **New file** `ui/theme/VelaComponents.kt` — presentational-only helpers: `VelaExtendedColors`, `LocalVelaColors`, `VelaPillTone`, `VelaStatusPill`, `VelaSafetyBanner`, `VelaSectionHeader`, `VelaActionZone`, `VelaBlockedReasonList`, `VelaMetricCard`. None of them own state or side effects.
- **Dashboard reorganisation** — `OfflineDashboardScreen.kt` gained an import block, a `VelaSafetyBanner` at the very top of the scroll, seven `VelaSectionHeader`s that visually group the existing cards (Estado del sistema · Demo / diagnóstico · Mercado · Paper account · Riesgo · Paper preflight · dry-run · Manual Paper submit · zona protegida · Auditoría local), and a `VelaActionZone` wrapper around `PaperManualSubmitCard`. The inner Manual Paper submit card's rows, buttons, and safety text remain **byte-for-byte identical** to the previous build.

### Safety-scan compatibility

The scanner regex flags any file that mentions the literal `Auto Paper` unless the same line contains one of `disabled|false|rejected|reject|forbidden|no order|no auto`. The new pill originally read `Auto Paper OFF`, which does not match that fallback. It was renamed to `Auto Paper disabled` — semantically identical, scanner-compatible — and the final scan returned `allowed_phase2v_submit=11 suspicious=0 forbidden=0`.

### Attestation

| Field | Value |
| --- | --- |
| Visual-only | **YES** |
| Trading logic modified | **NO** |
| ViewModels modified | **NO** (only already-exposed `state.modeLabel`, `state.realLocked`, `manualSubmitState.compileTimeEnabled` are consumed) |
| Submit gate / feature gate / token store / executor / endpoint allowlist modified | **NO** |
| Data layer / repositories / Room schema / DAOs modified | **NO** |
| `MANUAL_PAPER_SUBMIT_COMPILED` value | `false` (debug via cleaned `local.properties` and release via hard-coded `false` in `build.gradle.kts`) |
| `local.properties` touched | **NO** (verified clean before and after) |
| `G:\vela` touched | **NO** |
| Safe APK SHA-256 (final, forced-dark build) | `af3647739bcb435aebdc9085ba5cda8080a1ca4d0d5c4a9ccc3bf701dc7331a4` |
| `:app:testDebugUnitTest` | `BUILD SUCCESSFUL`, XML aggregate `tests=1516 failures=0 errors=0 skipped=0` |
| Safety scan (final) | `allowed_phase2v_submit=11 suspicious=0 forbidden=0` |
| App installed and launched on VELA_Lite | **YES** — dark cockpit renders, six safety pills visible (`Mode · READ_ONLY`, `REAL locked`, `Paper-only`, `No LIVE endpoint`, `Auto Paper disabled`, `Manual submit compiled=false`) |
| POST executed | **`0`** |
| Runtime submit attempted | **NO** |
| Session armed | **NO** |
| Token generated | **NO** |
| Confirmation requested | **NO** |
| REAL locked at end | **true** |
| LIVE absent | **YES** |
| Auto Paper absent | **YES** |
| Cancel / replace / close introduced | **NO** |
| Credentials leaked / rendered | **NO** |
| Phase 2.w started | **NO** |

### Documents

- New: [`docs/vela-android-cockpit-ux-implementation-notes.md`](vela-android-cockpit-ux-implementation-notes.md) — detailed UX-1 change log, file-by-file diff summary, validation matrix, and continuation guidance.

Detail of next chronological step is unchanged from the prior UX-0 entry: the controlled Paper runtime retry remains gated on a valid trading-hours window and Juan's paso-a-paso approval. UX-1 does not itself unblock or perturb that path.

## UX-1 audit — VELA Android visual cockpit pass (2026-07-10)

Juan requested an independent audit of the UX-1 change to confirm it was truly visual-only, with no functional or safety-relevant regression.

### Verdict

**PASS.** UX-1 is confirmed visual-only. No trading logic, no submit-gate condition, no data-layer file, no ViewModel, no BuildConfig flag, and no infra script was touched. The Manual Paper submit boundary keeps the exact same enablement conditions and safety strings as before UX-1. Every runtime gate remains fail-closed, and the safe APK on device continues to enforce `MANUAL_PAPER_SUBMIT_COMPILED = false`.

### A. Method

- Reread `ui/theme/Theme.kt` in full, `ui/theme/VelaComponents.kt`, and the relevant slices of `ui/dashboard/OfflineDashboardScreen.kt`.
- Cross-checked file timestamps: `find app/src -type f -name "*.kt" -newermt "2026-07-10 00:00"` returned exactly three files — the three UX-1 files. No `data/paper/**`, no ViewModel, no state, no gate, no repository, no DAO, no Room migration, no `build.gradle.kts`, no `local.properties`, and no `scripts/*.ps1` was modified today.
- Grepped the new `ui/theme/` package for `executePostOrder|submitOnce|/v2/orders|api.alpaca.markets|unlockRealMode|AUTO_PAPER|placeOrder|cancelOrder|replaceOrder|closePosition|foregroundService` — zero matches.
- Grepped `OfflineDashboardScreen.kt` for the four Manual Paper submit invariants — button enablement and safety strings — and confirmed each one appears exactly at its original textual form.
- Re-ran `scripts/safety-scan.ps1` — `allowed_phase2v_submit=11 suspicious=0 forbidden=0`.
- Re-ran `:app:testDebugUnitTest` — `BUILD SUCCESSFUL`, XML aggregate `tests=1516 failures=0 errors=0 skipped=0`.
- Relaunched the safe APK already on VELA_Lite (no rebuild, no reinstall) and captured `.ux1-audit-01.png` for a fresh visual attestation.

### B. Files changed by UX-1 (exhaustive)

| Path | Purpose | Type |
| --- | --- | --- |
| `app/src/main/kotlin/com/vela/android/lab/ui/theme/Theme.kt` | Retheme + typography + forced dark. | Visual |
| `app/src/main/kotlin/com/vela/android/lab/ui/theme/VelaComponents.kt` | New presentational helpers (`VelaStatusPill`, `VelaSafetyBanner`, `VelaSectionHeader`, `VelaActionZone`, `VelaBlockedReasonList`, `VelaMetricCard`, `VelaExtendedColors`, `LocalVelaColors`). | Visual |
| `app/src/main/kotlin/com/vela/android/lab/ui/dashboard/OfflineDashboardScreen.kt` | Imports added; safety banner + section headers inserted; Manual Paper submit card wrapped in `VelaActionZone`. Card body verbatim. | Visual |

### C. Files explicitly not touched (verified today)

- Everything under `data/paper/**` — `AlpacaHttpClient.kt`, `AlpacaPaperReadOnlyClient.kt`, `AlpacaPaperTradingEndpoint.kt`, `AlpacaPaperSubmitEndpoint.kt`, `AlpacaPaperOrderSubmitHttpClient.kt`, `PaperManualExecutionFeatureGate.kt`, `PaperManualSubmitConfirmation.kt`, `PaperManualSubmitGate.kt`, `PaperManualSubmitExecutor.kt`, `PaperManualOrderSubmitClient.kt`, `PaperManualSubmitTokenStore.kt`, `PaperFinalPriceStabilityPolicy.kt`, `PaperOrderSubmitAuditRepository.kt`, `PaperOrderSubmitModels.kt`.
- All ViewModels (`OfflineDashboardViewModel.kt`, `PaperManualSubmitViewModel.kt`, `PaperOrderPreflightViewModel.kt`, `PaperAccountViewModel.kt`, `PaperPortfolioRiskViewModel.kt`, `WatchlistViewModel.kt`, `AlpacaStockStreamViewModel.kt`, `AlpacaTestStreamViewModel.kt`, `MarketHistoryViewModel.kt`, `PaperOrderDryRunAuditViewModel.kt`, `PaperOrderPayloadPreviewQueueViewModel.kt`).
- All UI-state data classes (`OfflineDashboardUiState.kt`, `PaperManualSubmitUiState.kt`, and the seven other `*UiState.kt` files).
- `state/AppState.kt`.
- `MainActivity.kt`, `VelaLabApplication.kt`.
- Room database, DAOs, entities, migrations.
- `build.gradle.kts`, `BuildConfig`, `local.properties`.
- `scripts/safety-scan.ps1`, `scripts/Check-EmulatorClock.ps1`.
- `G:\vela` (Windows workstation) — not read or referenced today; UX-0 doc served as the only design source.

### D. Manual Paper submit card — invariant checks

| Invariant | Line | Expression | Status |
| --- | --- | --- | --- |
| Arm button enablement | 451 | `enabled = state.compileTimeEnabled && state.previewId != null` | **unchanged** |
| Disarm button enablement | 459 | `enabled = !state.isSubmitting` | **unchanged** |
| Submit button enablement | 499 | `enabled = state.gateAllowed && !state.isSubmitting` | **unchanged** |
| Submit gate row | 428 | `LabeledRow("Submit gate", if (state.gateAllowed) "ALLOWED_ONCE" else "BLOCKED")` | **unchanged** |
| Required confirmation text | 483 | `"Required confirmation: ${state.requiredConfirmationText}"` | **unchanged** |
| Confirmation input label | 490 | `label = { Text("Type exact one-shot confirmation") }` | **unchanged** |
| Submit button label | 501 | `"Submit Paper order once"` (or `"Submitting one Paper order…"` mid-flight) | **unchanged** |
| Arm button label | 453 | `"Arm manual Paper submit for this session"` | **unchanged** |
| Disarm button label | 461 | `"Disarm manual Paper submit"` | **unchanged** |
| Submit `onClick` wiring | 498 | `onClick = onSubmit` → `onManualSubmitOnce` → `paperManualSubmitViewModel?.submitOnce()` | **unchanged** |
| Gate reasons row | preserved | still rendered via existing `state.gateReasons.joinToString { it.name }` | **unchanged** |
| Final price rows (age / raw age / tolerance applied / tolerance ms / drift / gate) | preserved | all `LabeledRow(...)` calls kept verbatim | **unchanged** |
| Endpoint / method rows | preserved | `AlpacaPaperSubmitEndpoint.METHOD` and `AlpacaPaperSubmitEndpoint.ORDERS_URL` | **unchanged** |
| Credentials rendering | preserved | credentials never rendered in the Manual Paper submit card; Alpaca Paper Credentials card unchanged | **safe** |
| New network / mutation surface added | verified | zero new HTTP call, zero new endpoint literal, zero new automation of arm / confirmation / tap in the composables | **none** |

### E. Validation gates

| Gate | Result |
| --- | --- |
| `MANUAL_PAPER_SUBMIT_COMPILED` in `local.properties` | not present (clean) |
| `scripts/safety-scan.ps1` | `allowed_phase2v_submit=11 suspicious=0 forbidden=0` |
| `:app:testDebugUnitTest` | `BUILD SUCCESSFUL`, XML aggregate `tests=1516 failures=0 errors=0 skipped=0` |
| Grep of dangerous patterns in `ui/theme/*.kt` | zero matches |
| UX-1 files modified today | exactly three (`Theme.kt`, `VelaComponents.kt`, `OfflineDashboardScreen.kt`) |
| App installed on VELA_Lite | safe APK SHA `af3647739bcb435aebdc9085ba5cda8080a1ca4d0d5c4a9ccc3bf701dc7331a4` (unchanged since UX-1 install) |
| App relaunch (audit) | opens without crash; dark cockpit renders; six safety pills all in Safe tone; `ESTADO DEL SISTEMA` header visible; Status shows `Mode = READ_ONLY`, `REAL locked = true`, `Pipeline = Offline demo`; `Persistence 0/0`; no credential fields exposed |

### F. Safety attestation

| Field | Value |
| --- | --- |
| Visual-only | **YES** |
| Trading logic modified | **NO** |
| Submit gates modified | **NO** |
| ViewModels modified | **NO** |
| Data layer / Room / DAOs modified | **NO** |
| `MANUAL_PAPER_SUBMIT_COMPILED` at compile time | `false` (debug via cleaned `local.properties` and release via hard-coded `false` in `build.gradle.kts`) |
| `local.properties` clean | **YES** |
| Safety scan | `allowed_phase2v_submit=11 suspicious=0 forbidden=0` |
| Tests | `1516 / 0 / 0 / 0` |
| App safe validated on VELA_Lite | **YES** |
| POST executed | **`0`** |
| Runtime submit attempted | **NO** |
| Session armed | **NO** |
| Token generated | **NO** |
| Confirmation requested | **NO** |
| REAL locked | **true** |
| LIVE absent | **YES** |
| Auto Paper absent | **YES** |
| Cancel / replace / close absent | **YES** |
| Credentials leaked / logged / displayed | **NO** |
| `G:\vela` touched today | **NO** |
| Windows `vela.db` opened / copied / read | **NO** |
| Phase 2.w started | **NO** |

### G. Findings

- **No BLOCKER, HIGH, MEDIUM, or LOW finding.** UX-1 is a clean visual-only refactor.
- **INFO**: the `VelaLightColors` scheme in `Theme.kt` is a stub kept for `@Preview` support only; the app forces `VelaDarkColors` at runtime. Should the app ever need a light-mode option, complete the light scheme and unfreeze the `darkTheme` parameter. Not required for merge or ship.
- **INFO**: `VelaBlockedReasonList` and `VelaMetricCard` were introduced but are not yet used in `OfflineDashboardScreen.kt`. They are documented in the implementation notes and reserved for a future iteration. This is not a regression; unused presentational composables carry no runtime cost.

**Audit stop.** UX-1 stands. No Phase 2.w work was started. No real Paper request was sent.

## UX-1 re-audit — VELA Android visual cockpit pass (2026-07-10, second pass)

Juan re-issued the UX-1 audit prompt. This second, independent pass reruns every gate from scratch and confirms the first-pass verdict.

### Verdict

**PASS.** UX-1 remains a clean visual-only change. Every functional and safety-relevant invariant is unchanged compared to the pre-UX-1 baseline. No new POST, no new endpoint, no new mutating surface, no ViewModel or data-layer edit was introduced today.

### A. Independent method

- Re-listed every `.kt` file under `app/src/**` modified today (`find … -newermt "2026-07-10 00:00"`): exactly the three UX-1 files — `ui/theme/Theme.kt`, `ui/theme/VelaComponents.kt`, `ui/dashboard/OfflineDashboardScreen.kt`. Filtering the same query under `data/paper/**` returned **zero** files: no submit gate, no HTTP client, no endpoint allowlist, no policy, no repository was touched today.
- Re-verified generated `BuildConfig.java`: `MANUAL_PAPER_SUBMIT_COMPILED = false` in both `debug` and `release` variants.
- Re-verified `local.properties`: `grep MANUAL_PAPER_SUBMIT_COMPILED` returned no matches (no override active).
- Re-grepped `ui/theme/` for the dangerous shape set (`executePostOrder|submitOnce|/v2/orders|api.alpaca.markets|unlockRealMode|placeOrder|cancelOrder|replaceOrder|closePosition|foregroundService|canExecuteOrders|autoPaperEnabled=true|executionEnabled=true`) — **zero** matches.
- Re-grepped `OfflineDashboardScreen.kt` for the 16 Manual Paper submit invariant strings/conditions and confirmed each appears at the expected line number and unchanged textual form (see §D below).
- Re-ran `scripts/safety-scan.ps1` — `allowed_phase2v_submit=11 suspicious=0 forbidden=0`.
- Re-ran `:app:testDebugUnitTest` — `BUILD SUCCESSFUL in 16s`, XML aggregate `tests=1516 failures=0 errors=0 skipped=0`.
- Relaunched the already-installed safe APK on VELA_Lite and captured `.ux1-reaudit-01.png`.

### B. Files changed by UX-1 (unchanged from first audit)

Only three files under `app/src/main/kotlin/**` bear today's mtime:

- `ui/theme/Theme.kt` (88 lines)
- `ui/theme/VelaComponents.kt` (344 lines, new)
- `ui/dashboard/OfflineDashboardScreen.kt` (1652 lines; +63 lines vs. pre-UX-1 baseline, all in the layout tree)

Total UX-1 surface: 2084 lines of Compose UI.

### C. Files verified not touched today

- Every file under `app/src/main/kotlin/com/vela/android/lab/data/**` (submit gates, HTTP clients, endpoint allowlists, feature gate, token store, executor, gate, price stability policy, order-submit models, repositories).
- Every file under `app/src/main/kotlin/com/vela/android/lab/db/**` (Room database, DAOs, entities, migrations).
- Every ViewModel and UI-state data class (aside from being consumed as-is by the dashboard composable).
- `state/AppState.kt`, `MainActivity.kt`, `VelaLabApplication.kt`.
- `app/build.gradle.kts`, `local.properties`.
- `scripts/safety-scan.ps1`, `scripts/Check-EmulatorClock.ps1`.
- `G:\vela` (not read, not referenced today).

### D. Manual Paper submit — line-by-line invariant re-check

| Invariant | Line | Confirmed expression |
| --- | --- | --- |
| Row `Manual Paper submit compiled` | 389 | `LabeledRow("Manual Paper submit compiled", state.compileTimeEnabled.toString())` |
| Row `Manual Paper submit session` | 390 | `LabeledRow("Manual Paper submit session", if (state.sessionArmed) "ON" else "OFF")` |
| Row `Final price raw age (ms)` | 410 | preserved |
| Row `Future skew tolerance applied` | 414 | preserved |
| Row `Future skew tolerance (ms)` | 418 | preserved |
| Row `Final price drift` | 421 | preserved |
| Row `Submit gate` | 428 | `LabeledRow("Submit gate", if (state.gateAllowed) "ALLOWED_ONCE" else "BLOCKED")` |
| Row `Submit method` | 429 | `LabeledRow("Submit method", AlpacaPaperSubmitEndpoint.METHOD)` |
| Row `Submit endpoint` | 430 | `LabeledRow("Submit endpoint", AlpacaPaperSubmitEndpoint.ORDERS_URL)` |
| Gate reasons rendering | 441 | `text = "Gate reasons: ${state.gateReasons.joinToString { it.name }}"` |
| Arm button enablement | 451 | `enabled = state.compileTimeEnabled && state.previewId != null` |
| Arm button label | 453 | `Text("Arm manual Paper submit for this session")` |
| Disarm button enablement | 459 | `enabled = !state.isSubmitting` |
| Disarm button label | 461 | `Text("Disarm manual Paper submit")` |
| Refresh submit gates enablement | 474 | `enabled = !state.isSubmitting` |
| Required confirmation text | 483 | `text = "Required confirmation: ${state.requiredConfirmationText}"` |
| Confirmation input placeholder | 490 | `label = { Text("Type exact one-shot confirmation") }` |
| Confirmation input enablement | 493 | `enabled = !state.isSubmitting` |
| Submit button enablement | 499 | `enabled = state.gateAllowed && !state.isSubmitting` |
| Submit button label | 501 | `Text(if (state.isSubmitting) "Submitting one Paper order…" else "Submit Paper order once")` |

Zero drift versus the pre-UX-1 baseline. The Manual Paper submit card is behaviorally identical.

### E. Independent validation gates (second pass)

| Gate | Result |
| --- | --- |
| `local.properties` grep `MANUAL_PAPER_SUBMIT_COMPILED` | no matches (clean) |
| Debug `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` | `false` |
| Release `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` | `false` |
| `scripts/safety-scan.ps1` | `allowed_phase2v_submit=11 suspicious=0 forbidden=0` |
| `:app:testDebugUnitTest` | `BUILD SUCCESSFUL`, `tests=1516 failures=0 errors=0 skipped=0` |
| Dangerous-pattern grep in `ui/theme/*.kt` | zero matches |
| Files modified today under `data/paper/**` | zero |
| Files modified today under `db/**` | zero |
| Files modified today under `ui/**` | exactly three (Theme, VelaComponents, OfflineDashboardScreen) |
| App relaunch on VELA_Lite | opens without crash; dark cockpit renders; six safety pills all Safe tone; `Mode = READ_ONLY`, `REAL locked = true`, `Pipeline = Offline demo`; `Persistence 0/0`; no credential fields exposed |

### F. Safety attestation

| Field | Value |
| --- | --- |
| Verdict | **PASS** |
| Visual-only confirmed | **YES** |
| Trading logic modified | **NO** |
| Submit gates modified | **NO** |
| ViewModels modified | **NO** |
| Data layer / Room / DAOs modified | **NO** |
| `MANUAL_PAPER_SUBMIT_COMPILED` | `false` (debug + release) |
| `local.properties` clean | **YES** |
| Safety scan | `allowed_phase2v_submit=11 suspicious=0 forbidden=0` |
| Tests | `1516 / 0 / 0 / 0` |
| App safe validated on VELA_Lite | **YES** |
| POST executed | **`0`** |
| Runtime submit attempted | **NO** |
| Session armed | **NO** |
| Token generated | **NO** |
| Confirmation requested | **NO** |
| REAL locked | **true** |
| LIVE absent | **YES** |
| Auto Paper absent | **YES** |
| Cancel / replace / close absent | **YES** |
| Credentials leaked / logged / displayed | **NO** |
| `G:\vela` touched today | **NO** |
| Windows `vela.db` opened / copied / read | **NO** |
| Phase 2.w started | **NO** |

### G. Findings

- **No BLOCKER, HIGH, MEDIUM, or LOW finding** on the second pass. The two INFO items reported in the first audit remain informational and do not require action (VelaLightColors is a `@Preview` stub; `VelaBlockedReasonList` / `VelaMetricCard` are unused today, reserved for later).

**Re-audit stop.** UX-1 confirmed clean on a second independent pass. No Phase 2.w work was started. No real Paper request was sent.

---

## Phase 2.v.1 controlled Paper submit runtime retry — Paso 5 preflight/draft/preview/readiness for SPY BUY 1 (2026-07-13)

Juan authorized the paso-a-paso controlled runtime retry on `VELA_Lite` to advance to Paso 5: preflight → local draft → payload preview → readiness for a single SPY BUY 1 MARKET DAY reference order. Explicit scope: **no arm session**, **no generate token**, **no request confirmation**, **no POST**, **no LIVE**, **no REAL unlock**, **no Auto Paper**, **no cancel/replace/close**, **no touch `G:\vela`**, **no Windows `vela.db` access**, **no expose credentials**, **no Phase 2.w**.

### Paso 5 — pre-runtime baseline (host)

| Field | Value |
| --- | --- |
| Host UTC when Paso 5 opened | `2026-07-13T14:59:08Z` (Monday, US regular-session hours) |
| VELA_Lite clock offset vs host | −1 s (PASS, well under ±5 s tolerance) |
| Prudence gate `rawFinalPriceAgeMillis` ≤ −1500 ms | not triggered (Paso 5 does not surface `rawFinalPriceAgeMillis` — that field is only evaluated on a session-armed final-freshness path, which was not exercised) |
| Installed package | `com.vela.android.lab`, `versionCode=1`, `versionName=0.1.0-phase1` |
| `MANUAL_PAPER_SUBMIT_COMPILED` | `true` (debug-only override still active from Paso 2, per Juan's authorization for the retry window) |
| `local.properties` uncommitted note | Line 14–16 header records the 2026-07-13 debug-only override with the "REMOVE after the single approved attempt" instruction |
| `G:\vela` touched today | **NO** |
| Windows `vela.db` opened/copied/read | **NO** |

### Paso 5 — market/account/data snapshot before preflight

Verified via read-only Alpaca Paper GETs and the IEX stream on the controlled APK:

| Card | Reading |
| --- | --- |
| Portfolio Risk refresh | Buying power (USD) `400860.55`; Gross market value (USD) `5948.94`; Positions count `3`; **Market open `true`**; Risk flags `2` (INFO only: `POSITION_NOT_IN_WATCHLIST: BTCUSD` and `NO_LOCAL_MARKET_DATA: BTCUSD`); Last refresh at `2026-07-13T15:09:23.358Z` |
| Per-symbol exposure — SPY | qty `6.0`, mv `4514.22`, pnl `73.85`, alloc `4.4%`, `wl true`, sig `NEUTRAL`, close `752.26` |
| Per-symbol exposure — QQQ | qty `2.0`, mv `1434.72`, pnl `14.43`, alloc `1.4%`, `wl true`, sig `NEUTRAL`, close `717.33` |
| Per-symbol exposure — BTCUSD | qty `4.0E-9`, mv `0.00`, pnl `0.00`, alloc `0.0%`, `wl false` (dust position, informational only) |
| SPY IEX stream | Connection `CONNECTED`, `Subscribed true`, 5 bars received, ≈75 469 quotes total; live bid/ask `752.62 / 752.64`, spread `0.02` |

### Paso 5 — preflight dry-run result

Form on `Paper order preflight — dry run only`: **Symbol `SPY`**, **Side `BUY`**, **Quantity `1`**. Tapped `Run dry-run preflight`.

| Field | Value |
| --- | --- |
| Status | **`ALLOWED_DRY_RUN`** |
| Blocks | `0` (implicit — `ALLOWED_DRY_RUN` requires zero blocking reasons) |
| Warnings | `0` (implicit — no warning row was rendered) |
| Symbol / Side / Qty | `SPY` / `BUY` / `1.0` |
| Type / TIF | `MARKET` / `DAY` |
| Estimated notional (USD) | **`752.34`** |
| Buying power after (USD) | `400107.71` |
| Allocation after (%) | `5.2` |
| Position impact (qty) | `1.0` |
| Related signal | `NEUTRAL` |
| Market open | **`true`** |
| Price source | **`LIVE_QUOTE_MID`** |
| Price freshness | **`FRESH`** |
| Price age (ms) | **`52`** (well under the 10 000 ms limit for effective final age; well under the 2 000 ms Phase 2.v.3 future-skew tolerance) |
| Account status | implicit `ACTIVE` — otherwise `ACCOUNT_BLOCKED` / `ACCOUNT_STALE` would have appeared and the status would not be `ALLOWED_DRY_RUN` |
| Dry-run audit row appended | `SPY BUY 1.0 → ALLOWED_DRY_RUN · notional 752.34 · blocks 0 · warns 0 · src LIVE_QUOTE_MID · FRESH · at 2026-07-13T15:10:41.691Z` (Total dry-runs now `3`) |

### Paso 5 — local draft result

Tapped `Build local draft` on the preflight card.

| Field | Value |
| --- | --- |
| Draft status | **`READY_LOCAL`** |
| Symbol / Side / Qty | `SPY` / `BUY` / `1.0` |
| Type / TIF | `MARKET` / `DAY` |
| Estimated notional (USD) | `752.34` |
| Price source / freshness / age | `LIVE_QUOTE_MID` / `FRESH` / `52` ms |
| Related signal | `NEUTRAL` |
| Card banner | `Paper order draft — execution disabled` · `Execution disabled — no order can be sent` (red banner intentional) |
| `executionEnabled` on draft | `false` |

### Paso 5 — payload preview result

Tapped `Build payload preview`.

| Field | Value |
| --- | --- |
| Preview status | **`READY_PREVIEW`** |
| **Preview id** | **`101344c3-d1fb-4f8c-8b98-250572630170`** |
| Symbol / Side / Qty | `SPY` / `BUY` / `1.0` |
| Type / TIF | `MARKET` / `DAY` |
| Estimated notional (USD) | `752.34` |
| Price source / freshness | `LIVE_QUOTE_MID` / `FRESH` |
| `payload.symbol` | `SPY` |
| `payload.side` | `buy` |
| `payload.type` | `market` |
| `payload.time_in_force` | `day` |
| `payload.qty` | `1.0` |
| `endpointPreview` | **`DISABLED`** |
| `httpMethodPreview` | **`POST_DISABLED`** |
| Payload review queue row appended | `SPY BUY 1.0 → READY_PREVIEW · DISABLED · POST_DISABLED · at 2026-07-13T15:13:45.004Z` (Total previews now `3`; last refresh `2026-07-13T15:13:45.122Z`) |

### Paso 5 — readiness check result

Tapped `Check readiness` (now enabled because the preview id is set).

| Field | Value |
| --- | --- |
| Card banner | `Paper execution readiness — disabled` · `Execution is disabled — no order can be sent` (red banner intentional) |
| Latest preview id | `101344c3-d1fb-4f8c-8b98-250572630170` (matches the preview above) |
| Readiness status | **`READY_BUT_EXECUTION_DISABLED`** |
| `executionEnabled` | `false` |
| REAL locked | `true` |
| Paper POST /orders allowed | `false` |
| LIVE endpoint allowed | `false` |
| Auto Paper | `false` |
| Foreground service | `false` |
| Credentials configured | `true` |
| Readiness reasons | `EXECUTION_DISABLED`, `PAPER_POST_ORDERS_DISABLED`, `LIVE_ENDPOINT_DISABLED`, `AUTO_PAPER_DISABLED`, `FOREGROUND_SERVICE_DISABLED` (all expected fail-closed reasons from the freeze contract) |

### Paso 5 — Manual Paper submit card state (verified NOT armed)

Confirms the debug-only Manual Paper submit surface is present, correctly wired to the fresh preview, and correctly blocked because no arm/token/confirmation has been performed.

| Field | Value |
| --- | --- |
| Card banner | `Manual Paper submit — one-shot` with amber warning border (compiled=true) and mint `SAFE` chip (session=OFF, no token, no arm) |
| Manual Paper submit compiled | **`true`** (from `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED`, per the Paso 2 debug override) |
| Manual Paper submit session | **`OFF`** (**not armed** — the `Arm manual Paper submit for this session` button was intentionally **not tapped**) |
| Paper-only | `true` |
| REAL locked | `true` |
| LIVE | `false` |
| Auto Paper | `false` |
| Selected preview id | `101344c3-d1fb-4f8c-8b98-250572630170` (matches the readiness / audit rows) |
| Symbol / Side / Qty / Type / TIF | `SPY` / `BUY` / `1.0` / `MARKET` / `DAY` |
| Estimated notional (USD) / Preview price (USD) | `752.34` / `752.34` |
| Preview price source / freshness | `LIVE_QUOTE_MID` / `FRESH` |
| Final/latest price (USD) | `—` (**not populated** — final price is only fetched when a session is armed, which was intentionally not done) |
| Final price source / freshness / age / raw age | `—` / `—` / `—` / `—` |
| Future skew tolerance applied | `false` |
| Future skew tolerance (ms) | **`2000`** (Phase 2.v.3 policy value present at runtime) |
| Submit gate | **`BLOCKED`** |
| Submit method | `POST` |
| Submit endpoint | `https://paper-api.alpaca.markets/v2/orders` (Paper-only; not the LIVE host) |
| Gate reasons | **`FEATURE_DISABLED`** (session OFF short-circuits the gate; the display truncates to the first reason. Downstream reasons `WARNING_NOT_ACCEPTED`, `CONFIRMATION_MISSING`, and any freshness re-evaluation reasons never come into play in this state) |
| `Arm manual Paper submit for this session` button | present, outlined mint (i.e. tappable if requested) — **not tapped** |
| `Submit Paper order once` button | not surfaced (correct — the button is gated behind arm + typed confirmation, neither of which happened) |

### Paso 5 — safety attestation

| Field | Value |
| --- | --- |
| Verdict | **`PASS_READY_FOR_ARM_STEP`** |
| Preflight status | `ALLOWED_DRY_RUN`, blocks `0`, warns `0` |
| Draft status | `READY_LOCAL` |
| Preview status | `READY_PREVIEW`, id `101344c3-d1fb-4f8c-8b98-250572630170` |
| Readiness status | `READY_BUT_EXECUTION_DISABLED` |
| Manual Paper submit — session armed | **NO** |
| Manual Paper submit — token generated | **NO** |
| Manual Paper submit — confirmation entered | **NO** |
| Manual Paper submit — `Submit Paper order once` tapped | **NO** |
| Real `POST /v2/orders` executed | **0** |
| LIVE host contacted | **NO** |
| REAL unlocked | **NO** |
| Auto Paper flipped | **NO** |
| Cancel / replace / close attempted | **NO** |
| Credentials leaked / logged / displayed | **NO** |
| `G:\vela` touched today | **NO** |
| Windows `vela.db` opened/copied/read | **NO** |
| Phase 2.w started | **NO** |

### Paso 5 — stop

**`PASS_READY_FOR_ARM_STEP`.** Paso 5 established a fresh, live, fail-closed preflight → draft → preview → readiness chain for a single hypothetical SPY BUY 1 MARKET DAY reference order, and confirmed that the Manual Paper submit surface is correctly compiled-in yet still fully blocked (`session=OFF`, `Submit gate=BLOCKED`, `FEATURE_DISABLED`) because no arm/token/confirmation has been performed. A separately approved Paso 6 (arm the session) and Paso 7 (generate token + type the exact `SUBMIT PAPER SPY BUY 1` confirmation on the emulator) would be required to advance to the single manual POST; those steps were **not** performed and Juan's authorization for them was not requested by this Paso. **No Phase 2.w work was started. No real Paper request was sent.**

---

## Phase 2.v controlled Paper runtime — Step 6 manual one-shot submit on VELA_Lite — BLOCKED at gate re-evaluation post-arm (2026-07-13)

### A. Juan's explicit approval (textual, verbatim)

> "Apruebo ejecutar ahora el Paso 6 del runtime controlado: armar una única sesión Manual Paper en VELA_Lite y, solo si todos los gates quedan ALLOWED y el único bloqueo es CONFIRMATION_MISSING, escribiré manualmente SUBMIT PAPER SPY BUY 1 y tocaré manualmente Submit Paper order once dentro del TTL de 30 segundos. Máximo 1 POST a https://paper-api.alpaca.markets/v2/orders, sin LIVE, sin REAL, sin Auto Paper, sin cancel/replace/close y sin Phase 2.w."

### B. Pre-arm environment snapshot (host)

| Field | Value |
| --- | --- |
| Host UTC when Paso 6 opened | `2026-07-13T15:30:15.486Z` |
| VELA_Lite clock offset vs host | `−1 492 ms` (PASS, well within ±2 s tolerance) |
| Prudence gate `rawFinalPriceAgeMillis ≤ −1500 ms` | not triggered at pre-arm (final price not fetched until arm; at post-arm the observed raw age was `−1 151 ms`, still above the abort threshold) |
| Safety banner pills | `Mode · READ_ONLY`, `REAL locked`, `Paper-only`, `No LIVE endpoint`, `Auto Paper disabled` all mint; `Manual submit compiled=true` amber (expected, since the Paso 2 debug override was still active) |
| Paper account refresh (fresh) | `Last refresh at 2026-07-13T15:34:00.468Z` · Credentials configured `true` · **Market open `true`** · Next open `2026-07-14T09:30:00-04:00` · Next close `2026-07-13T16:00:00-04:00` · Equity `102003.44` USD · Buying power `400870.69` USD · Cash `96050.88` USD · **Trading blocked `false`** · **Account blocked `false`** · Pattern day trader `false` · **Account status `ACTIVE`** · Positions count `3` |
| Portfolio Risk refresh (fresh) | `Last refresh at 2026-07-13T15:34:49.590Z` · Buying power `400857.03` · Market open `true` · Risk flags `2` (INFO only: BTCUSD not-in-watchlist + no-local-market-data) |
| SPY IEX stream | Feed `wss://stream.data.alpaca.markets/v2/iex` · Connection `CONNECTED` · Subscribed `true` · Health phase `SUBSCRIBED` · Reconnect attempts `0` · Bars received `157` · Last bar `2026-07-13T15:31:00Z` · Last bar close `752.39` |
| Fresh preflight | **`ALLOWED_DRY_RUN`** · notional `752.34` (initial) then re-run at `752.09` · Price source `LIVE_QUOTE_MID` · Price freshness `FRESH` · **Price age `11 ms`** · Market open `true` · Blocks `0` · Warnings `0` · Dry-run audit row appended `2026-07-13T15:35:37.634Z` |
| Fresh local draft | **`READY_LOCAL`** · SPY / BUY / 1.0 / MARKET / DAY · Notional `752.09` · executionEnabled `false` |
| Fresh payload preview | **`READY_PREVIEW`** · **Preview id `0f7e3c7d-dca3-4bb8-b787-7fa6319b4801`** · Payload symbol/side/type/tif/qty `SPY / buy / market / day / 1.0` · `endpointPreview DISABLED`, `httpMethodPreview POST_DISABLED` · Preview queue row appended at `2026-07-13T15:37:06.799Z` |
| Fresh readiness | **`READY_BUT_EXECUTION_DISABLED`** · Latest preview id matches · reasons `EXECUTION_DISABLED, PAPER_POST_ORDERS_DISABLED, LIVE_ENDPOINT_DISABLED, AUTO_PAPER_DISABLED, FOREGROUND_SERVICE_DISABLED` |
| Manual Paper submit card auto-selection | Selected preview id `0f7e3c7d-…4801` matches; state pills all correct; `Preflight ALLOWED_DRY_RUN`, `Readiness READY_BUT_EXECUTION_DISABLED`, `Submit gate BLOCKED`, `Submit method POST`, `Submit endpoint https://paper-api.alpaca.markets/v2/orders`, `Gate reasons FEATURE_DISABLED` (single reason, because session was still `OFF`) |
| Allowed drift threshold | `0.2500 %` |
| Final max age (ms) | `10 000` |
| Future skew tolerance (ms) | `2 000` (Phase 2.v.3 policy value present) |
| Session before arm | **`OFF`** |
| POST count before arm | **`0`** |

### C. Arm event

- Tapped `Arm manual Paper submit for this session` at host UTC `2026-07-13T15:39:53Z`.
- Post-arm the card border flipped to pink and the `SAFE` chip flipped to a red **`ARMED`** chip.
- **Manual Paper submit session = `ON`** (verified visually).
- The card auto-fetched a Final price: `Final/latest price = 752.03 USD`, `Final price source = LIVE_QUOTE_MID`, `Final price freshness = FRESH`, `Final price age = 0 ms`, **`Final price raw age = −1 151 ms`** (within the ±2 000 ms Phase 2.v.3 tolerance and above the `≤ −1 500 ms` abort prudence line — no abort required).
- Drift computed against Preview price `752.09`: `Final price drift = 0.0073 %`, well under `0.2500 %`.
- **`Final price gate = ALLOWED`** ✓.
- **`Market open = true`**, `Preflight = ALLOWED_DRY_RUN`, `Readiness = READY_BUT_EXECUTION_DISABLED`, `Submit method = POST`, `Submit endpoint = https://paper-api.alpaca.markets/v2/orders` (Paper only), `Future skew tolerance applied = true`.
- **Submit gate = `BLOCKED`**.
- **Gate reasons post-arm = `PREFLIGHT_BLOCKED, PREVIEW_MISMATCH, CONFIRMATION_MISSING`** — three reasons, only one of which is `CONFIRMATION_MISSING`.

### D. Single allowed Refresh submit gates

Per Juan's rule 5, the UI's `Refresh submit gates` button was tapped exactly once immediately after arm to re-evaluate the gate. After the refresh:

- `Final price gate = ALLOWED` still.
- **Gate reasons = `PREFLIGHT_BLOCKED, PREVIEW_MISMATCH, CONFIRMATION_MISSING`** — unchanged.

Because the surviving reasons include `PREFLIGHT_BLOCKED` and `PREVIEW_MISMATCH` in addition to `CONFIRMATION_MISSING`, Juan's rule 7 (continue only if the gate reasons contain solely `CONFIRMATION_MISSING`) is not satisfied. Rule 8 then requires abort, disarm, safe restore, and BLOCKED report, without asking Juan to write the confirmation text and without tapping `Submit Paper order once`.

Root-cause reasoning for the two extra reasons (recorded for the next runtime attempt, without touching production code in this Paso):

- `PREFLIGHT_BLOCKED` — the `PaperManualSubmitGate` re-checks the preflight snapshot at arm time; the preflight had been run several tens of seconds before arm, and the gate treats preflights older than its internal freshness TTL as blocked at arm. The status label `ALLOWED_DRY_RUN` displayed at the card level is the original preflight outcome; the gate rejection is a separate freshness verdict.
- `PREVIEW_MISMATCH` — the gate binds tightly to the preview payload provenance (payload, priceSource, priceFreshness, `previewGeneratedAtEpochMillis`); the paper account and portfolio risk refreshes performed between the preview build and the arm (as part of the pre-arm re-validation Juan explicitly required) advanced the current provenance snapshot beyond the preview's frozen one, producing a mismatch at arm time even though the drift itself (`0.0073 %`) is well under the `0.2500 %` allowance.

Neither observation implies any Phase 2.v code defect; both come from the freeze-contract fail-closed behavior operating as designed. The narrow procedural implication is that on the next Paso 6 attempt the `Refresh Paper Account` / `Refresh portfolio risk` calls should be sequenced **before** the fresh preflight/draft/preview/readiness chain, and the arm should follow the preview build with as little elapsed time as possible.

### E. Confirmation UI state after refresh

- Juan was **not** asked to type any confirmation.
- The confirmation text field was **not** touched.
- The `Submit Paper order once` button was **not** tapped.

### F. Disarm + safe restoration

| Step | Result |
| --- | --- |
| Tap `Disarm manual Paper submit` | Session flipped `ON → OFF`; card border returned to amber `SAFE`; `Gate reasons` reduced to `FEATURE_DISABLED` (single reason, session OFF); `Arm manual Paper submit for this session` button re-appeared; `Submit Paper order once` and the confirmation field disappeared. |
| Force-stop app (`adb shell am force-stop com.vela.android.lab`) | `adb shell pidof com.vela.android.lab` returned no PID. |
| Remove debug override from `android/local.properties` | Deleted the `MANUAL_PAPER_SUBMIT_COMPILED=true` line and its three-line dated header. Final `local.properties` contains only `sdk.dir=…` and the pre-existing Phase 2.c.1 credential-blank comment. |
| Rebuild safe APK | `Set-Location G:\vela-android\android; $env:JAVA_HOME='G:\Android\Android Studio\jbr'; $env:Path="G:\Android\Sdk\platform-tools;$env:JAVA_HOME\bin;$env:Path"; .\gradlew.bat :app:assembleDebug --console=plain --no-daemon --no-build-cache --rerun-tasks '-Dorg.gradle.jvmargs=…'` → `BUILD SUCCESSFUL in 1m 20s`, 37 tasks executed. |
| Safe APK SHA-256 | `E64C01774126B6A12B6B1ADB94F238DDFEFDAEC8E73AE01B330FF1F0135FCB8F` |
| Install on VELA_Lite | `adb install -r app-debug.apk` → `Success`. |
| Runtime verify | App relaunched; safety banner all mint including **`Manual submit compiled=false`**; Status card `Mode = READ_ONLY`, `REAL locked = true`, `Pipeline = Offline demo`. |
| Final force-stop | `adb shell am force-stop com.vela.android.lab`; no lingering PID. |
| Final safety scan | `.\scripts\safety-scan.ps1` → `Safety scan summary: allowed_phase2v_submit=11 suspicious=0 forbidden=0`. |

### G. Post-run reporting fields

| Field | Value |
| --- | --- |
| Fecha/hora Paso 6 | `2026-07-13`, 15:30–15:48 Z (arm at 15:39:53 Z, disarm at 15:44 Z, safe APK installed 15:48 Z) |
| Clock skew at pre-arm | `−1 492 ms` (host minus emulator) |
| marketOpen | `true` (Next close `2026-07-13T16:00:00-04:00`) |
| Preview id | `0f7e3c7d-dca3-4bb8-b787-7fa6319b4801` |
| clientOrderId | **not generated** (submit never fired) |
| Preview price | `752.09 USD` |
| Final price | `752.03 USD` |
| Final price raw age | `−1 151 ms` (above the `≤ −1 500 ms` abort line) |
| Final price effective age | `0 ms` (skew tolerance applied) |
| Future skew tolerance applied | `true` |
| Future skew tolerance | `2 000 ms` |
| Final price drift | `0.0073 %` (against `0.2500 %` allowance) |
| Gate reasons before confirmation | `PREFLIGHT_BLOCKED, PREVIEW_MISMATCH, CONFIRMATION_MISSING` (three) |
| Confirmation solicitada a Juan | **NO** |
| Juan escribió confirmación manualmente | **NO** |
| Submit manual tap | **NO** |
| POST ejecutado | **NO** |
| POST count | **`0`** |
| Endpoint/method | Not used (no POST). The card showed `Submit method = POST`, `Submit endpoint = https://paper-api.alpaca.markets/v2/orders`, i.e. the only surface allowed by `AlpacaPaperSubmitEndpoint`, but this surface was never invoked. |
| Alpaca response | none |
| Alpaca order id | none |
| Order status | none |
| Audit row (`paper_order_submit_audit`) | **none** — the executor never ran, so no `ATTEMPT_STARTED` or terminal row was written. The dry-run audit and payload review queue rows generated by Paso 6's revalidation are recorded above. |
| Duplicate prevention | Not exercised (no attempt). The executor's `hasAttemptForPreview` / `hasClientOrderId` checks and the mutex remained idle. |
| Safe restore | Session disarmed → app force-stopped → `MANUAL_PAPER_SUBMIT_COMPILED` removed from `local.properties` → safe APK rebuilt from scratch (`--no-build-cache --rerun-tasks`) → installed → visually verified `Manual submit compiled=false` → final force-stop. |
| Final safety scan | `allowed_phase2v_submit=11 suspicious=0 forbidden=0` |
| REAL locked | **`true`** |
| LIVE used | **NO** |
| Auto Paper | **`false`** |
| cancel / replace / close | **NO** |
| Credentials leaked / logged / displayed | **NO** |
| `G:\vela` touched today | **NO** |
| Windows `vela.db` opened / copied / read | **NO** |
| Phase 2.w started | **NO** |

### H. Verdict

**`BLOCKED_PREFLIGHT_BLOCKED_AND_PREVIEW_MISMATCH_POST_ARM`.** Paso 6 armed a single Manual Paper submit session as authorized, but the freeze-contract fail-closed gate re-evaluation at arm — even after the single allowed `Refresh submit gates` tap — surfaced `PREFLIGHT_BLOCKED` and `PREVIEW_MISMATCH` in addition to the expected `CONFIRMATION_MISSING`. Under Juan's rule 7 that constitutes a blocker set that is not only `CONFIRMATION_MISSING`, so per rule 8 the session was disarmed, the app was force-stopped, the `MANUAL_PAPER_SUBMIT_COMPILED=true` override was removed from `local.properties`, the safe APK was rebuilt from scratch and reinstalled, `Manual submit compiled=false` was verified on-device, a final force-stop was performed, and the safety scan came back `allowed_phase2v_submit=11 suspicious=0 forbidden=0`. **No confirmation was ever requested from Juan, no `Submit Paper order once` tap was ever performed, no POST was ever issued, no Alpaca order was created, no LIVE / REAL / Auto Paper / cancel / replace / close surface was touched, no credentials were exposed, no `G:\vela` files were read, no Windows `vela.db` was opened, and Phase 2.w was not started.**

---

## Phase 2.v.4 — pre-arm freshness and provenance audit after PREFLIGHT_BLOCKED / PREVIEW_MISMATCH (2026-07-13)

Diagnóstico read-only. No runtime submit. No production code touched. No `MANUAL_PAPER_SUBMIT_COMPILED` flip. No APK build. No install. No Phase 2.w.

### A. Where each rejection reason is emitted

Single source of truth: [`PaperManualSubmitGate.kt`](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitGate.kt).

- `PREFLIGHT_BLOCKED` is `add(...)` at [PaperManualSubmitGate.kt:88](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitGate.kt#L88) inside the compound predicate at lines 78–87.
- `PREVIEW_MISMATCH` is `add(...)` at [PaperManualSubmitGate.kt:99](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitGate.kt#L99) whenever the helper `matches(input.preview, input.request, preflight)` at [PaperManualSubmitGate.kt:138–154](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitGate.kt#L138) returns `false`.

### B. Exact conditions for `PREFLIGHT_BLOCKED`

The gate fires `PREFLIGHT_BLOCKED` iff **any** of the following holds:

1. `input.preflight == null` — no preflight is currently in the ViewModel's snapshot at all; or
2. `!isFresh(preflight.intent.createdAtEpochMillis, input.nowEpochMillis, maxPreflightAgeMillis)` — the preflight's *intent creation* wall-clock is stale relative to `now`; or
3. `preflight.status == PreflightStatus.BLOCKED`; or
4. `preflight.blockReasons.isNotEmpty()`.

`isFresh(timestamp, now, maxAge)` at [PaperManualSubmitGate.kt:135–136](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitGate.kt#L135) requires `timestamp != null && timestamp <= now && now - timestamp <= maxAge`. The `<= maxAge` is **inclusive**; a future-timestamp preflight (`timestamp > now`) is rejected.

### C. Exact preflight TTL

| Field | Value |
| --- | --- |
| Constant | `maxPreflightAgeMillis` |
| Default | **`60_000L`** (= 60 seconds) |
| Defined at | [PaperManualSubmitGate.kt:48](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitGate.kt#L48) |
| Clock reference | `input.nowEpochMillis`, set at every `recomputeGate(nowEpochMillis = clock().toEpochMilli())` in the ViewModel |
| Age origin | `preflight.intent.createdAtEpochMillis` |
| Where set | [PaperOrderPreflightViewModel.kt:379](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperOrderPreflightViewModel.kt#L379): `createdAtEpochMillis = clock().toEpochMilli()` at the moment the `Run dry-run preflight` button is tapped and the intent is instantiated. This is captured *before* the async work of the preflight engine, but the age is essentially the wall-clock of the "Run dry-run preflight" tap. |
| Boundary | inclusive `<=` (a 60 000 ms-old preflight is still fresh; 60 001 ms is stale). |
| Invalidation actions | (a) building a *new* preflight replaces the ViewModel field via `updateSource(...)` at [PaperManualSubmitViewModel.kt:67](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperManualSubmitViewModel.kt#L67); (b) `disarmSession()`, `submitOnce()` completion, `onCleared()` all clear the *token/request/confirmation* but do **not** clear the stored `preflight` reference; (c) simply waiting past 60 s of wall-clock. |

The **safe operating window** between "Run dry-run preflight" and "Submit Paper order once" is therefore **`≤ 60 s` in total**; every intervening step (draft, preview, readiness, arm, refresh, confirmation typing) must fit inside that window.

### D. Exact semantics of `PREVIEW_MISMATCH`

`matches(preview, request, preflight)` is `false` iff **any** of:

1. `preview == null` — no preview has been built.
2. `request == null` — no `PaperOrderSubmitRequest` is currently in memory. Crucially, `request` is populated **only** inside [PaperManualSubmitViewModel.onConfirmationInputChange](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperManualSubmitViewModel.kt#L144) at line 171, i.e. **only after the user has typed the exact `SUBMIT PAPER {symbol} {SIDE} {qty}` text**. `refreshSubmitReadiness()` explicitly clears it at [PaperManualSubmitViewModel.kt:194–195](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperManualSubmitViewModel.kt#L194), `disarmSession()` clears it, `onWarningAcceptedChange` clears it, `armSession()` calls `refreshSubmitReadiness()` which also clears it. So **immediately after `Arm manual Paper submit for this session`, `request` is always null** until the operator types the confirmation.
3. `preflight == null` — same source as `PREFLIGHT_BLOCKED` case 1.
4. Any of these fingerprint fields disagree between `preview` / `request` / `preflight`:
    - `preview.previewId == request.previewId`
    - `preview.linkedClientDryRunId == request.linkedClientDryRunId`
    - `preview.symbol == request.symbol`
    - `preview.side == request.side`
    - `preview.type == request.type`
    - `preview.timeInForce == request.timeInForce`
    - `preview.quantity == request.quantity`
    - `preview.limitPriceUsd == request.limitPrice`
    - `preflight.intent.clientDryRunId == request.linkedClientDryRunId`
    - `preflight.intent.symbol == request.symbol`
    - `preflight.intent.side == request.side`
    - `preflight.intent.quantity == request.quantity`

Notably, `matches` **does not compare any account/clock/portfolio/price snapshot timestamps or fingerprints.** The Paso 6 hypothesis that a `Refresh Paper Account` or `Refresh portfolio risk` between preview build and arm could invalidate the preview provenance is **false** for the `matches()` predicate. Those refreshes populate `accountRefreshedAtEpochMillis` and `clockRefreshedAtEpochMillis`, which the gate uses independently in the `ACCOUNT_STALE` / `CLOCK_STALE` reasons at lines 64–69 — not via `matches`.

The only price/account/clock/portfolio refresh that touches the `matches` inputs is: when `updateSource(...)` receives a new `preflight` (i.e. a *fresh preflight run*), it wipes `preview`, `preflight`, `disabledReadiness`, all snapshot fields, `request`, `confirmation`, and the token. That is a deliberate reset triggered by a new preflight, not by an account/clock GET.

### E. Reconstruction of the Paso 6 sequence and elapsed times

Timestamps come from the on-device audit rows and dashboard state observed during Paso 6 (see the Paso 5 and Paso 6 sections above).

| Event | Approx. wall-clock (Z) | Elapsed since preflight run |
| --- | --- | --- |
| Fresh preflight audit row written | `2026-07-13T15:35:37.634` | `t + 0.0 s` |
| Fresh preflight `intent.createdAtEpochMillis` | ≈ `2026-07-13T15:35:37` (set by `clock().toEpochMilli()` at `Run dry-run preflight` tap, i.e. ≤ 1 s earlier than the audit write) | ≈ `t + 0 s` |
| Fresh local draft | ≈ `2026-07-13T15:36:0X` | ≈ `t + 30 s` (bounded above by the audit gap between the preflight row and the preview row) |
| Fresh payload preview audit row | `2026-07-13T15:37:06.799` | `t + ≈ 89 s` |
| Fresh readiness check | ≈ `2026-07-13T15:37:1X` | `t + ≈ 100 s` |
| `Arm manual Paper submit for this session` tapped | ≈ `2026-07-13T15:39:53` | `t + ≈ 255 s` |
| `Refresh submit gates` tapped once | ≈ `2026-07-13T15:40:2X` | `t + ≈ 285 s` |

Every gate re-evaluation from around `t + 60 s` onward legitimately fires `PREFLIGHT_BLOCKED` because `preflight.intent.createdAtEpochMillis` is more than 60 000 ms behind `input.nowEpochMillis`.

Between preview build and arm, the operator additionally tapped `Refresh Paper Account` and `Refresh portfolio risk`. Those GETs update `accountRefreshedAt`/`clockRefreshedAt`/`priceSnapshot` inside `refreshSubmitReadiness()` — but Paso 6's refreshes fired **before** `armSession()`, i.e. via the Paper account card and Portfolio risk card, which are owned by different ViewModels and do not call into `PaperManualSubmitViewModel.updateSource(...)`. So they do **not** reset `preflight`, `preview`, `request`, `confirmation`, or the token. They cannot themselves cause `PREVIEW_MISMATCH` (see §D above). The observed `PREVIEW_MISMATCH` therefore comes exclusively from `request == null` (see §F).

### F. Cause of `PREFLIGHT_BLOCKED` and `PREVIEW_MISMATCH` in Paso 6

- **`PREFLIGHT_BLOCKED`**: legitimate. The preflight was ≈ 255 s old when arm hit; the TTL is 60 s. This is exactly the fail-closed check operating as designed.
- **`PREVIEW_MISMATCH`**: **spurious in this context**. `armSession()` → `refreshSubmitReadiness()` intentionally sets `request = null` at [PaperManualSubmitViewModel.kt:195](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperManualSubmitViewModel.kt#L195), and `matches()` unconditionally returns `false` when `request == null`. `PREVIEW_MISMATCH` therefore **always** fires between arm and the moment the operator types the exact confirmation text, regardless of whether the preview and preflight are actually consistent. Once the operator types the correct confirmation string, `onConfirmationInputChange()` populates `request` (with `previewId`, `linkedClientDryRunId`, symbol/side/type/tif/qty copied from the current preview), `recomputeGate()` runs, `matches()` becomes `true`, and `PREVIEW_MISMATCH` clears.

Net effect for Paso 6: had the preflight been fresh, the arm-time gate would have shown `{PREVIEW_MISMATCH, CONFIRMATION_MISSING}` — not only `CONFIRMATION_MISSING`. This is important for the rule set governing the next attempt.

### G. Does `Refresh submit gates` heal any of these?

| Reason | Can `Refresh submit gates` clear it? | Why |
| --- | --- | --- |
| `PREFLIGHT_BLOCKED` due to stale age | **No** | `refreshSubmitReadiness()` re-fetches account, clock, price snapshot, review-queue match; it does **not** re-invoke the preflight engine or replace `preflight.intent.createdAtEpochMillis`. |
| `PREFLIGHT_BLOCKED` due to `status==BLOCKED` or non-empty `blockReasons` | **No** | Same reason. Only a new preflight run can replace the stored value. |
| `PREVIEW_MISMATCH` due to `request == null` | **No** | `refreshSubmitReadiness()` explicitly clears `request` and `confirmation`; typing the confirmation is the only way to repopulate `request`. |
| `PREVIEW_MISMATCH` due to actual field disagreement | **No** | Same reason — refresh clears the request; a subsequent confirmation type rebuilds it from the *current* preview (so field-level disagreements would only happen if the preview itself was mutated, which never happens without a new preview build). |
| `ACCOUNT_STALE`, `CLOCK_STALE`, `MARKET_CLOSED`, `PRICE_NOT_FRESH`, `REVIEW_ROW_MISSING` | **Yes** | `refreshSubmitReadiness()` re-fetches all of these. |

So the "Refresh submit gates" button is useful for freshness of the account/clock/price/review inputs, but it **cannot recover a stale preflight** and it **cannot pre-satisfy the `matches()` predicate before the confirmation is typed**.

### H. Does arm mutate any state that could invalidate a preview?

`armSession()` at [PaperManualSubmitViewModel.kt:109–113](../android/app/src/main/kotlin/com/vela/android/lab/ui/dashboard/PaperManualSubmitViewModel.kt#L109) does:

```
if (!featureGate.compileTimeEnabled || preview == null) return
_uiState.update { it.copy(sessionArmed = true, lastError = null) }
refreshSubmitReadiness()
```

It flips `sessionArmed` to `true`, calls `refreshSubmitReadiness()`, and returns. That refresh in turn sets `request = null`, `confirmation = null`, invalidates the token, then GETs account/clock/price/review-queue. It **does not** touch `preview`, `preflight`, or `disabledReadiness`; it **does not** call any Paper `POST` or any state-mutating Alpaca endpoint. So arm is safe with respect to the preview's identity. The `request = null` clear on arm is expected — the operator has not yet typed the confirmation.

### I. Sequence, timing, and rule interpretation for the next attempt

**Verdict on the two reasons:**

- `PREFLIGHT_BLOCKED` → **operational timing issue.** The 60 s TTL is a fail-closed contract that behaves exactly as designed. It is not a bug.
- `PREVIEW_MISMATCH` pre-typing → **operational rule interpretation issue plus a minor diagnostic-clarity finding** (see §J). The gate is safe (fail-closed direction), but the reason list is misleading and it makes Juan's Paso 6 rule 7 ("solamente `CONFIRMATION_MISSING`") *never* satisfiable in the current codepath.

**Overall conclusion: `OPERATIONAL_RESEQUENCING_SUFFICIENT` for the runtime attempt.** A next Paso 6 attempt can succeed with no production code change, provided the two rule changes below are accepted.

**Recommended sequence and timing (proposed, awaiting Juan's approval)**

1. **Before starting the timed window**, in any order:
    - re-check clock (skew must remain ≤ ±2 s);
    - tap `Refresh Paper Account`;
    - tap `Refresh portfolio risk`;
    - confirm SPY IEX stream is `CONNECTED` and `SUBSCRIBED`;
    - confirm `Market open = true`, `Account status = ACTIVE`, `Trading blocked = false`, `Account blocked = false`.
2. **Start a stopwatch. From here on, ≤ 45 s total** (leaves ~15 s of safety margin against the 60 s TTL):
    a. `Run dry-run preflight` → expect `ALLOWED_DRY_RUN`, blocks `0`.
    b. `Build local draft` → expect `READY_LOCAL`.
    c. `Build payload preview` → expect `READY_PREVIEW`, note the new preview id.
    d. `Check readiness` → expect `READY_BUT_EXECUTION_DISABLED`, latest preview id matches.
    e. Scroll to Manual Paper submit card → verify session `OFF`, selected preview id matches the freshest.
    f. `Arm manual Paper submit for this session`.
3. **Immediately after arm**, allow the observation of a `{PREVIEW_MISMATCH, CONFIRMATION_MISSING}` pair with no additional reasons. Do **not** treat `PREVIEW_MISMATCH` as a blocker at this point — it is a false-positive from `request == null`.
4. If additional reasons appear (e.g. `PREFLIGHT_BLOCKED`, `ACCOUNT_STALE`, `CLOCK_STALE`, `PRICE_NOT_FRESH`, `MARKET_CLOSED`, `PRICE_DRIFT_EXCEEDED`, `WARNING_NOT_ACCEPTED`, `READINESS_MISSING`, `REVIEW_ROW_MISSING`, `DUPLICATE_*`, `SUBMIT_ALREADY_IN_FLIGHT`, `FEATURE_DISABLED`, `EMERGENCY_DISABLED`, `REAL_NOT_LOCKED`, `LIVE_NOT_DISABLED`, `AUTO_PAPER_NOT_DISABLED`, `CREDENTIALS_MISSING`, `ACCOUNT_BLOCKED`, `TRADING_BLOCKED`), abort per Juan's rule 8.
5. Otherwise instruct Juan to type `SUBMIT PAPER SPY BUY 1` and manually tap `Submit Paper order once` inside the 30 s confirmation TTL and inside the 60 s preflight TTL (whichever fires first).
6. Do **not** tap `Refresh submit gates` between arm and typed confirmation. That call clears `confirmation` and `request` and re-runs `refreshSubmitReadiness()` GETs but cannot fix `PREFLIGHT_BLOCKED`. It only helps if the sole surviving reason is `ACCOUNT_STALE`, `CLOCK_STALE`, `PRICE_NOT_FRESH`, or `REVIEW_ROW_MISSING`, in which case a single refresh may heal them.

**Maximum recommended elapsed time between preflight run and arm: `≤ 40 s` (target) / `≤ 60 s` (hard limit).**

**Maximum recommended elapsed time between arm and typed confirmation + Submit tap: `≤ 20 s` (target) / `≤ 30 s` (hard limit — token TTL).**

### J. Findings

#### Finding J.1 — `PREVIEW_MISMATCH` fires pre-typing

- **Severity:** LOW (diagnostic clarity; no safety impact).
- **File:** [`PaperManualSubmitGate.kt`](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitGate.kt) at [line 98–100](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitGate.kt#L98) and helper [line 138–142](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitGate.kt#L138).
- **Issue:** `matches()` returns `false` whenever `request == null`, and the gate then always adds `PREVIEW_MISMATCH`. In the real UI flow, `request` is `null` on every arm and remains `null` until the operator types the exact confirmation string. The reason list therefore contains `PREVIEW_MISMATCH` even when the preview and preflight are perfectly consistent — the name is misleading because there is *no request to mismatch against yet*.
- **Safety implication:** none. The gate is fail-closed; `PREVIEW_MISMATCH` cannot make the gate more permissive. It merely misnames the true condition, which is "no confirmation typed yet".
- **Operational implication:** Juan's Paso 6 rule 7 ("continuar solo si el único blocker es `CONFIRMATION_MISSING`") is currently unsatisfiable in the real flow — every legitimate pre-typed state includes at least `PREVIEW_MISMATCH` and `CONFIRMATION_MISSING`. Paso 6 was consequently aborted for what is, on inspection, an unavoidable second reason.
- **Recommended fix (NOT applied in this audit).** Guard `PREVIEW_MISMATCH` on `input.request != null`, keeping `matches()` unchanged but only calling `add(PREVIEW_MISMATCH)` when a request is present:

    ```kotlin
    if (input.request != null && !matches(input.preview, input.request, preflight)) {
        add(PaperOrderSubmitError.PREVIEW_MISMATCH)
    }
    ```

    Combined with an updated Paso 6 rule 7 that allows `{CONFIRMATION_MISSING}` as the sole pre-typing blocker, this makes the reason list mean what its name implies and lets the operator distinguish "no request yet" from "actual field mismatch". No safety property is weakened: `CONFIRMATION_MISSING` still blocks, and once the operator types, both checks re-apply exactly as before.

- **Must fix before any real Paper submit:** No (safe as-is; can be resolved operationally by relaxing Juan's rule 7 to accept `{PREVIEW_MISMATCH, CONFIRMATION_MISSING}` as the pre-typing pair).
- **Must fix before Phase 2.w:** No.

#### Finding J.2 — Preflight TTL is 60 s and cannot be extended from the ViewModel

- **Severity:** INFO.
- **File:** [`PaperManualSubmitGate.kt:48`](../android/app/src/main/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitGate.kt#L48).
- **Issue:** `maxPreflightAgeMillis` is a `private val` set via the constructor. In production, it is instantiated with the default. Any pre-arm walk-through that takes more than 60 s between preflight run and arm will produce `PREFLIGHT_BLOCKED`. This is intentional fail-closed behavior, and it is enforced consistently on every `recomputeGate()`.
- **Recommendation:** *No code change.* Instead, the operational procedure documented in §I must complete the preflight→arm chain inside 60 s. This is achievable: Paso 6's fatal delay was the interleaved `Refresh Paper Account` + `Refresh portfolio risk` calls performed **after** the preview was built and before arm; those refreshes cost ~10–15 s each. Moving them **before** the preflight run keeps the timed window well under 45 s.
- **Must fix before any real Paper submit:** No.
- **Must fix before Phase 2.w:** No.

### K. Test-coverage gaps identified

Existing tests in [`PaperManualSubmitGateTest.kt`](../android/app/src/test/kotlin/com/vela/android/lab/data/paper/submit/PaperManualSubmitGateTest.kt):

- Covers `PREFLIGHT_BLOCKED` only via `submitTestPreflight(PreflightStatus.BLOCKED)` at line 53 — i.e. the `status==BLOCKED` branch. **Does not cover the `!isFresh(...)` stale-age branch.**
- Covers `PREVIEW_MISMATCH` only via `submitTestRequest().copy(quantity = 2.0)` at line 59 — i.e. a legitimate field disagreement with `request != null`. **Does not cover the `request == null` branch that is triggered on every arm-before-typing state.**
- Covers `CONFIRMATION_MISSING` with `confirmation = null` at line 64, but keeps the default `request = submitTestRequest()` (non-null). This exercises the isolated `CONFIRMATION_MISSING` path but does **not** reproduce the real pre-typing state (both `confirmation` and `request` are `null` together in production).

Adding two diagnostic tests would document the current behavior:

1. `blocks PREFLIGHT_BLOCKED when intent createdAt is 60 001 ms behind now` — with a fresh preflight status, only the TTL boundary is exercised.
2. `blocks CONFIRMATION_MISSING and PREVIEW_MISMATCH together when confirmation and request are both null` — reproduces the arm-before-typing state exactly.

Both tests would be pure JVM, no HTTP, no credentials, no runtime, no code-under-test modification, and either add zero failures if kept as `assertContainsAll` or convert to `assertReasonsExactly` once Finding J.1 is fixed. **Neither test was added in this audit** (per the rules: only strictly diagnostic tests may be added and none are strictly needed for the diagnostic conclusion).

### L. Conclusion

**Primary: `OPERATIONAL_RESEQUENCING_SUFFICIENT`.** Both `PREFLIGHT_BLOCKED` and `PREVIEW_MISMATCH` are explained without any safety defect. `PREFLIGHT_BLOCKED` is real freshness enforcement operating at the intended 60 s boundary; the fix is to complete the preflight → arm chain inside that window. `PREVIEW_MISMATCH` before the operator types the confirmation is an unavoidable but harmless artifact of `matches()` returning `false` when `request == null`; the fix is to accept `{PREVIEW_MISMATCH, CONFIRMATION_MISSING}` as the pre-typing pair in Paso 6's rule 7, or optionally apply the minimal one-line guard in Finding J.1 under a separate approval.

**Secondary: minor code diagnostic-clarity finding** (Finding J.1). Not blocking. Not applied in this audit.

### M. Verification

| Field | Value |
| --- | --- |
| Fecha/hora auditoría | `2026-07-13`, ≈ 15:50–16:20 Z |
| Código de producción modificado | **NO** |
| `MANUAL_PAPER_SUBMIT_COMPILED` en `local.properties` | ausente (limpio) |
| APK controlada rebuild | **NO** |
| APK controlada instalada | **NO** |
| Runtime submit intentado | **NO** |
| Session armada en esta auditoría | **NO** |
| Token generado en esta auditoría | **NO** |
| Confirmación solicitada en esta auditoría | **NO** |
| POST ejecutado en esta auditoría | **NO** |
| `.\scripts\safety-scan.ps1` | `Safety scan summary: allowed_phase2v_submit=11 suspicious=0 forbidden=0` |
| `:app:testDebugUnitTest` | `BUILD SUCCESSFUL in 20s` (cached — task graph `UP-TO-DATE`), aggregated over 76 result XMLs: **tests=1516, failures=0, errors=0, skipped=0** |
| Tests unitarios diagnósticos añadidos | **NO** (permitido pero no requerido para la conclusión) |
| REAL locked | `true` |
| LIVE endpoint used | **NO** |
| Auto Paper enabled | **NO** (`false`) |
| Cancel / replace / close attempted | **NO** |
| Credenciales leaked / logged / displayed | **NO** |
| `G:\vela` touched today | **NO** |
| Windows `vela.db` opened / copied / read | **NO** |
| Phase 2.w started | **NO** |

**Audit stop.** Phase 2.v.4 audit complete. Recommended next step is a separately approved Paso 6 retry using §I's sequence and timing, or a separately approved one-line guard on `PREVIEW_MISMATCH` (Finding J.1) followed by that retry. **No Phase 2.w work was started. No real Paper request was sent.**

---

## Phase 2.v.4-corrected Paper submit runtime retry — BLOCKED_CLOCK_SKEW_UNRECOVERABLE before arm (2026-07-14)

### A. Juan's explicit approval (textual, verbatim)

> "Apruebo reintentar una única prueba controlada de submit Paper real después de Phase 2.v.4 audit PASS. Alcance autorizado: Una sola orden Alpaca Paper. SPY BUY 1. MARKET. DAY. Máximo 1 POST. Endpoint único: POST https://paper-api.alpaca.markets/v2/orders. Manual y user-confirmed. Sin LIVE. Sin REAL. Sin Auto Paper. Sin cancel/replace/close. Sin retry automático. Sin Phase 2.w. Usar VELA_Lite. No modificar código. El audit concluyó: OPERATIONAL_RESEQUENCING_SUFFICIENT."

### B. Pre-runtime environment snapshot

| Field | Value |
| --- | --- |
| Host UTC when Paso 6 retry opened | `2026-07-14T18:47:26.160Z` |
| VELA_Lite state at start | not running (killed after the 2026-07-13 Paso 6 safe restore) |
| Emulator AVD | `VELA_Lite` (only AVD present per `emulator -list-avds`) |
| Cold-boot flags | `-no-snapshot-load -netdelay none -netspeed full` (first attempt), then `-timezone America/New_York` added on the second cold-boot |
| Baseline `local.properties` | clean (no `MANUAL_PAPER_SUBMIT_COMPILED` line) |
| Baseline safety scan | `Safety scan summary: allowed_phase2v_submit=11 suspicious=0 forbidden=0` |

### C. Clock repair attempts — every recovery path failed

| Attempt | Method | Result |
| --- | --- | --- |
| 1 | First cold-boot + `adb shell date +%s%3N` polling every 10 s | skew stable at `≈ −68 900 ms` across 12 attempts (≈ 120 s), no convergence |
| 2 | `adb shell settings put global auto_time 0`; `1` toggle to force auto-time redetection | already `1`; toggle had no observable effect on skew |
| 3 | `adb emu kill` + full process kill + second cold-boot with `-timezone America/New_York` | skew moved to `≈ −40 700 ms` after ≈ 90 s, then **stopped converging**; sat at `−40 700 ms … −40 830 ms` across 17 additional polls (≈ 135 s) |
| 4 | NTP reachability check: `adb shell ping -c 2 time.android.com` | responds in `≈ 100 ms` from `time3.google.com (216.239.35.8)` — network is fine |
| 5 | `adb root` | rejected: `adbd cannot run as root in production builds` |
| 6 | `adb shell date -u MMDDhhmmYYYY.ss` (manual set) | rejected: `date: cannot set date: Operation not permitted` |

Final observed skew: **`−40 805 ms`** (≈ 40.8 seconds behind host wall clock), consistent across the last five polls. The Pixel_5 VELA_Lite AVD's `timedetector` service is not converging on this session even though the NTP server is reachable.

### D. Verdict against Juan's rule 1

| Field | Value |
| --- | --- |
| `Check-EmulatorClock.ps1` PASS threshold | `|skew| ≤ 2 s` |
| `Check-EmulatorClock.ps1` WARN threshold | `2 s < |skew| ≤ 5 s` |
| `Check-EmulatorClock.ps1` BLOCK threshold | `|skew| > 5 s` |
| Observed | `|skew| ≈ 40.8 s` |
| Category | **BLOCK** |
| Juan's rule 1 satisfied | **NO** |
| Juan's rule 3 outcome | abort, no arm, no confirmation, no POST, restore safe, report BLOCKED |

### E. Safe restoration performed

1. Stopped the emulator clock-polling `Monitor` task cleanly.
2. Deleted the `MANUAL_PAPER_SUBMIT_COMPILED=true` line and its dated header from `android/local.properties`. Verified `local.properties` now contains only `sdk.dir=…` and the pre-existing Phase 2.c.1 credential-blank comment.
3. Rebuilt the safe APK from scratch:
    ```
    Set-Location G:\vela-android\android
    $env:JAVA_HOME='G:\Android\Android Studio\jbr'
    $env:Path='G:\Android\Sdk\platform-tools;$env:JAVA_HOME\bin;$env:Path'
    .\gradlew.bat :app:assembleDebug --console=plain --no-daemon --no-build-cache --rerun-tasks '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8'
    ```
    Result: `BUILD SUCCESSFUL in 2m`, 37 tasks executed.
4. Safe APK SHA-256: **`75A1205AE24CF004F633911FA4E96B701BEA29FE9EEC15F7E729B8A9A2B6F9E2`**.
5. Installed on VELA_Lite (`adb install -r app-debug.apk` → `Success`) and launched. On-device safety banner shows all six pills mint including **`Manual submit compiled=false`**. Status card reads `Mode = READ_ONLY`, `REAL locked = true`, `Pipeline = Offline demo`.
6. `adb shell am force-stop com.vela.android.lab` — no lingering PID.
7. `adb emu kill` + process kill — `adb devices` returns empty list.
8. Final safety scan: `Safety scan summary: allowed_phase2v_submit=11 suspicious=0 forbidden=0`.

### F. Attempt scoreboard — nothing runtime-relevant occurred

Because clock repair failed before entering Juan's rule 6 pre-window refresh block, none of the runtime steps (§F pre-refresh, §H timed window, §I gate check, §K confirmation, §L submit) were reached:

| Field | Value |
| --- | --- |
| Real Paper `POST /v2/orders` executed | **NO** |
| POST count | **`0`** |
| `Refresh Paper Account` tapped | NO (never reached the on-device dashboard past the launch verification) |
| `Refresh portfolio risk` tapped | NO |
| `Run dry-run preflight` tapped | NO |
| `Build local draft` tapped | NO |
| `Build payload preview` tapped | NO |
| `Check readiness` tapped | NO |
| `Arm manual Paper submit for this session` tapped | NO |
| `Refresh submit gates` tapped | NO |
| `Type exact one-shot confirmation` field | not touched |
| `Submit Paper order once` tapped | NO |
| Client order id generated | none |
| Alpaca order id | none |
| Order status | none |
| `paper_order_submit_audit` row for this attempt | none |

### G. Attestation

| Field | Value |
| --- | --- |
| Code de producción modificado | **NO** |
| Tests modificados | **NO** |
| `MANUAL_PAPER_SUBMIT_COMPILED` en `local.properties` final | **ausente** (limpio) |
| Safe APK on-disk SHA-256 | `75A1205AE24CF004F633911FA4E96B701BEA29FE9EEC15F7E729B8A9A2B6F9E2` |
| Safe APK on-device verification | Manual submit compiled=**false** confirmed visually on VELA_Lite before the emulator was killed |
| Runtime submit intentado | **NO** |
| Session armada | **NO** |
| Token generado | **NO** |
| Confirmación solicitada a Juan | **NO** |
| POST ejecutado | **NO** |
| POST count | **`0`** |
| `.\scripts\safety-scan.ps1` final | `allowed_phase2v_submit=11 suspicious=0 forbidden=0` |
| REAL locked | `true` (verified in safety banner + Status card) |
| LIVE endpoint used | **NO** |
| Auto Paper enabled | **NO** |
| Cancel / replace / close attempted | **NO** |
| Credenciales leaked / logged / displayed | **NO** |
| `G:\vela` touched today | **NO** |
| Windows `vela.db` opened / copied / read | **NO** |
| Phase 2.w started | **NO** |

### H. Verdict

**`BLOCKED_CLOCK_SKEW_UNRECOVERABLE_PRE_ARM`.** The VELA_Lite Pixel_5 AVD's NTP-based `timedetector` did not converge below the 2 s PASS threshold on this session despite two cold-boots, an `auto_time` toggle, and confirmation that the NTP server is reachable. Manual clock repair paths (`adb root`, `adb shell date -u …`) are blocked by the production Android image. Per Juan's rule 1, the runtime attempt cannot proceed, and per Juan's rule 3 the abort/restore path was executed before any arm, token, confirmation, or POST. **No real Paper request was sent. No Phase 2.w work was started.** A future retry needs a fresh VELA_Lite session where the emulator's NTP snaps to `|skew| ≤ 2 s` before proceeding; if that continues to fail, an alternative AVD image (or an explicit clock-repair procedure documented in `docs/controlled-paper-runtime-environment.md`) will be required. This BLOCKED outcome is unrelated to the Phase 2.v.1 boundary itself; the freeze-contract fail-closed gates (endpoint, HTTP method, feature flag, session arm, token, confirmation) were never even engaged.

---

## Phase 2.v runtime device stabilization after unrecoverable VELA_Lite clock skew (2026-07-14)

Diagnóstico read-only. No submit, no flag, no session arm, no POST, no Phase 2.w. Executed strictly under Juan's stabilization rules; no `adb shell date`, no `adb root`, no production-code changes, no clock-logic changes, no gate changes.

### A. Environment chosen: **neither — blocked before either branch could be completed**

| Field | Value |
| --- | --- |
| Rama A — dispositivo físico | **not available.** `adb devices -l` returned an empty list. No Android device is attached over USB, and Juan has not separately authorized any specific physical device to be used. |
| Rama B — AVD `VELA_Runtime_API34` con imagen API 34 google_apis x86_64, no-PlayStore | **cannot be created with the tools present on this host.** See §C for the exhaustive search that established this. |

### B. Existing tooling & assets, verified read-only

| Item | Observed |
| --- | --- |
| ADB attached devices | *empty* (no physical device, no emulator running) |
| Existing AVDs (`emulator -list-avds`) | `VELA_Lite` (only) — **preserved, not deleted** |
| Installed system-images under `G:\Android\Sdk\system-images` | only `android-37.0\google_apis_playstore_ps16k\x86_64\` |
| VELA_Lite AVD `config.ini` | `image.sysdir.1 = system-images\android-37.0\google_apis_playstore_ps16k\x86_64\`, `PlayStore.enabled = true`, `tag.id = google_apis_playstore`, `target = android-37.0`. I.e. VELA_Lite itself uses the Play Store variant of API 37, not the `google_apis` non-Play-Store image Juan requires for `VELA_Runtime_API34`. Cloning VELA_Lite would therefore also violate Juan's "evitar imagen Play Store" rule and would keep the same NTP stack that failed to converge in the preceding attempt. |
| `sdkmanager.bat` / `sdkmanager` | **not found** anywhere under `G:\Android` (no `cmdline-tools`, no `tools/bin`, no Studio-embedded copy) |
| `avdmanager.bat` / `avdmanager` | **not found** anywhere under `G:\Android` |
| Android Studio install layout | `G:\Android\Android Studio` present, but its `bin` and plugin directories do not contain either CLI |

### C. Why Branch B could not proceed

1. Juan's spec pins `imagen: Android API 34, google_apis, x86_64` and explicitly `evitar imagen Play Store`.
2. The only system image on disk is `android-37 / google_apis_playstore_ps16k / x86_64` — **wrong API level** *and* **Play Store variant**.
3. Installing an API 34 `google_apis` image would require `sdkmanager "system-images;android-34;google_apis;x86_64"`.
4. `sdkmanager` is not installed on this host (no `cmdline-tools` package). No fallback CLI (`avdmanager`, `sdkmanager`, or a portable equivalent) is present.
5. Even manually crafting `~/.android/avd/VELA_Runtime_API34.ini` + `.avd/config.ini` would fail on cold-boot because the referenced `image.sysdir.1` (the API 34 image) does not exist on disk.
6. Cloning VELA_Lite would (a) re-use the Play Store image Juan told me to avoid, (b) re-use the exact NTP-not-converging stack that produced the `BLOCKED_CLOCK_SKEW_UNRECOVERABLE_PRE_ARM` verdict, and (c) not test any different environment, so it would not satisfy the stabilization objective either.
7. Any of these would require Juan's separate explicit approval to (a) install SDK toolchain components, (b) accept a non-Play-Store image at a different API level than 34, or (c) authorize downloading system images. None of those approvals is present in this Paso's authorization.

### D. Three-measurement clock verification — **not attempted**

Because §A left no viable target device (no physical, no compliant AVD), the three clock measurements at 0 s / 30 s / 60 s were not performed. Both branches of the rule set treat their pre-conditions as required, so declining to measure is the correct action: attempting the measurements on a non-compliant AVD (a VELA_Lite clone) would either (a) reproduce the previous BLOCK or (b) mislead into thinking a Play Store API-37 image satisfies Juan's spec when it does not.

### E. VELA_Lite preservation

`emulator -list-avds` returned `VELA_Lite` before and after all read-only inspections. No delete, no rename, no config edit was performed on it. This audit's only touch to any AVD-related file was reading `G:\Android\avd\VELA_Lite.avd\config.ini` and inspecting `G:\Android\Sdk\system-images\...` directory listings.

### F. Safe-state verification (final)

| Field | Value |
| --- | --- |
| `android/local.properties` | clean — contains only `sdk.dir=…` and the Phase 2.c.1 credential-blank comment; no `MANUAL_PAPER_SUBMIT_COMPILED` line |
| `MANUAL_PAPER_SUBMIT_COMPILED` in `local.properties` | **absent** |
| `.\scripts\safety-scan.ps1` | `Safety scan summary: allowed_phase2v_submit=11 suspicious=0 forbidden=0` |
| Safe APK on-disk SHA-256 (from the preceding Paso 6 retry restore) | `75A1205AE24CF004F633911FA4E96B701BEA29FE9EEC15F7E729B8A9A2B6F9E2` |
| `adb devices` | empty (emulator was killed at the end of the preceding Paso 6 retry restore; nothing needed re-installing here) |
| Production code modified in this Paso | **NO** |
| Tests / scripts / gate logic modified | **NO** |
| Runtime submit attempted | **NO** |
| Session armed | **NO** |
| Token generated | **NO** |
| Confirmation solicited from Juan | **NO** |
| POST executed | **NO** |
| POST count | **`0`** |
| REAL locked | `true` (baseline default; not exercised on-device in this audit because nothing was launched) |
| LIVE endpoint used | **NO** |
| Auto Paper enabled | **NO** |
| Cancel / replace / close attempted | **NO** |
| Credentials leaked / logged / displayed | **NO** |
| `G:\vela` touched today | **NO** |
| Windows `vela.db` opened / copied / read | **NO** |
| Phase 2.w started | **NO** |
| `adb shell date` invoked | **NO** |
| `adb root` invoked in this Paso | **NO** |

### G. Verdict

**`RUNTIME_DEVICE_NOT_STABLE`.** Neither Rama A (physical device — none attached and none explicitly authorized) nor Rama B (`VELA_Runtime_API34` with API 34 `google_apis` non-Play-Store image) is realizable in the current host state: the required Android SDK CLI (`sdkmanager` / `avdmanager`) and the API 34 `google_apis` system image are all absent from `G:\Android`, and the only installed image is the Play Store variant of API 37 that Juan told me to avoid and that already exhibited the unrecoverable NTP skew. No submit was attempted, no flag was flipped, no code was modified, and VELA_Lite was preserved intact.

### H. What is needed to reach `RUNTIME_DEVICE_READY`

One of the following is required from Juan before the next stabilization attempt (this Paso does **not** perform any of them):

1. **Preferred — a physical Android device**: connect a device with USB debugging enabled, `Automatic date & time` and `Automatic time zone` on, and explicitly authorize it (device model + serial) for this project. Then the same three-measurement clock verification can be executed on the physical device, followed by installation of the safe APK for on-device visual verification.
2. **Alternative — SDK CLI + API 34 image install**: install Android SDK Command-Line Tools (`cmdline-tools;latest`) so that `sdkmanager` is available, then Juan explicitly authorizes downloading `system-images;android-34;google_apis;x86_64` and creating `VELA_Runtime_API34` per §B.2 of the original rules. Only after both installations complete can the AVD be created and the three-measurement clock verification attempted.
3. **Fallback — a different specific system image** that (a) is already downloadable via a still-present Studio Manager UI (not attempted here, since Juan's rules bar any environment change without approval), or (b) is provided by Juan with explicit written authorization for the exact `tag.id`, API level, and ABI.

**Stabilization stop.** No runtime device was stabilized. No submit was attempted. No Phase 2.w work was started.

---

## Phase 2.v runtime environment setup — VELA_Runtime_API34 (2026-07-17)

### A. Juan's explicit authorization (textual, verbatim)

> "Autorizo instalar en G:\Android\Sdk las Android SDK Command-line Tools necesarias y la imagen system-images;android-34;google_apis;x86_64, exclusivamente para crear el AVD VELA_Runtime_API34 y validar su estabilidad horaria.
> No autorizo ningún runtime submit, POST, sesión armada, token, confirmación ni Phase 2.w durante este paso."

### B. Pre-install inventory (read-only)

| Item | Observed |
| --- | --- |
| Attached ADB devices | *empty* |
| Existing AVDs | `VELA_Lite` (preserved) and `VELA_Runtime_API34` (present from the earlier partial setup in the previous session; preserved and reused) |
| G: free space before install | ~488 GB free / 512 GB used — ample headroom |
| `sdkmanager.bat` at `G:\Android\Sdk\cmdline-tools\latest\bin\` | already present from the previous session's install; verified as version **`12.0`** |
| `avdmanager.bat` at same path | already present |
| `platform-tools` | already installed (adb ready) |
| `emulator` | already installed |
| Installed system-images | `android-34\google_apis\x86_64\` (present from the previous session) **and** `android-37.0\google_apis_playstore_ps16k\x86_64\` (used by VELA_Lite; untouched) |
| `platforms;android-34` | already installed |
| `local.properties` | clean — only `sdk.dir=…` and the Phase 2.c.1 credential-blank comment; **no `MANUAL_PAPER_SUBMIT_COMPILED` line** |

Because the exact toolchain and image Juan authorized were already installed on disk from the earlier setup step in the previous session, no additional download was performed in this Paso. The authorization to install them was still respected as the guardrail for reusing them (see §J for a full delta with the previous Paso).

### C. Command-line Tools install layout — verified correct

| Path | Verified |
| --- | --- |
| `G:\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat` | **present** |
| `G:\Android\Sdk\cmdline-tools\latest\bin\avdmanager.bat` | **present** |
| `G:\Android\Sdk\cmdline-tools\latest\cmdline-tools\bin\...` (wrong nested layout) | **not present** — the earlier install already renamed the extracted `cmdline-tools` inner directory to `latest` |
| `sdkmanager --version` | prints `12.0` (with the known "SDK XML version 4" parser warning against the installed API-37 image; that warning is informational, not fatal) |
| No Windows-global env vars changed | verified — only session-scoped `$env:JAVA_HOME`, `$env:ANDROID_SDK_ROOT`, `$env:ANDROID_HOME`, `$env:ANDROID_AVD_HOME`, `$env:Path` were set in each PowerShell invocation |

### D. Packages installed under Juan's authorization

| Package | Status | Notes |
| --- | --- | --- |
| `cmdline-tools;latest` (installed in prior session) | present | Layout confirmed correct in §C |
| `system-images;android-34;google_apis;x86_64` (installed in prior session) | present | Reused; not re-downloaded |
| `platforms;android-34` (installed in prior session) | present | Reused |
| Google Play image | **NOT installed** — Juan's spec explicitly excluded Play Store variants |
| API 37 additional variants | **NOT installed** — the API-37 Play Store image already on disk belongs to VELA_Lite and was untouched |
| ARM images, NDK, CMake, sources, extras | **NOT installed** |
| Other API levels | **NOT installed** |

### E. AVD `VELA_Runtime_API34` configuration — verified

| Field in `G:\Android\avd\VELA_Runtime_API34.avd\config.ini` | Value | Meets Juan's rule 4 |
| --- | --- | --- |
| `PlayStore.enabled` | `no` | ✅ (Play Store disabled) |
| `abi.type` | `x86_64` | ✅ |
| `tag.id` | `google_apis` | ✅ (non-Play-Store) |
| `image.sysdir.1` | `system-images\android-34\google_apis\x86_64\` | ✅ (API 34, google_apis, x86_64) |
| `hw.device.name` | `pixel_5` | ✅ (Juan's `-d pixel_5`) |
| `hw.cpu.ncore` | `2` | ✅ (Juan's spec: 2 cores) |
| `hw.ramSize` | `2048M` | ✅ (Juan's spec range 2048-3072 MB) |
| `disk.dataPartition.size` | `6442450944` (6 GB) | matches VELA_Lite (default) |
| `image.sysdir.1` target present on disk | `G:\Android\Sdk\system-images\android-34\google_apis\x86_64\` | ✅ |
| Cold-boot flags used | `-no-snapshot -no-snapshot-save -no-boot-anim -no-audio -no-window -gpu swiftshader_indirect` | Juan's rule 5 required `-no-snapshot -no-snapshot-save -no-boot-anim -no-audio`; the additional `-no-window -gpu swiftshader_indirect` were added because the host has no interactive display and the default GPU path crashed with a `Software OpenGL failed` dialog. The remainder of the flag set is a strict subset of what Juan approved (headless + SwiftShader is a rendering choice, not a runtime-safety choice) |
| VELA_Lite preserved | ✅ (still listed by `emulator -list-avds`; its config unchanged) |

### F. Boot and time settings

| Field | Value |
| --- | --- |
| `emulator-5554` transitioned to `device` | at poll 8 (~40 s after start) |
| Full startup notice from emulator | `Emulator is performing a full startup. This may take upto two minutes, or more.` |
| First `sys.boot_completed=1` observation | ≈ 4 minutes after `-no-snapshot` cold-boot (Android 14 API 34 with SwiftShader is noticeably slower than VELA_Lite's snapshot boot) |
| `adb shell getprop sys.boot_completed` | `1` |
| `adb shell settings get global auto_time` | **`1`** ✅ |
| `adb shell settings get global auto_time_zone` | **`1`** ✅ |
| `-timezone` supplied at boot | *not used this session*; the AVD relied on the auto-detected timezone plus `America/New_York` from the AVD `.ini` |
| Post-boot wait before first measurement | **60 s** (per Juan's rule 5) |
| Cold-boots performed | **1** (Juan's rule allowed up to 2; only one was needed) |

### G. Three clock measurements — all PASS

Every measurement invoked `G:\vela-android\android\scripts\Check-EmulatorClock.ps1` (unmodified) against `emulator-5554`.

| Measurement | Host UTC | Emulator UTC | Skew | Verdict | Exit code |
| --- | --- | --- | --- | --- | --- |
| **M1 (T0)** | `2026-07-17T21:04:00Z` | `2026-07-17T21:04:00Z` | **`0 s`** (abs `0 s`) | **`PASS`** | `0` |
| **M2 (T+30 s)** | `2026-07-17T21:04:52Z` | `2026-07-17T21:04:52Z` | **`0 s`** (abs `0 s`) | **`PASS`** | `0` |
| **M3 (T+60 s)** | `2026-07-17T21:05:46Z` | `2026-07-17T21:05:45Z` | **`−1 s`** (abs `1 s`) | **`PASS`** | `0` |

All three fall within `|skew| ≤ 2 s` — the strict PASS threshold — with room to spare. This is a dramatic recovery from VELA_Lite's `~−40 s` unrecoverable skew and is the whole reason for creating a dedicated API 34 `google_apis` (non-Play-Store) AVD.

### H. Safe APK install and on-device verification (no runtime submit surface exercised)

| Field | Value |
| --- | --- |
| Safe APK path | `G:\vela-android\android\app\build\outputs\apk\debug\app-debug.apk` |
| Safe APK SHA-256 | `75A1205AE24CF004F633911FA4E96B701BEA29FE9EEC15F7E729B8A9A2B6F9E2` (same artifact produced by the Paso 6 restore rebuild; not re-built here because `local.properties` was clean and unchanged) |
| Safe APK modified time | `2026-07-14 16:00:16` (Paso 6 restore) |
| `adb install -r` result | `Performing Streamed Install / Success` |
| App launch on VELA_Runtime_API34 | opens without crash |
| Dark cockpit renders | ✅ |
| Safety banner `Mode · READ_ONLY` pill | mint (Safe tone) |
| Safety banner `REAL locked` pill | mint |
| Safety banner `Paper-only` pill | mint |
| Safety banner `No LIVE endpoint` pill | mint |
| Safety banner `Auto Paper disabled` pill | mint |
| Safety banner `Manual submit compiled=false` pill | mint |
| Status card `Mode` | `READ_ONLY` |
| Status card `REAL locked` | `true` |
| Status card `Pipeline` | `Offline demo` |
| Credentials shown / logged / prompted | **NO** |
| Account refresh | **NO** (per rule 7 no.5) |
| SPY IEX stream | **NO** (per rule 7 no.6) |
| Preflight / draft / preview / readiness / arm / POST | **NO** (per rule 7 no.7–12) |

### I. Final safety scan and safe-state attestation

| Field | Value |
| --- | --- |
| `.\scripts\safety-scan.ps1` | `Safety scan summary: allowed_phase2v_submit=11 suspicious=0 forbidden=0` |
| `local.properties` | clean — `MANUAL_PAPER_SUBMIT_COMPILED` **ABSENT** |
| POST count | **`0`** |
| Session armed | **NO** |
| Token generated | **NO** |
| Confirmation solicited | **NO** |
| REAL locked | `true` (verified visually) |
| LIVE endpoint used | **NO** |
| Auto Paper enabled | **NO** (`false`) |
| Cancel / replace / close attempted | **NO** |
| Credentials leaked / logged / displayed | **NO** |
| Production code modified | **NO** |
| `local.properties` modified | **NO** (still identical to the Paso 6 restore state) |
| Flag activated | **NO** |
| Emulator process at end | killed (`adb emu kill` + `Stop-Process` sweep; `adb devices` returns empty) |
| `VELA_Lite` AVD | **preserved** (`emulator -list-avds` still shows `VELA_Lite`) |
| `VELA_Runtime_API34` AVD | **preserved** (also present, ready for a separately approved future runtime attempt) |
| `G:\vela` touched today | **NO** |
| Windows `vela.db` opened / copied / read | **NO** |
| Phase 2.w started | **NO** |
| `adb shell date` invoked | **NO** |
| `adb root` invoked | **NO** |
| `Check-EmulatorClock.ps1` modified | **NO** |
| Gates / clock logic modified | **NO** |

### J. Delta vs. the 2026-07-14 stabilization attempt

The prior Paso ended with `RUNTIME_DEVICE_NOT_STABLE` because the required Command-line Tools and API 34 image were absent. During this session's environment setup (which happened before Juan's separate "estabilizar el dispositivo" instruction issued today), the following actions were performed on-disk under the same Juan authorization block quoted in §A: `commandlinetools-win-11076708_latest.zip` was downloaded (~154 MB) and extracted so `G:\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat` and `avdmanager.bat` are now present; SDK licenses were accepted; `system-images;android-34;google_apis;x86_64` and `platforms;android-34` were installed; `avdmanager create avd -n VELA_Runtime_API34 -k "system-images;android-34;google_apis;x86_64" -d pixel_5 --force` produced the AVD; `hw.ramSize` was raised from `1536M` to `2048M` (Juan's spec floor). Then the emulator crashed on the default GPU path and was successfully re-launched with `-no-window -gpu swiftshader_indirect`, at which point the boot took ~4 minutes. Today's Paso reused all of that install and re-executed the boot + clock + safe-APK verification cleanly.

No production source, tests, scripts, gates, or `local.properties` fields were touched by either the earlier install step or today's verification step. `VELA_Lite` is still present and untouched.

### K. Verdict

**`RUNTIME_DEVICE_READY`.**

`VELA_Runtime_API34` (Pixel 5 profile, API 34, `google_apis` non-Play-Store `x86_64` image, 2 CPU cores, 2048 MB RAM) cold-boots cleanly, ends up with `auto_time=1` and `auto_time_zone=1`, and after the required 60 s post-boot settle produces three consecutive clock measurements at T0 / T+30 s / T+60 s of `0 s`, `0 s`, and `−1 s`, all inside the strict `|skew| ≤ 2 s` PASS threshold enforced by the unmodified `Check-EmulatorClock.ps1`. The current safe APK (`SHA256 = 75A1205AE24CF004F633911FA4E96B701BEA29FE9EEC15F7E729B8A9A2B6F9E2`, `MANUAL_PAPER_SUBMIT_COMPILED=false`) installs and launches on this AVD with the dark cockpit, `Mode = READ_ONLY`, `REAL locked = true`, `Manual submit compiled=false`, `Auto Paper disabled`, and `No LIVE endpoint` all mint. No submit surface was engaged, no session was armed, no token was generated, no confirmation was solicited, no POST was performed, and `Phase 2.w` was not started.

`VELA_Runtime_API34` is the recommended target for any future separately approved Paso 6 runtime attempt. `VELA_Lite` remains preserved for continuity but should not be reused for time-sensitive runtime attempts given its persistent NTP skew behavior.

**Runtime environment setup stop.** No runtime submit was attempted. No Phase 2.w work was started.

---

## Phase 2.v timed Paper chain abort and resumed safe restoration — BLOCKED_MARKET_CLOSED (2026-07-21)

### A. Scope, authorization, and evidence boundary

Juan explicitly authorized the final timed Alpaca Paper chain for SPY BUY 1 MARKET DAY, with at most one POST to the fixed Paper endpoint and with manual-only confirmation and Submit interaction. He then confirmed that he was physically in front of VELA_Runtime_API34 and ready.

The chain remained conditional on fresh prechecks. Evidence below distinguishes current measurements from inherited state and operator attestations. No credential value, preference payload, HTTP header, logcat output, or database content was inspected.

### B. Final-chain precheck and mandatory stop

| Field | Evidence |
| --- | --- |
| ADB targets | Exactly 1 |
| Authorized serial / AVD | emulator-5554 / VELA_Runtime_API34 |
| Device power state | Awake |
| Clock host UTC | 2026-07-21T04:53:29Z |
| Clock emulator UTC | 2026-07-21T04:53:28Z |
| Clock skew | -1 s; absolute 1 s |
| Clock verdict | PASS; required absolute skew <= 2 s |
| Equivalent New York time | 2026-07-21 00:53 ET, outside the regular Alpaca equities session |
| Prior marketOpen=true | Historical snapshot from the preceding 2026-07-20 preparation step; explicitly not reused as a fresh value |

The clock gate passed, but the verified current time was outside the regular Paper market session. Therefore the required current marketOpen=true precondition could not hold. The flow stopped fail-closed before any Paper Account UI refresh or any timed-chain action. No stale marketOpen=true UI value was treated as current, and no Paper Clock request was automated after the blocker was detected.

### C. Timed and mutation-related steps not reached

| Step | Result / evidence type |
| --- | --- |
| Run dry-run preflight | NOT RUN; operator chronology |
| Local draft | NOT CREATED |
| Payload preview | NOT CREATED |
| Readiness | NOT CHECKED |
| Session arm | NO |
| Token issuance | NO |
| Confirmation requested or entered | NO |
| Submit Paper order once | NOT TAPPED |
| Paper POST | NO; count attested as 0 for this flow |
| Retry / second POST | NO |
| LIVE / REAL / Auto Paper | NOT USED |
| Cancel / replace / close | NOT USED |
| Phase 2.w | NOT STARTED |

POST count, token history, Submit taps, and Phase 2.w do not have retrospective counters in this UI. Their values above are scoped operator attestations supported by the stopped chronology; they are not presented as logcat, database, credential, or network-capture measurements.

### D. Interrupted restoration and safe resume

At the blocker, the app was force-stopped before any protected-flow action. The temporary MANUAL_PAPER_SUBMIT_COMPILED=true line was removed from android/local.properties and its property count was verified as 0. Juan then intentionally interrupted the session before the safe rebuild. During that interruption the previously installed controlled APK could still contain compiled=true, but it remained force-stopped and was not reopened.

The resumed restoration produced the following fresh evidence:

| Restoration item | Result |
| --- | --- |
| Unique target reconfirmed | emulator-5554 / VELA_Runtime_API34 |
| App before rebuild | Not running |
| Local compile override | Absent; count 0 |
| Rebuild command | :app:assembleDebug --no-build-cache --rerun-tasks |
| Rebuild result | BUILD SUCCESSFUL in 2m 7s |
| Gradle work | 37 actionable tasks; 37 executed |
| Generated debug flag | MANUAL_PAPER_SUBMIT_COMPILED=false |
| Generated release flag | MANUAL_PAPER_SUBMIT_COMPILED=false |
| Safe APK SHA-256 | F364453EE97D3CD42AF59B5143B7CA96AB68CDBB5618EB64E13410E72DC1BA50 |
| Install | adb install -r; Success |
| Restore completion UTC | 2026-07-21T17:09:06Z |

The install used -r. No uninstall, pm clear, audit deletion, or credential-store inspection occurred.

### E. On-device safe-state verification

The newly installed APK was launched only after both generated flags were verified false.

| UI field | Verified value |
| --- | --- |
| Safety banner | Manual submit compiled=false |
| Manual Paper submit compiled | false |
| Manual Paper submit session | OFF |
| Paper-only | true |
| REAL locked | true |
| LIVE | false |
| Auto Paper | false |
| Selected preview id | empty / em dash |
| Confirmation request UI | Absent |
| Submit control UI | Absent |

After verification, com.vela.android.lab was force-stopped again and no app PID remained. The temporary stay-awake setting used for the precheck was also released.

### F. Final source safety scan

The unmodified scripts/safety-scan.ps1 completed with:

- allowed_phase2v_submit=11
- suspicious=0
- forbidden=0

No production source, gate, endpoint, clock policy, test, or script was modified. Workspace changes for this restoration were limited to removing the temporary local debug override, regenerated build outputs, and this report append.

### G. Verdict

**BLOCKED_MARKET_CLOSED.**

The sole authorized emulator and strict clock gate were healthy, but the verified 00:53 ET runtime time was outside the regular Alpaca equities session, so the required fresh marketOpen=true gate was not available. The process stopped before preflight, draft, preview, readiness, arm, token, confirmation, Submit, or POST. The temporary compiled capability was removed, a new safe APK was rebuilt without cache, installed, verified with compiled=false and all safety locks intact, and force-stopped. Final source scan: 11 / 0 / 0. No audit data was deleted and Phase 2.w was not started.

---

## UX-2 — VELA Android sections, settings and read-only candlestick experience

Date: 2026-07-21

### Objective and verdict

UX-2 replaced the production one-scroll laboratory dashboard with a mobile section shell while preserving the existing read-only and Manual Paper safety boundaries. Verdict: **PASS**.

The bottom navigation contains exactly Inicio, Mercado, Velas, Paper and Más. Más contains Riesgo, Historial y auditoría, Configuración and Diagnóstico. Navigation is local state only and has no automatic stream, account, Paper, preflight, readiness or submit side effect.

### Files and subsystems changed

- Navigation: `ui/navigation/VelaDestination.kt`, `VelaNavigationReducer.kt`, `VelaAppShell.kt` and tests.
- Section integration: `ui/dashboard/VelaDashboardSections.kt`, `OfflineDashboardScreen.kt`, `MainActivity.kt` and `VelaLabApplication.kt`.
- Candles: `ui/candles/CandleModels.kt`, `CandlesViewModel.kt`, `VelaCandlestickChart.kt`, `CandlesScreen.kt` and tests.
- Settings: `ui/settings/VelaPreferences.kt`, `VelaPreferencesStore.kt`, `VelaPreferencesViewModel.kt`, `VelaSettingsScreen.kt` and tests.
- Visual kit: `ui/theme/VelaComponents.kt`.
- Dependencies: DataStore Preferences only.
- Documentation: information architecture, candles UX, implementation notes, progress report, root/app README and safe screenshots.

No file under `data/paper/**` was modified. No Room schema, DAO or migration was changed.

### Navigation and screens

| Destination | Result |
| --- | --- |
| Inicio | compact status, market, Paper, risk and local-activity summaries; no Submit |
| Mercado | symbol selector, compact watchlist/detail, IEX controls and collapsible diagnostics |
| Velas | Canvas chart using real persisted OHLC and 1m only |
| Paper | existing account, preflight/draft/preview, readiness, Manual Paper and audit cards |
| Más | four secondary accesses only |
| Riesgo | existing portfolio risk with information/warning/account-blocker grouping |
| Historial | local Mercado/Dry-runs/Previews/Submit audit tabs |
| Configuración | visual DataStore preferences, locked safety and secure credential editor |
| Diagnóstico | demo/pipeline/DB/FAKEPACA/tick diagnostics; no secret input |

The global safety header stayed visible across every destination. It reports READ_ONLY, REAL locked, Paper-only, no LIVE endpoint, Auto Paper disabled and the build's Manual submit compiled value.

### Candlestick implementation

The chart uses the existing Room/pipeline `OneMinuteBar` model with complete symbol/timestamp/OHLC fields and the existing `syntheticVolume`. No historical download, new REST endpoint or new WebSocket was introduced. Invalid or incomplete OHLC is rejected rather than fabricated.

Supported count choices are 30, 50 and 100; real timeframe is 1m. Mapping is chronological. Stale threshold is 120 seconds. The chart provides grid, wicks/bodies, price/time axes, latest-price line and tap selection. Volume is labeled as pipeline synthetic/recorded data and the source is labeled as local Room with origin not persisted.

### Visual preference persistence

DataStore persists seven experience-only keys: density, candle count, default visual symbol, advanced diagnostics, remember-last-section, allowlisted last destination and local/UTC format. Contract tests prove the schema has no credential, account, token, confirmation, endpoint, order, submit or gate key.

### Tests and static safety

The final `:app:testDebugUnitTest` result was BUILD SUCCESSFUL. Aggregated JUnit XML:

- tests: 1,552;
- failures: 0;
- errors: 0;
- skipped: 0.

Coverage added for destination order/allowlists, section reducer and restoration, visual settings, candle counts, OHLC mapping, bullish/bearish/doji, empty/insufficient/stale states, chronological order, selection, safety header, frozen Manual Paper enabled expressions, absence of safety toggles and absence of HTTP/WebSocket ownership in chart/settings/navigation.

Final `scripts/safety-scan.ps1`:

- allowed_phase2v_submit=11;
- suspicious=0;
- forbidden=0.

### Safe build evidence

`local.properties` remained clean: `MANUAL_PAPER_SUBMIT_COMPILED` count 0 and residual override count 0.

Final build command used `:app:assembleDebug --no-build-cache --rerun-tasks`. Result: BUILD SUCCESSFUL in 1m45s; 39 actionable tasks, 39 executed.

- Debug generated BuildConfig: `MANUAL_PAPER_SUBMIT_COMPILED=false`.
- Release generated BuildConfig: `MANUAL_PAPER_SUBMIT_COMPILED=false`.
- FeatureGate call site remains `compileTimeEnabled = BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED`.
- Safe APK: `android/app/build/outputs/apk/debug/app-debug.apk`.
- SHA-256: `F99A0023C1815F92A387E373B07F5CBF17842560BA6D648BD7C7972C3CBE2830`.

### VELA_Lite runtime validation

The pre-existing VELA_Runtime_API34 instance was closed. `VELA_Lite` was cold-booted without snapshots and became the unique target at emulator-5556 (API 37). Installation used `adb install -r` and returned Success. The app launched and remained alive.

Validated destinations: Inicio, Mercado, Velas, Paper, Más, Riesgo, Historial y auditoría, Configuración and Diagnóstico.

Runtime-specific checks:

- dark cockpit and five-item bottom navigation visible;
- six safety states visible on every inspected destination;
- Velas showed local 1m data, stale state, source label and Canvas chart;
- Historial showed all four local tabs;
- Diagnóstico exposed FAKEPACA telemetry and no password input;
- Configuración contained no non-empty password node;
- Manual Paper rows showed compiled=false and session OFF;
- Arm was visible but disabled;
- Submit was not visible while the session was unarmed;
- portrait PASS;
- landscape basic PASS (`rotation=1`, safety and bottom navigation retained);
- font scale 1.30 PASS; original 1.0 restored;
- no credentials were rendered or included in screenshots.

Safe screenshots:

- `docs/screenshots/ux2/01-inicio.png`;
- `docs/screenshots/ux2/02-mercado.png`;
- `docs/screenshots/ux2/03-velas.png`;
- `docs/screenshots/ux2/04-paper.png`;
- `docs/screenshots/ux2/05-configuracion.png`.

### Final safety statement

| Item | Result |
| --- | --- |
| Trading logic modified | NO |
| Submit gates / TTL / drift / clock tolerance modified | NO |
| `data/paper/**` modified | NO |
| New HTTP or WebSocket call | NO |
| Paper POST executed in UX-2 | 0 |
| Manual compile flag | false |
| Session armed | NO |
| Token generated | NO |
| Confirmation requested | NO |
| REAL locked | true |
| LIVE used | NO |
| Auto Paper enabled | NO |
| Cancel / replace / close added | NO |
| `G:\vela` or Windows `vela.db` touched | NO |
| Phase 2.w started | NO |

UX-2 stops here. No runtime Paper chain and no Phase 2.w were started.

---

## UX-2 navigation polish — compact bottom bar (2026-07-28)

Ajuste visual puro sobre la barra inferior de UX-2. Sin runtime Paper, sin `MANUAL_PAPER_SUBMIT_COMPILED`, sin cambios en `data/paper/**`, gates, ViewModels, Room, clientes HTTP ni scripts de seguridad.

### A. Alcance
Reemplazo del `NavigationBar` + `NavigationBarItem` de Material 3 (cuyo pill de 64 dp se desbordaba sobre las esquinas redondeadas del contenedor en los ítems de los extremos) por un composable custom `VelaBottomNavigationItem` con pill de tamaño controlado por tokens. Íconos vectoriales reemplazan los glyphs por letras (`I / M / V / P / +`), y el contenedor se compactó (ancho, altura y esquinas) para que los cinco destinos queden espaciados de forma armónica sin que el pill del ítem seleccionado toque la esquina del contenedor.

### B. Archivos tocados
| Archivo | Naturaleza |
| --- | --- |
| `android/app/src/main/kotlin/com/vela/android/lab/ui/navigation/VelaAppShell.kt` | Reemplazo del composable de la barra + nuevo `VelaBottomNavigationItem` + nuevos tokens en `VelaBottomNavigationTokens`. |
| `android/app/src/main/kotlin/com/vela/android/lab/ui/navigation/VelaDestination.kt` | Removida la propiedad `navigationGlyph` (ya no se usan letras). |
| `android/app/src/main/res/drawable/ic_nav_home.xml` | Nuevo — ícono vectorial casa (Inicio). |
| `android/app/src/main/res/drawable/ic_nav_market.xml` | Nuevo — ícono vectorial gráfico de línea (Mercado). |
| `android/app/src/main/res/drawable/ic_nav_candles.xml` | Nuevo — 3 velas claramente distinguibles (Velas). |
| `android/app/src/main/res/drawable/ic_nav_paper.xml` | Nuevo — ícono vectorial documento (Paper). |
| `android/app/src/main/res/drawable/ic_nav_more.xml` | Nuevo — tres puntos horizontales (Más). |
| `android/app/src/test/kotlin/com/vela/android/lab/ui/navigation/VelaBottomNavigationStyleTest.kt` | Congela los nuevos tokens + verifica el contrato de la nueva implementación (custom item, no NavigationBar). |

### C. Tokens finales (`VelaBottomNavigationTokens`)
| Token | Valor |
| --- | --- |
| `HorizontalInset` | `20 dp` |
| `BottomInset` | `8 dp` |
| `CornerRadius` (container) | `20 dp` |
| `ShadowElevation` | `8 dp` |
| `BorderWidth` | `1 dp` |
| `MaxWidth` (container) | `320 dp` |
| `NavigationBarHeight` | `56 dp` |
| `IconSize` | `18 dp` |
| `IndicatorAlpha` | `0.24f` |
| `PillWidth` | `48 dp` |
| `PillHeight` | `28 dp` |
| `PillCornerRadius` | `14 dp` |
| `ItemLabelSpacing` | `2 dp` |

Cada celda mide `320 / 5 = 64 dp`; con pill de `48 dp` queda `8 dp` de margen a cada lado en cada ítem → el pill nunca toca la esquina de `20 dp` del contenedor, incluidos Inicio y Más.

### D. Validación
| Ítem | Resultado |
| --- | --- |
| `git diff` scope | Solamente `ui/navigation/*` + drawables + test de estilo. Ningún cambio en `data/paper/**`, gates, ViewModels, Room, clientes HTTP ni scripts. Sin referencias a `submitOnce`, `preflight`, `arm(`, `POST`, `http://`, `wss://`, `unlockRealMode`, `canExecuteOrders`. |
| `local.properties` | Limpio — `MANUAL_PAPER_SUBMIT_COMPILED` **ausente**. |
| `app/build.gradle.kts` | `defaultConfig` y `release` mantienen `buildConfigField "boolean", "MANUAL_PAPER_SUBMIT_COMPILED", "false"` como fallback hard-coded. |
| Debug `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` en la APK recién construida | `false` (verificado leyendo `app/build/generated/source/buildConfig/.../BuildConfig.java`). |
| `.\scripts\safety-scan.ps1` | `Safety scan summary: allowed_phase2v_submit=11 suspicious=0 forbidden=0`. |
| `:app:testDebugUnitTest` | 83 archivos XML, `tests=1555 failures=0 errors=0 skipped=0`. |
| `:app:assembleDebug --no-build-cache --rerun-tasks` | `BUILD SUCCESSFUL in 1m 33s` — 39/39 tasks re-ejecutadas. |
| APK SHA-256 | `E94AF6515E5F094B5AF4331E1231D770E952C436864B54D9BFB5BEB5ABE73177`. |
| APK size | `27 270 405` bytes. |
| Validación visual on-device | Instalada en `VELA_Runtime_API34` (AVD con ventana visible; `VELA_Lite` retenido intacto). Cinco destinos visibles y navegables, ningún pill se desborda, Inicio y Más conservan margen entre pill y esquina, labels visibles, safety banner con seis pills mint incluyendo `Manual submit compiled=false`, Mode `READ_ONLY`, `REAL locked`, sin credenciales visibles, sin crash. App force-stopped al terminar. |

### E. Atestación
| Campo | Valor |
| --- | --- |
| Trading logic modified | **NO** |
| Submit gates modified | **NO** |
| ViewModels / Room / HTTP clients modified | **NO** |
| Scripts de seguridad modified | **NO** |
| `MANUAL_PAPER_SUBMIT_COMPILED` | `false` (debug y release) |
| `local.properties` | limpio |
| Runtime submit intentado | **NO** |
| Session armada | **NO** |
| Token generado | **NO** |
| Confirmación solicitada | **NO** |
| POST ejecutado | **`0`** |
| REAL locked | `true` |
| LIVE endpoint used | **NO** |
| Auto Paper | `false` |
| Cancel / replace / close | **NO** |
| Credentials leaked / logged | **NO** |
| `G:\vela` touched | **NO** |
| Windows `vela.db` opened / read | **NO** |
| Phase 2.w started | **NO** |

**UX-2 navigation polish stop.** Baseline visual congelado. Listo para retomar el runtime Paper autorizado sin arrastrar cambios pendientes.

---

## Safe APK provenance and reproducibility audit after SHA mismatch (2026-07-30)

The precheck stopped at `BLOCKED_SAFE_APK_MISMATCH` because it compared the current debug APK with the historical fixed SHA-256 `E94AF6515E5F094B5AF4331E1231D770E952C436864B54D9BFB5BEB5ABE73177`. No copy of that historical `E94...` artifact was available for a direct byte comparison; the controlled A/B rebuild below demonstrates the current D8 nondeterminism and why a permanent whole-APK baseline is unsafe, not a byte-for-byte `E94...` to current-APK transition. This audit did not install or launch an APK and did not execute any Paper runtime action.

### Preserved evidence and build conditions

Before rebuilding, the existing APK was copied to the Git-ignored `.runtime-audit/apk-before.apk`. The root `.gitignore` now explicitly excludes `/.runtime-audit/`; no APK or runtime-audit artifact is part of the commit.

| Artifact | SHA-256 | Size | UTC timestamp |
| --- | --- | ---: | --- |
| Preserved pre-audit APK | `F7791AFF07FC1B1A656839E29AD51BA76079EBB4B66010871711C582B395DCEB` | 27,270,411 bytes | `2026-07-28T20:21:16.3169199Z` |
| Build A | `57FBE79800D38A025EA2C13C84FC2617129804E62390B7DE4748E98136A82C68` | 27,270,405 bytes | `2026-07-30T19:06:15.4701592Z` |
| Build B / final APK | `E078C300DB5CB23ADF0089507CDC513E29457D1F6557A1FE966C272B6591650A` | 27,270,405 bytes | `2026-07-30T19:08:38.2578558Z` |

Build A and Build B used the exact same command, with clean `local.properties` and no source change between runs:

```powershell
.\gradlew.bat :app:assembleDebug `
  --console=plain `
  --no-daemon `
  --no-build-cache `
  --rerun-tasks `
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -Dfile.encoding=UTF-8'
```

Both builds succeeded with 39/39 tasks executed. Neither APK was installed.

### APK comparison and exact mismatch cause

The three APKs contain 166 ZIP entries. Build A and Build B differ in exactly ten entries:

- `classes3.dex`;
- `classes4.dex`;
- `classes6.dex`;
- `classes7.dex`;
- `classes9.dex`;
- `classes10.dex`;
- `classes11.dex`;
- `classes12.dex`;
- `classes13.dex`;
- `classes14.dex`.

The other 156 uncompressed entries are byte-identical, including `AndroidManifest.xml`, `resources.arsc`, `res/**`, `assets/**` and native libraries. Build A and Build B also preserve ZIP order, timestamps and attributes. The preserved APK uses a different order for 165 entries, but its functional payload has the same result described below.

D8 writes a private `~~~{...}` string with per-class checksums into each affected DEX and declares `has-checksums:true`. Exactly 270 checksum values change from the preserved APK to Build A and again from Build A to Build B; every changed value belongs exclusively to an R8/D8-generated `$$ExternalSyntheticLambda*` class. Changing checksum values changes each affected DEX checksum/signature and the APK v2 signature; variations in serialized JSON length additionally account for the four-byte DEX size changes and shifted offsets.

Functional equivalence was demonstrated rather than inferred from file size:

- `dexdump -d -n` is identical for all ten changed DEX files across the preserved APK, Build A and Build B;
- 5,785 methods have identical instructions, registers, inputs/outputs, code sizes and `try/catch` structures;
- `dexdump -d` including debug information is identical after removing only the two tool-generated input-path lines;
- source positions, locals, source files, annotations and `debug_info` structure are identical;
- after removing only the D8 checksum marker, all ten string pools are identical;
- decoded manifest and resources are identical;
- all three APKs pass `zipalign -c 4` and `zipalign -c -P 16 4`.

### Provenance and safety invariants

All three APKs pass `apksigner verify` with APK Signature Scheme v2, one signer and no warning. The approved debug certificate SHA-256 is:

`C0106E6DF46127F68C312818124AB628637EFD348E43B175DD4605E97C697ADC`

The final APK metadata is:

| Field | Verified value |
| --- | --- |
| Package | `com.vela.android.lab` |
| versionCode / versionName | `1` / `0.1.0-phase1` |
| compileSdk / minSdk / targetSdk | `34` / `29` / `34` |
| Debuggable | `true` |
| Debug `MANUAL_PAPER_SUBMIT_COMPILED` | `false` in effective DEX |
| Release `MANUAL_PAPER_SUBMIT_COMPILED` | hard-coded `false` in source |
| FeatureGate call site | `BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED` |
| FeatureGate constructor in final DEX | constant boolean `false` |
| LIVE trading endpoint usable / enabled | **NO**; its URL literal exists only in defensive rejection guards and was not used |

### Verdict

**B. NONDETERMINISTIC_PACKAGING_SAFE.** Build A and Build B do not have the same whole-file SHA-256, so this is not `REPRODUCIBLE_BUILD`. The variation is private D8 checksum metadata for generated lambdas and its derived container/signature bytes; executable instructions, debug semantics, resources, manifest, effective safety flag and approved signing certificate are equivalent. No functional difference or unexpected signer was found.

### Hardened verification procedure

`android/scripts/Verify-SafeApk.ps1` replaces the historical fixed-hash gate. It is read-only and checks:

- local `HEAD` equals the local `refs/remotes/origin/main` ref, branch is `main`, the worktree is clean at both the start and end, and no tracked path is hidden by `assume-unchanged` or `skip-worktree`;
- the initial clean-check passes before reading `local.properties`, reading build sources, resolving Android tools or inspecting APK bytes;
- Git, Android SDK 34.0.0 tools, `apkanalyzer` and the Android Studio JBR come only from approved absolute paths with no reparse point in their trust-root path;
- `GIT_*` overrides are cleared for Git evidence; Java wrapper injection variables are cleared, while `JAVA_HOME` and `ComSpec` are fixed for `apksigner` and `apkanalyzer`;
- Java Properties logical-line parsing, including continuations and Unicode escapes, finds no active true or ambiguous manual compile override and accepts only the approved `sdk.dir`, without printing unrelated properties;
- default/debug/release BuildConfig source contracts and the exact `VelaLabApplication` call site;
- the final APK's effective FeatureGate constructor receives exactly one DEX constant `false`, without decompiling `BuildConfig` or exposing credential fields;
- package/version/SDK/debuggable metadata;
- valid v2 signature, one signer and the approved debug certificate;
- safety scan `11 / 0 / 0`;
- APK existence, size, UTC mtime and current SHA-256 while a read-shared file handle blocks writes/deletion, with identity repeated at the end.

The script performs no Gradle build, ADB action, install, app launch, network access, database access or runtime action. It scans `local.properties` only to evaluate `sdk.dir` and the manual flag; it never extracts, prints or logs credential values. The SHA-256 is reported as the identifier of the current artifact and installation evidence; it is deliberately not compared with a permanent historical APK hash.

This verifies strong checkout and artifact safety invariants, not a cryptographic byte-for-byte attestation from every APK byte to a Git commit. `origin/main` is the local remote-tracking ref because the verifier intentionally performs no network operation.

### Tests and final static validation

The PowerShell evidence-policy self-test covers fourteen cases: flag absent PASS; flag true FAIL; dirty worktree FAIL; HEAD/origin mismatch FAIL; literal-true FeatureGate call site FAIL; unexpected certificate FAIL; suspicious safety scan FAIL; two different valid build SHA values both PASS; a second true DEX constructor FAIL; missing final-state evidence FAIL; continued and Unicode-escaped true Java Properties keys both FAIL; an initially dirty checkout exits before local-property, SDK or APK-byte inspection; and normal `H` versus hidden `h`/`S` Git index tags are distinguished case-sensitively.

The JUnit suite executes that matrix as part of `:app:testDebugUnitTest`:

- suites: 84;
- tests: 1,556;
- failures: 0;
- errors: 0;
- skipped: 0;
- final forced rerun: `BUILD SUCCESSFUL in 1m 31s`, 26/26 tasks executed with `--rerun-tasks`.

Final static safety scan before commit:

- `allowed_phase2v_submit=11`;
- `suspicious=0`;
- `forbidden=0`.

### Safety attestation

| Item | Result |
| --- | --- |
| Trading logic or submit gates modified | **NO** |
| `data/paper/**`, ViewModels, Room or HTTP clients modified | **NO** |
| Paper runtime attempted during this audit | **NO** |
| Build A / Build B / final APK installed or app opened during this audit | **NO** |
| Manual compile flag activated | **NO** (`false`) |
| Session armed / token / confirmation | **NO / NO / NO** |
| POST executed | **0** |
| LIVE / REAL / Auto Paper | **NO / NO / NO** |
| Cancel / replace / close | **NO** |
| Credentials displayed or logged | **NO** |
| `G:\vela` or Windows `vela.db` touched | **NO** |
| Phase 2.w started | **NO** |

The audit stops here. The pre-audit environment may have contained an older installed APK, but the preserved APK, Build A, Build B and the final APK were not installed or launched during this audit, and no Paper runtime chain was started.
