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
import java.nio.file.attribute as jnfa
import java.security as js
import java.security.spec as jss

import ambience.*
import anticipation.*
import contingency.*
import denominative.*
import fulminate.*
import galilei.*
import gossamer.*
import prepositional.*
import serpentine.*
import symbolism.*
import vacuous.*

import systems.javaBaseSystem

// Signing a release of an XEK executable (`spec/ethrcfg.md`, *Signing a release*): ML-DSA-44 key
// pairs, the statement a release is signed over, and the signature in its record. The verifier is
// the client stub's, `src/client/src/verify.rs`; the vectors in `spec/fixtures` check the two
// against each other.
//
// ML-DSA is the JDK's, from Java 24 (JEP 497). A key is kept as its 32-byte FIPS 204 seed, which
// the JDK cannot read as a key, so a key pair is generated again from the seed whenever it is
// needed, by a source of randomness that yields exactly the seed: FIPS 204's key generation draws
// one 32-byte seed and nothing else, and a JDK which draws anything else is refused, not guessed at.
object Signer:
  val seedSize: Int = 32
  val algorithm: String = "ML-DSA-44"

  // The DER header of an X.509 `SubjectPublicKeyInfo` for an ML-DSA-44 key (RFC 9881), which
  // the raw 1312-byte key the record holds follows.
  private val publicHeader: scala.Array[Byte] = bytes("30820532300b06096086480165030403110382052100")

  private val statementPrefix: scala.Array[Byte] = "XEKSIGN\u0004".getBytes("US-ASCII").nn

  private def usage(message: Message)(using Diagnostics): Assembler.Error =
    Assembler.Error(Assembler.Fault.Usage, message)

  private def malformed(message: Message)(using Diagnostics): Assembler.Error =
    Assembler.Error(Assembler.Fault.Format, message)

  // Whether this JVM provides ML-DSA at all.
  def available: Boolean =
    try
      js.KeyPairGenerator.getInstance(algorithm)
      true
    catch case _: js.NoSuchAlgorithmException => false

  private def ensureAvailable(): Unit raises Assembler.Error =
    if !available then
      val version: Text = java.lang.System.getProperty("java.version").nn.tt
      abort(usage(m"signing needs Java 24 or later, which provides ML-DSA, but xek is running on Java $version"))

  // A fresh seed, from the platform's source of randomness.
  def seed(): scala.Array[Byte] =
    val seed = new scala.Array[Byte](seedSize)
    js.SecureRandom().nextBytes(seed)
    seed

  // The key pair a seed denotes, generated by a source of randomness which yields the seed once.
  private def pair(seed: scala.Array[Byte]): js.KeyPair raises Assembler.Error =
    ensureAvailable()
    var draws: Int = 0
    var exact: Boolean = true

    val source: js.SecureRandom = new js.SecureRandom():
      override def nextBytes(bytes: scala.Array[Byte] | Null): Unit =
        val target = bytes.nn
        draws += 1
        if target.length == seed.length then java.lang.System.arraycopy(seed, 0, target, 0, seed.length)
        else exact = false

    val generator = js.KeyPairGenerator.getInstance(algorithm).nn
    generator.initialize(jss.NamedParameterSpec(algorithm), source)
    val pair = generator.generateKeyPair().nn

    if draws != 1 || !exact
    then abort(usage(m"this JDK does not derive ML-DSA keys from a single 32-byte seed, so it cannot sign"))

    pair

  // The raw 1312-byte public key of the key pair a seed denotes.
  def publicKey(seed: scala.Array[Byte]): scala.Array[Byte] raises Assembler.Error =
    val encoded: scala.Array[Byte] = pair(seed).getPublic.nn.getEncoded.nn

    if encoded.length != publicHeader.length + Record.keySize
       || !java.util.Arrays.equals(encoded, 0, publicHeader.length, publicHeader, 0, publicHeader.length)
    then abort(usage(m"this JDK encodes ML-DSA public keys in an unexpected form"))

    java.util.Arrays.copyOfRange(encoded, publicHeader.length, encoded.length).nn

  // The offset of an executable's record: the first magic with a whole record behind it.
  def record(bytes: scala.Array[Byte]): Optional[Int] =
    val magic: scala.Array[Byte] = Record.magic
    val last: Int = bytes.length - Record.size
    var index: Int = 0
    var found: Optional[Int] = Unset

    while found.absent && index <= last do
      if java.util.Arrays.equals(bytes, index, index + magic.length, magic, 0, magic.length) then found = index
      index += 1

    found

  // `"XEKSIGN\x04" ‖ SHA3-256(the file, with its signature slot read as zeros)`, hashed in place.
  def statement(bytes: scala.Array[Byte], record: Int): scala.Array[Byte] =
    val start: Int = record + Record.signatureOffset
    val end: Int = start + Record.signatureSize
    val digest = js.MessageDigest.getInstance("SHA3-256").nn
    digest.update(bytes, 0, start)
    digest.update(new scala.Array[Byte](Record.signatureSize))
    digest.update(bytes, end, bytes.length - end)
    statementPrefix ++ digest.digest().nn

  def sign(statement: scala.Array[Byte], seed: scala.Array[Byte]): scala.Array[Byte] raises Assembler.Error =
    val signer = js.Signature.getInstance(algorithm).nn
    signer.initSign(pair(seed).getPrivate)
    signer.update(statement)
    signer.sign().nn

  // Whether `signature` verifies over `statement` under the raw public key `key`. A key or a
  // signature which is malformed does not verify.
  def verifies(statement: scala.Array[Byte], signature: scala.Array[Byte], key: scala.Array[Byte])
  :   Boolean raises Assembler.Error =

    ensureAvailable()

    try
      val factory = js.KeyFactory.getInstance(algorithm).nn
      val public = factory.generatePublic(jss.X509EncodedKeySpec(publicHeader ++ key))
      val verifier = js.Signature.getInstance(algorithm).nn
      verifier.initVerify(public)
      verifier.update(statement)
      verifier.verify(signature)
    catch
      case _: js.GeneralSecurityException => false
      case _: IllegalArgumentException    => false

  // An executable read for signing: its bytes, and the offset of its record.
  private class Executable(val bytes: scala.Array[Byte], val record: Int):
    def field(offset: Int, length: Int): scala.Array[Byte] =
      java.util.Arrays.copyOfRange(bytes, record + offset, record + offset + length).nn

    def releaseKey: scala.Array[Byte] = field(Record.releaseKeyOffset, Record.keySize)
    def recoveryKey: scala.Array[Byte] = field(Record.recoveryKeyOffset, Record.keySize)
    def appId: scala.Array[Byte] = field(Record.appIdOffset, 32)
    def signature: scala.Array[Byte] = field(Record.signatureOffset, Record.signatureSize)

    def buildId: Long =
      java.nio.ByteBuffer.wrap(bytes, record + 8, 8).nn.order(java.nio.ByteOrder.LITTLE_ENDIAN).nn.getLong

    // The flags are part of what is signed, so they are set before the statement is taken.
    def permitDowngrade(permit: Boolean): Unit = bytes(record + 21) = (if permit then 1 else 0).toByte

    def statement: scala.Array[Byte] = Signer.statement(bytes, record)

    def attach(signature: scala.Array[Byte]): Unit =
      java.lang.System.arraycopy(signature, 0, bytes, record + Record.signatureOffset, Record.signatureSize)

    // Which of the record's own keys `key` is.
    def role(key: scala.Array[Byte]): Optional[Text] =
      if !zero(key) && java.util.Arrays.equals(key, releaseKey) then t"release"
      else if !zero(key) && java.util.Arrays.equals(key, recoveryKey) then t"recovery"
      else Unset

  private def zero(bytes: scala.Array[Byte]): Boolean = bytes.forall(_ == 0)

  private def executable(path: Path on Local): Executable raises Assembler.Error =
    val bytes: scala.Array[Byte] = Files.read(path)
    val offset: Int = record(bytes).lest(malformed(m"${path.encode} has no ETHRCFG v4 record"))
    Executable(bytes, offset)

  // Runs one of the subcommands which sign a release, as `Command` parsed it. `path` resolves a
  // word against the invocation's working directory, and `variable` reads its environment.
  def run
    ( action:   Command.Action,
      parsed:   Command.Parsed,
      path:     Text => Path on Local,
      variable: Text => Optional[Text] )
    ( out: Text => Unit, err: Text => Unit )
  :   Unit raises Assembler.Error =

    if !parsed.operands.nil then
      val extra: Text = parsed.operands.join(t" ")
      abort(usage(m"xek ${action.name} takes no operands, but was given $extra"))

    def required(spec: Command.Spec): Path on Local =
      path(parsed.value(spec).lest(usage(m"xek ${action.name} needs --${spec.name}")))

    def downgrade: Boolean = parsed.has(Command.AllowDowngrade)

    action match
      case Command.Action.Keygen =>
        val prefix: Path on Local = required(Command.Prefix)
        val seed: scala.Array[Byte] = Signer.seed()
        val public: scala.Array[Byte] = publicKey(seed)
        val seedFile: Path on Local = Files.sibling(prefix, t"${prefix.name}.seed")
        val publicFile: Path on Local = Files.sibling(prefix, t"${prefix.name}.pub")

        if Files.exists(publicFile) then abort(usage(m"${publicFile.encode} already exists; xek will not overwrite it"))

        create(seedFile, seed, secret = true)
        create(publicFile, public, secret = false)
        err(t"Wrote ${seedFile.encode}, the seed, which must be kept secret, and ${publicFile.encode}")

      case Command.Action.PublicKeyOf =>
        val target: Path on Local = required(Command.Out)
        val public: scala.Array[Byte] = publicKey(seedOf(parsed, path, variable))
        Files.write(target)(_.write(public))
        err(t"Wrote ${target.encode}")

      case Command.Action.Sign =>
        val seed: scala.Array[Byte] = seedOf(parsed, path, variable)
        val binary: Executable = executable(required(Command.In))
        val target: Path on Local = required(Command.Out)
        binary.permitDowngrade(downgrade)

        binary.role(publicKey(seed)).let { role => err(t"Signing with the record's $role key") }.or:
          if !parsed.has(Command.ForeignKey) then
            abort:
              usage:
                m"""the key is neither the record's release key nor its recovery key; give
                    --foreign-key if that is intended, as for the release which follows a rotation"""

          err(t"Signing with a key the record does not carry (--foreign-key)")

        binary.attach(sign(binary.statement, seed))
        Files.write(target, executable = true)(_.write(binary.bytes))
        err(t"Wrote ${target.encode}, build ${binary.buildId}")

      case Command.Action.Statement =>
        val binary: Executable = executable(required(Command.In))
        binary.permitDowngrade(downgrade)
        out(hex(binary.statement))

      case Command.Action.Attach =>
        val binary: Executable = executable(required(Command.In))
        val signatureFile: Path on Local = required(Command.Signature)
        val target: Path on Local = required(Command.Out)
        val signature: scala.Array[Byte] = Files.read(signatureFile)

        if signature.length != Record.signatureSize then
          val size: Int = signature.length
          abort(malformed(m"${signatureFile.encode} is $size bytes, not a ${Record.signatureSize}-byte ML-DSA-44 signature"))

        binary.permitDowngrade(downgrade)
        val statement: scala.Array[Byte] = binary.statement

        val role: Optional[Text] =
          if !zero(binary.releaseKey) && verifies(statement, signature, binary.releaseKey) then t"release"
          else if !zero(binary.recoveryKey) && verifies(statement, signature, binary.recoveryKey)
          then t"recovery"
          else Unset

        role.let { role => err(t"The signature verifies under the record's $role key") }.or:
          err(t"The signature verifies under neither of the record's keys: is it a foreign key?")

        binary.attach(signature)
        Files.write(target, executable = true)(_.write(binary.bytes))
        err(t"Wrote ${target.encode}")

      case Command.Action.Verify =>
        val keyFile: Path on Local = required(Command.VerifyKey)
        val key: scala.Array[Byte] = Files.read(keyFile)

        if key.length != Record.keySize
        then abort(malformed(m"${keyFile.encode} is not a ${Record.keySize}-byte public key"))

        val binary: Executable = executable(required(Command.In))

        parsed.value(Command.VerifyApp).let: id =>
          if !java.util.Arrays.equals(binary.appId, Record.hash(id))
          then abort(Assembler.Error(Assembler.Fault.Verification, m"the executable is not for the application $id"))

        if !verifies(binary.statement, binary.signature, key)
        then abort(Assembler.Error(Assembler.Fault.Verification, m"the signature does not verify under that key"))

        out(binary.buildId.toString.tt)

      case Command.Action.Build =>
        abort(usage(m"xek build does not sign"))

  // The seed, from `--key <file>`, or from `--key-env <variable>` as 64 hexadecimal digits, so that
  // a secret need never be written to disk. It is never accepted as an argument.
  private def seedOf(parsed: Command.Parsed, path: Text => Path on Local, variable: Text => Optional[Text])
  :   scala.Array[Byte] raises Assembler.Error =

    val file: Optional[Text] = parsed.value(Command.Key)
    val name: Optional[Text] = parsed.value(Command.KeyEnv)

    if file.present == name.present then abort(usage(m"give exactly one of --key or --key-env"))

    val seed: scala.Array[Byte] = file.let { file => Files.read(path(file)) }.or:
      val text: Text = variable(name.or(t"")).lest(usage(m"the environment variable ${name.or(t"")} is not set"))
      unhex(text.trim).lest(usage(m"${name.or(t"")} must hold the seed as 64 hexadecimal digits"))

    if seed.length != seedSize then
      val size: Int = seed.length
      abort(malformed(m"a seed is $seedSize bytes, but this one is $size"))

    seed

  // Writes a file which must not exist already, readable only by its owner if it is secret and the
  // filesystem has POSIX permissions.
  private def create(path: Path on Local, bytes: scala.Array[Byte], secret: Boolean)
  :   Unit raises Assembler.Error =

    val target: jnf.Path = Files.javaPath(path)

    val posix: Boolean =
      jnf.FileSystems.getDefault.nn.supportedFileAttributeViews.nn.contains("posix")

    try
      if secret && posix then
        val owner = jnfa.PosixFilePermissions.asFileAttribute(jnfa.PosixFilePermissions.fromString("rw-------"))
        jnf.Files.createFile(target, owner)
      else jnf.Files.createFile(target)

      jnf.Files.write(target, bytes)

    catch
      case error: jnf.FileAlreadyExistsException =>
        abort(usage(m"${path.encode} already exists; xek will not overwrite it"))

      case error: ji.IOException =>
        abort(usage(m"could not write ${path.encode}"))

  def hex(bytes: scala.Array[Byte]): Text =
    val builder = StringBuilder()
    var index = 0

    while index < bytes.length do
      builder.append(String.format("%02x", Integer.valueOf(bytes(index) & 0xff)))
      index += 1

    builder.toString.tt

  def unhex(text: Text): Optional[scala.Array[Byte]] =
    val string: String = text.s
    if string.length%2 != 0 || !string.forall(Character.digit(_, 16) >= 0) then Unset
    else bytes(string)

  private def bytes(hex: String): scala.Array[Byte] =
    scala.Array.tabulate(hex.length/2): index =>
      Integer.parseInt(hex.substring(2*index, 2*index + 2), 16).toByte
