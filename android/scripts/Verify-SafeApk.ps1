[CmdletBinding()]
param(
    [string]$ApkPath,
    [string]$SdkRoot
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$script:ExpectedApplicationId = 'com.vela.android.lab'
$script:ExpectedVersionCode = '1'
$script:ExpectedVersionName = '0.1.0-phase1'
$script:ExpectedMinSdk = '29'
$script:ExpectedTargetSdk = '34'
$script:ExpectedCompileSdk = '34'
$script:ExpectedCertificateSha256 =
    'C0106E6DF46127F68C312818124AB628637EFD348E43B175DD4605E97C697ADC'
$script:ManualCompileFlag = 'MANUAL_PAPER_SUBMIT_COMPILED'
$script:ApprovedSdkRoot = 'G:\Android\Sdk'
$script:ApprovedGitPath = 'G:\Programs\Git\Git\cmd\git.exe'
$script:ApprovedGitRoot = 'G:\Programs\Git\Git'
$script:ApprovedJavaHome = 'G:\Android\Android Studio 2026.1.2\jbr'

function Get-EvidenceValue {
    param(
        [Parameter(Mandatory)]$Evidence,
        [Parameter(Mandatory)][string]$Name,
        $DefaultValue = $null
    )

    if ($Evidence -is [System.Collections.IDictionary] -and
        $Evidence.Contains($Name)
    ) {
        return $Evidence[$Name]
    }

    $property = $Evidence.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $DefaultValue
    }
    return $property.Value
}

function Add-FailureCode {
    param(
        [Parameter(Mandatory)]
        [AllowEmptyCollection()]
        [System.Collections.Generic.List[string]]$Codes,
        [Parameter(Mandatory)][string]$Code
    )

    if (-not $Codes.Contains($Code)) {
        $Codes.Add($Code)
    }
}

function Test-EvidenceHasProperty {
    param(
        [Parameter(Mandatory)]$Evidence,
        [Parameter(Mandatory)][string]$Name
    )

    if ($Evidence -is [System.Collections.IDictionary]) {
        return $Evidence.Contains($Name)
    }
    return $null -ne $Evidence.PSObject.Properties[$Name]
}

function New-VerificationResult {
    param(
        [Parameter(Mandatory)][int]$ExitCode,
        [Parameter(Mandatory)]
        [AllowEmptyCollection()]
        [string[]]$Lines
    )

    return [pscustomobject]@{
        ExitCode = $ExitCode
        Lines = @($Lines)
    }
}

function Test-InitialGitSnapshot {
    param([Parameter(Mandatory)]$Snapshot)

    $codes = New-Object 'System.Collections.Generic.List[string]'
    $hashPattern = '^(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})$'
    if ($Snapshot.Head -notmatch $hashPattern -or
        $Snapshot.OriginHead -notmatch $hashPattern -or
        $Snapshot.Head -ne $Snapshot.OriginHead
    ) {
        Add-FailureCode -Codes $codes -Code 'HEAD_ORIGIN_MISMATCH'
    }
    if ($Snapshot.Branch -ne 'main') {
        Add-FailureCode -Codes $codes -Code 'BRANCH_NOT_MAIN'
    }
    if (@(Get-NonEmptyLines $Snapshot.StatusLines).Count -ne 0) {
        Add-FailureCode -Codes $codes -Code 'WORKTREE_DIRTY'
    }
    return [pscustomobject]@{
        Passed = $codes.Count -eq 0
        Codes = @($codes)
    }
}

function Get-NonEmptyLines {
    param($Value)

    return @(
        @($Value) |
            ForEach-Object { if ($null -ne $_) { $_.ToString() } } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )
}

function Remove-KotlinComments {
    param([Parameter(Mandatory)][string]$Text)

    if ($Text.Contains('"""')) {
        throw 'Triple-quoted source cannot be verified safely.'
    }

    $builder = New-Object System.Text.StringBuilder
    $inLineComment = $false
    $blockCommentDepth = 0
    $inString = $false
    $inChar = $false
    $escaped = $false

    for ($index = 0; $index -lt $Text.Length; $index++) {
        $character = $Text[$index]
        $next = if ($index + 1 -lt $Text.Length) {
            $Text[$index + 1]
        } else {
            [char]0
        }

        if ($inLineComment) {
            if ($character -eq "`r" -or $character -eq "`n") {
                $inLineComment = $false
                [void]$builder.Append($character)
            } else {
                [void]$builder.Append(' ')
            }
            continue
        }

        if ($blockCommentDepth -gt 0) {
            if ($character -eq '/' -and $next -eq '*') {
                $blockCommentDepth++
                [void]$builder.Append(' ')
                [void]$builder.Append(' ')
                $index++
            } elseif ($character -eq '*' -and $next -eq '/') {
                $blockCommentDepth--
                [void]$builder.Append(' ')
                [void]$builder.Append(' ')
                $index++
            } elseif ($character -eq "`r" -or $character -eq "`n") {
                [void]$builder.Append($character)
            } else {
                [void]$builder.Append(' ')
            }
            continue
        }

        if ($inString -or $inChar) {
            [void]$builder.Append($character)
            if ($escaped) {
                $escaped = $false
            } elseif ($character -eq [char]0x5c) {
                $escaped = $true
            } elseif ($inString -and $character -eq '"') {
                $inString = $false
            } elseif ($inChar -and $character -eq "'") {
                $inChar = $false
            }
            continue
        }

        if ($character -eq '/' -and $next -eq '/') {
            $inLineComment = $true
            [void]$builder.Append(' ')
            [void]$builder.Append(' ')
            $index++
        } elseif ($character -eq '/' -and $next -eq '*') {
            $blockCommentDepth = 1
            [void]$builder.Append(' ')
            [void]$builder.Append(' ')
            $index++
        } else {
            [void]$builder.Append($character)
            if ($character -eq '"') {
                $inString = $true
            } elseif ($character -eq "'") {
                $inChar = $true
            }
        }
    }

    if ($blockCommentDepth -ne 0 -or $inString -or $inChar) {
        throw 'Unterminated source construct cannot be verified safely.'
    }

    return $builder.ToString()
}

function Get-BracedBlockText {
    param(
        [Parameter(Mandatory)][string]$Text,
        [Parameter(Mandatory)][string]$AnchorPattern
    )

    $matches = @([regex]::Matches(
        $Text,
        $AnchorPattern,
        [System.Text.RegularExpressions.RegexOptions]::Multiline
    ))
    if ($matches.Count -ne 1) {
        return $null
    }

    $openBrace = $Text.IndexOf('{', $matches[0].Index + $matches[0].Length)
    if ($openBrace -lt 0) {
        return $null
    }

    $depth = 0
    $inString = $false
    $inChar = $false
    $escaped = $false
    for ($index = $openBrace; $index -lt $Text.Length; $index++) {
        $character = $Text[$index]
        if ($inString -or $inChar) {
            if ($escaped) {
                $escaped = $false
            } elseif ($character -eq [char]0x5c) {
                $escaped = $true
            } elseif ($inString -and $character -eq '"') {
                $inString = $false
            } elseif ($inChar -and $character -eq "'") {
                $inChar = $false
            }
            continue
        }

        if ($character -eq '"') {
            $inString = $true
        } elseif ($character -eq "'") {
            $inChar = $true
        } elseif ($character -eq '{') {
            $depth++
        } elseif ($character -eq '}') {
            $depth--
            if ($depth -eq 0) {
                return $Text.Substring($openBrace, $index - $openBrace + 1)
            }
        }
    }

    return $null
}

function Get-AaptFacts {
    param([Parameter(Mandatory)][string]$BadgingText)

    $packageMatches = @([regex]::Matches(
        $BadgingText,
        "(?m)^package:\s+name='(?<id>[^']+)'\s+versionCode='(?<code>[^']+)'" +
            "\s+versionName='(?<name>[^']+)'(?<rest>[^\r\n]*)\r?$"
    ))
    $minMatches = @([regex]::Matches(
        $BadgingText,
        "(?m)^sdkVersion:'(?<value>\d+)'\s*$"
    ))
    $targetMatches = @([regex]::Matches(
        $BadgingText,
        "(?m)^targetSdkVersion:'(?<value>\d+)'\s*$"
    ))
    $debugMatches = @([regex]::Matches(
        $BadgingText,
        '(?m)^application-debuggable\s*$'
    ))

    if ($packageMatches.Count -ne 1 -or $minMatches.Count -ne 1 -or
        $targetMatches.Count -ne 1 -or $debugMatches.Count -ne 1
    ) {
        return $null
    }

    $package = $packageMatches[0]
    $compileMatch = [regex]::Match(
        $package.Groups['rest'].Value,
        "compileSdkVersion='(?<value>\d+)'"
    )
    if (-not $compileMatch.Success) {
        return $null
    }

    return [pscustomobject]@{
        ApplicationId = $package.Groups['id'].Value
        VersionCode = $package.Groups['code'].Value
        VersionName = $package.Groups['name'].Value
        MinSdk = $minMatches[0].Groups['value'].Value
        TargetSdk = $targetMatches[0].Groups['value'].Value
        CompileSdk = $compileMatch.Groups['value'].Value
        Debuggable = $true
    }
}

function Get-CertificateSha256 {
    param([Parameter(Mandatory)][string]$SignatureText)

    $matches = @([regex]::Matches(
        $SignatureText,
        '(?im)^Signer #1 certificate SHA-256 digest:\s*(?<value>[0-9a-f:]+)\s*$'
    ))
    if ($matches.Count -ne 1) {
        return $null
    }

    return [regex]::Replace(
        $matches[0].Groups['value'].Value,
        '[^0-9a-fA-F]',
        ''
    ).ToUpperInvariant()
}

function Test-BuildSourceEvidence {
    param(
        [Parameter(Mandatory)][string]$BuildScriptText,
        [Parameter(Mandatory)][string]$ApplicationSourceText,
        [Parameter(Mandatory)]
        [AllowEmptyCollection()]
        [System.Collections.Generic.List[string]]$Codes
    )

    try {
        $cleanBuild = Remove-KotlinComments -Text $BuildScriptText
        $cleanApplication = Remove-KotlinComments -Text $ApplicationSourceText
        $defaultBlock = Get-BracedBlockText `
            -Text $cleanBuild `
            -AnchorPattern '^\s*defaultConfig\s*'
        $debugBlock = Get-BracedBlockText `
            -Text $cleanBuild `
            -AnchorPattern '^\s*getByName\("debug"\)\s*'
        $releaseBlock = Get-BracedBlockText `
            -Text $cleanBuild `
            -AnchorPattern '^\s*getByName\("release"\)\s*'
    } catch {
        Add-FailureCode -Codes $Codes -Code 'BUILD_CONFIG_UNSAFE'
        return
    }

    if ($null -eq $defaultBlock -or $null -eq $debugBlock -or
        $null -eq $releaseBlock
    ) {
        Add-FailureCode -Codes $Codes -Code 'BUILD_CONFIG_UNSAFE'
        return
    }

    $escapedFlag = [regex]::Escape($script:ManualCompileFlag)
    $fieldStart =
        'buildConfigField\s*\(\s*"boolean"\s*,\s*"' + $escapedFlag + '"\s*,'
    $fieldAny = $fieldStart + '(?s:.*?)\)'
    $fieldFalse = $fieldStart + '\s*"false"\s*,?\s*\)'
    $fieldDebug =
        $fieldStart + '\s*manualPaperCompiled\.toString\(\)\s*,?\s*\)'
    $fieldTrue = $fieldStart + '\s*"true"\s*,?\s*\)'

    $defaultSafe =
        @([regex]::Matches($defaultBlock, $fieldAny)).Count -eq 1 -and
        @([regex]::Matches($defaultBlock, $fieldFalse)).Count -eq 1
    $debugSafe =
        @([regex]::Matches($debugBlock, $fieldAny)).Count -eq 1 -and
        @([regex]::Matches($debugBlock, $fieldDebug)).Count -eq 1 -and
        @([regex]::Matches(
            $debugBlock,
            'loadLocalBooleanProperty\s*\(\s*"' + $escapedFlag + '"\s*\)'
        )).Count -eq 1
    $releaseSafe =
        @([regex]::Matches($releaseBlock, $fieldAny)).Count -eq 1 -and
        @([regex]::Matches($releaseBlock, $fieldFalse)).Count -eq 1 -and
        $releaseBlock -notmatch 'loadLocalBooleanProperty|manualPaperCompiled'
    $literalTrueCount = @([regex]::Matches($cleanBuild, $fieldTrue)).Count

    if (-not $defaultSafe -or -not $debugSafe -or -not $releaseSafe -or
        $literalTrueCount -ne 0
    ) {
        Add-FailureCode -Codes $Codes -Code 'BUILD_CONFIG_UNSAFE'
    }

    $literalApplicationTrue =
        $cleanApplication -match 'compileTimeEnabled\s*=\s*true\b' -or
        $cleanApplication -match 'PaperManualExecutionFeatureGate\s*\(\s*true\b'
    if ($literalApplicationTrue) {
        Add-FailureCode -Codes $Codes -Code 'APPLICATION_GATE_TRUE'
    }

    $safeCallsite = @([regex]::Matches(
        $cleanApplication,
        'PaperManualExecutionFeatureGate\s*\(\s*' +
            'compileTimeEnabled\s*=\s*BuildConfig\.' + $escapedFlag +
            '\s*,?\s*\)',
        [System.Text.RegularExpressions.RegexOptions]::Singleline
    )).Count -eq 1
    if (-not $safeCallsite) {
        Add-FailureCode -Codes $Codes -Code 'APPLICATION_GATE_UNSAFE'
    }
}

function Test-FeatureGateDexEvidence {
    param(
        [Parameter(Mandatory)][string]$DexCodeText,
        [Parameter(Mandatory)]
        [AllowEmptyCollection()]
        [System.Collections.Generic.List[string]]$Codes
    )

    $compactDex = $DexCodeText -replace '\s+', ''
    $gateType =
        'Lcom/vela/android/lab/data/paper/submit/' +
        'PaperManualExecutionFeatureGate;'
    $newInstancePattern =
        'new-instance[vp]\d+,' + [regex]::Escape($gateType)
    $constructorPattern =
        'invoke-direct\{[vp]\d+,[vp]\d+\},' +
        [regex]::Escape($gateType) + '-><init>\(Z\)V'
    if (@([regex]::Matches($compactDex, $newInstancePattern)).Count -ne 1 -or
        @([regex]::Matches($compactDex, $constructorPattern)).Count -ne 1
    ) {
        Add-FailureCode -Codes $Codes -Code 'FEATURE_GATE_DEX_UNSAFE'
        return
    }

    $methodBlocks = @([regex]::Matches(
        $DexCodeText,
        '(?ms)^\s*\.method\b.*?^\s*\.end method\s*$'
    ))
    $gateBlocks = @(
        $methodBlocks | Where-Object {
            $compact = $_.Value -replace '\s+', ''
            $compact -match (
                'new-instance[vp]\d+,Lcom/vela/android/lab/data/paper/submit/' +
                'PaperManualExecutionFeatureGate;'
            ) -and $compact -match (
                'Lcom/vela/android/lab/data/paper/submit/' +
                'PaperManualExecutionFeatureGate;-><init>\(Z\)V'
            )
        }
    )
    if ($gateBlocks.Count -ne 1) {
        Add-FailureCode -Codes $Codes -Code 'FEATURE_GATE_DEX_UNSAFE'
        return
    }

    $compactGate = $gateBlocks[0].Value -replace '\s+', ''
    $zeroPattern =
        'new-instance(?<obj>[vp]\d+),Lcom/vela/android/lab/data/paper/submit/' +
        'PaperManualExecutionFeatureGate;' +
        '(?:\.line\d+|nop)*' +
        'const/4(?<flag>[vp]\d+),(?:0x0|0|#int0)' +
        '(?:\.line\d+|nop)*' +
        'invoke-direct\{\k<obj>,\k<flag>\},Lcom/vela/android/lab/data/paper/' +
        'submit/PaperManualExecutionFeatureGate;-><init>\(Z\)V'
    if (@([regex]::Matches($compactGate, $zeroPattern)).Count -ne 1) {
        Add-FailureCode -Codes $Codes -Code 'FEATURE_GATE_DEX_UNSAFE'
    }
}

function Test-SafeApkEvidence {
    [CmdletBinding()]
    param([Parameter(Mandatory)]$Evidence)

    $codes = New-Object 'System.Collections.Generic.List[string]'

    $requiredEvidence = @(
        'Head', 'OriginHead', 'Branch', 'StatusLines',
        'EndHead', 'EndOriginHead', 'EndBranch', 'EndStatusLines',
        'ManualFlagLines', 'BuildScriptText', 'ApplicationSourceText',
        'SafetyExitCode', 'SafetyText', 'AaptExitCode', 'BadgingText',
        'SignatureExitCode', 'SignatureText', 'DexExitCode', 'DexCodeText',
        'ApkSha256', 'EndApkSha256', 'ApkLength', 'EndApkLength',
        'ApkLastWriteTimeUtc', 'EndApkLastWriteTimeUtc'
    )
    foreach ($name in $requiredEvidence) {
        if (-not (Test-EvidenceHasProperty -Evidence $Evidence -Name $name)) {
            Add-FailureCode -Codes $codes -Code 'EVIDENCE_INCOMPLETE'
        }
    }

    $head = (Get-EvidenceValue -Evidence $Evidence -Name 'Head' -DefaultValue '').ToString()
    $originHead = (
        Get-EvidenceValue -Evidence $Evidence -Name 'OriginHead' -DefaultValue ''
    ).ToString()
    $endHead = (
        Get-EvidenceValue -Evidence $Evidence -Name 'EndHead' -DefaultValue ''
    ).ToString()
    $endOriginHead = (
        Get-EvidenceValue `
            -Evidence $Evidence `
            -Name 'EndOriginHead' `
            -DefaultValue ''
    ).ToString()
    $branch = (
        Get-EvidenceValue -Evidence $Evidence -Name 'Branch' -DefaultValue ''
    ).ToString()
    $endBranch = (
        Get-EvidenceValue -Evidence $Evidence -Name 'EndBranch' -DefaultValue ''
    ).ToString()
    $hashPattern = '^(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})$'

    if ($head -notmatch $hashPattern -or $originHead -notmatch $hashPattern -or
        $head -ne $originHead -or $endHead -notmatch $hashPattern -or
        $endOriginHead -notmatch $hashPattern -or $endHead -ne $endOriginHead
    ) {
        Add-FailureCode -Codes $codes -Code 'HEAD_ORIGIN_MISMATCH'
    }
    if ($branch -ne 'main' -or $endBranch -ne 'main') {
        Add-FailureCode -Codes $codes -Code 'BRANCH_NOT_MAIN'
    }
    if ($head -ne $endHead -or $originHead -ne $endOriginHead) {
        Add-FailureCode -Codes $codes -Code 'CHECKOUT_CHANGED_DURING_VERIFY'
    }

    $statusLines = @(Get-NonEmptyLines (
        Get-EvidenceValue -Evidence $Evidence -Name 'StatusLines' -DefaultValue @()
    ))
    $endStatusLines = @(Get-NonEmptyLines (
        Get-EvidenceValue -Evidence $Evidence -Name 'EndStatusLines' -DefaultValue @()
    ))
    if ($statusLines.Count -ne 0 -or $endStatusLines.Count -ne 0) {
        Add-FailureCode -Codes $codes -Code 'WORKTREE_DIRTY'
    }

    $manualFlagLines = @(Get-NonEmptyLines (
        Get-EvidenceValue `
            -Evidence $Evidence `
            -Name 'ManualFlagLines' `
            -DefaultValue @()
    ))
    $flagPattern =
        '^\s*' + [regex]::Escape($script:ManualCompileFlag) +
        '(?:\s*[:=]\s*|\s+)false\s*$'
    if ($manualFlagLines.Count -gt 1 -or
        ($manualFlagLines.Count -eq 1 -and $manualFlagLines[0] -notmatch $flagPattern)
    ) {
        Add-FailureCode -Codes $codes -Code 'LOCAL_FLAG_UNSAFE'
    }

    $buildScriptText = (
        Get-EvidenceValue `
            -Evidence $Evidence `
            -Name 'BuildScriptText' `
            -DefaultValue ''
    ).ToString()
    $applicationSourceText = (
        Get-EvidenceValue `
            -Evidence $Evidence `
            -Name 'ApplicationSourceText' `
            -DefaultValue ''
    ).ToString()
    Test-BuildSourceEvidence `
        -BuildScriptText $buildScriptText `
        -ApplicationSourceText $applicationSourceText `
        -Codes $codes

    $safetyExitCode = [int](
        Get-EvidenceValue -Evidence $Evidence -Name 'SafetyExitCode' -DefaultValue -1
    )
    $safetyText = (
        Get-EvidenceValue -Evidence $Evidence -Name 'SafetyText' -DefaultValue ''
    ).ToString()
    $safetyMatches = @([regex]::Matches(
        $safetyText,
        '(?m)^Safety scan summary:\s+allowed_phase2v_submit=(?<allowed>\d+)\s+' +
            'suspicious=(?<suspicious>\d+)\s+forbidden=(?<forbidden>\d+)\s*$'
    ))
    if ($safetyExitCode -ne 0 -or $safetyMatches.Count -ne 1 -or
        ($safetyMatches.Count -eq 1 -and (
            $safetyMatches[0].Groups['allowed'].Value -ne '11' -or
            $safetyMatches[0].Groups['suspicious'].Value -ne '0' -or
            $safetyMatches[0].Groups['forbidden'].Value -ne '0'
        ))
    ) {
        Add-FailureCode -Codes $codes -Code 'SAFETY_SCAN_UNSAFE'
    }

    $aaptExitCode = [int](
        Get-EvidenceValue -Evidence $Evidence -Name 'AaptExitCode' -DefaultValue -1
    )
    $badgingText = (
        Get-EvidenceValue -Evidence $Evidence -Name 'BadgingText' -DefaultValue ''
    ).ToString()
    $aaptFacts = Get-AaptFacts -BadgingText $badgingText
    if ($aaptExitCode -ne 0 -or $null -eq $aaptFacts -or
        ($null -ne $aaptFacts -and (
            $aaptFacts.ApplicationId -ne $script:ExpectedApplicationId -or
            $aaptFacts.VersionCode -ne $script:ExpectedVersionCode -or
            $aaptFacts.VersionName -ne $script:ExpectedVersionName -or
            $aaptFacts.MinSdk -ne $script:ExpectedMinSdk -or
            $aaptFacts.TargetSdk -ne $script:ExpectedTargetSdk -or
            $aaptFacts.CompileSdk -ne $script:ExpectedCompileSdk -or
            -not $aaptFacts.Debuggable
        ))
    ) {
        Add-FailureCode -Codes $codes -Code 'APK_METADATA_MISMATCH'
    }

    $signatureExitCode = [int](
        Get-EvidenceValue `
            -Evidence $Evidence `
            -Name 'SignatureExitCode' `
            -DefaultValue -1
    )
    $signatureText = (
        Get-EvidenceValue -Evidence $Evidence -Name 'SignatureText' -DefaultValue ''
    ).ToString()
    $signatureShapeSafe =
        $signatureExitCode -eq 0 -and
        @([regex]::Matches($signatureText, '(?m)^Verifies\s*$')).Count -eq 1 -and
        @([regex]::Matches(
            $signatureText,
            '(?m)^Verified using v2 scheme \(APK Signature Scheme v2\): true\s*$'
        )).Count -eq 1 -and
        @([regex]::Matches(
            $signatureText,
            '(?m)^Number of signers:\s*1\s*$'
        )).Count -eq 1 -and
        @([regex]::Matches($signatureText, '(?m)^WARNING:')).Count -eq 0
    if (-not $signatureShapeSafe) {
        Add-FailureCode -Codes $codes -Code 'APK_SIGNATURE_INVALID'
    }
    $certificateSha256 = Get-CertificateSha256 -SignatureText $signatureText
    if ($null -eq $certificateSha256 -or
        $certificateSha256 -ne $script:ExpectedCertificateSha256
    ) {
        Add-FailureCode -Codes $codes -Code 'CERTIFICATE_MISMATCH'
    }

    $dexExitCode = [int](
        Get-EvidenceValue -Evidence $Evidence -Name 'DexExitCode' -DefaultValue -1
    )
    $dexCodeText = (
        Get-EvidenceValue -Evidence $Evidence -Name 'DexCodeText' -DefaultValue ''
    ).ToString()
    if ($dexExitCode -ne 0) {
        Add-FailureCode -Codes $codes -Code 'FEATURE_GATE_DEX_UNSAFE'
    } else {
        Test-FeatureGateDexEvidence -DexCodeText $dexCodeText -Codes $codes
    }

    $apkSha256 = (
        Get-EvidenceValue -Evidence $Evidence -Name 'ApkSha256' -DefaultValue ''
    ).ToString()
    $endApkSha256 = (
        Get-EvidenceValue `
            -Evidence $Evidence `
            -Name 'EndApkSha256' `
            -DefaultValue ''
    ).ToString()
    $apkLength = [long](
        Get-EvidenceValue -Evidence $Evidence -Name 'ApkLength' -DefaultValue -1
    )
    $endApkLength = [long](
        Get-EvidenceValue `
            -Evidence $Evidence `
            -Name 'EndApkLength' `
            -DefaultValue -1
    )
    $apkLastWriteTimeUtc = (
        Get-EvidenceValue `
            -Evidence $Evidence `
            -Name 'ApkLastWriteTimeUtc' `
            -DefaultValue ''
    ).ToString()
    $endApkLastWriteTimeUtc = (
        Get-EvidenceValue `
            -Evidence $Evidence `
            -Name 'EndApkLastWriteTimeUtc' `
            -DefaultValue ''
    ).ToString()
    if ($apkSha256 -notmatch '^[0-9a-fA-F]{64}$' -or
        $endApkSha256 -notmatch '^[0-9a-fA-F]{64}$' -or
        $apkLength -le 0 -or $endApkLength -le 0 -or
        [string]::IsNullOrWhiteSpace($apkLastWriteTimeUtc) -or
        [string]::IsNullOrWhiteSpace($endApkLastWriteTimeUtc)
    ) {
        Add-FailureCode -Codes $codes -Code 'APK_IDENTITY_INVALID'
    }
    if ($apkSha256 -ne $endApkSha256 -or $apkLength -ne $endApkLength -or
        $apkLastWriteTimeUtc -ne $endApkLastWriteTimeUtc
    ) {
        Add-FailureCode -Codes $codes -Code 'APK_CHANGED_DURING_VERIFY'
    }

    return [pscustomobject]@{
        Passed = $codes.Count -eq 0
        Codes = @($codes)
    }
}

function Invoke-CapturedNative {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$ArgumentList
    )

    $lines = @()
    $exitCode = -1
    try {
        $lines = @(
            & $FilePath @ArgumentList 2>&1 |
                ForEach-Object { $_.ToString() }
        )
        $exitCode = $LASTEXITCODE
    } catch {
        $lines = @()
        $exitCode = -1
    }

    return [pscustomobject]@{
        ExitCode = $exitCode
        Lines = @($lines)
        Text = $lines -join "`n"
    }
}

function Invoke-CapturedSdkJavaTool {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string[]]$ArgumentList
    )

    $javaPath = Join-Path $script:ApprovedJavaHome 'bin\java.exe'
    [void](Resolve-PathWithoutReparse `
        -Path $javaPath `
        -AnchorPath $script:ApprovedJavaHome `
        -PathType Leaf)

    $environmentNames = @(
        'JAVA_HOME', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS',
        'JDK_JAVA_OPTIONS', 'JAVA_OPTS', 'APKANALYZER_OPTS',
        'CLASSPATH', 'ComSpec'
    )
    $original = @{}
    foreach ($name in $environmentNames) {
        $original[$name] = [System.Environment]::GetEnvironmentVariable(
            $name,
            [System.EnvironmentVariableTarget]::Process
        )
    }

    try {
        foreach ($name in @(
            'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS',
            'JAVA_OPTS', 'APKANALYZER_OPTS', 'CLASSPATH'
        )) {
            [System.Environment]::SetEnvironmentVariable(
                $name,
                $null,
                [System.EnvironmentVariableTarget]::Process
            )
        }
        [System.Environment]::SetEnvironmentVariable(
            'JAVA_HOME',
            $script:ApprovedJavaHome,
            [System.EnvironmentVariableTarget]::Process
        )
        [System.Environment]::SetEnvironmentVariable(
            'ComSpec',
            'C:\Windows\System32\cmd.exe',
            [System.EnvironmentVariableTarget]::Process
        )
        return Invoke-CapturedNative $FilePath $ArgumentList
    } finally {
        foreach ($name in $environmentNames) {
            [System.Environment]::SetEnvironmentVariable(
                $name,
                $original[$name],
                [System.EnvironmentVariableTarget]::Process
            )
        }
    }
}

function Test-GitIndexEntryUnsafe {
    param([Parameter(Mandatory)][string]$Line)

    return $Line -cmatch '^(?:[a-z]|S)\s'
}

function Get-GitSnapshot {
    param(
        [Parameter(Mandatory)][string]$GitPath,
        [Parameter(Mandatory)][string]$RepoRoot
    )

    $gitEnvironment = @{}
    foreach ($entry in @(Get-ChildItem Env: | Where-Object {
        $_.Name.StartsWith('GIT_', [System.StringComparison]::OrdinalIgnoreCase)
    })) {
        $gitEnvironment[$entry.Name] = $entry.Value
        [System.Environment]::SetEnvironmentVariable(
            $entry.Name,
            $null,
            [System.EnvironmentVariableTarget]::Process
        )
    }
    try {
        $gitDir = Join-Path $RepoRoot '.git'
        $gitPrefix = @(
            '--no-optional-locks',
            "--git-dir=$gitDir",
            "--work-tree=$RepoRoot",
            '-c', 'core.fsmonitor=false',
            '-c', 'core.untrackedCache=false'
        )
        $headResult = Invoke-CapturedNative $GitPath ($gitPrefix + @(
            'rev-parse', '--verify', 'HEAD^{commit}'
        ))
        $originResult = Invoke-CapturedNative $GitPath ($gitPrefix + @(
            'rev-parse', '--verify', 'refs/remotes/origin/main^{commit}'
        ))
        $branchResult = Invoke-CapturedNative $GitPath ($gitPrefix + @(
            'rev-parse', '--abbrev-ref', 'HEAD'
        ))
        $statusResult = Invoke-CapturedNative $GitPath ($gitPrefix + @(
            'status', '--porcelain=v1', '--untracked-files=all',
            '--ignore-submodules=none'
        ))
        $indexResult = Invoke-CapturedNative $GitPath ($gitPrefix + @(
            'ls-files', '-v'
        ))
    } finally {
        foreach ($entry in @(Get-ChildItem Env: | Where-Object {
            $_.Name.StartsWith('GIT_', [System.StringComparison]::OrdinalIgnoreCase)
        })) {
            [System.Environment]::SetEnvironmentVariable(
                $entry.Name,
                $null,
                [System.EnvironmentVariableTarget]::Process
            )
        }
        foreach ($name in $gitEnvironment.Keys) {
            [System.Environment]::SetEnvironmentVariable(
                $name,
                $gitEnvironment[$name],
                [System.EnvironmentVariableTarget]::Process
            )
        }
    }

    if ($headResult.ExitCode -ne 0 -or $originResult.ExitCode -ne 0 -or
        $branchResult.ExitCode -ne 0 -or $statusResult.ExitCode -ne 0 -or
        $indexResult.ExitCode -ne 0
    ) {
        throw 'Git evidence could not be read.'
    }

    $headLines = @(Get-NonEmptyLines $headResult.Lines)
    $originLines = @(Get-NonEmptyLines $originResult.Lines)
    $branchLines = @(Get-NonEmptyLines $branchResult.Lines)
    if ($headLines.Count -ne 1 -or $originLines.Count -ne 1 -or
        $branchLines.Count -ne 1
    ) {
        throw 'Git evidence was malformed.'
    }

    $statusLines = @(Get-NonEmptyLines $statusResult.Lines)
    $hiddenIndexFlags = @(
        @(Get-NonEmptyLines $indexResult.Lines) |
            Where-Object { Test-GitIndexEntryUnsafe -Line $_ }
    )
    if ($hiddenIndexFlags.Count -ne 0) {
        $statusLines += '__GIT_INDEX_FLAGS_UNSAFE__'
    }

    return [pscustomobject]@{
        Head = $headLines[0]
        OriginHead = $originLines[0]
        Branch = $branchLines[0]
        StatusLines = @($statusLines)
    }
}

function Test-JavaPropertyWhitespace {
    param([Parameter(Mandatory)][char]$Character)

    return $Character -eq ' ' -or $Character -eq "`t" -or
        $Character -eq [char]0x0c
}

function ConvertFrom-JavaPropertyEscapes {
    param([Parameter(Mandatory)][string]$RawValue)

    $builder = New-Object System.Text.StringBuilder
    for ($index = 0; $index -lt $RawValue.Length; $index++) {
        $character = $RawValue[$index]
        if ($character -ne [char]0x5c) {
            [void]$builder.Append($character)
            continue
        }
        $index++
        if ($index -ge $RawValue.Length) {
            throw 'Dangling Java Properties escape.'
        }
        $escaped = $RawValue[$index]
        switch ($escaped) {
            't' { [void]$builder.Append("`t") }
            'n' { [void]$builder.Append("`n") }
            'r' { [void]$builder.Append("`r") }
            'f' { [void]$builder.Append([char]0x0c) }
            'u' {
                if ($index + 4 -ge $RawValue.Length) {
                    throw 'Incomplete Java Properties unicode escape.'
                }
                $hex = $RawValue.Substring($index + 1, 4)
                if ($hex -notmatch '^[0-9a-fA-F]{4}$') {
                    throw 'Invalid Java Properties unicode escape.'
                }
                [void]$builder.Append([char][Convert]::ToInt32($hex, 16))
                $index += 4
            }
            default { [void]$builder.Append($escaped) }
        }
    }
    return $builder.ToString()
}

function Test-JavaPropertyContinuation {
    param([Parameter(Mandatory)][string]$Line)

    $backslashes = 0
    for ($index = $Line.Length - 1; $index -ge 0 -and
        $Line[$index] -eq [char]0x5c; $index--
    ) {
        $backslashes++
    }
    return ($backslashes % 2) -eq 1
}

function Get-JavaPropertyLinesFromPhysicalLines {
    param(
        [Parameter(Mandatory)]
        [AllowEmptyCollection()]
        $PhysicalLines,
        [Parameter(Mandatory)][string]$ExpectedKey
    )

    $matches = New-Object 'System.Collections.Generic.List[string]'
    $logicalLine = $null
    $continuing = $false
    $trimCharacters = [char[]]@(' ', "`t", [char]0x0c)
    try {
        foreach ($rawLine in $PhysicalLines) {
            $physicalLine = if ($null -eq $rawLine) { '' } else {
                $rawLine.ToString()
            }
            if (-not $continuing) {
                $trimmedStart = $physicalLine.TrimStart($trimCharacters)
                if ([string]::IsNullOrEmpty($trimmedStart) -or
                    $trimmedStart.StartsWith('#') -or
                    $trimmedStart.StartsWith('!')
                ) {
                    continue
                }
                $logicalLine = $physicalLine
            } else {
                $logicalLine += $physicalLine.TrimStart($trimCharacters)
            }
            if (Test-JavaPropertyContinuation -Line $logicalLine) {
                $logicalLine = $logicalLine.Substring(0, $logicalLine.Length - 1)
                $continuing = $true
                continue
            }
            $continuing = $false

            $start = 0
            while ($start -lt $logicalLine.Length -and
                (Test-JavaPropertyWhitespace $logicalLine[$start])
            ) { $start++ }
            if ($start -ge $logicalLine.Length) {
                $logicalLine = $null
                continue
            }
            $separator = $logicalLine.Length
            $escaped = $false
            for ($index = $start; $index -lt $logicalLine.Length; $index++) {
                $character = $logicalLine[$index]
                if ($escaped) { $escaped = $false; continue }
                if ($character -eq [char]0x5c) { $escaped = $true; continue }
                if ($character -eq '=' -or $character -eq ':' -or
                    (Test-JavaPropertyWhitespace $character)
                ) { $separator = $index; break }
            }
            $rawKey = $logicalLine.Substring($start, $separator - $start)
            $key = ConvertFrom-JavaPropertyEscapes $rawKey
            if ($key.Equals(
                $ExpectedKey,
                [System.StringComparison]::OrdinalIgnoreCase
            )) {
                $valueStart = $separator
                while ($valueStart -lt $logicalLine.Length -and
                    (Test-JavaPropertyWhitespace $logicalLine[$valueStart])
                ) { $valueStart++ }
                if ($valueStart -lt $logicalLine.Length -and
                    ($logicalLine[$valueStart] -eq '=' -or
                        $logicalLine[$valueStart] -eq ':')
                ) { $valueStart++ }
                while ($valueStart -lt $logicalLine.Length -and
                    (Test-JavaPropertyWhitespace $logicalLine[$valueStart])
                ) { $valueStart++ }
                $rawValue = $logicalLine.Substring($valueStart)
                $value = (ConvertFrom-JavaPropertyEscapes $rawValue).Trim()
                $matches.Add($ExpectedKey + '=' + $value)
            }
            $logicalLine = $null
        }
        if ($continuing) {
            throw 'Unterminated Java Properties continuation.'
        }
    } catch {
        return @('__MALFORMED_JAVA_PROPERTIES__')
    }
    return @($matches.ToArray())
}
function Get-ManualFlagLinesFromPhysicalLines {
    param(
        [Parameter(Mandatory)]
        [AllowEmptyCollection()]
        $PhysicalLines
    )

    return @(Get-JavaPropertyLinesFromPhysicalLines `
        -PhysicalLines $PhysicalLines `
        -ExpectedKey $script:ManualCompileFlag)
}


function Get-ManualFlagLines {
    param([Parameter(Mandatory)][string]$LocalPropertiesPath)

    if (-not (Test-Path -LiteralPath $LocalPropertiesPath -PathType Leaf)) {
        return @()
    }
    return @(Get-ManualFlagLinesFromPhysicalLines (
        [System.IO.File]::ReadLines($LocalPropertiesPath)
    ))
}

function Resolve-PathWithoutReparse {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$AnchorPath,
        [Parameter(Mandatory)]
        [ValidateSet('Leaf', 'Container')]
        [string]$PathType
    )

    if (-not (Test-Path -LiteralPath $Path -PathType $PathType)) {
        throw 'Approved path is unavailable.'
    }
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $anchor = (Resolve-Path -LiteralPath $AnchorPath).Path.TrimEnd('\', '/')
    $prefix = $anchor + [System.IO.Path]::DirectorySeparatorChar
    if (-not $resolved.Equals(
        $anchor,
        [System.StringComparison]::OrdinalIgnoreCase
    ) -and -not $resolved.StartsWith(
        $prefix,
        [System.StringComparison]::OrdinalIgnoreCase
    )) {
        throw 'Approved path escaped its trust root.'
    }

    $cursorPath = $resolved
    while (-not [string]::IsNullOrWhiteSpace($cursorPath)) {
        $cursor = Get-Item -LiteralPath $cursorPath -Force
        if (($cursor.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'Approved path cannot traverse a reparse point.'
        }
        $cursorFull = $cursor.FullName.TrimEnd('\', '/')
        if ($cursorFull.Equals(
            $anchor,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
            return $resolved
        }
        $parent = [System.IO.Directory]::GetParent($cursor.FullName)
        $cursorPath = if ($null -eq $parent) { $null } else { $parent.FullName }
    }
    throw 'Approved path containment could not be proven.'
}

function Resolve-AndroidSdkRoot {
    param(
        [string]$RequestedRoot,
        [Parameter(Mandatory)][string]$LocalPropertiesPath
    )
    $approved = Resolve-PathWithoutReparse `
        -Path $script:ApprovedSdkRoot `
        -AnchorPath $script:ApprovedSdkRoot `
        -PathType Container

    if (-not [string]::IsNullOrWhiteSpace($RequestedRoot)) {
        $requested = [System.IO.Path]::GetFullPath($RequestedRoot)
        if (-not $requested.Equals(
            $approved,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
            throw 'SdkRoot must equal the approved Android SDK root.'
        }
    }

    if (Test-Path -LiteralPath $LocalPropertiesPath -PathType Leaf) {
        $sdkMatches = @(Get-JavaPropertyLinesFromPhysicalLines `
            -PhysicalLines ([System.IO.File]::ReadLines($LocalPropertiesPath)) `
            -ExpectedKey 'sdk.dir')
        if ($sdkMatches.Count -gt 1 -or
            ($sdkMatches.Count -eq 1 -and
                $sdkMatches[0] -eq '__MALFORMED_JAVA_PROPERTIES__')
        ) {
            throw 'local.properties contains ambiguous sdk.dir values.'
        }
        if ($sdkMatches.Count -eq 1) {
            $configuredText = $sdkMatches[0].Substring('sdk.dir='.Length)
            if (-not [System.IO.Path]::IsPathRooted($configuredText)) {
                throw 'local.properties sdk.dir must be absolute.'
            }
            $configured = [System.IO.Path]::GetFullPath($configuredText)
            if (-not $configured.Equals(
                $approved,
                [System.StringComparison]::OrdinalIgnoreCase
            )) {
                throw 'local.properties sdk.dir is not the approved SDK root.'
            }
        }
    }
    return $approved

}

function Resolve-BuildTool {
    param(
        [Parameter(Mandatory)][string]$ResolvedSdkRoot,
        [Parameter(Mandatory)][string]$ToolName
    )
    $approved = Resolve-PathWithoutReparse `
        -Path $script:ApprovedSdkRoot `
        -AnchorPath $script:ApprovedSdkRoot `
        -PathType Container
    $requested = [System.IO.Path]::GetFullPath($ResolvedSdkRoot)
    if (-not $requested.Equals(
        $approved,
        [System.StringComparison]::OrdinalIgnoreCase
    ) -or @('aapt.exe', 'apksigner.bat') -notcontains $ToolName) {
        throw 'Android build tool request is not approved.'
    }
    $approvedTool = Join-Path $approved (
        Join-Path 'build-tools\34.0.0' $ToolName
    )
    return Resolve-PathWithoutReparse `
        -Path $approvedTool `
        -AnchorPath $approved `
        -PathType Leaf

}

function Resolve-ApkAnalyzer {
    param([Parameter(Mandatory)][string]$ResolvedSdkRoot)
    $approved = Resolve-PathWithoutReparse `
        -Path $script:ApprovedSdkRoot `
        -AnchorPath $script:ApprovedSdkRoot `
        -PathType Container
    $requested = [System.IO.Path]::GetFullPath($ResolvedSdkRoot)
    if (-not $requested.Equals(
        $approved,
        [System.StringComparison]::OrdinalIgnoreCase
    )) {
        throw 'apkanalyzer request is not approved.'
    }
    $approvedAnalyzer = Join-Path $approved (
        'cmdline-tools\latest\bin\apkanalyzer.bat'
    )
    return Resolve-PathWithoutReparse `
        -Path $approvedAnalyzer `
        -AnchorPath $approved `
        -PathType Leaf

}

function Resolve-ContainedApkPath {
    param(
        [string]$RequestedPath,
        [Parameter(Mandatory)][string]$AndroidRoot,
        [Parameter(Mandatory)][string]$RepoRoot
    )

    $candidate = if ([string]::IsNullOrWhiteSpace($RequestedPath)) {
        Join-Path $AndroidRoot 'app\build\outputs\apk\debug\app-debug.apk'
    } elseif ([System.IO.Path]::IsPathRooted($RequestedPath)) {
        $RequestedPath
    } else {
        Join-Path $AndroidRoot $RequestedPath
    }
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw 'APK does not exist.'
    }

    $resolved = (Resolve-Path -LiteralPath $candidate).Path
    $repoFull = (Resolve-Path -LiteralPath $RepoRoot).Path.TrimEnd('\', '/')
    $repoPrefix = $repoFull + [System.IO.Path]::DirectorySeparatorChar
    $extensionSafe = [System.IO.Path]::GetExtension($resolved).Equals(
        '.apk',
        [System.StringComparison]::OrdinalIgnoreCase
    )
    if (-not $resolved.StartsWith(
        $repoPrefix,
        [System.StringComparison]::OrdinalIgnoreCase
    ) -or -not $extensionSafe) {
        throw 'APK path must stay inside the repository and end in .apk.'
    }

    $cursorPath = $resolved
    $reachedRepo = $false
    while (-not [string]::IsNullOrWhiteSpace($cursorPath)) {
        $cursor = Get-Item -LiteralPath $cursorPath -Force
        if (($cursor.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'APK path cannot traverse a reparse point.'
        }
        $cursorFull = $cursor.FullName.TrimEnd('\', '/')
        if ($cursorFull.Equals(
            $repoFull,
            [System.StringComparison]::OrdinalIgnoreCase
        )) {
            $reachedRepo = $true
            break
        }
        $parent = [System.IO.Directory]::GetParent($cursor.FullName)
        $cursorPath = if ($null -eq $parent) { $null } else { $parent.FullName }
    }
    if (-not $reachedRepo) {
        throw 'APK path containment could not be proven.'
    }
    return $resolved
}

function Get-ApkSnapshot {
    param([Parameter(Mandatory)][string]$ResolvedApkPath)

    $item = Get-Item -LiteralPath $ResolvedApkPath
    $hash = Get-FileHash -LiteralPath $ResolvedApkPath -Algorithm SHA256
    return [pscustomobject]@{
        Sha256 = $hash.Hash.ToUpperInvariant()
        Length = [long]$item.Length
        LastWriteTimeUtc = $item.LastWriteTimeUtc
    }
}

function Invoke-SafeApkVerification {
    param(
        [string]$RequestedApkPath,
        [string]$RequestedSdkRoot
    )

    $androidRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
    $repoRoot = (Resolve-Path -LiteralPath (Join-Path $androidRoot '..')).Path
    $localPropertiesPath = Join-Path $androidRoot 'local.properties'
    $buildScriptPath = Join-Path $androidRoot 'app\build.gradle.kts'
    $applicationSourcePath = Join-Path $androidRoot (
        'app\src\main\kotlin\com\vela\android\lab\VelaLabApplication.kt'
    )
    $safetyScanPath = Join-Path $PSScriptRoot 'safety-scan.ps1'
    $resolvedApkPath = Resolve-ContainedApkPath `
        -RequestedPath $RequestedApkPath `
        -AndroidRoot $androidRoot `
        -RepoRoot $repoRoot
    $buildScriptPath = Resolve-PathWithoutReparse `
        -Path $buildScriptPath -AnchorPath $repoRoot -PathType Leaf
    $applicationSourcePath = Resolve-PathWithoutReparse `
        -Path $applicationSourcePath -AnchorPath $repoRoot -PathType Leaf
    $safetyScanPath = Resolve-PathWithoutReparse `
        -Path $safetyScanPath -AnchorPath $repoRoot -PathType Leaf
    $gitPath = Resolve-PathWithoutReparse `
        -Path $script:ApprovedGitPath `
        -AnchorPath $script:ApprovedGitRoot `
        -PathType Leaf
    $gitStart = Get-GitSnapshot -GitPath $gitPath -RepoRoot $repoRoot
    $initialGit = Test-InitialGitSnapshot -Snapshot $gitStart
    if (-not $initialGit.Passed) {
        return New-VerificationResult -ExitCode 1 -Lines @(
            'VERIFY_SAFE_APK_FAIL'
            'failure_codes=' + ($initialGit.Codes -join ',')
        )
    }

    $manualFlagLines = @(Get-ManualFlagLines $localPropertiesPath)
    $buildScriptText = [System.IO.File]::ReadAllText($buildScriptPath)
    $applicationSourceText = [System.IO.File]::ReadAllText($applicationSourcePath)
    $sourceCodes = New-Object 'System.Collections.Generic.List[string]'
    $manualNonEmpty = @(Get-NonEmptyLines $manualFlagLines)
    $manualFalsePattern =
        '^\s*' + [regex]::Escape($script:ManualCompileFlag) +
        '(?:\s*[:=]\s*|\s+)false\s*$'
    if ($manualNonEmpty.Count -gt 1 -or
        ($manualNonEmpty.Count -eq 1 -and
            $manualNonEmpty[0] -notmatch $manualFalsePattern)
    ) {
        Add-FailureCode -Codes $sourceCodes -Code 'LOCAL_FLAG_UNSAFE'
    }
    Test-BuildSourceEvidence `
        -BuildScriptText $buildScriptText `
        -ApplicationSourceText $applicationSourceText `
        -Codes $sourceCodes
    if ($sourceCodes.Count -ne 0) {
        return New-VerificationResult -ExitCode 1 -Lines @(
            'VERIFY_SAFE_APK_FAIL'
            'failure_codes=' + ($sourceCodes -join ',')
        )
    }

    $resolvedSdkRoot = Resolve-AndroidSdkRoot `
        -RequestedRoot $RequestedSdkRoot `
        -LocalPropertiesPath $localPropertiesPath
    $aaptPath = Resolve-BuildTool $resolvedSdkRoot 'aapt.exe'
    $apksignerPath = Resolve-BuildTool $resolvedSdkRoot 'apksigner.bat'
    $apkanalyzerPath = Resolve-ApkAnalyzer $resolvedSdkRoot

    $apkLock = [System.IO.File]::Open(
        $resolvedApkPath,
        [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Read,
        [System.IO.FileShare]::Read
    )
    try {
        $apkStart = Get-ApkSnapshot -ResolvedApkPath $resolvedApkPath

    $safetyResult = Invoke-CapturedNative (Join-Path $PSHOME 'powershell.exe') @(
        '-NoLogo', '-NoProfile', '-NonInteractive',
        '-ExecutionPolicy', 'Bypass', '-File', $safetyScanPath,
        '-SourceRoot', (Join-Path $androidRoot 'app\src\main')
    )
    $aaptResult = Invoke-CapturedNative $aaptPath @(
        'dump', 'badging', $resolvedApkPath
    )
    $signatureResult = Invoke-CapturedSdkJavaTool $apksignerPath @(
        'verify', '--verbose', '--print-certs', $resolvedApkPath
    )
    $dexResult = Invoke-CapturedSdkJavaTool $apkanalyzerPath @(
        'dex', 'code', '--class',
        'com.vela.android.lab.VelaLabApplication', $resolvedApkPath
    )

        $apkEnd = Get-ApkSnapshot -ResolvedApkPath $resolvedApkPath
        $gitEnd = Get-GitSnapshot -GitPath $gitPath -RepoRoot $repoRoot
    } finally {
        $apkLock.Dispose()
    }

    $evidence = [pscustomobject]@{
        Head = $gitStart.Head
        OriginHead = $gitStart.OriginHead
        Branch = $gitStart.Branch
        StatusLines = @($gitStart.StatusLines)
        EndHead = $gitEnd.Head
        EndOriginHead = $gitEnd.OriginHead
        EndBranch = $gitEnd.Branch
        EndStatusLines = @($gitEnd.StatusLines)
        ManualFlagLines = @($manualFlagLines)
        BuildScriptText = $buildScriptText
        ApplicationSourceText = $applicationSourceText
        SafetyExitCode = $safetyResult.ExitCode
        SafetyText = $safetyResult.Text
        AaptExitCode = $aaptResult.ExitCode
        BadgingText = $aaptResult.Text
        SignatureExitCode = $signatureResult.ExitCode
        SignatureText = $signatureResult.Text
        DexExitCode = $dexResult.ExitCode
        DexCodeText = $dexResult.Text
        ApkSha256 = $apkStart.Sha256
        EndApkSha256 = $apkEnd.Sha256
        ApkLength = $apkStart.Length
        EndApkLength = $apkEnd.Length
        ApkLastWriteTimeUtc = $apkStart.LastWriteTimeUtc.ToString('o')
        EndApkLastWriteTimeUtc = $apkEnd.LastWriteTimeUtc.ToString('o')
    }
    $result = Test-SafeApkEvidence -Evidence $evidence
    if (-not $result.Passed) {
        return [pscustomobject]@{
            ExitCode = 1
            Lines = @(
                'VERIFY_SAFE_APK_FAIL'
                'failure_codes=' + ($result.Codes -join ',')
            )
        }
    }

    $facts = Get-AaptFacts -BadgingText $aaptResult.Text
    $certificate = Get-CertificateSha256 -SignatureText $signatureResult.Text
    return [pscustomobject]@{
        ExitCode = 0
        Lines = @(
            'VERIFY_SAFE_APK_PASS'
            'git_head_origin_main=' + $gitEnd.Head
            'worktree_clean=true'
            'manual_paper_submit_compiled_local=false'
            'debug_buildconfig_effective=false'
            'release_buildconfig_source=false'
            'feature_gate_effective=false'
            'package_id=' + $facts.ApplicationId
            'version=' + $facts.VersionCode + '/' + $facts.VersionName
            'sdk=' + $facts.MinSdk + '/' + $facts.TargetSdk
            'certificate_sha256=' + $certificate
            'safety_scan=11/0/0'
            'apk_sha256=' + $apkEnd.Sha256
            'apk_size_bytes=' + $apkEnd.Length
            'apk_last_write_utc=' + $apkEnd.LastWriteTimeUtc.ToString('o')
            'historical_sha_baseline_enforced=false'
            'read_only=true'
        )
    }
}

if ($MyInvocation.InvocationName -ne '.') {
    try {
        $verification = Invoke-SafeApkVerification `
            -RequestedApkPath $ApkPath `
            -RequestedSdkRoot $SdkRoot
        $verification.Lines | Write-Output
        exit ([int]$verification.ExitCode)
    } catch {
        Write-Output 'VERIFY_SAFE_APK_FAIL'
        Write-Output ('failure_codes=VERIFIER_ERROR')
        exit 1
    }
}
