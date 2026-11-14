# Mirage Folia Plugin - build script
# Folia 是 Paper 的区域化多线程深度优化分支，线程模型与 API 约束与 Paper 差异极大，
# 因此作为独立项目维护，与 nms/（NMS + Leaf）分开。
$ErrorActionPreference = "Continue"
Set-Location $PSScriptRoot

$JDK  = "C:\Program Files\Java\jdk-25.0.3\bin"
$JAVAC = "$JDK\javac.exe"
$JAR   = "$JDK\jar.exe"
$ROOT  = (Resolve-Path "..\..\..").Path
$CORE  = (Resolve-Path (Join-Path $ROOT "frontier\out11")).Path
$H2JAR = (Resolve-Path (Join-Path $ROOT "frontier\lib\h2-2.5.252.jar")).Path
$API   = "lib\paper-api.jar"
$OUT   = "out"
$TMP   = "build_tmp"
$JAR_FOLIA = Join-Path $ROOT "mirage-folia.jar"

$SRC = "src\main\java\mirage\mc\folia\MirageFoliaPlugin.java"

if (-not (Test-Path $API)) {
    Write-Host "[WARN] paper-api.jar not found; using _stub (compile check only)"
    $API = "_stub"
}

Write-Host "[1/3] Compile Folia plugin..."
if (Test-Path $OUT) { Remove-Item $OUT -Recurse -Force -ErrorAction SilentlyContinue }
New-Item -ItemType Directory $OUT | Out-Null
if ($API -eq "_stub") {
    & $JAVAC --release 11 -encoding UTF-8 -cp $CORE -d $OUT _stub\org\bukkit\plugin\java\JavaPlugin.java $SRC 2>&1 | Out-Null
} else {
    & $JAVAC --release 11 -encoding UTF-8 -cp "$CORE;$API" -d $OUT $SRC 2>&1 | Out-Null
}
if ($LASTEXITCODE -ne 0) { Write-Host "  [FAIL] compile"; exit 1 }

Write-Host "[2/3] Assemble staging..."
if (Test-Path $TMP) { Remove-Item $TMP -Recurse -Force -ErrorAction SilentlyContinue }
New-Item -ItemType Directory $TMP | Out-Null
robocopy $OUT $TMP /E /NFL /NDL /NJH /NJS /NC /NS | Out-Null
robocopy (Join-Path $CORE "mirage") (Join-Path $TMP "mirage") /E /NFL /NDL /NJH /NJS /NC /NS | Out-Null
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
# Folia 只识别 plugin.yml（不认 folia-plugin.yml）
$meta = "src\main\resources\plugin.yml"
if (-not (Test-Path $meta)) { Write-Host "  [FAIL] $meta missing"; exit 1 }
Copy-Item $meta (Join-Path $TMP "plugin.yml") -Force
# 按 Apache-2.0 要求，jar 内附带 LICENSE 与 NOTICE
Copy-Item "LICENSE" (Join-Path $TMP "LICENSE") -Force
Copy-Item "NOTICE"  (Join-Path $TMP "NOTICE")  -Force
if (-not (Test-Path (Join-Path $TMP "plugin.yml"))) { Write-Host "  [FAIL] staging plugin.yml missing"; exit 1 }
$coreCount = (Get-ChildItem (Join-Path $TMP "mirage\core") -Filter *.class -ErrorAction SilentlyContinue | Measure-Object).Count
Write-Host "  staging core class count = $coreCount"
if ($coreCount -eq 0) { Write-Host "  [FAIL] core classes missing"; exit 1 }

Write-Host "[3/3] Package -> $JAR_FOLIA"
if (Test-Path $JAR_FOLIA) { Remove-Item $JAR_FOLIA -Force -ErrorAction SilentlyContinue }
& $JAR cf $JAR_FOLIA -C $TMP .
if ($LASTEXITCODE -ne 0) { Write-Host "  [FAIL] jar"; exit 1 }
Remove-Item $TMP -Recurse -Force -ErrorAction SilentlyContinue
Write-Host "[OK] done"
