# Mirage Velocity Plugin - build script
$ErrorActionPreference = "Continue"
Set-Location $PSScriptRoot

$JDK   = "C:\Program Files\Java\jdk-25.0.3\bin"
$JAVAC = "$JDK\javac.exe"
$JAR   = "$JDK\jar.exe"
$CORE  = (Resolve-Path "..\..\frontier\out11").Path
$OUT   = "out"
$TMP   = "build_tmp"
$PLUGIN_JAR = (Join-Path (Resolve-Path "..\..").Path "mirage-velocity.jar")

Write-Host "[1/3] Compile (stub API)..."
if (Test-Path $OUT) { Remove-Item $OUT -Recurse -Force -ErrorAction SilentlyContinue }
New-Item -ItemType Directory $OUT | Out-Null
$LIBAPI = "lib\velocity-api.jar;lib\slf4j-api.jar;lib\guice.jar"
& $JAVAC --release 11 -encoding UTF-8 -cp "$CORE;$LIBAPI" -d $OUT src\main\java\mirage\mc\proxy\MirageVelocityPlugin.java 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Host "  [FAIL] compile"; exit 1 }

Write-Host "[2/3] Assemble staging..."
if (Test-Path $TMP) { Remove-Item $TMP -Recurse -Force -ErrorAction SilentlyContinue }
New-Item -ItemType Directory $TMP | Out-Null
robocopy (Join-Path $OUT "mirage") (Join-Path $TMP "mirage") /E /NFL /NDL /NJH /NJS /NC /NS | Out-Null
robocopy (Join-Path $CORE "mirage") (Join-Path $TMP "mirage") /E /NFL /NDL /NJH /NJS /NC /NS | Out-Null
$H2JAR = (Resolve-Path "..\..\frontier\lib\h2-2.5.252.jar").Path
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($H2JAR)
try {
  foreach ($e in $zip.Entries) {
    if ($e.FullName -like "META-INF/*") { continue }
    if ($e.FullName -eq "module-info.class") { continue }
    $dest = Join-Path $TMP $e.FullName
    if ($e.Name -eq "") { New-Item -ItemType Directory -Force -Path $dest | Out-Null }
    else { New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null; [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dest, $true) }
  }
} finally { $zip.Dispose() }
Copy-Item "src\main\resources\velocity-plugin.json" (Join-Path $TMP "velocity-plugin.json") -Force
# 按 Apache-2.0 要求，jar 内附带 LICENSE 与 NOTICE
Copy-Item "LICENSE" (Join-Path $TMP "LICENSE") -Force
Copy-Item "NOTICE"  (Join-Path $TMP "NOTICE")  -Force
Write-Host "  staging core class count = $((Get-ChildItem (Join-Path $TMP "mirage\core") -Filter *.class | Measure-Object).Count)"

Write-Host "[3/3] Package -> $PLUGIN_JAR"
if (Test-Path $PLUGIN_JAR) { Remove-Item $PLUGIN_JAR -Force -ErrorAction SilentlyContinue }
& $JAR cf $PLUGIN_JAR -C $TMP .
if ($LASTEXITCODE -ne 0) { Write-Host "  [FAIL] jar"; exit 1 }
Remove-Item $TMP -Recurse -Force -ErrorAction SilentlyContinue
Write-Host "[OK] done"