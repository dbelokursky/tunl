<#
.SYNOPSIS
    Installs a Tunl MSI silently, validates the packaged runtime and app
    payload, then uninstalls it and verifies basic cleanup.

.DESCRIPTION
    This is a packaging smoke test, not a TUN/network test. It catches broken
    MSI tables, missing jpackage runtime files, wrong embedded app versions,
    missing sing-box payloads, shortcut regressions, and uninstall failures on
    a real Windows runner without launching the interactive JavaFX desktop.

.PARAMETER MsiPath
    Path to the MSI produced by scripts/package-windows.ps1.

.PARAMETER ExpectedVersion
    Human-readable app version expected in the installed Tunl.cfg.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$MsiPath,
    [Parameter(Mandatory = $true)][string]$ExpectedVersion
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$msiMatches = @(Resolve-Path -Path $MsiPath)
if ($msiMatches.Count -ne 1) {
    throw "[windows-msi-smoke] expected one MSI matching '$MsiPath', found $($msiMatches.Count)"
}
$msi = $msiMatches[0].Path
$tempRoot = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { $env:TEMP }
$installLog = Join-Path $tempRoot 'tunl-msi-install.log'
$uninstallLog = Join-Path $tempRoot 'tunl-msi-uninstall.log'
$shortcut = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\Tunl\Tunl.lnk'
$appRoot = Join-Path $env:LOCALAPPDATA 'Tunl'
$installedExe = Join-Path $appRoot 'Tunl.exe'
$installed = $false

function Invoke-MsiExec {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('install', 'uninstall')][string]$Action,
        [Parameter(Mandatory = $true)][string]$LogPath
    )

    $verb = if ($Action -eq 'install') { '/i' } else { '/x' }
    $arguments = "$verb `"$msi`" /qn /norestart /L*v `"$LogPath`""
    $process = Start-Process msiexec.exe -ArgumentList $arguments -Wait -PassThru
    if ($process.ExitCode -notin 0, 3010) {
        if (Test-Path $LogPath) {
            Get-Content $LogPath -Tail 100 | Write-Host
        }
        throw "[windows-msi-smoke] MSI $Action failed with exit code $($process.ExitCode)"
    }
}

try {
    Write-Host "[windows-msi-smoke] installing $msi"
    Invoke-MsiExec -Action install -LogPath $installLog
    $installed = $true

    $runtime = Join-Path $appRoot 'runtime\bin\server\jvm.dll'
    $config = Join-Path $appRoot 'app\Tunl.cfg'

    foreach ($required in @($installedExe, $runtime, $config, $shortcut)) {
        if (-not (Test-Path $required)) {
            throw "[windows-msi-smoke] installed payload is missing: $required"
        }
    }

    $jars = @(Get-ChildItem (Join-Path $appRoot 'app') -Filter 'vless-client-*.jar' -File)
    if ($jars.Count -ne 1) {
        throw "[windows-msi-smoke] expected one application JAR, found $($jars.Count)"
    }

    if (-not (Select-String -Path $config -SimpleMatch "-Dapp.version=$ExpectedVersion" -Quiet)) {
        throw "[windows-msi-smoke] Tunl.cfg does not contain app.version=$ExpectedVersion"
    }

    $jar = $jars[0].FullName
    $jarEntries = & jar tf $jar
    if ($LASTEXITCODE -ne 0 -or
            -not ($jarEntries -contains 'native/windows-amd64/sing-box.exe')) {
        throw '[windows-msi-smoke] packaged JAR does not contain the Windows sing-box binary'
    }
    Write-Host "[windows-msi-smoke] installed payload verified at $appRoot"
} catch {
    if (Test-Path $appRoot) {
        Write-Host '[windows-msi-smoke] installed payload at failure:'
        Get-ChildItem $appRoot -Recurse |
            Select-Object -First 200 -ExpandProperty FullName |
            Write-Host
    }
    throw
} finally {
    if ($installed) {
        Write-Host "[windows-msi-smoke] uninstalling $msi"
        Invoke-MsiExec -Action uninstall -LogPath $uninstallLog
        if (Test-Path $installedExe) {
            throw "[windows-msi-smoke] Tunl.exe remains after uninstall: $installedExe"
        }
        if (Test-Path $shortcut) {
            throw "[windows-msi-smoke] Start Menu shortcut remains after uninstall: $shortcut"
        }
    }
}

Write-Host '[windows-msi-smoke] install, payload, runtime, shortcut, and uninstall checks passed'
