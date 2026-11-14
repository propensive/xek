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
import gossamer.*
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
        abort(Packager.Error(1, m"Burdock remote dependencies are not yet supported (Stage C)"))

    config.delivery match
      case Packaging.Delivery.Native if config.targets.size != 1 =>
        val length: Int = config.targets.size
        abort(Packager.Error(2, m"Native delivery requires exactly one target, but $length were given"))
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
      case error: Assembler.Error => Packager.Error(0, error.detail, error.code.skip(1))
    . protect:
        val plan: Build.Plan = Build.plan(options, Unset, Files.parent(local(config.output)))
        Build.execute(plan, cache)(_ => ())

    config.output

  private def target(label: Text): Target raises Packager.Error =
    Target.parse(label).lest(Packager.Error(3, m"$label is not a known platform"))

  private def javaPath(path: Path on Linux): jnf.Path = jnf.Path.of(path.encode.s).nn
  private def local(path: Path on Linux): Path on Local = Files.local(javaPath(path))

  // An error of the packager's own is ↯/x.3.1 and the `site` it was raised from; one which carries
  // an error of the assembler's is ↯/x.3.2 followed by that error's code, as in ↯/x.3.2/x.2.1.1.37.
  case class Error(site: Int, detail: Message, cause: Optional[Text] = Unset)(using Diagnostics)
  extends fulminate.Error(detail):
    def code: Text = cause.let { cause => t"↯/x.3.2$cause" }.or(t"↯/x.3.1.$site")
