$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$verifierPath = (Resolve-Path -LiteralPath (
    Join-Path $PSScriptRoot '..\Verify-SafeApk.ps1'
)).Path
. $verifierPath

$script:TestsRun = 0

function New-ValidEvidence {
    $head = 'de8fe2a55ee4a571a0f739248bfbb8a5a3d37475'
    $buildScript = @'
android {
    defaultConfig {
        buildConfigField("boolean", "MANUAL_PAPER_SUBMIT_COMPILED", "false")
    }
    buildTypes {
        getByName("debug") {
            val manualPaperCompiled =
                loadLocalBooleanProperty("MANUAL_PAPER_SUBMIT_COMPILED")
            buildConfigField(
                "boolean",
                "MANUAL_PAPER_SUBMIT_COMPILED",
                manualPaperCompiled.toString(),
            )
        }
        getByName("release") {
            buildConfigField("boolean", "MANUAL_PAPER_SUBMIT_COMPILED", "false")
        }
    }
}
'@
    $applicationSource = @'
class VelaLabApplication {
    val paperManualExecutionFeatureGate by lazy {
        PaperManualExecutionFeatureGate(
            compileTimeEnabled = BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED,
        )
    }
}
'@
    $badging = @'
package: name='com.vela.android.lab' versionCode='1' versionName='0.1.0-phase1' platformBuildVersionName='' compileSdkVersion='34' compileSdkVersionCodename='14'
sdkVersion:'29'
targetSdkVersion:'34'
application-debuggable
'@
    $signature = @'
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): false
Number of signers: 1
Signer #1 certificate SHA-256 digest: c0106e6df46127f68c312818124ab628637efd348e43b175dd4605e97c697adc
'@
    $dexCode = @'
.method private static final paperManualExecutionFeatureGate_delegate$lambda$28()Lcom/vela/android/lab/data/paper/submit/PaperManualExecutionFeatureGate;
    .registers 2
    new-instance v0, Lcom/vela/android/lab/data/paper/submit/PaperManualExecutionFeatureGate;
    .line 248
    nop
    .line 247
    const/4 v1, 0x0
    invoke-direct {v0, v1}, Lcom/vela/android/lab/data/paper/submit/PaperManualExecutionFeatureGate;-><init>(Z)V
    return-object v0
.end method
'@

    return @{
        Head = $head
        OriginHead = $head
        Branch = 'main'
        StatusLines = @()
        EndHead = $head
        EndOriginHead = $head
        EndBranch = 'main'
        EndStatusLines = @()
        ManualFlagLines = @()
        BuildScriptText = $buildScript
        ApplicationSourceText = $applicationSource
        SafetyExitCode = 0
        SafetyText =
            'Safety scan summary: allowed_phase2v_submit=11 suspicious=0 forbidden=0'
        AaptExitCode = 0
        BadgingText = $badging
        SignatureExitCode = 0
        SignatureText = $signature
        DexExitCode = 0
        DexCodeText = $dexCode
        ApkSha256 =
            '57FBE79800D38A025EA2C13C84FC2617129804E62390B7DE4748E98136A82C68'
        EndApkSha256 =
            '57FBE79800D38A025EA2C13C84FC2617129804E62390B7DE4748E98136A82C68'
        ApkLength = 27270405
        EndApkLength = 27270405
        ApkLastWriteTimeUtc = '2026-07-30T19:08:38.2578558Z'
        EndApkLastWriteTimeUtc = '2026-07-30T19:08:38.2578558Z'
    }
}

function Assert-Scenario {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)]$Evidence,
        [Parameter(Mandatory)][bool]$ExpectedPass,
        [string]$ExpectedCode
    )

    $result = Test-SafeApkEvidence -Evidence $Evidence
    if ([bool]$result.Passed -ne $ExpectedPass) {
        throw "Scenario '$Name' returned Passed=$($result.Passed)."
    }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedCode) -and
        @($result.Codes) -notcontains $ExpectedCode
    ) {
        throw "Scenario '$Name' did not return code $ExpectedCode."
    }
    $script:TestsRun++
}

# 1. An absent local flag is safe.
$case = New-ValidEvidence
Assert-Scenario 'flag absent' $case $true

# 2. An explicitly true local compile flag fails closed.
$case = New-ValidEvidence
$case.ManualFlagLines = @('MANUAL_PAPER_SUBMIT_COMPILED=true')
Assert-Scenario 'flag true' $case $false 'LOCAL_FLAG_UNSAFE'

# 3. Any dirty worktree evidence fails.
$case = New-ValidEvidence
$case.StatusLines = @(' M tracked-file')
Assert-Scenario 'dirty worktree' $case $false 'WORKTREE_DIRTY'

# 4. HEAD must equal the local origin/main ref.
$case = New-ValidEvidence
$case.OriginHead = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
Assert-Scenario 'head origin mismatch' $case $false 'HEAD_ORIGIN_MISMATCH'

# 5. Safe BuildConfig source cannot compensate for a literal-true call site.
$case = New-ValidEvidence
$case.ApplicationSourceText = $case.ApplicationSourceText.Replace(
    'BuildConfig.MANUAL_PAPER_SUBMIT_COMPILED',
    'true'
)
Assert-Scenario 'literal true call site' $case $false 'APPLICATION_GATE_TRUE'

# 6. A different signer fingerprint fails.
$case = New-ValidEvidence
$case.SignatureText = $case.SignatureText.Replace(
    'c0106e6df46127f68c312818124ab628637efd348e43b175dd4605e97c697adc',
    '00106e6df46127f68c312818124ab628637efd348e43b175dd4605e97c697adc'
)
Assert-Scenario 'unexpected certificate' $case $false 'CERTIFICATE_MISMATCH'

# 7. Any suspicious safety-scan result fails.
$case = New-ValidEvidence
$case.SafetyText =
    'Safety scan summary: allowed_phase2v_submit=11 suspicious=1 forbidden=0'
Assert-Scenario 'suspicious safety scan' $case $false 'SAFETY_SCAN_UNSAFE'

# 8. A new valid build SHA is evidence, never a permanent source baseline.
$firstBuild = New-ValidEvidence
$secondBuild = New-ValidEvidence
$secondBuild.ApkSha256 =
    'E078C300DB5CB23ADF0089507CDC513E29457D1F6557A1FE966C272B6591650A'
$secondBuild.EndApkSha256 = $secondBuild.ApkSha256
$firstResult = Test-SafeApkEvidence -Evidence $firstBuild
$secondResult = Test-SafeApkEvidence -Evidence $secondBuild
if (-not $firstResult.Passed -or -not $secondResult.Passed -or
    $firstBuild.ApkSha256 -eq $secondBuild.ApkSha256
) {
    throw 'A historical SHA mismatch incorrectly changed provenance validity.'
}
$script:TestsRun++

# 9. A second true gate construction in the same method fails closed.
$case = New-ValidEvidence
$case.DexCodeText = $case.DexCodeText.Replace(
    '    return-object v0',
@'
    new-instance v0, Lcom/vela/android/lab/data/paper/submit/PaperManualExecutionFeatureGate;
    const/4 v1, 0x1
    invoke-direct {v0, v1}, Lcom/vela/android/lab/data/paper/submit/PaperManualExecutionFeatureGate;-><init>(Z)V
    return-object v0
'@
)
Assert-Scenario `
    'second true DEX constructor in same method' `
    $case `
    $false `
    'FEATURE_GATE_DEX_UNSAFE'

# 10. Missing final-state evidence fails closed.
$case = New-ValidEvidence
foreach ($name in @(
    'EndHead',
    'EndOriginHead',
    'EndBranch',
    'EndStatusLines',
    'EndApkSha256',
    'EndApkLength',
    'EndApkLastWriteTimeUtc'
)) {
    [void]$case.Remove($name)
}
Assert-Scenario `
    'missing end evidence' `
    $case `
    $false `
    'EVIDENCE_INCOMPLETE'

# 11. A continued Java Properties key cannot hide a true flag.
$case = New-ValidEvidence
$parsedFlag = @(Get-ManualFlagLinesFromPhysicalLines -PhysicalLines @(
    'MANUAL_PAPER_SUBMIT_COMPI\'
    '    LED=true'
))
if ($parsedFlag.Count -ne 1 -or
    $parsedFlag[0] -cne 'MANUAL_PAPER_SUBMIT_COMPILED=true'
) {
    throw 'Continued Java Properties key was not normalized exactly.'
}
$case.ManualFlagLines = $parsedFlag
Assert-Scenario `
    'continued true flag' `
    $case `
    $false `
    'LOCAL_FLAG_UNSAFE'

# 12. A Unicode-escaped Java Properties key cannot hide a true flag.
$case = New-ValidEvidence
$parsedFlag = @(Get-ManualFlagLinesFromPhysicalLines -PhysicalLines @(
    'MANUAL_PAPER_SUBMIT_COMPILE\u0044=true'
))
if ($parsedFlag.Count -ne 1 -or
    $parsedFlag[0] -cne 'MANUAL_PAPER_SUBMIT_COMPILED=true'
) {
    throw 'Unicode-escaped Java Properties key was not normalized exactly.'
}
$case.ManualFlagLines = $parsedFlag
Assert-Scenario `
    'unicode escaped true flag' `
    $case `
    $false `
    'LOCAL_FLAG_UNSAFE'

# 13. A dirty initial checkout exits before APK or SDK inspection.
$script:EarlyExitHead = 'de8fe2a55ee4a571a0f739248bfbb8a5a3d37475'
$script:VerifierFixturePath = $verifierPath

function Resolve-ContainedApkPath {
    param($RequestedPath, $AndroidRoot, $RepoRoot)
    return $script:VerifierFixturePath
}
function Get-GitSnapshot {
    param($GitPath, $RepoRoot)
    return [pscustomobject]@{
        Head = $script:EarlyExitHead
        OriginHead = $script:EarlyExitHead
        Branch = 'main'
        StatusLines = @(' M tracked-file')
    }
}
function Resolve-AndroidSdkRoot {
    throw 'SDK_RESOLUTION_OCCURRED_BEFORE_INITIAL_GIT_GATE'
}
function Get-ApkSnapshot {
    throw 'APK_READ_OCCURRED_BEFORE_INITIAL_GIT_GATE'
}
function Get-ManualFlagLines {
    throw 'LOCAL_PROPERTIES_READ_OCCURRED_BEFORE_INITIAL_GIT_GATE'
}
function Resolve-PathWithoutReparse {
    param($Path, $AnchorPath, $PathType)
    return [System.IO.Path]::GetFullPath($Path)
}

$earlyResult = Invoke-SafeApkVerification
$earlyText = @($earlyResult.Lines) -join "`n"
if ([int]$earlyResult.ExitCode -ne 1 -or
    $earlyText -notmatch 'WORKTREE_DIRTY'
) {
    throw 'Dirty checkout did not fail at the initial gate.'
}
$script:TestsRun++

Write-Output "VERIFY_SAFE_APK_SELF_TEST_PASS tests=$script:TestsRun"
