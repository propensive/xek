                                                                                                  /*
┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃                                                                                                  ┃
┃                                 ╭───╮ ╭───╮╭────────╮╭───╮ ╭───╮                                 ┃
┃                                 │   ╰─╯   ││   ╭─╮  ││   ╰─╯   │                                 ┃
┃                                 ╰──╮   ╭──╯│   ╰─╯  ││      ╭──╯                                 ┃
┃                                 ╭──╯   ╰──╮│   ╭────╯│      ╰──╮                                 ┃
┃                                 │   ╭─╮   ││   ╰────╮│   ╭─╮   │                                 ┃
┃                                 ╰───╯ ╰───╯╰────────╯╰───╯ ╰───╯                                 ┃
┃                                                                                                  ┃
┃    Cross-platform Executable Kit, version ${VERSION}.                                            ┃
┃    © Copyright 2021-26 Jon Pretty, Propensive OÜ.                                                ┃
┃                                                                                                  ┃
┃    The primary distribution site is:                                                             ┃
┃                                                                                                  ┃
┃        https://github.com/propensive/xek/                                                        ┃
┃                                                                                                  ┃
┃    Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file     ┃
┃    except in compliance with the License. You may obtain a copy of the License at                ┃
┃                                                                                                  ┃
┃        https://www.apache.org/licenses/LICENSE-2.0                                               ┃
┃                                                                                                  ┃
┃    Unless required by applicable law or agreed to in writing,  software distributed under the    ┃
┃    License is distributed on an "AS IS" BASIS,  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,    ┃
┃    either express or implied. See the License for the specific language governing permissions    ┃
┃    and limitations under the License.                                                            ┃
┃                                                                                                  ┃
┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
                                                                                                  */
package xek

import java.io as ji
import java.util.zip as juz

import anticipation.*
import contingency.*
import denominative.*
import galilei.*
import gossamer.*
import hellenism.*
import hieroglyph.*
import prepositional.*
import rudiments.*
import serpentine.*
import symbolism.*
import turbulence.*


import charsets.utf8Charset
import denominative.dysasymptotics.linearSize
import classloaders.threadContextClassloader
import textSanitizers.skipSanitizer

// The polyglot file: one file which is at once a valid `sh` script, a PowerShell script and a
// `.bat` file, and which turns itself into the native executable for the platform it runs on.
//
// Its prefix is `xek.tmpl` with one section per shell substituted in, from the three templates
// of a delivery. After the prefix comes an `index:` line of `label=offset` pairs and the payloads
// it indexes, each framed by `-----BEGIN CERTIFICATE-----` and `-----END CERTIFICATE-----` lines
// as base64 folded at 8000 columns (the framing is what `certutil -decode` reads). An offset is
// the number of lines from the `index:` line to its payload's `BEGIN` line. A table of
// downloadable `assets:` may follow, and a final `#>` closes the PowerShell block comment which
// hides everything after the PowerShell section.
object Polyglot:
  enum Delivery:
    // Carries every stub and the application; unpacks the right stub where it runs.
    case Installer

    // Carries the application; downloads and verifies its stub on first run.
    case OnlineLauncher

    // Carries nothing but a table of complete executables to download.
    case Dispatcher

  case class Payload(label: Text, bytes: scala.Array[Byte], gzip: Boolean)

  // A download: the URL of a stub or a complete executable, and its SHA-256.
  case class Asset(target: Target, url: Text, hash: Text)

  private val width: Int = 8000

  def installer(stubs: List[(Target, Path on Local)], record: Record, jar: Path on Local, shells: List[Shell])
  :   Text raises Assembler.Error =

    val payloads: List[Payload] =
      stubs.map { (target, stub) => Payload(target.label, Files.read(stub), !target.windows) }

    val tail: List[Payload] =
      List
        ( Payload(t"record", Array.unsafeJvm(record.data), false),
          Payload(t"data", Files.read(jar), false) )

    prefix(Delivery.Installer, shells)+frame(payloads + tail)+t"#>\n"

  def onlineLauncher(assets: List[Asset], record: Record, jar: Path on Local, shells: List[Shell])
  :   Text raises Assembler.Error =

    val payloads: List[Payload] =
      List
        ( Payload(t"record", Array.unsafeJvm(record.data), false),
          Payload(t"data", Files.read(jar), false) )

    prefix(Delivery.OnlineLauncher, shells)+frame(payloads)+table(assets)+t"#>\n"

  def dispatcher(assets: List[Asset], shells: List[Shell]): Text =
    prefix(Delivery.Dispatcher, shells)+table(assets)+t"#>\n"

  // The `assets:` line: `label=url|sha256`, comma-separated.
  def table(assets: List[Asset]): Text =
    t"assets:${assets.map { asset => t"${asset.target.label}=${asset.url}|${asset.hash}" }.join(t",")}\n"

  def prefix(delivery: Delivery, shells: List[Shell]): Text =
    val (bat, ps1, sh) = sections(delivery)

    def section(shell: Shell, text: Text): Text =
      if shells.has(shell) then text.s.stripSuffix("\n").tt else refusal(shell, shells)

    val template: Text = cp"/xek/xek.tmpl".read[Text]

    // Substituted line by line, as a whole line, exactly as the markers are written.
    template.cut(t"\n").map: line =>
      line match
        case t"@@BAT@@" => section(Shell.Bat, bat)
        case t"@@PS1@@" => section(Shell.Pwsh, ps1)
        case t"@@SH@@"  => section(Shell.Sh, sh)
        case line       => line
    . join(t"\n")

  // The line which stands in for an excluded shell's section, naming those that can run it.
  def refusal(shell: Shell, shells: List[Shell]): Text =
    val others: Text = shells.map(_.name).join(t", ")

    shell match
      case Shell.Bat =>
        t"echo This file cannot be run by cmd.exe; run it with: $others 1>&2\nexit /b 1"

      case Shell.Pwsh =>
        t"[Console]::Error.WriteLine('This file cannot be run by PowerShell; run it with: $others'); exit 1"

      case Shell.Sh =>
        t"printf 'This file cannot be run by sh; run it with: %s\\n' '$others' >&2; exit 1"

  private def sections(delivery: Delivery): (Text, Text, Text) = delivery match
    case Delivery.Installer =>
      ( cp"/xek/xek-installer.bat".read[Text],
        cp"/xek/xek-installer.ps1".read[Text],
        cp"/xek/xek-installer.sh".read[Text] )

    case Delivery.OnlineLauncher =>
      ( cp"/xek/xek-onlinelauncher.bat".read[Text],
        cp"/xek/xek-onlinelauncher.ps1".read[Text],
        cp"/xek/xek-onlinelauncher.sh".read[Text] )

    case Delivery.Dispatcher =>
      ( cp"/xek/xek-dispatcher.bat".read[Text],
        cp"/xek/xek-dispatcher.ps1".read[Text],
        cp"/xek/xek-dispatcher.sh".read[Text] )

  // The `index:` line and the framed payloads it indexes. Every payload occupies at least one
  // line, even an empty one, so that its offset and the next payload's always differ.
  def frame(payloads: List[Payload]): Text =
    val encoded: List[(Text, List[String])] =
      payloads.map: payload =>
        val bytes: scala.Array[Byte] = if payload.gzip then gzip(payload.bytes) else payload.bytes
        (payload.label, fold(java.util.Base64.getEncoder.nn.encodeToString(bytes).nn))

    val builder = StringBuilder()
    var offset: Int = 1

    val index: List[Text] =
      encoded.map: (label, lines) =>
        val entry = t"$label=$offset"
        offset += lines.size + 2
        entry

    builder.append(t"index:${index.join(t",")}\n".s)

    encoded.each: (_, lines) =>
      builder.append("-----BEGIN CERTIFICATE-----\n")
      lines.each { line => builder.append(line).append('\n') }
      builder.append("-----END CERTIFICATE-----\n")

    builder.toString.tt

  private def fold(text: String): List[String] =
    if text.isEmpty then List("")
    else List.tabulate((text.length + width - 1)/width) { index => text.substring(index*width, ((index + 1)*width) min text.length).nn }

  // Compressed as `gzip -n` would: no name and no timestamp, so the same input always yields the
  // same output.
  private def gzip(bytes: scala.Array[Byte]): scala.Array[Byte] =
    val buffer = ji.ByteArrayOutputStream()
    val stream = juz.GZIPOutputStream(buffer)
    try stream.write(bytes) finally stream.close()
    buffer.toByteArray.nn

  // Writes a polyglot file, executable (so that `sh` need not be named to run it).
  def write(text: Text, output: Path on Local): Path on Local raises Assembler.Error =
    Files.write(output, executable = true)(_.write(text.s.getBytes("UTF-8").nn))
    output
