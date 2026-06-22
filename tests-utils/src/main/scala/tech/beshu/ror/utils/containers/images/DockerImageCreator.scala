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
    // STABLE content-derived tag: identical images share one tag → cache hit across parallel workers (no
    // concurrent rebuilds, fails at shardCount>=3). deleteOnExit=true reclaims per-suite images post-use.
    val stableTag = s"ror-it-es:${imageTag(imageDescription)}"
    copyFilesFrom(imageDescription, to = new ImageFromDockerfile(stableTag, /* deleteOnExit = */ true))
      .withDockerfileFromBuilder((builder: DockerfileBuilder) => {
        val dockerfile = builder
          .from(imageDescription.baseImage)
          .applyStepsFrom(imageDescription) // COPY/RUN/USER in DECLARATION order (config files last)
          .addEnvsFrom(imageDescription)
          .setEntrypointFrom(imageDescription)
          .setCommandFrom(imageDescription)
          .build()
        logger.info("Dockerfile\n" + dockerfile)
      })
  }

  // Short hex digest over everything that defines the image (base, commands, envs, copied-file CONTENT)
  // so a changed plugin zip / cert / settings yields a new tag.
  private def imageTag(d: DockerImageDescription): String = {
    val md = MessageDigest.getInstance("SHA-1")
    md.update(d.baseImage.getBytes("UTF-8"))
    d.steps.foreach { case c: Command.Run => md.update(c.toString.getBytes("UTF-8"))
                      case u: Command.ChangeUser => md.update(u.toString.getBytes("UTF-8"))
                      case _: Command.Copy => () } // copy CONTENT hashed below (order-independent, sorted)
    // envs is a Set — sort so iteration order can't vary the digest (order-dependent hash would cause
    // spurious cache misses → redundant concurrent rebuilds).
    d.envs.toList.sortBy(e => (e.name, e.value)).foreach(e => md.update(s"${e.name}=${e.value}".getBytes("UTF-8")))
    d.copyFiles.toList.sortBy(_.destination.toString).foreach { cf =>
      md.update(cf.destination.toString.getBytes("UTF-8"))
      val f = File(cf.file.pathAsString)
      if (f.exists) md.update(f.sha256.getBytes("UTF-8"))
    }
    // entrypoint/command also define the image — fold them in so images differing only there can't
    // collide to the same tag (false cache hit running the wrong launcher).
    d.entrypoint.foreach(p => md.update(p.toString.getBytes("UTF-8")))
    d.command.foreach(c => md.update(c.getBytes("UTF-8")))
    md.digest().take(10).map("%02x".format(_)).mkString
  }

  private def copyFilesFrom(imageDescription: DockerImageDescription, to: ImageFromDockerfile) = {
    // FRESH staging dir PER BUILD (UUID): hand testcontainers a private copy of each source so parallel
    // cluster-node builds (parSequenceUnordered) can't clobber a shared dir mid-build and corrupt context.
    val stagingDir = File.newTemporaryDirectory(prefix = s"ror-img-${UUID.randomUUID()}-")
    val dockerfile = imageDescription
      .copyFiles
      .zipWithIndex
      .foldLeft(to) {
        case (dockerfile, (copyFile, idx)) =>
          val source = File(copyFile.file.pathAsString)
          // index-prefix the staged name so two sources sharing a filename can't clobber each other
          val privateCopy = source.copyTo(stagingDir / s"$idx-${source.name}", overwrite = true)
          dockerfile.withFileFromFile(copyFile.destination.toIO.getAbsolutePath, privateCopy.toJava)
      }
    // Best-effort reclaim once the build context is consumed; deleteOnExit is the safety net.
    stagingDir.toJava.deleteOnExit()
    dockerfile
  }

  private implicit class BuilderExt(val builder: DockerfileBuilder) extends AnyVal {

    // Emit COPY/RUN/USER in DECLARATION order so config files land AFTER heavy config-independent RUNs,
    // keeping those layers shared. Adjacent Runs merge into one `&&` RUN; a COPY/USER breaks the merge.
    def applyStepsFrom(imageDescription: DockerImageDescription): DockerfileBuilder = {
      imageDescription.steps
        .foldLeft(List.empty[Command]) {
          case (acc, r@Run(command)) =>
            acc.reverse match {
              case Run(lastCommand) :: tail => (Run(s"$lastCommand && $command") :: tail).reverse
              case _ => acc :+ r
            }
          case (acc, other) => acc :+ other // ChangeUser / Copy are layer boundaries; never merged
        }
        .foldLeft(builder) {
          case (b, ChangeUser(user)) => b.user(user)
          case (b, Run(command)) => b.run(command)
          case (b, Command.Copy(file)) => b.copy(file.destination.toString(), file.destination.toString())
        }
    }

    def addEnvsFrom(imageDescription: DockerImageDescription): DockerfileBuilder = {
      imageDescription
        .envs
        .toList.sortBy(_.name) // deterministic ENV order -> stable layer hash, matches imageTag
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
