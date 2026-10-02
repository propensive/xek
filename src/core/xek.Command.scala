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

// The `xek` command line, as a pure function from its words to `Options`. It lives here, not
// beside the daemon, so that it can be tested without one; `xek.cli` declares the same flags to
// Exoskeleton, for tab-completion.
//
// The syntax is the POSIX convention: options and operands in any order, an option's value
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
  val PublicKey = Spec(t"public-key", Unset, t"file", t"the public key self-upgrades are verified against")
  val AllowDowngrade = Spec(t"allow-downgrade", Unset, Unset, t"accept a self-upgrade to a lower build number")
  val Client = Spec(t"client", Unset, t"directory", t"take unverified stubs from a local directory")
  val ClientUrl = Spec(t"client-url", Unset, t"url", t"download stubs from this URL instead")
  val ClientManifest = Spec(t"client-manifest", Unset, t"file", t"verify downloaded stubs against this manifest")
  val Help = Spec(t"help", 'h', Unset, t"show this help")
  val Version = Spec(t"version", 'v', Unset, t"show the version")

  val specs: List[Spec] =
    List
      ( Platform, Polyglot, Download, Exclude, Dispatch, Java, JavaMin, Jdk, BuildId, PublicKey,
        AllowDowngrade, Client, ClientUrl, ClientManifest, Help, Version )

  // The words of a command line, sorted: each option given, with its value if it takes one, in
  // order, and the operands.
  case class Parsed(options: List[(Spec, Text)], operands: List[Text]):
    def has(spec: Spec): Boolean = options.exists(_(0) == spec)

    def values(spec: Spec): List[Text] =
      options.filter(_(0) == spec).map(_(1)).flatMap(_.cut(t",")).map(_.trim).filter(_ != t"")

    def value(spec: Spec): Optional[Text] = options.filter(_(0) == spec).map(_(1)).reverse.prim

  private def usage(message: Message)(using Diagnostics): Assembler.Error =
    Assembler.Error(Assembler.Fault.Usage, message)

  def spec(name: Text): Optional[Spec] = specs.filter { spec => spec.name == name || spec.aliases.has(name) }.prim

  def spec(short: Char): Optional[Spec] = specs.filter(_.short == short).prim

  def parse(words: List[Text]): Parsed raises Assembler.Error =
    def recur(words: List[Text], options: List[(Spec, Text)], operands: List[Text]): Parsed =
      words.absolve match
        case Nil =>
          Parsed(options.reverse, operands.reverse)

        case t"--" :: rest =>
          Parsed(options.reverse, operands.reverse + rest)

        case word :: rest if word.starts(t"--") =>
          val body: Text = word.skip(2)
          val (name, joined) = split(body)
          val found: Spec = spec(name).lest(usage(m"there is no option --$name"))
          option(found, t"--$name", joined, rest, options, operands)

        case word :: rest if word.starts(t"-") && word.length > 1 =>
          val short: Char = word.s.charAt(1)
          val found: Spec = spec(short).lest(usage(m"there is no option -$short"))
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

    val publicKey: Optional[Data] =
      parsed.value(PublicKey).let(path).let { key => Array.unsafeFrozen(Files.read(key)) }

    val record: Record =
      Record
        ( buildId        = parsed.value(BuildId).let(number(BuildId, _)).or(0L),
          javaMinimum    = parsed.value(JavaMin).let(version(JavaMin, _)).or(Record.javaMinimum),
          javaPreferred  = parsed.value(Java).let(version(Java, _)).or(Record.javaPreferred),
          jdk            = parsed.has(Jdk),
          allowDowngrade = parsed.has(AllowDowngrade),
          publicKey      = publicKey )

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
