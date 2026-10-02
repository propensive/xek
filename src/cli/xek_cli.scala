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
import denominative.*
import ethereal.*
import exoskeleton.*
import fulminate.*
import galilei.*
import gossamer.*
import parasite.*
import prepositional.*
import rudiments.*
import serpentine.*
import symbolism.*
import turbulence.*
import vacuous.*

import backstops.stackTraceBackstop
import executives.completionsExecutive
import interpreters.posixInterpreter
import threading.virtualThreading
import errorDiagnostics.emptyDiagnostics
import systems.javaBaseSystem

// The exit statuses of `xek`, beyond success: one for each `Assembler.Fault`.
object Unusable extends Status(1, t"the command line could not be acted on")
object Malformed extends Status(2, t"a file was malformed, or a download did not match its hash")
object Unreachable extends Status(3, t"a download failed")

// The flags of `xek`, declared to Exoskeleton so that they complete and appear in its help. They
// are not how the command line is read — `Command.parse` does that, since `xek` takes operands
// after its options as well as before them — but each is named for its `Command.Spec`.
object ui:
  private def flag(spec: Command.Spec): List[Text | Char] =
    if spec.short.present then List(spec.short.or(' ')) else Nil

  val Platform =
    Flag[Text](t"platform", true, Command.Platform.aliases + flag(Command.Platform), Command.Platform.description)
  val Polyglot = Flag[Unit](t"polyglot", false, Nil, Command.Polyglot.description)
  val Download = Flag[Unit](t"download", false, Nil, Command.Download.description)
  val Exclude = Flag[Text](t"exclude", true, flag(Command.Exclude), Command.Exclude.description)
  val Dispatch = Flag[Text](t"dispatch", false, Nil, Command.Dispatch.description)
  val Java = Flag[Text](t"java", false, Nil, Command.Java.description)
  val JavaMin = Flag[Text](t"java-min", false, Nil, Command.JavaMin.description)
  val Jdk = Flag[Unit](t"jdk", false, Nil, Command.Jdk.description)
  val BuildId = Flag[Text](t"build-id", false, Nil, Command.BuildId.description)
  val PublicKey = Flag[Text](t"public-key", false, Nil, Command.PublicKey.description)
  val AllowDowngrade = Flag[Unit](t"allow-downgrade", false, Nil, Command.AllowDowngrade.description)
  val Client = Flag[Text](t"client", false, Nil, Command.Client.description)
  val ClientUrl = Flag[Text](t"client-url", false, Nil, Command.ClientUrl.description)
  val ClientManifest = Flag[Text](t"client-manifest", false, Nil, Command.ClientManifest.description)
  val Help = Flag[Unit](t"help", false, flag(Command.Help), Command.Help.description)
  val Version = Flag[Unit](t"version", false, flag(Command.Version), Command.Version.description)

// `xek`, the command. It is an XEK executable itself, so this runs in a daemon, and everything
// about the invocation — its working directory, its environment — is the client's, read
// through the `Cli`, never the daemon JVM's own.
@main
def command(): Unit = cli:
  val interface: Cli = summon[Cli]
  given WorkingDirectory = interface.workingDirectory

  complete(arguments)

  execute:
    val words: List[Text] = arguments.map(_())
    val variable: Text => Optional[Text] = interface.environment.variable(_)
    val here: Path on Local = workingDirectory

    Driver.run(words, here, variable)(Out.println(_), Err.println(_)) match
      case Exit.Ok => Exit.Ok
      case Exit.Fail(1) => Unusable
      case Exit.Fail(2) => Malformed
      case Exit.Fail(_) => Unreachable

// Registers every flag, with suggestions for its value, and suggests paths for the operands: a
// JAR first, then the output's name.
private def complete(arguments: List[Argument])(using Cli, Interpreter, WorkingDirectory): Unit =
  locally:
    given (Text is Discoverable) = (_, _) =>
      Target.all.map { target => Suggestion(target.label, target.description, operand = true) }

    ui.Platform.present

  locally:
    given (Text is Discoverable) = (_, _) =>
      _root_.xek.Shell.all.map { shell => Suggestion(shell.name, shell.description, operand = true) }

    ui.Exclude.present

  locally:
    given (Text is Discoverable) = (_, _) =>
      List
        ( Suggestion(t"25", t"the current long-term-support release", operand = true),
          Suggestion(t"21", t"the previous long-term-support release", operand = true) )

    ui.Java.present
    ui.JavaMin.present

  locally:
    given (Text is Discoverable) = (operand, tab) => Pathname.complete(operand, tab)
    ui.Dispatch.present
    ui.PublicKey.present
    ui.Client.present
    ui.ClientManifest.present

  ui.BuildId.present
  ui.ClientUrl.present
  ui.Polyglot.present
  ui.Download.present
  ui.Jdk.present
  ui.AllowDowngrade.present
  ui.Help.present
  ui.Version.present

  // The first operand is the JAR, unless `--dispatch` takes its place, so only directories and
  // JARs are offered for it.
  val dispatching: Boolean = arguments.exists { argument => argument().starts(t"--dispatch") }

  operands(arguments).indexed.each: (argument, index) =>
    val paths: List[Suggestion] = Pathname.complete(argument(), argument.tab.or(Prim))

    val offered: List[Suggestion] =
      if index != Prim || dispatching then paths
      else paths.filter { path => path.incomplete || path.core.lower.ends(t".jar") }

    argument.suggest(offered + prior)

// The arguments which are operands rather than options or their values, by the rules
// `Command.parse` reads them with.
private def operands(arguments: List[Argument]): List[Argument] =
  def takesValue(word: Text): Boolean =
    val spec: Optional[Command.Spec] =
      if word.starts(t"--") && !word.contains(t"=") then Command.spec(word.skip(2))
      else if word.starts(t"-") && word.length == 2 then Command.spec(word.s.charAt(1))
      else Unset

    spec.present && spec.or(Command.Help).operand.present

  def recur(todo: List[Argument], done: List[Argument]): List[Argument] = todo.absolve match
    case Nil => done.reverse

    case head :: tail =>
      val word: Text = head()
      if word == t"--" then done.reverse + tail
      else if takesValue(word) then recur(tail.absolve match { case _ :: rest => rest; case Nil => Nil }, done)
      else if word.starts(t"-") && word.length > 1 then recur(tail, done)
      else recur(tail, head :: done)

  recur(arguments, Nil)

// Running `xek`, from its words to its exit status: shared by the daemon and by `bootstrap`,
// which differ only in where their working directory, environment and output come from.
object Driver:
  def run(words: List[Text], here: Path on Local, variable: Text => Optional[Text])
    ( out: Text => Unit, err: Text => Unit )
  :   Exit =

    def path(word: Text): Path on Local =
      val home: Text = variable(t"HOME").or(variable(t"USERPROFILE")).or(t"")

      val expanded: Text =
        if word == t"~" then home else if word.starts(t"~/") then home+word.skip(1) else word

      Files.local(jnf.Path.of(here.encode.s).nn.resolve(expanded.s).nn)

    attempt[Assembler.Error]:
      val parsed: Command.Parsed = Command.parse(words)

      if parsed.has(Command.Help) then
        out(help)
        Exit.Ok
      else if parsed.has(Command.Version) then
        out(t"xek $version (client ${xek.Client.version})")
        Exit.Ok
      else if words.nil then
        err(help)
        Exit.Fail(1)
      else
        val options: Options = Command.options(parsed, path)
        val host: Optional[Target] = Target.host(property("os.name"), property("os.arch"))
        val home: Text = variable(t"HOME").or(variable(t"USERPROFILE")).or(property("user.home"))
        val cache: Path on Local = Stubs.cache(variable, home)
        val plan: Build.Plan = Build.plan(options, host, here)
        val written: List[Path on Local] = Build.execute(plan, cache)(err(_))
        written.each { file => err(t"Wrote ${file.encode}") }
        Exit.Ok

    . absolve match
        case Attempt.Success(exit) => exit

        case Attempt.Failure(error) =>
          err(t"xek: ${error.message}")
          if error.fault == Assembler.Fault.Usage then err(t"Try `xek --help` for more information.")
          Exit.Fail(error.fault.status)

  private def property(name: String): Text = java.lang.System.getProperty(name).nn.tt

  // The version of `xek` itself, written into its JAR by the build.
  lazy val version: Text =
    val stream = Driver.getClass.getClassLoader.nn.getResourceAsStream("xek/cli.version")
    if stream == null then t"unknown" else
      try String(stream.readAllBytes().nn, "UTF-8").trim.nn.tt finally stream.close()

  // One option's line of the help, its description aligned in a column.
  private def line(spec: Command.Spec): Text =
    val short: Text = if spec.short.present then t"-${spec.short.or(' ')}, " else t"    "
    val operand: Text = if spec.operand.present then t" <${spec.operand.or(t"")}>" else t""
    val label: Text = t"  $short--${spec.name}$operand"
    label+t" "*((32 - label.length) max 1)+spec.description

  def help: Text =
    val options: List[Text] = Command.specs.map(line(_))

    val platforms: Text = Target.all.map(_.label).join(t", ")

    List
      ( t"xek — build XEK executables from JVM applications",
        t"",
        t"Usage:",
        t"  xek [options] <app.jar> [<output>]",
        t"  xek --dispatch <manifest.tsv> [options] <output>",
        t"",
        t"With no options, writes a native executable for this platform, named for the JAR",
        t"(app.jar makes app) unless <output> is given; an <output> which is a directory",
        t"receives the executable under its default name.",
        t"",
        t"  -p <platform>      a native executable for that platform; several -p options write",
        t"                     one for each, named <output>-<platform>",
        t"  --polyglot         one file which runs in sh, and in PowerShell and cmd.exe once",
        t"                     renamed to end .ps1 or .bat, unpacking the stub for its platform",
        t"  --download         like --polyglot, but downloads its stub on first run",
        t"  --dispatch <file>  downloads a complete executable, from the rows of a manifest:",
        t"                     platform, URL and SHA-256, separated by tabs",
        t"",
        t"Platforms: $platforms",
        t"",
        t"Options:" )
    . join(t"\n")+t"\n"+options.join(t"\n")+t"\n\nInstall tab-completions with: xek '{admin}' install"

// `xek` from a plain JVM, without a launcher: how the first `xek` executable is built, from
// its own JAR, by `mill xek.cli.executable`. It reads the same command line.
@main
def bootstrap(arguments: String*): Unit =
  val here: Path on Local = Files.local(jnf.Path.of("").nn)
  val words: List[Text] = List.from(arguments.map(_.tt))

  def variable(name: Text): Optional[Text] =
    val value: String | Null = java.lang.System.getenv(name.s)
    if value == null then Unset else value.tt

  val exit: Exit =
    Driver.run(words, here, variable)
      ( text => java.lang.System.out.nn.println(text.s),
        text => java.lang.System.err.nn.println(text.s) )

  exit match
    case Exit.Ok         => ()
    case Exit.Fail(code) => java.lang.System.exit(code)
