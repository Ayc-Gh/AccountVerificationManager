param(
    [string]$JdkPath = $env:JAVA_HOME,
    [string]$SdkPath = $env:ANDROID_HOME,
    [switch]$Offline,
    [switch]$DeviceTests
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$projectPath = $PSScriptRoot
foreach ($name in @('release.jks', 'release.properties')) {
    if (-not (Test-Path -LiteralPath (Join-Path $projectPath "signing\$name") -PathType Leaf)) {
        throw '正式构建需要当前发布签名，请恢复 android/signing 目录的完整备份。'
    }
}
if (-not $JdkPath) {
    $JdkPath = Join-Path (Split-Path $projectPath) '.analysis\location-network-20261103\toolchain\jdk21\jdk-21.0.13+9'
}
if (-not (Test-Path -LiteralPath (Join-Path $JdkPath 'bin\java.exe'))) { throw '请指定 JDK 21：-JdkPath <path>' }
$env:JAVA_HOME = (Resolve-Path -LiteralPath $JdkPath).Path
if (-not $SdkPath) {
    $SdkPath = Join-Path (Split-Path $projectPath) '.analysis\location-network-20261103\toolchain\sdk'
}
if (-not (Test-Path -LiteralPath $SdkPath)) { throw '请指定 Android SDK：-SdkPath <path>' }
$resolvedSdk = (Resolve-Path -LiteralPath $SdkPath).Path.Replace('\', '/').Replace(':', '\:')
[System.IO.File]::WriteAllText((Join-Path $projectPath 'local.properties'), "sdk.dir=$resolvedSdk", [System.Text.UTF8Encoding]::new($false))

Push-Location $projectPath
try {
    $gradleArguments = @(':app:verifyReleaseSigning', ':app:testDebugUnitTest', ':app:lintRelease', ':app:assembleRelease', '--console=plain')
    if ($DeviceTests) { $gradleArguments += @(':app:assembleDebug', ':app:assembleDebugAndroidTest') }
    if ($Offline) { $gradleArguments += '--offline' }
    & (Join-Path $projectPath 'gradlew.bat') @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw '构建或验证失败。' }
    $builtApk = Join-Path $projectPath 'app\build\outputs\apk\release\app-release.apk'
    $sdkBuildTools = Join-Path $SdkPath 'build-tools\37.0.0'
    $certificateReport = & (Join-Path $sdkBuildTools 'apksigner.bat') verify --print-certs $builtApk
    if ($LASTEXITCODE -ne 0) { throw 'APK 签名校验失败。' }
    $signers = @($certificateReport | Select-String 'certificate SHA-256 digest:\s*([a-f0-9]{64})\s*$' |
        ForEach-Object { $_.Matches[0].Groups[1].Value } | Sort-Object -Unique)
    $publishedSigner = (Get-Content -LiteralPath (Join-Path $projectPath 'gradle\release-certificate.sha256') -Raw).Trim()
    if ($signers.Count -ne 1 -or $signers[0] -ne $publishedSigner) {
        throw 'APK 签名证书与当前发布指纹不一致。'
    }
    $metadataPath = Join-Path $projectPath 'app\build\outputs\apk\release\output-metadata.json'
    $metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
    $version = $metadata.elements[0].versionName
    $apkName = "账号验证管家-v$version.apk"
    $releasePath = Join-Path $projectPath 'release'
    New-Item -ItemType Directory -Force -Path $releasePath | Out-Null
    $apkPath = Join-Path $releasePath $apkName
    Copy-Item -LiteralPath $builtApk -Destination $apkPath -Force
    $hash = Get-FileHash -LiteralPath $apkPath -Algorithm SHA256
    [System.IO.File]::WriteAllText((Join-Path $releasePath "SHA256-v$version.txt"), "$($hash.Hash)  $apkName`n", [System.Text.UTF8Encoding]::new($false))
    Copy-Item -LiteralPath (Join-Path $projectPath '使用说明.txt') -Destination $releasePath -Force
    Write-Output "已生成：$apkPath"
} finally { Pop-Location }
