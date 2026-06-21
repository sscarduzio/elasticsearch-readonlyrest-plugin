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
    // of redundantly (and concurrently) rebuilding it — which is what fails at shardCount>=3.
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
          .applyStepsFrom(imageDescription) // COPY/RUN/USER in DECLARATION order (config files last)
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
    d.steps.foreach { case c: Command.Run => md.update(c.toString.getBytes("UTF-8"))
                      case u: Command.ChangeUser => md.update(u.toString.getBytes("UTF-8"))
                      case _: Command.Copy => () } // copy CONTENT hashed below (order-independent, sorted)
    // envs is a Set — sort so iteration order can't vary the digest. The whole stable-tag scheme
    // depends on identical inputs -> identical tag -> cache hit across parallel workers; an
    // order-dependent hash would cause spurious cache misses (redundant concurrent rebuilds).
    d.envs.toList.sortBy(e => (e.name, e.value)).foreach(e => md.update(s"${e.name}=${e.value}".getBytes("UTF-8")))
    d.copyFiles.toList.sortBy(_.destination.toString).foreach { cf =>
      md.update(cf.destination.toString.getBytes("UTF-8"))
      val f = File(cf.file.pathAsString)
      if (f.exists) md.update(f.sha256.getBytes("UTF-8"))
    }
    // entrypoint/command also define the image — fold them in so two images differing only there
    // can't collide to the same tag (a false cache hit that would run the wrong launcher).
    d.entrypoint.foreach(p => md.update(p.toString.getBytes("UTF-8")))
    d.command.foreach(c => md.update(c.getBytes("UTF-8")))
    md.digest().take(10).map("%02x".format(_)).mkString
  }

  private def copyFilesFrom(imageDescription: DockerImageDescription, to: ImageFromDockerfile) = {
    // A FRESH staging dir PER BUILD (UUID). We hand testcontainers a private COPY of every source file
    // rather than the shared on-disk original — when builds run concurrently they otherwise point at
    // the SAME files (e.g. the ROR plugin zip) and concurrent tar-streaming corrupts the build context.
    //
    // MUST be per-build, not a reused per-JVM dir: cluster nodes build their images IN PARALLEL
    // (EsClusterContainer.startContainersAsynchronously → parSequenceUnordered), so a shared dir that's
    // wiped at the start of each build let one node's build delete a sibling node's staged files
    // mid-build → the victim booted with default config (cluster.name=elasticsearch) and crashed →
    // multi-node clusters never formed (peer `failed to resolve host`). Per-build isolation fixes that.
    //
    // Disk: staging is small per build (~plugin zip + a few certs); the dominant disk consumer was the
    // built IMAGES, now handled by deleteOnExit=true in create(). Staging keeps deleteOnExit as a net.
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
    // Best-effort reclaim once the build context has been consumed; deleteOnExit is the safety net in
    // case the JVM dies before this runs.
    stagingDir.toJava.deleteOnExit()
    dockerfile
  }

  private implicit class BuilderExt(val builder: DockerfileBuilder) extends AnyVal {

    // Emit COPY / RUN / USER in DECLARATION order so the Dockerfile (and thus the layer chain) matches
    // the order steps were added — per-config config files end up AFTER the heavy config-independent
    // RUNs, letting those layers be shared across configs. Adjacent Runs are still merged into one `&&`
    // RUN (the original optimisation); a COPY or USER between two Runs breaks the merge (correct — a
    // COPY is its own layer boundary anyway).
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
