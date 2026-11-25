param(
    [string]$GradlePath = (Join-Path $PSScriptRoot 'gradlew.bat'),
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 21 before running the signing checks.' }
$keytool = Join-Path $env:JAVA_HOME 'bin/keytool.exe'
$tempRoot = (Get-Item -LiteralPath ([IO.Path]::GetTempPath())).FullName
$fixture = Join-Path $tempRoot ('avm-signing-test-' + [Guid]::NewGuid().ToString('N'))
$fixture = (New-Item -ItemType Directory -Path $fixture).FullName

function Check-Gradle([string[]]$Tasks, [bool]$ShouldPass, [string]$ExpectedText) {
    $arguments = @('-p', $fixture, '--console=plain') + $Tasks
    if ($Offline) { $arguments += '--offline' }
    $output = & $GradlePath @arguments 2>&1 | Out-String
    if (($LASTEXITCODE -eq 0) -ne $ShouldPass -or $output -notmatch $ExpectedText) {
        throw "Unexpected Gradle result for $($Tasks -join ', ') (exit $LASTEXITCODE): $output"
    }
    return $output
}

try {
    foreach ($dir in @('app', 'gradle')) { New-Item -ItemType Directory -Path (Join-Path $fixture $dir) | Out-Null }
    foreach ($file in @('build.ps1', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'app/build.gradle.kts', 'gradle/release-certificate.sha256')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $file) -Destination (Join-Path $fixture $file)
    }
    $testSigning = Join-Path $fixture 'signing'
    $config = Join-Path $testSigning 'release.properties'
    $store = Join-Path $testSigning 'release.jks'
    foreach ($state in @('missing', 'partial')) {
        if ($state -eq 'partial') {
            New-Item -ItemType Directory -Path $testSigning | Out-Null
            Set-Content -LiteralPath $config -Encoding ascii -Value 'keyAlias=test'
        }
        try { & (Join-Path $fixture 'build.ps1'); throw 'Standalone build accepted missing signing files.' }
        catch { if ($_.Exception.Message -notmatch 'android/signing') { throw } }
        if ((Test-Path -LiteralPath $store) -or (Test-Path -LiteralPath (Join-Path $fixture 'local.properties'))) {
            throw "Standalone build wrote files while the signing configuration was $state."
        }
    }
    Check-Gradle @(':app:verifyReleaseSigning') $false 'Missing release signing configuration' | Out-Null
    foreach ($task in @(':app:assembleRelease', ':app:bundleRelease')) {
        Check-Gradle @($task, '--dry-run') $true ':app:verifyReleaseSigning' | Out-Null
    }
    $debugGraph = Check-Gradle @(':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintRelease', '--dry-run') $true ':app:testDebugUnitTest'
    if ($debugGraph -match ':app:verifyReleaseSigning') { throw 'Debug or lint tasks unexpectedly require a release signer.' }

    $testCert = Join-Path $fixture 'test.der'
    & $keytool -genkeypair -storetype JKS -keystore $store -storepass fixture-only -keypass fixture-only -alias test -keyalg RSA -keysize 2048 -validity 2 -dname 'CN=Signing Test' -noprompt 2>&1 | Out-Null
    if ($LASTEXITCODE) { throw 'Could not generate a disposable test key.' }
    Set-Content -LiteralPath $config -Encoding ascii -Value @('keyAlias=test', 'storePassword=fixture-only', 'keyPassword=fixture-only')
    Check-Gradle @(':app:verifyReleaseSigning') $false 'does not match the published Android app' | Out-Null
    & $keytool -exportcert -keystore $store -storepass fixture-only -alias test -file $testCert 2>&1 | Out-Null
    if ($LASTEXITCODE) { throw 'Could not export the disposable test certificate.' }
    $testFingerprint = (Get-FileHash -LiteralPath $testCert).Hash
    Set-Content -LiteralPath (Join-Path $fixture 'gradle/release-certificate.sha256') -Encoding ascii -Value $testFingerprint
    Check-Gradle @(':app:verifyReleaseSigning') $true 'BUILD SUCCESSFUL' | Out-Null

    # Exercise the final publication step independently of Gradle and without creating an APK.
    $fakeSdk = Join-Path $fixture 'fixture-sdk'
    $fakeTools = New-Item -ItemType Directory -Force -Path (Join-Path $fakeSdk 'build-tools/37.0.0')
    $outputDir = New-Item -ItemType Directory -Force -Path (Join-Path $fixture 'app/build/outputs/apk/release')
    $releaseDir = New-Item -ItemType Directory -Path (Join-Path $fixture 'release')
    $publishedApk = Join-Path $releaseDir '账号验证管家-vfixture.apk'
    Set-Content -LiteralPath (Join-Path $fixture 'gradlew.bat') -Encoding ascii -Value '@exit /b 0'
    Set-Content -LiteralPath (Join-Path $fakeTools 'apksigner.bat') -Encoding ascii -Value '@type "%~dp0report.txt"'
    Set-Content -LiteralPath (Join-Path $outputDir 'output-metadata.json') -Value '{"elements":[{"versionName":"fixture"}]}'
    Set-Content -LiteralPath (Join-Path $outputDir 'app-release.apk') -Value 'candidate'
    Set-Content -LiteralPath (Join-Path $fixture '使用说明.txt') -Value 'fixture'
    Set-Content -LiteralPath $publishedApk -Value 'previous'
    foreach ($report in @('no signers', ('V2 Signer: certificate SHA-256 digest: ' + ('0' * 64)))) {
        Set-Content -LiteralPath (Join-Path $fakeTools 'report.txt') -Encoding ascii -Value $report
        try { & (Join-Path $fixture 'build.ps1') -SdkPath $fakeSdk; throw 'Publication accepted an invalid signer report.' }
        catch { if ($_.Exception.Message -notmatch '签名证书') { throw } }
        if ((Get-Content -LiteralPath $publishedApk) -ne 'previous') { throw 'A failed signature check replaced the previous deliverable.' }
    }
    Set-Content -LiteralPath (Join-Path $fakeTools 'report.txt') -Encoding ascii -Value "V2 Signer: certificate SHA-256 digest: $testFingerprint"
    & (Join-Path $fixture 'build.ps1') -SdkPath $fakeSdk | Out-Null
    if ((Get-Content -LiteralPath $publishedApk) -ne 'candidate') { throw 'The validated fixture was not copied.' }
    Write-Output 'Android signing checks passed (build inputs, APK/Bundle and QA/lint tasks, delivery signer and previous output).'
} finally {
    $resolved = (Get-Item -LiteralPath $fixture).FullName
    if ((Split-Path -Parent $resolved) -ne $tempRoot.TrimEnd('\') -or (Split-Path -Leaf $resolved) -notmatch '^avm-signing-test-[a-f0-9]{32}$') {
        throw 'Unexpected signing test directory.'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
