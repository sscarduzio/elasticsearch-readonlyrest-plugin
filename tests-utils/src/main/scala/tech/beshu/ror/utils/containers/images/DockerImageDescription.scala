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
import os.Path
import tech.beshu.ror.utils.containers.images.DockerImageDescription.{Command, CopyFile, Env}

final case class DockerImageDescription(baseImage: String,
                                        runCommands: Seq[Command],
                                        copyFiles: Set[CopyFile],
                                        envs: Set[Env],
                                        entrypoint: Option[Path]) {

  def run(command: String): DockerImageDescription = {
    this.copy(runCommands = this.runCommands :+ Command.Run(command))
  }

  def runWhen(condition: Boolean, command: => String): DockerImageDescription = {
    if (condition) run(command)
    else this
  }

  def runWhen(condition: Boolean, command: => String, orElseCommand: => String): DockerImageDescription = {
    if (condition) run(command)
    else run(orElseCommand)
  }

  def user(name: String): DockerImageDescription = {
    this.copy(runCommands = this.runCommands :+ Command.ChangeUser(name))
  }

  def copyFile(destination: Path, file: File): DockerImageDescription = {
    this.copy(copyFiles = copyFiles + CopyFile(destination, file))
  }

  def addEnv(name: String, value: String): DockerImageDescription = {
    this.copy(envs = this.envs + Env(name, value))
  }

  def addEnvs(envs: Map[String, String]): DockerImageDescription = {
    this.copy(envs = this.envs ++ envs.map { case (k, v) => Env(k, v) })
  }

  def setEntrypoint(entrypoint: Path): DockerImageDescription = {
    this.copy(entrypoint = Some(entrypoint))
  }
}

object DockerImageDescription {
  sealed trait Command
  object Command {
    final case class Run(command: String) extends Command
    final case class ChangeUser(user: String) extends Command
  }
  final case class CopyFile(destination: Path, file: File)
  final case class Env(name: String, value: String)

  def create(image: String): DockerImageDescription = DockerImageDescription(
    baseImage = image,
    runCommands = Seq.empty,
    copyFiles = Set.empty,
    envs = Set.empty,
    entrypoint = None
  )
}