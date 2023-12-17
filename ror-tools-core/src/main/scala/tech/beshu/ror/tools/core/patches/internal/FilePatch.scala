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

private [patches] abstract class FilePatch(rorPluginDirectory: RorPluginDirectory,
                                           fileToPatchPath: Path,
                                           patchingSteps: Iterable[FileModifier]) {

  def backup(): Unit = {
    rorPluginDirectory.backup(fileToPatchPath)
  }

  def patch(): Unit = {
    patchingSteps.foreach { step =>
      step(fileToPatchPath toIO)
    }
  }

  def restore(): Unit = {
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