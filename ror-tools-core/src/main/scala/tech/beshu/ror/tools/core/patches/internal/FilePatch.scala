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
package tech.beshu.ror.tools.core.patches.internal

import better.files.File
import os.Path
import tech.beshu.ror.tools.core.patches.internal.FileModifiersBasedPatch.addPatchedByRorVersionPropertyToJarManifest
import tech.beshu.ror.tools.core.patches.internal.FilePatch.FilePatchMetadata
import tech.beshu.ror.tools.core.patches.internal.filePatchers.JarManifestModifier
import tech.beshu.ror.tools.core.patches.internal.modifiers.FileModifier
import tech.beshu.ror.tools.core.utils.FileUtils

import scala.language.postfixOps

private[patches] abstract class FilePatch {
  def backup(): Unit
  def patch(): List[FilePatchMetadata]
  def restore(): Unit
}

object FilePatch {
  final case class FilePatchMetadata(path: Path, hash: String)

  object FilePatchMetadata {
    def forPath(path: Path): FilePatchMetadata =
      FilePatchMetadata(path, FileUtils.calculateFileHash(path.wrapped))
  }
}

private[patches] abstract class FileModifiersBasedPatch(val rorPluginDirectory: RorPluginDirectory,
                                                        val fileToPatchPath: Path,
                                                        patchingSteps: Iterable[FileModifier])
  extends FilePatch {

  override def backup(): Unit = {
    rorPluginDirectory.backup(fileToPatchPath)
  }

  override def patch(): List[FilePatchMetadata] = {
    patchingSteps.foreach { step =>
      step(fileToPatchPath toIO)
    }
    addPatchedByRorVersionPropertyToJarManifest(rorPluginDirectory, fileToPatchPath)
    List(FilePatchMetadata.forPath(fileToPatchPath))
  }

  override def restore(): Unit = {
    rorPluginDirectory.restore(fileToPatchPath)
  }
}

object FileModifiersBasedPatch {
  def addPatchedByRorVersionPropertyToJarManifest(rorPluginDirectory: RorPluginDirectory,
                                                  fileToPatchPath: Path): Unit = {
    if (fileToPatchPath.toString.endsWith(".jar")) {
      JarManifestModifier.addPatchedByRorVersionProperty(
        File(fileToPatchPath.wrapped),
        rorPluginDirectory.readCurrentRorVersion()
      )
    }
  }
}

private[patches] class MultiFilePatch(filePatches: FilePatch*) {

  def backup(): Unit = {
    filePatches.foreach(_.backup())
  }

  def patch(): List[FilePatchMetadata] = {
    filePatches.flatMap(_.patch()).toList
  }

  def restore(): Unit = {
    filePatches.foreach(_.restore())
  }

}

private [patches] class OptionalFilePatchDecorator[FP <: FilePatch](underlying: FP, fileToPatchPath: Path)
  extends FilePatch {

  private val chosenFilePatch = {
    if(os.exists(fileToPatchPath)) underlying
    else NoOpFilePatch
  }

  override def backup(): Unit = chosenFilePatch.backup()
  override def patch(): List[FilePatchMetadata] = chosenFilePatch.patch()
  override def restore(): Unit = chosenFilePatch.restore()
}

private [patches] object NoOpFilePatch extends FilePatch{
  override def backup(): Unit = ()
  override def patch(): List[FilePatchMetadata] = List.empty
  override def restore(): Unit = ()
}