<#
.SYNOPSIS
  Static checks for the source tree and (optionally) the built module zip.
.EXAMPLE
  pwsh -File tests/check-package.ps1                       # source checks only
  pwsh -File tests/check-package.ps1 -ZipPath dist/PicoUSB-Quiet-v1.0.0.zip
#>
param([string]$ZipPath = '', [string]$Tag = '')

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$failures = New-Object System.Collections.Generic.List[string]
function Check([bool]$ok, [string]$message) {
    if ($ok) { Write-Host "ok   $message" } else { Write-Host "FAIL $message"; $failures.Add($message) }
}

# ---- module.prop ----
$propsText = Get-Content -Raw (Join-Path $repo 'module/module.prop')
$props = @{}
foreach ($line in $propsText -split "`n") {
    if ($line -match '^([A-Za-z]+)=(.*)$') { $props[$Matches[1]] = $Matches[2].Trim() }
}
foreach ($key in 'id', 'name', 'version', 'versionCode', 'author', 'description') {
    Check ($props.ContainsKey($key) -and $props[$key]) "module.prop has $key"
}
Check ($props['id'] -match '^[a-z][a-z0-9_]{2,}$') 'module id is a safe identifier'
Check ($props['version'] -match '^\d+\.\d+\.\d+(-[0-9A-Za-z.]+)?$') 'version is semver'
Check ($props['versionCode'] -match '^\d+$') 'versionCode is an integer'
if ($Tag) { Check ($Tag -ceq "v$($props['version'])") "tag $Tag matches module.prop version" }

# ---- rules.txt syntax (mirrors QuietServer's parser) ----
$positions = 'system_dialog', 'global_dialog', 'near_dialog'
$rulesPath = Join-Path $repo 'module/rules.txt'
$ruleLines = Get-Content $rulesPath | ForEach-Object { ($_ -replace '#.*$', '').Trim() } | Where-Object { $_ }
Check ($ruleLines.Count -gt 0) 'rules.txt has at least one rule'
$bad = @()
foreach ($line in $ruleLines) {
    $p = $line -split '\s+'
    if ($p[0] -eq 'usb-default') {
        $valid = $p.Count -ge 3 -and $p[1] -notmatch '/' -and (($p[2..($p.Count - 1)] | Where-Object { $_ -notmatch '^(0x[0-9A-Fa-f]{1,4}|\d{1,5}):(0x[0-9A-Fa-f]{1,4}|\d{1,5})$' }).Count -eq 0)
    } else {
        $valid = $p.Count -eq 3 -and $p[0] -notmatch '/' -and $positions -contains $p[2]
    }
    if (-not $valid) { $bad += $line }
}
Check ($bad.Count -eq 0) "every rule line parses ($($bad -join ' | '))"
$raw = Get-Content -Raw $rulesPath
Check ($raw -match 'NOT TESTED') 'untested Paper rule is labelled NOT TESTED'
Check ($raw -notmatch "`r") 'rules.txt uses LF line endings'
Check ((Get-Content -Raw (Join-Path $repo 'module/customize.sh')) -notmatch "`r") 'customize.sh uses LF line endings'

# ---- no machine-specific paths leaked into the public tree ----
$leak = Get-ChildItem $repo -Recurse -File -Exclude *.zip, *.so, *.dex |
    Where-Object { $_.FullName -notmatch '[\\/](build|dist|\.git)[\\/]' -and $_.Name -ne 'check-package.ps1' } |
    Select-String -Pattern 'C:\\Users\\|69546|PicoFacialDataExtractor|usb-attach-quiet' -List
Check (-not $leak) "no local paths or private names in the tree $(if ($leak) { $leak.Path -join ', ' })"

# ---- built zip ----
if ($ZipPath) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $ZipPath).Path)
    try {
        $names = @($zip.Entries | ForEach-Object { $_.FullName })
        foreach ($required in 'module.prop', 'customize.sh', 'rules.txt', 'classes.dex', 'zygisk/arm64-v8a.so') {
            Check ($names -ccontains $required) "zip contains $required"
        }
        Check (@($names | Where-Object { $_.Contains('\') }).Count -eq 0) 'zip has no backslash entry names'
        Check ($names.Count -eq 5) "zip has exactly the 5 expected entries ($($names.Count))"
        $so = $zip.GetEntry('zygisk/arm64-v8a.so'); $buf = New-Object byte[] 20
        $s = $so.Open(); [void]$s.Read($buf, 0, 20); $s.Dispose()
        Check (($buf[0] -eq 0x7F) -and ($buf[1] -eq 0x45) -and ($buf[4] -eq 2) -and ($buf[18] -eq 183)) 'arm64-v8a.so is an AArch64 ELF64'
        $dex = $zip.GetEntry('classes.dex'); $s = $dex.Open(); [void]$s.Read($buf, 0, 8); $s.Dispose()
        Check (([Text.Encoding]::ASCII.GetString($buf, 0, 4)) -eq "dex`n") 'classes.dex has a dex header'
    } finally { $zip.Dispose() }
}

if ($failures.Count) { Write-Host "`n$($failures.Count) check(s) failed"; exit 1 }
Write-Host "`nall checks passed"
