<#
.SYNOPSIS
    Windows counterpart to write-jvm-args.sh: writes the Java argfile consumed by
    the javafx-maven-plugin run goal. Invoked by exec-maven-plugin during the
    generate-resources phase on Windows build hosts.

.PARAMETER OutFile
    Output file path (e.g. target/jvm-args.txt).

.NOTES
    Writes the options every platform shares -- the DEBUG log level, the
    --enable-native-access grant JavaFX needs on JDK 24+ (JEP 472) and the
    heap options from scripts/java-options.txt -- and nothing else, so a dev
    run on Windows gets exactly the shared part of what write-jvm-args.sh
    gives macOS and Linux. The macOS Dock and Apple
    application-name options stay in the .sh: an unknown -Xdock:... option
    makes the JVM refuse to start here. Its Linux-only
    -Djava.awt.headless=false is a Unix concern -- AWT defaults to headless
    only where it has to look for a DISPLAY, never on Windows -- so it is not
    mirrored either.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$OutFile
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$dir = Split-Path -Parent $OutFile
if ($dir) {
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
}
# --enable-native-access mirrors write-jvm-args.sh: JavaFX loads its natives
# from the unnamed module, which JDK 24+ warns about and will later refuse.
# The launcher options shared with the packaging scripts through
# scripts/java-options.txt follow, so a development run gets the heap a
# packaged app gets; an argfile has no comments, so they are stripped.
$javaOptions = @(Get-Content (Join-Path $PSScriptRoot 'java-options.txt') |
    ForEach-Object { ($_ -replace '#.*', '').Trim() } |
    Where-Object { $_ })
Set-Content -LiteralPath $OutFile -Value (@(
    '-Dvless.log.level=DEBUG',
    '--enable-native-access=ALL-UNNAMED'
) + $javaOptions)
Write-Host "[write-jvm-args] wrote $OutFile"
