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
import tech.beshu.ror.utils.containers.images.DockerImageDescription.Instruction
import tech.beshu.ror.utils.containers.images.DockerImageDescription.Instruction.{ChangeUser, Copy, Run}

import java.util.UUID

object DockerImageCreator extends StrictLogging {

  def create(elasticsearch: Elasticsearch): ImageFromDockerfile = {
    val imageDescription = elasticsearch.toDockerImageDescription
    // STABLE content-derived tag (DockerImageDescription.imageTag): identical images share one tag →
    // cache hit across parallel workers (no concurrent rebuilds, fails at shardCount>=3). deleteOnExit
    // reclaims per-suite images post-use.
    val stableTag = s"ror-it-es:${imageDescription.imageTag}"
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

  private def copyFilesFrom(imageDescription: DockerImageDescription, to: ImageFromDockerfile) = {
    // FRESH staging dir PER BUILD (UUID): hand testcontainers a private copy of each source so parallel
    // cluster-node builds (parSequenceUnordered) can't clobber a shared dir mid-build and corrupt context.
    val stagingDir = File.newTemporaryDirectory(prefix = s"es-img-${UUID.randomUUID()}-")
    val dockerfile = imageDescription.copyFiles.zipWithIndex
      .foldLeft(to) { case (dockerfile, (copy, idx)) =>
        val source = File(copy.file.pathAsString)
        // Fail loud on a missing/empty source: a silently-staged empty file would build an image with
        // wrong content while imageTag (hashed from the ORIGINAL file) still claims a valid cache hit.
        require(source.exists && source.size > 0, s"Docker COPY source is missing or empty: $source")
        // index-prefix the staged name so two sources sharing a filename can't clobber each other
        val privateCopy = source.copyTo(stagingDir / s"$idx-${source.name}", overwrite = true)
        dockerfile.withFileFromFile(copy.destination.toIO.getAbsolutePath, privateCopy.toJava)
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
        .foldLeft(List.empty[Instruction]) {
          case (acc, r @ Run(command)) =>
            acc.reverse match {
              case Run(lastCommand) :: tail => (Run(s"$lastCommand && $command") :: tail).reverse
              case _                        => acc :+ r
            }
          case (acc, other) => acc :+ other // ChangeUser / Copy are layer boundaries; never merged
        }
        .foldLeft(builder) {
          case (b, ChangeUser(user)) => b.user(user)
          case (b, Run(command))     => b.run(command)
          case (b, Copy(dest, _))    => b.copy(dest.toString(), dest.toString())
        }
    }

    def addEnvsFrom(imageDescription: DockerImageDescription): DockerfileBuilder = {
      imageDescription.envs.toList
        .sortBy(_.name) // deterministic ENV order -> stable layer hash, matches imageTag
        .foldLeft(builder) { case (b, env) => b.env(env.name, env.value) }
    }

    def setEntrypointFrom(imageDescription: DockerImageDescription): DockerfileBuilder = {
      imageDescription.entrypoint
        .foldLeft(builder) { case (b, entrypoint) => b.entryPoint(entrypoint.toIO.getAbsolutePath) }
    }

    def setCommandFrom(imageDescription: DockerImageDescription): DockerfileBuilder = {
      imageDescription.command
        .foldLeft(builder) { case (b, command) => b.cmd(command) }
    }

  }

}
