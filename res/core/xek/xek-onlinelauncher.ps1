$os = if ($IsWindows -or $env:OS) {
    "windows"
} elseif ($IsMacOS) { "macos"
} else { "linux" }
$raw = if ($env:PROCESSOR_ARCHITECTURE) {
    $env:PROCESSOR_ARCHITECTURE
} else {
    [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
}
$arch = switch ($raw) {
    "AMD64"  { "x64" }
    "X64"    { "x64" }
    "x86"    { "x86" }
    "ARM64"  { "arm64" }
    default  { $raw.ToLower() }
}
$label = "${os}-${arch}"
$s = $MyInvocation.MyCommand.Definition
$lines = Get-Content -Path $s
$assets = ($lines -match '^assets:' | Select-Object -First 1) -replace '^assets:'
if (-not $assets) { [Console]::Error.WriteLine("No asset table"); exit 1 }
$row = $assets.Split(',') | Where-Object { $_.StartsWith("$label=") } | Select-Object -First 1
if (-not $row) { [Console]::Error.WriteLine("No client for $label"); exit 1 }
$value = $row.Substring($label.Length + 1)
$parts = $value.Split('|')
$url = $parts[0]
$hash = $parts[1]
$t = "$s.tmp"
xek_msg 33 '████████' 0 'Downloading client…'
try { Invoke-WebRequest -Uri $url -OutFile $t -UseBasicParsing } catch {
    [Console]::Error.WriteLine("Download failed"); exit 1
}
$size = (Get-Item $t).Length
xek_msg 32 '████████' 1 "Downloaded $size bytes"
xek_msg 33 '████████' 0 'Verifying SHA-256…'
$g = (Get-FileHash -Path $t -Algorithm SHA256).Hash
if ($g -ine $hash) {
    [Console]::Error.WriteLine("Hash mismatch"); Remove-Item $t; exit 1
}
xek_msg 32 '████████' 1 'Verified SHA-256'

# Locate the embedded application JAR (the `data` payload) and append it to the downloaded
# stub, exactly as the offline installer does, forming the self-contained executable.
$indexLineNum = 0
$indexContent = $null
$reader = [IO.StreamReader]::new($s)
while ($null -ne ($line = $reader.ReadLine())) {
    $indexLineNum++
    if ($line.StartsWith('index:')) { $indexContent = $line.Substring(6); break }
}
$reader.Close()
if ($null -eq $indexContent) { [Console]::Error.WriteLine("No index found"); Remove-Item $t; exit 1 }
$offsets = @{}
foreach ($entry in $indexContent.Split(',')) {
    $k, $v = $entry.Split('=')
    $offsets[$k] = [int]$v
}
if (-not $offsets.ContainsKey("data")) {
    [Console]::Error.WriteLine("No embedded data payload"); Remove-Item $t; exit 1
}
xek_msg 33 '████████' 0 'Assembling…'
if ($os -eq "windows") {
    $tmpDir = if ($env:TEMP) { $env:TEMP } else { "/tmp" }
    $tmp = Join-Path $tmpDir "~zig_$PID"
    if ($offsets.ContainsKey("record")) {
        $rskip = $indexLineNum + $offsets["record"] - 1
        & cmd /c "more +$rskip `"$s`" > `"${tmp}.pem`""
        & certutil -decode "${tmp}.pem" "${tmp}.rec" > $null
        Remove-Item "${tmp}.pem"
        & cmd /c "copy /b `"$t`"+`"${tmp}.rec`" `"${t}.rec`" > nul"
        Move-Item -Force "${t}.rec" $t
        Remove-Item "${tmp}.rec"
    }
    $dskip = $indexLineNum + $offsets["data"] - 1
    & cmd /c "more +$dskip `"$s`" > `"${tmp}.pem`""
    & certutil -decode "${tmp}.pem" "${tmp}.dat" > $null
    Remove-Item "${tmp}.pem"
    & cmd /c "copy /b `"$t`"+`"${tmp}.dat`" `"${t}.out`" > nul"
    Remove-Item "${tmp}.dat"
    Move-Item -Force "${t}.out" $t
} else {
    $decode = '{ base64 -d 2>/dev/null || base64 -D; }'
    if ($offsets.ContainsKey("record")) {
        $rskip = $indexLineNum + $offsets["record"]
        & bash -c "tail -n +$($rskip + 1) '$s' | sed -n '/^-----END/q; /^-----BEGIN/d; p' | $decode >> '$t'" 2>/dev/null
    }
    $dskip = $indexLineNum + $offsets["data"]
    & bash -c "tail -n +$($dskip + 1) '$s' | sed -n '/^-----END/q; /^-----BEGIN/d; p' | $decode >> '$t'" 2>/dev/null
    & chmod +x $t
}
xek_msg 32 '████████' 1 'Assembled'
Move-Item -Force $t $s
& $s @args
