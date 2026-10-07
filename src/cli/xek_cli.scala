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
import distillate.*
import ethereal.*
import eucalyptus.*
import exoskeleton.*
import fulminate.*
import galilei.*
import gossamer.*
import parasite.*
import prepositional.*
import revolution.*
import rudiments.*
import serpentine.*
import symbolism.*
import turbulence.*
import vacuous.*

import backstops.stackTraceBackstop
import executives.completionsExecutive
import interpreters.posixInterpreter
import logging.silentLogging
import threading.virtualThreading
import errorDiagnostics.emptyDiagnostics
import systems.javaBaseSystem

// The exit statuses of `xek`, beyond success: one for each `Assembler.Fault`.
object Unusable extends Status(1, t"the command line could not be acted on")
object Malformed extends Status(2, t"a file was malformed, or a download did not match its hash")
object Unreachable extends Status(3, t"a download failed")
object Unverified extends Status(4, t"a signature did not verify, or was for another application")
object InstallFailed extends Status(5, t"the tab-completions or manpage could not be installed")

// The subcommands and flags of `xek`, declared to Exoskeleton so that they complete and appear in
// its help. They are not how the command line is read — `Command.parse` does that, since `xek`
// takes operands after its options as well as before them — but each is named for its
// `Command.Action` or `Command.Spec`.
object ui:
  private def flag(spec: Command.Spec): List[Text | Char] =
    if spec.short.present then List(spec.short.or(' ')) else Nil

  object subcommand:
    private def of(action: Command.Action): Subcommand = Subcommand(action.name, action.description)

    val Build = of(Command.Action.Build)
    val Installer = of(Command.Action.Installer)
    val Install = of(Command.Action.Install)
    val Keygen = of(Command.Action.Keygen)
    val PublicKey = of(Command.Action.PublicKeyOf)
    val Sign = of(Command.Action.Sign)
    val Statement = of(Command.Action.Statement)
    val Attach = of(Command.Action.Attach)
    val Verify = of(Command.Action.Verify)

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
  val RecoveryKey = Flag[Text](t"recovery-key", false, Nil, Command.RecoveryKey.description)
  val AppId = Flag[Text](t"app-id", false, Nil, Command.AppId.description)
  val AllowDowngrade = Flag[Unit](t"allow-downgrade", false, Nil, Command.AllowDowngrade.description)
  val Client = Flag[Text](t"client", false, Nil, Command.Client.description)
  val ClientUrl = Flag[Text](t"client-url", false, Nil, Command.ClientUrl.description)
  val ClientManifest = Flag[Text](t"client-manifest", false, Nil, Command.ClientManifest.description)
  val Url = Flag[Text](t"url", false, Nil, Command.Url.description)
  val Name = Flag[Text](t"name", false, Nil, Command.Name.description)
  val Release = Flag[Text](t"release", false, Nil, Command.Release.description)
  val Manifest = Flag[Text](t"manifest", false, Nil, Command.Manifest.description)
  val OutDir = Flag[Text](t"out", false, Nil, Command.OutDir.description)
  val In = Flag[Text](t"in", false, Nil, Command.In.description)
  val Out = Flag[Text](t"out", false, Nil, Command.Out.description)
  val Key = Flag[Text](t"key", false, Nil, Command.Key.description)
  val KeyEnv = Flag[Text](t"key-env", false, Nil, Command.KeyEnv.description)
  val Signature = Flag[Text](t"signature", false, Nil, Command.Signature.description)
  val ForeignKey = Flag[Unit](t"foreign-key", false, Nil, Command.ForeignKey.description)
  val Force = Flag[Unit](t"force", false, flag(Command.Force), Command.Force.description)
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

    Driver.run(words, here, variable, install(_))(Out.println(_), Err.println(_)) match
      case Exit.Ok => Exit.Ok
      case Exit.Fail(1) => Unusable
      case Exit.Fail(2) => Malformed
      case Exit.Fail(3) => Unreachable
      case Exit.Fail(4) => Unverified
      case Exit.Fail(_) => InstallFailed

// Installs the shell tab-completions and the manpage, as every Pyrocosm tool's `install` does.
// `Completions.ensure` needs an `Entrypoint`, which the ambient `DaemonService` is; so this
// only runs in the daemon, never under `bootstrap`. The manpage's structure is the subcommand
// and flag tree the completions register, from `service.help()`, so `man xek` cannot disagree
// with the command line; `force = true` for the completions installs them even before `xek`
// is on the `PATH`.
private def install(force: Boolean)
  ( using service: DaemonService[?], cli: Cli, environment: Environment )
  ( using WorkingDirectory )
  ( using erased Effectful )
:   Exit =

  given entrypoint: Entrypoint = service

  val prose: Text =
    t"xek builds native executables, for every platform, from a JVM application's JAR, and " +
      t"signs their releases for self-upgrade."

  given manual: Manual = Manual(prose = prose, version = safely(Driver.version.as[Semver]))

  recover:
    case error: exoskeleton.Install.Error =>
      Out.println(t"Could not install the tab-completions or manpage")
      Exit.Fail(5)

  . protect:
      Completions.ensure(force = true).each(Out.println(_))

      Manpages.install(service.help().roff, force) match
        case Manpages.InstallResult.Installed(path) =>
          Out.println(t"Installed the manpage to $path")

        case Manpages.InstallResult.AlreadyInstalled(path) =>
          Out.println(t"A manpage is already installed at $path; use --force to overwrite it")

        case Manpages.InstallResult.NoWritableLocation =>
          Out.println(t"No writable location was found for the manpage")

      Exit.Ok

// Offers the subcommands for the first word, and then the flags of the one chosen.
private def complete(arguments: List[Argument])(using Cli, Interpreter, WorkingDirectory): Unit =
  // Flags whose values are paths, registered with path completion.
  def paths(block: (Text is Discoverable) ?=> Unit): Unit =
    given (Text is Discoverable) = (operand, tab) => Pathname.complete(operand, tab)
    block

  arguments match
    case ui.subcommand.Build() :: rest =>
      completeBuild(rest)

    case ui.subcommand.Installer() :: rest =>
      paths { ui.Manifest.present; ui.OutDir.present }
      ui.Url.present
      ui.Name.present
      ui.Release.present

      operands(rest).each: argument =>
        argument.suggest(Pathname.complete(argument(), argument.tab.or(Prim)))

    case ui.subcommand.Install() :: _ =>
      ui.Force.present

    case ui.subcommand.Keygen() :: _ =>
      paths(ui.Out.present)

    case ui.subcommand.PublicKey() :: _ =>
      paths { ui.Key.present; ui.Out.present }
      ui.KeyEnv.present

    case ui.subcommand.Sign() :: _ =>
      paths { ui.Key.present; ui.In.present; ui.Out.present }
      ui.KeyEnv.present
      ui.AllowDowngrade.present
      ui.ForeignKey.present

    case ui.subcommand.Statement() :: _ =>
      paths(ui.In.present)
      ui.AllowDowngrade.present

    case ui.subcommand.Attach() :: _ =>
      paths { ui.In.present; ui.Signature.present; ui.Out.present }
      ui.AllowDowngrade.present

    case ui.subcommand.Verify() :: _ =>
      paths { ui.PublicKey.present; ui.In.present }
      ui.AppId.present

    case _ =>
      ()

  ui.Help.present
  ui.Version.present

// Registers every flag of `xek build`, with suggestions for its value, and suggests paths for the
// operands: a JAR first, then the output's name.
private def completeBuild(arguments: List[Argument])(using Cli, Interpreter, WorkingDirectory): Unit =
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
    ui.RecoveryKey.present
    ui.Client.present
    ui.ClientManifest.present

  ui.BuildId.present
  ui.AppId.present
  ui.ClientUrl.present
  ui.Polyglot.present
  ui.Download.present
  ui.Jdk.present
  ui.AllowDowngrade.present

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
  // `install` is what `xek install` does, given `--force`: it needs the daemon, so `bootstrap`
  // passes one which refuses.
  def run
    ( words:    List[Text],
      here:     Path on Local,
      variable: Text => Optional[Text],
      install:  Boolean => Exit )
    ( out: Text => Unit, err: Text => Unit )
  :   Exit =

    def path(word: Text): Path on Local =
      val home: Text = variable(t"HOME").or(variable(t"USERPROFILE")).or(t"")

      val expanded: Text =
        if word == t"~" then home else if word.starts(t"~/") then home+word.skip(1) else word

      Files.local(jnf.Path.of(here.encode.s).nn.resolve(expanded.s).nn)

    // Which subcommand a mistake belongs to, so that its own help can be suggested.
    val action: Optional[Command.Action] = words.prim.let(Command.Action.parse(_))

    attempt[Assembler.Error]:
      if words.nil then
        err(help)
        Exit.Fail(1)
      else
        val parsed: Command.Parsed = Command.parse(words)

        parsed.action.let: action =>
          if parsed.has(Command.Help) then
            out(help(action))
            Exit.Ok
          else if action == Command.Action.Install then
            install(parsed.has(Command.Force))
          else
            if action == Command.Action.Build then build(parsed, path, here, variable)(err)
            else if action == Command.Action.Installer then installer(parsed, path)(err)
            else Signer.run(action, parsed, path, variable)(out, err)

            Exit.Ok

        . or:
            if parsed.has(Command.Help) then out(help)
            else out(t"xek $version (client ${xek.Client.version})")

            Exit.Ok

    . absolve match
        case Attempt.Success(exit) => exit

        case Attempt.Failure(error) =>
          err(t"xek: ${error.message}")

          if error.fault == Assembler.Fault.Usage then
            val command: Text = action.let { action => t"xek ${action.name} --help" }.or(t"xek --help")
            err(t"Try `$command` for more information.")

          Exit.Fail(error.fault.status)

  private def build
    ( parsed:   Command.Parsed,
      path:     Text => Path on Local,
      here:     Path on Local,
      variable: Text => Optional[Text] )
    ( err: Text => Unit )
  :   Unit raises Assembler.Error =

        val options: Options = Command.options(parsed, path)
        val host: Optional[Target] = Target.host(property("os.name"), property("os.arch"))
        val home: Text = variable(t"HOME").or(variable(t"USERPROFILE")).or(property("user.home"))
        val cache: Path on Local = Stubs.cache(variable, home)
        val plan: Build.Plan = Build.plan(options, host, here)
        val written: List[Path on Local] = Build.execute(plan, cache)(err(_))
        written.each { file => err(t"Wrote ${file.encode}") }

  private def installer(parsed: Command.Parsed, path: Text => Path on Local)(err: Text => Unit)
  :   Unit raises Assembler.Error =

    val options: _root_.xek.Installer.Options = Command.installer(parsed, path)
    _root_.xek.Installer.write(options).each { file => err(t"Wrote ${file.encode}") }

  private def property(name: String): Text = java.lang.System.getProperty(name).nn.tt

  // The version of `xek` itself, written into its JAR by the build.
  lazy val version: Text =
    val stream = Driver.getClass.getClassLoader.nn.getResourceAsStream("xek/cli.version")
    if stream == null then t"unknown" else
      try String(stream.readAllBytes().nn, "UTF-8").trim.nn.tt finally stream.close()

  // One line of the help, its description aligned in a column.
  private def entry(label: Text, description: Text): Text =
    label+t" "*((32 - label.length) max 1)+description

  private def line(spec: Command.Spec): Text =
    val short: Text = if spec.short.present then t"-${spec.short.or(' ')}, " else t"    "
    val operand: Text = if spec.operand.present then t" <${spec.operand.or(t"")}>" else t""
    entry(t"  $short--${spec.name}$operand", spec.description)

  def help: Text =
    val subcommands: List[Text] =
      List(Command.Action.values*).map: (action: Command.Action) =>
        entry(t"  "+action.name, action.description)

    List
      ( t"xek — build XEK executables from JVM applications, and sign their releases",
        t"",
        t"Usage:",
        t"  xek <subcommand> [options]",
        t"",
        t"Subcommands:" )
    . join(t"\n")+t"\n"+subcommands.join(t"\n")+t"\n\n"
    + t"Run `xek <subcommand> --help` for a subcommand's options. Signing needs Java 24 or later.\n"
    + t"Install tab-completions and the manpage with: xek install"

  def help(action: Command.Action): Text =
    val options: List[Text] = (action.specs + List(Command.Help)).map(line(_))
    val usage: Text = t"Usage:\n  xek ${action.name} ${action.synopsis}"

    val body: List[Text] = action match
      case Command.Action.Build =>
        val platforms: Text = Target.all.map(_.label).join(t", ")

        List
          ( t"  xek build --dispatch <manifest.tsv> [options] <output>",
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
            t"Platforms: $platforms" )

      case Command.Action.Installer =>
        List
          ( t"",
            t"Writes install.sh, for `curl -fsSL <url> | sh`, and install.ps1, for `irm <url> | iex`,",
            t"into <directory> (by default, the working directory). Each downloads",
            t"<base-url>/<name>-<platform>[.exe] for the platform it runs on and checks it against",
            t"the digest embedded for that platform. The executables are named <name>-<platform>",
            t"or <name>-<platform>.exe, as `xek build -p` writes them; with --manifest, the",
            t"digests are read from its lines of platform and SHA-256 instead, and --name is needed." )

      case Command.Action.Install =>
        List
          ( t"",
            t"Writes the tab-completions for each shell installed — zsh, bash, fish and PowerShell —",
            t"where that shell looks for them, and the manpage to ~/.local/share/man/man1 (or",
            t"$$XDG_DATA_HOME/man/man1), which `man` searches by default. An installed manpage is",
            t"left alone unless --force is given." )

      case _ =>
        List(t"", t"${action.description.s.capitalize.nn.tt}. Needs Java 24 or later.")

    (t"xek ${action.name} — ${action.description}" :: t"" :: usage :: body)
    . join(t"\n")+t"\n\nOptions:\n"+options.join(t"\n")

// `xek` from a plain JVM, without a launcher: how the first `xek` executable is built, from
// its own JAR, by `mill xek.cli.executable`. It reads the same command line.
@main
def bootstrap(arguments: String*): Unit =
  val here: Path on Local = Files.local(jnf.Path.of("").nn)
  val words: List[Text] = List.from(arguments.map(_.tt))

  def variable(name: Text): Optional[Text] =
    val value: String | Null = java.lang.System.getenv(name.s)
    if value == null then Unset else value.tt

  def err(text: Text): Unit = java.lang.System.err.nn.println(text.s)

  def install(force: Boolean): Exit =
    err(t"xek: install needs the xek command, which is not what this is: build it with `make xek`")
    Exit.Fail(1)

  val exit: Exit =
    Driver.run(words, here, variable, install(_))(text => java.lang.System.out.nn.println(text.s), err(_))

  exit match
    case Exit.Ok         => ()
    case Exit.Fail(code) => java.lang.System.exit(code)
