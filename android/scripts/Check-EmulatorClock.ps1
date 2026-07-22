<#
.SYNOPSIS
    Phase 2.v.2 read-only preflight: verify emulator UTC is close enough to host UTC
    before any controlled Phase 2.v manual Paper submit runtime attempt.

.DESCRIPTION
    Reads the target emulator's UTC epoch via `adb shell date -u +%s`, compares to the
    host's UTC epoch, prints both plus the absolute skew in seconds, and returns
    one of three exit codes:

        0 = PASS  (|skew| <= PassMaxSeconds)
        2 = WARN  (PassMaxSeconds < |skew| <= WarnMaxSeconds)
        1 = BLOCK (|skew| > WarnMaxSeconds  OR any error reading times)

    The script is strictly read-only:
      * it never sets the emulator clock,
      * it never launches, installs, or force-stops the app,
      * it never reads app-private data or credentials,
      * it never modifies local.properties or any BuildConfig field,
      * it never opens a network connection.

    Intended to be run BEFORE toggling MANUAL_PAPER_SUBMIT_COMPILED=true, and again
    right before starting the runtime flow. If it does not return PASS, do NOT proceed
    with the controlled runtime submit attempt (see
    docs/controlled-paper-runtime-environment.md).

.PARAMETER Serial
    ADB serial of the target emulator. Defaults to 'emulator-5554'.

.PARAMETER AdbPath
    Full path to adb.exe. Defaults to G:\Android\Sdk\platform-tools\adb.exe.

.PARAMETER PassMaxSeconds
    Absolute skew (in seconds) at or below which the script returns PASS. Default 2.

.PARAMETER WarnMaxSeconds
    Absolute skew (in seconds) at or below which the script returns WARN. Default 5.

.EXAMPLE
    PS> .\Check-EmulatorClock.ps1
    Reads emulator-5554 vs host and prints a PASS/WARN/BLOCK verdict.

.EXAMPLE
    PS> .\Check-EmulatorClock.ps1 -Serial emulator-5556 -PassMaxSeconds 1
    Uses a stricter 1-second threshold against a different emulator serial.

.NOTES
    Do not use this script (or its output) to justify any change to the app-level
    Phase 2.v.1 freshness policy. Its only purpose is to gate the OPERATOR's
    decision to enable the debug submit flag for a runtime attempt.
#>
[CmdletBinding()]
param(
    [string]$Serial = 'emulator-5554',
    [string]$AdbPath = 'G:\Android\Sdk\platform-tools\adb.exe',
    [int]$PassMaxSeconds = 2,
    [int]$WarnMaxSeconds = 5
)

$ErrorActionPreference = 'Stop'

function Fail-Block([string]$message) {
    Write-Output ("[BLOCK] " + $message)
    Write-Output "Do NOT enable MANUAL_PAPER_SUBMIT_COMPILED. Do NOT start a controlled runtime submit."
    exit 1
}

if (-not (Test-Path -LiteralPath $AdbPath -PathType Leaf)) {
    Fail-Block "adb.exe not found at '$AdbPath'."
}

# Enumerate attached devices and require the requested serial to be present as
# a live 'device' (not 'offline', 'unauthorized', or missing).
$devicesRaw = & $AdbPath 'devices'
if ($LASTEXITCODE -ne 0) {
    Fail-Block "adb devices failed with exit code $LASTEXITCODE."
}
$serialFound = $false
foreach ($line in $devicesRaw) {
    $trimmed = $line.Trim()
    if ($trimmed -match ('^' + [regex]::Escape($Serial) + '\s+device\b')) {
        $serialFound = $true
        break
    }
}
if (-not $serialFound) {
    Fail-Block "Emulator serial '$Serial' is not attached as 'device'."
}

# Sample device UTC epoch AFTER host UTC epoch so any measurement latency
# biases the observed skew toward "device ahead of host", not "device behind
# host". This makes the read-only check conservative: it will not silently
# hide a real drift by attributing it to sampling latency.
$hostEpoch = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$deviceEpochRaw = & $AdbPath '-s' $Serial 'shell' 'date' '-u' '+%s'
if ($LASTEXITCODE -ne 0) {
    Fail-Block "adb -s $Serial shell date -u +%s failed with exit code $LASTEXITCODE."
}

$deviceEpochStr = ($deviceEpochRaw -join "`n").Trim()
if (-not ($deviceEpochStr -match '^\d+$')) {
    Fail-Block ("Unexpected device epoch output: '" + $deviceEpochStr + "'.")
}
$deviceEpoch = [long]$deviceEpochStr

$skewSeconds = $deviceEpoch - $hostEpoch
$absSkew = [math]::Abs($skewSeconds)

$hostIso = [DateTimeOffset]::FromUnixTimeSeconds($hostEpoch).UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ssZ')
$deviceIso = [DateTimeOffset]::FromUnixTimeSeconds($deviceEpoch).UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ssZ')

Write-Output "Phase 2.v.2 emulator clock preflight"
Write-Output ("  Emulator serial       : " + $Serial)
Write-Output ("  Host UTC              : " + $hostIso)
Write-Output ("  Emulator UTC          : " + $deviceIso)
Write-Output ("  Skew (device - host)  : " + $skewSeconds + " s (absolute " + $absSkew + " s)")
Write-Output ("  PASS threshold        : |skew| <= " + $PassMaxSeconds + " s")
Write-Output ("  WARN threshold        : " + $PassMaxSeconds + " s < |skew| <= " + $WarnMaxSeconds + " s")
Write-Output ("  BLOCK threshold       : |skew| > " + $WarnMaxSeconds + " s")

if ($absSkew -le $PassMaxSeconds) {
    Write-Output "[PASS] Emulator clock is within tolerance. Safe to proceed with the app-level Phase 2.v.1 preflight."
    exit 0
}

if ($absSkew -le $WarnMaxSeconds) {
    Write-Output "[WARN] Emulator clock skew is above the PASS threshold but under BLOCK. Prefer to cold-boot the emulator with network time enabled before continuing."
    Write-Output "See docs/controlled-paper-runtime-environment.md for safe repair options."
    exit 2
}

Write-Output "[BLOCK] Emulator clock skew exceeds the BLOCK threshold."
Write-Output "Do NOT enable MANUAL_PAPER_SUBMIT_COMPILED. Do NOT start a controlled runtime submit."
Write-Output "Cold-boot the emulator with network time enabled (see docs/controlled-paper-runtime-environment.md) and re-run this preflight until it returns PASS."
exit 1
