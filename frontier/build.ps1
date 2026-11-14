# Mirage Frontier - build script (PowerShell)
# Produces:
#   mirage.jar        (Java 11+, H2 2.x)
#   mirage-java8.jar  (Java 8,  H2 1.4.200)
$ErrorActionPreference = "Continue"
Set-Location $PSScriptRoot

$JDK = "C:\Program Files\Java\jdk-25.0.3\bin"
$JAVAC = "$JDK\javac.exe"
$JAR = "$JDK\jar.exe"

$SRC = Get-ChildItem "src\mirage\core\*.java" | ForEach-Object { $_.FullName }

function New-FatJar($classesDir, $h2Jar, $outJar) {
    $tmp = Join-Path $PSScriptRoot "build_tmp"
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    New-Item -ItemType Directory $tmp | Out-Null
    Copy-Item "$classesDir\*" $tmp -Recurse -Force
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path $h2Jar))
    try {
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName -like "META-INF/*") { continue }
            if ($entry.FullName -eq "module-info.class") { continue }
            $dest = Join-Path $tmp $entry.FullName
            if ($entry.Name -eq "") {
                New-Item -ItemType Directory -Force -Path $dest | Out-Null
            } else {
                New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $dest, $true)
            }
        }
    } finally { $zip.Dispose() }
    Set-Content -Path (Join-Path $tmp "MANIFEST.MF") -Value @("Manifest-Version: 1.0","Main-Class: mirage.core.FakeMysql") -Encoding ASCII
    if (Test-Path $outJar) { Remove-Item $outJar -Force }
    & $JAR cfm $outJar (Join-Path $tmp "MANIFEST.MF") -C $tmp .
    if ($LASTEXITCODE -ne 0) { throw "jar failed for $outJar" }
    Remove-Item $tmp -Recurse -Force
}

# 编译目标 = H2 2.x 支持的最低 Java（11），保证 Java 11 ~ 26 的 JVM 都能加载
Write-Host "[1/4] Compile Java 11 -> out11 (H2 2.x)"
if (Test-Path out11) { Remove-Item out11 -Recurse -Force }
New-Item -ItemType Directory out11 | Out-Null
& $JAVAC --release 11 -encoding UTF-8 -cp "lib\h2-2.5.252.jar" -d out11 @SRC
if ($LASTEXITCODE -ne 0) { throw "Java 17 compile failed" }

Write-Host "[2/4] Compile Java 8 -> out8 (H2 1.4.200)"
if (Test-Path out8) { Remove-Item out8 -Recurse -Force }
New-Item -ItemType Directory out8 | Out-Null
& $JAVAC --release 8 -encoding UTF-8 -cp "lib\h2-1.4.200.jar" -d out8 @SRC 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Java 8 compile failed" }

Write-Host "[3/4] Package mirage.jar (Java 11+)"
New-FatJar "out11" "lib\h2-2.5.252.jar" "mirage.jar"

Write-Host "[4/4] Package mirage-java8.jar (Java 8)"
New-FatJar "out8" "lib\h2-1.4.200.jar" "mirage-java8.jar"

Write-Host ""
Write-Host "[OK] Build complete:"
Write-Host "  mirage.jar         - Java 11+ (H2 2.x)"
Write-Host "  mirage-java8.jar   - Java 8   (H2 1.4.200)"