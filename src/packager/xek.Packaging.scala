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
import galilei.*
import prepositional.*
import serpentine.*
import vacuous.*

// A complete, declarative description of how to turn an application into a
// distributable. `Packager.pack` builds it with `core`, exactly as the `xek` command would.
object Packaging:
  // How the per-platform binaries reach the user.
  enum Delivery:
    case EmbedAll
    // Online: the JAR is embedded once and each bare stub is downloaded at runtime from the
    // `ClientSource.Remote` base URL (so `Download` requires a `Remote` client source).
    case Download
    case Native

  // Where each platform's bare reusable client stub comes from. The stubs are published
  // independently (by tagging `X.Y.Z`); a build never compiles them.
  enum ClientSource:
    // Read `<directory>/client-<label>[.exe]` from a local directory (e.g. the output of
    // `make client-build`). For development and testing — no download, no hash check.
    case Local(directory: Path on Linux)

    // Download each stub from `<baseUrl>/client-<label>[.exe]` and verify it against
    // `hashes(label)` (lowercase SHA-256 hex, from the committed `etc/client/<v>.tsv`
    // manifest). The production source.
    case Remote(baseUrl: Text, hashes: Map[Text, Text])

  object ClientSource:
    // The published stubs named in `res/core/xek/client.*`.
    def standard: ClientSource = Remote(Client.baseUrl, Client.hashes)

  // The bundled Java runtime *preference* recorded in the ETHRCFG block. Records
  // a preference only — the client downloads a JRE/JDK at runtime; nothing is
  // embedded in the artifact.
  enum Bundle:
    case Jre, Jdk

  // How the application's classes reach the runtime.
  enum Dependencies:
    case FatJar(jar: Path on Linux)         // the fat jar, appended to the client as-is
    case BurdockRemote(jar: Path on Linux)  // a macro-built thin jar that fetches deps (Stage C)

  case class JavaPolicy(minimum: Int = 21, preferred: Int = 24, bundle: Bundle = Bundle.Jre)

  // The keys self-upgrades are verified against, written into each executable's record. `Unset`
  // overall disables upgrades (the safe default). Signing a release is a separate step, with
  // `xek-sign`, which never happens inside a build that also holds the application's code.
  case class Signing
    ( publicKey:      Optional[Path on Linux] = Unset, // the release key
      recoveryKey:    Optional[Path on Linux] = Unset, // a second key, kept offline
      allowDowngrade: Boolean                 = false )

case class Packaging
  ( name:         Text,
    targets:      List[Text],
    delivery:     Packaging.Delivery,
    dependencies: Packaging.Dependencies,
    output:       Path on Linux,
    clientSource: Packaging.ClientSource,
    java:         Packaging.JavaPolicy        = Packaging.JavaPolicy(),
    signing:      Optional[Packaging.Signing] = Unset,
    appId:        Optional[Text]              = Unset,
    buildId:      Long                        = 0L )
