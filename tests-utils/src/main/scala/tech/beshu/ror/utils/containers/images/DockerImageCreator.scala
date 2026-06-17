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
    val stableTag = s"ror-it-es:${imageTag(imageDescription)}"
    copyFilesFrom(imageDescription, to = new ImageFromDockerfile(stableTag, /* deleteOnExit = */ false))
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

  private def copyFilesFrom(imageDescription: DockerImageDescription, to: ImageFromDockerfile) = {
    // Each image build gets its OWN private staging dir, and we hand testcontainers a per-build COPY
    // of every source file rather than the shared on-disk original. testcontainers tar-streams these
    // into the Docker build context lazily; when N parallel worker JVMs build the same image they all
    // pointed at the SAME files (e.g. the ROR plugin zip), and concurrent tar-streaming corrupted the
    // context ("Request to write N bytes exceeds size in header" → plugin install fails). Private
    // per-build copies make image building parallel-safe (observed at IT_MAX_PARALLEL_FORKS=3).
    val stagingDir = File.newTemporaryDirectory(prefix = s"ror-img-${UUID.randomUUID()}-")
    stagingDir.toJava.deleteOnExit()
    imageDescription
      .copyFiles
      .zipWithIndex
      .foldLeft(to) {
        case (dockerfile, (copyFile, idx)) =>
          val source = File(copyFile.file.pathAsString)
          // index-prefix the staged name so two sources sharing a filename can't clobber each other
          val privateCopy = source.copyTo(stagingDir / s"$idx-${source.name}", overwrite = true)
          privateCopy.toJava.deleteOnExit()
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
