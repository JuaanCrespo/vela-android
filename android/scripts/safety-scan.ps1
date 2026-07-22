[CmdletBinding()]
param(
    [string]$SourceRoot = (Join-Path $PSScriptRoot '..\app\src\main')
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$resolvedSourceRoot = (Resolve-Path -LiteralPath $SourceRoot).Path

if (-not $resolvedSourceRoot.StartsWith($projectRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "SourceRoot must stay inside $projectRoot (got $resolvedSourceRoot)."
}

$paperOrdersUrl = 'https://paper-api.alpaca.markets/v2/orders'
$dangerPattern = [regex]::new(
    '(?i)submitOrder|placeOrder|cancelOrder|replaceOrder|closePosition|executeOrder|' +
    'executePostOrder|submitOnce|https://api\.alpaca\.markets|/v2/orders|' +
    '\.\s*(?:post|delete|patch)\s*\(|@(?:POST|DELETE|PATCH|PUT)\s*\(|' +
    '\.method\s*\(\s*"(?:POST|DELETE|PATCH|PUT)"|' +
    'Auto[ _]Paper|foregroundService|canExecuteOrders|executionEnabled|unlockRealMode'
)

$alwaysForbiddenPatterns = @(
    [regex]::new(
        '(?i)\bfun\s+(?:<[^>]+>\s*)?' +
        '(?:cancelOrder|replaceOrder|closePosition|placeOrder|executeOrder)\b'
    ),
    [regex]::new('(?i)\.\s*(?:delete|patch)\s*\('),
    [regex]::new('(?i)@(?:DELETE|PATCH|PUT)\s*\('),
    [regex]::new('(?i)\.method\s*\(\s*"(?:DELETE|PATCH|PUT)"'),
    [regex]::new(
        '(?i)\b(?:canExecuteOrders|executionEnabled|autoPaperEnabled|' +
        'foregroundServiceEnabled)\s*=\s*true\b'
    ),
    [regex]::new('(?i)\bAUTO_PAPER(?!_DISABLED)\b')
)

$findings = [System.Collections.Generic.List[object]]::new()
$sourceFiles = Get-ChildItem -LiteralPath $resolvedSourceRoot -Recurse -File |
    Where-Object { $_.Extension -in @('.kt', '.xml') }

foreach ($file in $sourceFiles) {
    $relativePath = $file.FullName.Substring($projectRoot.Length).TrimStart('\', '/')
    $lineNumber = 0
    foreach ($line in Get-Content -LiteralPath $file.FullName) {
        $lineNumber++
        $isHttpPutShape = $file.Name -like 'OkHttp*.kt' -and
            $line -match '(?i)^\s*\.put\s*\('
        if (-not $dangerPattern.IsMatch($line) -and -not $isHttpPutShape) {
            continue
        }

        $trimmed = $line.TrimStart()
        $isComment = $trimmed.StartsWith('//') -or
            $trimmed.StartsWith('/*') -or
            $trimmed.StartsWith('*') -or
            $trimmed.StartsWith('*/')
        $isUnlockDeclaration = $line -match '(?i)\bfun\s+unlockRealMode\s*\('
        $isUnlockCall = -not $isComment -and
            $line -match '(?i)\bunlockRealMode\s*\(' -and
            -not $isUnlockDeclaration

        $isSubmitHttpFile = $relativePath -match
            '(?i)data[\\/]paper[\\/]submit[\\/]AlpacaPaperOrderSubmitHttpClient\.kt$'
        $isSubmitEndpointFile = $relativePath -match
            '(?i)data[\\/]paper[\\/]submit[\\/]AlpacaPaperSubmitEndpoint\.kt$'
        $isSubmitPackage = $relativePath -match '(?i)data[\\/]paper[\\/]submit[\\/]'
        $isSubmitUi = $relativePath -match
            '(?i)ui[\\/]dashboard[\\/](?:PaperManualSubmitViewModel|OfflineDashboardScreen)\.kt$'
        $isAllowedPostBuilder = $isSubmitHttpFile -and
            $line -match '(?i)\.\s*post\s*\('
        $isAllowedSubmitUrl = $isSubmitEndpointFile -and $line.Contains($paperOrdersUrl)
        $isAllowedSubmitMethod = ($isSubmitPackage -or $isSubmitUi) -and
            $line -match '(?i)executePostOrder|submitOnce'
        $isAllowedPhase2v = -not $isComment -and (
            $isAllowedPostBuilder -or $isAllowedSubmitUrl -or $isAllowedSubmitMethod
        )

        $hasUnapprovedPost = -not $isComment -and
            $line -match '(?i)\.\s*post\s*\(' -and -not $isAllowedPostBuilder
        $hasUnapprovedOrdersLiteral = -not $isComment -and
            $line.Contains($paperOrdersUrl) -and -not $isAllowedSubmitUrl
        $hasAlwaysForbidden = -not $isComment -and
            ($alwaysForbiddenPatterns |
                Where-Object { $_.IsMatch($line) } |
                Select-Object -First 1)

        if ($isUnlockCall -or $isHttpPutShape -or $hasUnapprovedPost -or
            $hasUnapprovedOrdersLiteral -or $hasAlwaysForbidden
        ) {
            $category = 'FORBIDDEN_HIT'
        } elseif ($isAllowedPhase2v) {
            $category = 'ALLOWED_PHASE_2V_PAPER_SUBMIT_BOUNDARY'
        } elseif ($isComment -or
            $line -match '(?i)disabled|false|rejected|reject|forbidden|no order|no auto' -or
            $line -match '(?i)\b(?:canExecuteOrders|executionEnabled)\b' -or
            $isUnlockDeclaration -or
            $relativePath -match '(?i)AlpacaPaperTradingEndpoint|AlpacaStreamEndpoint|MarketDataConfig' -or
            ($isSubmitEndpointFile -and $line -match '(?i)api\.alpaca\.markets') -or
            ($relativePath -match 'OfflineDashboardScreen' -and
                $line -match '(?i)Auto Paper|foregroundService|executionEnabled')
        ) {
            $category = 'ALLOWED_NEGATIVE_DOC_OR_GUARD'
        } else {
            $category = 'SUSPICIOUS_PRODUCTION_HIT'
        }

        $findings.Add([pscustomobject]@{
            Category = $category
            File = $relativePath
            Line = $lineNumber
            Text = $line.Trim()
        })
    }
}

foreach ($category in @(
    'ALLOWED_PHASE_2V_PAPER_SUBMIT_BOUNDARY',
    'ALLOWED_NEGATIVE_DOC_OR_GUARD',
    'SUSPICIOUS_PRODUCTION_HIT',
    'FORBIDDEN_HIT'
)) {
    $matches = @($findings | Where-Object Category -eq $category)
    Write-Output "[$category] $($matches.Count)"
    foreach ($match in $matches) {
        Write-Output "  $($match.File):$($match.Line): $($match.Text)"
    }
}

$allowedSubmitCount =
    @($findings | Where-Object Category -eq 'ALLOWED_PHASE_2V_PAPER_SUBMIT_BOUNDARY').Count
$suspiciousCount = @($findings | Where-Object Category -eq 'SUSPICIOUS_PRODUCTION_HIT').Count
$forbiddenCount = @($findings | Where-Object Category -eq 'FORBIDDEN_HIT').Count
Write-Output (
    "Safety scan summary: allowed_phase2v_submit=$allowedSubmitCount " +
    "suspicious=$suspiciousCount forbidden=$forbiddenCount"
)

if ($suspiciousCount -gt 0 -or $forbiddenCount -gt 0) {
    exit 1
}

exit 0
