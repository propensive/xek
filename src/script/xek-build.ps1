# ---------------------------------------------------------------------------
# xek builder — PowerShell 5.1+ section. Mirrors the bash section: joins a
# stub, an ETHRCFG v3 record and a JAR, and generates the polyglot launchers.
# ---------------------------------------------------------------------------
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$XekSelf = $MyInvocation.MyCommand.Definition
$Latin = [Text.Encoding]::GetEncoding(28591)
$Utf8NoBom = New-Object Text.UTF8Encoding($false)

function Xek-Die($code, $msg) { [Console]::Error.WriteLine("xek: $msg"); exit $code }

# Baked-in metadata from the payload region.
$XekLines = [IO.File]::ReadAllLines($XekSelf)
function Xek-Meta($k) { ($XekLines | Where-Object { $_ -like "# $k=*" } | Select-Object -First 1) -replace "^# $k=" }
$XekVersion    = Xek-Meta 'XEK_VERSION'
$XekRunnersUrl = Xek-Meta 'RUNNERS_URL'
$XekManifest   = ($XekLines | Where-Object { $_ -like 'runners:*' } | Select-Object -First 1) -replace '^runners:'

function Xek-BakedHash($label) {
  foreach ($e in $XekManifest.Split(',')) { if ($e -like "$label=*") { return $e.Substring($label.Length + 1) } }
  return $null
}

function Xek-Cache {
  if ($env:XEK_CACHE) { $env:XEK_CACHE }
  elseif ($env:XDG_CACHE_HOME) { Join-Path $env:XDG_CACHE_HOME 'xek' }
  elseif ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA 'xek' }
  else { Join-Path $HOME '.cache/xek' }
}

function Xek-Sha256($path) { (Get-FileHash -Path $path -Algorithm SHA256).Hash.ToLower() }

function Xek-StubName($label) { if ($label -like 'windows*') { "runner-$label.exe" } else { "runner-$label" } }

function Xek-ResolveStub($label) {
  $name = Xek-StubName $label
  if ($opt.runnersDir) {
    $p = Join-Path $opt.runnersDir $name
    if (-not (Test-Path $p)) { Xek-Die 2 "no stub $name in $($opt.runnersDir)" }
    return $p
  }
  $base = if ($opt.runnersUrl) { $opt.runnersUrl } else { $XekRunnersUrl }
  if (-not $base) { Xek-Die 2 "no runner source: pass --runners or --runners-url" }
  $want = if ($opt.runnersManifest) {
    (Get-Content $opt.runnersManifest | ForEach-Object { $_ -replace "`r" } |
      Where-Object { $_ -match "^$label[`t=]" } | Select-Object -First 1) -replace "^$label[`t=]"
  } else { Xek-BakedHash $label }
  $dir = Join-Path (Xek-Cache) ("xek-" + $(if ($XekVersion) { $XekVersion } else { '0' }))
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  $path = Join-Path $dir $name
  if ((Test-Path $path) -and $want -and ((Xek-Sha256 $path) -eq $want)) { return $path }
  xek_msg 33 '████████' 0 "Fetching $name…"
  $tmp = "$path.part"
  Invoke-WebRequest -Uri "$base/$name" -OutFile $tmp -UseBasicParsing
  if ($want) { $got = Xek-Sha256 $tmp; if ($got -ne $want) { Remove-Item $tmp; Xek-Die 2 "SHA-256 mismatch for $name" } }
  Move-Item -Force $tmp $path
  xek_msg 32 '████████' 1 "Fetched $name"
  return $path
}

function Xek-Record {
  $r = New-Object byte[] 3764
  $Latin.GetBytes('ETHRCFG').CopyTo($r, 0); $r[7] = 3
  if (-not [BitConverter]::IsLittleEndian) { Xek-Die 2 'big-endian host unsupported' }
  [BitConverter]::GetBytes([uint64]$opt.buildId).CopyTo($r, 8)
  [BitConverter]::GetBytes([uint16]$opt.javaMin).CopyTo($r, 16)
  [BitConverter]::GetBytes([uint16]$opt.javaPref).CopyTo($r, 18)
  $r[20] = [byte]$opt.bundle
  $r[21] = [byte]$opt.flags
  if ($opt.pubkey) {
    $k = [IO.File]::ReadAllBytes($opt.pubkey)
    if ($k.Length -ne 1312) { Xek-Die 2 "public key must be 1312 bytes, is $($k.Length)" }
    $k.CopyTo($r, 32)
  }
  return $r
}

function Xek-RebaseZip64($path, $delta) {
  $fs = [IO.File]::Open($path, 'Open', 'ReadWrite')
  try {
    $size = $fs.Length
    $n = [Math]::Min(65557, $size)
    $tail = New-Object byte[] $n
    $fs.Seek($size - $n, 'Begin') | Out-Null
    $fs.Read($tail, 0, $n) | Out-Null
    $eocd = -1
    for ($i = $n - 4; $i -ge 0; $i--) {
      if ($tail[$i] -eq 0x50 -and $tail[$i+1] -eq 0x4b -and $tail[$i+2] -eq 0x05 -and $tail[$i+3] -eq 0x06) { $eocd = $i; break }
    }
    if ($eocd -lt 20) { return }
    if (-not ($tail[$eocd-20] -eq 0x50 -and $tail[$eocd-19] -eq 0x4b -and $tail[$eocd-18] -eq 0x06 -and $tail[$eocd-17] -eq 0x07)) { return }
    $locAt = $size - $n + $eocd - 20
    $fs.Seek($locAt + 8, 'Begin') | Out-Null
    $buf = New-Object byte[] 8
    $fs.Read($buf, 0, 8) | Out-Null
    $old = [BitConverter]::ToUInt64($buf, 0)
    $fs.Seek($locAt + 8, 'Begin') | Out-Null
    $fs.Write([BitConverter]::GetBytes([uint64]($old + $delta)), 0, 8)
  } finally { $fs.Close() }
}

function Xek-Concat($paths, $out) {
  $o = [IO.File]::Create($out)
  try { foreach ($p in $paths) { $s = [IO.File]::OpenRead($p); $s.CopyTo($o); $s.Close() } } finally { $o.Close() }
}

function Xek-BuildNative($stub, $out) {
  $tmp = "$out.tmp"
  $rec = [IO.Path]::GetTempFileName()
  [IO.File]::WriteAllBytes($rec, (Xek-Record))
  Xek-Concat @($stub, $rec, $opt.jar) $tmp
  Remove-Item $rec
  $stubSize = (Get-Item $stub).Length
  Xek-RebaseZip64 $tmp ($stubSize + 3764)
  Move-Item -Force $tmp $out
}

# --- template extraction ---------------------------------------------------
$XekIndexNum = $null; $XekIndex = $null
function Xek-LoadIndex {
  if ($null -ne $script:XekIndexNum) { return }
  for ($i = 0; $i -lt $XekLines.Count; $i++) {
    if ($XekLines[$i].StartsWith('index:')) { $script:XekIndexNum = $i + 1; $script:XekIndex = $XekLines[$i].Substring(6); return }
  }
  Xek-Die 2 'no embedded templates'
}
function Xek-Template($name) {
  Xek-LoadIndex
  $off = $null
  foreach ($e in $XekIndex.Split(',')) { if ($e -like "$name=*") { $off = [int]$e.Substring($name.Length + 1) } }
  if ($null -eq $off) { Xek-Die 2 "no embedded template $name" }
  $start = $XekIndexNum + $off  # 0-based line index of first content line
  $b64 = New-Object Text.StringBuilder
  for ($i = $start; $i -lt $XekLines.Count; $i++) {
    if ($XekLines[$i].StartsWith('-----END')) { break }
    [void]$b64.Append($XekLines[$i])
  }
  return [Convert]::FromBase64String($b64.ToString())
}

function Xek-B64Lines($bytes, $gzip) {
  if ($gzip) {
    $ms = New-Object IO.MemoryStream
    $gz = New-Object IO.Compression.GZipStream($ms, [IO.Compression.CompressionMode]::Compress)
    $gz.Write($bytes, 0, $bytes.Length); $gz.Close()
    $bytes = $ms.ToArray()
  }
  $b64 = [Convert]::ToBase64String($bytes)
  $out = New-Object Collections.Generic.List[string]
  for ($i = 0; $i -lt $b64.Length; $i += 8000) { $out.Add($b64.Substring($i, [Math]::Min(8000, $b64.Length - $i))) }
  return ,$out
}

function Xek-Prefix($delivery) {
  $tmpl = $Latin.GetString((Xek-Template 'xek.tmpl'))
  $bat = $Latin.GetString((Xek-Template "xek-$delivery.bat"))
  $ps1 = $Latin.GetString((Xek-Template "xek-$delivery.ps1"))
  $sh  = $Latin.GetString((Xek-Template "xek-$delivery.sh"))
  $lines = $tmpl -split "`n"
  $sb = New-Object Text.StringBuilder
  foreach ($ln in $lines) {
    switch -regex ($ln) {
      '@@BAT@@' { [void]$sb.Append($bat); [void]$sb.Append("`n"); continue }
      '@@PS1@@' { [void]$sb.Append($ps1); [void]$sb.Append("`n"); continue }
      '@@SH@@'  { [void]$sb.Append($sh);  [void]$sb.Append("`n"); continue }
      default   { [void]$sb.Append($ln);  [void]$sb.Append("`n") }
    }
  }
  return $sb.ToString()
}

# payloads: array of @{label;bytes;gzip}
function Xek-EmitPayloads($payloads, $sb) {
  $offset = 1; $index = @(); $encoded = @()
  foreach ($p in $payloads) {
    $lines = Xek-B64Lines $p.bytes $p.gzip
    $encoded += ,$lines
    $index += "$($p.label)=$offset"
    $offset += $lines.Count + 2
  }
  [void]$sb.Append("index:" + ($index -join ',') + "`n")
  foreach ($lines in $encoded) {
    [void]$sb.Append("-----BEGIN CERTIFICATE-----`n")
    foreach ($l in $lines) { [void]$sb.Append($l + "`n") }
    [void]$sb.Append("-----END CERTIFICATE-----`n")
  }
}

function Xek-WriteText($out, $text) { [IO.File]::WriteAllBytes($out, $Utf8NoBom.GetBytes($text)) }

function Xek-BuildEmbedAll($out) {
  $payloads = @()
  foreach ($label in $opt.targets) {
    $stub = Xek-ResolveStub $label
    $payloads += @{ label = $label; bytes = [IO.File]::ReadAllBytes($stub); gzip = -not ($label -like 'windows*') }
  }
  $payloads += @{ label = 'record'; bytes = (Xek-Record); gzip = $false }
  $payloads += @{ label = 'data'; bytes = [IO.File]::ReadAllBytes($opt.jar); gzip = $false }
  $sb = New-Object Text.StringBuilder
  [void]$sb.Append((Xek-Prefix 'installer'))
  Xek-EmitPayloads $payloads $sb
  [void]$sb.Append("#>`n")
  Xek-WriteText $out $sb.ToString()
}

function Xek-BuildDownload($out) {
  $base = if ($opt.runnersUrl) { $opt.runnersUrl } else { $XekRunnersUrl }
  if (-not $base) { Xek-Die 2 'download needs a runner URL' }
  $assets = @()
  foreach ($label in $opt.targets) {
    $name = Xek-StubName $label
    $hash = Xek-BakedHash $label
    if (-not $hash) { Xek-Die 2 "no hash for $label" }
    $assets += "$label=$base/$name|$hash"
  }
  $sb = New-Object Text.StringBuilder
  [void]$sb.Append((Xek-Prefix 'onlinelauncher'))
  Xek-EmitPayloads @(@{label='record';bytes=(Xek-Record);gzip=$false}, @{label='data';bytes=[IO.File]::ReadAllBytes($opt.jar);gzip=$false}) $sb
  [void]$sb.Append("assets:" + ($assets -join ',') + "`n")
  [void]$sb.Append("#>`n")
  Xek-WriteText $out $sb.ToString()
}

function Xek-BuildDispatch($out, $manifest) {
  $assets = @()
  foreach ($line in [IO.File]::ReadAllLines($manifest)) {
    if (-not $line.Trim()) { continue }
    $f = $line -split "`t"
    $assets += "$($f[0])=$($f[1])|$($f[2])"
  }
  $sb = New-Object Text.StringBuilder
  [void]$sb.Append((Xek-Prefix 'dispatcher'))
  [void]$sb.Append("assets:" + ($assets -join ',') + "`n")
  [void]$sb.Append("#>`n")
  Xek-WriteText $out $sb.ToString()
}

function Xek-HostTarget {
  $arch = if ($env:PROCESSOR_ARCHITECTURE -eq 'ARM64') { 'arm64' } else { 'x64' }
  "windows-$arch"
}
function Xek-AllLabels {
  if ($opt.runnersManifest) { Get-Content $opt.runnersManifest | ForEach-Object { ($_ -split "[`t=]")[0] } | Where-Object { $_ } }
  else { $XekManifest.Split(',') | ForEach-Object { $_.Split('=')[0] } | Where-Object { $_ } }
}

# --- parse & dispatch ------------------------------------------------------
$opt = @{ jar=$null; out=$null; targets=@(); manifest=$null; runnersDir=$null; runnersUrl=$null;
          runnersManifest=$null; javaMin=21; javaPref=24; bundle=0; flags=0; buildId=0; pubkey=$null }
$cmd = if ($args.Count -ge 1) { $args[0] } else { '' }
$i = 1
while ($i -lt $args.Count) {
  switch ($args[$i]) {
    '--jar' { $opt.jar = $args[++$i] }
    '--out' { $opt.out = $args[++$i] }
    '--target' { $opt.targets += $args[++$i] }
    '--manifest' { $opt.manifest = $args[++$i] }
    '--runners' { $opt.runnersDir = $args[++$i] }
    '--runners-url' { $opt.runnersUrl = $args[++$i] }
    '--runners-manifest' { $opt.runnersManifest = $args[++$i] }
    '--java-min' { $opt.javaMin = [int]$args[++$i] }
    '--java-pref' { $opt.javaPref = [int]$args[++$i] }
    '--jdk' { $opt.bundle = 1 }
    '--jre' { $opt.bundle = 0 }
    '--build-id' { $opt.buildId = [uint64]$args[++$i] }
    '--public-key' { $opt.pubkey = $args[++$i] }
    '--allow-downgrade' { $opt.flags = 1 }
    default { Xek-Die 1 "unknown option: $($args[$i])" }
  }
  $i++
}

switch ($cmd) {
  'version' { Write-Output "xek $XekVersion"; exit 0 }
  { $_ -in 'help','-h','--help','' } {
    [Console]::Error.WriteLine("xek $XekVersion — build XEK executables and launchers")
    [Console]::Error.WriteLine("  xek build|embed-all|download --jar F --out F [opts]")
    [Console]::Error.WriteLine("  xek dispatch --out F --manifest TSV; xek record --out F; xek fetch; xek version")
    exit $(if ($cmd) { 0 } else { 1 })
  }
  'build' {
    if (-not $opt.jar -or -not $opt.out) { Xek-Die 1 'build needs --jar and --out' }
    $target = if ($opt.targets.Count -ge 1) { $opt.targets[0] } else { Xek-HostTarget }
    Xek-BuildNative (Xek-ResolveStub $target) $opt.out; exit 0
  }
  'embed-all' {
    if (-not $opt.jar -or -not $opt.out) { Xek-Die 1 'embed-all needs --jar and --out' }
    if ($opt.targets.Count -eq 0) { $opt.targets = @(Xek-AllLabels) }
    Xek-BuildEmbedAll $opt.out; exit 0
  }
  'download' {
    if (-not $opt.jar -or -not $opt.out) { Xek-Die 1 'download needs --jar and --out' }
    if ($opt.targets.Count -eq 0) { $opt.targets = @(Xek-AllLabels) }
    Xek-BuildDownload $opt.out; exit 0
  }
  'dispatch' {
    if (-not $opt.out -or -not $opt.manifest) { Xek-Die 1 'dispatch needs --out and --manifest' }
    Xek-BuildDispatch $opt.out $opt.manifest; exit 0
  }
  'record' {
    if (-not $opt.out) { Xek-Die 1 'record needs --out' }
    [IO.File]::WriteAllBytes($opt.out, (Xek-Record)); exit 0
  }
  'fetch' {
    if ($opt.targets.Count -eq 0) { $opt.targets = @(Xek-AllLabels) }
    foreach ($l in $opt.targets) { Xek-ResolveStub $l | Out-Null }; exit 0
  }
  default { Xek-Die 1 "unknown command: $cmd" }
}
