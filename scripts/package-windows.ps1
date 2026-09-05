<#
.SYNOPSIS
    Builds the Windows MSI from the shaded JAR via jpackage. Windows
    counterpart to package-dmg.sh, shared by the two CI paths so they can
    never drift: build.yml packages pull requests and merges (workflow
    artifact), release.yml packages tagged releases.

.PARAMETER Version
    Human-readable version passed to the app via -Dapp.version
    (e.g. "1.0.0" or "1.0.0-dev-abc1234").

.PARAMETER MsiVersion
    Numeric x.y.z for the MSI ProductVersion (WiX rejects anything else).
    Defaults to -Version, which only works for plain x.y.z labels -- dev
    builds must pass this explicitly.

.NOTES
    Expects 'mvn package' to have produced the shaded JAR already; the JAR
    built on a Windows host already carries the Windows JavaFX natives and
    the bundled sing-box.exe. Requires the WiX Toolset on PATH (preinstalled
    on the windows-latest GitHub runners).

    The upgrade UUID is fixed for the lifetime of the product: it is what
    lets a newer MSI upgrade an existing install in place instead of
    installing a second copy side by side. Never change it.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$MsiVersion = ''
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrEmpty($MsiVersion)) {
    $MsiVersion = $Version
}
if ($MsiVersion -notmatch '^\d+(\.\d+){0,2}$') {
    throw ("[package-windows] MSI version '$MsiVersion' is not numeric x.y.z" +
        " -- pass -MsiVersion explicitly for non-numeric labels")
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
Set-Location $repoRoot

# Resolve the shaded jar by glob, not by name: the artifact version lives in
# pom.xml, and a bump there must not strand the packagers. The shade plugin
# leaves the unshaded original-vless-client-*.jar next to the shaded one; the
# filter's prefix already skips it, and the Where-Object says so out loud.
$jars = @(Get-ChildItem -Path target -Filter 'vless-client-*.jar' -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike 'original-*' })
if ($jars.Count -eq 0) {
    throw "[package-windows] no target/vless-client-*.jar -- run 'mvn package' first"
} elseif ($jars.Count -gt 1) {
    throw ("[package-windows] expected exactly one target/vless-client-*.jar, found $($jars.Count):`n" +
        (($jars | ForEach-Object { "  $($_.FullName)" }) -join "`n") +
        "`n  Run 'mvn clean package' so only the current build's jar remains.")
}
$jarPath = $jars[0].FullName
$jarName = $jars[0].Name

# The module list is shared with package-dmg.sh / package-linux.sh through
# scripts/runtime-modules.txt: one module per line, '#' starts a comment.
$runtimeModules = (Get-Content 'scripts/runtime-modules.txt' |
    ForEach-Object { ($_ -replace '#.*', '').Trim() } |
    Where-Object { $_ }) -join ','
if (-not $runtimeModules) {
    throw '[package-windows] scripts/runtime-modules.txt lists no modules'
}

# The jlink options are shared the same way through scripts/jlink-options.txt
# (one option per line, '#' starts a comment); the reasoning behind each
# option lives there. Joined with spaces: --jlink-options takes one string.
$jlinkOptions = (Get-Content 'scripts/jlink-options.txt' |
    ForEach-Object { ($_ -replace '#.*', '').Trim() } |
    Where-Object { $_ }) -join ' '
if (-not $jlinkOptions) {
    throw '[package-windows] scripts/jlink-options.txt lists no options'
}

# Stage just the shaded jar (not the original-*.jar the shade plugin
# leaves alongside it).
Remove-Item -Recurse -Force -ErrorAction SilentlyContinue -Path staging, dist
New-Item -ItemType Directory -Force -Path staging | Out-Null
Copy-Item $jarPath staging/

# Per-user install: lands in %LOCALAPPDATA%\Programs without an admin
# prompt, matching how the app manages everything else per-user.
& jpackage `
    --type msi `
    --name 'Tunl' `
    --app-version $MsiVersion `
    --input staging `
    --main-jar $jarName `
    --main-class com.vlessclient.app.Launcher `
    --icon src/main/resources/icons/app-icon.ico `
    --dest dist `
    --win-upgrade-uuid 'ff1f0b21-e3d2-420f-80ce-95d5d9ab61fb' `
    --win-menu `
    --win-menu-group 'Tunl' `
    --win-shortcut `
    --win-per-user-install `
    --java-options "-Dapp.version=$Version" `
    --java-options "--enable-native-access=ALL-UNNAMED" `
    --add-modules $runtimeModules `
    --jlink-options $jlinkOptions
if ($LASTEXITCODE -ne 0) {
    throw "[package-windows] jpackage failed with exit code $LASTEXITCODE"
}

$msi = Get-ChildItem dist -Filter '*.msi' | Select-Object -First 1
if (-not $msi) {
    throw '[package-windows] jpackage reported success but no MSI in dist/'
}
# Normalise the file name. jpackage names the MSI after --name
# ("Tunl-<v>.msi"); rename it to the lowercase, underscore-versioned form the
# .deb and .dmg share so all three installers use one scheme.
$asset = Join-Path 'dist' "tunl_$MsiVersion.msi"
Move-Item -Force $msi.FullName $asset

# Code signing (optional, off by default): when WINDOWS_SIGN_PFX points at a
# .pfx and WINDOWS_SIGN_PASSWORD holds its password, sign the MSI with
# signtool. Without them the MSI is unsigned exactly as before. The release
# workflow materializes the .pfx from a secret. See docs/SIGNING.md.
if ($env:WINDOWS_SIGN_PFX -and (Test-Path $env:WINDOWS_SIGN_PFX)) {
    Write-Host '[package-windows] signing MSI with signtool'
    $timestamp = 'http://timestamp.digicert.com'
    & signtool sign `
        /f $env:WINDOWS_SIGN_PFX `
        /p $env:WINDOWS_SIGN_PASSWORD `
        /fd SHA256 /tr $timestamp /td SHA256 `
        /d 'Tunl' `
        $asset
    if ($LASTEXITCODE -ne 0) {
        throw "[package-windows] signtool failed with exit code $LASTEXITCODE"
    }
} else {
    Write-Host '[package-windows] WINDOWS_SIGN_PFX not set - MSI is unsigned'
}

Write-Host "[package-windows] built: $asset (app-version=$Version, ProductVersion=$MsiVersion)"
