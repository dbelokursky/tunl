<#
.SYNOPSIS
    Windows counterpart to bundle-singbox.sh: downloads and bundles sing-box.exe
    for windows-amd64 into the Maven build output. Invoked by the
    exec-maven-plugin during the generate-resources phase on Windows build hosts.

.PARAMETER OutDir
    Output directory (e.g. target/classes/native).

.PARAMETER Version
    sing-box version (e.g. 1.13.14), passed by Maven from singbox.properties.

.NOTES
    The version and the SHA-256 checksum both come from singbox.properties -- the
    single source of truth also read by pom.xml and SingBoxInstaller. The
    -Version argument is cross-checked against the file to catch a stale Maven
    property cache.

    A ~/.cache/vless-client-build/sing-box-<version> directory is reused so
    repeated builds don't re-download. A .singbox-version stamp next to the
    bundled binary makes incremental builds re-bundle after a version bump
    instead of silently keeping the old binary.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$OutDir,
    [Parameter(Mandatory = $true)][string]$Version
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$propsFile = Join-Path (Split-Path -Parent $scriptDir) 'src/main/resources/singbox.properties'
if (-not (Test-Path $propsFile)) {
    throw "[bundle-singbox] missing $propsFile"
}

# .properties lookup: last value wins, whitespace trimmed.
function Get-Prop([string]$Key) {
    $value = $null
    foreach ($line in Get-Content -LiteralPath $propsFile) {
        if ($line -match "^\s*$([regex]::Escape($Key))\s*=\s*(.*?)\s*$") {
            $value = $Matches[1]
        }
    }
    return $value
}

$propsVersion = Get-Prop 'singbox.version'
if ($propsVersion -ne $Version) {
    throw ("[bundle-singbox] version mismatch: Maven passed '$Version' but " +
        "$propsFile says '$propsVersion'. Run 'mvn clean' -- the Maven property cache is stale.")
}

$arch = 'amd64'
$targetDir = Join-Path $OutDir "windows-$arch"
$targetBinary = Join-Path $targetDir 'sing-box.exe'
$stampFile = Join-Path $targetDir '.singbox-version'

if ((Test-Path $targetBinary) -and (Test-Path $stampFile) -and
    ((Get-Content -LiteralPath $stampFile -Raw).Trim() -eq $Version)) {
    Write-Host "[bundle-singbox] already present: $targetBinary ($Version)"
    exit 0
}

$expected = Get-Prop "singbox.sha256.windows-$arch"
if ([string]::IsNullOrWhiteSpace($expected)) {
    throw "[bundle-singbox] no singbox.sha256.windows-$arch in $propsFile"
}

$cacheDir = Join-Path $env:USERPROFILE ".cache/vless-client-build/sing-box-$Version"
New-Item -ItemType Directory -Force -Path $cacheDir | Out-Null
$zip = Join-Path $cacheDir "sing-box-$Version-windows-$arch.zip"

if (-not (Test-Path $zip)) {
    $url = "https://github.com/SagerNet/sing-box/releases/download/v$Version/sing-box-$Version-windows-$arch.zip"
    Write-Host "[bundle-singbox] downloading $url"
    $part = "$zip.part"
    # Windows PowerShell 5.1 has no -MaximumRetryCount, so the retry loop is
    # spelled out: three attempts two seconds apart, five minutes each at
    # most -- the budget bundle-singbox.sh gives curl. A flaky connection to
    # the GitHub CDN is one retry away from a green build, and a stalled
    # transfer must fail here rather than sit on the job's timeout.
    $maxAttempts = 3
    for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
        try {
            Invoke-WebRequest -Uri $url -OutFile $part -UseBasicParsing -TimeoutSec 300
            break
        } catch {
            Remove-Item -Force -ErrorAction SilentlyContinue -LiteralPath $part
            if ($attempt -eq $maxAttempts) {
                throw ("[bundle-singbox] download failed after $maxAttempts attempts: $url`n" +
                    "  $($_.Exception.Message)")
            }
            Write-Warning "[bundle-singbox] attempt $attempt of $maxAttempts failed: $($_.Exception.Message); retrying in 2s"
            Start-Sleep -Seconds 2
        }
    }
    Move-Item -Force -LiteralPath $part -Destination $zip
}

# Hash via .NET rather than Get-FileHash: some Windows PowerShell builds on CI
# don't expose that cmdlet, but the SHA256 type is present in every version.
$sha256 = [System.Security.Cryptography.SHA256]::Create()
try {
    $stream = [System.IO.File]::OpenRead($zip)
    try {
        $actual = [System.BitConverter]::ToString($sha256.ComputeHash($stream)).Replace('-', '').ToLower()
    } finally {
        $stream.Dispose()
    }
} finally {
    $sha256.Dispose()
}
if ($actual -ne $expected.ToLower()) {
    Remove-Item -Force -LiteralPath $zip
    throw ("[bundle-singbox] SHA-256 mismatch for windows-$arch`n" +
        "  expected $expected`n  got      $actual")
}

New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
Remove-Item -Force -ErrorAction SilentlyContinue -LiteralPath $targetBinary, $stampFile

# Extract just sing-box.exe out of the nested sing-box-<version>-windows-amd64/
# directory (the .sh bundler's --strip-components=1 equivalent). Use .NET
# ZipFile instead of Expand-Archive, which isn't on every CI PowerShell.
$extractDir = Join-Path $cacheDir 'extract'
Remove-Item -Recurse -Force -ErrorAction SilentlyContinue -LiteralPath $extractDir
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory($zip, $extractDir)
$exe = Get-ChildItem -Path $extractDir -Recurse -Filter 'sing-box.exe' | Select-Object -First 1
if (-not $exe) {
    throw "[bundle-singbox] sing-box.exe not found inside $zip"
}
Copy-Item -Force -LiteralPath $exe.FullName -Destination $targetBinary
Set-Content -LiteralPath $stampFile -Value $Version -NoNewline
Write-Host "[bundle-singbox] bundled $targetBinary ($Version)"
