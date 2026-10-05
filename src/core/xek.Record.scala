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
import fulminate.*
import gossamer.*
import vacuous.*

// The 5108-byte ETHRCFG v4 record which configures a client stub for one application, laid out
// exactly as `spec/ethrcfg.md` describes. A builder writes it between the stub and the JAR; the
// client finds it by its magic.
//
// `appId` is the application's identifier — `propensive/fume`, say — which the record holds as
// its SHA3-256. A release key with no application id would build an executable that could never
// upgrade, and a recovery key with no release key one that would never check it, so both are
// refused.
case class Record
  ( buildId:        Long            = 0L,
    javaMinimum:    Int             = Record.javaMinimum,
    javaPreferred:  Int             = Record.javaPreferred,
    jdk:            Boolean         = false,
    allowDowngrade: Boolean         = false,
    appId:          Optional[Text]  = Unset,
    releaseKey:     Optional[Data]  = Unset,
    recoveryKey:    Optional[Data]  = Unset ):

  def data: Data raises Assembler.Error =
    if javaMinimum < 0 || javaMinimum > 0xffff || javaPreferred < 0 || javaPreferred > 0xffff
    then abort(Assembler.Error(Assembler.Fault.Usage, m"a Java version must be between 0 and 65535"))

    if releaseKey.present && appId.absent
    then abort(Assembler.Error(Assembler.Fault.Usage, m"a release key needs an application id"))

    if recoveryKey.present && releaseKey.absent
    then abort(Assembler.Error(Assembler.Fault.Usage, m"a recovery key needs a release key"))

    val buffer = java.nio.ByteBuffer.allocate(Record.size).nn
    buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
    buffer.put(Record.magic)
    buffer.putLong(buildId)
    buffer.putShort(javaMinimum.toShort)
    buffer.putShort(javaPreferred.toShort)
    buffer.put((if jdk then 1 else 0).toByte)
    buffer.put((if allowDowngrade then 1 else 0).toByte)

    appId.let: id =>
      buffer.position(Record.appIdOffset)
      buffer.put(Record.hash(id))

    def key(key: Optional[Data], offset: Int, name: Text): Unit = key.let: key =>
      val bytes: scala.Array[Byte] = Array.unsafeJvm(key)

      if bytes.length != Record.keySize then
        val size: Int = bytes.length
        abort(Assembler.Error(Assembler.Fault.Format, m"a $name key must be ${Record.keySize} bytes, but is $size"))

      buffer.position(offset)
      buffer.put(bytes)

    key(releaseKey, Record.releaseKeyOffset, t"release")
    key(recoveryKey, Record.recoveryKeyOffset, t"recovery")

    Array.unsafeFrozen(buffer.array().nn)

object Record:
  // The defaults a client reads for an unset (zero) field.
  val javaMinimum: Int = 21
  val javaPreferred: Int = 24

  val size: Int = 5108
  val appIdOffset: Int = 32
  val releaseKeyOffset: Int = 64
  val recoveryKeyOffset: Int = 1376
  val keySize: Int = 1312
  val signatureOffset: Int = 2688
  val signatureSize: Int = 2420

  // `ETHRCFG` and the format version, 4. No stub contains these bytes (`spec/ethrcfg.md`).
  def magic: scala.Array[Byte] =
    val bytes = new scala.Array[Byte](8)
    System.arraycopy("ETHRCFG".getBytes("US-ASCII").nn, 0, bytes, 0, 7)
    bytes(7) = 4
    bytes

  // The SHA3-256 of an application's identifier, as UTF-8: what the record holds as `app_id`.
  def hash(appId: Text): scala.Array[Byte] =
    java.security.MessageDigest.getInstance("SHA3-256").nn.digest(appId.s.getBytes("UTF-8")).nn
