# The @@NAME@@ installer for PowerShell, for:
#
#     irm <url> | iex
#
# Detects the operating system and CPU architecture, downloads the matching `@@NAME@@`
# executable, verifies its SHA-256 against the digest embedded below, and installs it: on
# Windows as `@@NAME@@.exe` in %LOCALAPPDATA%\Programs\@@NAME@@, put on the user's PATH;
# elsewhere as `@@NAME@@` in ~/.local/bin. Either is overridden by $env:@@UPPER@@_INSTALL_DIR.
# Runs in Windows PowerShell 5.1 and in PowerShell 7 on any platform; no stdin is read, so
# piping from irm is safe.
#
# Generated for @@NAME@@ @@RELEASE@@ by `xek installer`; the digests are per-release.

$ErrorActionPreference = 'Stop'

$name = '@@NAME@@'
$version = '@@RELEASE@@'
$base = '@@BASE@@'

# `$IsWindows` is absent in Windows PowerShell 5.1, which only ever runs on Windows.
$windows = $PSVersionTable.PSVersion.Major -lt 6 -or $IsWindows

$os = if ($windows) { 'windows' } elseif ($IsMacOS) { 'macos' } elseif ($IsLinux) { 'linux' }
      else { throw "${name}: unsupported operating system" }

$raw = if ($env:PROCESSOR_ARCHITECTURE) { $env:PROCESSOR_ARCHITECTURE }
       else { [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString() }

$arch = switch -Regex ($raw) {
  '^(AMD64|X64|x86_64)$' { 'x64' }
  '^(ARM64|Arm64|aarch64)$' { 'arm64' }
  default { throw "${name}: unsupported architecture: $raw" }
}

$label = "$os-$arch"
$suffix = if ($windows) { '.exe' } else { '' }

$digests = @{
@@DIGESTS@@
}

# Failures `throw`, never `exit`: under `irm | iex` an `exit` would close the user's shell.
if (-not $digests.ContainsKey($label)) { throw "${name}: no executable is published for $label" }

$expected = $digests[$label]
$url = "$base/$name-$label$suffix"

$dir = if ($env:@@UPPER@@_INSTALL_DIR) { $env:@@UPPER@@_INSTALL_DIR }
       elseif ($windows) { Join-Path $env:LOCALAPPDATA "Programs\$name" }
       else { Join-Path $HOME '.local/bin' }

$installed = Join-Path $dir "$name$suffix"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$tmp = Join-Path $dir ".$name.download.$PID"

try {
  Write-Host "Downloading $name $version for $label..."
  Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing

  $actual = (Get-FileHash -Path $tmp -Algorithm SHA256).Hash

  if ($actual -ine $expected) {
    throw "${name}: checksum mismatch for $url`n  expected $expected`n  received $($actual.ToLower())"
  }

  if (-not $windows) { chmod +x $tmp }
  Move-Item -Force -Path $tmp -Destination $installed
} finally {
  if (Test-Path $tmp) { Remove-Item -Force $tmp }
}

Write-Host "Installed $name $version to $installed"

$separator = [System.IO.Path]::PathSeparator
$onPath = ($env:PATH -split [regex]::Escape($separator)) -contains $dir

if (-not $onPath) {
  if ($windows) {
    # Persisted for every future shell, and made good in this one.
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    $joined = if ($userPath) { "$userPath;$dir" } else { $dir }
    [Environment]::SetEnvironmentVariable('Path', $joined, 'User')
    $env:Path = "$env:Path;$dir"
    Write-Host "Added $dir to your PATH; new terminals will find $name."
  } else {
    Write-Host "Note: $dir is not on your PATH; add it with:"
    Write-Host "    `$env:PATH = `"${dir}:`$env:PATH`""
  }
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
