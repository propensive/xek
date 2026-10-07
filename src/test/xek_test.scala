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
import java.util.jar as juj
import java.util.zip as juz

import ambience.*
import anticipation.*
import aperture.*
import contingency.*
import denominative.*
import digression.*
import distillate.*
import eucalyptus.*
import fulminate.*
import galilei.*
import gossamer.*
import guillotine.*
import hieroglyph.*
import inimitable.*
import prepositional.*
import probably.*
import rudiments.*
import serpentine.*
import spectacular.*
import symbolism.*
import turbulence.*
import vacuous.*

import systems.javaBaseSystem
// Reported as unused, but it is the environment — and so the `PATH` — the `sh"…"` commands below
// are run with: without it, none of them can be found.
import environments.javaBaseEnvironment
import temporaryDirectories.systemTemporaryDirectory
import workingDirectories.javaBaseWorkingDirectory
import logging.silentLogging
import strategies.throwUnsafely
import codepages.utf8Codepage
import errorDiagnostics.stackTracesDiagnostics
import filesystemOptions.deleteRecursively
import filesystemBackends.javaBaseFilesystem

// The suite drives `core` — the builder `xek` and `Packager` both run — directly, and runs what
// it builds: native executables around fake stubs (shell scripts which echo their platform), and
// polyglot files under `sh`, and under PowerShell where it is installed. Suites that need Docker
// step aside when it is absent.
object Tests extends Suite(m"XEK tests"):
  def run(): Unit =
    val tempDirs = scala.collection.mutable.ListBuffer.empty[Path on Linux]

    def tempDir(): Path on Linux =
      val dir: Path on Linux = temporaryDirectory[Path on Linux]/Uuid().show
      dir.create[Directory]()
      tempDirs += dir
      dir

    try body(tempDir) finally tempDirs.each { dir => safely(dir.delete()) }

  private def body(tempDir: () => Path on Linux): Unit =
    val hostLabel: Text = sh"uname -s".exec[Text]().trim match
      case t"Darwin" => sh"uname -m".exec[Text]().trim match
        case t"arm64" | t"aarch64" => t"macos-arm64"
        case _                     => t"macos-x64"
      case _ => sh"uname -m".exec[Text]().trim match
        case t"aarch64" | t"arm64" => t"linux-arm64"
        case _                     => t"linux-x64"

    val host: Target = Target.parse(hostLabel).or(Target.LinuxX64)
    val unix: List[Target] = Target.all.filter(!_.windows)

    def local(path: Path on Linux): Path on Local = Files.path(path.encode)
    def bytes(path: Path on Linux): scala.Array[Byte] = jnf.Files.readAllBytes(jnf.Path.of(path.encode.s)).nn
    def sha(path: Path on Linux): Text = Files.sha256(bytes(path))

    def writeText(path: Path on Linux, text: Text): Unit =
      path.open[File](Write, OpenFlag.Create, OpenFlag.Truncate)(file.write(Chain(text.in[Data])))

    // Path with a computed Text segment (avoids the Admissible ambiguity of `dir / textValue`).
    def sub(dir: Path on Linux, name: Text): Path on Linux =
      unsafely(t"${dir.encode}/$name".as[Path on Linux])

    def stubOf(dir: Path on Linux, target: Target): Path on Linux = sub(dir, target.stub)

    // A directory of fake "stubs": shell scripts that echo and exit before the appended record
    // and JAR are ever reached, so the whole chain runs with no daemon and no real client.
    def fakeClient(): Path on Linux =
      val dir = tempDir()
      Target.all.each: target =>
        val stub = stubOf(dir, target)
        val content: Text = t"#!/bin/sh\necho ran-"+target.label+t"\nexit 0\n"
        writeText(stub, content)
        sh"chmod +x ${stub.encode}".exec[Exit]()
      dir

    // A real JAR, with a manifest naming a main class, as the builder insists on; `entries`
    // extra empty entries make it as large a ZIP as a test needs.
    def fakeJar(dir: Path on Linux, entries: Int = 0): Path on Linux =
      val jar = dir/t"app.jar"
      val manifest = juj.Manifest()
      manifest.getMainAttributes.nn.put(juj.Attributes.Name.MANIFEST_VERSION, "1.0")
      manifest.getMainAttributes.nn.put(juj.Attributes.Name.MAIN_CLASS, "app.Main")
      val stream = juj.JarOutputStream(jnf.Files.newOutputStream(jnf.Path.of(jar.encode.s)).nn, manifest)

      try
        var index = 0
        while index < entries do
          stream.putNextEntry(juz.ZipEntry(s"e$index"))
          stream.closeEntry()
          index += 1
      finally stream.close()

      jar

    def cat(dir: Path on Linux, parts: Path on Linux*): Path on Linux =
      val out = dir/t"cat"
      val names: Text = parts.map { part => t"'${part.encode}'" }.to(List).join(t" ")
      sh"sh -c ${t"cat $names > '${out.encode}'"}".exec[Exit]()
      out

    def record(dir: Path on Linux, record: Record = Record()): Path on Linux =
      val out = dir/t"rec"
      jnf.Files.write(jnf.Path.of(out.encode.s), Array.unsafeJvm(record.data))
      out

    val cache: Path on Local = local(tempDir())

    def build(options: Options, dir: Path on Linux): List[Path on Local] =
      Build.execute(Build.plan(options, host, local(dir)), cache)(_ => ())

    def plan(options: Options): Build.Plan = Build.plan(options, host, local(tempDir()))

    def parse(words: Text*): Options =
      val here: Path on Linux = tempDir()
      xek.Command.options(xek.Command.parse(t"build" :: words.to(List)), word => Files.child(local(here), word))

    def fault(block: => Any): Optional[Assembler.Fault] =
      safely(capture[Assembler.Error](block).fault)

    // Read from the jar this suite was loaded from: the host client's classloader does
    // not expose the jar's resources by name.
    def resource(name: String): Text =
      val location = Tests.getClass.nn.getProtectionDomain.nn.getCodeSource.nn.getLocation.nn
      val zip = java.util.zip.ZipFile(java.io.File(location.toURI.nn))
      try
        val entry = zip.getEntry(name).nn
        String(zip.getInputStream(entry).nn.readAllBytes().nn, "UTF-8").tt
      finally zip.close()

    def hex(bytes: scala.Array[Byte]): Text =
      val builder = StringBuilder()
      var index = 0

      while index < bytes.length do
        builder.append(String.format("%02x", Integer.valueOf(bytes(index) & 0xff)))
        index += 1

      builder.toString.tt

    def le(data: scala.Array[Byte]): java.nio.ByteBuffer =
      java.nio.ByteBuffer.wrap(data).nn.order(java.nio.ByteOrder.LITTLE_ENDIAN).nn

    def keyOf(fill: Int => Int): Data = Array.unsafeFrozen(scala.Array.tabulate[Byte](1312)(fill(_).toByte))

    suite(m"record"):
      test(m"is exactly 5108 bytes and starts with the v4 magic"):
        val data = Array.unsafeJvm(Record(buildId = 42).data)
        (data.length, String(data, 0, 8, "ISO-8859-1").tt)
      .assert(_ == (5108, t"ETHRCFG\u0004"))

      test(m"lays out its fields little-endian, at the offsets the spec gives"):
        val data = Array.unsafeJvm(Record(0x0102030405060708L, 21, 25, true, true).data)
        val buffer = le(data)
        (buffer.getLong(8), buffer.getShort(16).toInt, buffer.getShort(18).toInt, data(20).toInt, data(21).toInt)
      .assert(_ == (0x0102030405060708L, 21, 25, 1, 1))

      test(m"holds the SHA3-256 of the application id at offset 32"):
        val data = Array.unsafeJvm(Record(appId = t"propensive/fume").data)
        hex(data.slice(32, 64))
      .assert(_ == hex(java.security.MessageDigest.getInstance("SHA3-256").nn.digest("propensive/fume".getBytes("UTF-8")).nn))

      test(m"places the release key at offset 64 and the recovery key at 1376"):
        val record = Record(appId = t"a/b", releaseKey = keyOf(_ => 7), recoveryKey = keyOf(_ => 9))
        val data = Array.unsafeJvm(record.data)
        (data(31).toInt, data(64).toInt, data(1375).toInt, data(1376).toInt, data(2687).toInt, data(2688).toInt)
      .assert(_ == (0, 7, 7, 9, 9, 0))

      test(m"writes, byte for byte, the record in spec/fixtures that the client reads back"):
        val record =
          Record
            ( 0x0102030405060708L, 21, 25, true, true, t"propensive/fume", keyOf(_%251),
              keyOf(i => (i*7)%251) )

        hex(Array.unsafeJvm(record.data))
      .assert(_ == resource("fixtures/ethrcfg-v4.hex").s.filter(!_.isWhitespace).tt)

      test(m"refuses a key of the wrong size"):
        fault(Record(appId = t"a/b", releaseKey = Array.unsafeFrozen(scala.Array.fill[Byte](10)(1))).data)
      .assert(_ == Assembler.Fault.Format)

      test(m"refuses a release key without an application id"):
        fault(Record(releaseKey = keyOf(_ => 1)).data)
      .assert(_ == Assembler.Fault.Usage)

      test(m"refuses a recovery key without a release key"):
        fault(Record(appId = t"a/b", recoveryKey = keyOf(_ => 1)).data)
      .assert(_ == Assembler.Fault.Usage)

    def unhex(text: Text): scala.Array[Byte] = Signer.unhex(text.s.filter(!_.isWhitespace).tt).or(scala.Array())

    // The vector in `spec/fixtures/ml-dsa-44.tsv`, which the client's own tests check too.
    def vector(name: Text): scala.Array[Byte] =
      val row: Text = resource("fixtures/ml-dsa-44.tsv").cut(t"\n").filter(_.starts(t"$name\t")).prim.or(t"")
      unhex(row.skip(name.length + 1))

    // `xek`'s own subcommands, run in-process as the daemon runs them, against a directory.
    def invoke(dir: Path on Linux, words: Text*): (List[Text], List[Text]) =
      val out = scala.collection.mutable.ListBuffer[Text]()
      val err = scala.collection.mutable.ListBuffer[Text]()
      val parsed: xek.Command.Parsed = xek.Command.parse(words.to(List))
      val path: Text => Path on Local = word => Files.child(local(dir), word)
      def variable(name: Text): Optional[Text] = if name == t"SEED" then t"00"*32 else Unset
      parsed.action.let(Signer.run(_, parsed, path, variable)(out += _, err += _))
      (out.to(List), err.to(List))

    // An executable built by `xek build` with both keys and an application id, and signed.
    def keyed(dir: Path on Linux): Path on Linux =
      val client = fakeClient(); val jar = fakeJar(dir)
      invoke(dir, t"keygen", t"--out", t"release")
      invoke(dir, t"keygen", t"--out", t"recovery")
      val words =
        List
          ( t"build", jar.encode, (dir/t"tool").encode, t"--client", client.encode, t"--build-id", t"7",
            t"--public-key", (dir/t"release.pub").encode, t"--recovery-key", (dir/t"recovery.pub").encode,
            t"--app-id", t"propensive/fume" )

      build(xek.Command.options(xek.Command.parse(words), Files.path(_)), dir)
      invoke(dir, t"sign", t"--key", t"release.seed", t"--in", t"tool", t"--out", t"signed")
      dir/t"signed"

    // ML-DSA is the JDK's from Java 24; on an older JDK the signing suites step aside.
    if Signer.available then
      suite(m"signing"):
        test(m"derives the public key in spec/fixtures from its seed, as the client does"):
          Signer.hex(Signer.publicKey(vector(t"seed")))
        .assert(_ == Signer.hex(vector(t"public-key")))

        test(m"takes the statement in spec/fixtures of the record there, as the client does"):
          Signer.hex(Signer.statement(unhex(resource("fixtures/ethrcfg-v4.hex")), 0))
        .assert(_ == Signer.hex(vector(t"statement")))

        test(m"verifies the client's signature in spec/fixtures"):
          Signer.verifies(vector(t"statement"), vector(t"signature"), vector(t"public-key"))
        .assert(_ == true)

        test(m"makes a signature which verifies, and no other"):
          val statement: scala.Array[Byte] = vector(t"statement")
          val signature: scala.Array[Byte] = Signer.sign(statement, vector(t"seed"))
          val other: scala.Array[Byte] = Signer.publicKey(scala.Array.fill[Byte](32)(9))
          val altered: scala.Array[Byte] = statement.clone()
          altered(39) = (altered(39) ^ 1).toByte
          ( Signer.verifies(statement, signature, vector(t"public-key")),
            Signer.verifies(statement, signature, other),
            Signer.verifies(altered, signature, vector(t"public-key")) )
        .assert(_ == (true, false, false))

        test(m"keygen writes a secret seed and its public key, and will not overwrite them"):
          val dir = tempDir()
          invoke(dir, t"keygen", t"--out", t"key")
          val mode = jnf.Files.getPosixFilePermissions(jnf.Path.of((dir/t"key.seed").encode.s)).nn.toString
          val again = fault(invoke(dir, t"keygen", t"--out", t"key"))
          val derived = Signer.hex(Signer.publicKey(bytes(dir/t"key.seed")))
          (mode, derived == Signer.hex(bytes(dir/t"key.pub")), again)
        .assert(_ == (t"[OWNER_READ, OWNER_WRITE]", true, Assembler.Fault.Usage))

        test(m"reads a seed from an environment variable"):
          val dir = tempDir()
          invoke(dir, t"public-key", t"--key-env", t"SEED", t"--out", t"key.pub")
          Signer.hex(bytes(dir/t"key.pub"))
        .assert(_ == Signer.hex(Signer.publicKey(new scala.Array[Byte](32))))

        test(m"an executable built with both keys and an application id, once signed, verifies"):
          val dir = tempDir(); keyed(dir)
          invoke(dir, t"verify", t"--public-key", t"release.pub", t"--app-id", t"propensive/fume", t"--in", t"signed")(0)
        .assert(_ == List(t"7"))

        test(m"it does not verify for another application"):
          val dir = tempDir(); keyed(dir)
          fault(invoke(dir, t"verify", t"--public-key", t"release.pub", t"--app-id", t"propensive/flame", t"--in", t"signed"))
        .assert(_ == Assembler.Fault.Verification)

        test(m"it does not verify under the recovery key, which did not sign it"):
          val dir = tempDir(); keyed(dir)
          fault(invoke(dir, t"verify", t"--public-key", t"recovery.pub", t"--in", t"signed"))
        .assert(_ == Assembler.Fault.Verification)

        test(m"a change to the executable after signing does not verify"):
          val dir = tempDir(); val signed = keyed(dir)
          val data = bytes(signed)
          data(3) = (data(3) ^ 1).toByte
          jnf.Files.write(jnf.Path.of(signed.encode.s), data)
          fault(invoke(dir, t"verify", t"--public-key", t"release.pub", t"--in", t"signed"))
        .assert(_ == Assembler.Fault.Verification)

        test(m"the recovery key may sign, but a key the record does not carry needs --foreign-key"):
          val dir = tempDir(); keyed(dir)
          invoke(dir, t"keygen", t"--out", t"other")
          invoke(dir, t"sign", t"--key", t"recovery.seed", t"--in", t"tool", t"--out", t"recovered")
          val recovered = invoke(dir, t"verify", t"--public-key", t"recovery.pub", t"--in", t"recovered")(0)
          val refused = fault(invoke(dir, t"sign", t"--key", t"other.seed", t"--in", t"tool", t"--out", t"x"))
          invoke(dir, t"sign", t"--key", t"other.seed", t"--in", t"tool", t"--out", t"x", t"--foreign-key")
          val foreign = invoke(dir, t"verify", t"--public-key", t"other.pub", t"--in", t"x")(0)
          (recovered, refused, foreign)
        .assert(_ == (List(t"7"), Assembler.Fault.Usage, List(t"7")))

        test(m"a signature made elsewhere over the statement can be attached"):
          val dir = tempDir(); keyed(dir)
          val statement = unhex(invoke(dir, t"statement", t"--in", t"tool")(0).prim.or(t""))
          val signature = Signer.sign(statement, bytes(dir/t"release.seed"))
          jnf.Files.write(jnf.Path.of((dir/t"sig").encode.s), signature)
          invoke(dir, t"attach", t"--in", t"tool", t"--signature", t"sig", t"--out", t"attached")
          invoke(dir, t"verify", t"--public-key", t"release.pub", t"--in", t"attached")(0)
        .assert(_ == List(t"7"))

    // The schema in `spec/ethereal-launcher.tel` is the contract, and `src/client/src/bintel.rs`
    // pins the hash of its base as the constant from which the client derives every signature
    // it writes and compares on the wire. Derive the one from the other, so that neither can
    // change without the other: a schema edit that forgets the constant, or a constant edited
    // by hand, fails here rather than at the first document. The base's hash is taken with the
    // schema's layers removed (BinTEL §8.1), so adding a layer must leave it unchanged.
    suite(m"Launcher protocol"):
      import stratiform.*
      val schemaText: Text = resource("ethereal-launcher.tel")
      val rust: Text = resource("bintel.rs")

      def hex(data: Data): Text =
        val builder = StringBuilder()
        var i = 0
        while i < data.length do
          builder.append(String.format("%02x", Integer.valueOf(data.readable(i) & 0xff)))
          i += 1
        builder.toString.tt

      def derived: Text = hex(SchemaSignature.fromDocument(schemaText.read[Tel], Tels.Axiom.tels))

      // The hex bytes of the `BASE` constant, in order.
      def pinned: Text =
        val source: String = rust.s
        val start = source.indexOf("pub const BASE")
        val end = source.indexOf("];", start)
        val builder = StringBuilder()
        var i = source.indexOf("0x", start)
        while i >= 0 && i < end do
          builder.append(source.substring(i + 2, i + 4).nn)
          i = source.indexOf("0x", i + 4)
        builder.toString.tt

      // The client writes the base's signature as the pinned hash followed by a trailer that
      // makes every byte XOR to the cadence 0x79 (BinTEL §8.2); the same arithmetic, here,
      // must reproduce what Stratiform derives, or the two sides' framing differs.
      def clientSignature: Text =
        val hash = pinned.s.grouped(2).map(Integer.parseInt(_, 16)).toList
        val trailer = hash.foldLeft(0x79)(_ ^ _) & 0xff
        (hash :+ trailer).map { byte => String.format("%02x", Integer.valueOf(byte)) }.mkString.tt

      test(m"the client pins the hash of the base schema in spec/"):
        derived.s.take(64).tt
      .check(_ == pinned)

      test(m"the client's palimpsest arithmetic reproduces the base signature"):
        derived
      .check(_ == clientSignature)

    suite(m"native"):
      test(m"output equals stub ‖ record ‖ jar, byte for byte"):
        val dir = tempDir(); val client = fakeClient(); val jar = fakeJar(dir)
        val out = dir/t"tool"
        build(Options(jar = local(jar), output = local(out), source = Stubs.Source.Directory(local(client))), dir)
        sha(out) == sha(cat(dir, stubOf(client, host), record(dir), jar))
      .assert(_ == true)

      test(m"runs, and is executable"):
        val dir = tempDir(); val client = fakeClient(); val jar = fakeJar(dir)
        build(Options(jar = local(jar), source = Stubs.Source.Directory(local(client))), dir)
        sh"${dir/t"app"}".exec[Text]().trim
      .assert(_ == t"ran-$hostLabel")

      test(m"rebases a ZIP64 JAR's locator, so the result still opens as a ZIP"):
        val dir = tempDir(); val client = fakeClient(); val jar = fakeJar(dir, 65600)
        val out = dir/t"tool"
        build(Options(jar = local(jar), output = local(out), source = Stubs.Source.Directory(local(client))), dir)
        val zip = juz.ZipFile(out.encode.s)
        try (Zip64.locator(bytes(jar)).present, zip.size) finally zip.close()
      .assert(_ == (true, 65601))

      test(m"refuses a JAR with no main class"):
        val dir = tempDir(); val client = fakeClient(); val jar = dir/t"app.jar"
        writeText(jar, t"JARBYTES\n")
        fault(build(Options(jar = local(jar), source = Stubs.Source.Directory(local(client))), dir))
      .assert(_ == Assembler.Fault.Format)

      test(m"writes one executable per platform, named for each"):
        val dir = tempDir(); val client = fakeClient(); val jar = fakeJar(dir)
        val options =
          Options(jar = local(jar), targets = List(Target.LinuxX64, Target.WindowsX64), source = Stubs.Source.Directory(local(client)))
        build(options, dir).map(_.name)
      .assert(_ == List(t"app-linux-x64", t"app-windows-x64.exe"))

    suite(m"polyglot"):
      test(m"unpacks to the native executable for the host, record and all"):
        val dir = tempDir(); val client = fakeClient(); val jar = fakeJar(dir); val out = dir/t"tool"
        build(Options(jar = local(jar), output = local(out), polyglot = true, source = Stubs.Source.Directory(local(client))), dir)
        val ran = sh"$out".exec[Text]().trim
        (ran, sha(out) == sha(cat(dir, stubOf(client, host), record(dir), jar)))
      .assert(_ == (t"ran-$hostLabel", true))

      test(m"leaves out an excluded shell's section, and still runs in sh"):
        val dir = tempDir(); val client = fakeClient(); val jar = fakeJar(dir); val out = dir/t"tool"
        val options =
          Options
            ( jar = local(jar), output = local(out), polyglot = true, exclude = List(Shell.Bat),
              source = Stubs.Source.Directory(local(client)) )

        build(options, dir)
        val text = String(bytes(out), "UTF-8").tt
        (text.contains(t"cannot be run by cmd.exe"), text.contains(t"findstr"), sh"$out".exec[Text]().trim)
      .assert(_ == (true, false, t"ran-$hostLabel"))

      test(m"includes only the stubs for the platforms asked for"):
        val dir = tempDir(); val client = fakeClient(); val jar = fakeJar(dir); val out = dir/t"tool"
        val options =
          Options
            ( jar = local(jar), output = local(out), polyglot = true, targets = List(Target.LinuxX64, host),
              source = Stubs.Source.Directory(local(client)) )

        build(options, dir)
        String(bytes(out), "UTF-8").tt.cut(t"\n").filter(_.starts(t"index:")).prim.or(t"")
      .assert(_.starts(t"index:linux-x64=1,"))

      if safely(sh"pwsh -Version".exec[Exit]()) == Exit.Ok then
        test(m"unpacks in PowerShell to the same native executable"):
          val dir = tempDir(); val client = fakeClient(); val jar = fakeJar(dir); val out = dir/t"tool.ps1"
          build(Options(jar = local(jar), output = local(out), polyglot = true, source = Stubs.Source.Directory(local(client))), dir)
          // The installer registers tab-completions in the PowerShell profile, which is kept in a
          // scratch directory, not the user's own.
          val config: Text = tempDir().encode
          val ran = sh"env XDG_CONFIG_HOME=$config pwsh -NoProfile -File $out".exec[Text]().trim
          (ran, sha(dir/t"tool") == sha(cat(dir, stubOf(client, host), record(dir), jar)))
        .assert(_ == (t"ran-$hostLabel", true))

    suite(m"download (online launcher)"):
      test(m"fetches the stub over file://, appends record and jar, runs"):
        val dir = tempDir(); val client = fakeClient(); val jar = fakeJar(dir); val out = dir/t"tool"
        val hashes: Map[Text, Text] = Target.all.map { target => (target.label, sha(stubOf(client, target))) }.to[Map]
        val source = Stubs.Source.Remote(t"file://${client.encode}", hashes)
        build(Options(jar = local(jar), output = local(out), download = true, source = source), dir)
        val ran = sh"$out".exec[Text]().trim
        (ran, sha(out) == sha(cat(dir, stubOf(client, host), record(dir), jar)))
      .assert(_ == (t"ran-$hostLabel", true))

      test(m"needs stubs at a URL"):
        val dir = tempDir(); val client = fakeClient(); val jar = fakeJar(dir)
        fault(plan(Options(jar = local(jar), download = true, source = Stubs.Source.Directory(local(client)))))
      .assert(_ == Assembler.Fault.Usage)

    suite(m"dispatch"):
      test(m"downloads a complete executable and re-execs it"):
        val dir = tempDir()
        val exe = dir/t"real"
        val exeBody: Text = t"#!/bin/sh\necho dispatched\nexit 0\n"
        writeText(exe, exeBody)
        sh"chmod +x ${exe.encode}".exec[Exit]()
        val manifest = dir/t"d.tsv"
        writeText(manifest, t"$hostLabel\tfile://${exe.encode}\t${sha(exe)}\n")
        val out = dir/t"tool"
        build(Options(dispatch = local(manifest), output = local(out)), dir)
        sh"$out".exec[Text]().trim
      .assert(_ == t"dispatched")

    suite(m"stubs"):
      test(m"a download whose hash differs is refused"):
        val client = fakeClient()
        val source = Stubs.Source.Remote(t"file://${client.encode}", Map(host.label -> t"00"))
        fault(Stubs.resolve(source, host, local(tempDir()))(_ => ()))
      .assert(_ == Assembler.Fault.Format)

      test(m"a cached stub is used without downloading it again"):
        val client = fakeClient()
        val hashes: Map[Text, Text] = Map(host.label -> sha(stubOf(client, host)))
        val directory = local(tempDir())
        Stubs.resolve(Stubs.Source.Remote(t"file://${client.encode}", hashes), host, directory)(_ => ())
        val unreachable = Stubs.Source.Remote(t"file:///nonexistent", hashes)
        Stubs.resolve(unreachable, host, directory)(_ => ()).name
      .assert(_ == host.stub)

      test(m"a download which fails is reported as such"):
        val hashes: Map[Text, Text] = Map(host.label -> t"00")
        fault(Stubs.resolve(Stubs.Source.Remote(t"file:///nonexistent", hashes), host, local(tempDir()))(_ => ()))
      .assert(_ == Assembler.Fault.Download)

    suite(m"installer"):
      // The executables of a release, named as `xek build -p` names them, in a directory.
      def release(dir: Path on Linux, name: Text, targets: List[Target]): List[Text] =
        targets.map: target =>
          val suffix: Text = if target.windows then t".exe" else t""
          val file: Text = t"$name-${target.label}$suffix"
          writeText(sub(dir, file), t"#!/bin/sh\necho $file\n")
          file

      def installer(dir: Path on Linux, words: Text*): _root_.xek.Installer.Options =
        val parsed = xek.Command.parse(t"installer" :: words.to(List))
        xek.Command.installer(parsed, word => Files.child(local(dir), word))

      def written(dir: Path on Linux, words: Text*): (Text, Text) =
        _root_.xek.Installer.write(installer(dir, words*))
        (String(bytes(dir/t"install.sh"), "UTF-8").tt, String(bytes(dir/t"install.ps1"), "UTF-8").tt)

      test(m"names the command for its executables, and the release for the URL"):
        val dir = tempDir()
        val files = release(dir, t"my-tool", List(Target.LinuxX64, Target.WindowsX64))
        val options = installer(dir, (t"--url" :: t"https://example.com/r/2.0.1/" :: files)*)
        (options.name, options.release, options.url, Target.all.filter(options.digests(_).present))
      .assert(_ == (t"my-tool", t"2.0.1", t"https://example.com/r/2.0.1", List(Target.LinuxX64, Target.WindowsX64)))

      test(m"embeds each executable's SHA-256 in both scripts"):
        val dir = tempDir()
        val files = release(dir, t"tool", List(Target.LinuxArm64, Target.MacosArm64, Target.WindowsX64))
        val (sh, ps1) = written(dir, (t"--url" :: t"https://example.com/r/1.0" :: files)*)
        val expected: List[Text] = files.map { file => sha(sub(dir, file)) }
        ( expected.all { digest => sh.contains(t"expected=$digest ;;") },
          expected.all { digest => ps1.contains(t"= '$digest'") },
          sh.contains(t"linux-x64)"), ps1.contains(t"'linux-x64'"),
          sh.contains(t"base=\"https://example.com/r/1.0\""), ps1.contains(t"$$base = 'https://example.com/r/1.0'"),
          sh.contains(t"TOOL_INSTALL_DIR"), ps1.contains(t"$$env:TOOL_INSTALL_DIR"),
          sh.contains(t"@@"), ps1.contains(t"@@") )
      .assert(_ == (true, true, false, false, true, true, true, true, false, false))

      test(m"takes digests from a manifest instead, with a name"):
        val dir = tempDir()
        val digest: Text = t"ab"*32
        writeText(dir/t"m.tsv", t"# digests\nlinux-x64\t$digest\nwindows-x64\t${digest.upper}\n")
        val options = installer(dir, t"--url", t"https://example.com/r/3", t"--manifest", t"m.tsv", t"--name", t"tool", t"--release", t"3.0.0")
        (options.name, options.release, options.digests(Target.LinuxX64) == digest, options.digests(Target.WindowsX64) == digest)
      .assert(_ == (t"tool", t"3.0.0", true, true))

      test(m"refuses a missing URL, an unnamed manifest, a bad platform and mixed names"):
        val dir = tempDir()
        val files = release(dir, t"tool", List(Target.LinuxX64))
        writeText(dir/t"other-macos-x64", t"x")
        writeText(dir/t"tool-plan9", t"x")
        writeText(dir/t"m.tsv", t"linux-x64\t${t"ab"*32}\n")
        ( fault(installer(dir, files*)),
          fault(installer(dir, t"--url", t"u", t"--manifest", t"m.tsv")),
          fault(installer(dir, t"--url", t"u", t"--manifest", t"m.tsv", t"tool-linux-x64")),
          fault(installer(dir, t"--url", t"u", t"tool-plan9")),
          fault(installer(dir, t"--url", t"u", t"tool-linux-x64", t"other-macos-x64")),
          fault(installer(dir, t"--url", t"u", t"tool-linux-x64", t"tool-linux-x64")),
          fault(installer(dir, t"--url", t"u")) )
      .assert(_ == (Assembler.Fault.Usage, Assembler.Fault.Usage, Assembler.Fault.Usage, Assembler.Fault.Usage,
                    Assembler.Fault.Usage, Assembler.Fault.Usage, Assembler.Fault.Usage))

      test(m"writes an install.sh which installs the executable for this platform from a file URL"):
        val dir = tempDir()
        val files = release(dir, t"tool", List(host))
        val bin = tempDir()
        written(dir, (t"--url" :: t"file://${dir.encode}" :: t"--release" :: t"9.9" :: files)*)
        val install = dir/t"install.sh"
        val output = sh"env TOOL_INSTALL_DIR=${bin.encode} sh ${install.encode}".exec[Text]()
        val installed = bin/t"tool"
        val file: Text = files.prim.or(t"")
        (output.contains(t"Installed tool 9.9"), sha(installed) == sha(sub(dir, file)), sh"$installed".exec[Text]().trim == file)
      .assert(_ == (true, true, true))

      test(m"an install.sh refuses an executable whose digest differs"):
        val dir = tempDir()
        val files = release(dir, t"tool", List(host))
        written(dir, (t"--url" :: t"file://${dir.encode}" :: files)*)
        writeText(sub(dir, files.prim.or(t"")), t"#!/bin/sh\necho tampered\n")
        val bin = tempDir()
        val exit = sh"env TOOL_INSTALL_DIR=${bin.encode} sh ${(dir/t"install.sh").encode}".exec[Exit]()
        (exit, jnf.Files.exists(jnf.Path.of((bin/t"tool").encode.s)))
      .assert(_ == (Exit.Fail(1), false))

      if safely(sh"pwsh -Version".exec[Exit]()) == Exit.Ok then
        test(m"writes an install.ps1 which PowerShell parses"):
          val dir = tempDir()
          val files = release(dir, t"tool", List(Target.WindowsX64))
          written(dir, (t"--url" :: t"https://example.com/r/1" :: files)*)
          val script: Text = (dir/t"install.ps1").encode
          val check: Text = t"$$null = [scriptblock]::Create((Get-Content -Raw '$script')); 'parsed'"
          sh"pwsh -NoProfile -Command $check".exec[Text]().trim
        .assert(_ == t"parsed")

    suite(m"command line"):
      test(m"needs a subcommand, and suggests one for a JAR"):
        (fault(xek.Command.parse(Nil)), fault(xek.Command.parse(List(t"app.jar"))),
         fault(xek.Command.parse(List(t"frob"))))
      .assert(_ == (Assembler.Fault.Usage, Assembler.Fault.Usage, Assembler.Fault.Usage))

      test(m"accepts --help and --version alone"):
        (xek.Command.parse(List(t"--help")).action, xek.Command.parse(List(t"-v")).action)
      .assert(_ == (Unset, Unset))

      test(m"reads the subcommand, and gives each only its own options"):
        val sign = xek.Command.parse(List(t"sign", t"--key", t"k", t"--in", t"a", t"--out", t"b"))
        (sign.action, sign.value(xek.Command.Key), fault(xek.Command.parse(List(t"sign", t"--platform", t"x"))),
         fault(xek.Command.parse(List(t"build", t"--key", t"k", t"app.jar"))))
      .assert(_ == (xek.Command.Action.Sign, t"k", Assembler.Fault.Usage, Assembler.Fault.Usage))

      test(m"reads install's --force, long and short, and nothing else"):
        val short = xek.Command.parse(List(t"install", t"-f"))
        val long = xek.Command.parse(List(t"install", t"--force"))
        val bare = xek.Command.parse(List(t"install"))
        (short.has(xek.Command.Force), long.has(xek.Command.Force), bare.has(xek.Command.Force),
         fault(xek.Command.parse(List(t"install", t"--out", t"x"))))
      .assert(_ == (true, true, false, Assembler.Fault.Usage))

      test(m"names the output for the JAR by default"):
        plan(parse(t"app.jar")).files.map(_.name)
      .assert(_ == List(t"app"))

      test(m"accepts options after operands, and values joined by ="):
        val options = parse(t"app.jar", t"out", t"--platform=linux-x64", t"-pwindows-x64", t"--java", t"25")
        (options.output.let(_.name), options.targets, options.record.javaPreferred)
      .assert(_ == (t"out", List(Target.LinuxX64, Target.WindowsX64), 25))

      test(m"splits comma-separated lists, and accepts --platforms"):
        parse(t"app.jar", t"--polyglot", t"--platforms", t"linux-x64,macos-arm64", t"-x", t"bat").pipe: options =>
          (options.targets, options.exclude)
      .assert(_ == (List(Target.LinuxX64, Target.MacosArm64), List(Shell.Bat)))

      test(m"reads everything after -- as operands"):
        parse(t"--", t"-odd.jar").jar.let(_.name)
      .assert(_ == t"-odd.jar")

      test(m"refuses an unknown option"):
        fault(parse(t"app.jar", t"--bogus"))
      .assert(_ == Assembler.Fault.Usage)

      test(m"refuses an unknown platform"):
        fault(parse(t"app.jar", t"-p", t"plan9"))
      .assert(_ == Assembler.Fault.Usage)

      test(m"refuses a third operand"):
        fault(parse(t"app.jar", t"out", t"extra"))
      .assert(_ == Assembler.Fault.Usage)

      test(m"appends .exe to a Windows executable"):
        plan(parse(t"app.jar", t"-p", t"windows-x64")).files.map(_.name)
      .assert(_ == List(t"app.exe"))

      test(m"writes into an output which is a directory"):
        val directory = tempDir()
        val options = parse(t"app.jar").copy(output = local(directory))
        Build.plan(options, host, local(tempDir())).files.map(_.encode) == List(t"${local(directory).encode}/app")
      .assert(_ == true)

      test(m"refuses to overwrite the JAR"):
        fault(plan(parse(t"app.jar", t"app.jar")))
      .assert(_ == Assembler.Fault.Usage)

      test(m"refuses --exclude without a polyglot file"):
        fault(plan(parse(t"app.jar", t"-x", t"bat")))
      .assert(_ == Assembler.Fault.Usage)

      test(m"refuses to exclude every shell"):
        fault(plan(parse(t"app.jar", t"--polyglot", t"-x", t"sh,pwsh,bat")))
      .assert(_ == Assembler.Fault.Usage)

      test(m"excluding cmd.exe and PowerShell leaves out the Windows stub"):
        plan(parse(t"app.jar", t"--polyglot", t"-x", t"bat,pwsh")).absolve match
          case Build.Plan.Installer(_, targets, _, _, _, _) => targets
      .assert(_ == unix)

      test(m"refuses a platform no included shell can unpack"):
        fault(plan(parse(t"app.jar", t"--polyglot", t"-x", t"bat,pwsh", t"-p", t"windows-x64")))
      .assert(_ == Assembler.Fault.Usage)

      test(m"refuses --public-key without --app-id"):
        val key = tempDir()/t"key.pub"
        jnf.Files.write(jnf.Path.of(key.encode.s), scala.Array.fill[Byte](1312)(1))
        fault(parse(t"app.jar", t"--public-key", key.encode))
      .assert(_ == Assembler.Fault.Usage)

      test(m"refuses --recovery-key without --public-key"):
        val key = tempDir()/t"key.pub"
        jnf.Files.write(jnf.Path.of(key.encode.s), scala.Array.fill[Byte](1312)(1))
        fault(parse(t"app.jar", t"--recovery-key", key.encode, t"--app-id", t"a/b"))
      .assert(_ == Assembler.Fault.Usage)

      test(m"refuses a minimum Java version above the preferred one"):
        fault(plan(parse(t"app.jar", t"--java-min", t"25", t"--java", t"21")))
      .assert(_ == Assembler.Fault.Usage)

    // Packager is a front end over the same `core`.
    def config
       (delivery:     Packaging.Delivery,
        dependencies: Packaging.Dependencies,
        clientSource: Packaging.ClientSource = Packaging.ClientSource.Remote(t"https://x.test/", Map()),
        targets:      List[Text]             = List(t"linux-x64"))
    :   Packaging =
      val dir = tempDir()
      Packaging(name = t"hello", targets = targets, delivery = delivery, dependencies = dependencies,
                output = dir/t"hello", clientSource = clientSource)

    val fatJar: Packaging.Dependencies = Packaging.Dependencies.FatJar(fakeJar(tempDir()))

    suite(m"Packager validation"):
      test(m"Burdock remote dependencies are rejected"):
        capture[Packager.Error](Packager.pack(config(Packaging.Delivery.EmbedAll,
          Packaging.Dependencies.BurdockRemote(tempDir()/t"app.jar"))))
      .assert(_ => true)

      test(m"remote client with no hash for the target is rejected"):
        capture[Packager.Error](Packager.pack(config(Packaging.Delivery.Native, fatJar,
          Packaging.ClientSource.Remote(t"https://example.invalid/", Map()))))
      .assert(_ => true)

      test(m"native delivery with multiple targets is rejected"):
        capture[Packager.Error]:
          Packager.pack(config(Packaging.Delivery.Native, fatJar, targets = List(t"linux-x64", t"macos-arm64")))
      .assert(_ => true)

    suite(m"Packager assembly"):
      test(m"Native delivery builds a byte-correct host binary"):
        val dir = tempDir(); val client = fakeClient()
        val jar = fakeJar(dir)
        val out = dir/t"hello"
        Packager.pack(Packaging(name = t"hello", targets = List(hostLabel),
          delivery = Packaging.Delivery.Native, dependencies = Packaging.Dependencies.FatJar(jar),
          output = out, clientSource = Packaging.ClientSource.Local(client)))
        sh"$out".exec[Text]().trim
      .assert(_ == t"ran-$hostLabel")

    // Linux via docker, using the same fake shell stubs (which run on Linux too).
    val dockerOk = safely(sh"docker info".exec[Exit]()) == Exit.Ok
    if dockerOk then
      def linuxCheck(platform: Text, label: Text): Boolean =
        val dir = tempDir(); val client = fakeClient(); val jar = fakeJar(dir); val out = dir/t"tool"
        build(Options(jar = local(jar), output = local(out), polyglot = true, source = Stubs.Source.Directory(local(client))), dir)
        val mount = t"${dir.encode}:/work"
        sh"docker run --rm --platform $platform -v $mount -w /work ubuntu:24.04 ./tool".exec[Text]().trim == t"ran-$label"
      // Docker being installed does not mean every platform can run under it: linux/arm64 on
      // an x86-64 host needs QEMU binfmt registered, which GitHub's client do not have. Probe
      // each platform and step aside where it cannot run, rather than reporting the
      // environment as a failure.
      def dockerRuns(platform: Text): Boolean =
        safely(sh"docker run --rm --platform $platform ubuntu:24.04 true".exec[Exit]()) == Exit.Ok

      if dockerRuns(t"linux/amd64") then
        suite(m"docker linux/amd64"):
          test(m"embed-all unpacks and selects linux-x64")(linuxCheck(t"linux/amd64", t"linux-x64")).assert(_ == true)

      if dockerRuns(t"linux/arm64") then
        suite(m"docker linux/arm64"):
          test(m"embed-all unpacks and selects linux-arm64")(linuxCheck(t"linux/arm64", t"linux-arm64")).assert(_ == true)
