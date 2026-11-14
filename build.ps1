param([string]$OutputDirectory = 'release')
$ErrorActionPreference = 'Stop'
$projectDir = $PSScriptRoot
$compilerPath = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $compilerPath)) {
    $compilerPath = Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe'
}
if (-not (Test-Path -LiteralPath $compilerPath)) { throw '.NET Framework compiler was not found.' }
$releaseDir = Join-Path $projectDir $OutputDirectory
$checksDir = Join-Path $projectDir '.checks'
New-Item -ItemType Directory -Path $releaseDir,$checksDir -Force | Out-Null

# Build an original icon locally, without any downloaded assets.
Add-Type -AssemblyName System.Drawing
$iconPath = Join-Path $projectDir 'app.ico'
$bitmap = [System.Drawing.Bitmap]::new(64, 64)
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.Clear([System.Drawing.Color]::FromArgb(44, 94, 235))
$pen = [System.Drawing.Pen]::new([System.Drawing.Color]::White, 5)
$pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
$pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
$graphics.DrawLine($pen, 16, 17, 48, 17)
$graphics.DrawLine($pen, 16, 32, 35, 32)
$graphics.DrawLine($pen, 16, 47, 27, 47)
$graphics.DrawLine($pen, 40, 39, 48, 47)
$graphics.DrawLine($pen, 48, 47, 40, 55)
$pngStream = [System.IO.MemoryStream]::new()
$bitmap.Save($pngStream, [System.Drawing.Imaging.ImageFormat]::Png)
$pngData = $pngStream.ToArray()
$iconStream = [System.IO.File]::Create($iconPath)
$writer = [System.IO.BinaryWriter]::new($iconStream)
$writer.Write([uint16]0); $writer.Write([uint16]1); $writer.Write([uint16]1)
$writer.Write([byte]64); $writer.Write([byte]64); $writer.Write([byte]0); $writer.Write([byte]0)
$writer.Write([uint16]1); $writer.Write([uint16]32)
$writer.Write([uint32]$pngData.Length); $writer.Write([uint32]22); $writer.Write($pngData)
$writer.Dispose(); $pngStream.Dispose(); $pen.Dispose(); $graphics.Dispose(); $bitmap.Dispose()

$corePath = Join-Path $projectDir 'Core.cs'
$testExe = Join-Path $checksDir 'Tests.exe'
& $compilerPath /nologo /target:exe /optimize+ "/out:$testExe" $corePath (Join-Path $projectDir 'Tests.cs')
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
& $testExe
if ($LASTEXITCODE -ne 0) { throw 'Core verification failed.' }

$historyPath = Join-Path $projectDir 'History.cs'
$historyTestExe = Join-Path $checksDir 'HistoryTests.exe'
& $compilerPath /nologo /target:exe /optimize+ /r:System.Security.dll "/out:$historyTestExe" $corePath $historyPath (Join-Path $projectDir 'HistoryTests.cs')
if ($LASTEXITCODE -ne 0) { throw 'History test compilation failed.' }
& $historyTestExe
if ($LASTEXITCODE -ne 0) { throw 'History verification failed.' }

$appExe = Join-Path $releaseDir '账号验证管家.exe'
& $compilerPath /nologo /target:winexe /platform:anycpu /optimize+ /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:System.Security.dll "/win32manifest:$(Join-Path $projectDir 'app.manifest')" "/win32icon:$iconPath" "/out:$appExe" $corePath $historyPath (Join-Path $projectDir 'Program.cs') (Join-Path $projectDir 'AssemblyInfo.cs')
if ($LASTEXITCODE -ne 0) { throw 'Application compilation failed.' }
$uiCheckExe = Join-Path $checksDir 'UiChecks.exe'
& $compilerPath /nologo /target:exe /main:UiChecks /optimize+ /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:System.Security.dll "/out:$uiCheckExe" $corePath $historyPath (Join-Path $projectDir 'Program.cs') (Join-Path $projectDir 'UiChecks.cs')
if ($LASTEXITCODE -ne 0) { throw 'UI integration test compilation failed.' }
& $uiCheckExe
if ($LASTEXITCODE -ne 0) { throw 'UI integration verification failed.' }
Copy-Item -LiteralPath (Join-Path $projectDir '使用说明.txt') -Destination $releaseDir -Force
Write-Output "Built: $appExe"
