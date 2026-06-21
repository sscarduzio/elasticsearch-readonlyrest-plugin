/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.utils.containers.images

import better.files.File
import com.typesafe.scalalogging.StrictLogging
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.images.builder.dockerfile.DockerfileBuilder
import tech.beshu.ror.utils.containers.images.DockerImageDescription.Command
import tech.beshu.ror.utils.containers.images.DockerImageDescription.Command.{ChangeUser, Run}

import java.security.MessageDigest
import java.util.UUID

object DockerImageCreator extends StrictLogging {

  def create(elasticsearch: Elasticsearch): ImageFromDockerfile = {
    val imageDescription = elasticsearch.toDockerImageDescription
    // STABLE, content-derived tag (vs testcontainers' random per-instance tag). Two builds of an
    // identical image (e.g. the singleton ES image, baked the same way in every worker) resolve to
    // the SAME tag, so once it's built locally every other worker gets an instant cache hit instead
    // of redundantly (and concurrently) rebuilding it — which is what fails at IT_MAX_PARALLEL_FORKS>=3.
    //
    // deleteOnExit = TRUE (testcontainers' default, as master used for 5 years): the image is removed
    // when its container stops, so per-suite custom images (each suite's readonlyrest.yml is baked in,
    // → a distinct image per config) don't accumulate and fill the disk. A leg builds ~15 such images;
    // at ~1GB each that overflowed the ~14GB hosted disk mid-leg ("No space left on device") once we
    // had switched this to false. The stable tag still gives the parallel-build cache hit; auto-clean
    // still reclaims each image after use. The long-lived singleton container keeps ITS image alive as
    // long as it's needed (it only stops at JVM end), so cleanup never pulls the singleton out early.
    val stableTag = s"ror-it-es:${imageTag(imageDescription)}"
    copyFilesFrom(imageDescription, to = new ImageFromDockerfile(stableTag, /* deleteOnExit = */ true))
      .withDockerfileFromBuilder((builder: DockerfileBuilder) => {
        val dockerfile = builder
          .from(imageDescription.baseImage)
          .copyFilesFrom(imageDescription)
          .runCommandsFrom(imageDescription)
          .addEnvsFrom(imageDescription)
          .setEntrypointFrom(imageDescription)
          .setCommandFrom(imageDescription)
          .build()
        logger.info("Dockerfile\n" + dockerfile)
      })
  }

  // Short hex digest over everything that defines the image: base image, the dockerfile commands/envs,
  // and the CONTENT of every copied file (so a changed ROR plugin zip / cert / settings -> new tag).
  private def imageTag(d: DockerImageDescription): String = {
    val md = MessageDigest.getInstance("SHA-1")
    md.update(d.baseImage.getBytes("UTF-8"))
    d.runCommands.foreach(c => md.update(c.toString.getBytes("UTF-8")))
    d.envs.foreach(e => md.update(s"${e.name}=${e.value}".getBytes("UTF-8")))
    d.copyFiles.toList.sortBy(_.destination.toString).foreach { cf =>
      md.update(cf.destination.toString.getBytes("UTF-8"))
      val f = File(cf.file.pathAsString)
      if (f.exists) md.update(f.sha256.getBytes("UTF-8"))
    }
    md.digest().take(10).map("%02x".format(_)).mkString
  }

  // ONE staging dir PER JVM (not per build). We hand testcontainers a private COPY of every source
  // file rather than the shared on-disk original — when N parallel worker JVMs build the same image
  // they otherwise point at the SAME files (e.g. the ROR plugin zip) and concurrent tar-streaming
  // corrupts the build context ("Request to write N bytes exceeds size in header" → plugin install
  // fails). A separate dir per JVM (UUID) keeps cross-JVM builds parallel-safe.
  //
  // WITHIN a JVM, image builds run serially (the singleton model), so we REUSE this one dir and wipe
  // it at the start of every build. The old code made a fresh ror-img-<UUID> per build with
  // deleteOnExit() — so every ES image's staged corretto JDK + ROR zip accumulated until JVM exit and
  // filled the disk mid-leg (15GB→2.8GB→"No space left on device" on the ~15GB-free hosted runner).
  // Reusing + wiping caps staging at ONE build's worth instead of N.
  private lazy val stagingDir: File = {
    val dir = File.newTemporaryDirectory(prefix = s"ror-img-${UUID.randomUUID()}-")
    dir.toJava.deleteOnExit() // backstop for the final build's files; per-build wipe handles the rest
    dir
  }

  private def copyFilesFrom(imageDescription: DockerImageDescription, to: ImageFromDockerfile) = {
    // Wipe the PREVIOUS build's staged files (its image is already built — serial-within-JVM), so disk
    // never accumulates across the leg's image builds. Recreate the (now empty) dir.
    if (stagingDir.exists) stagingDir.clear()
    imageDescription
      .copyFiles
      .zipWithIndex
      .foldLeft(to) {
        case (dockerfile, (copyFile, idx)) =>
          val source = File(copyFile.file.pathAsString)
          // index-prefix the staged name so two sources sharing a filename can't clobber each other
          val privateCopy = source.copyTo(stagingDir / s"$idx-${source.name}", overwrite = true)
          dockerfile.withFileFromFile(copyFile.destination.toIO.getAbsolutePath, privateCopy.toJava)
      }
  }

  private implicit class BuilderExt(val builder: DockerfileBuilder) extends AnyVal {

    def runCommandsFrom(imageDescription: DockerImageDescription): DockerfileBuilder = {
      imageDescription
        .runCommands
        .foldLeft(List.empty[Command]) {
          case (acc, c@ChangeUser(_)) => acc :+ c
          case (acc, r@Run(command)) =>
            acc.reverse match {
              case Nil => r :: Nil
              case ChangeUser(_) :: _ => acc ::: (r :: Nil)
              case Run(lastCommand) :: tail => (Run(s"$lastCommand && $command") :: tail).reverse
            }
        }
        .foldLeft(builder) {
          case (b, ChangeUser(user)) => b.user(user)
          case (b, Run(command)) => b.run(command)
        }
    }

    def copyFilesFrom(imageDescription: DockerImageDescription): DockerfileBuilder = {
      imageDescription
        .copyFiles
        .foldLeft(builder) { case (b, file) =>
          b.copy(file.destination.toString(), file.destination.toString())
        }
    }

    def addEnvsFrom(imageDescription: DockerImageDescription): DockerfileBuilder = {
      imageDescription
        .envs
        .foldLeft(builder) { case (b, env) => b.env(env.name, env.value) }
    }

    def setEntrypointFrom(imageDescription: DockerImageDescription): DockerfileBuilder = {
      imageDescription
        .entrypoint
        .foldLeft(builder) { case (b, entrypoint) => b.entryPoint(entrypoint.toIO.getAbsolutePath) }
    }

    def setCommandFrom(imageDescription: DockerImageDescription): DockerfileBuilder = {
      imageDescription
        .command
        .foldLeft(builder) { case (b, command) => b.cmd(command) }
    }

  }
}
