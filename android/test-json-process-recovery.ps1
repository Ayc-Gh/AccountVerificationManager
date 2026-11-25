#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^emulator-[0-9]+$')][string]$Serial,
    [string]$AdbPath = 'adb',
    [string]$AvdName
)

$ErrorActionPreference = 'Stop'
$deviceArgs = @{ Serial = $Serial; AdbPath = $AdbPath }
if ($AvdName) { $deviceArgs.AvdName = $AvdName }
. (Join-Path $PSScriptRoot 'test-json-picker.ps1') -FunctionsOnly @deviceArgs
$device = Initialize-AvmPickerDevice @deviceArgs
$qaPackage = 'cn.local.accountmanager.qa'
$createdDocument = $false

function Get-QaProcessId {
    $value = ([string](Invoke-AvmAdb -Device $device -Arguments @('shell', 'pidof', $qaPackage) -AllowFailure)).Trim()
    if ($value -and $value -notmatch '^[1-9][0-9]*$') { throw 'Unexpected PID response for the QA package.' }
    return $value
}

function Get-ResumedComponent {
    $line = Invoke-AvmAdb -Device $device -Arguments @('shell', "dumpsys activity activities | grep -m 1 -E '(topResumedActivity|mResumedActivity|mTopResumedActivity)[=:]' || true")
    if ($line -match 'ActivityRecord\{[^}]*?\bu[0-9]+\s+([^\s/}]+/[^\s}]+)') { return $Matches[1] }
    return ''
}

function Wait-ForQaExit([string]$OriginalId, [int]$Seconds) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        if (-not (Get-QaProcessId)) {
            $gone = Invoke-AvmAdb -Device $device -Arguments @('shell', "if [ ! -d /proc/$OriginalId ]; then echo gone; fi")
            if ($gone.Trim() -eq 'gone') { return $true }
        }
        Start-Sleep -Milliseconds 150
    } while ($watch.Elapsed.TotalSeconds -lt $Seconds)
    return $false
}

function Find-QaControl([string]$XPath, [bool]$TowardBottom) {
    foreach ($down in @($TowardBottom, !$TowardBottom)) {
        for ($i = 0; $i -lt 4; $i++) {
            $ui = Get-AvmUi -Device $device
            $match = $ui.SelectSingleNode($XPath)
            if ($match) { return ,$match }
            $scroll = $ui.SelectSingleNode("//node[@package='$qaPackage' and @scrollable='true']")
            if (-not $scroll) { break }
            $b = Get-AvmBounds $scroll
            $x = [int](($b[0] + $b[2]) / 2)
            $inset = [int](($b[3] - $b[1]) / 4)
            $low = $b[3] - $inset
            $high = $b[1] + $inset
            if ($down) { $from = $low; $to = $high } else { $from = $high; $to = $low }
            Invoke-AvmAdb -Device $device -Arguments @('shell', 'input', 'swipe', $x, $from, $x, $to, '260') | Out-Null
        }
    }
    throw "QA control did not appear: $XPath"
}

function Assert-QaButton([string]$Label, [bool]$Enabled, [bool]$TowardBottom) {
    $node = Find-QaControl "//node[@package='$qaPackage' and @class='android.widget.Button' and @text='$Label']" $TowardBottom
    if ($node.GetAttribute('enabled') -ne $Enabled.ToString().ToLowerInvariant()) {
        throw "Unexpected state for the QA login button '$Label' (expected enabled=$Enabled)."
    }
}

$pickerPackage = Get-AvmPickerPackage -Device $device
try {
    Start-AvmExportFixture -Device $device | Out-Null
    Assert-QaButton '生成 CPA 文件' $true $true
    Assert-QaButton '生成 sub2api 文件' $true $true
    Open-AvmExportPicker -Device $device -Format CPA | Out-Null
    $editor = Wait-AvmDocumentPicker -Device $device
    $documentName = $editor.GetAttribute('text')
    if ($documentName -cnotmatch '^claude-[0-9]{13}\.json$') { throw 'The real CPA picker did not offer the expected timestamped Claude filename.' }
    $documentPath = "/sdcard/Download/$documentName"
    $before = Invoke-AvmAdb -Device $device -Arguments @('shell', "if [ -e $documentPath ]; then echo present; else echo absent; fi")
    if ($before -ne 'absent') { throw 'A download already exists under the offered filename.' }
    $oldId = Get-QaProcessId
    if (-not $oldId -or (Get-ResumedComponent) -notlike "$pickerPackage/*") { throw 'Expected the real system picker in front of the live QA login.' }

    $method = 'am kill'
    Invoke-AvmAdb -Device $device -Arguments @('shell', 'am', 'kill', '--user', 'current', $qaPackage) | Out-Null
    if (-not (Wait-ForQaExit $oldId 7)) {
        if ((Get-QaProcessId) -ne $oldId -or (Get-ResumedComponent) -notlike "$pickerPackage/*") {
            throw 'QA process identity or foreground picker changed before the fallback.'
        }
        # A debug-only, same-UID SIGKILL reproduces reclaim without force-stopping the package or removing its task.
        $method = 'same-UID SIGKILL'
        Invoke-AvmAdb -Device $device -Arguments @('shell', 'run-as', $qaPackage, 'kill', '-9', $oldId) | Out-Null
        if (-not (Wait-ForQaExit $oldId 10)) { throw 'The original background QA process did not disappear.' }
    }
    if ((Get-ResumedComponent) -notlike "$pickerPackage/*") { throw 'The system document picker did not survive process death.' }
    $editor = Wait-AvmDocumentPicker -Device $device
    if ($editor.GetAttribute('text') -cne $documentName) { throw 'The surviving document picker lost its original default filename.' }
    Write-Output "PASS: $method removed QA PID $oldId; DocumentsUI remained in front."

    Save-AvmPickerDocument -Device $device -Name $documentName | Out-Null
    $createdDocument = $true
    $restartPath = "//node[@package='$qaPackage' and @text='打开官方网页' and @enabled='true']"
    Wait-AvmUi -Device $device -XPath "//node[@package='$qaPackage']" | Out-Null
    $newId = Get-QaProcessId
    if (-not $newId -or $newId -eq $oldId -or (Get-ResumedComponent) -notmatch '^cn\.local\.accountmanager\.qa/(?:cn\.local\.accountmanager)?\.LoginActivity$') {
        throw 'Returning from the picker did not restore the login Activity in a fresh QA process.'
    }
    Find-QaControl "//node[@package='$qaPackage' and @text='登录结果已失效，未写入文件。']" $true | Out-Null
    Assert-QaButton '打开官方网页' $true $false
    Assert-QaButton '完成登录' $false $true
    Assert-QaButton '生成 CPA 文件' $false $true
    Assert-QaButton '生成 sub2api 文件' $false $true
    $size = Invoke-AvmAdb -Device $device -Arguments @('shell', "if [ -f $documentPath ]; then wc -c < $documentPath; else echo missing; fi")
    if ($size.Trim() -ne '0') { throw "Expected the newly selected QA document to exist and remain empty (size result: $size)." }
    Write-Output "PASS: the picker returned to new QA PID $newId, no credential was written and login choices recovered."

    $rotationSettings = Get-AvmRotationSettings -Device $device
    $rotation = (Get-AvmUi $device).DocumentElement.GetAttribute('rotation')
    if ($rotation -notmatch '^[0-3]$') { throw 'Cannot capture the original QA display orientation.' }
    $nextRotation = if ([int]$rotation % 2 -eq 0) { 1 } else { 0 }
    try {
        Invoke-AvmAdb $device @('shell', 'wm', 'user-rotation', 'lock', $nextRotation) | Out-Null
        Wait-AvmUi $device "//hierarchy[@rotation='$nextRotation']//node[@package='$qaPackage']" | Out-Null
        if ((Get-QaProcessId) -ne $newId -or (Get-ResumedComponent) -notmatch '^cn\.local\.accountmanager\.qa/(?:cn\.local\.accountmanager)?\.LoginActivity$') {
            throw 'The restored login Activity did not remain in the same QA process across rotation.'
        }
        Write-Output "PASS: restored LoginActivity actually rotated $rotation -> $nextRotation in QA PID $newId."
        Find-QaControl "//node[@package='$qaPackage' and @text='登录结果已失效，未写入文件。']" $true | Out-Null
        Assert-QaButton '打开官方网页' $true $false
        Assert-QaButton '完成登录' $false $true
        Assert-QaButton '生成 CPA 文件' $false $true
        Assert-QaButton '生成 sub2api 文件' $false $true
        Write-Output 'PASS: process-loss notice, disabled exports and usable restart survived the real rotation.'
    } finally {
        Restore-AvmRotationSettings -Device $device -Settings $rotationSettings
    }
    # Only the nonsensitive picker choices survive the original authorized process.
    Find-QaControl "//node[@package='$qaPackage' and @text='Claude / Anthropic']" $false | Out-Null
    Find-QaControl "//node[@package='$qaPackage' and @text='官方网页手动登录与授权']" $false | Out-Null
    $resolved = Invoke-AvmAdb -Device $device -Arguments @('shell', 'cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.VIEW', '-c', 'android.intent.category.BROWSABLE', '-d', 'https://claude.ai/oauth/authorize')
    if ($resolved -notmatch '(?m)^([\w.]+)/[\w.$]+\s*$' -or $Matches[1] -in @('android', $qaPackage, $pickerPackage)) {
        throw 'The emulator has no unambiguous browser for the manual authorization page.'
    }
    $browserPackage = $Matches[1]
    $restart = Find-QaControl $restartPath $true
    Touch-AvmNode -Device $device -Node $restart | Out-Null
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ((Get-ResumedComponent) -notlike "$browserPackage/*") {
        if ($watch.Elapsed.TotalSeconds -gt 15) { throw 'The fresh login did not bring the resolved browser to the foreground.' }
        Start-Sleep -Milliseconds 200
    }
    Invoke-AvmAdb -Device $device -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_BACK') | Out-Null
    Wait-AvmUi -Device $device -XPath "//node[@package='$qaPackage']" | Out-Null
    Assert-QaButton '重新开始登录' $true $false
    Assert-QaButton '完成登录' $true $true
    Assert-QaButton '生成 CPA 文件' $false $true
    Assert-QaButton '生成 sub2api 文件' $false $true
    $close = Find-QaControl "//node[@package='$qaPackage' and @text='关闭并清除登录结果']" $true
    Touch-AvmNode -Device $device -Node $close | Out-Null
    Write-Output "PASS: fresh manual login opened $browserPackage and returned awaiting authorization; both exports were disabled."
} finally {
    if ($createdDocument) {
        Invoke-AvmAdb -Device $device -Arguments @('shell', 'rm', '-f', $documentPath) | Out-Null
    }
}
