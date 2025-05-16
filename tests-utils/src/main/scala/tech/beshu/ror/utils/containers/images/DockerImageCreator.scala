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

import com.typesafe.scalalogging.StrictLogging
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.images.builder.dockerfile.DockerfileBuilder
import tech.beshu.ror.utils.containers.images.DockerImageDescription.Command
import tech.beshu.ror.utils.containers.images.DockerImageDescription.Command.{ChangeUser, Run}
import tech.beshu.ror.utils.containers.images.PathUtils.linuxPath

object DockerImageCreator extends StrictLogging {

  def create(imageDescription: DockerImageDescription): ImageFromDockerfile = {
    copyFilesFrom(imageDescription, to = new ImageFromDockerfile())
      .withDockerfileFromBuilder((builder: DockerfileBuilder) => {
        val dockerfile = builder
          .from(imageDescription.baseImage)
          .copyFilesFrom(imageDescription)
          .runCommandsFrom(imageDescription)
          .addEnvsFrom(imageDescription)
          .setEntrypointFrom(imageDescription)
          .build()
        logger.info("Dockerfile\n" + dockerfile)
      })
  }

  private def copyFilesFrom(imageDescription: DockerImageDescription, to: ImageFromDockerfile) = {
    imageDescription
      .copyFiles
      .foldLeft(to) {
        case (dockerfile, copyFile) =>
          dockerfile.withFileFromFile(linuxPath(copyFile.destination.toIO.getAbsolutePath), copyFile.file.toJava)
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
          b.copy(linuxPath(file.destination.toString()), linuxPath(file.destination.toString()))
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
        .foldLeft(builder) { case (b, entrypoint) => b.entryPoint(entrypoint) }
    }

  }
}
