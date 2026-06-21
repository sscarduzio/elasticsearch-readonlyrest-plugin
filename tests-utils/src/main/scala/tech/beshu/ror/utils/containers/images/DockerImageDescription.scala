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

// `steps` is an ORDERED sequence of COPY/RUN/USER in DECLARATION order. This matters for Docker layer
// caching: per-config files (readonlyrest.yml, elasticsearch.yml) must be COPIED *after* the heavy,
// config-independent RUNs (plugin install, ES patch) so those ~140MB layers are byte-identical across
// configs and SHARED in the image store. The previous design kept copies in a Set emitted before all
// runs, which (a) lost order and (b) put per-config copies at the top → cache-miss → ~140MB rebuilt
// per config → CI disk exhaustion. Keeping copies and runs in ONE ordered Seq fixes both.
final case class DockerImageDescription(baseImage: String,
                                        steps: Seq[Command],
                                        envs: Set[Env],
                                        entrypoint: Option[Path],
                                        command: Option[String]) {

  // Copies in declaration order — used by the image-context streamer and the content-hash tag.
  def copyFiles: Seq[CopyFile] = steps.collect { case Command.Copy(c) => c }

  def run(command: String): DockerImageDescription = {
    this.copy(steps = this.steps :+ Command.Run(command))
  }

  def run(command: String, otherCommands: String*): DockerImageDescription = {
    this.copy(steps = (this.steps :+ Command.Run(command)) ++ otherCommands.map(Command.Run.apply))
  }

  def runWhen(condition: Boolean, command: => String): DockerImageDescription = {
    if (condition) run(command)
    else this
  }

  def runWhen(condition: Boolean, command: => String, orElseCommand: => String): DockerImageDescription = {
    if (condition) run(command)
    else run(orElseCommand)
  }

  def when(condition: Boolean, f: DockerImageDescription => DockerImageDescription): DockerImageDescription = {
    if (condition) f(this)
    else this
  }

  def user(name: String): DockerImageDescription = {
    this.copy(steps = this.steps :+ Command.ChangeUser(name))
  }

  def copyFile(destination: Path, file: File): DockerImageDescription = {
    // dedup identical destinations (the old Set semantics): a later copy to the same path wins.
    val deduped = steps.filterNot { case Command.Copy(c) => c.destination == destination; case _ => false }
    this.copy(steps = deduped :+ Command.Copy(CopyFile(destination, file)))
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

  def setCommand(cmd: String): DockerImageDescription = {
    this.copy(command = Some(cmd))
  }
}

object DockerImageDescription {
  sealed trait Command
  object Command {
    final case class Run(command: String) extends Command
    final case class ChangeUser(user: String) extends Command
    final case class Copy(file: CopyFile) extends Command
  }
  final case class CopyFile(destination: Path, file: File)
  final case class Env(name: String, value: String)

  def create(image: String, customEntrypoint: Option[Path] = None): DockerImageDescription = DockerImageDescription(
    baseImage = image,
    steps = Seq.empty,
    envs = Set.empty,
    entrypoint = customEntrypoint,
    command = None,
  )
}