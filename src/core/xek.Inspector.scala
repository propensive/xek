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
import java.util as ju
import java.util.jar as juj

import anticipation.*
import contingency.*
import denominative.*
import fulminate.*
import gossamer.*
import rudiments.*
import symbolism.*
import vacuous.*

// `xek inspect`: what an XEK executable carries, read without running it. A native executable is
// read where it lies — the stub's header for its platform, the record after it, the JAR after
// that — and a polyglot file through its index, decoding the payloads the record and the JAR are
// held in. Each fact is a line of a label and its value, which `render` aligns.
object Inspector:
  case class Line(label: Text, value: Text)

  private def malformed(message: Message)(using Diagnostics): Assembler.Error =
    Assembler.Error(Assembler.Fault.Format, message)

  def render(lines: List[Line]): List[Text] =
    var width: Int = 0
    lines.each { line => width = width max (line.label.length + 3) }

    lines.map: line =>
      val label: Text = if line.label == t"" then t"" else t"${line.label}:"
      t"$label${t" "*(width - label.length)}${line.value}"

  // `appId`, if given, is checked against the record's hash of the application's identifier,
  // which cannot be read back.
  def inspect(bytes: scala.Array[Byte], name: Text, appId: Optional[Text]): List[Line] raises Assembler.Error =
    val offset: Optional[Int] = Signer.record(bytes)

    if offset.present then native(bytes, offset.or(0), appId) else
      val text: String = String(bytes, "ISO-8859-1")
      if text.contains("\nindex:") || text.contains("\nassets:") then polyglot(text, appId)
      else abort(malformed(m"$name is neither an XEK executable nor a polyglot file"))

  private def slice(bytes: scala.Array[Byte], from: Int, length: Int): scala.Array[Byte] =
    ju.Arrays.copyOfRange(bytes, from, from + length).nn

  private def zero(bytes: scala.Array[Byte]): Boolean = bytes.forall(_ == 0)

  // `text` cut at every `separator`, empty pieces included.
  private def split(text: String, separator: Char): List[String] =
    var pieces: List[String] = Nil
    var end: Int = text.length
    var index: Int = text.length - 1

    while index >= -1 do
      if index < 0 || text.charAt(index) == separator then
        pieces = text.substring(index + 1, end).nn :: pieces
        end = index

      index -= 1

    pieces

  private def le(bytes: scala.Array[Byte]): java.nio.ByteBuffer =
    java.nio.ByteBuffer.wrap(bytes).nn.order(java.nio.ByteOrder.LITTLE_ENDIAN).nn

  // A label for the first of several values, which the lines after it continue.
  private def column(label: Text, values: List[Text]): List[Line] =
    values.indexed.map { (value, index) => Line(if index == Prim then label else t"", value) }

  private def native(bytes: scala.Array[Byte], offset: Int, appId: Optional[Text]): List[Line] =
    val record: scala.Array[Byte] = ju.Arrays.copyOfRange(bytes, offset, bytes.length).nn
    val platform: Text = Inspector.platform(bytes).let(_.label).or(t"an unknown platform")
    val end: Int = offset + Record.size + optionsLength(record)

    List(Line(t"Kind", t"native executable for $platform"), Line(t"Stub", t"$offset bytes"))
    + fields(record, appId)
    + List(Line(t"Signature", signatureOf(bytes, offset, record)))
    + jar(ju.Arrays.copyOfRange(bytes, end, bytes.length).nn)

  // The platform a stub is for, from its executable header: ELF's machine, Mach-O's CPU type,
  // and for a PE, the one Windows platform there is.
  def platform(bytes: scala.Array[Byte]): Optional[Target] =
    def u16(at: Int): Int = if bytes.length < at + 2 then -1 else (bytes(at) & 0xff) | (bytes(at + 1) & 0xff) << 8
    def u32(at: Int): Int = if bytes.length < at + 4 then -1 else u16(at) | u16(at + 2) << 16

    if u32(0) == 0x464c457f then
      if u16(18) == 62 then Target.LinuxX64 else if u16(18) == 183 then Target.LinuxArm64 else Unset
    else if u32(0) == 0xfeedfacf then
      if u32(4) == 0x01000007 then Target.MacosX64 else if u32(4) == 0x0100000c then Target.MacosArm64 else Unset
    else if u16(0) == 0x5a4d then Target.WindowsX64
    else Unset

  // The length of the block of Java options after a record, its length field included, or zero
  // when the record announces none.
  private def optionsLength(record: scala.Array[Byte]): Int =
    if (record(Record.flagsOffset) & Record.javaOptionsFlag) == 0 || record.length < Record.size + 4 then 0
    else 4 + le(record).getInt(Record.size)

  // The Java options in the block after a record: none if it has none, or if the block is
  // malformed, as the client reads it.
  def javaOptions(record: scala.Array[Byte]): List[Text] =
    val length: Int = optionsLength(record)

    if length <= 4 || length > Record.javaOptionsLimit + 4 || record.length < Record.size + length then Nil
    else
      val text: String = String(record, Record.size + 4, length - 4, "UTF-8")
      split(text, '\u0000').filter(_.nonEmpty).map(_.tt)

  // The facts the record holds, and the Java options after it.
  private def fields(record: scala.Array[Byte], appId: Optional[Text]): List[Line] =
    val buffer: java.nio.ByteBuffer = le(record)
    val minimum: Int = buffer.getShort(16) & 0xffff
    val preferred: Int = buffer.getShort(18) & 0xffff
    val flags: Int = record(Record.flagsOffset) & 0xff
    val id: scala.Array[Byte] = slice(record, Record.appIdOffset, 32)
    val release: scala.Array[Byte] = slice(record, Record.releaseKeyOffset, Record.keySize)
    val recovery: scala.Array[Byte] = slice(record, Record.recoveryKeyOffset, Record.keySize)

    def version(value: Int, default: Int): Text =
      if value == 0 then t"$default (the default)" else t"$value"

    val application: Text =
      if zero(id) then t"none" else
        val hash: Text = t"SHA3-256 ${Signer.hex(id)}"

        appId.lay(hash): appId =>
          if ju.Arrays.equals(id, Record.hash(appId)) then t"$appId ($hash)" else t"not $appId ($hash)"

    // A key's SHA-256, which is what `sha256sum` prints for its `.pub` file.
    def key(bytes: scala.Array[Byte]): Text = if zero(bytes) then t"none" else t"SHA-256 ${Files.sha256(bytes)}"

    val upgrades: Text =
      if zero(release) && zero(id) then t"disabled: there is no release key or application id"
      else if zero(release) then t"disabled: there is no release key"
      else if zero(id) then t"disabled: there is no application id"
      else t"enabled"

    val options: List[Text] = javaOptions(record)
    val buildId: Text = java.lang.Long.toUnsignedString(buffer.getLong(8)).nn.tt

    List
      ( Line(t"Format", t"ETHRCFG v${record(7).toInt}"),
        Line(t"Build id", buildId),
        Line(t"Minimum Java", version(minimum, Record.javaMinimum)),
        Line(t"Preferred Java", version(preferred, Record.javaPreferred)),
        Line(t"Downloads", if record(20) == 0 then t"a JRE, if no Java is found" else t"a JDK, if no Java is found") )
    + column(t"Java options", if options == Nil then List(t"none") else options)
    + List
        ( Line(t"Application id", application),
          Line(t"Release key", key(release)),
          Line(t"Recovery key", key(recovery)),
          Line(t"Self-upgrade", upgrades),
          Line(t"Downgrades", if (flags & Record.downgradeFlag) != 0 then t"permitted" else t"not permitted") )

  // Whether the signature verifies, and under which of the record's own keys. Checking it needs
  // ML-DSA, which only Java 24 and later provide.
  private def signatureOf(bytes: scala.Array[Byte], offset: Int, record: scala.Array[Byte]): Text =
    val signature: scala.Array[Byte] = slice(record, Record.signatureOffset, Record.signatureSize)

    if zero(signature) then t"none"
    else if !Signer.available then t"present; checking it needs Java 24 or later"
    else
      val statement: scala.Array[Byte] = Signer.statement(bytes, offset)

      def under(at: Int): Boolean =
        val key: scala.Array[Byte] = slice(record, at, Record.keySize)
        !zero(key) && safely(Signer.verifies(statement, signature, key)).or(false)

      if under(Record.releaseKeyOffset) then t"verifies under the release key"
      else if under(Record.recoveryKeyOffset) then t"verifies under the recovery key"
      else t"does not verify under either of the record's keys"

  // The JAR's size and main class. The manifest is found through the ZIP's central directory, as
  // the JVM finds it, since it need not be a JAR's first entry; so the bytes are read as a file.
  private def jar(bytes: scala.Array[Byte]): List[Line] =
    val main: Text =
      try
        val file: jnf.Path = jnf.Files.createTempFile("xek-inspect", ".jar").nn

        try
          jnf.Files.write(file, bytes)
          val archive = juj.JarFile(file.toFile)

          try
            val manifest: juj.Manifest | Null = archive.getManifest()
            val value: String | Null = if manifest == null then null else manifest.getMainAttributes().nn.getValue("Main-Class")
            if value == null then t"none" else value.tt
          finally archive.close()

        finally jnf.Files.deleteIfExists(file)

      catch case _: java.io.IOException => t"unreadable"

    List(Line(t"JAR", t"${bytes.length} bytes"), Line(t"Main class", main))

  // A polyglot file: its kind, from the payloads its index names, then what the record and the
  // JAR say, decoded from theirs. An offset counts lines from the `index:` line to its payload's
  // `BEGIN` line (`Polyglot.frame`).
  private def polyglot(text: String, appId: Optional[Text]): List[Line] raises Assembler.Error =
    val lines: scala.Array[String] = text.split("\n", -1).nn.map(_.nn)
    var indexAt: Int = -1
    var assetsLine: String = ""
    var index: Int = 0

    while index < lines.length do
      if indexAt < 0 && lines(index).startsWith("index:") then indexAt = index
      if lines(index).startsWith("assets:") then assetsLine = lines(index).substring(7).nn
      index += 1

    val offsets = ju.LinkedHashMap[String, Integer]()

    if indexAt >= 0 then split(lines(indexAt).substring(6).nn, ',').each: entry =>
      val equals: Int = entry.indexOf('=')

      if equals > 0 then
        try offsets.put(entry.substring(0, equals).nn, Integer.valueOf(entry.substring(equals + 1).nn.toInt))
        catch case _: NumberFormatException => ()

    def payload(label: String): Optional[scala.Array[Byte]] =
      val offset: Integer | Null = offsets.get(label)

      if offset == null then Unset else
        val body = StringBuilder()
        var line: Int = indexAt + offset.intValue + 1

        while line < lines.length && !lines(line).startsWith("-----END") do
          body.append(lines(line))
          line += 1

        try ju.Base64.getDecoder.nn.decode(body.toString).nn
        catch case _: IllegalArgumentException => abort(malformed(m"the $label payload is not valid base64"))

    val assets: List[Text] =
      if assetsLine.isEmpty then Nil
      else split(assetsLine, ',').filter(_.nonEmpty).map: entry =>
        val equals: Int = entry.indexOf('=')
        val bar: Int = entry.lastIndexOf('|')
        if equals < 0 || bar < equals then entry.tt
        else t"${entry.substring(0, equals).nn}: ${entry.substring(equals + 1, bar).nn}, SHA-256 ${entry.substring(bar + 1).nn}"

    val stubs: List[Text] = Target.all.map(_.label).filter { label => offsets.containsKey(label.s) }
    val carries: Boolean = offsets.containsKey("data")

    val kind: Text =
      if carries && stubs != Nil then t"polyglot file, which unpacks the stub for its platform"
      else if carries then t"polyglot file, which downloads its stub on first run"
      else t"polyglot file, which downloads a complete executable"

    val record: List[Line] = payload("record").lay(Nil): record =>
      if record.length < Record.size then abort(malformed(m"the record in this polyglot file is truncated"))
      val signed: Boolean = !zero(slice(record, Record.signatureOffset, Record.signatureSize))
      val signature: Text = if signed then t"present; checked in the executable this unpacks to" else t"none"
      fields(record, appId) + List(Line(t"Signature", signature))

    List(Line(t"Kind", kind))
    + column(t"Stubs", stubs)
    + column(if carries then t"Stub downloads" else t"Downloads", assets)
    + record
    + payload("data").lay(Nil)(jar(_))
