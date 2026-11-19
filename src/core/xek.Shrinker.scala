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
import java.time as jt
import java.util as ju
import java.util.jar as juj
import java.util.zip as juz

import abscission.Shrink
import ambience.*
import anticipation.*
import aperture.*
import contingency.*
import digression.*
import distillate.*
import fulminate.*
import galilei.*
import gossamer.*
import hellenism.*
import prepositional.*
import rudiments.*
import serpentine.*
import vacuous.*

import errorDiagnostics.emptyDiagnostics
import filesystemBackends.javaBaseFilesystem
import systems.javaBaseSystem
import temporaryDirectories.systemTemporaryDirectory

// `--shrink`: a JAR reduced to the closed world its main class reaches, by Soundness's
// `abscission`, before anything is built from it. Classes this version of the compiler compiled,
// whose TASTy is in the JAR, are recompiled without the members nothing reaches; the rest are
// kept or left out a class at a time. What is built is then built from the smaller JAR, exactly
// as it would have been from the original.
object Shrinker:
  private def malformed(site: Int, message: Message)(using Diagnostics): Assembler.Error =
    Assembler.Error(Assembler.Fault.Format, site, message)

  // Every entry is given the same time, the earliest a ZIP can hold, so that the same classes
  // make the same JAR whenever they are shrunk.
  private val epoch: jt.LocalDateTime = jt.LocalDateTime.of(1980, 1, 1, 0, 0).nn

  // Runs `body` on the JAR to build from: `jar` itself, or, when `keep` is set, the JAR it
  // shrinks to, which exists only while `body` runs. `keep` are the patterns of the classes to
  // keep whatever reaches them, and `main` the JAR's `Main-Class`, the one entry point.
  def shrinking[result](jar: Path on Local, main: Text, keep: Optional[List[Text]])(progress: Text => Unit)
    ( body: Path on Local => result )
  :   result raises Assembler.Error =

    keep.lay(body(jar)): patterns =>
      mitigate:
        case error: Io.Error =>
          Assembler.Error(Assembler.Fault.Usage, 69, m"there is no temporary directory to shrink ${jar.encode} in")

      . protect:
          temporaryDirectory[Path on Linux].open[Scratch](Read & Write): scratch ?=>
            val directory: jnf.Path = jnf.Path.of(scratch.stem.encode.s).nn
            jnf.Files.createDirectories(directory)
            body(shrink(jar, main, patterns, directory)(progress))

  private def shrink(jar: Path on Local, main: Text, keep: List[Text], scratch: jnf.Path)
    ( progress: Text => Unit )
  :   Path on Local raises Assembler.Error =

    val entryPoint: Fqcn =
      safely(Fqcn(main)).lest(malformed(17, m"the Main-Class of ${jar.encode}, $main, is not the name of a class"))

    val classes: jnf.Path = scratch.resolve("classes").nn
    val output: Path on Local = Files.local(scratch.resolve(jar.name.s).nn)

    progress(t"Shrinking ${jar.encode}")

    val report: Shrink.Report =
      mitigate:
        case Shrink.Error(reason) =>
          val detail: Text = reason match
            case Shrink.Error.Reason.Unsound(problem :: _ :: _) => t"$problem, among other problems"
            case Shrink.Error.Reason.Unsound(problem :: Nil)    => problem
            case Shrink.Error.Reason.Unsound(Nil)               => t"what it keeps would not be whole"
            case Shrink.Error.Reason.Failed(trace)              => t"${trace.className}: ${trace.message.text}"

          malformed(16, m"${jar.encode} could not be shrunk: $detail")

      . protect:
          Shrink(keep = keep)
            ( LocalClasspath(Classpath.Entry.Jar(jar.encode)),
              List(entryPoint),
              unsafely(classes.toString.tt.as[Path on Linux]) )

    archive(jar, classes, output)

    val before: Long = jnf.Files.size(Files.javaPath(jar)) / 1024
    val after: Long = jnf.Files.size(Files.javaPath(output)) / 1024
    progress(t"Shrunk it from $before kB to $after kB, keeping ${report.kept} of ${report.classes} classes")

    output

  // The closed world as a JAR. Its manifest is the original's, which the shrinker leaves out,
  // less the per-entry sections: those are the digests of a signed JAR, and its signature is not
  // kept, since it could not hold. The entries are in the order of their names.
  private def archive(original: Path on Local, classes: jnf.Path, output: Path on Local)
  :   Unit raises Assembler.Error =

    Files.write(output): stream =>
      val source = juj.JarFile(Files.javaPath(original).toFile)
      val manifest: juj.Manifest = try source.getManifest().nn finally source.close()
      manifest.getEntries.nn.clear()

      val jar = juz.ZipOutputStream(stream)

      def entry(name: String)(write: => Unit): Unit =
        val entry = juz.ZipEntry(name)
        entry.setTimeLocal(epoch)
        jar.putNextEntry(entry)
        write
        jar.closeEntry()

      entry(juj.JarFile.MANIFEST_NAME.nn)(manifest.write(jar))

      val walk = jnf.Files.walk(classes).nn

      val files: ju.TreeMap[String, jnf.Path] = ju.TreeMap()

      try
        walk.forEach: file =>
          if jnf.Files.isRegularFile(file) then
            val name: String = classes.relativize(file).nn.toString.replace(java.io.File.separatorChar, '/').nn
            if name != juj.JarFile.MANIFEST_NAME then files.put(name, file.nn)

      finally walk.close()

      files.forEach: (name, file) =>
        entry(name.nn)(jnf.Files.copy(file, jar))

      jar.finish()
