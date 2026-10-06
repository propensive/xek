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

import anticipation.*
import contingency.*
import galilei.*
import gossamer.*
import hellenism.*
import hieroglyph.*
import prepositional.*
import rudiments.*
import serpentine.*
import turbulence.*
import vacuous.*

import charsets.utf8Charset
import classloaders.threadContextClassloader
import textSanitizers.skipSanitizer

// The install scripts of a release: `install.sh`, for `curl -fsSL <url> | sh`, and
// `install.ps1`, for `irm <url> | iex`. Each detects the platform it runs on, downloads
// `<base>/<name>-<label>[.exe]` and checks it against the digest embedded for that platform,
// so the scripts are per-release: they are written from `xek-install.sh` and `xek-install.ps1`
// with the release's name, base URL and digests substituted in.
object Installer:
  case class Options
    ( url:     Text,
      name:    Text,
      release: Text,
      digests: Map[Target, Text],
      out:     Path on Local )

  // The sh script's digest table: one `case` arm per platform.
  def sh(options: Options): Text =
    val arms: List[Text] =
      published(options).map: (target, digest) => t"  ${target.label}) expected=$digest ;;"

    substitute(cp"/xek/xek-install.sh".read[Text], options, arms)

  // The PowerShell script's digest table: one hashtable entry per platform.
  def pwsh(options: Options): Text =
    val entries: List[Text] =
      published(options).map: (target, digest) => t"  '${target.label}' = '$digest'"

    substitute(cp"/xek/xek-install.ps1".read[Text], options, entries)

  // The platforms a digest is known for, in `Target.all`'s order.
  private def published(options: Options): List[(Target, Text)] =
    Target.all.flatMap: target =>
      options.digests(target).let { digest => List((target, digest)) }.or(Nil)

  // Substitutes the placeholders, and the whole `@@DIGESTS@@` line, as `Polyglot` does with
  // the sections of its templates.
  private def substitute(template: Text, options: Options, digests: List[Text]): Text =
    val base: Text = options.url.s.stripSuffix("/").tt

    template.cut(t"\n").flatMap: line =>
      if line == t"@@DIGESTS@@" then digests
      else
        List
          ( line.s.replace("@@NAME@@", options.name.s).nn
            . replace("@@UPPER@@", upper(options.name).s).nn
            . replace("@@RELEASE@@", options.release.s).nn
            . replace("@@BASE@@", base.s).nn.tt )

    . join(t"\n")

  // The environment variable naming the install directory: `XEK_INSTALL_DIR` for `xek`, and
  // for a name with hyphens, `MY_TOOL_INSTALL_DIR`.
  def upper(name: Text): Text = name.upper.s.replace('-', '_').nn.tt

  def write(options: Options): List[Path on Local] raises Assembler.Error =
    val files: List[(Text, Text)] =
      List((t"install.sh", sh(options)), (t"install.ps1", pwsh(options)))

    files.map: (name, text) =>
      val path: Path on Local = Files.child(options.out, name)

      Files.write(path, executable = name.ends(t".sh")): stream =>
        stream.write(text.s.getBytes("UTF-8"))

      path
