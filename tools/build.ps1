<#
.SYNOPSIS
  Build PicoUSB-Quiet and pack dist/PicoUSB-Quiet-v<version>.zip (+ SHA256SUMS.txt).
.DESCRIPTION
  Needs PowerShell 7 (pwsh), JDK 17, and an Android SDK with:
    ndk;26.1.10909125  cmake;3.22.1  platforms;android-35  build-tools;35.0.0
  Locations come from -SdkRoot / -JdkRoot, else ANDROID_SDK_ROOT (or ANDROID_HOME) / JAVA_HOME.
  The script only writes inside the repository; it never touches a device.
#>
param(
    [string]$SdkRoot = $(if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $env:ANDROID_HOME }),
    [string]$JdkRoot = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Fail([string]$message) { throw "build: $message" }
function Run([string]$what, [scriptblock]$command) {
    & $command
    if ($LASTEXITCODE -ne 0) { Fail "$what failed (exit $LASTEXITCODE)" }
}

$repo = Split-Path -Parent $PSScriptRoot
if (-not $SdkRoot) { Fail 'set ANDROID_SDK_ROOT (or pass -SdkRoot)' }
if (-not $JdkRoot) { Fail 'set JAVA_HOME to a JDK 17 (or pass -JdkRoot)' }

$exe = if ($IsWindows) { '.exe' } else { '' }
$bat = if ($IsWindows) { '.bat' } else { '' }
$ndk = Join-Path $SdkRoot 'ndk/26.1.10909125'
$cmake = Join-Path $SdkRoot "cmake/3.22.1/bin/cmake$exe"
$ninja = Join-Path $SdkRoot "cmake/3.22.1/bin/ninja$exe"
$toolchain = Join-Path $ndk 'build/cmake/android.toolchain.cmake'
$javac = Join-Path $JdkRoot "bin/javac$exe"
$d8 = Join-Path $SdkRoot "build-tools/35.0.0/d8$bat"
$androidJar = Join-Path $SdkRoot 'platforms/android-35/android.jar'
foreach ($path in @($cmake, $ninja, $toolchain, $javac, $d8, $androidJar)) {
    if (-not (Test-Path -LiteralPath $path)) { Fail "missing build input: $path" }
}

$props = Get-Content -Raw (Join-Path $repo 'module/module.prop')
$version = ([regex]::Match($props, '(?m)^version=(.+)$')).Groups[1].Value.Trim()
if (-not $version) { Fail 'module.prop has no version' }

$build = Join-Path $repo 'build'
$dist = Join-Path $repo 'dist'
if (Test-Path $build) { Remove-Item -Recurse -Force $build }
New-Item -ItemType Directory -Force (Join-Path $build 'classes'), (Join-Path $build 'dex'), (Join-Path $build 'native'), $dist | Out-Null

Write-Host '== native (Zygisk loader) =='
Run 'cmake configure' {
    & $cmake -S (Join-Path $repo 'src/native') -B (Join-Path $build 'native') -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" `
        "-DCMAKE_TOOLCHAIN_FILE=$toolchain" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-29 `
        -DCMAKE_BUILD_TYPE=Release '-DCMAKE_CXX_FLAGS_RELEASE=-O2 -DNDEBUG -g0' | Out-Null
}
Run 'cmake build' { & $cmake --build (Join-Path $build 'native') }

Write-Host '== java (system_server helper) =='
$sources = @(Get-ChildItem (Join-Path $repo 'src/java') -Recurse -Filter *.java | ForEach-Object FullName)
if ($sources.Count -eq 0) { Fail 'no Java sources' }
Run 'javac' {
    & $javac -encoding UTF-8 -source 8 -target 8 '-Xlint:all,-options,-deprecation' -Werror `
        -classpath $androidJar -d (Join-Path $build 'classes') @sources
}
$classes = @(Get-ChildItem (Join-Path $build 'classes') -Recurse -Filter *.class | ForEach-Object FullName)
$env:JAVA_HOME = $JdkRoot
Run 'd8' { & $d8 --lib $androidJar --min-api 29 --output (Join-Path $build 'dex') @classes }

Write-Host '== stage and pack =='
$stage = Join-Path $build 'stage'
New-Item -ItemType Directory -Force (Join-Path $stage 'zygisk') | Out-Null
foreach ($name in 'module.prop', 'customize.sh', 'rules.txt') {
    Copy-Item (Join-Path $repo "module/$name") $stage
}
Copy-Item (Join-Path $build 'dex/classes.dex') $stage
Copy-Item (Join-Path $build 'native/arm64-v8a.so') (Join-Path $stage 'zygisk/arm64-v8a.so')

$zipPath = Join-Path $dist "PicoUSB-Quiet-v$version.zip"
if (Test-Path $zipPath) { Remove-Item -Force $zipPath }
Add-Type -AssemblyName System.IO.Compression
$root = (Resolve-Path $stage).Path.TrimEnd('/', '\')
$relative = @(Get-ChildItem -LiteralPath $root -Recurse -File | ForEach-Object {
    $_.FullName.Substring($root.Length + 1).Replace('\', '/')
})
[Array]::Sort($relative, [StringComparer]::Ordinal)   # deterministic entry order
$fixedTime = [DateTimeOffset]::new(2026, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
$stream = [IO.File]::Open($zipPath, [IO.FileMode]::Create)
try {
    $archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create, $true)
    try {
        foreach ($entryName in $relative) {
            $entry = $archive.CreateEntry($entryName, [IO.Compression.CompressionLevel]::Optimal)
            $entry.LastWriteTime = $fixedTime
            $in = [IO.File]::OpenRead((Join-Path $root $entryName))
            try { $out = $entry.Open(); try { $in.CopyTo($out) } finally { $out.Dispose() } } finally { $in.Dispose() }
        }
    } finally { $archive.Dispose() }
} finally { $stream.Dispose() }

$hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $zipPath).Hash.ToLowerInvariant()
[IO.File]::WriteAllText((Join-Path $dist 'SHA256SUMS.txt'), "$hash  $(Split-Path -Leaf $zipPath)`n", [Text.UTF8Encoding]::new($false))
Write-Host "packed: $zipPath"
Write-Host "sha256: $hash"
