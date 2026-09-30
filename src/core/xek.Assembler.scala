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

import java.io as ji
import java.nio.file as jnf
import java.util.jar as juj

import ambience.*
import anticipation.*
import contingency.*
import distillate.*
import fulminate.*
import galilei.*
import prepositional.*
import serpentine.*
import vacuous.*

import systems.javaBaseSystem

// Writing an XEK executable: joining a bare stub, a record and a JAR (`spec/ethrcfg.md`).
// Everything here works on bytes and files; nothing touches a network, which is `Stubs`'s job,
// and nothing produces text, which is `Polyglot`'s.
object Assembler:
  // What went wrong, which decides the exit status a command-line front end reports.
  enum Fault(val status: Int):
    case Usage extends Fault(1)
    case Format extends Fault(2)
    case Download extends Fault(3)

  case class Error(fault: Fault, detail: Message)(using Diagnostics)
  extends fulminate.Error(detail)

  // `stub ‖ record ‖ jar`, with the JAR's ZIP64 locator rebased by the bytes now in front of it,
  // marked executable unless it is a Windows executable (whose name marks it instead), and moved
  // into place only once it is complete.
  def native(stub: Path on Local, record: Record, jar: Path on Local, output: Path on Local, windows: Boolean)
  :   Path on Local raises Error =

    val stubBytes: scala.Array[Byte] = Files.read(stub)
    val recordBytes: scala.Array[Byte] = Array.unsafeJvm(record.data)
    val jarBytes: scala.Array[Byte] = Files.read(jar)
    Zip64.rebase(jarBytes, stubBytes.length.toLong + recordBytes.length)

    Files.write(output, executable = !windows): stream =>
      stream.write(stubBytes)
      stream.write(recordBytes)
      stream.write(jarBytes)

    output

  // Checks that a file is a JAR a runner can launch with `java -jar`: a ZIP whose manifest names
  // a `Main-Class`. The runner never names a class itself, so without one the daemon cannot
  // start, and this is the last point at which that can be said clearly.
  def checkJar(jar: Path on Local): Unit raises Error =
    val file = ji.File(jar.encode.s)

    if !file.isFile then abort(Error(Fault.Usage, m"${jar.encode} is not a file"))

    val mainClass: Optional[Text] =
      try
        val archive = juj.JarFile(file)

        try
          val manifest: juj.Manifest | Null = archive.getManifest()
          if manifest == null then Unset else
            val value: String | Null = manifest.getMainAttributes().nn.getValue("Main-Class")
            if value == null then Unset else value.tt
        finally archive.close()

      catch case error: ji.IOException =>
        abort(Error(Fault.Format, m"${jar.encode} could not be read as a JAR file"))

    if mainClass.absent
    then abort(Error(Fault.Format, m"${jar.encode} has no Main-Class in its manifest"))

// The one physical offset in a ZIP. A JAR with a ZIP64 end-of-central-directory locator — the
// 20-byte block starting `PK\x06\x07` immediately before the end-of-central-directory record —
// holds the absolute offset of the ZIP64 end record at offset 8 within the locator; every other
// offset is relative, so this is the only one to move when bytes are put in front of the JAR.
object Zip64:
  def rebase(bytes: scala.Array[Byte], delta: Long): Unit =
    locator(bytes).let: position =>
      val buffer = java.nio.ByteBuffer.wrap(bytes).nn.order(java.nio.ByteOrder.LITTLE_ENDIAN).nn
      buffer.putLong(position + 8, buffer.getLong(position + 8) + delta)

  // The position of the ZIP64 locator, if the end-of-central-directory record has one before
  // it. The record is at least 22 bytes and at most 65557 from the end, so it is sought
  // backwards from the last possible position.
  def locator(bytes: scala.Array[Byte]): Optional[Int] =
    def signature(position: Int, b2: Int, b3: Int): Boolean =
      bytes(position) == 0x50 && bytes(position + 1) == 0x4b && bytes(position + 2) == b2
      && bytes(position + 3) == b3

    val floor: Int = 0 max (bytes.length - 65557)
    var position: Int = bytes.length - 22
    var eocd: Int = -1

    while eocd < 0 && position >= floor do
      if signature(position, 5, 6) then eocd = position
      position -= 1

    if eocd >= 20 && signature(eocd - 20, 6, 7) then eocd - 20 else Unset

// Plain byte-level file operations, through `java.nio`: the builder's business is bytes, and a
// whole file in memory is the simplest correct thing for files of a few megabytes.
private[xek] object Files:
  def javaPath(path: Path on Local): jnf.Path = jnf.Path.of(path.encode.s).nn

  def read(path: Path on Local): scala.Array[Byte] raises Assembler.Error =
    try jnf.Files.readAllBytes(javaPath(path)).nn
    catch case error: ji.IOException =>
      abort(Assembler.Error(Assembler.Fault.Usage, m"could not read ${path.encode}"))

  def exists(path: Path on Local): Boolean = jnf.Files.exists(javaPath(path))

  // Paths are joined through `java.nio`, so that the host's separator is used.
  def local(path: jnf.Path): Path on Local =
    unsafely(path.toAbsolutePath.nn.normalize.nn.toString.tt.as[Path on Local])
  def path(text: Text): Path on Local = local(jnf.Path.of(text.s).nn)
  def child(directory: Path on Local, name: Text): Path on Local = local(javaPath(directory).resolve(name.s).nn)

  def sibling(path: Path on Local, name: Text): Path on Local =
    local(javaPath(path).toAbsolutePath.nn.resolveSibling(name.s).nn)

  def parent(path: Path on Local): Path on Local = local(javaPath(path).toAbsolutePath.nn.getParent.nn)
  def directory(path: Path on Local): Boolean = jnf.Files.isDirectory(javaPath(path))

  // Writes a file beside its destination, then moves it into place, so that an interrupted
  // build never leaves a partial file where a complete one is expected.
  def write(path: Path on Local, executable: Boolean = false)(body: ji.OutputStream => Unit)
  :   Unit raises Assembler.Error =

    val target: jnf.Path = javaPath(path).toAbsolutePath.nn
    val parent: jnf.Path = target.getParent.nn
    val name: String = target.getFileName.nn.toString

    try
      jnf.Files.createDirectories(parent)
      val temporary: jnf.Path = parent.resolve(s".$name.tmp.${ProcessHandle.current.nn.pid}").nn
      val stream = ji.BufferedOutputStream(jnf.Files.newOutputStream(temporary).nn)

      try body(stream) finally stream.close()

      if executable then temporary.toFile.nn.setExecutable(true, false)

      jnf.Files.move
        (temporary, target, jnf.StandardCopyOption.REPLACE_EXISTING, jnf.StandardCopyOption.ATOMIC_MOVE)

    catch case error: ji.IOException =>
      abort(Assembler.Error(Assembler.Fault.Usage, m"could not write ${path.encode}"))

  def sha256(bytes: scala.Array[Byte]): Text =
    val digest = java.security.MessageDigest.getInstance("SHA-256").nn.digest(bytes).nn
    val builder = StringBuilder()
    var index = 0

    while index < digest.length do
      builder.append(String.format("%02x", Integer.valueOf(digest(index) & 0xff)))
      index += 1

    builder.toString.tt
