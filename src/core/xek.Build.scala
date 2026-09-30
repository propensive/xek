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

// What a user asks `xek` for, as the command line states it, before any default is applied.
case class Options
  ( jar:      Optional[Path on Local] = Unset,
    output:   Optional[Path on Local] = Unset,
    targets:  List[Target]            = Nil,
    polyglot: Boolean                 = false,
    download: Boolean                 = false,
    exclude:  List[Shell]             = Nil,
    dispatch: Optional[Path on Local] = Unset,
    record:   Record                  = Record(),
    source:   Stubs.Source            = Runners.standard )

// Deciding what to build, and building it. `plan` applies the defaults and checks the request as
// a whole, before anything is downloaded or written; `execute` does the work.
object Build:
  enum Plan:
    case Natives(jar: Path on Local, outputs: List[(Target, Path on Local)], record: Record, source: Stubs.Source)

    case Installer
      ( jar: Path on Local, targets: List[Target], output: Path on Local, record: Record,
        source: Stubs.Source, shells: List[Shell] )

    case OnlineLauncher
      ( jar: Path on Local, targets: List[Target], output: Path on Local, record: Record,
        source: Stubs.Source, shells: List[Shell] )

    case Dispatcher(manifest: Path on Local, output: Path on Local, shells: List[Shell])

    def files: List[Path on Local] = this match
      case Natives(_, outputs, _, _)                 => outputs.map(_(1))
      case Installer(_, _, output, _, _, _)          => List(output)
      case OnlineLauncher(_, _, output, _, _, _)     => List(output)
      case Dispatcher(_, output, _)                  => List(output)

  private def usage(message: Message)(using Diagnostics): Assembler.Error =
    Assembler.Error(Assembler.Fault.Usage, message)

  // The executable's name when none is given: the JAR's, without its `.jar`.
  def defaultName(jar: Path on Local): Text =
    val name: Text = jar.name
    if name.lower.ends(t".jar") && name.length > 4 then name.keep(name.length - 4) else name

  // Which targets a polyglot file can serve with the shells it includes: the sh and PowerShell
  // sections can each unpack a Linux or macOS stub, and the PowerShell and cmd.exe sections a
  // Windows one.
  def servable(target: Target, shells: List[Shell]): Boolean =
    if target.windows then shells.has(Shell.Bat) || shells.has(Shell.Pwsh)
    else shells.has(Shell.Sh) || shells.has(Shell.Pwsh)

  // Whether a stub is to be had for a target from a source, by default: a local directory's own
  // stubs, or a remote manifest's.
  private def available(source: Stubs.Source, target: Target): Boolean = source match
    case Stubs.Source.Directory(directory) => Files.exists(Files.child(directory, target.stub))
    case Stubs.Source.Remote(_, hashes) => hashes(target.label).present

  // `directory` is the invocation's working directory, where the output goes by default.
  def plan(options: Options, host: Optional[Target], directory: Path on Local)
  :   Plan raises Assembler.Error =

    val shells: List[Shell] = Shell.all.filter(!options.exclude.has(_))
    val polyglot: Boolean = options.polyglot || options.download
    val record: Record = options.record

    if record.javaMinimum > record.javaPreferred then
      val minimum = record.javaMinimum
      val preferred = record.javaPreferred
      abort(usage(m"the minimum Java version, $minimum, is above the preferred version, $preferred"))

    if !options.exclude.nil && !polyglot && options.dispatch.absent
    then abort(usage(m"--exclude applies only to a polyglot file (--polyglot, --download or --dispatch)"))

    if shells.nil then abort(usage(m"every shell is excluded, so the file could not run anywhere"))

    if options.dispatch.present then
      if polyglot then abort(usage(m"--dispatch cannot be combined with --polyglot or --download"))
      if !options.targets.nil then abort(usage(m"--dispatch takes its platforms from its manifest"))

      val manifest: Path on Local = options.dispatch.or(Files.child(directory, t""))
      val output: Path on Local = options.output.lest(usage(m"--dispatch needs the name of the file to write"))
      if Files.directory(output) then abort(usage(m"${output.encode} is a directory"))
      Plan.Dispatcher(manifest, output, shells)

    else
      val jar: Path on Local = options.jar.lest(usage(m"no JAR file was given"))
      val name: Text = defaultName(jar)

      // An output which is a directory is where the default name goes, as `cp` would have it.
      val named: Path on Local = options.output.or(directory)
      val output: Path on Local = if Files.directory(named) then Files.child(named, name) else named

      if output.encode == jar.encode then abort(usage(m"the output would overwrite the JAR itself"))

      if polyglot then polyglotPlan(options, jar, output, shells)
      else nativePlan(options, jar, output, host)

  private def polyglotPlan
    ( options: Options, jar: Path on Local, output: Path on Local, shells: List[Shell] )
  :   Plan raises Assembler.Error =

    val targets: List[Target] =
      if !options.targets.nil then options.targets
      else Target.all.filter { target => servable(target, shells) && available(options.source, target) }

    targets.each: target =>
      if !servable(target, shells)
      then abort(usage(m"no included shell can unpack a stub for ${target.label}"))

    if targets.nil then abort(usage(m"there are no stubs to include"))

    if options.download then
      options.source match
        case Stubs.Source.Directory(_) =>
          abort(usage(m"--download needs stubs published at a URL, not a local --runners directory"))

        case _ =>
          Plan.OnlineLauncher(jar, targets, output, options.record, options.source, shells)

    else Plan.Installer(jar, targets, output, options.record, options.source, shells)

  private def nativePlan
    ( options: Options, jar: Path on Local, output: Path on Local, host: Optional[Target] )
  :   Plan raises Assembler.Error =

    val targets: List[Target] =
      if !options.targets.nil then options.targets
      else List(host.lest(usage(m"this platform has no runner stub; name one with --platform")))

    def exe(path: Path on Local, target: Target): Path on Local =
      if target.windows && !path.name.lower.ends(t".exe") then Files.sibling(path, t"${path.name}.exe")
      else path

    // Several targets make several files, each named for its platform.
    val outputs: List[(Target, Path on Local)] = targets match
      case List(target) =>
        List((target, exe(output, target)))

      case _ =>
        // Bound before the lambda: interpolating it inside trips dotc (scala/scala3#24824).
        val stem: Text = output.name

        targets.map: target =>
          val label: Text = target.label
          (target, exe(Files.sibling(output, t"$stem-$label"), target))

    Plan.Natives(jar, outputs, options.record, options.source)

  // Carries out a plan, reporting progress as it goes, and returns the files written.
  def execute(plan: Plan, cache: Path on Local)(progress: Text => Unit)
  :   List[Path on Local] raises Assembler.Error =

    plan match
      case Plan.Natives(jar, outputs, record, source) =>
        Assembler.checkJar(jar)
        Stubs.check(source, outputs.map(_(0)))

        outputs.map: (target, output) =>
          nativeOutput(jar, target, output, record, source, cache)(progress)

      case Plan.Installer(jar, targets, output, record, source, shells) =>
        Assembler.checkJar(jar)
        Stubs.check(source, targets)
        val stubs = targets.map { target => (target, Stubs.resolve(source, target, cache)(progress)) }
        progress(t"Writing ${output.encode}")
        List(Polyglot.write(Polyglot.installer(stubs, record, jar, shells), output))

      case Plan.OnlineLauncher(jar, targets, output, record, source, shells) =>
        Assembler.checkJar(jar)
        Stubs.check(source, targets)

        val assets: List[Polyglot.Asset] = targets.map(asset(source, _))

        progress(t"Writing ${output.encode}")
        List(Polyglot.write(Polyglot.onlineLauncher(assets, record, jar, shells), output))

      case Plan.Dispatcher(manifest, output, shells) =>
        val text: Text = String(Files.read(manifest), "UTF-8").tt
        progress(t"Writing ${output.encode}")
        List(Polyglot.write(Polyglot.dispatcher(dispatchAssets(text), shells), output))

  // Each step is its own method rather than the body of a lambda: an interpolation or an
  // `Optional` read inside a lambda can trip dotc (scala/scala3#24824).
  private def nativeOutput
    ( jar:    Path on Local,
      target: Target,
      output: Path on Local,
      record: Record,
      source: Stubs.Source,
      cache:  Path on Local )
    ( progress: Text => Unit )
  :   Path on Local raises Assembler.Error =

    val stub: Path on Local = Stubs.resolve(source, target, cache)(progress)
    progress(t"Writing ${output.encode} for ${target.label}")
    Assembler.native(stub, record, jar, output, target.windows)

  private def asset(source: Stubs.Source, target: Target): Polyglot.Asset =
    val url: Text = Stubs.url(source, target).or(t"")
    val hash: Text = Stubs.hash(source, target).or(t"")
    Polyglot.Asset(target, url, hash)

  // A dispatcher's manifest: `label<TAB>url<TAB>sha256` lines naming complete executables.
  def dispatchAssets(text: Text): List[Polyglot.Asset] raises Assembler.Error =
    text.cut(t"\n").map(_.trim).filter { line => line != t"" && !line.starts(t"#") }.map(dispatchAsset(_))

  private def dispatchAsset(line: Text): Polyglot.Asset raises Assembler.Error =
    line.cut(t"\t").map(_.trim) match
      case List(label, url, hash) =>
        val target: Target =
          Target.parse(label).lest(Assembler.Error(Assembler.Fault.Format, m"$label is not a known platform"))

        Polyglot.Asset(target, url, hash.lower)

      case _ =>
        abort(Assembler.Error(Assembler.Fault.Format, m"a dispatch manifest line must be label, URL and SHA-256, separated by tabs"))
