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
import tech.beshu.ror.utils.containers.images.DockerImageDescription.{Env, Instruction}
import tech.beshu.ror.utils.misc.ScalaUtils

import java.security.MessageDigest

// `steps` is ORDERED (COPY/RUN/USER in declaration order) for Docker layer caching: per-config files
// COPY *after* the heavy config-independent RUNs so those ~140MB layers stay byte-identical and shared.
final case class DockerImageDescription(
    baseImage: String,
    steps: Seq[Instruction],
    envs: Set[Env],
    entrypoint: Option[Path],
    command: Option[String]
) {

  // Copies in declaration order — used by the image-context streamer and the content-hash tag.
  def copyFiles: Seq[Instruction.Copy] = steps.collect { case c: Instruction.Copy => c }

  def run(command: String): DockerImageDescription = {
    this.copy(steps = this.steps :+ Instruction.Run(command))
  }

  def run(command: String, otherCommands: String*): DockerImageDescription = {
    this.copy(steps = (this.steps :+ Instruction.Run(command)) ++ otherCommands.map(Instruction.Run.apply))
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
    this.copy(steps = this.steps :+ Instruction.ChangeUser(name))
  }

  def copyFile(destination: Path, file: File): DockerImageDescription = {
    // Dedup by destination, last-wins. NOT a mistake to guard against: the combined
    // ReadonlyRest+XpackSecurity plugin legitimately copies the same cert files (elastic-certificates*)
    // to the same config dir from both plugins, so a fail-loud `require` here breaks every xpack-security
    // suite. Last-wins collapses the duplicates harmlessly (same content anyway).
    val deduped = steps.filterNot { case Instruction.Copy(d, _) => d == destination; case _ => false }
    this.copy(steps = deduped :+ Instruction.Copy(destination, file))
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

  // Short hex digest over everything that defines the image (base, instructions, envs, copied-file
  // CONTENT) so a changed plugin zip / cert / settings yields a new tag. Stable across parallel
  // workers: identical inputs → identical tag → cache hit (no redundant concurrent rebuilds).
  def imageTag: String = {
    val md = MessageDigest.getInstance("SHA-1")
    md.update(baseImage.getBytes("UTF-8"))
    // Hash each instruction's defining FIELDS directly (not toString — an impl-detail format; not
    // hashCode — only 32 bits, risks a false cache hit). A `RUN`/`USER` prefix keeps the two kinds
    // from colliding on equal payloads.
    steps.foreach {
      case Instruction.Run(command)            => md.update(s"RUN:$command".getBytes("UTF-8"))
      case Instruction.ChangeUser(user)        => md.update(s"USER:$user".getBytes("UTF-8"))
      case Instruction.Copy(destination, file) =>
        // destination + file CONTENT (so a changed source yields a new tag)
        md.update(destination.toString.getBytes("UTF-8"))
        if (file.exists) md.update(ScalaUtils.sha256(file).getBytes("UTF-8"))
    }
    // envs is a Set — sort so iteration order can't vary the digest (order-dependent hash → spurious
    // cache misses → redundant concurrent rebuilds).
    envs.toList.sortBy(e => (e.name, e.value)).foreach(e => md.update(s"${e.name}=${e.value}".getBytes("UTF-8")))
    // entrypoint/command also define the image — fold them in so images differing only there can't
    // collide to the same tag (false cache hit running the wrong launcher).
    // Note: entrypoint is an in-container os.Path (e.g. /usr/share/elasticsearch/bin/es-readonlyrest),
    // not a host File — its content is pinned by the baseImage hash above, not copied from the host.
    entrypoint.foreach(p => md.update(p.toString.getBytes("UTF-8")))
    command.foreach(c => md.update(c.getBytes("UTF-8")))
    md.digest().take(10).map("%02x".format(_)).mkString
  }

}

object DockerImageDescription {
  sealed trait Instruction

  object Instruction {
    final case class Run(command: String) extends Instruction
    final case class ChangeUser(user: String) extends Instruction
    final case class Copy(destination: Path, file: File) extends Instruction
  }

  final case class Env(name: String, value: String)

  def create(image: String, customEntrypoint: Option[Path] = None): DockerImageDescription = DockerImageDescription(
    baseImage = image,
    steps = Seq.empty,
    envs = Set.empty,
    entrypoint = customEntrypoint,
    command = None,
  )

}
