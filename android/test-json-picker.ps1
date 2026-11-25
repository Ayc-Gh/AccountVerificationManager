#requires -Version 7.0
param(
    [string]$Serial,
    [string]$AdbPath = 'adb.exe',
    [string]$AvdName,
    [switch]$FunctionsOnly
)

# Shared by the process-recovery test. Importing the helpers never starts a device.
function Invoke-AvmAdb {
    param($Device, [string[]]$Arguments, [switch]$AllowFailure)
    if ($Device.Serial -notmatch '^emulator-[0-9]+$') { throw 'An explicit local emulator serial is required.' }
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $Device.Adb
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = [Text.Encoding]::UTF8
    foreach ($arg in (@('-H', '127.0.0.1', '-P', '5037', '-s', $Device.Serial) + $Arguments)) {
        $info.ArgumentList.Add($arg)
    }
    $process = [Diagnostics.Process]::Start($info)
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(35000)) { $process.Kill(); throw "ADB timed out during $($Arguments[0])." }
        $result = $stdout.GetAwaiter().GetResult()
        $null = $stderr.GetAwaiter().GetResult()
        if (-not $AllowFailure -and $process.ExitCode -ne 0) { throw "ADB $($Arguments[0]) failed (exit $($process.ExitCode))." }
        return $result.TrimEnd()
    } finally { $process.Dispose() }
}

function Initialize-AvmPickerDevice {
    param([string]$Serial, [string]$AdbPath = 'adb.exe', [string]$AvdName)
    if ($Serial -notmatch '^emulator-[0-9]+$') { throw 'Pass the explicit serial of the local QA emulator.' }
    $device = [pscustomobject]@{ Serial = $Serial; Adb = (Get-Command $AdbPath -CommandType Application -ErrorAction Stop).Source; PickerPackage = '' }
    if ((Invoke-AvmAdb $device @('get-state')) -ne 'device' -or
        ((Invoke-AvmAdb $device @('shell', 'getprop', 'ro.kernel.qemu')) -ne '1' -and (Invoke-AvmAdb $device @('shell', 'getprop', 'ro.boot.qemu')) -ne '1')) {
        throw 'The selected device is not a running local Android emulator.'
    }
    $name = ((Invoke-AvmAdb $device @('emu', 'avd', 'name')) -split '\r?\n')[0].Trim()
    if (($AvdName -and $name -cne $AvdName) -or (-not $AvdName -and $name -notmatch 'QA')) {
        throw "Unexpected AVD '$name'. Specify the expected QA AVD using -AvdName."
    }
    if ((Invoke-AvmAdb $device @('shell', 'pm', 'path', 'cn.local.accountmanager.qa')) -notmatch '^package:') {
        throw 'Install the independent QA application on the selected emulator first.'
    }
    $resolver = Invoke-AvmAdb $device @('shell', 'cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.CREATE_DOCUMENT', '-c', 'android.intent.category.OPENABLE', '-t', 'application/json')
    if ($resolver -notmatch '(?m)^((?:com\.google\.android|com\.android)\.documentsui)/') { throw 'The system DocumentsUI picker could not be identified.' }
    $device.PickerPackage = $Matches[1]
    return $device
}

function Get-AvmPickerPackage { param($Device) return $Device.PickerPackage }

function Get-AvmRotationSettings {
    param($Device)
    $mode = (Invoke-AvmAdb $Device @('shell', 'wm', 'user-rotation')).Trim()
    $value = (Invoke-AvmAdb $Device @('shell', 'settings', 'get', 'system', 'user_rotation')).Trim()
    if ($mode -notmatch '^(free|lock(?:ed)?(?:\s+[0-3])?)$' -or $value -notmatch '^([0-3]|null)$') {
        throw 'Cannot record the existing rotation settings of the QA emulator.'
    }
    return [pscustomobject]@{ Mode = $mode; Value = $value }
}

function Restore-AvmRotationSettings {
    param($Device, $Settings)
    $restore = if ($Settings.Value -eq 'null') { '0' } else { $Settings.Value }
    $null = Invoke-AvmAdb $Device @('shell', 'wm', 'user-rotation', 'lock', $restore)
    if ($Settings.Mode -eq 'free') { $null = Invoke-AvmAdb $Device @('shell', 'wm', 'user-rotation', 'free') }
    if ($Settings.Value -eq 'null') { $null = Invoke-AvmAdb $Device @('shell', 'settings', 'delete', 'system', 'user_rotation') }
}

function Get-AvmUi {
    param($Device)
    $remote = '/data/local/tmp/avm-picker-' + [guid]::NewGuid().ToString('N') + '.xml'
    try {
        $null = Invoke-AvmAdb $Device @('shell', 'uiautomator', 'dump', '--compressed', $remote)
        $dump = Invoke-AvmAdb $Device @('exec-out', 'cat', $remote)
        return ,([xml]$dump)
    } finally { $null = Invoke-AvmAdb $Device @('shell', 'rm', '-f', $remote) -AllowFailure }
}

function Wait-AvmUi {
    param($Device, [string]$XPath, [int]$TimeoutSeconds = 35)
    $time = [Diagnostics.Stopwatch]::StartNew()
    do {
        try { $node = (Get-AvmUi $Device).SelectSingleNode($XPath); if ($node) { return ,$node } }
        catch { if ($time.Elapsed.TotalSeconds -ge $TimeoutSeconds) { throw } }
        Start-Sleep -Milliseconds 200
    } while ($time.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw "Android did not display the expected control within $TimeoutSeconds seconds ($XPath)."
}

function Get-AvmBounds {
    param([Xml.XmlElement]$Node)
    if ($Node.GetAttribute('bounds') -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { throw 'Android returned unusable control bounds.' }
    $box = @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3], [int]$Matches[4])
    if ($box[2] -le $box[0] -or $box[3] -le $box[1]) { throw 'The requested UI control is outside the visible area.' }
    return $box
}

function Touch-AvmNode {
    param($Device, [Xml.XmlElement]$Node)
    if ($Node.GetAttribute('package') -notin @('cn.local.accountmanager.qa', $Device.PickerPackage)) { throw 'Unexpected foreground application.' }
    $box = Get-AvmBounds $Node
    $x = [int](($box[0] + $box[2]) / 2)
    $y = [int](($box[1] + $box[3]) / 2)
    $null = Invoke-AvmAdb $Device @('shell', 'input', 'tap', $x, $y)
}

function Start-AvmExportFixture {
    param($Device)
    $null = Invoke-AvmAdb $Device @('shell', 'am', 'start', '-W', '-n', 'cn.local.accountmanager.qa/cn.local.accountmanager.ExportFixtureActivity')
    $null = Wait-AvmUi $Device "//node[@package='cn.local.accountmanager.qa' and (@text='生成 CPA 文件' or @text='登录并生成 CPA / sub2api')]"
}

function Wait-AvmDocumentPicker {
    param($Device)
    return ,(Wait-AvmUi $Device "//node[@package='$($Device.PickerPackage)' and @class='android.widget.EditText' and @resource-id='android:id/title']")
}

function Open-AvmExportPicker {
    param($Device, [ValidateSet('CPA', 'sub2api')][string]$Format)
    $label = "生成 $Format 文件"
    for ($attempt = 0; $attempt -lt 6; $attempt++) {
        $ui = Get-AvmUi $Device
        $button = $ui.SelectSingleNode("//node[@package='cn.local.accountmanager.qa' and @text='$label' and @enabled='true']")
        if ($button) { Touch-AvmNode $Device $button; $null = Wait-AvmDocumentPicker $Device; return }
        $scroll = $ui.SelectSingleNode("//node[@package='cn.local.accountmanager.qa' and @scrollable='true']")
        if (-not $scroll) { Start-Sleep -Milliseconds 250; continue }
        $bounds = Get-AvmBounds $scroll
        $x = [int](($bounds[0] + $bounds[2]) / 2)
        $height = $bounds[3] - $bounds[1]
        $null = Invoke-AvmAdb $Device @('shell', 'input', 'swipe', $x, [int]($bounds[3] - $height / 4), $x, [int]($bounds[1] + $height / 4), '300')
    }
    throw "The authorized QA page did not enable $label."
}

function Save-AvmPickerDocument {
    param($Device, [string]$Name)
    if ($Name -cnotmatch '^(claude|sub2api)-[0-9]{13}\.json$') { throw 'Expected the original timestamped filename generated by the QA application.' }
    $path = "/sdcard/Download/$Name"
    $editor = Wait-AvmDocumentPicker $Device
    if ($editor.GetAttribute('text') -cne $Name) { throw 'Unexpected filename in the system document picker.' }
    $ui = $editor.OwnerDocument
    $pkg = "@package='$($Device.PickerPackage)'"
    $downloads = "(@text='Downloads' or @text='下载' or @text='下载内容' or @text='下載')"
    $crumb = "//node[$pkg and @resource-id='$($Device.PickerPackage):id/breadcrumb_text']"
    $downloadsRoot = "$crumb[$downloads and count($crumb)=1]"
    if (-not $ui.SelectSingleNode($downloadsRoot)) {
        $root = $ui.SelectSingleNode("//node[$pkg and $downloads and ancestor::node[contains(@resource-id, 'roots')]]")
        if (-not $root) {
            $navigation = $ui.SelectSingleNode("//node[$pkg and (@content-desc='Show roots' or @content-desc='显示根目录' or @content-desc='显示根文件夹' or @content-desc='Open navigation drawer')]")
            if (-not $navigation) { throw 'Cannot open the system document picker navigation drawer.' }
            Touch-AvmNode $Device $navigation
            $root = Wait-AvmUi $Device "//node[$pkg and $downloads and not(@resource-id='$($Device.PickerPackage):id/breadcrumb_text')]"
        }
        Touch-AvmNode $Device $root
        $null = Wait-AvmUi $Device $downloadsRoot
    }
    $ime = Invoke-AvmAdb $Device @('shell', 'dumpsys', 'input_method')
    if ($ime -match '\bmInputShown=(true|false)' -and $Matches[1] -eq 'true') {
        $null = Invoke-AvmAdb $Device @('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        $watch = [Diagnostics.Stopwatch]::StartNew()
        do {
            $ime = Invoke-AvmAdb $Device @('shell', 'dumpsys', 'input_method')
            if ($ime -match '\bmInputShown=(true|false)' -and $Matches[1] -eq 'false') { break }
            if ($watch.Elapsed.TotalSeconds -ge 8) { throw 'The system keyboard still covers the document picker.' }
            Start-Sleep -Milliseconds 150
        } while ($true)
    }
    $title = "//node[$pkg and @class='android.widget.EditText' and @resource-id='android:id/title' and @text='$Name']"
    $save = Wait-AvmUi $Device "//node[$pkg and @class='android.widget.Button' and @resource-id='android:id/button1' and @enabled='true' and @clickable='true' and boolean($title) and boolean($downloadsRoot)]"
    if ((Invoke-AvmAdb $Device @('shell', "if [ -e $path ]; then echo present; fi")) -eq 'present') {
        throw "The default filename already exists in Downloads: $Name"
    }
    Touch-AvmNode $Device $save
    try {
        $null = Wait-AvmUi $Device "//node[@package='cn.local.accountmanager.qa' and not(//node[$pkg])]"
        if ((Invoke-AvmAdb $Device @('shell', "if [ -f $path ]; then echo created; fi")) -ne 'created') { throw 'DocumentsUI returned without creating the expected document.' }
    } catch {
        # This exact default name was absent before tapping SAVE on the isolated QA emulator.
        $null = Invoke-AvmAdb $Device @('shell', 'rm', '-f', $path) -AllowFailure
        throw
    }
}

function Confirm-AvmExportFile {
    param($Device, [string]$Name, [string]$Format)
    $time = [Diagnostics.Stopwatch]::StartNew()
    $json = $null
    while ($time.Elapsed.TotalSeconds -lt 30) {
        $raw = Invoke-AvmAdb $Device @('exec-out', 'cat', "/sdcard/Download/$Name") -AllowFailure
        if ($raw) {
            try { $json = ConvertFrom-Json -AsHashtable -InputObject $raw -ErrorAction Stop; break }
            catch { } # The provider may still be flushing the document.
        }
        Start-Sleep -Milliseconds 250
    }
    if (-not $json) { throw "The system picker did not produce a complete $Format JSON document in Downloads." }
    $creds = $json
    $organizationKey = 'organization_uuid'
    if ($Format -eq 'sub2api') {
        if ($json.type -cne 'sub2api-data' -or $json.version -ne 1 -or $json.accounts.Count -ne 1 -or
            $json.accounts[0].platform -cne 'anthropic' -or $json.accounts[0].type -cne 'oauth') {
            throw 'Unexpected sub2api document structure.'
        }
        $creds = $json.accounts[0].credentials
        $organizationKey = 'org_uuid'
    } elseif ($json.type -cne 'claude') { throw 'Unexpected CPA document structure.' }
    if ($creds.email -cne 'alice@example.com' -or $creds.account_uuid -cne 'rotation-account' -or
        $creds.access_token -cne 'rotation-access' -or $creds.refresh_token -cne 'rotation-refresh' -or
        $creds[$organizationKey] -cne 'rotation-org') { throw "Unexpected synthetic credential fields in $Format." }
}

function Wait-AvmExportNotice {
    param($Device, [string]$Format)
    $notice = "$Format 文件已生成，可继续生成另一种格式。"
    $null = Wait-AvmUi $Device "//node[@package='cn.local.accountmanager.qa']"
    for ($attempt = 0; $attempt -lt 10; $attempt++) {
        $ui = Get-AvmUi $Device
        if ($ui.SelectSingleNode("//node[@package='cn.local.accountmanager.qa' and @text='$notice']")) { return }
        $scroll = $ui.SelectSingleNode("//node[@package='cn.local.accountmanager.qa' and @scrollable='true']")
        if ($scroll) {
            $bounds = Get-AvmBounds $scroll
            $x = [int](($bounds[0] + $bounds[2]) / 2)
            $height = $bounds[3] - $bounds[1]
            $top = [int]($bounds[1] + $height * .30)
            $bottom = [int]($bounds[1] + $height * .68)
            if ($attempt -ge 6) { $top, $bottom = $bottom, $top }
            $null = Invoke-AvmAdb $Device @('shell', 'input', 'swipe', $x, $bottom, $x, $top, '350')
        } else { Start-Sleep -Milliseconds 250 }
    }
    throw "The resumed QA login page did not display the completion notice for $Format."
}

if ($FunctionsOnly) { return }
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$target = Initialize-AvmPickerDevice -Serial $Serial -AdbPath $AdbPath -AvdName $AvdName
$rotationSettings = Get-AvmRotationSettings $target
$files = [Collections.Generic.List[string]]::new()
try {
    Start-AvmExportFixture $target
    foreach ($format in @('CPA', 'sub2api')) {
        Open-AvmExportPicker $target $format
        $before = Wait-AvmDocumentPicker $target
        $title = $before.GetAttribute('text')
        $prefix = if ($format -eq 'CPA') { 'claude-' } else { 'sub2api-' }
        if ($title -cnotmatch "^$prefix[0-9]{13}\.json$") { throw "The system picker was not opened with the default name for $format." }
        $previous = $before.OwnerDocument.DocumentElement.GetAttribute('rotation')
        if ($previous -notmatch '^[0-3]$') { throw 'Cannot determine the current display rotation.' }
        $next = if ([int]$previous % 2 -eq 0) { 1 } else { 0 }
        $null = Invoke-AvmAdb $target @('shell', 'wm', 'user-rotation', 'lock', $next)
        $after = Wait-AvmUi $target "//hierarchy[@rotation='$next']//node[@package='$($target.PickerPackage)' and @class='android.widget.EditText' and @resource-id='android:id/title']"
        if ($after.GetAttribute('text') -cne $title) { throw "The $format picker lost its pending filename during rotation." }
        Save-AvmPickerDocument $target $title
        $files.Add($title)
        Wait-AvmExportNotice $target $format
        Confirm-AvmExportFile $target $title $format
        Write-Output "PASS ${format}: system DocumentsUI stayed open through rotation $previous -> $next, the QA page confirmed completion, and Downloads contained the expected JSON."
    }
} finally {
    try { Restore-AvmRotationSettings $target $rotationSettings }
    finally { foreach ($file in $files) { $null = Invoke-AvmAdb $target @('shell', 'rm', '-f', "/sdcard/Download/$file") -AllowFailure } }
}
