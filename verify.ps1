param(
    [ValidateSet('All', 'Windows', 'Android')]
    [string]$Platform = 'All',
    [string]$JdkPath = $env:JAVA_HOME,
    [string]$SdkPath = $env:ANDROID_HOME,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$projectRoot = $PSScriptRoot
$configPath = Join-Path $projectRoot 'local-build.psd1'
if (Test-Path -LiteralPath $configPath) {
    $config = Import-PowerShellDataFile -LiteralPath $configPath
    if (-not $JdkPath -and $config.ContainsKey('JdkPath')) { $JdkPath = $config.JdkPath }
    if (-not $SdkPath -and $config.ContainsKey('SdkPath')) { $SdkPath = $config.SdkPath }
}
$logDirectory = Join-Path $projectRoot '.local\verification'
New-Item -ItemType Directory -Force -Path $logDirectory | Out-Null
if ($Platform -eq 'All' -or $Platform -eq 'Windows') {
    & (Join-Path $projectRoot 'build.ps1') -OutputDirectory 'release' 2>&1 |
        Tee-Object -FilePath (Join-Path $logDirectory 'windows-build.log')
}
if ($Platform -eq 'All' -or $Platform -eq 'Android') {
    # This is an already published app: never silently generate a replacement key.
    foreach ($name in @('release.jks', 'release.properties')) {
        if (-not (Test-Path -LiteralPath (Join-Path $projectRoot "android\signing\$name"))) {
            throw 'Restore the original android/signing directory before building a release.'
        }
    }
    if (-not $JdkPath -or -not $SdkPath) {
        throw 'Set JdkPath and SdkPath in local-build.psd1 or pass them as parameters.'
    }
    & (Join-Path $projectRoot 'android\build.ps1') -JdkPath $JdkPath -SdkPath $SdkPath -Offline:$Offline 2>&1 |
        Tee-Object -FilePath (Join-Path $logDirectory 'android-build.log')
}
