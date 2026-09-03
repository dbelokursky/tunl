<#
.SYNOPSIS
    Windows counterpart to write-jvm-args.sh: writes the Java argfile consumed by
    the javafx-maven-plugin run goal. Invoked by exec-maven-plugin during the
    generate-resources phase on Windows build hosts.

.PARAMETER OutFile
    Output file path (e.g. target/jvm-args.txt).

.NOTES
    The macOS Dock and Apple application-name options don't exist on Windows --
    and an unknown -Xdock:... option makes the JVM refuse to start -- so only the
    shared log-level option carries over.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$OutFile
)

$ErrorActionPreference = 'Stop'

$dir = Split-Path -Parent $OutFile
if ($dir) {
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
}
# --enable-native-access mirrors write-jvm-args.sh: JavaFX loads its natives
# from the unnamed module, which JDK 24+ warns about and will later refuse.
Set-Content -LiteralPath $OutFile -Value @(
    '-Dvless.log.level=DEBUG',
    '--enable-native-access=ALL-UNNAMED'
)
Write-Host "[write-jvm-args] wrote $OutFile"
