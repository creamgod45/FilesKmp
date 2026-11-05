<#
.SYNOPSIS
    打包 FilesKmp：免安裝版 ZIP、MSI 安裝檔與 SHA256 檢查碼，放到 dist\。

.DESCRIPTION
    與 .github/workflows/release-packages.yml 相同的步驟，在本機執行：
      1. 找一個含 jpackage 的 JDK（-JdkHome、JAVA_HOME、~/.jdks、常見安裝位置）
      2. 找 WiX 3.14（-WixHome、WIX、%LOCALAPPDATA%\wix314、已安裝的 WiX），沒有就下載到 %LOCALAPPDATA%\wix314
      3. gradlew windowsMsi（或只做 windowsApp）
      4. 檢查 FilesKmp.exe 與 jvm.dll 的架構，可選擇做啟動測試
      5. 產生 dist\FilesKmp-<版本>-windows-<架構>{.msi,-portable.zip,-SHA256SUMS.txt}

    產物的架構取決於執行 Gradle 的 JDK：要打包 ARM64 版，請在 ARM64 Windows 上用 ARM64 JDK 執行。

.EXAMPLE
    .\package.ps1 -Version 1.2.3
.EXAMPLE
    .\package.ps1 -SkipMsi -SmokeTest
#>
[CmdletBinding()]
param(
    # x.y.z；MSI 限制 major 1..255、minor 0..255、patch 0..65535。省略時用 build.gradle.kts 的預設版本。
    [string]$Version,
    # 含 bin\jpackage.exe 的 JDK 資料夾。
    [string]$JdkHome,
    # 含 candle.exe 的 WiX 資料夾。
    [string]$WixHome,
    # 只打包免安裝版 ZIP（不需要 WiX）。
    [switch]$SkipMsi,
    # 打包後以 --screenshot 啟動程式一次，確認能開起來。
    [switch]$SmokeTest,
    # 先執行 gradlew clean。
    [switch]$Clean,
    # 產物資料夾。
    [string]$OutDir = 'dist'
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-Location -LiteralPath $PSScriptRoot

function Step([string]$text) { Write-Host "==> $text" -ForegroundColor Cyan }

# --- 版本 -------------------------------------------------------------------
if ($Version) {
    $Version = $Version -replace '^v', ''
    if ($Version -notmatch '^(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,4})$') {
        throw "版本須為 x.y.z，且不可含前導零：$Version"
    }
    $parts = $Version.Split('.') | ForEach-Object { [int]$_ }
    if ($parts[0] -lt 1 -or $parts[0] -gt 255 -or $parts[1] -gt 255 -or $parts[2] -gt 65535) {
        throw 'MSI 版本範圍：major 1..255、minor 0..255、patch 0..65535'
    }
} else {
    $m = Select-String -LiteralPath build.gradle.kts -Pattern 'gradleProperty\("appVersion"\)\.getOrElse\("([^"]+)"\)'
    if (-not $m) { throw '無法從 build.gradle.kts 讀取預設版本，請用 -Version 指定' }
    $Version = $m.Matches[0].Groups[1].Value
}

# --- JDK --------------------------------------------------------------------
function Find-Jdk {
    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($JdkHome) { $candidates.Add($JdkHome) }
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
    $jdks = Join-Path $env:USERPROFILE '.jdks'
    if (Test-Path $jdks) { Get-ChildItem $jdks -Directory | Sort-Object Name -Descending | ForEach-Object { $candidates.Add($_.FullName) } }
    foreach ($pf in @($env:ProgramW6432, $env:ProgramFiles) | Where-Object { $_ } | Select-Object -Unique) {
        foreach ($vendor in 'Java', 'Eclipse Adoptium', 'Microsoft', 'Zulu', 'Amazon Corretto', 'BellSoft') {
            $dir = Join-Path $pf $vendor
            if (Test-Path $dir) { Get-ChildItem $dir -Directory | Sort-Object Name -Descending | ForEach-Object { $candidates.Add($_.FullName) } }
        }
    }
    foreach ($c in $candidates) {
        if ((Test-Path (Join-Path $c 'bin\java.exe')) -and (Test-Path (Join-Path $c 'bin\jpackage.exe'))) { return (Resolve-Path $c).Path }
    }
    throw "找不到含 jpackage.exe 的 JDK（需要 JDK 21+）。請安裝 JDK 或用 -JdkHome 指定。找過：`n$($candidates -join "`n")"
}

# --- WiX --------------------------------------------------------------------
function Find-Wix {
    $dirs = [System.Collections.Generic.List[string]]::new()
    foreach ($base in @($WixHome, $env:WIX) | Where-Object { $_ }) { $dirs.Add($base); $dirs.Add((Join-Path $base 'bin')) }
    $dirs.Add((Join-Path $env:LOCALAPPDATA 'wix314'))
    foreach ($pf in @(${env:ProgramFiles(x86)}, $env:ProgramFiles) | Where-Object { $_ }) {
        Get-ChildItem $pf -Directory -Filter 'WiX Toolset*' -ErrorAction SilentlyContinue |
            Sort-Object Name -Descending | ForEach-Object { $dirs.Add((Join-Path $_.FullName 'bin')) }
    }
    foreach ($d in $dirs) { if (Test-Path (Join-Path $d 'candle.exe')) { return $d } }
    return $null
}

function Install-Wix {
    $dest = Join-Path $env:LOCALAPPDATA 'wix314'
    $zip = Join-Path $env:TEMP "wix314-$([guid]::NewGuid()).zip"
    Step "下載 WiX 3.14.1 到 $dest"
    Invoke-WebRequest 'https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip' -OutFile $zip
    try { Expand-Archive -LiteralPath $zip -DestinationPath $dest -Force } finally { Remove-Item $zip -ErrorAction SilentlyContinue }
    return $dest
}

$jdk = Find-Jdk
$env:JAVA_HOME = $jdk
$env:PATH = "$jdk\bin;$env:PATH"

$osArch = (Select-String -LiteralPath (Join-Path $jdk 'release') -Pattern '^OS_ARCH="?([^"]+)"?' -ErrorAction SilentlyContinue).Matches |
    Select-Object -First 1 | ForEach-Object { $_.Groups[1].Value }
$arch = switch ($osArch) {
    { $_ -in 'amd64', 'x86_64' } { 'x64' }
    { $_ -in 'aarch64', 'arm64' } { 'arm64' }
    default { throw "不支援的 JDK 架構：$_" }
}

$gradleArgs = @("-PappVersion=$Version", "-PjdkHome=$jdk", '--console=plain')
if (-not $SkipMsi) {
    $wix = Find-Wix
    if (-not $wix) { $wix = Install-Wix }
    $env:PATH = "$wix;$env:PATH"
    $gradleArgs += "-PwixHome=$wix"
}

Write-Host "FilesKmp $Version · windows-$arch"
Write-Host "JDK: $jdk"
if (-not $SkipMsi) { Write-Host "WiX: $wix" }

# --- 建置 -------------------------------------------------------------------
$tasks = @()
if ($Clean) { $tasks += 'clean' }
if ($SkipMsi) { $tasks += 'windowsApp' } else { $tasks += 'windowsMsi' }
Step "gradlew $($tasks -join ' ')"
& .\gradlew.bat @tasks @gradleArgs
if ($LASTEXITCODE -ne 0) { throw "Gradle 建置失敗（exit $LASTEXITCODE）" }

# --- 驗證架構 ---------------------------------------------------------------
Step '檢查 launcher 與 runtime 架構'
$expected = if ($arch -eq 'arm64') { 0xAA64 } else { 0x8664 }
foreach ($file in 'build\FilesKmp\FilesKmp.exe', 'build\FilesKmp\runtime\bin\server\jvm.dll') {
    $bytes = [IO.File]::ReadAllBytes((Resolve-Path $file))
    $pe = [BitConverter]::ToInt32($bytes, 0x3C)
    $machine = [BitConverter]::ToUInt16($bytes, $pe + 4)
    if ($machine -ne $expected) { throw ("架構不符：{0}（0x{1:X4}，預期 0x{2:X4}）" -f $file, $machine, $expected) }
}

if ($SmokeTest) {
    Step '啟動測試'
    $shot = Join-Path $env:TEMP "fileskmp-smoke-$PID.png"
    Remove-Item $shot -ErrorAction SilentlyContinue
    $p = Start-Process -FilePath '.\build\FilesKmp\FilesKmp.exe' -ArgumentList @('--screenshot', "`"$shot`"") -PassThru -WindowStyle Hidden
    if (-not $p.WaitForExit(60000)) { $p.Kill(); throw '程式啟動逾時' }
    if ($p.ExitCode -ne 0 -or -not (Test-Path $shot) -or (Get-Item $shot).Length -lt 1000) { throw "程式啟動失敗（exit $($p.ExitCode)）" }
    Remove-Item $shot -ErrorAction SilentlyContinue
}

# --- 收集產物 ---------------------------------------------------------------
Step "收集產物到 $OutDir"
$prefix = "FilesKmp-$Version-windows-$arch"
New-Item $OutDir -ItemType Directory -Force | Out-Null
$outputs = @()
if (-not $SkipMsi) {
    $msi = Join-Path $OutDir "$prefix.msi"
    Copy-Item "build\installers\$prefix.msi" $msi -Force
    $outputs += $msi
}
$zip = Join-Path $OutDir "$prefix-portable.zip"
Remove-Item $zip -ErrorAction SilentlyContinue
Compress-Archive -Path 'build\FilesKmp' -DestinationPath $zip
$outputs += $zip

$sums = Join-Path $OutDir "$prefix-SHA256SUMS.txt"
$outputs | ForEach-Object {
    $hash = (Get-FileHash $_ -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $(Split-Path $_ -Leaf)"
} | Set-Content $sums -Encoding ascii
$outputs += $sums

Write-Host ''
Write-Host '完成：' -ForegroundColor Green
$outputs | ForEach-Object { $f = Get-Item $_; '  {0}  ({1:N1} MB)' -f $f.FullName, ($f.Length / 1MB) }
