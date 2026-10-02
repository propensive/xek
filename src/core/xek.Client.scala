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
import gossamer.*
import hellenism.*
import hieroglyph.*
import rudiments.*
import turbulence.*
import vacuous.*

import charsets.utf8Charset
import textSanitizers.skipSanitizer

// The reusable native client stubs are published independently of any application, as a
// GitHub release whose assets are one bare stub per platform, and are verified against a
// manifest of their SHA-256 hashes. Which release that is, and where it lives, is not
// compiled in: each release's record pull request writes the three resources read below, so
// publishing a new set of stubs changes data, not code.
//
// The manifest is the same tab-separated format as `etc/client/<version>.tsv` — one
// `<label>\t<sha256>` line per platform — so the archived manifests and the embedded one
// are interchangeable, and `etc/ci/client-fetch.sh` reads either.
object Client:
  // The resources travel with this class, so they are read through the classloader that
  // defines it: the thread's may be another application's — a test runner's, say — holding
  // another copy of xek with other resources of the same names.
  private given classloader: Classloader = Classloader[Client.type]

  // The published release these hashes came from.
  lazy val version: Text = cp"/xek/client.version".read[Text].trim

  // Where that release's assets are downloaded from, without a trailing slash. Held as data
  // rather than derived from `version`, so that a set of stubs can be republished — or
  // mirrored — without changing this code.
  lazy val baseUrl: Text = cp"/xek/client.url".read[Text].trim

  // Lowercase SHA-256 hex of each published stub, by platform label.
  lazy val hashes: Map[Text, Text] = Stubs.manifest(cp"/xek/client.tsv".read[Text])

  // The published stubs, as a source to build from.
  def standard: Stubs.Source = Stubs.Source.Remote(baseUrl, hashes)

  // Every platform the published release names.
  def labels: List[Text] = hashes.keys.to[List]

  // The published filename for a platform's bare client stub (Windows stubs carry `.exe`).
  def clientName(label: Text): Text =
    if label.starts(t"windows") then t"client-$label.exe" else t"client-$label"

  // The URL a platform's bare client stub is published at.
  def url(label: Text): Text = t"$baseUrl/${clientName(label)}"
