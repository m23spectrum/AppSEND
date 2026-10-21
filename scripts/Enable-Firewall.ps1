[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$ExecutablePath)
$ErrorActionPreference = 'Stop'
$beamExe = (Resolve-Path -LiteralPath $ExecutablePath).Path
if (-not (Test-Path -LiteralPath $beamExe -PathType Leaf) -or [IO.Path]::GetExtension($beamExe) -ne '.exe') { throw 'ExecutablePath must identify the installed AppSEND executable.' }
$beamIdentity = [Security.Principal.WindowsIdentity]::GetCurrent()
$beamPrincipal = [Security.Principal.WindowsPrincipal]::new($beamIdentity)
if (-not $beamPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Run this script from an elevated PowerShell to create firewall rules.' }
foreach ($beamRule in @('AppSEND-Private-TCP', 'AppSEND-Private-UDP')) {
    Get-NetFirewallRule -Name $beamRule -ErrorAction SilentlyContinue | Remove-NetFirewallRule
}
New-NetFirewallRule -Name 'AppSEND-Private-TCP' -DisplayName 'AppSEND LAN transfer (Private)' -Direction Inbound -Action Allow -Profile Private -Protocol TCP -Program $beamExe -RemoteAddress LocalSubnet | Out-Null
New-NetFirewallRule -Name 'AppSEND-Private-UDP' -DisplayName 'AppSEND discovery (Private)' -Direction Inbound -Action Allow -Profile Private -Protocol UDP -LocalPort 45871 -Program $beamExe -RemoteAddress LocalSubnet | Out-Null
Write-Host "Enabled Private/LocalSubnet rules for $beamExe"

