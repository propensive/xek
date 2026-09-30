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

import ambience.*
import anticipation.*
import contingency.*
import denominative.*
import fulminate.*
import galilei.*
import gossamer.*
import prepositional.*
import rudiments.*
import serpentine.*
import vacuous.*

import systems.javaBaseSystem

// Where the bare runner stubs come from, and how one is made available as a local file.
object Stubs:
  enum Source:
    // `<directory>/runner-<label>[.exe]`, as `make runners-build` writes them: for development
    // and testing, so neither downloaded nor verified.
    case Directory(directory: Path on Local)

    // `<baseUrl>/runner-<label>[.exe]`, verified against `hashes(label)` — lowercase SHA-256
    // hex, as in `etc/runners/<version>.tsv`. The production source.
    case Remote(baseUrl: Text, hashes: Map[Text, Text])

  // Where downloaded stubs are cached: `$XEK_CACHE`, else `$XDG_CACHE_HOME/xek`, else
  // `~/.cache/xek`. The environment is passed in, since a daemon must read its client's.
  def cache(variable: Text => Optional[Text], home: Text): Path on Local =
    val explicit: Optional[Text] = variable(t"XEK_CACHE")
    val xdg: Optional[Text] = variable(t"XDG_CACHE_HOME")

    if explicit.present then Files.path(explicit.or(t""))
    else if xdg.present then Files.child(Files.path(xdg.or(t"")), t"xek")
    else Files.child(Files.child(Files.path(home), t".cache"), t"xek")

  // A manifest of `label<TAB>sha256` lines, as `etc/runners/<version>.tsv` holds, or of
  // `label=sha256` lines; blank lines and `#` comments are ignored.
  def manifest(text: Text): Map[Text, Text] =
    text.cut(t"\n").map(_.trim).filter { line => line != t"" && !line.starts(t"#") }.map: line =>
      val separator: Text = if line.contains(t"\t") then t"\t" else t"="
      val fields = line.cut(separator)
      (fields.prim.or(t"").trim, fields.reverse.prim.or(t"").trim)
    . to[Map]

  def hash(source: Source, target: Target): Optional[Text] = source match
    case Source.Directory(_)          => Unset
    case Source.Remote(_, hashes) => hashes(target.label)

  def url(source: Source, target: Target): Optional[Text] = source match
    case Source.Directory(_)           => Unset
    case Source.Remote(baseUrl, _) => t"${baseUrl.s.stripSuffix("/").tt}/${target.stub}"

  // Checks, before anything is downloaded, that every target can be resolved: that a local
  // stub exists, or that a remote one has a published hash to verify it against.
  def check(source: Source, targets: List[Target]): Unit raises Assembler.Error =
    targets.each: target =>
      source match
        case Source.Directory(directory) =>
          val stub: Path on Local = Files.child(directory, target.stub)

          if !Files.exists(stub)
          then abort(Assembler.Error(Assembler.Fault.Usage, m"there is no stub ${target.stub} in ${directory.encode}"))

        case Source.Remote(_, hashes) =>
          if hashes(target.label).absent
          then abort(Assembler.Error(Assembler.Fault.Format, m"no runner hash is known for ${target.label}"))

  // A local path to the stub for `target`: from a local directory directly; otherwise from the
  // cache when a copy with the right hash is there, or else downloaded, verified and cached. The
  // cache is content-addressed, so stubs from any release or mirror can share it.
  def resolve(source: Source, target: Target, cache: Path on Local)(progress: Text => Unit)
  :   Path on Local raises Assembler.Error =

    source match
      case Source.Directory(directory) =>
        check(source, List(target))
        Files.child(directory, target.stub)

      case Source.Remote(baseUrl, hashes) =>
        val expected: Text =
          hashes(target.label).lest(Assembler.Error(Assembler.Fault.Format, m"no runner hash is known for ${target.label}"))

        val cached: Path on Local = Files.child(Files.child(Files.child(cache, t"runners"), expected), target.stub)

        if Files.exists(cached) && Files.sha256(Files.read(cached)) == expected then cached else
          val location: Text = url(source, target).or(t"")
          progress(t"Fetching ${target.stub}")
          val bytes: scala.Array[Byte] = download(location)
          val actual: Text = Files.sha256(bytes)

          if actual != expected then
            abort(Assembler.Error(Assembler.Fault.Format, m"SHA-256 mismatch for $location: expected $expected, but got $actual"))

          Files.write(cached, executable = !target.windows)(_.write(bytes))
          cached

  // The bytes at a URL; `file:` URLs are read too, which is what the tests use.
  def download(url: Text): scala.Array[Byte] raises Assembler.Error =
    try
      val connection = java.net.URI(url.s).toURL.nn.openConnection().nn
      connection.setConnectTimeout(30000)
      connection.setReadTimeout(60000)
      val stream = connection.getInputStream().nn
      try stream.readAllBytes().nn finally stream.close()
    catch
      case error: ji.IOException =>
        abort(Assembler.Error(Assembler.Fault.Download, m"could not download $url"))

      case error: IllegalArgumentException =>
        abort(Assembler.Error(Assembler.Fault.Usage, m"$url is not a valid URL"))
