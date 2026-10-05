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

import java.nio.file as jnf

import ambience.*
import anticipation.*
import contingency.*
import fulminate.*
import galilei.*
import prepositional.*
import rudiments.*
import serpentine.*
import vacuous.*

import denominative.size
import denominative.dysasymptotics.linearSize
import errorDiagnostics.emptyDiagnostics

// Turns a `Packaging` into a distributable through `core` — the same code the `xek` command runs,
// so that an Anthology build and a shell user reach the same bytes.
object Packager:
  def pack(config: Packaging)(using WorkingDirectory): Path on Linux raises Packager.Error =
    val jar: Path on Linux = config.dependencies.absolve match
      case Packaging.Dependencies.FatJar(jar) => jar
      case Packaging.Dependencies.BurdockRemote(_) =>
        abort(Packager.Error(m"Burdock remote dependencies are not yet supported (Stage C)"))

    config.delivery match
      case Packaging.Delivery.Native if config.targets.size != 1 =>
        val length: Int = config.targets.size
        abort(Packager.Error(m"Native delivery requires exactly one target, but $length were given"))
      case _ => ()

    val targets: List[Target] = config.targets.map(target(_))

    val source: Stubs.Source = config.clientSource.absolve match
      case Packaging.ClientSource.Local(directory)      => Stubs.Source.Directory(local(directory))
      case Packaging.ClientSource.Remote(baseUrl, hashes) => Stubs.Source.Remote(baseUrl, hashes)

    def key(path: Optional[Path on Linux]): Optional[Data] =
      path.let { path => Array.unsafeFrozen(jnf.Files.readAllBytes(javaPath(path)).nn) }

    val record: Record =
      Record
        ( buildId        = config.buildId,
          javaMinimum    = config.java.minimum,
          javaPreferred  = config.java.preferred,
          jdk            = config.java.bundle == Packaging.Bundle.Jdk,
          allowDowngrade = config.signing.let(_.allowDowngrade).or(false),
          appId          = config.appId,
          releaseKey     = key(config.signing.let(_.publicKey)),
          recoveryKey    = key(config.signing.let(_.recoveryKey)) )

    val options: Options =
      Options
        ( jar      = local(jar),
          output   = local(config.output),
          targets  = targets,
          polyglot = config.delivery == Packaging.Delivery.EmbedAll,
          download = config.delivery == Packaging.Delivery.Download,
          record   = record,
          source   = source )

    val home: Text = java.lang.System.getProperty("user.home").nn.tt
    val cache: Path on Local = Stubs.cache(name => Optional(java.lang.System.getenv(name.s)).let(_.tt), home)

    mitigate:
      case Assembler.Error(_, detail) => Packager.Error(detail)
    . protect:
        val plan: Build.Plan = Build.plan(options, Unset, Files.parent(local(config.output)))
        Build.execute(plan, cache)(_ => ())

    config.output

  private def target(label: Text): Target raises Packager.Error =
    Target.parse(label).lest(Packager.Error(m"$label is not a known platform"))

  private def javaPath(path: Path on Linux): jnf.Path = jnf.Path.of(path.encode.s).nn
  private def local(path: Path on Linux): Path on Local = Files.local(javaPath(path))

  case class Error(detail: Message)(using Diagnostics) extends fulminate.Error(detail)
