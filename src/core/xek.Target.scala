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
import gossamer.*
import rudiments.*
import vacuous.*

// The target platforms a runner stub is published for. The label is the name every other part of the
// system uses — the stub's asset name (`runner-<label>[.exe]`), the key of the published hash
// manifest, and the payload name the polyglot launchers select by — so it is the one spelling a
// user types, too.
enum Target(val label: Text, val description: Text):
  case LinuxX64   extends Target(t"linux-x64",   t"Linux on x86-64")
  case LinuxArm64 extends Target(t"linux-arm64", t"Linux on ARM64")
  case MacosX64   extends Target(t"macos-x64",   t"macOS on Intel")
  case MacosArm64 extends Target(t"macos-arm64", t"macOS on Apple silicon")
  case WindowsX64 extends Target(t"windows-x64", t"Windows on x86-64")

  def windows: Boolean = this == Target.WindowsX64

  // The published filename of this platform's bare stub; Windows stubs carry `.exe`.
  def stub: Text = if windows then t"runner-$label.exe" else t"runner-$label"

object Target:
  val all: List[Target] = List(LinuxX64, LinuxArm64, MacosX64, MacosArm64, WindowsX64)

  def parse(label: Text): Optional[Target] =
    val lower: Text = label.lower
    all.filter(_.label == lower).prim

  // The platform named by a JVM's `os.name` and `os.arch` properties, mapped exactly as the
  // launcher templates map `uname -s` and `uname -m`. There is no Windows ARM64 stub, so a
  // Windows host on ARM is unsupported, as it is in the templates.
  def host(osName: Text, osArch: Text): Optional[Target] =
    val arm: Boolean = osArch.lower == t"aarch64" || osArch.lower == t"arm64"
    val name: Text = osName.lower

    if name.starts(t"mac") || name.starts(t"darwin") then if arm then MacosArm64 else MacosX64
    else if name.starts(t"windows") then if arm then Unset else WindowsX64
    else if name.starts(t"linux") then if arm then LinuxArm64 else LinuxX64
    else Unset
