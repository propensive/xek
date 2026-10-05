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
import denominative.*
import fulminate.*
import galilei.*
import gossamer.*
import prepositional.*
import rudiments.*
import serpentine.*
import symbolism.*
import vacuous.*

import denominative.dysasymptotics.linearSize

// The `xek` command line, as a pure function from its words to a subcommand and its options. It
// lives here, not beside the daemon, so that it can be tested without one; `xek.cli` declares the
// same subcommands and flags to Exoskeleton, for tab-completion.
//
// Every command line begins with a subcommand — `build`, or one of those which sign a release —
// and each subcommand accepts only its own options; only `--help` and `--version` may stand alone.
// After the subcommand the syntax is the POSIX convention: options and operands in any order, an option's value
// either the next word or joined by `=` (`--platform=linux-x64`, `-p linux-x64`, `-plinux-x64`),
// repeatable options accumulating, comma-separated lists accepted wherever a list is, and `--`
// ending the options.
object Command:
  case class Spec
    ( name:        Text,
      short:       Optional[Char],
      operand:     Optional[Text],
      description: Text,
      aliases:     List[Text] = Nil )

  val Platform =
    Spec(t"platform", 'p', t"platform", t"build for this platform; repeatable, or comma-separated", List(t"platforms"))

  val Polyglot = Spec(t"polyglot", Unset, Unset, t"build one polyglot file for sh, PowerShell and cmd.exe, carrying every stub")
  val Download = Spec(t"download", Unset, Unset, t"build a polyglot file which downloads its stub on first run")
  val Exclude = Spec(t"exclude", 'x', t"shell", t"leave a shell's section out of a polyglot file: sh, pwsh or bat")
  val Dispatch = Spec(t"dispatch", Unset, t"manifest", t"build a polyglot file which downloads complete executables")
  val Java = Spec(t"java", Unset, t"version", t"the Java version to download when none is installed")
  val JavaMin = Spec(t"java-min", Unset, t"version", t"the oldest Java version the application runs on")
  val Jdk = Spec(t"jdk", Unset, Unset, t"download a full JDK rather than a JRE")
  val BuildId = Spec(t"build-id", Unset, t"number", t"the build number self-upgrades are ordered by")
  val PublicKey = Spec(t"public-key", Unset, t"file", t"the release key self-upgrades are verified against")
  val RecoveryKey = Spec(t"recovery-key", Unset, t"file", t"a second key, kept offline, which may sign any self-upgrade")
  val AppId = Spec(t"app-id", Unset, t"identifier", t"the application a self-upgrade must be for, like propensive/fume")
  val AllowDowngrade = Spec(t"allow-downgrade", Unset, Unset, t"accept a self-upgrade to a lower build number")
  val In = Spec(t"in", Unset, t"executable", t"the executable to read")
  val Out = Spec(t"out", Unset, t"file", t"the file to write")
  val Prefix = Spec(t"out", Unset, t"prefix", t"write the key pair as <prefix>.seed and <prefix>.pub")
  val Key = Spec(t"key", Unset, t"seed-file", t"the 32-byte seed of the key to sign with")
  val KeyEnv = Spec(t"key-env", Unset, t"variable", t"an environment variable holding that seed as 64 hex digits")
  val Signature = Spec(t"signature", Unset, t"file", t"a signature made elsewhere, over the statement")
  val ForeignKey = Spec(t"foreign-key", Unset, Unset, t"sign with a key the executable's record does not carry")
  val VerifyKey = Spec(t"public-key", Unset, t"file", t"the public key the signature must verify under")
  val VerifyApp = Spec(t"app-id", Unset, t"identifier", t"the application the executable must be for")
  val Client = Spec(t"client", Unset, t"directory", t"take unverified stubs from a local directory")
  val ClientUrl = Spec(t"client-url", Unset, t"url", t"download stubs from this URL instead")
  val ClientManifest = Spec(t"client-manifest", Unset, t"file", t"verify downloaded stubs against this manifest")
  val Help = Spec(t"help", 'h', Unset, t"show this help")
  val Version = Spec(t"version", 'v', Unset, t"show the version")

  // A subcommand, the first word of every command line, with the options it accepts.
  enum Action(val name: Text, val synopsis: Text, val description: Text, val specs: List[Spec]):
    case Build
    extends Action
      ( t"build",
        t"[options] <app.jar> [<output>]",
        t"build an executable from an application's JAR",
        List
          ( Platform, Polyglot, Download, Exclude, Dispatch, Java, JavaMin, Jdk, BuildId, PublicKey,
            RecoveryKey, AppId, AllowDowngrade, Client, ClientUrl, ClientManifest ) )

    case Keygen
    extends Action(t"keygen", t"--out <prefix>", t"generate a key pair for signing releases", List(Prefix))

    case PublicKeyOf
    extends Action
      ( t"public-key",
        t"(--key <seed-file> | --key-env <variable>) --out <file>",
        t"derive a public key from its seed",
        List(Key, KeyEnv, Out) )

    case Sign
    extends Action
      ( t"sign",
        t"(--key <seed-file> | --key-env <variable>) --in <executable> --out <file>",
        t"sign a release",
        List(Key, KeyEnv, In, Out, AllowDowngrade, ForeignKey) )

    case Statement
    extends Action
      ( t"statement",
        t"--in <executable>",
        t"print the statement an external signer is asked to sign",
        List(In, AllowDowngrade) )

    case Attach
    extends Action
      ( t"attach",
        t"--in <executable> --signature <file> --out <file>",
        t"write a signature made elsewhere into a release",
        List(In, Signature, Out, AllowDowngrade) )

    case Verify
    extends Action
      ( t"verify",
        t"--public-key <file> [--app-id <identifier>] --in <executable>",
        t"check a release's signature, and print its build id",
        List(VerifyKey, VerifyApp, In) )

  object Action:
    def parse(name: Text): Optional[Action] = values.find(_.name == name).getOrElse(Unset)

  // Every option, for completion, which reads a command line before knowing whether it is whole.
  lazy val specs: List[Spec] = (List(Action.values*).flatMap(_.specs) + List(Help, Version)).distinct

  // The words of a command line, sorted: the subcommand, each option given, with its value if it
  // takes one, in order, and the operands. Only `--help` and `--version` come without a
  // subcommand.
  case class Parsed(action: Optional[Action], options: List[(Spec, Text)], operands: List[Text]):
    def has(spec: Spec): Boolean = options.exists(_(0) == spec)

    def values(spec: Spec): List[Text] =
      options.filter(_(0) == spec).map(_(1)).flatMap(_.cut(t",")).map(_.trim).filter(_ != t"")

    def value(spec: Spec): Optional[Text] = options.filter(_(0) == spec).map(_(1)).reverse.prim

  private def usage(message: Message)(using Diagnostics): Assembler.Error =
    Assembler.Error(Assembler.Fault.Usage, message)

  def spec(name: Text, specs: List[Spec] = specs): Optional[Spec] =
    specs.filter { spec => spec.name == name || spec.aliases.has(name) }.prim

  def spec(short: Char): Optional[Spec] = specs.filter(_.short == short).prim

  def parse(words: List[Text]): Parsed raises Assembler.Error =
    words.prim.let(Action.parse(_)).lay(standalone(words)): action =>
      parse(action, words.skip(1))

  // A command line with no subcommand, which may only ask for help or the version. Anything else
  // is a mistake, and most likely the command line of an `xek` from before subcommands, which
  // built an executable from the JAR it was given: so that is suggested.
  private def standalone(words: List[Text]): Parsed raises Assembler.Error =
    val parsed: Parsed = safely(parse(Unset, words, List(Help, Version))).or(Parsed(Unset, Nil, words))

    if !words.nil && parsed.operands.nil && parsed.options.size == words.size then parsed else
      val names: Text = List(Action.values*).map(_.name).join(t", ")
      val first: Text = words.prim.or(t"")

      if words.exists(_.ends(t".jar")) then
        val line: Text = words.join(t" ")
        abort(usage(m"xek needs a subcommand first; to build an executable, use: xek build $line"))
      else if words.nil || first.starts(t"-")
      then abort(usage(m"xek needs a subcommand first: one of $names"))
      else abort(usage(m"there is no subcommand $first; choose from $names"))

  def parse(action: Action, words: List[Text]): Parsed raises Assembler.Error =
    parse(action, words, action.specs :+ Help)

  private def parse(action: Optional[Action], words: List[Text], accepted: List[Spec])
  :   Parsed raises Assembler.Error =

    def unknown(written: Text)(using Diagnostics): Assembler.Error =
      action.let { action => usage(m"xek ${action.name} has no option $written") }.or:
        usage(m"there is no option $written")

    def recur(words: List[Text], options: List[(Spec, Text)], operands: List[Text]): Parsed =
      words.absolve match
        case Nil =>
          Parsed(action, options.reverse, operands.reverse)

        case t"--" :: rest =>
          Parsed(action, options.reverse, operands.reverse + rest)

        case word :: rest if word.starts(t"--") =>
          val body: Text = word.skip(2)
          val (name, joined) = split(body)
          val found: Spec = spec(name, accepted).lest(unknown(t"--$name"))
          option(found, t"--$name", joined, rest, options, operands)

        case word :: rest if word.starts(t"-") && word.length > 1 =>
          val short: Char = word.s.charAt(1)
          val found: Spec = accepted.filter(_.short == short).prim.lest(unknown(t"-$short"))
          val remainder: Text = word.skip(2)
          val joined: Optional[Text] = if remainder == t"" then Unset else remainder.s.stripPrefix("=").tt
          option(found, t"-$short", joined, rest, options, operands)

        case word :: rest =>
          recur(rest, options, word :: operands)

    def option
      ( found:    Spec,
        written:  Text,
        joined:   Optional[Text],
        rest:     List[Text],
        options:  List[(Spec, Text)],
        operands: List[Text] )
    :   Parsed =

      if found.operand.absent then
        if joined.present then abort(usage(m"$written takes no value"))
        recur(rest, (found, t"") :: options, operands)
      else if joined.present then recur(rest, (found, joined.or(t"")) :: options, operands)
      else rest.absolve match
        case value :: rest => recur(rest, (found, value) :: options, operands)
        case Nil           => abort(usage(m"$written needs a value"))

    recur(words, Nil, Nil)

  private def split(body: Text): (Text, Optional[Text]) =
    val index: Int = body.s.indexOf('=')
    if index < 0 then (body, Unset) else (body.keep(index), body.skip(index + 1))

  // `path` resolves a word to a path, relative to the invocation's working directory.
  def options(parsed: Parsed, path: Text => Path on Local): Options raises Assembler.Error =
    val targets: List[Target] = parsed.values(Platform).map(target(_))
    val exclude: List[Shell] = parsed.values(Exclude).map(shell(_))
    val dispatch: Optional[Path on Local] = parsed.value(Dispatch).let(path)

    val limit: Int = if dispatch.present then 1 else 2

    if parsed.operands.size > limit then
      val extra: Text = parsed.operands.skip(limit).join(t" ")
      abort(usage(m"there are more arguments than expected: $extra"))

    val jar: Optional[Path on Local] =
      if dispatch.present then Unset else parsed.operands.prim.let(path)

    val output: Optional[Path on Local] =
      if dispatch.present then parsed.operands.prim.let(path)
      else parsed.operands.skip(1).prim.let(path)

    def key(spec: Spec): Optional[Data] =
      parsed.value(spec).let(path).let { key => Array.unsafeFrozen(Files.read(key)) }

    if parsed.has(PublicKey) && !parsed.has(AppId)
    then abort(usage(m"--public-key needs --app-id, or the executable could never upgrade"))

    if parsed.has(RecoveryKey) && !parsed.has(PublicKey)
    then abort(usage(m"--recovery-key needs --public-key"))

    val record: Record =
      Record
        ( buildId        = parsed.value(BuildId).let(number(BuildId, _)).or(0L),
          javaMinimum    = parsed.value(JavaMin).let(version(JavaMin, _)).or(Record.javaMinimum),
          javaPreferred  = parsed.value(Java).let(version(Java, _)).or(Record.javaPreferred),
          jdk            = parsed.has(Jdk),
          allowDowngrade = parsed.has(AllowDowngrade),
          appId          = parsed.value(AppId),
          releaseKey     = key(PublicKey),
          recoveryKey    = key(RecoveryKey) )

    Options
      ( jar      = jar,
        output   = output,
        targets  = targets,
        polyglot = parsed.has(Polyglot),
        download = parsed.has(Download),
        exclude  = exclude,
        dispatch = dispatch,
        record   = record,
        source   = source(parsed, path) )

  // Stubs from a local directory; or from a URL and a manifest, either of which may be given
  // alone to replace the published release's.
  private def source(parsed: Parsed, path: Text => Path on Local): Stubs.Source raises Assembler.Error =
    val directory: Optional[Text] = parsed.value(Client)
    val url: Optional[Text] = parsed.value(ClientUrl)
    val manifest: Optional[Text] = parsed.value(ClientManifest)

    if directory.present then
      if url.present || manifest.present
      then abort(usage(m"--client cannot be combined with --client-url or --client-manifest"))

      Stubs.Source.Directory(path(directory.or(t"")))

    else if url.present || manifest.present then
      val hashes: Map[Text, Text] =
        if manifest.absent then xek.Client.hashes
        else Stubs.manifest(String(Files.read(path(manifest.or(t""))), "UTF-8").tt)

      Stubs.Source.Remote(url.or(xek.Client.baseUrl), hashes)

    else xek.Client.standard

  private def target(label: Text): Target raises Assembler.Error =
    Target.parse(label).lest:
      usage(m"$label is not a platform; choose from ${Target.all.map(_.label).join(t", ")}")

  private def shell(name: Text): Shell raises Assembler.Error =
    Shell.parse(name).lest(usage(m"$name is not a shell; choose from sh, pwsh or bat"))

  private def version(spec: Spec, text: Text): Int raises Assembler.Error =
    val value: Long = number(spec, text)
    if value < 8 || value > 0xffff then abort(usage(m"--${spec.name} must be a Java version, like 25"))
    value.toInt

  private def number(spec: Spec, text: Text): Long raises Assembler.Error =
    try java.lang.Long.parseLong(text.s) catch case error: NumberFormatException =>
      abort(usage(m"--${spec.name} must be a whole number, not $text"))
