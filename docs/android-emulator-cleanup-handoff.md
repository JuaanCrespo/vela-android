# Android emulator cleanup handoff

Date/time: 2026-07-09 12:57:56 -03:00 / 2026-07-09T15:57:56Z

## Result

Cleanup completed. The previous heavy AVD `Pixel_10_Pro_XL` was removed and `VELA_Lite` remains the active emulator under `G:\Android\avd`.

No Phase 2.w work was started. No runtime submit was performed. No Paper POST was executed.

## What was deleted

- `G:\Android\avd\Pixel_10_Pro_XL.avd`
- `G:\Android\avd\Pixel_10_Pro_XL.ini`

Deletion method:

- `G:\Android\Sdk\cmdline-tools\latest\bin\avdmanager.bat` was not available under `G:\Android`.
- Manual fallback was used after resolving and verifying the exact absolute target paths.

Estimated space liberated:

- `G:\Android\avd\Pixel_10_Pro_XL.avd`: 12,830,318,338 bytes / 12,235.95 MiB / 11.949 GiB
- `G:\Android\avd\Pixel_10_Pro_XL.ini`: 83 bytes
- Estimated total: 12,830,318,421 bytes / approximately 11.949 GiB

## What was not deleted

- `G:\Android\avd\VELA_Lite.avd`
- `G:\Android\avd\VELA_Lite.ini`
- `G:\Android\Sdk`
- `G:\Android\Sdk\system-images\android-37.0\google_apis_playstore_ps16k\x86_64`
- `G:\vela-android`
- Gradle global caches
- `G:\vela`
- Windows `vela.db`

Important: both `Pixel_10_Pro_XL` and `VELA_Lite` referenced the same shared SDK image:

`system-images\android-37.0\google_apis_playstore_ps16k\x86_64\`

That shared image was retained.

## AVD state before deletion

`emulator.exe -list-avds` returned:

- `Pixel_10_Pro_XL`
- `VELA_Lite`

Pre-delete measured sizes:

| Path | Size |
| --- | ---: |
| `G:\Android\avd\Pixel_10_Pro_XL.avd` | 12,830,318,338 bytes / 11.949 GiB |
| `G:\Android\avd\Pixel_10_Pro_XL.ini` | 83 bytes |
| `G:\Android\avd\VELA_Lite.avd` | 2,244,272,408 bytes / 2.090 GiB |
| `G:\Android\avd\VELA_Lite.ini` | 77 bytes |

## Config comparison before deletion

| Field | Pixel_10_Pro_XL | VELA_Lite |
| --- | --- | --- |
| `image.sysdir.1` | `system-images\android-37.0\google_apis_playstore_ps16k\x86_64\` | `system-images\android-37.0\google_apis_playstore_ps16k\x86_64\` |
| `PlayStore.enabled` | `true` | `true` |
| `abi.type` | `x86_64` | `x86_64` |
| `target` | `android-37.0` | `android-37.0` |
| `hw.ramSize` | `2048` | `2048` |
| `hw.cpu.ncore` | `2` | `2` |
| `disk.dataPartition.size` | `6G` | `6442450944` |
| `skin.name` | `pixel_10_pro_xl` | `pixel_5` |
| `skin.path` | `G:\Android\Sdk\skins\pixel_10_pro_xl` | `G:\Android\Sdk\skins\pixel_5` |
| `hw.lcd.width` | `1344` | `1080` |
| `hw.lcd.height` | `2992` | `2340` |
| `hw.lcd.density` | `480` | `440` |

## AVD state after deletion

`emulator.exe -list-avds` returned:

- `VELA_Lite`

Confirmed absent:

- `G:\Android\avd\Pixel_10_Pro_XL.avd`
- `G:\Android\avd\Pixel_10_Pro_XL.ini`

Confirmed present:

- `G:\Android\avd\VELA_Lite.avd`
- `G:\Android\avd\VELA_Lite.ini`
- `G:\Android\Sdk\system-images\android-37.0\google_apis_playstore_ps16k\x86_64`

Final measured `VELA_Lite` size after post-cleanup boot/install:

- `G:\Android\avd\VELA_Lite.avd`: 4,135,569,816 bytes / 3.852 GiB
- `G:\Android\avd\VELA_Lite.ini`: 77 bytes

## Active emulator

- AVD: `VELA_Lite`
- Location: `G:\Android\avd`
- Profile/skin: Pixel 5
- API/image: Android API 37.0, `google_apis_playstore_ps16k`, `x86_64`
- RAM: 2048 MB
- Cores: 2
- Resolution/density: 1080x2340, density 440

Post-delete boot command used:

`G:\Android\Sdk\emulator\emulator.exe -avd VELA_Lite -no-snapshot-load -no-snapshot-save -no-boot-anim -no-audio`

Boot result:

- Serial: `emulator-5554`
- `sys.boot_completed=1`

## Clock validation

The first post-delete clock check after cold boot returned BLOCK because the emulator clock was behind host UTC:

- Initial skew: `-30 s`

No runtime submit was active, no token existed, and no confirmation flow was started. The emulator was corrected only through allowed pre-runtime environment handling:

- `settings put global auto_time 1`
- `settings put global auto_time_zone 1`
- `settings put global ntp_server time.google.com`
- cold boot with `-no-snapshot-load -no-snapshot-save`
- no `adb shell date` clock forcing was used

Final `Check-EmulatorClock.ps1` result:

- Host UTC: `2026-07-09T15:55:03Z`
- Emulator UTC: `2026-07-09T15:55:02Z`
- Skew: `-1 s`
- Absolute skew: `1 s`
- Verdict: `[PASS] Emulator clock is within tolerance.`

## Safe app state

APK:

- `G:\vela-android\android\app\build\outputs\apk\debug\app-debug.apk`
- Installed with `adb install -r`
- Opened with `adb shell am start -n com.vela.android.lab/.MainActivity`

Safe flag checks:

- `local.properties`: `LOCAL_OVERRIDE_COUNT=0`
- Debug `BuildConfig`: `MANUAL_PAPER_SUBMIT_COMPILED = false`

Visual/runtime-safe checks:

- App opens: YES
- `Mode READ_ONLY`: YES
- `REAL locked=true`: YES
- No crash: YES
- No credentials exposed: YES
- Read-only/no-order text visible: YES (`No orders`, `No account`, `No live endpoint`)

## Safety scan

Command:

`Set-Location G:\vela-android\android; .\scripts\safety-scan.ps1`

Result:

`Safety scan summary: allowed_phase2v_submit=11 suspicious=0 forbidden=0`

## Trading/runtime safety

- `MANUAL_PAPER_SUBMIT_COMPILED=false`
- REAL locked: YES
- LIVE absent: YES
- Auto Paper absent: YES
- Cancel/replace/close: absent / not used
- Session armed: NO
- Token generated: NO
- Confirmation requested: NO
- Runtime submit performed: NO
- POST `/v2/orders` executed: NO
- Phase 2.w started: NO

## Next chronological step

Next chronological step remains: controlled Paper runtime retry only with a new, explicit one-attempt approval from Juan, after the emulator clock preflight returns PASS and before any submit flow is armed.
