# The @@NAME@@ installer for Windows, for:
#
#     irm <url> | iex
#
# Detects the CPU architecture, downloads the matching `@@NAME@@.exe`, verifies its SHA-256
# against the digest embedded below, installs it in %LOCALAPPDATA%\Programs\@@NAME@@ (or
# $env:@@UPPER@@_INSTALL_DIR), and puts that directory on the user's PATH. Runs in Windows
# PowerShell 5.1 and in PowerShell 7; no stdin is read, so piping from irm is safe.
#
# Generated for @@NAME@@ @@RELEASE@@ by `xek installer`; the digests are per-release.

$ErrorActionPreference = 'Stop'

$name = '@@NAME@@'
$version = '@@RELEASE@@'
$base = '@@BASE@@'

$raw = if ($env:PROCESSOR_ARCHITECTURE) { $env:PROCESSOR_ARCHITECTURE }
       else { [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString() }

$arch = switch ($raw) {
  'AMD64' { 'x64' }
  'X64'   { 'x64' }
  'ARM64' { 'arm64' }
  default { $raw.ToLower() }
}

$label = "windows-$arch"

$digests = @{
@@DIGESTS@@
}

# Failures `throw`, never `exit`: under `irm | iex` an `exit` would close the user's shell.
if (-not $digests.ContainsKey($label)) { throw "${name}: no executable is published for $label" }

$expected = $digests[$label]
$url = "$base/$name-$label.exe"

$dir = if ($env:@@UPPER@@_INSTALL_DIR) { $env:@@UPPER@@_INSTALL_DIR }
       else { Join-Path $env:LOCALAPPDATA "Programs\$name" }

New-Item -ItemType Directory -Force -Path $dir | Out-Null
$tmp = Join-Path $dir ".$name.download.$PID"

try {
  Write-Host "Downloading $name $version for $label..."
  Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing

  $actual = (Get-FileHash -Path $tmp -Algorithm SHA256).Hash

  if ($actual -ine $expected) {
    throw "${name}: checksum mismatch for $url`n  expected $expected`n  received $($actual.ToLower())"
  }

  Move-Item -Force -Path $tmp -Destination (Join-Path $dir "$name.exe")
} finally {
  if (Test-Path $tmp) { Remove-Item -Force $tmp }
}

Write-Host "Installed $name $version to $(Join-Path $dir "$name.exe")"

$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$onPath = (($userPath -split ';') -contains $dir) -or (($env:Path -split ';') -contains $dir)

if (-not $onPath) {
  $joined = if ($userPath) { "$userPath;$dir" } else { $dir }
  [Environment]::SetEnvironmentVariable('Path', $joined, 'User')
  $env:Path = "$env:Path;$dir"
  Write-Host "Added $dir to your PATH; new terminals will find $name."
}

Write-Host "The first run fetches $name's dependencies; subsequent runs start instantly."

<#
┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃                                                                                    ┃
┃  If this script has been PRINTED to your terminal, it has not been run: you are    ┃
┃  looking at the installer itself. To download and run it in one step, invoke:      ┃
┃                                                                                    ┃
┃      irm <the URL you fetched this from> | iex                                     ┃
┃                                                                                    ┃
┃  or, if you have already saved it to a file:                                       ┃
┃                                                                                    ┃
┃      powershell -ExecutionPolicy Bypass -File install.ps1                          ┃
┃                                                                                    ┃
┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
#>
