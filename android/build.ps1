param(
    [string]$JdkPath = $env:JAVA_HOME,
    [string]$SdkPath = $env:ANDROID_HOME,
    [switch]$Offline,
    [switch]$DeviceTests
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$projectPath = $PSScriptRoot
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

$signingPath = Join-Path $projectPath 'signing'
$keyPath = Join-Path $signingPath 'release.jks'
$propertiesPath = Join-Path $signingPath 'release.properties'
New-Item -ItemType Directory -Force -Path $signingPath | Out-Null
if ((Test-Path -LiteralPath $keyPath) -xor (Test-Path -LiteralPath $propertiesPath)) {
    throw '签名文件不完整，请恢复 signing 目录的完整备份，不能替换既有签名。'
}
if (-not (Test-Path -LiteralPath $keyPath)) {
    $randomBytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($randomBytes)
    $signingPassword = [Convert]::ToBase64String($randomBytes).Replace('=', '').Replace('+', 'A').Replace('/', 'B')
    $env:AVM_SIGNING_PASSWORD = $signingPassword
    try {
        & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -keystore $keyPath -alias account-manager -keyalg RSA -keysize 3072 -validity 10000 -storetype JKS -storepass:env AVM_SIGNING_PASSWORD -keypass:env AVM_SIGNING_PASSWORD -dname 'CN=Account Verification Manager,OU=Personal App,O=Local,C=CN' -noprompt
        if ($LASTEXITCODE -ne 0) { throw '创建应用签名失败。' }
        $propertiesText = "storePassword=$signingPassword`nkeyPassword=$signingPassword`nkeyAlias=account-manager`n"
        [System.IO.File]::WriteAllText($propertiesPath, $propertiesText, [System.Text.UTF8Encoding]::new($false))
    } finally { Remove-Item Env:AVM_SIGNING_PASSWORD -ErrorAction SilentlyContinue }
}

Push-Location $projectPath
try {
    $gradleArguments = @(':app:testDebugUnitTest', ':app:lintRelease', ':app:assembleRelease', '--console=plain')
    if ($DeviceTests) { $gradleArguments += @(':app:assembleDebug', ':app:assembleDebugAndroidTest') }
    if ($Offline) { $gradleArguments += '--offline' }
    & (Join-Path $projectPath 'gradlew.bat') @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw '构建或验证失败。' }
    $releasePath = Join-Path $projectPath 'release'
    New-Item -ItemType Directory -Force -Path $releasePath | Out-Null
    $apkPath = Join-Path $releasePath '账号验证管家-v1.0.0.apk'
    Copy-Item -LiteralPath (Join-Path $projectPath 'app\build\outputs\apk\release\app-release.apk') -Destination $apkPath -Force
    $sdkBuildTools = Join-Path $SdkPath 'build-tools\37.0.0'
    & (Join-Path $sdkBuildTools 'apksigner.bat') verify --verbose $apkPath
    if ($LASTEXITCODE -ne 0) { throw 'APK 签名校验失败。' }
    $hash = Get-FileHash -LiteralPath $apkPath -Algorithm SHA256
    [System.IO.File]::WriteAllText((Join-Path $releasePath 'SHA256.txt'), "$($hash.Hash)  账号验证管家-v1.0.0.apk`n", [System.Text.UTF8Encoding]::new($false))
    Copy-Item -LiteralPath (Join-Path $projectPath '使用说明.txt') -Destination $releasePath -Force
    Write-Output "已生成：$apkPath"
} finally { Pop-Location }
