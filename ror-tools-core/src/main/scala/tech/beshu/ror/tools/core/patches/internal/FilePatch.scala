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

import os.Path
import tech.beshu.ror.tools.core.patches.internal.modifiers.FileModifier

import scala.language.postfixOps

private [patches] abstract class FilePatch(val fileToPatchPath: Path) {
  def backup(): Unit
  def patch(): Unit
  def restore(): Unit
}

private [patches] abstract class FileModifiersBasedPatch(val rorPluginDirectory: RorPluginDirectory,
                                                         override val fileToPatchPath: Path,
                                                         patchingSteps: Iterable[FileModifier])
  extends FilePatch(fileToPatchPath) {

  override def backup(): Unit = {
    rorPluginDirectory.backup(fileToPatchPath)
  }

  override def patch(): Unit = {
    patchingSteps.foreach { step =>
      step(fileToPatchPath toIO)
    }
  }

  override def restore(): Unit = {
    rorPluginDirectory.restore(fileToPatchPath)
  }
}

private [patches] class MultiFilePatch(filePatches: FilePatch*) {

  def backup(): Unit = {
    filePatches.foreach(_.backup())
  }

  def patch(): Unit = {
    filePatches.foreach(_.patch())
  }

  def restore(): Unit = {
    filePatches.foreach(_.restore())
  }
}

private [patches] class OptionalFilePatchDecorator[FP <: FilePatch](underlying: FP)
  extends FilePatch(underlying.fileToPatchPath) {

  private val chosenFilePatch = {
    if(os.exists(fileToPatchPath)) underlying
    else NoOpFilePatch
  }

  override def backup(): Unit = chosenFilePatch.backup()
  override def patch(): Unit = chosenFilePatch.patch()
  override def restore(): Unit = chosenFilePatch.restore()
}

private [patches] object NoOpFilePatch extends FilePatch(os.root){
  override def backup(): Unit = ()
  override def patch(): Unit = ()
  override def restore(): Unit = ()
}